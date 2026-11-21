package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Integration tests for the 3 AI-Native Pillars:
 * 1. Native Local LLM (OpenAI/Ollama compatible) payload translation & tool-call parsing
 * 2. Household Movie Night Blend (intersection ranking + mutual watched/disliked exclusion)
 * 3. Voice-Steered Home Morphing (MorphHomeMoodTool + memory persistence)
 */
public class AiNativePillarsIntegrationTest {

    @Test
    public void testLocalLlmOpenAiConversionAndToolCallParsing() throws Exception {
        JSONObject geminiReq = new JSONObject()
            .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", "Be concise."))))
            .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", "Movie night")))))
            .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", new JSONArray().put(new BlendProfilesTool().declaration()))));
        JSONObject openAi = LocalLlmClient.convertGeminiPayloadToOpenAi(geminiReq, "llama3.2");
        assertEquals("llama3.2", openAi.getString("model"));
        assertEquals(2, openAi.getJSONArray("messages").length());
        assertEquals("blend_profiles", openAi.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"));
        assertEquals("object", openAi.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters").getString("type"));
        assertEquals("http://192.168.1.20:11434/v1/chat/completions", LocalLlmClient.resolveEndpointUrl("http://192.168.1.20:11434/v1/"));
        String resp = "{\"choices\":[{\"message\":{\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"blend_profiles\",\"arguments\":\"{\\\"profile_a\\\":\\\"member1\\\",\\\"profile_b\\\":\\\"partner\\\"}\"}}]}}]}";
        ToolModelClient.ModelTurnResult turn = LocalLlmClient.parseOpenAiToolResponse(resp);
        assertNotNull(turn);
        assertTrue(turn.hasToolCalls());
        assertEquals("blend_profiles", turn.toolCalls.get(0).name);
        assertEquals("partner", turn.toolCalls.get(0).args.getString("profile_b"));
    }

    @Test
    public void testHouseholdBlendExcludesEitherWatchedAndRanksIntersectionHighest() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        db.setSetting("profile_name_member1", "Alex");
        db.setSetting("profile_name_partner", "Sam");
        db.setSetting("profile_taste_member1", "Sci-Fi Thriller");
        db.setSetting("profile_taste_partner", "Comedy Sci-Fi");
        JSONArray pool = new JSONArray()
            .put(new JSONObject().put("title", "Pure Horror").put("genre_codes", "hrr").put("imdb_score", 7.8))
            .put(new JSONObject().put("title", "Shared Sci-Fi Comedy").put("genre_codes", "scf,cmy").put("imdb_score", 8.2))
            .put(new JSONObject().put("title", "Already Watched").put("genre_codes", "scf,cmy").put("imdb_score", 9.0));
        db.saveProfileRecommendations("member1", pool);
        db.markTitleWatched(new JSONObject().put("title", "Already Watched").put("user_rating", 9).put("genre_codes", "scf,cmy"), true);
        List<JSONObject> blended = HouseholdBlendEngine.blendProfiles(db, "member1", "partner", 5);
        assertFalse(blended.isEmpty());
        assertEquals("Shared Sci-Fi Comedy", blended.get(0).getString("title"));
        assertTrue(blended.get(0).getString("reason").contains("Alex \u00D7 Sam"));
        for (JSONObject o : blended) assertNotEquals("Already Watched", o.optString("title"));
    }

    @Test
    public void testMorphHomeMoodToolAppliesMoodAndPersistsMemory() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        final String[] appliedMood = {null};
        AuraToolContext ctx = new AuraToolContext(null, db, null, null, null, "member1", "", new AuraToolContext.UiActionBridge() {
            public JSONObject getActiveScreenItem(AuraDb d, String p) { return null; }
            public boolean openOrPlayTitle(JSONObject i, boolean a) { return false; }
            public void applyMoodAndRefresh(String m, boolean f) { appliedMood[0] = m; }
            public void presentVisualResults(JSONObject s) {}
            public boolean performInAppSearch(String p, String q) { return false; }
            public void performTvControl(String c) {}
        });
        JSONObject res = new MorphHomeMoodTool().execute(new JSONObject()
            .put("mood", TasteIntelligenceEngine.MOOD_LIGHT_SMART)
            .put("remember_note", "Prefers cozy light shows this week"), ctx);
        assertEquals("morphed", res.getString("status"));
        assertEquals(TasteIntelligenceEngine.MOOD_LIGHT_SMART, appliedMood[0]);
        assertFalse(ctx.visualSpokenSummary.isEmpty());
        assertTrue(UserMemoryStore.formatMemoriesForPrompt(db, "member1").contains("cozy light shows"));
        assertTrue(AuraToolRegistry.createDefaultRegistry().size() >= 17);
    }
}
