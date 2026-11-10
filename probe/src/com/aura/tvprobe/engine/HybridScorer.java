package com.aura.tvprobe;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Multi-factor hybrid recommendation scorer and Maximal Marginal Relevance (MMR)
 * diversity reranker. Completely agnostic to specific titles or regions.
 */
public final class HybridScorer {

    private HybridScorer() {}

    public static double computeHybridScore(JSONObject item, UserTasteProfile profile, int currentYear) {
        double bayes = CandidateValidator.computeBayesianImdb(item);
        double qualityMult = "prestige".equalsIgnoreCase(profile.getRankMode()) ? 1.35 : 1.15;
        double coWatch = computeCoWatchBoost(item, profile.getTopAnchor()) + computeLlmPlanBoost(item);
        double genreAff = profile.computeGenreAffinity(item.optString("genre_codes", ""));
        double recency = computeRecencyScore(item, currentYear, profile.getRankMode());
        double originAff = computeOriginBoost(item, profile);
        double platformAff = RegionProviderRegistry.isPrimaryTierProvider(item.optString("pkg_code", "")) ? 0.45 : 0.15;
        return (bayes * qualityMult) + coWatch + genreAff + recency + originAff + platformAff;
    }

    private static double computeLlmPlanBoost(JSONObject item) {
        String src = item.optString("source_channel");
        if (!"llm_planned".equals(src) && !"app_trending".equals(src)) return 0.0;
        int rank = Math.max(0, item.optInt("llm_rank", 0));
        double aw = item.optDouble("anchor_weight", 1.0);
        return Math.max(1.25, 2.05 - rank * 0.12) * aw;
    }

    private static double computeCoWatchBoost(JSONObject item, String topAnchor) {
        String seedAnchor = item.optString("seed_anchor", "");
        if (seedAnchor.isEmpty()) return 0.0;
        double aw = item.optDouble("anchor_weight", 1.0);
        double base = (seedAnchor.equalsIgnoreCase(topAnchor) ? 2.15 : 1.75) * aw;
        return base + (item.optBoolean("is_direct_sim", false) ? 0.65 : 0.0);
    }

    private static double computeRecencyScore(JSONObject item, int currentYear, String rankMode) {
        int year = item.optInt("year", currentYear - 2);
        int age = Math.max(0, currentYear - year);
        double weight = "fresh".equalsIgnoreCase(rankMode) ? 0.36 : 0.22;
        return Math.max(0.0, 5.0 - age) * weight;
    }

    private static double computeOriginBoost(JSONObject item, UserTasteProfile profile) {
        String country = item.optString("country", "");
        int votes = item.optInt("imdb_votes", 0);
        double pop = item.optDouble("tmdb_popularity", 0.0);
        boolean hasSeed = !item.optString("seed_anchor", "").isEmpty()
            || "llm_planned".equals(item.optString("source_channel"));
        return profile.computeOriginAffinity(country, votes, pop, hasSeed);
    }

    public static double computeItemToItemScore(JSONObject cand, JSONObject seed, int currentYear) {
        double bayes = CandidateValidator.computeBayesianImdb(cand);
        int age = Math.max(0, currentYear - cand.optInt("year", currentYear - 2));
        double recency = Math.max(0.0, 5.0 - age) * 0.28;
        double directSim = cand.optBoolean("is_direct_sim", false) ? 1.35 : 0.0;
        return bayes + recency + directSim + computeSeedAlignmentBonus(cand, seed);
    }

    private static double computeSeedAlignmentBonus(JSONObject cand, JSONObject seed) {
        if (seed == null) return 0.0;
        double gSim = CandidateValidator.computeGenreJaccard(
            cand.optString("genre_codes", ""), seed.optString("genre_codes", "")) * 2.6;
        double fmt = cand.optString("type", "").equalsIgnoreCase(seed.optString("type", "")) ? 0.45 : 0.0;
        String cCountry = cand.optString("country", "");
        boolean sameOrigin = !cCountry.isEmpty() && cCountry.equalsIgnoreCase(seed.optString("country", ""));
        return gSim + fmt + (sameOrigin ? 0.55 : 0.0);
    }

    public static List<JSONObject> rankByMmr(List<JSONObject> candidates, int topK, double lambda) {
        List<JSONObject> remaining = new ArrayList<>(candidates);
        remaining.sort((a, b) -> Double.compare(b.optDouble("sota_score", 0.0), a.optDouble("sota_score", 0.0)));
        List<JSONObject> selected = new ArrayList<>();
        while (!remaining.isEmpty() && selected.size() < topK) {
            int nextIdx = selected.isEmpty() ? 0 : pickBestMmrIndex(remaining, selected, lambda);
            selected.add(remaining.remove(nextIdx));
        }
        return selected;
    }

    private static int pickBestMmrIndex(List<JSONObject> remaining, List<JSONObject> selected, double lambda) {
        int bestIdx = 0;
        double bestMmr = -1e9;
        int limit = Math.min(remaining.size(), 28);
        for (int i = 0; i < limit; i++) {
            double mmr = evaluateCandidateMmr(remaining.get(i), selected, lambda);
            if (mmr > bestMmr) { bestMmr = mmr; bestIdx = i; }
        }
        return bestIdx;
    }

    private static double evaluateCandidateMmr(JSONObject cand, List<JSONObject> selected, double lambda) {
        double rel = cand.optDouble("sota_score", 0.0);
        return lambda * rel - (1.0 - lambda) * computeMaxPairSimilarity(cand, selected) * 2.2;
    }

    private static double computeMaxPairSimilarity(JSONObject cand, List<JSONObject> selected) {
        double maxSim = 0.0;
        for (JSONObject sel : selected) {
            maxSim = Math.max(maxSim, computePairSimilarity(cand, sel));
        }
        return maxSim;
    }

    private static double computePairSimilarity(JSONObject cand, JSONObject sel) {
        String candGenres = cand.optString("genre_codes", "");
        String selGenres = sel.optString("genre_codes", "");
        double gSim = CandidateValidator.computeGenreJaccard(candGenres, selGenres);
        double sameApp = cand.optString("app", "").equalsIgnoreCase(sel.optString("app", "")) ? 0.25 : 0.0;
        String sA = cand.optString("seed_anchor", "");
        double sameSeed = (!sA.isEmpty() && sA.equalsIgnoreCase(sel.optString("seed_anchor", ""))) ? 0.85 : 0.0;
        double bothAnime = (candGenres.contains("ani") && selGenres.contains("ani")) ? 0.45 : 0.0;
        return gSim * 1.4 + sameApp + sameSeed + bothAnime;
    }

    public static String formatReason(JSONObject item, String seedAnchor, double imdb, int votes) {
        String genres = item.optString("genres", "Drama");
        String voteStr = votes >= 1000 ? String.format(Locale.US, " (%.1fk votes)", votes / 1000.0) : "";
        String prefix = resolveReasonPrefix(item, seedAnchor);
        return prefix + " \u00B7 " + genres + " \u00B7 \u2605 " + String.format(Locale.US, "%.1f", imdb) + voteStr;
    }

    private static String resolveReasonPrefix(JSONObject item, String seedAnchor) {
        if (seedAnchor != null && !seedAnchor.isEmpty()) return "Because you watched " + seedAnchor;
        String bcast = item.optString("broadcast_badge", "");
        if (!bcast.isEmpty()) return bcast;
        if ("llm_planned".equals(item.optString("source_channel"))) return "AI Curated for You";
        return "Acclaimed " + item.optInt("year");
    }
}
