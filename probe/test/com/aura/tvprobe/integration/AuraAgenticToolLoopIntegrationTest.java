package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import static org.junit.Assert.*;

/**
 * End-to-End Integration Tests for Aura TV's Unified Tool-Calling Agentic System.
 */
public class AuraAgenticToolLoopIntegrationTest {

    private InMemoryAuraDb db;
    private AiRecommender ai;
    private RecordingUiBridge uiBridge;

    @Before
    public void setUp() {
        AuraConversationSession.reset();
        db = new InMemoryAuraDb();
        ai = new AiRecommender(db);
        uiBridge = new RecordingUiBridge();
        db.saveProfileRecommendations("member1", ai.getBuiltInSotaSeedCatalog("member1"));
    }

    @Test
    public void testAllFourteenToolSchemasAreValidGeminiDeclarations() {
        AuraToolRegistry reg = AuraToolRegistry.createDefaultRegistry();
        assertEquals(14, reg.size());
        JSONArray decls = reg.buildFunctionDeclarations();
        assertEquals(14, decls.length());
        for (int i = 0; i < decls.length(); i++) {
            JSONObject d = decls.optJSONObject(i);
            assertNotNull(d);
            assertFalse(d.optString("name", "").isEmpty());
            assertFalse(d.optString("description", "").isEmpty());
            assertEquals("OBJECT", d.optJSONObject("parameters").optString("type"));
        }
    }

    @Test
    public void testDiscoveryOrdinalFollowUpAndDirectPlayChain() throws Exception {
        FakeToolModelClient fakeModel = new FakeToolModelClient(
            toolTurn(new ToolModelClient.ToolCall("recommend_for_user", new JSONObject().put("mood", "MIND_BENDING"))),
            toolTurn(new ToolModelClient.ToolCall("present_visual_results",
                new JSONObject().put("headline", "Mind-Bending Picks").put("spoken_summary", "Here are your top mind-bending picks."))),
            textTurn("Here are your top mind-bending picks."),
            toolTurn(new ToolModelClient.ToolCall("explain_show_or_scene",
                new JSONObject().put("title", "#2").put("question", "Tell me more about the second one"))),
            textTurn("Severance follows office workers whose work and home memories are split."),
            toolTurn(new ToolModelClient.ToolCall("open_title_or_play", new JSONObject().put("title", "it")))
        );
        AuraAgentOrchestrator agent = new AuraAgentOrchestrator(fakeModel, AuraToolRegistry.createDefaultRegistry());
        AuraAgentOrchestrator.AgentTurnOutcome t1 = agent.runTurn("Recommend something mind-bending", newCtx(""), null);
        assertTrue(t1.hasVisualResults());
        assertEquals(1, uiBridge.presentedSessions.size());
        AuraAgentOrchestrator.AgentTurnOutcome t2 = agent.runTurn("Tell me more about the second one", newCtx(""), null);
        assertNotNull(t2.companionRecap);
        assertTrue(t2.spokenText.contains("Severance"));
        AuraAgentOrchestrator.AgentTurnOutcome t3 = agent.runTurn("Play it", newCtx(""), null);
        assertTrue(t3.actionExecutedDirectly);
        assertNotNull(uiBridge.lastPlayedItem);
        assertTrue(uiBridge.lastAutoPlay);
    }

    @Test
    public void testScreenAwareCompanionAndInAppSearch() throws Exception {
        uiBridge.screenItem = new JSONObject().put("title", "MobLand").put("season", 1).put("episode", 4);
        FakeToolModelClient fakeModel = new FakeToolModelClient(
            toolTurn(new ToolModelClient.ToolCall("get_screen_context", new JSONObject())),
            toolTurn(new ToolModelClient.ToolCall("explain_show_or_scene",
                new JSONObject().put("title", "MobLand").put("question", "Who is in this scene?"))),
            textTurn("Tom Hardy leads the scene as Harry Da Souza in MobLand."),
            toolTurn(new ToolModelClient.ToolCall("in_app_ott_search", new JSONObject().put("query", "Reacher")))
        );
        AuraAgentOrchestrator agent = new AuraAgentOrchestrator(fakeModel, AuraToolRegistry.createDefaultRegistry());
        AuraAgentOrchestrator.AgentTurnOutcome t1 = agent.runTurn("Who is this?", newCtx(""), null);
        assertTrue(t1.spokenText.contains("Tom Hardy"));
        assertEquals(2, t1.toolCallsExecuted);
        AuraAgentOrchestrator.AgentTurnOutcome t2 = agent.runTurn("Search Reacher", newCtx("com.amazon.amazonvideo.livingroom"), null);
        assertTrue(t2.actionExecutedDirectly);
        assertEquals("Reacher", uiBridge.lastInAppSearchQuery);
    }

