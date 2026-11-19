package com.aura.tvprobe;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Holistic Multi-Interest Home Rail Clusterer.
 * Distributes the 30-slot slate across balanced, value-driven rails (For You,
 * Sci-Fi & Crime Thrillers, Sports & Docuseries, and Critically Acclaimed)
 * without over-indexing on any single seed show.
 */
public final class DynamicRailClusterer {

    public static final class RailGroup {
        public final String title;
        public final List<JSONObject> items;

        public RailGroup(String title, List<JSONObject> items) {
            this.title = title;
            this.items = items;
        }
    }

    private DynamicRailClusterer() {}

    public static List<RailGroup> buildRails(List<JSONObject> all) {
        List<RailGroup> rails = new ArrayList<>();
        if (all == null || all.isEmpty()) return rails;
        Set<String> usedKeys = new HashSet<>();
        addRailIfNotEmpty(rails, "For You", buildForYouRail(all, usedKeys, 8));
        addRailIfMinSize(rails, "Live & Trending Across Your Apps", buildAppTrendingRail(all, usedKeys, 7), usedKeys, 2);
        addRailIfMinSize(rails, "Indian Crime, Drama & Regional Picks", buildRegionalRail(all, usedKeys, 7), usedKeys, 2);
        addRailIfNotEmpty(rails, "Sci-Fi, Crime & High-Stakes Thrillers", buildThrillerSciFiRail(all, usedKeys, 8));
        addRailIfMinSize(rails, "Sports, Docuseries & True Stories", buildSportsAndDocRail(all, usedKeys, 7), usedKeys, 2);
        addRailIfNotEmpty(rails, "Critically Acclaimed Across Your Apps", buildAcclaimedRail(all, usedKeys, 8));
        return rails;
    }

    private static List<JSONObject> buildAppTrendingRail(List<JSONObject> all, Set<String> usedKeys, int maxCount) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : all) {
            boolean isTrend = "app_trending".equals(item.optString("source_channel")) || !item.optString("broadcast_badge").isEmpty();
            if (isTrend && tryClaimItem(item, usedKeys, out) && out.size() >= maxCount) break;
        }
        return out;
    }

    private static List<JSONObject> buildRegionalRail(List<JSONObject> all, Set<String> usedKeys, int maxCount) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : all) {
            if ("IN".equalsIgnoreCase(item.optString("country")) && tryClaimItem(item, usedKeys, out) && out.size() >= maxCount) break;
        }
        return out;
    }

    private static void addRailIfNotEmpty(List<RailGroup> rails, String title, List<JSONObject> items) {
        if (items != null && !items.isEmpty()) {
            rails.add(new RailGroup(title, items));
        }
    }

    private static void addRailIfMinSize(
            List<RailGroup> rails, String title, List<JSONObject> items, Set<String> usedKeys, int minSize) {
        if (items == null || items.isEmpty()) return;
        if (items.size() >= minSize) {
            rails.add(new RailGroup(title, items));
            return;
        }
        for (JSONObject item : items) {
            usedKeys.remove(AuraDb.normalizeTitleKey(item.optString("title", "")));
        }
    }

    private static List<JSONObject> buildForYouRail(List<JSONObject> all, Set<String> usedKeys, int maxCount) {
        List<JSONObject> out = new ArrayList<>();
        Set<String> usedSeeds = new HashSet<>();
        for (JSONObject item : all) {
            if (tryAddForYouItem(item, usedKeys, usedSeeds, out) && out.size() >= maxCount) break;
        }
        return out;
    }

    private static boolean tryAddForYouItem(JSONObject item, Set<String> usedKeys, Set<String> usedSeeds, List<JSONObject> out) {
        String k = AuraDb.normalizeTitleKey(item.optString("title", ""));
        if (k.isEmpty() || usedKeys.contains(k)) return false;
        String seed = item.optString("seed_anchor", "").trim().toLowerCase();
        if (!seed.isEmpty() && !usedSeeds.add(seed)) return false;
        usedKeys.add(k);
        return out.add(item);
    }

    private static List<JSONObject> buildThrillerSciFiRail(List<JSONObject> all, Set<String> usedKeys, int maxCount) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : all) {
            if (isThrillerSciFiMatch(item) && tryClaimItem(item, usedKeys, out) && out.size() >= maxCount) break;
        }
        return out;
    }

    private static boolean isThrillerSciFiMatch(JSONObject item) {
        String g = item.optString("genre_codes", "");
        if (g.contains("spt") || g.contains("doc")) return false;
        return g.contains("scf") || g.contains("crm") || g.contains("trl");
    }

    private static List<JSONObject> buildSportsAndDocRail(List<JSONObject> all, Set<String> usedKeys, int maxCount) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : all) {
            if (isSportsOrDocMatch(item) && tryClaimItem(item, usedKeys, out) && out.size() >= maxCount) break;
        }
        return out;
    }

    private static boolean isSportsOrDocMatch(JSONObject item) {
        String g = item.optString("genre_codes", "");
        return g.contains("spt") || g.contains("doc");
    }

    private static boolean tryClaimItem(JSONObject item, Set<String> usedKeys, List<JSONObject> out) {
        String k = AuraDb.normalizeTitleKey(item.optString("title", ""));
        if (k.isEmpty() || !usedKeys.add(k)) return false;
        return out.add(item);
    }

    private static List<JSONObject> buildAcclaimedRail(List<JSONObject> all, Set<String> usedKeys, int maxCount) {
        List<JSONObject> remaining = collectUnusedItems(all, usedKeys);
        remaining.sort((a, b) -> Double.compare(CandidateValidator.computeBayesianImdb(b), CandidateValidator.computeBayesianImdb(a)));
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : remaining) {
            if (out.size() < maxCount) tryClaimItem(item, usedKeys, out);
        }
        return out;
    }

    private static List<JSONObject> collectUnusedItems(List<JSONObject> all, Set<String> usedKeys) {
        List<JSONObject> remaining = new ArrayList<>();
        for (JSONObject item : all) {
            String k = AuraDb.normalizeTitleKey(item.optString("title", ""));
            if (!k.isEmpty() && !usedKeys.contains(k)) remaining.add(item);
        }
        return remaining;
    }
}
