package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Unified Taste Intelligence Engine providing:
 * 1. Engagement weighting (binge vs. bounce/abandon)
 * 2. One-click "Not Interested" persistence + implicit skip penalties
 * 3. Household taste separation (regional vs. prestige thriller/sci-fi)
 * 4. Structured persistent Taste DNA for LLM steering
 * 5. Time-of-day & 1-tap Mood local re-ranking (<1ms, zero LLM wait).
 */
public final class TasteIntelligenceEngine {

    public static final String MOOD_ALL          = "ALL";
    public static final String MOOD_MIND_BENDING = "MIND_BENDING";
    public static final String MOOD_LIGHT_SMART  = "LIGHT_SMART";
    public static final String MOOD_EDGE_OF_SEAT = "EDGE_OF_SEAT";
    public static final String MOOD_UNDER_2H     = "UNDER_2H";

    public static final String CTX_LATE_NIGHT    = "LATE_NIGHT";
    public static final String CTX_WEEKEND_BINGE = "WEEKEND_BINGE";
    public static final String CTX_DAYTIME       = "DAYTIME";

    private static final long ABANDON_AGE_SEC = 7L * 86400L;

    private TasteIntelligenceEngine() {}

    // ── 1. Engagement Weighting (Binge vs Bounce) ────────────────────────

    public static double computeEngagementWeight(JSONObject histItem, long nowSec, Map<String, Integer> ratings) {
        if (histItem == null) return 0.0;
        String key = extractCanonicalKey(histItem);
        int rating = ratings != null ? ratings.getOrDefault(key, 0) : 0;
        if (rating >= 8) return 3.0;
        if (rating > 0 && rating <= 4) return -2.0;
        if (histItem.optBoolean("watched", false) || histItem.optInt("episode", 0) >= 2) return 2.6;
        return computeProgressEngagementWeight(histItem, nowSec);
    }

    private static double computeProgressEngagementWeight(JSONObject item, long nowSec) {
        int pos = item.optInt("position_sec", 0);
        int dur = item.optInt("duration_sec", 0);
        if (pos <= 0 || dur <= 0) return 1.0;
        int pct = (pos * 100) / dur;
        if (pct >= 85) return 2.8;
        if (pct >= 20) return 1.5;
        long age = nowSec - item.optLong("updated_at", nowSec);
        return (pct < 15 && age >= ABANDON_AGE_SEC) ? -1.2 : 0.7;
    }

    // ── 2. "Not Interested" Feedback & Implicit Skip Learning ────────────

    public static void recordNotInterested(AuraDb db, String profileId, JSONObject item) {
        if (db == null || item == null) return;
        String key = extractCanonicalKey(item);
        if (key.isEmpty()) return;
        JSONArray list = loadDismissedArray(db, profileId);
        appendOrReplaceDismissed(list, key, item);
        db.setSetting("dismissed_items_" + profileId, list.toString());
        buildAndPersistTasteDna(db, profileId);
    }

    public static void clearDismissedItems(AuraDb db, String profileId) {
        if (db == null) return;
        db.setSetting("dismissed_items_" + profileId, "[]");
        buildAndPersistTasteDna(db, profileId);
    }

