package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * LLM-First Taste-Lane Recommendation Engine for Aura TV.
 * Curates 24 elite shows & movies across 4 intentional lanes (Core Thrillers/Crime/Espionage,
 * Mind-Bending Sci-Fi/Mystery, Smart Comedies, Fresh 2024-2026 Prestige Drops) spanning 2012-2026,
 * verifies real flatrate OTT availability via JustWatch, enforces a strict IMDb/vote quality floor,
 * and applies a deterministic genre-balance guard so no single genre hijacks the slate.
 */
public final class TasteCuratedSlateEngine {

    public static final int CLASSIC_MIN_YEAR = 2012;
    public static final int REFRESH_COOLDOWN_ROUNDS = 4;
    private static final long REFRESH_COOLDOWN_TTL_SEC = 48L * 3600L;
    private static final ExecutorService VERIFY_POOL = AuraExecutors.io();

    private TasteCuratedSlateEngine() {}

    public static List<JSONObject> curateAndVerifyElitePicks(
            AiRecommender ai, AuraDb db, String profileId, UserTasteProfile taste,
            Set<String> seenKeys, int currentYear) {
        if (ai == null || db == null || !ai.hasGeminiApiKey()) return new ArrayList<>();
        int epoch = ImpressionTracker.getCurrentEpoch(db, profileId);
        Set<String> cooldownKeys = collectActiveRefreshCooldownKeys(db, epoch, System.currentTimeMillis() / 1000L);
        seenKeys.addAll(cooldownKeys);
        String prompt = buildCuratedLanesPrompt(db, profileId, currentYear, cooldownKeys);
        JSONArray picks = parseCuratedPicksArray(ai.callGeminiJsonFast(prompt));
        if (picks.length() == 0) return new ArrayList<>();
        return verifyPicksOnJustWatch(db, picks, taste, seenKeys, currentYear);
    }

    public static Set<String> collectActiveRefreshCooldownKeys(AuraDb db, int currentEpoch, long nowSec) {
        return collectCooldownKeysFromMap(ImpressionTracker.loadActiveImpressions(db), currentEpoch, nowSec);
    }

    public static Set<String> collectCooldownKeysFromMap(
            java.util.Map<String, ImpressionTracker.ImpressionRecord> map, int currentEpoch, long nowSec) {
        Set<String> out = new HashSet<>();
        if (map == null) return out;
        for (java.util.Map.Entry<String, ImpressionTracker.ImpressionRecord> e : map.entrySet()) {
            ImpressionTracker.ImpressionRecord rec = e.getValue();
            boolean withinRounds = (currentEpoch - rec.lastEpoch) < REFRESH_COOLDOWN_ROUNDS;
            boolean withinTime = rec.lastShownAt <= 0L || (nowSec - rec.lastShownAt) < REFRESH_COOLDOWN_TTL_SEC;
            if (withinRounds && withinTime) out.add(e.getKey());
        }
        return out;
    }

    public static boolean passesEliteQualityGate(JSONObject item, int currentYear) {
        if (item == null || item.optString("poster_url", "").isEmpty()) return false;
        int year = item.optInt("year", 0);
        if (year < CLASSIC_MIN_YEAR || year > currentYear) return false;
        double imdb = item.optDouble("imdb_raw", item.optDouble("imdb_score", 0.0));
        int votes = item.optInt("imdb_votes", 0);
        boolean isFreshDrop = year >= (currentYear - 1);
        return isFreshDrop ? (imdb >= 7.3 && votes >= 2000) : (imdb >= 7.5 && votes >= 10000);
    }

    public static List<JSONObject> enforceTasteBalance(List<JSONObject> ranked, int targetSize, int maxComedies) {
        List<JSONObject> out = new ArrayList<>();
        List<JSONObject> deferred = new ArrayList<>();
        int[] cmy = new int[]{0};
        for (JSONObject item : ranked) {
            if (out.size() >= targetSize) break;
            routeBalancedItem(item, maxComedies, cmy, out, deferred);
        }
        for (JSONObject c : deferred) if (out.size() < targetSize) out.add(c);
        return out;
    }

