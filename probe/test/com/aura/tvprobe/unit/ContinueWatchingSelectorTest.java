package com.aura.tvprobe;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link ContinueWatchingSelector}: filters short/completed/promo
 * noise and interleaves active sessions across streaming platforms.
 */
public class ContinueWatchingSelectorTest {

    @Test
    public void testFiltersNoiseAndCompletedAndInterleavesPlatforms() throws Exception {
        List<JSONObject> raw = new ArrayList<>();
        // 1. Valid Prime Video session (40%)
        raw.add(makeSession("Marry My Husband", "Prime Video", "com.amazon.avod", 1200, 3000));
        // 2. Second Prime Video session (should be interleaved after other platforms)
        raw.add(makeSession("Reacher", "Prime Video", "com.amazon.avod", 900, 3000));
        // 3. Valid Apple TV+ session (50%)
        raw.add(makeSession("Severance", "Apple TV+", "com.apple.atve", 1500, 3000));
        // 4. Fabricated Netflix 1350/3000 row -> MUST BE REJECTED
        raw.add(makeSession("Dead-End Job", "Netflix", "com.netflix.ninja", 1350, 3000));
        // 5. Completed 98% row -> MUST BE REJECTED
        raw.add(makeSession("Finished Movie", "Netflix", "com.netflix.ninja", 2950, 3000));
        // 6. Short <60s trailer preview -> MUST BE REJECTED
        raw.add(makeSession("Brief Trailer", "Netflix", "com.netflix.ninja", 25, 3000));
        // 7. YouTube clip -> MUST BE REJECTED
        raw.add(makeSession("Tech Review", "YouTube", "com.google.android.youtube.tv", 300, 900));

        List<JSONObject> selected = ContinueWatchingSelector.selectDiversified(raw, 5);
        assertEquals(3, selected.size());
        assertEquals("Marry My Husband", selected.get(0).optString("title"));
        assertEquals("Round-robin must pick Apple TV+ before 2nd Prime Video item",
            "Severance", selected.get(1).optString("title"));
        assertEquals("Reacher", selected.get(2).optString("title"));
    }

    @Test
    public void testCleanPlatformNameNormalizesRawPackageNames() {
        assertEquals("SonyLIV", ContinueWatchingSelector.cleanPlatformName("com.sonyliv"));
        assertEquals("ZEE5", ContinueWatchingSelector.cleanPlatformName("com.graymatrix.did"));
        assertEquals("Airtel Xstream", ContinueWatchingSelector.cleanPlatformName("tv.airtel.xstream.tvapp"));
        assertEquals("Apple TV+", ContinueWatchingSelector.cleanPlatformName("com.apple.atve.androidtv.appletv"));
    }

    @Test
    public void testFiltersOutManuallyWatchedItemsFromContinueWatching() throws Exception {
        List<JSONObject> raw = new ArrayList<>();
        raw.add(makeSession("Silo", "Apple TV+", "com.apple.atve", 1200, 3000).put("watched", true));
        raw.add(makeSession("Slow Horses", "Apple TV+", "com.apple.atve", 1400, 3000).put("watched", false));
        List<JSONObject> selected = ContinueWatchingSelector.selectDiversified(raw, 5);
        assertEquals(1, selected.size());
        assertEquals("Slow Horses", selected.get(0).optString("title"));
    }

    private static JSONObject makeSession(String title, String app, String pkg,
                                          int pos, int dur) throws Exception {
        return new JSONObject()
            .put("title", title).put("app_name", app)
            .put("app_pkg", pkg).put("position_sec", pos).put("duration_sec", dur);
    }
}
