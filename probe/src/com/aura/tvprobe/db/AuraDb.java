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

    public static AuraDb peekInstance() {
        return sInstance;
    }

    public AuraDb(Context ctx) {
        super(ctx.getApplicationContext(), "aura_tv_local.db", null, 8);
        setWriteAheadLoggingEnabled(true);
        this.appCtx = ctx.getApplicationContext();
        applyBundledEnvIfPresent();
        ensureDefaultProfileExists();
        scheduleStartupCleanupAsync();
        if (sInstance == null) sInstance = this;
    }

    private void scheduleStartupCleanupAsync() {
        Thread t = new Thread(() -> {
            purgePromotionalNoiseFromHistory();
            purgeUnreleasedFromRecos();
            DatabaseJanitor.maybeRunDailyJanitor(this);
        }, "AuraDbCleanup");
        t.setDaemon(true);
        t.start();
    }

    public void purgePromotionalNoiseFromHistory() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            deleteInvalidHistoryRows(db);
            purgeStaleRecommendationsIfNeeded(db);
        } catch (Exception ignored) {}
    }

    private void deleteInvalidHistoryRows(SQLiteDatabase db) {
        db.execSQL("DELETE FROM watch_history WHERE app_pkg LIKE '%youtube%' OR app_pkg LIKE '%smarttube%' OR app_pkg LIKE '%stremio%'");
        db.execSQL("DELETE FROM watch_history WHERE LOWER(title) = 'netflix' OR LOWER(title) = 'prime video'");
        db.execSQL("DELETE FROM watch_history WHERE position_sec < 60");
        db.execSQL("DELETE FROM watch_history WHERE position_sec = 1350 AND duration_sec = 3000");
        db.execSQL("DELETE FROM watch_history WHERE duration_sec > 0 AND position_sec >= (duration_sec * 95) / 100");
    }

    private void purgeStaleRecommendationsIfNeeded(SQLiteDatabase db) {
        db.execSQL("UPDATE watch_history SET series_title = '' WHERE series_title LIKE '%\u2022%'");
        if (!"1".equals(getSetting("purged_stale_wn_v4", ""))) {
            db.execSQL("DELETE FROM watch_history WHERE app_name LIKE '%Watch Next%' AND updated_at = 1793976540");
            db.execSQL("DELETE FROM watch_history WHERE app_name LIKE '%Recommended%' OR app_name LIKE '%Netflix \u00B7%'");
            setSetting("purged_stale_wn_v4", "1");
        }
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
        createWatchHistoryTable(db);
        createSupportTables(db);
        DatabaseJanitor.ensureCompanionTables(db);
        seedDefaultSettings(db);
    }

    private void createWatchHistoryTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS watch_history (" +
            "media_key TEXT PRIMARY KEY, profile_id TEXT DEFAULT 'family', " +
            "app_pkg TEXT, app_name TEXT, title TEXT, series_title TEXT, " +
            "season INTEGER, episode INTEGER, synopsis TEXT, poster_url TEXT, " +
            "genres TEXT, runtime_min INTEGER DEFAULT 45, launch_uri TEXT, " +
            "position_sec INTEGER, duration_sec INTEGER, updated_at INTEGER)");
    }

    private void createSupportTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS gti_cache (gti TEXT PRIMARY KEY, payload_json TEXT, updated_at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS profile_recos (profile_id TEXT PRIMARY KEY, payload_json TEXT, updated_at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS watched_items (title_key TEXT PRIMARY KEY, payload_json TEXT, updated_at INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS kv_settings (k TEXT PRIMARY KEY, v TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS user_profiles (profile_id TEXT PRIMARY KEY, name TEXT, interests TEXT, created_at INTEGER)");
        createDatabaseIndices(db);
    }

    private void createDatabaseIndices(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_watch_history_prof_ts ON watch_history(profile_id, updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_watched_items_ts ON watched_items(updated_at DESC)");
    }

    private void seedDefaultSettings(SQLiteDatabase db) {
        setKvInternal(db, "active_profile", DEFAULT_PROFILE);
        setKvInternal(db, "server_mode", "tv");
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
        if (listProfilesRaw().isEmpty()) {
            saveProfileRecord(DEFAULT_PROFILE, getSetting("profile_name_member1", "Explorer"), getSetting("profile_taste_member1", ""));
            setSetting("active_profile", DEFAULT_PROFILE);
        }
        migrateOrphanedHistoryToProfile(getValidActiveProfile());
    }

    public String getValidActiveProfile() {
        String cur = getSetting("active_profile", DEFAULT_PROFILE);
        List<JSONObject> profiles = listProfilesRaw();
        if (isProfileInList(cur, profiles) || profiles.isEmpty()) return cur;
        String valid = profiles.get(0).optString("id", DEFAULT_PROFILE);
        setSetting("active_profile", valid);
        migrateOrphanedHistoryToProfile(valid);
        return valid;
    }

    private boolean isProfileInList(String profileId, List<JSONObject> profiles) {
        for (JSONObject p : profiles) {
            if (profileId != null && profileId.equals(p.optString("id"))) return true;
        }
        return false;
    }

    private void migrateOrphanedHistoryToProfile(String validProfileId) {
        try {
            getWritableDatabase().execSQL(
                "UPDATE watch_history SET profile_id = ? WHERE profile_id NOT IN (SELECT profile_id FROM user_profiles)",
                new Object[]{validProfileId});
        } catch (Exception ignored) {}
    }

    private List<JSONObject> listProfilesRaw() {
        List<JSONObject> list = new ArrayList<>();
        String sql = "SELECT profile_id, name, interests FROM user_profiles ORDER BY created_at ASC";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) list.add(parseProfileRow(c));
        } catch (Exception ignored) {}
        return list;
    }

    private JSONObject parseProfileRow(Cursor c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", c.getString(0)).put("name", c.getString(1));
        return o.put("interests", c.isNull(2) ? "" : c.getString(2));
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
        if (rem.isEmpty()) return createFallbackProfile();
        String nextId = rem.get(0).optString("id", DEFAULT_PROFILE);
        setSetting("active_profile", nextId);
        return nextId;
    }

    private String createFallbackProfile() {
        String fallbackId = "prof_" + (System.currentTimeMillis() % 1000000L);
        saveProfileRecord(fallbackId, "Temp_User", "");
        setSetting("active_profile", fallbackId);
        return fallbackId;
    }

    private static boolean envLoadedThisProcess = false;

    private void applyBundledEnvIfPresent() {
        if (appCtx == null || envLoadedThisProcess) return;
        envLoadedThisProcess = true;
        try (InputStream is = appCtx.getAssets().open("aura.env");
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) applySingleEnvLine(line.trim());
        } catch (Exception ignored) {}
    }

    private void applySingleEnvLine(String t) {
        if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) return;
        int eq = t.indexOf('=');
        String k = t.substring(0, eq).trim();
        String v = t.substring(eq + 1).trim();
        if (!v.isEmpty()) applyEnvKeyValue(k, v);
    }

    private void applyEnvKeyValue(String k, String v) {
        if ("GEMINI_API_KEY".equals(k)) setSetting("gemini_api_key", v);
        else if ("AURA_HOST_URL".equals(k)) setSetting("mac_host", v);
        else if ("AURA_PHONE_URL".equals(k)) setSetting("phone_host", v);
        else applyEnvProfileName(k, v);
    }

    private void applyEnvProfileName(String k, String v) {
        if ("AURA_MEMBER1_NAME".equals(k)) setSetting("profile_name_member1", v);
        else if ("AURA_PARTNER_NAME".equals(k)) setSetting("profile_name_partner", v);
        else if ("AURA_MEMBER3_NAME".equals(k)) setSetting("profile_name_member3", v);
        else if ("AURA_KIDS_NAME".equals(k)) setSetting("profile_name_kids", v);
        else if ("AURA_FAMILY_NAME".equals(k)) setSetting("profile_name_family", v);
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
        return RegionProviderRegistry.resolveAndroidPackage(appName);
    }

    public String findNativeLaunchUri(String title) {
        String key = normalizeTitleKey(title);
        if (key.isEmpty()) return "";
        String fromHistory = findLaunchUriInHistory(key);
        if (!fromHistory.isEmpty()) return fromHistory;
        return findLaunchUriInGtiCache(title);
    }

    private String findLaunchUriInHistory(String normKey) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT title, series_title, launch_uri FROM watch_history WHERE launch_uri IS NOT NULL AND launch_uri != ''", null)) {
            while (c.moveToNext()) {
                String t = getStr(c, "series_title").isEmpty() ? getStr(c, "title") : getStr(c, "series_title");
                if (normKey.equals(normalizeTitleKey(t))) {
                    return getStr(c, "launch_uri");
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String findLaunchUriInGtiCache(String title) {
        if (title == null) return "";
        String raw = getCachedGti("jw_imdb_v6:" + title.trim().toLowerCase());
        if (raw == null || raw.isEmpty()) return "";
        try {
            return new JSONObject(raw).optString("launch_uri", "");
        } catch (Exception e) {
            return "";
        }
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
            addMutuallyExclusiveFilter(cur, filterId);
        }
        setSetting("filters_" + profileId, String.join(",", cur));
    }

    private void addMutuallyExclusiveFilter(List<String> cur, String filterId) {
        if ("movies".equals(filterId)) cur.remove("series");
        if ("series".equals(filterId)) cur.remove("movies");
        cur.add(filterId);
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
            return "Modern critically acclaimed releases (" + minYear + "-" + curYear + ", IMDb 7.5+), gripping storytelling, smart pacing, streaming on " + RegionProviderRegistry.getProviderDisplayListForRegion(this);
        }
        return def;
    }

    public void setDefaultTasteGuide(String guide) {
        setSetting("default_guide_ai", guide != null ? guide.trim() : "");
    }

    public List<String> getTraversalChips(String profileId) {
        List<String> out = parseStoredTraversalChips(getSetting("traversal_chips_" + profileId, ""));
        if (out.isEmpty()) {
            populateDefaultTraversalChips(profileId, out);
        }
        return out;
    }

    private List<String> parseStoredTraversalChips(String raw) {
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && out.size() < 4; i++) {
                String s = arr.optString(i).trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void populateDefaultTraversalChips(String profileId, List<String> out) {
        int curYear = JustWatchGraph.getCurrentYear();
        List<String> anchors = getDynamicTasteAnchors(profileId);
        out.add(!anchors.isEmpty() ? ("Similar to " + anchors.get(0)) : ((curYear - 2) + "–" + curYear + " Prestige Series"));
        out.add("Mind-Bending Sci-Fi");
        out.add("Gripping Crime Thrillers");
    }

    public void saveTraversalChips(String profileId, JSONArray chips) {
        if (chips != null && chips.length() > 0) {
            setSetting("traversal_chips_" + profileId, chips.toString());
        }
    }

    public void purgeUnreleasedFromRecos() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            if (resetRecosIfEngineUpgraded(db)) return;
            List<String> toDelete = findProfilesWithStaleRecos(db);
            for (String pid : toDelete) {
                db.delete("profile_recos", "profile_id = ?", new String[]{pid});
            }
        } catch (Exception ignored) {}
    }

    private boolean resetRecosIfEngineUpgraded(SQLiteDatabase db) {
        if ("sota_v15".equals(getSetting("reco_engine_version", ""))) return false;
        db.delete("profile_recos", null, null);
        db.delete("gti_cache", "gti LIKE 'jw_imdb_%'", null);
        setSetting("voice_session_default", "");
        setSetting("reco_engine_version", "sota_v15");
        return true;
    }

    private List<String> findProfilesWithStaleRecos(SQLiteDatabase db) throws Exception {
        List<String> toDelete = new ArrayList<>();
        Set<String> watchedKeys = getWatchedTitleKeys();
        try (Cursor c = db.rawQuery("SELECT profile_id, payload_json FROM profile_recos", null)) {
            while (c.moveToNext()) {
                if (hasStaleOrWatchedReco(new JSONArray(c.getString(1)), watchedKeys)) {
                    toDelete.add(c.getString(0));
                }
            }
        }
        return toDelete;
    }

    private boolean hasStaleOrWatchedReco(JSONArray arr, Set<String> watchedKeys) {
        int maxYear = JustWatchGraph.getCurrentYear();
        int minModernYear = JustWatchGraph.getDefaultMinModernYear();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && isSingleRecoStale(o, i, minModernYear, maxYear, watchedKeys)) return true;
        }
        return false;
    }

    private boolean isSingleRecoStale(JSONObject o, int idx, int minModernYear, int maxYear, Set<String> watchedKeys) {
        int y = o.optInt("year", maxYear);
        String normKey = normalizeTitleKey(o.optString("title", ""));
        if (y > maxYear || y < minModernYear || o.optString("app", "").contains("Store")) return true;
        if (o.optInt("imdb_votes", 0) < 900 || watchedKeys.contains(normKey)) return true;
        return idx < 6 && o.optString("backdrop_url", "").isEmpty();
    }

    /**
     * SOTA Temporal Engagement-Weighted Taste Anchors.
     * Filters out Stremio, YouTube, and accidental clicks (< 120s), then scores
     * each watched title by completion ratio * 14-day exponential recency decay.
     */
    public List<String> getDynamicTasteAnchors(String profileId) {
        return getRotatingTasteAnchors(profileId, ImpressionTracker.getCurrentEpoch(this, profileId));
    }

    /**
     * Infinite-Seed Rotating Taste Portfolio.
     * Takes at most 2 active watch_history items (with 48h TTL decay so stale starts age out)
     * plus a rotating window of 4 titles from the user's watched/rated library.
     */
    public List<String> getRotatingTasteAnchors(String profileId, int epoch) {
        List<JSONObject> validHistory = collectActiveHistoryAnchors(profileId);
        List<String> anchors = new ArrayList<>();
        appendDiversifiedHistoryAnchors(validHistory, anchors, 4);
        appendRotatingLibrarySeeds(anchors, epoch, 6);
        return anchors;
    }

    private void appendDiversifiedHistoryAnchors(List<JSONObject> validHistory, List<String> anchors, int maxHistory) {
        Set<String> seenApps = new HashSet<>();
        for (JSONObject item : validHistory) {
            if (anchors.size() >= maxHistory) return;
            if (seenApps.add(item.optString("app_pkg", ""))) addHistoryAnchorIfUnique(item, anchors);
        }
        for (JSONObject item : validHistory) {
            if (anchors.size() < maxHistory) addHistoryAnchorIfUnique(item, anchors);
        }
    }

    private void addHistoryAnchorIfUnique(JSONObject item, List<String> anchors) {
        String t = item.optString("clean_anchor_title", "").trim();
        if (t.length() >= 2 && !anchors.contains(t)) anchors.add(t);
    }

    private List<JSONObject> collectActiveHistoryAnchors(String profileId) {
        List<JSONObject> validHistory = new ArrayList<>();
        JSONArray hist = listHistory(profileId);
        long nowSec = System.currentTimeMillis() / 1000L;
        for (int i = 0; i < hist.length(); i++) {
            JSONObject candidate = tryBuildActiveHistoryAnchor(hist.optJSONObject(i), nowSec);
            if (candidate != null) validHistory.add(candidate);
        }
        validHistory.sort((a, b) -> Double.compare(b.optDouble("engagement_weight", 0.0), a.optDouble("engagement_weight", 0.0)));
        return validHistory;
    }

    private JSONObject tryBuildActiveHistoryAnchor(JSONObject h, long nowSec) {
        if (h == null || isIgnoredHistoryApp(h) || !hasEligibleAnchorProgress(h)) return null;
        double hoursAgo = computeHistoryHoursAgo(h, nowSec);
        if (hoursAgo > 720.0) return null; // 30-day window for active watch_history shows
        String t = extractUsableHistoryAnchorTitle(h);
        if (t.isEmpty()) return null;
        return cloneWithEngagementWeight(h, t, hoursAgo);
    }

    private String extractUsableHistoryAnchorTitle(JSONObject h) {
        String series = h.optString("series_title", "").trim();
        if (series.contains("\u2022") || series.contains(",")) series = "";
        String t = series.isEmpty() ? h.optString("title", "").trim() : series;
        if (isInvalidHistoryAnchorTitle(h, t, series)) return "";
        return t;
    }

    private boolean isInvalidHistoryAnchorTitle(JSONObject h, String t, String series) {
        if (t.isEmpty() || t.equalsIgnoreCase("Netflix") || t.equalsIgnoreCase("Prime Video")) return true;
        if (t.contains("\u2022") || t.contains("Tour of ") || t.contains("Full House With ")) return true;
        return series.isEmpty() && h.optString("app_pkg").contains("graymatrix") && t.split("\\s+").length >= 3;
    }

    private boolean hasEligibleAnchorProgress(JSONObject h) {
        int pos = h.optInt("position_sec", 0);
        int dur = Math.max(1800, h.optInt("duration_sec", 2700));
        return pos >= 180 && !(pos == 1350 && dur == 3000);
    }

    private double computeHistoryHoursAgo(JSONObject h, long nowSec) {
        long updatedSec = h.optLong("updated_at", nowSec);
        if (updatedSec > 10_000_000_000L) updatedSec /= 1000L;
        return Math.max(0.0, (nowSec - updatedSec) / 3600.0);
    }

    private JSONObject cloneWithEngagementWeight(JSONObject h, String title, double hoursAgo) {
        try {
            int pos = h.optInt("position_sec", 0);
            int dur = Math.max(1800, h.optInt("duration_sec", 2700));
            JSONObject copy = new JSONObject(h.toString());
            copy.put("clean_anchor_title", title);
            return copy.put("engagement_weight", ((double) pos / dur) * Math.exp(-0.693 * hoursAgo / 72.0));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isIgnoredHistoryApp(JSONObject h) {
        String pkg = h.optString("app_pkg", "").toLowerCase();
        String app = h.optString("app_name", h.optString("app", "")).toLowerCase();
        return pkg.contains("youtube") || pkg.contains("smarttube") || pkg.contains("stremio")
            || app.contains("stremio") || app.contains("youtube");
    }

    private void appendRotatingLibrarySeeds(List<String> anchors, int epoch, int targetTotal) {
        JSONArray manual = listManuallyWatched();
        List<String> pool = collectRatedLibrarySeeds(manual, 7);
        if (pool.isEmpty() && anchors.size() < 3) pool = collectRatedLibrarySeeds(manual, 0);
        if (pool.isEmpty()) return;
        int startIdx = (Math.max(0, epoch) * 3) % pool.size();
        for (int k = 0; k < pool.size() && anchors.size() < targetTotal; k++) {
            String candidate = pool.get((startIdx + k) % pool.size());
            if (!anchors.contains(candidate)) anchors.add(candidate);
        }
    }

    private List<String> collectRatedLibrarySeeds(JSONArray watched, int minExplicitRating) {
        List<String> pool = new ArrayList<>();
        for (int i = 0; i < watched.length(); i++) {
            JSONObject w = watched.optJSONObject(i);
            if (w == null || w.optInt("user_rating", 0) < minExplicitRating) continue;
            String t = w.optString("series_title").isEmpty() ? w.optString("title") : w.optString("series_title");
            if (t != null && t.trim().length() >= 2 && !pool.contains(t.trim())) pool.add(t.trim());
        }
        return pool;
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
        try (Cursor c = getReadableDatabase().rawQuery("SELECT title_key, payload_json FROM watched_items", null)) {
            while (c.moveToNext()) putRatingIfPresent(c, map);
        } catch (Exception ignored) {}
        return map;
    }

    private void putRatingIfPresent(Cursor c, java.util.Map<String, Integer> map) throws Exception {
        int r = new JSONObject(c.getString(1)).optInt("user_rating", 0);
        if (r > 0) map.put(c.getString(0), r);
    }

    public String getRatingFeedbackSummary() {
        List<String> loved = new ArrayList<>();
        List<String> disliked = new ArrayList<>();
        partitionRatedLibrary(listManuallyWatched(), loved, disliked);
        StringBuilder sb = new StringBuilder();
        if (!loved.isEmpty()) sb.append("Highly Rated by User (recommend more like these): ").append(loved.toString()).append(". ");
        if (!disliked.isEmpty()) sb.append("Low Rated by User (avoid similar tone/style): ").append(disliked.toString()).append(".");
        return sb.toString();
    }

    private void partitionRatedLibrary(JSONArray watched, List<String> loved, List<String> disliked) {
        for (int i = 0; i < watched.length(); i++) {
            JSONObject o = watched.optJSONObject(i);
            int r = o != null ? o.optInt("user_rating", 0) : 0;
            if (r <= 0) continue;
            String t = o.optString("series_title").isEmpty() ? o.optString("title") : o.optString("series_title");
            if (r >= 7) loved.add(t + " (" + r + "/10)");
            else if (r <= 5) disliked.add(t + " (" + r + "/10)");
        }
    }

    public void markTitleWatched(JSONObject item, boolean watched) {
        String rawTitle = item.optString("series_title").isEmpty() ? item.optString("title") : item.optString("series_title");
        String key = normalizeTitleKey(rawTitle);
        if (key.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        if (!watched) {
            db.delete("watched_items", "title_key = ?", new String[]{key});
        } else {
            insertWatchedItemRecord(db, key, item);
        }
    }

    private void insertWatchedItemRecord(SQLiteDatabase db, String key, JSONObject item) {
        try {
            JSONObject copy = new JSONObject(item.toString()).put("watched", true).put("state", "WATCHED ✓");
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
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM watched_items WHERE title_key = ? LIMIT 1", new String[]{key})) {
            return c.moveToFirst();
        } catch (Exception ignored) {
            return false;
        }
    }

    public Set<String> getWatchedTitleKeys() {
        Set<String> keys = new HashSet<>();
        String sql = "SELECT title_key FROM watched_items ORDER BY updated_at DESC LIMIT 2000";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) addWatchedKeyWithBaseVariant(c.getString(0), keys);
        } catch (Exception ignored) {}
        return keys;
    }

    private void addWatchedKeyWithBaseVariant(String k, Set<String> keys) {
        keys.add(k);
        if (k.matches(".*(chapter|part|vol)\\d+$")) {
            keys.add(k.replaceAll("\\d+$", ""));
        }
    }

    public Set<String> getHistoryTitleKeys(String profileId) {
        Set<String> keys = new HashSet<>();
        JSONArray hist = listHistory(profileId);
        for (int i = 0; i < hist.length(); i++) {
            JSONObject h = hist.optJSONObject(i);
            if (h == null) continue;
            String t = h.optString("series_title").isEmpty() ? h.optString("title") : h.optString("series_title");
            String k = normalizeTitleKey(t);
            if (!k.isEmpty()) keys.add(k);
        }
        return keys;
    }

    public JSONArray listManuallyWatched() {
        JSONArray arr = new JSONArray();
        String sql = "SELECT payload_json, updated_at FROM watched_items ORDER BY updated_at DESC LIMIT 500";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) arr.put(parseWatchedCursorRow(c));
        } catch (Exception ignored) {}
        return arr;
    }

    private JSONObject parseWatchedCursorRow(Cursor c) throws Exception {
        JSONObject obj = new JSONObject(c.getString(0));
        if (!c.isNull(1) && c.getLong(1) > 0) obj.put("updated_at", c.getLong(1));
        return obj;
    }

    public JSONArray filterOutWatched(JSONArray raw) {
        JSONArray filtered = new JSONArray();
        if (raw == null) return filtered;
        Set<String> watchedKeys = getWatchedTitleKeys();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item != null && isUnwatchedItem(item, watchedKeys)) filtered.put(item);
        }
        return filtered;
    }

    private boolean isUnwatchedItem(JSONObject item, Set<String> watchedKeys) {
        String rawTitle = item.optString("series_title").isEmpty() ? item.optString("title") : item.optString("series_title");
        return !watchedKeys.contains(normalizeTitleKey(rawTitle)) && !item.optBoolean("watched", false);
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
        int newPos = item.optInt("position_sec", 0);
        int newDur = item.optInt("duration_sec", 0);
        if (rawKey.isEmpty() || (newPos == 1350 && newDur == 3000)) return;
        SQLiteDatabase db = getWritableDatabase();
        long updatedAt = resolveWatchItemTimestamp(db, item, rawKey, newPos);
        ContentValues cv = buildWatchItemValues(item, rawKey, newPos, newDur, updatedAt);
        db.insertWithOnConflict("watch_history", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        recordCompanionProgressAndPrefetch(item, newPos, newDur);
    }

    private void recordCompanionProgressAndPrefetch(JSONObject item, int newPos, int newDur) {
        String baseTitle = item.optString("series_title").isEmpty() ? item.optString("title") : item.optString("series_title");
        int s = item.optInt("season", 0);
        int e = item.optInt("episode", 0);
        DatabaseJanitor.recordEpisodeCheckpoint(this, baseTitle, s, e, newPos, newDur);
        SubtitleAndGuideStore.proactivelyCacheSeriesAsync(this, baseTitle, s, e);
    }

    private ContentValues buildWatchItemValues(JSONObject item, String rawKey, int newPos, int newDur, long updatedAt) {
        ContentValues cv = new ContentValues();
        cv.put("media_key", rawKey);
        cv.put("profile_id", item.optString("profile_id", getSetting("active_profile", DEFAULT_PROFILE)));
        cv.put("app_pkg", item.optString("app_pkg"));
        cv.put("app_name", item.optString("app_name"));
        cv.put("title", item.optString("title"));
        cv.put("series_title", item.optString("series_title"));
        populateWatchItemDetailValues(cv, item, newPos, newDur, updatedAt);
        return cv;
    }

    private void populateWatchItemDetailValues(ContentValues cv, JSONObject item, int newPos, int newDur, long updatedAt) {
        cv.put("season", item.optInt("season", 0));
        cv.put("episode", item.optInt("episode", 0));
        cv.put("synopsis", item.optString("synopsis"));
        cv.put("poster_url", item.optString("poster_url"));
        cv.put("genres", item.optString("genres"));
        cv.put("runtime_min", item.optInt("runtime_min", 45));
        cv.put("launch_uri", item.optString("launch_uri"));
        cv.put("position_sec", newPos);
        cv.put("duration_sec", newDur);
        cv.put("updated_at", updatedAt);
    }

    private long resolveWatchItemTimestamp(SQLiteDatabase db, JSONObject item, String mediaKey, int newPos) {
        long explicitTs = item.optLong("updated_at", 0L);
        if (explicitTs > 0L) return explicitTs;
        long nowSec = System.currentTimeMillis() / 1000L;
        return queryExistingTimestampIfUnmoved(db, mediaKey, newPos, nowSec);
    }

    private long queryExistingTimestampIfUnmoved(SQLiteDatabase db, String mediaKey, int newPos, long fallbackTs) {
        try (Cursor c = db.rawQuery("SELECT position_sec, updated_at FROM watch_history WHERE media_key = ?", new String[]{mediaKey})) {
            if (c.moveToFirst() && Math.abs(c.getInt(0) - newPos) < 30 && c.getLong(1) > 0) {
                return c.getLong(1);
            }
        } catch (Exception ignored) {}
        return fallbackTs;
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
        Set<String> watchedKeys = getWatchedTitleKeys();
        boolean hasProf = profileId != null && !profileId.isEmpty();
        JSONArray arr = queryHistoryRows(hasProf ? profileId : null, watchedKeys);
        return (hasProf && arr.length() < 3) ? queryHistoryRows(null, watchedKeys) : arr;
    }

    private JSONArray queryHistoryRows(String profileId, Set<String> watchedKeys) {
        JSONArray arr = new JSONArray();
        String sql = "SELECT * FROM watch_history " + (profileId != null ? "WHERE profile_id = ? " : "") + "ORDER BY updated_at DESC LIMIT " + MAX_TV_HISTORY;
        try (Cursor c = getReadableDatabase().rawQuery(sql, profileId != null ? new String[]{profileId} : null)) {
            while (c.moveToNext()) arr.put(parseHistoryRowWithWatchedFlag(c, watchedKeys));
        } catch (Exception ignored) {}
        return arr;
    }

    private JSONObject parseHistoryRowWithWatchedFlag(Cursor c, Set<String> watchedKeys) throws Exception {
        JSONObject o = cursorToWatchJson(c);
        String tKey = normalizeTitleKey(o.optString("series_title").isEmpty() ? o.optString("title") : o.optString("series_title"));
        if (watchedKeys.contains(tKey)) o.put("watched", true);
        return o;
    }

    private JSONObject cursorToWatchJson(Cursor c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("media_key", getStr(c, "media_key")).put("profile_id", getStr(c, "profile_id"));
        o.put("app_pkg", getStr(c, "app_pkg")).put("app_name", getStr(c, "app_name"));
        o.put("title", getStr(c, "title")).put("series_title", getStr(c, "series_title"));
        o.put("season", getInt(c, "season")).put("episode", getInt(c, "episode"));
        o.put("synopsis", getStr(c, "synopsis")).put("poster_url", getStr(c, "poster_url"));
        o.put("genres", getStr(c, "genres")).put("runtime_min", getInt(c, "runtime_min"));
        o.put("launch_uri", getStr(c, "launch_uri")).put("position_sec", getInt(c, "position_sec"));
        return o.put("duration_sec", getInt(c, "duration_sec")).put("updated_at", getLong(c, "updated_at"));
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
        long now = System.currentTimeMillis() / 1000L;
        ContentValues cv = new ContentValues();
        cv.put("gti", gti);
        cv.put("payload_json", json);
        cv.put("updated_at", now);
        db.insertWithOnConflict("gti_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        DatabaseJanitor.enforceGtiCacheCaps(db, now);
    }

    public boolean isRecoCacheFresh(String profileId) {
        long age = getRecoCacheAgeSec(profileId);
        return age >= 0 && age < RECO_TTL_SEC;
    }

    public long getRecoCacheAgeSec(String profileId) {
        long updated = getRecoUpdatedAt(profileId);
        return updated > 0L ? Math.max(0L, (System.currentTimeMillis() / 1000L) - updated) : -1L;
    }

    public long getRecoUpdatedAt(String profileId) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT updated_at FROM profile_recos WHERE profile_id = ?", new String[]{profileId})) {
            if (c.moveToFirst()) return c.getLong(0);
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
            JSONObject parsed = tryParseVoiceSession(s);
            if (parsed != null) return parsed;
        }
        return defaultVoiceSession();
    }

    private JSONObject tryParseVoiceSession(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            if (o.has("results")) o.put("results", filterOutWatched(o.optJSONArray("results")));
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject defaultVoiceSession() {
        JSONObject o = new JSONObject();
        try {
            o.put("reply", "Ready. Type or speak a request, or toggle persistent filters below.");
            o.put("turns", new JSONArray()).put("results", new JSONArray());
            o.put("follow_ups", defaultVoiceChips());
        } catch (Exception ignored) {}
        return o;
    }

    private JSONArray defaultVoiceChips() {
        return new JSONArray()
            .put("Sci-Fi movie (no Marvel/DC)")
            .put("Terrifying horror under 2h")
            .put("Mind-bending thriller")
            .put("Feel-good comedy");
    }
}
