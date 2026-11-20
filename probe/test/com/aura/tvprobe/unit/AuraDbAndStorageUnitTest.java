package com.aura.tvprobe;

import android.database.Cursor;
import android.database.MatrixCursor;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link AuraDb}, {@link ImpressionTracker}, and {@link DatabaseJanitor}.
 */
public class AuraDbAndStorageUnitTest {

    @Test
    public void testNormalizeTitleKeyAndResolvePackage() {
        assertEquals("", AuraDb.normalizeTitleKey(null));
        assertEquals("severance", AuraDb.normalizeTitleKey("Severance - Season 2 · Apple TV+"));
        assertEquals("pushpa2", AuraDb.normalizeTitleKey("Pushpa 2 (Hindi)"));
        assertEquals("com.netflix.ninja", AuraDb.resolveAppPackage("Netflix"));
        assertEquals("in.startv.hotstar", AuraDb.resolveAppPackage("JioHotstar"));
    }

    @Test
    public void testProfileLifecycleTasteAndLabels() {
        InMemoryAuraDb db = new InMemoryAuraDb();
        String pid = db.addProfile("SciFiFan", "Cyberpunk, AI");
        assertEquals(pid, db.getValidActiveProfile());
        assertEquals("SciFiFan", db.getProfileLabel(pid));
        assertEquals("Cyberpunk, AI", db.getProfileTaste(pid));
        db.setProfileTaste(pid, "Noir Mysteries");
        assertEquals("Noir Mysteries", db.getProfileTaste(pid));
        assertTrue(db.getProfileLabelsJson().has(pid));
        assertNotEquals(pid, db.deleteProfile(pid));
    }

    @Test
    public void testMutuallyExclusiveFiltersAndClear() {
        InMemoryAuraDb db = new InMemoryAuraDb();
        db.toggleFilter("m1", "movies");
        db.toggleFilter("m1", "scifi");
        assertTrue(db.isFilterActive("m1", "movies"));
        db.toggleFilter("m1", "series");
        assertFalse(db.isFilterActive("m1", "movies"));
        assertTrue(db.isFilterActive("m1", "series"));
        db.clearFilters("m1");
        assertTrue(db.getActiveFilters("m1").isEmpty());
    }

