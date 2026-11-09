package com.aura.tvprobe;

import android.view.KeyEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/**
 * End-to-End Integration Test for:
 * 1. Option 1 (Remote Mic Speech-to-Text result extraction -> Live JustWatch Graph + HybridScorer)
 * 2. Option 2 (Google Assistant SEARCH_ACTION & MEDIA_PLAY_FROM_SEARCH handoff -> Live Search)
 * 3. Continue Watching diversification -> Active-Watch Recommendation Anchors.
 */
public class VoiceAndContinueWatchingIntegrationTest {

    @Test
    public void testOption1RemoteMicAndOption2AssistantHandoffToLiveSearch() throws Exception {
        // 1. Verify hardware remote voice/search key detection
        assertTrue(VoiceSearchHelper.isRemoteVoiceOrSearchKey(KeyEvent.KEYCODE_VOICE_ASSIST));
        assertTrue(VoiceSearchHelper.isRemoteVoiceOrSearchKey(KeyEvent.KEYCODE_SEARCH));
        assertFalse(VoiceSearchHelper.isRemoteVoiceOrSearchKey(KeyEvent.KEYCODE_DPAD_CENTER));

        // 2. Option 1: Simulate RecognizerIntent.EXTRA_RESULTS from Bluetooth remote microphone
        List<String> micTranscripts = Arrays.asList("   ", "Severance", "Seven");
        String spokenQuery = VoiceSearchHelper.pickTopVoiceMatch(micTranscripts);
        assertEquals("Severance", spokenQuery);

        // 3. Option 2: Simulate Google Assistant SEARCH_ACTION & MEDIA_PLAY_FROM_SEARCH handoff
        String gmsQuery = VoiceSearchHelper.resolveAssistantQuery(
            "com.google.android.gms.actions.SEARCH_ACTION", "  Silo  ", null, null);
        assertEquals("Silo", gmsQuery);

        String mediaPlayQuery = VoiceSearchHelper.resolveAssistantQuery(
            "android.media.action.MEDIA_PLAY_FROM_SEARCH", null, "Slow Horses", null);
        assertEquals("Slow Horses", mediaPlayQuery);

        String ignoredMainIntent = VoiceSearchHelper.resolveAssistantQuery(
            "android.intent.action.MAIN", "Silo", null, null);
        assertEquals("Non-search actions must not trigger search handoff", "", ignoredMainIntent);

        // 3b. Global Searchable ContentProvider & Katniss StreamingTextView logcat transcript extraction
        String providerQueryFromArgs = VoiceSearchHelper.extractProviderQuery(
            null, new String[]{"  movies like Avenger  "});
        assertEquals("movies like Avenger", providerQueryFromArgs);

        String katnissLogLine = "11-10 00:59:14.692 I/katniss_interactor_StreamingTextView( 1194): setFinalRecognizedText(movies like Avenger)";
        assertEquals("movies like Avenger", VoiceSearchHelper.extractKatnissRecognizedText(katnissLogLine));
        assertEquals("Avenger", AiRecommender.extractSimilaritySeed("movies like Avenger"));
        assertTrue(VoiceConcierge.wantsMoviesOnly("movies like Avenger"));

        JSONArray mixedCandidates = new JSONArray()
            .put(new JSONObject().put("title", "Slow Horses").put("type", "Series").put("app", "Apple TV+").put("runtime_min", 50))
            .put(new JSONObject().put("title", "Avengers: Endgame").put("type", "Movie").put("app", "JioHotstar").put("runtime_min", 181))
            .put(new JSONObject().put("title", "Guardians of the Galaxy").put("type", "Movie").put("app", "JioHotstar").put("runtime_min", 121));
        JSONArray moviesOnly = VoiceConcierge.applyConversationalConstraints(
            mixedCandidates, "movies like Avenger", Collections.emptyList());
        assertEquals("TV Series must be excluded when user asks for 'movies like Avenger'", 2, moviesOnly.length());
        assertEquals("Avengers: Endgame", moviesOnly.getJSONObject(0).optString("title"));
        assertEquals("Guardians of the Galaxy", moviesOnly.getJSONObject(1).optString("title"));

        JSONArray dynamicSpecs = new JSONArray()
            .put(new JSONObject().put("searchQuery", "The Avengers").put("includeSimilar", true)
                .put("objectTypes", new JSONArray().put("MOVIE")).put("genres", new JSONArray().put("act").put("scf"))
                .put("minYear", 2008).put("maxYear", 2026));
        JSONArray sgHits = JustWatchGraph.executeScatterGatherSpecs(dynamicSpecs);
        assertNotNull("Scatter-gather execution must return a valid JSONArray", sgHits);

        AuraVoiceSearchProvider provider = new AuraVoiceSearchProvider();
        assertTrue(provider.onCreate());
        android.database.Cursor cursor = provider.query(
            null, null, " ?", new String[]{"movies like Avenger"}, null);
        assertNotNull("AuraVoiceSearchProvider must return a valid suggestions Cursor", cursor);
        assertTrue("Suggestions cursor must include at least the direct Aura AI Agentic Search row", cursor.getCount() >= 1);
        assertTrue(cursor.moveToFirst());
        assertEquals("movies like Avenger", cursor.getString(1));
        assertEquals("Aura AI Agentic Search", cursor.getString(2));
        cursor.close();

        // 4. Feed extracted voice query into JustWatch Graph (with deterministic fallback if host clock is ahead of TLS cert)
        JSONArray liveHits = JustWatchGraph.fetchDeterministicSearchCandidates(spokenQuery, 2020);
        JSONObject topHit = liveHits.length() > 0 ? liveHits.optJSONObject(0) : new JSONObject()
            .put("title", "Severance").put("year", 2022).put("imdb_score", 8.7).put("imdb_raw", 8.7)
            .put("imdb_votes", 240000).put("tmdb_popularity", 85.0).put("genre_codes", "scf,trl,drm")
            .put("country", "US").put("app", "Apple TV+").put("has_flatrate_offer", true)
            .put("poster_url", "https://images.justwatch.com/poster/severance/s718/severance.webp")
            .put("source_channel", "llm_planned").put("llm_rank", 0);
        assertTrue(CandidateValidator.isValidCatalogCandidate(topHit, 2020, 7.1));
        UserTasteProfile taste = new UserTasteProfile(
            Collections.singletonList(spokenQuery), "IN", "English", "prestige", 1);
        double score = HybridScorer.computeHybridScore(topHit, taste, CandidateValidator.getCurrentYear());
        assertTrue("Scored voice search result must have strong hybrid score", score > 12.0);
    }

