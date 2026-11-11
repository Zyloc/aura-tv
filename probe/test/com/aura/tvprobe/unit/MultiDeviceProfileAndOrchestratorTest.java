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

    @Test
    public void testShowCompanionIntentLensesAndTargetExtraction() throws Exception {
        assertTrue(ShowCompanionAgent.isShowCompanionQuery("Catch me up on MobLand Season 1 Episode 4 at 24 minutes"));
        assertEquals(ShowCompanionAgent.LENS_FULL_RECAP,
            ShowCompanionAgent.detectLens("Catch me up on MobLand Season 1 Episode 4 at 24 minutes"));
        assertEquals("MobLand",
            ShowCompanionAgent.extractTitleFromCompanionQuery("Catch me up on MobLand Season 1 Episode 4 at 24 minutes"));
        assertEquals("Generic 'what happened so far' must not overwrite active show title", "",
            ShowCompanionAgent.extractTitleFromCompanionQuery("Catch me up on what happened so far"));

        assertTrue(ShowCompanionAgent.isShowCompanionQuery("Who is Harry Da Souza in MobLand and why did he visit Antoine?"));
        assertEquals(ShowCompanionAgent.LENS_CHARACTER_OR_PLOT,
            ShowCompanionAgent.detectLens("Who is Harry Da Souza in MobLand and why did he visit Antoine?"));

        assertTrue(ShowCompanionAgent.isShowCompanionQuery("What happened in the last 5 minutes?"));
        assertEquals(ShowCompanionAgent.LENS_RECENT_SCENE,
            ShowCompanionAgent.detectLens("What happened in the last 5 minutes?"));

        assertTrue(ShowCompanionAgent.isShowCompanionQuery("Who is this actor on screen and find me movies with him"));
        assertEquals(ShowCompanionAgent.LENS_ACTOR_XRAY,
            ShowCompanionAgent.detectLens("Who is this actor on screen and find me movies with him"));

        JSONObject ctx = ShowCompanionAgent.resolveTargetContext(
            null, "member1", "Catch me up on MobLand Season 1 Episode 4 at 24 minutes", null);
        assertEquals("MobLand", ctx.optString("title"));
        assertEquals(1, ctx.optInt("season"));
        assertEquals(4, ctx.optInt("episode"));
        assertEquals(1440, ctx.optInt("position_sec"));
    }

    @Test
    public void testSubtitleSrtSlicingSceneBeatCompressionGrepAndActorXray() throws Exception {
        String sampleSrt =
            "1\n00:00:15,000 --> 00:00:18,000\nYeah.\n\n" +
            "2\n00:02:10,000 --> 00:02:16,000\nHarry Da Souza is heading to the docks to meet Conrad Harrigan.\n\n" +
            "3\n00:08:40,000 --> 00:08:46,000\nMaeve Harrigan warned Kevin that Richie is planning a retaliation tonight.\n\n" +
            "4\n00:23:10,000 --> 00:23:18,000\nBella told Harry to pay a visit to Antoine before Conrad finds the rat.\n\n" +
            "5\n00:42:00,000 --> 00:42:10,000\nSpoiler from minute 42 that must be excluded when paused at minute 24!\n";

        JSONArray cues = SubtitleAndGuideStore.parseRawSrtToCues(sampleSrt);
        assertEquals("Filler 'Yeah.' must be stripped while 4 substantive cues remain", 4, cues.length());

        // Slice up to 24:00 (1440s) -> must include minutes 2, 8, 23 and exclude minute 42
        String compressed = SubtitleAndGuideStore.sliceCompressedSceneBeats(cues, 1440);
        assertTrue(compressed.contains("Harry Da Souza"));
        assertTrue(compressed.contains("Antoine"));
        assertFalse("Cues after position_sec (1440s) must never leak into sliced beats", compressed.contains("minute 42"));

        // Grep for 'Antoine' up to 1440s
        String grepped = SubtitleAndGuideStore.grepCharacterOrTopic(cues, 1440, Arrays.asList("Antoine"));
        assertTrue(grepped.contains("Antoine"));

        // Actor <-> Character X-Ray at 23m 30s (1410s)
        JSONObject guide = new JSONObject().put("cast", new JSONArray()
            .put(new JSONObject().put("actor", "Tom Hardy").put("character", "Harry Da Souza"))
            .put(new JSONObject().put("actor", "Pierce Brosnan").put("character", "Conrad Harrigan"))
            .put(new JSONObject().put("actor", "Lara Pulver").put("character", "Bella Harrigan")));
        JSONArray activeActors = SubtitleAndGuideStore.inferActiveSceneActors(guide, cues, 1410);
        assertTrue(activeActors.length() >= 2);
        assertEquals("Clean TTS text must strip markdown/icons and expand episode codes",
            "MobLand Season 1, Episode 4 Audio Recap", AuraTtsNarrator.cleanSpokenText("🔊 **MobLand** `S1E4` Audio Recap"));
    }

    @Test
    public void testGlobalVoiceInterceptionFromBackgroundAppAndLiveSubtitleWordBounds() {
        // Katniss YouTube media.PLAY_MEDIA and LIBAS_CLIENT_OPS_RECEIVED takeover logs must be intercepted
        assertTrue(VoiceSearchHelper.isKatnissResponseTakeoverLog(
            "11-10 20:01:18.182 17402 17431 I katniss_interactor_MediaClientOpHandler: Preparing ClientOp: media.PLAY_MEDIA"));
        assertTrue(VoiceSearchHelper.isKatnissResponseTakeoverLog(
            "11-10 20:01:17.694 17402 17446 I katniss_interactor_LibAssistantAppFlowLogger: LIBAS_CLIENT_OPS_RECEIVED"));

        // Show Companion queries ("can you catch me up") must ALWAYS be handled even when in Netflix background
        assertTrue(AuraActivity.shouldHandleGlobalVoiceQuery("can you catch me up"));

        // LiveSubtitleBar sentence splitting and word-by-word highlight boundary calculation
        List<String> sentences = AuraTtsNarrator.splitIntoSentences(
            "Harry Da Souza met Conrad at the docks. Maeve warned Kevin about Richie! What happens next?");
        assertEquals(3, sentences.size());
        assertEquals("Harry Da Souza met Conrad at the docks.", sentences.get(0));
        assertEquals("Maeve warned Kevin about Richie!", sentences.get(1));

        int[] bounds = AuraTtsNarrator.findWordBoundsAt("Harry Da Souza met Conrad", 10);
        assertEquals("Souza", "Harry Da Souza met Conrad".substring(bounds[0], bounds[1]));
    }

    @Test
    public void testInAppVoiceRouterIntentClassificationAndSameAppSearchUris() {
        InAppVoiceRouter.VoiceDecision c1 = InAppVoiceRouter.classifyIntent(
            null, "Can you catch me up on what happened so far", "Disney+ Hotstar");
        assertEquals(InAppVoiceRouter.VoiceAction.COMPANION, c1.action);

        InAppVoiceRouter.VoiceDecision c2 = InAppVoiceRouter.classifyIntent(
            null, "Search for Interstellar on Hotstar please", "Disney+ Hotstar");
        assertEquals(InAppVoiceRouter.VoiceAction.IN_APP_SEARCH, c2.action);
        assertEquals("Interstellar", c2.cleanQuery);

        InAppVoiceRouter.VoiceDecision c3 = InAppVoiceRouter.classifyIntent(
            null, "Christopher Nolan sci-fi movies", "Netflix");
        assertEquals(InAppVoiceRouter.VoiceAction.IN_APP_SEARCH, c3.action);
        assertEquals("Christopher Nolan sci-fi movies", c3.cleanQuery);

        InAppVoiceRouter.VoiceDecision ctrl = InAppVoiceRouter.classifyIntent(null, "volume up", "YouTube");
        assertEquals(InAppVoiceRouter.VoiceAction.CONTROL, ctrl.action);

        InAppVoiceRouter.VoiceDecision home = InAppVoiceRouter.classifyIntent(null, "go home", "Prime Video");
        assertEquals(InAppVoiceRouter.VoiceAction.GO_HOME, home.action);

        assertTrue(InAppVoiceRouter.isIgnoredForegroundPackage("com.google.android.katniss"));
        assertTrue(InAppVoiceRouter.isIgnoredForegroundPackage("com.aura.tvprobe"));
        assertFalse(InAppVoiceRouter.isIgnoredForegroundPackage("in.startv.hotstar"));
        assertFalse(InAppVoiceRouter.isIgnoredForegroundPackage("com.netflix.ninja"));

        assertEquals("https://www.hotstar.com/in/explore?search_query=Prison%20Break",
            MediaDeepLinkLauncher.buildAppSearchUri("in.startv.hotstar", "Prison Break"));
        assertEquals("nflx://www.netflix.com/search?q=Dark%20Matter",
            MediaDeepLinkLauncher.buildAppSearchUri("com.netflix.ninja", "Dark Matter"));
        assertEquals("https://app.primevideo.com/search?phrase=Fallout",
            MediaDeepLinkLauncher.buildAppSearchUri("com.amazon.amazonvideo.livingroom", "Fallout"));
        assertEquals("https://www.youtube.com/results?search_query=Interstellar%204K",
            MediaDeepLinkLauncher.buildAppSearchUri("com.google.android.youtube.tv", "Interstellar 4K"));
        assertEquals("sony://search?query=Scam%201992&q=Scam%201992",
            MediaDeepLinkLauncher.buildAppSearchUri("com.sonyliv", "Scam 1992"));
        assertEquals("https://tv.apple.com/search?term=Severance",
            MediaDeepLinkLauncher.buildAppSearchUri("com.apple.atve.androidtv.appletv", "Severance"));
    }
}
