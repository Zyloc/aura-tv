package com.aura.tvprobe;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Intent;
import android.database.MatrixCursor;
import android.net.Uri;
import android.speech.RecognizerIntent;
import android.view.KeyEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Pure-JVM unit tests for {@link QrRemoteBuilder}, {@link VoiceSearchHelper},
 * {@link AuraTtsNarrator}, {@link JustWatchGraph}, {@link RegionProviderRegistry},
 * and {@link SystemResourceMonitor}.
 */
public class UiPureLogicAndGraphParserTest {

    @Test
    public void testQrRemoteBuilderMatrixEncoderAcrossAllVersions() {
        boolean[][] v1 = QrRemoteBuilder.encode("http://mitv:8765");
        boolean[][] v2 = QrRemoteBuilder.encode("http://192.168.1.4:8765/open");
        boolean[][] v3 = QrRemoteBuilder.encode("http://192-168-1-4.sslip.io:8765/api/state");
        boolean[][] v4 = QrRemoteBuilder.encode("http://192-168-1-4.sslip.io:8765/api/state?profile=member1&refresh=1");
        assertEquals(21, v1.length);
        assertEquals(25, v2.length);
        assertEquals(29, v3.length);
        assertEquals(33, v4.length);
        assertTrue(v1[0][0] && v1[0][6] && v1[6][0] && v4[25][8]);
        assertNull(QrRemoteBuilder.encode("x".repeat(90)));
    }

    @Test
    public void testQrRemoteNetworkHelpers() throws Exception {
        assertNotNull(QrRemoteBuilder.getTvIpAddress(null));
        assertNotNull(QrRemoteBuilder.getLiveArpPeers());
        Method fmt = QrRemoteBuilder.class.getDeclaredMethod("formatIp", int.class);
        fmt.setAccessible(true);
        assertEquals("192.168.1.4", fmt.invoke(null, (4 << 24) | (1 << 16) | (168 << 8) | 192));
    }

