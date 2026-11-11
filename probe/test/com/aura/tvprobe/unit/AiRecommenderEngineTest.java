package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link AiRecommender}, {@link AgenticSearchOrchestrator}, and {@link AgentStatusNarrator}.
 */
public class AiRecommenderEngineTest {

    @Test
    public void testBuiltInSeedCatalogRegionalAdaptationAndWatchedFilter() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        db.setSetting("region_country", "US");
        db.markTitleWatched(new JSONObject().put("title", "Severance"), true);
        TestableAiRecommender ai = new TestableAiRecommender(db);
        JSONArray seeds = ai.getBuiltInSotaSeedCatalog("member1");
        assertTrue(seeds.length() >= 8);
        verifyNoWatchedOrIndianHotstarInUsSeeds(seeds);
    }

    private void verifyNoWatchedOrIndianHotstarInUsSeeds(JSONArray seeds) {
        for (int i = 0; i < seeds.length(); i++) {
            JSONObject s = seeds.optJSONObject(i);
            assertNotEquals("Severance", s.optString("title"));
            assertNotEquals("jhs", s.optString("provider_code"));
        }
    }

    @Test
    public void testGetOrRefreshForProfileUsesFreshCache() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        JSONArray cached = new JSONArray();
        for (int i = 0; i < 6; i++) cached.put(new JSONObject().put("title", "Show " + i).put("app", "Netflix"));
        db.saveProfileRecommendations("member1", cached);
        TestableAiRecommender ai = new TestableAiRecommender(db);
        JSONArray out = ai.getOrRefreshForProfile("member1", new JSONObject(), new JSONArray(), false);
        assertEquals(6, out.length());
        assertEquals(0, ai.llmCallCount);
    }

    @Test
    public void testPlanAgentSearchStrategyValidAndFallback() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        db.setSetting("gemini_api_key", "fake_key");
        TestableAiRecommender ai = new TestableAiRecommender(db);
        ai.enqueueLlmResponse("{\"queries\":[{\"searchQuery\":\"Dark\",\"includeSimilar\":true}],\"traversal_chips\":[\"Time Loop\"],\"taste_hypothesis\":\"Loves puzzle sci-fi\"}");
        JSONObject plan = ai.planAgentSearchStrategy("member1", "", Arrays.asList("Severance"), 2022, 2026);
        assertEquals(1, plan.getJSONArray("queries").length());
        assertEquals("Time Loop", db.getTraversalChips("member1").get(0));
        ai.enqueueLlmResponse("not valid json");
        JSONObject fallback = ai.planAgentSearchStrategy("member1", "thriller", Arrays.asList("Severance"), 2022, 2026);
        assertTrue(fallback.getJSONArray("primary_queries").length() >= 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFilterAndRerankPoolWithLlmAndBackfill() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        db.setSetting("gemini_api_key", "fake_key");
        TestableAiRecommender ai = new TestableAiRecommender(db);
        List<JSONObject> pool = buildSamplePool(12);
        ai.enqueueLlmResponse(buildLlmRankingResponseJson(8));
        Method m = AiRecommender.class.getDeclaredMethod("filterAndRerankPoolWithLlm", String.class, List.class, int.class);
        m.setAccessible(true);
        List<JSONObject> reranked = (List<JSONObject>) m.invoke(ai, "member1", pool, 1);
        assertTrue(reranked.size() >= 8);
        assertEquals("Candidate 2", reranked.get(0).optString("title"));
    }

    private String buildLlmRankingResponseJson(int count) throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(new JSONObject().put("title", "Candidate 2").put("reason", "Matches your dark sci-fi streak"));
        for (int i = 0; i < count; i++) {
            if (i != 2) arr.put(new JSONObject().put("title", "Candidate " + i).put("reason", "Strong thriller fit"));
        }
        return new JSONObject().put("results", arr).toString();
    }

    private List<JSONObject> buildSamplePool(int count) throws Exception {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new JSONObject().put("title", "Candidate " + i).put("app", "Netflix").put("pkg_code", "nfx")
                .put("year", 2024).put("type", "Series").put("genres", "Sci-Fi").put("genre_codes", "scf")
                .put("imdb_raw", 8.1).put("imdb_score", 8.1).put("imdb_votes", 30000).put("sota_score", 8.5));
        }
        return list;
    }

    @Test
    public void testEnrichAllRichWatchedContextAndPrimeScraper() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        seedWatchedAndHistoryForContext(db);
        TestableAiRecommender ai = new TestableAiRecommender(db);
        JSONArray items = new JSONArray().put(new JSONObject().put("title", "Silo").put("reason", "Dystopian mystery"));
        JSONArray enriched = ai.enrichAll(items);
        assertEquals(1, enriched.length());
        assertEquals("Netflix", enriched.getJSONObject(0).optString("app"));
        assertTrue(ai.buildRichWatchedContext("member1").length() >= 1);
        verifyPrimeHtmlScraper(ai);
    }

    private void seedWatchedAndHistoryForContext(InMemoryAuraDb db) throws Exception {
        db.upsertWatchItem(new JSONObject().put("media_key", "nf:dark").put("profile_id", "member1")
            .put("title", "Dark").put("app_pkg", "com.netflix.ninja").put("position_sec", 1500).put("duration_sec", 3000));
        db.rateTitle(new JSONObject().put("title", "Andor").put("genres", "Sci-Fi"), 9);
    }

    private void verifyPrimeHtmlScraper(TestableAiRecommender ai) throws Exception {
        Method htmlM = AiRecommender.class.getDeclaredMethod("scrapePrimeGtiHtml", String.class, String.class);
        htmlM.setAccessible(true);
        String html = "\"parentTitle\":\"Reacher\",\"gti1\":{\"title\":\"Welcome to Margrave\",\"synopsis\":\"Jack arrives.\"}";
        JSONObject p = (JSONObject) htmlM.invoke(ai, "gti1", html);
        assertEquals("Reacher", p.optString("series_title"));
        assertEquals("Welcome to Margrave", p.optString("title"));
        assertFalse(AiRecommender.getEpochExplorationAngle(3).isEmpty());
    }

    @Test
    public void testSimilarityHelpersAndOrchestratorDeMerge() throws Exception {
        assertEquals("Severance", AiRecommender.extractSimilaritySeed("shows like Severance"));
        assertEquals("", AiRecommender.extractSimilaritySeed("90s heist movies with twists and comedy"));
        assertTrue(AiRecommender.isGenericNextOrTasteQuery("what's next"));
        assertFalse(AiRecommender.isGenericNextOrTasteQuery("cyberpunk anime movies"));
        InMemoryAuraDb db = new InMemoryAuraDb();
        TestableAiRecommender ai = new TestableAiRecommender(db);
        assertEquals("Severance", ai.buildFastSeedPlan("member1", "similar to Severance").getJSONArray("primary_queries").getString(0));
        verifyOrchestratorDeMergeAndBalance(db);
    }

    private void verifyOrchestratorDeMergeAndBalance(InMemoryAuraDb db) throws Exception {
        JSONObject plan = buildRegionalPlanForBalanceTest();
        AgenticSearchOrchestrator.enforceGlobalRegionalBalance("thrillers", plan);
        assertFalse(plan.getJSONArray("queries").getJSONObject(1).has("productionCountries"));
        Set<String> cd = new HashSet<>(Arrays.asList("silo", "dark"));
        AgenticSearchOrchestrator.exemptTopExactSearchHits("Silo", plan, cd);
        assertFalse(cd.contains("silo"));
        assertEquals(3, AgenticSearchOrchestrator.deMergeCandidates(
            db, "member1", "sci-fi", Collections.emptyList(), plan, 2020, buildSamplePool(3), false).size());
    }

    private JSONObject buildRegionalPlanForBalanceTest() throws Exception {
        JSONArray queries = new JSONArray()
            .put(new JSONObject().put("productionCountries", new JSONArray().put("KR")))
            .put(new JSONObject().put("productionCountries", new JSONArray().put("JP")));
        return new JSONObject().put("queries", queries).put("primary_queries", new JSONArray().put("Silo"));
    }

    @Test
    public void testAgentStatusNarratorLifecycle() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        TestableAiRecommender ai = new TestableAiRecommender(db);
        List<String> emitted = Collections.synchronizedList(new ArrayList<>());
        AgentStatusNarrator narrator = AgentStatusNarrator.start(ai, db, "member1", "sci-fi", emitted::add);
        AgentStatusNarrator.publishPlanSteps(new JSONObject().put("narrator_steps", new JSONArray().put("JustWatch GraphQL lookup")));
        AgentStatusNarrator.publishLiveStep("Ranking Bayesian candidates...");
        narrator.appendSteps(new JSONArray().put("Finalizing picks"));
        assertEquals("Finalizing picks\u2026", AgentStatusNarrator.sanitizeStep("Finalizing AI picks..."));
        narrator.stop();
    }
}
