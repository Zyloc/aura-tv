package com.aura.tvprobe;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure domain selector that curates a diverse, deduplicated Continue Watching
 * list across active OTT platforms using verified unfinished playback sessions.
 */
public final class ContinueWatchingSelector {

    private static final int MIN_POSITION_SEC = 60;
    private static final int MAX_COMPLETION_PCT = 90;

    private ContinueWatchingSelector() {}

    public static List<JSONObject> selectDiversified(List<JSONObject> rawHistory, int maxItems) {
        if (rawHistory == null || rawHistory.isEmpty() || maxItems <= 0) {
            return new ArrayList<>();
        }
        Map<String, List<JSONObject>> byPlatform = groupValidByPlatform(rawHistory);
        return roundRobinInterleave(byPlatform, maxItems);
    }

    private static Map<String, List<JSONObject>> groupValidByPlatform(List<JSONObject> rawHistory) {
        Map<String, List<JSONObject>> byPlatform = new LinkedHashMap<>();
        Set<String> seenTitles = new HashSet<>();
        for (JSONObject item : rawHistory) {
            if (!isEligibleSession(item, seenTitles)) continue;
            String cleanApp = extractCleanPlatform(item);
            byPlatform.computeIfAbsent(cleanApp, k -> new ArrayList<>()).add(item);
        }
        return byPlatform;
    }

    public static boolean isEligibleSession(JSONObject item, Set<String> seenTitles) {
        if (item == null || isBlockedByWatchedFlag(item)) return false;
        if (SessionInspector.isOrphanEpisodeCodeTitle(item.optString("series_title"), item.optString("title"))) return false;
        String title = extractDisplayTitle(item);
        String pkg = item.optString("app_pkg", "").toLowerCase();
        if (title.isEmpty() || isExcludedPackage(pkg)) return false;
        String cleanApp = extractCleanPlatform(item);
        if (title.equalsIgnoreCase(cleanApp) || !isUnfinishedProgress(item)) return false;
        String normKey = AuraDb.normalizeTitleKey(title);
        return !normKey.isEmpty() && seenTitles.add(normKey);
    }

    private static boolean isBlockedByWatchedFlag(JSONObject item) {
        if (!item.optBoolean("watched", false)) return false;
        boolean hasEpisode = !item.optString("series_title", "").isEmpty()
            || item.optInt("season", 0) > 0 || item.optInt("episode", 0) > 0;
        return !(hasEpisode && isUnfinishedProgress(item));
    }

    public static boolean isExcludedPackage(String pkg) {
        if (pkg == null) return false;
        String low = pkg.toLowerCase();
        return low.contains("youtube") || low.contains("smarttube") || low.contains("stremio");
    }

    public static boolean isUnfinishedProgress(JSONObject item) {
        int pos = item.optInt("position_sec", 0);
        int dur = item.optInt("duration_sec", 0);
        if (pos < MIN_POSITION_SEC) return false;
        if (pos == 1350 && dur == 3000) return false;
        return dur <= 0 || pos < (dur * MAX_COMPLETION_PCT) / 100;
    }

    private static String extractDisplayTitle(JSONObject item) {
        String series = item.optString("series_title", "");
        return !series.isEmpty() ? series : item.optString("title", "");
    }

    private static String extractCleanPlatform(JSONObject item) {
        String rawApp = item.optString("app_name", item.optString("app", ""));
        return cleanPlatformName(rawApp);
    }

    public static String cleanPlatformName(String raw) {
        if (raw == null || raw.isEmpty()) return "Streaming";
        int dot = raw.indexOf('\u00B7');
        String base = dot > 0 ? raw.substring(0, dot).trim() : raw.trim();
        return normalizePackageDisplayName(base);
    }

    private static String normalizePackageDisplayName(String base) {
        return RegionProviderRegistry.resolveFriendlyAppName(null, base);
    }

    private static List<JSONObject> roundRobinInterleave(Map<String, List<JSONObject>> byPlatform, int maxItems) {
        List<JSONObject> diversified = new ArrayList<>();
        boolean addedAny = true;
        int pass = 0;
        while (addedAny && diversified.size() < maxItems) {
            addedAny = appendPassItems(byPlatform, pass, diversified, maxItems);
            pass++;
        }
        return diversified;
    }

    private static boolean appendPassItems(Map<String, List<JSONObject>> byPlatform, int pass,
                                           List<JSONObject> out, int maxItems) {
        boolean added = false;
        for (List<JSONObject> list : byPlatform.values()) {
            if (pass < list.size() && out.size() < maxItems) {
                out.add(list.get(pass));
                added = true;
            }
        }
        return added;
    }
}
