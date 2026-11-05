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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AuraActivity extends Activity {
    private static final String[] PROFILE_IDS = {"member1", "partner", "member3", "kids", "family"};
    private static final String[][] CATEGORY_FILTERS = {
        {"all", "All"},
        {"movies", "Movies"},
        {"series", "Series"},
        {"short", "< 30m"},
        {"scifi", "Sci-Fi"},
        {"horror", "Thriller"},
        {"comedy", "Comedy"},
        {"drama", "Drama"},
        {"action", "Action"}
    };

    private static final String BG_OBSIDIAN = "#08090E";
    private static final String BG_SIDEBAR = "#0E1018";
    private static final String SURFACE_PILL = "#161924";
    private static final String BORDER_SUBTLE = "#262B3D";
    private static final String ACTIVE_FILTER_BG = "#2C2410";
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
    private JSONArray memRecos = new JSONArray();
    private JSONArray memWatchedList = new JSONArray();
    private JSONObject memVoiceSession = new JSONObject();
    private boolean showingWatchedList = false;
    private boolean showingVoiceResults = false;

    private FrameLayout rootFrame;
    private LinearLayout sideProfileContainer;
    private TextView navAllPicksBtn;
    private TextView navWatchedBtn;
    private TextView navRefreshBtn;

    private EditText searchInput;
    private TextView clearSearchBtn;
    private LinearLayout statusBox;
    private ProgressBar spinner;
    private TextView statusLabel;

    private LinearLayout filterChipsRow;
    private TextView headerTitle;
    private TextView headerRating;
    private TextView headerMeta;
    private LinearLayout carouselRow;

    // One-click Details Modal Overlay
    private FrameLayout detailsOverlay;
    private ImageView modalPoster;
    private TextView modalTitle;
    private TextView modalRating;
    private TextView modalMeta;
    private TextView modalAvailableBadge;
    private TextView modalSynopsis;
    private TextView modalOpenAppBtn;
    private TextView modalWatchedBtn;
    private TextView modalCloseBtn;
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
        buildDetailsModalOverlay();
        switchProfileAndLoad(db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE), true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && detailsOverlay != null && detailsOverlay.getVisibility() == View.VISIBLE) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                closeDetailsModal();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
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
            final JSONArray wList = db.listManuallyWatched();
            JSONArray recos = db.getProfileRecommendations(targetProfile);

            if (recos.length() == 0) {
                recos = ai.getFastCollaborativeForProfile(targetProfile);
            }
            final JSONArray initialRecos = recos;

            uiHandler.post(() -> {
                memProfile = targetProfile;
                memFilters.clear();
                memFilters.addAll(flt);
                memWatchedKeys.clear();
                memWatchedKeys.addAll(wKeys);
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

    private void focusSideProfileById(String pid) {
        if (sideProfileContainer == null) return;
        for (int i = 0; i < sideProfileContainer.getChildCount(); i++) {
            View child = sideProfileContainer.getChildAt(i);
            if (("prof_" + pid).equals(child.getTag())) {
                child.requestFocus();
                return;
            }
        }
    }

    private void buildMainLayout() {
        LinearLayout mainRow = new LinearLayout(this);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setClipChildren(false);

        mainRow.addView(buildLeftSidePanel(), new LinearLayout.LayoutParams(
            265, LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout rightStage = new LinearLayout(this);
        rightStage.setOrientation(LinearLayout.VERTICAL);
        rightStage.setPadding(38, 28, 38, 20);
        rightStage.setClipChildren(false);
        rightStage.setClipToPadding(false);

        rightStage.addView(buildSearchRow());
        rightStage.addView(buildFilterStripRow());
        rightStage.addView(buildMinimalTitleRow());

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        carouselRow = new LinearLayout(this);
        carouselRow.setOrientation(LinearLayout.HORIZONTAL);
        carouselRow.setClipChildren(false);
        carouselRow.setClipToPadding(false);
        carouselRow.setPadding(8, 14, 24, 14);
        hsv.addView(carouselRow);
        rightStage.addView(hsv, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mainRow.addView(rightStage, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        rootFrame.addView(mainRow, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private View buildLeftSidePanel() {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(22, 28, 22, 24);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(BG_SIDEBAR));
        bg.setStroke(1, Color.parseColor("#1C2030"));
        side.setBackground(bg);

        // Brand Logo
        TextView brand = makeText("AURA", 20f, IMDB_GOLD, FONT_DISPLAY, 1);
        brand.setLetterSpacing(0.10f);
        brand.setPadding(8, 0, 8, 20);
        side.addView(brand);

        // Profiles Header
        TextView profHdr = makeText("PROFILES", 11f, "#6D7488", FONT_MEDIUM, 1);
        profHdr.setLetterSpacing(0.08f);
        profHdr.setPadding(8, 4, 8, 8);
        side.addView(profHdr);

        sideProfileContainer = new LinearLayout(this);
        sideProfileContainer.setOrientation(LinearLayout.VERTICAL);
        side.addView(sideProfileContainer);

        // Library Header
        TextView libHdr = makeText("LIBRARY", 11f, "#6D7488", FONT_MEDIUM, 1);
        libHdr.setLetterSpacing(0.08f);
        libHdr.setPadding(8, 18, 8, 8);
        side.addView(libHdr);

        navAllPicksBtn = makeSideNavBtn("All Picks", true, () -> {
            showingWatchedList = false;
            showingVoiceResults = false;
            refreshAllSections(true);
        });
        side.addView(navAllPicksBtn);

        navWatchedBtn = makeSideNavBtn("Watched", false, () -> {
            showingWatchedList = !showingWatchedList;
            refreshAllSections(true);
        });
        side.addView(navWatchedBtn);

        navRefreshBtn = makeSideNavBtn("Refresh Picks", false, this::triggerManualRefresh);
        side.addView(navRefreshBtn);

        return side;
    }

    private void populateSideProfiles() {
        if (sideProfileContainer == null) return;
        sideProfileContainer.removeAllViews();
        for (final String pid : PROFILE_IDS) {
            final boolean isAct = pid.equals(memProfile);
            String label = (isAct ? "●  " : "    ") + db.getProfileLabel(pid);
            TextView btn = makeSideNavBtn(label, isAct, () -> {
                if (!pid.equals(memProfile)) {
                    switchProfileAndLoad(pid, false);
                }
            });
            btn.setTag("prof_" + pid);
            sideProfileContainer.addView(btn);
        }
    }

    private View buildSearchRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 16);

        searchInput = new EditText(this);
        searchInput.setHint("Search titles or ask Aura (e.g. 'dark sci-fi thriller' or 'funny movie')…");
        searchInput.setHintTextColor(Color.parseColor("#788096"));
        searchInput.setTextColor(Color.WHITE);
        searchInput.setTextSize(15f);
        searchInput.setTypeface(FONT_REGULAR);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setPadding(24, 13, 24, 13);
        styleInputBox(searchInput, false);
        searchInput.setOnFocusChangeListener((v, hasFocus) -> styleInputBox(searchInput, hasFocus));
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submitSearchInput();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inLp.setMargins(0, 0, 12, 0);
        row.addView(searchInput, inLp);

        row.addView(makePillBtn("Search", false, this::submitSearchInput));

        clearSearchBtn = makePillBtn("✕ Clear Search", false, () -> {
            showingVoiceResults = false;
            clearSearchBtn.setVisibility(View.GONE);
            refreshCardsOnly(true);
        });
        clearSearchBtn.setVisibility(View.GONE);
        row.addView(clearSearchBtn);

        statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.HORIZONTAL);
        statusBox.setGravity(Gravity.CENTER_VERTICAL);
        statusBox.setPadding(16, 10, 16, 10);
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(20, 20);
        spLp.setMargins(0, 0, 8, 0);
        statusBox.addView(spinner, spLp);
        statusLabel = makeText("", 13f, IMDB_GOLD, FONT_MEDIUM, 1);
        statusBox.addView(statusLabel);
        statusBox.setVisibility(View.GONE);
        row.addView(statusBox);

        return row;
    }

    private View buildFilterStripRow() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        filterChipsRow = new LinearLayout(this);
        filterChipsRow.setOrientation(LinearLayout.HORIZONTAL);
        filterChipsRow.setGravity(Gravity.CENTER_VERTICAL);
        filterChipsRow.setPadding(0, 0, 0, 16);
        hsv.addView(filterChipsRow);
        return hsv;
    }

    private View buildMinimalTitleRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(4, 2, 4, 8);

        headerTitle = makeText("Select a Title", 26f, "#FFFFFF", FONT_DISPLAY, 1);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.setMargins(0, 0, 16, 0);
        row.addView(headerTitle, tLp);

        headerRating = makeText("★ 8.5", 13f, "#000000", FONT_MEDIUM, 1);
        headerRating.setPadding(10, 4, 10, 4);
        GradientDrawable rBg = new GradientDrawable();
        rBg.setColor(Color.parseColor(IMDB_GOLD));
        rBg.setCornerRadius(6f);
        headerRating.setBackground(rBg);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.setMargins(0, 0, 14, 0);
        row.addView(headerRating, rLp);

        headerMeta = makeText("", 14f, "#9AA0B0", FONT_REGULAR, 1);
        row.addView(headerMeta);
        return row;
    }

    private void buildDetailsModalOverlay() {
        detailsOverlay = new FrameLayout(this);
        detailsOverlay.setBackgroundColor(Color.parseColor("#E006070C"));
        detailsOverlay.setVisibility(View.GONE);
        detailsOverlay.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(32, 32, 32, 32);
        GradientDrawable cBg = new GradientDrawable();
        cBg.setColor(Color.parseColor("#131622"));
        cBg.setCornerRadius(20f);
        cBg.setStroke(2, Color.parseColor("#2E344A"));
        card.setBackground(cBg);

        modalPoster = new ImageView(this);
        modalPoster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        modalPoster.setBackgroundColor(Color.parseColor("#1B1F2E"));
        ImageLoader.applyRoundedCorners(modalPoster, 14f);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(235, 355);
        pLp.setMargins(0, 0, 34, 0);
        card.addView(modalPoster, pLp);

        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);

        modalTitle = makeText("", 26f, "#FFFFFF", FONT_DISPLAY, 2);
        infoCol.addView(modalTitle);

        LinearLayout badgeRow = new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);
        badgeRow.setPadding(0, 10, 0, 10);

        modalRating = makeText("★ 8.5", 13f, "#000000", FONT_MEDIUM, 1);
        modalRating.setPadding(12, 4, 12, 4);
        GradientDrawable mrBg = new GradientDrawable();
        mrBg.setColor(Color.parseColor(IMDB_GOLD));
        mrBg.setCornerRadius(6f);
        modalRating.setBackground(mrBg);
        LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mrLp.setMargins(0, 0, 14, 0);
        badgeRow.addView(modalRating, mrLp);

        modalMeta = makeText("", 14f, "#B4BACB", FONT_MEDIUM, 1);
        badgeRow.addView(modalMeta);
        infoCol.addView(badgeRow);

        modalAvailableBadge = makeText("Available on Netflix", 13f, IMDB_GOLD, FONT_MEDIUM, 1);
        modalAvailableBadge.setPadding(14, 6, 14, 6);
        GradientDrawable avBg = new GradientDrawable();
        avBg.setColor(Color.parseColor("#232014"));
        avBg.setCornerRadius(8f);
        avBg.setStroke(1, Color.parseColor("#594B1A"));
        modalAvailableBadge.setBackground(avBg);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        avLp.setMargins(0, 2, 0, 14);
        infoCol.addView(modalAvailableBadge, avLp);

        modalSynopsis = makeText("", 14f, "#D8DCE8", FONT_REGULAR, 6);
        modalSynopsis.setLineSpacing(5f, 1.20f);
        LinearLayout.LayoutParams synLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        infoCol.addView(modalSynopsis, synLp);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, 16, 0, 0);

        modalOpenAppBtn = makePillBtn("▶  Open in App", true, null);
        modalWatchedBtn = makePillBtn("✓  Mark as Watched", false, null);
        modalCloseBtn = makePillBtn("←  Back", false, this::closeDetailsModal);

        actionRow.addView(modalOpenAppBtn);
        actionRow.addView(modalWatchedBtn);
        actionRow.addView(modalCloseBtn);
        infoCol.addView(actionRow);

        card.addView(infoCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(1140, 480);
        cardLp.gravity = Gravity.CENTER;
        detailsOverlay.addView(card, cardLp);

        rootFrame.addView(detailsOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void openDetailsModal(final JSONObject item, final View posterView) {
        if (item == null) return;
        lastFocusedPoster = posterView;
        final String mainTitle = item.optString("series_title").isEmpty()
            ? item.optString("title")
            : item.optString("series_title");
        final String appName = cleanAppName(item.optString("app", item.optString("app_name", "Streaming")));
        String rat = item.optString("rating", "★ 8.2");
        int rt = item.optInt("runtime_min", 48);
        int year = item.optInt("year", 2024);
        String genres = cleanGenres(item.optString("genres", "Drama"));

        String syn = item.optString("synopsis");
        String reason = item.optString("reason");
        String fullText = !syn.isEmpty() ? syn : reason;
        if (!reason.isEmpty() && !syn.isEmpty() && !syn.equals(reason)) {
            fullText = syn + "\n\nWhy watch: " + reason;
        }

        ImageLoader.load(this, item.optString("poster_url"), modalPoster);
        modalTitle.setText(mainTitle);
        modalRating.setText(rat);
        modalMeta.setText(year + "   •   " + rt + " min   •   " + genres);
        modalAvailableBadge.setText("Available on " + appName);
        modalSynopsis.setText(fullText);

        modalOpenAppBtn.setText("▶  Open in " + appName);
        modalOpenAppBtn.setOnClickListener(v -> {
            closeDetailsModal();
            String uri = item.optString("launch_uri");
            String pkg = item.optString("launch_pkg", item.optString("app_pkg"));
            if (pkg.isEmpty() || pkg.contains("smarttube")) pkg = AuraDb.resolveAppPackage(appName);
            TvOverlayManager.launchNativeStreamingApp(this, uri, pkg.isEmpty() ? appName : pkg, mainTitle);
        });

        final boolean isW = memWatchedKeys.contains(AuraDb.normalizeTitleKey(mainTitle));
        modalWatchedBtn.setText(isW ? "✕  Unmark Watched" : "✓  Mark as Watched");
        modalWatchedBtn.setOnClickListener(v -> {
            closeDetailsModal();
            handleMarkWatchedInPlace(posterView, item, mainTitle);
        });

        detailsOverlay.setVisibility(View.VISIBLE);
        modalOpenAppBtn.requestFocus();
    }

    private void closeDetailsModal() {
        if (detailsOverlay != null) {
            detailsOverlay.setVisibility(View.GONE);
        }
        if (lastFocusedPoster != null) {
            lastFocusedPoster.requestFocus();
        }
    }

    private void updateHeaderForCard(JSONObject item) {
        if (item == null) return;
        String mainTitle = item.optString("series_title").isEmpty()
            ? item.optString("title")
            : item.optString("series_title");
        String rat = item.optString("rating", "★ 8.2");
        int rt = item.optInt("runtime_min", 45);
        int year = item.optInt("year", 2024);
        String genres = cleanGenres(item.optString("genres", "Drama"));

        headerTitle.setText(mainTitle);
        headerRating.setText(rat);
        headerMeta.setText(year + "   •   " + rt + " min   •   " + genres);
    }

    private void refreshAllSections(boolean focusFirstCard) {
        populateSideProfiles();
        styleSideNavBtn(navAllPicksBtn, navAllPicksBtn.hasFocus(), !showingWatchedList);
        navWatchedBtn.setText("Watched (" + memWatchedKeys.size() + ")");
        styleSideNavBtn(navWatchedBtn, navWatchedBtn.hasFocus(), showingWatchedList);
        clearSearchBtn.setVisibility(showingVoiceResults ? View.VISIBLE : View.GONE);

        populateFilterChips();
        refreshCardsOnly(focusFirstCard);
    }

    private void populateFilterChips() {
        filterChipsRow.removeAllViews();
        for (int idx = 0; idx < CATEGORY_FILTERS.length; idx++) {
            String[] f = CATEGORY_FILTERS[idx];
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
            if (idx == 0) {
                chip.setOnKeyListener((v, keyCode, event) -> {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN) {
                        focusSideProfileById(memProfile);
                        return true;
                    }
                    return false;
                });
            }
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
                View tile = buildMinimalistPosterTile(item);
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
            headerMeta.setText("Try selecting 'All' filters");
        }
        if (focusFirstCard && carouselRow.getChildCount() > 0) {
            carouselRow.getChildAt(0).requestFocus();
        }
    }

    private View buildMinimalistPosterTile(final JSONObject item) {
        final FrameLayout posterFrame = new FrameLayout(this);
        posterFrame.setPadding(4, 4, 4, 4);
        posterFrame.setFocusable(true);
        posterFrame.setClickable(true);
        stylePosterFrame(posterFrame, false);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(Color.parseColor("#161924"));
        ImageLoader.applyRoundedCorners(img, 12f);
        posterFrame.addView(img, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ImageLoader.load(this, item.optString("poster_url"), img);

        // ONLY Star Rating on poster — clean, high usability, zero app tag clutter
        TextView star = makeText(item.optString("rating", "★ 8.2"), 12f, "#000000", FONT_MEDIUM, 1);
        star.setPadding(9, 3, 9, 3);
        GradientDrawable sBg = new GradientDrawable();
        sBg.setColor(Color.parseColor(IMDB_GOLD));
        sBg.setCornerRadius(6f);
        star.setBackground(sBg);
        FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        sLp.gravity = Gravity.BOTTOM | Gravity.START;
        sLp.setMargins(10, 10, 10, 10);
        posterFrame.addView(star, sLp);

        posterFrame.setOnFocusChangeListener((v, hasFocus) -> {
            stylePosterFrame(posterFrame, hasFocus);
            posterFrame.animate()
                .scaleX(hasFocus ? 1.05f : 1.0f)
                .scaleY(hasFocus ? 1.05f : 1.0f)
                .setDuration(90)
                .start();
            if (hasFocus) updateHeaderForCard(item);
        });

        // One click opens rich Details & Streaming Modal
        posterFrame.setOnClickListener(v -> openDetailsModal(item, posterFrame));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(268, 402);
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

        navWatchedBtn.setText("Watched (" + memWatchedKeys.size() + ")");
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
            if ("scifi".equals(f) && !text.matches(".*(sci-fi|science|fantasy|space|future|dystop).*")) return false;
            if ("horror".equals(f) && !text.matches(".*(horror|thriller|dark|suspense|mystery).*")) return false;
            if ("comedy".equals(f) && !text.contains("comedy") && !text.contains("sitcom") && !text.contains("funny")) return false;
            if ("drama".equals(f) && !text.contains("drama") && !text.contains("romance")) return false;
            if ("action".equals(f) && !text.matches(".*(action|crime|adventure).*")) return false;
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
        setLoader(true, "Searching: \"" + query + "\"…");
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

    private TextView makeSideNavBtn(String text, final boolean isSelected, final Runnable onClick) {
        final TextView btn = makeText(text, 13f, "#D8DCE6", FONT_MEDIUM, 1);
        btn.setPadding(14, 10, 14, 10);
        btn.setFocusable(true);
        btn.setClickable(true);
        styleSideNavBtn(btn, false, isSelected);
        btn.setOnFocusChangeListener((v, hasFocus) -> styleSideNavBtn(btn, hasFocus, isSelected));
        btn.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.getAction() == KeyEvent.ACTION_DOWN) {
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
        final TextView btn = makeText(text, 14f, "#E2E6F0", FONT_MEDIUM, 1);
        btn.setPadding(20, 10, 20, 10);
        btn.setFocusable(true);
        btn.setClickable(true);
        stylePillBtn(btn, false, isSelected);
        btn.setOnFocusChangeListener((v, hasFocus) -> stylePillBtn(btn, hasFocus, isSelected));
        if (onClick != null) btn.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 10, 0);
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
        d.setCornerRadius(15f);
        d.setColor(Color.TRANSPARENT);
        d.setStroke(focused ? 4 : 1, Color.parseColor(focused ? IMDB_GOLD : "#222634"));
        frame.setBackground(d);
    }

    private void styleInputBox(EditText et, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(14f);
        d.setColor(Color.parseColor(focused ? "#1E2232" : "#141722"));
        d.setStroke(focused ? 3 : 1, Color.parseColor(focused ? IMDB_GOLD : "#2E344A"));
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
