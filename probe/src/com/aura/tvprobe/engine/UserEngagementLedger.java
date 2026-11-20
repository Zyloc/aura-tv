package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unified Single Source of Truth for User Engagement across ALL 4 signals:
 * 1. watch_history (cumulative total_watch_sec + play_count across Netflix, Apple TV+, Prime, Hotstar, etc.)
 * 2. manually_watched library
 * 3. user_ratings (8-10 = top loved anchor, 1-4 = strong avoid)
 * 4. dismissed_items ("Not Interested")
 */
public final class UserEngagementLedger {

    public static final class TitleEngagement {
        public final String canonicalKey;
        public final String displayTitle;
        public String app = "";
        public int totalWatchSec = 0;
        public int playCount = 0;
        public int maxEpisode = 0;
        public int userRating = 0;
        public boolean manuallyWatched = false;
        public boolean dismissed = false;
        public double bestWeight = Double.NEGATIVE_INFINITY;

        TitleEngagement(String key, String title) {
            this.canonicalKey = key;
            this.displayTitle = title;
        }

        public boolean isLoved() {
            if (dismissed || (userRating > 0 && userRating <= 4)) return false;
            return userRating >= 8 || manuallyWatched || playCount >= 2 || totalWatchSec >= 1800 || bestWeight >= 1.5;
        }

        public boolean isDislikedOrDropped() {
            boolean dropped = bestWeight > Double.NEGATIVE_INFINITY && bestWeight < 0.0;
            return dismissed || (userRating > 0 && userRating <= 4) || (dropped && !manuallyWatched && playCount < 2);
        }

        public double unifiedScore() {
            if (isDislikedOrDropped()) return -1000.0;
            double ratingBonus = userRating >= 8 ? (userRating * 1200.0) : 0.0;
            double repeatBonus = Math.max(0, playCount - 1) * 1800.0;
            double watchedBonus = manuallyWatched ? 3600.0 : 0.0;
            double w = bestWeight > Double.NEGATIVE_INFINITY ? bestWeight : 0.0;
            return totalWatchSec + ratingBonus + repeatBonus + watchedBonus + (w * 500.0);
        }
    }

    private UserEngagementLedger() {}

    public static List<TitleEngagement> buildEntries(AuraDb db, String profileId, long nowSec) {
        if (db == null) return new ArrayList<>();
        Map<String, TitleEngagement> map = new LinkedHashMap<>();
        Map<String, Integer> ratings = db.getUserRatingsMap();
        mergeWatchHistory(db.listHistory(profileId), nowSec, ratings, map);
        mergeManuallyWatched(db.listManuallyWatched(), ratings, map);
        mergeStandaloneRatings(ratings, map);
        applyDismissedFlags(TasteIntelligenceEngine.getDismissedTitleKeys(db, profileId), map);
        return sortEntries(map);
    }

    public static List<String> getTopLovedTitles(AuraDb db, String profileId, int limit) {
        List<TitleEngagement> entries = buildEntries(db, profileId, System.currentTimeMillis() / 1000L);
        List<String> out = new ArrayList<>();
        for (TitleEngagement e : entries) {
            if (out.size() >= limit) break;
            if (e.isLoved()) out.add(e.displayTitle);
        }
        return out;
    }

    public static List<String> getDislikedOrDroppedTitles(AuraDb db, String profileId, int limit) {
        List<TitleEngagement> entries = buildEntries(db, profileId, System.currentTimeMillis() / 1000L);
        List<String> out = new ArrayList<>();
        for (TitleEngagement e : entries) {
            if (out.size() >= limit) break;
            if (e.isDislikedOrDropped()) out.add(e.displayTitle);
        }
        return out;
    }

    private static void mergeWatchHistory(
            JSONArray hist, long nowSec, Map<String, Integer> ratings, Map<String, TitleEngagement> map) {
        for (int i = 0; i < hist.length(); i++) {
            JSONObject h = hist.optJSONObject(i);
            if (h != null) foldHistoryRow(h, nowSec, ratings, map);
        }
    }

