package com.aura.tvprobe;

import android.content.ComponentName;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/**
 * End-to-End Integration Tests for "Catch Me Up" Companion across:
 * 1. Active OTT Video Playback Screen (stays in place, mutes background, zero spoilers, BACK cancels)
 * 2. OTT Show Detail Page (vision identifies title, merges saved episode/timestamp, blackout TTS)
 * 3. Aura Launcher Voice + MediaProbeService HTTP /api/catchup endpoint.
 */
public class CatchMeUpCompanionIntegrationTest {

    @Test
    public void testVideoPlaybackScreenCatchMeUpStaysInPlaceMutesAndCancelsOnBack() throws Exception {
        RecordingContext ctx = new RecordingContext();
        InMemoryAuraDb db = buildSeededPeripheralDb();
        TestableAiRecommender ai = new TestableAiRecommender(db);
        ai.enqueueLlmResponse("{\"intent\":\"COMPANION\",\"lens\":\"FULL_RECAP\",\"target_title\":\"\",\"query\":\"\"}");
        ai.enqueueLlmResponse("{\"headline\":\"The Peripheral \u00B7 Season 1, Episode 4\",\"spoken_narration\":\"In Season 1, Episode 4, Flynne tests the headset in Clanton.\",\"actor_to_search\":\"\"}");
        SessionInspector inspector = buildLivePlayerInspector(ctx, db, ai);
        RecordingTvOverlayManager overlay = new RecordingTvOverlayManager(ctx);
        triggerInAppVideoCatchMeUp(ctx, db, ai, inspector, overlay);
        assertVideoCatchMeUpActiveState(ctx, ai, overlay);
        assertBackKeyCancelsCleanlyWithoutNavigation(ctx, overlay);
    }

    private void triggerInAppVideoCatchMeUp(
            RecordingContext ctx, InMemoryAuraDb db, TestableAiRecommender ai,
            SessionInspector inspector, RecordingTvOverlayManager overlay) throws Exception {
        String primePkg = "com.amazon.amazonvideo.livingroom";
        assertFalse(InAppVoiceRouter.isHijackForegroundPkg("com.google.android.katniss", primePkg));
        InAppVoiceRouter.routeWhileInsideExternalApp(
            ctx, db, ai, inspector, overlay, primePkg, "catch me up right now");
        InAppVoiceRouter.onKatnissTakeoverDetected(ctx);
        assertTrue("Recap must be delivered to overlay", overlay.awaitRecap(5000L));
    }

    private void assertVideoCatchMeUpActiveState(
            RecordingContext ctx, TestableAiRecommender ai, RecordingTvOverlayManager overlay) {
        assertTrue("Video player must never be relaunched or moved back", ctx.startedActivities.isEmpty());
        assertTrue(TvOverlayManager.isCompanionSessionActive());
        assertTrue(CompanionAudioFocusController.isCompanionSilenceActive());
        assertTrue(overlay.loaderCount > 0 && overlay.progressSteps.size() >= 3);
        assertTrue(ai.lastPromptText.contains("Flynne tests the haptic peripheral"));
        assertFalse("Post-timestamp spoiler must be excluded", ai.lastPromptText.contains("Cherise detonates"));
        assertFalse(overlay.deliveredRecap.optString("spoken_narration").contains("S1E4"));
    }

    private void assertBackKeyCancelsCleanlyWithoutNavigation(
            RecordingContext ctx, RecordingTvOverlayManager overlay) {
        assertTrue(overlay.cancelCompanionByBackKey());
        assertFalse(TvOverlayManager.isCompanionSessionActive());
        assertFalse(CompanionAudioFocusController.isCompanionSilenceActive());
        assertTrue(ctx.startedActivities.isEmpty());
    }

    @Test
    public void testShowDetailPageCatchMeUpVisionResolvesSeriesAndMergesSavedProgress() throws Exception {
        RecordingContext ctx = new RecordingContext();
        InMemoryAuraDb db = buildSeededPeripheralDb();
        TestableAiRecommender ai = new TestableAiRecommender(db);
        ai.enqueueLlmResponse("{\"title\":\"The Peripheral\",\"season\":1,\"episode\":0}");
        ai.enqueueLlmResponse("{\"headline\":\"The Peripheral \u00B7 Season 1, Episode 4\",\"spoken_narration\":\"Up to minute 20 in Season 1, Episode 4, Flynne investigates Clanton.\",\"actor_to_search\":\"\"}");
        RecordingTvOverlayManager overlay = new RecordingTvOverlayManager(ctx);
        executeDetailPageCompanionFlow(ctx, db, ai, overlay);
        assertDetailPageCompanionOutcome(ctx, ai, overlay);
    }

