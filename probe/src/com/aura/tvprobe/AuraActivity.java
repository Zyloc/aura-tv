package com.aura.tvprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main Launcher Activity for Aura TV v2.
 * Native Android TV feel with high-visibility D-pad focus rings, real app banners, and hero carousel.
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
    private String activeProfile = AuraDb.DEFAULT_PROFILE;
    private boolean initialFocusSet = false;

    private FrameLayout rootLayout;
    private ScrollView mainScroll;
    private LinearLayout navPanel;
    private FrameLayout heroSlider;
    private LinearLayout railsContainer;
    private LinearLayout chipsRow;
    private LinearLayout searchBox;
    private EditText searchInput;
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
        ai = new AiRecommender(db);
        sync = new SyncManager(db);
        voice = new VoiceConcierge(db, ai, sync);
        activeProfile = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
        try {
            startService(new Intent(this, MediaProbeService.class));
        } catch (Throwable ignored) {}
    }

    private void buildUi() {
        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(GlassTheme.BG_AMBIENT);
        rootLayout.addView(buildMainScrollArea());
        navPanel = NavPanelBuilder.build(this, this);
        rootLayout.addView(navPanel);
        addOverlays();
        setContentView(rootLayout);
    }

    private ScrollView buildMainScrollArea() {
        mainScroll = new ScrollView(this);
        mainScroll.setVerticalScrollBarEnabled(false);
        mainScroll.setFillViewport(true);
        mainScroll.setFocusable(false);
        mainScroll.setClipChildren(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.leftMargin = dp(GlassTheme.NAV_COLLAPSED_W + 8);
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
        statusBanner = GlassTheme.makeText(this, "", 11f, GlassTheme.ACCENT, GlassTheme.FONT_BOLD, 1);
        statusBanner.setPadding(dp(20), dp(2), dp(20), dp(2));
        statusBanner.setVisibility(View.GONE);
        col.addView(statusBanner);
        railsContainer = new LinearLayout(this);
        railsContainer.setOrientation(LinearLayout.VERTICAL);
        railsContainer.setClipChildren(false);
        railsContainer.setClipToPadding(false);
        railsContainer.setPadding(dp(8), 0, dp(12), dp(32));
        col.addView(railsContainer, GlassTheme.matchWrapParams());
        return col;
    }

    private LinearLayout buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setPadding(dp(16), dp(10), dp(16), dp(6));
        bar.addView(buildSearchBox());
        bar.addView(buildChipsScroll(), GlassTheme.weightParams(1f));
        profileClockLabel = GlassTheme.makeText(this, "", 11.5f,
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
        searchBox.setPadding(dp(12), dp(4), dp(6), dp(4));

        TextView icon = GlassTheme.makeText(this, "\uD83D\uDD0D", 12f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_REGULAR, 1);
        icon.setGravity(Gravity.CENTER);
        searchBox.addView(icon, GlassTheme.wrapParams());

        searchInput = createSearchEditText(r);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(dp(230), dp(30));
        etLp.gravity = Gravity.CENTER_VERTICAL;
        searchBox.addView(searchInput, etLp);

        TextView askBtn = GlassTheme.makePrimaryButton(this, "Search");
        askBtn.setTextSize(11f);
        askBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
        askBtn.setOnClickListener(v -> runSearchQuery(searchInput.getText().toString()));
        searchBox.addView(askBtn);
        return searchBox;
    }

    private EditText createSearchEditText(float r) {
        EditText et = new EditText(this);
        et.setHint("Search shows or ask Aura AI\u2026");
        et.setHintTextColor(GlassTheme.TEXT_MUTED);
        et.setTextColor(GlassTheme.TEXT_PRIMARY);
        et.setTextSize(12f);
        et.setSingleLine(true);
        et.setIncludeFontPadding(false);
        et.setGravity(Gravity.CENTER_VERTICAL);
        et.setPadding(dp(8), 0, dp(8), 0);
        et.setBackground(null);
        et.setOnFocusChangeListener((v, focused) -> {
            if (searchBox != null) {
                searchBox.setBackground(focused
                    ? GlassTheme.focusRounded(Color.WHITE, GlassTheme.ACCENT, dp(2), r)
                    : GlassTheme.glassCard(r));
            }
        });
        et.setOnEditorActionListener((v, actionId, ev) -> {
            runSearchQuery(et.getText().toString());
            return true;
        });
        return et;
    }

    private HorizontalScrollView buildChipsScroll() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        hsv.setPadding(dp(10), dp(2), dp(10), dp(2));
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
        slp.topMargin = dp(56);
        slp.leftMargin = dp(GlassTheme.NAV_COLLAPSED_W + 20);
        searchOverlay.setLayoutParams(slp);
        rootLayout.addView(searchOverlay);
        detailOverlay = DetailOverlay.build(this, this);
        rootLayout.addView(detailOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void setupGlobalFocusRouting() {
        rootLayout.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF == null) return;
            boolean inNav = isDescendantOf(newF, navPanel);
            NavPanelBuilder.setExpanded(navPanel, inNav, this);
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
        JSONArray cached = db.getProfileRecommendations(activeProfile);
        if (cached.length() > 0) renderHomeContent(cached);
        setBannerText("Updating recommendations\u2026", true);
        bgPool.submit(() -> {
            JSONArray history = db.listHistory(activeProfile);
            JSONObject np = history.length() > 0 ? history.optJSONObject(0) : null;
            JSONArray fresh = ai.getOrRefreshForProfile(activeProfile, np, history, forceRefresh,
                msg -> ui.post(() -> setBannerText(msg, true)));
            ui.post(() -> {
                setBannerText("", false);
                if (fresh != null && fresh.length() > 0) renderHomeContent(fresh);
            });
        });
    }

    private void updateNavMetadata() {
        NavPanelBuilder.updateProfiles(navPanel, db.listProfiles(), activeProfile, this);
        long freeMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        String mode = db.getSetting("gemini_api_key", "").isEmpty() ? "JustWatch" : "Gemini AI";
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
        List<JSONObject> heroList = all.subList(0, Math.min(5, all.size()));
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

    private void addContinueWatchingIfPresent() {
        List<JSONObject> rawHistory = toJsonList(db.listHistory(activeProfile));
        List<JSONObject> filtered = new ArrayList<>();
        for (JSONObject item : rawHistory) {
            String title = item.optString("title", "");
            String app = item.optString("app_name", item.optString("app", "")).toLowerCase();
            String pkg = item.optString("app_pkg", "").toLowerCase();
            if (title.isEmpty()) continue;
            if (app.contains("recommended") || app.contains("youtube") || pkg.contains("smarttube") || pkg.contains("youtube")) {
                continue;
            }
            filtered.add(item);
        }
        if (!filtered.isEmpty()) {
            railsContainer.addView(RailBuilder.buildContinueWatching(this, filtered, this));
        }
    }

    private void addCategorizedRails(List<JSONObject> all) {
        if (all.isEmpty()) return;
        String profName = db.getProfileLabel(activeProfile);
        int n = all.size();
        List<JSONObject> r1 = all.subList(0, Math.min(8, n));
        railsContainer.addView(RailBuilder.buildContentRail(
            this, "Top Picks for " + profName, "\u2726", r1, this));
        if (n > 6) {
            List<JSONObject> r2 = all.subList(Math.min(6, n), Math.min(14, n));
            railsContainer.addView(RailBuilder.buildContentRail(
                this, "Trending Across Your Apps", "\uD83D\uDD25", r2, this));
        }
        if (n > 12) {
            List<JSONObject> r3 = all.subList(Math.min(12, n), n);
            railsContainer.addView(RailBuilder.buildContentRail(
                this, "Critically Acclaimed & Mind-Bending", "\uD83C\uDFAC", r3, this));
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
            {"JioHotstar", "in.startv.hotstar", "#1D4ED8"},
            {"Prime Video", "com.amazon.amazonvideo.livingroom", "#00A8E1"},
            {"YouTube", "com.google.android.youtube.tv", "#FF0000"},
            {"SmartTube", "org.smarttube.stable", "#DC2626"},
            {"MX Player", "com.mxtech.videoplayer.television", "#2563EB"},
            {"Apple TV+", "com.apple.atve.androidtv.appletv", "#1A1A1A"},
            {"SonyLIV", "com.sonyliv", "#141414"},
            {"ZEE5", "com.graymatrix.did", "#8B22AE"}
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
        else if ("watched".equals(destination)) showWatchedDialog();
        else if (destination.startsWith("sys_")) openSystemSetting(destination);
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
        setBannerText("Finding modern titles similar to " + seed + "\u2026", true);
        bgPool.submit(() -> {
            JSONArray sim = ai.traverseSimilarToWatched(activeProfile, seed,
                msg -> ui.post(() -> setBannerText(msg, true)));
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
        if (mainScroll != null) mainScroll.smoothScrollTo(0, 0);
        HeroSliderBuilder.requestInitialFocus(heroSlider);
    }

    @Override
    public void onMoveLeftToNav() {
        if (navPanel != null) {
            NavPanelBuilder.setExpanded(navPanel, true, this);
            navPanel.requestFocus();
        }
    }

    // ── Callbacks: SearchOverlay & DetailOverlay ─────────────────────────

    private void runSearchQuery(String query) {
        String q = query != null ? query.trim() : "";
        if (q.isEmpty()) return;
        SearchOverlay.show(searchOverlay, this);
        SearchOverlay.addMessage(searchOverlay, "user", q, this);
        SearchOverlay.setLoading(searchOverlay, true, "Searching JustWatch & Aura AI\u2026");
        bgPool.submit(() -> {
            JSONObject res = voice.handleTurn(activeProfile, q, false,
                msg -> ui.post(() -> SearchOverlay.setLoading(searchOverlay, true, msg)));
            ui.post(() -> displaySearchResults(res));
        });
    }

    private void displaySearchResults(JSONObject res) {
        SearchOverlay.setLoading(searchOverlay, false, "");
        if (res == null) return;
        String reply = res.optString("reply", "");
        if (!reply.isEmpty()) SearchOverlay.addMessage(searchOverlay, "assistant", reply, this);
        SearchOverlay.setResults(searchOverlay, res.optJSONArray("results"), this, this);
        List<String> chips = new ArrayList<>();
        JSONArray arr = res.optJSONArray("follow_ups");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) chips.add(arr.optString(i));
        }
        SearchOverlay.setFollowUps(searchOverlay, chips, this, this);
        populateTraversalChips();
    }

    @Override
    public void onResultClicked(JSONObject item) {
        SearchOverlay.dismiss(searchOverlay);
        openDetailDrawer(item);
    }

    @Override
    public void onFollowUpClicked(String query) {
        searchInput.setText(query);
        runSearchQuery(query);
    }

    @Override
    public void onDismiss() {
        if (DetailOverlay.isVisible(detailOverlay)) DetailOverlay.dismiss(detailOverlay);
        if (SearchOverlay.isVisible(searchOverlay)) SearchOverlay.dismiss(searchOverlay);
    }

    private void openDetailDrawer(JSONObject item) {
        if (item == null) return;
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

    // ── Dialogs & Launch Helpers ─────────────────────────────────────────

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
        EditText et = new EditText(this);
        et.setText(db.getSetting("gemini_api_key", ""));
        et.setHint("Gemini API Key (optional — works offline without key)");
        new AlertDialog.Builder(this)
            .setTitle("Aura Settings \u00B7 Phone Remote at :" + REMOTE_PORT)
            .setView(et)
            .setPositiveButton("Save Key", (d, w) -> {
                db.setSetting("gemini_api_key", et.getText().toString().trim());
                refreshAllData(true);
            })
            .setNeutralButton("Android TV Settings", (d, w) -> openSystemSetting("sys_all"))
            .setNegativeButton("Close", null)
            .show();
    }

    private void showWatchedDialog() {
        JSONArray watched = db.listManuallyWatched();
        String[] items = new String[watched.length()];
        for (int i = 0; i < watched.length(); i++) {
            JSONObject o = watched.optJSONObject(i);
            items[i] = o != null ? o.optString("title") : "";
        }
        new AlertDialog.Builder(this)
            .setTitle("Watched History (" + items.length + ")")
            .setItems(items, null)
            .setPositiveButton("Close", null)
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
        String pkg = item.optString("launch_pkg", AuraDb.resolveAppPackage(item.optString("app")));
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
                return true;
            }
            if (SearchOverlay.isVisible(searchOverlay)) {
                SearchOverlay.dismiss(searchOverlay);
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
