package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Unified Multi-Hop Agentic Search & Refresh Orchestrator:
 * Stage 1: Intent & Profile Decomposition
 * Stage 2: Parallel Graph Tool Scatter-Gather (Exact Lookups + Co-Watch + Actor/Genre Specs)
 * Stage 3: Hard-Constraint Merge / De-Merge (Format, Platform, Runtime, Watched, 8-Round Cooldown)
 * Stage 4: Automatic LLM Recovery Hop when De-Merged Pool < 8
 */
public final class AgenticSearchOrchestrator {

    private AgenticSearchOrchestrator() {}

    public static List<String> executeAgenticScatterGather(
            AiRecommender ai, AuraDb db, String profileId, String userQuery, JSONObject plan, int minModernYear) {
        enforceGlobalRegionalBalance(userQuery, plan);
        List<JSONObject> rawPool = gatherParallelCandidates(userQuery, plan, minModernYear);
        List<String> filters = db.getActiveFilters(profileId);
        List<JSONObject> deMerged = deMergeCandidates(db, profileId, userQuery, filters, plan, minModernYear, rawPool, true);
        if (deMerged.size() < 6) {
            deMerged = deMergeCandidates(db, profileId, userQuery, filters, plan, minModernYear, rawPool, false);
        }
        if (deMerged.size() < 8) {
            runRecoveryHop(ai, db, profileId, userQuery, filters, plan, minModernYear, rawPool, deMerged);
        }
        AgentStatusNarrator.publishLiveStep("Verified " + deMerged.size() + " streaming matches");
        return formatAndCachePoolSummaries(db, deMerged);
    }

    public static void enforceGlobalRegionalBalance(String userQuery, JSONObject plan) {
        if (plan == null || hasExplicitRegionalIntent(userQuery)) return;
        JSONArray specs = plan.optJSONArray("queries");
        if (specs == null) return;
        int countryRestrictedCount = 0;
        for (int i = 0; i < specs.length(); i++) {
            countryRestrictedCount = stripExcessCountryFilter(specs.optJSONObject(i), countryRestrictedCount);
        }
    }

    private static int stripExcessCountryFilter(JSONObject spec, int count) {
        if (spec == null || spec.optJSONArray("productionCountries") == null) return count;
        if (spec.optJSONArray("productionCountries").length() == 0) return count;
        if (count >= 1) spec.remove("productionCountries");
        return count + 1;
    }

    private static boolean hasExplicitRegionalIntent(String q) {
        if (q == null) return false;
        String low = q.toLowerCase(Locale.US);
        return low.contains("indian") || low.contains("hindi") || low.contains("bollywood")
            || low.contains("korean") || low.contains("japanese") || low.contains("anime")
            || low.contains("british") || low.contains("spanish") || low.contains("french")
            || low.contains("tamil") || low.contains("telugu") || low.contains("malayalam") || low.contains("asian");
    }

    private static List<JSONObject> gatherParallelCandidates(String userQuery, JSONObject plan, int minModernYear) {
        ExecutorService exec = AuraExecutors.io();
        JSONArray pq = plan != null ? plan.optJSONArray("primary_queries") : null;
        String fmt = plan != null ? plan.optString("format", "") : "";
        Future<JSONObject[]> pqFut = exec.submit(() -> lookupTitlesInParallel(pq, pq != null ? Math.min(12, pq.length()) : 0));
        Future<JSONArray> castFut = exec.submit(() -> JustWatchGraph.fetchCastSeedCandidates(plan != null ? plan.optJSONArray("cast_seed_titles") : null, fmt));
        Future<JSONArray> specFut = exec.submit(() -> JustWatchGraph.executeScatterGatherSpecs(plan != null ? plan.optJSONArray("queries") : null));
        Future<JSONArray> simFut = exec.submit(() -> fetchDirectSimIfSimple(userQuery));
        return collectParallelFutures(pqFut, castFut, specFut, simFut, plan, minModernYear);
    }

    private static JSONArray fetchDirectSimIfSimple(String userQuery) {
        String seed = AiRecommender.extractSimilaritySeed(userQuery);
        return seed.isEmpty() ? new JSONArray() : JustWatchGraph.fetchCollaborativeSimilar(seed, 2000);
    }

    private static List<JSONObject> collectParallelFutures(
            Future<JSONObject[]> pqFut, Future<JSONArray> castFut, Future<JSONArray> specFut,
            Future<JSONArray> simFut, JSONObject plan, int minModernYear) {
        List<JSONObject> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean isNewReleases = minModernYear >= JustWatchGraph.getCurrentYear() - 1;
        JSONObject[] pqHits = awaitArray(pqFut, 10000);
        if (!isNewReleases) appendLookupArray(pqHits, seen, merged);
        appendJsonArray(awaitJson(castFut, 9000), seen, merged);
        appendJsonArray(awaitJson(simFut, 6000), seen, merged);
        appendJsonArray(awaitJson(specFut, 12000), seen, merged);
        if (isNewReleases) appendLookupArray(pqHits, seen, merged);
        if (merged.size() < 10) appendCoWatchFallbackFromPlan(plan, minModernYear, seen, merged);
        return merged;
    }

