package com.aura.tvprobe;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link InAppVoiceRouter}, {@link MediaDeepLinkLauncher},
 * {@link TvOverlayManager}, {@link BootReceiver}, and {@link AuraVoiceSearchProvider}.
 */
public class InAppVoiceAndDeepLinkUnitTest {

    @Test
    public void testInAppVoiceDeterministicIntentClassification() {
        assertEquals(InAppVoiceRouter.VoiceAction.GO_HOME,
            InAppVoiceRouter.classifyIntent(null, "go back to aura home!", "Netflix").action);
        assertEquals(InAppVoiceRouter.VoiceAction.CONTROL,
            InAppVoiceRouter.classifyIntent(null, "volume up", "Netflix").action);
        assertEquals(InAppVoiceRouter.VoiceAction.COMPANION,
            InAppVoiceRouter.classifyIntent(null, "catch me up on this show", "Netflix").action);
        assertEquals(InAppVoiceRouter.VoiceAction.IN_APP_SEARCH,
            InAppVoiceRouter.classifyIntent(null, "search Inception on Netflix?", "Netflix").action);
    }

    @Test
    public void testInAppVoiceShortTitleAndLlmIntentClassification() {
        InMemoryAuraDb db = new InMemoryAuraDb();
        TestableAiRecommender ai = new TestableAiRecommender(db);
        assertEquals(InAppVoiceRouter.VoiceAction.IN_APP_SEARCH,
            InAppVoiceRouter.classifyIntent(ai, "Severance Season 2", "Apple TV+").action);
        ai.enqueueLlmResponse("{\"intent\":\"SEARCH\",\"query\":\"korean heist thriller\"}");
        InAppVoiceRouter.VoiceDecision dec = InAppVoiceRouter.classifyIntent(
            ai, "can you recommend a really good korean heist thriller for tonight", "Netflix");
        assertEquals(InAppVoiceRouter.VoiceAction.IN_APP_SEARCH, dec.action);
        assertEquals("korean heist thriller", dec.cleanQuery);
        verifyLlmFallbackIntentClassification(ai);
    }

    private void verifyLlmFallbackIntentClassification(TestableAiRecommender ai) {
        ai.enqueueLlmResponse("{\"intent\":\"HOME\",\"query\":\"\"}");
        assertEquals(InAppVoiceRouter.VoiceAction.GO_HOME,
            InAppVoiceRouter.classifyIntent(ai, "take me back to my main movie dashboard right now", "Netflix").action);
        ai.enqueueLlmResponse("malformed");
        assertEquals(InAppVoiceRouter.VoiceAction.IN_APP_SEARCH,
            InAppVoiceRouter.classifyIntent(ai, "what is a mind bending sci fi thriller", "Netflix").action);
    }

    @Test
    public void testInAppVoiceQueryCleanerAndPackageHelpers() {
        assertEquals("Interstellar",
            InAppVoiceRouter.extractCleanSearchQuery("Hey Google, please search for Interstellar on Netflix please!", "Netflix"));
        assertEquals("Dark",
            InAppVoiceRouter.extractCleanSearchQuery("Can you search for Dark on Netflix?", "Netflix"));
        assertEquals("Prisoners",
            InAppVoiceRouter.extractCleanSearchQuery("find me Prisoners in Disney+ Hotstar", "Disney+ Hotstar"));
        verifyInAppPackageHelpers();
    }

    private void verifyInAppPackageHelpers() {
        assertTrue(InAppVoiceRouter.isIgnoredForegroundPackage("com.aura.tvprobe"));
        assertTrue(InAppVoiceRouter.isIgnoredForegroundPackage("com.google.android.katniss"));
        assertFalse(InAppVoiceRouter.isIgnoredForegroundPackage("com.netflix.ninja"));
        assertEquals("Disney+ Hotstar", InAppVoiceRouter.mapKnownStreamingLabel("in.startv.hotstar"));
        assertEquals("ZEE5", InAppVoiceRouter.resolveAppLabel(null, "com.graymatrix.did"));
        assertEquals("", InAppVoiceRouter.resolveActiveExternalPackage(new RecordingContext()));
    }

    @Test
    public void testMediaDeepLinkLauncherUriAndIntentBuilders() throws Exception {
        JSONObject dune = new JSONObject().put("title", "Dune · Prime Video").put("year", 2021).put("app", "Netflix");
        assertEquals("Dune 2021 official trailer", MediaDeepLinkLauncher.buildTrailerSearchQuery(dune));
        assertEquals("com.netflix.ninja", MediaDeepLinkLauncher.resolvePackage(dune));
        Intent nf = MediaDeepLinkLauncher.buildDeepLinkIntent("https://www.netflix.com/watch/80100172", "com.netflix.ninja");
        assertEquals("30", nf.getStringExtra("source"));
        Intent scheme = MediaDeepLinkLauncher.buildDeepLinkIntent("intent://detail#Intent;package=in.startv.hotstar;end", "");
        assertEquals("in.startv.hotstar", scheme.getPackage());
        verifyAllOttAppSearchUris();
    }

