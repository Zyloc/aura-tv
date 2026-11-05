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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

public class AuraActivity extends Activity {
    private static final String[] PROFILE_IDS = {"member1", "partner", "member3", "kids", "family"};
    private static final String[][] PERSISTENT_FILTERS = {
        {"movies", "🎬 Movies"},
        {"series", "📺 Series"},
        {"short", "⏱️ <30m"},
        {"scifi", "🚀 Sci-Fi"},
        {"horror", "🧟 Horror/Thriller"},
        {"comedy", "😂 Comedy"},
        {"drama", "🎭 Drama"},
        {"action", "💥 Action"},
        {"hotstar", "Disney+ Hotstar"},
        {"netflix", "Netflix"},
        {"prime", "Prime Video"}
    };

    // Material Design 3 Dark + IMDb Gold tokens
    private static final String M3_BG = "#121212";
    private static final String M3_CARD = "#1E1E1E";
    private static final String M3_CARD_BORDER = "#333333";
    private static final String M3_CHIP_BG = "#252525";
    private static final String M3_CHIP_BORDER = "#444444";
    private static final String M3_CHIP_ACTIVE_BG = "#1B364D";
    private static final String M3_CHIP_ACTIVE_BORDER = "#64B5F6";
    private static final String IMDB_GOLD = "#F5C518";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private AuraDb db;
    private AiRecommender ai;
    private SessionInspector inspector;
    private SyncManager sync;
    private VoiceConcierge voice;

    private boolean showingWatchedList = false;
    private boolean isLoading = false;
    private String statusText = "";
    private JSONObject currentState = new JSONObject();

    private LinearLayout rootBox;
    private EditText voiceInput;
    private LinearLayout statusBox;
    private ProgressBar spinner;
    private TextView statusLabel;
    private TextView profileDropdownBtn;
    private TextView watchedToggleBtn;

    private LinearLayout filterChipsRow;
    private TextView sessionFlowLabel;
    private LinearLayout followUpRow;
    private LinearLayout carouselRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        startService(new Intent(this, MediaProbeService.class));
        db = new AuraDb(this);
        ai = new AiRecommender(db);
        inspector = new SessionInspector(this, new ComponentName(this, MediaProbeService.class), db, ai);
        sync = new SyncManager(db);
        voice = new VoiceConcierge(db, ai, sync);

        rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        rootBox.setBackgroundColor(Color.parseColor(M3_BG));
        rootBox.setPadding(44, 28, 44, 24);
        setContentView(rootBox);

        currentState = inspector.buildFastCachedState();
        buildStaticShell();
        refreshAllSections(true);

