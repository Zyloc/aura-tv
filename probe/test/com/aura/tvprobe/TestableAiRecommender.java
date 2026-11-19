package com.aura.tvprobe;

import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Deterministic {@link AiRecommender} subclass for offline unit and pipeline tests.
 */
public class TestableAiRecommender extends AiRecommender {

    private final Deque<String> queuedLlmResponses = new ArrayDeque<>();
    private final Map<String, JSONObject> cannedMetadata = new HashMap<>();
    public String defaultLlmResponse = "";
    public String lastPromptText = "";
    public int llmCallCount = 0;

    public TestableAiRecommender(AuraDb db) {
        super(db);
    }

    public void enqueueLlmResponse(String jsonText) {
        queuedLlmResponses.addLast(jsonText);
    }

    public void putCannedMetadata(String title, JSONObject meta) {
        cannedMetadata.put(AuraDb.normalizeTitleKey(title), meta);
    }

    @Override
    public String callGeminiMultimodalJson(String promptText, String base64Jpeg) {
        llmCallCount++;
        lastPromptText = promptText != null ? promptText : "";
        if (!queuedLlmResponses.isEmpty()) return queuedLlmResponses.removeFirst();
        return defaultLlmResponse;
    }

    @Override
    public JSONObject fetchOpenMetadata(String title) {
        JSONObject hit = cannedMetadata.get(AuraDb.normalizeTitleKey(title));
        return hit != null ? cloneJson(hit) : buildSyntheticMetadata(title);
    }

    private JSONObject cloneJson(JSONObject src) {
        try { return new JSONObject(src.toString()); } catch (Exception e) { return new JSONObject(); }
    }

    private JSONObject buildSyntheticMetadata(String title) {
        JSONObject o = new JSONObject();
        try {
            o.put("title", title).put("app", "Netflix").put("badge", "Netflix").put("has_flatrate_offer", true);
            o.put("year", 2024).put("imdb_raw", 8.1).put("imdb_score", 8.1).put("imdb_votes", 25000);
            populateSyntheticArtwork(o);
        } catch (Exception ignored) {}
        return o;
    }

    private void populateSyntheticArtwork(JSONObject o) throws Exception {
        o.put("rating", "\u2605 8.1").put("genres", "Sci-Fi, Thriller").put("genre_codes", "scf,trl");
        o.put("type", "Series").put("poster_url", "https://images.justwatch.com/poster/1/s718/test.jpg");
        o.put("backdrop_url", "https://images.justwatch.com/backdrop/1/s1920/test.jpg");
    }
}
