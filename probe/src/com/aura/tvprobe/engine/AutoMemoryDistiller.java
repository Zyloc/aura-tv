package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Automatic Behavioral Memory Distiller.
 * Synthesizes human-readable viewing habits (binge favorites, quick drops,
 * time-of-day & session length rhythm, primary OTT apps, series vs movie bias)
 * directly from AuraDb watch history and Taste DNA with zero LLM overhead.
 */
public final class AutoMemoryDistiller {

    private AutoMemoryDistiller() {}

    public static List<String> distillAndPersist(AuraDb db, String profileId) {
        if (db == null) return Collections.emptyList();
        List<String> distilled = distillFromHistory(db, profileId, System.currentTimeMillis() / 1000L);
        persistAutoMemories(db, profileId, distilled);
        return distilled;
    }

    public static List<String> distillFromHistory(AuraDb db, String profileId, long nowSec) {
        JSONArray hist = db != null ? db.listHistory(profileId) : new JSONArray();
        List<String> out = new ArrayList<>();
        if (db == null) return out;
        addIfNonEmpty(out, buildUnifiedLovedMemory(db, profileId));
        if (hist.length() > 0) {
            addIfNonEmpty(out, buildAppAffinityMemory(hist));
            addIfNonEmpty(out, buildSessionAndTimeMemory(hist));
        }
        addIfNonEmpty(out, buildUnifiedAvoidMemory(db, profileId));
        return out;
    }

    private static String buildUnifiedLovedMemory(AuraDb db, String profileId) {
        List<String> topLoved = UserEngagementLedger.getTopLovedTitles(db, profileId, 8);
        return topLoved.isEmpty() ? "" : ("Most-watched, rated, and binged titles: " + String.join(", ", topLoved));
    }

    private static String buildUnifiedAvoidMemory(AuraDb db, String profileId) {
        List<String> avoid = UserEngagementLedger.getDislikedOrDroppedTitles(db, profileId, 4);
        return avoid.isEmpty() ? "" : ("Quickly drops or dislikes titles like " + String.join(", ", avoid));
    }

    private static String buildAppAffinityMemory(JSONArray hist) {
        Map<String, Integer> counts = countByApp(hist);
        List<String> topApps = topKeysByCount(counts, 3);
        return topApps.isEmpty() ? "" : ("Primarily streams on " + String.join(", ", topApps));
    }

    private static Map<String, Integer> countByApp(JSONArray hist) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < hist.length(); i++) {
            JSONObject h = hist.optJSONObject(i);
            String app = h != null ? resolveAppLabel(h) : "";
            if (!app.isEmpty()) counts.put(app, counts.getOrDefault(app, 0) + 1);
        }
        return counts;
    }

    private static String buildSessionAndTimeMemory(JSONArray hist) {
        int[] stats = collectTimeAndFormatStats(hist);
        int late = stats[0], evening = stats[1], totalTimed = stats[2], seriesCnt = stats[3], avgMin = stats[4];
        String format = (seriesCnt * 2 >= hist.length()) ? "TV series episodes" : "movies";
        String dur = avgMin > 0 ? (" (~" + avgMin + " min)") : "";
        String slot = (totalTimed > 0 && late * 2 >= totalTimed) ? " late at night"
            : (totalTimed > 0 && evening * 2 >= totalTimed) ? " in the evening" : "";
        return "Mostly watches " + format + dur + slot;
    }

    private static int[] collectTimeAndFormatStats(JSONArray hist) {
        int[] acc = new int[6]; // late, evening, timed, series, durSum, durCnt
        for (int i = 0; i < hist.length(); i++) accumulateItemStats(hist.optJSONObject(i), acc);
        return new int[]{acc[0], acc[1], acc[2], acc[3], acc[5] > 0 ? (acc[4] / acc[5]) : 0};
    }

    private static void accumulateItemStats(JSONObject h, int[] acc) {
        if (h == null) return;
        int hr = extractHour(h);
        if (hr >= 0) { acc[2]++; if (hr >= 21 || hr < 4) acc[0]++; else if (hr >= 17) acc[1]++; }
        if (h.optInt("episode", 0) >= 1 || h.optInt("season", 0) >= 1) acc[3]++;
        int min = h.optInt("duration_sec", 0) / 60;
        if (min >= 15 && min <= 240) { acc[4] += min; acc[5]++; }
    }

    private static void persistAutoMemories(AuraDb db, String profileId, List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        String prof = (profileId != null && !profileId.isEmpty()) ? profileId : AuraDb.DEFAULT_PROFILE;
        db.setSetting("auto_memories_" + prof, arr.toString());
    }

    private static List<String> topKeysByCount(Map<String, Integer> counts, int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < entries.size() && i < limit; i++) out.add(entries.get(i).getKey());
        return out;
    }

    private static int extractHour(JSONObject h) {
        long ts = h.optLong("updated_at", 0L);
        if (ts <= 0L) return -1;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ts * 1000L);
        return cal.get(Calendar.HOUR_OF_DAY);
    }

    private static String resolveAppLabel(JSONObject h) {
        String app = h.optString("app_name", h.optString("app", "")).trim();
        if (!app.isEmpty() && !"TV".equalsIgnoreCase(app)) return app;
        return SessionInspector.friendlyApp(h.optString("app_pkg", ""));
    }

    private static String extractTitle(JSONObject h) {
        String s = h.optString("series_title", "").trim();
        return !s.isEmpty() ? s : h.optString("title", "").trim();
    }

    private static void addUniqueTitle(List<String> list, String title) {
        if (title != null && !title.isEmpty() && !list.contains(title)) list.add(title);
    }

    private static void addIfNonEmpty(List<String> list, String entry) {
        if (entry != null && !entry.isEmpty()) list.add(entry);
    }
}
