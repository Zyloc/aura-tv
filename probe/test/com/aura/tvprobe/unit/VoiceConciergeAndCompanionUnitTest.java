package com.aura.tvprobe;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link VoiceConcierge}, {@link ShowCompanionAgent}, and {@link SubtitleAndGuideStore}.
 */
public class VoiceConciergeAndCompanionUnitTest {

    @Test
    public void testVoiceConciergeConstraintsAndYearResolution() throws Exception {
        assertTrue(VoiceConcierge.wantsMoviesOnly("90s thriller movies"));
        assertFalse(VoiceConcierge.wantsMoviesOnly("movies or series"));
        assertTrue(VoiceConcierge.wantsSeriesOnly("limited series on Netflix"));
        assertEquals(2026, VoiceConcierge.resolveSearchMinYear("released this year", 2026, 1950));
        assertEquals(2025, VoiceConcierge.resolveSearchMinYear("latest sci-fi", 2026, 1950));
        assertEquals(1950, VoiceConcierge.resolveSearchMinYear("90s thriller", 2026, 1950));
        assertEquals(1950, VoiceConcierge.resolveSearchMinYear(null, 2026, 1950));
        verifyConversationalConstraintFiltering();
    }

    private void verifyConversationalConstraintFiltering() throws Exception {
        JSONArray items = new JSONArray()
            .put(new JSONObject().put("title", "Avengers").put("synopsis", "Marvel hero").put("type", "Movie").put("app", "Disney+"))
            .put(new JSONObject().put("title", "Arrival").put("synopsis", "Alien linguistics").put("type", "Movie").put("app", "Netflix"));
        JSONArray filtered = VoiceConcierge.applyConversationalConstraints(
            items, "sci-fi movie no marvel on netflix", Collections.emptyList());
        assertEquals(1, filtered.length());
        assertEquals("Arrival", filtered.getJSONObject(0).getString("title"));
    }