    private void executeDetailPageCompanionFlow(
            RecordingContext ctx, InMemoryAuraDb db, TestableAiRecommender ai,
            RecordingTvOverlayManager overlay) {
        String prof = db.getValidActiveProfile();
        overlay.showCompanionLoader("catch me up", "Locking Prime Video\u2026",
            () -> InAppVoiceRouter.cancelActiveCompanionTurn(ctx, overlay, "com.amazon.amazonvideo.livingroom"));
        JSONObject item = ShowCompanionAgent.resolveDetailPageItemIfNeeded(
            ai, db, prof, "catch me up", new JSONObject(), "ZmFrZV9kZXRhaWxfZnJhbWU=", overlay::updateVoiceStatusSubtext);
        JSONObject recap = ShowCompanionAgent.executeCompanionTurn(
            ai, db, prof, "catch me up", item, overlay::updateVoiceStatusSubtext);
        overlay.showInAppCompanionRecap(recap, () -> InAppVoiceRouter.cancelActiveCompanionTurn(ctx, overlay, ""));
    }

    private void assertDetailPageCompanionOutcome(
            RecordingContext ctx, TestableAiRecommender ai, RecordingTvOverlayManager overlay) {
        assertEquals(2, ai.llmCallCount);
        assertEquals(4, overlay.deliveredRecap.optInt("episode"));
        assertEquals(1200, overlay.deliveredRecap.optInt("position_sec"));
        assertTrue(overlay.progressSteps.get(0).contains("Reading show page"));
        assertTrue(TvOverlayManager.isCompanionSessionActive() && CompanionAudioFocusController.isCompanionSilenceActive());
        assertBackKeyCancelsCleanlyWithoutNavigation(ctx, overlay);
    }

    @Test
    public void testLauncherVoiceAndHttpCatchupEndpointEndToEndIntegration() throws Exception {
        verifyLauncherVoiceCatchMeUpAndVoiceStop();
        verifyHttpCatchupEndpointWithLiveOverlay();
    }

    private void verifyLauncherVoiceCatchMeUpAndVoiceStop() throws Exception {
        RecordingContext ctx = new RecordingContext();
        InMemoryAuraDb db = buildSeededPeripheralDb();
        TestableAiRecommender ai = buildSummarizeReadyAi(db, "Season 1, Episode 4 recap from launcher.");
        SessionInspector inspector = new SessionInspector(ctx, null, db, ai);
        RecordingTvOverlayManager overlay = new RecordingTvOverlayManager(ctx);
        InAppVoiceRouter.onVoiceTranscriptCaptured(ctx, db, ai, inspector, overlay, "what happened so far in this episode");
        assertTrue(overlay.awaitRecap(5000L));
        assertTrue(TvOverlayManager.isCompanionSessionActive());
        InAppVoiceRouter.cancelActiveCompanionTurn(ctx, overlay, "");
        assertFalse(TvOverlayManager.isCompanionSessionActive());
    }

    private void verifyHttpCatchupEndpointWithLiveOverlay() throws Exception {
        RecordingContext ctx = new RecordingContext();
        InMemoryAuraDb db = buildSeededPeripheralDb();
        TestableAiRecommender ai = buildSummarizeReadyAi(db, "HTTP triggered Season 1, Episode 4 summary.");
        RecordingTvOverlayManager overlay = new RecordingTvOverlayManager(ctx);
        MediaProbeService svc = buildWiredServiceWithOverlay(ctx, db, ai, overlay);
        String resp = invokeCatchupHttpRoute(svc, "/api/catchup?title=The%20Peripheral&season=1&episode=4&pos=1200&speak=1");
        assertTrue(resp.contains("HTTP triggered Season 1, Episode 4 summary."));
        assertTrue(overlay.awaitRecap(2000L) && TvOverlayManager.isCompanionSessionActive());
        assertBackKeyCancelsCleanlyWithoutNavigation(ctx, overlay);
        assertFalse("BACK after HTTP response must not interrupt worker thread", Thread.currentThread().isInterrupted());
    }

    @Test
    public void testBugfixesSeriesMergeVoiceStopUnmuteAndSingleCancelInvocation() throws Exception {
        verifyContinueWatchingSeriesMergePreservesSeriesAndPosition();
        verifyVoiceStopCancelsActiveBlackoutAndUnmutesStream();
        verifyStandaloneRecapBackCancelExecutesExactlyOnce();
    }

