package com.aura.tvprobe;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
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
        DetailOverlay.DetailCallback {

    private static final int REMOTE_PORT = 8765;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bgPool = Executors.newFixedThreadPool(3);

    private AuraDb db;
    private AiRecommender ai;
    private SyncManager sync;
    private VoiceConcierge voice;
    private SessionInspector inspector;
    private String activeProfile = AuraDb.DEFAULT_PROFILE;
    private boolean initialFocusSet = false;
    private String lastSearchQuery = "";

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        initServices();
        buildUi();
        setupGlobalFocusRouting();
        refreshAllData(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HeroSliderBuilder.startAutoRotate(heroSlider, ui);
        updateNavMetadata();
    }

    @Override
    protected void onPause() {
        super.onPause();
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
    }

    private void initServices() {
        db = AuraDb.getInstance(this);
        db.purgePromotionalNoiseFromHistory();
        ai = new AiRecommender(db);
        sync = new SyncManager(db);
        voice = new VoiceConcierge(db, ai, sync);
        inspector = new SessionInspector(this, new ComponentName(this, MediaProbeService.class), db, ai);
        activeProfile = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
        try {
            startService(new Intent(this, MediaProbeService.class));
        } catch (Throwable ignored) {}
    }

    private void buildUi() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(GlassTheme.BG_AMBIENT);
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
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.addView(buildTopBar());
        heroSlider = HeroSliderBuilder.build(this, this);
        col.addView(heroSlider);
        statusBanner = GlassTheme.makeText(this, "", 11f, GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1);
        statusBanner.setPadding(dp(20), dp(2), dp(20), dp(2));
        statusBanner.setVisibility(View.GONE);
        col.addView(statusBanner);
        railsContainer = new LinearLayout(this);
        railsContainer.setOrientation(LinearLayout.VERTICAL);
        railsContainer.setClipChildren(false);
        railsContainer.setClipToPadding(false);
        railsContainer.setPadding(dp(8), 0, dp(12), dp(36));
        col.addView(railsContainer, GlassTheme.matchWrapParams());
        return col;
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setPadding(dp(14), dp(10), dp(14), dp(6));
        // 3-dash (☰) button lives inside the top bar so it scrolls cleanly and never overlaps rail headers
        bar.addView(NavPanelBuilder.createTopBarHamburger(navPanel, this));
        bar.addView(buildSearchBox());
        LinearLayout.LayoutParams chipsLp = GlassTheme.weightParams(1f);
        chipsLp.leftMargin = dp(8);
        chipsLp.rightMargin = dp(10);
        bar.addView(buildChipsScroll(), chipsLp);
        profileClockLabel = GlassTheme.makeText(this, "", 11f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        profileClockLabel.setBackground(GlassTheme.glassCard(dp(10)));
        profileClockLabel.setPadding(dp(12), dp(6), dp(12), dp(6));
        bar.addView(profileClockLabel);
        return bar;
    }

    private LinearLayout buildSearchBox() {
        searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        float r = dp(12);
        searchBox.setBackground(GlassTheme.glassCard(r));
        searchBox.setPadding(dp(12), dp(5), dp(6), dp(5));
        searchBox.setFocusable(true);
        searchBox.setClickable(true);

        TextView icon = GlassTheme.makeText(this, "\u2315", 13f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_BOLD, 1);
        icon.setGravity(Gravity.CENTER);
        searchBox.addView(icon, GlassTheme.wrapParams());

        searchLabel = GlassTheme.makeText(this, "Search shows or ask Aura AI\u2026", 11.5f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_REGULAR, 1);
        searchLabel.setGravity(Gravity.CENTER_VERTICAL);
        searchLabel.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(dp(215), dp(26));
        lblLp.gravity = Gravity.CENTER_VERTICAL;
        searchBox.addView(searchLabel, lblLp);

        TextView askPill = GlassTheme.makeText(this, "Ask AI", 10.5f,
            GlassTheme.TEXT_ON_LIGHT, GlassTheme.FONT_BOLD, 1);
        askPill.setBackground(GlassTheme.primaryPill(dp(8)));
        askPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        searchBox.addView(askPill, GlassTheme.wrapParams());

        searchBox.setOnClickListener(v -> showSearchInputDialog());
        searchBox.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                searchBox.setBackground(GlassTheme.focusRounded(
                    GlassTheme.SURFACE_ELEV, Color.WHITE, dp(2.5f), r));
                searchLabel.setTextColor(Color.WHITE);
                v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(150).start();
            } else {
                searchBox.setBackground(GlassTheme.glassCard(r));
                searchLabel.setTextColor(lastSearchQuery.isEmpty()
                    ? GlassTheme.TEXT_MUTED : GlassTheme.TEXT_PRIMARY);
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            }
        });
        return searchBox;
    }

    private void showSearchInputDialog() {
        EditText et = new EditText(this);
        et.setHint("e.g. Gripping crime thriller, sci-fi like Severance\u2026");
        et.setText(lastSearchQuery);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(EditorInfo.IME_ACTION_SEARCH);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Search Shows or Ask Aura AI")
            .setView(et)
            .setPositiveButton("Search AI", (d, w) -> {
                hideKeyboard(et);
                runSearchQuery(et.getText().toString());
            })
            .setNegativeButton("Cancel", (d, w) -> hideKeyboard(et))
            .create();

        et.setOnEditorActionListener((v, actionId, ev) -> {
            hideKeyboard(et);
            dialog.dismiss();
            runSearchQuery(et.getText().toString());
            return true;
        });
        dialog.show();
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
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        chipsRow.setClipChildren(false);
        hsv.addView(chipsRow);
        return hsv;
    }

    private void addOverlays() {
        searchOverlay = SearchOverlay.build(this, this);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        slp.topMargin = dp(52);
        slp.leftMargin = dp(16);
        slp.rightMargin = dp(16);
        slp.bottomMargin = dp(14);
        searchOverlay.setLayoutParams(slp);
        rootLayout.addView(searchOverlay);
        detailOverlay = DetailOverlay.build(this, this);
        rootLayout.addView(detailOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        db.purgePromotionalNoiseFromHistory();
        updateNavMetadata();
        populateTraversalChips();
        JSONArray cached = db.getProfileRecommendations(activeProfile);
        boolean renderedCached = false;
        if (cached.length() > 0 && !forceRefresh) {
            renderHomeContent(cached);
            renderedCached = true;
        } else {
            setBannerText("\u2726 Syncing Watch History & JustWatch India Graph\u2026", true);
        }
        final boolean alreadyRendered = renderedCached;
        bgPool.submit(() -> {
            try { inspector.syncAndBuildState(false); } catch (Exception ignored) {}
            if (alreadyRendered) return;
            JSONArray history = db.listHistory(activeProfile);
            JSONObject np = history.length() > 0 ? history.optJSONObject(0) : null;
            JSONArray fresh = ai.getOrRefreshForProfile(activeProfile, np, history, forceRefresh,
                msg -> ui.post(() -> setBannerText("\u2726 " + msg, true)));
            ui.post(() -> {
                setBannerText("", false);
                if (fresh != null && fresh.length() > 0) renderHomeContent(fresh);
                else if (cached.length() > 0) renderHomeContent(cached);
            });
        });
    }

    private void updateNavMetadata() {
        NavPanelBuilder.updateProfiles(navPanel, db.listProfiles(), activeProfile, this);
        long freeMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        String mode = db.getSetting("gemini_api_key", "").isEmpty() ? "JustWatch Graph" : "Gemini AI + JW";
        NavPanelBuilder.updateStatus(navPanel, mode, freeMb + " MB", String.valueOf(REMOTE_PORT));
        String profName = db.getProfileLabel(activeProfile);
        String clock = new SimpleDateFormat("EEE h:mm a", Locale.US).format(new Date());
        profileClockLabel.setText(profName + " \u00B7 " + clock);
    }

    private void populateTraversalChips() {
        chipsRow.removeAllViews();
        for (String chip : db.getTraversalChips(activeProfile)) {
            TextView btn = GlassTheme.makeGlassButton(this, "\u2726 " + chip);
            btn.setTextSize(10.5f);
            btn.setPadding(dp(10), dp(5), dp(10), dp(5));
            LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
            lp.rightMargin = dp(6);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> runSearchQuery(chip));
            chipsRow.addView(btn);
        }
    }

    private void renderHomeContent(JSONArray recos) {
        List<JSONObject> all = toJsonList(recos);
        List<JSONObject> heroList = all.subList(0, Math.min(6, all.size()));
        HeroSliderBuilder.setItems(heroSlider, heroList, this, this);
        HeroSliderBuilder.startAutoRotate(heroSlider, ui);
        railsContainer.removeAllViews();
        railsContainer.addView(RailBuilder.buildAppsDock(this, discoverTvApps(), this));
        addContinueWatchingIfPresent();
        addCategorizedRails(all);
        if (!initialFocusSet) {
            initialFocusSet = true;
            ui.post(() -> HeroSliderBuilder.requestInitialFocus(heroSlider));
        }
    }

    /**
     * Builds a diverse Continue Watching list across active OTT apps (Prime Video,
     * Disney+ Hotstar, Apple TV+, Netflix) using only verified 90-day recency items.
     */
    private void addContinueWatchingIfPresent() {
        List<JSONObject> rawHistory = toJsonList(db.listHistory(activeProfile));
        Map<String, List<JSONObject>> byPlatform = new LinkedHashMap<>();
        Set<String> seenTitles = new HashSet<>();

        for (JSONObject item : rawHistory) {
            String series = item.optString("series_title", "");
            String title = !series.isEmpty() ? series : item.optString("title", "");
            String rawApp = item.optString("app_name", item.optString("app", ""));
            String pkg = item.optString("app_pkg", "").toLowerCase();
            if (title.isEmpty() || pkg.contains("youtube") || pkg.contains("smarttube")
                    || pkg.contains("stremio")) {
                continue;
            }
            String cleanApp = HeroSliderBuilder.cleanPlatformName(rawApp);
            if (title.equalsIgnoreCase(cleanApp)) continue;
            int pos = item.optInt("position_sec", 0);
            int dur = item.optInt("duration_sec", 0);
            if (pos < 60) continue;
            if (dur > 0 && pos >= (dur * 95) / 100) continue;
            String normKey = AuraDb.normalizeTitleKey(title);
            if (normKey.isEmpty() || !seenTitles.add(normKey)) continue;
            byPlatform.computeIfAbsent(cleanApp, k -> new ArrayList<>()).add(item);
        }

        List<JSONObject> diversified = new ArrayList<>();
        boolean addedAny = true;
        int pass = 0;
        while (addedAny && diversified.size() < 8) {
            addedAny = false;
            for (List<JSONObject> list : byPlatform.values()) {
                if (pass < list.size()) {
                    diversified.add(list.get(pass));
                    addedAny = true;
                    if (diversified.size() >= 8) break;
                }
            }
            pass++;
        }
        if (!diversified.isEmpty()) {
            railsContainer.addView(RailBuilder.buildContinueWatching(this, diversified, this));
        }
    }

    private void addCategorizedRails(List<JSONObject> all) {
        if (all.isEmpty()) return;
        String profName = db.getProfileLabel(activeProfile);
        List<String> anchors = db.getDynamicTasteAnchors(activeProfile);
        String anchorSub = anchors.isEmpty()
            ? "Modern 2020\u20132026 Releases"
            : ("Based on " + String.join(", ", anchors.subList(0, Math.min(3, anchors.size()))));

        int n = all.size();
        List<JSONObject> r1 = all.subList(0, Math.min(8, n));
        railsContainer.addView(RailBuilder.buildContentRail(
            this, "Top Picks for " + profName, anchorSub, r1, this));
        if (n > 6) {
            List<JSONObject> r2 = all.subList(Math.min(6, n), Math.min(14, n));
            railsContainer.addView(RailBuilder.buildContentRail(
                this, "Trending Across Your Apps",
                "Netflix \u00B7 Prime Video \u00B7 JioHotstar \u00B7 Apple TV+", r2, this));
        }
        if (n > 12) {
            List<JSONObject> r3 = all.subList(Math.min(12, n), n);
            railsContainer.addView(RailBuilder.buildContentRail(
                this, "Critically Acclaimed & Mind-Bending",
                "IMDb 8.0+ \u00B7 Curated via JustWatch India Graph", r3, this));
        }
    }

    private List<JSONObject> discoverTvApps() {
        List<JSONObject> list = new ArrayList<>();
        Set<String> added = new HashSet<>();
        added.add(getPackageName());
        added.add("com.spocky.projengmenu");
        addFallbackStreamingApps(list, added);
        PackageManager pm = getPackageManager();
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(leanback, 0);
        if (resolved != null) {
            for (ResolveInfo ri : resolved) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (added.contains(pkg)) continue;
                CharSequence lbl = ri.loadLabel(pm);
                String name = lbl != null ? lbl.toString() : pkg;
                if (name.toLowerCase().contains("phone media")) continue;
                added.add(pkg);
                addAppJson(list, name, pkg, "#1E293B");
            }
        }
        return list;
    }

    private void addFallbackStreamingApps(List<JSONObject> list, Set<String> added) {
        String[][] defaults = {
            {"Netflix", "com.netflix.ninja", "#E50914"},
            {"Prime Video", "com.amazon.amazonvideo.livingroom", "#00A8E1"},
            {"JioHotstar", "in.startv.hotstar", "#2563EB"},
            {"Apple TV+", "com.apple.atve.androidtv.appletv", "#334155"},
            {"YouTube", "com.google.android.youtube.tv", "#DC2626"},
            {"SmartTube", "org.smarttube.stable", "#B91C1C"},
            {"SonyLIV", "com.sonyliv", "#4F46E5"},
            {"ZEE5", "com.graymatrix.did", "#9333EA"},
            {"MX Player", "com.mxtech.videoplayer.television", "#2563EB"}
        };
        for (String[] d : defaults) {
            if (!added.contains(d[1])) {
                added.add(d[1]);
                addAppJson(list, d[0], d[1], d[2]);
            }
        }
    }

    private void addAppJson(List<JSONObject> list, String name, String pkg, String color) {
        try {
            list.add(new JSONObject().put("name", name).put("package", pkg).put("color", color));
        } catch (Exception ignored) {}
    }

    // ── Callbacks: NavPanelBuilder ───────────────────────────────────────

    @Override
    public void onProfileSelected(String profileId) {
        activeProfile = profileId;
        db.setSetting("active_profile", profileId);
        refreshAllData(false);
    }

    @Override
    public void onAddProfile() {
        EditText input = new EditText(this);
        input.setHint("Profile Name (e.g. Alex)");
        new AlertDialog.Builder(this)
            .setTitle("Add New Profile")
            .setView(input)
            .setPositiveButton("Create", (d, w) -> {
                activeProfile = db.addProfile(input.getText().toString(), "");
                refreshAllData(true);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    public void onNavigate(String destination) {
        if ("home".equals(destination)) refreshAllData(false);
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
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("content://android.media.tv/passthrough/com.droidlogic.tvinput%2F.services.Hdmi" + port + "InputService%2FHW" + port));
            startActivity(i);
        } catch (Exception e) {
            openSystemSetting("sys_display");
        }
    }

    @Override
    public void onSettingsOpen() {
        showSettingsDialog();
    }

    @Override
    public void onQrOpen() {
        String ip = QrRemoteBuilder.getTvIpAddress(this);
        View card = QrRemoteBuilder.buildQrCard(this, ip, REMOTE_PORT);
        new AlertDialog.Builder(this).setView(card).setPositiveButton("Close", null).show();
    }

    // ── Callbacks: HeroSliderBuilder & RailBuilder ───────────────────────

    @Override
    public void onWatchClicked(JSONObject item) {
        launchMediaItem(item);
    }

    @Override
    public void onWatchedClicked(JSONObject item) {
        db.markTitleWatched(item, true);
        Toast.makeText(this, "Marked watched: " + item.optString("title"), Toast.LENGTH_SHORT).show();
        refreshAllData(false);
    }

    @Override
    public void onSimilarClicked(JSONObject item) {
        String seed = item.optString("title");
        setBannerText("\u2726 Finding modern titles similar to " + seed + "\u2026", true);
        bgPool.submit(() -> {
            JSONArray sim = ai.traverseSimilarToWatched(activeProfile, seed,
                msg -> ui.post(() -> setBannerText("\u2726 " + msg, true)));
            ui.post(() -> {
                setBannerText("", false);
                if (sim != null && sim.length() > 0) renderHomeContent(sim);
            });
        });
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
        HeroSliderBuilder.showItemDirect(heroSlider, item, this, this);
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
        lastSearchQuery = q;
        if (searchLabel != null) {
            searchLabel.setText(q);
            searchLabel.setTextColor(GlassTheme.TEXT_PRIMARY);
        }
        hideKeyboard(rootLayout);
        HeroSliderBuilder.stopAutoRotate(heroSlider, ui);
        setBackgroundFocusBlocked(true);
        SearchOverlay.show(searchOverlay, this);
        SearchOverlay.setLoading(searchOverlay, true,
            "Querying JustWatch India Graph & Gemini AI (using your Watch History)\u2026");
        bgPool.submit(() -> {
            JSONObject res = voice.handleTurn(activeProfile, q, false,
                msg -> ui.post(() -> {
                    if (SearchOverlay.isVisible(searchOverlay)) {
                        SearchOverlay.setLoading(searchOverlay, true, msg);
                    }
                }));
            ui.post(() -> displaySearchResults(res));
        });
    }

    private void displaySearchResults(JSONObject res) {
        if (!SearchOverlay.isVisible(searchOverlay)) return;
        SearchOverlay.setLoading(searchOverlay, false, "");
        if (res == null) return;
        String reply = res.optString("reply", "");
        if (!reply.isEmpty()) SearchOverlay.addMessage(searchOverlay, "assistant", reply, this);
        List<String> chips = new ArrayList<>();
        JSONArray arr = res.optJSONArray("follow_ups");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) chips.add(arr.optString(i));
        }
        SearchOverlay.setFollowUps(searchOverlay, chips, this, this);
        SearchOverlay.setResults(searchOverlay, res.optJSONArray("results"), this, this);
        populateTraversalChips();
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
    public void onDismiss() {
        if (DetailOverlay.isVisible(detailOverlay)) DetailOverlay.dismiss(detailOverlay);
        if (SearchOverlay.isVisible(searchOverlay)) SearchOverlay.dismiss(searchOverlay);
        setBackgroundFocusBlocked(false);
        HeroSliderBuilder.requestInitialFocus(heroSlider);
    }

    private void openDetailDrawer(JSONObject item) {
        if (item == null) return;
        setBackgroundFocusBlocked(true);
        String title = item.optString("title");
        boolean watched = db.isTitleWatched(title);
        Map<String, Integer> ratings = db.getUserRatingsMap();
        int rating = ratings.getOrDefault(AuraDb.normalizeTitleKey(title), 0);
        DetailOverlay.showItem(detailOverlay, item, watched, rating, this, this);
        bgPool.submit(() -> {
            JSONArray sim = JustWatchGraph.fetchCollaborativeSimilar(title);
            ui.post(() -> DetailOverlay.setSimilarItems(detailOverlay, sim, this, this));
        });
    }

    @Override
    public void onWatchedToggle(JSONObject item) {
        boolean nowWatched = !db.isTitleWatched(item.optString("title"));
        db.markTitleWatched(item, nowWatched);
        openDetailDrawer(item);
        refreshAllData(false);
    }

    @Override
    public void onRated(JSONObject item, int score) {
        db.rateTitle(item, score);
        Toast.makeText(this, "Rated " + score + "/10", Toast.LENGTH_SHORT).show();
        openDetailDrawer(item);
    }

    @Override
    public void onTraversalClicked(JSONObject similarItem) {
        openDetailDrawer(similarItem);
    }

    // ── Dialogs: Guide AI, Settings & Watch History Verifier ─────────────

    private void showGuideAiDialog() {
        EditText et = new EditText(this);
        et.setText(db.getProfileTaste(activeProfile));
        et.setHint("e.g. Dark sci-fi, mind-bending thrillers, high IMDb");
        new AlertDialog.Builder(this)
            .setTitle("Personal Guide AI (" + db.getProfileLabel(activeProfile) + ")")
            .setView(et)
            .setPositiveButton("Save & Refresh", (d, w) -> {
                db.setProfileTaste(activeProfile, et.getText().toString());
                refreshAllData(true);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        box.setPadding(p, p, p, p);

        String hasKey = db.getSetting("gemini_api_key", "").isEmpty() ? "Not set (Offline JustWatch Mode)" : "Configured \u2713";
        TextView info = new TextView(this);
        info.setText("Engine: " + hasKey + "\nPhone Remote & Web Config: http://"
            + QrRemoteBuilder.getTvIpAddress(this) + ":" + REMOTE_PORT
            + "\nActive Taste Anchors: " + db.getDynamicTasteAnchors(activeProfile));
        info.setPadding(0, 0, 0, dp(10));
        box.addView(info);

        EditText et = new EditText(this);
        et.setText(db.getSetting("gemini_api_key", ""));
        et.setHint("Gemini API Key (optional)");
        box.addView(et);

        new AlertDialog.Builder(this)
            .setTitle("Aura & Android TV Settings")
            .setView(box)
            .setPositiveButton("Save & Refresh AI", (d, w) -> {
                db.setSetting("gemini_api_key", et.getText().toString().trim());
                refreshAllData(true);
            })
            .setNeutralButton("Open Android TV Settings", (d, w) -> openSystemSetting("sys_all"))
            .setNegativeButton("Close", null)
            .show();
    }

    private void showWatchHistoryDialog() {
        List<String> lines = new ArrayList<>();
        List<String> anchors = db.getDynamicTasteAnchors(activeProfile);
        lines.add("\u2726 ACTIVE AI TASTE ANCHORS: " + (anchors.isEmpty() ? "Default Modern" : String.join(", ", anchors)));

        JSONArray ottHist = db.listHistory(activeProfile);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ottHist.length(); i++) {
            JSONObject o = ottHist.optJSONObject(i);
            if (o == null) continue;
            String t = !o.optString("series_title").isEmpty() ? o.optString("series_title") : o.optString("title");
            String app = HeroSliderBuilder.cleanPlatformName(o.optString("app_name", o.optString("app", "TV")));
            if (t.isEmpty() || t.equalsIgnoreCase(app) || !seen.add(AuraDb.normalizeTitleKey(t))) continue;
            int pos = o.optInt("position_sec", 0);
            int dur = o.optInt("duration_sec", 0);
            String prog = (dur > 0 && pos > 0) ? (" \u00B7 " + Math.min(99, (pos * 100) / dur) + "%") : " \u00B7 Watch Next";
            lines.add("\u25B6 [" + app + "]  " + t + prog);
        }

        JSONArray watched = db.listManuallyWatched();
        for (int i = 0; i < watched.length(); i++) {
            JSONObject o = watched.optJSONObject(i);
            if (o == null) continue;
            String t = !o.optString("series_title").isEmpty() ? o.optString("series_title") : o.optString("title");
            String app = HeroSliderBuilder.cleanPlatformName(o.optString("app", "Watched"));
            int rating = o.optInt("user_rating", 0);
            String rStr = rating > 0 ? (" \u00B7 \u2605 " + rating + "/10") : " \u00B7 Watched \u2713";
            lines.add("\u2713 [" + app + "]  " + t + rStr);
        }

        String[] items = lines.toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle("Watch History Considered by Aura AI (" + items.length + ")")
            .setItems(items, null)
            .setPositiveButton("Re-Sync & Refresh Recommendations", (d, w) -> refreshAllData(true))
            .setNegativeButton("Close", null)
            .show();
    }

    private void openSystemSetting(String dest) {
        String action = Settings.ACTION_SETTINGS;
        if (dest.contains("wi-fi")) action = Settings.ACTION_WIFI_SETTINGS;
        else if (dest.contains("bluetooth")) action = Settings.ACTION_BLUETOOTH_SETTINGS;
        else if (dest.contains("display")) action = Settings.ACTION_DISPLAY_SETTINGS;
        else if (dest.contains("audio")) action = Settings.ACTION_SOUND_SETTINGS;
        try { startActivity(new Intent(action)); } catch (Exception ignored) {}
    }

    private void launchMediaItem(JSONObject item) {
        if (item == null) return;
        String uri = item.optString("launch_uri", "");
        String pkg = item.optString("launch_pkg", AuraDb.resolveAppPackage(
            item.optString("app", item.optString("app_name"))));
        try {
            if (!uri.isEmpty()) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            }
        } catch (Exception ignored) {}
        launchPackage(pkg);
    }

    private void launchPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            Intent li = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (li == null) li = getPackageManager().getLaunchIntentForPackage(pkg);
            if (li != null) {
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(li);
                return;
            }
        } catch (Exception ignored) {}
        Toast.makeText(this, "App not installed: " + pkg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (DetailOverlay.isVisible(detailOverlay)) {
                DetailOverlay.dismiss(detailOverlay);
                setBackgroundFocusBlocked(false);
                HeroSliderBuilder.requestInitialFocus(heroSlider);
                return true;
            }
            if (SearchOverlay.isVisible(searchOverlay)) {
                SearchOverlay.dismiss(searchOverlay);
                setBackgroundFocusBlocked(false);
                HeroSliderBuilder.requestInitialFocus(heroSlider);
                return true;
            }
            if (NavPanelBuilder.isExpanded(navPanel)) {
                NavPanelBuilder.setExpanded(navPanel, false, this);
                HeroSliderBuilder.requestInitialFocus(heroSlider);
                return true;
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // Home launcher never finishes on Back press
    }

    private void setBannerText(String msg, boolean visible) {
        statusBanner.setText(msg);
        statusBanner.setVisibility(visible ? View.VISIBLE : View.GONE);
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
