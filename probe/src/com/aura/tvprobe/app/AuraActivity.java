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
    private static volatile String sLastGlobalVoiceQuery = "";
    private static volatile long sLastGlobalVoiceMs = 0L;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bgPool = Executors.newFixedThreadPool(
        Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
    private final Runnable idleScreensaverTask = this::activateScreensaver;
    private final Runnable homeSearchHintTask = this::runHomeSearchHintLoop;
    private final Runnable hourlyRecoRefreshTask = this::runHourlyRecoRefreshLoop;
    private volatile boolean refreshInFlight = false;
    private long lastRenderedRecoSec = 0L;
    private String lastRenderedHistorySignature = "";

    private AuraDb db;
    private AiRecommender ai;
    private SyncManager sync;
    private VoiceConcierge voice;
    private SessionInspector inspector;
    private String activeProfile = AuraDb.DEFAULT_PROFILE;
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
    private TextView globalLoaderText;
    private View lastFocusedView;
    private int[] lastRailFocus;
    private int lastScrollY;
    private boolean watchedChangedWhileInDetail = false;
    private final android.content.BroadcastReceiver homeKeyReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("homekey".equals(intent.getStringExtra("reason"))) {
                ui.post(() -> resetToHomeTop());
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sActiveInstance = this;
        sLastActiveWallMs = System.currentTimeMillis();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        initServices();
        buildUi();
        setupGlobalFocusRouting();
        registerHomeKeyReceiverSafe();
        startHomeSearchHintAnimation();
        resetIdleTimer();
        refreshAllData(false);
        handleIncomingSearchIntent(getIntent());
    }

    private void registerHomeKeyReceiverSafe() {
        try {
            registerReceiver(homeKeyReceiver, new android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        sActiveInstance = this;
        sIsInForeground = true;
        sLastActiveWallMs = System.currentTimeMillis();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        HeroSliderBuilder.startAutoRotate(heroSlider, ui);
        startHomeSearchHintAnimation();
        scheduleHourlyRecoRefresh();
        updateNavMetadata();
        resetIdleTimer();
        maybeRefreshOnResume();
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
        if (topChanged) {
            refreshAllData(true);
        } else if (sigChanged) {
            renderHomeContent(db.getProfileRecommendations(activeProfile));
        }
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
        if (DetailOverlay.isVisible(detailOverlay)) DetailOverlay.dismiss(detailOverlay);
        if (SearchOverlay.isVisible(searchOverlay)) SearchOverlay.dismiss(searchOverlay);
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
        ImageLoader.trimMemory();
    }

    @Override
    protected void onDestroy() {
        if (sActiveInstance == this) sActiveInstance = null;
        sIsInForeground = false;
        ui.removeCallbacks(hourlyRecoRefreshTask);
        try { unregisterReceiver(homeKeyReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    public static void onGlobalVoiceSearchQuery(Context ctx, String rawQuery) {
        String q = rawQuery != null ? rawQuery.trim() : "";
        if (!shouldHandleGlobalVoiceQuery(q)) return;
        AuraActivity inst = sActiveInstance;
        if (inst == null) return;
        inst.ui.post(() -> inst.runSearchQuery(q));
        scheduleBringAuraToFront(inst.ui, ctx);
    }

    private static boolean shouldHandleGlobalVoiceQuery(String q) {
        long now = System.currentTimeMillis();
        boolean activeOrJustPaused = sIsInForeground || (now - sLastActiveWallMs) < 90_000L;
        if (q.length() < 2 || !activeOrJustPaused) return false;
        if (q.equalsIgnoreCase(sLastGlobalVoiceQuery) && (now - sLastGlobalVoiceMs) < 3_500L) return false;
        sLastGlobalVoiceQuery = q;
        sLastGlobalVoiceMs = now;
        sLastActiveWallMs = now;
        return true;
    }

    private static void scheduleBringAuraToFront(Handler h, Context ctx) {
        bringAuraToFrontNow(ctx);
        for (long delay : new long[]{160L, 340L, 600L, 950L, 1450L, 2200L, 3200L}) {
            h.postDelayed(() -> bringAuraToFrontNow(ctx), delay);
        }
    }

    private static void bringAuraToFrontNow(Context ctx) {
        try {
            ctx.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            Intent i = new Intent(ctx, AuraActivity.class);
            i.putExtra("aura_bring_to_front", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(i);
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
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                AmbientScreensaverOverlay.dismiss(screensaverOverlay);
                HeroSliderBuilder.startAutoRotate(heroSlider, ui);
                HeroSliderBuilder.requestInitialFocus(heroSlider);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && VoiceSearchHelper.isRemoteVoiceOrSearchKey(event.getKeyCode())) {
            startVoiceSearch();
            return true;
        }
        return super.dispatchKeyEvent(event);
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
        activeProfile = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
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
        populateSearchStudioFromSession(db.getVoiceSession(activeProfile));
        SearchOverlay.show(searchOverlay, this, true);
    }

    private void populateSearchStudioFromSession(JSONObject session) {
        JSONArray turns = session != null ? session.optJSONArray("turns") : null;
        JSONArray results = session != null ? session.optJSONArray("results") : null;
        if (results == null || results.length() == 0) {
            results = db.getProfileRecommendations(activeProfile);
        }
        SearchOverlay.setConversationTurns(searchOverlay, turns, this);
        String reply = session != null ? session.optString("reply", "") : "";
        SearchOverlay.addMessage(searchOverlay, "assistant", resolveInitialStudioReply(reply), this);
        SearchOverlay.setFollowUps(searchOverlay, db.getTraversalChips(activeProfile), this, this);
        SearchOverlay.setResults(searchOverlay, results, this, this);
    }

    private String resolveInitialStudioReply(String reply) {
        return !reply.isEmpty()
            ? reply
            : "Ask any question or click a follow-up chip below to refine your recommendations across your streaming apps.";
    }

    private void hideKeyboard(View v) {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && v != null) {
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
    }

    private HorizontalScrollView buildChipsScroll() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFocusable(false);
        hsv.setClipChildren(true);
        hsv.setClipToPadding(true);
        hsv.setPadding(dp(4), dp(2), dp(4), dp(2));
        hsv.addView(buildChipsRowLayout());
        return hsv;
    }

    private LinearLayout buildChipsRowLayout() {
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        chipsRow.setClipChildren(false);
        return chipsRow;
    }

    private void addOverlays() {
        searchOverlay = SearchOverlay.build(this, this);
        detailOverlay = DetailOverlay.build(this, this);
        modalContainer = new FrameLayout(this);
        modalContainer.setVisibility(View.GONE);
        loadingOverlay = buildGlobalLoadingOverlay();
        screensaverOverlay = buildScreensaverOverlayView();
        attachAllOverlaysToRoot();
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
        updateNavMetadata();
        populateTraversalChips();
        if (forceRefresh) setBannerText("Looking at what you\u2019ve been watching lately\u2026", true);
        JSONArray cached = db.getProfileRecommendations(activeProfile);
        boolean hadFresh = cached.length() >= 12 && db.isRecoCacheFresh(activeProfile);
        if (!forceRefresh || railsContainer.getChildCount() == 0) {
            renderHomeContent(cached.length() > 0 ? cached : ai.getBuiltInSotaSeedCatalog(activeProfile));
        }
        submitBackgroundRefresh(forceRefresh, hadFresh);
    }

    private void submitBackgroundRefresh(boolean forceRefresh, boolean hadFresh) {
        if (refreshInFlight) return;
        refreshInFlight = true;
        bgPool.submit(() -> executeBackgroundRefreshTask(forceRefresh, hadFresh));
    }

    private void executeBackgroundRefreshTask(boolean forceRefresh, boolean hadFresh) {
        String prevTop = getTopContinueWatchingTitle();
        JSONObject np = syncNowPlayingSafe();
        boolean topChanged = !getTopContinueWatchingTitle().equals(prevTop);
        if (!forceRefresh && hadFresh && !topChanged) {
            postContinueWatchingUpdateIfChanged();
            return;
        }
        refreshRecommendationsInBackground(np, forceRefresh || topChanged, hadFresh);
    }

    private void postContinueWatchingUpdateIfChanged() {
        refreshInFlight = false;
        if (!currentContinueWatchingSignature().equals(lastRenderedHistorySignature)) {
            ui.post(() -> renderHomeContent(db.getProfileRecommendations(activeProfile)));
        }
    }

    private void refreshRecommendationsInBackground(JSONObject np, boolean forceRefresh, boolean hadFresh) {
        JSONArray history = db.listHistory(activeProfile);
        if (np == null && history.length() > 0) np = history.optJSONObject(0);
        JSONArray fresh = ai.getOrRefreshForProfile(activeProfile, np, history, forceRefresh,
            step -> postRefreshBannerStep(step, forceRefresh));
        ui.post(() -> applyBackgroundRefreshResult(fresh, forceRefresh, hadFresh));
    }

    private void postRefreshBannerStep(String step, boolean forceRefresh) {
        if (forceRefresh) ui.post(() -> setBannerText(step, true));
    }

    private JSONObject syncNowPlayingSafe() {
        try { return inspector.forceSyncProvidersAndNowPlaying(activeProfile); } catch (Exception e) { return null; }
    }

    private void applyBackgroundRefreshResult(JSONArray fresh, boolean forceRefresh, boolean hadFresh) {
        refreshInFlight = false;
        setBannerText("", false);
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
        for (String chip : db.getTraversalChips(activeProfile)) {
            chipsRow.addView(buildTraversalChipButton(chip));
        }
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
        final int savedScrollY = mainScroll != null ? mainScroll.getScrollY() : 0;
        final int[] savedRailFocus = findFocusedRailAndCard();
        List<JSONObject> fullList = toJsonList(recos);
        List<JSONObject> all = fullList.subList(0, Math.min(16, fullList.size()));
        AmbientScreensaverOverlay.setItems(screensaverOverlay, all);
        HeroSliderBuilder.setItems(heroSlider, all.subList(0, Math.min(6, all.size())), this, this);
        HeroSliderBuilder.startAutoRotate(heroSlider, ui);
        rebuildHomeRails(all);
        animateHomeRailsEntrance();
        restoreOrInitFocus(savedRailFocus, savedScrollY);
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
        if (savedRailFocus != null && savedRailFocus[0] >= 0
                && savedRailFocus[0] < railsContainer.getChildCount()) {
            focusSavedRailItem(savedRailFocus[0], savedRailFocus[1], savedScrollY);
            return;
        }
        if (mainScroll != null) mainScroll.scrollTo(0, 0);
        HeroSliderBuilder.requestInitialFocus(heroSlider);
    }

    private void focusSavedRailItem(int railIdx, int cardIdx, int savedScrollY) {
        if (mainScroll != null) mainScroll.scrollTo(0, savedScrollY);
        View railView = railsContainer.getChildAt(railIdx);
        if (railView instanceof LinearLayout) {
            RailBuilder.focusItemAt((LinearLayout) railView, cardIdx);
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
        else if ("refresh_recos".equals(destination)) refreshAllData(true);
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
        ModalOverlayBuilder.showQrRemoteModal(modalContainer, this, ip, REMOTE_PORT, this);
    }

    @Override
    public void onRefreshRecommendations(boolean forceRefresh) {
        refreshAllData(forceRefresh);
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
        refreshAllData(true);
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
    public void onWatchedClicked(JSONObject item) {
        db.markTitleWatched(item, true);
        Toast.makeText(this, "Marked watched: " + item.optString("title"), Toast.LENGTH_SHORT).show();
        replaceWatchedFromReserve();
    }

    private void replaceWatchedFromReserve() {
        JSONArray remaining = db.getProfileRecommendations(activeProfile);
        renderHomeContent(remaining);
        if (remaining.length() < 12) {
            submitBackgroundRefresh(true, true);
        }
    }

    @Override
    public void onSimilarClicked(JSONObject item) {
        String seed = item.optString("title", "this title");
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        setBackgroundFocusBlocked(true);
        setBannerText("Looking for shows and movies like " + seed + "\u2026", true);
        final long startMs = System.currentTimeMillis();
        bgPool.submit(() -> executeSimilarTraversal(seed, startMs));
    }

    private void executeSimilarTraversal(String seed, long startMs) {
        JSONArray sim = ai.traverseSimilarToWatched(activeProfile, seed,
            msg -> ui.post(() -> setBannerText(msg, true)));
        enforceMinLoadingDuration(startMs, 1750L);
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
        if (sim != null && sim.length() > 0) renderHomeContent(sim);
        JSONObject saved = db.getVoiceSession(activeProfile);
        SearchOverlay.setConversationTurns(searchOverlay,
            saved != null ? saved.optJSONArray("turns") : new JSONArray(), this);
        List<String> chips = java.util.Arrays.asList("Only on Netflix", "Shorter under 2h", "Darker & grittier");
        SearchOverlay.setFollowUps(searchOverlay, chips, this, this);
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

    public void runSearchQuery(String query) {
        String q = query != null ? query.trim() : "";
        if (q.isEmpty()) return;
        if (q.equalsIgnoreCase(lastSearchQuery) && SearchOverlay.isVisible(searchOverlay)) return;
        prepareUiForSearchQuery(q);
        submitSearchQueryTurn(q, System.currentTimeMillis());
    }

    private void prepareUiForSearchQuery(String q) {
        lastSearchQuery = q;
        if (AmbientScreensaverOverlay.isVisible(screensaverOverlay)) {
            AmbientScreensaverOverlay.dismiss(screensaverOverlay);
        }
        if (searchLabel != null) {
            searchLabel.setText(q);
            searchLabel.setTextColor(GlassTheme.TEXT_PRIMARY);
        }
        hideKeyboard(rootLayout);
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        setBackgroundFocusBlocked(true);
        ensureSearchOverlayShowingLoading();
    }

    private void ensureSearchOverlayShowingLoading() {
        if (!SearchOverlay.isVisible(searchOverlay)) {
            SearchOverlay.show(searchOverlay, this, false);
        }
        SearchOverlay.setLoading(searchOverlay, true,
            "Understanding what you\u2019re in the mood to watch\u2026");
    }

    private void submitSearchQueryTurn(String q, long startMs) {
        bgPool.submit(() -> {
            JSONObject res = voice.handleTurn(activeProfile, q, false, this::postSearchProgress);
            enforceMinLoadingDuration(startMs, 1600L);
            ui.post(() -> displaySearchResults(res));
        });
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
        if (res == null) return;
        SearchOverlay.setConversationTurns(searchOverlay, res.optJSONArray("turns"), this);
        String reply = res.optString("reply", "");
        if (!reply.isEmpty()) SearchOverlay.addMessage(searchOverlay, "assistant", reply, this);
        SearchOverlay.setFollowUps(searchOverlay, extractFollowUpList(res.optJSONArray("follow_ups")), this, this);
        SearchOverlay.setResults(searchOverlay, res.optJSONArray("results"), this, this);
        populateTraversalChips();
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
        openDetailDrawer(item);
    }

    @Override
    public void onFollowUpClicked(String query) {
        runSearchQuery(query);
    }

    @Override
    public void onResetConversation() {
        lastSearchQuery = "";
        if (searchLabel != null) {
            searchLabel.setText(SearchOverlay.MULTILINGUAL_HINTS[0]);
            searchLabel.setTextColor(GlassTheme.TEXT_SECONDARY);
        }
        bgPool.submit(() -> {
            voice.handleTurn(activeProfile, "", true);
            ui.post(this::applyResetConversationUi);
        });
    }

    private void applyResetConversationUi() {
        SearchOverlay.setConversationTurns(searchOverlay, new JSONArray(), this);
        SearchOverlay.addMessage(searchOverlay, "assistant",
            "Started a fresh conversation topic. Type a query above or pick a chip below.", this);
        SearchOverlay.setFollowUps(searchOverlay, db.getTraversalChips(activeProfile), this, this);
        SearchOverlay.setResults(searchOverlay, db.getProfileRecommendations(activeProfile), this, this);
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
        if (DetailOverlay.isVisible(detailOverlay)) DetailOverlay.dismiss(detailOverlay);
        if (SearchOverlay.isVisible(searchOverlay)) SearchOverlay.dismiss(searchOverlay);
        setBackgroundFocusBlocked(false);
        if (watchedChangedWhileInDetail) {
            watchedChangedWhileInDetail = false;
            replaceWatchedFromReserve();
            return;
        }
        restoreFocusAfterOverlay();
    }

    private void openDetailDrawer(JSONObject item) {
        if (item == null) return;
        saveFocusBeforeOverlay();
        setBackgroundFocusBlocked(true);
        String title = item.optString("title");
        boolean watched = db.isTitleWatched(title);
        int rating = db.getUserRatingsMap().getOrDefault(AuraDb.normalizeTitleKey(title), 0);
        DetailOverlay.showItem(detailOverlay, item, watched, rating, this, this);
        bgPool.submit(() -> enrichDetailDrawerAsync(item, title));
    }

    private void enrichDetailDrawerAsync(JSONObject item, String title) {
        maybeUpgradeDetailPoster(item, title);
        JSONArray sim = db.filterOutWatched(JustWatchGraph.fetchCollaborativeSimilar(title));
        ui.post(() -> {
            if (DetailOverlay.isShowingTitle(detailOverlay, title)) {
                DetailOverlay.setSimilarItems(detailOverlay, sim, this, this);
            }
        });
    }

    private void maybeUpgradeDetailPoster(JSONObject item, String title) {
        String curPoster = item.optString("poster_url", "");
        if (!curPoster.isEmpty() && curPoster.contains("/s718/")) return;
        String freshPoster = JustWatchGraph.lookupTitleInIndia(title).optString("poster_url", "");
        if (freshPoster.isEmpty()) return;
        try { item.put("poster_url", freshPoster); } catch (Exception ignored) {}
        ui.post(() -> {
            if (DetailOverlay.isShowingTitle(detailOverlay, title)) {
                DetailOverlay.updatePosterUrl(detailOverlay, freshPoster, this);
            }
        });
    }

    @Override
    public void onWatchedToggle(JSONObject item) {
        String title = item.optString("title");
        boolean nowWatched = !db.isTitleWatched(title);
        db.markTitleWatched(item, nowWatched);
        watchedChangedWhileInDetail = true;
        int rating = db.getUserRatingsMap().getOrDefault(AuraDb.normalizeTitleKey(title), 0);
        DetailOverlay.updateUserFeedback(detailOverlay, item, nowWatched, rating, this, this);
    }

    @Override
    public void onRated(JSONObject item, int score) {
        String title = item.optString("title");
        db.rateTitle(item, score);
        watchedChangedWhileInDetail = true;
        Toast.makeText(this, "Rated " + score + "/10", Toast.LENGTH_SHORT).show();
        boolean watched = db.isTitleWatched(title);
        DetailOverlay.updateUserFeedback(detailOverlay, item, watched, score, this, this);
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
            setBannerText("", false);
        } else if (modalContainer != null && modalContainer.getVisibility() == View.VISIBLE) {
            ModalOverlayBuilder.closeModal(modalContainer, this);
        } else if (DetailOverlay.isVisible(detailOverlay) || SearchOverlay.isVisible(searchOverlay)) {
            onDismiss();
        } else if (NavPanelBuilder.isExpanded(navPanel)) {
            NavPanelBuilder.setExpanded(navPanel, false, this);
            HeroSliderBuilder.requestInitialFocus(heroSlider);
        }
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
            globalLoaderText.setText(clean);
        }
        if (mainScroll != null) mainScroll.animate().alpha(0.08f).setDuration(180).start();
        fadeInGlobalLoadingOverlay();
    }

    private void fadeInGlobalLoadingOverlay() {
        if (loadingOverlay.getVisibility() == View.VISIBLE) return;
        loadingOverlay.animate().cancel();
        loadingOverlay.setAlpha(0f);
        loadingOverlay.setVisibility(View.VISIBLE);
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
