package com.aura.tvprobe;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Instagram-style Impression TTL & Cooldown Tracker.
 * Tracks which titles were shown in recent recommendation epochs and recycles
 * unacted recommendations so every refresh surfaces a distinct slate.
 */
public final class ImpressionTracker {

    private static final long IMPRESSION_TTL_SEC = 72L * 3600L; // 72-hour (3-day) recycle TTL
    public static final int COOLDOWN_EPOCHS = 8; // Recycle every 8 rounds (5-10 round window), then delete

    public static final class ImpressionRecord {
        public final int lastEpoch;
        public final int shownCount;
        public final long lastShownAt;

        public ImpressionRecord(int lastEpoch, int shownCount, long lastShownAt) {
            this.lastEpoch = lastEpoch;
            this.shownCount = shownCount;
            this.lastShownAt = lastShownAt;
        }
    }

    private ImpressionTracker() {}

    public static void ensureTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reco_impressions (" +
            "title_key TEXT PRIMARY KEY, last_epoch INTEGER, " +
            "shown_count INTEGER DEFAULT 1, last_shown_at INTEGER)");
    }

    public static int advanceEpoch(AuraDb db, String profileId) {
        String key = "feed_epoch_" + profileId;
        int cur = parseIntSafe(db.getSetting(key, "0")) + 1;
        db.setSetting(key, String.valueOf(cur));
        pruneExpiredImpressions(db, cur);
        return cur;
    }

    public static int getCurrentEpoch(AuraDb db, String profileId) {
        return parseIntSafe(db.getSetting("feed_epoch_" + profileId, "1"));
    }

    private static int parseIntSafe(String val) {
        try {
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean shouldDeleteImpression(int lastEpoch, int currentEpoch) {
        return (currentEpoch - lastEpoch) >= COOLDOWN_EPOCHS;
    }

    public static void pruneExpiredImpressions(AuraDb auraDb) {
        pruneExpiredImpressions(auraDb, getCurrentEpoch(auraDb, auraDb.getValidActiveProfile()));
    }

    public static void pruneExpiredImpressions(AuraDb auraDb, int currentEpoch) {
        try {
            long cutoff = (System.currentTimeMillis() / 1000L) - IMPRESSION_TTL_SEC;
            int minEpochToKeep = currentEpoch - COOLDOWN_EPOCHS;
            SQLiteDatabase db = auraDb.getWritableDatabase();
            ensureTable(db);
            db.delete("reco_impressions", "last_shown_at < ? OR last_epoch <= ?",
                new String[]{String.valueOf(cutoff), String.valueOf(minEpochToKeep)});
        } catch (Exception ignored) {}
    }

    public static Map<String, ImpressionRecord> loadActiveImpressions(AuraDb auraDb) {
        Map<String, ImpressionRecord> map = new HashMap<>();
        try {
            SQLiteDatabase db = auraDb.getReadableDatabase();
            ensureTable(db);
            readImpressionsCursor(db, map);
        } catch (Exception ignored) {}
        return map;
    }

    private static void readImpressionsCursor(SQLiteDatabase db, Map<String, ImpressionRecord> map) {
        String sql = "SELECT title_key, last_epoch, shown_count, last_shown_at FROM reco_impressions";
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                map.put(c.getString(0), new ImpressionRecord(c.getInt(1), c.getInt(2), c.getLong(3)));
            }
        }
    }

    public static double computeNoveltyPenalty(String titleKey, int currentEpoch, Map<String, ImpressionRecord> history) {
        if (history == null || !history.containsKey(titleKey)) return 0.0;
        ImpressionRecord rec = history.get(titleKey);
        int epochDelta = currentEpoch - rec.lastEpoch;
        if (epochDelta < 0) return 0.0;
        if (epochDelta < COOLDOWN_EPOCHS) return -4.5 + (epochDelta * 0.45);
        return -Math.min(2.0, rec.shownCount * 0.45);
    }

    public static boolean isRecentlyShown(String titleKey, int currentEpoch, Map<String, ImpressionRecord> history) {
        if (history == null || !history.containsKey(titleKey)) return false;
        ImpressionRecord rec = history.get(titleKey);
        int delta = currentEpoch - rec.lastEpoch;
        return delta >= 0 && delta < COOLDOWN_EPOCHS;
    }

    public static List<String> getRecentCooldownKeys(AuraDb auraDb, int currentEpoch) {
        Map<String, ImpressionRecord> active = loadActiveImpressions(auraDb);
        List<String> keys = new ArrayList<>();
        for (String k : active.keySet()) {
            if (isRecentlyShown(k, currentEpoch, active) && keys.size() < 60) keys.add(k);
        }
        return keys;
    }

    public static JSONArray filterOutRecentImpressions(
            JSONArray items, int currentEpoch, Map<String, ImpressionRecord> history, int minKeep) {
        if (items == null || items.length() == 0) return new JSONArray();
        JSONArray fresh = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            String key = item != null ? AuraDb.normalizeTitleKey(item.optString("title", "")) : "";
            if (item != null && !isRecentlyShown(key, currentEpoch, history)) fresh.put(item);
        }
        return fresh.length() >= minKeep ? fresh : items;
    }

    public static void recordJsonArrayImpressions(AuraDb auraDb, int epoch, JSONArray arr) {
        if (arr == null || arr.length() == 0) return;
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < arr.length() && i < 16; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) list.add(o);
        }
        recordSlateImpressions(auraDb, epoch, list);
    }

    public static void recordSlateImpressions(AuraDb auraDb, int epoch, List<JSONObject> slate) {
        if (slate == null || slate.isEmpty()) return;
        try {
            Map<String, ImpressionRecord> existing = loadActiveImpressions(auraDb);
            SQLiteDatabase db = auraDb.getWritableDatabase();
            ensureTable(db);
            commitSlateImpressions(db, epoch, slate, existing);
        } catch (Exception ignored) {}
    }

    private static void commitSlateImpressions(SQLiteDatabase db, int epoch, List<JSONObject> slate, Map<String, ImpressionRecord> existing) {
        long nowSec = System.currentTimeMillis() / 1000L;
        db.beginTransaction();
        try {
            for (JSONObject item : slate) upsertSingleImpression(db, item, epoch, nowSec, existing);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void upsertSingleImpression(SQLiteDatabase db, JSONObject item, int epoch, long nowSec, Map<String, ImpressionRecord> existing) {
        String key = AuraDb.normalizeTitleKey(item.optString("title", ""));
        if (key.isEmpty()) return;
        ImpressionRecord prev = existing.get(key);
        ContentValues cv = buildImpressionValues(key, epoch, (prev != null ? prev.shownCount : 0) + 1, nowSec);
        db.insertWithOnConflict("reco_impressions", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static ContentValues buildImpressionValues(String key, int epoch, int count, long nowSec) {
        ContentValues cv = new ContentValues();
        cv.put("title_key", key);
        cv.put("last_epoch", epoch);
        cv.put("shown_count", count);
        cv.put("last_shown_at", nowSec);
        return cv;
    }
}
