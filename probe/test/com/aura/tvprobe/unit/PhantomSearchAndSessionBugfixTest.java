package com.aura.tvprobe;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.Test;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import static org.junit.Assert.*;

/**
 * Unit tests covering phantom search prevention, Katniss logcat freshness,
 * voice intent replay protection, and episode transition timing accuracy.
 */
public class PhantomSearchAndSessionBugfixTest {

    @Test
    public void testKatnissLogcatTimestampFilterRejectsStaleRingBufferReplays() {
        long now = 1794414920580L;
        String freshTs = VoiceSearchHelper.formatLogcatStartTimestamp(now - 1_200L);
        String staleTs = VoiceSearchHelper.formatLogcatStartTimestamp(now - 1_800_000L);
        assertTrue(VoiceSearchHelper.isFreshKatnissLogLine(freshTs + " 1200 1200 I katniss: ok", now, 5_000L));
        assertFalse(VoiceSearchHelper.isFreshKatnissLogLine(staleTs + " 1200 1200 I katniss: old", now, 5_000L));
        assertFalse(VoiceSearchHelper.isFreshKatnissLogLine("no_timestamp_prefix", now, 5_000L));
    }

    @Test
    public void testKatnissLogcatYearBoundaryRollover() throws Exception {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        long jan1Ms = fmt.parse("2027-01-01 00:00:01.000").getTime();
        String dec31Line = "12-31 23:59:59.500  900  900 I katniss: state[FINAL_RESULT]";
        assertTrue(VoiceSearchHelper.isFreshKatnissLogLine(dec31Line, jan1Ms, 5_000L));
    }

    @Test
    public void testDirectVoiceExtraRequiresFreshUnconsumedTimestamp() {
        long now = System.currentTimeMillis();
        Intent missingTs = new Intent(Intent.ACTION_MAIN).putExtra("aura_voice_query", "Dark");
        assertEquals("", VoiceSearchHelper.extractFreshDirectVoiceQuery(missingTs, now));
        Intent staleTs = new Intent(Intent.ACTION_MAIN).putExtra("aura_voice_query", "Dark").putExtra("aura_voice_ts", now - 60_000L);
        assertEquals("", VoiceSearchHelper.extractFreshDirectVoiceQuery(staleTs, now));
        verifySingleConsumptionOfFreshVoiceIntent(now);
    }

    private void verifySingleConsumptionOfFreshVoiceIntent(long now) {
        Intent fresh = new Intent(Intent.ACTION_MAIN)
            .putExtra("aura_voice_query", "Severance")
            .putExtra("aura_voice_ts", now - 400L);
        assertEquals("Severance", VoiceSearchHelper.extractAssistantSearchQuery(fresh));
        Intent recreated = new Intent(Intent.ACTION_MAIN)
            .putExtra("aura_voice_query", "Severance")
            .putExtra("aura_voice_ts", now - 400L);
        assertEquals("", VoiceSearchHelper.extractFreshDirectVoiceQuery(recreated, now));
    }

    @Test
    public void testActionViewSuggestionFilterRejectsUntrustedUris() {
        Intent randomView = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/watch/80100172"));
        assertEquals("", VoiceSearchHelper.extractAssistantSearchQuery(randomView));
        Intent trustedUri = new Intent(Intent.ACTION_VIEW, Uri.parse("content://com.aura.tvprobe.search/suggest/Andor"));
        assertEquals("Andor", VoiceSearchHelper.extractAssistantSearchQuery(trustedUri));
        Intent extraKeyView = new Intent(Intent.ACTION_VIEW).putExtra(SearchManager.EXTRA_DATA_KEY, "Silo");
        assertEquals("Silo", VoiceSearchHelper.extractAssistantSearchQuery(extraKeyView));
    }

    @Test
    public void testEpisodeTransitionPositionResetAndDurationNormalization() throws Exception {
        verifySnapshotIdentityAndPlayTimerReset();
        int[] longMovie = SessionInspector.normalizeSessionTiming(4200, -1L, 150);
        assertArrayEquals(new int[]{4200, 9000}, longMovie);
        int[] unestimatedFinale = SessionInspector.normalizeSessionTiming(4548, -1L, 60);
        assertArrayEquals(new int[]{4548, 5448}, unestimatedFinale);
        int[] explicitClamp = SessionInspector.normalizeSessionTiming(3050, 3_000_000L, 50);
        assertArrayEquals(new int[]{3000, 3000}, explicitClamp);
    }

    private void verifySnapshotIdentityAndPlayTimerReset() throws Exception {
        JSONObject ep1 = buildPrimeSnapshot("amzn1.dv.gti.ep1", 3_480_000L);
        JSONObject ep2 = buildPrimeSnapshot("amzn1.dv.gti.ep2", 15_000L);
        assertNotEquals(SessionInspector.extractSnapshotIdentity(ep1), SessionInspector.extractSnapshotIdentity(ep2));
        assertEquals(15_000L, SessionInspector.resolveUpdatedPlayStartPosMs(3_480_000L, 15_000L, true));
        assertEquals(720_000L, SessionInspector.resolveUpdatedPlayStartPosMs(2_400_000L, 720_000L, false));
        assertEquals(2_400_000L, SessionInspector.resolveUpdatedPlayStartPosMs(2_400_000L, 0L, false));
    }

    @Test
    public void testPrimeSessionPersistenceKeepsMidEpisodeInContinueWatching() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        db.saveCachedGti("amzn1.dv.gti.periph2", new JSONObject()
            .put("series_title", "The Peripheral").put("title", "Empathy Bonus")
            .put("season", 1).put("episode", 2).put("runtime_min", 68).toString());
        SessionInspector insp = new SessionInspector(new RecordingContext(),
            new ComponentName("com.aura.tvprobe", "MediaProbeService"), db, new TestableAiRecommender(db));
        insp.persistSerializedSession(buildPrimeSnapshot("amzn1.dv.gti.periph2", 1_500_000L));
        verifyPeripheralPresentInContinueWatching(db);
    }

    private void verifyPeripheralPresentInContinueWatching(InMemoryAuraDb db) {
        JSONObject saved = db.listHistory("member1").optJSONObject(0);
        assertNotNull(saved);
        assertEquals(1500, saved.optInt("position_sec"));
        assertEquals(4080, saved.optInt("duration_sec"));
        List<JSONObject> cw = ContinueWatchingSelector.selectDiversified(Collections.singletonList(saved), 5);
        assertEquals(1, cw.size());
    }

    private JSONObject buildPrimeSnapshot(String gti, long posMs) throws Exception {
        JSONObject meta = new JSONObject()
            .put("com.amazon.alexa.externalmediaplayer.metadata.PLAYBACK_SOURCE_ID", gti);
        return new JSONObject().put("package", "com.amazon.amazonvideo.livingroom")
            .put("state", 3).put("position_ms", posMs).put("metadata", meta);
    }
}
