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
        String profileId, String userText, boolean reset, AiRecommender.AgentProgressListener progress
    ) {
        if (reset) return resetSession(profileId);
        sync.syncWithHost();
        List<String> activeFilters = db.getActiveFilters(profileId);
        String effectiveQuery = resolveEffectiveQuery(userText, activeFilters);
        JSONObject localRes = runAgenticSearchTurn(profileId, effectiveQuery, activeFilters, progress);
        if (hasResults(localRes)) {
            new Thread(sync::syncWithHost).start();
            return localRes;
        }
        return fallbackToRemoteHost(profileId, effectiveQuery, localRes);
    }

    private JSONObject resetSession(String profileId) {
        db.setSetting("voice_session_" + profileId, "");
        new Thread(sync::syncWithHost).start();
        return db.getVoiceSession(profileId);
    }

    private String resolveEffectiveQuery(String userText, List<String> activeFilters) {
        return (userText == null || userText.trim().isEmpty())
            ? ("Apply filters: " + activeFilters.toString())
            : userText.trim();
    }

    private boolean hasResults(JSONObject res) {
        return res != null && res.optJSONArray("results") != null && res.optJSONArray("results").length() > 0;
    }

    private JSONObject fallbackToRemoteHost(String profileId, String effectiveQuery, JSONObject localRes) {
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
            JSONObject req = new JSONObject().put("profile", profileId).put("message", userText);
            HttpURLConnection conn = openChatPostConnection(host + "/api/chat", req.toString());
            if (conn.getResponseCode() != 200) return null;
            return readJsonResponse(conn);
        } catch (Exception e) {
            return null;
        }
    }

    private HttpURLConnection openChatPostConnection(String urlStr, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(2500);
        conn.setReadTimeout(12000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    private JSONObject readJsonResponse(HttpURLConnection conn) throws Exception {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        }
    }

    private JSONObject runAgenticSearchTurn(
        String profileId, String userText, List<String> activeFilters, AiRecommender.AgentProgressListener progress
    ) {
        JSONArray turns = getOrInitTurns(profileId);
        int currentYear = JustWatchGraph.getCurrentYear();
        int minModernYear = JustWatchGraph.getDefaultMinModernYear();
        String contextualQuery = buildContextualQuery(turns, userText);
        List<String> cfPool = runRetrievalSteps(profileId, userText, contextualQuery, minModernYear, currentYear, progress);
        if (progress != null) progress.onStep("AI ranking top matches for your conversation\u2026");
        String prompt = buildPrompt(profileId, turns, userText, activeFilters, collectWatchedNames(profileId), cfPool, minModernYear, currentYear);
        String rawJson = ai.callGeminiJson(prompt);
        persistTurnResult(profileId, turns, userText, contextualQuery, activeFilters, rawJson, minModernYear, currentYear);
        return db.getVoiceSession(profileId);
    }

    private JSONArray getOrInitTurns(String profileId) {
        JSONObject session = db.getVoiceSession(profileId);
        JSONArray turns = session.optJSONArray("turns");
        return turns != null ? turns : new JSONArray();
    }

    private List<String> runRetrievalSteps(
        String profileId, String userText, String contextualQuery,
        int minModernYear, int currentYear, AiRecommender.AgentProgressListener progress
    ) {
        String simSeed = resolveActiveSimilaritySeed(userText, contextualQuery);
        if (!simSeed.isEmpty()) {
            List<String> simPool = buildSimilarityCandidatePool(simSeed, userText, progress);
            if (simPool.size() >= 3) return simPool;
        }
        if (progress != null) progress.onStep("Searching across your streaming apps for \"" + userText + "\"\u2026");
        JSONObject searchPlan = ai.buildFastSeedPlan(profileId, contextualQuery);
        if (progress != null) progress.onStep("Traversing " + minModernYear + "\u2013" + currentYear + " streaming graph\u2026");
        return ai.executeMultiHopTraversal(profileId, searchPlan, minModernYear);
    }

    private String resolveActiveSimilaritySeed(String userText, String contextualQuery) {
        String direct = AiRecommender.extractSimilaritySeed(userText);
        return !direct.isEmpty() ? direct : AiRecommender.extractSimilaritySeed(contextualQuery);
    }

    private List<String> buildSimilarityCandidatePool(
        String simSeed, String userText, AiRecommender.AgentProgressListener progress
    ) {
        if (progress != null) progress.onStep("Traversing co-watch & franchise graph for \"" + simSeed + "\"\u2026");
        List<String> summaries = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        appendSimilarityCandidates(JustWatchGraph.fetchCollaborativeSimilar(simSeed, 2008), userText, seen, summaries);
        appendSimilarityCandidates(JustWatchGraph.fetchDeterministicSearchCandidates(simSeed, 2008), userText, seen, summaries);
        return summaries;
    }

    private void appendSimilarityCandidates(
        JSONArray arr, String userText, java.util.Set<String> seen, List<String> summaries
    ) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) tryAddSimilaritySummary(o, userText, seen, summaries);
        }
    }

    private void tryAddSimilaritySummary(
        JSONObject o, String userText, java.util.Set<String> seen, List<String> summaries
    ) {
        String t = o.optString("title", "").trim();
        String norm = AuraDb.normalizeTitleKey(t);
        if (t.isEmpty() || db.isTitleWatched(t) || !matchesFormatConstraint(o.optString("type", ""), userText)) return;
        if (!seen.add(norm)) return;
        db.saveCachedGti("jw_imdb_v6:" + t.toLowerCase(), o.toString());
        summaries.add(t + " (" + o.optInt("year") + ", " + o.optString("type") + ", " + o.optString("genres") + ", " + o.optString("rating") + ", " + o.optString("app") + ")");
    }

    private void persistTurnResult(
        String profileId, JSONArray turns, String userText, String contextualQuery,
        List<String> activeFilters, String rawJson, int minModernYear, int currentYear
    ) {
        try {
            turns.put(new JSONObject().put("role", "user").put("text", userText));
            JSONObject synth = synthesizeTurnOutput(profileId, userText, contextualQuery, activeFilters, rawJson, minModernYear, currentYear);
            turns.put(new JSONObject().put("role", "assistant").put("text", synth.optString("reply")));
            synth.put("turns", trimTurns(turns, 8));
            db.saveTraversalChips(profileId, synth.optJSONArray("follow_ups"));
            db.saveVoiceSession(profileId, synth);
        } catch (Exception ignored) {}
    }

    private JSONObject synthesizeTurnOutput(
        String profileId, String userText, String contextualQuery, List<String> activeFilters,
        String rawJson, int minModernYear, int currentYear
    ) throws Exception {
        JSONObject out = parseGeminiTurnOrDefault(rawJson, userText, activeFilters, minModernYear, currentYear);
        JSONArray finalResults = out.getJSONArray("results");
        if (finalResults.length() < 8) {
            backfillWithDeterministicSearch(profileId, userText, contextualQuery, activeFilters, minModernYear, finalResults);
            if (rawJson == null) {
                out.put("reply", "Ranked top matches for \"" + userText + "\" by taste similarity & Bayesian IMDb.");
            }
        }
        return out;
    }

    private JSONObject parseGeminiTurnOrDefault(
        String rawJson, String userText, List<String> activeFilters, int minModernYear, int currentYear
    ) throws Exception {
        JSONObject out = new JSONObject();
        out.put("reply", "Curated top matches across your streaming apps.");
        out.put("results", new JSONArray());
        out.put("follow_ups", defaultFollowUps(currentYear));
        if (rawJson != null) {
            applyParsedGeminiTurn(new JSONObject(rawJson), userText, activeFilters, minModernYear, currentYear, out);
        }
        return out;
    }

    private void applyParsedGeminiTurn(
        JSONObject parsed, String userText, List<String> activeFilters,
        int minModernYear, int currentYear, JSONObject out
    ) throws Exception {
        out.put("reply", parsed.optString("reply", "Updated picks for your search."));
        int minSearchYear = !AiRecommender.extractSimilaritySeed(userText).isEmpty() ? 2008 : (minModernYear - 2);
        JSONArray enriched = ai.enrichAll(parsed.optJSONArray("results"), minSearchYear, currentYear);
        out.put("results", applyConversationalConstraints(db.filterOutWatched(enriched), userText, activeFilters));
        JSONArray parsedFollowUps = parsed.optJSONArray("follow_ups");
        if (parsedFollowUps != null && parsedFollowUps.length() > 0) {
            out.put("follow_ups", parsedFollowUps);
        }
    }

    private void backfillWithDeterministicSearch(
        String profileId, String userText, String contextualQuery,
        List<String> activeFilters, int minModernYear, JSONArray finalResults
    ) {
        JSONArray sotaFallback = buildDeterministicSotaSearch(profileId, userText, contextualQuery, minModernYear);
        sotaFallback = applyConversationalConstraints(db.filterOutWatched(sotaFallback), userText, activeFilters);
        java.util.Set<String> seen = collectResultTitleKeys(finalResults);
        appendUniqueFallbackItems(sotaFallback, seen, finalResults, 16);
    }

    private java.util.Set<String> collectResultTitleKeys(JSONArray results) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item != null) seen.add(AuraDb.normalizeTitleKey(item.optString("title", "")));
        }
        return seen;
    }

    private void appendUniqueFallbackItems(JSONArray fallback, java.util.Set<String> seen, JSONArray target, int maxCount) {
        for (int i = 0; i < fallback.length() && target.length() < maxCount; i++) {
            JSONObject item = fallback.optJSONObject(i);
            if (item == null) continue;
            String key = AuraDb.normalizeTitleKey(item.optString("title", ""));
            if (!key.isEmpty() && seen.add(key)) target.put(item);
        }
    }

    private JSONArray buildDeterministicSotaSearch(String profileId, String userText, String contextualQuery, int minModernYear) {
        JSONArray directSim = tryDirectSimilarTraversal(profileId, userText);
        if (directSim != null) return directSim;
        List<JSONObject> pool = retrieveDeterministicPool(userText, contextualQuery, minModernYear);
        pool.sort((a, b) -> Double.compare(JustWatchGraph.computeBayesianImdb(b), JustWatchGraph.computeBayesianImdb(a)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(16, pool.size()); i++) out.put(pool.get(i));
        return out.length() > 0 ? out : db.getProfileRecommendations(profileId);
    }

    private JSONArray tryDirectSimilarTraversal(String profileId, String userText) {
        String seed = AiRecommender.extractSimilaritySeed(userText);
        if (seed.isEmpty()) return null;
        JSONArray sim = ai.traverseSimilarToWatched(profileId, seed, null);
        return (sim != null && sim.length() > 0) ? sim : null;
    }

    private List<JSONObject> retrieveDeterministicPool(String userText, String contextualQuery, int minModernYear) {
        String low = userText == null ? "" : userText.toLowerCase();
        boolean allowAnime = low.contains("anime") || low.contains("animation");
        List<String> genres = extractConversationalGenres(low);
        List<JSONObject> pool = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (!genres.isEmpty()) {
            int startYr = Math.max(minModernYear, JustWatchGraph.getCurrentYear() - 3);
            addUniqueCandidatesToPool(JustWatchGraph.fetchStructuredGenreCandidates(genres, null, startYr, JustWatchGraph.getCurrentYear()), seen, allowAnime, pool);
        }
        addUniqueCandidatesToPool(JustWatchGraph.fetchDeterministicSearchCandidates(contextualQuery, minModernYear), seen, allowAnime, pool);
        return pool;
    }

    private List<String> extractConversationalGenres(String low) {
        List<String> genres = new ArrayList<>();
        if (low.contains("sci-fi") || low.contains("scifi") || low.contains("science")) genres.add("scf");
        if (low.contains("thriller") || low.contains("suspense") || low.contains("mystery") || low.contains("dark")) genres.add("trl");
        if (low.contains("crime") || low.contains("detective")) genres.add("crm");
        if (low.contains("action") || low.contains("fast")) genres.add("act");
        if (low.contains("horror") || low.contains("scary")) genres.add("hrr");
        appendSecondaryConversationalGenres(low, genres);
        return genres;
    }

    private void appendSecondaryConversationalGenres(String low, List<String> genres) {
        if (low.contains("comedy") || low.contains("funny")) genres.add("cmy");
        if (low.contains("romance") || low.contains("romantic")) genres.add("rma");
        if (low.contains("drama")) genres.add("drm");
        if (low.contains("anime") || low.contains("animation")) genres.add("ani");
    }

    private void addUniqueCandidatesToPool(JSONArray arr, java.util.Set<String> seen, boolean allowAnime, List<JSONObject> pool) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || CandidateValidator.isUnwantedAnimation(o.optString("genre_codes", ""), allowAnime)) continue;
            if (seen.add(AuraDb.normalizeTitleKey(o.optString("title", "")))) pool.add(o);
        }
    }

    public static JSONArray applyConversationalConstraints(JSONArray items, String userText, List<String> activeFilters) {
        if (items == null || items.length() == 0) return new JSONArray();
        String combined = ((userText == null ? "" : userText) + " " + (activeFilters == null ? "" : activeFilters.toString())).toLowerCase();
        JSONArray filtered = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && matchesConversationalItem(item, combined)) filtered.put(item);
        }
        boolean strictFormat = wantsMoviesOnly(combined) || wantsSeriesOnly(combined);
        return (filtered.length() >= 3 || (strictFormat && filtered.length() > 0)) ? filtered : items;
    }

    public static boolean wantsMoviesOnly(String text) {
        if (text == null) return false;
        String low = text.toLowerCase();
        return (low.contains("movie") || low.contains("film")) && !low.contains("show") && !low.contains("series");
    }

    public static boolean wantsSeriesOnly(String text) {
        if (text == null) return false;
        String low = text.toLowerCase();
        return (low.contains("show") || low.contains("series")) && !low.contains("movie") && !low.contains("film");
    }

    private static boolean matchesFormatConstraint(String type, String text) {
        if (wantsMoviesOnly(text)) return "Movie".equalsIgnoreCase(type);
        if (wantsSeriesOnly(text)) return "Series".equalsIgnoreCase(type);
        return true;
    }

    private static boolean matchesConversationalItem(JSONObject item, String combined) {
        if (!matchesFormatConstraint(item.optString("type", ""), combined)) return false;
        if (!matchesPlatformConstraint(item.optString("app", "").toLowerCase(), combined)) return false;
        boolean underTwoHours = combined.contains("under 2 hours") || combined.contains("shorter") || combined.contains("< 120");
        return !underTwoHours || item.optInt("runtime_min", 100) <= 125;
    }

    private static boolean matchesPlatformConstraint(String app, String combined) {
        boolean fNfx = combined.contains("netflix"), fPrv = combined.contains("prime");
        boolean fJhs = combined.contains("hotstar") || combined.contains("jio"), fAtp = combined.contains("apple");
        if (!fNfx && !fPrv && !fJhs && !fAtp) return true;
        return (fNfx && app.contains("netflix")) || (fPrv && app.contains("prime"))
            || (fJhs && (app.contains("hotstar") || app.contains("jio"))) || (fAtp && app.contains("apple"));
    }

    private String buildContextualQuery(JSONArray turns, String currentQuery) {
        List<String> userQueries = extractUserTurnTexts(turns);
        if (userQueries.isEmpty()) return currentQuery;
        String firstTopic = userQueries.get(0);
        String lastTopic = userQueries.get(userQueries.size() - 1);
        if (firstTopic.equalsIgnoreCase(currentQuery)) return currentQuery;
        if (!firstTopic.equalsIgnoreCase(lastTopic)) {
            return firstTopic + " -> " + lastTopic + " -> Follow-up: " + currentQuery;
        }
        return firstTopic + " -> Follow-up: " + currentQuery;
    }

    private List<String> extractUserTurnTexts(JSONArray turns) {
        List<String> userQueries = new ArrayList<>();
        if (turns == null) return userQueries;
        for (int i = 0; i < turns.length(); i++) {
            JSONObject t = turns.optJSONObject(i);
            if (t != null && "user".equalsIgnoreCase(t.optString("role"))) {
                String txt = t.optString("text", "").trim();
                if (!txt.isEmpty()) userQueries.add(txt);
            }
        }
        return userQueries;
    }

    private String buildPrompt(
        String profileId, JSONArray turns, String userText, List<String> filters,
        List<String> watched, List<String> cfPool, int minModernYear, int currentYear
    ) {
        String todayDate = JustWatchGraph.getCurrentDateString();
        String header = buildPromptContextHeader(profileId, turns, userText, filters, watched, cfPool, minModernYear, currentYear, todayDate);
        return header + buildPromptRulesSection(minModernYear, currentYear, todayDate);
    }

    private String buildPromptContextHeader(
        String profileId, JSONArray turns, String userText, List<String> filters,
        List<String> watched, List<String> cfPool, int minModernYear, int currentYear, String todayDate
    ) {
        String personalGuide = db.getProfileTaste(profileId);
        return "You are Aura's Agentic Search & Recommendation Engine for profile '" + db.getProfileLabel(profileId) + "'.\n" +
            "Current TV System Date: " + todayDate + " (any title released up to " + todayDate + " in " + currentYear + " is valid; " + (currentYear + 1) + "+ are unreleased).\n" +
            "Personal Guide AI (for this profile): " + (personalGuide.isEmpty() ? "(None)" : personalGuide) + "\n" +
            "Default Guide AI (global baseline): " + db.getDefaultTasteGuide() + "\n" +
            "STRICT EXCLUSION LIST — Already watched (NEVER recommend any of these): " + watched.toString() + "\n" +
            "Multi-Hop Search & Co-Watch Graph Traversal Candidates (" + minModernYear + "–" + currentYear + "): " + cfPool.toString() + "\n" +
            "Active Persistent Filters (MUST obey): " + (filters.isEmpty() ? "None" : filters.toString()) + "\n" +
            "Session Flow so far:\n" + formatTurnsHistory(turns) +
            "User Search / Traversal Query: \"" + userText + "\"\n\n";
    }

    private String formatTurnsHistory(JSONArray turns) {
        StringBuilder conv = new StringBuilder();
        for (int i = 0; i < turns.length(); i++) {
            JSONObject t = turns.optJSONObject(i);
            if (t != null) conv.append(t.optString("role")).append(": ").append(t.optString("text")).append("\n");
        }
        return conv.toString();
    }

    private String buildPromptRulesSection(int minModernYear, int currentYear, String todayDate) {
        int recentPriorityStart = Math.max(minModernYear, currentYear - 5);
        return "STRICT RULES:\n" +
            "- MULTI-TURN CONVERSATION: If 'Session Flow so far' has previous user turns, treat the current query as a FOLLOW-UP REFINEMENT on that conversation.\n" +
            "- SIMILARITY & FRANCHISE QUERIES: If the user asks for titles 'like X' or 'similar to X' (e.g. 'movies like Avenger'), EVERY SINGLE RESULT must match the exact subgenre, tone, scale, and theme of X (e.g. superhero / comic-book / MCU / DC / ensemble sci-fi action movies). NEVER include unrelated crime dramas or thrillers from watch history.\n" +
            "- FORMAT CONSTRAINT: If the user asks for 'movies' or 'films', ONLY recommend feature films (NEVER TV series). If they ask for 'shows' or 'series', ONLY recommend TV series.\n" +
            "- RECENCY WINDOW: Prioritize modern " + recentPriorityStart + "\u2013" + currentYear + " releases (up to " + todayDate + "), and for 'like X' queries you may also include iconic 2008\u2013" + currentYear + " franchise/genre peers from the candidate pool. NEVER recommend unreleased " + (currentYear + 1) + "+ titles.\n" +
            "Return a JSON object with:\n" +
            "1. 'reply': Short 1-sentence conversational summary explaining how you answered or refined the search.\n" +
            "2. 'results': Array of 14 unwatched titles streaming in India ('Disney+ Hotstar', 'Netflix', 'Prime Video', 'Apple TV+', 'SonyLIV', or 'ZEE5'), each with keys: 'title', 'app', 'genres', 'runtime_min' (integer), 'rating' (IMDb rating like '★ 8.3'), and 'reason' (18-26 words explaining why this fits their query).\n" +
            "3. 'follow_ups': Array of 4 short conversational follow-up refinements (max 26 chars each, e.g. 'Only on Netflix & Prime', 'Shorter under 2 hours', 'Darker & grittier tone', 'More like <Top Result>').";
    }

    private List<String> collectWatchedNames(String profileId) {
        List<String> list = new ArrayList<>();
        appendTitlesFromJsonArray(db.listManuallyWatched(), 0, Integer.MAX_VALUE, list);
        appendTitlesFromJsonArray(db.listHistory(profileId), 120, 45, list);
        return list;
    }

    private void appendTitlesFromJsonArray(JSONArray arr, int minPosSec, int maxTotal, List<String> list) {
        for (int i = 0; i < arr.length() && list.size() < maxTotal; i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null || (minPosSec > 0 && item.optInt("position_sec", 0) < minPosSec)) continue;
            String t = item.optString("series_title").isEmpty() ? item.optString("title") : item.optString("series_title");
            if (!t.isEmpty() && !list.contains(t)) list.add(t);
        }
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