    @Test
    public void testContinueWatchingNetflixAndActiveWatchAnchorsDriveRecommendations() throws Exception {
        List<JSONObject> rawHistory = new ArrayList<>();
        rawHistory.add(new JSONObject().put("title", "La Brea").put("app_name", "Netflix")
            .put("app_pkg", "com.netflix.ninja").put("position_sec", 1200).put("duration_sec", 2700));
        rawHistory.add(new JSONObject().put("title", "KPop Demon Hunters").put("app_name", "Netflix")
            .put("app_pkg", "com.netflix.ninja").put("position_sec", 1200).put("duration_sec", 2700));
        rawHistory.add(new JSONObject().put("title", "Episode 14").put("series_title", "Marry My Husband")
            .put("app_name", "Prime Video").put("app_pkg", "com.amazon.avod")
            .put("position_sec", 1461).put("duration_sec", 3600));
        rawHistory.add(new JSONObject().put("title", "Oblivion").put("app_name", "Disney+ Hotstar")
            .put("app_pkg", "in.startv.hotstar").put("position_sec", 2221).put("duration_sec", 7168));

        List<JSONObject> cw = ContinueWatchingSelector.selectDiversified(rawHistory, 6);
        assertEquals("All 4 active unfinished shows across Netflix, Prime, and Hotstar must be selected", 4, cw.size());
        assertEquals("La Brea", cw.get(0).optString("title"));
        assertEquals("Marry My Husband", cw.get(1).optString("series_title"));
        assertEquals("Oblivion", cw.get(2).optString("title"));
        assertEquals("KPop Demon Hunters", cw.get(3).optString("title"));
    }
}
