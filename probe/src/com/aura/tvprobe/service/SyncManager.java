package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SyncManager {
    private final AuraDb db;

    public SyncManager(AuraDb db) {
        this.db = db;
    }

    public String getTargetHostUrl() {
        // 100% self-contained on-TV mode by default (no external MacBook or phone server required)
        String mode = db.getSetting("server_mode", "tv");
        if ("tv".equalsIgnoreCase(mode) || "auto".equalsIgnoreCase(mode)) return "";
        if ("phone".equalsIgnoreCase(mode)) return db.getSetting("phone_host", "");
        if ("mac".equalsIgnoreCase(mode)) return db.getSetting("mac_host", "");
        return "";
    }

    public boolean syncWithHost() {
        String host = getTargetHostUrl();
        if (host.isEmpty()) return false;
        try {
            String profile = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
            JSONObject payload = buildSyncPayload(profile);
            return postAndIngestSync(host, payload, profile);
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject buildSyncPayload(String profile) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("active_profile", profile);
        payload.put("active_filters", db.getSetting("filters_" + profile, ""));
        payload.put("watched_items", db.listManuallyWatched());
        payload.put("voice_session", db.getVoiceSession(profile));
        payload.put("history", db.listHistory(profile));
        payload.put("recommendations", db.getProfileRecommendations(profile));
        return payload;
    }

    private boolean postAndIngestSync(String host, JSONObject payload, String profile) throws Exception {
        HttpURLConnection conn = openSyncConnection(host);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (conn.getResponseCode() != 200) return false;
        ingestRemoteSync(new JSONObject(readConnectionBody(conn)), profile);
        return true;
    }

    private HttpURLConnection openSyncConnection(String host) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(host + "/api/sync").openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(3500);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        return conn;
    }

    private String readConnectionBody(HttpURLConnection conn) throws Exception {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    public void ingestRemoteSync(JSONObject remote, String profile) {
        try {
            ingestProfileLabels(remote.optJSONObject("profile_labels"));
            ingestWatchedItems(remote.optJSONArray("watched_items"));
            ingestRemoteHistory(remote.optJSONArray("history"), profile);
            ingestRemoteRecommendations(remote.optJSONArray("recommendations"), profile);
            ingestRemoteVoiceSession(remote.optJSONObject("voice_session"), profile);
        } catch (Exception ignored) {}
    }

    private void ingestProfileLabels(JSONObject labels) {
        if (labels == null) return;
        for (String k : new String[]{"member1", "partner", "member3", "kids", "family"}) {
            String v = labels.optString(k);
            if (!v.isEmpty()) db.setSetting("profile_name_" + k, v);
        }
    }

    private void ingestWatchedItems(JSONArray watched) {
        if (watched == null) return;
        for (int i = 0; i < watched.length(); i++) {
            JSONObject w = watched.optJSONObject(i);
            if (w != null) db.markTitleWatched(w, true);
        }
    }

    private void ingestRemoteHistory(JSONArray hist, String profile) {
        if (hist == null || db.listHistory(profile).length() >= 10) return;
        android.database.sqlite.SQLiteDatabase wdb = db.getWritableDatabase();
        wdb.beginTransaction();
        try {
            upsertEligibleHistoryItems(hist);
            wdb.setTransactionSuccessful();
        } finally {
            wdb.endTransaction();
        }
        db.enforceStorageCap();
    }

    private void upsertEligibleHistoryItems(JSONArray hist) {
        for (int i = 0; i < hist.length(); i++) {
            JSONObject item = hist.optJSONObject(i);
            if (isEligibleHistoryItem(item)) db.upsertWatchItem(item);
        }
    }

    private boolean isEligibleHistoryItem(JSONObject item) {
        if (item == null || item.optInt("position_sec", 0) < 60) return false;
        String pkg = item.optString("app_pkg", "").toLowerCase();
        return !pkg.contains("youtube") && !pkg.contains("smarttube") && !pkg.contains("stremio");
    }

    private void ingestRemoteRecommendations(JSONArray recos, String profile) {
        if (recos == null || recos.length() == 0 || db.getProfileRecommendations(profile).length() > 0) return;
        JSONArray validModern = filterModernRecommendations(recos);
        if (validModern.length() >= 8) {
            db.saveProfileRecommendations(profile, validModern);
        }
    }

    private JSONArray filterModernRecommendations(JSONArray recos) {
        int minYear = JustWatchGraph.getDefaultMinModernYear();
        int maxYear = JustWatchGraph.getCurrentYear();
        JSONArray validModern = new JSONArray();
        for (int i = 0; i < recos.length(); i++) {
            JSONObject r = recos.optJSONObject(i);
            int y = r != null ? r.optInt("year", maxYear) : -1;
            if (r != null && y >= minYear && y <= maxYear) validModern.put(r);
        }
        return validModern;
    }

    private void ingestRemoteVoiceSession(JSONObject vs, String profile) {
        if (vs == null) return;
        JSONArray remoteRes = vs.optJSONArray("results");
        JSONArray localRes = db.getVoiceSession(profile).optJSONArray("results");
        if (remoteRes != null && remoteRes.length() > 0 && (localRes == null || localRes.length() == 0)) {
            db.saveVoiceSession(profile, vs);
        }
    }
}
