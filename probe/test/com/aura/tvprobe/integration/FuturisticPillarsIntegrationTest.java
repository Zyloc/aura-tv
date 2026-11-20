package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Integration Tests for Pillars 1, 2, and 4:
 * - Pillar 4: True Long-Term Memory (UserMemoryStore + RememberPreferenceTool + TasteDNA injection)
 * - Pillar 1: Live Co-Watcher (ScreenVisionTool + GeminiSseStreamer + StaleShowRecapDetector)
 * - Pillar 2: Decision-Free "Tonight's 3 Picks" Hero + SurprisePlayTool
 */
public class FuturisticPillarsIntegrationTest {

    private InMemoryAuraDb db;
    private AiRecommender ai;

    @Before
    public void setUp() {
        AuraConversationSession.reset();
        db = new InMemoryAuraDb();
        ai = new AiRecommender(db);
        db.saveProfileRecommendations("member1", ai.getBuiltInSotaSeedCatalog("member1"));
    }

    @Test
    public void testLongTermMemorySaveForgetAndPromptInjection() throws Exception {
        RememberPreferenceTool tool = new RememberPreferenceTool();
        AuraToolContext ctx = new AuraToolContext(null, db, ai, null, null, "member1", "", null);
        JSONObject saved1 = tool.execute(new JSONObject().put("action", "save").put("rule", "No jump scares"), ctx);
        assertTrue(saved1.optBoolean("success"));
        tool.execute(new JSONObject().put("action", "save").put("rule", "No jump scares"), ctx);
        tool.execute(new JSONObject().put("action", "save").put("rule", "Love slow-burn British espionage"), ctx);
        assertEquals(2, UserMemoryStore.listMemories(db, "member1").size());
        String summary = TasteIntelligenceEngine.getTasteDnaPromptSummary(db, "member1");
        assertTrue(summary.contains("No jump scares"));
        assertTrue(summary.contains("Love slow-burn British espionage"));
        tool.execute(new JSONObject().put("action", "forget").put("rule", "jump scares"), ctx);
        assertEquals(1, UserMemoryStore.listMemories(db, "member1").size());
    }

    @Test
    public void testScreenVisionToolCapturesFrameAndAnswersMultimodalQuestion() throws Exception {
        AiRecommender visionAi = new AiRecommender(db) {
            @Override
            public String callGeminiMultimodalJson(String prompt, String base64Jpeg) {
                return "{\"answer\":\"That is Gary Oldman playing Jackson Lamb in Slow Horses.\"}";
            }
        };
        AuraToolContext ctx = new AuraToolContext(null, db, visionAi, null, null, "member1", "com.apple.atve.androidtv.appletv", null);
        ctx.visionFrameProvider = () -> "ZmFrZV9qcGVnX2ZyYW1l";
        JSONObject res = new ScreenVisionTool().execute(new JSONObject().put("question", "Who is that actor?"), ctx);
        assertTrue(res.optBoolean("frame_captured"));
        assertTrue(res.optString("answer").contains("Gary Oldman"));
        ctx.visionFrameProvider = () -> null;
        JSONObject missing = new ScreenVisionTool().execute(new JSONObject().put("question", "What is that?"), ctx);
        assertFalse(missing.optBoolean("frame_captured"));
    }

    @Test
    public void testGeminiSseStreamerEmitsIncrementalSentences() {
        List<String> sseLines = Arrays.asList(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Mark leads a team of office workers. \"}]}}]}",
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Their memories are split between work and home! \"}]}}]}",
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Want to start Episode 1?\"}]}}]}",
            "data: [DONE]"
        );
        List<String> emitted = new ArrayList<>();
        List<Boolean> firstFlags = new ArrayList<>();
        String full = GeminiSseStreamer.consumeSseLines(sseLines, (s, isFirst) -> {
            emitted.add(s);
            firstFlags.add(isFirst);
        });
        assertEquals(3, emitted.size());
        assertTrue(firstFlags.get(0));
        assertFalse(firstFlags.get(1));
        assertTrue(emitted.get(0).contains("Mark leads"));
        assertTrue(full.contains("Want to start Episode 1?"));
    }

