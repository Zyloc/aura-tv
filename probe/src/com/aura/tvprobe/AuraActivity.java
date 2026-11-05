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
import android.view.Menu;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
    private static final String[][] PERSISTENT_FILTERS = {
        {"movies", "Movies"},
        {"series", "Series"},
        {"short", "< 30m"},
        {"scifi", "Sci-Fi"},
        {"horror", "Thriller & Horror"},
        {"comedy", "Comedy"},
        {"drama", "Drama"},
        {"action", "Action"},
        {"hotstar", "Hotstar"},
        {"netflix", "Netflix"},
        {"prime", "Prime Video"}
    };

    private static final String BG_OBSIDIAN = "#090A0F";
    private static final String SURFACE_PILL = "#161821";
    private static final String BORDER_SUBTLE = "#2A2E3D";
    private static final String ACTIVE_FILTER_BG = "#2B2310";
    private static final String IMDB_GOLD = "#F5C518";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgPool = Executors.newFixedThreadPool(3);

    private AuraDb db;
    private AiRecommender ai;
    private SessionInspector inspector;
    private SyncManager sync;
    private VoiceConcierge voice;

    // Lock-free in-memory UI state (zero SQLite calls on UI thread)
    private String memProfile = AuraDb.DEFAULT_PROFILE;
    private final List<String> memFilters = new ArrayList<>();
    private final Set<String> memWatchedKeys = new HashSet<>();
    private JSONArray memRecos = new JSONArray();
    private JSONArray memWatchedList = new JSONArray();
    private JSONObject memVoiceSession = new JSONObject();
    private boolean showingWatchedList = false;

    private LinearLayout rootBox;
    private EditText voiceInput;
    private TextView resetBtn;
    private LinearLayout statusBox;
    private ProgressBar spinner;
    private TextView statusLabel;
    private TextView profileDropdownBtn;
    private TextView watchedToggleBtn;

    private LinearLayout filterChipsRow;
    private TextView spotlightTitle;
    private TextView spotlightRating;
    private TextView spotlightMeta;
    private TextView spotlightAppBadge;
    private TextView spotlightDesc;
    private TextView spotlightHint;
    private LinearLayout followUpRow;
    private LinearLayout carouselRow;

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

        rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        rootBox.setBackgroundColor(Color.parseColor(BG_OBSIDIAN));
        rootBox.setPadding(52, 32, 52, 24);
        setContentView(rootBox);

        buildStaticShell();
        loadMemoryStateAsync(true);
    }

    private void loadMemoryStateAsync(final boolean initialLaunch) {
        bgPool.submit(() -> {
            final String prof = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
            final List<String> flt = db.getActiveFilters(prof);
            final Set<String> wKeys = db.getWatchedTitleKeys();
            final JSONArray recos = db.getProfileRecommendations(prof);
            final JSONArray wList = db.listManuallyWatched();
            final JSONObject vSess = db.getVoiceSession(prof);

            uiHandler.post(() -> {
                memProfile = prof;
                memFilters.clear();
                memFilters.addAll(flt);
                memWatchedKeys.clear();
                memWatchedKeys.addAll(wKeys);
                memRecos = recos;
                memWatchedList = wList;
                memVoiceSession = vSess;
                refreshAllSections(initialLaunch);
            });

            sync.syncWithHost();
            if (!db.isRecoCacheFresh(prof) || recos.length() == 0) {
                uiHandler.post(() -> setLoader(true, "Curating recommendations…"));
                inspector.syncAndBuildState(false);
                final JSONArray freshRecos = db.getProfileRecommendations(prof);
                uiHandler.post(() -> {
                    memRecos = freshRecos;
                    setLoader(false, "");
                    refreshCardsOnly(initialLaunch);
                });
            }
        });
    }

    private void buildStaticShell() {
        rootBox.removeAllViews();
        rootBox.addView(buildTopNavRow());
        rootBox.addView(buildSpotlightStage());
        rootBox.addView(buildFilterStripRow());
        rootBox.addView(buildSessionRefinementRow());

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        carouselRow = new LinearLayout(this);
        carouselRow.setOrientation(LinearLayout.HORIZONTAL);
        carouselRow.setClipChildren(false);
        carouselRow.setClipToPadding(false);
        carouselRow.setPadding(12, 16, 24, 12);
        hsv.addView(carouselRow);
        rootBox.addView(hsv, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private View buildTopNavRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 14);

        TextView brand = makeText("AURA", 14f, "#000000", true, 1);
        brand.setPadding(16, 7, 16, 7);
        GradientDrawable bBg = new GradientDrawable();
        bBg.setColor(Color.parseColor(IMDB_GOLD));
        bBg.setCornerRadius(6f);
        brand.setBackground(bBg);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bLp.setMargins(0, 0, 16, 0);
        row.addView(brand, bLp);

        voiceInput = new EditText(this);
        voiceInput.setHint("Ask Aura or search (e.g. 'mind-bending sci-fi' or 'dark thriller')…");
        voiceInput.setHintTextColor(Color.parseColor("#6E7385"));
        voiceInput.setTextColor(Color.WHITE);
        voiceInput.setTextSize(13f);
        voiceInput.setSingleLine(true);
        voiceInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        voiceInput.setPadding(22, 9, 22, 9);
        styleInputBox(voiceInput, false);
        voiceInput.setOnFocusChangeListener((v, hasFocus) -> styleInputBox(voiceInput, hasFocus));
        voiceInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submitVoiceInput();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inLp.setMargins(0, 0, 10, 0);
        row.addView(voiceInput, inLp);

        row.addView(makePillBtn("Search", false, this::submitVoiceInput));
        resetBtn = makePillBtn("Reset Session", false, () -> runSessionTurn("", true));
        row.addView(resetBtn);

        statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.HORIZONTAL);
        statusBox.setGravity(Gravity.CENTER_VERTICAL);
        statusBox.setPadding(14, 7, 14, 7);
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(18, 18);
        spLp.setMargins(0, 0, 8, 0);
        statusBox.addView(spinner, spLp);
        statusLabel = makeText("", 11f, IMDB_GOLD, true, 1);
        statusBox.addView(statusLabel);
        statusBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.setMargins(0, 0, 10, 0);
        row.addView(statusBox, stLp);

        profileDropdownBtn = makePillBtn("Profile ▾", false, this::showProfileDropdown);
        row.addView(profileDropdownBtn);

        watchedToggleBtn = makePillBtn("Watched", false, () -> {
            showingWatchedList = !showingWatchedList;
            stylePillBtn(watchedToggleBtn, watchedToggleBtn.hasFocus(), showingWatchedList);
            refreshCardsOnly(false);
        });
        row.addView(watchedToggleBtn);

        row.addView(makePillBtn("↻", false, this::triggerManualRefresh));
        return row;
    }

    private View buildFilterStripRow() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        filterChipsRow = new LinearLayout(this);
        filterChipsRow.setOrientation(LinearLayout.HORIZONTAL);
        filterChipsRow.setGravity(Gravity.CENTER_VERTICAL);
        filterChipsRow.setPadding(0, 0, 0, 14);
        hsv.addView(filterChipsRow);
        return hsv;
    }

    private View buildSpotlightStage() {
        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setPadding(4, 10, 4, 16);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        spotlightTitle = makeText("Select a Title", 32f, "#FFFFFF", true, 1);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.setMargins(0, 0, 18, 0);
        titleRow.addView(spotlightTitle, tLp);

        spotlightRating = makeText("★ 8.5", 13f, "#000000", true, 1);
        spotlightRating.setPadding(10, 4, 10, 4);
        GradientDrawable rBg = new GradientDrawable();
        rBg.setColor(Color.parseColor(IMDB_GOLD));
        rBg.setCornerRadius(6f);
        spotlightRating.setBackground(rBg);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.setMargins(0, 0, 12, 0);
        titleRow.addView(spotlightRating, rLp);

        spotlightAppBadge = makeText("Prime Video", 12f, "#E8EAED", true, 1);
        spotlightAppBadge.setPadding(12, 4, 12, 4);
        GradientDrawable aBg = new GradientDrawable();
        aBg.setColor(Color.parseColor("#1F2433"));
        aBg.setStroke(1, Color.parseColor("#3B4259"));
        aBg.setCornerRadius(6f);
        spotlightAppBadge.setBackground(aBg);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        aLp.setMargins(0, 0, 14, 0);
        titleRow.addView(spotlightAppBadge, aLp);

        spotlightMeta = makeText("", 13f, "#9AA0B0", false, 1);
        titleRow.addView(spotlightMeta);
        stage.addView(titleRow);

        spotlightDesc = makeText("", 14f, "#C8CCD8", false, 2);
        spotlightDesc.setPadding(0, 8, 120, 6);
        spotlightDesc.setMinLines(2);
        stage.addView(spotlightDesc);

        spotlightHint = makeText("Press OK on poster to open in app   •   Press DOWN on poster to mark Watched", 11f, "#7C8296", false, 1);
        stage.addView(spotlightHint);
        return stage;
    }

    private View buildSessionRefinementRow() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        followUpRow = new LinearLayout(this);
        followUpRow.setOrientation(LinearLayout.HORIZONTAL);
        followUpRow.setGravity(Gravity.CENTER_VERTICAL);
        followUpRow.setPadding(0, 0, 0, 6);
        hsv.addView(followUpRow);
        return hsv;
    }

    private void updateSpotlightForCard(JSONObject item) {
        if (item == null) return;
        String mainTitle = item.optString("series_title").isEmpty()
            ? item.optString("title")
            : item.optString("series_title");
        String appName = item.optString("app", item.optString("app_name", "Streaming")).split("·")[0].trim();
        String rat = item.optString("rating", "★ 8.2");
        int rt = item.optInt("runtime_min", 45);
        int year = item.optInt("year", 2024);
        String genres = item.optString("genres", "Drama")
            .replace("[", "").replace("]", "").replace("\"", "").replace(",", ", ").replaceAll("\\s+", " ").trim();
        String desc = item.optString("reason", item.optString("synopsis", ""));
        if (!item.optString("synopsis").isEmpty() && !desc.equals(item.optString("synopsis"))) {
            desc = desc + " — " + item.optString("synopsis");
        }

        spotlightTitle.setText(mainTitle);
        spotlightRating.setText(rat);
        spotlightAppBadge.setText(appName);
        spotlightMeta.setText(year + "   •   " + rt + " min   •   " + genres);
        spotlightDesc.setText(desc);
        spotlightHint.setText("Press OK to launch in " + appName + "   •   Press DOWN to mark Watched");
    }

    private void showProfileDropdown() {
        PopupMenu popup = new PopupMenu(this, profileDropdownBtn);
        Menu menu = popup.getMenu();
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            String pid = PROFILE_IDS[i];
            String label = (pid.equals(memProfile) ? "✓ " : "    ") + db.getProfileLabel(pid);
            menu.add(0, i, i, label);
        }
        popup.setOnMenuItemClickListener(item -> {
            final String chosen = PROFILE_IDS[item.getItemId()];
            memProfile = chosen;
            bgPool.submit(() -> {
                db.setSetting("active_profile", chosen);
                sync.syncWithHost();
            });
            loadMemoryStateAsync(false);
            profileDropdownBtn.requestFocus();
            return true;
        });
        popup.show();
    }

    private void refreshAllSections(boolean focusFirstCard) {
        profileDropdownBtn.setText(db.getProfileLabel(memProfile) + " ▾");
        watchedToggleBtn.setText(showingWatchedList ? "← Back" : ("Watched (" + memWatchedKeys.size() + ")"));
        stylePillBtn(watchedToggleBtn, watchedToggleBtn.hasFocus(), showingWatchedList);

        populateFilterChips();
        populateSessionRow();
        refreshCardsOnly(focusFirstCard);
    }

    private void populateFilterChips() {
        filterChipsRow.removeAllViews();
        for (String[] f : PERSISTENT_FILTERS) {
            final String fid = f[0];
            final String fLabel = f[1];
            boolean isAct = memFilters.contains(fid);
            final TextView chip = makePillBtn((isAct ? "✓ " : "") + fLabel, isAct, null);
            chip.setOnClickListener(v -> {
                toggleFilterInMemory(fid);
                boolean nowAct = memFilters.contains(fid);
                chip.setText((nowAct ? "✓ " : "") + fLabel);
                stylePillBtn(chip, chip.hasFocus(), nowAct);
                populateFilterChips();
                refreshCardsOnly(false);
                focusChipById(fid);
                if (!showingWatchedList && nowAct && getFilteredItemsFromMemory().length() < 4) {
                    runSessionTurnKeepFocus("Recommend critically acclaimed titles matching filters: " + memFilters.toString(), fid);
                }
            });
            chip.setTag("filter_" + fid);
            filterChipsRow.addView(chip);
        }

        if (!memFilters.isEmpty()) {
            TextView clearBtn = makePillBtn("✕ Clear", false, () -> {
                memFilters.clear();
                bgPool.submit(() -> {
                    db.clearFilters(memProfile);
                    sync.syncWithHost();
                });
                populateFilterChips();
                refreshCardsOnly(false);
            });
            filterChipsRow.addView(clearBtn);
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
        bgPool.submit(() -> {
            db.toggleFilter(prof, fid);
            sync.syncWithHost();
        });
    }

    private void populateSessionRow() {
        followUpRow.removeAllViews();
        JSONArray turns = memVoiceSession.optJSONArray("turns");
        boolean hasTurns = turns != null && turns.length() > 0;
        resetBtn.setVisibility(hasTurns ? View.VISIBLE : View.GONE);

        if (hasTurns) {
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, turns.length() - 4); i < turns.length(); i++) {
                JSONObject t = turns.optJSONObject(i);
                if (t != null && "user".equals(t.optString("role"))) {
                    if (sb.length() > 0) sb.append("  →  ");
                    sb.append("\"").append(t.optString("text")).append("\"");
                }
            }
            TextView crumb = makeText("Session: " + sb.toString(), 11f, IMDB_GOLD, true, 1);
            crumb.setMaxWidth(520);
            crumb.setEllipsize(TextUtils.TruncateAt.START);
            crumb.setPadding(0, 0, 14, 0);
            followUpRow.addView(crumb);
        }

        JSONArray chips = memVoiceSession.optJSONArray("follow_ups");
        if (chips != null) {
            for (int i = 0; i < Math.min(chips.length(), 4); i++) {
                final String prompt = chips.optString(i);
                TextView chip = makeSubtleRefinementChip("+ " + prompt, () -> runSessionTurn(prompt, false));
                followUpRow.addView(chip);
            }
        }
    }

    private void refreshCardsOnly(boolean focusFirstCard) {
        carouselRow.removeAllViews();
        JSONArray items = getFilteredItemsFromMemory();
        int limit = Math.min(items.length(), 22);
        for (int i = 0; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) carouselRow.addView(buildMinimalistPosterTile(item));
        }
        if (items.length() > 0) {
            updateSpotlightForCard(items.optJSONObject(0));
        }
        if (focusFirstCard && carouselRow.getChildCount() > 0) {
            View firstTile = carouselRow.getChildAt(0);
            if (firstTile instanceof LinearLayout && ((LinearLayout) firstTile).getChildCount() > 0) {
                ((LinearLayout) firstTile).getChildAt(0).requestFocus();
            }
        }
    }

    private View buildMinimalistPosterTile(final JSONObject item) {
        final String mainTitle = item.optString("series_title").isEmpty()
            ? item.optString("title")
            : item.optString("series_title");
        final String appName = item.optString("app", item.optString("app_name", "Streaming")).split("·")[0].trim();

        final LinearLayout tileWrap = new LinearLayout(this);
        tileWrap.setOrientation(LinearLayout.VERTICAL);
        tileWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        tileWrap.setClipChildren(false);
        tileWrap.setClipToPadding(false);

        // Frameless 2:3 Poster Frame (226 x 334 px — pressing OK opens directly in app!)
        final FrameLayout posterFrame = new FrameLayout(this);
        posterFrame.setPadding(4, 4, 4, 4);
        posterFrame.setFocusable(true);
        posterFrame.setClickable(true);
        posterFrame.setAlpha(0.76f);
        stylePosterFrame(posterFrame, false);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(Color.parseColor("#181B24"));
        ImageLoader.applyRoundedCorners(img, 12f);
        posterFrame.addView(img, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ImageLoader.load(this, item.optString("poster_url"), img);

        // Subtle top-right streaming badge on poster
        TextView badge = makeText(shortAppLabel(appName), 10f, "#FFFFFF", true, 1);
        badge.setPadding(8, 3, 8, 3);
        GradientDrawable bBg = new GradientDrawable();
        bBg.setColor(Color.parseColor("#CC0D1017"));
        bBg.setCornerRadius(6f);
        badge.setBackground(bBg);
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bLp.gravity = Gravity.TOP | Gravity.END;
        bLp.setMargins(8, 8, 8, 8);
        posterFrame.addView(badge, bLp);

        // Subtle bottom-left IMDb score pill on poster
        TextView star = makeText(item.optString("rating", "★ 8.2"), 10f, "#000000", true, 1);
        star.setPadding(7, 2, 7, 2);
        GradientDrawable sBg = new GradientDrawable();
        sBg.setColor(Color.parseColor(IMDB_GOLD));
        sBg.setCornerRadius(5f);
        star.setBackground(sBg);
        FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        sLp.gravity = Gravity.BOTTOM | Gravity.START;
        sLp.setMargins(8, 8, 8, 8);
        posterFrame.addView(star, sLp);

        // Secondary action below poster (reached by pressing DOWN from poster)
        final boolean isW = memWatchedKeys.contains(AuraDb.normalizeTitleKey(mainTitle));
        final TextView watchPill = makeText(isW ? "✕ Unwatch" : "✓ Watched", 10f, "#9AA0B0", true, 1);
        watchPill.setGravity(Gravity.CENTER);
        watchPill.setPadding(14, 5, 14, 5);
        watchPill.setFocusable(true);
        watchPill.setClickable(true);
        watchPill.setAlpha(0.0f); // Hidden until poster or pill is focused for pure minimalism!
        stylePillBtn(watchPill, false, isW);

        posterFrame.setOnFocusChangeListener((v, hasFocus) -> {
            stylePosterFrame(posterFrame, hasFocus);
            posterFrame.animate()
                .scaleX(hasFocus ? 1.07f : 1.0f)
                .scaleY(hasFocus ? 1.07f : 1.0f)
                .alpha(hasFocus ? 1.0f : 0.76f)
                .setDuration(130)
                .start();
            watchPill.setAlpha((hasFocus || watchPill.hasFocus()) ? 1.0f : 0.0f);
            if (hasFocus) updateSpotlightForCard(item);
        });

        posterFrame.setOnClickListener(v -> {
            String uri = item.optString("launch_uri");
            String pkg = item.optString("launch_pkg", item.optString("app_pkg"));
            if (pkg.isEmpty() || pkg.contains("smarttube")) pkg = AuraDb.resolveAppPackage(appName);
            TvOverlayManager.launchNativeStreamingApp(this, uri, pkg.isEmpty() ? appName : pkg, mainTitle);
        });

        watchPill.setOnFocusChangeListener((v, hasFocus) -> {
            stylePillBtn(watchPill, hasFocus, isW);
            watchPill.setAlpha((hasFocus || posterFrame.hasFocus()) ? 1.0f : 0.0f);
            posterFrame.setAlpha((hasFocus || posterFrame.hasFocus()) ? 1.0f : 0.76f);
            if (hasFocus) updateSpotlightForCard(item);
        });

        watchPill.setOnClickListener(v -> handleMarkWatchedInPlace(tileWrap, item, mainTitle));

        tileWrap.addView(posterFrame, new LinearLayout.LayoutParams(224, 332));
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wLp.setMargins(0, 10, 0, 0);
        tileWrap.addView(watchPill, wLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 22, 0);
        tileWrap.setLayoutParams(lp);
        return tileWrap;
    }

    private void handleMarkWatchedInPlace(View tileWrap, final JSONObject item, final String title) {
        int idx = carouselRow.indexOfChild(tileWrap);
        String key = AuraDb.normalizeTitleKey(title);
        final boolean nowWatched = !memWatchedKeys.contains(key);
        if (nowWatched) {
            memWatchedKeys.add(key);
            memWatchedList.put(item);
        } else {
            memWatchedKeys.remove(key);
        }

        watchedToggleBtn.setText(showingWatchedList ? "← Back" : ("Watched (" + memWatchedKeys.size() + ")"));
        setLoader(false, nowWatched ? ("✓ Marked '" + title + "' watched") : ("Unmarked '" + title + "'"));

        if (idx >= 0) {
            carouselRow.removeViewAt(idx);
            int remaining = carouselRow.getChildCount();
            if (remaining > 0) {
                int targetIdx = Math.min(idx, remaining - 1);
                View nextTile = carouselRow.getChildAt(targetIdx);
                if (nextTile instanceof LinearLayout && ((LinearLayout) nextTile).getChildCount() > 0) {
                    ((LinearLayout) nextTile).getChildAt(0).requestFocus();
                }
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
        JSONArray primary = (vr != null && vr.length() > 0) ? vr : memRecos;

        JSONArray pool = new JSONArray();
        Set<String> seenKeys = new HashSet<>();
        JSONArray[] sources = new JSONArray[]{primary, memRecos};
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
            if ("hotstar".equals(f) && !text.contains("hotstar") && !text.contains("disney") && !text.contains("jio")) return false;
            if ("netflix".equals(f) && !text.contains("netflix")) return false;
            if ("prime".equals(f) && !text.contains("prime")) return false;
        }
        return true;
    }

    private void submitVoiceInput() {
        String q = voiceInput.getText().toString().trim();
        if (q.isEmpty()) {
            voiceInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(voiceInput, InputMethodManager.SHOW_IMPLICIT);
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(voiceInput.getWindowToken(), 0);
        voiceInput.setText("");
        runSessionTurn(q, false);
    }

    private void runSessionTurn(final String query, final boolean reset) {
        showingWatchedList = false;
        setLoader(true, reset ? "Resetting…" : ("Curating: \"" + query + "\"…"));
        final String prof = memProfile;
        bgPool.submit(() -> {
            final JSONObject res = voice.handleTurn(prof, query, reset);
            final JSONArray recos = db.getProfileRecommendations(prof);
            uiHandler.post(() -> {
                memVoiceSession = res;
                memRecos = recos;
                int count = res.optJSONArray("results") != null ? res.optJSONArray("results").length() : 0;
                setLoader(false, reset ? "" : ("✓ " + count + " Picks"));
                refreshAllSections(true);
            });
        });
    }

    private void runSessionTurnKeepFocus(final String query, final String keepFilterId) {
        setLoader(true, "Curating matches…");
        final String prof = memProfile;
        bgPool.submit(() -> {
            final JSONObject res = voice.handleTurn(prof, query, false);
            uiHandler.post(() -> {
                memVoiceSession = res;
                setLoader(false, "");
                populateSessionRow();
                refreshCardsOnly(false);
                focusChipById(keepFilterId);
            });
        });
    }

    private void triggerManualRefresh() {
        setLoader(true, "Refreshing taste graph…");
        final String prof = memProfile;
        bgPool.submit(() -> {
            sync.syncWithHost();
            inspector.syncAndBuildState(true);
            final JSONArray freshRecos = db.getProfileRecommendations(prof);
            uiHandler.post(() -> {
                memRecos = freshRecos;
                setLoader(false, "✓ Updated");
                refreshAllSections(false);
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

    private TextView makePillBtn(String text, final boolean isSelected, final Runnable onClick) {
        final TextView btn = makeText(text, 12f, "#D8DCE6", true, 1);
        btn.setPadding(16, 7, 16, 7);
        btn.setFocusable(true);
        btn.setClickable(true);
        stylePillBtn(btn, false, isSelected);
        btn.setOnFocusChangeListener((v, hasFocus) -> stylePillBtn(btn, hasFocus, isSelected));
        if (onClick != null) btn.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 8, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private TextView makeSubtleRefinementChip(String text, final Runnable onClick) {
        final TextView btn = makeText(text, 11f, "#9AA0B0", false, 1);
        btn.setPadding(12, 5, 12, 5);
        btn.setFocusable(true);
        btn.setClickable(true);
        stylePillBtn(btn, false, false);
        btn.setOnFocusChangeListener((v, hasFocus) -> stylePillBtn(btn, hasFocus, false));
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
            btn.setTextColor(Color.parseColor("#C8CCD8"));
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
        d.setCornerRadius(99f);
        d.setColor(Color.parseColor(focused ? "#1E2230" : "#13151E"));
        d.setStroke(focused ? 3 : 1, Color.parseColor(focused ? IMDB_GOLD : "#2A2E3D"));
        et.setBackground(d);
    }

    private String shortAppLabel(String app) {
        String a = app.toLowerCase();
        if (a.contains("hotstar") || a.contains("disney") || a.contains("jio")) return "Hotstar";
        if (a.contains("netflix")) return "Netflix";
        if (a.contains("prime")) return "Prime";
        if (a.contains("apple")) return "Apple TV+";
        if (a.contains("sony")) return "SonyLIV";
        if (a.contains("zee")) return "ZEE5";
        return "Stream";
    }

    private TextView makeText(String txt, float sp, String hex, boolean bold, int maxLines) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextSize(sp);
        tv.setTextColor(Color.parseColor(hex));
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setMaxLines(maxLines);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }
}
