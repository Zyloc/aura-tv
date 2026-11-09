package com.aura.tvprobe;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link DynamicRailClusterer}: verifies disjoint rail partitioning,
 * max-1-per-seed rule in "For You", and dedicated Sports/Docuseries clustering.
 */
public class DynamicRailClustererTest {

    @Test
    public void testForYouRailEnforcesUniqueSeedsAndZeroCrossRailDuplicates() throws Exception {
        List<JSONObject> slate = new ArrayList<>();
        slate.add(make("Show 1", "SeedA", "scf,trl", 8.8));
        slate.add(make("Show 2", "SeedA", "scf,crm", 8.6));
        slate.add(make("Show 3", "SeedB", "crm,trl", 8.5));
        slate.add(make("Show 4", "SeedC", "spt,doc", 8.4));
        slate.add(make("Show 5", "SeedD", "drm,trl,war", 8.9));

        List<DynamicRailClusterer.RailGroup> rails = DynamicRailClusterer.buildRails(slate);
        assertFalse("Rails must not be empty", rails.isEmpty());
        assertEquals("For You", rails.get(0).title);
        for (DynamicRailClusterer.RailGroup rg : rails) {
            if ("Sports, Docuseries & True Stories".equals(rg.title)) {
                assertTrue("Sports rail must have at least 2 items when present", rg.items.size() >= 2);
            }
        }

        Set<String> forYouSeeds = new HashSet<>();
        for (JSONObject item : rails.get(0).items) {
            String seed = item.optString("seed_anchor");
            assertTrue("For You rail must not repeat seed_anchor: " + seed, forYouSeeds.add(seed));
        }

        Set<String> globalTitles = new HashSet<>();
        for (DynamicRailClusterer.RailGroup rg : rails) {
            for (JSONObject item : rg.items) {
                String key = AuraDb.normalizeTitleKey(item.optString("title"));
                assertTrue("No title may appear in multiple rails: " + key, globalTitles.add(key));
            }
        }
    }

    private static JSONObject make(String title, String seed, String genres, double imdb) throws Exception {
        return new JSONObject()
            .put("title", title).put("seed_anchor", seed)
            .put("genre_codes", genres).put("imdb_raw", imdb)
            .put("imdb_votes", 25000).put("tmdb_popularity", 30.0);
    }
}