    @Test
    public void testStaleShowRecapDetectorAndTonightThreePicksHero() throws Exception {
        long nowSec = 1_770_000_000L;
        JSONObject staleShow = new JSONObject()
            .put("media_key", "atp:silo").put("series_title", "Silo").put("title", "Silo")
            .put("season", 1).put("episode", 4).put("position_sec", 900).put("duration_sec", 3000)
            .put("poster_url", "https://images.justwatch.com/poster/346669772/s718/silo.jpg")
            .put("updated_at", nowSec - (12L * 86_400L));
        JSONObject recentShow = new JSONObject().put("title", "Reacher").put("episode", 2)
            .put("updated_at", nowSec - 86_400L);
        assertTrue(StaleShowRecapDetector.isStaleForRecap(staleShow, nowSec));
        assertFalse(StaleShowRecapDetector.isStaleForRecap(recentShow, nowSec));
        assertTrue(StaleShowRecapDetector.buildRecapBadge(staleShow, nowSec).contains("12 DAYS AGO"));
        staleShow.put("profile_id", "member1");
        db.upsertWatchItem(staleShow);
        List<JSONObject> recos = toList(db.getProfileRecommendations("member1"));
        List<JSONObject> picks = TonightPicksCurator.buildHeroPicks(db, "member1", recos, nowSec, TasteIntelligenceEngine.CTX_LATE_NIGHT);
        assertEquals(3, picks.size());
        assertEquals("Silo", picks.get(0).optString("title"));
        assertTrue(picks.get(0).optString("hero_concierge_tag").contains("15s RECAP READY"));
        assertTrue(picks.get(1).optString("hero_concierge_tag").contains("TOP TASTE MATCH"));
        assertTrue(picks.get(2).optString("hero_concierge_tag").contains("QUICK"));
    }

    @Test
    public void testSurprisePlayToolRespectsRuntimeAndLaunchesDirectly() throws Exception {
        final JSONObject[] launched = new JSONObject[1];
        final boolean[] autoPlayFlag = new boolean[1];
        AuraToolContext.UiActionBridge bridge = new AuraToolContext.UiActionBridge() {
            @Override public JSONObject getActiveScreenItem(AuraDb d, String p) { return null; }
            @Override public boolean openOrPlayTitle(JSONObject item, boolean autoPlay) {
                launched[0] = item; autoPlayFlag[0] = autoPlay; return true;
            }
            @Override public void applyMoodAndRefresh(String m, boolean f) {}
            @Override public void presentVisualResults(JSONObject p) {}
            @Override public boolean performInAppSearch(String pkg, String q) { return false; }
            @Override public void performTvControl(String cmd) {}
        };
        AuraToolContext ctx = new AuraToolContext(null, db, ai, null, null, "member1", "", bridge);
        JSONObject res = new SurprisePlayTool().execute(new JSONObject().put("max_runtime_min", 50), ctx);
        assertTrue(res.optBoolean("launched"));
        assertTrue(autoPlayFlag[0]);
        assertNotNull(launched[0]);
        assertTrue(launched[0].optInt("runtime_min", 0) <= 50);
    }

    @Test
    public void testAutoMemoryDistillerLearnsHabitsFromWatchHistory() throws Exception {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 22);
        long lateNightSec = cal.getTimeInMillis() / 1000L;
        db.upsertWatchItem(new JSONObject().put("profile_id", "member1").put("media_key", "prv:mobland")
            .put("series_title", "MobLand").put("title", "MobLand").put("app", "Prime Video")
            .put("season", 1).put("episode", 5).put("position_sec", 2600).put("duration_sec", 3000)
            .put("updated_at", lateNightSec));
        db.upsertWatchItem(new JSONObject().put("profile_id", "member1").put("media_key", "atp:severance")
            .put("series_title", "Severance").put("title", "Severance").put("app", "Apple TV+")
            .put("season", 1).put("episode", 4).put("position_sec", 2700).put("duration_sec", 3000)
            .put("updated_at", lateNightSec));
        UserMemoryStore.saveMemory(db, "member1", "No jump scares");
        List<String> autoMem = AutoMemoryDistiller.distillAndPersist(db, "member1");
        String combined = UserMemoryStore.formatMemoriesForPrompt(db, "member1");
        assertFalse(autoMem.isEmpty());
        assertTrue(combined.contains("No jump scares"));
        assertTrue(combined.contains("MobLand"));
        assertTrue(combined.contains("Prime Video"));
        assertTrue(combined.contains("late at night"));
    }

    private static List<JSONObject> toList(JSONArray arr) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }
}
