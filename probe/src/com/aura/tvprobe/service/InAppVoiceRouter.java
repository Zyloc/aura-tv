package com.aura.tvprobe;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Intercepts global remote voice transcripts while inside external TV apps,
 * classifies intent (In-App Search vs In-App Show Companion vs Home vs Control),
 * and routes without yanking the user back to the Aura Launcher.
 */
public final class InAppVoiceRouter {

    public enum VoiceAction {
        COMPANION,
        IN_APP_SEARCH,
        GO_HOME,
        CONTROL
    }

    public static final class VoiceDecision {
        public final VoiceAction action;
        public final String cleanQuery;

        public VoiceDecision(VoiceAction action, String cleanQuery) {
            this.action = action;
            this.cleanQuery = cleanQuery != null ? cleanQuery.trim() : "";
        }
    }

    private static final ExecutorService WORKER = Executors.newCachedThreadPool();
    private static volatile Handler sMainHandler;
    private static volatile String sLastTranscript = "";
    private static volatile long sLastTranscriptMs = 0L;
    private static volatile long sLastInAppInterceptMs = 0L;
    private static volatile long sLastTakeoverHandledMs = 0L;
    private static volatile String sLastInterceptedPkg = "";
    private static volatile Future<?> sActiveCompanionTask;
    private static volatile int sCompanionRunId = 0;

    private InAppVoiceRouter() {}

    private static Handler getMainHandlerSafe() {
        if (sMainHandler != null) return sMainHandler;
        try { sMainHandler = new Handler(Looper.getMainLooper()); } catch (Throwable ignored) {}
        return sMainHandler;
    }

    private static void postToMain(Runnable r, long delayMs) {
        Handler h = getMainHandlerSafe();
        if (h == null) return;
        if (delayMs <= 0L) h.post(r);
        else h.postDelayed(r, delayMs);
    }

    public static void onVoiceTranscriptCaptured(
            Context ctx, AuraDb db, AiRecommender ai, SessionInspector inspector,
            TvOverlayManager overlay, String rawSpoken) {
        String spoken = rawSpoken != null ? rawSpoken.trim() : "";
        if (!acceptTranscriptDedup(spoken)) return;
        String fgPkg = resolveActiveExternalPackage(ctx);
        if (AuraActivity.isInForeground() || fgPkg.isEmpty()) {
            routeWhileOnLauncher(ctx, overlay, spoken);
            return;
        }
        routeWhileInsideExternalApp(ctx, db, ai, inspector, overlay, fgPkg, spoken);
    }

    private static synchronized boolean acceptTranscriptDedup(String spoken) {
        long now = System.currentTimeMillis();
        if (spoken.length() < 2) return false;
        if (spoken.equalsIgnoreCase(sLastTranscript) && (now - sLastTranscriptMs) < 3_200L) return false;
        sLastTranscript = spoken;
        sLastTranscriptMs = now;
        return true;
    }

    private static void routeWhileOnLauncher(Context ctx, TvOverlayManager overlay, String spoken) {
        if (ShowCompanionAgent.isShowCompanionQuery(spoken)) {
            VoiceSearchHelper.silenceAssistantAudio(ctx, getMainHandlerSafe());
            if (overlay != null) {
                overlay.showVoiceStatusPill("\u2726 AURA SHOW COMPANION",
                    "Catch Me Up works inside your app",
                    "Open your show in Netflix, Hotstar, Prime, or YouTube and ask again.", 6);
            }
            return;
        }
        AuraActivity.onGlobalVoiceSearchQuery(ctx, spoken);
    }

    private static void routeWhileInsideExternalApp(
            Context ctx, AuraDb db, AiRecommender ai, SessionInspector inspector,
            TvOverlayManager overlay, String fgPkg, String spoken) {
        String low = spoken.toLowerCase(Locale.US);
        if ((AuraTtsNarrator.isSpeaking() || isCompanionRunning()) && isStopNarrationPhrase(low)) {
            cancelActiveCompanionTurn(ctx, overlay, fgPkg);
            return;
        }
        if (isControlPhrase(low)) return;
        beginInAppInterception(ctx, db, ai, inspector, overlay, fgPkg, spoken);
    }