    private void verifyContinueWatchingSeriesMergePreservesSeriesAndPosition() throws Exception {
        InMemoryAuraDb db = buildSeededPeripheralDb();
        JSONObject cwCard = new JSONObject().put("series_title", "The Peripheral").put("title", "Jackpot").put("season", 1).put("episode", 4);
        JSONObject mergedHist = ShowCompanionAgent.mergeDetectedShowWithHistory(db, "default", cwCard, cwCard);
        assertEquals("The Peripheral", mergedHist.optString("series_title"));
        assertEquals(1200, mergedHist.optInt("position_sec"));
        JSONObject freshCard = new JSONObject().put("series_title", "Silo").put("title", "Freedom Day").put("season", 1).put("episode", 2).put("position_sec", 2450);
        JSONObject mergedFresh = ShowCompanionAgent.mergeDetectedShowWithHistory(db, "default", freshCard, freshCard);
        assertEquals("Silo", mergedFresh.optString("series_title"));
        assertEquals(2450, mergedFresh.optInt("position_sec"));
    }

    private void verifyVoiceStopCancelsActiveBlackoutAndUnmutesStream() throws Exception {
        RecordingContext ctx = new RecordingContext();
        InMemoryAuraDb db = buildSeededPeripheralDb();
        TestableAiRecommender ai = buildSummarizeReadyAi(db, "Season 1, Episode 4 summary.");
        RecordingTvOverlayManager overlay = new RecordingTvOverlayManager(ctx);
        overlay.showInAppCompanionRecap(new JSONObject().put("title", "The Peripheral").put("spoken_narration", "Narration."), null);
        assertTrue(TvOverlayManager.isCompanionSessionActive());
        InAppVoiceRouter.onVoiceTranscriptCaptured(ctx, db, ai, null, overlay, "stop recap");
        assertFalse(TvOverlayManager.isCompanionSessionActive());
        assertFalse(CompanionAudioFocusController.isCompanionSilenceActive());
        assertTrue(VoiceSearchHelper.isStreamUnmutedOverride());
    }

    private void verifyStandaloneRecapBackCancelExecutesExactlyOnce() throws Exception {
        RecordingContext ctx = new RecordingContext();
        RecordingTvOverlayManager overlay = new RecordingTvOverlayManager(ctx);
        final int[] calls = {0};
        overlay.showInAppCompanionRecap(
            new JSONObject().put("title", "The Peripheral").put("spoken_narration", "Hello."), () -> calls[0]++);
        assertTrue(overlay.cancelCompanionByBackKey());
        assertEquals("Cancel callback must fire exactly once (no double-wrap)", 1, calls[0]);
        assertFalse(overlay.cancelCompanionByBackKey());
    }

    @Test
    public void testAgenticNaturalIntentRoutingAndCalmSubtitleChunking() throws Exception {
        RecordingContext ctx = new RecordingContext();
        InMemoryAuraDb db = buildSeededPeripheralDb();
        TestableAiRecommender ai = new TestableAiRecommender(db);
        ai.enqueueLlmResponse("{\"intent\":\"COMPANION\",\"lens\":\"FULL_RECAP\",\"target_title\":\"The Peripheral\",\"query\":\"\"}");
        ai.enqueueLlmResponse("{\"headline\":\"The Peripheral \u00B7 Season 1, Episode 4\",\"spoken_narration\":\"Flynne enters the headset in Clanton, and Wilf explains the Jackpot.\",\"actor_to_search\":\"\"}");
        assertFalse(ShowCompanionAgent.isShowCompanionQuery("I forgot where I left off on this series"));
        verifyNaturalLauncherCompanionEndToEnd(ctx, db, ai);
        verifyCalmClauseSubtitleChunking();
    }

    private void verifyNaturalLauncherCompanionEndToEnd(
            RecordingContext ctx, InMemoryAuraDb db, TestableAiRecommender ai) throws Exception {
        RecordingTvOverlayManager overlay = new RecordingTvOverlayManager(ctx);
        SessionInspector inspector = new SessionInspector(ctx, null, db, ai);
        InAppVoiceRouter.onVoiceTranscriptCaptured(ctx, db, ai, inspector, overlay, "I forgot where I left off on this series");
        assertTrue("Agentic intent must route natural phrasing to Companion", overlay.awaitRecap(5000L));
        assertTrue(CompanionAudioFocusController.isCompanionSilenceActive());
        CompanionAudioFocusController.ensureBackgroundPausedBeforeSpeech(ctx);
        assertBackKeyCancelsCleanlyWithoutNavigation(ctx, overlay);
    }

