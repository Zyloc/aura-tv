package com.aura.tvprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
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
    private static final String[] PROFILE_IDS = {"member1", "partner", "member3", "kids", "family"};
    private static final String[][] QUICK_FILTERS = {
        {"all", "All"},
        {"movies", "Movies"},
        {"series", "Series"},
        {"short", "< 30m"}
    };

    private static final int SIDEBAR_COLLAPSED_W = 76;
    private static final int SIDEBAR_EXPANDED_W = 248;
    private static final int RIGHT_DRAWER_W = 580;

    private static final String BG_OBSIDIAN = "#08090E";
    private static final String BG_SIDEBAR = "#0D0F17";
    private static final String BG_DRAWER = "#111420";
    private static final String SURFACE_PILL = "#151824";
    private static final String BORDER_SUBTLE = "#252A3C";
    private static final String ACTIVE_FILTER_BG = "#2A2210";
    private static final String IMDB_GOLD = "#F5C518";

    private static final Typeface FONT_DISPLAY = Typeface.create("sans-serif", Typeface.BOLD);
    private static final Typeface FONT_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface FONT_REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgPool = Executors.newSingleThreadExecutor();

    private AuraDb db;
    private AiRecommender ai;
    private SessionInspector inspector;
    private SyncManager sync;
    private VoiceConcierge voice;

    // Lock-free in-memory UI state
    private String memProfile = AuraDb.DEFAULT_PROFILE;
    private final List<String> memFilters = new ArrayList<>();
    private final Set<String> memWatchedKeys = new HashSet<>();
    private final Map<String, Integer> memUserRatings = new HashMap<>();
    private JSONArray memRecos = new JSONArray();
    private JSONArray memWatchedList = new JSONArray();
    private JSONObject memVoiceSession = new JSONObject();
    private boolean showingWatchedList = false;
    private boolean showingVoiceResults = false;
    private boolean sidebarExpanded = false;

    private FrameLayout rootFrame;
    private LinearLayout sidePanel;
    private TextView brandLabel;
    private TextView profSectionHdr;
    private TextView libSectionHdr;
    private LinearLayout sideProfileContainer;
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

    private TextView headerTitle;
    private TextView headerMeta;
    private LinearLayout carouselRow;

    // Right-Hand Slide-Over Details Drawer
    private FrameLayout rightScrim;
    private LinearLayout rightDetailsDrawer;
    private TextView drawerTitle;
    private TextView drawerMeta;
    private TextView drawerAvailableBadge;
    private TextView drawerSynopsis;
    private TextView drawerOpenAppBtn;
    private TextView drawerWatchedBtn;
    private TextView drawerRatingLabel;
    private LinearLayout drawerRatingRow;

    // Right-Hand Slide-Over AI Taste Guide Drawer
    private LinearLayout rightTasteDrawer;
    private TextView tasteDrawerTitle;
    private TextView tasteDrawerSub;
    private EditText tasteEditInput;
    private LinearLayout tasteTagsContainer;
    private TextView tasteSaveBtn;

    private View lastFocusedPoster;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        startService(new Intent(this, MediaProbeService.class));

        db = new AuraDb(this);
        ai = new AiRecommender(db);
        inspector = new SessionInspector(this, new ComponentName(this, MediaProbeService.class), db, ai);
        sync = new SyncManager(db);
        voice = new VoiceConcierge(db, ai, sync);

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Color.parseColor(BG_OBSIDIAN));
        setContentView(rootFrame);

        buildMainLayout();
        buildRightSlideDrawers();
        switchProfileAndLoad(db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE), true);
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
        return rightScrim != null && rightScrim.getVisibility() == View.VISIBLE;
    }

    private void switchProfileAndLoad(final String targetProfile, final boolean focusFirstPoster) {
        memProfile = targetProfile;
        showingWatchedList = false;
        showingVoiceResults = false;
        populateSideProfiles();
        setLoader(true, "Loading " + db.getProfileLabel(targetProfile) + "…");

        bgPool.submit(() -> {
            db.setSetting("active_profile", targetProfile);
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
        LinearLayout mainRow = new LinearLayout(this);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setClipChildren(false);

        sidePanel = buildCollapsibleLeftSidePanel();
        mainRow.addView(sidePanel, new LinearLayout.LayoutParams(
            SIDEBAR_COLLAPSED_W, LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout rightStage = new LinearLayout(this);
        rightStage.setOrientation(LinearLayout.VERTICAL);
        rightStage.setPadding(42, 32, 42, 24);
        rightStage.setClipChildren(false);
        rightStage.setClipToPadding(false);

        rightStage.addView(buildUnifiedTopBar());
        rightStage.addView(buildMinimalTitleRow());

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        carouselRow = new LinearLayout(this);
        carouselRow.setOrientation(LinearLayout.HORIZONTAL);
        carouselRow.setClipChildren(false);
        carouselRow.setClipToPadding(false);
        carouselRow.setPadding(8, 16, 28, 16);
        hsv.addView(carouselRow);
        rightStage.addView(hsv, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mainRow.addView(rightStage, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        rootFrame.addView(mainRow, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout buildCollapsibleLeftSidePanel() {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        side.setPadding(12, 28, 12, 24);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(BG_SIDEBAR));
        bg.setStroke(1, Color.parseColor("#1A1E2E"));
        side.setBackground(bg);

        brandLabel = makeText("A", 20f, IMDB_GOLD, FONT_DISPLAY, 1);
        brandLabel.setGravity(Gravity.CENTER);
        brandLabel.setPadding(6, 0, 6, 22);
        side.addView(brandLabel);

        profSectionHdr = makeText("PROFILES", 10f, "#6D7488", FONT_MEDIUM, 1);
        profSectionHdr.setLetterSpacing(0.08f);
        profSectionHdr.setPadding(8, 2, 8, 8);
        profSectionHdr.setVisibility(View.GONE);
        side.addView(profSectionHdr);

        sideProfileContainer = new LinearLayout(this);
        sideProfileContainer.setOrientation(LinearLayout.VERTICAL);
        side.addView(sideProfileContainer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        libSectionHdr = makeText("MENU", 10f, "#6D7488", FONT_MEDIUM, 1);
        libSectionHdr.setLetterSpacing(0.08f);
        libSectionHdr.setPadding(8, 18, 8, 8);
        libSectionHdr.setVisibility(View.GONE);
        side.addView(libSectionHdr);

        navTasteBtn = makeSideNavBtn("✎", "✎  Guide AI", false, this::openTasteGuideDrawer);
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

    private void setSidebarExpanded(boolean expand) {
        if (sidebarExpanded == expand && sidePanel != null) return;
        sidebarExpanded = expand;
        if (sidePanel == null) return;

        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) sidePanel.getLayoutParams();
        lp.width = expand ? SIDEBAR_EXPANDED_W : SIDEBAR_COLLAPSED_W;
        sidePanel.setPadding(expand ? 18 : 12, 28, expand ? 18 : 12, 24);
        sidePanel.setGravity(expand ? Gravity.START : Gravity.CENTER_HORIZONTAL);
        sidePanel.setLayoutParams(lp);

        brandLabel.setText(expand ? "AURA" : "A");
        brandLabel.setGravity(expand ? Gravity.START : Gravity.CENTER);
        profSectionHdr.setVisibility(expand ? View.VISIBLE : View.GONE);
        libSectionHdr.setVisibility(expand ? View.VISIBLE : View.GONE);

        updateSideButtonLabels();
    }

    private void updateSideButtonLabels() {
        if (sideProfileContainer != null) {
            for (int i = 0; i < sideProfileContainer.getChildCount() && i < PROFILE_IDS.length; i++) {
                String pid = PROFILE_IDS[i];
                String fullName = db.getProfileLabel(pid);
                String shortInitial = fullName.isEmpty() ? "P" : fullName.substring(0, 1).toUpperCase();
                boolean isAct = pid.equals(memProfile);
                TextView btn = (TextView) sideProfileContainer.getChildAt(i);
                btn.setText(sidebarExpanded ? ((isAct ? "●  " : "    ") + fullName) : shortInitial);
                btn.setGravity(sidebarExpanded ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
            }
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
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            final String pid = PROFILE_IDS[i];
            final boolean isAct = pid.equals(memProfile);
            String fullName = db.getProfileLabel(pid);
            String shortInitial = fullName.isEmpty() ? "P" : fullName.substring(0, 1).toUpperCase();
            TextView btn = makeSideNavBtn(
                shortInitial,
                (isAct ? "●  " : "    ") + fullName,
                isAct,
                () -> {
                    if (!pid.equals(memProfile)) {
                        switchProfileAndLoad(pid, false);
                    } else {
                        openTasteGuideDrawer();
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
    }

    private View buildUnifiedTopBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 22);

        searchInput = new EditText(this);
        searchInput.setHint("Search or ask Aura (e.g. 'sports drama' or 'sci-fi')…");
        searchInput.setHintTextColor(Color.parseColor("#6E758A"));
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(14f);
        searchInput.setTypeface(FONT_REGULAR);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setPadding(22, 11, 22, 11);
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
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(440, LinearLayout.LayoutParams.WRAP_CONTENT);
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
        fLp.setMargins(10, 0, 0, 0);
        row.addView(filterChipsRow, fLp);

        row.addView(makePillBtn("✎ Guide AI", false, this::openTasteGuideDrawer));

        statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.HORIZONTAL);
        statusBox.setGravity(Gravity.CENTER_VERTICAL);
        statusBox.setPadding(14, 8, 14, 8);
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(18, 18);
        spLp.setMargins(0, 0, 8, 0);
        statusBox.addView(spinner, spLp);
        statusLabel = makeText("", 12f, IMDB_GOLD, FONT_MEDIUM, 1);
        statusBox.addView(statusLabel);
        statusBox.setVisibility(View.GONE);
        row.addView(statusBox);

        return row;
    }

    private View buildMinimalTitleRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(6, 4, 6, 12);

        headerTitle = makeText("Select a Title", 30f, "#FFFFFF", FONT_DISPLAY, 1);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.setMargins(0, 0, 20, 0);
        row.addView(headerTitle, tLp);

        headerMeta = makeText("", 15f, "#8E95A8", FONT_REGULAR, 1);
        row.addView(headerMeta);
        return row;
    }

    private void buildRightSlideDrawers() {
        rightScrim = new FrameLayout(this);
        rightScrim.setBackgroundColor(Color.parseColor("#88040508"));
        rightScrim.setVisibility(View.GONE);
        rightScrim.setClickable(true);

        // 1. Right-Hand Slide-Over Show Details Drawer
        rightDetailsDrawer = new LinearLayout(this);
        rightDetailsDrawer.setOrientation(LinearLayout.VERTICAL);
        rightDetailsDrawer.setPadding(36, 42, 36, 36);
        GradientDrawable dBg = new GradientDrawable();
        dBg.setColor(Color.parseColor(BG_DRAWER));
        dBg.setStroke(2, Color.parseColor("#262C40"));
        rightDetailsDrawer.setBackground(dBg);
        rightDetailsDrawer.setVisibility(View.GONE);

        drawerTitle = makeText("", 28f, "#FFFFFF", FONT_DISPLAY, 2);
        rightDetailsDrawer.addView(drawerTitle);

        drawerMeta = makeText("", 14f, "#A6B0C6", FONT_MEDIUM, 1);
        drawerMeta.setPadding(0, 10, 0, 12);
        rightDetailsDrawer.addView(drawerMeta);

        drawerAvailableBadge = makeText("Available on Netflix", 13f, IMDB_GOLD, FONT_MEDIUM, 1);
        drawerAvailableBadge.setPadding(14, 6, 14, 6);
        GradientDrawable avBg = new GradientDrawable();
        avBg.setColor(Color.parseColor("#231F12"));
        avBg.setCornerRadius(8f);
        avBg.setStroke(1, Color.parseColor("#574919"));
        drawerAvailableBadge.setBackground(avBg);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        avLp.setMargins(0, 0, 0, 18);
        rightDetailsDrawer.addView(drawerAvailableBadge, avLp);

        // Truncated 3-line synopsis for clean readability
        drawerSynopsis = makeText("", 15f, "#D6DAE6", FONT_REGULAR, 3);
        drawerSynopsis.setLineSpacing(5f, 1.20f);
        rightDetailsDrawer.addView(drawerSynopsis);

        LinearLayout actionBtns = new LinearLayout(this);
        actionBtns.setOrientation(LinearLayout.HORIZONTAL);
        actionBtns.setGravity(Gravity.CENTER_VERTICAL);
        actionBtns.setPadding(0, 24, 0, 24);

        drawerOpenAppBtn = makePillBtn("▶  Open in App", true, null);
        drawerWatchedBtn = makePillBtn("✓  Watched", false, null);
        actionBtns.addView(drawerOpenAppBtn);
        actionBtns.addView(drawerWatchedBtn);
        rightDetailsDrawer.addView(actionBtns);

        drawerRatingLabel = makeText("RATE WATCHED SHOW (1–10)", 11f, "#8E95A8", FONT_MEDIUM, 1);
        drawerRatingLabel.setLetterSpacing(0.06f);
        drawerRatingLabel.setPadding(0, 6, 0, 10);
        rightDetailsDrawer.addView(drawerRatingLabel);

        drawerRatingRow = new LinearLayout(this);
        drawerRatingRow.setOrientation(LinearLayout.HORIZONTAL);
        drawerRatingRow.setGravity(Gravity.CENTER_VERTICAL);
        rightDetailsDrawer.addView(drawerRatingRow);

        TextView closeHint = makeText("Press BACK on remote to close", 12f, "#646B7E", FONT_REGULAR, 1);
        closeHint.setPadding(0, 28, 0, 0);
        rightDetailsDrawer.addView(closeHint);

        FrameLayout.LayoutParams detLp = new FrameLayout.LayoutParams(
            RIGHT_DRAWER_W, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        rightScrim.addView(rightDetailsDrawer, detLp);

        // 2. Right-Hand Slide-Over AI Taste Guide Drawer
        rightTasteDrawer = new LinearLayout(this);
        rightTasteDrawer.setOrientation(LinearLayout.VERTICAL);
        rightTasteDrawer.setPadding(36, 42, 36, 36);
        rightTasteDrawer.setBackground(dBg);
        rightTasteDrawer.setVisibility(View.GONE);

        tasteDrawerTitle = makeText("Guide AI Taste", 26f, "#FFFFFF", FONT_DISPLAY, 1);
        rightTasteDrawer.addView(tasteDrawerTitle);

        tasteDrawerSub = makeText("Tell Aura what you love watching (e.g. Sports, F1, Sci-Fi):", 14f, "#9AA2B8", FONT_REGULAR, 2);
        tasteDrawerSub.setPadding(0, 8, 0, 16);
        rightTasteDrawer.addView(tasteDrawerSub);

        tasteEditInput = new EditText(this);
        tasteEditInput.setHint("e.g. Sports dramas, Formula 1, mind-bending sci-fi…");
        tasteEditInput.setHintTextColor(Color.parseColor("#6E758A"));
        tasteEditInput.setTextColor(Color.WHITE);
        tasteEditInput.setTextSize(14f);
        tasteEditInput.setTypeface(FONT_REGULAR);
        tasteEditInput.setSingleLine(true);
        tasteEditInput.setPadding(18, 12, 18, 12);
        styleInputBox(tasteEditInput, false);
        tasteEditInput.setOnFocusChangeListener((v, hasFocus) -> styleInputBox(tasteEditInput, hasFocus));
        rightTasteDrawer.addView(tasteEditInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView presetHdr = makeText("QUICK-ADD INTERESTS", 11f, "#7C849B", FONT_MEDIUM, 1);
        presetHdr.setLetterSpacing(0.06f);
        presetHdr.setPadding(0, 20, 0, 10);
        rightTasteDrawer.addView(presetHdr);

        tasteTagsContainer = new LinearLayout(this);
        tasteTagsContainer.setOrientation(LinearLayout.VERTICAL);
        rightTasteDrawer.addView(tasteTagsContainer);

        LinearLayout tasteActions = new LinearLayout(this);
        tasteActions.setOrientation(LinearLayout.HORIZONTAL);
        tasteActions.setPadding(0, 24, 0, 0);
        tasteSaveBtn = makePillBtn("✓  Save & Curate Picks", true, this::saveTasteAndCurate);
        TextView tasteCancelBtn = makePillBtn("Close", false, this::closeRightDrawers);
        tasteActions.addView(tasteSaveBtn);
        tasteActions.addView(tasteCancelBtn);
        rightTasteDrawer.addView(tasteActions);

        FrameLayout.LayoutParams tstLp = new FrameLayout.LayoutParams(
            RIGHT_DRAWER_W, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        rightScrim.addView(rightTasteDrawer, tstLp);

        rootFrame.addView(rightScrim, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void openDetailsDrawer(final JSONObject item, final View posterView) {
        if (item == null) return;
        lastFocusedPoster = posterView;
        final String mainTitle = item.optString("series_title").isEmpty()
            ? item.optString("title")
            : item.optString("series_title");
        final String titleKey = AuraDb.normalizeTitleKey(mainTitle);
        final String appName = cleanAppName(item.optString("app", item.optString("app_name", "Streaming")));
        String rawImdb = item.optString("rating", "8.2").replace("★", "").trim();
        int rt = item.optInt("runtime_min", 48);
        int year = item.optInt("year", 2024);
        String genres = cleanGenres(item.optString("genres", "Drama"));

        String syn = item.optString("synopsis");
        String reason = item.optString("reason");
        String text = !syn.isEmpty() ? syn : reason;

        drawerTitle.setText(mainTitle);
        drawerMeta.setText("IMDb " + rawImdb + "/10   ·   " + year + "   ·   " + rt + " min   ·   " + genres);
        drawerAvailableBadge.setText("Available on " + appName);
        drawerSynopsis.setText(text);

        drawerOpenAppBtn.setText("▶  Open in " + appName);
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

        int currentScore = memUserRatings.getOrDefault(titleKey, item.optInt("user_rating", 0));
        populateRatingButtons(item, mainTitle, titleKey, currentScore);

        rightTasteDrawer.setVisibility(View.GONE);
        rightDetailsDrawer.setVisibility(View.VISIBLE);
        rightScrim.setVisibility(View.VISIBLE);

        // Smooth right-hand slide-in animation
        rightDetailsDrawer.setTranslationX(RIGHT_DRAWER_W);
        rightDetailsDrawer.animate()
            .translationX(0f)
            .setDuration(220)
            .setInterpolator(new DecelerateInterpolator())
            .start();

        drawerOpenAppBtn.requestFocus();
    }

    private void populateRatingButtons(final JSONObject item, final String mainTitle, final String titleKey, final int activeScore) {
        drawerRatingRow.removeAllViews();
        drawerRatingLabel.setText(activeScore > 0
            ? ("YOUR RATING: " + activeScore + " / 10  (SAVED)")
            : "RATE WATCHED SHOW (OUT OF 10)");

        for (int score = 1; score <= 10; score++) {
            final int sc = score;
            boolean isSel = (sc == activeScore);
            final TextView numBtn = makeText(String.valueOf(sc), 12f, isSel ? IMDB_GOLD : "#D0D4E0", FONT_MEDIUM, 1);
            numBtn.setGravity(Gravity.CENTER);
            numBtn.setPadding(11, 8, 11, 8);
            numBtn.setFocusable(true);
            numBtn.setClickable(true);
            stylePillBtn(numBtn, false, isSel);
            numBtn.setOnFocusChangeListener((v, hasFocus) -> stylePillBtn(numBtn, hasFocus, sc == activeScore));
            numBtn.setOnClickListener(v -> {
                memUserRatings.put(titleKey, sc);
                if (!memWatchedKeys.contains(titleKey)) {
                    memWatchedKeys.add(titleKey);
                    try {
                        item.put("user_rating", sc);
                        memWatchedList.put(item);
                    } catch (Exception ignored) {}
                }
                populateRatingButtons(item, mainTitle, titleKey, sc);
                drawerWatchedBtn.setText("✓  Watched (" + sc + "/10)");
                updateSideButtonLabels();
                bgPool.submit(() -> {
                    db.rateTitle(item, sc);
                    sync.syncWithHost();
                });
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 6, 0);
            drawerRatingRow.addView(numBtn, lp);
        }
    }

    private void openTasteGuideDrawer() {
        setSidebarExpanded(false);
        String profName = db.getProfileLabel(memProfile);
        String currentTaste = db.getProfileTaste(memProfile);
        tasteDrawerTitle.setText("Guide AI for " + profName);
        tasteDrawerSub.setText("Customize what " + profName + " likes watching so recommendations adapt:");
        tasteEditInput.setText(currentTaste);

        populateTastePresetRows();

        rightDetailsDrawer.setVisibility(View.GONE);
        rightTasteDrawer.setVisibility(View.VISIBLE);
        rightScrim.setVisibility(View.VISIBLE);

        rightTasteDrawer.setTranslationX(RIGHT_DRAWER_W);
        rightTasteDrawer.animate()
            .translationX(0f)
            .setDuration(220)
            .setInterpolator(new DecelerateInterpolator())
            .start();

        tasteSaveBtn.requestFocus();
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
        for (int i = 0; i < memRecos.length() && dynamicTags.size() < 8; i++) {
            JSONObject r = memRecos.optJSONObject(i);
            if (r == null) continue;
            for (String g : cleanGenres(r.optString("genres")).split(",")) {
                String gt = g.trim();
                if (!gt.isEmpty() && !dynamicTags.contains(gt) && dynamicTags.size() < 8) {
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
            if (row != null) row.addView(chip);
        }
    }

    private void saveTasteAndCurate() {
        final String newTaste = tasteEditInput.getText().toString().trim();
        final String prof = memProfile;
        closeRightDrawers();
        setLoader(true, "Curating for " + db.getProfileLabel(prof) + "…");

        bgPool.submit(() -> {
            db.setProfileTaste(prof, newTaste);
            // Clear old cached recos for this profile and regenerate with the new taste prompt
            JSONArray fresh = ai.getOrRefreshForProfile(prof, new JSONObject(), db.listHistory(prof), true);
            sync.syncWithHost();
            uiHandler.post(() -> {
                memRecos = fresh;
                showingVoiceResults = false;
                setLoader(false, "✓ Taste Updated");
                refreshAllSections(true);
            });
        });
    }

    private void closeRightDrawers() {
        if (rightScrim != null) {
            rightScrim.setVisibility(View.GONE);
        }
        if (rightDetailsDrawer != null) {
            rightDetailsDrawer.setVisibility(View.GONE);
        }
        if (rightTasteDrawer != null) {
            rightTasteDrawer.setVisibility(View.GONE);
        }
        if (lastFocusedPoster != null) {
            lastFocusedPoster.requestFocus();
        } else if (carouselRow != null && carouselRow.getChildCount() > 0) {
            carouselRow.getChildAt(0).requestFocus();
        }
    }

    private void updateHeaderForCard(JSONObject item) {
        if (item == null) return;
        String mainTitle = item.optString("series_title").isEmpty()
            ? item.optString("title")
            : item.optString("series_title");
        int rt = item.optInt("runtime_min", 45);
        int year = item.optInt("year", 2024);
        String genres = cleanGenres(item.optString("genres", "Drama"));

        headerTitle.setText(mainTitle);
        headerMeta.setText(year + "   ·   " + rt + " min   ·   " + genres);
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
        carouselRow.removeAllViews();
        JSONArray items = getFilteredItemsFromMemory();
        int limit = Math.min(items.length(), 20);
        for (int i = 0; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) {
                View tile = buildPurePosterTile(item);
                if (i == 0) {
                    tile.setOnKeyListener((v, keyCode, event) -> {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN) {
                            focusSideProfileById(memProfile);
                            return true;
                        }
                        return false;
                    });
                }
                carouselRow.addView(tile);
            }
        }
        if (items.length() > 0) {
            updateHeaderForCard(items.optJSONObject(0));
        } else {
            headerTitle.setText("No matching titles");
            headerMeta.setText("Select 'All' or update Guide AI");
        }
        if (focusFirstCard && carouselRow.getChildCount() > 0) {
            setSidebarExpanded(false);
            carouselRow.getChildAt(0).requestFocus();
        }
    }

    private View buildPurePosterTile(final JSONObject item) {
        final FrameLayout posterFrame = new FrameLayout(this);
        posterFrame.setPadding(4, 4, 4, 4);
        posterFrame.setFocusable(true);
        posterFrame.setClickable(true);
        stylePosterFrame(posterFrame, false);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(Color.parseColor("#141722"));
        ImageLoader.applyRoundedCorners(img, 14f);
        posterFrame.addView(img, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ImageLoader.load(this, item.optString("poster_url"), img);

        posterFrame.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) setSidebarExpanded(false);
            stylePosterFrame(posterFrame, hasFocus);
            posterFrame.animate()
                .scaleX(hasFocus ? 1.05f : 1.0f)
                .scaleY(hasFocus ? 1.05f : 1.0f)
                .setDuration(90)
                .start();
            if (hasFocus) updateHeaderForCard(item);
        });

        // Clicking OK slides open the Right-Hand Details Drawer
        posterFrame.setOnClickListener(v -> openDetailsDrawer(item, posterFrame));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(276, 414);
        lp.setMargins(0, 0, 24, 0);
        posterFrame.setLayoutParams(lp);
        return posterFrame;
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
        setLoader(true, "Refreshing picks…");
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
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor("#1D202B"));
        d.setCornerRadius(99f);
        d.setStroke(1, Color.parseColor(loading ? IMDB_GOLD : "#4CAF50"));
        statusBox.setBackground(d);
        statusLabel.setTextColor(Color.parseColor(loading ? IMDB_GOLD : "#81C784"));
    }

    private TextView makeSideNavBtn(String shortText, String fullText, final boolean isSelected, final Runnable onClick) {
        final TextView btn = makeText(sidebarExpanded ? fullText : shortText, 13f, "#D8DCE6", FONT_MEDIUM, 1);
        btn.setGravity(sidebarExpanded ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        btn.setPadding(12, 10, 12, 10);
        btn.setFocusable(true);
        btn.setClickable(true);
        styleSideNavBtn(btn, false, isSelected);
        btn.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) setSidebarExpanded(true);
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

    private void styleSideNavBtn(TextView btn, boolean focused, boolean selected) {
        if (btn == null) return;
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(10f);
        if (focused) {
            d.setColor(Color.parseColor(IMDB_GOLD));
            d.setStroke(2, Color.WHITE);
            btn.setTextColor(Color.BLACK);
        } else if (selected) {
            d.setColor(Color.parseColor(ACTIVE_FILTER_BG));
            d.setStroke(1, Color.parseColor(IMDB_GOLD));
            btn.setTextColor(Color.parseColor(IMDB_GOLD));
        } else {
            d.setColor(Color.TRANSPARENT);
            btn.setTextColor(Color.parseColor("#B8BECE"));
        }
        btn.setBackground(d);
    }

    private TextView makePillBtn(String text, final boolean isSelected, final Runnable onClick) {
        final TextView btn = makeText(text, 13f, "#E2E6F0", FONT_MEDIUM, 1);
        btn.setPadding(18, 9, 18, 9);
        btn.setFocusable(true);
        btn.setClickable(true);
        stylePillBtn(btn, false, isSelected);
        btn.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !isAnyDrawerOpen()) setSidebarExpanded(false);
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
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(99f);
        if (focused) {
            d.setColor(Color.parseColor(IMDB_GOLD));
            d.setStroke(3, Color.WHITE);
            btn.setTextColor(Color.BLACK);
        } else if (selected) {
            d.setColor(Color.parseColor(ACTIVE_FILTER_BG));
            d.setStroke(2, Color.parseColor(IMDB_GOLD));
            btn.setTextColor(Color.parseColor(IMDB_GOLD));
        } else {
            d.setColor(Color.parseColor(SURFACE_PILL));
            d.setStroke(1, Color.parseColor(BORDER_SUBTLE));
            btn.setTextColor(Color.parseColor("#D0D4E0"));
        }
        btn.setBackground(d);
    }

    private void stylePosterFrame(FrameLayout frame, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(16f);
        d.setColor(Color.TRANSPARENT);
        d.setStroke(focused ? 4 : 1, Color.parseColor(focused ? IMDB_GOLD : "#1F2330"));
        frame.setBackground(d);
    }

    private void styleInputBox(EditText et, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(99f);
        d.setColor(Color.parseColor(focused ? "#1E2232" : "#131621"));
        d.setStroke(focused ? 3 : 1, Color.parseColor(focused ? IMDB_GOLD : "#2A3045"));
        et.setBackground(d);
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
        tv.setLetterSpacing(0.015f);
        tv.setMaxLines(maxLines);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }
}
