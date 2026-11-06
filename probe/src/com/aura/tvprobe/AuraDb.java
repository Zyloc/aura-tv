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
    private static AuraDb sInstance;
    private final Context appCtx;

    public static synchronized AuraDb getInstance(Context ctx) {
        if (sInstance == null) {
            sInstance = new AuraDb(ctx.getApplicationContext());
        }
        return sInstance;
    }

    public AuraDb(Context ctx) {
        super(ctx.getApplicationContext(), "aura_tv_local.db", null, 8);
        setWriteAheadLoggingEnabled(true);
        this.appCtx = ctx.getApplicationContext();
        applyBundledEnvIfPresent();
        ensureDefaultProfileExists();
        purgePromotionalNoiseFromHistory();
        purgeUnreleasedFromRecos();
        if (sInstance == null) sInstance = this;
    }

    public void purgePromotionalNoiseFromHistory() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("DELETE FROM watch_history WHERE app_pkg LIKE '%youtube%' OR app_pkg LIKE '%smarttube%' OR app_pkg LIKE '%stremio%'");
            db.execSQL("DELETE FROM watch_history WHERE LOWER(title) = 'netflix' OR LOWER(title) = 'prime video'");
            db.execSQL("DELETE FROM watch_history WHERE position_sec < 60");
            db.execSQL("DELETE FROM watch_history WHERE duration_sec > 0 AND position_sec >= (duration_sec * 95) / 100");
            // One-time cleanup of rows that had synthetic updated_at timestamps from preview_programs
            if (!"1".equals(getSetting("purged_stale_wn_v2", ""))) {
                db.execSQL("DELETE FROM watch_history WHERE app_name LIKE '%·%'");
                setSetting("purged_stale_wn_v2", "1");
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        try {
            db.execSQL("PRAGMA cache_size = -256");
            db.execSQL("PRAGMA synchronous = NORMAL");
        } catch (Exception ignored) {}
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
        db.execSQL("CREATE TABLE IF NOT EXISTS user_profiles (" +
            "profile_id TEXT PRIMARY KEY, name TEXT, interests TEXT, created_at INTEGER)");
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

    private void ensureDefaultProfileExists() {
        List<JSONObject> existing = listProfilesRaw();
        if (!existing.isEmpty()) return;
        String starterName = getSetting("profile_name_member1", "Explorer");
        String starterTaste = getSetting("profile_taste_member1", "");
        saveProfileRecord(DEFAULT_PROFILE, starterName, starterTaste);
        setSetting("active_profile", DEFAULT_PROFILE);
    }

    private List<JSONObject> listProfilesRaw() {
        List<JSONObject> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT profile_id, name, interests FROM user_profiles ORDER BY created_at ASC", null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getString(0));
                o.put("name", c.getString(1));
                o.put("interests", c.isNull(2) ? "" : c.getString(2));
                list.add(o);
            }
        } catch (Exception ignored) {}
        return list;
    }

    public List<JSONObject> listProfiles() {
        List<JSONObject> list = listProfilesRaw();
        if (list.isEmpty()) {
            ensureDefaultProfileExists();
            list = listProfilesRaw();
        }
        return list;
    }

    public void saveProfileRecord(String profileId, String name, String interests) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("profile_id", profileId);
        cv.put("name", name != null && !name.trim().isEmpty() ? name.trim() : "Guest");
        cv.put("interests", interests != null ? interests.trim() : "");
        cv.put("created_at", System.currentTimeMillis() / 1000L);
        db.insertWithOnConflict("user_profiles", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        setSetting("profile_name_" + profileId, cv.getAsString("name"));
        setSetting("profile_taste_" + profileId, cv.getAsString("interests"));
    }

    public String addProfile(String name, String interests) {
        String cleanName = (name == null || name.trim().isEmpty())
            ? ("User_" + (100 + (int) (Math.random() * 899)))
            : name.trim();
        String pid = "prof_" + (System.currentTimeMillis() % 1000000L);
        saveProfileRecord(pid, cleanName, interests);
        setSetting("active_profile", pid);
        return pid;
    }

    public String deleteProfile(String profileId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("user_profiles", "profile_id = ?", new String[]{profileId});
        db.delete("profile_recos", "profile_id = ?", new String[]{profileId});
        List<JSONObject> rem = listProfilesRaw();
        if (rem.isEmpty()) {
            String fallbackId = "prof_" + (System.currentTimeMillis() % 1000000L);
            saveProfileRecord(fallbackId, "Temp_User", "");
            setSetting("active_profile", fallbackId);
            return fallbackId;
        }
        String nextId = rem.get(0).optString("id", DEFAULT_PROFILE);
        setSetting("active_profile", nextId);
        return nextId;
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
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT name FROM user_profiles WHERE profile_id = ?", new String[]{profileId})) {
            if (c.moveToFirst() && !c.getString(0).isEmpty()) return c.getString(0);
        } catch (Exception ignored) {}
        return getSetting("profile_name_" + profileId, "Guest");
    }

    public JSONObject getProfileLabelsJson() {
        JSONObject o = new JSONObject();
        try {
            for (JSONObject p : listProfiles()) {
                o.put(p.optString("id"), p.optString("name"));
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

    public String getProfileTaste(String profileId) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT interests FROM user_profiles WHERE profile_id = ?", new String[]{profileId})) {
            if (c.moveToFirst() && c.getString(0) != null && !c.getString(0).isEmpty()) return c.getString(0);
        } catch (Exception ignored) {}
        return getSetting("profile_taste_" + profileId, "");
    }

    public void setProfileTaste(String profileId, String taste) {
        String clean = taste != null ? taste.trim() : "";
        setSetting("profile_taste_" + profileId, clean);
        saveProfileRecord(profileId, getProfileLabel(profileId), clean);
    }

    public String getDefaultTasteGuide() {
        String def = getSetting("default_guide_ai", "").trim();
        int curYear = JustWatchGraph.getCurrentYear();
        int minYear = JustWatchGraph.getDefaultMinModernYear();
        if (def.isEmpty()) {
            return "Modern critically acclaimed releases (" + minYear + "-" + curYear + ", IMDb 7.5+), gripping storytelling, smart pacing, streaming on Netflix, Prime Video, Disney+ Hotstar, or Apple TV+";
        }
        return def;
    }

    public void setDefaultTasteGuide(String guide) {
        setSetting("default_guide_ai", guide != null ? guide.trim() : "");
    }

    public List<String> getTraversalChips(String profileId) {
        List<String> out = new ArrayList<>();
        String raw = getSetting("traversal_chips_" + profileId, "");
        if (!raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length() && out.size() < 4; i++) {
                    String s = arr.optString(i).trim();
                    if (!s.isEmpty()) out.add(s);
                }
            } catch (Exception ignored) {}
        }
        if (out.isEmpty()) {
            int curYear = JustWatchGraph.getCurrentYear();
            List<String> anchors = getDynamicTasteAnchors(profileId);
            if (!anchors.isEmpty()) {
                out.add("Similar to " + anchors.get(0));
            } else {
                out.add((curYear - 2) + "–" + curYear + " Prestige Series");
            }
            out.add("Mind-Bending Sci-Fi");
            out.add("Gripping Crime Thrillers");
        }
        return out;
    }

    public void saveTraversalChips(String profileId, JSONArray chips) {
        if (chips != null && chips.length() > 0) {
            setSetting("traversal_chips_" + profileId, chips.toString());
        }
    }

    public void purgeUnreleasedFromRecos() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            int maxYear = JustWatchGraph.getCurrentYear();
            int minModernYear = JustWatchGraph.getDefaultMinModernYear();
            List<String> toDeleteProfiles = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT profile_id, payload_json FROM profile_recos", null)) {
                while (c.moveToNext()) {
                    String pid = c.getString(0);
                    JSONArray arr = new JSONArray(c.getString(1));
                    boolean hasStaleOrOld = false;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        int y = o.optInt("year", maxYear);
                        String app = o.optString("app", "");
                        if (y > maxYear || y < minModernYear || app.contains("Store")) {
                            hasStaleOrOld = true;
                            break;
                        }
                    }
                    if (hasStaleOrOld) {
                        toDeleteProfiles.add(pid);
                    }
                }
            }
            for (String pid : toDeleteProfiles) {
                db.delete("profile_recos", "profile_id = ?", new String[]{pid});
            }
        } catch (Exception ignored) {}
    }

    public List<String> getDynamicTasteAnchors(String profileId) {
        List<String> anchors = new ArrayList<>();
        JSONArray hist = listHistory(profileId);
        for (int i = 0; i < hist.length() && anchors.size() < 4; i++) {
            JSONObject h = hist.optJSONObject(i);
            if (h == null) continue;
            if (h.optString("app_pkg").contains("youtube")) continue;
            String t = h.optString("series_title").isEmpty() ? h.optString("title") : h.optString("series_title");
            if (!t.isEmpty() && !t.equalsIgnoreCase("Netflix") && !anchors.contains(t)) {
                anchors.add(t);
            }
        }
        JSONArray watched = listManuallyWatched();
        for (int i = 0; i < watched.length() && anchors.size() < 6; i++) {
            JSONObject o = watched.optJSONObject(i);
            if (o != null && o.optInt("user_rating", 8) >= 7) {
                String t = o.optString("series_title").isEmpty() ? o.optString("title") : o.optString("series_title");
                if (!t.isEmpty() && !anchors.contains(t)) anchors.add(t);
            }
        }
        return anchors;
    }

    public void rateTitle(JSONObject item, int ratingOutOf10) {
        try {
            JSONObject copy = new JSONObject(item.toString());
            copy.put("user_rating", ratingOutOf10);
            markTitleWatched(copy, true);
        } catch (Exception ignored) {}
    }

    public java.util.Map<String, Integer> getUserRatingsMap() {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT title_key, payload_json FROM watched_items", null)) {
            while (c.moveToNext()) {
                String k = c.getString(0);
                JSONObject o = new JSONObject(c.getString(1));
                int r = o.optInt("user_rating", 0);
                if (r > 0) map.put(k, r);
            }
        } catch (Exception ignored) {}
        return map;
    }

    public String getRatingFeedbackSummary() {
        List<String> loved = new ArrayList<>();
        List<String> disliked = new ArrayList<>();
        JSONArray watched = listManuallyWatched();
        for (int i = 0; i < watched.length(); i++) {
            JSONObject o = watched.optJSONObject(i);
            if (o == null) continue;
            int r = o.optInt("user_rating", 0);
            if (r <= 0) continue;
            String t = o.optString("series_title").isEmpty() ? o.optString("title") : o.optString("series_title");
            if (r >= 7) loved.add(t + " (" + r + "/10)");
            else if (r <= 5) disliked.add(t + " (" + r + "/10)");
        }
        StringBuilder sb = new StringBuilder();
        if (!loved.isEmpty()) sb.append("Highly Rated by User (recommend more like these): ").append(loved.toString()).append(". ");
        if (!disliked.isEmpty()) sb.append("Low Rated by User (avoid similar tone/style): ").append(disliked.toString()).append(".");
        return sb.toString();
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
        enforceStorageCapForProfile(null);
    }

    public void enforceStorageCapForProfile(String profileId) {
        SQLiteDatabase db = getWritableDatabase();
        String where = profileId != null
            ? "profile_id = '" + profileId + "' AND media_key NOT IN (" +
              "SELECT media_key FROM watch_history WHERE profile_id = '" + profileId +
              "' ORDER BY updated_at DESC LIMIT " + MAX_TV_HISTORY + ")"
            : "media_key NOT IN (" +
              "SELECT media_key FROM watch_history ORDER BY updated_at DESC LIMIT " + MAX_TV_HISTORY + ")";
        db.execSQL("DELETE FROM watch_history WHERE " + where);
    }

    public JSONArray listHistory(String profileId) {
        JSONArray arr = new JSONArray();
        Set<String> watchedKeys = getWatchedTitleKeys();
        SQLiteDatabase db = getReadableDatabase();
        boolean hasProf = profileId != null && !profileId.isEmpty();
        String sql = hasProf
            ? "SELECT * FROM watch_history WHERE profile_id = ? ORDER BY updated_at DESC LIMIT " + MAX_TV_HISTORY
            : "SELECT * FROM watch_history ORDER BY updated_at DESC LIMIT " + MAX_TV_HISTORY;
        String[] args = hasProf ? new String[]{profileId} : null;
        try (Cursor c = db.rawQuery(sql, args)) {
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
