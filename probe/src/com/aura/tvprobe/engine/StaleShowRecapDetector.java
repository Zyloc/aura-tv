package com.aura.tvprobe;

import org.json.JSONObject;

/**
 * Detects stale in-progress series (>= 7 days since last watched) so Aura can
 * proactively offer a 15-second spoiler-free "Previously On..." recap pill.
 */
public final class StaleShowRecapDetector {

    public static final long STALE_THRESHOLD_DAYS = 7L;
    private static final long SECONDS_PER_DAY = 86_400L;

    private StaleShowRecapDetector() {}

    public static boolean isStaleForRecap(JSONObject histItem, long nowSec) {
        if (histItem == null || histItem.optBoolean("watched", false)) return false;
        long ageDays = computeAgeDays(histItem, nowSec);
        boolean hasProgress = histItem.optInt("episode", 0) >= 2 || histItem.optInt("position_sec", 0) >= 300;
        return ageDays >= STALE_THRESHOLD_DAYS && hasProgress;
    }

    public static long computeAgeDays(JSONObject histItem, long nowSec) {
        if (histItem == null) return 0L;
        long updated = histItem.optLong("updated_at", 0L);
        if (updated <= 0L || updated >= nowSec) return 0L;
        return (nowSec - updated) / SECONDS_PER_DAY;
    }

    public static String buildRecapBadge(JSONObject histItem, long nowSec) {
        if (!isStaleForRecap(histItem, nowSec)) return "";
        long days = computeAgeDays(histItem, nowSec);
        int s = histItem.optInt("season", 1);
        int e = histItem.optInt("episode", 1);
        return days + " DAYS AGO \u00B7 S" + s + "E" + e + " \u00B7 15s RECAP READY";
    }
}
