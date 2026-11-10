package com.aura.tvprobe;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hard-capped SQLite storage janitor for Aura TV.
 * Enforces strict row and byte-size caps across all tables (including subtitle_cache
 * and episode_checkpoints) on a daily schedule and whenever size thresholds are reached.
 */
public final class DatabaseJanitor {

    public static final int MAX_SUBTITLE_EPISODES = 24;
    public static final long MAX_SUBTITLE_BYTES   = 6L * 1024L * 1024L; // 6.0 MB hard cap
    public static final int MAX_CHECKPOINT_ROWS   = 400;
    public static final int MAX_GTI_CACHE_ROWS    = 350;
    public static final long MAX_GTI_CACHE_BYTES  = 3584L * 1024L;      // 3.5 MB hard cap
    public static final int MAX_IMPRESSION_ROWS   = 250;
    public static final int MAX_RAM_HISTORY_ROWS  = 100;
    private static final long DAILY_INTERVAL_SEC  = 24L * 3600L;
    private static final long SUBTITLE_TTL_SEC    = 14L * 24L * 3600L;
    private static final long GTI_TTL_SEC         = 7L * 24L * 3600L;

    private DatabaseJanitor() {}

    public static void ensureCompanionTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS subtitle_cache (" +
            "cache_key TEXT PRIMARY KEY, series_key TEXT, season INTEGER, episode INTEGER, " +
            "payload_json TEXT, byte_size INTEGER, last_accessed_at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS episode_checkpoints (" +
            "checkpoint_key TEXT PRIMARY KEY, series_key TEXT, season INTEGER, episode INTEGER, " +
            "position_sec INTEGER, duration_sec INTEGER, updated_at INTEGER)");
        ensureRamHistoryTable(db);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sub_cache_lru ON subtitle_cache(last_accessed_at ASC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ep_ckpt_series ON episode_checkpoints(series_key, season, episode)");
    }

