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
        String mode = db.getSetting("server_mode", "auto");
        if ("tv".equals(mode)) return "";
        if ("phone".equals(mode)) return db.getSetting("phone_host", "");
        return db.getSetting("mac_host", "");
    }

    public boolean syncWithHost() {
        String host = getTargetHostUrl();
        if (host.isEmpty()) return false;
        try {
            String profile = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
            JSONObject payload = new JSONObject();
            payload.put("active_profile", profile);
            payload.put("active_filters", db.getSetting("filters_" + profile, ""));
            payload.put("watched_items", db.listManuallyWatched());
            payload.put("voice_session", db.getVoiceSession(profile));
            payload.put("history", db.listHistory(profile));
            payload.put("recommendations", db.getProfileRecommendations(profile));

            HttpURLConnection conn = (HttpURLConnection) new URL(host + "/api/sync").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3500);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) return false;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                ingestRemoteSync(new JSONObject(sb.toString()), profile);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void ingestRemoteSync(JSONObject remote, String profile) {
        try {
            JSONObject labels = remote.optJSONObject("profile_labels");
            if (labels != null) {
                for (String k : new String[]{"member1", "partner", "member3", "kids", "family"}) {
                    String v = labels.optString(k);
                    if (!v.isEmpty()) db.setSetting("profile_name_" + k, v);
                }
            }
            JSONArray watched = remote.optJSONArray("watched_items");
            if (watched != null) {
                for (int i = 0; i < watched.length(); i++) {
                    JSONObject w = watched.optJSONObject(i);
                    if (w != null) db.markTitleWatched(w, true);
                }
            }
            JSONArray hist = remote.optJSONArray("history");
            if (hist != null && db.listHistory(profile).length() < 20) {
                android.database.sqlite.SQLiteDatabase wdb = db.getWritableDatabase();
                wdb.beginTransaction();
                try {
                    for (int i = 0; i < hist.length(); i++) {
                        JSONObject item = hist.optJSONObject(i);
                        if (item != null) db.upsertWatchItem(item);
                    }
                    wdb.setTransactionSuccessful();
                } finally {
                    wdb.endTransaction();
                }
                db.enforceStorageCap();
            }
            JSONArray recos = remote.optJSONArray("recommendations");
            if (recos != null && recos.length() > 0 && db.getProfileRecommendations(profile).length() == 0) {
                db.saveProfileRecommendations(profile, recos);
            }
            JSONObject vs = remote.optJSONObject("voice_session");
            JSONArray localRes = db.getVoiceSession(profile).optJSONArray("results");
            if (vs != null && vs.optJSONArray("results") != null && vs.optJSONArray("results").length() > 0
                && (localRes == null || localRes.length() == 0)) {
                db.saveVoiceSession(profile, vs);
            }
        } catch (Exception ignored) {}
    }
}