    private static void appendOrReplaceDismissed(JSONArray list, String key, JSONObject item) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject cur = list.optJSONObject(i);
            if (cur != null && key.equals(cur.optString("key"))) return;
        }
        try { list.put(buildDismissedEntry(key, item)); } catch (Exception ignored) {}
    }

    private static JSONObject buildDismissedEntry(String key, JSONObject item) throws Exception {
        return new JSONObject().put("key", key).put("title", item.optString("title", ""))
            .put("genre_codes", item.optString("genre_codes", ""))
            .put("seed_anchor", item.optString("seed_anchor", ""))
            .put("dismissed_at", System.currentTimeMillis() / 1000L);
    }

    public static Set<String> getDismissedTitleKeys(AuraDb db, String profileId) {
        Set<String> keys = new HashSet<>();
        JSONArray list = loadDismissedArray(db, profileId);
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && !o.optString("key").isEmpty()) keys.add(o.optString("key"));
        }
        return keys;
    }

    public static JSONArray filterOutDismissed(AuraDb db, String profileId, JSONArray raw) {
        if (raw == null) return new JSONArray();
        Set<String> dismissed = getDismissedTitleKeys(db, profileId);
        if (dismissed.isEmpty()) return raw;
        JSONArray out = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject o = raw.optJSONObject(i);
            if (o != null && !dismissed.contains(extractCanonicalKey(o))) out.put(o);
        }
        return out;
    }

    public static double computeNegativeAndSkipPenalty(
            JSONObject cand, Map<String, Double> negGenres, Set<String> negSeeds,
            Map<String, ImpressionTracker.ImpressionRecord> impressions) {
        if (cand == null) return 0.0;
        double penalty = computeDismissedAffinityPenalty(cand, negGenres, negSeeds);
        String key = extractCanonicalKey(cand);
        if (impressions != null && impressions.containsKey(key)) {
            int skips = Math.max(0, impressions.get(key).shownCount - 2);
            penalty -= Math.min(2.5, skips * 0.75);
        }
        return penalty;
    }

    private static double computeDismissedAffinityPenalty(
            JSONObject cand, Map<String, Double> negGenres, Set<String> negSeeds) {
        double pen = 0.0;
        String seed = AuraDb.normalizeTitleKey(cand.optString("seed_anchor", ""));
        if (negSeeds != null && !seed.isEmpty() && negSeeds.contains(seed)) pen -= 1.8;
        if (negGenres != null && !negGenres.isEmpty()) {
            pen -= sumMatchingGenrePenalties(cand.optString("genre_codes", ""), negGenres);
        }
        return Math.max(-4.0, pen);
    }

    private static double sumMatchingGenrePenalties(String gCodes, Map<String, Double> negGenres) {
        double sum = 0.0;
        for (String g : gCodes.toLowerCase(Locale.US).split(",")) {
            sum += negGenres.getOrDefault(g.trim(), 0.0);
        }
        return Math.min(2.4, sum);
    }

    public static Map<String, Double> buildNegativeGenrePenalties(AuraDb db, String profileId) {
        Map<String, Double> map = new HashMap<>();
        JSONArray list = loadDismissedArray(db, profileId);
        for (int i = 0; i < list.length(); i++) {
            addGenreWeights(map, list.optJSONObject(i), 0.7);
        }
        return map;
    }

    public static Set<String> buildNegativeSeedSet(AuraDb db, String profileId) {
        Set<String> seeds = new HashSet<>();
        JSONArray list = loadDismissedArray(db, profileId);
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            String s = o != null ? AuraDb.normalizeTitleKey(o.optString("seed_anchor", "")) : "";
            if (!s.isEmpty()) seeds.add(s);
        }
        return seeds;
    }

    private static void addGenreWeights(Map<String, Double> map, JSONObject o, double delta) {
        if (o == null) return;
        for (String g : o.optString("genre_codes", "").toLowerCase(Locale.US).split(",")) {
            String k = g.trim();
            if (!k.isEmpty()) map.put(k, Math.min(2.1, map.getOrDefault(k, 0.0) + delta));
        }
    }

    // ── 3 & 4. Structured Persistent Taste DNA ───────────────────────────

    public static JSONObject buildAndPersistTasteDna(AuraDb db, String profileId) {
        JSONObject dna = new JSONObject();
        if (db == null) return dna;
        try {
            populateTasteDnaFields(db, profileId, dna);
            db.setSetting("taste_dna_" + profileId, dna.toString());
            AutoMemoryDistiller.distillAndPersist(db, profileId);
        } catch (Exception ignored) {}
        return dna;
    }

    private static void populateTasteDnaFields(AuraDb db, String profileId, JSONObject dna) throws Exception {
        long nowSec = System.currentTimeMillis() / 1000L;
        Map<String, Integer> ratings = db.getUserRatingsMap();
        List<String> loved = new ArrayList<>();
        List<String> bounced = new ArrayList<>();
        classifyHistoryByEngagement(db.listHistory(profileId), nowSec, ratings, loved, bounced);
        dna.put("loved_anchors", toJsonArray(loved, 8));
        dna.put("bounced_anchors", toJsonArray(bounced, 6));
        dna.put("avoid_genres", toJsonArray(new ArrayList<>(buildNegativeGenrePenalties(db, profileId).keySet()), 6));
        dna.put("dismissed_count", loadDismissedArray(db, profileId).length());
        dna.put("updated_at", nowSec);
    }

    private static void classifyHistoryByEngagement(
            JSONArray hist, long nowSec, Map<String, Integer> ratings, List<String> loved, List<String> bounced) {
        for (int i = 0; i < hist.length(); i++) {
            JSONObject h = hist.optJSONObject(i);
            String title = extractDisplayTitle(h);
            if (title.isEmpty()) continue;
            double w = computeEngagementWeight(h, nowSec, ratings);
            if (w >= 2.0 && !loved.contains(title)) loved.add(title);
            else if (w < 0.0 && !bounced.contains(title)) bounced.add(title);
        }
    }

    public static String getTasteDnaPromptSummary(AuraDb db, String profileId) {
        if (db == null) return "";
        String raw = db.getSetting("taste_dna_" + profileId, "");
        JSONObject dna = parseOrBuildDna(db, profileId, raw);
        String mem = UserMemoryStore.formatMemoriesForPrompt(db, profileId);
        return "Loved/Binged: " + dna.optJSONArray("loved_anchors")
            + " | Bounced/Avoid: " + dna.optJSONArray("bounced_anchors")
            + " | Avoid Genres: " + dna.optJSONArray("avoid_genres")
            + (mem.isEmpty() ? "" : (" | Long-Term Rules: " + mem));
    }

    private static JSONObject parseOrBuildDna(AuraDb db, String profileId, String raw) {
        if (raw != null && !raw.isEmpty()) {
            try { return new JSONObject(raw); } catch (Exception ignored) {}
        }
        return buildAndPersistTasteDna(db, profileId);
    }

    // ── 5. Time-of-Day & Instant 1-Tap Mood Re-Ranking (<1ms) ────────────

    public static String detectCurrentTimeContext() {
        Calendar cal = Calendar.getInstance();
        return detectTimeContext(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.DAY_OF_WEEK));
    }

    public static String detectTimeContext(int hourOfDay, int dayOfWeek) {
        boolean isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)
            || (dayOfWeek == Calendar.FRIDAY && hourOfDay >= 18);
        if (isWeekend) return CTX_WEEKEND_BINGE;
        if (hourOfDay >= 22 || hourOfDay < 5) return CTX_LATE_NIGHT;
        return CTX_DAYTIME;
    }

    public static List<JSONObject> rerankForMoodAndContext(
            List<JSONObject> items, String moodKey, int hourOfDay, int dayOfWeek) {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        String timeCtx = detectTimeContext(hourOfDay, dayOfWeek);
        String mood = moodKey != null ? moodKey : MOOD_ALL;
        List<JSONObject> copy = new ArrayList<>(items);
        copy.sort((a, b) -> Double.compare(
            computeContextualScore(b, mood, timeCtx), computeContextualScore(a, mood, timeCtx)));
        return copy;
    }

    private static double computeContextualScore(JSONObject item, String mood, String timeCtx) {
        double base = item.optDouble("slate_score", item.optDouble("sota_score", item.optDouble("imdb_score", 7.0)));
        return base + computeMoodBoost(item, mood) + computeTimeBoost(item, timeCtx);
    }

    private static double computeMoodBoost(JSONObject item, String mood) {
        String g = item.optString("genre_codes", "").toLowerCase(Locale.US);
        int rt = item.optInt("runtime_min", 60);
        if (MOOD_MIND_BENDING.equals(mood)) return (g.contains("scf") ? 4.5 : 0.0) + (g.contains("trl") ? 3.0 : (g.contains("scf") ? 0.0 : -1.5));
        if (MOOD_LIGHT_SMART.equals(mood)) return (g.contains("cmy") || g.contains("rma")) ? 6.0 : -1.5;
        if (MOOD_EDGE_OF_SEAT.equals(mood)) return (g.contains("crm") || g.contains("act") || g.contains("trl")) ? 6.0 : -1.5;
        if (MOOD_UNDER_2H.equals(mood)) return (rt > 0 && rt <= 115) ? 6.0 : -3.0;
        return 0.0;
    }

    private static double computeTimeBoost(JSONObject item, String timeCtx) {
        int rt = item.optInt("runtime_min", 55);
        boolean isSeries = !"Movie".equalsIgnoreCase(item.optString("type", "Series"));
        if (CTX_LATE_NIGHT.equals(timeCtx)) return (rt > 0 && rt <= 52) ? 1.4 : (rt > 130 ? -1.2 : 0.0);
        if (CTX_WEEKEND_BINGE.equals(timeCtx)) return (isSeries || rt >= 100) ? 1.1 : 0.0;
        return 0.0;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static JSONArray loadDismissedArray(AuraDb db, String profileId) {
        if (db == null) return new JSONArray();
        String raw = db.getSetting("dismissed_items_" + profileId, "[]");
        try { return new JSONArray(raw); } catch (Exception e) { return new JSONArray(); }
    }

    private static String extractCanonicalKey(JSONObject item) {
        return AuraDb.normalizeTitleKey(extractDisplayTitle(item));
    }

    private static String extractDisplayTitle(JSONObject item) {
        if (item == null) return "";
        String s = item.optString("series_title", "").trim();
        return !s.isEmpty() ? s : item.optString("title", "").trim();
    }

    private static JSONArray toJsonArray(List<String> list, int max) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size() && i < max; i++) arr.put(list.get(i));
        return arr;
    }
}
