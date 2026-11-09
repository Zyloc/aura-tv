package com.aura.tvprobe;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Production Multi-Interest Portfolio Slate Allocator (30 Slots).
 * Prevents single-seed over-indexing (hard cap of 2 items per seed), enforces
 * 3-epoch impression cooldown, and guarantees balanced representation across
 * Sci-Fi, Crime/Action, Sports/Docuseries, Thrillers, and Prestige Drama.
 */
public final class SlateAllocator {

    private static final int MAX_PER_SEED = 2;
    private static final int TARGET_SLATE_SIZE = 30;

    private static final String BUCKET_SPORTS = "sports_doc";
    private static final String BUCKET_SCIFI = "scifi";
    private static final String BUCKET_CRIME = "crime_action";
    private static final String BUCKET_THRILLER = "thriller";
    private static final String BUCKET_DRAMA = "drama_comedy";

    private SlateAllocator() {}

    public static List<JSONObject> allocateBalancedSlate(
            List<JSONObject> pool, int currentEpoch, Map<String, ImpressionTracker.ImpressionRecord> impressions) {
        applyEpochExplorationAndNovelty(pool, currentEpoch, impressions);
        List<JSONObject> selected = new ArrayList<>();
        Set<String> usedKeys = new HashSet<>();
        Map<String, Integer> seedCounts = new HashMap<>();
        fillFromPool(filterByFreshness(pool, currentEpoch, impressions, true), selected, usedKeys, seedCounts);
        if (selected.size() < TARGET_SLATE_SIZE) {
            fillFromPool(filterByFreshness(pool, currentEpoch, impressions, false), selected, usedKeys, seedCounts);
        }
        selected.sort((a, b) -> Double.compare(b.optDouble("slate_score", 0.0), a.optDouble("slate_score", 0.0)));
        return selected;
    }

    private static void fillFromPool(
            List<JSONObject> poolSubset, List<JSONObject> selected, Set<String> usedKeys, Map<String, Integer> seedCounts) {
        fillFromBuckets(partitionIntoBuckets(poolSubset), selected, usedKeys, seedCounts);
    }

    private static void applyEpochExplorationAndNovelty(
            List<JSONObject> pool, int epoch,
            Map<String, ImpressionTracker.ImpressionRecord> impressions) {
        for (JSONObject item : pool) {
            String key = AuraDb.normalizeTitleKey(item.optString("title", ""));
            double base = item.optDouble("sota_score", 0.0);
            double novelty = ImpressionTracker.computeNoveltyPenalty(key, epoch, impressions);
            double jitter = computeDeterministicEpochJitter(key, epoch);
            try {
                item.put("slate_score", base + novelty + jitter);
            } catch (Exception ignored) {}
        }
        pool.sort((a, b) -> Double.compare(b.optDouble("slate_score", 0.0), a.optDouble("slate_score", 0.0)));
    }

    private static double computeDeterministicEpochJitter(String key, int epoch) {
        int hash = (key + "#ep" + epoch).hashCode() & 0x7fffffff;
        return ((hash % 1000) / 1000.0) * 0.85;
    }

