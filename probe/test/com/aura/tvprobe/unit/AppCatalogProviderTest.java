package com.aura.tvprobe;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for {@link AppCatalogProvider}: verifies system app filtering,
 * Play Store retention, and OEM utility label suppression.
 */
public class AppCatalogProviderTest {

    @Test
    public void testPlayStoreAndYouTubeSystemAppsAreKeptWhileOemSystemAppsAreFiltered() {
        assertFalse("Play Store must never be filtered out",
            AppCatalogProvider.shouldFilterSystemPackage("com.android.vending", true, false));
        assertFalse("YouTube TV must never be filtered out",
            AppCatalogProvider.shouldFilterSystemPackage("com.google.android.youtube.tv", true, false));
        assertFalse("User-installed apps are never filtered",
            AppCatalogProvider.shouldFilterSystemPackage("com.stremio.one", false, false));
        assertTrue("Unupdated OEM system app must be filtered",
            AppCatalogProvider.shouldFilterSystemPackage("com.xiaomi.mitv.systemapp", true, false));
    }

    @Test
    public void testHiddenUtilityLabelsAreSuppressed() {
        assertTrue(AppCatalogProvider.isHiddenUtilityLabel("Package Installer", "com.android.packageinstaller"));
        assertTrue(AppCatalogProvider.isHiddenUtilityLabel("Factory Menu", "com.mstar.tv"));
        assertFalse(AppCatalogProvider.isHiddenUtilityLabel("Netflix", "com.netflix.ninja"));
        assertFalse(AppCatalogProvider.isHiddenUtilityLabel("Play Store", "com.android.vending"));
    }

    @Test
    public void testMediaDeepLinkLauncherResolvesTitlesPackagesAndHdmiPorts() throws Exception {
        org.json.JSONObject item = new org.json.JSONObject()
            .put("title", "Gullak \u00B7 S4 E2")
            .put("app_name", "Airtel Xstream");
        assertEquals("Gullak", MediaDeepLinkLauncher.extractCleanTitle(item));
        assertEquals("tv.airtel.xstream.tvapp", MediaDeepLinkLauncher.resolvePackage(item));
        assertEquals("com.mediatek.tvinput/.hdmi.HDMIInputService/HW2",
            MediaDeepLinkLauncher.resolveHdmiInputId(null, 1));
        assertEquals("com.mediatek.tvinput/.hdmi.HDMIInputService/HW4",
            MediaDeepLinkLauncher.resolveHdmiInputId(null, 3));
    }
}
