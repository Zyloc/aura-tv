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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SessionInspector {
    private static final Pattern EP_CODE_PAT =
        Pattern.compile("(?i)^S(\\d{1,2})\\s*[:·-]?\\s*E(\\d{1,3})\\s+(.+)$");
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

    public void recordActivePlaybackOnly() {
        String profile = db.getValidActiveProfile();
        JSONObject raw = new JSONObject();
        try { raw.put("sessions", getMediaSessions()); } catch (Exception ignored) {}
        resolveNowPlaying(raw, profile);
    }

    public JSONObject inspectLivePackageSession(String fgPkg, String profile) {
        JSONObject match = findLiveSessionForPackage(getMediaSessions(), fgPkg);
        if (match == null) return null;
        JSONObject out = sessionToNowPlaying(match, profile);
        try { out.put("live_player_screen", isLikelyOnVideoPlayerScreen(match)); } catch (Exception ignored) {}
        return out;
    }

    public static JSONObject findLiveSessionForPackage(JSONArray sessions, String fgPkg) {
        if (sessions == null || sessions.length() == 0) return null;
        JSONObject pausedFallback = null;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (!matchesSessionPackage(s, fgPkg)) continue;
            if (CompanionAudioFocusController.isActivePlaybackState(s.optInt("state", 0))) return s;
            if (pausedFallback == null && s.optInt("state", 0) == PlaybackState.STATE_PAUSED) pausedFallback = s;
        }
        return pausedFallback;
    }

    private static boolean matchesSessionPackage(JSONObject s, String fgPkg) {
        if (s == null) return false;
        String pkg = s.optString("package", "");
        if (pkg.isEmpty()) return false;
        return fgPkg == null || fgPkg.isEmpty() || fgPkg.equals(pkg);
    }

    public static boolean isLikelyOnVideoPlayerScreen(JSONObject sessionJson) {
        if (sessionJson == null) return false;
        int st = sessionJson.optInt("state", 0);
        if (CompanionAudioFocusController.isActivePlaybackState(st)) return true;
        return st == PlaybackState.STATE_PAUSED
            && sessionJson.optLong("age_since_update_ms", Long.MAX_VALUE) <= 30_000L;
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
        String profile = db.getValidActiveProfile();
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
            out.put("system_health", SystemResourceMonitor.collectTelemetrySnapshot(ctx, db));
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
        String profile = db.getValidActiveProfile();
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
            out.put("system_health", SystemResourceMonitor.collectTelemetrySnapshot(ctx, db));
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
        out.put("app_broadcast_trends", db.getAppBroadcastTrends());
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
        JSONArray watchNext = raw.optJSONArray("watch_next");
        JSONArray previews = raw.optJSONArray("preview_programs");
        ingestRows(watchNext, chMap, "Watch Next");
        ingestNetflixContinueWatching(previews);
        ingestBroadcastedAppTrends(previews, watchNext, chMap);
        wdb.setTransactionSuccessful();
    }

    public void ingestBroadcastedAppTrends(JSONArray previews, JSONArray watchNext, Map<Long, String> chMap) {
        JSONArray trends = new JSONArray();
        Set<String> seen = new HashSet<>();
        collectTrendRowsFromArray(previews, chMap, false, seen, trends);
        collectTrendRowsFromArray(watchNext, chMap, true, seen, trends);
        if (trends.length() > 0) db.saveAppBroadcastTrends(trends);
    }

    private void collectTrendRowsFromArray(JSONArray rows, Map<Long, String> chMap,
                                           boolean isWatchNext, Set<String> seen, JSONArray out) {
        if (rows == null) return;
        for (int i = 0; i < rows.length() && out.length() < 32; i++) {
            JSONObject r = rows.optJSONObject(i);
            if (isEligibleBroadcastTrendRow(r, isWatchNext)) {
                appendBroadcastTrendEntry(r, chMap, isWatchNext, seen, out);
            }
        }
    }

    private boolean isEligibleBroadcastTrendRow(JSONObject r, boolean isWatchNext) {
        if (r == null || isValidNetflixContinueRow(r)) return false;
        String pkg = r.optString("package_name", "");
        if (pkg.isEmpty() || isExcludedProviderPkg(pkg)) return false;
        if (isWatchNext && r.optLong("last_playback_position_millis", 0L) >= 60000L) return false;
        String title = r.optString("title", "").trim();
        return !title.isEmpty() && !title.equalsIgnoreCase(friendlyApp(pkg)) && !title.equalsIgnoreCase("Program");
    }

    private void appendBroadcastTrendEntry(JSONObject r, Map<Long, String> chMap,
                                           boolean isWatchNext, Set<String> seen, JSONArray out) {
        String title = r.optString("title", "").trim();
        String norm = AuraDb.normalizeTitleKey(title);
        if (norm.isEmpty() || !seen.add(norm)) return;
        try {
            out.put(buildBroadcastTrendJson(r, title, chMap, isWatchNext));
        } catch (Exception ignored) {}
    }

    private JSONObject buildBroadcastTrendJson(JSONObject r, String title,
                                               Map<Long, String> chMap, boolean isWatchNext) throws Exception {
        String pkg = r.optString("package_name", "");
        String intentUri = r.optString("intent_uri", "");
        String chName = chMap != null ? chMap.getOrDefault(r.optLong("channel_id"), "") : "";
        if (chName.isEmpty()) chName = resolveBroadcastChannelLabel(intentUri, isWatchNext);
        return new JSONObject().put("title", title).put("app_pkg", pkg).put("app", friendlyApp(pkg))
            .put("channel", chName).put("synopsis", r.optString("short_description", ""))
            .put("poster_url", r.optString("poster_art_uri", "")).put("launch_uri", intentUri);
    }

    private String resolveBroadcastChannelLabel(String intentUri, boolean isWatchNext) {
        String low = intentUri != null ? intentUri.toLowerCase() : "";
        if (low.contains("trendingnow")) return "Trending Now";
        if (low.contains("new.season")) return "New Season";
        if (low.contains("recently.added")) return "Recently Added";
        return isWatchNext ? "Recommended Next" : "App Featured";
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
        long baseSec = extractNetflixExpireAtBase(intentUri, nowSec - 1800L, nowSec);
        return baseSec - parseNetflixTileOffset(intentUri);
    }

    private long extractNetflixExpireAtBase(String intentUri, long fallbackSec, long nowSec) {
        try {
            int expIdx = intentUri.indexOf("expireAt%3D");
            if (expIdx < 0) return fallbackSec;
            int start = expIdx + 11;
            int end = intentUri.indexOf("%26", start);
            long expSec = Long.parseLong(intentUri.substring(start, end < 0 ? Math.min(intentUri.length(), start + 10) : end));
            return expSec > 1700000000L ? Math.min(nowSec, expSec - 86400L) : fallbackSec;
        } catch (Exception ignored) { return fallbackSec; }
    }

    private long parseNetflixTileOffset(String intentUri) {
        try {
            int idx = intentUri.indexOf("tileIndex%253D");
            if (idx < 0) return 0L;
            int start = idx + 14;
            int end = intentUri.indexOf("%2526", start);
            int tile = Integer.parseInt(intentUri.substring(start, end < 0 ? start + 1 : end));
            return Math.max(0, tile) * 60L;
        } catch (Exception ignored) { return 0L; }
    }

    private void upsertNetflixWatchItem(JSONObject r, String title, String intentUri, long updatedSec) {
        try {
            JSONObject item = buildNetflixItemBase(r, title, intentUri);
            int existingPos = findExistingNetflixPositionSec(title);
            item.put("position_sec", existingPos > 60 ? existingPos : 1200);
            item.put("duration_sec", 2700);
            item.put("updated_at", updatedSec);
            db.upsertWatchItem(item);
        } catch (Exception ignored) {}
    }

    private int findExistingNetflixPositionSec(String title) {
        JSONArray hist = db.listHistory(db.getValidActiveProfile());
        for (int i = 0; i < hist.length(); i++) {
            JSONObject h = hist.optJSONObject(i);
            if (h != null && "com.netflix.ninja".equals(h.optString("app_pkg"))
                    && title.equalsIgnoreCase(h.optString("title"))) {
                return h.optInt("position_sec", 1200);
            }
        }
        return 1200;
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
        item.put("season", r.optInt("season_display_number", r.optInt("season_number", 0)));
        item.put("episode", r.optInt("episode_display_number", r.optInt("episode_number", 0)));
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

    public void persistSerializedSession(JSONObject sessionJson) {
        if (sessionJson == null) return;
        String profile = db.getValidActiveProfile();
        sessionToNowPlaying(sessionJson, profile);
    }

    public JSONObject serializeControllerSafe(MediaController mc) {
        try { return mc != null ? serializeController(mc) : null; } catch (Exception e) { return null; }
    }

    private JSONObject sessionToNowPlaying(JSONObject s, String profile) {
        JSONObject out = new JSONObject();
        try {
            JSONObject meta = s.optJSONObject("metadata") != null ? s.optJSONObject("metadata") : new JSONObject();
            String pkg = s.optString("package");
            populateBaseSessionFields(out, s, meta, pkg, profile);
            populateSessionMediaMetadata(out, meta, pkg);
            applyNormalizedSessionTiming(out, meta);
            maybePersistActiveSession(out, meta, pkg);
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

    private void applyNormalizedSessionTiming(JSONObject out, JSONObject meta) throws Exception {
        long durMs = meta.optLong("android.media.metadata.DURATION", -1L);
        int[] norm = normalizeSessionTiming(out.optInt("position_sec", 0), durMs, out.optInt("runtime_min", 60));
        out.put("position_sec", norm[0]);
        out.put("duration_sec", norm[1]);
    }

    public static int[] normalizeSessionTiming(int posSec, long explicitDurMs, int runtimeMin) {
        int safeRuntime = runtimeMin > 0 ? runtimeMin : 60;
        int durSec = explicitDurMs > 0L ? (int) (explicitDurMs / 1000L) : Math.max(1800, safeRuntime * 60);
        int cleanPos = Math.max(0, posSec);
        if (explicitDurMs > 0L && cleanPos > durSec) cleanPos = durSec;
        else if (explicitDurMs <= 0L && cleanPos >= durSec) durSec = cleanPos + 900;
        return new int[]{cleanPos, durSec};
    }

    public static String extractSnapshotIdentity(JSONObject snap) {
        JSONObject meta = snap != null ? snap.optJSONObject("metadata") : null;
        if (meta == null) return "";
        String id = meta.optString("android.media.metadata.MEDIA_ID",
            meta.optString("com.amazon.alexa.externalmediaplayer.metadata.PLAYBACK_SOURCE_ID", ""));
        String title = meta.optString("android.media.metadata.TITLE",
            meta.optString("android.media.metadata.DISPLAY_TITLE", ""));
        return (id.isEmpty() && title.isEmpty()) ? "" : (snap.optString("package") + "|" + id + "|" + title);
    }

    public static long resolveUpdatedPlayStartPosMs(long extrapolatedPrevMs, long currentPosMs, boolean mediaChanged) {
        if (mediaChanged) return Math.max(0L, currentPosMs);
        if (currentPosMs >= 60_000L) return currentPosMs;
        return Math.max(0L, extrapolatedPrevMs);
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

    private void maybePersistActiveSession(JSONObject out, JSONObject meta, String pkg) throws Exception {
        String checkTitle = out.optString("series_title").isEmpty()
            ? out.optString("title") : out.optString("series_title");
        if (isOrphanEpisodeCodeTitle(out.optString("series_title"), out.optString("title"))) return;
        if (isPersistableVideoTitle(checkTitle, pkg)) {
            enrichAndSaveActiveSession(out, meta, checkTitle, pkg);
        }
    }

    private boolean isPersistableVideoTitle(String checkTitle, String pkg) {
        return !checkTitle.isEmpty() && !checkTitle.equalsIgnoreCase(friendlyApp(pkg))
            && !pkg.contains("youtube") && !pkg.contains("smarttube");
    }

    private void enrichAndSaveActiveSession(JSONObject out, JSONObject meta, String checkTitle, String pkg) throws Exception {
        enrichFromLocalCache(out, checkTitle);
        applyNormalizedSessionTiming(out, meta);
        out.put("media_key", (pkg + ":" + out.optString("series_title") + ":" + out.optString("title")).toLowerCase());
        db.upsertWatchItem(out);
        SubtitleAndGuideStore.proactivelyCacheSeriesAsync(db, checkTitle, out.optInt("season", 0), out.optInt("episode", 0));
        if ("PLAYING".equals(out.optString("state"))) SystemResourceMonitor.maybeAutoGuardOnPlayback(ctx, db, pkg);
    }

    public static boolean isOrphanEpisodeCodeTitle(String seriesTitle, String title) {
        if (seriesTitle != null && !seriesTitle.trim().isEmpty()) return false;
        return title != null && title.trim().matches("(?i)^S\\d{1,2}\\s*[:·-]?\\s*E\\d{1,3}\\b.*");
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
        out.put("series_title", resolveSeriesFromMetadata(meta, t));
        out.put("poster_url", meta.optString("android.media.metadata.ALBUM_ART_URI"));
        if (t.equalsIgnoreCase(friendlyApp(pkg))) {
            hydrateSessionFromHistoryForPackage(out, pkg);
        } else if (out.optString("series_title").isEmpty()) {
            resolveMissingSeriesFromEpisodeTitle(out, pkg, t);
        }
    }

    private void resolveMissingSeriesFromEpisodeTitle(JSONObject out, String pkg, String title) throws Exception {
        tryResolveOrphanEpisodeCode(out, title);
        if (!out.optString("series_title").isEmpty()) return;
        String profile = out.optString("profile_id", AuraDb.DEFAULT_PROFILE);
        if (applyEpisodeMatchFromRows(out, db.listHistory(profile), pkg, title, true)) return;
        applyEpisodeMatchFromRows(out, queryTvProvider("content://android.media.tv/watch_next_program", true), pkg, title, false);
    }

    public static boolean applyEpisodeMatchFromRows(
            JSONObject out, JSONArray rows, String pkg, String epTitle, boolean isHistorySchema) throws Exception {
        if (rows == null || epTitle == null || epTitle.isEmpty()) return false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (matchesProviderOrHistoryEpisode(r, pkg, epTitle, isHistorySchema)) {
                copyMatchedSeriesEpisodeFields(out, r, isHistorySchema);
                return true;
            }
        }
        return false;
    }

    private static boolean matchesProviderOrHistoryEpisode(
            JSONObject r, String pkg, String epTitle, boolean isHistory) {
        if (r == null) return false;
        String rowPkg = r.optString(isHistory ? "app_pkg" : "package_name", "");
        if (pkg != null && !pkg.isEmpty() && !pkg.equals(rowPkg)) return false;
        String rowEp = r.optString(isHistory ? "title" : "episode_title", "").trim();
        String rowSeries = r.optString(isHistory ? "series_title" : "title", "").trim();
        return !rowSeries.isEmpty() && epTitle.trim().equalsIgnoreCase(rowEp);
    }

    private static void copyMatchedSeriesEpisodeFields(
            JSONObject out, JSONObject r, boolean isHistory) throws Exception {
        out.put("series_title", r.optString(isHistory ? "series_title" : "title", "").trim());
        int s = isHistory ? r.optInt("season", 0) : r.optInt("season_display_number", r.optInt("season_number", 0));
        int e = isHistory ? r.optInt("episode", 0) : r.optInt("episode_display_number", r.optInt("episode_number", 0));
        if (s > 0) out.put("season", s);
        if (e > 0) out.put("episode", e);
    }

    private void tryResolveOrphanEpisodeCode(JSONObject out, String title) throws Exception {
        Matcher m = EP_CODE_PAT.matcher(title != null ? title.trim() : "");
        if (!m.matches()) return;
        int season = Integer.parseInt(m.group(1));
        int episode = Integer.parseInt(m.group(2));
        String epName = m.group(3).trim();
        out.put("season", season).put("episode", episode);
        String matchedSeries = findParentSeriesForEpisode(out.optString("profile_id", AuraDb.DEFAULT_PROFILE), season, episode, epName);
        if (!matchedSeries.isEmpty()) out.put("series_title", matchedSeries);
    }

    private String findParentSeriesForEpisode(String profileId, int season, int episode, String epName) {
        JSONArray hist = db.listHistory(profileId);
        Set<String> checked = new HashSet<>();
        for (int i = 0; i < Math.min(10, hist.length()); i++) {
            String cand = extractSeriesCandidate(hist.optJSONObject(i));
            if (!cand.isEmpty() && checked.add(cand.toLowerCase()) && guideContainsEpisode(cand, season, episode, epName)) {
                return cand;
            }
        }
        return "";
    }

    private String extractSeriesCandidate(JSONObject h) {
        if (h == null) return "";
        String s = h.optString("series_title", "").trim();
        String t = h.optString("title", "").trim();
        String cand = s.isEmpty() ? t : s;
        return isOrphanEpisodeCodeTitle("", cand) ? "" : cand;
    }

    private boolean guideContainsEpisode(String seriesTitle, int season, int episode, String epName) {
        JSONObject guide = SubtitleAndGuideStore.getOrFetchShowGuide(db, seriesTitle, true);
        JSONArray eps = guide != null ? guide.optJSONArray("episodes") : null;
        if (eps == null) return false;
        for (int i = 0; i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e != null && e.optInt("s") == season && e.optInt("e") == episode
                    && e.optString("name", "").equalsIgnoreCase(epName)) return true;
        }
        return false;
    }

    private void hydrateSessionFromHistoryForPackage(JSONObject out, String pkg) throws Exception {
        JSONArray hist = db.listHistory(out.optString("profile_id", AuraDb.DEFAULT_PROFILE));
        for (int i = 0; i < hist.length(); i++) {
            JSONObject h = hist.optJSONObject(i);
            if (h != null && pkg.equals(h.optString("app_pkg")) && !h.optString("title").isEmpty()) {
                copyHistoryFieldsToActiveSession(out, h);
                return;
            }
        }
    }

    private void copyHistoryFieldsToActiveSession(JSONObject out, JSONObject h) throws Exception {
        out.put("title", h.optString("title"));
        out.put("series_title", h.optString("series_title"));
        out.put("synopsis", h.optString("synopsis"));
        out.put("poster_url", h.optString("poster_url"));
        out.put("launch_uri", h.optString("launch_uri"));
        if (out.optInt("position_sec", 0) < 30) out.put("position_sec", h.optInt("position_sec", 1200));
    }

    private String resolveSeriesFromMetadata(JSONObject meta, String title) {
        String series = sanitizeSeriesArtist(meta.optString("android.media.metadata.ARTIST"), title);
        if (series.isEmpty()) series = sanitizeSeriesArtist(meta.optString("android.media.metadata.ALBUM"), title);
        if (series.isEmpty()) series = sanitizeSeriesArtist(meta.optString("android.media.metadata.DISPLAY_SUBTITLE"), title);
        return series;
    }

    private String sanitizeSeriesArtist(String artist, String title) {
        if (artist == null || artist.contains("\u2022") || artist.contains(",") || artist.equalsIgnoreCase(title)) {
            return "";
        }
        return artist.trim();
    }

    private void enrichFromLocalCache(JSONObject out, String title) {
        try {
            String cached = db.getCachedGti("jw_imdb_v6:" + title.trim().toLowerCase());
            if (cached == null) return;
            applyCachedMetadataFields(out, new JSONObject(cached));
        } catch (Exception ignored) {}
    }

    private void applyCachedMetadataFields(JSONObject out, JSONObject c) throws Exception {
        if (out.optString("launch_uri").isEmpty()) out.put("launch_uri", c.optString("launch_uri"));
        if (out.optString("poster_url").isEmpty()) out.put("poster_url", c.optString("poster_url"));
        if (out.optString("genres").isEmpty()) out.put("genres", c.optString("genres"));
        if (out.optString("synopsis").isEmpty()) out.put("synopsis", c.optString("synopsis"));
        if (out.optInt("runtime_min", 0) <= 0 && c.optInt("runtime", 0) > 0) out.put("runtime_min", c.optInt("runtime"));
    }

    public static String friendlyApp(String pkg) {
        return RegionProviderRegistry.resolveFriendlyAppName(null, pkg);
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
        obj.put("position_ms", computeLivePositionMs(ps));
        obj.put("speed", ps.getPlaybackSpeed());
        if (ps.getLastPositionUpdateTime() > 0L) {
            long age = Math.max(0L, android.os.SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime());
            obj.put("age_since_update_ms", age);
        }
    }

    public static long computeLivePositionMs(PlaybackState ps) {
        if (ps == null) return 0L;
        long pos = Math.max(0L, ps.getPosition());
        if (ps.getState() == PlaybackState.STATE_PLAYING && ps.getLastPositionUpdateTime() > 0L) {
            long elapsed = android.os.SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime();
            if (elapsed > 0L && elapsed < 14_400_000L) pos += (long) (elapsed * Math.max(1.0f, ps.getPlaybackSpeed()));
        }
        return pos;
    }

    private static final String[] META_TEXT_KEYS = {
        "android.media.metadata.MEDIA_ID", "android.media.metadata.TITLE",
        "android.media.metadata.DISPLAY_TITLE", "android.media.metadata.ARTIST",
        "android.media.metadata.ALBUM", "android.media.metadata.DISPLAY_SUBTITLE",
        "android.media.metadata.ALBUM_ART_URI", "android.media.metadata.ART_URI",
        "com.amazon.alexa.externalmediaplayer.metadata.PLAYBACK_SOURCE_ID"
    };

    private JSONObject extractMetadata(MediaMetadata meta) throws Exception {
        JSONObject m = new JSONObject();
        if (meta == null) return m;
        for (String key : META_TEXT_KEYS) {
            CharSequence cs = meta.getText(key);
            if (cs != null) m.put(key, cs.toString());
        }
        long dur = meta.getLong("android.media.metadata.DURATION");
        if (dur > 0) m.put("android.media.metadata.DURATION", dur);
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
        boolean isPreview = uriStr.contains("preview_program");
        try (Cursor c = ctx.getContentResolver().query(Uri.parse(uriStr), null, null, null, "_id DESC")) {
            if (c != null) collectCursorRows(c, rows, seen, dedupByTitle, isPreview);
        } catch (Exception ignored) {}
        return rows;
    }

    private void collectCursorRows(Cursor c, JSONArray rows, Set<String> seen,
                                   boolean dedupByTitle, boolean isPreview) throws Exception {
        int pkgIdx = c.getColumnIndex("package_name");
        int intentIdx = c.getColumnIndex("intent_uri");
        Map<String, Integer> appCounts = new HashMap<>();
        int scanned = 0;
        while (c.moveToNext() && scanned++ < 400 && rows.length() < 48) {
            if (isPreview && !shouldKeepPreviewCursorRow(c, pkgIdx, intentIdx, appCounts)) continue;
            JSONObject row = cursorRowToJson(c);
            if (dedupByTitle && !seen.add(buildProviderRowKey(row))) continue;
            rows.put(row);
        }
    }

    private boolean shouldKeepPreviewCursorRow(Cursor c, int pkgIdx, int intentIdx, Map<String, Integer> appCounts) {
        String pkg = pkgIdx >= 0 ? c.getString(pkgIdx) : null;
        if (pkg == null || isExcludedProviderPkg(pkg)) return false;
        String intent = intentIdx >= 0 ? c.getString(intentIdx) : "";
        if (pkg.contains("netflix") && intent != null && intent.toLowerCase().contains("continuewatching")) return true;
        int count = appCounts.getOrDefault(pkg, 0);
        if (count >= 8) return false;
        appCounts.put(pkg, count + 1);
        return true;
    }

    private String buildProviderRowKey(JSONObject row) {
        return (row.optString("package_name") + "|" + row.optString("title")
            + "|" + row.optString("episode_title")).toLowerCase();
    }

    private JSONObject cursorRowToJson(Cursor c) throws Exception {
        JSONObject row = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++) {
            int t = c.getType(i);
            if (t == Cursor.FIELD_TYPE_STRING) putTrimmedColumnString(row, c.getColumnName(i), c.getString(i));
            else if (t == Cursor.FIELD_TYPE_INTEGER) row.put(c.getColumnName(i), c.getLong(i));
        }
        return row;
    }

    private void putTrimmedColumnString(JSONObject row, String col, String val) throws Exception {
        if (val != null && val.length() <= 1200) {
            row.put(col, val);
        }
    }
}
