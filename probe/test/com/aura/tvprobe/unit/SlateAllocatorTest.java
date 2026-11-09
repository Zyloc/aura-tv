package com.aura.tvprobe;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link SlateAllocator}: enforces MAX_PER_SEED = 2 cap,
 * multi-interest bucket quotas (including Sports & Docuseries), and
 * 3-epoch impression TTL cooldown recycling.
 */
public class SlateAllocatorTest {

    @Test
    public void testHardCapPreventsSingleSeedFromDominatingSlate() throws Exception {
        List<JSONObject> pool = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            pool.add(makeItem("K-Drama #" + i, "Marry My Husband", "drm,rma", 14.0 - i * 0.1));
            pool.add(makeItem("Workplace #" + i, "Dead-End Job", "drm,cmy", 13.5 - i * 0.1));
        }
        for (int i = 1; i <= 8; i++) {
            pool.add(makeItem("SciFi #" + i, "SciFiSeed" + i, "scf,trl", 11.0));
            pool.add(makeItem("Sports #" + i, "SportsSeed" + i, "spt,doc", 10.8));
        }

        List<JSONObject> slate = SlateAllocator.allocateBalancedSlate(pool, 1, new HashMap<>());
        int marryCount = countSeed(slate, "Marry My Husband");
        int deadEndCount = countSeed(slate, "Dead-End Job");
        int sportsCount = countGenre(slate, "spt");

        assertTrue("Marry My Husband must be capped at <= 2 items", marryCount <= 2);
        assertTrue("Dead-End Job must be capped at <= 2 items", deadEndCount <= 2);
        assertTrue("Sports/Docuseries bucket must be populated", sportsCount >= 4);
    }

    @Test
    public void testImpressionCooldownRecyclesRecentlyShownTitlesInNextEpochs() throws Exception {
        List<JSONObject> pool = new ArrayList<>();
        pool.add(makeItem("Stale Pick", "SeedA", "scf", 12.0));
        pool.add(makeItem("Fresh Pick", "SeedB", "scf", 10.5));

        Map<String, ImpressionTracker.ImpressionRecord> history = new HashMap<>();
        String staleKey = AuraDb.normalizeTitleKey("Stale Pick");
        history.put(staleKey, new ImpressionTracker.ImpressionRecord(2, 2, System.currentTimeMillis() / 1000L));

        List<JSONObject> slate = SlateAllocator.allocateBalancedSlate(pool, 3, history);
        assertEquals("Fresh unshown title must be prioritized ahead of cooldown item",
            "Fresh Pick", slate.get(0).optString("title"));
    }

    private static int countSeed(List<JSONObject> list, String seed) {
        int c = 0;
        for (JSONObject o : list) {
            if (seed.equalsIgnoreCase(o.optString("seed_anchor"))) c++;
        }
        return c;
    }

    private static int countGenre(List<JSONObject> list, String code) {
        int c = 0;
        for (JSONObject o : list) {
            if (o.optString("genre_codes").contains(code)) c++;
        }
        return c;
    }

    private static JSONObject makeItem(String title, String seed, String genres, double score) throws Exception {
        return new JSONObject()
            .put("title", title).put("seed_anchor", seed)
            .put("genre_codes", genres).put("sota_score", score);
    }
}
