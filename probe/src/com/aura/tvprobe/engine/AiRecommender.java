package com.aura.tvprobe;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 4-Stage SOTA Recommendation & Ranking Engine for Aura TV:
 * Stage 1: Temporal User Taste Vector (engagement-weighted anchors, genre & language affinity)
 * Stage 2: 4-Channel Parallel Retrieval (Co-Watch Graph + Structured Genre Discovery + LLM Semantic + Fresh Hits)
 * Stage 3: Hybrid Multi-Factor Scoring (Relatedness + Bayesian IMDb Votes + Recency + Language + OTT Affinity)
 * Stage 4: Maximal Marginal Relevance (MMR) Diversity Reranking
 */
public class AiRecommender {
    public interface AgentProgressListener {
        void onStep(String stepMessage);
    }

    private static final String[] MODELS = {
        "gemini-flash-lite-latest",
        "gemini-flash-latest"
    };
    private static final Object REFRESH_LOCK = new Object();
    private static volatile long lastRefreshEndMs = 0L;
    private final AuraDb db;

    public AiRecommender(AuraDb db) {
        this.db = db;
    }

    private String getApiKey() {
        return db.getSetting("gemini_api_key", "").trim();
    }

    public JSONArray getOrRefreshForProfile(String profileId, JSONObject nowPlaying, JSONArray history, boolean forceManual) {
        return getOrRefreshForProfile(profileId, nowPlaying, history, forceManual, null);
    }

    public JSONArray getOrRefreshForProfile(
        String profileId, JSONObject nowPlaying, JSONArray history, boolean forceManual, AgentProgressListener progress
    ) {
        long entryMs = System.currentTimeMillis();
        synchronized (REFRESH_LOCK) {
            return runLockedRefresh(profileId, history, forceManual, progress, entryMs);
        }
    }

    private JSONArray runLockedRefresh(
        String profileId, JSONArray history, boolean forceManual, AgentProgressListener progress, long entryMs
    ) {
        db.purgeUnreleasedFromRecos();
        JSONArray cached = TasteIntelligenceEngine.filterOutDismissed(db, profileId, db.getProfileRecommendations(profileId));
        if (forceManual) {
            JSONArray swapped = trySwapReserveAndWarmNext(profileId, history);
            if (swapped != null) return swapped;
        }
        if (cached.length() >= 6 && (lastRefreshEndMs > entryMs || (!forceManual && db.isRecoCacheFresh(profileId)))) {
            ReserveSlateManager.warmNextReserveAsync(db, this, profileId, history);
            return cached;
        }
        return finishFreshPipelineAndWarmReserve(profileId, history, progress, cached);
    }

    private JSONArray trySwapReserveAndWarmNext(String profileId, JSONArray history) {
        JSONArray swapped = ReserveSlateManager.tryPopFreshReserveSlate(db, profileId);
        if (swapped == null) return null;
        lastRefreshEndMs = System.currentTimeMillis();
        ReserveSlateManager.warmNextReserveAsync(db, this, profileId, history);
        return swapped;
    }

    private JSONArray finishFreshPipelineAndWarmReserve(
            String profileId, JSONArray history, AgentProgressListener progress, JSONArray cached) {
        JSONArray out = finalizeRecommendations(profileId, executeRefreshPipeline(profileId, history, progress), cached);
        lastRefreshEndMs = System.currentTimeMillis();
        ReserveSlateManager.warmNextReserveAsync(db, this, profileId, history);
        return out;
    }

    public JSONArray generateNextEpochSlateWithoutSaving(String profileId, JSONArray history) {
        synchronized (REFRESH_LOCK) {
            return executeRefreshPipeline(profileId, history, null);
        }
    }

    private JSONArray executeRefreshPipeline(String profileId, JSONArray history, AgentProgressListener progress) {
        AgentStatusNarrator narrator = AgentStatusNarrator.start(this, db, profileId, "", progress);
        try {
            int currentYear = JustWatchGraph.getCurrentYear();
            int minModernYear = JustWatchGraph.getDefaultMinModernYear();
            int epoch = ImpressionTracker.advanceEpoch(db, profileId);
            JSONObject seedPlan = resolveSeedPlan(profileId, collectWatchedTitles(history), minModernYear, currentYear);
            return buildSotaRecommendationsForEpoch(profileId, seedPlan, minModernYear, currentYear, epoch);
        } finally {
            narrator.stop();
        }
    }

    private JSONObject resolveSeedPlan(String profileId, List<String> watchedList, int minModernYear, int currentYear) {
        return !getApiKey().isEmpty()
            ? planAgentSearchStrategy(profileId, "", watchedList, minModernYear, currentYear)
            : buildFastSeedPlan(profileId, "");
    }

    private JSONArray finalizeRecommendations(String profileId, JSONArray sotaRanked, JSONArray cached) {
        if (sotaRanked.length() >= 8) {
            db.saveProfileRecommendations(profileId, sotaRanked);
            return sotaRanked;
        }
        return cached.length() > 0 ? cached : getBuiltInSotaSeedCatalog(profileId);
    }

    public JSONArray buildSotaRecommendations(String profileId, JSONObject seedPlan, int minModernYear, int currentYear) {
        int epoch = ImpressionTracker.advanceEpoch(db, profileId);
        return buildSotaRecommendationsForEpoch(profileId, seedPlan, minModernYear, currentYear, epoch);
    }

    private JSONArray buildSotaRecommendationsForEpoch(
            String profileId, JSONObject seedPlan, int minModernYear, int currentYear, int epoch) {
        final UserTasteProfile taste = new UserTasteProfile(db, profileId, epoch);
        final Set<String> seenKeys = initSeenTitleKeys(profileId, taste);
        AgentStatusNarrator.publishLiveStep("Curating elite picks for your taste");
        List<JSONObject> curated = TasteCuratedSlateEngine.curateAndVerifyElitePicks(this, db, profileId, taste, seenKeys, currentYear);
        if (curated.size() >= 10) return finalizeCuratedEliteSlate(profileId, curated, taste, currentYear, epoch);
        return runFallbackScatterGatherSlate(profileId, seedPlan, minModernYear, currentYear, epoch, taste, seenKeys, curated);
    }

    private JSONArray finalizeCuratedEliteSlate(
            String profileId, List<JSONObject> curated, UserTasteProfile taste, int currentYear, int epoch) {
        scoreAndRerankPool(curated, taste, currentYear);
        List<JSONObject> balanced = TasteCuratedSlateEngine.enforceTasteBalance(curated, 18, 4);
        ImpressionTracker.recordSlateImpressions(db, epoch, balanced.subList(0, Math.min(16, balanced.size())));
        return packRankedItems(balanced);
    }

    private JSONArray runFallbackScatterGatherSlate(
            String profileId, JSONObject seedPlan, int minModernYear, int currentYear, int epoch,
            UserTasteProfile taste, Set<String> seenKeys, List<JSONObject> initialPool) {
        final List<JSONObject> pool = Collections.synchronizedList(new ArrayList<>(initialPool));
        runParallelCandidateRetrieval(taste, seedPlan, minModernYear, currentYear, seenKeys, pool);
        List<JSONObject> deMerged = AgenticSearchOrchestrator.deMergeCandidates(db, profileId, "", db.getActiveFilters(profileId), seedPlan, minModernYear, pool, false);
        scoreAndRerankPool(deMerged, taste, currentYear);
        List<JSONObject> slate = TasteCuratedSlateEngine.enforceTasteBalance(filterAndRerankPoolWithLlm(profileId, deMerged, epoch), 18, 4);
        ImpressionTracker.recordSlateImpressions(db, epoch, slate.subList(0, Math.min(6, slate.size())));
        return packRankedItems(slate);
    }

    private Set<String> initSeenTitleKeys(String profileId, UserTasteProfile taste) {
        Set<String> seenKeys = Collections.synchronizedSet(new HashSet<>(db.getAllExcludedTitleKeys()));
        seenKeys.addAll(db.getHistoryTitleKeys(profileId));
        seenKeys.addAll(TasteIntelligenceEngine.getDismissedTitleKeys(db, profileId));
        for (String a : taste.getAnchors()) {
            seenKeys.add(AuraDb.normalizeTitleKey(a));
        }
        return seenKeys;
    }