    private static boolean isCompanionRunning() {
        Future<?> f = sActiveCompanionTask;
        return f != null && !f.isDone();
    }

    public static synchronized void cancelActiveCompanionTurn(Context ctx, TvOverlayManager overlay, String fgPkg) {
        sCompanionRunId++;
        if (sActiveCompanionTask != null) {
            sActiveCompanionTask.cancel(true);
            sActiveCompanionTask = null;
        }
        if (fgPkg != null && !fgPkg.isEmpty()) suppressKatnissOnceInApp(ctx, fgPkg);
        AuraTtsNarrator.stop(ctx);
        if (overlay != null) overlay.dismissCompanionOverlays();
    }

    private static void beginInAppInterception(
            Context ctx, AuraDb db, AiRecommender ai, SessionInspector inspector,
            TvOverlayManager overlay, String fgPkg, String spoken) {
        sLastInAppInterceptMs = System.currentTimeMillis();
        sLastInterceptedPkg = fgPkg;
        startKatnissHijackGuardian(ctx, fgPkg);
        String appLabel = resolveAppLabel(ctx, fgPkg);
        showInitialVoiceOverlay(ctx, overlay, fgPkg, appLabel, spoken);
        final int runId = ++sCompanionRunId;
        sActiveCompanionTask = WORKER.submit(() ->
            executeClassifiedAction(ctx, db, ai, inspector, overlay, fgPkg, appLabel, spoken, runId));
    }

    private static void showInitialVoiceOverlay(
            Context ctx, TvOverlayManager overlay, String fgPkg, String appLabel, String spoken) {
        if (overlay == null) return;
        if (ShowCompanionAgent.isShowCompanionQuery(spoken)) {
            overlay.showCompanionLoader(spoken, "Locking " + appLabel + " & checking timestamp\u2026",
                () -> cancelActiveCompanionTurn(ctx, overlay, fgPkg));
        } else {
            overlay.showVoiceStatusPill("\uD83C\uDFA4 AURA VOICE \u00B7 " + appLabel.toUpperCase(Locale.US),
                "\u201C" + spoken + "\u201D", "Checking intent in " + appLabel + "\u2026", 6);
        }
    }

    private static void executeClassifiedAction(
            Context ctx, AuraDb db, AiRecommender ai, SessionInspector inspector,
            TvOverlayManager overlay, String fgPkg, String appLabel, String spoken, int runId) {
        VoiceDecision dec = classifyIntent(ai, spoken, appLabel);
        if (runId != sCompanionRunId || Thread.currentThread().isInterrupted()) return;
        dispatchVoiceDecision(ctx, db, ai, inspector, overlay, fgPkg, appLabel, spoken, runId, dec);
    }

    private static void dispatchVoiceDecision(
            Context ctx, AuraDb db, AiRecommender ai, SessionInspector inspector,
            TvOverlayManager overlay, String fgPkg, String appLabel, String spoken, int runId, VoiceDecision dec) {
        if (dec.action == VoiceAction.GO_HOME) {
            handleGoHomeDecision(ctx, overlay);
        } else if (dec.action == VoiceAction.COMPANION) {
            runInAppCompanionFlow(ctx, db, ai, inspector, overlay, fgPkg, spoken, runId);
        } else if (dec.action == VoiceAction.IN_APP_SEARCH) {
            executeInAppSearchRedirect(ctx, db, overlay, fgPkg, appLabel, dec.cleanQuery);
        } else if (overlay != null) {
            overlay.dismissOverlay();
        }
    }

    private static void handleGoHomeDecision(Context ctx, TvOverlayManager overlay) {
        if (overlay != null) overlay.dismissOverlay();
        AuraActivity.onHardwareHomeKeyPressed(ctx);
    }

