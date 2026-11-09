package com.aura.tvprobe;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SessionInspector {
    private final Context ctx;
    private final ComponentName component;
    private final AuraDb db;
    private final AiRecommender ai;
    private final SyncManager sync;
    private long lastProviderSyncMs = 0L;

    public SessionInspector(Context ctx, ComponentName component, AuraDb db, AiRecommender ai) {
        this.ctx = ctx;
        this.component = component;
        this.db = db;
        this.ai = ai;
        this.sync = new SyncManager(db);
    }

    public boolean isMediaActivelyPlaying() {
        JSONArray sessions = getMediaSessions();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (s != null && s.optInt("state") == PlaybackState.STATE_PLAYING) return true;
        }
        return false;
    }

    public void recordActivePlaybackOnly() {
        String profile = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
        JSONObject raw = new JSONObject();
        try { raw.put("sessions", getMediaSessions()); } catch (Exception ignored) {}
        resolveNowPlaying(raw, profile);
    }

    public JSONObject inspectAll() {
        JSONObject root = new JSONObject();
        try {
            populateAllInspectionData(root);
        } catch (Exception e) {
            try { root.put("error", e.toString()); } catch (Exception ignored) {}
        }
        return root;
    }

    private void populateAllInspectionData(JSONObject root) throws Exception {
        root.put("foreground_pkg", getForegroundPackage());
        root.put("sessions", getMediaSessions());
        root.put("channels", queryTvProvider("content://android.media.tv/channel", false));
        root.put("watch_next", queryTvProvider("content://android.media.tv/watch_next_program", true));
        root.put("preview_programs", queryTvProvider("content://android.media.tv/preview_program", true));
    }

    public JSONObject buildFastCachedState() {
        String profile = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
        JSONArray history = db.listHistory(profile);
        JSONArray recos = getCachedOrSeedRecos(profile);
        JSONObject nowPlaying = history.length() > 0 ? history.optJSONObject(0) : new JSONObject();
        return buildFastStateJson(profile, nowPlaying, recos, history);
    }

    private JSONArray getCachedOrSeedRecos(String profile) {
        JSONArray recos = db.getProfileRecommendations(profile);
        return recos.length() == 0 ? ai.getBuiltInSotaSeedCatalog(profile) : recos;
    }

    private JSONObject buildFastStateJson(String profile, JSONObject nowPlaying,
                                          JSONArray recos, JSONArray history) {
        JSONObject out = new JSONObject();
        try {
            populateBaseStateFields(out, profile, nowPlaying, recos, history);
            out.put("profile_labels", db.getProfileLabelsJson());
        } catch (Exception ignored) {}
        return out;
    }

    public JSONObject syncProvidersAndNowPlaying(String profile) {
        long nowMs = System.currentTimeMillis();
        JSONObject raw = new JSONObject();
        if ((nowMs - lastProviderSyncMs) > 60000L || db.listHistory(profile).length() == 0) {
            raw = refreshFullProviderState(nowMs);
        } else {
            populateLightweightSessionSnapshot(raw);
        }
        return resolveNowPlaying(raw, profile);
    }

    private JSONObject refreshFullProviderState(long nowMs) {
        JSONObject raw = inspectAll();
        syncProviderToLocalDb(raw);
        db.enforceStorageCap();
        lastProviderSyncMs = nowMs;
        return raw;
    }

    private void populateLightweightSessionSnapshot(JSONObject raw) {
        try {
            raw.put("foreground_pkg", getForegroundPackage());
            raw.put("sessions", getMediaSessions());
        } catch (Exception ignored) {}
    }

    public JSONObject syncAndBuildState(boolean forceAiRefresh) {
        String profile = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
        if (forceAiRefresh) lastProviderSyncMs = 0L;
        JSONObject nowPlaying = syncProvidersAndNowPlaying(profile);
        JSONArray history = db.listHistory(profile);
        boolean wasFresh = db.isRecoCacheFresh(profile);
        JSONArray recos = ai.getOrRefreshForProfile(profile, nowPlaying, history, forceAiRefresh);
        if (forceAiRefresh || !wasFresh) {
            new TvChannelPublisher(ctx).publishRecommendations(recos);
        }
        return assembleStateResponse(profile, nowPlaying, recos, history);
    }

    private JSONObject assembleStateResponse(String profile, JSONObject nowPlaying,
                                             JSONArray recos, JSONArray history) {
        JSONObject out = new JSONObject();
        try {
            populateBaseStateFields(out, profile, nowPlaying, recos, history);
            out.put("foreground_pkg", getForegroundPackage());
        } catch (Exception ignored) {}
        return out;
    }

    private void populateBaseStateFields(JSONObject out, String profile, JSONObject nowPlaying,
                                         JSONArray recos, JSONArray history) throws Exception {
        out.put("online", true);
        out.put("active_profile", profile);
        out.put("server_mode", db.getSetting("server_mode", "auto"));
        out.put("host_url", sync.getTargetHostUrl());
        out.put("cache_age_sec", db.getRecoCacheAgeSec(profile));
        populateMediaStateFields(out, profile, nowPlaying, recos, history);
    }

    private void populateMediaStateFields(JSONObject out, String profile, JSONObject nowPlaying,
                                          JSONArray recos, JSONArray history) throws Exception {
        out.put("now_playing", nowPlaying);
        out.put("recommendations", recos);
        out.put("watched_items", db.listManuallyWatched());
        out.put("voice_session", db.getVoiceSession(profile));
        out.put("active_filters", new JSONArray(db.getActiveFilters(profile)));
        out.put("history", history);
        out.put("history_count", history.length());
    }

    private void syncProviderToLocalDb(JSONObject raw) {
        Map<Long, String> chMap = buildChannelMap(raw.optJSONArray("channels"));
        android.database.sqlite.SQLiteDatabase wdb = db.getWritableDatabase();
        wdb.beginTransaction();
        try {
            // Ingest verified watch_next_program rows engaged within the last 90 days
            ingestAllProviderRows(raw, chMap, wdb);
        } finally {
            wdb.endTransaction();
        }
    }

    private void ingestAllProviderRows(JSONObject raw, Map<Long, String> chMap,
                                       android.database.sqlite.SQLiteDatabase wdb) {
        ingestRows(raw.optJSONArray("watch_next"), chMap, "Watch Next");
        ingestNetflixContinueWatching(raw.optJSONArray("preview_programs"));
        wdb.setTransactionSuccessful();
    }

    private Map<Long, String> buildChannelMap(JSONArray chs) {
        Map<Long, String> chMap = new HashMap<>();
        if (chs == null) return chMap;
        for (int i = 0; i < chs.length(); i++) {
            JSONObject c = chs.optJSONObject(i);
            if (c != null) chMap.put(c.optLong("_id"), c.optString("display_name"));
        }
        return chMap;
    }

    private void ingestNetflixContinueWatching(JSONArray rows) {
        if (rows == null) return;
        long nowSec = System.currentTimeMillis() / 1000L;
        for (int i = rows.length() - 1; i >= 0; i--) {
            ingestSingleNetflixRow(rows.optJSONObject(i), nowSec);
        }
    }

    private void ingestSingleNetflixRow(JSONObject r, long nowSec) {
        if (!isValidNetflixContinueRow(r)) return;
        String title = r.optString("title", "").trim();
        String intentUri = r.optString("intent_uri", "");
        // Extract expireAt from Netflix deeplink (expireAt is updated_at + 86400s)
        long updatedSec = parseNetflixUpdatedSec(intentUri, nowSec);
        upsertNetflixWatchItem(r, title, intentUri, updatedSec);
    }

    private boolean isValidNetflixContinueRow(JSONObject r) {
        if (r == null) return false;
        String pkg = r.optString("package_name", "");
        String intentUri = r.optString("intent_uri", "");
        if (!pkg.contains("netflix") || !intentUri.toLowerCase().contains("continuewatching")) return false;
        String title = r.optString("title", "").trim();
        return !title.isEmpty() && !title.equalsIgnoreCase("Netflix");
    }

    private long parseNetflixUpdatedSec(String intentUri, long nowSec) {
        long updatedSec = nowSec - 1800L;
        try {
            int expIdx = intentUri.indexOf("expireAt%3D");
            if (expIdx < 0) return updatedSec;
            int start = expIdx + 11;
            int end = intentUri.indexOf("%26", start);
            if (end < 0) end = Math.min(intentUri.length(), start + 10);
            long expSec = Long.parseLong(intentUri.substring(start, end));
            return expSec > 1700000000L ? expSec - 86400L : updatedSec;
        } catch (Exception ignored) { return updatedSec; }
    }

    private void upsertNetflixWatchItem(JSONObject r, String title, String intentUri, long updatedSec) {
        try {
            JSONObject item = buildNetflixItemBase(r, title, intentUri);
            item.put("position_sec", 1350);
            item.put("duration_sec", 3000);
            item.put("updated_at", updatedSec);
            db.upsertWatchItem(item);
        } catch (Exception ignored) {}
    }

    private JSONObject buildNetflixItemBase(JSONObject r, String title, String intentUri) throws Exception {
        JSONObject item = new JSONObject();
        item.put("media_key", ("com.netflix.ninja:" + title + ":").toLowerCase());
        item.put("app_pkg", "com.netflix.ninja");
        item.put("app_name", "Netflix");
        item.put("title", title);
        item.put("series_title", "");
        item.put("synopsis", r.optString("short_description"));
        item.put("poster_url", r.optString("poster_art_uri"));
        item.put("runtime_min", 50);
        return item.put("launch_uri", intentUri);
    }

    private void ingestRows(JSONArray rows, Map<Long, String> chMap, String defaultCh) {
        if (rows == null) return;
        long nowSec = System.currentTimeMillis() / 1000L;
        long maxAgeSec = 90L * 86400L; // Only shows engaged within the last 90 days
        for (int i = rows.length() - 1; i >= 0; i--) {
            ingestSingleProviderRow(rows.optJSONObject(i), chMap, defaultCh, nowSec, maxAgeSec);
        }
    }

    private void ingestSingleProviderRow(JSONObject r, Map<Long, String> chMap,
                                         String defaultCh, long nowSec, long maxAgeSec) {
        if (!isEligibleWatchNextRow(r, nowSec, maxAgeSec)) return;
        String pkg = r.optString("package_name");
        String title = r.optString("title", "Program");
        if (title.equalsIgnoreCase(friendlyApp(pkg))) return;
        String ch = defaultCh.isEmpty() ? chMap.getOrDefault(r.optLong("channel_id"), "") : defaultCh;
        upsertProviderWatchItem(r, pkg, title, ch);
    }

    private boolean isEligibleWatchNextRow(JSONObject r, long nowSec, long maxAgeSec) {
        if (r == null || isExcludedProviderPkg(r.optString("package_name"))) return false;
        int posSec = (int) (r.optLong("last_playback_position_millis", 0) / 1000L);
        int durSec = (int) (r.optLong("duration_millis", 0) / 1000L);
        // Skip already completed movies/episodes (>= 95% watched)
        if (posSec < 60 || (durSec > 0 && posSec >= (durSec * 95) / 100)) return false;
        long engagedSec = r.optLong("last_engagement_time_utc_millis", 0L) / 1000L;
        return engagedSec > 0 && (nowSec - engagedSec) <= maxAgeSec;
    }

    private boolean isExcludedProviderPkg(String pkg) {
        return ctx.getPackageName().equals(pkg) || pkg.contains("youtube")
            || pkg.contains("smarttube") || pkg.contains("stremio");
    }

    private void upsertProviderWatchItem(JSONObject r, String pkg, String title, String ch) {
        try {
            JSONObject item = buildProviderItemCore(r, pkg, title, ch);
            populateProviderItemTiming(item, r);
            db.upsertWatchItem(item);
        } catch (Exception ignored) {}
    }

    private JSONObject buildProviderItemCore(JSONObject r, String pkg, String title, String ch) throws Exception {
        String epTitle = r.optString("episode_title", "");
        JSONObject item = new JSONObject();
        item.put("media_key", (pkg + ":" + title + ":" + epTitle).toLowerCase());
        item.put("app_pkg", pkg);
        item.put("app_name", friendlyApp(pkg) + (ch.isEmpty() ? "" : " · " + ch));
        item.put("title", epTitle.isEmpty() ? title : epTitle);
        item.put("series_title", epTitle.isEmpty() ? "" : title);
        item.put("season", r.optInt("season_number", 0));
        item.put("episode", r.optInt("episode_number", 0));
        return item;
    }

    private void populateProviderItemTiming(JSONObject item, JSONObject r) throws Exception {
        int posSec = (int) (r.optLong("last_playback_position_millis", 0) / 1000L);
        int durSec = (int) (r.optLong("duration_millis", 0) / 1000L);
        long engagedSec = r.optLong("last_engagement_time_utc_millis", 0L) / 1000L;
        item.put("synopsis", r.optString("short_description"));
        item.put("poster_url", r.optString("poster_art_uri"));
        item.put("runtime_min", durSec > 0 ? Math.max(1, durSec / 60) : 45);
        item.put("launch_uri", r.optString("intent_uri"));
        item.put("position_sec", posSec);
        item.put("duration_sec", durSec);
        item.put("updated_at", engagedSec);
    }

    private JSONObject resolveNowPlaying(JSONObject raw, String profile) {
        JSONObject s = pickPreferredSession(raw.optJSONArray("sessions"));
        if (s != null) return sessionToNowPlaying(s, profile);
        return resolveLastWatchedFromHistory(profile);
    }

    private JSONObject pickPreferredSession(JSONArray sessions) {
        if (sessions == null || sessions.length() == 0) return null;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject cand = sessions.optJSONObject(i);
            if (cand != null && cand.optInt("state") == 3) return cand;
        }
        return sessions.optJSONObject(0);
    }

    private JSONObject resolveLastWatchedFromHistory(String profile) {
        JSONArray hist = db.listHistory(profile);
        if (hist.length() == 0) return new JSONObject();
        JSONObject last = hist.optJSONObject(0);
        try { last.put("state", "LAST WATCHED"); } catch (Exception ignored) {}
        return last;
    }

    private JSONObject sessionToNowPlaying(JSONObject s, String profile) {
        JSONObject out = new JSONObject();
        try {
            String pkg = s.optString("package");
            JSONObject meta = s.optJSONObject("metadata");
            if (meta == null) meta = new JSONObject();
            populateBaseSessionFields(out, s, meta, pkg, profile);
            populateSessionMediaMetadata(out, meta, pkg);
            maybePersistActiveSession(out, pkg);
        } catch (Exception ignored) {}
        return out;
    }

    private void populateBaseSessionFields(JSONObject out, JSONObject s, JSONObject meta,
                                           String pkg, String profile) throws Exception {
        out.put("profile_id", profile);
        out.put("app_pkg", pkg);
        out.put("app_name", friendlyApp(pkg));
        out.put("state", s.optInt("state") == 3 ? "PLAYING" : "PAUSED");
        out.put("position_sec", (int) (s.optLong("position_ms", 0) / 1000L));
        long durMs = meta.optLong("android.media.metadata.DURATION", -1);
        out.put("duration_sec", durMs > 0 ? (int) (durMs / 1000L) : 3600);
    }

    private void populateSessionMediaMetadata(JSONObject out, JSONObject meta, String pkg) throws Exception {
        String mediaId = meta.optString("android.media.metadata.MEDIA_ID",
            meta.optString("com.amazon.alexa.externalmediaplayer.metadata.PLAYBACK_SOURCE_ID", ""));
        if (mediaId.startsWith("amzn1.dv.gti.")) {
            populatePrimeSessionMetadata(out, mediaId);
        } else {
            populateGenericSessionMetadata(out, meta, pkg);
        }
    }

    private void maybePersistActiveSession(JSONObject out, String pkg) throws Exception {
        String checkTitle = out.optString("series_title").isEmpty()
            ? out.optString("title") : out.optString("series_title");
        if (!checkTitle.isEmpty() && !checkTitle.equalsIgnoreCase(friendlyApp(pkg))
                && !pkg.contains("youtube") && !pkg.contains("smarttube")) {
            enrichFromLocalCache(out, checkTitle);
            out.put("media_key", (pkg + ":" + out.optString("series_title") + ":" + out.optString("title")).toLowerCase());
            db.upsertWatchItem(out);
        }
    }

    private void populatePrimeSessionMetadata(JSONObject out, String mediaId) throws Exception {
        JSONObject p = ai.resolvePrimeGti(mediaId);
        out.put("series_title", p.optString("series_title"));
        out.put("title", p.optString("title"));
        out.put("episode", p.optInt("episode"));
        out.put("season", p.optInt("season", 1));
        out.put("synopsis", p.optString("synopsis"));
        out.put("poster_url", p.optString("poster_url"));
        out.put("genres", p.optString("genres"));
        out.put("runtime_min", p.optInt("runtime_min", 60));
        out.put("launch_uri", "https://app.primevideo.com/watch?gti=" + mediaId);
    }

    private void populateGenericSessionMetadata(JSONObject out, JSONObject meta, String pkg) throws Exception {
        String t = meta.optString("android.media.metadata.TITLE",
            meta.optString("android.media.metadata.DISPLAY_TITLE", friendlyApp(pkg)));
        out.put("title", t);
        out.put("series_title", meta.optString("android.media.metadata.ARTIST"));
        out.put("poster_url", meta.optString("android.media.metadata.ALBUM_ART_URI"));
    }

    private void enrichFromLocalCache(JSONObject out, String title) {
        try {
            String cached = db.getCachedGti("jw_imdb_v6:" + title.trim().toLowerCase());
            if (cached == null) return;
            JSONObject c = new JSONObject(cached);
            if (out.optString("launch_uri").isEmpty()) out.put("launch_uri", c.optString("launch_uri"));
            if (out.optString("poster_url").isEmpty()) out.put("poster_url", c.optString("poster_url"));
            if (out.optString("genres").isEmpty()) out.put("genres", c.optString("genres"));
            if (out.optString("synopsis").isEmpty()) out.put("synopsis", c.optString("synopsis"));
        } catch (Exception ignored) {}
    }

    public static String friendlyApp(String pkg) {
        if (pkg.contains("amazon")) return "Prime Video";
        if (pkg.contains("netflix")) return "Netflix";
        if (pkg.contains("smarttube")) return "SmartTube";
        if (pkg.contains("youtube")) return "YouTube";
        if (pkg.contains("hotstar")) return "Disney+ Hotstar";
        if (pkg.contains("appletv") || pkg.contains("apple.atve")) return "Apple TV+";
        return friendlyAppExtended(pkg);
    }

    private static String friendlyAppExtended(String pkg) {
        if (pkg.contains("xstream")) return "Airtel Xstream";
        if (pkg.contains("sonyliv")) return "SonyLIV";
        if (pkg.contains("stremio")) return "Stremio";
        if (pkg.contains("graymatrix")) return "ZEE5";
        if (pkg.contains("mxtech")) return "MX Player";
        return pkg;
    }

    private JSONArray getMediaSessions() {
        JSONArray arr = new JSONArray();
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = msm.getActiveSessions(component);
            for (MediaController mc : list) arr.put(serializeController(mc));
        } catch (Exception ignored) {}
        return arr;
    }

    private JSONObject serializeController(MediaController mc) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("package", mc.getPackageName());
        populatePlaybackState(obj, mc.getPlaybackState());
        obj.put("metadata", extractMetadata(mc.getMetadata()));
        obj.put("extras", extractBundle(mc.getExtras()));
        return obj;
    }

    private void populatePlaybackState(JSONObject obj, PlaybackState ps) throws Exception {
        if (ps == null) return;
        obj.put("state", ps.getState());
        obj.put("position_ms", ps.getPosition());
        obj.put("speed", ps.getPlaybackSpeed());
    }

    private JSONObject extractMetadata(MediaMetadata meta) throws Exception {
        JSONObject m = new JSONObject();
        if (meta == null) return m;
        for (String key : meta.keySet()) {
            CharSequence cs = meta.getText(key);
            if (cs != null) m.put(key, cs.toString());
            else m.put(key, meta.getLong(key));
        }
        return m;
    }

    private JSONObject extractBundle(Bundle b) throws Exception {
        JSONObject j = new JSONObject();
        if (b == null) return j;
        for (String k : b.keySet()) {
            Object v = b.get(k);
            if (v != null) j.put(k, v.toString());
        }
        return j;
    }

    private String getForegroundPackage() {
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60000, now);
            return pickMostRecentPackage(stats);
        } catch (Exception e) {
            return "";
        }
    }

    private String pickMostRecentPackage(List<UsageStats> stats) {
        UsageStats recent = null;
        for (UsageStats s : stats) {
            if (recent == null || s.getLastTimeUsed() > recent.getLastTimeUsed()) recent = s;
        }
        return recent != null ? recent.getPackageName() : "";
    }

    private JSONArray queryTvProvider(String uriStr, boolean dedupByTitle) {
        JSONArray rows = new JSONArray();
        Set<String> seen = new HashSet<>();
        try (Cursor c = ctx.getContentResolver().query(Uri.parse(uriStr), null, null, null, "_id DESC")) {
            if (c != null) collectCursorRows(c, rows, seen, dedupByTitle);
        } catch (Exception ignored) {}
        return rows;
    }

    private void collectCursorRows(Cursor c, JSONArray rows, Set<String> seen, boolean dedupByTitle) throws Exception {
        while (c.moveToNext()) {
            JSONObject row = cursorRowToJson(c);
            if (dedupByTitle && !seen.add(buildProviderRowKey(row))) continue;
            rows.put(row);
        }
    }

    private String buildProviderRowKey(JSONObject row) {
        return (row.optString("package_name") + "|" + row.optString("title")
            + "|" + row.optString("episode_title")).toLowerCase();
    }

    private JSONObject cursorRowToJson(Cursor c) throws Exception {
        JSONObject row = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++) {
            int t = c.getType(i);
            if (t == Cursor.FIELD_TYPE_STRING) row.put(c.getColumnName(i), c.getString(i));
            else if (t == Cursor.FIELD_TYPE_INTEGER) row.put(c.getColumnName(i), c.getLong(i));
        }
        return row;
    }
}
