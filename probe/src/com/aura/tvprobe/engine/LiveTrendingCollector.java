package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Aggregates real-time cross-OTT trending titles from:
 * 1. JustWatch GraphQL `sortBy: TRENDING` API across all region OTT apps
 * 2. Installed Android TV app broadcast trends (`db.getAppBroadcastTrends()`)
 * Filters out low-rated/unverified filler and already-watched titles.
 */
public final class LiveTrendingCollector {

    private LiveTrendingCollector() {}

    public static List<JSONObject> collectQualityTrending(AuraDb db, String profileId, int currentYear, int limit) {
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = initExcludedKeys(db, profileId);
        appendTrendingArray(JustWatchGraph.fetchLiveTrendingTitles(currentYear - 2, currentYear, 24), seen, out, limit);
        if (db != null && out.size() < limit) {
            appendTrendingArray(db.getAppBroadcastTrends(), seen, out, limit);
        }
        return out;
    }

    public static String formatTrendingForPrompt(List<JSONObject> trending) {
        if (trending == null || trending.isEmpty()) return "(Unavailable)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < trending.size() && i < 16; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(formatSingleTrendingItem(trending.get(i)));
        }
        return sb.toString();
    }

    private static String formatSingleTrendingItem(JSONObject t) {
        double imdb = t.optDouble("imdb_raw", t.optDouble("imdb_score", 0.0));
        return t.optString("title") + " (" + t.optInt("year", 0) + ", " + t.optString("app", "OTT")
            + ", IMDb " + String.format(Locale.US, "%.1f", imdb) + ", " + t.optString("genres", "") + ")";
    }

    static boolean passesTrendingQualityFilter(JSONObject item) {
        if (item == null || item.optString("title", "").trim().isEmpty()) return false;
        double imdb = item.optDouble("imdb_raw", item.optDouble("imdb_score", 0.0));
        int votes = item.optInt("imdb_votes", 0);
        return imdb >= 7.2 && votes >= 2000;
    }

    private static Set<String> initExcludedKeys(AuraDb db, String profileId) {
        Set<String> seen = new HashSet<>();
        if (db == null) return seen;
        seen.addAll(db.getAllExcludedTitleKeys());
        seen.addAll(db.getHistoryTitleKeys(profileId));
        seen.addAll(TasteIntelligenceEngine.getDismissedTitleKeys(db, profileId));
        return seen;
    }

    private static void appendTrendingArray(JSONArray raw, Set<String> seen, List<JSONObject> out, int limit) {
        if (raw == null) return;
        for (int i = 0; i < raw.length() && out.size() < limit; i++) {
            JSONObject item = raw.optJSONObject(i);
            if (!passesTrendingQualityFilter(item)) continue;
            String key = AuraDb.normalizeTitleKey(item.optString("title", ""));
            if (!key.isEmpty() && seen.add(key)) out.add(item);
        }
    }
}