    private static void executeInAppSearchRedirect(
            Context ctx, AuraDb db, TvOverlayManager overlay,
            String fgPkg, String appLabel, String cleanQuery) {
        String q = cleanQuery.isEmpty() ? sLastTranscript : cleanQuery;
        String directUri = JustWatchGraph.lookupDirectProviderUri(fgPkg, q);
        if (overlay != null) {
            overlay.showVoiceStatusPill("\uD83D\uDD0E " + appLabel.toUpperCase(Locale.US) + " SEARCH",
                "\u201C" + q + "\u201D", "Opening in " + appLabel + "\u2026", 4);
        }
        postToMain(() -> MediaDeepLinkLauncher.launchInAppSearchWithResolvedUri(ctx, db, fgPkg, q, directUri), 0L);
        postToMain(() -> suppressKatnissOnceInApp(ctx, fgPkg), 360L);
    }

    private static void runInAppCompanionFlow(
            Context ctx, AuraDb db, AiRecommender ai, SessionInspector inspector,
            TvOverlayManager overlay, String fgPkg, String spoken, int runId) {
        JSONObject activeItem = prepareCompanionSessionContext(ctx, db, inspector, overlay, fgPkg, spoken);
        String profile = db != null ? db.getValidActiveProfile() : AuraDb.DEFAULT_PROFILE;
        JSONObject recap = ShowCompanionAgent.executeCompanionTurn(
            ai, db, profile, spoken, activeItem, overlay != null ? overlay::updateVoiceStatusSubtext : null);
        if (runId != sCompanionRunId || Thread.currentThread().isInterrupted()) return;
        if (overlay != null && recap != null) {
            overlay.showInAppCompanionRecap(recap, () -> cancelActiveCompanionTurn(ctx, overlay, fgPkg));
        }
    }

    private static JSONObject prepareCompanionSessionContext(
            Context ctx, AuraDb db, SessionInspector inspector,
            TvOverlayManager overlay, String fgPkg, String spoken) {
        ensureCompanionLoaderMounted(ctx, overlay, fgPkg, spoken);
        pausePackageSessionGently(ctx, fgPkg);
        if (inspector != null) inspector.recordActivePlaybackOnly();
        String profile = db != null ? db.getValidActiveProfile() : AuraDb.DEFAULT_PROFILE;
        return findActiveItemForPackage(ctx, db, inspector, profile, fgPkg);
    }

    private static void ensureCompanionLoaderMounted(
            Context ctx, TvOverlayManager overlay, String fgPkg, String spoken) {
        if (overlay != null && !ShowCompanionAgent.isShowCompanionQuery(spoken)) {
            overlay.showCompanionLoader(spoken, "Checking active show & timestamp\u2026",
                () -> cancelActiveCompanionTurn(ctx, overlay, fgPkg));
        }
    }

    public static void onKatnissTakeoverDetected(Context ctx) {
        if (AuraActivity.isInForeground()) {
            AuraActivity.suppressKatnissOverlayNow(ctx);
            return;
        }
        long now = System.currentTimeMillis();
        if ((now - sLastInAppInterceptMs) < 12_000L && (now - sLastTakeoverHandledMs) > 2_500L) {
            sLastTakeoverHandledMs = now;
            guardInterceptedAppOnce(ctx, sLastInterceptedPkg);
        }
    }

    public static void suppressKatnissInAppBurst(Context ctx, String fgPkg) {
        startKatnissHijackGuardian(ctx, fgPkg);
    }

    private static void startKatnissHijackGuardian(Context ctx, String fgPkg) {
        VoiceSearchHelper.silenceAssistantAudio(ctx, getMainHandlerSafe());
        suppressKatnissOnceInApp(ctx, fgPkg);
        for (int step = 1; step <= 32; step++) {
            postToMain(() -> guardInterceptedAppOnce(ctx, fgPkg), step * 280L);
        }
    }

