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

    public JSONObject inspectAll() {
        JSONObject root = new JSONObject();
        try {
            root.put("foreground_pkg", getForegroundPackage());
            root.put("sessions", getMediaSessions());
            root.put("channels", queryTvProvider("content://android.media.tv/channel", false));
            root.put("watch_next", queryTvProvider("content://android.media.tv/watch_next_program", true));
            root.put("preview_programs", queryTvProvider("content://android.media.tv/preview_program", true));
        } catch (Exception e) {
            try { root.put("error", e.toString()); } catch (Exception ignored) {}
        }
        return root;
    }

    public JSONObject buildFastCachedState() {
        String profile = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
        JSONArray history = db.listHistory(profile);
        JSONArray recos = db.getProfileRecommendations(profile);
        JSONObject nowPlaying = history.length() > 0 ? history.optJSONObject(0) : new JSONObject();
        JSONObject out = new JSONObject();
        try {
            out.put("online", true);
            out.put("active_profile", profile);
            out.put("profile_labels", db.getProfileLabelsJson());
            out.put("server_mode", db.getSetting("server_mode", "auto"));
            out.put("host_url", sync.getTargetHostUrl());
            out.put("cache_age_sec", db.getRecoCacheAgeSec(profile));
            out.put("now_playing", nowPlaying);
            out.put("recommendations", recos);
            out.put("watched_items", db.listManuallyWatched());
            out.put("voice_session", db.getVoiceSession(profile));
            out.put("active_filters", new JSONArray(db.getActiveFilters(profile)));
            out.put("history", history);
            out.put("history_count", history.length());
        } catch (Exception ignored) {}
        return out;
    }

    public JSONObject syncAndBuildState(boolean forceAiRefresh) {
        String profile = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
        long nowMs = System.currentTimeMillis();
        JSONObject raw;
        if (forceAiRefresh || (nowMs - lastProviderSyncMs) > 60000L || db.listHistory(profile).length() == 0) {
            raw = inspectAll();
            syncProviderToLocalDb(raw);
            db.enforceStorageCap();
            lastProviderSyncMs = nowMs;
        } else {
            raw = new JSONObject();
            try {
                raw.put("foreground_pkg", getForegroundPackage());
                raw.put("sessions", getMediaSessions());
            } catch (Exception ignored) {}
        }

        JSONObject nowPlaying = resolveNowPlaying(raw, profile);
        JSONArray history = db.listHistory(profile);
        boolean wasFresh = db.isRecoCacheFresh(profile);
        JSONArray recos = ai.getOrRefreshForProfile(profile, nowPlaying, history, forceAiRefresh);
        if (forceAiRefresh || !wasFresh) {
            new TvChannelPublisher(ctx).publishRecommendations(recos);
        }

        JSONObject out = new JSONObject();
        try {
            out.put("online", true);
            out.put("active_profile", profile);
            out.put("server_mode", db.getSetting("server_mode", "auto"));
            out.put("host_url", sync.getTargetHostUrl());
            out.put("cache_age_sec", db.getRecoCacheAgeSec(profile));
            out.put("foreground_pkg", raw.optString("foreground_pkg"));
            out.put("now_playing", nowPlaying);
            out.put("recommendations", recos);
            out.put("watched_items", db.listManuallyWatched());
            out.put("voice_session", db.getVoiceSession(profile));
            out.put("active_filters", new JSONArray(db.getActiveFilters(profile)));
            out.put("history", history);
            out.put("history_count", history.length());
        } catch (Exception ignored) {}
        return out;
    }

    private void syncProviderToLocalDb(JSONObject raw) {
        Map<Long, String> chMap = new HashMap<>();
        JSONArray chs = raw.optJSONArray("channels");
        if (chs != null) {
            for (int i = 0; i < chs.length(); i++) {
                JSONObject c = chs.optJSONObject(i);
                if (c != null) chMap.put(c.optLong("_id"), c.optString("display_name"));
            }
        }
        android.database.sqlite.SQLiteDatabase wdb = db.getWritableDatabase();
        wdb.beginTransaction();
        try {
            ingestRows(raw.optJSONArray("preview_programs"), chMap, "");
            ingestRows(raw.optJSONArray("watch_next"), chMap, "Watch Next");
            wdb.setTransactionSuccessful();
        } finally {
            wdb.endTransaction();
        }
    }

    private void ingestRows(JSONArray rows, Map<Long, String> chMap, String defaultCh) {
        if (rows == null) return;
        for (int i = rows.length() - 1; i >= 0; i--) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String pkg = r.optString("package_name");
            if (ctx.getPackageName().equals(pkg) || pkg.contains("youtube") || pkg.contains("smarttube")) continue;
            String ch = defaultCh.isEmpty() ? chMap.getOrDefault(r.optLong("channel_id"), "") : defaultCh;
            int posSec = (int) (r.optLong("last_playback_position_millis", 0) / 1000L);
            if (posSec < 60) continue;

            String title = r.optString("title", "Program");
            String epTitle = r.optString("episode_title", "");
            if (title.equalsIgnoreCase(friendlyApp(pkg))) continue;
            int durSec = (int) (r.optLong("duration_millis", 0) / 1000L);
            JSONObject item = new JSONObject();
            try {
                item.put("media_key", (pkg + ":" + title + ":" + epTitle).toLowerCase());
                item.put("app_pkg", pkg);
                item.put("app_name", friendlyApp(pkg) + (ch.isEmpty() ? "" : " · " + ch));
                item.put("title", epTitle.isEmpty() ? title : epTitle);
                item.put("series_title", epTitle.isEmpty() ? "" : title);
                item.put("season", r.optInt("season_number", 0));
                item.put("episode", r.optInt("episode_number", 0));
                item.put("synopsis", r.optString("short_description"));
                item.put("poster_url", r.optString("poster_art_uri"));
                item.put("runtime_min", durSec > 0 ? Math.max(1, durSec / 60) : 45);
                item.put("launch_uri", r.optString("intent_uri"));
                item.put("position_sec", posSec);
                item.put("duration_sec", durSec);
                db.upsertWatchItem(item);
            } catch (Exception ignored) {}
        }
    }

    private JSONObject resolveNowPlaying(JSONObject raw, String profile) {
        JSONArray sessions = raw.optJSONArray("sessions");
        if (sessions != null && sessions.length() > 0) {
            JSONObject s = sessions.optJSONObject(0);
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject cand = sessions.optJSONObject(i);
                if (cand != null && cand.optInt("state") == 3) { s = cand; break; }
            }
            if (s != null) return sessionToNowPlaying(s, profile);
        }
        JSONArray hist = db.listHistory(profile);
        if (hist.length() > 0) {
            JSONObject last = hist.optJSONObject(0);
            try { last.put("state", "LAST WATCHED"); } catch (Exception ignored) {}
            return last;
        }
        return new JSONObject();
    }

    private JSONObject sessionToNowPlaying(JSONObject s, String profile) {
        JSONObject out = new JSONObject();
        try {
            String pkg = s.optString("package");
            JSONObject meta = s.optJSONObject("metadata");
            if (meta == null) meta = new JSONObject();
            String mediaId = meta.optString("android.media.metadata.MEDIA_ID",
                meta.optString("com.amazon.alexa.externalmediaplayer.metadata.PLAYBACK_SOURCE_ID", ""));
            out.put("profile_id", profile);
            out.put("app_pkg", pkg);
            out.put("app_name", friendlyApp(pkg));
            out.put("state", s.optInt("state") == 3 ? "PLAYING" : "PAUSED");
            out.put("position_sec", (int) (s.optLong("position_ms", 0) / 1000L));
            long durMs = meta.optLong("android.media.metadata.DURATION", -1);
            out.put("duration_sec", durMs > 0 ? (int) (durMs / 1000L) : 3600);

            if (mediaId.startsWith("amzn1.dv.gti.")) {
                JSONObject p = ai.resolvePrimeGti(mediaId);
                out.put("series_title", p.optString("series_title"));
                out.put("title", p.optString("title"));
                out.put("episode", p.optInt("episode"));
                out.put("season", p.optInt("season", 1));
                out.put("synopsis", p.optString("synopsis"));
                out.put("poster_url", p.optString("poster_url"));
                out.put("genres", p.optString("genres"));
                out.put("runtime_min", p.optInt("runtime_min", 60));
            } else {
                String t = meta.optString("android.media.metadata.TITLE", meta.optString("android.media.metadata.DISPLAY_TITLE", friendlyApp(pkg)));
                out.put("title", t);
                out.put("series_title", meta.optString("android.media.metadata.ARTIST"));
                out.put("poster_url", meta.optString("android.media.metadata.ALBUM_ART_URI"));
            }
            String checkTitle = out.optString("series_title").isEmpty() ? out.optString("title") : out.optString("series_title");
            if (!checkTitle.isEmpty() && !checkTitle.equalsIgnoreCase(friendlyApp(pkg))
                    && !pkg.contains("youtube") && !pkg.contains("smarttube")) {
                out.put("media_key", (pkg + ":" + out.optString("series_title") + ":" + out.optString("title")).toLowerCase());
                db.upsertWatchItem(out);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static String friendlyApp(String pkg) {
        if (pkg.contains("amazon")) return "Prime Video";
        if (pkg.contains("netflix")) return "Netflix";
        if (pkg.contains("smarttube")) return "SmartTube";
        if (pkg.contains("youtube")) return "YouTube";
        if (pkg.contains("hotstar")) return "Disney+ Hotstar";
        if (pkg.contains("appletv")) return "Apple TV+";
        if (pkg.contains("xstream")) return "Airtel Xstream";
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
        PlaybackState ps = mc.getPlaybackState();
        if (ps != null) {
            obj.put("state", ps.getState());
            obj.put("position_ms", ps.getPosition());
            obj.put("speed", ps.getPlaybackSpeed());
        }
        obj.put("metadata", extractMetadata(mc.getMetadata()));
        obj.put("extras", extractBundle(mc.getExtras()));
        return obj;
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
            UsageStats recent = null;
            for (UsageStats s : stats) {
                if (recent == null || s.getLastTimeUsed() > recent.getLastTimeUsed()) recent = s;
            }
            return recent != null ? recent.getPackageName() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private JSONArray queryTvProvider(String uriStr, boolean dedupByTitle) {
        JSONArray rows = new JSONArray();
        Set<String> seen = new HashSet<>();
        try (Cursor c = ctx.getContentResolver().query(Uri.parse(uriStr), null, null, null, "_id DESC")) {
            if (c == null) return rows;
            while (c.moveToNext()) {
                JSONObject row = cursorRowToJson(c);
                if (dedupByTitle) {
                    String key = (row.optString("package_name") + "|" + row.optString("title") + "|" + row.optString("episode_title")).toLowerCase();
                    if (!seen.add(key)) continue;
                }
                rows.put(row);
            }
        } catch (Exception ignored) {}
        return rows;
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