    @Test
    public void testVoiceConciergeResetCompanionAndRemoteDelegation() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        TestableAiRecommender ai = new TestableAiRecommender(db);
        VoiceConcierge vc = new VoiceConcierge(db, ai, new SyncManager(db));
        JSONObject resetRes = vc.handleTurn("member1", "", true);
        assertEquals(0, resetRes.getJSONArray("turns").length());
        JSONObject compRes = vc.handleTurn("member1", "catch me up", false);
        assertTrue(compRes.has("companion_recap"));
        verifyRemoteHostChatDelegation(db, ai);
    }

    private void verifyRemoteHostChatDelegation(InMemoryAuraDb db, TestableAiRecommender ai) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api/chat", this::writeRemoteChatResponse);
        srv.start();
        try {
            db.setSetting("server_mode", "mac");
            db.setSetting("mac_host", "http://127.0.0.1:" + srv.getAddress().getPort());
            JSONObject res = new VoiceConcierge(db, ai, new SyncManager(db)).handleTurn("member1", "thriller", false);
            assertEquals("Remote Mac Reply", res.optString("reply"));
        } finally { srv.stop(0); }
    }

    private void writeRemoteChatResponse(com.sun.net.httpserver.HttpExchange ex) throws java.io.IOException {
        byte[] body = "{\"reply\":\"Remote Mac Reply\",\"turns\":[],\"results\":[]}".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    @Test
    public void testShowCompanionLensesAndExecution() throws Exception {
        assertEquals(ShowCompanionAgent.LENS_FULL_RECAP, ShowCompanionAgent.detectLens("catch me up on severance"));
        assertEquals(ShowCompanionAgent.LENS_RECENT_SCENE, ShowCompanionAgent.detectLens("what did he just say"));
        assertEquals(ShowCompanionAgent.LENS_ACTOR_XRAY, ShowCompanionAgent.detectLens("who is this actor on screen"));
        assertEquals(ShowCompanionAgent.LENS_CHARACTER_OR_PLOT, ShowCompanionAgent.detectLens("who is Cobel"));
        assertEquals("Severance", ShowCompanionAgent.extractTitleFromCompanionQuery("catch me up on Severance"));
        verifyShowCompanionFullAndXrayTurns();
    }

    private void verifyShowCompanionFullAndXrayTurns() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        seedCachedGuideAndSubtitles(db);
        TestableAiRecommender ai = new TestableAiRecommender(db);
        ai.enqueueLlmResponse("{\"headline\":\"Severance · S1E2\",\"story_so_far\":\"Mark joined Lumon.\",\"current_episode_beats\":\"Helly undergoes orientation.\",\"cast_xray\":\"\",\"spoken_narration\":\"Mark joined Lumon and Helly started orientation.\"}");
        JSONObject item = new JSONObject().put("title", "Half Loop").put("series_title", "Severance")
            .put("season", 1).put("episode", 2).put("position_sec", 600).put("duration_sec", 3000);
        JSONObject recap = ShowCompanionAgent.executeCompanionTurn(ai, db, "member1", "catch me up", item);
        assertEquals("Severance · S1E2", recap.optString("headline"));
        assertEquals(1, executeCachedAndXrayCompanionChecks(db, ai, item));
    }

    private int executeCachedAndXrayCompanionChecks(
            InMemoryAuraDb db, TestableAiRecommender ai, JSONObject item) {
        JSONObject cachedHit = ShowCompanionAgent.executeCompanionTurn(ai, db, "member1", "catch me up", item);
        assertEquals("Severance · S1E2", cachedHit.optString("headline"));
        int callsAfterCache = ai.llmCallCount;
        ai.enqueueLlmResponse("broken json");
        JSONObject xray = ShowCompanionAgent.executeCompanionTurn(ai, db, "member1", "who is this actor", item);
        assertEquals(ShowCompanionAgent.LENS_ACTOR_XRAY, xray.optString("lens"));
        return callsAfterCache;
    }

    private void seedCachedGuideAndSubtitles(InMemoryAuraDb db) throws Exception {
        JSONArray eps = new JSONArray()
            .put(new JSONObject().put("s", 1).put("e", 1).put("name", "Pilot").put("summary", "Mark greets Helly."))
            .put(new JSONObject().put("s", 1).put("e", 2).put("name", "Half Loop").put("summary", "Helly tries to leave."));
        JSONArray cast = new JSONArray().put(new JSONObject().put("actor", "Adam Scott").put("character", "Mark Scout"));
        JSONObject guide = new JSONObject().put("title", "Severance").put("imdb_id", "tt11280740").put("episodes", eps).put("cast", cast);
        db.saveCachedGti("tvmaze_guide_v2:severance", guide.toString());
    }

    @Test
    public void testSubtitleAndGuideStoreSlicingAndParsers() throws Exception {
        String srt = "1\n00:01:00,000 --> 00:01:04,500\n<i>Mark:</i> Welcome to MDR orientation today!\n\n2\n00:04:10,200 --> 00:04:15,000\nCobel is watching the hallway closely.\n";
        JSONArray cues = SubtitleAndGuideStore.parseRawSrtToCues(srt);
        assertEquals(2, cues.length());
        assertEquals("04:10", SubtitleAndGuideStore.formatMmSs(250));
        assertTrue(SubtitleAndGuideStore.sliceCompressedSceneBeats(cues, 300).contains("Welcome to MDR"));
        assertTrue(SubtitleAndGuideStore.sliceRawWindow(cues, 240, 260).contains("Cobel"));
        assertTrue(SubtitleAndGuideStore.grepCharacterOrTopic(cues, 300, Arrays.asList("Cobel")).contains("Cobel"));
        verifyStitchAndOpenSubsPicker(cues);
    }

    private void verifyStitchAndOpenSubsPicker(JSONArray cues) throws Exception {
        JSONArray cast = new JSONArray().put(new JSONObject().put("actor", "Adam Scott").put("character", "Mark Scout"));
        JSONObject guide = new JSONObject().put("cast", cast);
        assertEquals(1, SubtitleAndGuideStore.inferActiveSceneActors(guide, cues, 70).length());
        JSONArray eps = new JSONArray().put(new JSONObject().put("s", 1).put("e", 1).put("name", "Pilot").put("summary", "Begins."));
        assertTrue(SubtitleAndGuideStore.buildHierarchicalEpisodeStitch(new JSONObject().put("episodes", eps), 1, 1).contains("S1E1"));
        Method m = SubtitleAndGuideStore.class.getDeclaredMethod("pickBestEnglishSrtUrl", String.class);
        m.setAccessible(true);
        assertEquals("https://s/1.srt", m.invoke(null, "{\"subtitles\":[{\"lang\":\"eng\",\"url\":\"https://s/1.srt\"}]}"));
    }

    @Test
    public void testProgressiveSubtitlesFriendlyTtsVoiceScoringAndHijackGuard() throws Exception {
        verifyProgressiveSubtitleAndTtsHelpers();
        verifyWordToWordCounterAndFrameSync();
        verifySimpleSummarizeStepsAndNonCachedFallback();
    }

    private void verifyWordToWordCounterAndFrameSync() {
        java.util.List<SubtitleWordTracker.WordToken> toks = SubtitleWordTracker.tokenize(
            "One two three four five six seven eight nine ten eleven twelve");
        assertEquals(2, SubtitleWordTracker.resolveWordIndex(toks, 8, 13));
        SubtitleWordTracker.PageSlice p0 = SubtitleWordTracker.buildPageAtWord(toks, 2);
        assertEquals("three", p0.text.substring(p0.activeStart, p0.activeEnd));
        assertEquals("eleven", SubtitleWordTracker.buildPageAtWord(toks, 10).text);
        assertEquals(22050, AuraTtsNarrator.resolveRangeFrameRateHz(48000));
        assertEquals(1320L, AuraTtsNarrator.computeFrameSyncDelayMs(1000L, 1000L, 22050, 48000, 320L));
    }

    private void verifyProgressiveSubtitleAndTtsHelpers() {
        String s = "Flynne enters the headset in Clanton.";
        assertEquals("Flynne", LiveSubtitleBar.buildProgressiveText(s, 0));
        assertEquals("Flynne enters the", LiveSubtitleBar.buildProgressiveText(s, 17));
        assertEquals(s, LiveSubtitleBar.buildProgressiveText(s, 999));
        assertEquals("Season 1, Episode 2 recap.", AuraTtsNarrator.cleanSpokenText("[CURRENT EPISODE] Premise: S1E2 recap."));
        verifyVoiceScoringAndHijackRules();
    }

    private void verifyVoiceScoringAndHijackRules() {
        int netScore = AuraTtsNarrator.scoreVoiceAttributes("en", "US", "en-us-x-iog-server", 400, true);
        int embScore = AuraTtsNarrator.scoreVoiceAttributes("en", "US", "en-us-x-iog-lstm-embedded", 400, false);
        assertTrue(netScore > embScore);
        assertFalse(InAppVoiceRouter.isHijackForegroundPkg("com.google.android.katniss", "com.amazon.amazonvideo.livingroom"));
        assertFalse(InAppVoiceRouter.isHijackForegroundPkg("com.google.android.katniss:search", "com.netflix.ninja"));
        assertTrue(InAppVoiceRouter.isHijackForegroundPkg("com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom"));
        assertFalse(InAppVoiceRouter.isHijackForegroundPkg("com.google.android.youtube.tv", "com.google.android.youtube.tv"));
    }

    private void verifySimpleSummarizeStepsAndNonCachedFallback() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        seedCachedGuideAndSubtitles(db);
        TestableAiRecommender ai = new TestableAiRecommender(db);
        ai.enqueueLlmResponse("");
        java.util.List<String> steps = new java.util.ArrayList<>();
        JSONObject item = new JSONObject().put("series_title", "Severance").put("season", 1).put("episode", 2).put("position_sec", 600);
        JSONObject fb = ShowCompanionAgent.executeCompanionTurn(ai, db, "m1", "catch me up", item, steps::add);
        assertTrue(fb.optBoolean("is_fallback") && !fb.optString("spoken_narration").contains("S1E1") && steps.size() >= 3);
        verifyVideoScreenVsDetailPageAndAudioSilence(db);
    }

    private void verifyVideoScreenVsDetailPageAndAudioSilence(InMemoryAuraDb db) throws Exception {
        verifyLivePlayerScreenDetection();
        verifyDetailPageVisionMergeAndAudioState(db);
    }

    private void verifyLivePlayerScreenDetection() throws Exception {
        JSONObject pausedJustNow = new JSONObject().put("package", "com.amazon.amazonvideo.livingroom").put("state", 2).put("age_since_update_ms", 3500L);
        JSONObject pausedOld = new JSONObject().put("package", "com.amazon.amazonvideo.livingroom").put("state", 2).put("age_since_update_ms", 95000L);
        assertTrue(SessionInspector.isLikelyOnVideoPlayerScreen(pausedJustNow));
        assertFalse(SessionInspector.isLikelyOnVideoPlayerScreen(pausedOld));
        JSONArray sessions = new JSONArray().put(pausedJustNow);
        assertNotNull(SessionInspector.findLiveSessionForPackage(sessions, "com.amazon.amazonvideo.livingroom"));
        assertEquals(3, ShowCompanionAgent.findPngHeaderOffset(new byte[]{'w', 'r', 'n', (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));
    }

    private void verifyDetailPageVisionMergeAndAudioState(InMemoryAuraDb db) throws Exception {
        assertNull(ShowCompanionAgent.parseDetectedDetailPageJson("{\"title\":\"Prime Video\"}"));
        db.upsertWatchItem(new JSONObject().put("media_key", "prime:the peripheral:jackpot").put("app_pkg", "com.amazon.amazonvideo.livingroom")
            .put("series_title", "The Peripheral").put("title", "Jackpot").put("season", 1).put("episode", 4).put("position_sec", 3036));
        TestableAiRecommender ai = new TestableAiRecommender(db);
        ai.enqueueLlmResponse("{\"title\":\"The Peripheral\",\"season\":1,\"episode\":0}");
        JSONObject resolved = ShowCompanionAgent.resolveDetailPageItemIfNeeded(ai, db, "default", "catch me up", new JSONObject(), "ZmFrZQ==", null);
        assertEquals(4, resolved.optInt("episode"));
        assertEquals(3036, resolved.optInt("position_sec"));
        verifyCompanionAudioSilenceToggle();
    }

    private void verifyCompanionAudioSilenceToggle() throws Exception {
        CompanionAudioFocusController.engageCompanionSilence(null);
        assertTrue(CompanionAudioFocusController.isCompanionSilenceActive());
        CompanionAudioFocusController.releaseCompanionSilence(null);
        assertFalse(CompanionAudioFocusController.isCompanionSilenceActive());
        JSONObject out = new JSONObject();
        JSONArray wn = new JSONArray().put(new JSONObject().put("package_name", "com.graymatrix.did")
            .put("title", "Tu Hi Re Dil Mein").put("episode_title", "Sanjog Shock").put("season_display_number", "1").put("episode_display_number", "149"));
        assertTrue(SessionInspector.applyEpisodeMatchFromRows(out, wn, "com.graymatrix.did", "Sanjog Shock", false));
        assertEquals("Tu Hi Re Dil Mein", out.optString("series_title"));
        assertEquals(149, out.optInt("episode"));
    }
}