    private static void guardInterceptedAppOnce(Context ctx, String fgPkg) {
        if (ctx == null || fgPkg == null || fgPkg.isEmpty()) return;
        if ((System.currentTimeMillis() - sLastInAppInterceptMs) > 10_500L) return;
        String topPkg = queryRawLatestForegroundPkg(ctx);
        if (isHijackForegroundPkg(topPkg, fgPkg)) {
            evictHijackAndRestoreApp(ctx, topPkg, fgPkg);
        }
    }

    public static boolean isHijackForegroundPkg(String topPkg, String expectedFgPkg) {
        if (topPkg == null || topPkg.isEmpty() || expectedFgPkg == null || expectedFgPkg.isEmpty()) return false;
        if (topPkg.startsWith("com.google.android.katniss")) return true;
        return topPkg.contains("youtube") && !expectedFgPkg.contains("youtube");
    }

    private static void evictHijackAndRestoreApp(Context ctx, String hijackPkg, String fgPkg) {
        if (hijackPkg.contains("youtube")) pausePackageSessionGently(ctx, hijackPkg);
        refrontInterceptedApp(ctx, fgPkg);
        postToMain(() -> suppressKatnissOnceInApp(ctx, fgPkg), 180L);
    }

    private static void refrontInterceptedApp(Context ctx, String fgPkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent intent = pm.getLeanbackLaunchIntentForPackage(fgPkg);
            if (intent == null) intent = pm.getLaunchIntentForPackage(fgPkg);
            if (intent == null) return;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(intent);
        } catch (Throwable ignored) {}
    }

    private static String queryRawLatestForegroundPkg(Context ctx) {
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return "";
            long now = System.currentTimeMillis();
            return scanRawMoveToForeground(usm.queryEvents(now - 12_000L, now));
        } catch (Throwable ignored) { return ""; }
    }

    private static String scanRawMoveToForeground(UsageEvents events) {
        if (events == null) return "";
        UsageEvents.Event ev = new UsageEvents.Event();
        String latest = "";
        while (events.hasNextEvent()) {
            events.getNextEvent(ev);
            if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && ev.getPackageName() != null) {
                latest = ev.getPackageName();
            }
        }
        return latest;
    }

    private static void suppressKatnissOnceInApp(Context ctx, String fgPkg) {
        if (ctx == null) return;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) killAssistantProcessesSafe(am, fgPkg);
            ctx.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        } catch (Throwable ignored) {}
    }

    private static void killAssistantProcessesSafe(ActivityManager am, String fgPkg) {
        am.killBackgroundProcesses("com.google.android.katniss");
        if (fgPkg == null || !fgPkg.contains("youtube")) {
            am.killBackgroundProcesses("com.google.android.youtube.tv");
        }
    }

    // ── Intent Classification & Query Cleaning ───────────────────────────

    public static VoiceDecision classifyIntent(AiRecommender ai, String rawSpoken, String appLabel) {
        String raw = rawSpoken != null ? rawSpoken.trim() : "";
        if (raw.isEmpty()) return new VoiceDecision(VoiceAction.CONTROL, "");
        VoiceDecision fast = classifyDeterministicFastPath(raw, appLabel);
        if (fast != null) return fast;
        VoiceDecision llm = tryClassifyViaFastLlm(ai, raw, appLabel);
        if (llm != null) return llm;
        return new VoiceDecision(VoiceAction.IN_APP_SEARCH, extractCleanSearchQuery(raw, appLabel));
    }

    public static VoiceDecision classifyDeterministicFastPath(String raw, String appLabel) {
        String low = raw.toLowerCase(Locale.US).trim();
        if (ShowCompanionAgent.isShowCompanionQuery(raw)) return new VoiceDecision(VoiceAction.COMPANION, raw);
        if (isGoHomePhrase(low)) return new VoiceDecision(VoiceAction.GO_HOME, "");
        if (isControlPhrase(low)) return new VoiceDecision(VoiceAction.CONTROL, "");
        if (hasExplicitSearchPrefix(low)) {
            return new VoiceDecision(VoiceAction.IN_APP_SEARCH, extractCleanSearchQuery(raw, appLabel));
        }
        return null;
    }

    private static VoiceDecision tryClassifyViaFastLlm(AiRecommender ai, String raw, String appLabel) {
        if (ai == null) return null;
        try {
            Future<VoiceDecision> fut = WORKER.submit(() -> callLlmIntentClassifier(ai, raw, appLabel));
            return fut.get(1600L, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static VoiceDecision callLlmIntentClassifier(AiRecommender ai, String raw, String appLabel) {
        String prompt = buildIntentPrompt(raw, appLabel);
        String jsonStr = ai.callGeminiMultimodalJson(prompt, null);
        return parseLlmDecisionJson(jsonStr, raw, appLabel);
    }

    private static String buildIntentPrompt(String raw, String appLabel) {
        String app = (appLabel == null || appLabel.isEmpty()) ? "streaming app" : appLabel;
        return "User spoke \"" + raw + "\" on Android TV inside " + app + ".\n" +
            "Classify intent into ONE of: SEARCH (movie/show/actor/genre/topic search), " +
            "COMPANION (recap, story so far, explain scene, who is this character/actor on screen), " +
            "HOME (go to launcher/home screen), CONTROL (pause/play/volume/mute/subtitles).\n" +
            "Return ONLY JSON: {\"intent\":\"SEARCH\",\"query\":\"clean search query without filler or app name\"}";
    }

    public static VoiceDecision parseLlmDecisionJson(String jsonStr, String rawFallback, String appLabel) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) return null;
        try {
            JSONObject obj = new JSONObject(jsonStr);
            String intent = obj.optString("intent", "SEARCH").toUpperCase(Locale.US);
            String q = obj.optString("query", "").trim();
            if ("COMPANION".equals(intent)) return new VoiceDecision(VoiceAction.COMPANION, rawFallback);
            if ("HOME".equals(intent)) return new VoiceDecision(VoiceAction.GO_HOME, "");
            if ("CONTROL".equals(intent)) return new VoiceDecision(VoiceAction.CONTROL, "");
            return new VoiceDecision(VoiceAction.IN_APP_SEARCH, q.isEmpty() ? extractCleanSearchQuery(rawFallback, appLabel) : q);
        } catch (Exception e) { return null; }
    }

    public static boolean isGoHomePhrase(String low) {
        if (low == null) return false;
        String c = low.replaceAll("[?.!]+$", "").trim();
        if (c.equals("home") || c.equals("go home") || c.equals("go to home")
                || c.equals("home screen") || c.equals("launcher") || c.equals("go to launcher")) return true;
        return c.contains("open aura") || c.contains("aura launcher") || c.contains("aura home")
            || c.equals("go back home") || c.equals("back to home") || c.equals("back to launcher");
    }

    public static boolean isControlPhrase(String low) {
        if (low == null || low.isEmpty()) return false;
        if (isPlaybackControlWord(low) || isAudioOrPowerControlWord(low)) return true;
        return low.equals("skip intro") || low.equals("next episode")
            || low.startsWith("fast forward") || low.startsWith("rewind")
            || low.contains("turn on subtitles") || low.contains("turn off subtitles");
    }

    private static boolean isPlaybackControlWord(String low) {
        return low.equals("pause") || low.equals("pause video") || low.equals("play")
            || low.equals("resume") || low.equals("stop") || low.equals("continue playing");
    }

    private static boolean isAudioOrPowerControlWord(String low) {
        return low.equals("mute") || low.equals("unmute") || low.startsWith("volume ")
            || low.equals("louder") || low.equals("quieter") || low.equals("turn off tv")
            || low.equals("sleep");
    }

    private static boolean isStopNarrationPhrase(String low) {
        return low.equals("stop") || low.equals("quiet") || low.equals("cancel")
            || low.contains("stop recap") || low.contains("stop talking");
    }

    private static boolean hasExplicitSearchPrefix(String low) {
        return low.startsWith("search ") || low.startsWith("find ") || low.startsWith("look up ")
            || low.startsWith("look for ") || low.startsWith("show me ") || low.startsWith("can you search ");
    }

    public static String extractCleanSearchQuery(String rawSpoken, String appLabel) {
        if (rawSpoken == null) return "";
        String s = rawSpoken.trim().replaceAll("[?.!]+$", "").trim();
        s = stripLeadingConversationalFiller(s);
        s = stripLeadingSearchVerb(s);
        s = stripTrailingAppAndPoliteSuffix(s, appLabel);
        String cleaned = s.replaceAll("[?.!]+$", "").trim();
        return cleaned.isEmpty() ? rawSpoken.trim() : cleaned;
    }

    private static String stripLeadingConversationalFiller(String s) {
        return s.replaceFirst("(?i)^(hey google|ok google|okay google)[,\\s]*", "")
            .replaceFirst("(?i)^(can you please|could you please|can you|could you|please)\\s+", "")
            .trim();
    }

    private static String stripLeadingSearchVerb(String s) {
        return s.replaceFirst("(?i)^(search for|search|find me|find|look up|look for|show me|play|watch)\\s+", "")
            .trim();
    }

    private static String stripTrailingAppAndPoliteSuffix(String s, String appLabel) {
        String out = s.replaceFirst("(?i)\\s+(in|on)\\s+(this app|hotstar|jiohotstar|disney\\+?\\s*hotstar|disney plus|netflix|prime video|amazon prime|prime|youtube|smarttube|sonyliv|sony liv|apple tv\\+?|zee5)(\\s+please)?$", "");
        if (appLabel != null && !appLabel.trim().isEmpty()) {
            String esc = java.util.regex.Pattern.quote(appLabel.trim());
            out = out.replaceFirst("(?i)\\s+(in|on)\\s+" + esc + "(\\s+please)?$", "");
        }
        return out.replaceFirst("(?i)\\s+please$", "").trim();
    }

    // ── Foreground External App & Active Playback Helpers ────────────────

    public static String resolveActiveExternalPackage(Context ctx) {
        if (ctx == null || AuraActivity.isInForeground()) return "";
        String fromEvents = queryRecentForegroundEventPkg(ctx);
        if (!fromEvents.isEmpty()) return fromEvents;
        String fromStats = queryRecentUsageStatsPkg(ctx);
        if (!fromStats.isEmpty()) return fromStats;
        return queryActiveMediaSessionPkg(ctx);
    }

    private static String queryRecentForegroundEventPkg(Context ctx) {
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return "";
            long now = System.currentTimeMillis();
            return scanForegroundEvents(usm.queryEvents(now - 600_000L, now));
        } catch (Throwable ignored) { return ""; }
    }

    private static String scanForegroundEvents(UsageEvents events) {
        if (events == null) return "";
        UsageEvents.Event ev = new UsageEvents.Event();
        String latest = "";
        while (events.hasNextEvent()) {
            events.getNextEvent(ev);
            if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && !isIgnoredForegroundPackage(ev.getPackageName())) {
                latest = ev.getPackageName();
            }
        }
        return latest;
    }

    private static String queryRecentUsageStatsPkg(Context ctx) {
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return "";
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 600_000L, now);
            return pickLatestExternalUsageStat(stats);
        } catch (Throwable ignored) { return ""; }
    }

    private static String pickLatestExternalUsageStat(List<UsageStats> stats) {
        if (stats == null) return "";
        UsageStats best = null;
        for (UsageStats s : stats) {
            if (!isIgnoredForegroundPackage(s.getPackageName())
                    && (best == null || s.getLastTimeUsed() > best.getLastTimeUsed())) {
                best = s;
            }
        }
        return best != null ? best.getPackageName() : "";
    }

    private static String queryActiveMediaSessionPkg(Context ctx) {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return "";
            ComponentName comp = new ComponentName(ctx, MediaProbeService.class);
            for (MediaController mc : msm.getActiveSessions(comp)) {
                if (mc != null && !isIgnoredForegroundPackage(mc.getPackageName())) return mc.getPackageName();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    public static boolean isIgnoredForegroundPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if ("com.aura.tvprobe".equals(pkg) || "android".equals(pkg)) return true;
        if (pkg.startsWith("com.google.android.katniss") || pkg.startsWith("com.google.android.tts")) return true;
        if (pkg.startsWith("com.google.android.inputmethod") || pkg.startsWith("com.google.android.gms")) return true;
        if (pkg.startsWith("com.android.systemui") || pkg.startsWith("com.android.providers.tv")) return true;
        return pkg.equals("com.google.android.tvlauncher") || pkg.equals("com.mitv.tvhome.atv") || pkg.startsWith("com.xiaomi.");
    }

    public static String resolveAppLabel(Context ctx, String pkg) {
        String known = mapKnownStreamingLabel(pkg);
        if (!known.isEmpty() || ctx == null) return !known.isEmpty() ? known : "App";
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence lbl = pm.getApplicationLabel(ai);
            return lbl != null && lbl.length() > 0 ? lbl.toString() : pkg;
        } catch (Throwable t) { return pkg; }
    }

    public static String mapKnownStreamingLabel(String pkg) {
        if (pkg == null) return "";
        if ("in.startv.hotstar".equals(pkg)) return "Disney+ Hotstar";
        if ("com.disney.disneyplus".equals(pkg)) return "Disney+";
        if ("com.netflix.ninja".equals(pkg)) return "Netflix";
        if (pkg.startsWith("com.amazon.amazonvideo") || pkg.startsWith("com.amazon.avod")) return "Prime Video";
        return mapSecondaryStreamingLabel(pkg);
    }

    private static String mapSecondaryStreamingLabel(String pkg) {
        if (pkg.contains("youtube")) return "YouTube";
        if (pkg.contains("smarttube")) return "SmartTube";
        if ("com.sonyliv".equals(pkg)) return "SonyLIV";
        if ("com.apple.atve.androidtv.appletv".equals(pkg)) return "Apple TV+";
        if ("com.graymatrix.did".equals(pkg)) return "ZEE5";
        return "";
    }

    private static void pausePackageSessionGently(Context ctx, String fgPkg) {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            for (MediaController mc : msm.getActiveSessions(new ComponentName(ctx, MediaProbeService.class))) {
                pauseControllerIfPlaying(mc, fgPkg);
            }
        } catch (Throwable ignored) {}
    }

    private static void pauseControllerIfPlaying(MediaController mc, String fgPkg) {
        if (mc == null || (fgPkg != null && !fgPkg.isEmpty() && !fgPkg.equals(mc.getPackageName()))) return;
        PlaybackState st = mc.getPlaybackState();
        if (st != null && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING)) {
            mc.getTransportControls().pause();
        }
    }

    private static JSONObject findActiveItemForPackage(
            Context ctx, AuraDb db, SessionInspector inspector, String profile, String fgPkg) {
        if (db == null) return null;
        JSONArray hist = db.listHistory(profile);
        JSONObject substantial = findHistoryForPkg(hist, fgPkg, 30);
        if (substantial != null) return substantial;
        JSONObject anyPkg = findHistoryForPkg(hist, fgPkg, 0);
        return anyPkg != null ? anyPkg : (hist.length() > 0 ? hist.optJSONObject(0) : null);
    }

    private static JSONObject findHistoryForPkg(JSONArray hist, String fgPkg, int minPosSec) {
        for (int i = 0; i < hist.length(); i++) {
            JSONObject h = hist.optJSONObject(i);
            if (h != null && fgPkg.equals(h.optString("app_pkg", h.optString("launch_pkg", "")))
                    && h.optInt("position_sec", 0) >= minPosSec) {
                return h;
            }
        }
        return null;
    }
}
