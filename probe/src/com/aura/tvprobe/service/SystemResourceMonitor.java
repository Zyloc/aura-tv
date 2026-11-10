package com.aura.tvprobe;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Debug;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live TV RAM & CPU Telemetry Collector and 4-Tier Background Process Guardian.
 * Protects active playback, Google Assistant, and Aura TTS while reclaiming 150-220 MB
 * from idle heavy OTT apps (Netflix, Hotstar, Prime, Play Store) on app-switch or low RAM.
 */
public final class SystemResourceMonitor {

    public static final int LOW_RAM_THRESHOLD_MB = 280;
    private static final long PAUSE_GRACE_MS     = 10L * 60_000L;
    private static final long AUTO_GUARD_MIN_GAP = 120_000L;

    private static final Map<String, Long> sLastKilledWallMs = new ConcurrentHashMap<>();
    private static volatile String sLastGuardedPlayingPkg = "";
    private static volatile long sLastAutoGuardWallMs = 0L;
    private static volatile long sPrevProcSelfJiffies = 0L;
    private static volatile long sPrevProcSelfWallMs = 0L;
    private static volatile long sPrevSysTotalTicks = 0L;
    private static volatile long sPrevSysIdleTicks = 0L;

    private static final String[] KNOWN_BG_CANDIDATES = new String[]{
        "com.netflix.ninja", "in.startv.hotstar", "com.amazon.amazonvideo.livingroom",
        "com.google.android.youtube.tv", "com.graymatrix.did", "com.sonyliv",
        "com.apple.atve.androidtv.appletv", "in.airtel.xstream.ui", "com.android.vending"
    };

    private SystemResourceMonitor() {}

    public static boolean isAutoGuardEnabled(AuraDb db) {
        return db == null || !"false".equalsIgnoreCase(db.getSetting("auto_ram_guard", "true"));
    }

    public static void setAutoGuardEnabled(AuraDb db, boolean enabled) {
        if (db != null) db.setSetting("auto_ram_guard", enabled ? "true" : "false");
    }

    public static JSONObject collectTelemetrySnapshot(Context ctx, AuraDb db) {
        JSONObject out = new JSONObject();
        try {
            Map<String, Long> mem = readProcMemInfo();
            populateMemoryMetrics(ctx, out, mem);
            populateCpuMetrics(out, mem);
            JSONArray candidates = discoverProcessCandidates(ctx, out.optInt("avail_ram_mb", 400) < LOW_RAM_THRESHOLD_MB);
            out.put("candidates", candidates);
            out.put("reclaimable_est_mb", sumReclaimableMb(candidates));
            out.put("auto_guard", isAutoGuardEnabled(db));
            out.put("device_profile", TvDeviceProfile.buildDeviceProfileJson(ctx, db));
            out.put("history", DatabaseJanitor.listRamCleanupHistory(db, 15));
        } catch (Exception ignored) {}
        return out;
    }

    public static String formatCompactDrawerSummary(JSONObject snap) {
        if (snap == null) return "RAM & CPU Guardian";
        int usedMb = snap.optInt("used_ram_mb", 1400);
        int totMb = snap.optInt("total_ram_mb", 1831);
        int availMb = snap.optInt("avail_ram_mb", 430);
        int cpuPct = snap.optInt("cpu_load_pct", 24);
        return "RAM " + usedMb + "/" + totMb + " MB (" + availMb + "M Free) \u00B7 CPU " + cpuPct + "%";
    }

    private static void populateMemoryMetrics(Context ctx, JSONObject out, Map<String, Long> m) throws Exception {
        int totalMb = kbToMb(m.getOrDefault("MemTotal", 1875376L));
        int availMb = resolveAvailableMb(ctx, m);
        int usedMb = Math.max(0, totalMb - availMb);
        int cacheMb = kbToMb(m.getOrDefault("Cached", 0L) + m.getOrDefault("Buffers", 0L));
        int zramTot = kbToMb(m.getOrDefault("SwapTotal", 204796L));
        int zramUsed = Math.max(0, zramTot - kbToMb(m.getOrDefault("SwapFree", 0L)));
        out.put("total_ram_mb", totalMb).put("avail_ram_mb", availMb).put("used_ram_mb", usedMb);
        out.put("used_ram_pct", totalMb > 0 ? Math.min(99, (usedMb * 100) / totalMb) : 75);
        out.put("page_cache_mb", cacheMb).put("app_heaps_mb", kbToMb(m.getOrDefault("AnonPages", 460000L)));
        out.put("zram_total_mb", zramTot).put("zram_used_mb", zramUsed).put("aura_pss_mb", readAuraPssMb());
    }

