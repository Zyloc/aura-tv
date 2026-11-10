package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

/**
 * Unit & Integration tests for Multi-Device / Multi-Region compatibility,
 * Multi-Profile Impression Isolation, AgenticSearchOrchestrator, and Evolving Refresh Lenses.
 */
public class MultiDeviceProfileAndOrchestratorTest {

    @Test
    public void testRegionProviderRegistryDynamicGlobalResolution() {
        List<String> usProviders = RegionProviderRegistry.getProvidersForCountry("US");
        assertTrue(usProviders.contains("nfx") && usProviders.contains("mxx") && usProviders.contains("hlu"));

        List<String> gbProviders = RegionProviderRegistry.getProvidersForCountry("GB");
        assertTrue(gbProviders.contains("bbc") && gbProviders.contains("itv") && gbProviders.contains("dnp"));

        List<String> jpProviders = RegionProviderRegistry.getProvidersForCountry("JP");
        assertTrue(jpProviders.contains("unx") && jpProviders.contains("abm") && jpProviders.contains("cru"));

        List<String> inProviders = RegionProviderRegistry.getProvidersForCountry("IN");
        assertTrue(inProviders.contains("jhs") && inProviders.contains("snl") && inProviders.contains("zee"));

        assertEquals("com.disney.disneyplus", RegionProviderRegistry.resolveAndroidPackage("Disney+"));
        assertEquals("com.wbd.stream", RegionProviderRegistry.resolveAndroidPackage("Max"));
        assertEquals("com.hulu.livingroomplus", RegionProviderRegistry.resolveAndroidPackage("Hulu"));
        assertEquals("com.peacocktv.peacockandroid", RegionProviderRegistry.resolveAndroidPackage("Peacock"));
        assertEquals("com.cbs.ott", RegionProviderRegistry.resolveAndroidPackage("Paramount+"));
        assertEquals("bbc.iplayer.android", RegionProviderRegistry.resolveAndroidPackage("BBC iPlayer"));
        assertEquals("com.crunchyroll.crunchyroid", RegionProviderRegistry.resolveAndroidPackage("Crunchyroll"));
    }

    @Test
    public void testRomanizedSlugHumanizationForNonEnglishTitles() {
        String resolved = RegionProviderRegistry.resolveTitleFromSlugIfRomanized(
            "Geimu", "Geimu", "/in/movie/game-over", "IN", "en");
        assertEquals("Game Over", resolved);

        String keepEnglish = RegionProviderRegistry.resolveTitleFromSlugIfRomanized(
            "The Matrix", "The Matrix", "/us/movie/the-matrix", "US", "en");
        assertEquals("The Matrix", keepEnglish);
    }

    @Test
    public void testMultiProfileImpressionIsolationAndEightRoundRecycle() {
        Map<String, ImpressionTracker.ImpressionRecord> impressions = new HashMap<>();
        impressions.put("severance", new ImpressionTracker.ImpressionRecord(50, 2, System.currentTimeMillis() / 1000L));

        // Profile A at epoch 52 (within 8-round window) -> recently shown
        assertTrue(ImpressionTracker.isRecentlyShown("severance", 52, impressions));
        assertTrue(ImpressionTracker.computeNoveltyPenalty("severance", 52, impressions) < -3.0);

        // Profile A at epoch 58 (8 rounds later) -> recycled & eligible for deletion
        assertFalse(ImpressionTracker.isRecentlyShown("severance", 58, impressions));
        assertTrue(ImpressionTracker.shouldDeleteImpression(50, 58));

        // Profile B at epoch 2 on the same TV -> must NOT be locked out by Profile A's epoch 50!
        assertFalse("Higher-epoch profile must never lock out lower-epoch profile",
            ImpressionTracker.isRecentlyShown("severance", 2, impressions));
        assertEquals(0.0, ImpressionTracker.computeNoveltyPenalty("severance", 2, impressions), 0.001);
    }

    @Test
    public void testOrchestratorGlobalRegionalBalanceAndExactHitCooldownExemption() throws Exception {
        JSONObject plan = new JSONObject()
            .put("primary_queries", new JSONArray().put("Knives Out").put("Glass Onion").put("The Menu"))
            .put("queries", new JSONArray()
                .put(new JSONObject().put("genres", new JSONArray().put("cmy")).put("productionCountries", new JSONArray().put("IN")))
                .put(new JSONObject().put("genres", new JSONArray().put("crm")).put("productionCountries", new JSONArray().put("IN")))
                .put(new JSONObject().put("genres", new JSONArray().put("trl")).put("productionCountries", new JSONArray().put("IN"))));

        // Generic query without regional intent -> at most 1 spec keeps productionCountries
        AgenticSearchOrchestrator.enforceGlobalRegionalBalance("whodunit mystery comedy movie", plan);
        JSONArray specs = plan.getJSONArray("queries");
        assertNotNull(specs.getJSONObject(0).optJSONArray("productionCountries"));
        assertNull("2nd spec must have regional country cap stripped for global diversity",
            specs.getJSONObject(1).optJSONArray("productionCountries"));
        assertNull("3rd spec must have regional country cap stripped for global diversity",
            specs.getJSONObject(2).optJSONArray("productionCountries"));

        // Exact primary hits must be exempted from cooldown on specific search queries
        Set<String> cooldown = new HashSet<>(Arrays.asList("knivesout", "glassonion", "otherfilm"));
        AgenticSearchOrchestrator.exemptTopExactSearchHits("whodunit mystery comedy movie", plan, cooldown);
        assertFalse(cooldown.contains("knivesout"));
        assertFalse(cooldown.contains("glassonion"));
        assertTrue(cooldown.contains("otherfilm"));
    }

    @Test
    public void testEvolvingRefreshLensesAndFollowUpGuard() {
        Set<String> distinctLenses = new HashSet<>();
        for (int epoch = 1; epoch <= 8; epoch++) {
            String lens = AiRecommender.getEpochExplorationAngle(epoch);
            assertNotNull(lens);
            assertTrue(lens.length() > 30);
            distinctLenses.add(lens);
        }
        assertEquals("All 8 hourly/manual refresh epochs must have distinct exploration lenses", 8, distinctLenses.size());

        // Long nested queries containing "only" or "shorter" must never be misclassified as follow-ups
        assertFalse(VoiceConcierge.isFollowUpRefinement(
            "Find me a mind-bending sci-fi thriller movie under 2 hours with a 90s vibe"));
        assertTrue(VoiceConcierge.isFollowUpRefinement("under 2 hours"));
        assertTrue(VoiceConcierge.isFollowUpRefinement("only on Netflix"));
    }
}