    private void verifyCalmClauseSubtitleChunking() {
        String fourteenWords = "Flynne enters the advanced headset in Clanton while Wilf watches closely from London.";
        List<String> steady = SubtitleWordTracker.splitIntoReadableCaptions(fourteenWords, 16);
        assertEquals("14-word sentence must stay as 1 calm caption without mid-sentence wipe", 1, steady.size());
        String longSentence = "Earlier in Clanton, Flynne discovered the headset connects to future London, and Wilf warned her that Cherise is tracking every move they make.";
        List<String> clauses = SubtitleWordTracker.splitIntoReadableCaptions(longSentence, 16);
        assertTrue("Long sentence must split cleanly into calm readable clauses", clauses.size() >= 2);
        assertTrue(clauses.get(0).endsWith(","));
    }

    @Test
    public void testConversationalClarificationAndMultiTurnFollowUpChatbot() throws Exception {
        CompanionChatSession.reset();
        RecordingContext ctx = new RecordingContext();
        InMemoryAuraDb emptyDb = new InMemoryAuraDb();
        RecordingTvOverlayManager clarifyOverlay = new RecordingTvOverlayManager(ctx);
        InAppVoiceRouter.onVoiceTranscriptCaptured(ctx, emptyDb, new TestableAiRecommender(emptyDb), null, clarifyOverlay, "catch me up please");
        assertTrue(clarifyOverlay.awaitRecap(4000L));
        assertTrue("Must ask which show when none is identifiable", clarifyOverlay.deliveredRecap.optBoolean("is_clarification"));
        assertTrue(CompanionChatSession.isAwaitingClarification());
        verifyClarificationReplyAndMultiTurnFollowUp(ctx);
    }

    private void verifyClarificationReplyAndMultiTurnFollowUp(RecordingContext ctx) throws Exception {
        InMemoryAuraDb db = buildSeededPeripheralDb();
        TestableAiRecommender ai = new TestableAiRecommender(db);
        ai.enqueueLlmResponse("{\"headline\":\"The Peripheral \u00B7 Season 1, Episode 4\",\"spoken_narration\":\"Flynne enters Clanton headset.\",\"actor_to_search\":\"\"}");
        ai.enqueueLlmResponse("{\"headline\":\"The Peripheral \u00B7 Season 1, Episode 4\",\"spoken_narration\":\"Wilf warns Flynne about the Jackpot timeline.\",\"actor_to_search\":\"\"}");
        RecordingTvOverlayManager replyOv = new RecordingTvOverlayManager(ctx);
        InAppVoiceRouter.onVoiceTranscriptCaptured(ctx, db, ai, null, replyOv, "The Peripheral");
        assertTrue(replyOv.awaitRecap(4000L));
        assertFalse(CompanionChatSession.isAwaitingClarification());
        verifyFollowUpIncludesPriorConversationHistory(ctx, db, ai);
    }

    private void verifyFollowUpIncludesPriorConversationHistory(
            RecordingContext ctx, InMemoryAuraDb db, TestableAiRecommender ai) throws Exception {
        RecordingTvOverlayManager followOv = new RecordingTvOverlayManager(ctx);
        InAppVoiceRouter.onVoiceTranscriptCaptured(ctx, db, ai, null, followOv, "explain further about Wilf");
        assertTrue(followOv.awaitRecap(4000L));
        assertTrue("Follow-up prompt must include prior conversation history", ai.lastPromptText.contains("Ongoing Conversation History"));
        assertTrue(followOv.deliveredRecap.optString("spoken_narration").contains("Wilf warns Flynne"));
        CompanionChatSession.reset();
    }

