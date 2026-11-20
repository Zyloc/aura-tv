package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * End-to-End Integration Test for Aura TV's 6 Taste Intelligence Upgrades:
 * 1. Engagement Weighting (Binge vs Bounce)
 * 2. One-Click "Not Interested" & Skip Learning
 * 3. Household Taste Separation (Live/Trending + Regional + Thrillers)
 * 4. Structured Persistent Taste DNA
 * 5. Time-of-Day & 1-Tap Mood Re-Ranking (<1ms)
 * 6. Instant 0-Second Reserve Slate Refresh Swap.
 */
public class TasteIntelligenceIntegrationTest {

    @Test
    public void testEngagementWeightingTasteDnaAndNotInterestedPenalty() throws Exception {
        InMemoryAuraDb db = buildEngagementSeedDb();
        JSONObject dna = TasteIntelligenceEngine.buildAndPersistTasteDna(db, "member1");
        assertTrue(dna.optJSONArray("loved_anchors").toString().contains("MobLand"));
        assertTrue(dna.optJSONArray("bounced_anchors").toString().contains("Boring Soap"));
        verifyNotInterestedAndSkipPenalties(db);
    }

    private InMemoryAuraDb buildEngagementSeedDb() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        long now = System.currentTimeMillis() / 1000L;
        db.upsertWatchItem(new JSONObject().put("media_key", "hs:mobland").put("series_title", "MobLand")
            .put("title", "Ep 4").put("episode", 4).put("position_sec", 2200).put("duration_sec", 3000).put("updated_at", now - 60));
        db.upsertWatchItem(new JSONObject().put("media_key", "zee:soap").put("title", "Boring Soap")
            .put("position_sec", 180).put("duration_sec", 3600).put("updated_at", now - (10L * 86400L)));
        return db;
    }

    private void verifyNotInterestedAndSkipPenalties(InMemoryAuraDb db) throws Exception {
        JSONObject dismissed = new JSONObject().put("title", "Cheesy Romcom").put("genre_codes", "rma,cmy").put("seed_anchor", "SoapSeed");
        TasteIntelligenceEngine.recordNotInterested(db, "member1", dismissed);
        assertTrue(TasteIntelligenceEngine.getDismissedTitleKeys(db, "member1").contains("cheesyromcom"));
        assertTrue("Dismissed seed/genre + 5x skips must produce strong negative penalty", computePeerSkipPenalty(db) <= -3.5);
        TasteIntelligenceEngine.clearDismissedItems(db, "member1");
        assertTrue("clearDismissedItems must wipe hidden titles", TasteIntelligenceEngine.getDismissedTitleKeys(db, "member1").isEmpty());
        verifyCompanionVoiceAccentAndSpeedPreferences();
    }

    private double computePeerSkipPenalty(InMemoryAuraDb db) throws Exception {
        Map<String, ImpressionTracker.ImpressionRecord> imp = new HashMap<>();
        imp.put("similarromcom", new ImpressionTracker.ImpressionRecord(1, 5, System.currentTimeMillis() / 1000L));
        JSONObject peer = new JSONObject().put("title", "Similar Romcom").put("genre_codes", "rma").put("seed_anchor", "SoapSeed");
        return TasteIntelligenceEngine.computeNegativeAndSkipPenalty(peer,
            TasteIntelligenceEngine.buildNegativeGenrePenalties(db, "member1"),
            TasteIntelligenceEngine.buildNegativeSeedSet(db, "member1"), imp);
    }

    private void verifyCompanionVoiceAccentAndSpeedPreferences() {
        AuraTtsNarrator.applyVoicePreferences(null, "IN", 1.15f);
        assertEquals("IN", AuraTtsNarrator.getPreferredAccent());
        assertEquals(1.15f, AuraTtsNarrator.getSpeechRate(), 0.001f);
        assertTrue(AuraTtsNarrator.scoreVoiceAttributes("en", "IN", "en-in-x-enc-network", 400, true)
            > AuraTtsNarrator.scoreVoiceAttributes("en", "US", "en-us-x-iog-network", 400, true));
        AuraTtsNarrator.applyVoicePreferences(null, "HI", 0.90f);
        assertTrue(AuraTtsNarrator.scoreVoiceAttributes("hi", "IN", "hi-in-x-hia-network", 400, true) > 0);
        assertEquals(-1, AuraTtsNarrator.scoreVoiceAttributes("en", "US", "en-us-x-iog-network", 400, true));
        AuraTtsNarrator.applyVoicePreferences(null, "US", 0.97f);
    }

    @Test
    public void testHouseholdRailSeparationAndInstantMoodContextReranking() throws Exception {
        List<JSONObject> mixed = buildHouseholdMixedSlate();
        List<DynamicRailClusterer.RailGroup> rails = DynamicRailClusterer.buildRails(mixed);
        assertRailPresent(rails, "Live & Trending Across Your Apps");
        assertRailPresent(rails, RegionProviderRegistry.getRegionDisplayName(null) + " & Regional Picks");
        verifyMoodAndTimeOfDayReranking(mixed);
    }

    private List<JSONObject> buildHouseholdMixedSlate() throws Exception {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 1; i <= 8; i++) list.add(makeItem("ForYou " + i, "Seed" + i, "US", "drm", "", 50, 10.0 - i * 0.1));
        list.add(makeItem("India vs Pakistan", "", "IN", "spt", "app_trending", 45, 9.2));
        list.add(makeItem("Doctor John", "", "KR", "drm,trl", "app_trending", 50, 9.0));
        String activeCountry = RegionProviderRegistry.getCountryCode(null);
        list.add(makeItem("Regional Crime", "", activeCountry, "crm,drm", "co_watch", 55, 8.8));
        list.add(makeItem("Regional Comedy", "", activeCountry, "cmy,drm", "co_watch", 35, 8.7));
        list.add(makeItem("Dark Matter", "", "US", "scf,trl", "co_watch", 52, 8.6));
        list.add(makeItem("Long Epic", "", "US", "drm", "co_watch", 170, 8.9));
        return list;
    }

    private void verifyMoodAndTimeOfDayReranking(List<JSONObject> mixed) {
        assertEquals(TasteIntelligenceEngine.CTX_LATE_NIGHT, TasteIntelligenceEngine.detectTimeContext(23, Calendar.WEDNESDAY));
        assertEquals(TasteIntelligenceEngine.CTX_WEEKEND_BINGE, TasteIntelligenceEngine.detectTimeContext(20, Calendar.SATURDAY));
        List<JSONObject> mind = TasteIntelligenceEngine.rerankForMoodAndContext(mixed, TasteIntelligenceEngine.MOOD_MIND_BENDING, 15, Calendar.TUESDAY);
        assertEquals("Dark Matter", mind.get(0).optString("title"));
        List<JSONObject> light = TasteIntelligenceEngine.rerankForMoodAndContext(mixed, TasteIntelligenceEngine.MOOD_LIGHT_SMART, 15, Calendar.TUESDAY);
        assertEquals("Regional Comedy", light.get(0).optString("title"));
        verifyRedesignedDetailUnifiedMeta();
    }

    private void verifyRedesignedDetailUnifiedMeta() {
        try {
            JSONObject item = new JSONObject().put("app", "Prime Video").put("imdb_raw", 8.1)
                .put("year", 2026).put("runtime_min", 62).put("genres", "Action, Comedy, Crime");
            String meta = DetailOverlay.buildUnifiedCinemaMeta(item);
            assertEquals("PRIME VIDEO  \u00B7  \u2605 8.1  \u00B7  2026  \u00B7  62m  \u00B7  Action, Comedy", meta);
        } catch (Exception e) { fail(e.getMessage()); }
    }

    @Test
    public void testInstantZeroSecondReserveSlateSwapOnForceRefresh() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        TestableAiRecommender ai = new TestableAiRecommender(db);
        seedTwelveItemReserveSlate(db);
        long t0 = System.currentTimeMillis();
        JSONArray swapped = ai.getOrRefreshForProfile("member1", null, new JSONArray(), true, null);
        assertTrue("Instant reserve swap must finish under 200ms", (System.currentTimeMillis() - t0) < 200L);
        assertEquals("Reserve Pick 1", swapped.getJSONObject(0).optString("title"));
        assertFalse("Reserve must be consumed after swap", ReserveSlateManager.hasReadyReserve(db, "member1"));
    }

    private void seedTwelveItemReserveSlate(InMemoryAuraDb db) throws Exception {
        JSONArray reserve = new JSONArray();
        for (int i = 1; i <= 12; i++) reserve.put(makeItem("Reserve Pick " + i, "S" + i, "US", "scf,trl", "co_watch", 48, 9.0));
        ReserveSlateManager.saveReserveSlate(db, "member1", reserve);
        assertTrue(ReserveSlateManager.hasReadyReserve(db, "member1"));
    }

    private JSONObject makeItem(String title, String seed, String country, String genres, String src, int rt, double score) throws Exception {
        return new JSONObject().put("title", title).put("seed_anchor", seed).put("country", country)
            .put("genre_codes", genres).put("source_channel", src).put("runtime_min", rt)
            .put("slate_score", score).put("imdb_raw", 8.2).put("imdb_votes", 25000);
    }

    private void assertRailPresent(List<DynamicRailClusterer.RailGroup> rails, String expectedTitle) {
        for (DynamicRailClusterer.RailGroup r : rails) {
            if (expectedTitle.equals(r.title)) return;
        }
        fail("Expected rail not found: " + expectedTitle);
    }
}