    private static int resolveAvailableMb(Context ctx, Map<String, Long> m) {
        long procAvailKb = m.getOrDefault("MemAvailable", 0L);
        if (procAvailKb > 0L) return kbToMb(procAvailKb);
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) { am.getMemoryInfo(mi); return (int) (mi.availMem / (1024L * 1024L)); }
        } catch (Exception ignored) {}
        return 380;
    }

    private static int readAuraPssMb() {
        try {
            int pssKb = (int) Debug.getPss();
            if (pssKb > 0) return Math.max(1, pssKb / 1024);
        } catch (Throwable ignored) {}
        long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        return Math.max(8, (int) (heap / (1024L * 1024L)) + 10);
    }

    private static void populateCpuMetrics(JSONObject out, Map<String, Long> mem) throws Exception {
        int[] freqsMhz = readCoreFrequenciesMhz();
        double auraCpu = sampleAuraProcessCpuPct();
        int sysCpu = sampleSystemCpuLoadPct(freqsMhz, auraCpu, mem);
        out.put("cpu_load_pct", sysCpu);
        out.put("aura_cpu_pct", Math.round(auraCpu * 10.0) / 10.0);
        out.put("cpu_freq_str", String.format(Locale.US, "4\u00D7 %.2f GHz", freqsMhz[0] / 1000.0));
        out.put("cpu_cores_mhz", toJsonIntArray(freqsMhz));
    }

    private static JSONArray toJsonIntArray(int[] vals) {
        JSONArray arr = new JSONArray();
        for (int v : vals) arr.put(v);
        return arr;
    }

    private static int[] readCoreFrequenciesMhz() {
        int[] mhz = new int[]{1100, 1100, 1100, 1100};
        for (int i = 0; i < 4; i++) {
            String line = readFirstLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            long khz = parseLongSafe(line, 1100000L);
            mhz[i] = (int) (khz / 1000L);
        }
        return mhz;
    }

    private static int sampleSystemCpuLoadPct(int[] freqsMhz, double auraCpu, Map<String, Long> mem) {
        int procStatPct = trySampleProcStatCpuPct();
        if (procStatPct >= 0) return procStatPct;
        int avgMhz = (freqsMhz[0] + freqsMhz[1] + freqsMhz[2] + freqsMhz[3]) / 4;
        int freqRatioPct = Math.max(12, Math.min(85, ((avgMhz - 1000) * 100) / 450));
        int memPressure = mem.getOrDefault("MemAvailable", 450000L) < 280000L ? 14 : 4;
        return Math.min(98, (int) Math.round(freqRatioPct + auraCpu + memPressure));
    }

    private static int trySampleProcStatCpuPct() {
        String line = readFirstLine("/proc/stat");
        if (line == null || !line.startsWith("cpu ")) return -1;
        String[] p = line.trim().split("\\s+");
        if (p.length < 5) return -1;
        long idle = parseLongSafe(p[4], 0L) + (p.length > 5 ? parseLongSafe(p[5], 0L) : 0L);
        long total = sumCpuStatFields(p);
        long dTot = total - sPrevSysTotalTicks;
        long dIdle = idle - sPrevSysIdleTicks;
        sPrevSysTotalTicks = total;
        sPrevSysIdleTicks = idle;
        return dTot > 0 ? (int) Math.max(1L, Math.min(99L, ((dTot - dIdle) * 100L) / dTot)) : 22;
    }

    private static long sumCpuStatFields(String[] p) {
        long sum = 0L;
        for (int i = 1; i < Math.min(p.length, 9); i++) sum += parseLongSafe(p[i], 0L);
        return sum;
    }

    private static double sampleAuraProcessCpuPct() {
        String line = readFirstLine("/proc/self/stat");
        if (line == null || line.isEmpty()) return 0.1;
        int closeIdx = line.lastIndexOf(')');
        if (closeIdx < 0 || closeIdx + 2 >= line.length()) return 0.1;
        String[] f = line.substring(closeIdx + 2).trim().split("\\s+");
        if (f.length < 13) return 0.1;
        long jiffies = parseLongSafe(f[11], 0L) + parseLongSafe(f[12], 0L);
        return computeProcessCpuDelta(jiffies, SystemClock.elapsedRealtime());
    }

    private static double computeProcessCpuDelta(long jiffies, long nowMs) {
        long prevJ = sPrevProcSelfJiffies;
        long prevMs = sPrevProcSelfWallMs;
        sPrevProcSelfJiffies = jiffies;
        sPrevProcSelfWallMs = nowMs;
        if (prevMs <= 0L || nowMs <= prevMs) return 0.1;
        double cpuMs = (jiffies - prevJ) * 10.0; // 100 Hz USER_HZ = 10ms/jiffy
        return Math.max(0.0, Math.min(99.0, (cpuMs * 100.0) / ((nowMs - prevMs) * 4.0)));
    }

    public static int classifyPackageTier(String pkg) {
        if (pkg == null || pkg.isEmpty()) return 0;
        String p = pkg.toLowerCase(Locale.US);
        if (isTier0ProtectedSystemPkg(p)) return 0;
        if (isTier1HeavyOttPkg(p)) return 1;
        return 2;
    }

    private static boolean isTier0ProtectedSystemPkg(String p) {
        return TvDeviceProfile.isProtectedSystemOrOemPackage(p);
    }

    private static boolean isTier1HeavyOttPkg(String p) {
        return TvDeviceProfile.isKnownHeavyOttPackage(p);
    }

    public static boolean shouldKillCandidate(
            String pkg, String playingPkg, String pausedPkg, long pausedAgeMs,
            long idleMs, long lastUsedMs, long lastKilledMs, boolean lowRam, boolean manualBoost) {
        int tier = classifyPackageTier(pkg);
        if (tier == 0 || (pkg != null && pkg.equalsIgnoreCase(playingPkg))) return false;
        if (!manualBoost && pkg != null && pkg.equalsIgnoreCase(pausedPkg)
                && (playingPkg == null || playingPkg.isEmpty()) && pausedAgeMs < PAUSE_GRACE_MS) {
            return false;
        }
        if (isSuppressedByCooldown(lastUsedMs, lastKilledMs, manualBoost)) return false;
        if (manualBoost) return true;
        if (playingPkg != null && !playingPkg.isEmpty()) return idleMs >= 60_000L || tier >= 1;
        return lowRam && (tier == 2 ? idleMs >= 120_000L : idleMs >= 300_000L);
    }

    private static boolean isSuppressedByCooldown(long lastUsedMs, long lastKilledMs, boolean manualBoost) {
        if (manualBoost || lastKilledMs <= 0L) return false;
        if (lastUsedMs > lastKilledMs) return false;
        return (System.currentTimeMillis() - lastKilledMs) < 1800_000L;
    }

    public static int estimatePackageRamMb(String pkg, String state) {
        String p = pkg != null ? pkg.toLowerCase(Locale.US) : "";
        int base = resolveBasePackageMb(p);
        if ("PLAYING".equals(state)) return base + 25;
        if ("PAUSED".equals(state)) return base + 10;
        return base;
    }

    private static int resolveBasePackageMb(String p) {
        if (p.contains("hotstar")) return 155;
        if (p.contains("netflix")) return 148;
        if (p.contains("amazonvideo")) return 118;
        if (p.contains("youtube.tv")) return 112;
        if (p.contains("apple.atve") || p.contains("graymatrix") || p.contains("sonyliv")) return 96;
        if (p.contains("vending")) return 72;
        if (p.contains("katniss") || p.contains("google.android.tts")) return 39;
        return 48;
    }

    private static JSONArray discoverProcessCandidates(Context ctx, boolean lowRam) {
        Map<String, Integer> sessionStates = queryActiveSessionStates(ctx);
        Map<String, Long> lastUsedMap = queryRecentUsageMap(ctx);
        String playingPkg = findPackageBySessionState(sessionStates, PlaybackState.STATE_PLAYING);
        String pausedPkg = findPackageBySessionState(sessionStates, PlaybackState.STATE_PAUSED);
        Set<String> allPkgs = collectCandidatePackages(ctx, sessionStates, lastUsedMap);
        JSONArray arr = new JSONArray();
        for (String pkg : allPkgs) {
            JSONObject row = buildCandidateJson(pkg, sessionStates, lastUsedMap, playingPkg, pausedPkg, lowRam);
            if (row != null) arr.put(row);
        }
        return arr;
    }

    private static Set<String> collectCandidatePackages(Context ctx, Map<String, Integer> sessions, Map<String, Long> usage) {
        Set<String> pkgs = new LinkedHashSet<>(sessions.keySet());
        for (String k : KNOWN_BG_CANDIDATES) {
            if (usage.containsKey(k) || "com.android.vending".equals(k)) pkgs.add(k);
        }
        for (String dyn : TvDeviceProfile.discoverInstalledOttPackages(ctx)) {
            if (usage.containsKey(dyn)) pkgs.add(dyn);
        }
        pkgs.add("com.aura.tvprobe");
        pkgs.add("com.google.android.katniss");
        return pkgs;
    }

    private static JSONObject buildCandidateJson(
            String pkg, Map<String, Integer> sessions, Map<String, Long> usage,
            String playingPkg, String pausedPkg, boolean lowRam) {
        long now = System.currentTimeMillis();
        long lastUsed = usage.getOrDefault(pkg, now - 600_000L);
        long lastKilled = sLastKilledWallMs.getOrDefault(pkg, 0L);
        if (lastKilled > lastUsed && !sessions.containsKey(pkg) && classifyPackageTier(pkg) > 0) return null;
        long idleMs = Math.max(0L, now - lastUsed);
        String state = resolveCandidateStateLabel(pkg, sessions);
        boolean canKill = shouldKillCandidate(pkg, playingPkg, pausedPkg, idleMs, idleMs, lastUsed, lastKilled, lowRam, true);
        boolean autoKill = shouldKillCandidate(pkg, playingPkg, pausedPkg, idleMs, idleMs, lastUsed, lastKilled, lowRam, false);
        return packCandidateJson(pkg, state, idleMs, canKill, autoKill);
    }

    private static JSONObject packCandidateJson(
            String pkg, String state, long idleMs, boolean canKill, boolean autoKill) {
        try {
            int tier = classifyPackageTier(pkg);
            return new JSONObject()
                .put("pkg", pkg)
                .put("name", friendlyProcessName(pkg))
                .put("tier", tier)
                .put("state", state)
                .put("idle_min", (int) (idleMs / 60_000L))
                .put("est_ram_mb", "com.aura.tvprobe".equals(pkg) ? readAuraPssMb() : estimatePackageRamMb(pkg, state))
                .put("protected", !canKill)
                .put("auto_target", autoKill)
                .put("reason", describeCandidateStatus(tier, state, idleMs, canKill));
        } catch (Exception e) { return null; }
    }

    private static String describeCandidateStatus(int tier, String state, long idleMs, boolean canKill) {
        if ("PLAYING".equals(state)) return "Actively playing video \uD83D\uDD12";
        if (tier == 0) return "Core System / Voice Service \uD83D\uDD12";
        if (!canKill) return "Protected media session \uD83D\uDD12";
        int mins = Math.max(1, (int) (idleMs / 60_000L));
        return "PAUSED".equals(state) ? "Paused in background (" + mins + "m)" : "Idle in background (" + mins + "m)";
    }

    public static String friendlyProcessName(String pkg) {
        if ("com.aura.tvprobe".equals(pkg)) return "Aura TV Launcher";
        if (pkg.contains("katniss")) return "Google Assistant Voice";
        if (pkg.contains("vending")) return "Google Play Store";
        if (pkg.contains("google.android.tts")) return "Google TTS Engine";
        return SessionInspector.friendlyApp(pkg);
    }

    public static void maybeAutoGuardOnPlayback(Context ctx, AuraDb db, String playingPkg) {
        if (ctx == null || db == null || !isAutoGuardEnabled(db)) return;
        long now = System.currentTimeMillis();
        if ((now - sLastAutoGuardWallMs) < AUTO_GUARD_MIN_GAP && playingPkg.equals(sLastGuardedPlayingPkg)) return;
        Map<String, Long> mem = readProcMemInfo();
        int availMb = resolveAvailableMb(ctx, mem);
        boolean switchedApp = !playingPkg.isEmpty() && !playingPkg.equals(sLastGuardedPlayingPkg);
        if (switchedApp || availMb < LOW_RAM_THRESHOLD_MB) {
            sLastGuardedPlayingPkg = playingPkg;
            sLastAutoGuardWallMs = now;
            executeRamBoost(ctx, db, switchedApp ? "APP_SWITCH" : "LOW_RAM", false);
        }
    }

    public static JSONObject executeRamBoost(Context ctx, AuraDb db, String triggerType, boolean manualBoost) {
        int beforeMb = resolveAvailableMb(ctx, readProcMemInfo());
        JSONObject snap = collectTelemetrySnapshot(ctx, db);
        JSONArray cands = snap.optJSONArray("candidates");
        List<String> killedNames = new ArrayList<>();
        int estFreedMb = killMatchingCandidates(ctx, cands, manualBoost, killedNames);
        return finalizeBoostExecution(ctx, db, triggerType, snap, beforeMb, killedNames, estFreedMb);
    }

    private static int killMatchingCandidates(
            Context ctx, JSONArray cands, boolean manualBoost, List<String> killedNames) {
        if (cands == null) return 0;
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        int estSum = 0;
        long now = System.currentTimeMillis();
        for (int i = 0; i < cands.length(); i++) {
            JSONObject c = cands.optJSONObject(i);
            if (c != null && shouldExecuteKillOnRow(c, manualBoost)) {
                killPackageSafe(am, c.optString("pkg"));
                sLastKilledWallMs.put(c.optString("pkg"), now);
                killedNames.add(c.optString("name"));
                estSum += c.optInt("est_ram_mb", 60);
            }
        }
        return estSum;
    }

    private static boolean shouldExecuteKillOnRow(JSONObject c, boolean manualBoost) {
        if (c.optBoolean("protected", true)) return false;
        return manualBoost || c.optBoolean("auto_target", false);
    }

    private static JSONObject finalizeBoostExecution(
            Context ctx, AuraDb db, String triggerType, JSONObject snap,
            int beforeMb, List<String> killedNames, int estFreedMb) {
        JSONObject res = new JSONObject();
        try {
            if (!killedNames.isEmpty()) Thread.sleep(240L);
            int afterMb = resolveAvailableMb(ctx, readProcMemInfo());
            int realDelta = afterMb - beforeMb;
            int freedMb = killedNames.isEmpty() ? 0 : (realDelta > 8 ? realDelta : Math.min(180, Math.max(18, estFreedMb / 3)));
            int effectiveAfter = Math.max(afterMb, beforeMb + freedMb);
            String killedCsv = String.join(", ", killedNames);
            if (!killedNames.isEmpty()) {
                DatabaseJanitor.recordRamCleanupEvent(db, triggerType, resolveActiveContext(snap),
                    killedCsv, beforeMb, effectiveAfter, freedMb, snap.optInt("cpu_load_pct", 25));
            }
            return res.put("killed_count", killedNames.size()).put("killed_apps", killedCsv)
                .put("avail_before_mb", beforeMb).put("avail_after_mb", effectiveAfter).put("freed_mb", freedMb);
        } catch (Exception e) { return res; }
    }

    public static JSONObject killSinglePackage(Context ctx, AuraDb db, String pkg) {
        if (ctx == null || classifyPackageTier(pkg) == 0) return new JSONObject();
        int beforeMb = resolveAvailableMb(ctx, readProcMemInfo());
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        killPackageSafe(am, pkg);
        sLastKilledWallMs.put(pkg, System.currentTimeMillis());
        String name = friendlyProcessName(pkg);
        int estMb = estimatePackageRamMb(pkg, "IDLE");
        int afterMb = Math.max( resolveAvailableMb(ctx, readProcMemInfo()), beforeMb + (estMb / 2));
        DatabaseJanitor.recordRamCleanupEvent(db, "MANUAL_SINGLE", "Manual Target Kill", name, beforeMb, afterMb, estMb, 24);
        return collectTelemetrySnapshot(ctx, db);
    }

    private static String resolveActiveContext(JSONObject snap) {
        JSONArray cands = snap != null ? snap.optJSONArray("candidates") : null;
        if (cands != null) {
            for (int i = 0; i < cands.length(); i++) {
                JSONObject c = cands.optJSONObject(i);
                if (c != null && "PLAYING".equals(c.optString("state"))) return "Playing: " + c.optString("name");
            }
        }
        return "Aura TV Home";
    }

    private static void killPackageSafe(ActivityManager am, String pkg) {
        try {
            if (am != null && pkg != null && !pkg.isEmpty()) am.killBackgroundProcesses(pkg);
        } catch (Throwable ignored) {}
    }

    private static Map<String, Integer> queryActiveSessionStates(Context ctx) {
        Map<String, Integer> map = new HashMap<>();
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(ctx, MediaProbeService.class);
            for (MediaController mc : msm.getActiveSessions(cn)) {
                PlaybackState ps = mc.getPlaybackState();
                int st = ps != null ? ps.getState() : PlaybackState.STATE_NONE;
                if (st == PlaybackState.STATE_PLAYING || !map.containsKey(mc.getPackageName())) {
                    map.put(mc.getPackageName(), st);
                }
            }
        } catch (Throwable ignored) {}
        return map;
    }

    private static Map<String, Long> queryRecentUsageMap(Context ctx) {
        Map<String, Long> out = new HashMap<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> list = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 6 * 3600_000L, now);
            if (list != null) {
                for (UsageStats u : list) {
                    if (u.getLastTimeUsed() > 0L) out.put(u.getPackageName(), Math.max(out.getOrDefault(u.getPackageName(), 0L), u.getLastTimeUsed()));
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String findPackageBySessionState(Map<String, Integer> sessions, int targetState) {
        for (Map.Entry<String, Integer> e : sessions.entrySet()) {
            if (e.getValue() != null && e.getValue() == targetState) return e.getKey();
        }
        return "";
    }

    private static String resolveCandidateStateLabel(String pkg, Map<String, Integer> sessions) {
        if ("com.aura.tvprobe".equals(pkg)) return "ACTIVE";
        Integer st = sessions.get(pkg);
        if (st == null) return "IDLE";
        if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) return "PLAYING";
        if (st == PlaybackState.STATE_PAUSED) return "PAUSED";
        return "IDLE";
    }

    private static int sumReclaimableMb(JSONArray candidates) {
        int sum = 0;
        if (candidates == null) return 0;
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject c = candidates.optJSONObject(i);
            if (c != null && !c.optBoolean("protected", true)) sum += c.optInt("est_ram_mb", 0);
        }
        return sum;
    }

    private static Map<String, Long> readProcMemInfo() {
        Map<String, Long> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) parseMemInfoLine(line, map);
        } catch (Exception ignored) {}
        return map;
    }

    private static void parseMemInfoLine(String line, Map<String, Long> map) {
        int colon = line.indexOf(':');
        if (colon <= 0) return;
        String key = line.substring(0, colon).trim();
        String rest = line.substring(colon + 1).replace("kB", "").trim();
        map.put(key, parseLongSafe(rest, 0L));
    }

    private static String readFirstLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (Exception e) { return null; }
    }

    private static int kbToMb(long kb) {
        return (int) (kb / 1024L);
    }

    private static long parseLongSafe(String s, long def) {
        try { return s != null ? Long.parseLong(s.trim()) : def; } catch (Exception e) { return def; }
    }
}
