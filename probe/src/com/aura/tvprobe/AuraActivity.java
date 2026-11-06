package com.aura.tvprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main Launcher Activity for Aura TV v2.
 * Composes NavPanelBuilder, HeroSliderBuilder, RailBuilder, SearchOverlay, and DetailOverlay.
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

    private FrameLayout rootLayout;
    private LinearLayout navPanel;
    private FrameLayout heroSlider;
    private LinearLayout railsContainer;
    private LinearLayout chipsRow;
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
        rootLayout.addView(buildFloatingTopBar());
        navPanel = NavPanelBuilder.build(this, this);
        rootLayout.addView(navPanel);
        addOverlays();
        setContentView(rootLayout);
    }

    private ScrollView buildMainScrollArea() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.leftMargin = dp(GlassTheme.NAV_COLLAPSED_W);
        sv.setLayoutParams(lp);
        sv.addView(buildMainColumn());
        return sv;
    }

    private LinearLayout buildMainColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        heroSlider = HeroSliderBuilder.build(this, this);
        col.addView(heroSlider);
        statusBanner = GlassTheme.makeText(this, "", 11f, GlassTheme.ACCENT, GlassTheme.FONT_MEDIUM, 1);
        statusBanner.setPadding(dp(24), dp(2), dp(24), dp(2));
        statusBanner.setVisibility(View.GONE);
        col.addView(statusBanner);
        railsContainer = new LinearLayout(this);
        railsContainer.setOrientation(LinearLayout.VERTICAL);
        railsContainer.setPadding(dp(16), 0, dp(16), dp(28));
        col.addView(railsContainer, GlassTheme.matchWrapParams());
        return col;
    }

    private LinearLayout buildFloatingTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(20), dp(8));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(GlassTheme.NAV_COLLAPSED_W + 8);
        bar.setLayoutParams(lp);
        bar.addView(buildSearchBox());
        bar.addView(buildChipsScroll(), GlassTheme.weightParams(1f));
        profileClockLabel = GlassTheme.makeText(this, "", 11f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        profileClockLabel.setBackground(GlassTheme.glassPanel(dp(12)));
        profileClockLabel.setPadding(dp(10), dp(5), dp(10), dp(5));
        bar.addView(profileClockLabel);
        return bar;
    }

    private LinearLayout buildSearchBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(GlassTheme.glassPanel(dp(14)));
        box.setElevation(dp(4));
        box.setPadding(dp(12), dp(4), dp(8), dp(4));
        searchInput = createSearchEditText();
        box.addView(searchInput, new LinearLayout.LayoutParams(dp(260), dp(32)));
        TextView askBtn = GlassTheme.makePrimaryButton(this, "Ask AI");
        askBtn.setTextSize(11f);
        askBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        askBtn.setOnClickListener(v -> runSearchQuery(searchInput.getText().toString()));
        box.addView(askBtn);
        return box;
    }

    private EditText createSearchEditText() {
        EditText et = new EditText(this);
        et.setHint("\uD83D\uDD0D Search shows or ask Aura AI\u2026");
        et.setHintTextColor(GlassTheme.TEXT_MUTED);
        et.setTextColor(GlassTheme.TEXT_PRIMARY);
        et.setTextSize(12f);
        et.setSingleLine(true);
        et.setBackground(null);
        et.setOnEditorActionListener((v, actionId, ev) -> {
            runSearchQuery(et.getText().toString());
            return true;
        });
        return et;
    }

    private HorizontalScrollView buildChipsScroll() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setPadding(dp(10), 0, dp(10), 0);
        chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(chipsRow);
        return hsv;
    }

    private void addOverlays() {
        searchOverlay = SearchOverlay.build(this, this);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        slp.topMargin = dp(56);
        slp.leftMargin = dp(GlassTheme.NAV_COLLAPSED_W + 16);
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
        setBannerText("Syncing personalized recommendations\u2026", true);
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
            btn.setTextSize(11f);
            btn.setPadding(dp(10), dp(4), dp(10), dp(4));
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
        addContinueAndAppsStrip();
        addCategorizedRails(all);
    }

    private void addContinueAndAppsStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        List<JSONObject> history = toJsonList(db.listHistory(activeProfile));
        if (!history.isEmpty()) {
            LinearLayout cw = RailBuilder.buildContinueWatching(this, history, this);
            strip.addView(cw, GlassTheme.weightParams(1f));
        }
        LinearLayout apps = RailBuilder.buildAppsDock(this, getDefaultStreamingApps(), this);
        strip.addView(apps, GlassTheme.weightParams(1f));
        railsContainer.addView(strip, GlassTheme.matchWrapParams());
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

    private List<JSONObject> getDefaultStreamingApps() {
        List<JSONObject> list = new ArrayList<>();
        addAppJson(list, "Netflix", "com.netflix.ninja", "#E50914");
        addAppJson(list, "Prime Video", "com.amazon.amazonvideo.livingroom", "#00A8E1");
        addAppJson(list, "Hotstar", "in.startv.hotstar", "#1D4ED8");
        addAppJson(list, "Apple TV+", "com.apple.atve.androidtv.appletv", "#1A1A1A");
        addAppJson(list, "YouTube", "org.smarttube.stable", "#FF0000");
        addAppJson(list, "SonyLIV", "com.sonyliv", "#141414");
        addAppJson(list, "ZEE5", "com.graymatrix.did", "#8B22AE");
        return list;
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
            .setTitle("Aura Settings · Remote at :" + REMOTE_PORT)
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                db.setSetting("gemini_api_key", et.getText().toString().trim());
                refreshAllData(true);
            })
            .setNeutralButton("QR Remote", (d, w) -> onQrOpen())
            .setNegativeButton("Cancel", null)
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
            }
        } catch (Exception ignored) {}
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
