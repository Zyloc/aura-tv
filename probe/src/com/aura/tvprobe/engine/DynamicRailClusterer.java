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
        addRailIfNotEmpty(rails, "Sci-Fi, Crime & High-Stakes Thrillers", buildThrillerSciFiRail(all, usedKeys, 8));
        addRailIfNotEmpty(rails, "Sports, Docuseries & True Stories", buildSportsAndDocRail(all, usedKeys, 7));
        addRailIfNotEmpty(rails, "Critically Acclaimed Across Your Apps", buildAcclaimedRail(all, usedKeys, 8));
        return rails;
    }

    private static void addRailIfNotEmpty(List<RailGroup> rails, String title, List<JSONObject> items) {
        if (items != null && !items.isEmpty()) {
            rails.add(new RailGroup(title, items));
        }
    }

    private static List<JSONObject> buildForYouRail(List<JSONObject> all, Set<String> usedKeys, int maxCount) {
        List<JSONObject> out = new ArrayList<>();
        Set<String> usedSeedsInTopRail = new HashSet<>();
        for (JSONObject item : all) {
            String k = AuraDb.normalizeTitleKey(item.optString("title", ""));
            if (k.isEmpty() || usedKeys.contains(k)) continue;
            String seed = item.optString("seed_anchor", "").trim().toLowerCase();
            if (!seed.isEmpty() && !usedSeedsInTopRail.add(seed)) continue;
            usedKeys.add(k);
            out.add(item);
            if (out.size() >= maxCount) break;
        }
        return out;
    }

    private static List<JSONObject> buildThrillerSciFiRail(List<JSONObject> all, Set<String> usedKeys, int maxCount) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : all) {
            String k = AuraDb.normalizeTitleKey(item.optString("title", ""));
            if (k.isEmpty() || usedKeys.contains(k)) continue;
            String g = item.optString("genre_codes", "");
            if (g.contains("spt") || g.contains("doc")) continue;
            if (g.contains("scf") || g.contains("crm") || g.contains("trl")) {
                usedKeys.add(k);
                out.add(item);
                if (out.size() >= maxCount) break;
            }
        }
        return out;
    }

    private static List<JSONObject> buildSportsAndDocRail(List<JSONObject> all, Set<String> usedKeys, int maxCount) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : all) {
            String k = AuraDb.normalizeTitleKey(item.optString("title", ""));
            if (k.isEmpty() || usedKeys.contains(k)) continue;
            String g = item.optString("genre_codes", "");
            if (g.contains("spt") || g.contains("doc") || g.contains("hst") || g.contains("war")) {
                usedKeys.add(k);
                out.add(item);
                if (out.size() >= maxCount) break;
            }
        }
        return out;
    }

    private static List<JSONObject> buildAcclaimedRail(List<JSONObject> all, Set<String> usedKeys, int maxCount) {
        List<JSONObject> remaining = new ArrayList<>();
        for (JSONObject item : all) {
            String k = AuraDb.normalizeTitleKey(item.optString("title", ""));
            if (!k.isEmpty() && !usedKeys.contains(k)) remaining.add(item);
        }
        remaining.sort((a, b) -> Double.compare(
            CandidateValidator.computeBayesianImdb(b),
            CandidateValidator.computeBayesianImdb(a)));
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : remaining) {
            String k = AuraDb.normalizeTitleKey(item.optString("title", ""));
            if (out.size() < maxCount && usedKeys.add(k)) out.add(item);
        }
        return out;
    }
}
