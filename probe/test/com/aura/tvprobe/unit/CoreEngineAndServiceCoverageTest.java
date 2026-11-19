package com.aura.tvprobe;

import android.media.session.PlaybackState;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Comprehensive unit coverage for core engine, service, and helper invariants
 * to catch regressions early across scoring, validation, voice routing, and audio focus.
 */
public class CoreEngineAndServiceCoverageTest {

    @Test
    public void testContinueWatchingEligibilityProgressAndExcludedPackages() throws Exception {
        assertTrue(ContinueWatchingSelector.isExcludedPackage("com.google.android.youtube.tv"));
        assertTrue(ContinueWatchingSelector.isExcludedPackage("org.smarttube.stable"));
        assertTrue(ContinueWatchingSelector.isExcludedPackage("com.stremio.one"));
        assertFalse(ContinueWatchingSelector.isExcludedPackage("com.netflix.ninja"));
        assertFalse(ContinueWatchingSelector.isUnfinishedProgress(new JSONObject().put("position_sec", 30).put("duration_sec", 3000)));
        assertFalse(ContinueWatchingSelector.isUnfinishedProgress(new JSONObject().put("position_sec", 1350).put("duration_sec", 3000)));
        assertFalse(ContinueWatchingSelector.isUnfinishedProgress(new JSONObject().put("position_sec", 2750).put("duration_sec", 3000)));
        assertTrue(ContinueWatchingSelector.isUnfinishedProgress(new JSONObject().put("position_sec", 1200).put("duration_sec", 3000)));
        verifyEligibleSessionDeduplication();
    }

    private void verifyEligibleSessionDeduplication() throws Exception {
        HashSet<String> seen = new HashSet<>();
        JSONObject valid = new JSONObject().put("series_title", "Silo").put("title", "Ep 2")
            .put("app_pkg", "com.apple.atve.androidtv.appletv").put("app_name", "Apple TV+")
            .put("position_sec", 900).put("duration_sec", 3000);
        assertTrue(ContinueWatchingSelector.isEligibleSession(valid, seen));
        assertFalse("Duplicate title key must be rejected", ContinueWatchingSelector.isEligibleSession(valid, seen));
    }

    @Test
    public void testRegionProviderRegistryAcrossCountriesAndCatalog() {
        InMemoryAuraDb db = new InMemoryAuraDb();
        db.setSetting("region_country", "IN");
        assertEquals("IN", RegionProviderRegistry.getCountryCode(db));
        assertEquals("India", RegionProviderRegistry.getRegionDisplayName(db));
        assertTrue(RegionProviderRegistry.getProviderCodesForRegion(db).contains("jhs"));
        assertTrue(RegionProviderRegistry.getProviderDisplayListForRegion(db).contains("Netflix"));
        assertEquals("en", RegionProviderRegistry.getLanguageCode(db));
        assertTrue(RegionProviderRegistry.getCoreStreamingAppsCatalog().length > 0);
    }

    @Test
    public void testUserTasteProfileValidatorAndSlateBuckets() throws Exception {
        UserTasteProfile p = new UserTasteProfile(Arrays.asList("Severance", "Silo"), "IN", "English", "prestige", 3);
        p.ensureDefaultGenreWeightsIfEmpty();
        assertEquals("Severance", p.getTopAnchor());
        assertEquals("prestige", p.getRankMode());
        assertEquals(3, p.getCurrentEpoch());
        assertEquals(2, p.getAnchors().size());
        assertTrue(p.computeOriginAffinity("US", 25000, 8.2, false) >= 0.0);
        verifyCandidateValidatorAndSlateBucketRules();
    }

    private void verifyCandidateValidatorAndSlateBucketRules() throws Exception {
        assertTrue(CandidateValidator.getDefaultMinModernYear() >= 2020);
        assertFalse(CandidateValidator.isCompatibleFormatForSeed("ani,act", "crm,trl"));
        assertTrue(CandidateValidator.isCompatibleFormatForSeed("scf,trl", "scf,drm"));
        assertEquals("sports_doc", SlateAllocator.classifyBucket("spt,doc"));
        assertEquals("scifi", SlateAllocator.classifyBucket("scf,drm"));
        assertEquals("crime_action", SlateAllocator.classifyBucket("crm,trl"));
        assertEquals("thriller", SlateAllocator.classifyBucket("trl"));
        assertEquals("drama_comedy", SlateAllocator.classifyBucket("drm,cmy"));
    }

    @Test
    public void testCompanionAudioFocusStatesAndVoiceRoutingHelpers() {
        assertTrue(CompanionAudioFocusController.isActivePlaybackState(PlaybackState.STATE_PLAYING));
        assertTrue(CompanionAudioFocusController.isActivePlaybackState(PlaybackState.STATE_BUFFERING));
        assertFalse(CompanionAudioFocusController.isActivePlaybackState(PlaybackState.STATE_PAUSED));
        assertEquals("", CompanionAudioFocusController.getLastPausedExternalPkg());
        verifyVoiceSearchHelperAndInAppRouterRules();
    }