    private static void appendCoWatchFallbackFromPlan(
            JSONObject plan, int minModernYear, Set<String> seen, List<JSONObject> merged) {
        JSONArray pq = plan != null ? plan.optJSONArray("primary_queries") : null;
        if (pq == null) return;
        for (int i = 0; i < pq.length() && i < 4 && merged.size() < 24; i++) {
            String q = pq.optString(i, "").trim();
            if (!q.isEmpty()) appendJsonArray(JustWatchGraph.fetchCollaborativeSimilar(q, Math.max(2000, minModernYear)), seen, merged);
        }
    }

    public static JSONObject[] lookupTitlesInParallel(JSONArray titles, int count) {
        if (titles == null || count <= 0) return new JSONObject[0];
        final JSONObject[] hits = new JSONObject[count];
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            final String q = titles.optString(i, "").trim();
            if (!q.isEmpty()) futures.add(AuraExecutors.io().submit(() -> hits[idx] = JustWatchGraph.lookupTitle(q)));
        }
        AuraExecutors.awaitAll(futures, 8000L);
        return hits;
    }

    private static void appendLookupArray(JSONObject[] hits, Set<String> seen, List<JSONObject> out) {
        if (hits == null) return;
        for (JSONObject h : hits) {
            if (h != null && h.optBoolean("has_flatrate_offer", false)) addUniqueCandidate(h, seen, out);
        }
    }

    private static void appendJsonArray(JSONArray arr, Set<String> seen, List<JSONObject> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optBoolean("has_flatrate_offer", true)) addUniqueCandidate(o, seen, out);
        }
    }

    private static void addUniqueCandidate(JSONObject o, Set<String> seen, List<JSONObject> out) {
        String t = o.optString("title", "").trim();
        String norm = AuraDb.normalizeTitleKey(t);
        if (!t.isEmpty() && !norm.isEmpty() && seen.add(norm)) {
            out.add(o);
        }
    }

    public static List<JSONObject> deMergeCandidates(
            AuraDb db, String profileId, String userQuery, List<String> activeFilters,
            JSONObject plan, int minModernYear, List<JSONObject> rawPool, boolean excludeCooldown) {
        Set<String> excluded = buildExclusionSet(db, profileId, userQuery, plan, excludeCooldown);
        String combined = buildCombinedConstraintText(userQuery, activeFilters, plan);
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JSONObject item : rawPool) {
            if (passesDeMergeGates(db, item, combined, minModernYear, excluded, seen)) out.add(item);
        }
        return out;
    }

    private static Set<String> buildExclusionSet(
            AuraDb db, String profileId, String userQuery, JSONObject plan, boolean excludeCooldown) {
        Set<String> watched = new HashSet<>(db.getHistoryTitleKeys(profileId));
        watched.addAll(db.getWatchedTitleKeys());
        if (!excludeCooldown) return watched;
        Set<String> keys = new HashSet<>(ImpressionTracker.getRecentCooldownKeys(db, ImpressionTracker.getCurrentEpoch(db, profileId)));
        exemptTopExactSearchHits(userQuery, plan, keys);
        keys.addAll(watched);
        return keys;
    }

    public static void exemptTopExactSearchHits(String userQuery, JSONObject plan, Set<String> cooldownKeys) {
        if (AiRecommender.isGenericNextOrTasteQuery(userQuery) || VoiceConcierge.isFollowUpRefinement(userQuery)) return;
        JSONArray pq = plan != null ? plan.optJSONArray("primary_queries") : null;
        if (pq == null) return;
        for (int i = 0; i < pq.length() && i < 4; i++) {
            cooldownKeys.remove(AuraDb.normalizeTitleKey(pq.optString(i, "")));
        }
    }

    private static String buildCombinedConstraintText(String userQuery, List<String> filters, JSONObject plan) {
        String q = userQuery == null ? "" : userQuery;
        String f = filters == null ? "" : filters.toString();
        String pf = plan != null ? plan.optString("format", "") : "";
        return (q + " " + f + " " + pf).toLowerCase(Locale.US);
    }

    private static boolean passesDeMergeGates(
            AuraDb db, JSONObject o, String combined, int minModernYear, Set<String> excluded, Set<String> seen) {
        String t = o.optString("title", "").trim();
        String norm = AuraDb.normalizeTitleKey(t);
        if (t.isEmpty() || excluded.contains(norm) || db.isTitleWatched(t) || !seen.add(norm)) return false;
        if (minModernYear >= JustWatchGraph.getCurrentYear() - 1 && o.optInt("year", 0) < minModernYear) return false;
        return VoiceConcierge.matchesConversationalItem(o, combined);
    }

    private static void runRecoveryHop(
            AiRecommender ai, AuraDb db, String profileId, String userQuery, List<String> filters,
            JSONObject plan, int minModernYear, List<JSONObject> rawPool, List<JSONObject> deMerged) {
        if (userQuery == null || userQuery.trim().isEmpty()) return;
        AgentStatusNarrator.publishLiveStep("Expanding search for exact matches");
        String raw = ai.callGeminiJson(buildRecoveryHopPrompt(db, profileId, userQuery, filters, rawPool));
        JSONArray extraTitles = extractRecoveryTitles(raw);
        if (extraTitles == null || extraTitles.length() == 0) return;
        JSONObject[] hits = lookupTitlesInParallel(extraTitles, Math.min(12, extraTitles.length()));
        mergeRecoveryHits(db, profileId, userQuery, filters, plan, minModernYear, hits, deMerged);
    }

    private static void mergeRecoveryHits(
            AuraDb db, String profileId, String userQuery, List<String> filters,
            JSONObject plan, int minModernYear, JSONObject[] hits, List<JSONObject> deMerged) {
        List<JSONObject> extraRaw = new ArrayList<>();
        appendLookupArray(hits, new HashSet<>(), extraRaw);
        List<JSONObject> extraValid = deMergeCandidates(db, profileId, userQuery, filters, plan, minModernYear, extraRaw, false);
        Set<String> existing = new HashSet<>();
        for (JSONObject d : deMerged) existing.add(AuraDb.normalizeTitleKey(d.optString("title", "")));
        for (JSONObject e : extraValid) {
            if (existing.add(AuraDb.normalizeTitleKey(e.optString("title", "")))) deMerged.add(e);
        }
    }

    private static String buildRecoveryHopPrompt(
            AuraDb db, String profileId, String userQuery, List<String> filters, List<JSONObject> alreadyChecked) {
        List<String> checkedNames = new ArrayList<>();
        for (int i = 0; i < alreadyChecked.size() && i < 20; i++) checkedNames.add(alreadyChecked.get(i).optString("title", ""));
        String region = RegionProviderRegistry.getRegionDisplayName(db);
        String apps = RegionProviderRegistry.getProviderDisplayListForRegion(db);
        return "You are Aura's Multi-Hop Search Recovery Agent for " + region + " streaming apps (" + apps + ").\n" +
            "User Query: \"" + userQuery + "\"\nActive Filters: " + filters + "\nProfile Taste: " + db.getProfileTaste(profileId) + "\n" +
            "Already Checked Titles: " + checkedNames + "\n" +
            "Task: Propose 12 ADDITIONAL exact Movie/Show titles (NOT in Already Checked) that strictly satisfy ALL constraints of the User Query (format, genre blend, tone, actor/show connection, runtime, negative constraints) and are available on streaming platforms in " + region + ".\n" +
            "Return ONLY a JSON object: {\"titles\": [\"Title 1\", \"Title 2\", ...]}.";
    }

    private static JSONArray extractRecoveryTitles(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            String t = raw.trim();
            if (t.startsWith("[")) return new JSONArray(t);
            JSONObject o = new JSONObject(t);
            return o.optJSONArray("titles") != null ? o.optJSONArray("titles") : o.optJSONArray("primary_queries");
        } catch (Exception e) {
            return null;
        }
    }

    public static List<String> formatAndCachePoolSummaries(AuraDb db, List<JSONObject> pool) {
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < pool.size() && i < 26; i++) {
            JSONObject o = pool.get(i);
            String t = o.optString("title", "").trim();
            if (t.isEmpty()) continue;
            db.saveCachedGti("jw_imdb_v6:" + t.toLowerCase(Locale.US), o.toString());
            summaries.add(formatSingleCandidateSummary(o, t));
        }
        return summaries;
    }

    private static String formatSingleCandidateSummary(JSONObject o, String title) {
        String syn = o.optString("synopsis", "");
        if (syn.length() > 95) syn = syn.substring(0, 95) + "...";
        String cast = o.optString("cast", "");
        return title + " (" + o.optInt("year") + ", " + o.optString("type") + ", " + o.optString("genres") +
            ", " + o.optString("country") + ", " + o.optString("rating") + ", " + o.optString("app") +
            ", " + o.optInt("runtime_min", 100) + "m" + (cast.isEmpty() ? "" : ", Cast=" + cast) + ") — " + syn;
    }

    private static JSONObject[] awaitArray(Future<JSONObject[]> fut, long ms) {
        try { return fut.get(ms, TimeUnit.MILLISECONDS); } catch (Exception e) { return new JSONObject[0]; }
    }

    private static JSONArray awaitJson(Future<JSONArray> fut, long ms) {
        try { return fut.get(ms, TimeUnit.MILLISECONDS); } catch (Exception e) { return new JSONArray(); }
    }
}
