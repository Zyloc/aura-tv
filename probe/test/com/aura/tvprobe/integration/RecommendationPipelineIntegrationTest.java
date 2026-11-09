package com.aura.tvprobe;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

/**
 * End-to-End Integration Test of the multi-stage Aura Recommendation Engine:
 * UserTasteProfile -> CandidateValidator -> HybridScorer -> SlateAllocator (Epoch 1)
 * -> ImpressionTracker TTL Cooldown -> SlateAllocator (Epoch 2) -> DynamicRailClusterer.
 */
public class RecommendationPipelineIntegrationTest {

    @Test
    public void testFullPipelineBlocksAnimeCapsSingleSeedAndRecyclesUnactedEpochs() throws Exception {
        // 1. Build a realistic household profile (Sci-Fi, Crime, Sports, plus spouse's K-drama)
        List<String> anchors = Arrays.asList("Severance", "Formula 1: Drive to Survive", "Marry My Husband", "Dead-End Job");
        UserTasteProfile profile = new UserTasteProfile(anchors, "IN", "English Hindi", "prestige", 1);
        profile.recordSeedMetadata("US", "scf,trl", 2.4);
        profile.recordSeedMetadata("GB", "spt,doc", 2.0);
        profile.recordSeedMetadata("KR", "drm,rma", 1.2);
        assertFalse("Anime must be disabled when not in user preferences", profile.isAnimeEnabled());

        // 2. Build a mixed raw candidate catalog (including unwanted Anime, over-indexed K-dramas, Sports, Sci-Fi)
        List<JSONObject> rawCatalog = buildSyntheticMixedCatalog();
        int year = CandidateValidator.getCurrentYear();
        List<JSONObject> validated = filterAndScoreCandidates(rawCatalog, profile, year);

        for (JSONObject item : validated) {
            assertFalse("Zero anime titles may survive candidate validation",
                item.optString("genre_codes").contains("ani"));
        }

        // 3. Allocate Epoch 1 Slate (30 slots)
        Map<String, ImpressionTracker.ImpressionRecord> impressions = new HashMap<>();
        List<JSONObject> epoch1Slate = SlateAllocator.allocateBalancedSlate(
            new ArrayList<>(validated), 1, impressions);
        assertTrue("Epoch 1 slate must have >= 15 diverse items", epoch1Slate.size() >= 15);
        assertSeedCapRespected(epoch1Slate, "Marry My Husband", 2);
        assertSeedCapRespected(epoch1Slate, "Dead-End Job", 2);
        assertTrue("Slate must be sorted by slate_score descending so top items lead Hero and For You",
            epoch1Slate.get(0).optDouble("slate_score") >= epoch1Slate.get(epoch1Slate.size() - 1).optDouble("slate_score"));

        // 4. Cluster Epoch 1 into Home Rails and verify Sports & For You rails
        List<DynamicRailClusterer.RailGroup> rails1 = DynamicRailClusterer.buildRails(epoch1Slate);
        assertRailExists(rails1, "For You");
        assertRailExists(rails1, "Sports, Docuseries & True Stories");

        // 5. Simulate user ignoring the top 6 items of Epoch 1 -> advance to Epoch 2
        long nowSec = System.currentTimeMillis() / 1000L;
        Set<String> epoch1TopKeys = new HashSet<>();
        for (int i = 0; i < Math.min(6, epoch1Slate.size()); i++) {
            String key = AuraDb.normalizeTitleKey(epoch1Slate.get(i).optString("title"));
            epoch1TopKeys.add(key);
            impressions.put(key, new ImpressionTracker.ImpressionRecord(1, 1, nowSec));
        }

        List<JSONObject> epoch2Slate = SlateAllocator.allocateBalancedSlate(
            new ArrayList<>(validated), 2, impressions);
        String newTopKey = AuraDb.normalizeTitleKey(epoch2Slate.get(0).optString("title"));
        assertFalse("Epoch 2 top recommendation must rotate away from unacted Epoch 1 top picks",
            epoch1TopKeys.contains(newTopKey));

        // 6. Verify Epoch 2 rails maintain 'For You' section stability and valid continue-watching filtering
        List<DynamicRailClusterer.RailGroup> rails2 = DynamicRailClusterer.buildRails(epoch2Slate);
        assertRailExists(rails2, "For You");
        assertEquals("For You must remain the top content rail across epochs", "For You", rails2.get(0).title);
    }

    private static List<JSONObject> filterAndScoreCandidates(
            List<JSONObject> raw, UserTasteProfile profile, int currentYear) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : raw) {
            String gCodes = item.optString("genre_codes", "");
            if (CandidateValidator.isUnwantedAnimation(gCodes, profile.isAnimeEnabled())) continue;
            if (!CandidateValidator.isValidCoWatchCandidate(item, currentYear - 6, 6.5)) continue;
            double score = HybridScorer.computeHybridScore(item, profile, currentYear);
            item.put("sota_score", score);
            out.add(item);
        }
        return out;
    }

    private static List<JSONObject> buildSyntheticMixedCatalog() throws Exception {
        List<JSONObject> list = new ArrayList<>();
        int y = CandidateValidator.getCurrentYear() - 1;
        // Unwanted anime candidates (should be 100% filtered out)
        list.add(cand("Solo Leveling", "DanMachi", "ani,act,fnt", y, 8.6, 55000));
        list.add(cand("Jujutsu Kaisen", "DanMachi", "ani,act", y, 8.5, 95000));
        // Over-represented single-seed candidates (should be capped at 2)
        for (int i = 1; i <= 6; i++) {
            list.add(cand("Seoul Romance " + i, "Marry My Husband", "drm,rma", y, 8.2, 18000));
            list.add(cand("Office Mystery " + i, "Dead-End Job", "drm,trl", y, 8.1, 16000));
        }
        // Multi-seed Sci-Fi, Crime, Sports & Docuseries candidates
        for (int i = 1; i <= 8; i++) {
            list.add(cand("SciFi Odyssey " + i, "SciFiAnchor" + i, "scf,trl", y, 8.4, 42000));
            list.add(cand("Crime Files " + i, "CrimeAnchor" + i, "crm,act,trl", y, 8.3, 38000));
            list.add(cand("Pro Championship " + i, "SportsAnchor" + i, "spt,doc", y, 8.5, 14000));
        }
        return list;
    }

    private static JSONObject cand(String title, String seed, String genres,
                                   int year, double imdb, int votes) throws Exception {
        return new JSONObject()
            .put("title", title).put("seed_anchor", seed).put("genre_codes", genres)
            .put("year", year).put("imdb_raw", imdb).put("imdb_votes", votes)
            .put("tmdb_popularity", 35.0).put("has_flatrate_offer", true)
            .put("poster_url", "https://images.justwatch.com/poster/1/s718/p.jpg")
            .put("country", "US").put("pkg_code", "nfx").put("app", "Netflix");
    }

    private static void assertSeedCapRespected(List<JSONObject> slate, String seed, int maxAllowed) {
        int count = 0;
        for (JSONObject item : slate) {
            if (seed.equalsIgnoreCase(item.optString("seed_anchor"))) count++;
        }
        assertTrue("Seed '" + seed + "' exceeded cap (" + count + " > " + maxAllowed + ")", count <= maxAllowed);
    }

    private static void assertRailExists(List<DynamicRailClusterer.RailGroup> rails, String expectedTitle) {
        for (DynamicRailClusterer.RailGroup rg : rails) {
            if (expectedTitle.equals(rg.title) && !rg.items.isEmpty()) return;
        }
        fail("Expected non-empty rail '" + expectedTitle + "' in clustered home rails");
    }
}
