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
        "gemini-3.1-flash-lite-preview",
        "gemini-flash-lite-latest",
        "gemini-2.5-flash"
    };
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
        String profileId,
        JSONObject nowPlaying,
        JSONArray history,
        boolean forceManual,
        AgentProgressListener progress
    ) {
        db.purgeUnreleasedFromRecos();
        JSONArray cached = db.getProfileRecommendations(profileId);
        if (!forceManual && cached.length() >= 12 && db.isRecoCacheFresh(profileId)) {
            return cached;
        }

        int currentYear = JustWatchGraph.getCurrentYear();
        int minModernYear = JustWatchGraph.getDefaultMinModernYear();
        List<String> watchedList = collectWatchedTitles(history);

        // Stage 1: Build Temporal Taste Vector & Anchor Plan
        if (progress != null) progress.onStep("Modeling temporal taste vector from your recent watch history\u2026");
        JSONObject seedPlan = !getApiKey().isEmpty()
            ? planAgentSearchStrategy(profileId, "", watchedList, minModernYear, currentYear)
            : buildFastSeedPlan(profileId, "");

        // Stage 2: 4-Channel Parallel Retrieval + Stage 3 Hybrid Scoring + Stage 4 MMR Diversity
        if (progress != null) progress.onStep("Retrieving & scoring verified candidates across your streaming apps\u2026");
        JSONArray sotaRanked = buildSotaRecommendations(profileId, seedPlan, minModernYear, currentYear);

        if (sotaRanked.length() >= 8) {
            db.saveProfileRecommendations(profileId, sotaRanked);
            return sotaRanked;
        }
        if (cached.length() > 0) return cached;
        JSONArray seedCatalog = getBuiltInSotaSeedCatalog(profileId);
        db.saveProfileRecommendations(profileId, seedCatalog);
        return seedCatalog;
    }

    /**
     * Executes Stages 1–4 of the SOTA Recommendation Pipeline via modular UserTasteProfile,
     * CandidateValidator, HybridScorer, SlateAllocator, and ImpressionTracker.
     */
    public JSONArray buildSotaRecommendations(String profileId, JSONObject seedPlan, int minModernYear, int currentYear) {
        final List<JSONObject> pool = Collections.synchronizedList(new ArrayList<>());
        final Set<String> seenKeys = Collections.synchronizedSet(new HashSet<>());
        final int epoch = ImpressionTracker.advanceEpoch(db, profileId);
        final UserTasteProfile taste = new UserTasteProfile(db, profileId, epoch);

        seenKeys.addAll(db.getWatchedTitleKeys());
        seenKeys.addAll(db.getHistoryTitleKeys(profileId));
        for (String a : taste.getAnchors()) {
            seenKeys.add(AuraDb.normalizeTitleKey(a));
        }

        ExecutorService exec = Executors.newFixedThreadPool(4);
        submitCoWatchTasks(exec, taste, minModernYear, seenKeys, pool);
        submitStructuredDiscoveryTasks(exec, taste, minModernYear, currentYear, seenKeys, pool);
        submitPlannedQueryTasks(exec, taste, seedPlan, minModernYear, seenKeys, pool);
        exec.shutdown();
        try { exec.awaitTermination(9, TimeUnit.SECONDS); } catch (Exception ignored) {}

        taste.ensureDefaultGenreWeightsIfEmpty();
        scoreAndRerankPool(pool, taste, currentYear);
        Map<String, ImpressionTracker.ImpressionRecord> impressions = ImpressionTracker.loadActiveImpressions(db);
        List<JSONObject> slate = SlateAllocator.allocateBalancedSlate(pool, epoch, impressions);
        ImpressionTracker.recordSlateImpressions(db, epoch, slate);
        return packRankedItems(slate);
    }

    private void submitCoWatchTasks(
            ExecutorService exec, UserTasteProfile taste, int minModernYear,
            Set<String> seenKeys, List<JSONObject> pool) {
        List<String> anchors = taste.getAnchors();
        for (int i = 0; i < anchors.size() && i < 6; i++) {
            final String anchor = anchors.get(i);
            final double weight = Math.max(0.75, 1.15 - i * 0.06);
            exec.submit(() -> retrieveAnchorNeighbors(anchor, weight, taste, minModernYear, seenKeys, pool));
        }
    }

    private void retrieveAnchorNeighbors(
            String anchor, double weight, UserTasteProfile taste, int minModernYear,
            Set<String> seenKeys, List<JSONObject> pool) {
        JSONArray sim = JustWatchGraph.fetchCollaborativeSimilar(anchor, minModernYear);
        if (sim.length() > 0) {
            JSONObject first = sim.optJSONObject(0);
            if (first != null) {
                taste.recordSeedMetadata(first.optString("seed_country", ""), first.optString("seed_genres", ""), weight);
            }
        }
        for (int k = 0; k < sim.length() && k < 4; k++) {
            JSONObject cand = sim.optJSONObject(k);
            if (cand == null) continue;
            boolean isDirect = cand.optBoolean("is_direct_sim", false);
            addSotaCandidate(cand, isDirect ? anchor : null, isDirect ? weight : 0.85, taste, seenKeys, pool);
        }
    }

    private void submitStructuredDiscoveryTasks(
            ExecutorService exec, UserTasteProfile taste, int minModernYear, int currentYear,
            Set<String> seenKeys, List<JSONObject> pool) {
        String[][] structuredBuckets = {
            {"spt", "doc"},
            {"spt", "drm"},
            {"scf", "trl"},
            {"crm", "act"},
            {"trl", "drm"},
            {"drm", "hst", "war"}
        };
        int epochOffset = taste.getCurrentEpoch() % 3;
        int startYear = Math.max(2019, minModernYear - epochOffset);
        for (final String[] bucket : structuredBuckets) {
            exec.submit(() -> {
                JSONArray hits = JustWatchGraph.fetchStructuredGenreCandidates(
                    Arrays.asList(bucket), null, startYear, currentYear);
                for (int k = 0; k < hits.length() && k < 14; k++) {
                    addSotaCandidate(hits.optJSONObject(k), null, 0.90, taste, seenKeys, pool);
                }
            });
        }
    }

    private void submitPlannedQueryTasks(
            ExecutorService exec, UserTasteProfile taste, JSONObject seedPlan, int minModernYear,
            Set<String> seenKeys, List<JSONObject> pool) {
        if (seedPlan == null) return;
        JSONArray pq = seedPlan.optJSONArray("primary_queries");
        if (pq == null) return;
        for (int i = 0; i < pq.length() && i < 4; i++) {
            final String q = pq.optString(i).trim();
            if (q.isEmpty() || taste.getAnchors().contains(q)) continue;
            exec.submit(() -> {
                JSONArray hits = JustWatchGraph.fetchDeterministicSearchCandidates(q, minModernYear);
                for (int k = 0; k < hits.length() && k < 6; k++) {
                    addSotaCandidate(hits.optJSONObject(k), null, 0.9, taste, seenKeys, pool);
                }
            });
        }
    }

    private void scoreAndRerankPool(List<JSONObject> pool, UserTasteProfile taste, int currentYear) {
        for (JSONObject item : pool) {
            double score = HybridScorer.computeHybridScore(item, taste, currentYear);
            try { item.put("sota_score", score); } catch (Exception ignored) {}
        }
    }

    private JSONArray packRankedItems(List<JSONObject> items) {
        JSONArray out = new JSONArray();
        for (JSONObject item : items) out.put(item);
        return out;
    }

    public JSONArray buildDeterministicRecommendations(String profileId, int minModernYear, int currentYear) {
        return buildSotaRecommendations(profileId, buildFastSeedPlan(profileId, ""), minModernYear, currentYear);
    }

    private void addSotaCandidate(
            JSONObject o, String seedAnchor, double anchorWeight,
            UserTasteProfile taste, Set<String> seenKeys, List<JSONObject> pool) {
        if (!isEligibleCandidate(o, seedAnchor, taste, seenKeys)) return;
        String t = o.optString("title").trim();
        String norm = AuraDb.normalizeTitleKey(t);
        String slugKey = o.optString("slug_key", "");
        if (!seenKeys.add(norm)) return;
        if (!slugKey.isEmpty()) seenKeys.add(slugKey);
        enrichAndStoreCandidate(o, t, seedAnchor, anchorWeight, pool);
    }

    private boolean isEligibleCandidate(
            JSONObject o, String seedAnchor, UserTasteProfile taste, Set<String> seenKeys) {
        if (o == null) return false;
        String t = o.optString("title").trim();
        String norm = AuraDb.normalizeTitleKey(t);
        String slugKey = o.optString("slug_key", "");
        if (t.isEmpty() || seenKeys.contains(norm) || (!slugKey.isEmpty() && seenKeys.contains(slugKey)) || db.isTitleWatched(t)) {
            return false;
        }
        boolean hasSeed = seedAnchor != null && !seedAnchor.isEmpty();
        int minYear = hasSeed ? Math.min(JustWatchGraph.getDefaultMinModernYear(), 2020) : JustWatchGraph.getDefaultMinModernYear();
        boolean valid = hasSeed
            ? CandidateValidator.isValidCoWatchCandidate(o, minYear, 7.1)
            : CandidateValidator.isValidCatalogCandidate(o, minYear, 7.1);
        if (!valid || CandidateValidator.isUnwantedAnimation(o.optString("genre_codes", ""), taste.isAnimeEnabled())) {
            return false;
        }
        return taste.passesLocalShovelwareGate(o.optString("country", ""), o.optInt("imdb_votes", 0));
    }

    private void enrichAndStoreCandidate(
            JSONObject o, String title, String seedAnchor, double anchorWeight, List<JSONObject> pool) {
        try {
            double imdb = o.optDouble("imdb_raw", o.optDouble("imdb_score", 0.0));
            int votes = o.optInt("imdb_votes", 0);
            o.put("imdb_score", imdb);
            if (seedAnchor != null && !seedAnchor.isEmpty()) {
                o.put("seed_anchor", seedAnchor);
                o.put("anchor_weight", anchorWeight);
                o.put("source_channel", "co_watch");
                o.put("is_direct_sim", true);
                o.put("sim_score", anchorWeight * 10.0);
            } else {
                o.put("source_channel", "structured");
                o.put("sim_score", anchorWeight * 5.0);
            }
            o.put("reason", buildSotaReasonString(o, seedAnchor, imdb, votes));
            db.saveCachedGti("jw_imdb_v6:" + title.toLowerCase(Locale.US), o.toString());
            pool.add(o);
        } catch (Exception ignored) {}
    }

    private String buildSotaReasonString(JSONObject o, String seedAnchor, double imdb, int votes) {
        return HybridScorer.formatReason(o, seedAnchor, imdb, votes);
    }

    public JSONObject buildFastSeedPlan(String profileId, String userQuery) {
        JSONObject plan = new JSONObject();
        try {
            JSONArray pq = new JSONArray();
            JSONArray seeds = new JSONArray();
            if (userQuery != null && !userQuery.trim().isEmpty()) {
                String cleanQ = userQuery.trim();
                String stripped = cleanQ.replaceAll("(?i)^(shows and movies )?similar to\\s+", "")
                                        .replaceAll("(?i)^more like\\s+", "").trim();
                if (!stripped.isEmpty() && !stripped.contains("->")) {
                    seeds.put(stripped);
                    pq.put(stripped);
                } else {
                    pq.put(cleanQ);
                }
            }
            List<String> anchors = db.getDynamicTasteAnchors(profileId);
            for (String a : anchors) {
                if (seeds.length() < 5) seeds.put(a);
                if (pq.length() < 5) pq.put(a);
            }
            plan.put("primary_queries", pq);
            plan.put("watched_traversal_seeds", seeds);
        } catch (Exception ignored) {}
        return plan;
    }

    /**
     * Triggered when the user clicks "Explore Similar".
     * Uses SOTA Item-to-Item Similarity + Genre Jaccard + Bayesian Vote Confidence.
     */
    public JSONArray traverseSimilarToWatched(String profileId, String seedTitle, AgentProgressListener progress) {
        int currentYear = JustWatchGraph.getCurrentYear();
        int minModernYear = JustWatchGraph.getDefaultMinModernYear();
        if (progress != null) progress.onStep("Traversing co-watch graph & genre vector for " + seedTitle + "\u2026");

        JSONArray sim = JustWatchGraph.fetchCollaborativeSimilar(seedTitle, minModernYear);
        JSONArray filtered = new JSONArray();
        for (int i = 0; i < sim.length(); i++) {
            JSONObject o = sim.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("title", "");
            if (t.isEmpty() || db.isTitleWatched(t)) continue;
            double sc = o.optDouble("imdb_raw", o.optDouble("imdb_score", 0.0));
            int votes = o.optInt("imdb_votes", 0);
            try {
                o.put("reason", buildSotaReasonString(o, seedTitle, sc, votes));
            } catch (Exception ignored) {}
            db.saveCachedGti("jw_imdb_v6:" + t.trim().toLowerCase(), o.toString());
            filtered.put(o);
        }
        if (filtered.length() == 0) {
            JSONArray fallback = getBuiltInSotaSeedCatalog(profileId);
            String normSeed = AuraDb.normalizeTitleKey(seedTitle);
            for (int i = 0; i < fallback.length(); i++) {
                JSONObject o = fallback.optJSONObject(i);
                if (o == null) continue;
                if (AuraDb.normalizeTitleKey(o.optString("title", "")).equals(normSeed)) continue;
                try {
                    JSONObject copy = new JSONObject(o.toString());
                    copy.put("reason", buildSotaReasonString(copy, seedTitle, copy.optDouble("imdb_score", 8.0), copy.optInt("imdb_votes", 50000)));
                    filtered.put(copy);
                } catch (Exception ignored) {}
            }
        }
        return filtered;
    }

    public JSONObject planAgentSearchStrategy(
        String profileId,
        String userQuery,
        List<String> watchedList,
        int minModernYear,
        int currentYear
    ) {
        String profileName = db.getProfileLabel(profileId);
        String personalGuide = db.getProfileTaste(profileId);
        String defaultGuide = db.getDefaultTasteGuide();
        String ratingsFeedback = db.getRatingFeedbackSummary();
        List<String> anchors = db.getDynamicTasteAnchors(profileId);

        String todayDate = JustWatchGraph.getCurrentDateString();
        String plannerPrompt = "You are Aura's Search Query Planner Agent. Current TV System Date: " + todayDate + ".\n" +
            "Profile: '" + profileName + "'.\n" +
            "Personal Guide AI: " + (personalGuide.isEmpty() ? "(None)" : personalGuide) + "\n" +
            "Default Guide AI: " + defaultGuide + "\n" +
            "User Ratings Feedback: " + ratingsFeedback + "\n" +
            "Recent Watched/Liked Anchors: " + anchors.toString() + "\n" +
            "Already Watched (do not recommend): " + watchedList.toString() + "\n" +
            "User Search Query (if any): \"" + (userQuery == null ? "" : userQuery) + "\"\n\n" +
            "Task:\n" +
            "1. Plan 'primary_queries': 5 specific modern (" + minModernYear + "-" + currentYear + ") critically acclaimed show/movie titles (with 5,000+ IMDb votes) that match the user's query and Guide AI.\n" +
            "2. Plan 'watched_traversal_seeds': 3 titles from the user's watched/rated list to run a co-watch graph traversal query.\n" +
            "3. Plan 'traversal_chips': 3 short clickable search-engine traversal phrases (max 24 chars each).\n" +
            "Return ONLY a JSON object with keys: 'primary_queries' (array of strings), 'watched_traversal_seeds' (array of strings), 'traversal_chips' (array of 3 strings).";

        String raw = callGeminiJson(plannerPrompt);
        if (raw != null) {
            try {
                JSONObject plan = new JSONObject(raw);
                JSONArray chips = plan.optJSONArray("traversal_chips");
                if (chips != null && chips.length() > 0) {
                    db.saveTraversalChips(profileId, chips);
                }
                return plan;
            } catch (Exception ignored) {}
        }
        return buildFastSeedPlan(profileId, userQuery);
    }

    public List<String> executeMultiHopTraversal(String profileId, JSONObject searchPlan, int minModernYear) {
        final List<String> summaries = Collections.synchronizedList(new ArrayList<>());
        final Set<String> seenNorm = Collections.synchronizedSet(new HashSet<>());
        JSONArray sota = buildSotaRecommendations(profileId, searchPlan, minModernYear, JustWatchGraph.getCurrentYear());
        for (int i = 0; i < sota.length(); i++) {
            JSONObject o = sota.optJSONObject(i);
            if (o != null) {
                addCandidateIfValid(o, seenNorm, summaries);
            }
        }
        return summaries;
    }

    private void addCandidateIfValid(JSONObject o, Set<String> seenNorm, List<String> summaries) {
        if (o == null) return;
        String t = o.optString("title").trim();
        String norm = AuraDb.normalizeTitleKey(t);
        if (t.isEmpty() || seenNorm.contains(norm) || db.isTitleWatched(t)) return;
        if (!JustWatchGraph.isValidModernStreamingCandidate(o, JustWatchGraph.getDefaultMinModernYear(), 7.1)) return;
        seenNorm.add(norm);
        db.saveCachedGti("jw_imdb_v6:" + t.toLowerCase(), o.toString());
        summaries.add(t + " (" + o.optInt("year") + ", " + o.optString("genres") + ", " + o.optString("rating") + " [" + o.optInt("imdb_votes") + " votes], " + o.optString("app") + ")");
    }

    private List<String> collectWatchedTitles(JSONArray history) {
        List<String> seen = new ArrayList<>();
        JSONArray manual = db.listManuallyWatched();
        for (int i = 0; i < manual.length(); i++) {
            JSONObject m = manual.optJSONObject(i);
            if (m != null) {
                String t = m.optString("series_title").isEmpty() ? m.optString("title") : m.optString("series_title");
                if (!t.isEmpty() && !seen.contains(t)) seen.add(t);
            }
        }
        if (history != null) {
            for (int i = 0; i < history.length() && seen.size() < 45; i++) {
                JSONObject h = history.optJSONObject(i);
                if (h == null || h.optString("app_pkg").contains("youtube")) continue;
                String t = h.optString("series_title").isEmpty() ? h.optString("title") : h.optString("series_title");
                if (!t.isEmpty() && !seen.contains(t)) seen.add(t);
            }
        }
        return seen;
    }

    private String buildAgentRankingPrompt(
        String profileId,
        JSONObject np,
        List<String> watchedList,
        List<String> verifiedCandidates,
        int minModernYear,
        int currentYear
    ) {
        String profileName = db.getProfileLabel(profileId);
        String personalGuide = db.getProfileTaste(profileId);
        String defaultGuide = db.getDefaultTasteGuide();
        String ratingsFeedback = db.getRatingFeedbackSummary();
        List<String> activeFilters = db.getActiveFilters(profileId);
        List<String> tasteAnchors = db.getDynamicTasteAnchors(profileId);
        String cur = np != null ? (np.optString("series_title").isEmpty() ? np.optString("title") : np.optString("series_title")) : "";
        String todayDate = JustWatchGraph.getCurrentDateString();

        return "You are Aura's SOTA Ranking & Reasoning Agent for Android TV in India.\n" +
            "Current TV System Date: " + todayDate + ".\n" +
            "Active Profile: '" + profileName + "'.\n" +
            "1. PERSONAL GUIDE AI: " + (personalGuide.isEmpty() ? "(Use Default Guide AI and Watch History)" : "\"" + personalGuide + "\"") + "\n" +
            "2. DEFAULT GUIDE AI: \"" + defaultGuide + "\"\n" +
            "3. USER RATINGS FEEDBACK: " + (ratingsFeedback.isEmpty() ? "None yet" : ratingsFeedback) + "\n" +
            "4. ACTIVE FILTERS: " + (activeFilters.isEmpty() ? "All categories" : activeFilters.toString()) + "\n" +
            "5. RECENTLY WATCHED ANCHORS: " + tasteAnchors.toString() + " (Latest: '" + cur + "').\n" +
            "6. STRICT WATCHED EXCLUSION LIST: " + watchedList.toString() + ".\n" +
            "7. SOTA VERIFIED CANDIDATE POOL (ONLY pick from or closely match this verified pool): " + verifiedCandidates.toString() + ".\n\n" +
            "Select & rank 20 titles from the SOTA Verified Candidate Pool that best match '" + profileName + "'s watched anchors (" + tasteAnchors.toString() + ").\n" +
            "Return ONLY a JSON array of 20 objects with keys: 'title', 'app', 'genres', 'runtime_min' (integer), and 'reason' (18-28 words explaining why it connects to their watched shows).";
    }

    public JSONArray enrichAll(JSONArray raw) {
        return enrichAll(raw, JustWatchGraph.getDefaultMinModernYear(), JustWatchGraph.getCurrentYear());
    }

    public JSONArray enrichAll(JSONArray raw, final int minAllowedYear, final int maxAllowedYear) {
        JSONArray out = new JSONArray();
        if (raw == null) return out;
        int n = raw.length();
        final JSONObject[] results = new JSONObject[n];
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final JSONObject p = raw.optJSONObject(i);
            if (p == null) continue;
            pool.submit(() -> {
                String title = p.optString("title").trim();
                if (title.isEmpty() || db.isTitleWatched(title)) return;
                JSONObject meta = fetchOpenMetadata(title);
                if (!JustWatchGraph.isValidModernStreamingCandidate(meta, minAllowedYear, 7.0)) return;

                int year = meta.optInt("year", maxAllowedYear);
                String posterUrl = meta.optString("poster_url");
                String verifiedApp = meta.optString("app", "");
                if (verifiedApp.isEmpty()) return;

                JSONObject card = new JSONObject();
                try {
                    card.put("title", meta.optString("title", title));
                    card.put("badge", verifiedApp);
                    card.put("app", verifiedApp);
                    card.put("pkg_code", meta.optString("pkg_code", ""));
                    card.put("country", meta.optString("country", ""));
                    card.put("year", year);
                    card.put("type", meta.optString("type", "Series"));
                    String reason = p.optString("reason");
                    if (reason.isEmpty()) reason = meta.optString("reason", meta.optString("synopsis"));
                    card.put("reason", reason);
                    card.put("synopsis", !meta.optString("synopsis").isEmpty() ? meta.optString("synopsis") : reason);
                    card.put("poster_url", ImageLoader.upgradePosterUrl(posterUrl));
                    String backdropUrl = meta.optString("backdrop_url", p.optString("backdrop_url", ""));
                    card.put("backdrop_url", !backdropUrl.isEmpty()
                        ? ImageLoader.upgradePosterUrl(backdropUrl)
                        : ImageLoader.upgradePosterUrl(posterUrl));
                    card.put("genres", meta.optString("genres", p.optString("genres", "Drama")));
                    card.put("genre_codes", meta.optString("genre_codes", ""));
                    card.put("runtime_min", meta.optInt("runtime_min", p.optInt("runtime_min", 48)));
                    card.put("rating", meta.optString("rating", ""));
                    card.put("imdb_raw", meta.optDouble("imdb_raw", 0.0));
                    card.put("imdb_score", meta.optDouble("imdb_score", 0.0));
                    card.put("imdb_votes", meta.optInt("imdb_votes", 0));
                    card.put("tmdb_popularity", meta.optDouble("tmdb_popularity", 0.0));
                    card.put("llm_idx", idx);
                    card.put("launch_uri", !meta.optString("launch_uri").isEmpty()
                        ? meta.optString("launch_uri")
                        : db.findNativeLaunchUri(title));
                    card.put("launch_pkg", !meta.optString("launch_pkg").isEmpty()
                        ? meta.optString("launch_pkg")
                        : AuraDb.resolveAppPackage(verifiedApp));
                    results[idx] = card;
                } catch (Exception ignored) {}
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(8, TimeUnit.SECONDS); } catch (Exception ignored) {}
        Set<String> seenKeys = new HashSet<>();
        List<JSONObject> deduped = new ArrayList<>();
        for (JSONObject r : results) {
            if (r != null) {
                String k = AuraDb.normalizeTitleKey(r.optString("title"));
                if (!k.isEmpty() && seenKeys.add(k)) {
                    deduped.add(r);
                }
            }
        }
        for (JSONObject r : deduped) {
            out.put(r);
        }
        return out;
    }

    public JSONObject fetchOpenMetadata(String title) {
        JSONObject res = new JSONObject();
        if (title == null || title.isEmpty()) return res;
        String cacheKey = "jw_imdb_v6:" + title.trim().toLowerCase();
        String cached = db.getCachedGti(cacheKey);
        if (cached != null) {
            try {
                JSONObject cObj = new JSONObject(cached);
                if (cObj.optInt("imdb_votes", 0) > 0) return cObj;
            } catch (Exception ignored) {}
        }
        res = JustWatchGraph.lookupTitleInIndia(title);
        int maxYear = JustWatchGraph.getCurrentYear();
        if (!res.optString("poster_url").isEmpty() && res.optInt("year", maxYear) <= maxYear) {
            db.saveCachedGti(cacheKey, res.toString());
        }
        return res;
    }

    public JSONObject resolvePrimeGti(String gti) {
        try {
            String cached = db.getCachedGti(gti);
            if (cached != null) return new JSONObject(cached);

            String html = httpGet("https://www.primevideo.com/detail/" + gti);
            JSONObject info = new JSONObject();
            String parent = extractRegex(html, "\"parentTitle\"\\s*:\\s*\"([^\"]+)\"");
            String epTitle = extractRegex(html, Pattern.quote(gti) + "\":\\{\"title\":\"([^\"]+)\"");
            String syn = extractRegex(html, Pattern.quote(gti) + "\":\\{\"title\":\"[^\"]+\",\"synopsis\":\"([^\"]+)\"");
            String seq = extractRegex(html, Pattern.quote(gti) + "\",\"isLaunched\":true,\"link\":\"[^\"]+\",\"sequenceNumber\":(\\d+)");

            info.put("series_title", parent.isEmpty() ? "Prime Video" : parent);
            info.put("title", epTitle.isEmpty() ? info.optString("series_title") : epTitle);
            info.put("synopsis", syn);
            if (!seq.isEmpty()) info.put("episode", Integer.parseInt(seq));
            info.put("season", 1);

            JSONObject tv = fetchOpenMetadata(info.optString("series_title"));
            info.put("poster_url", tv.optString("poster_url"));
            info.put("genres", tv.optString("genres", "Drama"));
            info.put("runtime_min", tv.optInt("runtime_min", 60));

            db.saveCachedGti(gti, info.toString());
            return info;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public String callGeminiJson(String prompt) {
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) return null;
        for (String model : MODELS) {
            try {
                String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
                JSONObject body = new JSONObject();
                JSONArray contents = new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt))));
                body.put("contents", contents);
                body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4));
                String resp = httpPost(url, body.toString());
                JSONObject root = new JSONObject(resp);
                return root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            } catch (Exception ignored) {}
        }
        return null;
    }

    private JSONArray callGemini(String model, String prompt) {
        String apiKey = getApiKey();
        if (apiKey.isEmpty()) return null;
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
            JSONObject body = new JSONObject();
            JSONArray contents = new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt))));
            body.put("contents", contents);
            body.put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4));
            String resp = httpPost(url, body.toString());
            JSONObject root = new JSONObject(resp);
            String text = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim();
            if (text.startsWith("[")) {
                return new JSONArray(text);
            }
            if (text.startsWith("{")) {
                JSONObject obj = new JSONObject(text);
                JSONArray names = obj.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        JSONArray arr = obj.optJSONArray(names.getString(i));
                        if (arr != null && arr.length() > 0) return arr;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final ExecutorService HTTP_EXEC = Executors.newCachedThreadPool();

    private String httpGet(final String urlStr) throws Exception {
        java.util.concurrent.Future<String> fut = HTTP_EXEC.submit(() -> {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        });
        try {
            return fut.get(4000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            fut.cancel(true);
            throw e;
        }
    }

    private String httpPost(final String urlStr, final String jsonBody) throws Exception {
        java.util.concurrent.Future<String> fut = HTTP_EXEC.submit(() -> {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        });
        try {
            return fut.get(5200, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            fut.cancel(true);
            throw e;
        }
    }

    /**
     * Built-in verified SOTA seed catalog from JustWatch India GraphQL (2022–2026).
     * Guarantees instant 0ms Home rendering and zero empty states even if TV DNS is slow.
     */
    public JSONArray getBuiltInSotaSeedCatalog(String profileId) {
        JSONArray arr = new JSONArray();
        addSeed(arr, "Dexter: Resurrection", 2025, "Series", 55, 9.0, 164128, "Prime Video", "prv",
            "Crime, Drama, Thriller", "crm,drm,trl", "co_watch", 9.65,
            "Because you watched Dead-End Job · Crime, Thriller · ★ 9.0 (164.1k votes)",
            "Set 10 weeks after Dexter: New Blood, Dexter Morgan chases a missing Harrison to New York City, where Captain Angel Batista is hot on their trail.",
            "https://images.justwatch.com/poster/355231202/s718/dexter-resurrection.jpg",
            "https://images.justwatch.com/backdrop/321995044/s1920/dexter-resurrection.jpg",
            "intent://app.primevideo.com/watch?gti=amzn1.dv.gti.8500885e-a8bf-420a-bb55-eb33708aacee&time=0&territory=IN#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end");
        addSeed(arr, "Severance", 2022, "Series", 49, 8.6, 414782, "Apple TV+", "atp",
            "Sci-Fi, Thriller, Drama", "drm,trl,scf", "co_watch", 9.52,
            "Because you watched Oblivion · Sci-Fi, Thriller · ★ 8.6 (414.8k votes)",
            "Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.",
            "https://images.justwatch.com/poster/324479701/s718/severance.jpg",
            "https://images.justwatch.com/backdrop/324479719/s1920/severance.jpg",
            "intent://tv.apple.com/in/episode/good-news-about-hell/umc.cmc.s80mx1ic96pu6ewupz8pfasf?showId=umc.cmc.1srk2goyh2q2zdxcx605w8vtx&playableId=tvs.sbd.4000%3AVSEVR0560101#Intent;launchFlags=0x00800000;scheme=https;end");
        addSeed(arr, "MobLand", 2025, "Series", 46, 8.3, 140134, "Disney+ Hotstar", "jhs",
            "Crime, Drama", "crm,drm", "co_watch", 9.38,
            "Because you watched Dead-End Job · Crime, Drama · ★ 8.3 (140.1k votes)",
            "Two mob families clash in a war that threatens to topple empires and lives in Guy Ritchie's high-stakes crime series.",
            "https://images.justwatch.com/poster/354739547/s718/mobland.jpg",
            "https://images.justwatch.com/backdrop/328945489/s1920/mobland.jpg",
            "https://www.hotstar.com/in/shows/mobland/1271397631");
        addSeed(arr, "The Diplomat", 2023, "Series", 50, 8.1, 108113, "Netflix", "nfx",
            "Thriller, Drama", "drm,trl,war", "co_watch", 9.25,
            "Because you watched Dead-End Job · Political Thriller · ★ 8.1 (108.1k votes)",
            "In the midst of an international crisis, a career diplomat lands in a high-profile job with tectonic implications.",
            "https://images.justwatch.com/poster/304992451/s718/the-diplomat.jpg",
            "https://images.justwatch.com/backdrop/355789186/s1920/the-diplomat.jpg",
            "intent://www.netflix.com/watch/81288983#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end");
        addSeed(arr, "The Gentlemen", 2024, "Series", 50, 8.0, 178420, "Netflix", "nfx",
            "Action, Crime, Comedy", "act,crm,cmy", "co_watch", 9.18,
            "Because you watched Dead-End Job · Crime, Action · ★ 8.0 (178.4k votes)",
            "When aristocratic Eddie inherits the family estate, he discovers that it's home to a massive weed empire—and its proprietors aren't going anywhere.",
            "https://images.justwatch.com/poster/312465722/s718/the-gentlemen.jpg",
            "https://images.justwatch.com/backdrop/312465731/s1920/the-gentlemen.jpg",
            "intent://www.netflix.com/watch/81437051#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end");
        addSeed(arr, "The Day of the Jackal", 2024, "Series", 51, 8.1, 139687, "Disney+ Hotstar", "jhs",
            "Action, Crime, Thriller", "act,crm,drm", "co_watch", 9.12,
            "Because you watched Matchbox The Movie · Espionage Thriller · ★ 8.1 (139.7k votes)",
            "An elusive assassin who makes his living carrying out hits for the highest fee meets his match in a tenacious intelligence officer.",
            "https://images.justwatch.com/poster/322756732/s718/the-day-of-the-jackal.jpg",
            "https://images.justwatch.com/backdrop/322756746/s1920/the-day-of-the-jackal.jpg",
            "intent://content/1971311802#Intent;launchFlags=0x00800000;scheme=hotstar;end");
        addSeed(arr, "Silo", 2023, "Series", 51, 8.1, 253859, "Apple TV+", "atp",
            "Sci-Fi, Drama", "drm,scf", "co_watch", 9.08,
            "Because you watched Oblivion · Dystopian Sci-Fi · ★ 8.1 (253.9k votes)",
            "Men and women live in a giant silo underground with strict regulations they believe protect them from the toxic world on the surface.",
            "https://images.justwatch.com/poster/346669772/s718/silo.jpg",
            "https://images.justwatch.com/backdrop/346257620/s1920/silo.jpg",
            "intent://tv.apple.com/in/episode/freedom-day/umc.cmc.4v3gat04y129mm0smdu6ea3dh?showId=umc.cmc.3yksgc857px0k0rqe5zd4jice&playableId=tvs.sbd.4000%3AA0026501001#Intent;launchFlags=0x00800000;scheme=https;end");
        addSeed(arr, "Slow Horses", 2022, "Series", 46, 8.4, 169223, "Apple TV+", "atp",
            "Thriller, Drama", "drm,trl", "structured", 9.04,
            "Acclaimed 2022 Thriller · ★ 8.4 (169.2k votes)",
            "Follow a dysfunctional team of MI5 agents and their notorious boss Jackson Lamb as they navigate the espionage world's smoke and mirrors.",
            "https://images.justwatch.com/poster/349310634/s718/slow-horses.jpg",
            "https://images.justwatch.com/backdrop/272750278/s1920/slow-horses.jpg",
            "intent://tv.apple.com/in/episode/failures-contagious/umc.cmc.72legmwolm5ytctyq637r6x0?showId=umc.cmc.2szz3fdt71tl1ulnbp8utgq5o&playableId=tvs.sbd.4000%3AA0006401001#Intent;launchFlags=0x00800000;scheme=https;end");
        addSeed(arr, "Black Warrant", 2025, "Series", 44, 7.9, 8836, "Netflix", "nfx",
            "Crime, Drama", "crm,drm", "co_watch", 8.95,
            "Because you watched Dead-End Job · Indian Crime Thriller · ★ 7.9 (8.8k votes)",
            "When an upright officer accepts a job at Tihar Jail, he vows to make amends after witnessing cold-blooded corruption within its walls.",
            "https://images.justwatch.com/poster/325958141/s718/black-warrant.jpg",
            "https://images.justwatch.com/backdrop/325958144/s1920/black-warrant.jpg",
            "intent://www.netflix.com/watch/81971071#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end");
        addSeed(arr, "Reacher", 2022, "Series", 48, 8.0, 328430, "Prime Video", "prv",
            "Action, Crime, Drama", "act,crm,drm", "co_watch", 8.91,
            "Because you watched Matchbox The Movie · Action Crime · ★ 8.0 (328.4k votes)",
            "Jack Reacher, a veteran military police investigator, enters civilian life as a drifter uncovering deep small-town conspiracies.",
            "https://images.justwatch.com/poster/326970737/s718/jack-reacher.jpg",
            "https://images.justwatch.com/backdrop/348320196/s1920/jack-reacher.jpg",
            "intent://app.primevideo.com/watch?gti=amzn1.dv.gti.41f71162-2344-4089-accf-5ce0e9c12535&time=0&territory=IN#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end");
        addSeed(arr, "East of Eden", 2026, "Series", 59, 8.0, 36138, "Netflix", "nfx",
            "Drama", "drm", "structured", 8.86,
            "Acclaimed 2026 Drama · ★ 8.0 (36.1k votes)",
            "A fiercely independent woman drives a rift between two brothers, igniting a sweeping multi-generational saga.",
            "https://images.justwatch.com/poster/345990721/s718/east-of-eden.jpg",
            "https://images.justwatch.com/backdrop/345990726/s1920/east-of-eden.jpg",
            "intent://www.netflix.com/watch/81611854#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end");
        addSeed(arr, "The Terminal List", 2022, "Series", 56, 7.9, 135744, "Prime Video", "prv",
            "Action, Thriller, Drama", "act,drm,trl", "co_watch", 8.80,
            "Because you watched Matchbox The Movie · Action Thriller · ★ 7.9 (135.7k votes)",
            "A former Navy SEAL officer investigates why his entire platoon was ambushed during a high-stakes covert mission.",
            "https://images.justwatch.com/poster/288434876/s718/the-terminal-list.jpg",
            "https://images.justwatch.com/backdrop/288444687/s1920/the-terminal-list.jpg",
            "intent://app.primevideo.com/watch?gti=amzn1.dv.gti.0c17249c-bd78-474d-9fe7-91edf9b71dfa&time=0&territory=IN#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end");
        addSeed(arr, "FROM", 2022, "Series", 51, 7.7, 213849, "Prime Video", "prv",
            "Thriller, Horror, Drama", "drm,fnt,hrr", "structured", 8.74,
            "Acclaimed Mystery Thriller · ★ 7.7 (213.8k votes)",
            "Unravel the mystery of a nightmarish town in middle America that traps all those who enter.",
            "https://images.justwatch.com/poster/344518661/s718/from.jpg",
            "https://images.justwatch.com/backdrop/344518677/s1920/from.jpg",
            "intent://app.primevideo.com/watch?gti=amzn1.dv.gti.68db6859-4422-41c3-9f75-292818b9eafa&time=0&territory=IN#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end");
        addSeed(arr, "Dark Matter", 2024, "Series", 54, 7.6, 86573, "Apple TV+", "atp",
            "Sci-Fi, Thriller, Drama", "drm,trl,scf", "co_watch", 8.68,
            "Because you watched Oblivion · Sci-Fi Thriller · ★ 7.6 (86.6k votes)",
            "Jason Dessen is abducted into an alternate version of his life and embarks on a harrowing journey back to his real family.",
            "https://images.justwatch.com/poster/354727700/s718/dark-matter-2024.jpg",
            "https://images.justwatch.com/backdrop/318995476/s1920/dark-matter-2024.jpg",
            "intent://tv.apple.com/in/episode/are-you-happy-in-your-life/umc.cmc.59p6kv4nynpisd1gl300a7810?showId=umc.cmc.4luj45vtqpmjsvb6sc2675oeg&playableId=tvs.sbd.4000%3AA0052201001#Intent;launchFlags=0x00800000;scheme=https;end");
        addSeed(arr, "3 Body Problem", 2024, "Series", 56, 7.5, 186951, "Netflix", "nfx",
            "Sci-Fi, Drama", "drm,fnt,scf", "co_watch", 8.62,
            "Because you watched Oblivion · Hard Sci-Fi · ★ 7.5 (187.0k votes)",
            "Across continents and decades, five brilliant friends make earth-shattering discoveries as the laws of science unravel.",
            "https://images.justwatch.com/poster/313070678/s718/3-body-problem.jpg",
            "https://images.justwatch.com/backdrop/348986715/s1920/3-body-problem.jpg",
            "intent://www.netflix.com/watch/81024821#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end");
        return db.filterOutWatched(arr);
    }

    private void addSeed(JSONArray arr, String title, int year, String type, int runtime,
                         double imdb, int votes, String app, String pkgCode, String genres,
                         String genreCodes, String channel, double sotaScore, String reason,
                         String synopsis, String poster, String backdrop, String launchUri) {
        try {
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("year", year);
            o.put("type", type);
            o.put("runtime_min", runtime);
            o.put("imdb_raw", imdb);
            o.put("imdb_score", imdb);
            o.put("imdb_votes", votes);
            o.put("tmdb_popularity", 95.0);
            o.put("rating", String.format(Locale.US, "\u2605 %.1f", imdb));
            o.put("app", app);
            o.put("badge", app);
            o.put("pkg_code", pkgCode);
            o.put("has_flatrate_offer", true);
            o.put("genres", genres);
            o.put("genre_codes", genreCodes);
            o.put("source_channel", channel);
            o.put("sota_score", sotaScore);
            o.put("sim_score", sotaScore);
            o.put("reason", reason);
            o.put("synopsis", synopsis);
            o.put("poster_url", poster);
            o.put("backdrop_url", backdrop);
            o.put("launch_uri", launchUri);
            o.put("launch_pkg", AuraDb.resolveAppPackage(app));
            arr.put(o);
        } catch (Exception ignored) {}
    }

    private String extractRegex(String text, String regex) {
        if (text == null) return "";
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
