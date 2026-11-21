package com.aura.tvprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Movie Night Blend Engine: computes the exact taste intersection of two household profiles.
 * Excludes any show watched or disliked by EITHER person and ranks candidates by the
 * harmonic mean of both profiles' affinities so both viewers genuinely enjoy the pick.
 */
public final class HouseholdBlendEngine {

    private HouseholdBlendEngine() {}

    public static List<JSONObject> blendProfiles(AuraDb db, String profileA, String profileB, int limit) {
        if (db == null) return new ArrayList<>();
        String pA = normalizeProfile(profileA, "member1");
        String pB = normalizeProfile(profileB, "partner");
        UserTasteProfile tasteA = new UserTasteProfile(db, pA, 1);
        UserTasteProfile tasteB = new UserTasteProfile(db, pB, 1);
        Set<String> excluded = collectExcludedKeys(db, pA, pB);
        List<JSONObject> pool = collectCandidatePool(db, pA, pB, excluded);
        return rankAndAnnotateBlend(db, pool, tasteA, tasteB, pA, pB, limit);
    }

    private static String normalizeProfile(String p, String fallback) {
        return (p != null && !p.trim().isEmpty()) ? p.trim() : fallback;
    }

    private static Set<String> collectExcludedKeys(AuraDb db, String pA, String pB) {
        Set<String> out = new HashSet<>();
        addTitlesToSet(UserEngagementLedger.getDislikedOrDroppedTitles(db, pA, 40), out);
        addTitlesToSet(UserEngagementLedger.getDislikedOrDroppedTitles(db, pB, 40), out);
        addTitlesToSet(UserEngagementLedger.getTopLovedTitles(db, pA, 40), out);
        addTitlesToSet(UserEngagementLedger.getTopLovedTitles(db, pB, 40), out);
        return out;
    }

    private static void addTitlesToSet(List<String> titles, Set<String> out) {
        if (titles == null) return;
        for (String t : titles) {
            String k = AuraDb.normalizeTitleKey(t);
            if (!k.isEmpty()) out.add(k);
        }
    }

    private static List<JSONObject> collectCandidatePool(AuraDb db, String pA, String pB, Set<String> excluded) {
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>(excluded);
        appendUnseen(db.getProfileRecommendations(pA), seen, out);
        appendUnseen(db.getProfileRecommendations(pB), seen, out);
        return out;
    }

    private static void appendUnseen(JSONArray arr, Set<String> seen, List<JSONObject> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            String key = item != null ? AuraDb.normalizeTitleKey(item.optString("title", "")) : "";
            if (!key.isEmpty() && seen.add(key)) out.add(copyJson(item));
        }
    }

    private static List<JSONObject> rankAndAnnotateBlend(
            AuraDb db, List<JSONObject> pool, UserTasteProfile tA, UserTasteProfile tB,
            String pA, String pB, int limit) {
        String labelA = db.getSetting("profile_name_" + pA, pA);
        String labelB = db.getSetting("profile_name_" + pB, pB);
        for (JSONObject item : pool) scoreBlendedItem(item, tA, tB, labelA, labelB);
        pool.sort((x, y) -> Double.compare(y.optDouble("blend_score", 0.0), x.optDouble("blend_score", 0.0)));
        return pool.subList(0, Math.min(Math.max(1, limit), pool.size()));
    }

    private static void scoreBlendedItem(
            JSONObject item, UserTasteProfile tA, UserTasteProfile tB, String labelA, String labelB) {
        double a = Math.max(0.15, scoreSingleProfileAffinity(item, tA));
        double b = Math.max(0.15, scoreSingleProfileAffinity(item, tB));
        double harmonic = (2.0 * a * b) / (a + b);
        double imdb = item.optDouble("imdb_raw", item.optDouble("imdb_score", 7.5));
        try {
            item.put("blend_score", harmonic * 10.0 + imdb);
            item.put("reason", "Movie Night Blend (" + labelA + " \u00D7 " + labelB + ") \u00B7 Neither has seen it");
        } catch (Exception ignored) {}
    }

    private static double scoreSingleProfileAffinity(JSONObject item, UserTasteProfile taste) {
        String codes = item.optString("genre_codes", "");
        if (codes.isEmpty()) return 0.5;
        return Math.min(2.5, 0.35 + taste.computeGenreAffinity(codes));
    }

    private static JSONObject copyJson(JSONObject src) {
        try { return new JSONObject(src.toString()); } catch (Exception e) { return src; }
    }
}