    @Test
    public void testVoiceSearchHelperIntentProviderAndKatnissParsers() throws Exception {
        Intent rec = new Intent().putStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS, new ArrayList<>(Arrays.asList("", "  mind bending sci fi  ")));
        assertEquals("mind bending sci fi",
            VoiceSearchHelper.extractActivityResultQuery(VoiceSearchHelper.REQ_VOICE_SEARCH, Activity.RESULT_OK, rec));
        Intent searchIntent = new Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, "Severance");
        assertEquals("Severance", VoiceSearchHelper.extractAssistantSearchQuery(searchIntent));
        assertEquals(Intent.ACTION_MAIN, searchIntent.getAction());
        verifyKatnissAndSuggestionCursor();
    }

    private void verifyKatnissAndSuggestionCursor() throws Exception {
        Uri uri = Uri.parse("content://com.aura.tvprobe.search/search_suggest_query/Dune%202");
        assertEquals("Dune 2", VoiceSearchHelper.extractProviderQuery(uri, null));
        JSONArray items = new JSONArray().put(new JSONObject().put("title", "Dune: Part Two").put("app", "Max").put("year", 2024));
        MatrixCursor mc = VoiceSearchHelper.buildSuggestionsCursor("Dune 2", items);
        assertEquals(2, mc.getCount());
        assertTrue(VoiceSearchHelper.isRemoteVoiceOrSearchKey(KeyEvent.KEYCODE_VOICE_ASSIST));
        assertEquals("catch me up", VoiceSearchHelper.extractKatnissRecognizedText("state[FINAL_RESULT] recognized_speech: \"catch me up\""));
        assertEquals("play silo", VoiceSearchHelper.extractKatnissRecognizedText("setFinalRecognizedText(play silo)"));
        assertTrue(VoiceSearchHelper.isKatnissResponseTakeoverLog("LIBAS_TTS_PLAYBACK_STARTED"));
    }

    @Test
    public void testAuraTtsNarratorTextSplittingBoundsAndLifecycle() {
        assertEquals("Hello world", AuraTtsNarrator.cleanSpokenText("**Hello**  `world` \u2726"));
        List<String> sents = AuraTtsNarrator.splitIntoSentences("Mark wakes up. Helly rebels! Who is Cobel?");
        assertEquals(3, sents.size());
        assertArrayEquals(new int[]{5, 10}, AuraTtsNarrator.findWordBoundsAt("Mark wakes up", 7));
        assertArrayEquals(new int[]{0, 0}, AuraTtsNarrator.findWordBoundsAt("", 0));
        verifyNarratorSpeakAndStopCallbacks();
    }

    private void verifyNarratorSpeakAndStopCallbacks() {
        List<String> events = new ArrayList<>();
        AuraTtsNarrator.setSubtitleListener(new AuraTtsNarrator.SubtitleListener() {
            @Override public void onSentenceStart(String s) { events.add("start:" + s); }
            @Override public void onWordHighlight(String s, int ws, int we) { events.add("word"); }
            @Override public void onNarrationStop() { events.add("stop"); }
        });
        AuraTtsNarrator.speak(null, "First sentence. Second sentence.", () -> events.add("done"));
        assertFalse(AuraTtsNarrator.isSpeaking());
        AuraTtsNarrator.stop(null);
        assertTrue(events.contains("stop"));
    }

    @Test
    public void testJustWatchGraphOfflineNodeAndSpecParsers() throws Exception {
        List<String> genres = JustWatchGraph.inferGenreCodesFromText("scary space heist funny anime romance fantasy drama");
        assertTrue(genres.containsAll(Arrays.asList("scf", "crm", "hrr", "cmy", "ani", "rma", "fnt", "drm")));
        assertTrue(JustWatchGraph.isValidGenreCode("trl"));
        assertNotNull(JustWatchGraph.getCurrentDateString());
        JSONObject parsed = invokeParseNode(buildMockGraphQlNode(), "en");
        assertEquals("Netflix", parsed.optString("app"));
        assertEquals("nfx", parsed.optString("pkg_code"));
        assertTrue(parsed.optBoolean("has_flatrate_offer"));
        verifyDynamicSpecBuilderAndLookupSelector(parsed);
    }

    private JSONObject buildMockGraphQlNode() throws Exception {
        JSONObject content = buildMockGraphQlContent();
        JSONObject offer = new JSONObject().put("monetizationType", "FLATRATE")
            .put("deeplinkURL", "nflx://www.netflix.com/watch/80100172")
            .put("package", new JSONObject().put("shortName", "nfx").put("clearName", "Netflix"));
        return new JSONObject().put("objectType", "SHOW").put("content", content).put("offers", new JSONArray().put(offer));
    }

    private JSONObject buildMockGraphQlContent() throws Exception {
        return new JSONObject().put("title", "Dark").put("originalTitle", "Dark")
            .put("fullPath", "/in/tv-show/dark").put("originalReleaseYear", 2023).put("runtime", 52)
            .put("shortDescription", "Time travel mystery").put("productionCountries", new JSONArray().put("DE"))
            .put("posterUrl", "/poster/1/{profile}/{format}")
            .put("backdrops", new JSONArray().put(new JSONObject().put("backdropUrl", "/bd/1/{profile}/{format}")))
            .put("genres", new JSONArray().put(new JSONObject().put("shortName", "scf")).put(new JSONObject().put("shortName", "trl")))
            .put("scoring", new JSONObject().put("imdbScore", 8.7).put("imdbVotes", 450000).put("tmdbPopularity", 95.0))
            .put("credits", new JSONArray().put(new JSONObject().put("name", "Louis Hofmann").put("role", "ACTOR")));
    }

    private JSONObject invokeParseNode(JSONObject node, String lang) throws Exception {
        Method m = JustWatchGraph.class.getDeclaredMethod("parseNode", JSONObject.class, String.class);
        m.setAccessible(true);
        return (JSONObject) m.invoke(null, node, lang);
    }

    private void verifyDynamicSpecBuilderAndLookupSelector(JSONObject parsedNode) throws Exception {
        Method buildVars = JustWatchGraph.class.getDeclaredMethod(
            "buildDynamicSpecVars", JSONObject.class, String.class, String.class);
        buildVars.setAccessible(true);
        JSONObject spec = new JSONObject().put("genres", new JSONArray().put("sci-fi"))
            .put("packages", new JSONArray().put("nfx")).put("objectTypes", new JSONArray().put("SHOW"))
            .put("productionCountries", new JSONArray().put("KR")).put("minYear", 2022);
        JSONObject vars = (JSONObject) buildVars.invoke(null, spec, "IN", "en");
        assertEquals("IN", vars.getString("country"));
        assertTrue(vars.getJSONObject("filter").has("genres"));
        assertTrue(JustWatchGraph.isValidModernStreamingCandidate(parsedNode, 2020, 7.5));
    }

    @Test
    public void testRegionProviderRegistryAndSystemResourceMonitor() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        db.setSetting("region_country", "JP");
        db.setSetting("region_language", "en");
        assertTrue(RegionProviderRegistry.getProvidersForCountry("JP").contains("unx"));
        assertTrue(RegionProviderRegistry.getProvidersForCountry("GB").contains("bbc"));
        assertEquals("Squid Game", RegionProviderRegistry.humanizeFullPathSlug("/kr/tv-show/squid-game-2021"));
        assertTrue(RegionProviderRegistry.matchesSpokenPlatformFilter("Netflix", "thriller on netflix"));
        assertFalse(RegionProviderRegistry.matchesSpokenPlatformFilter("Prime Video", "thriller on netflix"));
        assertNotEquals(0xFF334155, RegionProviderRegistry.getPlatformColorInt("Netflix"));
        verifySystemResourceMonitorSnapshotAndBoost(db);
    }

    private void verifySystemResourceMonitorSnapshotAndBoost(InMemoryAuraDb db) throws Exception {
        RecordingContext ctx = new RecordingContext();
        SystemResourceMonitor.setAutoGuardEnabled(db, false);
        assertFalse(SystemResourceMonitor.isAutoGuardEnabled(db));
        SystemResourceMonitor.setAutoGuardEnabled(db, true);
        JSONObject snap = SystemResourceMonitor.collectTelemetrySnapshot(ctx, db);
        assertTrue(SystemResourceMonitor.formatCompactDrawerSummary(snap).contains("MB Free"));
        assertTrue(SystemResourceMonitor.executeRamBoost(ctx, db, "MANUAL", true).has("killed_count"));
        assertTrue(SystemResourceMonitor.killSinglePackage(ctx, db, "com.netflix.ninja").has("total_ram_mb"));
    }
}