    private void verifyVoiceSearchHelperAndInAppRouterRules() {
        assertTrue(VoiceSearchHelper.isTrustedSuggestionUri(Uri.parse("aura://search/Silo")));
        assertFalse(VoiceSearchHelper.isTrustedSuggestionUri(Uri.parse("http://evil.example/search/Silo")));
        assertTrue(VoiceSearchHelper.isSearchHandoffAction("com.google.android.gms.actions.SEARCH_ACTION"));
        assertFalse(VoiceSearchHelper.isSearchHandoffAction("android.intent.action.MAIN"));
        assertEquals("Silo", VoiceSearchHelper.extractQueryFromSuggestUri(Uri.parse("aura://search/Silo")));
        verifyInAppVoiceFastPathAndJsonParsing();
    }

    private void verifyInAppVoiceFastPathAndJsonParsing() {
        assertTrue(InAppVoiceRouter.isGoHomePhrase("go home"));
        assertTrue(InAppVoiceRouter.isControlPhrase("pause video"));
        assertEquals(InAppVoiceRouter.VoiceAction.COMPANION,
            InAppVoiceRouter.classifyDeterministicFastPath("catch me up", "Netflix").action);
        assertEquals(InAppVoiceRouter.VoiceAction.GO_HOME,
            InAppVoiceRouter.classifyDeterministicFastPath("go to launcher", "Netflix").action);
        InAppVoiceRouter.VoiceDecision dec = InAppVoiceRouter.parseLlmDecisionJson(
            "{\"intent\":\"SEARCH\",\"query\":\"Dark Matter\"}", "find dark matter", "Apple TV+");
        assertEquals(InAppVoiceRouter.VoiceAction.IN_APP_SEARCH, dec.action);
        assertEquals("Dark Matter", dec.cleanQuery);
    }

    @Test
    public void testCompanionPromptFormattingSrtTimestampsAndResourceHelpers() throws Exception {
        assertEquals(3661, SubtitleAndGuideStore.parseSrtTimestampSec("01:01:01,500"));
        assertEquals("Season 1, Episode 2 recap.", ShowCompanionAgent.humanizeEpisodeStitch("[CTX] Premise: S1E2 recap."));
        JSONArray cast = new JSONArray().put(new JSONObject().put("actor", "Tom Hardy").put("character", "Harry"));
        assertEquals("Tom Hardy as Harry", ShowCompanionAgent.formatSceneActorsReadable(cast));
        String prompt = ShowCompanionAgent.buildSimpleSummarizePrompt("catch me up", ShowCompanionAgent.LENS_FULL_RECAP,
            new JSONObject().put("title", "MobLand").put("season", 1).put("episode", 2).put("position_sec", 600),
            new JSONObject(), new JSONArray(), cast);
        assertTrue(prompt.contains("MobLand") && prompt.contains("Season 1, Episode 2"));
        verifyResourceAndSessionFriendlyNames();
    }

    private void verifyResourceAndSessionFriendlyNames() {
        assertEquals("Netflix", SessionInspector.friendlyApp("com.netflix.ninja"));
        assertEquals("ZEE5", SessionInspector.friendlyApp("com.graymatrix.did"));
        assertTrue(SystemResourceMonitor.estimatePackageRamMb("com.netflix.ninja", "com.netflix.ninja") >= 100);
        assertEquals("Prime Video", SystemResourceMonitor.friendlyProcessName("com.amazon.amazonvideo.livingroom"));
    }

    @Test
    public void testEngagementWeightAllBranchesAndTasteDnaSummary() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        Map<String, Integer> ratings = new HashMap<>();
        ratings.put("lovedshow", 9);
        ratings.put("hatedshow", 3);
        assertEquals(3.0, TasteIntelligenceEngine.computeEngagementWeight(new JSONObject().put("title", "Loved Show"), now, ratings), 0.01);
        assertEquals(-2.0, TasteIntelligenceEngine.computeEngagementWeight(new JSONObject().put("title", "Hated Show"), now, ratings), 0.01);
        assertEquals(2.8, TasteIntelligenceEngine.computeEngagementWeight(
            new JSONObject().put("title", "Done Film").put("position_sec", 5400).put("duration_sec", 6000), now, ratings), 0.01);
        assertEquals(-1.2, TasteIntelligenceEngine.computeEngagementWeight(
            new JSONObject().put("title", "Bounce").put("position_sec", 120).put("duration_sec", 3600).put("updated_at", now - 9L * 86400L), now, ratings), 0.01);
        assertFalse(TasteIntelligenceEngine.detectCurrentTimeContext().isEmpty());
    }
}
