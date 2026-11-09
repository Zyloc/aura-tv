package com.aura.tvprobe;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/**
 * Contract test for {@code probe/AndroidManifest.xml} and {@code scripts/deploy_tv.sh}:
 * prevents regressions where HOME launcher categories, singleTask launchMode,
 * BootReceiver, or MediaProbeService declarations are accidentally dropped.
 */
public class AndroidManifestContractTest {

    @Test
    public void testAndroidManifestDeclaresHomeLauncherCategoriesAndSingleTask() throws Exception {
        File manifestFile = resolveFile("probe/AndroidManifest.xml", "AndroidManifest.xml");
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists());
        String xml = new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8);

        assertTrue("Must declare .AuraActivity", xml.contains("android:name=\".AuraActivity\""));
        assertTrue("Must use singleInstance or singleTask launchMode for Home button routing",
            xml.contains("android:launchMode=\"singleInstance\"") || xml.contains("android:launchMode=\"singleTask\""));
        assertTrue("Must declare android.intent.category.HOME so Home button never hits FallbackHome",
            xml.contains("android.intent.category.HOME"));
        assertTrue("Must declare android.intent.category.DEFAULT for Home intent resolution",
            xml.contains("android.intent.category.DEFAULT"));
        assertTrue("Must declare android.intent.category.LEANBACK_LAUNCHER",
            xml.contains("android.intent.category.LEANBACK_LAUNCHER"));
        assertTrue("Must declare .BootReceiver", xml.contains("android:name=\".BootReceiver\""));
        assertTrue("Must declare .MediaProbeService", xml.contains("android:name=\".MediaProbeService\""));
    }

    @Test
    public void testDeployScriptRegistersDefaultHomeActivity() throws Exception {
        File deployFile = resolveFile("scripts/deploy_tv.sh", "../scripts/deploy_tv.sh");
        assertTrue("deploy_tv.sh must exist", deployFile.exists());
        String script = new String(Files.readAllBytes(deployFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("deploy_tv.sh must register com.aura.tvprobe/.AuraActivity as home activity",
            script.contains("cmd package set-home-activity com.aura.tvprobe/.AuraActivity"));
        assertTrue("deploy_tv.sh must launch AuraActivity in the HOME stack (-c android.intent.category.HOME)",
            script.contains("-c android.intent.category.HOME -n com.aura.tvprobe/.AuraActivity"));
    }

    @Test
    public void testSearchOverlayKeyedViewTagHasValidAppResourcePrefix() throws Exception {
        File searchFile = resolveFile(
            "probe/src/com/aura/tvprobe/ui/SearchOverlay.java",
            "src/com/aura/tvprobe/ui/SearchOverlay.java");
        String src = new String(Files.readAllBytes(searchFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("TAG_HINT_IDX must use 0x7F prefix for 2-arg View.setTag(int, Object)",
            src.contains("TAG_HINT_IDX  = 0x7F0B_000B"));
    }

    @Test
    public void testAuraActivityHandlesHardwareHomeKeyBroadcastAndNewIntent() throws Exception {
        File actFile = resolveFile(
            "probe/src/com/aura/tvprobe/app/AuraActivity.java",
            "src/com/aura/tvprobe/app/AuraActivity.java");
        String src = new String(Files.readAllBytes(actFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("AuraActivity must listen for ACTION_CLOSE_SYSTEM_DIALOGS homekey",
            src.contains("ACTION_CLOSE_SYSTEM_DIALOGS") && src.contains("\"homekey\""));
        assertTrue("AuraActivity must override onNewIntent and call resetToHomeTop()",
            src.contains("protected void onNewIntent(Intent intent)") && src.contains("resetToHomeTop()"));
    }

    @Test
    public void testImageLoaderUsesKeyedTagSoDetailOverlayPosterTagIsPreserved() throws Exception {
        File loaderFile = resolveFile(
            "probe/src/com/aura/tvprobe/ui/ImageLoader.java",
            "src/com/aura/tvprobe/ui/ImageLoader.java");
        String src = new String(Files.readAllBytes(loaderFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("ImageLoader must define keyed TAG_CACHE_KEY with 0x7F prefix",
            src.contains("TAG_CACHE_KEY = 0x7F0B_0010"));
        assertFalse("ImageLoader must not clobber unkeyed View.setTag(Object) used by DetailOverlay",
            src.contains("iv.setTag(cacheKey)") || src.contains("iv.setTag(null)"));
    }

    @Test
    public void testOpenSourceHygieneAndPackageReplacedReceiver() throws Exception {
        File license = resolveFile("LICENSE", "../LICENSE");
        File buildScript = resolveFile("scripts/build_apk.sh", "../scripts/build_apk.sh");
        File manifestFile = resolveFile("probe/AndroidManifest.xml", "AndroidManifest.xml");
        String buildSrc = new String(Files.readAllBytes(buildScript.toPath()), StandardCharsets.UTF_8);
        String xml = new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("LICENSE file must exist for open-source readiness", license.exists());
        assertTrue("build_apk.sh must clean up assets/aura.env after linking", buildSrc.contains("rm -f assets/aura.env"));
        assertTrue("BootReceiver must handle MY_PACKAGE_REPLACED", xml.contains("android.intent.action.MY_PACKAGE_REPLACED"));
    }

    @Test
    public void testBackgroundRecommendationRefreshRendersWhenStale() throws Exception {
        File actFile = resolveFile(
            "probe/src/com/aura/tvprobe/app/AuraActivity.java",
            "src/com/aura/tvprobe/app/AuraActivity.java");
        String src = new String(Files.readAllBytes(actFile.toPath()), StandardCharsets.UTF_8);
        assertTrue("applyBackgroundRefreshResult must render when cache was stale (!hadFresh)",
            src.contains("(forceRefresh || !hadFresh)"));
        assertFalse("applyBackgroundRefreshResult must not block stale refresh with !hadUsable",
            src.contains("(forceRefresh || !hadUsable)"));
    }

    private static File resolveFile(String rootRelative, String probeRelative) {
        File f1 = new File(rootRelative);
        return f1.exists() ? f1 : new File(probeRelative);
    }
}