    private static List<JSONObject> filterByFreshness(
            List<JSONObject> pool, int epoch,
            Map<String, ImpressionTracker.ImpressionRecord> impressions, boolean wantFresh) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject item : pool) {
            String key = AuraDb.normalizeTitleKey(item.optString("title", ""));
            boolean recent = ImpressionTracker.isRecentlyShown(key, epoch, impressions);
            if (wantFresh != recent) out.add(item);
        }
        return out;
    }

    private static Map<String, List<JSONObject>> partitionIntoBuckets(List<JSONObject> items) {
        Map<String, List<JSONObject>> buckets = new LinkedHashMap<>();
        buckets.put(BUCKET_SCIFI, new ArrayList<>());
        buckets.put(BUCKET_CRIME, new ArrayList<>());
        buckets.put(BUCKET_SPORTS, new ArrayList<>());
        buckets.put(BUCKET_THRILLER, new ArrayList<>());
        buckets.put(BUCKET_DRAMA, new ArrayList<>());
        for (JSONObject item : items) {
            buckets.get(classifyBucket(item.optString("genre_codes", ""))).add(item);
        }
        return buckets;
    }

    public static String classifyBucket(String gCodes) {
        if (gCodes == null) return BUCKET_DRAMA;
        if (gCodes.contains("spt") || gCodes.contains("doc")) return BUCKET_SPORTS;
        if (gCodes.contains("scf")) return BUCKET_SCIFI;
        if (gCodes.contains("crm") || (gCodes.contains("act") && gCodes.contains("trl"))) return BUCKET_CRIME;
        if (gCodes.contains("trl") || gCodes.contains("hrr")) return BUCKET_THRILLER;
        return BUCKET_DRAMA;
    }

    private static void fillFromBuckets(
            Map<String, List<JSONObject>> buckets, List<JSONObject> selected,
            Set<String> usedKeys, Map<String, Integer> seedCounts) {
        Map<String, Integer> quotas = getBucketQuotas();
        Map<String, Integer> bucketCounts = new HashMap<>();
        boolean addedInPass = true;
        while (selected.size() < TARGET_SLATE_SIZE && addedInPass) {
            addedInPass = runRoundRobinPass(buckets, quotas, bucketCounts, selected, usedKeys, seedCounts);
        }
    }

    private static Map<String, Integer> getBucketQuotas() {
        Map<String, Integer> q = new HashMap<>();
        q.put(BUCKET_SCIFI, 8);
        q.put(BUCKET_CRIME, 8);
        q.put(BUCKET_SPORTS, 6);
        q.put(BUCKET_THRILLER, 8);
        q.put(BUCKET_DRAMA, 10);
        return q;
    }

    private static boolean runRoundRobinPass(
            Map<String, List<JSONObject>> buckets, Map<String, Integer> quotas,
            Map<String, Integer> bucketCounts, List<JSONObject> selected,
            Set<String> usedKeys, Map<String, Integer> seedCounts) {
        boolean added = false;
        for (Map.Entry<String, List<JSONObject>> entry : buckets.entrySet()) {
            if (tryPullFromBucket(entry.getKey(), entry.getValue(), quotas, bucketCounts, selected, usedKeys, seedCounts)) {
                added = true;
                if (selected.size() >= TARGET_SLATE_SIZE) break;
            }
        }
        return added;
    }

    private static boolean tryPullFromBucket(
            String bName, List<JSONObject> list, Map<String, Integer> quotas,
            Map<String, Integer> bucketCounts, List<JSONObject> selected,
            Set<String> usedKeys, Map<String, Integer> seedCounts) {
        int currentCount = bucketCounts.getOrDefault(bName, 0);
        if (currentCount >= quotas.getOrDefault(bName, 6)) return false;
        JSONObject next = pollNextEligible(list, usedKeys, seedCounts);
        if (next == null) return false;
        selected.add(next);
        bucketCounts.put(bName, currentCount + 1);
        return true;
    }

    private static JSONObject pollNextEligible(
            List<JSONObject> bucketList, Set<String> usedKeys, Map<String, Integer> seedCounts) {
        while (!bucketList.isEmpty()) {
            JSONObject cand = bucketList.remove(0);
            if (tryClaimCandidate(cand, usedKeys, seedCounts)) return cand;
        }
        return null;
    }

    private static boolean tryClaimCandidate(JSONObject cand, Set<String> usedKeys, Map<String, Integer> seedCounts) {
        String key = AuraDb.normalizeTitleKey(cand.optString("title", ""));
        if (key.isEmpty() || usedKeys.contains(key)) return false;
        String seed = cand.optString("seed_anchor", "").trim().toLowerCase();
        if (!seed.isEmpty() && seedCounts.getOrDefault(seed, 0) >= MAX_PER_SEED) return false;
        usedKeys.add(key);
        if (!seed.isEmpty()) seedCounts.put(seed, seedCounts.getOrDefault(seed, 0) + 1);
        return true;
    }
}