    private static void ensureRamHistoryTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ram_cleanup_history (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp_sec INTEGER, trigger_type TEXT, " +
            "active_context TEXT, killed_apps TEXT, avail_before_mb INTEGER, " +
            "avail_after_mb INTEGER, freed_mb INTEGER, cpu_pct INTEGER)");
    }

    public static void maybeRunDailyJanitor(AuraDb auraDb) {
        if (auraDb == null) return;
        long nowSec = System.currentTimeMillis() / 1000L;
        long lastRun = parseLongSafe(auraDb.getSetting("last_db_janitor_sec", "0"));
        if ((nowSec - lastRun) < DAILY_INTERVAL_SEC) return;
        auraDb.setSetting("last_db_janitor_sec", String.valueOf(nowSec));
        runFullCleanupNow(auraDb);
    }

    public static void runFullCleanupNow(AuraDb auraDb) {
        if (auraDb == null) return;
        try {
            SQLiteDatabase db = auraDb.getWritableDatabase();
            ensureCompanionTables(db);
            enforceAllTableCaps(auraDb, db);
            checkpointAndOptimize(db);
        } catch (Exception ignored) {}
    }

    private static void enforceAllTableCaps(AuraDb auraDb, SQLiteDatabase db) {
        long nowSec = System.currentTimeMillis() / 1000L;
        enforceSubtitleCacheCaps(db, nowSec);
        enforceGtiCacheCaps(db, nowSec);
        enforceCheckpointCaps(db);
        enforceImpressionCaps(auraDb, db);
        auraDb.enforceStorageCap();
        enforceWatchedItemsCap(db);
        trimTableByRowCount(db, "ram_cleanup_history", "id", "timestamp_sec", MAX_RAM_HISTORY_ROWS);
    }

    public static void enforceSubtitleCacheCaps(SQLiteDatabase db, long nowSec) {
        ensureCompanionTables(db);
        db.delete("subtitle_cache", "last_accessed_at < ?", new String[]{String.valueOf(nowSec - SUBTITLE_TTL_SEC)});
        trimTableByRowCount(db, "subtitle_cache", "cache_key", "last_accessed_at", MAX_SUBTITLE_EPISODES);
        while (queryTotalBytes(db, "subtitle_cache", "byte_size") > MAX_SUBTITLE_BYTES) {
            deleteOldestRows(db, "subtitle_cache", "cache_key", "last_accessed_at", 3);
        }
    }

    public static void enforceGtiCacheCaps(SQLiteDatabase db, long nowSec) {
        db.delete("gti_cache", "updated_at < ?", new String[]{String.valueOf(nowSec - GTI_TTL_SEC)});
        trimTableByRowCount(db, "gti_cache", "gti", "updated_at", MAX_GTI_CACHE_ROWS);
        while (queryTotalPayloadLen(db, "gti_cache", "payload_json") > MAX_GTI_CACHE_BYTES) {
            deleteOldestRows(db, "gti_cache", "gti", "updated_at", 15);
        }
    }

    public static void enforceCheckpointCaps(SQLiteDatabase db) {
        ensureCompanionTables(db);
        trimTableByRowCount(db, "episode_checkpoints", "checkpoint_key", "updated_at", MAX_CHECKPOINT_ROWS);
    }

    private static void enforceImpressionCaps(AuraDb auraDb, SQLiteDatabase db) {
        ImpressionTracker.pruneExpiredImpressions(auraDb);
        trimTableByRowCount(db, "reco_impressions", "title_key", "last_shown_at", MAX_IMPRESSION_ROWS);
    }

    private static void enforceWatchedItemsCap(SQLiteDatabase db) {
        trimTableByRowCount(db, "watched_items", "title_key", "updated_at", 500);
    }

    private static void trimTableByRowCount(SQLiteDatabase db, String table, String pk, String tsCol, int maxRows) {
        String sql = "DELETE FROM " + table + " WHERE " + pk + " NOT IN (" +
            "SELECT " + pk + " FROM " + table + " ORDER BY " + tsCol + " DESC LIMIT " + maxRows + ")";
        try { db.execSQL(sql); } catch (Exception ignored) {}
    }

    private static void deleteOldestRows(SQLiteDatabase db, String table, String pk, String tsCol, int batchSize) {
        String sql = "DELETE FROM " + table + " WHERE " + pk + " IN (" +
            "SELECT " + pk + " FROM " + table + " ORDER BY " + tsCol + " ASC LIMIT " + batchSize + ")";
        try { db.execSQL(sql); } catch (Exception ignored) {}
    }

    public static long queryTotalBytes(SQLiteDatabase db, String table, String byteCol) {
        try (Cursor c = db.rawQuery("SELECT COALESCE(SUM(" + byteCol + "), 0) FROM " + table, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } catch (Exception e) { return 0L; }
    }

    private static long queryTotalPayloadLen(SQLiteDatabase db, String table, String textCol) {
        try (Cursor c = db.rawQuery("SELECT COALESCE(SUM(LENGTH(" + textCol + ")), 0) FROM " + table, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } catch (Exception e) { return 0L; }
    }

    private static void checkpointAndOptimize(SQLiteDatabase db) {
        try { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).close(); } catch (Exception ignored) {}
        try { db.execSQL("PRAGMA optimize"); } catch (Exception ignored) {}
    }

    public static void saveSubtitleCacheRow(AuraDb auraDb, String cacheKey, String seriesKey, int season, int episode, String json) {
        if (auraDb == null || json == null || json.isEmpty()) return;
        try {
            SQLiteDatabase db = auraDb.getWritableDatabase();
            ensureCompanionTables(db);
            long now = System.currentTimeMillis() / 1000L;
            insertSubtitleRow(db, cacheKey, seriesKey, season, episode, json, now);
            enforceSubtitleCacheCaps(db, now);
        } catch (Exception ignored) {}
    }

    private static void insertSubtitleRow(
            SQLiteDatabase db, String cacheKey, String seriesKey, int season, int episode, String json, long now) {
        ContentValues cv = new ContentValues();
        cv.put("cache_key", cacheKey);
        cv.put("series_key", seriesKey);
        cv.put("season", season);
        cv.put("episode", episode);
        cv.put("payload_json", json);
        cv.put("byte_size", json.length());
        cv.put("last_accessed_at", now);
        db.insertWithOnConflict("subtitle_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public static String getSubtitleCacheRow(AuraDb auraDb, String cacheKey) {
        if (auraDb == null || cacheKey == null) return null;
        try {
            SQLiteDatabase db = auraDb.getWritableDatabase();
            ensureCompanionTables(db);
            return readAndTouchSubtitleRow(db, cacheKey);
        } catch (Exception e) { return null; }
    }

    private static String readAndTouchSubtitleRow(SQLiteDatabase db, String cacheKey) {
        try (Cursor c = db.rawQuery("SELECT payload_json FROM subtitle_cache WHERE cache_key = ?", new String[]{cacheKey})) {
            if (!c.moveToFirst()) return null;
            String payload = c.getString(0);
            touchSubtitleRowTimestamp(db, cacheKey);
            return payload;
        }
    }

    private static void touchSubtitleRowTimestamp(SQLiteDatabase db, String cacheKey) {
        ContentValues cv = new ContentValues();
        cv.put("last_accessed_at", System.currentTimeMillis() / 1000L);
        db.update("subtitle_cache", cv, "cache_key = ?", new String[]{cacheKey});
    }

    public static void recordEpisodeCheckpoint(
            AuraDb auraDb, String rawTitle, int season, int episode, int positionSec, int durationSec) {
        String seriesKey = AuraDb.normalizeTitleKey(rawTitle);
        if (auraDb == null || seriesKey.isEmpty() || positionSec < 30) return;
        try {
            SQLiteDatabase db = auraDb.getWritableDatabase();
            ensureCompanionTables(db);
            upsertCheckpointRow(db, seriesKey, Math.max(1, season), Math.max(1, episode), positionSec, durationSec);
            enforceCheckpointCaps(db);
        } catch (Exception ignored) {}
    }

    private static void upsertCheckpointRow(
            SQLiteDatabase db, String seriesKey, int season, int episode, int posSec, int durSec) {
        String ckptKey = seriesKey + ":s" + season + "e" + episode;
        int prevMax = queryPreviousCheckpointPos(db, ckptKey);
        ContentValues cv = new ContentValues();
        cv.put("checkpoint_key", ckptKey);
        cv.put("series_key", seriesKey);
        cv.put("season", season);
        cv.put("episode", episode);
        cv.put("position_sec", Math.max(posSec, prevMax));
        cv.put("duration_sec", Math.max(durSec, 0));
        cv.put("updated_at", System.currentTimeMillis() / 1000L);
        db.insertWithOnConflict("episode_checkpoints", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static int queryPreviousCheckpointPos(SQLiteDatabase db, String ckptKey) {
        try (Cursor c = db.rawQuery("SELECT position_sec FROM episode_checkpoints WHERE checkpoint_key = ?", new String[]{ckptKey})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) { return 0; }
    }

    private static long parseLongSafe(String val) {
        try { return Long.parseLong(val.trim()); } catch (Exception e) { return 0L; }
    }

    public static void recordRamCleanupEvent(
            AuraDb auraDb, String triggerType, String activeContext, String killedApps,
            int availBeforeMb, int availAfterMb, int freedMb, int cpuPct) {
        if (auraDb == null || killedApps == null || killedApps.trim().isEmpty()) return;
        try {
            SQLiteDatabase db = auraDb.getWritableDatabase();
            ensureCompanionTables(db);
            insertRamCleanupRow(db, triggerType, activeContext, killedApps, availBeforeMb, availAfterMb, freedMb, cpuPct);
            trimTableByRowCount(db, "ram_cleanup_history", "id", "timestamp_sec", MAX_RAM_HISTORY_ROWS);
        } catch (Exception ignored) {}
    }

    private static void insertRamCleanupRow(
            SQLiteDatabase db, String triggerType, String activeContext, String killedApps,
            int beforeMb, int afterMb, int freedMb, int cpuPct) {
        ContentValues cv = new ContentValues();
        cv.put("timestamp_sec", System.currentTimeMillis() / 1000L);
        cv.put("trigger_type", triggerType);
        cv.put("active_context", activeContext);
        cv.put("killed_apps", killedApps);
        cv.put("avail_before_mb", beforeMb);
        cv.put("avail_after_mb", afterMb);
        cv.put("freed_mb", Math.max(1, freedMb));
        cv.put("cpu_pct", cpuPct);
        db.insert("ram_cleanup_history", null, cv);
    }

    public static JSONArray listRamCleanupHistory(AuraDb auraDb, int limit) {
        JSONArray out = new JSONArray();
        if (auraDb == null) return out;
        try {
            SQLiteDatabase db = auraDb.getWritableDatabase();
            ensureCompanionTables(db);
            queryRamHistoryCursor(db, Math.max(1, limit), out);
        } catch (Exception ignored) {}
        return out;
    }

    private static void queryRamHistoryCursor(SQLiteDatabase db, int limit, JSONArray out) throws Exception {
        String sql = "SELECT timestamp_sec, trigger_type, active_context, killed_apps, " +
            "avail_before_mb, avail_after_mb, freed_mb, cpu_pct FROM ram_cleanup_history " +
            "ORDER BY timestamp_sec DESC, id DESC LIMIT " + limit;
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) out.put(cursorToRamHistoryJson(c));
        }
    }

    private static JSONObject cursorToRamHistoryJson(Cursor c) throws Exception {
        return new JSONObject()
            .put("timestamp_sec", c.getLong(0))
            .put("trigger_type", c.getString(1))
            .put("active_context", c.getString(2))
            .put("killed_apps", c.getString(3))
            .put("avail_before_mb", c.getInt(4))
            .put("avail_after_mb", c.getInt(5))
            .put("freed_mb", c.getInt(6))
            .put("cpu_pct", c.getInt(7));
    }
}
