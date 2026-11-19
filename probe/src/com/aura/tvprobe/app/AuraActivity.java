package com.aura.tvprobe;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main Launcher Activity for Aura TV.
 * Smoked Obsidian & Slate Glass theme, 3-dash (☰) animated navigation drawer,
 * focus-locked Agentic Search overlay, and 90-day recency-verified Continue Watching.
 */
public class AuraActivity extends Activity implements
        NavPanelBuilder.NavCallback,
        HeroSliderBuilder.HeroCallback,
        RailBuilder.RailCallback,
        SearchOverlay.SearchCallback,
        DetailOverlay.DetailCallback,
        ModalOverlayBuilder.ModalActions {

    private static final int REMOTE_PORT = 8765;
    private static final long SCREENSAVER_IDLE_MS = 120_000L; // 2 minutes idle -> Ambient Screensaver
    private static final long HOURLY_REFRESH_MS = 3_600_000L; // 1 hour auto-refresh interval
    private static final int TAG_GLOBAL_LOADER_TEXT = 0xAA_0101;
    private static final int TAG_GLOBAL_LOADER_SUB  = 0xAA_0102;
    private static volatile AuraActivity sActiveInstance;
    private static volatile boolean sIsInForeground = false;
    private static volatile long sLastActiveWallMs = 0L;
    private static volatile long sLastOutboundLaunchMs = 0L;
    private static volatile String sLastGlobalVoiceQuery = "";
    private static volatile long sLastGlobalVoiceMs = 0L;
    private static volatile long sLastTakeoverBurstMs = 0L;
    private static volatile long sLastHomeKeyMs = 0L;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bgPool = Executors.newFixedThreadPool(3);
    private final Runnable idleScreensaverTask = this::activateScreensaver;
    private final Runnable homeSearchHintTask = this::runHomeSearchHintLoop;
    private final Runnable hourlyRecoRefreshTask = this::runHourlyRecoRefreshLoop;
    private volatile boolean refreshInFlight = false;
    private volatile boolean loaderDismissedByUser = false;
    private volatile int activeSearchTurnId = 0;
    private long lastSilenceScheduleMs = 0L;
    private long lastRenderedRecoSec = 0L;
    private String lastRenderedHistorySignature = "";

    private AuraDb db;
    private AiRecommender ai;
    private SyncManager sync;
    private VoiceConcierge voice;
    private SessionInspector inspector;
    private String activeProfile = AuraDb.DEFAULT_PROFILE;
    private String activeMoodKey = TasteIntelligenceEngine.MOOD_ALL;
    private boolean initialFocusSet = false;
    private String lastSearchQuery = "";
    private int homeHintIndex = 0;

    private FrameLayout rootLayout;
    private ScrollView mainScroll;
    private FrameLayout navPanel;
    private FrameLayout heroSlider;
    private LinearLayout railsContainer;
    private LinearLayout chipsRow;
    private LinearLayout searchBox;
    private TextView searchLabel;
    private TextView statusBanner;
    private TextView profileClockLabel;
    private FrameLayout searchOverlay;
    private FrameLayout detailOverlay;
    private FrameLayout modalContainer;
    private FrameLayout loadingOverlay;
    private FrameLayout screensaverOverlay;
    private FrameLayout liveSubtitleBar;
    private TextView globalLoaderText;
    private View lastFocusedView;
    private JSONObject currentDetailItem;
    private int[] lastRailFocus;
    private int lastScrollY;
    private boolean watchedChangedWhileInDetail = false;
    private final android.content.BroadcastReceiver homeKeyReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("homekey".equals(intent.getStringExtra("reason"))) {
                onHardwareHomeKeyPressed(context);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finishDuplicateInstanceIfPresent();
        initActivityWindowAndState();
        buildUiAndStartLoops();
        refreshAllData(false);
        if (savedInstanceState == null) handleIncomingSearchIntent(getIntent());
    }

    private void finishDuplicateInstanceIfPresent() {
        AuraActivity prev = sActiveInstance;
        if (prev != null && prev != this) {
            try { prev.finish(); } catch (Throwable ignored) {}
        }
    }

    private void initActivityWindowAndState() {
        sActiveInstance = this;
        sLastActiveWallMs = System.currentTimeMillis();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        initServices();
        AuraTtsNarrator.warmup(this);
    }

    private void buildUiAndStartLoops() {
        buildUi();
        setupGlobalFocusRouting();
        registerHomeKeyReceiverSafe();
        startHomeSearchHintAnimation();
        resetIdleTimer();
    }

    private void registerHomeKeyReceiverSafe() {
        try {
            registerReceiver(homeKeyReceiver, new android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        markLauncherResumed();
        scheduleSilenceBackgroundMediaOnResume();
        HeroSliderBuilder.startAutoRotate(heroSlider, ui);
        startHomeSearchHintAnimation();
        scheduleHourlyRecoRefresh();
        updateNavMetadata();
        resetIdleTimer();
        maybeRefreshOnResume();
    }

    private void markLauncherResumed() {
        sActiveInstance = this;
        sIsInForeground = true;
        sLastActiveWallMs = System.currentTimeMillis();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!TvOverlayManager.isCompanionSessionActive()) {
            TvOverlayManager.dismissActiveInstanceOverlays();
            bindLiveSubtitleListener();
        }
    }

    private void scheduleSilenceBackgroundMediaOnResume() {
        long now = System.currentTimeMillis();
        if ((now - lastSilenceScheduleMs) < 1200L) return;
        lastSilenceScheduleMs = now;
        bgPool.submit(() -> silenceBackgroundMediaNow(this));
        ui.postDelayed(this::silenceIfStillInForeground, 1600L);
        ui.postDelayed(this::silenceIfStillInForeground, 3400L);
    }

    private void silenceIfStillInForeground() {
        if (sIsInForeground && shouldBlockBackgroundPlayback()) {
            bgPool.submit(() -> silenceBackgroundMediaNow(this));
        }
    }

    private void maybeRefreshOnResume() {
        if (!initialFocusSet || refreshInFlight) return;
        boolean stale = !db.isRecoCacheFresh(activeProfile);
        boolean updatedInBg = db.getRecoUpdatedAt(activeProfile) > lastRenderedRecoSec;
        if (stale || updatedInBg) {
            refreshAllData(stale);
        } else {
            syncContinueWatchingOnResume();
        }
    }

    private void syncContinueWatchingOnResume() {
        bgPool.submit(() -> {
            String prevTop = getTopContinueWatchingTitle();
            syncNowPlayingSafe();
            boolean topChanged = !getTopContinueWatchingTitle().equals(prevTop);
            boolean sigChanged = !currentContinueWatchingSignature().equals(lastRenderedHistorySignature);
            ui.post(() -> applyResumeContinueWatchingSync(topChanged, sigChanged));
        });
    }

    private void applyResumeContinueWatchingSync(boolean topChanged, boolean sigChanged) {
        if (topChanged || sigChanged) {
            renderHomeContent(db.getProfileRecommendations(activeProfile));
        }
        if (topChanged) refreshAllData(true);
    }

    private void scheduleHourlyRecoRefresh() {
        ui.removeCallbacks(hourlyRecoRefreshTask);
        long ageSec = db != null ? db.getRecoCacheAgeSec(activeProfile) : -1L;
        long remMs = ageSec < 0 ? 60_000L : Math.max(60_000L, HOURLY_REFRESH_MS - (ageSec * 1000L));
        ui.postDelayed(hourlyRecoRefreshTask, Math.min(remMs, 300_000L));
    }

    private void runHourlyRecoRefreshLoop() {
        if (!refreshInFlight && !db.isRecoCacheFresh(activeProfile)) {
            refreshAllData(true);
        } else if (db.getRecoUpdatedAt(activeProfile) > lastRenderedRecoSec) {
            renderHomeContent(db.getProfileRecommendations(activeProfile));
        }
        scheduleHourlyRecoRefresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("aura_bring_to_front", false)) return;
        if (handleIncomingSearchIntent(intent)) return;
        resetToHomeTop();
    }

    private boolean handleIncomingSearchIntent(Intent intent) {
        String q = VoiceSearchHelper.extractAssistantSearchQuery(intent);
        if (q.isEmpty()) return false;
        setIntent(buildHomeLauncherIntent(this));
        ui.post(() -> runSearchQuery(q));
        return true;
    }

    @Override
    public boolean onSearchRequested() {
        startVoiceSearch();
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        String spoken = VoiceSearchHelper.extractActivityResultQuery(requestCode, resultCode, data);
        if (!spoken.isEmpty()) {
            ui.post(() -> runSearchQuery(spoken));
        }
    }

    private void resetToHomeTop() {
        lastFocusedView = null;
        lastRailFocus = null;
        lastScrollY = 0;
        dismissAllActiveOverlays();
        setBackgroundFocusBlocked(false);
        clearCurrentFocusIfNotHero();
        if (mainScroll != null) mainScroll.scrollTo(0, 0);
        HeroSliderBuilder.requestInitialFocus(heroSlider);
    }

    private void clearCurrentFocusIfNotHero() {
        View cur = getCurrentFocus();
        if (cur != null && cur != heroSlider) cur.clearFocus();
    }

    private void dismissAllActiveOverlays() {
        if (AmbientScreensaverOverlay.isVisible(screensaverOverlay)) {
            AmbientScreensaverOverlay.dismiss(screensaverOverlay);
        }
        if (modalContainer != null && modalContainer.getVisibility() == View.VISIBLE) {
            modalContainer.removeAllViews();
            modalContainer.setVisibility(View.GONE);
        }
        dismissDetailSearchAndNav();
    }

    private void dismissDetailSearchAndNav() {
        if (DetailOverlay.isVisible(detailOverlay)) {
            currentDetailItem = null;
            DetailOverlay.dismiss(detailOverlay);
        }
        if (SearchOverlay.isVisible(searchOverlay)) {
            SearchOverlay.dismiss(searchOverlay);
            clearRetainedSearchState();
        }
        if (NavPanelBuilder.isExpanded(navPanel)) NavPanelBuilder.setExpanded(navPanel, false, this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sIsInForeground = false;
        sLastActiveWallMs = System.currentTimeMillis();
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        ui.removeCallbacks(homeSearchHintTask);
        ui.removeCallbacks(idleScreensaverTask);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (loadingOverlay != null && !refreshInFlight) loadingOverlay.setVisibility(View.GONE);
        if (!TvOverlayManager.isCompanionSessionActive()) AuraTtsNarrator.stop(this);
        ImageLoader.trimMemory();
    }

    @Override
    protected void onDestroy() {
        if (sActiveInstance == this) sActiveInstance = null;
        sIsInForeground = false;
        ui.removeCallbacksAndMessages(null);
        bgPool.shutdownNow();
        try { unregisterReceiver(homeKeyReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    public static boolean isInForeground() {
        return sIsInForeground;
    }

    public static void rebindLauncherSubtitleIfActive() {
        AuraActivity inst = sActiveInstance;
        if (inst != null && !TvOverlayManager.isCompanionSessionActive()) {
            inst.ui.post(inst::bindLiveSubtitleListener);
        }
    }

    public static JSONObject getActiveCompanionItem(AuraDb db, String profileId) {
        AuraActivity inst = sActiveInstance;
        if (inst == null || !DetailOverlay.isVisible(inst.detailOverlay) || inst.currentDetailItem == null) {
            return null;
        }
        return ShowCompanionAgent.mergeDetectedShowWithHistory(db, profileId, inst.currentDetailItem, inst.currentDetailItem);
    }

    public static Intent buildHomeLauncherIntent(Context ctx) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        i.setClassName(ctx, AuraActivity.class.getName());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
            | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    public static void onHardwareHomeKeyPressed(Context ctx) {
        long now = System.currentTimeMillis();
        if ((now - sLastHomeKeyMs) < 500L) return;
        sLastHomeKeyMs = now;
        AuraActivity inst = sActiveInstance;
        if (inst != null && sIsInForeground) {
            inst.ui.post(inst::resetToHomeTop);
            return;
        }
        scheduleHomeRescueIfNeeded(ctx);
    }

    private static void scheduleHomeRescueIfNeeded(Context ctx) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!sIsInForeground) launchHomeActivityNow(ctx, sActiveInstance);
        }, 220L);
    }

    private static void launchHomeActivityNow(Context ctx, AuraActivity inst) {
        if (ctx != null) {
            try { ctx.startActivity(buildHomeLauncherIntent(ctx)); } catch (Throwable ignored) {}
        }
        if (inst != null) inst.ui.post(inst::resetToHomeTop);
    }

    public static void onGlobalVoiceSearchQuery(Context ctx, String rawQuery) {
        String q = rawQuery != null ? rawQuery.trim() : "";
        if (!shouldHandleGlobalVoiceQuery(q)) return;
        AuraActivity inst = sActiveInstance;
        if (inst == null) {
            coldLaunchAuraWithVoiceQuery(ctx, q);
            return;
        }
        VoiceSearchHelper.silenceAssistantAudio(ctx, inst.ui);
        inst.ui.post(() -> inst.runSearchQuery(q));
        scheduleBringAuraToFront(inst.ui, ctx);
    }

    private static void coldLaunchAuraWithVoiceQuery(Context ctx, String q) {
        try {
            Handler mainH = new Handler(Looper.getMainLooper());
            VoiceSearchHelper.silenceAssistantAudio(ctx, mainH);
            Intent i = buildHomeLauncherIntent(ctx)
                .putExtra("aura_voice_query", q)
                .putExtra("aura_voice_ts", System.currentTimeMillis());
            ctx.startActivity(i);
            mainH.postDelayed(() -> scheduleBringAuraToFront(mainH, ctx), 250L);
        } catch (Throwable ignored) {}
    }

    public static void suppressKatnissOverlayNow(Context ctx) {
        long now = System.currentTimeMillis();
        if ((now - sLastGlobalVoiceMs) > 14_000L || (now - sLastTakeoverBurstMs) < 2_200L) return;
        sLastTakeoverBurstMs = now;
        AuraActivity inst = sActiveInstance;
        Handler h = inst != null ? inst.ui : new Handler(Looper.getMainLooper());
        VoiceSearchHelper.silenceAssistantAudio(ctx, h);
        scheduleShortTakeoverBurst(h, ctx);
    }

    private static void scheduleShortTakeoverBurst(Handler h, Context ctx) {
        bringAuraToFrontNow(ctx);
        for (int step = 1; step <= 12; step++) {
            h.postDelayed(() -> bringAuraToFrontNow(ctx), step * 160L);
        }
    }

    public static boolean shouldHandleGlobalVoiceQuery(String q) {
        long now = System.currentTimeMillis();
        boolean companionQuery = ShowCompanionAgent.isShowCompanionQuery(q);
        boolean activeOrJustPaused = sIsInForeground || (now - sLastActiveWallMs) < 90_000L || companionQuery;
        if (q == null || q.length() < 2 || !activeOrJustPaused) return false;
        if (q.equalsIgnoreCase(sLastGlobalVoiceQuery) && (now - sLastGlobalVoiceMs) < 3_500L) return false;
        sLastGlobalVoiceQuery = q;
        sLastGlobalVoiceMs = now;
        sLastActiveWallMs = now;
        return true;
    }

    private static void scheduleBringAuraToFront(Handler h, Context ctx) {
        bringAuraToFrontNow(ctx);
        for (int step = 1; step <= 40; step++) {
            h.postDelayed(() -> bringAuraToFrontNow(ctx), step * 180L);
        }
    }

    private static void bringAuraToFrontNow(Context ctx) {
        try {
            killKatnissBackgroundSafe(ctx);
            ctx.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            AuraActivity inst = sActiveInstance;
            if (inst != null && sIsInForeground && inst.hasWindowFocus()) return;
            Intent i = buildHomeLauncherIntent(ctx).putExtra("aura_bring_to_front", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ctx.startActivity(i);
        } catch (Throwable ignored) {}
    }

    private static void killKatnissBackgroundSafe(Context ctx) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            am.killBackgroundProcesses("com.google.android.katniss");
            if ((System.currentTimeMillis() - sLastGlobalVoiceMs) < 14_000L) {
                am.killBackgroundProcesses("com.google.android.youtube.tv");
            }
        } catch (Throwable ignored) {}
    }

    public static void markOutboundAppLaunch() {
        sLastOutboundLaunchMs = System.currentTimeMillis();
    }

    public static boolean shouldBlockBackgroundPlayback() {
        return sIsInForeground && (System.currentTimeMillis() - sLastOutboundLaunchMs) > 2500L;
    }

    public static void silenceBackgroundMediaNow(Context ctx) {
        if (ctx == null || !shouldBlockBackgroundPlayback()) return;
        if (pauseActiveBackgroundSessions(ctx)) {
            stealAndAbandonAudioFocus(ctx);
        }
        killYouTubeBackgroundSafe(ctx);
    }

    private static boolean pauseActiveBackgroundSessions(Context ctx) {
        try {
            android.media.session.MediaSessionManager msm =
                (android.media.session.MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return false;
            return pauseControllerList(msm.getActiveSessions(new ComponentName(ctx, MediaProbeService.class)));
        } catch (Throwable e) { return false; }
    }

    private static boolean pauseControllerList(List<android.media.session.MediaController> list) {
        boolean pausedAny = false;
        if (list == null) return false;
        for (android.media.session.MediaController mc : list) pausedAny |= stopPlayingControllerSafe(mc);
        return pausedAny;
    }

    private static boolean stopPlayingControllerSafe(android.media.session.MediaController mc) {
        try {
            android.media.session.PlaybackState st = mc != null ? mc.getPlaybackState() : null;
            int s = st != null ? st.getState() : 0;
            if (s != 3 && s != 6) return false;
            mc.getTransportControls().pause();
            mc.getTransportControls().stop();
            return true;
        } catch (Throwable e) { return false; }
    }

    private static void stealAndAbandonAudioFocus(Context ctx) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            android.media.AudioManager.OnAudioFocusChangeListener l = ch -> {};
            am.requestAudioFocus(l, android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            am.abandonAudioFocus(l);
        } catch (Throwable ignored) {}
    }

    private static void killYouTubeBackgroundSafe(Context ctx) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            am.killBackgroundProcesses("com.google.android.youtube.tv");
            am.killBackgroundProcesses("com.netflix.ninja");
            am.killBackgroundProcesses("com.amazon.amazonvideo.livingroom");
        } catch (Throwable ignored) {}
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        ImageLoader.trimMemory();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        sLastActiveWallMs = System.currentTimeMillis();
        resetIdleTimer();
        if (AmbientScreensaverOverlay.isVisible(screensaverOverlay)) {
            return consumeScreensaverWakeKey(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && VoiceSearchHelper.isRemoteVoiceOrSearchKey(event.getKeyCode())) {
            startVoiceSearch();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean consumeScreensaverWakeKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            AmbientScreensaverOverlay.dismiss(screensaverOverlay);
            HeroSliderBuilder.startAutoRotate(heroSlider, ui);
            HeroSliderBuilder.requestInitialFocus(heroSlider);
        }
        return true;
    }

    private void resetIdleTimer() {
        ui.removeCallbacks(idleScreensaverTask);
        ui.postDelayed(idleScreensaverTask, SCREENSAVER_IDLE_MS);
    }

    private void activateScreensaver() {
        if (screensaverOverlay == null || AmbientScreensaverOverlay.isVisible(screensaverOverlay)) return;
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        AmbientScreensaverOverlay.show(screensaverOverlay, this);
    }

    private void initServices() {
        db = AuraDb.getInstance(this);
        bgPool.submit(() -> db.purgePromotionalNoiseFromHistory());
        ImageLoader.setQualityMode(db.getSetting("image_quality", "ultra"));
        ai = new AiRecommender(db);
        sync = new SyncManager(db);
        voice = new VoiceConcierge(db, ai, sync);
        inspector = new SessionInspector(this, new ComponentName(this, MediaProbeService.class), db, ai);
        activeProfile = db.getValidActiveProfile();
        startMediaProbeServiceSafe();
    }

    private void startMediaProbeServiceSafe() {
        try {
            android.service.notification.NotificationListenerService.requestRebind(
                new ComponentName(this, MediaProbeService.class));
            startService(new Intent(this, MediaProbeService.class));
        } catch (Throwable ignored) {}
    }

    private void buildUi() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(GlassTheme.BG_AMBIENT);
        rootLayout.setKeepScreenOn(true);
        navPanel = NavPanelBuilder.build(this, this);
        rootLayout.addView(buildMainScrollArea());
        NavPanelBuilder.bindMainContentForAnimation(navPanel, mainScroll);
        rootLayout.addView(navPanel);
        addOverlays();
        clearRetainedSearchState();
        setContentView(rootLayout);
    }

    private ScrollView buildMainScrollArea() {
        mainScroll = new RailBuilder.SmoothVerticalScrollView(this);
        mainScroll.setVerticalScrollBarEnabled(false);
        mainScroll.setFillViewport(true);
        mainScroll.setFocusable(false);
        mainScroll.setClipChildren(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        mainScroll.setLayoutParams(lp);
        mainScroll.addView(buildMainColumn());
        return mainScroll;
    }

    private LinearLayout buildMainColumn() {
        LinearLayout col = createVerticalUnclippedColumn();
        heroSlider = HeroSliderBuilder.build(this, this);
        chipsRow = new LinearLayout(this);
        col.addView(initAndBuildTopBar());
        col.addView(heroSlider);
        col.addView(buildStatusBannerView());
        col.addView(buildRailsContainerView(), GlassTheme.matchWrapParams());
        return col;
    }

    private LinearLayout createVerticalUnclippedColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        return col;
    }

    private View initAndBuildTopBar() {
        TopBarBuilder.TopBarViews topBar = TopBarBuilder.build(
            this, navPanel, heroSlider,
            SearchOverlay.getHintsForProfile(db, activeProfile),
            this::openAgenticSearchStudio,
            this::startVoiceSearch);
        searchBox = topBar.searchBox;
        searchLabel = topBar.searchLabel;
        profileClockLabel = topBar.clockLabel;
        return topBar.bar;
    }

    private void startVoiceSearch() {
        if (!VoiceSearchHelper.startVoiceRecognition(this)) {
            openAgenticSearchStudio();
        }
    }

    @Override
    public void onVoiceSearchClicked() {
        startVoiceSearch();
    }

    private TextView buildStatusBannerView() {
        statusBanner = GlassTheme.makeText(this, "", 11f, GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1);
        statusBanner.setPadding(dp(20), dp(2), dp(20), dp(2));
        statusBanner.setVisibility(View.GONE);
        return statusBanner;
    }

    private LinearLayout buildRailsContainerView() {
        railsContainer = new LinearLayout(this);
        railsContainer.setOrientation(LinearLayout.VERTICAL);
        railsContainer.setClipChildren(false);
        railsContainer.setClipToPadding(false);
        railsContainer.setPadding(dp(20), 0, dp(20), dp(28));
        return railsContainer;
    }

    private void startHomeSearchHintAnimation() {
        ui.removeCallbacks(homeSearchHintTask);
        ui.postDelayed(homeSearchHintTask, 3800);
    }

    private void runHomeSearchHintLoop() {
        tickHomeSearchHintAnimation();
        ui.postDelayed(homeSearchHintTask, 3800);
    }

    private void tickHomeSearchHintAnimation() {
        if (searchLabel == null || !lastSearchQuery.isEmpty() || SearchOverlay.isVisible(searchOverlay)) return;
        List<String> hints = SearchOverlay.getHintsForProfile(db, activeProfile);
        homeHintIndex = (homeHintIndex + 1) % hints.size();
        animateSearchHintLabelTo(hints.get(homeHintIndex));
    }

    private void animateSearchHintLabelTo(String nextHint) {
        searchLabel.animate().alpha(0.15f).translationY(-dp(3)).setDuration(220).withEndAction(() -> {
            searchLabel.setText(nextHint);
            searchLabel.setTranslationY(dp(3));
            searchLabel.animate().alpha(1f).translationY(0f).setDuration(260).start();
        }).start();
    }

    private void openAgenticSearchStudio() {
        saveFocusBeforeOverlay();
        hideKeyboard(rootLayout);
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        setBackgroundFocusBlocked(true);
        clearRetainedSearchState();
        populateFreshSearchStudio();
        SearchOverlay.show(searchOverlay, this, true);
    }

    private void populateFreshSearchStudio() {
        SearchOverlay.setConversationTurns(searchOverlay, new JSONArray(), this);
        SearchOverlay.setFollowUps(searchOverlay, new ArrayList<>(), this, this);
        SearchOverlay.setResults(searchOverlay, new JSONArray(), this, this);
    }

    private void hideKeyboard(View v) {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && v != null) {
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
    }

    private void addOverlays() {
        searchOverlay = SearchOverlay.build(this, this);
        detailOverlay = DetailOverlay.build(this, this);
        modalContainer = new FrameLayout(this);
        modalContainer.setVisibility(View.GONE);
        loadingOverlay = buildGlobalLoadingOverlay();
        screensaverOverlay = buildScreensaverOverlayView();
        liveSubtitleBar = LiveSubtitleBar.build(this);
        bindLiveSubtitleListener();
        attachAllOverlaysToRoot();
    }

    private void bindLiveSubtitleListener() {
        AuraTtsNarrator.setSubtitleListener(new AuraTtsNarrator.SubtitleListener() {
            @Override public void onSentenceStart(String s) { LiveSubtitleBar.animateNewSentence(liveSubtitleBar, s); }
            @Override public void onWordHighlight(String s, int ws, int we) { LiveSubtitleBar.updateWordHighlight(liveSubtitleBar, s, ws, we); }
            @Override public void onNarrationStop() { LiveSubtitleBar.hide(liveSubtitleBar); }
        });
    }

    private FrameLayout buildScreensaverOverlayView() {
        return AmbientScreensaverOverlay.build(this, () -> {
            HeroSliderBuilder.startAutoRotate(heroSlider, ui);
            HeroSliderBuilder.requestInitialFocus(heroSlider);
            resetIdleTimer();
        });
    }

    private void attachAllOverlaysToRoot() {
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rootLayout.addView(searchOverlay, full);
        rootLayout.addView(detailOverlay, full);
        rootLayout.addView(modalContainer, full);
        rootLayout.addView(loadingOverlay, full);
        rootLayout.addView(screensaverOverlay, full);
        rootLayout.addView(liveSubtitleBar, full);
    }

    private FrameLayout buildGlobalLoadingOverlay() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setVisibility(View.GONE);
        wrap.setAlpha(0f);
        wrap.setBackgroundColor(Color.argb(246, 8, 11, 18));
        wrap.setClickable(true);
        wrap.setFocusable(false);
        attachLoaderColumn(wrap);
        return wrap;
    }

    private void attachLoaderColumn(FrameLayout wrap) {
        LinearLayout col = AgenticOrbView.buildFloatingLoaderColumn(
            this, TAG_GLOBAL_LOADER_TEXT, TAG_GLOBAL_LOADER_SUB);
        globalLoaderText = col.findViewWithTag(TAG_GLOBAL_LOADER_TEXT);
        wrap.addView(col);
    }

    private void setBackgroundFocusBlocked(boolean blocked) {
        int mode = blocked
            ? ViewGroup.FOCUS_BLOCK_DESCENDANTS
            : ViewGroup.FOCUS_AFTER_DESCENDANTS;
        if (mainScroll != null) mainScroll.setDescendantFocusability(mode);
        if (navPanel != null) navPanel.setDescendantFocusability(mode);
    }

    private void setupGlobalFocusRouting() {
        rootLayout.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF == null) return;
            boolean inNav = isDescendantOf(newF, navPanel);
            if (!inNav && NavPanelBuilder.isExpanded(navPanel)) {
                NavPanelBuilder.setExpanded(navPanel, false, this);
            }
        });
    }

    private boolean isDescendantOf(View view, ViewGroup container) {
        View cur = view;
        while (cur != null) {
            if (cur == container) return true;
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return false;
    }

    // ── Data Loading & Rendering ─────────────────────────────────────────

    private void refreshAllData(boolean forceRefresh) {
        refreshAllData(forceRefresh, false);
    }

    private void refreshAllData(boolean forceRefresh, boolean showBlockingLoader) {
        updateNavMetadata();
        populateTraversalChips();
        if (showBlockingLoader) beginBlockingLoader("Looking at what you\u2019ve been watching lately\u2026");
        JSONArray cached = db.getProfileRecommendations(activeProfile);
        boolean hadFresh = cached.length() >= 12 && db.isRecoCacheFresh(activeProfile);
        if (!showBlockingLoader || railsContainer.getChildCount() == 0) {
            renderHomeContent(cached.length() > 0 ? cached : ai.getBuiltInSotaSeedCatalog(activeProfile));
        }
        submitBackgroundRefresh(forceRefresh, showBlockingLoader, hadFresh);
    }

    private void beginBlockingLoader(String initialMsg) {
        loaderDismissedByUser = false;
        setBannerText(initialMsg, true);
    }

    private void postBlockingLoaderStep(String step) {
        if (loaderDismissedByUser) return;
        ui.post(() -> { if (!loaderDismissedByUser) setBannerText(step, true); });
    }

    private void submitBackgroundRefresh(boolean forceRefresh, boolean showLoader, boolean hadFresh) {
        if (refreshInFlight) return;
        refreshInFlight = true;
        bgPool.submit(() -> executeBackgroundRefreshTask(forceRefresh, showLoader, hadFresh));
    }

    private void executeBackgroundRefreshTask(boolean forceRefresh, boolean showLoader, boolean hadFresh) {
        String prevTop = getTopContinueWatchingTitle();
        JSONObject np = syncNowPlayingSafe();
        boolean topChanged = !getTopContinueWatchingTitle().equals(prevTop);
        if (!forceRefresh && hadFresh && !topChanged) {
            postContinueWatchingUpdateIfChanged();
            return;
        }
        refreshRecommendationsInBackground(np, forceRefresh || topChanged, showLoader, hadFresh);
    }

    private void postContinueWatchingUpdateIfChanged() {
        refreshInFlight = false;
        if (!currentContinueWatchingSignature().equals(lastRenderedHistorySignature)) {
            ui.post(() -> renderHomeContent(db.getProfileRecommendations(activeProfile)));
        }
    }

    private void refreshRecommendationsInBackground(JSONObject np, boolean forceRefresh,
                                                    boolean showLoader, boolean hadFresh) {
        JSONArray history = db.listHistory(activeProfile);
        if (np == null && history.length() > 0) np = history.optJSONObject(0);
        JSONArray fresh = ai.getOrRefreshForProfile(activeProfile, np, history, forceRefresh,
            step -> postRefreshBannerStep(step, showLoader));
        ui.post(() -> applyBackgroundRefreshResult(fresh, forceRefresh, showLoader, hadFresh));
    }

    private void postRefreshBannerStep(String step, boolean showLoader) {
        if (showLoader) postBlockingLoaderStep(step);
    }

    private JSONObject syncNowPlayingSafe() {
        try { return inspector.syncProvidersAndNowPlaying(activeProfile); } catch (Exception e) { return null; }
    }

    private void applyBackgroundRefreshResult(JSONArray fresh, boolean forceRefresh,
                                              boolean showLoader, boolean hadFresh) {
        refreshInFlight = false;
        if (showLoader) setBannerText("", false);
        if (fresh != null && fresh.length() > 0 && (forceRefresh || !hadFresh)) {
            renderHomeContent(fresh);
        }
        scheduleHourlyRecoRefresh();
    }

    private void updateNavMetadata() {
        NavPanelBuilder.updateProfiles(navPanel, db.listProfiles(), activeProfile, this);
        long freeMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        String mode = db.getSetting("gemini_api_key", "").isEmpty() ? "JustWatch Graph" : "Aura Agent + JW";
        NavPanelBuilder.updateStatus(navPanel, mode, freeMb + " MB", String.valueOf(REMOTE_PORT));
        String clock = new SimpleDateFormat("h:mm a", Locale.US).format(new Date());
        profileClockLabel.setText(clock);
    }

    private void populateTraversalChips() {
        chipsRow.removeAllViews();
        addMoodPillButton("For You", TasteIntelligenceEngine.MOOD_ALL);
        addMoodPillButton("Mind-Bending", TasteIntelligenceEngine.MOOD_MIND_BENDING);
        addMoodPillButton("Light & Smart", TasteIntelligenceEngine.MOOD_LIGHT_SMART);
        addMoodPillButton("Edge-of-Seat", TasteIntelligenceEngine.MOOD_EDGE_OF_SEAT);
        addMoodPillButton("Under 2 hrs", TasteIntelligenceEngine.MOOD_UNDER_2H);
        for (String chip : db.getTraversalChips(activeProfile)) {
            chipsRow.addView(buildTraversalChipButton(chip));
        }
    }

    private void addMoodPillButton(String label, String moodKey) {
        boolean active = moodKey.equals(activeMoodKey);
        TextView btn = GlassTheme.makeGlassButton(this, (active ? "\u25CF " : "") + label);
        btn.setTextSize(10.5f);
        btn.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.rightMargin = dp(6);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> applyInstantMoodFilter(moodKey));
        chipsRow.addView(btn);
    }

    private void applyInstantMoodFilter(String moodKey) {
        activeMoodKey = moodKey;
        populateTraversalChips();
        renderHomeContent(db.getProfileRecommendations(activeProfile));
    }

    private TextView buildTraversalChipButton(String chip) {
        TextView btn = GlassTheme.makeGlassButton(this, "\u2726 " + chip);
        btn.setTextSize(10.5f);
        btn.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.rightMargin = dp(6);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> runSearchQuery(chip));
        return btn;
    }

    private void renderHomeContent(JSONArray recos) {
        lastRenderedRecoSec = Math.max(System.currentTimeMillis() / 1000L, db.getRecoUpdatedAt(activeProfile));
        final int savedScrollY = resolveSavedScrollY();
        final int[] savedRailFocus = resolveSavedRailFocus();
        List<JSONObject> all = sliceTopHomeRecos(recos);
        populateHeroAndScreensaver(all);
        rebuildHomeRails(all);
        animateHomeRailsEntrance();
        restoreOrInitFocus(savedRailFocus, savedScrollY);
    }

    private List<JSONObject> sliceTopHomeRecos(JSONArray recos) {
        JSONArray clean = TasteIntelligenceEngine.filterOutDismissed(db, activeProfile, recos);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        List<JSONObject> ranked = TasteIntelligenceEngine.rerankForMoodAndContext(
            toJsonList(clean), activeMoodKey, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.DAY_OF_WEEK));
        return ranked.subList(0, Math.min(24, ranked.size()));
    }

    private void populateHeroAndScreensaver(List<JSONObject> all) {
        AmbientScreensaverOverlay.setItems(screensaverOverlay, all);
        HeroSliderBuilder.setItems(heroSlider, all.subList(0, Math.min(6, all.size())), this, this);
        HeroSliderBuilder.startAutoRotate(heroSlider, ui);
    }

    private int resolveSavedScrollY() {
        int cur = mainScroll != null ? mainScroll.getScrollY() : 0;
        return cur > 0 ? cur : lastScrollY;
    }

    private int[] resolveSavedRailFocus() {
        int[] live = findFocusedRailAndCard();
        return live != null ? live : lastRailFocus;
    }

    private void rebuildHomeRails(List<JSONObject> all) {
        railsContainer.removeAllViews();
        railsContainer.addView(RailBuilder.buildAppsDock(this, discoverTvApps(), this));
        addContinueWatchingIfPresent();
        addCategorizedRails(all);
    }

    private void animateHomeRailsEntrance() {
        for (int i = 0; i < railsContainer.getChildCount(); i++) {
            View rail = railsContainer.getChildAt(i);
            rail.setAlpha(0f);
            rail.setTranslationY(dp(14));
            rail.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 50L).setDuration(260)
                .setInterpolator(GlassTheme.EASE).start();
        }
    }

    private void restoreOrInitFocus(int[] savedRailFocus, int savedScrollY) {
        if (DetailOverlay.isVisible(detailOverlay) || SearchOverlay.isVisible(searchOverlay)) return;
        initialFocusSet = true;
        ui.post(() -> applySavedOrInitialFocus(savedRailFocus, savedScrollY));
    }

    private void applySavedOrInitialFocus(int[] savedRailFocus, int savedScrollY) {
        if (savedRailFocus != null && savedRailFocus[0] >= 0 && railsContainer.getChildCount() > 0) {
            int railIdx = Math.min(savedRailFocus[0], railsContainer.getChildCount() - 1);
            focusSavedRailItem(railIdx, savedRailFocus[1], savedScrollY);
            return;
        }
        if (mainScroll != null) mainScroll.scrollTo(0, 0);
        HeroSliderBuilder.requestInitialFocus(heroSlider);
    }

    private void focusSavedRailItem(int railIdx, int cardIdx, int savedScrollY) {
        View railView = railsContainer.getChildAt(railIdx);
        if (railView instanceof LinearLayout) {
            RailBuilder.focusItemAt((LinearLayout) railView, cardIdx);
        }
        if (mainScroll != null) {
            mainScroll.scrollTo(0, savedScrollY);
            mainScroll.post(() -> mainScroll.scrollTo(0, savedScrollY));
        }
    }

    private int[] findFocusedRailAndCard() {
        if (railsContainer == null) return null;
        View focused = getCurrentFocus();
        if (focused == null || !isDescendantOf(focused, railsContainer)) return null;
        for (int r = 0; r < railsContainer.getChildCount(); r++) {
            View rail = railsContainer.getChildAt(r);
            if (rail instanceof ViewGroup && isDescendantOf(focused, (ViewGroup) rail)) {
                return resolveCardIndexInRail(r, focused);
            }
        }
        return null;
    }

    private int[] resolveCardIndexInRail(int railIndex, View focused) {
        if (focused.getParent() instanceof ViewGroup) {
            int c = ((ViewGroup) focused.getParent()).indexOfChild(focused);
            if (c >= 0) return new int[]{railIndex, c};
        }
        return new int[]{railIndex, 0};
    }

    private void addContinueWatchingIfPresent() {
        List<JSONObject> rawHistory = toJsonList(db.listHistory(activeProfile));
        List<JSONObject> diversified = ContinueWatchingSelector.selectDiversified(rawHistory, 8);
        lastRenderedHistorySignature = buildContinueWatchingSignature(diversified);
        if (!diversified.isEmpty()) {
            railsContainer.addView(RailBuilder.buildContinueWatching(this, diversified, this));
        }
    }

    private String currentContinueWatchingSignature() {
        List<JSONObject> rawHistory = toJsonList(db.listHistory(activeProfile));
        return buildContinueWatchingSignature(ContinueWatchingSelector.selectDiversified(rawHistory, 8));
    }

    private String buildContinueWatchingSignature(List<JSONObject> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size() && i < 4; i++) {
            JSONObject o = items.get(i);
            sb.append(o.optString("media_key", o.optString("title"))).append(':').append(o.optInt("position_sec")).append('|');
        }
        return sb.toString();
    }

    private String getTopContinueWatchingTitle() {
        List<JSONObject> cw = ContinueWatchingSelector.selectDiversified(toJsonList(db.listHistory(activeProfile)), 1);
        if (cw.isEmpty()) return "";
        JSONObject top = cw.get(0);
        return top.optString("series_title").isEmpty() ? top.optString("title") : top.optString("series_title");
    }

    private void addCategorizedRails(List<JSONObject> all) {
        for (DynamicRailClusterer.RailGroup rail : DynamicRailClusterer.buildRails(all)) {
            railsContainer.addView(RailBuilder.buildContentRail(this, rail.title, "", rail.items, this));
        }
    }

    private List<JSONObject> discoverTvApps() {
        return AppCatalogProvider.discoverTvApps(this);
    }

    // ── Callbacks: NavPanelBuilder & ModalOverlayBuilder ─────────────────

    @Override
    public void onProfileSelected(String profileId) {
        activeProfile = profileId;
        db.setSetting("active_profile", profileId);
        refreshAllData(false);
    }

    @Override
    public void onAddProfile() {
        saveFocusBeforeOverlay();
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        setBackgroundFocusBlocked(true);
        ModalOverlayBuilder.showAddProfileModal(modalContainer, this, db, this);
    }

    @Override
    public void onNavigate(String destination) {
        if ("home".equals(destination)) refreshAllData(false);
        else if ("refresh_recos".equals(destination)) refreshAllData(true, true);
        else if ("ram_guardian".equals(destination)) showSystemMonitorDialog();
        else if ("guide_ai".equals(destination)) showGuideAiDialog();
        else if ("watched".equals(destination)) showWatchHistoryDialog();
        else if (destination.startsWith("sys_")) openSystemSetting(destination);
    }

    @Override
    public void onWatchHistoryOpen() {
        showWatchHistoryDialog();
    }

    @Override
    public void onHdmiSelect(int port) {
        MediaDeepLinkLauncher.openHdmiInput(this, port);
    }

    @Override
    public void onSettingsOpen() {
        showSettingsDialog();
    }

    @Override
    public void onQrOpen() {
        saveFocusBeforeOverlay();
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        setBackgroundFocusBlocked(true);
        String ip = QrRemoteBuilder.getTvIpAddress(this);
        LocalDomainResponder.ensureStarted(this);
        bgPool.submit(() -> { QrRemoteBuilder.nudgeLanArpTable(ip); LocalDomainResponder.announceAllDomainsNow(this); });
        ModalOverlayBuilder.showQrRemoteModal(modalContainer, this, ip, REMOTE_PORT, "", this);
    }

    @Override
    public void onRefreshRecommendations(boolean forceRefresh) {
        boolean instant = forceRefresh && ReserveSlateManager.hasReadyReserve(db, activeProfile);
        refreshAllData(forceRefresh, forceRefresh && !instant);
    }

    @Override
    public void onLaunchMedia(JSONObject item) {
        launchMediaItem(item);
    }

    @Override
    public void onOpenSystemTvSettings() {
        openSystemSetting("sys_tv_settings");
    }

    @Override
    public void onProfileCreated(String newProfileId) {
        activeProfile = newProfileId;
        db.setSetting("active_profile", newProfileId);
        refreshAllData(true, true);
    }

    @Override
    public void onModalClosed() {
        setBackgroundFocusBlocked(false);
        restoreFocusAfterOverlay();
    }

    @Override
    public void onStartScreensaver() {
        activateScreensaver();
    }

    @Override
    public void onDrawerCollapsed() {
        if (!DetailOverlay.isVisible(detailOverlay)
                && !SearchOverlay.isVisible(searchOverlay)
                && (modalContainer == null || modalContainer.getVisibility() != View.VISIBLE)) {
            setBackgroundFocusBlocked(false);
            restoreFocusAfterOverlay();
        }
    }

    // ── Callbacks: HeroSliderBuilder & RailBuilder ───────────────────────

    @Override
    public void onMoveUpToTopBar() {
        if (mainScroll != null && mainScroll.getScrollY() > 0) {
            ObjectAnimator.ofInt(mainScroll, "scrollY", mainScroll.getScrollY(), 0)
                .setDuration(220).start();
        }
        if (searchBox != null) searchBox.requestFocus();
    }

    @Override
    public void onMoveDownToFirstRail() {
        if (railsContainer != null && railsContainer.getChildCount() > 0
                && railsContainer.getChildAt(0) instanceof LinearLayout) {
            RailBuilder.focusFirstItem((LinearLayout) railsContainer.getChildAt(0));
        }
    }

    @Override
    public void onWatchClicked(JSONObject item) {
        launchMediaItem(item);
    }

    @Override
    public void onTrailerClicked(JSONObject item) {
        MediaDeepLinkLauncher.launchYouTubeTrailer(this, item);
    }

    @Override
    public void onCatchMeUpClicked(JSONObject item) {
        if (item == null) return;
        if (AuraTtsNarrator.isSpeaking() || TvOverlayManager.isCompanionSessionActive()) {
            InAppVoiceRouter.cancelActiveCompanionTurn(this, getOrCreateOverlayManager(), "");
            return;
        }
        startCatchMeUpForItem(item);
    }

    private TvOverlayManager getOrCreateOverlayManager() {
        TvOverlayManager ov = TvOverlayManager.peekInstance();
        return ov != null ? ov : new TvOverlayManager(this);
    }

    private void startCatchMeUpForItem(JSONObject item) {
        String title = resolveCanonicalTitle(item, "Show");
        TvOverlayManager ov = getOrCreateOverlayManager();
        JSONObject merged = ShowCompanionAgent.mergeDetectedShowWithHistory(db, activeProfile, item, item);
        FutureHolder holder = new FutureHolder();
        ov.showCompanionLoader("Catch me up on " + title, "Fetching episode guide for " + title + "\u2026", holder::cancel);
        holder.task = bgPool.submit(() -> executeCatchMeUpBlackout(title, merged, ov, holder));
    }

    private void executeCatchMeUpBlackout(String title, JSONObject item, TvOverlayManager ov, FutureHolder holder) {
        JSONObject recap = ShowCompanionAgent.executeCompanionTurn(
            ai, db, activeProfile, "Catch me up on " + title, item, ov::updateVoiceStatusSubtext);
        if (holder.cancelled || Thread.currentThread().isInterrupted() || recap == null) return;
        ui.post(() -> {
            if (holder.cancelled) return;
            if (DetailOverlay.isShowingTitle(detailOverlay, item.optString("title"))) {
                DetailOverlay.showRecapInDetail(detailOverlay, recap);
            }
            ov.showInAppCompanionRecap(recap, holder::cancel);
        });
    }

    private static final class FutureHolder {
        volatile java.util.concurrent.Future<?> task;
        volatile boolean cancelled = false;
        void cancel() {
            cancelled = true;
            if (task != null) task.cancel(true);
        }
    }

    @Override
    public void onWatchedClicked(JSONObject item) {
        db.markTitleWatched(item, true);
        Toast.makeText(this, "Marked watched: " + item.optString("title"), Toast.LENGTH_SHORT).show();
        replaceWatchedFromReserve();
    }

    private void replaceWatchedFromReserve() {
        db.pruneWatchedFromCachedRecos(activeProfile);
        JSONArray remaining = db.getProfileRecommendations(activeProfile);
        renderHomeContent(remaining);
        if (remaining.length() < 12) {
            submitBackgroundRefresh(true, false, true);
        }
    }

    @Override
    public void onSimilarClicked(JSONObject item) {
        String seed = resolveCanonicalTitle(item, "this title");
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        setBackgroundFocusBlocked(true);
        beginBlockingLoader("Looking for shows and movies like " + seed + "\u2026");
        final long startMs = System.currentTimeMillis();
        bgPool.submit(() -> executeSimilarTraversal(seed, startMs));
    }

    private void executeSimilarTraversal(String seed, long startMs) {
        int epoch = ImpressionTracker.getCurrentEpoch(db, activeProfile);
        JSONArray rawSim = ai.traverseSimilarToWatched(activeProfile, seed, this::postBlockingLoaderStep);
        JSONArray sim = ImpressionTracker.filterOutRecentImpressions(
            rawSim, epoch, ImpressionTracker.loadActiveImpressions(db), 4);
        ImpressionTracker.recordJsonArrayImpressions(db, epoch, sim);
        enforceMinLoadingDuration(startMs, 600L);
        persistSimilarVoiceSession(seed, sim);
        ui.post(() -> presentSimilarResults(sim));
    }

    private void enforceMinLoadingDuration(long startMs, long minMs) {
        long elapsed = System.currentTimeMillis() - startMs;
        if (elapsed < minMs) {
            try { Thread.sleep(minMs - elapsed); } catch (InterruptedException ignored) {}
        }
    }

    private void persistSimilarVoiceSession(String seed, JSONArray sim) {
        try {
            String replyText = "Showing modern titles similar to " + seed + ".";
            JSONArray turns = new JSONArray()
                .put(new JSONObject().put("role", "user").put("text", "Similar to " + seed))
                .put(new JSONObject().put("role", "assistant").put("text", replyText));
            JSONObject session = new JSONObject().put("turns", turns).put("reply", replyText)
                .put("results", sim != null ? sim : new JSONArray());
            db.saveVoiceSession(activeProfile, session);
        } catch (Exception ignored) {}
    }

    private void presentSimilarResults(JSONArray sim) {
        setBannerText("", false);
        if (loaderDismissedByUser) return;
        JSONObject saved = db.getVoiceSession(activeProfile);
        SearchOverlay.setConversationTurns(searchOverlay,
            saved != null ? saved.optJSONArray("turns") : new JSONArray(), this);
        SearchOverlay.setFollowUps(searchOverlay,
            java.util.Arrays.asList("Only on Netflix", "Shorter under 2h", "Darker & grittier"), this, this);
        SearchOverlay.setResults(searchOverlay, sim != null ? sim : new JSONArray(), this, this);
        SearchOverlay.show(searchOverlay, this, false);
    }

    @Override
    public void onMoreClicked(JSONObject item) {
        openDetailDrawer(item);
    }

    @Override
    public void onSlideChanged(int index) {}

    @Override
    public void onPosterClicked(JSONObject item, View posterView) {
        openDetailDrawer(item);
    }

    @Override
    public void onPosterFocused(JSONObject item, View posterView) {
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        lastFocusedView = posterView;
        lastRailFocus = findFocusedRailAndCard();
        lastScrollY = mainScroll != null ? mainScroll.getScrollY() : 0;
    }

    @Override
    public void onAppClicked(String packageName) {
        launchPackage(packageName);
    }

    @Override
    public void onContinueWatchingClicked(JSONObject item) {
        launchMediaItem(item);
    }

    @Override
    public void onMoveUpFromTopRail() {
        if (mainScroll != null) {
            ObjectAnimator anim = ObjectAnimator.ofInt(mainScroll, "scrollY", mainScroll.getScrollY(), 0);
            anim.setDuration(240);
            anim.setInterpolator(GlassTheme.EASE);
            anim.start();
        }
        HeroSliderBuilder.requestInitialFocus(heroSlider);
    }

    @Override
    public void onMoveLeftToNav() {
        if (navPanel != null) {
            NavPanelBuilder.focusHamburgerOrExpand(navPanel, this);
        }
    }

    // ── Callbacks: SearchOverlay & DetailOverlay ─────────────────────────

    public static void onRemoteChatStarted(String query, boolean reset) {
        AuraActivity inst = sActiveInstance;
        if (inst == null) return;
        inst.ui.post(() -> {
            if (reset) inst.onResetConversation();
            else if (query != null && !query.trim().isEmpty()) inst.prepareUiForSearchQuery(query.trim());
        });
    }

    public static void onRemoteChatProgress(String msg) {
        AuraActivity inst = sActiveInstance;
        if (inst != null) inst.postSearchProgress(msg);
    }

    public static void onRemoteChatCompleted(String query, boolean reset, JSONObject res) {
        AuraActivity inst = sActiveInstance;
        if (inst == null || reset) return;
        inst.ui.post(() -> {
            if (query != null && !query.trim().isEmpty()) inst.lastSearchQuery = query.trim();
            inst.displaySearchResults(res);
        });
    }

    public void runSearchQuery(String query) {
        String q = query != null ? query.trim() : "";
        if (q.isEmpty()) return;
        int turnId = ++activeSearchTurnId;
        prepareUiForSearchQuery(q);
        submitSearchQueryTurn(q, turnId, System.currentTimeMillis());
    }

    private void prepareUiForSearchQuery(String q) {
        lastSearchQuery = q;
        if (AmbientScreensaverOverlay.isVisible(screensaverOverlay)) {
            AmbientScreensaverOverlay.dismiss(screensaverOverlay);
        }
        resetTopBarSearchLabel();
        hideKeyboard(rootLayout);
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        setBackgroundFocusBlocked(true);
        ensureSearchOverlayShowingLoading(q);
    }

    private void resetTopBarSearchLabel() {
        if (searchLabel != null) {
            searchLabel.setText(SearchOverlay.MULTILINGUAL_HINTS[0]);
            searchLabel.setTextColor(GlassTheme.TEXT_SECONDARY);
        }
    }

    private void ensureSearchOverlayShowingLoading(String q) {
        if (!SearchOverlay.isVisible(searchOverlay)) {
            SearchOverlay.show(searchOverlay, this, false);
        }
        SearchOverlay.setQueryText(searchOverlay, q);
        SearchOverlay.setLoading(searchOverlay, true, "Understanding your search\u2026");
    }

    private void submitSearchQueryTurn(String q, int turnId, long startMs) {
        bgPool.submit(() -> {
            JSONObject res = voice.handleTurn(activeProfile, q, false, msg -> postSearchProgressForTurn(turnId, msg));
            enforceMinLoadingDuration(startMs, 600L);
            ui.post(() -> { if (turnId == activeSearchTurnId) displaySearchResults(res); });
        });
    }

    private void postSearchProgressForTurn(int turnId, String msg) {
        if (turnId == activeSearchTurnId) postSearchProgress(msg);
    }

    private void postSearchProgress(String msg) {
        ui.post(() -> {
            if (SearchOverlay.isVisible(searchOverlay)) {
                SearchOverlay.setLoading(searchOverlay, true, msg);
            }
        });
    }

    private void displaySearchResults(JSONObject res) {
        if (!SearchOverlay.isVisible(searchOverlay)) return;
        SearchOverlay.setLoading(searchOverlay, false, "");
        if (res == null || tryPresentPureCompanionRecap(res)) return;
        populateSearchOverlayResultViews(res);
        populateTraversalChips();
    }

    private void populateSearchOverlayResultViews(JSONObject res) {
        SearchOverlay.setConversationTurns(searchOverlay, res.optJSONArray("turns"), this);
        if (!lastSearchQuery.isEmpty()) SearchOverlay.setQueryText(searchOverlay, lastSearchQuery);
        String spoken = res.optString("spoken_narration", "");
        if (!spoken.isEmpty()) AuraTtsNarrator.speak(this, spoken, null);
        SearchOverlay.setFollowUps(searchOverlay, extractFollowUpList(res.optJSONArray("follow_ups")), this, this);
        SearchOverlay.setResults(searchOverlay, res.optJSONArray("results"), this, this);
    }

    private boolean tryPresentPureCompanionRecap(JSONObject res) {
        JSONObject recap = res.optJSONObject("companion_recap");
        if (recap == null || !recap.optString("actor_to_search", "").isEmpty()) return false;
        SearchOverlay.dismiss(searchOverlay);
        TvOverlayManager ov = TvOverlayManager.peekInstance();
        if (ov == null) ov = new TvOverlayManager(this);
        ov.showInAppCompanionRecap(recap);
        return true;
    }

    private List<String> extractFollowUpList(JSONArray arr) {
        List<String> chips = new ArrayList<>();
        if (arr == null) return chips;
        for (int i = 0; i < arr.length(); i++) chips.add(arr.optString(i));
        return chips;
    }

    @Override
    public void onResultClicked(JSONObject item) {
        SearchOverlay.dismiss(searchOverlay);
        clearRetainedSearchState();
        openDetailDrawer(item);
    }

    @Override
    public void onFollowUpClicked(String query) {
        runSearchQuery(query);
    }

    @Override
    public void onResetConversation() {
        clearRetainedSearchState();
        applyResetConversationUi();
    }

    private void clearRetainedSearchState() {
        ++activeSearchTurnId;
        lastSearchQuery = "";
        resetTopBarSearchLabel();
        db.saveVoiceSession(activeProfile, new JSONObject());
        db.setSetting("traversal_chips_" + activeProfile, "");
    }

    private void applyResetConversationUi() {
        SearchOverlay.setConversationTurns(searchOverlay, new JSONArray(), this);
        SearchOverlay.setFollowUps(searchOverlay, new ArrayList<>(), this, this);
        SearchOverlay.setResults(searchOverlay, new JSONArray(), this, this);
    }

    private void saveFocusBeforeOverlay() {
        if (DetailOverlay.isVisible(detailOverlay) || SearchOverlay.isVisible(searchOverlay)
                || (modalContainer != null && modalContainer.getVisibility() == View.VISIBLE)) {
            return;
        }
        View cur = getCurrentFocus();
        if (cur != null && !isDescendantOf(cur, navPanel)) {
            lastFocusedView = cur;
            lastRailFocus = findFocusedRailAndCard();
            lastScrollY = mainScroll != null ? mainScroll.getScrollY() : 0;
        }
    }

    private void restoreFocusAfterOverlay() {
        if (lastFocusedView != null && lastFocusedView.isShown()) {
            if (mainScroll != null) mainScroll.scrollTo(0, lastScrollY);
            lastFocusedView.requestFocus();
            return;
        }
        restoreOrInitFocus(lastRailFocus, lastScrollY);
    }

    @Override
    public void onDismiss() {
        AuraTtsNarrator.stop(this);
        dismissActiveOverlays();
        setBackgroundFocusBlocked(false);
        if (watchedChangedWhileInDetail) {
            watchedChangedWhileInDetail = false;
            replaceWatchedFromReserve();
            return;
        }
        restoreFocusAfterOverlay();
    }

    private void dismissActiveOverlays() {
        if (DetailOverlay.isVisible(detailOverlay)) {
            currentDetailItem = null;
            DetailOverlay.dismiss(detailOverlay);
        }
        if (SearchOverlay.isVisible(searchOverlay)) {
            SearchOverlay.dismiss(searchOverlay);
            clearRetainedSearchState();
        }
    }

    private String resolveCanonicalTitle(JSONObject item, String fallback) {
        if (item == null) return fallback;
        String series = item.optString("series_title", "").trim();
        String title = item.optString("title", "").trim();
        String chosen = !series.isEmpty() ? series : title;
        return !chosen.isEmpty() ? chosen : fallback;
    }

    private void openDetailDrawer(JSONObject item) {
        if (item == null) return;
        currentDetailItem = item;
        saveFocusBeforeOverlay();
        setBackgroundFocusBlocked(true);
        String title = resolveCanonicalTitle(item, "");
        boolean watched = db.isTitleWatched(title);
        int rating = db.getUserRatingsMap().getOrDefault(AuraDb.normalizeTitleKey(title), 0);
        DetailOverlay.showItem(detailOverlay, item, watched, rating, this, this);
        bgPool.submit(() -> enrichDetailDrawerAsync(item, title));
    }

    private void enrichDetailDrawerAsync(JSONObject item, String title) {
        maybeUpgradeDetailPoster(item, title);
        JSONArray sim = db.filterOutWatched(JustWatchGraph.fetchCollaborativeSimilar(title));
        ui.post(() -> {
            if (DetailOverlay.isShowingTitle(detailOverlay, item.optString("title"))) {
                DetailOverlay.setSimilarItems(detailOverlay, sim, this, this);
            }
        });
    }

    private void maybeUpgradeDetailPoster(JSONObject item, String title) {
        String curPoster = item.optString("poster_url", "");
        if (!curPoster.isEmpty() && curPoster.contains("/s718/")) return;
        String freshPoster = JustWatchGraph.lookupTitle(title).optString("poster_url", "");
        if (freshPoster.isEmpty()) return;
        try { item.put("poster_url", freshPoster); } catch (Exception ignored) {}
        ui.post(() -> {
            if (DetailOverlay.isShowingTitle(detailOverlay, item.optString("title"))) {
                DetailOverlay.updatePosterUrl(detailOverlay, freshPoster, this);
            }
        });
    }

    @Override
    public void onNotInterested(JSONObject item) {
        if (item == null) return;
        TasteIntelligenceEngine.recordNotInterested(db, activeProfile, item);
        ReserveSlateManager.pruneTitleFromReserve(db, activeProfile);
        watchedChangedWhileInDetail = true;
        Toast.makeText(this, "Hidden \u2014 Aura learned your taste", Toast.LENGTH_SHORT).show();
        onDismiss();
    }

    @Override
    public void onWatchedToggle(JSONObject item) {
        String title = resolveCanonicalTitle(item, "");
        boolean nowWatched = !db.isTitleWatched(title);
        db.markTitleWatched(item, nowWatched);
        TasteIntelligenceEngine.buildAndPersistTasteDna(db, activeProfile);
        watchedChangedWhileInDetail = true;
        int rating = db.getUserRatingsMap().getOrDefault(AuraDb.normalizeTitleKey(title), 0);
        DetailOverlay.updateUserFeedback(detailOverlay, item, nowWatched, rating, this, this);
        if (nowWatched) ui.postDelayed(this::onDismiss, 160);
    }

    @Override
    public void onRated(JSONObject item, int score) {
        String title = resolveCanonicalTitle(item, "");
        db.rateTitle(item, score);
        TasteIntelligenceEngine.buildAndPersistTasteDna(db, activeProfile);
        watchedChangedWhileInDetail = true;
        Toast.makeText(this, "Rated " + score + "/10", Toast.LENGTH_SHORT).show();
        boolean watched = db.isTitleWatched(title);
        DetailOverlay.updateUserFeedback(detailOverlay, item, watched, score, this, this);
        ui.postDelayed(this::onDismiss, 160);
    }

    @Override
    public void onTraversalClicked(JSONObject similarItem) {
        openDetailDrawer(similarItem);
    }

    // ── Glass Modals: Guide AI, Aura Settings & Visual Watch History ─────

    private void showGuideAiDialog() {
        saveFocusBeforeOverlay();
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        setBackgroundFocusBlocked(true);
        ModalOverlayBuilder.showGuideAiModal(modalContainer, this, db, activeProfile, this);
    }

    private void showSystemMonitorDialog() {
        saveFocusBeforeOverlay();
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        setBackgroundFocusBlocked(true);
        SystemMonitorModal.show(modalContainer, this, db, this);
    }

    private void showSettingsDialog() {
        saveFocusBeforeOverlay();
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        setBackgroundFocusBlocked(true);
        ModalOverlayBuilder.showAuraSettingsModal(
            modalContainer, this, db, activeProfile, REMOTE_PORT, this);
    }

    private void showWatchHistoryDialog() {
        saveFocusBeforeOverlay();
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        setBackgroundFocusBlocked(true);
        ModalOverlayBuilder.showWatchHistoryModal(
            modalContainer, this, db, activeProfile, this);
    }

    private void openSystemSetting(String dest) {
        MediaDeepLinkLauncher.openSystemSetting(this, dest);
    }

    private void launchMediaItem(JSONObject item) {
        MediaDeepLinkLauncher.launchMediaItem(this, db, item);
    }

    private void launchPackage(String pkg) {
        MediaDeepLinkLauncher.launchPackage(this, pkg);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBackKeyPress();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void handleBackKeyPress() {
        if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE) {
            cancelBlockingLoaderByUser();
        } else if (modalContainer != null && modalContainer.getVisibility() == View.VISIBLE) {
            ModalOverlayBuilder.closeModal(modalContainer, this);
        } else if (DetailOverlay.isVisible(detailOverlay) || SearchOverlay.isVisible(searchOverlay)) {
            onDismiss();
        } else if (NavPanelBuilder.isExpanded(navPanel)) {
            collapseNavPanelToHero();
        }
    }

    private void cancelBlockingLoaderByUser() {
        loaderDismissedByUser = true;
        setBannerText("", false);
        if (!DetailOverlay.isVisible(detailOverlay) && !SearchOverlay.isVisible(searchOverlay)) {
            setBackgroundFocusBlocked(false);
            restoreFocusAfterOverlay();
        }
    }

    private void collapseNavPanelToHero() {
        NavPanelBuilder.setExpanded(navPanel, false, this);
        HeroSliderBuilder.requestInitialFocus(heroSlider);
    }

    @Override
    public void onBackPressed() {
        // Home launcher never finishes on Back press
    }

    private final Runnable hideLoaderSafetyTask = () -> setBannerText("", false);

    private void setBannerText(String msg, boolean visible) {
        ui.removeCallbacks(hideLoaderSafetyTask);
        statusBanner.setText(msg);
        statusBanner.setVisibility(View.GONE);
        if (loadingOverlay == null) return;
        if (visible) showGlobalLoadingOverlay(msg);
        else hideGlobalLoadingOverlay();
    }

    private void showGlobalLoadingOverlay(String msg) {
        String clean = (msg != null && msg.startsWith("\u2726")) ? msg.substring(1).trim() : msg;
        ui.postDelayed(hideLoaderSafetyTask, 25000);
        if (globalLoaderText != null && clean != null && !clean.isEmpty()) {
            updateGlobalLoaderTextSmooth(clean);
        }
        if (mainScroll != null) mainScroll.animate().alpha(0.08f).setDuration(180).start();
        fadeInGlobalLoadingOverlay();
    }

    private void updateGlobalLoaderTextSmooth(String clean) {
        CharSequence cur = globalLoaderText.getText();
        if (loadingOverlay.getVisibility() != View.VISIBLE || cur == null || cur.toString().equals(clean)) {
            globalLoaderText.setText(clean);
            return;
        }
        globalLoaderText.animate().alpha(0.2f).setDuration(130).withEndAction(() -> {
            globalLoaderText.setText(clean);
            globalLoaderText.animate().alpha(1f).setDuration(170).start();
        }).start();
    }

    private void fadeInGlobalLoadingOverlay() {
        if (loadingOverlay.getVisibility() == View.VISIBLE) {
            AgenticOrbView.ensureRunningIn(loadingOverlay);
            return;
        }
        loadingOverlay.animate().cancel();
        loadingOverlay.setAlpha(0f);
        loadingOverlay.setVisibility(View.VISIBLE);
        AgenticOrbView.ensureRunningIn(loadingOverlay);
        loadingOverlay.animate().alpha(1f).setDuration(180).setInterpolator(GlassTheme.EASE).start();
    }

    private void hideGlobalLoadingOverlay() {
        if (mainScroll != null) mainScroll.animate().alpha(1f).setDuration(180).start();
        if (loadingOverlay.getVisibility() == View.VISIBLE) {
            loadingOverlay.animate().cancel();
            loadingOverlay.animate().alpha(0f).setDuration(160)
                .setInterpolator(GlassTheme.EASE)
                .withEndAction(() -> loadingOverlay.setVisibility(View.GONE))
                .start();
        }
    }

    private List<JSONObject> toJsonList(JSONArray arr) {
        List<JSONObject> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) list.add(o);
        }
        return list;
    }

    private int dp(float v) {
        return GlassTheme.dp(this, v);
    }
}
