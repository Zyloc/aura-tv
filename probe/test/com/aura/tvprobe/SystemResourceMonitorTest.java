package com.aura.tvprobe;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for SystemResourceMonitor 4-Tier Process Protection & Kill Strategy.
 */
public class SystemResourceMonitorTest {

    @Test
    public void testTierClassificationProtectsCoreAndIdentifiesOtt() {
        assertEquals(0, SystemResourceMonitor.classifyPackageTier("com.aura.tvprobe"));
        assertEquals(0, SystemResourceMonitor.classifyPackageTier("com.google.android.katniss"));
        assertEquals(0, SystemResourceMonitor.classifyPackageTier("com.google.android.tts"));
        assertEquals(1, SystemResourceMonitor.classifyPackageTier("com.netflix.ninja"));
        assertEquals(1, SystemResourceMonitor.classifyPackageTier("in.startv.hotstar"));
        assertEquals(2, SystemResourceMonitor.classifyPackageTier("com.android.vending"));
    }

    @Test
    public void testNeverKillsPlayingAppOrTier0Core() {
        long now = System.currentTimeMillis();
        assertFalse(SystemResourceMonitor.shouldKillCandidate(
            "in.startv.hotstar", "in.startv.hotstar", "", 0L, 0L, now, 0L, true, true));
        assertFalse(SystemResourceMonitor.shouldKillCandidate(
            "com.google.android.katniss", "in.startv.hotstar", "", 0L, 900_000L, now - 900_000L, 0L, true, true));
        assertFalse(SystemResourceMonitor.shouldKillCandidate(
            "com.aura.tvprobe", "", "", 0L, 900_000L, now - 900_000L, 0L, true, true));
    }

    @Test
    public void testKillsIdleNetflixAndPlayStoreWhenHotstarIsPlaying() {
        long now = System.currentTimeMillis();
        assertTrue(SystemResourceMonitor.shouldKillCandidate(
            "com.netflix.ninja", "in.startv.hotstar", "", 0L, 180_000L, now - 180_000L, 0L, false, false));
        assertTrue(SystemResourceMonitor.shouldKillCandidate(
            "com.android.vending", "in.startv.hotstar", "", 0L, 180_000L, now - 180_000L, 0L, false, false));
    }

    @Test
    public void testProtectsRecentlyPausedAppWhenNoOtherAppPlaying() {
        long now = System.currentTimeMillis();
        assertFalse(SystemResourceMonitor.shouldKillCandidate(
            "com.netflix.ninja", "", "com.netflix.ninja", 120_000L, 120_000L, now - 120_000L, 0L, true, false));
        assertTrue(SystemResourceMonitor.shouldKillCandidate(
            "com.netflix.ninja", "", "com.netflix.ninja", 120_000L, 120_000L, now - 120_000L, 0L, true, true));
    }

    @Test
    public void testAntiThrashCooldownPreventsRepeatedAutoKill() {
        long now = System.currentTimeMillis();
        long lastUsed = now - 600_000L;
        long lastKilled = now - 120_000L; // Killed after lastUsed
        assertFalse(SystemResourceMonitor.shouldKillCandidate(
            "com.netflix.ninja", "in.startv.hotstar", "", 0L, 600_000L, lastUsed, lastKilled, true, false));
    }

    @Test
    public void testMultiBrandOemAndRegionAutoDetection() {
        assertEquals("SONY", TvDeviceProfile.detectOemFamily("Sony", "BRAVIA", "mt5895"));
        assertEquals("TCL", TvDeviceProfile.detectOemFamily("TCL", "TCL", "rt2851"));
        assertEquals("HISENSE", TvDeviceProfile.detectOemFamily("Hisense", "Hisense", "amlogic"));
        assertEquals("XIAOMI", TvDeviceProfile.detectOemFamily("Xiaomi", "MiTV", "mstar"));
        assertTrue(TvDeviceProfile.isProtectedSystemOrOemPackage("com.sony.dtv.tvinput"));
        assertTrue(TvDeviceProfile.isProtectedSystemOrOemPackage("com.tcl.tvinput"));
        assertTrue(TvDeviceProfile.isKnownHeavyOttPackage("bbc.iplayer.android"));
        assertTrue(TvDeviceProfile.isKnownHeavyOttPackage("jp.unext.mediaplayer"));
        assertEquals("com.sony.dtv.tvinput/.hdmi.HdmiInputService/HW2", TvDeviceProfile.resolveFallbackHdmiInputId("SONY", 2));
    }
}