    private static void foldHistoryRow(
            JSONObject h, long nowSec, Map<String, Integer> ratings, Map<String, TitleEngagement> map) {
        if (isIgnoredNoiseRow(h)) return;
        String title = extractDisplayTitle(h);
        String key = AuraDb.normalizeTitleKey(title);
        if (key.isEmpty()) return;
        TitleEngagement e = map.computeIfAbsent(key, k -> new TitleEngagement(k, title));
        accumulateRowStats(e, h, nowSec, ratings, key);
    }

    private static void accumulateRowStats(
            TitleEngagement e, JSONObject h, long nowSec, Map<String, Integer> ratings, String key) {
        int rowWatch = Math.max(h.optInt("total_watch_sec", 0), h.optInt("position_sec", 0));
        e.totalWatchSec += (rowWatch > 0 ? rowWatch : (h.optBoolean("watched", false) ? 1800 : 0));
        e.playCount += Math.max(1, h.optInt("play_count", 1));
        e.maxEpisode = Math.max(e.maxEpisode, h.optInt("episode", 0));
        e.bestWeight = Math.max(e.bestWeight, TasteIntelligenceEngine.computeEngagementWeight(h, nowSec, ratings));
        if (e.app.isEmpty()) e.app = h.optString("app_name", h.optString("app", ""));
        if (ratings != null && ratings.containsKey(key)) e.userRating = ratings.get(key);
    }

    private static boolean isIgnoredNoiseRow(JSONObject h) {
        String pkg = h.optString("app_pkg", "").toLowerCase(java.util.Locale.US);
        if (pkg.contains("youtube") || pkg.contains("stremio")) return true;
        String t = extractDisplayTitle(h).toLowerCase(java.util.Locale.US);
        return t.contains("promo") || t.contains("trailer") || t.contains("teaser");
    }

    private static void mergeManuallyWatched(
            JSONArray manual, Map<String, Integer> ratings, Map<String, TitleEngagement> map) {
        for (int i = 0; i < manual.length(); i++) {
            JSONObject m = manual.optJSONObject(i);
            if (m == null) continue;
            String title = extractDisplayTitle(m);
            String key = AuraDb.normalizeTitleKey(title);
            if (key.isEmpty()) continue;
            TitleEngagement e = map.computeIfAbsent(key, k -> new TitleEngagement(k, title));
            e.manuallyWatched = true;
            if (m.optInt("user_rating", 0) > 0) e.userRating = m.optInt("user_rating");
            else if (ratings != null && ratings.containsKey(key)) e.userRating = ratings.get(key);
        }
    }

    private static void mergeStandaloneRatings(Map<String, Integer> ratings, Map<String, TitleEngagement> map) {
        if (ratings == null) return;
        for (Map.Entry<String, Integer> entry : ratings.entrySet()) {
            String key = entry.getKey();
            int score = entry.getValue();
            if (key == null || key.isEmpty() || score <= 0) continue;
            TitleEngagement e = map.get(key);
            if (e != null) e.userRating = score;
        }
    }

    private static void applyDismissedFlags(Set<String> dismissedKeys, Map<String, TitleEngagement> map) {
        if (dismissedKeys == null) return;
        for (String key : dismissedKeys) {
            TitleEngagement e = map.get(key);
            if (e != null) e.dismissed = true;
        }
    }

    private static List<TitleEngagement> sortEntries(Map<String, TitleEngagement> map) {
        List<TitleEngagement> list = new ArrayList<>(map.values());
        list.sort((a, b) -> Double.compare(b.unifiedScore(), a.unifiedScore()));
        return list;
    }

    private static String extractDisplayTitle(JSONObject item) {
        String s = item.optString("series_title", "").trim();
        return !s.isEmpty() ? s : item.optString("title", "").trim();
    }
}
