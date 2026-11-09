package com.aura.tvprobe;

import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import static org.junit.Assert.*;

/**
 * Quantitative Information Retrieval (IR) Benchmark for LLM + Hybrid Ranking
 * (NDCG@5, Precision@5, MRR) and Hourly Auto-Refresh Contract Verification.
 */
public class LlmRankingBenchmarkTest {

    @Test
    public void testLlmPlannedCandidatesOutrankMediocreCoWatchNeighborsAndBoostNdcg() throws Exception {
        UserTasteProfile profile = buildBenchmarkProfile();
        List<JSONObject> baselineRanked = scoreAndSortPool(buildBenchmarkPool(false), profile);
        List<JSONObject> llmAwareRanked = scoreAndSortPool(buildBenchmarkPool(true), profile);

        double baseNdcg5 = computeNdcgAtK(baselineRanked, 5);
        double llmNdcg5 = computeNdcgAtK(llmAwareRanked, 5);
        double baseP5 = computePrecisionAtK(baselineRanked, 5);
        double llmP5 = computePrecisionAtK(llmAwareRanked, 5);
        printBenchmarkReport(baseNdcg5, llmNdcg5, baseP5, llmP5, llmAwareRanked);

        assertTrue("LLM-aware ranking must improve NDCG@5 over un-tagged baseline", llmNdcg5 > baseNdcg5);
        assertTrue("LLM-aware NDCG@5 must exceed 0.92", llmNdcg5 >= 0.92);
        assertTrue("LLM-aware Precision@5 (IMDb >= 8.1) must be >= 0.80", llmP5 >= 0.80);
    }

    @Test
    public void testHourlyAutoRefreshContractInAuraActivityAndService() throws Exception {
        String actSrc = readFileText("probe/src/com/aura/tvprobe/app/AuraActivity.java", "src/com/aura/tvprobe/app/AuraActivity.java");
        String svcSrc = readFileText("probe/src/com/aura/tvprobe/service/MediaProbeService.java", "src/com/aura/tvprobe/service/MediaProbeService.java");
        assertTrue("AuraActivity must define 1-hour HOURLY_REFRESH_MS = 3_600_000L", actSrc.contains("HOURLY_REFRESH_MS = 3_600_000L"));
        assertTrue("AuraActivity must schedule hourlyRecoRefreshTask", actSrc.contains("scheduleHourlyRecoRefresh()"));
        assertTrue("MediaProbeService must force refresh when 1h cache expires", svcSrc.contains("inspector.syncAndBuildState(true)"));
    }

    private static UserTasteProfile buildBenchmarkProfile() {
        List<String> anchors = Arrays.asList("The Boys", "Dark", "The Last of Us", "Severance");
        UserTasteProfile p = new UserTasteProfile(anchors, "IN", "English", "prestige", 1);
        p.recordSeedMetadata("US", "scf,trl,drm", 2.2);
        p.recordSeedMetadata("DE", "crm,trl,drm", 1.6);
        return p;
    }

    private static List<JSONObject> buildBenchmarkPool(boolean tagLlm) throws Exception {
        List<JSONObject> pool = new ArrayList<>();
        // Mediocre Co-Watch Graph Neighbors (IMDb 7.2 - 7.5)
        pool.add(makeItem("Dear Child", 2023, 7.3, 35800, "crm,drm,trl", "co_watch", "Dark", 0, 1.0, 1.0));
        pool.add(makeItem("Kleo", 2022, 7.5, 18592, "act,crm,drm", "co_watch", "Dark", 0, 1.0, 1.0));
        pool.add(makeItem("Gen V", 2023, 7.6, 158449, "act,cmy,drm", "co_watch", "The Boys", 0, 1.15, 2.0));
        // Acclaimed LLM-Planned Semantic Picks from Gemini (IMDb 8.1 - 8.6)
        String ch = tagLlm ? "llm_planned" : "structured";
        pool.add(makeItem("Andor", 2022, 8.6, 290326, "scf,act,drm", ch, "", 0, 1.10, 3.0));
        pool.add(makeItem("Shogun", 2024, 8.6, 255343, "drm,act,trl", ch, "", 1, 1.05, 3.0));
        pool.add(makeItem("Silo", 2023, 8.1, 165000, "scf,drm,trl", ch, "", 2, 1.00, 3.0));
        pool.add(makeItem("Fallout", 2024, 8.3, 280000, "scf,act,drm", ch, "", 3, 0.95, 3.0));
        return pool;
    }

    private static JSONObject makeItem(String title, int year, double imdb, int votes, String genres,
                                       String channel, String seed, int llmRank, double aw, double relGrade) throws Exception {
        return new JSONObject().put("title", title).put("year", year).put("imdb_raw", imdb)
            .put("imdb_score", imdb).put("imdb_votes", votes).put("tmdb_popularity", 32.0)
            .put("genre_codes", genres).put("source_channel", channel).put("seed_anchor", seed)
            .put("is_direct_sim", !seed.isEmpty()).put("llm_rank", llmRank).put("anchor_weight", aw)
            .put("country", "US").put("pkg_code", "nfx").put("rel_grade", relGrade);
    }

    private static List<JSONObject> scoreAndSortPool(List<JSONObject> pool, UserTasteProfile profile) throws Exception {
        for (JSONObject item : pool) {
            item.put("sota_score", HybridScorer.computeHybridScore(item, profile, 2026));
        }
        pool.sort((a, b) -> Double.compare(b.optDouble("sota_score"), a.optDouble("sota_score")));
        return pool;
    }

    private static double computeDcgAtK(List<JSONObject> ranked, int k) {
        double dcg = 0.0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            double gain = Math.pow(2.0, ranked.get(i).optDouble("rel_grade", 0.0)) - 1.0;
            dcg += gain / (Math.log(i + 2.0) / Math.log(2.0));
        }
        return dcg;
    }

    private static double computeNdcgAtK(List<JSONObject> ranked, int k) {
        List<JSONObject> ideal = new ArrayList<>(ranked);
        ideal.sort((a, b) -> Double.compare(b.optDouble("rel_grade"), a.optDouble("rel_grade")));
        double idcg = computeDcgAtK(ideal, k);
        return idcg > 0.0 ? computeDcgAtK(ranked, k) / idcg : 0.0;
    }

    private static double computePrecisionAtK(List<JSONObject> ranked, int k) {
        int hits = 0, limit = Math.min(k, ranked.size());
        for (int i = 0; i < limit; i++) {
            if (ranked.get(i).optDouble("imdb_raw", 0.0) >= 8.1) hits++;
        }
        return limit > 0 ? (double) hits / limit : 0.0;
    }

    private static void printBenchmarkReport(double baseNdcg, double llmNdcg,
                                             double baseP5, double llmP5, List<JSONObject> ranked) {
        System.out.printf(Locale.US,
            "    [BENCHMARK] Baseline NDCG@5=%.3f (P@5=%.0f%%) -> LLM-Aware NDCG@5=%.3f (P@5=%.0f%%) | Top-1=%s (%.2f)%n",
            baseNdcg, baseP5 * 100.0, llmNdcg, llmP5 * 100.0,
            ranked.get(0).optString("title"), ranked.get(0).optDouble("sota_score"));
    }

    private static String readFileText(String rootRel, String probeRel) throws Exception {
        File f = new File(rootRel).exists() ? new File(rootRel) : new File(probeRel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
