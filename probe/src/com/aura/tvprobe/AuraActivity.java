package com.aura.tvprobe;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AuraActivity extends Activity {
    private static final String[][] QUICK_FILTERS = {
        {"all", "All"},
        {"movies", "Movies"},
        {"series", "Series"},
        {"short", "< 30m"}
    };

    private static final int SIDEBAR_COLLAPSED_W = 72;
    private static final int SIDEBAR_EXPANDED_W = 240;
    private static final int RIGHT_DRAWER_W = 584;
    private static final int MAX_CAROUSEL_CARDS = 14;

    // Pre-parsed color constants to avoid Color.parseColor() allocations on focus events
    private static final int COLOR_BG_OBSIDIAN = Color.parseColor("#08090E");
    private static final int COLOR_BG_SIDEBAR = Color.parseColor("#0D0F17");
    private static final int COLOR_SIDEBAR_BORDER = Color.parseColor("#1D2233");
    private static final int COLOR_BG_DRAWER = Color.parseColor("#10131E");
    private static final int COLOR_DRAWER_BORDER = Color.parseColor("#242B3D");
    private static final int COLOR_SURFACE_PILL = Color.parseColor("#151824");
    private static final int COLOR_BORDER_SUBTLE = Color.parseColor("#252A3C");
    private static final int COLOR_ACTIVE_FILTER_BG = Color.parseColor("#2A2210");
    private static final int COLOR_IMDB_GOLD = Color.parseColor("#F5C518");
    private static final int COLOR_TEXT_PRIMARY = Color.parseColor("#F5F7FA");
    private static final int COLOR_TEXT_SECONDARY = Color.parseColor("#9DA6BA");
    private static final int COLOR_POSTER_BORDER_IDLE = Color.parseColor("#1F2433");

    private static final Typeface FONT_DISPLAY = Typeface.create("sans-serif", Typeface.BOLD);
    private static final Typeface FONT_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface FONT_REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final DecelerateInterpolator SMOOTH_INTERP = new DecelerateInterpolator(1.6f);

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgPool = Executors.newSingleThreadExecutor();

    private AuraDb db;
    private AiRecommender ai;
    private SessionInspector inspector;
    private SyncManager sync;
    private VoiceConcierge voice;

    // Lock-free in-memory UI state
    private String memProfile = AuraDb.DEFAULT_PROFILE;
    private final List<JSONObject> memProfiles = new ArrayList<>();
    private final List<String> memFilters = new ArrayList<>();
    private final Set<String> memWatchedKeys = new HashSet<>();
    private final Map<String, Integer> memUserRatings = new HashMap<>();
    private JSONArray memRecos = new JSONArray();
    private JSONArray memWatchedList = new JSONArray();
    private JSONObject memVoiceSession = new JSONObject();
    private boolean showingWatchedList = false;
    private boolean showingVoiceResults = false;
    private boolean sidebarExpanded = false;
    private boolean creatingNewProfileMode = false;
    private boolean drawerClosing = false;

    private FrameLayout rootFrame;
    private LinearLayout rightStage;
    private LinearLayout sidePanel;
    private ValueAnimator sidebarAnimator;
    private TextView brandLabel;
    private TextView profSectionHdr;
    private TextView libSectionHdr;
    private LinearLayout sideProfileContainer;
    private TextView navAddProfileBtn;
    private TextView navTasteBtn;
    private TextView navAllPicksBtn;
    private TextView navWatchedBtn;
    private TextView navRefreshBtn;

    private EditText searchInput;
    private TextView clearSearchBtn;
    private LinearLayout filterChipsRow;
    private LinearLayout statusBox;
    private ProgressBar spinner;
    private TextView statusLabel;

    private LinearLayout heroHeaderBox;
    private TextView headerTitle;
    private TextView headerMeta;
    private TextView headerReasonPreview;
    private LinearLayout carouselRow;

    // Right-Hand Slide-Over Drawers
    private FrameLayout rightScrim;
    private LinearLayout rightDetailsDrawer;
    private TextView drawerAvailableBadge;
    private TextView drawerMeta;
    private TextView drawerTitle;
    private TextView drawerGenres;
    private TextView drawerWhyHdr;
    private TextView drawerWhyReason;
    private TextView drawerSynopsisHdr;
    private TextView drawerSynopsis;
    private TextView drawerOpenAppBtn;
    private TextView drawerWatchedBtn;
    private TextView drawerRatingLabel;
    private LinearLayout drawerRatingRow;
    private final TextView[] ratingBtns = new TextView[10];
    private TextView drawerCloseBtn;

    // Current drawer active item state for pre-allocated rating buttons
    private JSONObject activeDrawerItem;
    private String activeDrawerTitle = "";
    private String activeDrawerTitleKey = "";
    private int activeDrawerScore = 0;

    // Right-Hand Slide-Over Profile & AI Interests Drawer
    private LinearLayout rightTasteDrawer;
    private TextView tasteDrawerTitle;
    private TextView tasteDrawerSub;
    private EditText profileNameInput;
    private EditText tasteEditInput;
    private LinearLayout tasteTagsContainer;
    private TextView tasteSaveBtn;
    private TextView tasteDeleteProfileBtn;
    private TextView tasteCloseBtn;

    private View lastFocusedPoster;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        startService(new Intent(this, MediaProbeService.class));

        db = AuraDb.getInstance(this);
        ai = new AiRecommender(db);
        inspector = new SessionInspector(this, new ComponentName(this, MediaProbeService.class), db, ai);
        sync = new SyncManager(db);
        voice = new VoiceConcierge(db, ai, sync);

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(COLOR_BG_OBSIDIAN);
        setContentView(rootFrame);

        memProfiles.clear();
        memProfiles.addAll(db.listProfiles());

        buildMainLayout();
        buildRightSlideDrawers();
        switchProfileAndLoad(db.getSetting("active_profile", memProfiles.get(0).optString("id", AuraDb.DEFAULT_PROFILE)), true);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            ImageLoader.trimMemory();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && isAnyDrawerOpen()) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                closeRightDrawers();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isAnyDrawerOpen() {
        return rightScrim != null && rightScrim.getVisibility() == View.VISIBLE && !drawerClosing;
    }

    private void switchProfileAndLoad(final String targetProfile, final boolean focusFirstPoster) {
        memProfile = targetProfile;
        showingWatchedList = false;
        showingVoiceResults = false;
        memProfiles.clear();
        memProfiles.addAll(db.listProfiles());
        populateSideProfiles();
        setLoader(true, "Curating for " + db.getProfileLabel(targetProfile) + "…");

        bgPool.submit(() -> {
            db.setSetting("active_profile", targetProfile);
            final List<JSONObject> profList = db.listProfiles();
            final List<String> flt = db.getActiveFilters(targetProfile);
            final Set<String> wKeys = db.getWatchedTitleKeys();
            final Map<String, Integer> rMap = db.getUserRatingsMap();
            final JSONArray wList = db.listManuallyWatched();
            JSONArray recos = db.getProfileRecommendations(targetProfile);

            if (recos.length() == 0) {
                recos = ai.getOrRefreshForProfile(targetProfile, new JSONObject(), db.listHistory(targetProfile), false);
            }
            final JSONArray initialRecos = recos;

            uiHandler.post(() -> {
                memProfile = targetProfile;
                memProfiles.clear();
                memProfiles.addAll(profList);
                memFilters.clear();
                memFilters.addAll(flt);
                memWatchedKeys.clear();
                memWatchedKeys.addAll(wKeys);
                memUserRatings.clear();
                memUserRatings.putAll(rMap);
                memWatchedList = wList;
                memRecos = initialRecos;
                setLoader(false, "");
                refreshAllSections(focusFirstPoster);
                if (!focusFirstPoster) {
                    focusSideProfileById(targetProfile);
                }
            });

            sync.syncWithHost();
        });
    }

    private void buildMainLayout() {
        // Fixed rightStage inside rootFrame with leftMargin = SIDEBAR_COLLAPSED_W.
        // Expanding/collapsing the left sidebar overlays cleanly and NEVER triggers a relayout on rightStage!
        rightStage = new LinearLayout(this);
        rightStage.setOrientation(LinearLayout.VERTICAL);
        rightStage.setPadding(36, 28, 36, 18);
        rightStage.setClipChildren(true);
        rightStage.setClipToPadding(true);

        rightStage.addView(buildUnifiedTopBar());
        rightStage.addView(buildMinimalTitleRow());

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hsv.setClipChildren(true);
        hsv.setClipToPadding(false);
        carouselRow = new LinearLayout(this);
        carouselRow.setOrientation(LinearLayout.HORIZONTAL);
        carouselRow.setClipChildren(false);
        carouselRow.setClipToPadding(false);
        carouselRow.setPadding(14, 18, 32, 18);
        hsv.addView(carouselRow);
        rightStage.addView(hsv, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams stageLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        stageLp.leftMargin = SIDEBAR_COLLAPSED_W;
        rootFrame.addView(rightStage, stageLp);

        // Collapsible left navigation rail sits as an overlay on the left edge
        sidePanel = buildCollapsibleLeftSidePanel();
        sidePanel.setElevation(28f);
        FrameLayout.LayoutParams sideLp = new FrameLayout.LayoutParams(
            SIDEBAR_COLLAPSED_W, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START);
        rootFrame.addView(sidePanel, sideLp);
    }

    private LinearLayout buildCollapsibleLeftSidePanel() {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        side.setPadding(10, 26, 10, 20);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_BG_SIDEBAR);
        bg.setStroke(1, COLOR_SIDEBAR_BORDER);
        side.setBackground(bg);

        brandLabel = makeText("A", 20f, "#F5C518", FONT_DISPLAY, 1);
        brandLabel.setGravity(Gravity.CENTER);
        brandLabel.setPadding(6, 0, 6, 16);
        side.addView(brandLabel);

        profSectionHdr = makeText("Profiles", 11f, "#788096", FONT_MEDIUM, 1);
        profSectionHdr.setPadding(8, 2, 8, 6);
        profSectionHdr.setVisibility(View.GONE);
        side.addView(profSectionHdr);

        sideProfileContainer = new LinearLayout(this);
        sideProfileContainer.setOrientation(LinearLayout.VERTICAL);
        side.addView(sideProfileContainer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        navAddProfileBtn = makeSideNavBtn("+", "+  New Profile", false, () -> openTasteGuideDrawer(true));
        side.addView(navAddProfileBtn);

        libSectionHdr = makeText("Menu", 11f, "#788096", FONT_MEDIUM, 1);
        libSectionHdr.setPadding(8, 12, 8, 6);
        libSectionHdr.setVisibility(View.GONE);
        side.addView(libSectionHdr);

        navTasteBtn = makeSideNavBtn("✎", "✎  Guide AI", false, () -> openTasteGuideDrawer(false));
        side.addView(navTasteBtn);

        navAllPicksBtn = makeSideNavBtn("▦", "▦  All Picks", true, () -> {
            showingWatchedList = false;
            showingVoiceResults = false;
            setSidebarExpanded(false);
            refreshAllSections(true);
        });
        side.addView(navAllPicksBtn);

        navWatchedBtn = makeSideNavBtn("✓", "✓  Watched", false, () -> {
            showingWatchedList = !showingWatchedList;
            setSidebarExpanded(false);
            refreshAllSections(true);
        });
        side.addView(navWatchedBtn);

        navRefreshBtn = makeSideNavBtn("↻", "↻  Refresh", false, () -> {
            setSidebarExpanded(false);
            triggerManualRefresh();
        });
        side.addView(navRefreshBtn);

        return side;
    }

    private void setSidebarExpanded(final boolean expand) {
        if (sidebarExpanded == expand || sidePanel == null) return;
        sidebarExpanded = expand;

        if (sidebarAnimator != null && sidebarAnimator.isRunning()) {
            sidebarAnimator.cancel();
        }

        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sidePanel.getLayoutParams();
        final int startW = lp.width;
        final int targetW = expand ? SIDEBAR_EXPANDED_W : SIDEBAR_COLLAPSED_W;

        sidePanel.setPadding(expand ? 16 : 10, 26, expand ? 16 : 10, 20);
        sidePanel.setGravity(expand ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        brandLabel.setText(expand ? "Aura" : "A");
        brandLabel.setGravity(expand ? Gravity.START : Gravity.CENTER);
        profSectionHdr.setVisibility(expand ? View.VISIBLE : View.GONE);
        libSectionHdr.setVisibility(expand ? View.VISIBLE : View.GONE);
        updateSideButtonLabels();

        sidebarAnimator = ValueAnimator.ofInt(startW, targetW);
        sidebarAnimator.setDuration(170);
        sidebarAnimator.setInterpolator(SMOOTH_INTERP);
        sidebarAnimator.addUpdateListener(anim -> {
            lp.width = (Integer) anim.getAnimatedValue();
            sidePanel.setLayoutParams(lp);
        });
        sidebarAnimator.start();
    }

    private void updateSideButtonLabels() {
        if (sideProfileContainer != null) {
            for (int i = 0; i < sideProfileContainer.getChildCount() && i < memProfiles.size(); i++) {
                JSONObject pObj = memProfiles.get(i);
                String pid = pObj.optString("id");
                String fullName = pObj.optString("name", "Guest");
                String shortInitial = fullName.isEmpty() ? "U" : fullName.substring(0, 1).toUpperCase();
                boolean isAct = pid.equals(memProfile);
                TextView btn = (TextView) sideProfileContainer.getChildAt(i);
                btn.setText(sidebarExpanded ? ((isAct ? "●  " : "    ") + fullName) : shortInitial);
                btn.setGravity(sidebarExpanded ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
            }
        }
        if (navAddProfileBtn != null) {
            navAddProfileBtn.setText(sidebarExpanded ? "+  New Profile" : "+");
            navAddProfileBtn.setGravity(sidebarExpanded ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        }
        if (navTasteBtn != null) {
            navTasteBtn.setText(sidebarExpanded ? "✎  Guide AI" : "✎");
            navTasteBtn.setGravity(sidebarExpanded ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        }
        if (navAllPicksBtn != null) {
            navAllPicksBtn.setText(sidebarExpanded ? "▦  All Picks" : "▦");
            navAllPicksBtn.setGravity(sidebarExpanded ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        }
        if (navWatchedBtn != null) {
            navWatchedBtn.setText(sidebarExpanded ? ("✓  Watched (" + memWatchedKeys.size() + ")") : "✓");
            navWatchedBtn.setGravity(sidebarExpanded ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        }
        if (navRefreshBtn != null) {
            navRefreshBtn.setText(sidebarExpanded ? "↻  Refresh" : "↻");
            navRefreshBtn.setGravity(sidebarExpanded ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        }
    }

    private void populateSideProfiles() {
        if (sideProfileContainer == null) return;
        sideProfileContainer.removeAllViews();
        for (int i = 0; i < memProfiles.size(); i++) {
            JSONObject pObj = memProfiles.get(i);
            final String pid = pObj.optString("id");
            final String fullName = pObj.optString("name", "Guest");
            final boolean isAct = pid.equals(memProfile);
            String shortInitial = fullName.isEmpty() ? "U" : fullName.substring(0, 1).toUpperCase();
            TextView btn = makeSideNavBtn(
                shortInitial,
                (isAct ? "●  " : "    ") + fullName,
                isAct,
                () -> {
                    if (!pid.equals(memProfile)) {
                        switchProfileAndLoad(pid, false);
                    } else {
                        openTasteGuideDrawer(false);
                    }
                }
            );
            btn.setTag("prof_" + pid);
            sideProfileContainer.addView(btn);
        }
        updateSideButtonLabels();
    }

    private void focusSideProfileById(String pid) {
        setSidebarExpanded(true);
        if (sideProfileContainer == null) return;
        for (int i = 0; i < sideProfileContainer.getChildCount(); i++) {
            View child = sideProfileContainer.getChildAt(i);
            if (("prof_" + pid).equals(child.getTag())) {
                child.requestFocus();
                return;
            }
        }
        if (sideProfileContainer.getChildCount() > 0) {
            sideProfileContainer.getChildAt(0).requestFocus();
        }
    }

    private View buildUnifiedTopBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(6, 0, 0, 14);

        searchInput = new EditText(this);
        searchInput.setHint("Ask Aura or search titles…");
        searchInput.setHintTextColor(Color.parseColor("#6E758A"));
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(13.5f);
        searchInput.setTypeface(FONT_REGULAR);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setPadding(20, 10, 20, 10);
        styleInputBox(searchInput, false);
        searchInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) setSidebarExpanded(false);
            styleInputBox(searchInput, hasFocus);
        });
        searchInput.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN
                && searchInput.getSelectionStart() <= 0) {
                focusSideProfileById(memProfile);
                return true;
            }
            return false;
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submitSearchInput();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(365, LinearLayout.LayoutParams.WRAP_CONTENT);
        inLp.setMargins(0, 0, 10, 0);
        row.addView(searchInput, inLp);

        row.addView(makePillBtn("Search", false, this::submitSearchInput));

        clearSearchBtn = makePillBtn("✕ Clear", false, () -> {
            showingVoiceResults = false;
            clearSearchBtn.setVisibility(View.GONE);
            refreshCardsOnly(true);
        });
        clearSearchBtn.setVisibility(View.GONE);
        row.addView(clearSearchBtn);

        filterChipsRow = new LinearLayout(this);
        filterChipsRow.setOrientation(LinearLayout.HORIZONTAL);
        filterChipsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fLp.setMargins(6, 0, 0, 0);
        row.addView(filterChipsRow, fLp);

        row.addView(makePillBtn("✎ Guide AI", false, () -> openTasteGuideDrawer(false)));

        statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.HORIZONTAL);
        statusBox.setGravity(Gravity.CENTER_VERTICAL);
        statusBox.setPadding(12, 6, 12, 6);
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(16, 16);
        spLp.setMargins(0, 0, 8, 0);
        statusBox.addView(spinner, spLp);
        statusLabel = makeText("", 12f, "#F5C518", FONT_MEDIUM, 1);
        statusBox.addView(statusLabel);
        statusBox.setVisibility(View.GONE);
        row.addView(statusBox);

        return row;
    }

    private View buildMinimalTitleRow() {
        heroHeaderBox = new LinearLayout(this);
        heroHeaderBox.setOrientation(LinearLayout.VERTICAL);
        heroHeaderBox.setPadding(10, 2, 12, 8);

        LinearLayout topLine = new LinearLayout(this);
        topLine.setOrientation(LinearLayout.HORIZONTAL);
        topLine.setGravity(Gravity.CENTER_VERTICAL);

        headerTitle = makeText("Select a Title", 28f, "#FFFFFF", FONT_DISPLAY, 1);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.setMargins(0, 0, 18, 0);
        topLine.addView(headerTitle, tLp);

        headerMeta = makeText("", 14f, "#8E95A8", FONT_MEDIUM, 1);
        topLine.addView(headerMeta);
        heroHeaderBox.addView(topLine);

        headerReasonPreview = makeText("", 13f, "#C8B88A", FONT_REGULAR, 1);
        headerReasonPreview.setPadding(0, 4, 0, 0);
        heroHeaderBox.addView(headerReasonPreview);

        return heroHeaderBox;
    }

    private void buildRightSlideDrawers() {
        rightScrim = new FrameLayout(this);
        rightScrim.setBackgroundColor(Color.parseColor("#99040508"));
        rightScrim.setVisibility(View.GONE);
        rightScrim.setClickable(true);

        GradientDrawable dBg = new GradientDrawable();
        dBg.setColor(COLOR_BG_DRAWER);
        dBg.setStroke(1, COLOR_DRAWER_BORDER);

        // 1. Clean, Untruncated Right-Hand Slide-Over Show Details Drawer
        rightDetailsDrawer = new LinearLayout(this);
        rightDetailsDrawer.setOrientation(LinearLayout.VERTICAL);
        rightDetailsDrawer.setPadding(40, 34, 40, 28);
        rightDetailsDrawer.setBackground(dBg);
        rightDetailsDrawer.setVisibility(View.GONE);
        rightDetailsDrawer.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Top metadata row: [Apple TV+]  2019 · 59 min · IMDb 8.1
        LinearLayout topMetaRow = new LinearLayout(this);
        topMetaRow.setOrientation(LinearLayout.HORIZONTAL);
        topMetaRow.setGravity(Gravity.CENTER_VERTICAL);
        topMetaRow.setPadding(0, 0, 0, 10);

        drawerAvailableBadge = makeText("Apple TV+", 12f, "#F5C518", FONT_MEDIUM, 1);
        drawerAvailableBadge.setPadding(12, 4, 12, 4);
        GradientDrawable avBg = new GradientDrawable();
        avBg.setColor(Color.parseColor("#231F12"));
        avBg.setCornerRadius(6f);
        avBg.setStroke(1, Color.parseColor("#574919"));
        drawerAvailableBadge.setBackground(avBg);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        avLp.setMargins(0, 0, 12, 0);
        topMetaRow.addView(drawerAvailableBadge, avLp);

        drawerMeta = makeText("", 13f, "#A6B0C6", FONT_MEDIUM, 1);
        topMetaRow.addView(drawerMeta);
        rightDetailsDrawer.addView(topMetaRow);

        drawerTitle = makeText("", 26f, "#FFFFFF", FONT_DISPLAY, 2);
        rightDetailsDrawer.addView(drawerTitle);

        drawerGenres = makeText("", 13f, "#8E96AA", FONT_REGULAR, 1);
        drawerGenres.setPadding(0, 4, 0, 16);
        rightDetailsDrawer.addView(drawerGenres);

        // Natural-case, clean, untruncated AI recommendation reason
        drawerWhyHdr = makeText("Why you'll like it", 13f, "#F5C518", FONT_MEDIUM, 1);
        drawerWhyHdr.setPadding(0, 0, 0, 6);
        rightDetailsDrawer.addView(drawerWhyHdr);

        drawerWhyReason = makeText("", 14f, "#F0F3F9", FONT_REGULAR, 6);
        drawerWhyReason.setEllipsize(null);
        drawerWhyReason.setLineSpacing(4f, 1.22f);
        drawerWhyReason.setPadding(0, 0, 0, 14);
        rightDetailsDrawer.addView(drawerWhyReason);

        // Optional clean story summary (only visible when distinct from AI reason, never truncated mid-sentence)
        drawerSynopsisHdr = makeText("Story", 12.5f, "#8A93A6", FONT_MEDIUM, 1);
        drawerSynopsisHdr.setPadding(0, 0, 0, 4);
        rightDetailsDrawer.addView(drawerSynopsisHdr);

        drawerSynopsis = makeText("", 13.5f, "#B8C0D2", FONT_REGULAR, 6);
        drawerSynopsis.setEllipsize(null);
        drawerSynopsis.setLineSpacing(3f, 1.2f);
        drawerSynopsis.setPadding(0, 0, 0, 16);
        rightDetailsDrawer.addView(drawerSynopsis);

        LinearLayout actionBtns = new LinearLayout(this);
        actionBtns.setOrientation(LinearLayout.HORIZONTAL);
        actionBtns.setGravity(Gravity.CENTER_VERTICAL);
        actionBtns.setPadding(0, 4, 0, 18);

        drawerOpenAppBtn = makePillBtn("▶  Watch Now", true, null);
        drawerWatchedBtn = makePillBtn("✓  Watched", false, null);
        trapLeftFocus(drawerOpenAppBtn);
        actionBtns.addView(drawerOpenAppBtn);
        actionBtns.addView(drawerWatchedBtn);
        rightDetailsDrawer.addView(actionBtns);

        drawerRatingLabel = makeText("Rate this show (1–10)", 12.5f, "#8E95A8", FONT_MEDIUM, 1);
        drawerRatingLabel.setPadding(0, 0, 0, 8);
        rightDetailsDrawer.addView(drawerRatingLabel);

        drawerRatingRow = new LinearLayout(this);
        drawerRatingRow.setOrientation(LinearLayout.HORIZONTAL);
        drawerRatingRow.setGravity(Gravity.CENTER_VERTICAL);
        // Pre-allocate all 10 rating buttons ONCE so opening the drawer does zero View allocations!
        for (int i = 0; i < 10; i++) {
            final int sc = i + 1;
            final TextView numBtn = makeText(String.valueOf(sc), 12f, "#D0D4E0", FONT_MEDIUM, 1);
            numBtn.setGravity(Gravity.CENTER);
            numBtn.setPadding(11, 7, 11, 7);
            numBtn.setFocusable(true);
            numBtn.setClickable(true);
            stylePillBtn(numBtn, false, false);
            numBtn.setOnFocusChangeListener((v, hasFocus) -> stylePillBtn(numBtn, hasFocus, sc == activeDrawerScore));
            if (sc == 1) trapLeftFocus(numBtn);
            numBtn.setOnClickListener(v -> onRatingNumberClicked(sc));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 6, 0);
            ratingBtns[i] = numBtn;
            drawerRatingRow.addView(numBtn, lp);
        }
        rightDetailsDrawer.addView(drawerRatingRow);

        LinearLayout closeRow = new LinearLayout(this);
        closeRow.setOrientation(LinearLayout.HORIZONTAL);
        closeRow.setPadding(0, 18, 0, 0);
        drawerCloseBtn = makePillBtn("←  Back", false, this::closeRightDrawers);
        trapLeftAndDownFocus(drawerCloseBtn);
        closeRow.addView(drawerCloseBtn);
        rightDetailsDrawer.addView(closeRow);

        FrameLayout.LayoutParams detLp = new FrameLayout.LayoutParams(
            RIGHT_DRAWER_W, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        rightScrim.addView(rightDetailsDrawer, detLp);

        // 2. Right-Hand Slide-Over Profile & AI Interests Drawer
        GradientDrawable dBg2 = new GradientDrawable();
        dBg2.setColor(COLOR_BG_DRAWER);
        dBg2.setStroke(1, COLOR_DRAWER_BORDER);

        rightTasteDrawer = new LinearLayout(this);
        rightTasteDrawer.setOrientation(LinearLayout.VERTICAL);
        rightTasteDrawer.setPadding(40, 34, 40, 28);
        rightTasteDrawer.setBackground(dBg2);
        rightTasteDrawer.setVisibility(View.GONE);
        rightTasteDrawer.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        tasteDrawerTitle = makeText("Profile & AI Guide", 24f, "#FFFFFF", FONT_DISPLAY, 1);
        rightTasteDrawer.addView(tasteDrawerTitle);

        tasteDrawerSub = makeText("Personalize your profile and tell Aura what you love watching:", 13f, "#9AA2B8", FONT_REGULAR, 2);
        tasteDrawerSub.setPadding(0, 6, 0, 14);
        rightTasteDrawer.addView(tasteDrawerSub);

        TextView nameLbl = makeText("Profile name", 12f, "#8E95A8", FONT_MEDIUM, 1);
        nameLbl.setPadding(0, 0, 0, 6);
        rightTasteDrawer.addView(nameLbl);

        profileNameInput = new EditText(this);
        profileNameInput.setHint("Profile name…");
        profileNameInput.setHintTextColor(Color.parseColor("#6E758A"));
        profileNameInput.setTextColor(Color.WHITE);
        profileNameInput.setTextSize(14f);
        profileNameInput.setTypeface(FONT_REGULAR);
        profileNameInput.setSingleLine(true);
        profileNameInput.setPadding(18, 10, 18, 10);
        styleInputBox(profileNameInput, false);
        profileNameInput.setOnFocusChangeListener((v, hasFocus) -> styleInputBox(profileNameInput, hasFocus));
        trapLeftFocus(profileNameInput);
        rightTasteDrawer.addView(profileNameInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView interestLbl = makeText("Interests & mood guidance", 12f, "#8E95A8", FONT_MEDIUM, 1);
        interestLbl.setPadding(0, 14, 0, 6);
        rightTasteDrawer.addView(interestLbl);

        tasteEditInput = new EditText(this);
        tasteEditInput.setHint("e.g. Sports dramas, Formula 1, mind-bending sci-fi…");
        tasteEditInput.setHintTextColor(Color.parseColor("#6E758A"));
        tasteEditInput.setTextColor(Color.WHITE);
        tasteEditInput.setTextSize(14f);
        tasteEditInput.setTypeface(FONT_REGULAR);
        tasteEditInput.setSingleLine(true);
        tasteEditInput.setPadding(18, 10, 18, 10);
        styleInputBox(tasteEditInput, false);
        tasteEditInput.setOnFocusChangeListener((v, hasFocus) -> styleInputBox(tasteEditInput, hasFocus));
        trapLeftFocus(tasteEditInput);
        rightTasteDrawer.addView(tasteEditInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView presetHdr = makeText("Quick interest tags", 12f, "#8E95A8", FONT_MEDIUM, 1);
        presetHdr.setPadding(0, 14, 0, 8);
        rightTasteDrawer.addView(presetHdr);

        tasteTagsContainer = new LinearLayout(this);
        tasteTagsContainer.setOrientation(LinearLayout.VERTICAL);
        rightTasteDrawer.addView(tasteTagsContainer);

        LinearLayout tasteActions = new LinearLayout(this);
        tasteActions.setOrientation(LinearLayout.HORIZONTAL);
        tasteActions.setPadding(0, 18, 0, 0);
        tasteSaveBtn = makePillBtn("✓  Save & Curate", true, this::saveProfileAndCurate);
        tasteDeleteProfileBtn = makePillBtn("Delete", false, this::deleteCurrentProfile);
        tasteCloseBtn = makePillBtn("←  Back", false, this::closeRightDrawers);
        trapLeftAndDownFocus(tasteSaveBtn);
        trapDownFocus(tasteDeleteProfileBtn);
        trapDownFocus(tasteCloseBtn);

        tasteActions.addView(tasteSaveBtn);
        tasteActions.addView(tasteDeleteProfileBtn);
        tasteActions.addView(tasteCloseBtn);
        rightTasteDrawer.addView(tasteActions);

        FrameLayout.LayoutParams tstLp = new FrameLayout.LayoutParams(
            RIGHT_DRAWER_W, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        rightScrim.addView(rightTasteDrawer, tstLp);

        rootFrame.addView(rightScrim, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void trapLeftFocus(View v) {
        v.setOnKeyListener((view, keyCode, event) ->
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN);
    }

    private void trapDownFocus(View v) {
        v.setOnKeyListener((view, keyCode, event) ->
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN);
    }

    private void trapLeftAndDownFocus(View v) {
        v.setOnKeyListener((view, keyCode, event) ->
            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                && event.getAction() == KeyEvent.ACTION_DOWN);
    }

    private void blockBackgroundFocus(boolean block) {
        int mode = block ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS;
        if (rightStage != null) rightStage.setDescendantFocusability(mode);
        if (sidePanel != null) sidePanel.setDescendantFocusability(mode);
    }

    private void openDetailsDrawer(final JSONObject item, final View posterView) {
        if (item == null) return;
        lastFocusedPoster = posterView;
        drawerClosing = false;
        blockBackgroundFocus(true);

        final String mainTitle = item.optString("series_title").isEmpty()
            ? item.optString("title")
            : item.optString("series_title");
        final String titleKey = AuraDb.normalizeTitleKey(mainTitle);
        final String appName = cleanAppName(item.optString("app", item.optString("app_name", "Streaming")));
        String rawImdb = item.optString("rating", "8.2").replace("★", "").trim();
        int rt = item.optInt("runtime_min", 48);
        int year = item.optInt("year", 2024);
        String genres = cleanGenres(item.optString("genres", "Drama"));

        String rawSyn = item.optString("synopsis").trim();
        String rawReason = item.optString("reason").trim();
        if (rawReason.isEmpty() || rawReason.equalsIgnoreCase(rawSyn)) {
            rawReason = "Recommended for " + db.getProfileLabel(memProfile) + " based on your interest in " + genres + " and top-rated titles.";
        }

        String cleanReason = formatCompleteSentences(rawReason, 280);
        String cleanSyn = formatCompleteSentences(rawSyn, 280);

        drawerAvailableBadge.setText(appName);
        drawerMeta.setText(year + "   ·   " + rt + " min   ·   IMDb " + rawImdb);
        drawerTitle.setText(mainTitle);
        drawerGenres.setText(genres.replace(", ", "  ·  "));
        drawerWhyReason.setText(cleanReason);

        if (!cleanSyn.isEmpty() && !cleanSyn.equalsIgnoreCase(cleanReason)) {
            drawerSynopsisHdr.setVisibility(View.VISIBLE);
            drawerSynopsis.setVisibility(View.VISIBLE);
            drawerSynopsis.setText(cleanSyn);
        } else {
            drawerSynopsisHdr.setVisibility(View.GONE);
            drawerSynopsis.setVisibility(View.GONE);
        }

        drawerOpenAppBtn.setText("▶  Watch on " + appName);
        drawerOpenAppBtn.setOnClickListener(v -> {
            closeRightDrawers();
            String uri = item.optString("launch_uri");
            String pkg = item.optString("launch_pkg", item.optString("app_pkg"));
            if (pkg.isEmpty() || pkg.contains("smarttube")) pkg = AuraDb.resolveAppPackage(appName);
            TvOverlayManager.launchNativeStreamingApp(this, uri, pkg.isEmpty() ? appName : pkg, mainTitle);
        });

        final boolean isW = memWatchedKeys.contains(titleKey);
        drawerWatchedBtn.setText(isW ? "✕  Unwatch" : "✓  Watched");
        drawerWatchedBtn.setOnClickListener(v -> {
            closeRightDrawers();
            handleMarkWatchedInPlace(posterView, item, mainTitle);
        });

        activeDrawerItem = item;
        activeDrawerTitle = mainTitle;
        activeDrawerTitleKey = titleKey;
        activeDrawerScore = memUserRatings.getOrDefault(titleKey, item.optInt("user_rating", 0));
        refreshRatingButtonStyles();

        rightTasteDrawer.setVisibility(View.GONE);
        animateOpenDrawer(rightDetailsDrawer, drawerOpenAppBtn);
    }

    private void onRatingNumberClicked(int sc) {
        if (activeDrawerItem == null || activeDrawerTitleKey.isEmpty()) return;
        activeDrawerScore = sc;
        memUserRatings.put(activeDrawerTitleKey, sc);
        if (!memWatchedKeys.contains(activeDrawerTitleKey)) {
            memWatchedKeys.add(activeDrawerTitleKey);
            try {
                activeDrawerItem.put("user_rating", sc);
                memWatchedList.put(activeDrawerItem);
            } catch (Exception ignored) {}
        }
        refreshRatingButtonStyles();
        drawerWatchedBtn.setText("✓  Watched (" + sc + "/10)");
        updateSideButtonLabels();
        final JSONObject itemSnap = activeDrawerItem;
        bgPool.submit(() -> {
            db.rateTitle(itemSnap, sc);
            sync.syncWithHost();
        });
    }

    private void refreshRatingButtonStyles() {
        drawerRatingLabel.setText(activeDrawerScore > 0
            ? ("Your rating: " + activeDrawerScore + "/10  ·  Saved to AI taste profile")
            : "Rate this show (1–10)");
        for (int i = 0; i < 10; i++) {
            int sc = i + 1;
            TextView btn = ratingBtns[i];
            if (btn != null) {
                stylePillBtn(btn, btn.hasFocus(), sc == activeDrawerScore);
            }
        }
    }

    private void openTasteGuideDrawer(boolean addNewMode) {
        creatingNewProfileMode = addNewMode;
        drawerClosing = false;
        setSidebarExpanded(false);
        blockBackgroundFocus(true);

        if (addNewMode) {
            String suggestedName = "User_" + (100 + (int) (Math.random() * 899));
            tasteDrawerTitle.setText("New Profile");
            tasteDrawerSub.setText("Choose a name and interests so Aura can curate a personal feed:");
            profileNameInput.setText(suggestedName);
            tasteEditInput.setText("");
            tasteSaveBtn.setText("✓  Create & Curate");
            tasteDeleteProfileBtn.setVisibility(View.GONE);
        } else {
            String profName = db.getProfileLabel(memProfile);
            String currentTaste = db.getProfileTaste(memProfile);
            tasteDrawerTitle.setText("Profile & AI Guide");
            tasteDrawerSub.setText("Update your name, refine interests, or remove this profile:");
            profileNameInput.setText(profName);
            tasteEditInput.setText(currentTaste);
            tasteSaveBtn.setText("✓  Save & Curate");
            tasteDeleteProfileBtn.setVisibility(View.VISIBLE);
        }

        populateTastePresetRows();

        rightDetailsDrawer.setVisibility(View.GONE);
        animateOpenDrawer(rightTasteDrawer, tasteSaveBtn);
    }

    private void animateOpenDrawer(final View drawer, final View initialFocusTarget) {
        rightScrim.animate().cancel();
        drawer.animate().cancel();

        rightScrim.setAlpha(0f);
        rightScrim.setVisibility(View.VISIBLE);
        rightScrim.animate()
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(SMOOTH_INTERP)
            .start();

        drawer.setTranslationX(RIGHT_DRAWER_W);
        drawer.setAlpha(0.4f);
        drawer.setVisibility(View.VISIBLE);
        drawer.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(250)
            .setInterpolator(SMOOTH_INTERP)
            .start();

        if (initialFocusTarget != null) {
            initialFocusTarget.requestFocus();
        }
    }

    private void populateTastePresetRows() {
        tasteTagsContainer.removeAllViews();
        List<String> dynamicTags = new ArrayList<>();
        JSONArray followUps = memVoiceSession.optJSONArray("follow_ups");
        if (followUps != null) {
            for (int i = 0; i < followUps.length(); i++) {
                String f = followUps.optString(i).trim();
                if (!f.isEmpty() && !dynamicTags.contains(f)) dynamicTags.add(f);
            }
        }
        for (int i = 0; i < memRecos.length() && dynamicTags.size() < 6; i++) {
            JSONObject r = memRecos.optJSONObject(i);
            if (r == null) continue;
            for (String g : cleanGenres(r.optString("genres")).split(",")) {
                String gt = g.trim();
                if (!gt.isEmpty() && !dynamicTags.contains(gt) && dynamicTags.size() < 6) {
                    dynamicTags.add(gt);
                }
            }
        }

        String cur = tasteEditInput.getText().toString();
        LinearLayout row = null;
        for (int i = 0; i < dynamicTags.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 0, 0, 8);
                tasteTagsContainer.addView(row);
            }
            final String tag = dynamicTags.get(i);
            final boolean active = cur.toLowerCase().contains(tag.toLowerCase());
            TextView chip = makePillBtn((active ? "✓ " : "+ ") + tag, active, () -> {
                String now = tasteEditInput.getText().toString().trim();
                if (now.toLowerCase().contains(tag.toLowerCase())) {
                    now = now.replaceAll("(?i),?\\s*" + java.util.regex.Pattern.quote(tag), "").replaceAll("^,\\s*", "").trim();
                } else {
                    now = now.isEmpty() ? tag : (now + ", " + tag);
                }
                tasteEditInput.setText(now);
                populateTastePresetRows();
            });
            if (i % 2 == 0) trapLeftFocus(chip);
            if (row != null) row.addView(chip);
        }
    }

    private void saveProfileAndCurate() {
        final String newName = profileNameInput.getText().toString().trim();
        final String newTaste = tasteEditInput.getText().toString().trim();
        final boolean isNew = creatingNewProfileMode;
        closeRightDrawers();

        bgPool.submit(() -> {
            final String targetPid;
            if (isNew) {
                targetPid = db.addProfile(newName, newTaste);
            } else {
                targetPid = memProfile;
                db.saveProfileRecord(targetPid, newName, newTaste);
            }
            final String label = db.getProfileLabel(targetPid);
            uiHandler.post(() -> {
                memProfile = targetPid;
                memProfiles.clear();
                memProfiles.addAll(db.listProfiles());
                populateSideProfiles();
                setLoader(true, "AI curating for " + label + "…");
            });

            JSONArray fresh = ai.getOrRefreshForProfile(targetPid, new JSONObject(), db.listHistory(targetPid), true);
            sync.syncWithHost();
            final List<JSONObject> profList = db.listProfiles();
            uiHandler.post(() -> {
                memProfile = targetPid;
                memProfiles.clear();
                memProfiles.addAll(profList);
                memRecos = fresh;
                showingVoiceResults = false;
                setLoader(false, "✓ Curated for " + label);
                refreshAllSections(true);
            });
        });
    }

    private void deleteCurrentProfile() {
        final String toDelete = memProfile;
        closeRightDrawers();
        bgPool.submit(() -> {
            final String nextPid = db.deleteProfile(toDelete);
            uiHandler.post(() -> switchProfileAndLoad(nextPid, true));
        });
    }

    private void closeRightDrawers() {
        if (rightScrim == null || rightScrim.getVisibility() != View.VISIBLE || drawerClosing) return;
        drawerClosing = true;
        blockBackgroundFocus(false);

        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && rootFrame != null) {
            imm.hideSoftInputFromWindow(rootFrame.getWindowToken(), 0);
        }

        final View activeDrawer = (rightDetailsDrawer != null && rightDetailsDrawer.getVisibility() == View.VISIBLE)
            ? rightDetailsDrawer
            : rightTasteDrawer;

        if (lastFocusedPoster != null && lastFocusedPoster.getParent() != null) {
            lastFocusedPoster.requestFocus();
        } else if (carouselRow != null && carouselRow.getChildCount() > 0) {
            carouselRow.getChildAt(0).requestFocus();
        }

        if (activeDrawer != null) {
            activeDrawer.animate()
                .translationX(RIGHT_DRAWER_W)
                .alpha(0f)
                .setDuration(190)
                .setInterpolator(SMOOTH_INTERP)
                .start();
        }
        rightScrim.animate()
            .alpha(0f)
            .setDuration(190)
            .setInterpolator(SMOOTH_INTERP)
            .withEndAction(() -> {
                drawerClosing = false;
                rightScrim.setVisibility(View.GONE);
                if (rightDetailsDrawer != null) rightDetailsDrawer.setVisibility(View.GONE);
                if (rightTasteDrawer != null) rightTasteDrawer.setVisibility(View.GONE);
            })
            .start();
    }

    private void updateHeaderForCard(JSONObject item) {
        if (item == null) return;
        String mainTitle = item.optString("series_title").isEmpty()
            ? item.optString("title")
            : item.optString("series_title");
        int rt = item.optInt("runtime_min", 45);
        int year = item.optInt("year", 2024);
        String genres = cleanGenres(item.optString("genres", "Drama"));
        String appName = cleanAppName(item.optString("app", item.optString("app_name", "")));
        String reason = formatCompleteSentences(item.optString("reason", ""), 125);

        CharSequence oldTitle = headerTitle.getText();
        boolean changed = oldTitle == null || !mainTitle.contentEquals(oldTitle);

        headerTitle.setText(mainTitle);
        headerMeta.setText(year + "   ·   " + rt + " min   ·   " + genres + (appName.isEmpty() ? "" : ("   ·   " + appName)));
        headerReasonPreview.setVisibility(View.GONE);

        if (changed && heroHeaderBox != null) {
            heroHeaderBox.animate().cancel();
            heroHeaderBox.setAlpha(0.55f);
            heroHeaderBox.setTranslationY(3f);
            heroHeaderBox.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(130)
                .setInterpolator(SMOOTH_INTERP)
                .start();
        }
    }

    private void refreshAllSections(boolean focusFirstCard) {
        populateSideProfiles();
        styleSideNavBtn(navAllPicksBtn, navAllPicksBtn.hasFocus(), !showingWatchedList);
        styleSideNavBtn(navWatchedBtn, navWatchedBtn.hasFocus(), showingWatchedList);
        clearSearchBtn.setVisibility(showingVoiceResults ? View.VISIBLE : View.GONE);

        populateFilterChips();
        refreshCardsOnly(focusFirstCard);
    }

    private void populateFilterChips() {
        filterChipsRow.removeAllViews();
        for (String[] f : QUICK_FILTERS) {
            final String fid = f[0];
            final String fLabel = f[1];
            final boolean isAct = "all".equals(fid) ? memFilters.isEmpty() : memFilters.contains(fid);
            final TextView chip = makePillBtn(fLabel, isAct, () -> {
                if ("all".equals(fid)) {
                    memFilters.clear();
                    final String prof = memProfile;
                    bgPool.submit(() -> db.clearFilters(prof));
                } else {
                    toggleFilterInMemory(fid);
                }
                populateFilterChips();
                refreshCardsOnly(false);
                focusChipById(fid);
            });
            chip.setTag("filter_" + fid);
            filterChipsRow.addView(chip);
        }
    }

    private void focusChipById(String fid) {
        for (int i = 0; i < filterChipsRow.getChildCount(); i++) {
            View child = filterChipsRow.getChildAt(i);
            if (("filter_" + fid).equals(child.getTag())) {
                child.requestFocus();
                return;
            }
        }
    }

    private void toggleFilterInMemory(final String fid) {
        if (memFilters.contains(fid)) {
            memFilters.remove(fid);
        } else {
            if ("movies".equals(fid)) memFilters.remove("series");
            if ("series".equals(fid)) memFilters.remove("movies");
            memFilters.add(fid);
        }
        final String prof = memProfile;
        bgPool.submit(() -> db.toggleFilter(prof, fid));
    }

    private void refreshCardsOnly(boolean focusFirstCard) {
        JSONArray items = getFilteredItemsFromMemory();
        int limit = Math.min(items.length(), MAX_CAROUSEL_CARDS);

        // Reuse existing poster views in carouselRow to avoid GC & View creation overhead!
        while (carouselRow.getChildCount() > limit) {
            carouselRow.removeViewAt(carouselRow.getChildCount() - 1);
        }

        for (int i = 0; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            FrameLayout tile;
            if (i < carouselRow.getChildCount()) {
                tile = (FrameLayout) carouselRow.getChildAt(i);
                bindPosterTile(tile, item, i == 0);
            } else {
                tile = buildPurePosterTile(item, i == 0);
                carouselRow.addView(tile);
            }
        }

        if (items.length() > 0) {
            updateHeaderForCard(items.optJSONObject(0));
        } else {
            headerTitle.setText("No matching titles");
            headerMeta.setText("Select 'All' or update Guide AI");
            headerReasonPreview.setVisibility(View.GONE);
        }
        if (focusFirstCard && carouselRow.getChildCount() > 0) {
            setSidebarExpanded(false);
            carouselRow.getChildAt(0).requestFocus();
        }
    }

    private FrameLayout buildPurePosterTile(final JSONObject item, boolean isFirst) {
        final FrameLayout posterFrame = new FrameLayout(this);
        posterFrame.setPadding(4, 4, 4, 4);
        posterFrame.setFocusable(true);
        posterFrame.setClickable(true);
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(16f);
        border.setColor(Color.TRANSPARENT);
        border.setStroke(1, COLOR_POSTER_BORDER_IDLE);
        posterFrame.setBackground(border);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(Color.parseColor("#141722"));
        ImageLoader.applyRoundedCorners(img, 13f);
        posterFrame.addView(img, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(264, 396);
        lp.setMargins(0, 0, 22, 0);
        posterFrame.setLayoutParams(lp);

        bindPosterTile(posterFrame, item, isFirst);
        return posterFrame;
    }

    private void bindPosterTile(final FrameLayout posterFrame, final JSONObject item, boolean isFirst) {
        ImageView img = (ImageView) posterFrame.getChildAt(0);
        ImageLoader.load(this, item.optString("poster_url"), img);

        posterFrame.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && sidebarExpanded) setSidebarExpanded(false);
            stylePosterFrame(posterFrame, hasFocus);
            posterFrame.animate()
                .scaleX(hasFocus ? 1.05f : 1.0f)
                .scaleY(hasFocus ? 1.05f : 1.0f)
                .translationY(hasFocus ? -4f : 0f)
                .setDuration(130)
                .setInterpolator(SMOOTH_INTERP)
                .start();
            posterFrame.setElevation(hasFocus ? 16f : 2f);
            if (hasFocus) updateHeaderForCard(item);
        });

        posterFrame.setOnClickListener(v -> openDetailsDrawer(item, posterFrame));

        if (isFirst) {
            posterFrame.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN) {
                    focusSideProfileById(memProfile);
                    return true;
                }
                return false;
            });
        } else {
            posterFrame.setOnKeyListener(null);
        }
    }

    private void handleMarkWatchedInPlace(View posterView, final JSONObject item, final String title) {
        int idx = carouselRow.indexOfChild(posterView);
        String key = AuraDb.normalizeTitleKey(title);
        final boolean nowWatched = !memWatchedKeys.contains(key);
        if (nowWatched) {
            memWatchedKeys.add(key);
            memWatchedList.put(item);
        } else {
            memWatchedKeys.remove(key);
        }

        updateSideButtonLabels();
        setLoader(false, nowWatched ? ("✓ Watched: " + title) : ("Unwatched: " + title));

        if (idx >= 0) {
            carouselRow.removeViewAt(idx);
            int remaining = carouselRow.getChildCount();
            if (remaining > 0) {
                int targetIdx = Math.min(idx, remaining - 1);
                carouselRow.getChildAt(targetIdx).requestFocus();
            }
        }

        bgPool.submit(() -> {
            db.markTitleWatched(item, nowWatched);
            sync.syncWithHost();
        });
    }

    private JSONArray getFilteredItemsFromMemory() {
        if (showingWatchedList) return memWatchedList;

        JSONArray vr = memVoiceSession.optJSONArray("results");
        JSONArray primary = (showingVoiceResults && vr != null && vr.length() > 0) ? vr : memRecos;

        JSONArray pool = new JSONArray();
        Set<String> seenKeys = new HashSet<>();
        JSONArray[] sources = showingVoiceResults ? new JSONArray[]{primary, memRecos} : new JSONArray[]{memRecos};
        for (JSONArray arr : sources) {
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || o.optString("poster_url").isEmpty()) continue;
                String t = o.optString("series_title").isEmpty() ? o.optString("title") : o.optString("series_title");
                String k = AuraDb.normalizeTitleKey(t);
                if (!k.isEmpty() && !memWatchedKeys.contains(k) && !seenKeys.contains(k)) {
                    seenKeys.add(k);
                    pool.put(o);
                }
            }
        }

        if (memFilters.isEmpty()) return pool;

        JSONArray strict = new JSONArray();
        Set<String> added = new HashSet<>();
        for (int i = 0; i < pool.length(); i++) {
            JSONObject o = pool.optJSONObject(i);
            if (o != null && matchesAllFilters(o, memFilters)) {
                strict.put(o);
                added.add(AuraDb.normalizeTitleKey(o.optString("title")));
            }
        }
        if (strict.length() < 6) {
            for (int i = 0; i < pool.length() && strict.length() < 10; i++) {
                JSONObject o = pool.optJSONObject(i);
                String k = o != null ? AuraDb.normalizeTitleKey(o.optString("title")) : "";
                if (o != null && !added.contains(k) && matchesAnyFilter(o, memFilters)) {
                    strict.put(o);
                    added.add(k);
                }
            }
        }
        return strict.length() > 0 ? strict : pool;
    }

    private boolean matchesAnyFilter(JSONObject o, List<String> filters) {
        for (String f : filters) {
            if (matchesAllFilters(o, Collections.singletonList(f))) return true;
        }
        return false;
    }

    private boolean matchesAllFilters(JSONObject o, List<String> filters) {
        int rt = o.optInt("runtime_min", 45);
        String type = o.optString("type", "").toLowerCase();
        String text = (o.optString("genres") + " " + o.optString("reason") + " " + o.optString("synopsis") + " " + o.optString("app")).toLowerCase();
        for (String f : filters) {
            if ("short".equals(f) && rt > 35) return false;
            if ("movies".equals(f) && !"movie".equals(type) && rt < 80 && !text.contains("movie") && !text.contains("film")) return false;
            if ("series".equals(f) && ("movie".equals(type) || rt > 80)) return false;
        }
        return true;
    }

    private void submitSearchInput() {
        String q = searchInput.getText().toString().trim();
        if (q.isEmpty()) {
            searchInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        searchInput.setText("");
        runSearchSessionTurn(q);
    }

    private void runSearchSessionTurn(final String query) {
        showingWatchedList = false;
        setLoader(true, "Curating: \"" + query + "\"…");
        final String prof = memProfile;
        bgPool.submit(() -> {
            final JSONObject res = voice.handleTurn(prof, query, false);
            uiHandler.post(() -> {
                memVoiceSession = res;
                showingVoiceResults = true;
                int count = res.optJSONArray("results") != null ? res.optJSONArray("results").length() : 0;
                setLoader(false, "✓ " + count + " Matches");
                refreshAllSections(true);
            });
        });
    }

    private void triggerManualRefresh() {
        setLoader(true, "AI curating picks…");
        final String prof = memProfile;
        bgPool.submit(() -> {
            JSONArray fresh = ai.getOrRefreshForProfile(prof, new JSONObject(), db.listHistory(prof), true);
            sync.syncWithHost();
            uiHandler.post(() -> {
                memRecos = fresh;
                showingVoiceResults = false;
                setLoader(false, "✓ Updated");
                refreshAllSections(true);
            });
        });
    }

    private void setLoader(boolean loading, String msg) {
        String text = msg != null ? msg : "";
        if (text.isEmpty() && !loading) {
            statusBox.setVisibility(View.GONE);
            return;
        }
        statusBox.setVisibility(View.VISIBLE);
        spinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        statusLabel.setText(text);
        GradientDrawable d = getOrCreateDrawable(statusBox, 99f);
        d.setColor(Color.parseColor("#1D202B"));
        d.setStroke(1, loading ? COLOR_IMDB_GOLD : Color.parseColor("#4CAF50"));
        statusLabel.setTextColor(loading ? COLOR_IMDB_GOLD : Color.parseColor("#81C784"));
    }

    private TextView makeSideNavBtn(String shortText, String fullText, final boolean isSelected, final Runnable onClick) {
        final TextView btn = makeText(sidebarExpanded ? fullText : shortText, 13f, "#D8DCE6", FONT_MEDIUM, 1);
        btn.setGravity(sidebarExpanded ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        btn.setPadding(12, 10, 12, 10);
        btn.setFocusable(true);
        btn.setClickable(true);
        styleSideNavBtn(btn, false, isSelected);
        btn.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !sidebarExpanded) setSidebarExpanded(true);
            styleSideNavBtn(btn, hasFocus, isSelected);
        });
        btn.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.getAction() == KeyEvent.ACTION_DOWN) {
                setSidebarExpanded(false);
                if (carouselRow != null && carouselRow.getChildCount() > 0) {
                    carouselRow.getChildAt(0).requestFocus();
                    return true;
                }
            }
            return false;
        });
        if (onClick != null) btn.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 6);
        btn.setLayoutParams(lp);
        return btn;
    }

    private GradientDrawable getOrCreateDrawable(View v, float radius) {
        Drawable existing = v.getBackground();
        if (existing instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) existing;
            gd.setCornerRadius(radius);
            return gd;
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(radius);
        v.setBackground(gd);
        return gd;
    }

    private void styleSideNavBtn(TextView btn, boolean focused, boolean selected) {
        if (btn == null) return;
        GradientDrawable d = getOrCreateDrawable(btn, 10f);
        if (focused) {
            d.setColor(COLOR_IMDB_GOLD);
            d.setStroke(2, Color.WHITE);
            btn.setTextColor(Color.BLACK);
        } else if (selected) {
            d.setColor(COLOR_ACTIVE_FILTER_BG);
            d.setStroke(1, COLOR_IMDB_GOLD);
            btn.setTextColor(COLOR_IMDB_GOLD);
        } else {
            d.setColor(Color.TRANSPARENT);
            d.setStroke(0, Color.TRANSPARENT);
            btn.setTextColor(Color.parseColor("#B8BECE"));
        }
    }

    private TextView makePillBtn(String text, final boolean isSelected, final Runnable onClick) {
        final TextView btn = makeText(text, 13f, "#E2E6F0", FONT_MEDIUM, 1);
        btn.setPadding(18, 9, 18, 9);
        btn.setFocusable(true);
        btn.setClickable(true);
        stylePillBtn(btn, false, isSelected);
        btn.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && sidebarExpanded && !isAnyDrawerOpen()) setSidebarExpanded(false);
            stylePillBtn(btn, hasFocus, isSelected);
        });
        if (onClick != null) btn.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 8, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void stylePillBtn(TextView btn, boolean focused, boolean selected) {
        GradientDrawable d = getOrCreateDrawable(btn, 99f);
        if (focused) {
            d.setColor(COLOR_IMDB_GOLD);
            d.setStroke(2, Color.WHITE);
            btn.setTextColor(Color.BLACK);
        } else if (selected) {
            d.setColor(COLOR_ACTIVE_FILTER_BG);
            d.setStroke(2, COLOR_IMDB_GOLD);
            btn.setTextColor(COLOR_IMDB_GOLD);
        } else {
            d.setColor(COLOR_SURFACE_PILL);
            d.setStroke(1, COLOR_BORDER_SUBTLE);
            btn.setTextColor(Color.parseColor("#D0D4E0"));
        }
    }

    private void stylePosterFrame(FrameLayout frame, boolean focused) {
        GradientDrawable d = getOrCreateDrawable(frame, 16f);
        d.setColor(Color.TRANSPARENT);
        d.setStroke(focused ? 4 : 1, focused ? COLOR_IMDB_GOLD : COLOR_POSTER_BORDER_IDLE);
    }

    private void styleInputBox(EditText et, boolean focused) {
        GradientDrawable d = getOrCreateDrawable(et, 12f);
        d.setColor(Color.parseColor(focused ? "#1E2232" : "#131621"));
        d.setStroke(focused ? 2 : 1, focused ? COLOR_IMDB_GOLD : Color.parseColor("#2A3045"));
    }

    private String formatCompleteSentences(String raw, int maxChars) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "";
        if (s.length() <= maxChars) {
            if (!s.endsWith(".") && !s.endsWith("!") && !s.endsWith("?")) {
                s = s + ".";
            }
            return s;
        }
        // Cut cleanly at the last sentence boundary or clause boundary before maxChars so it NEVER shows "..."
        String window = s.substring(0, maxChars);
        int lastPeriod = Math.max(window.lastIndexOf(". "), Math.max(window.lastIndexOf("! "), window.lastIndexOf("? ")));
        if (lastPeriod >= 45) {
            return window.substring(0, lastPeriod + 1).trim();
        }
        int lastCommaOrSemi = Math.max(window.lastIndexOf("; "), window.lastIndexOf(", "));
        if (lastCommaOrSemi >= 60) {
            return window.substring(0, lastCommaOrSemi).trim() + ".";
        }
        int lastSpace = window.lastIndexOf(' ');
        if (lastSpace > 40) {
            return window.substring(0, lastSpace).replaceAll("[,;:\\-]+$", "").trim() + ".";
        }
        return window.trim() + ".";
    }

    private String cleanAppName(String raw) {
        String a = raw.split("·")[0].trim();
        String low = a.toLowerCase();
        if (low.contains("hotstar") || low.contains("disney") || low.contains("jio")) return "Disney+ Hotstar";
        if (low.contains("netflix")) return "Netflix";
        if (low.contains("prime") || low.contains("amazon")) return "Prime Video";
        if (low.contains("apple")) return "Apple TV+";
        if (low.contains("sony")) return "SonyLIV";
        if (low.contains("zee")) return "ZEE5";
        return a.isEmpty() ? "Streaming" : a;
    }

    private String cleanGenres(String g) {
        return g.replace("[", "").replace("]", "").replace("\"", "").replace(",", ", ").replaceAll("\\s+", " ").trim();
    }

    private TextView makeText(String txt, float sp, String hex, Typeface tf, int maxLines) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextSize(sp);
        tv.setTextColor(Color.parseColor(hex));
        tv.setTypeface(tf);
        tv.setIncludeFontPadding(false);
        tv.setLetterSpacing(0.01f);
        tv.setMaxLines(maxLines);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }
}
