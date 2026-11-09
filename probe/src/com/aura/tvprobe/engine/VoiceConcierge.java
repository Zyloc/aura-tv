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
        return handleTurn(profileId, userText, reset, null);
    }

    public synchronized JSONObject handleTurn(
        String profileId,
        String userText,
        boolean reset,
        AiRecommender.AgentProgressListener progress
    ) {
        if (reset) {
            db.setSetting("voice_session_" + profileId, "");
            new Thread(sync::syncWithHost).start();
            return db.getVoiceSession(profileId);
        }

        sync.syncWithHost();

        List<String> activeFilters = db.getActiveFilters(profileId);
        String effectiveQuery = (userText == null || userText.trim().isEmpty())
            ? ("Apply filters: " + activeFilters.toString())
            : userText.trim();

        JSONObject localRes = runAgenticSearchTurn(profileId, effectiveQuery, activeFilters, progress);
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

    private JSONObject runAgenticSearchTurn(
        String profileId,
        String userText,
        List<String> activeFilters,
        AiRecommender.AgentProgressListener progress
    ) {
        JSONObject session = db.getVoiceSession(profileId);
        JSONArray turns = session.optJSONArray("turns");
        if (turns == null) turns = new JSONArray();

        int currentYear = JustWatchGraph.getCurrentYear();
        int minModernYear = JustWatchGraph.getDefaultMinModernYear();
        List<String> watchedNames = collectWatchedNames(profileId);
        String contextualQuery = buildContextualQuery(turns, userText);

        // Agent Step 1: Fast Seed & Watch-History Anchor Plan
        if (progress != null) progress.onStep("Searching across your streaming apps for \"" + userText + "\"\u2026");
        JSONObject searchPlan = ai.buildFastSeedPlan(profileId, contextualQuery);

        // Agent Step 2: Execute multi-hop search & co-watched graph traversal
        if (progress != null) progress.onStep("Traversing " + minModernYear + "\u2013" + currentYear + " streaming graph\u2026");
        List<String> cfPool = ai.executeMultiHopTraversal(profileId, searchPlan, minModernYear);

        // Agent Step 3: Rank & synthesize search results + next-hop traversal chips
        if (progress != null) progress.onStep("AI ranking top matches for your conversation\u2026");
        String prompt = buildPrompt(profileId, turns, userText, activeFilters, watchedNames, cfPool, minModernYear, currentYear);
        String rawJson = ai.callGeminiJson(prompt);

        JSONObject out = new JSONObject();
        try {
            turns.put(new JSONObject().put("role", "user").put("text", userText));
            JSONArray finalResults = new JSONArray();
            String reply = "Curated top matches across your streaming apps.";
            JSONArray followUps = defaultFollowUps(currentYear);

            if (rawJson != null) {
                JSONObject parsed = new JSONObject(rawJson);
                reply = parsed.optString("reply", "Updated picks for your search.");
                JSONArray enriched = ai.enrichAll(parsed.optJSONArray("results"), minModernYear, currentYear);
                finalResults = applyConversationalConstraints(db.filterOutWatched(enriched), userText, activeFilters);
                JSONArray parsedFollowUps = parsed.optJSONArray("follow_ups");
                if (parsedFollowUps != null && parsedFollowUps.length() > 0) {
                    followUps = parsedFollowUps;
                }
            }

            if (finalResults.length() < 6) {
                JSONArray sotaFallback = buildDeterministicSotaSearch(profileId, userText, contextualQuery, minModernYear);
                sotaFallback = applyConversationalConstraints(db.filterOutWatched(sotaFallback), userText, activeFilters);
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < finalResults.length(); i++) {
                    JSONObject item = finalResults.optJSONObject(i);
                    if (item != null) seen.add(AuraDb.normalizeTitleKey(item.optString("title", "")));
                }
                for (int i = 0; i < sotaFallback.length() && finalResults.length() < 16; i++) {
                    JSONObject item = sotaFallback.optJSONObject(i);
                    if (item == null) continue;
                    String key = AuraDb.normalizeTitleKey(item.optString("title", ""));
                    if (!key.isEmpty() && seen.add(key)) {
                        finalResults.put(item);
                    }
                }
                if (rawJson == null) {
                    reply = "Ranked top matches for \"" + userText + "\" by taste similarity & Bayesian IMDb.";
                }
            }

            turns.put(new JSONObject().put("role", "assistant").put("text", reply));
            out.put("reply", reply);
            out.put("turns", trimTurns(turns, 8));
            out.put("results", finalResults);
            db.saveTraversalChips(profileId, followUps);
            out.put("follow_ups", followUps);
            db.saveVoiceSession(profileId, out);
        } catch (Exception ignored) {}
        return db.getVoiceSession(profileId);
    }

    private JSONArray buildDeterministicSotaSearch(String profileId, String userText, String contextualQuery, int minModernYear) {
        String low = userText == null ? "" : userText.toLowerCase();
        if (low.startsWith("more like ") || low.startsWith("similar to ")) {
            String seed = userText.replaceFirst("(?i)^(more like|similar to)\\s+", "").trim();
            if (!seed.isEmpty()) {
                JSONArray sim = ai.traverseSimilarToWatched(profileId, seed, null);
                if (sim != null && sim.length() > 0) return sim;
            }
        }
        List<String> genres = new ArrayList<>();
        if (low.contains("sci-fi") || low.contains("scifi") || low.contains("science")) genres.add("scf");
        if (low.contains("thriller") || low.contains("suspense") || low.contains("mystery") || low.contains("dark")) genres.add("trl");
        if (low.contains("crime") || low.contains("detective")) genres.add("crm");
        if (low.contains("action") || low.contains("fast")) genres.add("act");
        if (low.contains("horror") || low.contains("scary")) genres.add("hrr");
        if (low.contains("comedy") || low.contains("funny")) genres.add("cmy");
        if (low.contains("romance") || low.contains("romantic")) genres.add("rma");
        if (low.contains("drama")) genres.add("drm");
        if (low.contains("anime") || low.contains("animation")) genres.add("ani");

        List<JSONObject> pool = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (!genres.isEmpty()) {
            JSONArray structured = JustWatchGraph.fetchStructuredGenreCandidates(genres, null, Math.max(minModernYear, JustWatchGraph.getCurrentYear() - 3), JustWatchGraph.getCurrentYear());
            for (int i = 0; i < structured.length(); i++) {
                JSONObject o = structured.optJSONObject(i);
                if (o != null && seen.add(AuraDb.normalizeTitleKey(o.optString("title", "")))) pool.add(o);
            }
        }
        JSONArray direct = JustWatchGraph.fetchDeterministicSearchCandidates(contextualQuery, minModernYear);
        for (int i = 0; i < direct.length(); i++) {
            JSONObject o = direct.optJSONObject(i);
            if (o != null && seen.add(AuraDb.normalizeTitleKey(o.optString("title", "")))) pool.add(o);
        }
        pool.sort((a, b) -> Double.compare(
            JustWatchGraph.computeBayesianImdb(b),
            JustWatchGraph.computeBayesianImdb(a)
        ));
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(16, pool.size()); i++) out.put(pool.get(i));
        return out.length() > 0 ? out : db.getProfileRecommendations(profileId);
    }

    private JSONArray applyConversationalConstraints(JSONArray items, String userText, List<String> activeFilters) {
        if (items == null || items.length() == 0) return new JSONArray();
        String combined = ((userText == null ? "" : userText) + " " + (activeFilters == null ? "" : activeFilters.toString())).toLowerCase();
        boolean filterNetflix = combined.contains("netflix");
        boolean filterPrime = combined.contains("prime");
        boolean filterHotstar = combined.contains("hotstar") || combined.contains("jio");
        boolean filterApple = combined.contains("apple");
        boolean hasPlatformConstraint = filterNetflix || filterPrime || filterHotstar || filterApple;
        boolean underTwoHours = combined.contains("under 2 hours") || combined.contains("shorter") || combined.contains("< 120");

        JSONArray filtered = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (hasPlatformConstraint) {
                String app = item.optString("app", "").toLowerCase();
                boolean match = (filterNetflix && app.contains("netflix"))
                    || (filterPrime && app.contains("prime"))
                    || (filterHotstar && (app.contains("hotstar") || app.contains("jio")))
                    || (filterApple && app.contains("apple"));
                if (!match) continue;
            }
            if (underTwoHours) {
                int rt = item.optInt("runtime_min", 100);
                if (rt > 125) continue;
            }
            filtered.put(item);
        }
        return filtered.length() >= 3 ? filtered : items;
    }

    private String buildContextualQuery(JSONArray turns, String currentQuery) {
        if (turns == null || turns.length() == 0) return currentQuery;
        List<String> userQueries = new ArrayList<>();
        for (int i = 0; i < turns.length(); i++) {
            JSONObject t = turns.optJSONObject(i);
            if (t != null && "user".equalsIgnoreCase(t.optString("role"))) {
                String txt = t.optString("text", "").trim();
                if (!txt.isEmpty()) userQueries.add(txt);
            }
        }
        if (userQueries.isEmpty()) return currentQuery;
        String firstTopic = userQueries.get(0);
        String lastTopic = userQueries.get(userQueries.size() - 1);
        if (firstTopic.equalsIgnoreCase(currentQuery)) return currentQuery;
        if (!firstTopic.equalsIgnoreCase(lastTopic)) {
            return firstTopic + " -> " + lastTopic + " -> Follow-up: " + currentQuery;
        }
        return firstTopic + " -> Follow-up: " + currentQuery;
    }

    private String buildPrompt(
        String profileId,
        JSONArray turns,
        String userText,
        List<String> filters,
        List<String> watched,
        List<String> cfPool,
        int minModernYear,
        int currentYear
    ) {
        StringBuilder conv = new StringBuilder();
        for (int i = 0; i < turns.length(); i++) {
            JSONObject t = turns.optJSONObject(i);
            if (t != null) conv.append(t.optString("role")).append(": ").append(t.optString("text")).append("\n");
        }
        String personalGuide = db.getProfileTaste(profileId);
        String defaultGuide = db.getDefaultTasteGuide();
        String todayDate = JustWatchGraph.getCurrentDateString();
        int recentPriorityStart = Math.max(minModernYear, currentYear - 5);
        return "You are Aura's Agentic Search & Recommendation Engine for profile '" + db.getProfileLabel(profileId) + "'.\n" +
            "Current TV System Date: " + todayDate + " (any title released up to " + todayDate + " in " + currentYear + " is valid; " + (currentYear + 1) + "+ are unreleased).\n" +
            "Personal Guide AI (for this profile): " + (personalGuide.isEmpty() ? "(None)" : personalGuide) + "\n" +
            "Default Guide AI (global baseline): " + defaultGuide + "\n" +
            "STRICT EXCLUSION LIST — Already watched (NEVER recommend any of these): " + watched.toString() + "\n" +
            "Multi-Hop Search & Co-Watch Graph Traversal Candidates (" + minModernYear + "–" + currentYear + "): " + cfPool.toString() + "\n" +
            "Active Persistent Filters (MUST obey): " + (filters.isEmpty() ? "None" : filters.toString()) + "\n" +
            "Session Flow so far:\n" + conv.toString() +
            "User Search / Traversal Query: \"" + userText + "\"\n\n" +
            "STRICT RULES:\n" +
            "- MULTI-TURN CONVERSATION: If 'Session Flow so far' has previous user turns, treat the current query as a FOLLOW-UP REFINEMENT on that conversation (e.g. narrowing by platform, runtime, mood, rating, or pivoting from a recommended show).\n" +
            "- If the user says they watched a title (e.g. 'watched X' or 'similar to X'), treat this as a second-hop traversal query and recommend modern titles similar to X.\n" +
            "- MODERN RECENCY WINDOW: ONLY recommend released titles from " + minModernYear + " to " + currentYear + " (strongly prioritize " + recentPriorityStart + "–" + currentYear + ", including recent releases up to " + todayDate + "). Do NOT recommend old 1980s, 1990s, 2000s, or early-2010s titles unless explicitly requested.\n" +
            "- NEVER recommend unreleased " + (currentYear + 1) + "+ titles.\n" +
            "Return a JSON object with:\n" +
            "1. 'reply': Short 1-sentence conversational summary explaining how you answered or refined the previous search.\n" +
            "2. 'results': Array of 14 unwatched modern (" + minModernYear + "–" + currentYear + ") titles streaming in India ('Disney+ Hotstar', 'Netflix', 'Prime Video', 'Apple TV+', 'SonyLIV', or 'ZEE5'), each with keys: 'title', 'app', 'genres', 'runtime_min' (integer), 'rating' (IMDb rating like '★ 8.3'), and 'reason' (18-26 words explaining why this fits their query & follow-up).\n" +
            "3. 'follow_ups': Array of 4 short conversational follow-up refinements (max 26 chars each, e.g. 'Only on Netflix & Prime', 'Shorter under 2 hours', 'Darker & grittier tone', 'More like <Top Result>').";
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
            if (h == null || h.optInt("position_sec", 0) < 120) continue;
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

    private JSONArray defaultFollowUps(int currentYear) {
        JSONArray arr = new JSONArray();
        arr.put((currentYear - 2) + "–" + currentYear + " Top Releases");
        arr.put("Mind-Bending Sci-Fi");
        arr.put("High-Pace Thrillers");
        return arr;
    }
}