    private void runParallelCandidateRetrieval(
            UserTasteProfile taste, JSONObject seedPlan, int minModernYear, int currentYear,
            Set<String> seenKeys, List<JSONObject> pool) {
        ExecutorService exec = Executors.newFixedThreadPool(10);
        submitAppBroadcastTrendTasks(exec, taste, seenKeys, pool);
        submitDynamicSpecTasks(exec, taste, seedPlan, seenKeys, pool);
        submitCoWatchTasks(exec, taste, seedPlan, minModernYear, seenKeys, pool);
        submitPlannedQueryTasks(exec, taste, seedPlan, minModernYear, seenKeys, pool);
        submitStructuredDiscoveryTasks(exec, taste, minModernYear, currentYear, seenKeys, pool);
        exec.shutdown();
        try { exec.awaitTermination(8, TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    private void submitAppBroadcastTrendTasks(
            ExecutorService exec, UserTasteProfile taste,
            Set<String> seenKeys, List<JSONObject> pool) {
        JSONArray trends = db.getAppBroadcastTrends();
        if (trends.length() == 0) return;
        int offset = Math.abs(taste.getCurrentEpoch() * 5) % Math.max(1, trends.length());
        for (int i = 0; i < Math.min(14, trends.length()); i++) {
            final int rankIdx = i;
            final JSONObject tObj = trends.optJSONObject((offset + i) % trends.length());
            if (tObj != null) exec.submit(() -> retrieveBroadcastTrendCandidate(tObj, rankIdx, taste, seenKeys, pool));
        }
    }

    private void retrieveBroadcastTrendCandidate(
            JSONObject trend, int rankIdx, UserTasteProfile taste,
            Set<String> seenKeys, List<JSONObject> pool) {
        String title = trend.optString("title", "").trim();
        if (title.isEmpty() || seenKeys.contains(AuraDb.normalizeTitleKey(title))) return;
        JSONObject hit = JustWatchGraph.lookupTitle(title);
        if (hit == null) hit = buildDirectBroadcastCandidate(trend);
        if (hit == null) return;
        overlayBroadcastMetadata(hit, trend, rankIdx);
        addSotaCandidate(hit, null, Math.max(0.92, 1.18 - rankIdx * 0.025), taste, seenKeys, pool);
    }

    static JSONObject buildDirectBroadcastCandidate(JSONObject trend) {
        String poster = trend.optString("poster_url", "").trim();
        String uri = trend.optString("launch_uri", "").trim();
        if (poster.isEmpty() || uri.isEmpty()) return null;
        try {
            return populateDirectBroadcastJson(trend, poster, uri);
        } catch (Exception e) { return null; }
    }

    private static JSONObject populateDirectBroadcastJson(JSONObject trend, String poster, String uri) throws Exception {
        String title = trend.optString("title", "").trim();
        String app = trend.optString("app", "TV");
        String gCodes = inferBroadcastGenreCodes(title, trend.optString("channel", ""));
        return new JSONObject().put("title", title).put("app", app).put("badge", app)
            .put("launch_pkg", trend.optString("app_pkg", "")).put("launch_uri", uri)
            .put("poster_url", poster).put("backdrop_url", poster).put("has_flatrate_offer", true)
            .put("year", JustWatchGraph.getCurrentYear()).put("type", "spt".equals(gCodes) ? "Live / Sports" : "Series")
            .put("genres", "spt".equals(gCodes) ? "Sports, Live" : "Trending").put("genre_codes", gCodes)
            .put("synopsis", trend.optString("synopsis", "Trending now on " + app)).put("imdb_raw", 0.0).put("imdb_votes", 0);
    }

    private static String inferBroadcastGenreCodes(String title, String channel) {
        String low = (title + " " + channel).toLowerCase(Locale.US);
        if (low.contains(" vs ") || low.contains("cricket") || low.contains("match") || low.contains("ipl")
                || low.contains("football") || low.contains("f1") || low.contains("sport") || low.contains("messi")) {
            return "spt";
        }
        return "drm,trl";
    }

    private void overlayBroadcastMetadata(JSONObject hit, JSONObject trend, int rankIdx) {
        try {
            String uri = trend.optString("launch_uri", "");
            if (!uri.isEmpty()) hit.put("launch_uri", uri);
            String app = trend.optString("app", "");
            String ch = trend.optString("channel", "Trending Now");
            if (!app.isEmpty()) hit.put("app", app).put("badge", app);
            hit.put("has_flatrate_offer", true);
            hit.put("broadcast_badge", ch + (app.isEmpty() ? "" : (" on " + app)));
            hit.put("source_channel", "app_trending").put("llm_rank", rankIdx + 2);
        } catch (Exception ignored) {}
    }

    private void submitDynamicSpecTasks(
            ExecutorService exec, UserTasteProfile taste, JSONObject seedPlan,
            Set<String> seenKeys, List<JSONObject> pool) {
        JSONArray specs = seedPlan != null ? seedPlan.optJSONArray("queries") : null;
        if (specs == null) return;
        for (int i = 0; i < specs.length() && i < 6; i++) {
            final JSONObject spec = specs.optJSONObject(i);
            final double weight = Math.max(0.85, 1.15 - i * 0.05);
            if (spec != null) exec.submit(() -> retrieveDynamicSpecHits(spec, weight, taste, seenKeys, pool));
        }
    }

    private void retrieveDynamicSpecHits(
            JSONObject spec, double weight, UserTasteProfile taste,
            Set<String> seenKeys, List<JSONObject> pool) {
        JSONArray hits = JustWatchGraph.executeDynamicQuerySpec(spec);
        String verifiedAnchor = resolveVerifiedSpecAnchor(spec, taste);
        int added = 0;
        for (int k = 0; k < hits.length() && added < 10; k++) {
            if (addSotaCandidate(hits.optJSONObject(k), verifiedAnchor, weight, taste, seenKeys, pool)) added++;
        }
    }

    private String resolveVerifiedSpecAnchor(JSONObject spec, UserTasteProfile taste) {
        String raw = spec.optString("seedAnchor", spec.optString("searchQuery", "")).trim();
        if (raw.isEmpty()) return null;
        if (db.isTitleWatched(raw) || db.getAllExcludedTitleKeys().contains(AuraDb.normalizeTitleKey(raw))) return raw;
        for (String a : taste.getAnchors()) {
            if (a.equalsIgnoreCase(raw)) return a;
        }
        return null;
    }

    private void submitCoWatchTasks(
            ExecutorService exec, UserTasteProfile taste, JSONObject seedPlan, int minModernYear,
            Set<String> seenKeys, List<JSONObject> pool) {
        List<String> anchors = mergePlannedTraversalSeeds(taste.getAnchors(), seedPlan);
        for (int i = 0; i < anchors.size() && i < 6; i++) {
            final String anchor = anchors.get(i);
            final double weight = Math.max(0.75, 1.15 - i * 0.06);
            exec.submit(() -> retrieveAnchorNeighbors(anchor, weight, taste, minModernYear, seenKeys, pool));
        }
    }

    private List<String> mergePlannedTraversalSeeds(List<String> baseAnchors, JSONObject seedPlan) {
        List<String> merged = new ArrayList<>(baseAnchors);
        JSONArray planned = seedPlan != null ? seedPlan.optJSONArray("watched_traversal_seeds") : null;
        if (planned == null) return merged;
        for (int i = 0; i < planned.length(); i++) {
            String s = planned.optString(i).trim();
            if (!s.isEmpty() && !merged.contains(s)) merged.add(s);
        }
        return merged;
    }

    private void retrieveAnchorNeighbors(
            String anchor, double weight, UserTasteProfile taste, int minModernYear,
            Set<String> seenKeys, List<JSONObject> pool) {
        JSONArray sim = JustWatchGraph.fetchCollaborativeSimilar(anchor, minModernYear);
        recordFirstNeighborSeedMeta(sim, taste, weight);
        int added = 0;
        for (int k = 0; k < sim.length() && added < 5; k++) {
            JSONObject cand = sim.optJSONObject(k);
            boolean isDirect = cand != null && cand.optBoolean("is_direct_sim", false);
            if (addSotaCandidate(cand, isDirect ? anchor : null, isDirect ? weight : 0.85, taste, seenKeys, pool)) added++;
        }
    }

    private void recordFirstNeighborSeedMeta(JSONArray sim, UserTasteProfile taste, double weight) {
        if (sim.length() == 0) return;
        JSONObject first = sim.optJSONObject(0);
        if (first != null) {
            taste.recordSeedMetadata(first.optString("seed_country", ""), first.optString("seed_genres", ""), weight);
        }
    }

    private static final String[][] STRUCTURED_BUCKETS = {
        {"scf", "trl"}, {"crm", "trl"}, {"drm", "trl"}, {"scf", "drm"}, {"crm", "drm"}, {"act", "trl"}
    };

    private void submitStructuredDiscoveryTasks(
            ExecutorService exec, UserTasteProfile taste, int minModernYear, int currentYear,
            Set<String> seenKeys, List<JSONObject> pool) {
        int startYear = Math.max(2019, minModernYear - (taste.getCurrentEpoch() % 3));
        for (final String[] bucket : STRUCTURED_BUCKETS) {
            exec.submit(() -> retrieveStructuredBucket(bucket, startYear, currentYear, taste, seenKeys, pool));
        }
    }

    private void retrieveStructuredBucket(
            String[] bucket, int startYear, int currentYear, UserTasteProfile taste,
            Set<String> seenKeys, List<JSONObject> pool) {
        JSONArray hits = JustWatchGraph.fetchStructuredGenreCandidates(Arrays.asList(bucket), null, startYear, currentYear);
        for (int k = 0; k < hits.length() && k < 10; k++) {
            addSotaCandidate(hits.optJSONObject(k), null, 0.65, taste, seenKeys, pool);
        }
    }

    private void submitPlannedQueryTasks(
            ExecutorService exec, UserTasteProfile taste, JSONObject seedPlan, int minModernYear,
            Set<String> seenKeys, List<JSONObject> pool) {
        JSONArray pq = seedPlan != null ? seedPlan.optJSONArray("primary_queries") : null;
        if (pq == null) return;
        for (int i = 0; i < pq.length() && i < 10; i++) {
            final int rankIdx = i;
            final String q = pq.optString(i).trim();
            if (!q.isEmpty() && !taste.getAnchors().contains(q)) {
                exec.submit(() -> retrievePlannedQueryHits(q, rankIdx, minModernYear, taste, seenKeys, pool));
            }
        }
    }

    private void retrievePlannedQueryHits(
            String query, int rankIdx, int minModernYear, UserTasteProfile taste,
            Set<String> seenKeys, List<JSONObject> pool) {
        JSONObject direct = JustWatchGraph.lookupTitle(query);
        if (direct != null && direct.optBoolean("has_flatrate_offer", false)) {
            markAsLlmPlannedCandidate(direct, rankIdx);
            addSotaCandidate(direct, null, Math.max(0.85, 1.15 - rankIdx * 0.04), taste, seenKeys, pool);
            return;
        }
        JSONArray hits = JustWatchGraph.fetchDeterministicSearchCandidates(query, minModernYear);
        for (int k = 0; k < hits.length() && k < 2; k++) {
            addSotaCandidate(hits.optJSONObject(k), null, Math.max(0.85, 1.10 - rankIdx * 0.05), taste, seenKeys, pool);
        }
    }

    private void markAsLlmPlannedCandidate(JSONObject hit, int rankIdx) {
        try {
            hit.put("source_channel", "llm_planned");
            hit.put("llm_rank", rankIdx);
        } catch (Exception ignored) {}
    }

    private void scoreAndRerankPool(List<JSONObject> pool, UserTasteProfile taste, int currentYear) {
        taste.ensureDefaultGenreWeightsIfEmpty();
        String prof = db.getValidActiveProfile();
        Map<String, Double> negG = TasteIntelligenceEngine.buildNegativeGenrePenalties(db, prof);
        Set<String> negS = TasteIntelligenceEngine.buildNegativeSeedSet(db, prof);
        Map<String, ImpressionTracker.ImpressionRecord> imp = ImpressionTracker.loadActiveImpressions(db);
        for (JSONObject item : pool) {
            applyHybridAndNegativeScore(item, taste, currentYear, negG, negS, imp);
        }
        pool.sort((a, b) -> Double.compare(b.optDouble("sota_score", 0.0), a.optDouble("sota_score", 0.0)));
    }

    private void applyHybridAndNegativeScore(
            JSONObject item, UserTasteProfile taste, int currentYear, Map<String, Double> negG,
            Set<String> negS, Map<String, ImpressionTracker.ImpressionRecord> imp) {
        double score = HybridScorer.computeHybridScore(item, taste, currentYear)
            + TasteIntelligenceEngine.computeNegativeAndSkipPenalty(item, negG, negS, imp);
        try { item.put("sota_score", score); } catch (Exception ignored) {}
    }

    private JSONArray packRankedItems(List<JSONObject> items) {
        JSONArray out = new JSONArray();
        for (JSONObject item : items) out.put(item);
        return out;
    }

    public JSONArray buildDeterministicRecommendations(String profileId, int minModernYear, int currentYear) {
        return buildSotaRecommendations(profileId, buildFastSeedPlan(profileId, ""), minModernYear, currentYear);
    }

    private boolean addSotaCandidate(
            JSONObject o, String seedAnchor, double anchorWeight,
            UserTasteProfile taste, Set<String> seenKeys, List<JSONObject> pool) {
        if (!isEligibleCandidate(o, seedAnchor, taste, seenKeys)) return false;
        String t = o.optString("title").trim();
        String norm = AuraDb.normalizeTitleKey(t);
        String slugKey = o.optString("slug_key", "");
        if (!seenKeys.add(norm)) return false;
        if (!slugKey.isEmpty()) seenKeys.add(slugKey);
        enrichAndStoreCandidate(o, t, seedAnchor, anchorWeight, pool);
        return true;
    }

    private boolean isEligibleCandidate(
            JSONObject o, String seedAnchor, UserTasteProfile taste, Set<String> seenKeys) {
        if (o == null || isAlreadySeenOrWatched(o, seenKeys)) return false;
        if (!passesQualityAndAnimationGate(o, seedAnchor, taste)) return false;
        if (taste.isBlockedByKidsSafety(o.optString("genre_codes", ""))) return false;
        return taste.passesLocalShovelwareGate(
            o.optString("country", ""), o.optInt("imdb_votes", 0),
            o.optDouble("tmdb_popularity", 0.0), o.optInt("year", 0), JustWatchGraph.getCurrentYear());
    }

    private boolean isAlreadySeenOrWatched(JSONObject o, Set<String> seenKeys) {
        String t = o.optString("title").trim();
        String norm = AuraDb.normalizeTitleKey(t);
        String slugKey = o.optString("slug_key", "");
        return t.isEmpty() || seenKeys.contains(norm) || (!slugKey.isEmpty() && seenKeys.contains(slugKey));
    }

    private boolean passesQualityAndAnimationGate(JSONObject o, String seedAnchor, UserTasteProfile taste) {
        if ("app_trending".equals(o.optString("source_channel"))) {
            return passesAppTrendingGate(o, taste);
        }
        boolean hasSeed = seedAnchor != null && !seedAnchor.isEmpty();
        boolean isCurated = "llm_planned".equals(o.optString("source_channel"));
        int minYear = (hasSeed || isCurated) ? TasteCuratedSlateEngine.CLASSIC_MIN_YEAR : JustWatchGraph.getDefaultMinModernYear();
        boolean valid = hasSeed
            ? CandidateValidator.isValidCoWatchCandidate(o, minYear, 7.2)
            : CandidateValidator.isValidCatalogCandidate(o, minYear, 7.2);
        return valid && !CandidateValidator.isUnwantedAnimation(o.optString("genre_codes", ""), taste.isAnimeEnabled());
    }

    private boolean passesAppTrendingGate(JSONObject o, UserTasteProfile taste) {
        if (o.optString("poster_url").isEmpty()) return false;
        if (CandidateValidator.isUnwantedAnimation(o.optString("genre_codes", ""), taste.isAnimeEnabled())) return false;
        double imdb = o.optDouble("imdb_raw", o.optDouble("imdb_score", 0.0));
        return imdb == 0.0 || imdb >= 6.4;
    }

    private void enrichAndStoreCandidate(
            JSONObject o, String title, String seedAnchor, double anchorWeight, List<JSONObject> pool) {
        try {
            double imdb = o.optDouble("imdb_raw", o.optDouble("imdb_score", 0.0));
            o.put("imdb_score", imdb);
            tagCandidateSourceChannel(o, seedAnchor, anchorWeight);
            o.put("reason", buildSotaReasonString(o, seedAnchor, imdb, o.optInt("imdb_votes", 0)));
            db.saveCachedGti("jw_imdb_v6:" + title.toLowerCase(Locale.US), o.toString());
            pool.add(o);
        } catch (Exception ignored) {}
    }

    private void tagCandidateSourceChannel(JSONObject o, String seedAnchor, double anchorWeight) throws Exception {
        String src = o.optString("source_channel");
        if (seedAnchor != null && !seedAnchor.isEmpty()) {
            o.put("seed_anchor", seedAnchor).put("anchor_weight", anchorWeight);
            o.put("source_channel", "co_watch").put("is_direct_sim", true).put("sim_score", anchorWeight * 10.0);
        } else if ("llm_planned".equals(src) || "app_trending".equals(src)) {
            o.put("anchor_weight", anchorWeight).put("sim_score", anchorWeight * 9.5);
        } else {
            o.put("source_channel", "structured").put("sim_score", anchorWeight * 5.0);
        }
    }

    private String buildSotaReasonString(JSONObject o, String seedAnchor, double imdb, int votes) {
        return HybridScorer.formatReason(o, seedAnchor, imdb, votes);
    }

    public JSONObject buildFastSeedPlan(String profileId, String userQuery) {
        JSONObject plan = new JSONObject();
        try {
            JSONArray pq = new JSONArray(), seeds = new JSONArray();
            addQueryToSeedPlan(userQuery, pq, seeds);
            if (extractSimilaritySeed(userQuery).isEmpty()) {
                appendProfileAnchorsToSeedPlan(profileId, pq, seeds);
            }
            plan.put("primary_queries", pq).put("watched_traversal_seeds", seeds);
        } catch (Exception ignored) {}
        return plan;
    }

    private void appendProfileAnchorsToSeedPlan(String profileId, JSONArray pq, JSONArray seeds) {
        int epoch = ImpressionTracker.getCurrentEpoch(db, profileId);
        for (String a : db.getRotatingTasteAnchors(profileId, epoch)) {
            if (seeds.length() < 5) seeds.put(a);
            if (pq.length() < 5) pq.put(a);
        }
    }

    public static String extractSimilaritySeed(String userQuery) {
        if (userQuery == null || userQuery.trim().isEmpty()) return "";
        String q = userQuery.trim();
        String stripped = q.replaceAll("(?i)^(?:show me |find |recommend |search |watch )?(?:shows and movies |movies and shows |movies |movie |shows |show |series |films |film |titles |something |anything )?(?:similar to|more like|like)\\s+", "").trim();
        String cand = (!stripped.equalsIgnoreCase(q) && !stripped.isEmpty()) ? stripped : extractTrailingLikeClause(q);
        return isCompoundConstraintSeed(cand) ? "" : cand;
    }

    private static String extractTrailingLikeClause(String q) {
        int idx = q.toLowerCase(Locale.US).lastIndexOf(" like ");
        return idx >= 0 ? q.substring(idx + 6).trim() : "";
    }

    private static boolean isCompoundConstraintSeed(String s) {
        if (s == null || s.isEmpty() || s.split("\\s+").length > 5) return true;
        String low = " " + s.toLowerCase(Locale.US) + " ";
        return s.contains(",") || s.contains(";") || low.contains(" but ") || low.contains(" or ")
            || low.contains(" with ") || low.contains(" without ") || low.contains(" where ")
            || low.contains(" starring ") || low.contains(" set in ") || low.contains(" under ");
    }

    private void addQueryToSeedPlan(String userQuery, JSONArray pq, JSONArray seeds) {
        if (userQuery == null || userQuery.trim().isEmpty() || isGenericNextOrTasteQuery(userQuery)) return;
        String cleanQ = userQuery.trim();
        String simSeed = extractSimilaritySeed(cleanQ);
        String target = !simSeed.isEmpty() ? simSeed : cleanQ;
        if (!target.isEmpty() && !target.contains("->")) {
            seeds.put(target);
            pq.put(target);
        } else {
            pq.put(cleanQ);
        }
    }

    /**
     * Triggered when the user clicks "Explore Similar".
     * Uses SOTA Item-to-Item Similarity + Genre Jaccard + Bayesian Vote Confidence.
     */
    public JSONArray traverseSimilarToWatched(String profileId, String seedTitle, AgentProgressListener progress) {
        AgentStatusNarrator narrator = AgentStatusNarrator.start(this, db, profileId, "Similar to " + seedTitle, progress);
        try {
            int minModernYear = JustWatchGraph.getDefaultMinModernYear();
            JSONArray sim = JustWatchGraph.fetchCollaborativeSimilar(seedTitle, minModernYear);
            JSONArray filtered = filterAndFormatSimilarHits(sim, seedTitle);
            if (filtered.length() == 0) appendFallbackSimilarHits(profileId, seedTitle, filtered);
            return filtered;
        } finally {
            narrator.stop();
        }
    }

    private JSONArray filterAndFormatSimilarHits(JSONArray sim, String seedTitle) {
        JSONArray filtered = new JSONArray();
        for (int i = 0; i < sim.length(); i++) {
            JSONObject o = sim.optJSONObject(i);
            if (o != null && tryFormatSimilarHit(o, seedTitle)) filtered.put(o);
        }
        return filtered;
    }

    private boolean tryFormatSimilarHit(JSONObject o, String seedTitle) {
        String t = o.optString("title", "");
        if (t.isEmpty() || db.isTitleWatched(t)) return false;
        double sc = o.optDouble("imdb_raw", o.optDouble("imdb_score", 0.0));
        try { o.put("reason", buildSotaReasonString(o, seedTitle, sc, o.optInt("imdb_votes", 0))); } catch (Exception ignored) {}
        db.saveCachedGti("jw_imdb_v6:" + t.trim().toLowerCase(), o.toString());
        return true;
    }

    private void appendFallbackSimilarHits(String profileId, String seedTitle, JSONArray filtered) {
        JSONArray fallback = getBuiltInSotaSeedCatalog(profileId);
        String normSeed = AuraDb.normalizeTitleKey(seedTitle);
        for (int i = 0; i < fallback.length(); i++) {
            JSONObject o = fallback.optJSONObject(i);
            if (o == null || AuraDb.normalizeTitleKey(o.optString("title", "")).equals(normSeed)) continue;
            addClonedFallbackSimilarHit(o, seedTitle, filtered);
        }
    }

    private void addClonedFallbackSimilarHit(JSONObject o, String seedTitle, JSONArray filtered) {
        try {
            JSONObject copy = new JSONObject(o.toString());
            copy.put("reason", buildSotaReasonString(copy, seedTitle, copy.optDouble("imdb_score", 8.0), copy.optInt("imdb_votes", 50000)));
            filtered.put(copy);
        } catch (Exception ignored) {}
    }

    public JSONObject planAgentSearchStrategy(
        String profileId, String userQuery, List<String> watchedList, int minModernYear, int currentYear
    ) {
        String plannerPrompt = buildPlannerPrompt(profileId, userQuery, watchedList, minModernYear, currentYear);
        String raw = callGeminiJsonFast(plannerPrompt);
        if (raw != null) {
            JSONObject parsed = tryParsePlannerResponse(profileId, raw);
            if (parsed != null) return parsed;
        }
        return buildFastSeedPlan(profileId, userQuery);
    }

    private JSONObject tryParsePlannerResponse(String profileId, String raw) {
        try {
            JSONObject plan = new JSONObject(raw);
            AgentStatusNarrator.publishPlanSteps(plan);
            savePlannerArtifacts(profileId, plan);
            return plan;
        } catch (Exception e) {
            return null;
        }
    }

    private void savePlannerArtifacts(String profileId, JSONObject plan) {
        JSONArray chips = plan.optJSONArray("traversal_chips");
        if (chips != null && chips.length() > 0) db.saveTraversalChips(profileId, chips);
        String hypo = plan.optString("taste_hypothesis", "").trim();
        if (!hypo.isEmpty()) db.setSetting("evolved_taste_memo_" + profileId, hypo);
    }

    public JSONArray buildRichWatchedContext(String profileId) {
        List<String> anchors = db.getDynamicTasteAnchors(profileId);
        final JSONObject[] entries = new JSONObject[Math.min(6, anchors.size())];
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, entries.length));
        for (int i = 0; i < entries.length; i++) {
            final int idx = i;
            final String title = anchors.get(i);
            pool.submit(() -> entries[idx] = buildRichAnchorEntry(title));
        }
        pool.shutdown();
        try { pool.awaitTermination(2500, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
        return packNonNullEntries(entries);
    }

    private JSONArray packNonNullEntries(JSONObject[] entries) {
        JSONArray rich = new JSONArray();
        for (JSONObject e : entries) {
            if (e != null) rich.put(e);
        }
        return rich;
    }

    private JSONObject buildRichAnchorEntry(String title) {
        try {
            JSONObject meta = fetchOpenMetadata(title);
            String syn = meta.optString("synopsis", "");
            if (syn.length() > 160) syn = syn.substring(0, 160) + "...";
            return new JSONObject().put("title", meta.optString("title", title))
                .put("type", meta.optString("type", "Series"))
                .put("genres", meta.optString("genres", "Drama"))
                .put("country", meta.optString("country", ""))
                .put("synopsis", syn);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildPlannerPrompt(
        String profileId, String userQuery, List<String> watchedList, int minModernYear, int currentYear
    ) {
        boolean isSearch = userQuery != null && !userQuery.trim().isEmpty();
        int epoch = ImpressionTracker.getCurrentEpoch(db, profileId);
        List<String> recentSuggested = ImpressionTracker.getRecentCooldownKeys(db, epoch);
        String personalGuide = db.getProfileTaste(profileId);
        String richHistStr = isSearch ? db.getRotatingTasteAnchors(profileId, epoch).toString() : buildRichWatchedContext(profileId).toString();
        return "You are Aura's Unified Dynamic JustWatch GraphQL Query & Filter Planner Agent. Current Date: " + JustWatchGraph.getCurrentDateString() + ".\n" +
            "Profile: '" + db.getProfileLabel(profileId) + "'. Personal Guide AI: " + (personalGuide.isEmpty() ? "(None)" : personalGuide) + "\n" +
            "Structured Taste DNA: " + TasteIntelligenceEngine.getTasteDnaPromptSummary(db, profileId) + "\n" +
            "User Ratings Feedback: " + db.getRatingFeedbackSummary() + "\n" +
            "Recent User Search Curiosity: " + extractRecentSearchCuriosity(profileId) + "\n" +
            "Previous Evolved Taste Memo: " + db.getSetting("evolved_taste_memo_" + profileId, "(Initial Evolution Round)") + "\n" +
            "Active Filters: " + db.getActiveFilters(profileId).toString() + "\n" +
            "Refresh Epoch #" + epoch + " Evolution Lens: " + getEpochExplorationAngle(epoch) + "\n" +
            "User's Watched & In-Progress Shows: " + richHistStr + "\n" +
            "Live App-Broadcasted Trending & Recommended Feed on TV: " + db.getAppBroadcastTrendSummary(12).toString() + "\n" +
            "Already Watched & Rated Exclusion List (NEVER suggest): " + watchedList.toString() + "\n" +
            "Recently Suggested in Last 8 Rounds (AVOID repeating; explore fresh peer titles): " + recentSuggested.toString() + "\n" +
            "User Search Query (empty = Evolving Hourly/Manual Refresh): \"" + (userQuery == null ? "" : userQuery) + "\"\n\n" +
            buildJustWatchSchemaPromptSection(minModernYear, currentYear);
    }

    private String extractRecentSearchCuriosity(String profileId) {
        JSONArray turns = db.getVoiceSession(profileId).optJSONArray("turns");
        if (turns == null || turns.length() == 0) return "(No recent searches)";
        List<String> queries = new ArrayList<>();
        for (int i = turns.length() - 1; i >= 0 && queries.size() < 3; i--) {
            JSONObject t = turns.optJSONObject(i);
            if (t != null && "user".equals(t.optString("role"))) queries.add(t.optString("text"));
        }
        return queries.isEmpty() ? "(No recent searches)" : queries.toString();
    }

    public static String getEpochExplorationAngle(int epoch) {
        String[] angles = {
            "Cross-Genre Intersection: Bridge two distinct genres/themes from their highest-rated watches into hybrid series & films.",
            "Auteur & Lead-Cast Lineage: Surface acclaimed movies & shows starring lead actors or created by showrunners of their top watches.",
            "Counterfactual Tone Shift: Take the core high-concept premise of their favorite anchor show and shift it into a fresh subgenre or witty tone.",
            "Search Curiosity Synthesis: Blend their Recent User Search Curiosity with their long-term rated favorites.",
            "Tight High-Momentum Narratives: Prioritize brisk under-2-hour feature movies and razor-sharp 1-season limited series with zero filler.",
            "Global & International Prestige Breakouts: Surface critically acclaimed international thrillers, mysteries, and sci-fi across regions.",
            "Puzzle-Box & Mind-Game Storytelling: Focus on unreliable narrators, time loops, closed-circle whodunits, and covert conspiracies.",
            "Critically Acclaimed Sleeper Gems: Surface IMDb 7.8+ hidden masterpieces that contrast with mainstream repeats."
        };
        return angles[Math.abs(epoch) % angles.length];
    }

    private String buildJustWatchSchemaPromptSection(int minModernYear, int currentYear) {
        String region = RegionProviderRegistry.getRegionDisplayName(db);
        String pkgCodes = RegionProviderRegistry.getProviderCodesForRegion(db);
        return "JUSTWATCH GRAPHQL TITLEFILTER SCHEMA:\n" +
            "Each query object in 'queries' is executed in parallel (scatter-gather) against JustWatch GraphQL (" + region + " catalog) and accepts:\n" +
            "- 'searchQuery': string (specific seed title/movie/show/franchise to match or traverse, OR \"\" for pure genre/country filter discovery)\n" +
            "- 'includeSimilar': boolean (true to traverse JustWatch's similarTitles co-watch graph for 'searchQuery')\n" +
            "- 'objectTypes': array of [\"MOVIE\"], [\"SHOW\"], or [\"MOVIE\", \"SHOW\"] (e.g. if user asks for movies, MUST be [\"MOVIE\"])\n" +
            "- 'genres': array of valid JustWatch genre codes from [\"act\",\"scf\",\"fnt\",\"trl\",\"crm\",\"drm\",\"cmy\",\"rma\",\"hrr\",\"ani\",\"doc\",\"spt\",\"hst\",\"war\"]\n" +
            "- 'packages': array of JustWatch package codes from " + pkgCodes + "\n" +
            "- 'minYear': integer (e.g. " + minModernYear + " for modern discovery, or 2000-2015 when searching iconic movie franchises/classics)\n" +
            "- 'maxYear': integer (" + currentYear + ")\n" +
            "- 'productionCountries': optional array of 2-letter ISO codes (e.g. [\"US\"], [\"GB\"], [\"KR\"], [\"JP\"], [\"IN\"], [\"ES\"])\n" +
            "- 'seedAnchor': string naming which watched show or search intent inspired this query.\n\n" +
            buildPlannerTaskInstructions(minModernYear, currentYear);
    }

    private String buildPlannerTaskInstructions(int minModernYear, int currentYear) {
        return "Task:\n" +
            "1. MULTI-HOP INTENT DECOMPOSITION: Decompose 'User Search Query' (or Profile Watch History & Ratings when query is empty) across ALL nested constraints: Format ('MOVIE' vs 'SHOW'), Counterfactual/Tone-Shifted Similarity (e.g. 'like X but romantic comedy' -> select titles that combine X's high-concept premise with the requested romantic comedy tone), Cross-Show Cast References (e.g. 'actor from Show X in a Genre Y movie' -> first identify the MAIN lead actors of Show X, then select their Genre Y movies), Plot Clues, Runtime, Platform, and Negative Constraints (e.g. 'no space travel', 'no zombie gore').\n" +
            "2. GLOBAL & REGIONAL DIVERSITY: Unless 'User Search Query' explicitly asks for a specific country/language, include globally acclaimed international/Hollywood classics alongside regional titles; at most 1 of the 6 'queries' specs may set 'productionCountries'.\n" +
            "3. Plan 'format': \"MOVIE\" if user asks for movies/films, \"SHOW\" if user asks for shows/series, or \"ANY\".\n" +
            "4. Plan 'cast_seed_titles': array of 0 to 2 exact Show/Movie titles if the user asks for an actor/actress/star from a specific show or movie (e.g. [\"The Office\"], [\"Peaky Blinders\", \"Succession\"], [\"La La Land\"]), or [] otherwise.\n" +
            "5. Plan 'queries': 6 JustWatch GraphQL query spec objects. CRITICAL: JustWatch 'searchQuery' ONLY matches an exact Movie/Show Title or exact Actor/Director Name (NEVER put descriptive phrases in 'searchQuery'). " +
            "If 'User Search Query' asks for 'new', 'latest', 'recent', or broad genre/platform discovery without naming a specific title/actor/clue, set 'searchQuery': \"\" and 'includeSimilar': false on ALL 6 specs with 'minYear': " + minModernYear + " and 'primary_queries': []. " +
            "Otherwise, set 'searchQuery' on the 6 specs to the top resolved Movie/Show Titles (with 'includeSimilar': true) and/or resolved MAIN Lead Actor/Director Names (with 'includeSimilar': false), and set 'objectTypes': [\"MOVIE\"] or [\"SHOW\"] matching 'format'.\n" +
            "6. Plan 'primary_queries': 12 exact Movie/Show titles that satisfy the intersection of ALL nested constraints (put exact decoded matches first, followed by closest globally diverse peers; NEVER include watched or cooldown titles; or [] for 'new'/'latest' release queries).\n" +
            "7. Plan 'watched_traversal_seeds': 3 seed titles for co-watch traversal.\n" +
            "8. Plan 'taste_hypothesis': 1 nuanced sentence evolving 'Previous Evolved Taste Memo' by combining their watched/rated shows, 'Recent User Search Curiosity', and the current 'Refresh Epoch Evolution Lens'.\n" +
            "9. Plan 'narrator_steps': 5 short dynamic status phrases (3 to 5 words each) narrating each reasoning hop you just performed.\n" +
            "10. Plan 'traversal_chips': 3 short clickable follow-up search phrases (max 24 chars each).\n" +
            "Return ONLY a JSON object with keys: 'format', 'cast_seed_titles', 'queries', 'primary_queries', 'watched_traversal_seeds', 'taste_hypothesis', 'narrator_steps', 'traversal_chips'.";
    }

    public List<String> executeScatterGatherForSearch(String profileId, String userQuery, int minModernYear) {
        int currentYear = JustWatchGraph.getCurrentYear();
        List<String> watched = collectWatchedTitles(db.listHistory(profileId));
        JSONObject plan = planAgentSearchStrategy(profileId, userQuery, watched, minModernYear, currentYear);
        return AgenticSearchOrchestrator.executeAgenticScatterGather(this, db, profileId, userQuery, plan, minModernYear);
    }

    public static boolean isGenericNextOrTasteQuery(String q) {
        if (q == null) return true;
        String low = q.trim().toLowerCase(Locale.US);
        return low.contains("what is next") || low.contains("what's next") || low.contains("whats next")
            || low.contains("next recommendation") || low.contains("watch next") || low.equals("next")
            || low.equals("more") || low.contains("something else") || low.contains("recommend something");
    }

    public List<String> executeMultiHopTraversal(String profileId, JSONObject searchPlan, int minModernYear) {
        final List<String> summaries = Collections.synchronizedList(new ArrayList<>());
        final Set<String> seenNorm = Collections.synchronizedSet(new HashSet<>());
        JSONArray sota = buildSotaRecommendations(profileId, searchPlan, minModernYear, JustWatchGraph.getCurrentYear());
        for (int i = 0; i < sota.length(); i++) {
            JSONObject o = sota.optJSONObject(i);
            if (o != null) addCandidateIfValid(o, seenNorm, summaries);
        }
        return summaries;
    }

    private void addCandidateIfValid(JSONObject o, Set<String> seenNorm, List<String> summaries) {
        if (o == null) return;
        String t = o.optString("title").trim();
        String norm = AuraDb.normalizeTitleKey(t);
        if (t.isEmpty() || seenNorm.contains(norm) || db.isTitleWatched(t)) return;
        if (!JustWatchGraph.isValidDirectSimilarCandidate(o, 2008, 6.8)) return;
        seenNorm.add(norm);
        db.saveCachedGti("jw_imdb_v6:" + t.toLowerCase(), o.toString());
        summaries.add(t + " (" + o.optInt("year") + ", " + o.optString("type") + ", " + o.optString("genres") + ", " + o.optString("rating") + ", " + o.optString("app") + ")");
    }

    private List<JSONObject> filterAndRerankPoolWithLlm(String profileId, List<JSONObject> pool, int epoch) {
        Map<String, ImpressionTracker.ImpressionRecord> imp = ImpressionTracker.loadActiveImpressions(db);
        List<JSONObject> baseSlate = SlateAllocator.allocateBalancedSlate(pool, epoch, imp);
        if (getApiKey().isEmpty() || pool.size() < 6) return baseSlate;
        List<JSONObject> freshPool = selectFreshPoolForLlm(pool, epoch, imp, 36);
        List<JSONObject> llmFiltered = runLlmStageThreeRerank(profileId, freshPool, baseSlate, epoch);
        return llmFiltered.size() >= 8 ? llmFiltered : baseSlate;
    }

    private List<JSONObject> runLlmStageThreeRerank(
            String profileId, List<JSONObject> freshPool, List<JSONObject> baseSlate, int epoch) {
        AgentStatusNarrator.publishLiveStep("Curating personalized picks");
        String raw = callGeminiJsonFast(buildAgentRankingPrompt(profileId, collectCandidateSummariesForLlm(freshPool, 36), epoch));
        AgentStatusNarrator.publishLiveStep("Publishing fresh recommendations");
        return applyLlmFilteredSlate(freshPool, baseSlate, raw);
    }

    private List<JSONObject> selectFreshPoolForLlm(
            List<JSONObject> pool, int epoch, Map<String, ImpressionTracker.ImpressionRecord> impressions, int limit) {
        List<JSONObject> fresh = new ArrayList<>();
        List<JSONObject> recycled = new ArrayList<>();
        for (JSONObject item : pool) {
            String key = AuraDb.normalizeTitleKey(item.optString("title", ""));
            if (ImpressionTracker.isRecentlyShown(key, epoch, impressions)) recycled.add(item);
            else fresh.add(item);
        }
        for (int i = 0; i < recycled.size() && fresh.size() < 12; i++) fresh.add(recycled.get(i));
        return fresh.size() > limit ? fresh.subList(0, limit) : fresh;
    }

    private List<String> collectCandidateSummariesForLlm(List<JSONObject> pool, int limit) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < pool.size() && i < limit; i++) {
            JSONObject o = pool.get(i);
            String syn = o.optString("synopsis", "");
            if (syn.length() > 120) syn = syn.substring(0, 120) + "...";
            String anchorTag = resolveCandidateAnchorTag(o);
            list.add(o.optString("title") + " (" + o.optInt("year") + ", " + o.optString("type") + ", " + o.optString("genres") + ", " + o.optString("country") + ", " + o.optString("rating") + ", " + o.optString("app") + ", Anchor=" + anchorTag + ") — " + syn);
        }
        return list;
    }

    private String resolveCandidateAnchorTag(JSONObject o) {
        String bcast = o.optString("broadcast_badge", "");
        if (!bcast.isEmpty()) return "LIVE_TV_TRENDING(" + bcast + ")";
        return o.optString("seed_anchor", "discovery");
    }

    private List<JSONObject> applyLlmFilteredSlate(List<JSONObject> pool, List<JSONObject> baseSlate, String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return Collections.emptyList();
        try {
            List<JSONObject> out = matchLlmPicksToPool(pool, parseLlmRankingArray(rawJson));
            if (out.size() >= 8 && out.size() < 18) backfillFromBaseSlate(baseSlate, out, 18);
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private JSONArray parseLlmRankingArray(String rawJson) throws Exception {
        String trimmed = rawJson.trim();
        if (trimmed.startsWith("[")) return new JSONArray(trimmed);
        JSONObject obj = new JSONObject(trimmed);
        JSONArray res = obj.optJSONArray("results");
        return res != null ? res : new JSONArray();
    }

    private List<JSONObject> matchLlmPicksToPool(List<JSONObject> pool, JSONArray arr) {
        Map<String, JSONObject> byKey = new HashMap<>();
        for (JSONObject c : pool) byKey.put(AuraDb.normalizeTitleKey(c.optString("title", "")), c);
        List<JSONObject> out = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            tryAddMatchedLlmPick(arr.optJSONObject(i), byKey, added, out);
        }
        return out;
    }

    private void backfillFromBaseSlate(List<JSONObject> baseSlate, List<JSONObject> out, int targetCount) {
        Set<String> added = new HashSet<>();
        for (JSONObject o : out) added.add(AuraDb.normalizeTitleKey(o.optString("title", "")));
        appendRemainingPoolItems(baseSlate, added, out, targetCount);
    }

    private void tryAddMatchedLlmPick(JSONObject pick, Map<String, JSONObject> byKey, Set<String> added, List<JSONObject> out) {
        if (pick == null) return;
        String key = AuraDb.normalizeTitleKey(pick.optString("title", ""));
        JSONObject matched = byKey.get(key);
        if (matched == null || !added.add(key)) return;
        applyLlmPickReason(matched, pick.optString("reason", "").trim());
        out.add(matched);
    }

    private void applyLlmPickReason(JSONObject matched, String reason) {
        if (reason.isEmpty()) return;
        String bcast = matched.optString("broadcast_badge", "");
        String finalReason = (!bcast.isEmpty() && !reason.startsWith(bcast)) ? (bcast + " \u00B7 " + reason) : reason;
        try { matched.put("reason", finalReason); } catch (Exception ignored) {}
    }

    private void appendRemainingPoolItems(List<JSONObject> pool, Set<String> added, List<JSONObject> out, int targetCount) {
        for (JSONObject c : pool) {
            if (out.size() >= targetCount) return;
            String k = AuraDb.normalizeTitleKey(c.optString("title", ""));
            if (!k.isEmpty() && added.add(k)) out.add(c);
        }
    }

    private List<String> collectWatchedTitles(JSONArray history) {
        List<String> seen = new ArrayList<>();
        if (history != null) appendWatchedTitlesFromArray(history, true, 40, seen);
        appendWatchedTitlesFromArray(db.listManuallyWatched(), false, 80, seen);
        return seen;
    }

    private void appendWatchedTitlesFromArray(JSONArray arr, boolean skipYoutube, int maxSourceItems, List<String> seen) {
        int added = 0;
        for (int i = 0; i < arr.length() && added < maxSourceItems; i++) {
            if (tryAddWatchedTitle(arr.optJSONObject(i), skipYoutube, seen)) added++;
        }
    }

    private boolean tryAddWatchedTitle(JSONObject item, boolean skipYoutube, List<String> seen) {
        if (item == null || (skipYoutube && item.optString("app_pkg").contains("youtube"))) return false;
        String t = item.optString("series_title").isEmpty() ? item.optString("title") : item.optString("series_title");
        if (t.isEmpty() || seen.contains(t)) return false;
        return seen.add(t);
    }

    private String buildAgentRankingPrompt(String profileId, List<String> verifiedCandidates, int epoch) {
        String profileName = db.getProfileLabel(profileId);
        JSONArray richHistory = buildRichWatchedContext(profileId);
        String header = buildRankingPromptHeader(profileId, profileName, richHistory, epoch);
        return header + buildRankingPromptBody(profileName, verifiedCandidates);
    }

    private String buildRankingPromptHeader(String profileId, String profileName, JSONArray richHistory, int epoch) {
        String personalGuide = db.getProfileTaste(profileId);
        String ratingsFeedback = db.getRatingFeedbackSummary();
        List<String> activeFilters = db.getActiveFilters(profileId);
        List<String> recentSuggested = ImpressionTracker.getRecentCooldownKeys(db, epoch);
        String region = RegionProviderRegistry.getRegionDisplayName(db);
        String memo = db.getSetting("evolved_taste_memo_" + profileId, getEpochExplorationAngle(epoch));
        return "You are Aura's Stage-3 Post-Filter & Reranking Agent for Android TV in " + region + ".\n" +
            "Current Date: " + JustWatchGraph.getCurrentDateString() + ". Active Profile: '" + profileName + "'. Refresh Epoch #" + epoch + "\n" +
            "0. EVOLVING TASTE HYPOTHESIS & RECENT SEARCH CURIOSITY: " + memo + " | Recent Searches: " + extractRecentSearchCuriosity(profileId) + "\n" +
            "0b. STRUCTURED TASTE DNA: " + TasteIntelligenceEngine.getTasteDnaPromptSummary(db, profileId) + "\n" +
            "1. PERSONAL GUIDE AI: " + (personalGuide.isEmpty() ? "(Use Default Guide AI and Watch History)" : "\"" + personalGuide + "\"") + "\n" +
            "2. DEFAULT GUIDE AI: \"" + db.getDefaultTasteGuide() + "\"\n3. USER RATINGS FEEDBACK: " + (ratingsFeedback.isEmpty() ? "None yet" : ratingsFeedback) + "\n" +
            "4. ACTIVE FILTERS: " + (activeFilters.isEmpty() ? "All categories" : activeFilters.toString()) + "\n" +
            "5. RECENTLY SUGGESTED IN LAST 8 ROUNDS (STRICT COOLDOWN — DO NOT REPEAT THESE): " + recentSuggested.toString() + "\n" +
            "6. USER'S WATCHED SHOWS (WITH PLOT SYNOPSIS, GENRES, TYPE, COUNTRY): " + richHistory.toString() + "\n";
    }

    private String buildRankingPromptBody(String profileName, List<String> verifiedCandidates) {
        return "7. SCATTER-GATHER JUSTWATCH CANDIDATE POOL: " + verifiedCandidates.toString() + "\n\n" +
            "Task:\n" +
            "- Strictly reject any candidate whose genre, tone, or premise does not match the user's watched & rated library and Guide AI (never include generic soaps, kids cartoons, or unrelated genres).\n" +
            "- Actively include 2 to 4 fresh Anchor=LIVE_TV_TRENDING(...) shows, new drops, or live sports/games from the user's installed TV apps when they fit the household's tastes.\n" +
            "- Select & rank the top 18 candidates from the pool that '" + profileName + "' will genuinely love watching next.\n" +
            "- Return ONLY a JSON object with key 'results': an array of objects, each with keys 'title' (exact title from the pool) and 'reason' (12-18 words explaining specifically how its plot/theme connects to one of their watched shows).";
    }

    public JSONArray enrichAll(JSONArray raw) {
        return enrichAll(raw, JustWatchGraph.getDefaultMinModernYear(), JustWatchGraph.getCurrentYear());
    }

    public JSONArray enrichAll(JSONArray raw, final int minAllowedYear, final int maxAllowedYear) {
        if (raw == null) return new JSONArray();
        final JSONObject[] results = new JSONObject[raw.length()];
        runParallelEnrichTasks(raw, minAllowedYear, maxAllowedYear, results);
        return packDedupedEnrichedResults(results);
    }

    private void runParallelEnrichTasks(JSONArray raw, int minAllowedYear, int maxAllowedYear, JSONObject[] results) {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < raw.length(); i++) {
            final int idx = i;
            final JSONObject p = raw.optJSONObject(i);
            if (p != null) pool.submit(() -> results[idx] = enrichSingleItem(p, idx, minAllowedYear, maxAllowedYear));
        }
        pool.shutdown();
        try { pool.awaitTermination(8, TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    private JSONObject enrichSingleItem(JSONObject p, int idx, int minAllowedYear, int maxAllowedYear) {
        String title = p.optString("title").trim();
        if (title.isEmpty() || db.isTitleWatched(title)) return null;
        JSONObject meta = fetchOpenMetadata(title);
        double minImdb = minAllowedYear <= 2000 ? 5.0 : 6.4;
        if (!JustWatchGraph.isValidDirectSimilarCandidate(meta, minAllowedYear, minImdb)) return null;
        String verifiedApp = meta.optString("app", "");
        if (verifiedApp.isEmpty()) return null;
        return buildEnrichedCard(p, meta, title, verifiedApp, idx, maxAllowedYear);
    }

    private JSONObject buildEnrichedCard(JSONObject p, JSONObject meta, String title, String verifiedApp, int idx, int maxAllowedYear) {
        JSONObject card = new JSONObject();
        try {
            populateEnrichedCoreFields(card, p, meta, title, verifiedApp, maxAllowedYear);
            populateEnrichedArtworkAndScoring(card, p, meta, idx);
            populateEnrichedLaunchFields(card, meta, title, verifiedApp);
            return card;
        } catch (Exception e) {
            return null;
        }
    }

    private void populateEnrichedCoreFields(
            JSONObject card, JSONObject p, JSONObject meta, String title, String verifiedApp, int maxAllowedYear) throws Exception {
        card.put("title", meta.optString("title", title)).put("badge", verifiedApp).put("app", verifiedApp);
        card.put("pkg_code", meta.optString("pkg_code", "")).put("country", meta.optString("country", ""));
        card.put("year", meta.optInt("year", maxAllowedYear)).put("type", meta.optString("type", "Series"));
        String reason = p.optString("reason");
        if (reason.isEmpty()) reason = meta.optString("reason", meta.optString("synopsis"));
        card.put("reason", reason);
        card.put("synopsis", !meta.optString("synopsis").isEmpty() ? meta.optString("synopsis") : reason);
    }

    private void populateEnrichedArtworkAndScoring(JSONObject card, JSONObject p, JSONObject meta, int idx) throws Exception {
        String posterUrl = meta.optString("poster_url");
        String backdropUrl = meta.optString("backdrop_url", p.optString("backdrop_url", ""));
        card.put("poster_url", ImageLoader.upgradePosterUrl(posterUrl));
        card.put("backdrop_url", ImageLoader.upgradePosterUrl(!backdropUrl.isEmpty() ? backdropUrl : posterUrl));
        card.put("genres", meta.optString("genres", p.optString("genres", "Drama")));
        card.put("genre_codes", meta.optString("genre_codes", ""));
        card.put("runtime_min", meta.optInt("runtime_min", p.optInt("runtime_min", 48)));
        card.put("rating", meta.optString("rating", "")).put("imdb_raw", meta.optDouble("imdb_raw", 0.0));
        card.put("imdb_score", meta.optDouble("imdb_score", 0.0)).put("imdb_votes", meta.optInt("imdb_votes", 0));
        card.put("tmdb_popularity", meta.optDouble("tmdb_popularity", 0.0)).put("llm_idx", idx);
    }

    private void populateEnrichedLaunchFields(JSONObject card, JSONObject meta, String title, String verifiedApp) throws Exception {
        String launchUri = meta.optString("launch_uri");
        String launchPkg = meta.optString("launch_pkg");
        card.put("launch_uri", !launchUri.isEmpty() ? launchUri : db.findNativeLaunchUri(title));
        card.put("launch_pkg", !launchPkg.isEmpty() ? launchPkg : AuraDb.resolveAppPackage(verifiedApp));
    }

    private JSONArray packDedupedEnrichedResults(JSONObject[] results) {
        JSONArray out = new JSONArray();
        Set<String> seenKeys = new HashSet<>();
        for (JSONObject r : results) {
            if (r == null) continue;
            String k = AuraDb.normalizeTitleKey(r.optString("title"));
            if (!k.isEmpty() && seenKeys.add(k)) out.put(r);
        }
        return out;
    }

    public JSONObject fetchOpenMetadata(String title) {
        if (title == null || title.isEmpty()) return new JSONObject();
        String cacheKey = "jw_imdb_v6:" + title.trim().toLowerCase();
        JSONObject cachedObj = tryReadCachedOpenMeta(cacheKey);
        if (cachedObj != null) return cachedObj;
        JSONObject res = JustWatchGraph.lookupTitle(title);
        int maxYear = JustWatchGraph.getCurrentYear();
        if (!res.optString("poster_url").isEmpty() && res.optInt("year", maxYear) <= maxYear) {
            db.saveCachedGti(cacheKey, res.toString());
        }
        return res;
    }

    private JSONObject tryReadCachedOpenMeta(String cacheKey) {
        String cached = db.getCachedGti(cacheKey);
        if (cached == null) return null;
        try {
            JSONObject cObj = new JSONObject(cached);
            return cObj.optInt("imdb_votes", 0) > 0 ? cObj : null;
        } catch (Exception e) {
            return null;
        }
    }

    public JSONObject resolvePrimeGti(String gti) {
        try {
            String cached = db.getCachedGti(gti);
            if (cached != null) return new JSONObject(cached);
            JSONObject info = scrapePrimeGtiHtml(gti, httpGet("https://www.primevideo.com/detail/" + gti));
            enrichPrimeGtiWithOpenMeta(info);
            db.saveCachedGti(gti, info.toString());
            return info;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject scrapePrimeGtiHtml(String gti, String html) throws Exception {
        JSONObject info = new JSONObject();
        String parent = extractRegex(html, "\"parentTitle\"\\s*:\\s*\"([^\"]+)\"");
        String epTitle = extractRegex(html, Pattern.quote(gti) + "\":\\{\"title\":\"([^\"]+)\"");
        String seq = extractRegex(html, Pattern.quote(gti) + "\",\"isLaunched\":true,\"link\":\"[^\"]+\",\"sequenceNumber\":(\\d+)");
        info.put("series_title", parent.isEmpty() ? "Prime Video" : parent);
        info.put("title", epTitle.isEmpty() ? info.optString("series_title") : epTitle);
        info.put("synopsis", extractRegex(html, Pattern.quote(gti) + "\":\\{\"title\":\"[^\"]+\",\"synopsis\":\"([^\"]+)\""));
        if (!seq.isEmpty()) info.put("episode", Integer.parseInt(seq));
        return info.put("season", 1);
    }

    private void enrichPrimeGtiWithOpenMeta(JSONObject info) throws Exception {
        JSONObject tv = fetchOpenMetadata(info.optString("series_title"));
        info.put("poster_url", tv.optString("poster_url"));
        info.put("genres", tv.optString("genres", "Drama"));
        info.put("runtime_min", tv.optInt("runtime_min", 60));
    }

    private static final ThreadLocal<Integer> TL_GEMINI_READ_TIMEOUT_MS = new ThreadLocal<>();

    public boolean hasGeminiApiKey() {
        return !getApiKey().isEmpty();
    }

    public String[] getGeminiModels() {
        return MODELS.clone();
    }

    public String postGeminiRaw(String model, String jsonBody, int timeoutMs) {
        String apiKey = getApiKey();
        if (apiKey.isEmpty() || model == null || jsonBody == null) return null;
        TL_GEMINI_READ_TIMEOUT_MS.set(timeoutMs);
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
            return httpPost(url, jsonBody);
        } catch (Exception e) {
            return null;
        } finally {
            TL_GEMINI_READ_TIMEOUT_MS.remove();
        }
    }

    public String callGeminiJson(String prompt) {
        return callGeminiMultimodalJson(prompt, null);
    }

    public String callGeminiJsonFast(String prompt) {
        TL_GEMINI_READ_TIMEOUT_MS.set(12000);
        try { return callGeminiMultimodalJson(prompt, null); }
        finally { TL_GEMINI_READ_TIMEOUT_MS.remove(); }
    }

    public String callGeminiMultimodalJson(String prompt, String base64Jpeg) {
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) return null;
        int maxAttempts = TL_GEMINI_READ_TIMEOUT_MS.get() != null ? 1 : 2;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String out = tryAllGeminiModelsOnce(apiKey, prompt, base64Jpeg);
            if (out != null || Thread.currentThread().isInterrupted()) return out;
            if (attempt + 1 < maxAttempts) sleepQuietly(600L);
        }
        return null;
    }

    private String tryAllGeminiModelsOnce(String apiKey, String prompt, String base64Jpeg) {
        for (String model : MODELS) {
            if (Thread.currentThread().isInterrupted()) return null;
            String out = tryCallSingleGeminiModel(model, apiKey, prompt, base64Jpeg);
            if (out != null && !out.isEmpty()) return out;
        }
        return null;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String tryCallSingleGeminiModel(String model, String apiKey, String prompt, String base64Jpeg) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
            String resp = httpPost(url, buildGeminiRequestBody(prompt, base64Jpeg).toString());
            return stripJsonFences(extractGeminiCandidateText(resp));
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractGeminiCandidateText(String resp) throws Exception {
        JSONObject root = new JSONObject(resp);
        return root.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
    }

    private static String stripJsonFences(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return t.trim();
    }

    private JSONObject buildGeminiRequestBody(String prompt, String base64Jpeg) throws Exception {
        JSONArray parts = new JSONArray().put(new JSONObject().put("text", prompt));
        if (base64Jpeg != null && !base64Jpeg.isEmpty()) {
            parts.put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "image/jpeg").put("data", base64Jpeg)));
        }
        JSONArray contents = new JSONArray().put(new JSONObject().put("parts", parts));
        JSONObject genCfg = new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.65);
        return new JSONObject().put("contents", contents).put("generationConfig", genCfg);
    }

    private static final ExecutorService HTTP_EXEC = Executors.newCachedThreadPool();

    private String httpGet(final String urlStr) throws Exception {
        java.util.concurrent.Future<String> fut = HTTP_EXEC.submit(() -> executeHttpGet(urlStr));
        try {
            return fut.get(4000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            fut.cancel(true);
            throw e;
        }
    }

    private String executeHttpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(3500);
        conn.setReadTimeout(3500);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)");
        return readConnectionBody(conn);
    }

    private String httpPost(final String urlStr, final String jsonBody) throws Exception {
        Integer customReadMs = TL_GEMINI_READ_TIMEOUT_MS.get();
        final int readTimeoutMs = customReadMs != null ? customReadMs : 35000;
        java.util.concurrent.Future<String> fut = HTTP_EXEC.submit(() -> executeHttpPost(urlStr, jsonBody, readTimeoutMs));
        try {
            return fut.get(readTimeoutMs + 7000L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            fut.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw e;
        }
    }

    private String executeHttpPost(String urlStr, String jsonBody, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = conn.getOutputStream()) { os.write(jsonBody.getBytes(StandardCharsets.UTF_8)); }
        return readConnectionBody(conn);
    }

    private String readConnectionBody(HttpURLConnection conn) throws Exception {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    /**
     * Built-in verified SOTA seed catalog from JustWatch India GraphQL (2022–2026).
     * Guarantees instant 0ms Home rendering and zero empty states even if TV DNS is slow.
     */
    public JSONArray getBuiltInSotaSeedCatalog(String profileId) {
        JSONArray arr = new JSONArray();
        addSeedBatchOne(arr);
        addSeedBatchTwo(arr);
        addSeedBatchThree(arr);
        addSeedBatchFour(arr);
        addSeedBatchFive(arr);
        adaptSeedsToActiveRegion(arr);
        return db.filterOutWatched(arr);
    }

    private void adaptSeedsToActiveRegion(JSONArray arr) {
        String country = RegionProviderRegistry.getCountryCode(db);
        for (int i = 0; i < arr.length(); i++) {
            RegionProviderRegistry.adaptSeedItemForRegion(arr.optJSONObject(i), country);
        }
    }

    private void addSeedBatchOne(JSONArray arr) {
        addSeed(arr, "Dexter: Resurrection", 2025, "Series", 55, 9.0, 164128, "Prime Video", "prv", "Crime, Drama, Thriller", "crm,drm,trl", "co_watch", 9.65,
            "Critically Acclaimed Crime Thriller · ★ 9.0 (164.1k votes)", "Set 10 weeks after Dexter: New Blood, Dexter Morgan chases a missing Harrison to New York City, where Captain Angel Batista is hot on their trail.",
            "https://images.justwatch.com/poster/355231202/s718/dexter-resurrection.jpg", "https://images.justwatch.com/backdrop/321995044/s1920/dexter-resurrection.jpg", "intent://app.primevideo.com/watch?gti=amzn1.dv.gti.8500885e-a8bf-420a-bb55-eb33708aacee&time=0#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end");
        addSeed(arr, "Severance", 2022, "Series", 49, 8.6, 414782, "Apple TV+", "atp", "Sci-Fi, Thriller, Drama", "drm,trl,scf", "co_watch", 9.52,
            "Emmy-Winning Sci-Fi Mystery · ★ 8.6 (414.8k votes)", "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.",
            "https://images.justwatch.com/poster/324479701/s718/severance.jpg", "https://images.justwatch.com/backdrop/324479719/s1920/severance.jpg", "intent://tv.apple.com/episode/good-news-about-hell/umc.cmc.s80mx1ic96pu6ewupz8pfasf?showId=umc.cmc.1srk2goyh2q2zdxcx605w8vtx&playableId=tvs.sbd.4000%3AVSEVR0560101#Intent;launchFlags=0x00800000;scheme=https;end");
        addSeed(arr, "MobLand", 2025, "Series", 46, 8.3, 140134, "Disney+ Hotstar", "jhs", "Crime, Drama", "crm,drm", "co_watch", 9.38,
            "High-Stakes Crime Drama · ★ 8.3 (140.1k votes)", "Two mob families clash in a war that threatens to topple empires and lives in Guy Ritchie's high-stakes crime series.",
            "https://images.justwatch.com/poster/354739547/s718/mobland.jpg", "https://images.justwatch.com/backdrop/328945489/s1920/mobland.jpg", "https://www.hotstar.com/shows/mobland/1271397631");
    }

    private void addSeedBatchTwo(JSONArray arr) {
        addSeed(arr, "The Diplomat", 2023, "Series", 50, 8.1, 108113, "Netflix", "nfx", "Thriller, Drama", "drm,trl,war", "co_watch", 9.25,
            "Acclaimed Political Thriller · ★ 8.1 (108.1k votes)", "In the midst of an international crisis, a career diplomat lands in a high-profile job with tectonic implications.",
            "https://images.justwatch.com/poster/304992451/s718/the-diplomat.jpg", "https://images.justwatch.com/backdrop/355789186/s1920/the-diplomat.jpg", "intent://www.netflix.com/watch/81288983#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end");
        addSeed(arr, "The Gentlemen", 2024, "Series", 50, 8.0, 178420, "Netflix", "nfx", "Action, Crime, Comedy", "act,crm,cmy", "co_watch", 9.18,
            "Sharp Crime Action-Comedy · ★ 8.0 (178.4k votes)", "When aristocratic Eddie inherits the family estate, he discovers that it's home to a massive weed empire—and its proprietors aren't going anywhere.",
            "https://images.justwatch.com/poster/312465722/s718/the-gentlemen.jpg", "https://images.justwatch.com/backdrop/312465731/s1920/the-gentlemen.jpg", "intent://www.netflix.com/watch/81437051#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end");
        addSeed(arr, "The Day of the Jackal", 2024, "Series", 51, 8.1, 139687, "Disney+ Hotstar", "jhs", "Action, Crime, Thriller", "act,crm,drm", "co_watch", 9.12,
            "Propulsive Espionage Thriller · ★ 8.1 (139.7k votes)", "An elusive assassin who makes his living carrying out hits for the highest fee meets his match in a tenacious intelligence officer.",
            "https://images.justwatch.com/poster/322756732/s718/the-day-of-the-jackal.jpg", "https://images.justwatch.com/backdrop/322756746/s1920/the-day-of-the-jackal.jpg", "intent://content/1971311802#Intent;launchFlags=0x00800000;scheme=hotstar;end");
    }

    private void addSeedBatchThree(JSONArray arr) {
        addSeed(arr, "Silo", 2023, "Series", 51, 8.1, 253859, "Apple TV+", "atp", "Sci-Fi, Drama", "drm,scf", "co_watch", 9.08,
            "Gripping Dystopian Sci-Fi · ★ 8.1 (253.9k votes)", "Men and women live in a giant silo underground with strict regulations they believe protect them from the toxic world on the surface.",
            "https://images.justwatch.com/poster/346669772/s718/silo.jpg", "https://images.justwatch.com/backdrop/346257620/s1920/silo.jpg", "intent://tv.apple.com/episode/freedom-day/umc.cmc.4v3gat04y129mm0smdu6ea3dh?showId=umc.cmc.3yksgc857px0k0rqe5zd4jice&playableId=tvs.sbd.4000%3AA0026501001#Intent;launchFlags=0x00800000;scheme=https;end");
        addSeed(arr, "Slow Horses", 2022, "Series", 46, 8.4, 169223, "Apple TV+", "atp", "Thriller, Drama", "drm,trl", "structured", 9.04,
            "Acclaimed Spy Thriller · ★ 8.4 (169.2k votes)", "Follow a dysfunctional team of MI5 agents and their notorious boss Jackson Lamb as they navigate the espionage world's smoke and mirrors.",
            "https://images.justwatch.com/poster/349310634/s718/slow-horses.jpg", "https://images.justwatch.com/backdrop/272750278/s1920/slow-horses.jpg", "intent://tv.apple.com/episode/failures-contagious/umc.cmc.72legmwolm5ytctyq637r6x0?showId=umc.cmc.2szz3fdt71tl1ulnbp8utgq5o&playableId=tvs.sbd.4000%3AA0006401001#Intent;launchFlags=0x00800000;scheme=https;end");
        addSeed(arr, "Black Warrant", 2025, "Series", 44, 7.9, 8836, "Netflix", "nfx", "Crime, Drama", "crm,drm", "co_watch", 8.95,
            "Gritty Prison Crime Drama · ★ 7.9 (8.8k votes)", "When an upright officer accepts a job at Tihar Jail, he vows to make amends after witnessing cold-blooded corruption within its walls.",
            "https://images.justwatch.com/poster/325958141/s718/black-warrant.jpg", "https://images.justwatch.com/backdrop/325958144/s1920/black-warrant.jpg", "intent://www.netflix.com/watch/81971071#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end");
    }

    private void addSeedBatchFour(JSONArray arr) {
        addSeed(arr, "Reacher", 2022, "Series", 48, 8.0, 328430, "Prime Video", "prv", "Action, Crime, Drama", "act,crm,drm", "co_watch", 8.91,
            "High-Octane Action Mystery · ★ 8.0 (328.4k votes)", "Jack Reacher, a veteran military police investigator, enters civilian life as a drifter uncovering deep small-town conspiracies.",
            "https://images.justwatch.com/poster/326970737/s718/jack-reacher.jpg", "https://images.justwatch.com/backdrop/348320196/s1920/jack-reacher.jpg", "intent://app.primevideo.com/watch?gti=amzn1.dv.gti.41f71162-2344-4089-accf-5ce0e9c12535&time=0#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end");
        addSeed(arr, "East of Eden", 2026, "Series", 59, 8.0, 36138, "Netflix", "nfx", "Drama", "drm", "structured", 8.86,
            "Acclaimed Period Drama · ★ 8.0 (36.1k votes)", "A fiercely independent woman drives a rift between two brothers, igniting a sweeping multi-generational saga.",
            "https://images.justwatch.com/poster/345990721/s718/east-of-eden.jpg", "https://images.justwatch.com/backdrop/345990726/s1920/east-of-eden.jpg", "intent://www.netflix.com/watch/81611854#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end");
        addSeed(arr, "The Terminal List", 2022, "Series", 56, 7.9, 135744, "Prime Video", "prv", "Action, Thriller, Drama", "act,drm,trl", "co_watch", 8.80,
            "Tactical Conspiracy Thriller · ★ 7.9 (135.7k votes)", "A former Navy SEAL officer investigates why his entire platoon was ambushed during a high-stakes covert mission.",
            "https://images.justwatch.com/poster/288434876/s718/the-terminal-list.jpg", "https://images.justwatch.com/backdrop/288444687/s1920/the-terminal-list.jpg", "intent://app.primevideo.com/watch?gti=amzn1.dv.gti.0c17249c-bd78-474d-9fe7-91edf9b71dfa&time=0#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end");
    }

    private void addSeedBatchFive(JSONArray arr) {
        addSeed(arr, "FROM", 2022, "Series", 51, 7.7, 213849, "Prime Video", "prv", "Thriller, Horror, Drama", "drm,fnt,hrr", "structured", 8.74,
            "Acclaimed Mystery Thriller · ★ 7.7 (213.8k votes)", "Unravel the mystery of a nightmarish town in middle America that traps all those who enter.",
            "https://images.justwatch.com/poster/344518661/s718/from.jpg", "https://images.justwatch.com/backdrop/344518677/s1920/from.jpg", "intent://app.primevideo.com/watch?gti=amzn1.dv.gti.68db6859-4422-41c3-9f75-292818b9eafa&time=0#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end");
        addSeed(arr, "Dark Matter", 2024, "Series", 54, 7.6, 86573, "Apple TV+", "atp", "Sci-Fi, Thriller, Drama", "drm,trl,scf", "co_watch", 8.68,
            "Mind-Bending Multiverse Sci-Fi · ★ 7.6 (86.6k votes)", "Jason Dessen is abducted into an alternate version of his life and embarks on a harrowing journey back to his real family.",
            "https://images.justwatch.com/poster/354727700/s718/dark-matter-2024.jpg", "https://images.justwatch.com/backdrop/318995476/s1920/dark-matter-2024.jpg", "intent://tv.apple.com/episode/are-you-happy-in-your-life/umc.cmc.59p6kv4nynpisd1gl300a7810?showId=umc.cmc.4luj45vtqpmjsvb6sc2675oeg&playableId=tvs.sbd.4000%3AA0052201001#Intent;launchFlags=0x00800000;scheme=https;end");
        addSeed(arr, "3 Body Problem", 2024, "Series", 56, 7.5, 186951, "Netflix", "nfx", "Sci-Fi, Drama", "drm,fnt,scf", "co_watch", 8.62,
            "Epic Hard Sci-Fi Mystery · ★ 7.5 (187.0k votes)", "Across continents and decades, five brilliant friends make earth-shattering discoveries as the laws of science unravel.",
            "https://images.justwatch.com/poster/313070678/s718/3-body-problem.jpg", "https://images.justwatch.com/backdrop/348986715/s1920/3-body-problem.jpg", "intent://www.netflix.com/watch/81024821#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end");
    }

    private void addSeed(JSONArray arr, String title, int year, String type, int runtime,
                         double imdb, int votes, String app, String pkgCode, String genres,
                         String genreCodes, String channel, double sotaScore, String reason,
                         String synopsis, String poster, String backdrop, String launchUri) {
        try {
            JSONObject o = createSeedCoreObject(title, year, type, runtime, imdb, votes, app, pkgCode);
            populateSeedDetails(o, genres, genreCodes, channel, sotaScore, reason, synopsis, poster, backdrop, launchUri, app);
            arr.put(o);
        } catch (Exception ignored) {}
    }

    private JSONObject createSeedCoreObject(
            String title, int year, String type, int runtime, double imdb, int votes, String app, String pkgCode) throws Exception {
        JSONObject o = new JSONObject();
        o.put("title", title).put("year", year).put("type", type).put("runtime_min", runtime);
        o.put("imdb_raw", imdb).put("imdb_score", imdb).put("imdb_votes", votes).put("tmdb_popularity", 95.0);
        o.put("rating", String.format(Locale.US, "\u2605 %.1f", imdb));
        return o.put("app", app).put("badge", app).put("pkg_code", pkgCode).put("has_flatrate_offer", true);
    }

    private void populateSeedDetails(
            JSONObject o, String genres, String genreCodes, String channel, double sotaScore,
            String reason, String synopsis, String poster, String backdrop, String launchUri, String app) throws Exception {
        o.put("genres", genres).put("genre_codes", genreCodes).put("source_channel", channel);
        o.put("sota_score", sotaScore).put("sim_score", sotaScore).put("reason", reason).put("synopsis", synopsis);
        o.put("poster_url", poster).put("backdrop_url", backdrop);
        o.put("launch_uri", launchUri).put("launch_pkg", AuraDb.resolveAppPackage(app));
    }

    private String extractRegex(String text, String regex) {
        if (text == null) return "";
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
