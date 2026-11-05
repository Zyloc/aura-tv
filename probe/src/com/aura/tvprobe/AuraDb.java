package com.aura.tvprobe;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AuraDb extends SQLiteOpenHelper {
    public static final String DEFAULT_PROFILE = "member1";
    private static final int MAX_TV_HISTORY = 500;
    private static final long RECO_TTL_SEC = 3600L; // 1 hour cache TTL
    private final Context appCtx;

    public AuraDb(Context ctx) {
        super(ctx, "aura_tv_local.db", null, 7);
        setWriteAheadLoggingEnabled(true);
        this.appCtx = ctx.getApplicationContext();
        applyBundledEnvIfPresent();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS watch_history (" +
            "media_key TEXT PRIMARY KEY, profile_id TEXT DEFAULT 'family', " +
            "app_pkg TEXT, app_name TEXT, title TEXT, series_title TEXT, " +
            "season INTEGER, episode INTEGER, synopsis TEXT, poster_url TEXT, " +
            "genres TEXT, runtime_min INTEGER DEFAULT 45, launch_uri TEXT, " +
            "position_sec INTEGER, duration_sec INTEGER, updated_at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS gti_cache (" +
            "gti TEXT PRIMARY KEY, payload_json TEXT, updated_at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS profile_recos (" +
            "profile_id TEXT PRIMARY KEY, payload_json TEXT, updated_at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS watched_items (" +
            "title_key TEXT PRIMARY KEY, payload_json TEXT, updated_at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS kv_settings (" +
            "k TEXT PRIMARY KEY, v TEXT)");
        seedDefaultSettings(db);
    }

    private void seedDefaultSettings(SQLiteDatabase db) {
        setKvInternal(db, "active_profile", DEFAULT_PROFILE);
        setKvInternal(db, "server_mode", "auto");
        setKvInternal(db, "mac_host", "");
        setKvInternal(db, "phone_host", "");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        try { db.execSQL("ALTER TABLE watch_history ADD COLUMN profile_id TEXT DEFAULT 'family'"); } catch (Exception ignored) {}
        try { db.execSQL("ALTER TABLE watch_history ADD COLUMN runtime_min INTEGER DEFAULT 45"); } catch (Exception ignored) {}
        onCreate(db);
    }

    private static boolean envLoadedThisProcess = false;

    private void applyBundledEnvIfPresent() {
        if (appCtx == null || envLoadedThisProcess) return;
        envLoadedThisProcess = true;
        try (InputStream is = appCtx.getAssets().open("aura.env");
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
                int eq = t.indexOf('=');
                String k = t.substring(0, eq).trim();
                String v = t.substring(eq + 1).trim();
                if (v.isEmpty()) continue;
                if ("GEMINI_API_KEY".equals(k)) setSetting("gemini_api_key", v);
                else if ("AURA_HOST_URL".equals(k)) setSetting("mac_host", v);
                else if ("AURA_PHONE_URL".equals(k)) setSetting("phone_host", v);
                else if ("AURA_MEMBER1_NAME".equals(k)) setSetting("profile_name_member1", v);
                else if ("AURA_PARTNER_NAME".equals(k)) setSetting("profile_name_partner", v);
                else if ("AURA_MEMBER3_NAME".equals(k)) setSetting("profile_name_member3", v);
                else if ("AURA_KIDS_NAME".equals(k)) setSetting("profile_name_kids", v);
                else if ("AURA_FAMILY_NAME".equals(k)) setSetting("profile_name_family", v);
            }
        } catch (Exception ignored) {}
    }

    public String getProfileLabel(String profileId) {
        String def = "Member 1";
        if ("partner".equals(profileId)) def = "Partner";
        else if ("member3".equals(profileId)) def = "Member 3";
        else if ("kids".equals(profileId)) def = "Kids";
        else if ("family".equals(profileId)) def = "Family";
        return getSetting("profile_name_" + profileId, def);
    }

    public JSONObject getProfileLabelsJson() {
        JSONObject o = new JSONObject();
        try {
            for (String p : new String[]{"member1", "partner", "member3", "kids", "family"}) {
                o.put(p, getProfileLabel(p));
            }
        } catch (Exception ignored) {}
        return o;
    }

    public static String normalizeTitleKey(String title) {
        if (title == null) return "";
        String base = title.split("·")[0].split("—")[0].trim();
        return base.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    public static String resolveAppPackage(String appName) {
        if (appName == null) return "";
        String a = appName.toLowerCase();
        if (a.contains("hotstar") || a.contains("jio") || a.contains("disney")) return "in.startv.hotstar";
        if (a.contains("netflix")) return "com.netflix.ninja";
        if (a.contains("prime") || a.contains("amazon")) return "com.amazon.amazonvideo.livingroom";
        if (a.contains("apple")) return "com.apple.atve.androidtv.appletv";
        if (a.contains("sony")) return "com.sonyliv";
        if (a.contains("zee")) return "com.graymatrix.did";
        if (a.contains("airtel") || a.contains("xstream")) return "tv.airtel.xstream.tvapp";
        if (a.contains("stremio")) return "com.stremio.one";
        if (a.contains("mx")) return "com.mxtech.videoplayer.television";
        if (a.contains("youtube") || a.contains("smarttube")) return "org.smarttube.stable";
        return "";
    }

    public String findNativeLaunchUri(String title) {
        String key = normalizeTitleKey(title);
        if (key.isEmpty()) return "";
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT title, series_title, launch_uri FROM watch_history WHERE launch_uri IS NOT NULL AND launch_uri != ''", null)) {
            while (c.moveToNext()) {
                String t = getStr(c, "series_title").isEmpty() ? getStr(c, "title") : getStr(c, "series_title");
                if (key.equals(normalizeTitleKey(t))) {
                    return getStr(c, "launch_uri");
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    public List<String> getActiveFilters(String profileId) {
        String raw = getSetting("filters_" + profileId, "");
        List<String> out = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String p : raw.split(",")) {
                if (!p.trim().isEmpty()) out.add(p.trim());
            }
        }
        return out;
    }

    public boolean isFilterActive(String profileId, String filterId) {
        return getActiveFilters(profileId).contains(filterId);
    }

    public void toggleFilter(String profileId, String filterId) {
        List<String> cur = getActiveFilters(profileId);
        if (cur.contains(filterId)) {
            cur.remove(filterId);
        } else {
            if ("movies".equals(filterId)) cur.remove("series");
            if ("series".equals(filterId)) cur.remove("movies");
            cur.add(filterId);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cur.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(cur.get(i));
        }
        setSetting("filters_" + profileId, sb.toString());
    }

    public void clearFilters(String profileId) {
        setSetting("filters_" + profileId, "");
    }

    public void markTitleWatched(JSONObject item, boolean watched) {
        String rawTitle = item.optString("series_title").isEmpty()
            ? item.optString("title")
            : item.optString("series_title");
        String key = normalizeTitleKey(rawTitle);
        if (key.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        if (!watched) {
            db.delete("watched_items", "title_key = ?", new String[]{key});
            return;
        }
        try {
            JSONObject copy = new JSONObject(item.toString());
            copy.put("watched", true);
            copy.put("state", "WATCHED ✓");
            ContentValues cv = new ContentValues();
            cv.put("title_key", key);
            cv.put("payload_json", copy.toString());
            cv.put("updated_at", System.currentTimeMillis() / 1000L);
            db.insertWithOnConflict("watched_items", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception ignored) {}
    }

    public boolean isTitleWatched(String title) {
        String key = normalizeTitleKey(title);
        if (key.isEmpty()) return false;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT 1 FROM watched_items WHERE title_key = ?", new String[]{key})) {
            return c.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    public Set<String> getWatchedTitleKeys() {
        Set<String> keys = new HashSet<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT title_key FROM watched_items", null)) {
            while (c.moveToNext()) keys.add(c.getString(0));
        } catch (Exception ignored) {}
        return keys;
    }

    public JSONArray listManuallyWatched() {
        JSONArray arr = new JSONArray();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT payload_json FROM watched_items ORDER BY updated_at DESC", null)) {
            while (c.moveToNext()) {
                arr.put(new JSONObject(c.getString(0)));
            }
        } catch (Exception ignored) {}
        return arr;
    }

    public JSONArray filterOutWatched(JSONArray raw) {
        JSONArray filtered = new JSONArray();
        if (raw == null) return filtered;
        Set<String> watchedKeys = getWatchedTitleKeys();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            String rawTitle = item.optString("series_title").isEmpty() ? item.optString("title") : item.optString("series_title");
            String key = normalizeTitleKey(rawTitle);
            if (!watchedKeys.contains(key) && !item.optBoolean("watched", false)) {
                filtered.put(item);
            }
        }
        return filtered;
    }

    private void setKvInternal(SQLiteDatabase db, String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        db.insertWithOnConflict("kv_settings", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public String getSetting(String k, String def) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT v FROM kv_settings WHERE k = ?", new String[]{k})) {
            if (c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return def;
    }

    public void setSetting(String k, String v) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        db.insertWithOnConflict("kv_settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void upsertWatchItem(JSONObject item) {
        String rawKey = item.optString("media_key").trim().toLowerCase();
        if (rawKey.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("media_key", rawKey);
        cv.put("profile_id", item.optString("profile_id", getSetting("active_profile", DEFAULT_PROFILE)));
        cv.put("app_pkg", item.optString("app_pkg"));
        cv.put("app_name", item.optString("app_name"));
        cv.put("title", item.optString("title"));
        cv.put("series_title", item.optString("series_title"));
        cv.put("season", item.optInt("season", 0));
        cv.put("episode", item.optInt("episode", 0));
        cv.put("synopsis", item.optString("synopsis"));
        cv.put("poster_url", item.optString("poster_url"));
        cv.put("genres", item.optString("genres"));
        cv.put("runtime_min", item.optInt("runtime_min", 45));
        cv.put("launch_uri", item.optString("launch_uri"));
        cv.put("position_sec", item.optInt("position_sec", 0));
        cv.put("duration_sec", item.optInt("duration_sec", 0));
        cv.put("updated_at", item.optLong("updated_at", System.currentTimeMillis() / 1000L));
        db.insertWithOnConflict("watch_history", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void enforceStorageCap() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM watch_history WHERE media_key NOT IN (" +
            "SELECT media_key FROM watch_history ORDER BY updated_at DESC LIMIT " + MAX_TV_HISTORY + ")");
    }

    public JSONArray listHistory(String profileId) {
        JSONArray arr = new JSONArray();
        Set<String> watchedKeys = getWatchedTitleKeys();
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT * FROM watch_history ORDER BY updated_at DESC LIMIT " + MAX_TV_HISTORY;
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                JSONObject o = cursorToWatchJson(c);
                String tKey = normalizeTitleKey(o.optString("series_title").isEmpty() ? o.optString("title") : o.optString("series_title"));
                if (watchedKeys.contains(tKey)) o.put("watched", true);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        return arr;
    }

    private JSONObject cursorToWatchJson(Cursor c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("media_key", getStr(c, "media_key"));
        o.put("profile_id", getStr(c, "profile_id"));
        o.put("app_pkg", getStr(c, "app_pkg"));
        o.put("app_name", getStr(c, "app_name"));
        o.put("title", getStr(c, "title"));
        o.put("series_title", getStr(c, "series_title"));
        o.put("season", getInt(c, "season"));
        o.put("episode", getInt(c, "episode"));
        o.put("synopsis", getStr(c, "synopsis"));
        o.put("poster_url", getStr(c, "poster_url"));
        o.put("genres", getStr(c, "genres"));
        o.put("runtime_min", getInt(c, "runtime_min"));
        o.put("launch_uri", getStr(c, "launch_uri"));
        o.put("position_sec", getInt(c, "position_sec"));
        o.put("duration_sec", getInt(c, "duration_sec"));
        o.put("updated_at", getLong(c, "updated_at"));
        return o;
    }

    private String getStr(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getString(i) : "";
    }

    private int getInt(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getInt(i) : 0;
    }

    private long getLong(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getLong(i) : 0L;
    }

    public String getCachedGti(String gti) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT payload_json FROM gti_cache WHERE gti = ?", new String[]{gti})) {
            if (c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return null;
    }

    public void saveCachedGti(String gti, String json) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("gti", gti);
        cv.put("payload_json", json);
        cv.put("updated_at", System.currentTimeMillis() / 1000L);
        db.insertWithOnConflict("gti_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public boolean isRecoCacheFresh(String profileId) {
        long age = getRecoCacheAgeSec(profileId);
        return age >= 0 && age < RECO_TTL_SEC;
    }

    public long getRecoCacheAgeSec(String profileId) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT updated_at FROM profile_recos WHERE profile_id = ?", new String[]{profileId})) {
            if (c.moveToFirst()) {
                long updated = c.getLong(0);
                return Math.max(0L, (System.currentTimeMillis() / 1000L) - updated);
            }
        } catch (Exception ignored) {}
        return -1L;
    }

    public void saveProfileRecommendations(String profileId, JSONArray recos) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("profile_id", profileId);
        cv.put("payload_json", filterOutWatched(recos).toString());
        cv.put("updated_at", System.currentTimeMillis() / 1000L);
        db.insertWithOnConflict("profile_recos", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public JSONArray getProfileRecommendations(String profileId) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT payload_json FROM profile_recos WHERE profile_id = ?", new String[]{profileId})) {
            if (c.moveToFirst()) {
                return filterOutWatched(new JSONArray(c.getString(0)));
            }
        } catch (Exception ignored) {}
        return new JSONArray();
    }

    public void saveVoiceSession(String profileId, JSONObject session) {
        try {
            if (session.has("results")) {
                session.put("results", filterOutWatched(session.optJSONArray("results")));
            }
        } catch (Exception ignored) {}
        setSetting("voice_session_" + profileId, session.toString());
    }

    public JSONObject getVoiceSession(String profileId) {
        String s = getSetting("voice_session_" + profileId, "");
        if (!s.isEmpty()) {
            try {
                JSONObject o = new JSONObject(s);
                if (o.has("results")) {
                    o.put("results", filterOutWatched(o.optJSONArray("results")));
                }
                return o;
            } catch (Exception ignored) {}
        }
        return defaultVoiceSession();
    }

    private JSONObject defaultVoiceSession() {
        JSONObject o = new JSONObject();
        try {
            o.put("reply", "Ready. Type or speak a request, or toggle persistent filters below.");
            o.put("turns", new JSONArray());
            o.put("results", new JSONArray());
            JSONArray chips = new JSONArray();
            chips.put("Sci-Fi movie (no Marvel/DC)");
            chips.put("Terrifying horror under 2h");
            chips.put("Mind-bending thriller");
            chips.put("Feel-good comedy");
            o.put("follow_ups", chips);
        } catch (Exception ignored) {}
        return o;
    }
}
