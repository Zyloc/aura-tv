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
        String prof = resolveValidProfile(profileId);
        if (reset) return resetSession(prof);
        sync.syncWithHost();
        List<String> activeFilters = db.getActiveFilters(prof);
        String effectiveQuery = resolveEffectiveQuery(userText, activeFilters);
        JSONObject localRes = runAgenticSearchTurn(prof, effectiveQuery, activeFilters, progress);
        return hasResults(localRes) ? syncAndReturn(localRes) : fallbackToRemoteHost(prof, effectiveQuery, localRes);
    }

    private String resolveValidProfile(String profileId) {
        if (profileId != null && !profileId.isEmpty() && db.getProfileLabelsJson().has(profileId)) return profileId;
        return db.getValidActiveProfile();
    }

    private JSONObject syncAndReturn(JSONObject res) {
        new Thread(sync::syncWithHost).start();
        return res;
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
        conn.setConnectTimeout(3500);
        conn.setReadTimeout(60000);
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
        AgentStatusNarrator narrator = AgentStatusNarrator.start(ai, db, profileId, userText, progress);
        try {
            return executeAgenticTurnBody(profileId, userText, activeFilters);
        } finally {
            narrator.stop();
        }
    }

    private JSONObject executeAgenticTurnBody(String profileId, String userText, List<String> activeFilters) {
        if (ShowCompanionAgent.isShowCompanionQuery(userText)) {
            return executeShowCompanionTurn(profileId, userText);
        }
        int epoch = ImpressionTracker.advanceEpoch(db, profileId);
        JSONArray turns = isFollowUpRefinement(userText) ? getOrInitTurns(profileId) : new JSONArray();
        int currentYear = JustWatchGraph.getCurrentYear();
        String contextualQuery = buildContextualQuery(turns, userText);
        int minModernYear = resolveSearchMinYear(contextualQuery, currentYear, JustWatchGraph.getDefaultMinModernYear());
        List<String> cfPool = runRetrievalSteps(profileId, contextualQuery, minModernYear);
        AgentStatusNarrator.publishLiveStep("Ranking top matches for " + db.getProfileLabel(profileId));
        String prompt = buildPrompt(profileId, turns, userText, activeFilters, collectWatchedNames(profileId), cfPool, minModernYear, currentYear, epoch);
        persistTurnResult(profileId, turns, userText, contextualQuery, activeFilters, cfPool, ai.callGeminiJson(prompt), minModernYear, currentYear, epoch);
        return db.getVoiceSession(profileId);
    }

    private JSONObject executeShowCompanionTurn(String profileId, String userText) {
        JSONObject recap = ShowCompanionAgent.executeCompanionTurn(ai, db, profileId, userText, null);
        String spoken = recap.optString("spoken_narration", "");
        String xray = recap.optString("cast_xray", "");
        String reply = "\uD83D\uDD0A " + recap.optString("headline", "Catch Me Up") + " — " + spoken + (xray.isEmpty() ? "" : " [Cast X-Ray: " + xray + "]");
        JSONArray results = recap.optJSONArray("results");
        if (results == null || results.length() == 0) results = db.getProfileRecommendations(profileId);
        saveCompanionVoiceSession(profileId, userText, reply, results, recap);
        return db.getVoiceSession(profileId);
    }

    private void saveCompanionVoiceSession(
            String profileId, String userText, String reply, JSONArray results, JSONObject recap) {
        try {
            JSONArray turns = new JSONArray()
                .put(new JSONObject().put("role", "user").put("text", userText))
                .put(new JSONObject().put("role", "assistant").put("text", reply));
            String t = recap.optString("title", "MobLand");
            JSONArray chips = new JSONArray().put("Who is in this scene in " + t + "?")
                .put("What happened in the last 5 minutes of " + t + "?").put("Movies with the lead actor of " + t);
            JSONObject sess = new JSONObject().put("turns", turns).put("reply", reply)
                .put("spoken_narration", recap.optString("spoken_narration", ""))
                .put("cast_xray", recap.optString("cast_xray", ""))
                .put("follow_ups", chips).put("results", results);
            db.saveVoiceSession(profileId, sess);
        } catch (Exception ignored) {}
    }

    public static boolean isFollowUpRefinement(String userText) {
        if (userText == null || userText.trim().split("\\s+").length > 6) return false;
        if (!AiRecommender.extractSimilaritySeed(userText).isEmpty()) return false;
        String low = userText.trim().toLowerCase();
        return low.contains("only on") || low.contains("under 2") || low.contains("shorter")
            || low.contains("darker") || low.contains("grittier") || low.contains("more like")
            || low.contains("top releases") || low.contains("high-pace") || low.startsWith("on ")
            || low.contains("next") || low.contains("more") || low.contains("else") || low.contains("another");
    }

    private JSONArray getOrInitTurns(String profileId) {
        JSONObject session = db.getVoiceSession(profileId);
        JSONArray turns = session.optJSONArray("turns");
        return turns != null ? turns : new JSONArray();
    }

    private List<String> runRetrievalSteps(String profileId, String contextualQuery, int minModernYear) {
        List<String> scatterGathered = ai.executeScatterGatherForSearch(profileId, contextualQuery, minModernYear);
        if (scatterGathered.size() >= 4) return scatterGathered;
        JSONObject searchPlan = ai.buildFastSeedPlan(profileId, contextualQuery);
        return ai.executeMultiHopTraversal(profileId, searchPlan, minModernYear);
    }

    private void persistTurnResult(
        String profileId, JSONArray turns, String userText, String contextualQuery,
        List<String> activeFilters, List<String> cfPool, String rawJson, int minModernYear, int currentYear, int epoch
    ) {
        try {
            turns.put(new JSONObject().put("role", "user").put("text", userText));
            JSONObject synth = synthesizeTurnOutput(profileId, userText, contextualQuery, activeFilters, cfPool, rawJson, minModernYear, currentYear, epoch);
            turns.put(new JSONObject().put("role", "assistant").put("text", synth.optString("reply")));
            finalizeAndSaveTurnSession(profileId, epoch, turns, synth);
        } catch (Exception ignored) {}
    }

    private void finalizeAndSaveTurnSession(String profileId, int epoch, JSONArray turns, JSONObject synth) throws Exception {
        synth.put("turns", trimTurns(turns, 8));
        ImpressionTracker.recordJsonArrayImpressions(db, epoch, synth.optJSONArray("results"));
        db.saveTraversalChips(profileId, synth.optJSONArray("follow_ups"));
        db.saveVoiceSession(profileId, synth);
    }

    private JSONObject synthesizeTurnOutput(
        String profileId, String userText, String contextualQuery, List<String> activeFilters,
        List<String> cfPool, String rawJson, int minModernYear, int currentYear, int epoch
    ) throws Exception {
        JSONObject out = parseGeminiTurnOrDefault(rawJson, userText, activeFilters, minModernYear, currentYear, epoch);
        JSONArray finalResults = out.getJSONArray("results");
        if (finalResults.length() < 2) {
            backfillWithDeterministicSearch(profileId, userText, contextualQuery, activeFilters, cfPool, minModernYear, epoch, finalResults);
            if (rawJson == null) {
                out.put("reply", "Ranked top matches for \"" + userText + "\" by taste similarity & Bayesian IMDb.");
            }
        }
        return out;
    }

    private JSONObject parseGeminiTurnOrDefault(
        String rawJson, String userText, List<String> activeFilters, int minModernYear, int currentYear, int epoch
    ) throws Exception {
        JSONObject out = new JSONObject();
        out.put("reply", "Curated top matches across your streaming apps.");
        out.put("results", new JSONArray());
        out.put("follow_ups", defaultFollowUps(currentYear));
        if (rawJson != null) {
            applyParsedGeminiTurn(new JSONObject(rawJson), userText, activeFilters, minModernYear, currentYear, epoch, out);
        }
        return out;
    }

    private void applyParsedGeminiTurn(
        JSONObject parsed, String userText, List<String> activeFilters,
        int minModernYear, int currentYear, int epoch, JSONObject out
    ) throws Exception {
        out.put("reply", parsed.optString("reply", "Updated picks for your search."));
        int minSearchYear = resolveSearchMinYear(userText, currentYear, minModernYear >= currentYear - 1 ? minModernYear : 1950);
        JSONArray enriched = ai.enrichAll(parsed.optJSONArray("results"), minSearchYear, currentYear);
        JSONArray constrained = applyConversationalConstraints(db.filterOutWatched(enriched), userText, activeFilters);
        boolean enforceStrictCooldown = isFollowUpRefinement(userText) || AiRecommender.isGenericNextOrTasteQuery(userText);
        out.put("results", enforceStrictCooldown ? ImpressionTracker.filterOutRecentImpressions(constrained, epoch, ImpressionTracker.loadActiveImpressions(db), 3) : constrained);
        JSONArray parsedFollowUps = parsed.optJSONArray("follow_ups");
        if (parsedFollowUps != null && parsedFollowUps.length() > 0) out.put("follow_ups", parsedFollowUps);
    }

    public static int resolveSearchMinYear(String query, int currentYear, int defaultYear) {
        if (query == null) return defaultYear;
        String low = " " + query.toLowerCase().trim() + " ";
        if (low.contains(" " + currentYear + " ") || low.contains("this year")) return currentYear;
        if (low.contains(" new ") || low.contains(" latest ") || low.contains(" recent ")
                || low.contains(" newest ") || low.contains("top releases") || low.contains(" " + (currentYear - 1) + " ")) {
            return currentYear - 1;
        }
        return defaultYear;
    }

    private void backfillWithDeterministicSearch(
        String profileId, String userText, String contextualQuery, List<String> activeFilters,
        List<String> cfPool, int minModernYear, int epoch, JSONArray finalResults
    ) {
        JSONArray poolCards = enrichCfPoolCandidates(cfPool);
        JSONArray sotaFallback = poolCards.length() >= 3 ? poolCards : buildDeterministicSotaSearch(profileId, userText, contextualQuery, minModernYear);
        sotaFallback = applyConversationalConstraints(db.filterOutWatched(sotaFallback), userText, activeFilters);
        sotaFallback = ImpressionTracker.filterOutRecentImpressions(sotaFallback, epoch, ImpressionTracker.loadActiveImpressions(db), 4);
        java.util.Set<String> seen = collectResultTitleKeys(finalResults);
        appendUniqueFallbackItems(sotaFallback, seen, finalResults, 16);
    }

    private JSONArray enrichCfPoolCandidates(List<String> cfPool) {
        JSONArray raw = new JSONArray();
        if (cfPool == null) return raw;
        for (int i = 0; i < cfPool.size() && i < 16; i++) {
            String s = cfPool.get(i);
            int p = s != null ? s.indexOf(" (") : -1;
            String title = p > 0 ? s.substring(0, p).trim() : "";
            if (!title.isEmpty()) try { raw.put(new JSONObject().put("title", title)); } catch (Exception ignored) {}
        }
        return ai.enrichAll(raw, 1950, JustWatchGraph.getCurrentYear());
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
            if (!key.isEmpty() && seen.add(key)) {
                trimLongFallbackReason(item);
                target.put(item);
            }
        }
    }

    private void trimLongFallbackReason(JSONObject item) {
        String r = item.optString("reason", "").trim();
        if (r.length() <= 170) return;
        int dot = r.indexOf(". ");
        String concise = (dot > 30 && dot <= 170) ? r.substring(0, dot + 1) : (r.substring(0, 165) + "...");
        try { item.put("reason", concise); } catch (Exception ignored) {}
    }

    private JSONArray buildDeterministicSotaSearch(String profileId, String userText, String contextualQuery, int minModernYear) {
        if (AiRecommender.isGenericNextOrTasteQuery(userText) && (contextualQuery == null || contextualQuery.isEmpty() || AiRecommender.isGenericNextOrTasteQuery(contextualQuery))) {
            return ai.buildDeterministicRecommendations(profileId, minModernYear, JustWatchGraph.getCurrentYear());
        }
        String seed = AiRecommender.extractSimilaritySeed(userText);
        if (!seed.isEmpty()) {
            JSONArray sim = ai.traverseSimilarToWatched(profileId, seed, null);
            if (sim != null && sim.length() > 0) return sim;
        }
        String target = AiRecommender.isGenericNextOrTasteQuery(userText) ? contextualQuery : userText;
        return JustWatchGraph.fetchDeterministicSearchCandidates(target, 1950);
    }

    public static JSONArray applyConversationalConstraints(JSONArray items, String userText, List<String> activeFilters) {
        if (items == null || items.length() == 0) return new JSONArray();
        String combined = ((userText == null ? "" : userText) + " " + (activeFilters == null ? "" : activeFilters.toString())).toLowerCase();
        JSONArray filtered = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && matchesConversationalItem(item, combined)) filtered.put(item);
        }
        if (filtered.length() > 0) return filtered;
        return (wantsMoviesOnly(combined) || wantsSeriesOnly(combined)) ? filterByFormatOnly(items, combined) : items;
    }

    private static JSONArray filterByFormatOnly(JSONArray items, String combined) {
        JSONArray out = new JSONArray();
        boolean allowAnim = combined.contains("anime") || combined.contains("animat") || combined.contains("kids");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && matchesFormatConstraint(item.optString("type", ""), combined)
                    && (allowAnim || !item.optString("genres", "").toLowerCase().contains("animation"))) out.put(item);
        }
        return out;
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

    public static boolean matchesConversationalItem(JSONObject item, String combined) {
        if (!matchesFormatConstraint(item.optString("type", ""), combined)) return false;
        if (!matchesPlatformConstraint(item.optString("app", "").toLowerCase(), combined)) return false;
        boolean allowAnim = combined.contains("anime") || combined.contains("animat") || combined.contains("kids");
        if (!allowAnim && item.optString("genres", "").toLowerCase().contains("animation")) return false;
        if (combined.contains("under 90") || combined.contains("< 90")) return item.optInt("runtime_min", 100) <= 98;
        boolean underTwoHours = combined.contains("under 2") || combined.contains("shorter") || combined.contains("< 120");
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
        if (userQueries.isEmpty()) {
            return AiRecommender.isGenericNextOrTasteQuery(currentQuery) ? "" : currentQuery;
        }
        String firstTopic = userQueries.get(0);
        if (AiRecommender.isGenericNextOrTasteQuery(currentQuery)) return firstTopic;
        String lastTopic = userQueries.get(userQueries.size() - 1);
        if (firstTopic.equalsIgnoreCase(currentQuery)) return currentQuery;
        return !firstTopic.equalsIgnoreCase(lastTopic)
            ? (firstTopic + " -> " + lastTopic + " -> Follow-up: " + currentQuery)
            : (firstTopic + " -> Follow-up: " + currentQuery);
    }

    private List<String> extractUserTurnTexts(JSONArray turns) {
        List<String> userQueries = new ArrayList<>();
        if (turns == null) return userQueries;
        for (int i = 0; i < turns.length(); i++) {
            JSONObject t = turns.optJSONObject(i);
            if (t != null && "user".equalsIgnoreCase(t.optString("role"))) {
                String txt = t.optString("text", "").trim();
                if (!txt.isEmpty() && !AiRecommender.isGenericNextOrTasteQuery(txt)) userQueries.add(txt);
            }
        }
        return userQueries;
    }

    private String buildPrompt(
        String profileId, JSONArray turns, String userText, List<String> filters,
        List<String> watched, List<String> cfPool, int minModernYear, int currentYear, int epoch
    ) {
        String todayDate = JustWatchGraph.getCurrentDateString();
        String header = buildPromptContextHeader(profileId, turns, userText, filters, watched, cfPool, minModernYear, currentYear, todayDate, epoch);
        return header + buildPromptRulesSection(minModernYear, currentYear, todayDate);
    }

    private String buildPromptContextHeader(
        String profileId, JSONArray turns, String userText, List<String> filters,
        List<String> watched, List<String> cfPool, int minModernYear, int currentYear, String todayDate, int epoch
    ) {
        String personalGuide = db.getProfileTaste(profileId);
        String ratings = db.getRatingFeedbackSummary();
        return "You are Aura's Agentic Search & Recommendation Engine for profile '" + db.getProfileLabel(profileId) + "' (Round #" + epoch + ").\n" +
            "Current TV System Date: " + todayDate + " (any title released up to " + todayDate + " in " + currentYear + " is valid; " + (currentYear + 1) + "+ are unreleased).\n" +
            "Personal Guide AI & Profile Interests: " + (personalGuide.isEmpty() ? db.getDefaultTasteGuide() : personalGuide) + "\n" +
            "User Ratings Feedback (What User Likes & Dislikes): " + (ratings.isEmpty() ? "(None)" : ratings) + "\n" +
            "User's Active & Rotating Taste Anchors: " + db.getRotatingTasteAnchors(profileId, epoch).toString() + "\n" +
            "STRICT EXCLUSION LIST — Already watched (NEVER recommend any of these): " + watched.toString() + "\n" +
            "STRICT COOLDOWN LIST — Recently Suggested in Last 8 Rounds (NEVER repeat or recommend any of these): " + ImpressionTracker.getRecentCooldownKeys(db, epoch).toString() + "\n" +
            "Multi-Hop Search & Co-Watch Graph Traversal Candidates (" + minModernYear + "–" + currentYear + "): " + cfPool.toString() + "\n" +
            "Active Persistent Filters (MUST obey): " + (filters.isEmpty() ? "None" : filters.toString()) + "\n" +
            "Session Flow so far:\n" + formatTurnsHistory(turns) + "User Search / Traversal Query: \"" + userText + "\"\n\n";
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
        String region = RegionProviderRegistry.getRegionDisplayName(db);
        String apps = RegionProviderRegistry.getProviderDisplayListForRegion(db);
        return "STRICT STAGE-3 POST-FILTER & RERANKING RULES:\n" +
            "- MULTI-HOP & COUNTERFACTUAL INTENT VERIFICATION: Decompose all clauses of the User Search Query (premise/seed reference, tone/genre modifiers like 'like X but romantic comedy', cross-show actor/cast references, plot clues, runtime limits, and negative exclusions like 'no space travel'). Select and rank candidates that satisfy the intersection of all clauses first, and explain how each title satisfies those clauses in 'reason'.\n" +
            "- PRECISION OVER PADDING: Return 3 to 8 unwatched titles that strictly satisfy the core premise, genre, and cast clauses. If only 3 or 4 candidates in the pool truly match the plot/subgenre premise, return ONLY those 3 or 4 rather than padding with an actor's unrelated movies (e.g. never include a military war drama or bar-brawler on a mind-bending time-loop query).\n" +
            "- GLOBAL & REGIONAL DIVERSITY: Unless the User Search Query explicitly requests a specific country/language, include globally acclaimed international/Hollywood classics alongside regional titles.\n" +
            "- PROFILE PERSONALIZATION & 8-ROUND RECYCLING: Dynamically tailor candidate selection and ranking to the user's Personal Guide AI, User Ratings Feedback, and Rotating Taste Anchors. NEVER return any title in 'Already watched'.\n" +
            "- MULTI-TURN CONVERSATION: If 'Session Flow so far' has previous user turns, treat the current query as a FOLLOW-UP REFINEMENT on that conversation.\n" +
            "- FORMAT CONSTRAINT: If the user asks for 'movies' or 'films', ONLY return feature films (Type='Movie', NEVER TV series). If they ask for 'shows' or 'series', ONLY return TV series.\n" +
            "- NEW / LATEST RELEASES: If the user asks for 'new', 'latest', or 'recent' releases, ONLY select " + minModernYear + "\u2013" + currentYear + " titles, rank " + currentYear + " releases first followed by " + minModernYear + ", and include a diverse mix of genres.\n" +
            "- RECENCY WINDOW: Prioritize " + recentPriorityStart + "\u2013" + currentYear + " releases (up to " + todayDate + "), and for franchise/similarity/classic queries include iconic peers from the candidate pool. NEVER recommend unreleased " + (currentYear + 1) + "+ titles.\n" +
            "Return a JSON object with:\n" +
            "1. 'reply': Short 1-sentence conversational summary explaining how you filtered and ranked the results for their profile.\n" +
            "2. 'results': Array of 5 to 10 unwatched titles from the candidates above (streaming in " + region + " on " + apps + ") that strictly match the query's sub-genre, tone, and format (NEVER pad with unrelated genres), each with keys: 'title' (exact title) and 'reason' (16-24 words explaining how its plot, cast, and genre satisfy their query clauses and profile taste).\n" +
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