    private void verifyAllOttAppSearchUris() {
        assertTrue(MediaDeepLinkLauncher.buildAppSearchUri("in.startv.hotstar", "Anupama").contains("hotstar.com"));
        assertTrue(MediaDeepLinkLauncher.buildAppSearchUri("com.disney.disneyplus", "Andor").contains("disneyplus.com"));
        assertTrue(MediaDeepLinkLauncher.buildAppSearchUri("com.netflix.ninja", "Dark").startsWith("nflx://"));
        assertTrue(MediaDeepLinkLauncher.buildAppSearchUri("com.amazon.amazonvideo.livingroom", "Fallout").contains("primevideo.com"));
        assertTrue(MediaDeepLinkLauncher.buildAppSearchUri("com.google.android.youtube.tv", "Veritasium").contains("youtube.com"));
        assertTrue(MediaDeepLinkLauncher.buildAppSearchUri("com.sonyliv", "Scam 1992").startsWith("sony://"));
        assertTrue(MediaDeepLinkLauncher.buildAppSearchUri("com.apple.atve.androidtv.appletv", "Silo").contains("tv.apple.com"));
        assertTrue(MediaDeepLinkLauncher.buildAppSearchUri("com.graymatrix.did", "Bandaa").contains("zee5.com"));
        assertEquals("", MediaDeepLinkLauncher.buildAppSearchUri("com.unknown.app", "Query"));
    }

    @Test
    public void testMediaDeepLinkLaunchActionsWithRecordingContext() throws Exception {
        RecordingContext ctx = new RecordingContext();
        InMemoryAuraDb db = new InMemoryAuraDb();
        JSONObject item = new JSONObject().put("title", "Dark").put("app", "Netflix")
            .put("launch_uri", "https://www.netflix.com/watch/80100172");
        MediaDeepLinkLauncher.launchMediaItem(ctx, db, item);
        assertEquals(1, ctx.startedActivities.size());
        ctx.throwOnFirstActivity = true;
        MediaDeepLinkLauncher.launchYouTubeTrailer(ctx, new JSONObject().put("title", "Severance"));
        assertTrue(ctx.startedActivities.get(1).getDataString().contains("youtube.com/results"));
        verifyInAppSearchHdmiAndSettings(ctx, db);
    }

    private void verifyInAppSearchHdmiAndSettings(RecordingContext ctx, InMemoryAuraDb db) {
        assertTrue(MediaDeepLinkLauncher.launchInAppSearchWithResolvedUri(
            ctx, db, "in.startv.hotstar", "Lootere", "https://www.hotstar.com/in/shows/lootere/123"));
        assertTrue(MediaDeepLinkLauncher.launchInAppSearchForPackage(ctx, db, "com.netflix.ninja", "Mindhunter"));
        assertTrue(MediaDeepLinkLauncher.launchInAppSearchForPackage(ctx, db, "com.custom.ott", "Indie Film"));
        MediaDeepLinkLauncher.launchPackage(ctx, "com.android.vending");
        assertFalse(MediaDeepLinkLauncher.resolveHdmiInputId(ctx, 2).isEmpty());
        assertEquals(Settings.ACTION_SOUND_SETTINGS, MediaDeepLinkLauncher.resolveSettingsAction("sound"));
        MediaDeepLinkLauncher.openHdmiInput(ctx, 1);
    }

    @Test
    public void testTvOverlayLauncherBootReceiverAndSearchProvider() {
        RecordingContext ctx = new RecordingContext();
        assertTrue(TvOverlayManager.launchNativeStreamingApp(ctx, "intent://open#Intent;package=com.sonyliv;end", "", ""));
        assertTrue(TvOverlayManager.launchNativeStreamingApp(ctx, "", "Netflix", "Bodies"));
        assertTrue(TvOverlayManager.launchNativeStreamingApp(ctx, "", "", "Dune 2"));
        new BootReceiver().onReceive(ctx, new Intent("com.example.OTHER"));
        new BootReceiver().onReceive(ctx, new Intent(Intent.ACTION_BOOT_COMPLETED));
        verifySearchProviderContract();
    }

    private void verifySearchProviderContract() {
        AuraVoiceSearchProvider p = new AuraVoiceSearchProvider();
        assertTrue(p.onCreate());
        assertNotNull(p.getType(Uri.parse("content://com.aura.tvprobe.search/search_suggest_query")));
        assertNull(p.insert(null, null));
        assertEquals(0, p.delete(null, null, null));
        assertEquals(0, p.update(null, null, null, null));
        assertEquals(0, p.query(Uri.parse("content://com.aura.tvprobe.search/search_suggest_query"), null, null, new String[]{"a"}, null).getCount());
    }
}