    @Test
    public void testAmbiguityClarificationAndFollowUpResolution() throws Exception {
        FakeToolModelClient fakeModel = new FakeToolModelClient(
            toolTurn(new ToolModelClient.ToolCall("get_screen_context", new JSONObject())),
            toolTurn(new ToolModelClient.ToolCall("ask_user_clarification",
                new JSONObject().put("question", "Which show would you like me to explain?").put("pending_intent", "catch me up"))),
            toolTurn(new ToolModelClient.ToolCall("get_user_taste_and_history", new JSONObject().put("title_filter", "MobLand")),
                new ToolModelClient.ToolCall("explain_show_or_scene", new JSONObject().put("title", "MobLand").put("question", "catch me up"))),
            textTurn("In MobLand, the Harrigan family tension just escalated.")
        );
        AuraAgentOrchestrator agent = new AuraAgentOrchestrator(fakeModel, AuraToolRegistry.createDefaultRegistry());
        AuraAgentOrchestrator.AgentTurnOutcome t1 = agent.runTurn("Catch me up", newCtx(""), null);
        assertTrue(t1.isClarification);
        assertTrue(AuraConversationSession.isAwaitingClarification());
        AuraAgentOrchestrator.AgentTurnOutcome t2 = agent.runTurn("MobLand", newCtx(""), null);
        assertFalse(t2.isClarification);
        assertFalse(AuraConversationSession.isAwaitingClarification());
        assertTrue(t2.spokenText.contains("MobLand"));
    }

    @Test
    public void testProactiveTasteMutationUpdatesDbAndHome() throws Exception {
        FakeToolModelClient fakeModel = new FakeToolModelClient(
            toolTurn(
                new ToolModelClient.ToolCall("update_taste_preference",
                    new JSONObject().put("action", "not_interested").put("title", "FROM")),
                new ToolModelClient.ToolCall("update_taste_preference",
                    new JSONObject().put("action", "set_mood").put("mood", TasteIntelligenceEngine.MOOD_LIGHT_SMART))),
            textTurn("Hidden FROM and switched your Home to Light & Smart picks.")
        );
        AuraAgentOrchestrator agent = new AuraAgentOrchestrator(fakeModel, AuraToolRegistry.createDefaultRegistry());
        AuraAgentOrchestrator.AgentTurnOutcome out = agent.runTurn("Hide FROM and switch to light smart", newCtx(""), null);
        assertTrue(TasteIntelligenceEngine.getDismissedTitleKeys(db, "member1").contains("from"));
        assertEquals(TasteIntelligenceEngine.MOOD_LIGHT_SMART, uiBridge.lastMoodApplied);
        assertTrue(out.spokenText.contains("Light & Smart"));
    }

    @Test
    public void testOfflineFallbackAndGeminiResponseParser() throws Exception {
        String rawGemini = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":["
            + "{\"functionCall\":{\"name\":\"get_screen_context\",\"args\":{}}},"
            + "{\"functionCall\":{\"name\":\"get_user_taste_and_history\",\"args\":{\"title_filter\":\"Silo\"}}}"
            + "]}}]}";
        ToolModelClient.ModelTurnResult parsed = GeminiToolClient.parseGeminiResponse(rawGemini);
        assertNotNull(parsed);
        assertEquals(2, parsed.toolCalls.size());
        assertEquals("get_screen_context", parsed.toolCalls.get(0).name);
        AuraAgentOrchestrator offlineAgent = new AuraAgentOrchestrator((req, ms) -> null, AuraToolRegistry.createDefaultRegistry());
        AuraAgentOrchestrator.AgentTurnOutcome fallback = offlineAgent.runTurn("Severance", newCtx(""), null);
        assertTrue(fallback.hasVisualResults());
    }

    private AuraToolContext newCtx(String fgPkg) {
        return new AuraToolContext(null, db, ai, null, null, "member1", fgPkg, uiBridge);
    }

    private static ToolModelClient.ModelTurnResult toolTurn(ToolModelClient.ToolCall... calls) {
        return new ToolModelClient.ModelTurnResult(Arrays.asList(calls), "", new JSONObject());
    }

    private static ToolModelClient.ModelTurnResult textTurn(String text) {
        return new ToolModelClient.ModelTurnResult(Collections.emptyList(), text, new JSONObject());
    }

    private static final class FakeToolModelClient implements ToolModelClient {
        private final Queue<ModelTurnResult> turns = new ArrayDeque<>();
        FakeToolModelClient(ModelTurnResult... results) { turns.addAll(Arrays.asList(results)); }
        @Override
        public ModelTurnResult generateNextTurn(JSONObject requestPayload, int timeoutMs) {
            return turns.isEmpty() ? null : turns.poll();
        }
    }

    private static final class RecordingUiBridge implements AuraToolContext.UiActionBridge {
        JSONObject screenItem;
        JSONObject lastPlayedItem;
        boolean lastAutoPlay;
        String lastMoodApplied = "";
        String lastInAppSearchQuery = "";
        final List<JSONObject> presentedSessions = new ArrayList<>();

        @Override public JSONObject getActiveScreenItem(AuraDb d, String p) { return screenItem; }
        @Override public boolean openOrPlayTitle(JSONObject item, boolean autoPlay) {
            lastPlayedItem = item; lastAutoPlay = autoPlay; return true;
        }
        @Override public void applyMoodAndRefresh(String mood, boolean forceFresh) { lastMoodApplied = mood; }
        @Override public void presentVisualResults(JSONObject payload) { presentedSessions.add(payload); }
        @Override public boolean performInAppSearch(String pkg, String query) { lastInAppSearchQuery = query; return true; }
        @Override public void performTvControl(String command) {}
    }
}