    private static void routeBalancedItem(
            JSONObject item, int maxComedies, int[] cmy, List<JSONObject> out, List<JSONObject> deferred) {
        if (!isPureComedyPick(item)) { out.add(item); return; }
        if (cmy[0] < maxComedies) { out.add(item); cmy[0]++; }
        else deferred.add(item);
    }

    static boolean isPureComedyPick(JSONObject item) {
        String g = (item.optString("genre_codes", "") + " " + item.optString("genres", "")).toLowerCase(Locale.US);
        boolean hasComedy = g.contains("cmy") || g.contains("comedy");
        boolean hasThrillerOrSciFi = g.contains("trl") || g.contains("scf") || g.contains("thriller") || g.contains("sci-fi");
        return hasComedy && !hasThrillerOrSciFi;
    }

    private static List<JSONObject> verifyPicksOnJustWatch(
            AuraDb db, JSONArray picks, UserTasteProfile taste, Set<String> seenKeys, int currentYear) {
        List<JSONObject> verified = Collections.synchronizedList(new ArrayList<>());
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < picks.length() && i < 26; i++) {
            final int rank = i;
            final JSONObject pick = picks.optJSONObject(i);
            if (pick != null) futures.add(VERIFY_POOL.submit(() -> verifySinglePick(db, pick, rank, taste, seenKeys, currentYear, verified)));
        }
        awaitFuturesBounded(futures, 7000L);
        verified.sort((a, b) -> Integer.compare(a.optInt("llm_rank", 99), b.optInt("llm_rank", 99)));
        return verified;
    }

    private static void awaitFuturesBounded(List<java.util.concurrent.Future<?>> futures, long budgetMs) {
        long deadline = System.currentTimeMillis() + budgetMs;
        for (java.util.concurrent.Future<?> f : futures) {
            long rem = Math.max(50L, deadline - System.currentTimeMillis());
            try { f.get(rem, TimeUnit.MILLISECONDS); } catch (Exception ignored) { f.cancel(true); }
        }
    }

    private static void verifySinglePick(
            AuraDb db, JSONObject pick, int rank, UserTasteProfile taste, Set<String> seenKeys,
            int currentYear, List<JSONObject> out) {
        String title = pick.optString("title", "").trim();
        if (title.isEmpty() || seenKeys.contains(AuraDb.normalizeTitleKey(title))) return;
        JSONObject hit = lookupCachedOrLiveJustWatch(db, title);
        if (hit == null || !hit.optBoolean("has_flatrate_offer", false)) return;
        if (!passesEliteQualityGate(hit, currentYear)) return;
        if (CandidateValidator.isUnwantedAnimation(hit.optString("genre_codes", ""), taste.isAnimeEnabled())) return;
        if (!seenKeys.add(AuraDb.normalizeTitleKey(hit.optString("title", title)))) return;
        tagVerifiedCuratedHit(hit, pick, rank, out);
    }

    private static JSONObject lookupCachedOrLiveJustWatch(AuraDb db, String title) {
        String cacheKey = "jw_imdb_v6:" + title.toLowerCase(Locale.US);
        String raw = db != null ? db.getCachedGti(cacheKey) : null;
        if (raw != null && !raw.isEmpty()) {
            try { return new JSONObject(raw); } catch (Exception ignored) {}
        }
        JSONObject hit = JustWatchGraph.lookupTitle(title);
        if (hit != null && db != null && hit.optBoolean("has_flatrate_offer", false)) {
            db.saveCachedGti(cacheKey, hit.toString());
        }
        return hit;
    }

    private static void tagVerifiedCuratedHit(JSONObject hit, JSONObject pick, int rank, List<JSONObject> out) {
        try {
            hit.put("source_channel", "llm_planned").put("llm_rank", rank).put("anchor_weight", 1.35);
            String reason = pick.optString("reason", "").trim();
            if (!reason.isEmpty()) hit.put("reason", reason);
            hit.put("imdb_score", hit.optDouble("imdb_raw", hit.optDouble("imdb_score", 0.0)));
            out.add(hit);
        } catch (Exception ignored) {}
    }

    private static String buildCuratedLanesPrompt(
            AuraDb db, String profileId, int currentYear, Set<String> cooldownKeys) {
        String mem = UserMemoryStore.formatMemoriesForPrompt(db, profileId);
        List<JSONObject> liveTrending = LiveTrendingCollector.collectQualityTrending(db, profileId, currentYear, 16);
        List<String> avoidShown = buildAvoidShownList(db, profileId, cooldownKeys);
        return "You are Aura's Elite TV & Film Curator for " + RegionProviderRegistry.getRegionDisplayName(db)
            + " (Year: " + currentYear + ").\n"
            + "USER LOVED / WATCHED / RATED FAVORITES: " + UserEngagementLedger.getTopLovedTitles(db, profileId, 10) + "\n"
            + "USER DISLIKED / DROPPED (AVOID): " + UserEngagementLedger.getDislikedOrDroppedTitles(db, profileId, 6) + "\n"
            + "LONG-TERM MEMORY & RULES: " + (mem.isEmpty() ? "None" : mem) + "\n"
            + "LIVE TRENDING ACROSS ALL OTT APPS RIGHT NOW: " + LiveTrendingCollector.formatTrendingForPrompt(liveTrending) + "\n"
            + "STRICTLY DO NOT REPEAT (SHOWN IN RECENT REFRESHES — PICK COMPLETELY NEW SHOWS): " + avoidShown + "\n"
            + buildCuratedLanesTaskRules(currentYear);
    }

    private static List<String> buildAvoidShownList(AuraDb db, String profileId, Set<String> cooldownKeys) {
        List<String> out = new ArrayList<>();
        Set<String> seenNorm = new HashSet<>();
        JSONArray prev = db.getProfileRecommendations(profileId);
        for (int i = 0; i < prev.length() && out.size() < 24; i++) {
            JSONObject o = prev.optJSONObject(i);
            String t = o != null ? o.optString("title", "").trim() : "";
            if (!t.isEmpty() && seenNorm.add(AuraDb.normalizeTitleKey(t))) out.add(t);
        }
        for (String k : cooldownKeys) if (out.size() < 40 && seenNorm.add(k)) out.add(k);
        return out;
    }

    private static String buildCuratedLanesTaskRules(int currentYear) {
        return "TASK: Curate 24 genuinely acclaimed, high-IMDb (7.5+) TV series & movies available on major streaming apps "
            + "(Netflix, Prime Video, Apple TV+, Disney+ Hotstar, ZEE5) spanning 2012-" + currentYear + ":\n"
            + "- 4-5 BEST TASTE-MATCHED PICKS FROM 'LIVE TRENDING ACROSS ALL OTT APPS RIGHT NOW' (ignore trending junk/soaps/kids)\n"
            + "- 9 Core High-Stakes Thrillers / Crime / Espionage (matching their loved favorites)\n"
            + "- 6 Mind-Bending Sci-Fi / Mystery\n"
            + "- 3-4 Smart Comedies / Sitcoms (witty, high-rated, never slapstick)\n"
            + "STRICT RULES: NEVER include watched/disliked titles, kids cartoons, anime, soap operas, or obscure low-vote filler. "
            + "Return ONLY JSON: {\"picks\":[{\"title\":\"Exact Official Title\",\"reason\":\"12-16 word reason tying it to one of their favorites or why it is trending now\"}]}.";
    }

    private static JSONArray parseCuratedPicksArray(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) return new JSONArray();
        try {
            JSONArray arr = new JSONObject(rawJson).optJSONArray("picks");
            return arr != null ? arr : new JSONArray();
        } catch (Exception e) { return new JSONArray(); }
    }
}
