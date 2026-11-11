package com.aura.tvprobe;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

/**
 * Deep unit tests for {@link SessionInspector} provider ingestion & session resolution,
 * {@link AiRecommender} candidate gates & Gemini protocol helpers, {@link ImageLoader}
 * URL tiers, {@link TvChannelPublisher}, and {@link VoiceConcierge} local turns.
 */
public class DeepPipelineAndSessionUnitTest {

    @Test
    public void testSessionInspectorWatchNextAndNetflixIngestion() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        SessionInspector insp = new SessionInspector(new RecordingContext(), null, db, new TestableAiRecommender(db));
        invokeIngestRows(insp, buildWatchNextRows());
        assertEquals(1, db.listHistory("member1").length());
        invokeIngestNetflix(insp, buildNetflixPreviewRows());
        assertEquals(2, db.listHistory("member1").length());
    }

    private JSONArray buildWatchNextRows() throws Exception {
        long nowMs = System.currentTimeMillis();
        JSONObject valid = new JSONObject().put("package_name", "com.disney.disneyplus")
            .put("title", "Andor").put("episode_title", "Rix Road").put("season_display_number", 1)
            .put("episode_display_number", 12).put("last_playback_position_millis", 900_000L)
            .put("duration_millis", 3000_000L).put("last_engagement_time_utc_millis", nowMs - 3600_000L);
        JSONObject completed = new JSONObject().put("package_name", "com.disney.disneyplus")
            .put("title", "Finished Movie").put("last_playback_position_millis", 2950_000L)
            .put("duration_millis", 3000_000L).put("last_engagement_time_utc_millis", nowMs);
        return new JSONArray().put(valid).put(completed);
    }

    private JSONArray buildNetflixPreviewRows() throws Exception {
        long expSec = (System.currentTimeMillis() / 1000L) + 84000L;
        String uri = "http://www.netflix.com/watch/81?source=continueWatching&expireAt%3D"
            + expSec + "%26tileIndex%253D2%2526x";
        return new JSONArray().put(new JSONObject().put("package_name", "com.netflix.ninja")
            .put("title", "Bodies").put("intent_uri", uri));
    }

    private void invokeIngestRows(SessionInspector insp, JSONArray rows) throws Exception {
        Method m = SessionInspector.class.getDeclaredMethod("ingestRows", JSONArray.class, Map.class, String.class);
        m.setAccessible(true);
        m.invoke(insp, rows, Collections.singletonMap(1L, "Continue"), "Watch Next");
    }

    private void invokeIngestNetflix(SessionInspector insp, JSONArray rows) throws Exception {
        Method m = SessionInspector.class.getDeclaredMethod("ingestNetflixContinueWatching", JSONArray.class);
        m.setAccessible(true);
        m.invoke(insp, rows);
    }

    @Test
    public void testSessionInspectorPrimeOrphanAndHistorySessionResolution() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        seedPrimeAndGuideCaches(db);
        SessionInspector insp = new SessionInspector(new RecordingContext(), null, db, new TestableAiRecommender(db));
        JSONObject primeSess = new JSONObject().put("package", "com.amazon.amazonvideo.livingroom").put("state", 3)
            .put("position_ms", 600_000L).put("metadata", new JSONObject().put("android.media.metadata.MEDIA_ID", "amzn1.dv.gti.123"));
        insp.persistSerializedSession(primeSess);
        assertTrue(db.listHistory("member1").toString().contains("Fallout"));
        verifyOrphanAndCursorCollection(db, insp);
    }

    private void seedPrimeAndGuideCaches(InMemoryAuraDb db) throws Exception {
        db.saveCachedGti("amzn1.dv.gti.123", new JSONObject().put("series_title", "Fallout").put("title", "The End").toString());
        db.upsertWatchItem(new JSONObject().put("media_key", "apple:sev").put("profile_id", "member1")
            .put("app_pkg", "com.apple.atve.androidtv.appletv").put("series_title", "Severance").put("title", "Pilot").put("position_sec", 800));
        JSONArray eps = new JSONArray().put(new JSONObject().put("s", 1).put("e", 2).put("name", "Half Loop"));
        db.saveCachedGti("tvmaze_guide_v2:severance", new JSONObject().put("episodes", eps).toString());
    }

    private void verifyOrphanAndCursorCollection(InMemoryAuraDb db, SessionInspector insp) throws Exception {
        JSONObject meta = new JSONObject().put("android.media.metadata.TITLE", "S1:E2 Half Loop");
        insp.persistSerializedSession(new JSONObject().put("package", "com.apple.atve.androidtv.appletv")
            .put("state", 2).put("position_ms", 500_000L).put("metadata", meta));
        assertEquals(1, collectPreviewCursorRows(insp).length());
    }

    private JSONArray collectPreviewCursorRows(SessionInspector insp) throws Exception {
        MatrixCursor mc = new MatrixCursor(new String[]{"package_name", "title", "intent_uri", "weight"});
        mc.addRow(new Object[]{"com.netflix.ninja", "Dark", "nflx://watch/1", 10L});
        Method collect = SessionInspector.class.getDeclaredMethod(
            "collectCursorRows", Cursor.class, JSONArray.class, Set.class, boolean.class, boolean.class);
        collect.setAccessible(true);
        JSONArray rows = new JSONArray();
        collect.invoke(insp, mc, rows, new HashSet<String>(), true, true);
        return rows;
    }

    @Test
    public void testAiRecommenderCandidateGatesSimilarFallbackAndGeminiParsers() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        TestableAiRecommender ai = new TestableAiRecommender(db);
        db.rateTitle(new JSONObject().put("title", "Dark").put("genre_codes", "scf,trl"), 9);
        UserTasteProfile taste = new UserTasteProfile(db, "member1", 1);
        verifyAddSotaCandidateChannels(ai, taste);
        assertTrue(ai.traverseSimilarToWatched("member1", "UnknownOfflineSeedXyz", s -> {}).length() >= 8);
        verifyGeminiProtocolHelpers(ai, db);
    }

    private void verifyAddSotaCandidateChannels(TestableAiRecommender ai, UserTasteProfile taste) throws Exception {
        Method addM = AiRecommender.class.getDeclaredMethod(
            "addSotaCandidate", JSONObject.class, String.class, double.class, UserTasteProfile.class, Set.class, List.class);
        addM.setAccessible(true);
        Set<String> seen = new HashSet<>();
        List<JSONObject> pool = new ArrayList<>();
        JSONObject c1 = buildValidCandidate("Bouncer", 2024, "scf,trl", "");
        JSONObject c2 = buildValidCandidate("Planned Hit", 2024, "drm,trl", "llm_planned");
        assertTrue((Boolean) addM.invoke(ai, c1, "Dark", 1.0, taste, seen, pool));
        assertTrue((Boolean) addM.invoke(ai, c2, null, 0.9, taste, seen, pool));
        assertFalse((Boolean) addM.invoke(ai, c1, "Dark", 1.0, taste, seen, pool));
    }

    private JSONObject buildValidCandidate(String title, int year, String genres, String src) throws Exception {
        return new JSONObject().put("title", title).put("year", year).put("has_flatrate_offer", true)
            .put("poster_url", "https://img/1.jpg").put("imdb_raw", 8.0).put("imdb_votes", 25000)
            .put("tmdb_popularity", 40.0).put("genre_codes", genres).put("source_channel", src);
    }

    private void verifyGeminiProtocolHelpers(TestableAiRecommender ai, InMemoryAuraDb db) throws Exception {
        db.saveCachedGti("gti_cached", "{\"series_title\":\"Reacher\"}");
        assertEquals("Reacher", ai.resolvePrimeGti("gti_cached").optString("series_title"));
        Method reqM = AiRecommender.class.getDeclaredMethod("buildGeminiRequestBody", String.class, String.class);
        reqM.setAccessible(true);
        assertTrue(reqM.invoke(ai, "Hi", "Ymlu").toString().contains("inlineData"));
        verifyFenceAndCandidateTextExtraction();
    }

    private void verifyFenceAndCandidateTextExtraction() throws Exception {
        Method extM = AiRecommender.class.getDeclaredMethod("extractGeminiCandidateText", String.class);
        extM.setAccessible(true);
        Method stripM = AiRecommender.class.getDeclaredMethod("stripJsonFences", String.class);
        stripM.setAccessible(true);
        String raw = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"```json\\n{\\\"a\\\":1}\\n```\"}]}}]}";
        assertEquals("{\"a\":1}", stripM.invoke(null, extM.invoke(null, raw)));
    }

    @Test
    public void testImageLoaderQualityTiersAndTvChannelPublisherBuilders() throws Exception {
        ImageLoader.setQualityMode("saver");
        assertEquals("saver", ImageLoader.getQualityMode());
        assertTrue(ImageLoader.upgradePosterUrl("https://img/poster/1/s718/a.jpg").contains("/s332/"));
        assertTrue(ImageLoader.upgradePosterUrl("https://img/backdrop/1/s1920/a.jpg").contains("/s640/"));
        ImageLoader.setQualityMode("ultra");
        assertTrue(ImageLoader.upgradePosterUrl("https://img/poster/1/s166/a.jpg").contains("/s718/"));
        assertTrue(ImageLoader.downgradeFallbackUrl("https://img/backdrop/1/s1920/a.jpg").contains("/s1440/"));
        assertTrue(ImageLoader.downgradeFallbackUrl("https://img/poster/1/s718/a.jpg").contains("/s592/"));
        ImageLoader.trimMemory();
        verifyTvChannelPublisherValues();
    }

    private void verifyTvChannelPublisherValues() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        TvChannelPublisher pub = new TvChannelPublisher(new RecordingContext(), db);
        assertEquals(-1L, pub.ensureOrCreateChannel());
        Method chM = TvChannelPublisher.class.getDeclaredMethod("buildChannelValues");
        chM.setAccessible(true);
        assertNotNull((ContentValues) chM.invoke(pub));
        Method progM = TvChannelPublisher.class.getDeclaredMethod("buildProgramValues", long.class, JSONObject.class, int.class);
        progM.setAccessible(true);
        assertNotNull((ContentValues) progM.invoke(pub, 5L, new JSONObject().put("title", "Silo").put("poster_url", "https://p"), 0));
    }

    @Test
    public void testVoiceConciergeLocalTurnAndFollowUpRefinement() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        db.setSetting("gemini_api_key", "fake_key");
        TestableAiRecommender ai = new TestableAiRecommender(db);
        ai.defaultLlmResponse = buildDeterministicConciergeJson(8);
        VoiceConcierge vc = new VoiceConcierge(db, ai, new SyncManager(db));
        JSONObject turn1 = vc.handleTurn("member1", "dark mystery shows", false);
        assertTrue(turn1.getJSONArray("results").length() >= 8);
        JSONObject turn2 = vc.handleTurn("member1", "only on Netflix", false);
        assertTrue(turn2.getJSONArray("turns").length() >= 4);
    }

    private String buildDeterministicConciergeJson(int count) throws Exception {
        JSONArray res = new JSONArray();
        for (int i = 0; i < count; i++) {
            res.put(new JSONObject().put("title", "Mystery Show " + i).put("reason", "Tense plot"));
        }
        return new JSONObject().put("reply", "Top mysteries.").put("results", res)
            .put("follow_ups", new JSONArray().put("More like this")).toString();
    }
}
