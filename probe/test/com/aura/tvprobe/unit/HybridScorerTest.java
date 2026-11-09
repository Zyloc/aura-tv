package com.aura.tvprobe;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link HybridScorer}: multi-factor hybrid utility calculation
 * and Maximal Marginal Relevance (MMR) diversity reranking.
 */
public class HybridScorerTest {

    @Test
    public void testHybridScorePrioritizesHighQualityTasteAlignedCandidates() throws Exception {
        UserTasteProfile profile = new UserTasteProfile(
            Arrays.asList("Severance"), "IN", "English", "prestige", 1);
        profile.recordSeedMetadata("US", "scf,trl", 2.5);
        int year = CandidateValidator.getCurrentYear();

        JSONObject strongMatch = new JSONObject()
            .put("title", "Dark Matter").put("year", year)
            .put("imdb_raw", 8.4).put("imdb_votes", 45000).put("tmdb_popularity", 50.0)
            .put("genre_codes", "scf,trl").put("country", "US").put("pkg_code", "atp")
            .put("seed_anchor", "Severance").put("anchor_weight", 1.2).put("is_direct_sim", true);

        JSONObject weakMatch = new JSONObject()
            .put("title", "Generic RomCom").put("year", year - 4)
            .put("imdb_raw", 6.6).put("imdb_votes", 4000).put("tmdb_popularity", 9.0)
            .put("genre_codes", "rma,cmy").put("country", "US").put("pkg_code", "zee");

        double sStrong = HybridScorer.computeHybridScore(strongMatch, profile, year);
        double sWeak = HybridScorer.computeHybridScore(weakMatch, profile, year);
        assertTrue("Taste-aligned prestige match must outscore weak match", sStrong > sWeak + 4.0);
    }

    @Test
    public void testMmrPenalizesRedundantSameSeedClones() throws Exception {
        List<JSONObject> pool = new ArrayList<>();
        pool.add(makeCand("Clone A1", "Marry My Husband", "rma,drm", "Prime Video", 12.0));
        pool.add(makeCand("Clone A2", "Marry My Husband", "rma,drm", "Prime Video", 11.9));
        pool.add(makeCand("Distinct SciFi", "Silo", "scf,trl", "Apple TV+", 11.4));

        List<JSONObject> ranked = HybridScorer.rankByMmr(pool, 3, 0.55);
        assertEquals("Clone A1", ranked.get(0).optString("title"));
        assertEquals("MMR must promote distinct seed/genre into slot #2",
            "Distinct SciFi", ranked.get(1).optString("title"));
    }

    private static JSONObject makeCand(String title, String seed, String genres,
                                       String app, double score) throws Exception {
        return new JSONObject()
            .put("title", title).put("seed_anchor", seed)
            .put("genre_codes", genres).put("app", app)
            .put("sota_score", score);
    }
}