    private InMemoryAuraDb buildSeededPeripheralDb() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        seedPeripheralWatchHistory(db);
        seedPeripheralGuideAndSrt(db);
        return db;
    }

    private void seedPeripheralWatchHistory(InMemoryAuraDb db) throws Exception {
        JSONObject hist = new JSONObject()
            .put("media_key", "prime:the peripheral:jackpot")
            .put("app_pkg", "com.amazon.amazonvideo.livingroom")
            .put("series_title", "The Peripheral").put("title", "Jackpot")
            .put("season", 1).put("episode", 4).put("position_sec", 1200).put("duration_sec", 3400);
        db.upsertWatchItem(hist);
    }

    private void seedPeripheralGuideAndSrt(InMemoryAuraDb db) throws Exception {
        String norm = AuraDb.normalizeTitleKey("The Peripheral");
        seedPeripheralGuide(db, norm);
        seedPeripheralSrtCues(db, norm);
    }

    private void seedPeripheralGuide(InMemoryAuraDb db, String norm) throws Exception {
        JSONArray eps = new JSONArray()
            .put(new JSONObject().put("s", 1).put("e", 3).put("name", "Haptic Drift").put("summary", "Wilf warns Flynne."))
            .put(new JSONObject().put("s", 1).put("e", 4).put("name", "Jackpot").put("summary", "Flynne learns of the Jackpot."));
        JSONObject guide = new JSONObject().put("title", "The Peripheral").put("imdb_id", "tt8291284").put("episodes", eps);
        DatabaseJanitor.saveSubtitleCacheRow(db, "guide:" + norm, norm, 0, 0, guide.toString());
    }

    private void seedPeripheralSrtCues(InMemoryAuraDb db, String norm) {
        String srt = "1\n00:05:10,000 --> 00:05:16,000\nFlynne tests the haptic peripheral in Clanton.\n\n"
            + "2\n00:28:00,000 --> 00:28:06,000\nCherise detonates the vault at midnight.\n";
        JSONArray cues = SubtitleAndGuideStore.parseRawSrtToCues(srt);
        DatabaseJanitor.saveSubtitleCacheRow(db, "srt:" + norm + ":s1e4", norm, 1, 4, cues.toString());
    }

    private TestableAiRecommender buildSummarizeReadyAi(InMemoryAuraDb db, String narration) {
        TestableAiRecommender ai = new TestableAiRecommender(db);
        ai.enqueueLlmResponse("{\"headline\":\"The Peripheral \u00B7 Season 1, Episode 4\",\"spoken_narration\":\""
            + narration + "\",\"actor_to_search\":\"\"}");
        return ai;
    }

    private SessionInspector buildLivePlayerInspector(
            RecordingContext ctx, InMemoryAuraDb db, TestableAiRecommender ai) {
        ComponentName cn = new ComponentName("com.aura.tvprobe", "MediaProbeService");
        return new SessionInspector(ctx, cn, db, ai) {
            @Override public JSONObject inspectLivePackageSession(String targetPkg, String profileId) {
                return buildSimulatedPrimeLiveSession(db, targetPkg);
            }
        };
    }

    private JSONObject buildSimulatedPrimeLiveSession(InMemoryAuraDb db, String targetPkg) {
        JSONObject out = new JSONObject();
        try {
            out.put("app_pkg", targetPkg).put("title", "Jackpot").put("position_sec", 1200).put("live_player_screen", true);
            SessionInspector.applyEpisodeMatchFromRows(out, db.listHistory(null), targetPkg, "Jackpot", true);
        } catch (Exception ignored) {}
        return out;
    }

    private MediaProbeService buildWiredServiceWithOverlay(
            RecordingContext ctx, InMemoryAuraDb db, TestableAiRecommender ai, TvOverlayManager overlay) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        MediaProbeService svc = (MediaProbeService) ((sun.misc.Unsafe) uf.get(null)).allocateInstance(MediaProbeService.class);
        setField(svc, "db", db);
        setField(svc, "ai", ai);
        setField(svc, "overlay", overlay);
        setField(svc, "inspector", new SessionInspector(ctx, null, db, ai));
        return svc;
    }

    private void setField(Object target, String name, Object val) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, val);
    }

    private String invokeCatchupHttpRoute(MediaProbeService svc, String fullPath) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Method m = MediaProbeService.class.getDeclaredMethod(
            "routeAndWrite", OutputStream.class, String.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(svc, out, "POST", fullPath, "{}");
        return out.toString("UTF-8");
    }

    private static final class RecordingTvOverlayManager extends TvOverlayManager {
        final List<String> progressSteps = new CopyOnWriteArrayList<>();
        final CountDownLatch recapLatch = new CountDownLatch(1);
        volatile int loaderCount = 0;
        volatile JSONObject deliveredRecap;

        RecordingTvOverlayManager(Context ctx) { super(ctx); }

        @Override public void showCompanionLoader(String q, String step, Runnable onCancel) {
            loaderCount++;
            super.showCompanionLoader(q, step, onCancel);
        }

        @Override public void updateVoiceStatusSubtext(String subtext) {
            if (subtext != null) progressSteps.add(subtext);
            super.updateVoiceStatusSubtext(subtext);
        }

        @Override public void showInAppCompanionRecap(JSONObject recap, Runnable onCancel) {
            deliveredRecap = recap;
            super.showInAppCompanionRecap(recap, onCancel);
            recapLatch.countDown();
        }

        boolean awaitRecap(long timeoutMs) throws InterruptedException {
            return recapLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }
}
