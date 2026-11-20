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
        db.upsertWatchItem(new JSONObject().put("profile_id", "member1").put("media_key", "nfx:tbbt")
            .put("title", "The Big Bang Theory").put("app_name", "Netflix").put("watched", true)
            .put("position_sec", 5600).put("duration_sec", 6400).put("updated_at", lateNightSec - 50000L));
        UserMemoryStore.saveMemory(db, "member1", "No jump scares");
        List<String> autoMem = AutoMemoryDistiller.distillAndPersist(db, "member1");
        String combined = UserMemoryStore.formatMemoriesForPrompt(db, "member1");
        assertFalse(autoMem.isEmpty());
        assertTrue(combined.contains("No jump scares"));
        assertTrue(combined.contains("The Big Bang Theory"));
        assertTrue(combined.contains("MobLand"));
        assertTrue(combined.contains("Prime Video"));
        assertTrue(combined.contains("late at night"));
    }

    @Test
    public void testUnifiedLedgerTracksRepeatNetflixAppleTvWatchedAndRatings() throws Exception {
        long t0 = 1_770_000_000L;
        // Netflix TBBT played across 3 episodes (S0 E0 same media_key, position resets)
        db.upsertWatchItem(new JSONObject().put("profile_id", "member1").put("media_key", "nfx:tbbt")
            .put("title", "The Big Bang Theory").put("app_name", "Netflix")
            .put("position_sec", 1250).put("duration_sec", 1320).put("updated_at", t0));
        db.upsertWatchItem(new JSONObject().put("profile_id", "member1").put("media_key", "nfx:tbbt")
            .put("title", "The Big Bang Theory").put("app_name", "Netflix")
            .put("position_sec", 1200).put("duration_sec", 1320).put("updated_at", t0 + 1800));
        db.upsertWatchItem(new JSONObject().put("profile_id", "member1").put("media_key", "nfx:tbbt")
            .put("title", "The Big Bang Theory").put("app_name", "Netflix")
            .put("position_sec", 1100).put("duration_sec", 1320).put("updated_at", t0 + 3600));
        // Standalone rated 10/10 show & low-rated 2/10 show + manually watched Apple TV+ show
        db.rateTitle(new JSONObject().put("title", "Slow Horses").put("app", "Apple TV+"), 10);
        db.rateTitle(new JSONObject().put("title", "Bad Horror Movie").put("app", "Netflix"), 2);
        db.markTitleWatched(new JSONObject().put("title", "Dark Matter").put("app", "Apple TV+"), true);

        List<String> loved = UserEngagementLedger.getTopLovedTitles(db, "member1", 8);
        List<String> disliked = UserEngagementLedger.getDislikedOrDroppedTitles(db, "member1", 4);
        List<String> anchors = db.getRotatingTasteAnchors("member1", 0);
        assertTrue("Rated 10/10 must be in loved ledger", loved.contains("Slow Horses"));
        assertTrue("Repeat Netflix TBBT must be in loved ledger", loved.contains("The Big Bang Theory"));
        assertTrue("Manually watched Dark Matter must be in loved ledger", loved.contains("Dark Matter"));
        assertTrue("Rated 2/10 must be in disliked ledger", disliked.contains("Bad Horror Movie"));
        assertFalse("Rated 2/10 must never be in loved ledger", loved.contains("Bad Horror Movie"));
        assertTrue("Recommendation anchors must include Netflix/rated/watched titles",
            anchors.contains("Slow Horses") || anchors.contains("The Big Bang Theory") || anchors.contains("Dark Matter"));
    }

    @Test
    public void testTasteCuratedSlateQualityFloorAndComedyBalanceGuard() throws Exception {
        JSONObject classic = new JSONObject().put("title", "Mr. Robot").put("year", 2015)
            .put("imdb_raw", 8.5).put("imdb_votes", 420000).put("poster_url", "https://img/mr.jpg");
        JSONObject freshHit = new JSONObject().put("title", "Dept. Q").put("year", 2025)
            .put("imdb_raw", 8.1).put("imdb_votes", 15000).put("poster_url", "https://img/dq.jpg");
        JSONObject filler = new JSONObject().put("title", "Obscure 2026 Show").put("year", 2026)
            .put("imdb_raw", 7.1).put("imdb_votes", 450).put("poster_url", "https://img/obs.jpg");
        assertTrue(TasteCuratedSlateEngine.passesEliteQualityGate(classic, 2026));
        assertTrue(TasteCuratedSlateEngine.passesEliteQualityGate(freshHit, 2026));
        assertFalse("Low-vote 2026 filler must be rejected", TasteCuratedSlateEngine.passesEliteQualityGate(filler, 2026));

        List<JSONObject> skewed = new ArrayList<>();
        for (int i = 0; i < 6; i++) skewed.add(new JSONObject().put("title", "Comedy " + i).put("genre_codes", "cmy,drm"));
        for (int i = 0; i < 6; i++) skewed.add(new JSONObject().put("title", "Thriller " + i).put("genre_codes", "crm,trl"));
        List<JSONObject> balanced = TasteCuratedSlateEngine.enforceTasteBalance(skewed, 10, 3);
        int pureComedies = 0;
        for (int i = 0; i < 8; i++) if (TasteCuratedSlateEngine.isPureComedyPick(balanced.get(i))) pureComedies++;
        assertEquals("Balance guard must cap pure comedies to 3 before deferring", 3, pureComedies);
    }

    @Test
    public void testLiveTrendingQualityFilterAndPromptFormatting() throws Exception {
        JSONObject good = new JSONObject().put("title", "The Body").put("year", 2026).put("app", "Netflix")
            .put("imdb_raw", 7.5).put("imdb_votes", 13000).put("genres", "Drama, Thriller");
        JSONObject junk = new JSONObject().put("title", "Low Rated Spam").put("year", 2026).put("app", "OTT")
            .put("imdb_raw", 5.7).put("imdb_votes", 1100);
        assertTrue(LiveTrendingCollector.passesTrendingQualityFilter(good));
        assertFalse(LiveTrendingCollector.passesTrendingQualityFilter(junk));
        String promptStr = LiveTrendingCollector.formatTrendingForPrompt(Collections.singletonList(good));
        assertTrue(promptStr.contains("The Body (2026, Netflix, IMDb 7.5"));
    }

    @Test
    public void testRefreshCooldownBlocksRepeatsAndRecyclesAfterFourRounds() {
        long nowSec = System.currentTimeMillis() / 1000L;
        java.util.Map<String, ImpressionTracker.ImpressionRecord> map = new java.util.HashMap<>();
        map.put("thefamilyman", new ImpressionTracker.ImpressionRecord(10, 1, nowSec));
        java.util.Set<String> cdNext = TasteCuratedSlateEngine.collectCooldownKeysFromMap(map, 11, nowSec);
        assertTrue("Shown title must be blocked on next refresh", cdNext.contains("thefamilyman"));
        java.util.Set<String> cdRecycledByRound = TasteCuratedSlateEngine.collectCooldownKeysFromMap(map, 14, nowSec);
        assertFalse("Shown title must recycle after 4 refreshes", cdRecycledByRound.contains("thefamilyman"));
        java.util.Set<String> cdRecycledByTime = TasteCuratedSlateEngine.collectCooldownKeysFromMap(map, 11, nowSec + (50L * 3600L));
        assertFalse("Shown title must recycle after 48 hours", cdRecycledByTime.contains("thefamilyman"));
    }

    @Test
    public void testSharedAuraExecutorsUseAutoShrinkingDaemonThreads() throws Exception {
        boolean ioDaemon = AuraExecutors.io().submit(() -> Thread.currentThread().isDaemon()).get();
        boolean serialDaemon = AuraExecutors.serial().submit(() -> Thread.currentThread().isDaemon()).get();
        assertTrue(ioDaemon);
        assertTrue(serialDaemon);
    }

    @Test
    public void testTasteCuratedPromptAdaptsRegionAppsAndAnimePreference() {
        InMemoryAuraDb db = new InMemoryAuraDb();
        db.setSetting("region_country", "US");
        UserTasteProfile animeUser = new UserTasteProfile(java.util.Collections.emptyList(), "US", "English Anime", "prestige", 1);
        String prompt = TasteCuratedSlateEngine.buildCuratedLanesPrompt(db, "member1", animeUser, 2026, new java.util.HashSet<>());
        assertTrue("US region prompt must include Max or Hulu", prompt.contains("Max") || prompt.contains("Hulu"));
        assertFalse("US region prompt must not hardcode ZEE5", prompt.contains("ZEE5"));
        assertFalse("Anime-enabled profile must not ban anime", prompt.contains("kids cartoons, anime"));
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