        new Thread(() -> {
            sync.syncWithHost();
            String prof = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
            if (!db.isRecoCacheFresh(prof) || db.getProfileRecommendations(prof).length() == 0) {
                final JSONObject fresh = inspector.syncAndBuildState(false);
                uiHandler.post(() -> {
                    currentState = fresh;
                    refreshCardsOnly(false);
                });
            }
        }).start();
    }

    private void buildStaticShell() {
        rootBox.removeAllViews();
        rootBox.addView(buildTopInputAndProfileRow());
        rootBox.addView(buildPersistentFilterRow());
        rootBox.addView(buildSessionFlowRow());

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        carouselRow = new LinearLayout(this);
        carouselRow.setOrientation(LinearLayout.HORIZONTAL);
        carouselRow.setPadding(2, 8, 2, 8);
        hsv.addView(carouselRow);
        rootBox.addView(hsv, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private View buildTopInputAndProfileRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 14);

        TextView brand = makeText("AURA", 15f, "#000000", true, 1);
        brand.setPadding(16, 8, 16, 8);
        GradientDrawable bBg = new GradientDrawable();
        bBg.setColor(Color.parseColor(IMDB_GOLD));
        bBg.setCornerRadius(8f);
        brand.setBackground(bBg);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bLp.setMargins(0, 0, 14, 0);
        row.addView(brand, bLp);

        voiceInput = new EditText(this);
        voiceInput.setHint("🎙️ Speak or type to search/refine (e.g. 'sci-fi movie or horror show')…");
        voiceInput.setHintTextColor(Color.parseColor("#8E8E93"));
        voiceInput.setTextColor(Color.WHITE);
        voiceInput.setTextSize(13f);
        voiceInput.setSingleLine(true);
        voiceInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        voiceInput.setPadding(20, 10, 20, 10);
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

        row.addView(makeM3Button("Ask", false, this::submitVoiceInput));
        row.addView(makeM3Button("Reset", false, () -> runSessionTurn("", true)));

        statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.HORIZONTAL);
        statusBox.setGravity(Gravity.CENTER_VERTICAL);
        statusBox.setPadding(14, 8, 14, 8);
        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(22, 22);
        spLp.setMargins(0, 0, 8, 0);
        statusBox.addView(spinner, spLp);
        statusLabel = makeText("", 12f, IMDB_GOLD, true, 1);
        statusBox.addView(statusLabel);
        statusBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.setMargins(0, 0, 10, 0);
        row.addView(statusBox, stLp);

        profileDropdownBtn = makeM3Button("👤 Profile ▾", false, this::showProfileDropdown);
        row.addView(profileDropdownBtn);

        watchedToggleBtn = makeM3Button("✓ Watched", false, () -> {
            showingWatchedList = !showingWatchedList;
            styleM3Button(watchedToggleBtn, watchedToggleBtn.hasFocus(), showingWatchedList);
            refreshCardsOnly(false);
        });
        row.addView(watchedToggleBtn);

        row.addView(makeM3Button("↻", false, this::triggerManualRefresh));
        return row;
    }

    private View buildPersistentFilterRow() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        filterChipsRow = new LinearLayout(this);
        filterChipsRow.setOrientation(LinearLayout.HORIZONTAL);
        filterChipsRow.setGravity(Gravity.CENTER_VERTICAL);
        filterChipsRow.setPadding(0, 0, 0, 10);
        hsv.addView(filterChipsRow);
        return hsv;
    }

    private View buildSessionFlowRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, 0, 0, 12);

        sessionFlowLabel = makeText("Session Flow: Ready", 12f, "#B0B0B8", true, 1);
        sessionFlowLabel.setMaxWidth(620);
        sessionFlowLabel.setEllipsize(TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams sfLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sfLp.setMargins(0, 0, 14, 0);
        box.addView(sessionFlowLabel, sfLp);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        followUpRow = new LinearLayout(this);
        followUpRow.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(followUpRow);
        box.addView(hsv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return box;
    }

    private void showProfileDropdown() {
        String active = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
        PopupMenu popup = new PopupMenu(this, profileDropdownBtn);
        Menu menu = popup.getMenu();
        for (int i = 0; i < PROFILE_IDS.length; i++) {
            String pid = PROFILE_IDS[i];
            String label = (pid.equals(active) ? "✓ " : "    ") + profileName(pid);
            menu.add(0, i, i, label);
        }
        popup.setOnMenuItemClickListener(item -> {
            String chosen = PROFILE_IDS[item.getItemId()];
            db.setSetting("active_profile", chosen);
            new Thread(() -> sync.syncWithHost()).start();
            currentState = inspector.buildFastCachedState();
            setLoader(false, "✓ Profile: " + profileName(chosen));
            refreshAllSections(false);
            profileDropdownBtn.requestFocus();
            return true;
        });
        popup.show();
    }

    private void refreshAllSections(boolean focusFirstCard) {
        String activeProf = currentState.optString("active_profile", AuraDb.DEFAULT_PROFILE);
        profileDropdownBtn.setText("👤 " + profileName(activeProf) + " ▾");
        int watchedCount = db.listManuallyWatched().length();
        watchedToggleBtn.setText(showingWatchedList ? "← Back to Picks" : ("✓ Watched (" + watchedCount + ")"));
        styleM3Button(watchedToggleBtn, watchedToggleBtn.hasFocus(), showingWatchedList);

        populateFilterChips(activeProf);
        populateSessionFlow(activeProf);
        refreshCardsOnly(focusFirstCard);
    }

    private void populateFilterChips(final String activeProf) {
        filterChipsRow.removeAllViews();
        List<String> active = db.getActiveFilters(activeProf);

        TextView lbl = makeText("Filters:", 12f, "#9E9E9E", true, 1);
        lbl.setPadding(0, 0, 10, 0);
        filterChipsRow.addView(lbl);

        for (String[] f : PERSISTENT_FILTERS) {
            final String fid = f[0];
            final String fLabel = f[1];
            boolean isAct = active.contains(fid);
            final TextView chip = makeM3Button((isAct ? "✓ " : "") + fLabel, isAct, null);
            chip.setOnClickListener(v -> {
                db.toggleFilter(activeProf, fid);
                boolean nowAct = db.isFilterActive(activeProf, fid);
                chip.setText((nowAct ? "✓ " : "") + fLabel);
                styleM3Button(chip, chip.hasFocus(), nowAct);
                populateSessionFlow(activeProf);
                refreshCardsOnly(false);
                // Keep focus on this exact chip — never reset cursor!
                chip.requestFocus();
                if (!showingWatchedList && nowAct && getFilteredItems().length() < 3) {
                    runSessionTurnKeepFocus("Show picks matching filters: " + db.getActiveFilters(activeProf).toString(), chip);
                } else {
                    new Thread(() -> sync.syncWithHost()).start();
                }
            });
            filterChipsRow.addView(chip);
        }

        if (!active.isEmpty()) {
            TextView clearBtn = makeM3Button("✕ Clear Filters", false, () -> {
                db.clearFilters(activeProf);
                populateFilterChips(activeProf);
                populateSessionFlow(activeProf);
                refreshCardsOnly(false);
                new Thread(() -> sync.syncWithHost()).start();
            });
            filterChipsRow.addView(clearBtn);
        }
    }

    private void populateSessionFlow(String activeProf) {
        JSONObject vs = db.getVoiceSession(activeProf);
        JSONArray turns = vs.optJSONArray("turns");
        List<String> activeFilters = db.getActiveFilters(activeProf);

        StringBuilder sb = new StringBuilder("Session: ");
        if (turns != null && turns.length() > 0) {
            int count = 0;
            for (int i = 0; i < turns.length(); i++) {
                JSONObject t = turns.optJSONObject(i);
                if (t != null && "user".equals(t.optString("role"))) {
                    if (count > 0) sb.append(" → ");
                    sb.append("\"").append(t.optString("text")).append("\"");
                    count++;
                }
            }
        } else {
            sb.append("All Recommendations");
        }
        if (!activeFilters.isEmpty()) {
            sb.append("  •  Filters: ").append(activeFilters.toString());
        }
        sessionFlowLabel.setText(sb.toString());

        followUpRow.removeAllViews();
        JSONArray chips = vs.optJSONArray("follow_ups");
        if (chips != null) {
            for (int i = 0; i < chips.length(); i++) {
                final String prompt = chips.optString(i);
                followUpRow.addView(makeM3Button("+ " + prompt, false, () -> runSessionTurn(prompt, false)));
            }
        }
    }

    private void refreshCardsOnly(boolean focusFirstCard) {
        carouselRow.removeAllViews();
        JSONArray items = getFilteredItems();
        int limit = Math.min(items.length(), 25);
        for (int i = 0; i < limit; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) carouselRow.addView(buildImdbCard(item));
        }
        if (focusFirstCard && carouselRow.getChildCount() > 0) {
            carouselRow.getChildAt(0).requestFocus();
        }
    }

    private View buildImdbCard(final JSONObject item) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(14, 14, 14, 16);
        styleImdbCard(card, false);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(Color.parseColor("#2A2A2A"));
        ImageLoader.applyRoundedCorners(img, 10f);
        // True 2:3 Movie Poster Aspect Ratio: 236 x 344 px
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(236, 344);
        imgLp.setMargins(0, 0, 0, 10);
        card.addView(img, imgLp);
        ImageLoader.load(this, item.optString("poster_url"), img);

        final String mainTitle = item.optString("series_title").isEmpty()
            ? item.optString("title")
            : item.optString("series_title");
        final String appName = item.optString("app", item.optString("app_name", "TV")).split("·")[0].trim();
        String rat = item.optString("rating", "★ 8.1");
        int rt = item.optInt("runtime_min", 45);

        // IMDb Rating + Runtime + App line
        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView starTv = makeText(rat, 12f, IMDB_GOLD, true, 1);
        TextView appTv = makeText("  •  " + rt + "m  •  " + shortAppLabel(appName), 11f, "#BDBDBD", true, 1);
        metaRow.addView(starTv);
        metaRow.addView(appTv);
        card.addView(metaRow);

        // Title
        TextView titleTv = makeText(mainTitle, 15f, "#FFFFFF", true, 1);
        titleTv.setPadding(0, 4, 0, 2);
        card.addView(titleTv);

        // Genres
        String genres = item.optString("genres", "Drama")
            .replace("[", "").replace("]", "").replace("\"", "").replace(",", ", ").replaceAll("\\s+", " ").trim();
        card.addView(makeText(genres, 11f, "#9E9E9E", false, 1));

        // 2-line concise description inside card (eliminates need for repetitive top hero!)
        String reason = item.optString("reason", item.optString("synopsis", ""));
        TextView descTv = makeText(reason, 11f, "#D0D0D0", false, 2);
        descTv.setPadding(0, 4, 0, 10);
        descTv.setMinLines(2);
        card.addView(descTv);

        // Action Buttons Row at bottom of IMDb card
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        final TextView openBtn = makeCardActionBtn("▶ Open", () -> {
            String uri = item.optString("launch_uri");
            if (uri.isEmpty()) uri = db.findNativeLaunchUri(mainTitle);
            String pkg = item.optString("launch_pkg", item.optString("app_pkg"));
            if (pkg.isEmpty() || pkg.contains("smarttube")) pkg = AuraDb.resolveAppPackage(appName);
            TvOverlayManager.launchNativeStreamingApp(this, uri, pkg.isEmpty() ? appName : pkg, mainTitle);
        });
        openBtn.setOnFocusChangeListener((v, hasFocus) -> {
            styleM3Button(openBtn, hasFocus, false);
            styleImdbCard(card, hasFocus);
        });
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        oLp.setMargins(0, 0, 8, 0);
        btnRow.addView(openBtn, oLp);

        final boolean isW = db.isTitleWatched(mainTitle);
        final TextView watchBtn = makeCardActionBtn(isW ? "✕ Unwatch" : "✓ Watched", null);
        watchBtn.setOnFocusChangeListener((v, hasFocus) -> {
            styleM3Button(watchBtn, hasFocus, isW);
            styleImdbCard(card, hasFocus);
        });
        watchBtn.setOnClickListener(v -> handleMarkWatchedInPlace(card, item, mainTitle));
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f);
        btnRow.addView(watchBtn, wLp);

        card.addView(btnRow);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(264, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 18, 0);
        card.setLayoutParams(lp);
        return card;
    }

    private void handleMarkWatchedInPlace(View cardView, JSONObject item, String title) {
        int idx = carouselRow.indexOfChild(cardView);
        boolean nowWatched = !db.isTitleWatched(title);
        db.markTitleWatched(item, nowWatched);
        // Push update to server immediately in background
        new Thread(() -> sync.syncWithHost()).start();

        int watchedCount = db.listManuallyWatched().length();
        watchedToggleBtn.setText(showingWatchedList ? "← Back to Picks" : ("✓ Watched (" + watchedCount + ")"));
        setLoader(false, nowWatched ? ("✓ Marked '" + title + "' watched") : ("Unmarked '" + title + "'"));

        // Remove card in-place and preserve focus on neighbor card (no cursor reset!)
        if (idx >= 0) {
            carouselRow.removeViewAt(idx);
            int remaining = carouselRow.getChildCount();
            if (remaining > 0) {
                int targetIdx = Math.min(idx, remaining - 1);
                View nextCard = carouselRow.getChildAt(targetIdx);
                if (nextCard != null) nextCard.requestFocus();
            }
        }
    }

    private JSONArray getFilteredItems() {
        String prof = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
        if (showingWatchedList) {
            return db.listManuallyWatched();
        }
        JSONObject vs = db.getVoiceSession(prof);
        JSONArray vr = vs != null ? vs.optJSONArray("results") : null;
        JSONArray recos = db.getProfileRecommendations(prof);
        JSONArray hist = db.listHistory(prof);

        JSONArray source = (vr != null && vr.length() > 0) ? vr : recos;
        JSONArray unwatched = db.filterOutWatched(source);

        List<String> filters = db.getActiveFilters(prof);
        if (filters.isEmpty()) return unwatched;

        // Combine voice session + recommendations + unwatched history when filtering locally
        JSONArray pool = new JSONArray();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        JSONArray[] sources = new JSONArray[] {
            vr != null ? db.filterOutWatched(vr) : new JSONArray(),
            db.filterOutWatched(recos),
            db.filterOutWatched(hist)
        };
        for (JSONArray arr : sources) {
            for (int i = 0; i < arr.length() && pool.length() < 90; i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null || item.optString("poster_url").isEmpty()) continue;
                String t = item.optString("series_title").isEmpty() ? item.optString("title") : item.optString("series_title");
                String k = AuraDb.normalizeTitleKey(t);
                if (!k.isEmpty() && !seenKeys.contains(k)) {
                    seenKeys.add(k);
                    pool.put(item);
                }
            }
        }

        JSONArray out = new JSONArray();
        for (int i = 0; i < pool.length() && out.length() < 24; i++) {
            JSONObject o = pool.optJSONObject(i);
            if (o != null && matchesAllFilters(o, filters)) out.put(o);
        }
        // Fallback if strict intersection is empty while waiting for AI filter turn: match any active filter
        if (out.length() == 0) {
            for (int i = 0; i < pool.length() && out.length() < 12; i++) {
                JSONObject o = pool.optJSONObject(i);
                if (o != null && matchesAnyFilter(o, filters)) out.put(o);
            }
        }
        return out.length() > 0 ? out : unwatched;
    }

    private boolean matchesAnyFilter(JSONObject o, List<String> filters) {
        for (String f : filters) {
            if (matchesAllFilters(o, java.util.Collections.singletonList(f))) return true;
        }
        return false;
    }

    private boolean matchesAllFilters(JSONObject o, List<String> filters) {
        int rt = o.optInt("runtime_min", 45);
        String text = (o.optString("genres") + " " + o.optString("reason") + " " + o.optString("synopsis") + " " + o.optString("app") + " " + o.optString("app_name")).toLowerCase();
        for (String f : filters) {
            if ("short".equals(f) && rt > 32) return false;
            if ("movies".equals(f) && rt < 75 && !text.contains("movie") && !text.contains("film")) return false;
            if ("series".equals(f) && rt > 80) return false;
            if ("scifi".equals(f) && !text.matches(".*(sci-fi|science|fantasy|mystery|space|future).*")) return false;
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
        setLoader(true, reset ? "Resetting session…" : ("Searching: \"" + query + "\"…"));
        new Thread(() -> {
            String prof = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
            final JSONObject res = voice.handleTurn(prof, query, reset);
            final JSONObject fast = inspector.buildFastCachedState();
            uiHandler.post(() -> {
                currentState = fast;
                int count = res.optJSONArray("results") != null ? res.optJSONArray("results").length() : 0;
                setLoader(false, reset ? "✓ Session Reset" : ("✓ " + count + " Results Ready"));
                refreshAllSections(true);
            });
        }).start();
    }

    private void runSessionTurnKeepFocus(final String query, final View keepFocusedView) {
        setLoader(true, "Filtering with AI…");
        new Thread(() -> {
            String prof = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
            final JSONObject res = voice.handleTurn(prof, query, false);
            final JSONObject fast = inspector.buildFastCachedState();
            uiHandler.post(() -> {
                currentState = fast;
                int count = res.optJSONArray("results") != null ? res.optJSONArray("results").length() : 0;
                setLoader(false, "✓ " + count + " Filtered Picks");
                populateSessionFlow(prof);
                refreshCardsOnly(false);
                if (keepFocusedView != null) keepFocusedView.requestFocus();
            });
        }).start();
    }

    private void triggerManualRefresh() {
        setLoader(true, "Syncing & refreshing…");
        new Thread(() -> {
            sync.syncWithHost();
            final JSONObject fresh = inspector.syncAndBuildState(true);
            uiHandler.post(() -> {
                currentState = fresh;
                setLoader(false, "✓ Refreshed");
                refreshAllSections(false);
            });
        }).start();
    }

    private void setLoader(boolean loading, String msg) {
        isLoading = loading;
        statusText = msg != null ? msg : "";
        if (statusText.isEmpty() && !loading) {
            statusBox.setVisibility(View.GONE);
            return;
        }
        statusBox.setVisibility(View.VISIBLE);
        spinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        statusLabel.setText(statusText);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(loading ? "#262210" : "#142E1F"));
        d.setCornerRadius(99f);
        d.setStroke(2, Color.parseColor(loading ? IMDB_GOLD : "#4CAF50"));
        statusBox.setBackground(d);
        statusLabel.setTextColor(Color.parseColor(loading ? IMDB_GOLD : "#81C784"));
        if (carouselRow != null) carouselRow.setAlpha(loading ? 0.4f : 1.0f);
    }

    private TextView makeM3Button(String text, final boolean isSelectedFilter, final Runnable onClick) {
        final TextView btn = makeText(text, 12f, "#E3E3E3", true, 1);
        btn.setPadding(16, 8, 16, 8);
        btn.setFocusable(true);
        btn.setClickable(true);
        styleM3Button(btn, false, isSelectedFilter);
        btn.setOnFocusChangeListener((v, hasFocus) -> styleM3Button(btn, hasFocus, isSelectedFilter));
        if (onClick != null) btn.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 8, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private TextView makeCardActionBtn(String text, final Runnable onClick) {
        final TextView btn = makeText(text, 11f, "#E3E3E3", true, 1);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(6, 8, 6, 8);
        btn.setFocusable(true);
        btn.setClickable(true);
        styleM3Button(btn, false, false);
        if (onClick != null) btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private void styleM3Button(TextView btn, boolean focused, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(99f);
        if (focused) {
            // Unmistakable High-Contrast Material 3 / IMDb Focus: Solid Gold bg + Black text + White border
            d.setColor(Color.parseColor(IMDB_GOLD));
            d.setStroke(3, Color.WHITE);
            btn.setTextColor(Color.BLACK);
        } else if (selected) {
            d.setColor(Color.parseColor(M3_CHIP_ACTIVE_BG));
            d.setStroke(2, Color.parseColor(M3_CHIP_ACTIVE_BORDER));
            btn.setTextColor(Color.parseColor("#E3F2FD"));
        } else {
            d.setColor(Color.parseColor(M3_CHIP_BG));
            d.setStroke(1, Color.parseColor(M3_CHIP_BORDER));
            btn.setTextColor(Color.parseColor("#E3E3E3"));
        }
        btn.setBackground(d);
    }

    private void styleInputBox(EditText et, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(99f);
        d.setColor(Color.parseColor(focused ? "#262626" : "#1A1A1A"));
        d.setStroke(focused ? 3 : 1, Color.parseColor(focused ? IMDB_GOLD : "#444444"));
        et.setBackground(d);
    }

    private void styleImdbCard(View card, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(14f);
        d.setColor(Color.parseColor(focused ? "#282828" : M3_CARD));
        d.setStroke(focused ? 3 : 1, Color.parseColor(focused ? IMDB_GOLD : M3_CARD_BORDER));
        card.setBackground(d);
    }

    private String shortAppLabel(String app) {
        String a = app.toLowerCase();
        if (a.contains("hotstar") || a.contains("disney")) return "Hotstar";
        if (a.contains("netflix")) return "Netflix";
        if (a.contains("prime")) return "Prime";
        if (a.contains("apple")) return "Apple TV+";
        if (a.contains("sony")) return "SonyLIV";
        if (a.contains("zee")) return "ZEE5";
        return "Open";
    }

    private String profileName(String pid) {
        return db.getProfileLabel(pid);
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