    @Test
    public void testBroadcastTrendsAndNativeLaunchUriResolution() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        JSONArray trends = new JSONArray().put(new JSONObject().put("title", "Silo").put("app", "Apple TV+").put("launch_uri", "https://tv.apple.com/silo"));
        db.saveAppBroadcastTrends(trends);
        assertEquals("Silo (Apple TV+)", db.getAppBroadcastTrendSummary(4).get(0));
        assertEquals("https://tv.apple.com/silo", db.findNativeLaunchUri("Silo - Season 1"));
        db.saveCachedGti("jw_imdb_v6:dark matter", new JSONObject().put("launch_uri", "https://tv.apple.com/dm").toString());
        assertEquals("https://tv.apple.com/dm", db.findNativeLaunchUri("Dark Matter"));
        assertEquals("", db.findNativeLaunchUri(""));
    }

    @Test
    public void testDefaultTasteGuideAndTraversalChips() {
        InMemoryAuraDb db = new InMemoryAuraDb();
        assertTrue(db.getDefaultTasteGuide().contains("IMDb 7.5+"));
        db.setDefaultTasteGuide("Nordic Noir only");
        assertEquals("Nordic Noir only", db.getDefaultTasteGuide());
        assertEquals(3, db.getTraversalChips("m1").size());
        db.saveTraversalChips("m1", new JSONArray().put("Chip A").put("Chip B"));
        assertEquals("Chip A", db.getTraversalChips("m1").get(0));
    }

    @Test
    public void testRatingsFeedbackWatchedFilterAndCacheFreshness() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        db.rateTitle(new JSONObject().put("title", "Dune Part 2"), 9);
        db.rateTitle(new JSONObject().put("title", "Bad Movie"), 3);
        String summary = db.getRatingFeedbackSummary();
        assertTrue(summary.contains("Dune Part 2 (9/10)") && summary.contains("Bad Movie (3/10)"));
        assertTrue(db.getWatchedTitleKeys().contains("dunepart"));
        verifyRecoPruningAndVoiceSession(db);
    }

    private void verifyRecoPruningAndVoiceSession(InMemoryAuraDb db) throws Exception {
        JSONArray raw = new JSONArray().put(new JSONObject().put("title", "Dune Part 2")).put(new JSONObject().put("title", "Andor"));
        db.saveProfileRecommendations("m1", raw);
        assertTrue(db.isRecoCacheFresh("m1"));
        assertEquals(1, db.getProfileRecommendations("m1").length());
        db.markTitleWatched(new JSONObject().put("title", "Andor"), true);
        db.pruneWatchedFromCachedRecos("m1");
        assertEquals(0, db.getProfileRecommendations("m1").length());
        db.saveVoiceSession("m1", new JSONObject().put("reply", "Hi").put("results", raw));
        assertEquals(0, db.getVoiceSession("m1").getJSONArray("results").length());
    }

    @Test
    public void testDynamicRotatingTasteAnchorsFiltering() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        long now = System.currentTimeMillis() / 1000L;
        seedHistoryAnchors(db, now);
        db.rateTitle(new JSONObject().put("title", "The Bear"), 9);
        List<String> anchors = db.getDynamicTasteAnchors("member1");
        assertTrue(anchors.contains("The Bear"));
        assertTrue(anchors.contains("Severance"));
        assertFalse(anchors.contains("YouTube Clip"));
    }

    private void seedHistoryAnchors(InMemoryAuraDb db, long now) throws Exception {
        db.upsertWatchItem(new JSONObject().put("media_key", "apple:sev").put("profile_id", "member1")
            .put("app_pkg", "com.apple.atve.androidtv.appletv").put("title", "Severance").put("position_sec", 900).put("duration_sec", 3000).put("updated_at", now));
        db.upsertWatchItem(new JSONObject().put("media_key", "yt:clip").put("profile_id", "member1")
            .put("app_pkg", "com.google.android.youtube.tv").put("title", "YouTube Clip").put("position_sec", 900).put("duration_sec", 3000).put("updated_at", now));
        db.upsertWatchItem(new JSONObject().put("media_key", "zee:promo").put("profile_id", "member1")
            .put("app_pkg", "com.graymatrix.did").put("title", "Big Long Movie Promo").put("position_sec", 900).put("duration_sec", 3000).put("updated_at", now));
    }

    @Test
    public void testAuraDbEnvParsingAndStaleRecoValidation() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        invokeEnvLine(db, "GEMINI_API_KEY=test_key_123");
        invokeEnvLine(db, "AURA_REGION=in");
        invokeEnvLine(db, "AURA_LANGUAGE=EN");
        invokeEnvLine(db, "AURA_PROFILE_VIP_NAME=Alex");
        invokeEnvLine(db, "AURA_PROFILE_VIP_TASTE=Sci-Fi");
        assertEquals("IN", db.getSetting("region_country", ""));
        assertEquals("Alex", db.getSetting("profile_name_vip", ""));
        assertTrue(invokeHasStaleReco(db, new JSONArray(), new HashSet<>()));
    }

    private void invokeEnvLine(AuraDb db, String line) throws Exception {
        Method m = AuraDb.class.getDeclaredMethod("applySingleEnvLine", String.class);
        m.setAccessible(true);
        m.invoke(db, line);
    }

    private boolean invokeHasStaleReco(AuraDb db, JSONArray arr, Set<String> keys) throws Exception {
        Method m = AuraDb.class.getDeclaredMethod("hasStaleOrWatchedReco", JSONArray.class, Set.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(db, arr, keys);
    }

    @Test
    public void testAuraDbAndJanitorMatrixCursorParsers() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        MatrixCursor mc = new MatrixCursor(new String[]{"profile_id", "name", "interests"});
        mc.addRow(new Object[]{"p1", "Alex", "Horror"});
        mc.moveToFirst();
        Method parseProf = AuraDb.class.getDeclaredMethod("parseProfileRow", Cursor.class);
        parseProf.setAccessible(true);
        assertEquals("Alex", ((JSONObject) parseProf.invoke(db, mc)).getString("name"));
        verifyJanitorRamCursorParser();
    }

    private void verifyJanitorRamCursorParser() throws Exception {
        MatrixCursor rc = new MatrixCursor(new String[]{"ts", "trig", "ctx", "apps", "b", "a", "f", "cpu"});
        rc.addRow(new Object[]{1000L, "LOW_RAM", "Home", "Netflix", 200, 350, 150, 22});
        rc.moveToFirst();
        Method m = DatabaseJanitor.class.getDeclaredMethod("cursorToRamHistoryJson", Cursor.class);
        m.setAccessible(true);
        assertEquals(150, ((JSONObject) m.invoke(null, rc)).getInt("freed_mb"));
    }

    @Test
    public void testImpressionTrackerPenaltiesAndFiltering() throws Exception {
        InMemoryAuraDb db = new InMemoryAuraDb();
        int ep = ImpressionTracker.advanceEpoch(db, "m1");
        assertEquals(1, ep);
        assertTrue(ImpressionTracker.shouldDeleteImpression(1, 9));
        Map<String, ImpressionTracker.ImpressionRecord> hist = new HashMap<>();
        hist.put("silo", new ImpressionTracker.ImpressionRecord(1, 2, 1000L));
        assertTrue(ImpressionTracker.computeNoveltyPenalty("silo", 3, hist) < -3.0);
        assertTrue(ImpressionTracker.isRecentlyShown("silo", 3, hist));
        verifyImpressionFilteringAndSafeCalls(db, hist);
    }

    private void verifyImpressionFilteringAndSafeCalls(InMemoryAuraDb db, Map<String, ImpressionTracker.ImpressionRecord> hist) throws Exception {
        JSONArray items = new JSONArray().put(new JSONObject().put("title", "Silo")).put(new JSONObject().put("title", "Andor"));
        assertEquals(1, ImpressionTracker.filterOutRecentImpressions(items, 3, hist, 1).length());
        assertEquals(2, ImpressionTracker.filterOutRecentImpressions(items, 3, hist, 2).length());
        ImpressionTracker.recordJsonArrayImpressions(db, 3, items);
        assertTrue(ImpressionTracker.getRecentCooldownKeys(db, 3).isEmpty());
        DatabaseJanitor.maybeRunDailyJanitor(db);
        DatabaseJanitor.recordEpisodeCheckpoint(db, "Silo", 1, 2, 600, 2700);
        DatabaseJanitor.recordRamCleanupEvent(db, "MANUAL", "Home", "Netflix", 200, 340, 140, 20);
        assertEquals(0, DatabaseJanitor.listRamCleanupHistory(db, 5).length());
    }
}
