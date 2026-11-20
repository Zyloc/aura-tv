package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pillar 2: Decision-Free "Tonight's 3 Picks" Concierge Hero Curator.
 * Builds 3 hyper-contextual Hero slots tailored to time-of-day, Continue Watching,
 * Taste DNA, stale-show recap readiness, and Long-Term Memories.
 */
public final class TonightPicksCurator {

    private TonightPicksCurator() {}

    public static List<JSONObject> buildHeroPicks(
            AuraDb db, String profileId, List<JSONObject> recos, long nowSec, String timeContext) {
        List<JSONObject> out = new ArrayList<>();
        Set<String> usedKeys = new HashSet<>();
        addResumeSlot(db, profileId, nowSec, timeContext, usedKeys, out);
        addTopMatchSlot(recos, timeContext, usedKeys, out);
        addQuickOrMoodSlot(recos, timeContext, usedKeys, out);
        fillRemainingHeroSlots(recos, usedKeys, out);
        return out;
    }

    private static void addResumeSlot(
            AuraDb db, String profileId, long nowSec, String timeCtx, Set<String> used, List<JSONObject> out) {
        if (db == null) return;
        JSONArray hist = db.listHistory(profileId);
        JSONObject best = findBestResumableItem(hist);
        if (best == null) return;
        JSONObject card = cloneWithHeroTag(best, buildResumeTag(best, nowSec, timeCtx));
        if (card != null && used.add(canonicalKey(card))) out.add(card);
    }

    private static JSONObject findBestResumableItem(JSONArray hist) {
        for (int i = 0; i < hist.length(); i++) {
            JSONObject h = hist.optJSONObject(i);
            if (h != null && !h.optBoolean("watched", false) && !h.optString("poster_url", "").isEmpty()) {
                return h;
            }
        }
        return null;
    }

    private static String buildResumeTag(JSONObject item, long nowSec, String timeCtx) {
        String stale = StaleShowRecapDetector.buildRecapBadge(item, nowSec);
        if (!stale.isEmpty()) return stale;
        int remMin = Math.max(1, (item.optInt("duration_sec", 0) - item.optInt("position_sec", 0)) / 60);
        String prefix = friendlyTimePrefix(timeCtx);
        return remMin > 1 ? (prefix + " \u00B7 RESUME \u00B7 " + remMin + "m LEFT") : (prefix + " \u00B7 CONTINUE WATCHING");
    }

    private static void addTopMatchSlot(List<JSONObject> recos, String timeCtx, Set<String> used, List<JSONObject> out) {
        if (recos == null) return;
        for (JSONObject r : recos) {
            if (r == null || used.contains(canonicalKey(r))) continue;
            String tag = friendlyTimePrefix(timeCtx) + " \u00B7 TOP TASTE MATCH";
            JSONObject card = cloneWithHeroTag(r, tag);
            if (card != null && used.add(canonicalKey(card))) { out.add(card); return; }
        }
    }

    private static void addQuickOrMoodSlot(List<JSONObject> recos, String timeCtx, Set<String> used, List<JSONObject> out) {
        if (recos == null) return;
        for (JSONObject r : recos) {
            if (r == null || used.contains(canonicalKey(r))) continue;
            int rt = r.optInt("runtime_min", 0);
            if (rt > 0 && rt <= 115) {
                String tag = friendlyTimePrefix(timeCtx) + " \u00B7 QUICK " + rt + "m PICK";
                JSONObject card = cloneWithHeroTag(r, tag);
                if (card != null && used.add(canonicalKey(card))) { out.add(card); return; }
            }
        }
    }

    private static void fillRemainingHeroSlots(List<JSONObject> recos, Set<String> used, List<JSONObject> out) {
        if (recos == null) return;
        for (JSONObject r : recos) {
            if (out.size() >= 3) return;
            if (r != null && used.add(canonicalKey(r))) out.add(cloneWithHeroTag(r, "TONIGHT'S PICK"));
        }
    }

    private static String friendlyTimePrefix(String timeCtx) {
        if (TasteIntelligenceEngine.CTX_LATE_NIGHT.equals(timeCtx)) return "LATE NIGHT";
        if (TasteIntelligenceEngine.CTX_WEEKEND_BINGE.equals(timeCtx)) return "WEEKEND PICK";
        return "TONIGHT";
    }

    private static JSONObject cloneWithHeroTag(JSONObject src, String heroTag) {
        try {
            JSONObject copy = new JSONObject(src.toString());
            if (heroTag != null && !heroTag.isEmpty()) copy.put("hero_concierge_tag", heroTag);
            return copy;
        } catch (Exception e) { return null; }
    }

    private static String canonicalKey(JSONObject item) {
        String s = item.optString("series_title", "").trim();
        return AuraDb.normalizeTitleKey(!s.isEmpty() ? s : item.optString("title", ""));
    }
}
