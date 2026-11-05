package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class VoiceConcierge {
    private final AuraDb db;
    private final AiRecommender ai;
    private final SyncManager sync;

    public VoiceConcierge(AuraDb db, AiRecommender ai, SyncManager sync) {
        this.db = db;
        this.ai = ai;
        this.sync = sync;
    }

    public synchronized JSONObject handleTurn(String profileId, String userText, boolean reset) {
        if (reset) {
            db.setSetting("voice_session_" + profileId, "");
            new Thread(sync::syncWithHost).start();
            return db.getVoiceSession(profileId);
        }

        // Push latest watched shows, filters, and profile to host server first
        sync.syncWithHost();

        List<String> activeFilters = db.getActiveFilters(profileId);
        String effectiveQuery = (userText == null || userText.trim().isEmpty())
            ? ("Apply filters: " + activeFilters.toString())
            : userText.trim();

        JSONObject localRes = runLocalConversationalTurn(profileId, effectiveQuery, activeFilters);
        if (localRes != null && localRes.optJSONArray("results") != null && localRes.optJSONArray("results").length() > 0) {
            new Thread(sync::syncWithHost).start();
            return localRes;
        }

        String host = sync.getTargetHostUrl();
        if (!host.isEmpty()) {
            JSONObject remoteRes = tryHostServerChat(host, profileId, effectiveQuery);
            if (remoteRes != null && remoteRes.optJSONArray("results") != null) {
                db.saveVoiceSession(profileId, remoteRes);
                return db.getVoiceSession(profileId);
            }
        }
        return localRes;
    }

    private JSONObject tryHostServerChat(String host, String profileId, String userText) {
        try {
            JSONObject req = new JSONObject();
            req.put("profile", profileId);
            req.put("message", userText);
            HttpURLConnection conn = (HttpURLConnection) new URL(host + "/api/chat").openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) return null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return new JSONObject(sb.toString());
            }
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject runLocalConversationalTurn(String profileId, String userText, List<String> activeFilters) {
        JSONObject session = db.getVoiceSession(profileId);
        JSONArray turns = session.optJSONArray("turns");
        if (turns == null) turns = new JSONArray();

        List<String> watchedNames = collectWatchedNames(profileId);
        String prompt = buildPrompt(profileId, turns, userText, activeFilters, watchedNames);
        String rawJson = ai.callGeminiJson(prompt);

        JSONObject out = new JSONObject();
        try {
            turns.put(new JSONObject().put("role", "user").put("text", userText));
            if (rawJson != null) {
                JSONObject parsed = new JSONObject(rawJson);
                String reply = parsed.optString("reply", "Updated picks for your session.");
                turns.put(new JSONObject().put("role", "assistant").put("text", reply));
                out.put("reply", reply);
                out.put("turns", trimTurns(turns, 8));
                JSONArray enriched = ai.enrichAll(parsed.optJSONArray("results"));
                out.put("results", db.filterOutWatched(enriched));
                JSONArray followUps = parsed.optJSONArray("follow_ups");
                out.put("follow_ups", (followUps != null && followUps.length() > 0) ? followUps : defaultFollowUps());
            } else {
                String reply = "Matched '" + userText + "' using local graph.";
                turns.put(new JSONObject().put("role", "assistant").put("text", reply));
                out.put("reply", reply);
                out.put("turns", trimTurns(turns, 8));
                out.put("results", db.getProfileRecommendations(profileId));
                out.put("follow_ups", defaultFollowUps());
            }
            db.saveVoiceSession(profileId, out);
        } catch (Exception ignored) {}
        return db.getVoiceSession(profileId);
    }

    private String buildPrompt(String profileId, JSONArray turns, String userText, List<String> filters, List<String> watched) {
        StringBuilder conv = new StringBuilder();
        for (int i = 0; i < turns.length(); i++) {
            JSONObject t = turns.optJSONObject(i);
            if (t != null) conv.append(t.optString("role")).append(": ").append(t.optString("text")).append("\n");
        }
        return "You are Aura, a concise IMDb-style movie & TV recommendation engine for profile '" + profileId + "'.\n" +
            "STRICT EXCLUSION LIST — Already watched (NEVER recommend any of these): " + watched.toString() + "\n" +
            "Active Persistent Filters (MUST obey): " + (filters.isEmpty() ? "None" : filters.toString()) + "\n" +
            "Session Flow so far:\n" + conv.toString() +
            "New User Input: \"" + userText + "\"\n\n" +
            "Return a JSON object with:\n" +
            "1. 'reply': Short 1-sentence summary of what was matched.\n" +
            "2. 'results': Array of 10 unwatched titles matching the session & filters, each with keys: 'title', 'app' (exact streaming service in India: 'Disney+ Hotstar', 'Netflix', 'Prime Video', 'Apple TV+', 'SonyLIV', or 'ZEE5'), 'genres', 'runtime_min' (integer), 'rating' (IMDb rating like '★ 8.3'), and 'reason' (max 14 words).\n" +
            "3. 'follow_ups': Array of 4 short 2-4 word refinement chips for the next step (e.g. 'Under 100 mins', 'More twist endings', 'Darker tone', '90s/2000s classics').";
    }

    private List<String> collectWatchedNames(String profileId) {
        List<String> list = new ArrayList<>();
        JSONArray manual = db.listManuallyWatched();
        for (int i = 0; i < manual.length(); i++) {
            JSONObject m = manual.optJSONObject(i);
            if (m != null) {
                String t = m.optString("series_title").isEmpty() ? m.optString("title") : m.optString("series_title");
                if (!t.isEmpty() && !list.contains(t)) list.add(t);
            }
        }
        JSONArray hist = db.listHistory(profileId);
        for (int i = 0; i < hist.length() && list.size() < 45; i++) {
            JSONObject h = hist.optJSONObject(i);
            if (h == null) continue;
            String t = h.optString("series_title").isEmpty() ? h.optString("title") : h.optString("series_title");
            if (!t.isEmpty() && !list.contains(t)) list.add(t);
        }
        return list;
    }

    private JSONArray trimTurns(JSONArray turns, int max) {
        JSONArray out = new JSONArray();
        int start = Math.max(0, turns.length() - max);
        for (int i = start; i < turns.length(); i++) out.put(turns.optJSONObject(i));
        return out;
    }

    private JSONArray defaultFollowUps() {
        JSONArray arr = new JSONArray();
        arr.put("Under 100 mins");
        arr.put("Mind-bending plot twists");
        arr.put("High IMDb rating (8.0+)");
        arr.put("Darker thriller tone");
        return arr;
    }
}
