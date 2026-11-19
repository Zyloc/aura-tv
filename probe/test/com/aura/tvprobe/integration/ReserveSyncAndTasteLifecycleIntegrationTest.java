package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import static org.junit.Assert.*;

/**
 * End-to-End Integration Tests for Reserve Slate pruning/popping, Phone Remote Sync
 * into Taste DNA, and Mood/Time-of-Day contextual ranking across all modes.
 */
public class ReserveSyncAndTasteLifecycleIntegrationTest {

    @Test
    public void testReserveSlatePruningInvalidationAndDirectPop() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        seedReserveOfSize(db, 11);
        assertTrue(ReserveSlateManager.hasReadyReserve(db, "member1"));
        db.markTitleWatched(new JSONObject().put("title", "Reserve 1"), true);
        ReserveSlateManager.pruneTitleFromReserve(db, "member1");
        assertTrue("10 clean items still satisfy min reserve size", ReserveSlateManager.hasReadyReserve(db, "member1"));
        TasteIntelligenceEngine.recordNotInterested(db, "member1", new JSONObject().put("title", "Reserve 2"));
        ReserveSlateManager.pruneTitleFromReserve(db, "member1");
        assertFalse("Dropping below 10 must invalidate stale reserve", ReserveSlateManager.hasReadyReserve(db, "member1"));
        verifyDirectPopConsumesAndSavesReserve(db);
    }

    private void verifyDirectPopConsumesAndSavesReserve(InMemoryAuraDb db) throws Exception {
        seedReserveOfSize(db, 12);
        JSONArray popped = ReserveSlateManager.tryPopFreshReserveSlate(db, "member1");
        assertNotNull(popped);
        assertEquals(10, popped.length());
        assertFalse(ReserveSlateManager.hasReadyReserve(db, "member1"));
        assertEquals(10, db.getProfileRecommendations("member1").length());
    }

    private void seedReserveOfSize(InMemoryAuraDb db, int count) throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = 1; i <= count; i++) arr.put(new JSONObject().put("title", "Reserve " + i).put("app", "Netflix"));
        ReserveSlateManager.saveReserveSlate(db, "member1", arr);
    }

    @Test
    public void testRemoteSyncFlowsIntoTasteDnaAndDismissalFiltering() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        SyncManager sync = new SyncManager(db);
        sync.ingestRemoteSync(buildRemoteSyncPayload(), "member1");
        String dnaSummary = TasteIntelligenceEngine.getTasteDnaPromptSummary(db, "member1");
        assertTrue("Synced binged show must appear in Taste DNA", dnaSummary.contains("Shogun"));
        TasteIntelligenceEngine.recordNotInterested(db, "member1", new JSONObject().put("title", "Skip Me"));
        JSONArray cleaned = TasteIntelligenceEngine.filterOutDismissed(db, "member1",
            new JSONArray().put(new JSONObject().put("title", "Skip Me")).put(new JSONObject().put("title", "Keep Me")));
        assertEquals(1, cleaned.length());
        assertEquals("Keep Me", cleaned.getJSONObject(0).optString("title"));
    }

    private JSONObject buildRemoteSyncPayload() throws Exception {
        JSONArray hist = new JSONArray().put(new JSONObject().put("media_key", "hs:shogun")
            .put("series_title", "Shogun").put("title", "Ep 5").put("episode", 5)
            .put("position_sec", 2500).put("duration_sec", 3600));
        JSONArray watched = new JSONArray().put(new JSONObject().put("title", "Dark").put("user_rating", 10));
        return new JSONObject().put("history", hist).put("watched_items", watched);
    }

    @Test
    public void testAllMoodsAndLateNightContextReranking() throws Exception {
        List<JSONObject> items = new ArrayList<>();
        items.add(new JSONObject().put("title", "Action Rush").put("genre_codes", "act,crm").put("runtime_min", 145).put("slate_score", 8.5));
        items.add(new JSONObject().put("title", "Crisp 40m Ep").put("genre_codes", "drm").put("runtime_min", 40).put("type", "Series").put("slate_score", 8.4));
        items.add(new JSONObject().put("title", "Tight 95m Film").put("genre_codes", "drm").put("runtime_min", 95).put("type", "Movie").put("slate_score", 8.2));
        List<JSONObject> edge = TasteIntelligenceEngine.rerankForMoodAndContext(items, TasteIntelligenceEngine.MOOD_EDGE_OF_SEAT, 14, Calendar.TUESDAY);
        assertEquals("Action Rush", edge.get(0).optString("title"));
        List<JSONObject> shortRun = TasteIntelligenceEngine.rerankForMoodAndContext(items, TasteIntelligenceEngine.MOOD_UNDER_2H, 14, Calendar.TUESDAY);
        assertEquals("Crisp 40m Ep", shortRun.get(0).optString("title"));
        List<JSONObject> lateNight = TasteIntelligenceEngine.rerankForMoodAndContext(items, TasteIntelligenceEngine.MOOD_ALL, 23, Calendar.WEDNESDAY);
        assertEquals("Crisp 40m Ep", lateNight.get(0).optString("title"));
    }
}
