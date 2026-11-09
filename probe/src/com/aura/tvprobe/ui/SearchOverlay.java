package com.aura.tvprobe;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimalist Full-Screen Search & Follow-Up View for Aura TV.
 * Everything AI does (multi-turn context, watch history filtering, graph traversal)
 * happens silently behind the scenes.
 * UI consists of ONLY:
 * 1. One clean top bar: [ Search / Ask follow-up... ] + 3-4 subtle follow-up pills + [ Reset ] + [ Close ]
 * 2. Clean 5-column Cinema Poster Grid (artwork + title + year, zero walls of text).
 */
public final class SearchOverlay {

    private SearchOverlay() { }

    private static final int TAG_LOADING   = 0x5E_0001;
    private static final int TAG_RESULTS   = 0x5E_0002;
    private static final int TAG_CHIPS     = 0x5E_0003;
    private static final int TAG_CLOSE     = 0x5E_0005;
    private static final int TAG_SCROLL    = 0x5E_0006;
    private static final int TAG_INPUT_BOX = 0x5E_0007;
    private static final int TAG_EDIT_TEXT = 0x5E_0008;
    private static final int TAG_RESET_BTN = 0x5E_000A;
    private static final int TAG_HINT_IDX  = 0x7F0B_000B;
    private static final int TAG_LOADING_PANEL = 0x5E_000C;
    private static final int TAG_LOADING_TEXT  = 0x5E_000D;
    private static final int TAG_MIC_BTN       = 0x5E_000E;

    private static final int COLS     = 6;
    private static final int POSTER_W = 132;
    private static final int POSTER_H = 204;

    public static final String[] MULTILINGUAL_HINTS = {
        "Search movies, shows, or ask by mood\u2026",
        "Ask AI \u00B7 'Dark sci-fi like Severance'",
        "Ask AI \u00B7 'Gripping crime thrillers on Netflix'"
    };

    public static List<String> getHintsForProfile(AuraDb db, String profileId) {
        List<String> hints = new ArrayList<>();
        for (String h : MULTILINGUAL_HINTS) hints.add(h);
        if (db == null) return hints;
        String langs = (db.getSetting("preferred_langs", "English") + " "
            + db.getProfileTaste(profileId) + " "
            + db.getActiveFilters(profileId).toString()).toLowerCase();
        appendRegionalHints(hints, langs);
        return hints;
    }

    private static void appendRegionalHints(List<String> hints, String langs) {
        if (langs.contains("hindi")) {
            hints.add("\u0939\u093F\u0902\u0926\u0940 \u092E\u0947\u0902 \u092A\u0942\u091B\u0947\u0902 \u00B7 '\u092E\u093F\u0930\u094D\u091C\u093C\u093E\u092A\u0941\u0930 \u091C\u0948\u0938\u0940 \u0915\u094D\u0930\u093E\u0907\u092E \u0925\u094D\u0930\u093F\u0932\u0930'");
        }
        if (langs.contains("tamil")) {
            hints.add("\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD \u00B7 'Top rated Tamil crime thrillers'");
        }
        if (langs.contains("telugu")) {
            hints.add("\u0C24\u0C46\u0C32\u0C41\u0C17\u0C41 \u00B7 'High IMDb Telugu suspense movies'");
        }
    }

    public interface SearchCallback {
        void onResultClicked(JSONObject item);
        void onFollowUpClicked(String query);
        void onResetConversation();
        void onDismiss();
        default void onVoiceSearchClicked() {}
    }

    public static FrameLayout build(Context ctx, SearchCallback cb) {
        FrameLayout root = new FrameLayout(ctx);
        root.setVisibility(View.GONE);
        root.setBackgroundColor(GlassTheme.BG_AMBIENT);
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        root.setClickable(true);
        root.addView(buildContent(ctx, root, cb));
        return root;
    }

    public static void show(FrameLayout overlay, Context ctx, boolean focusInputBar) {
        overlay.setVisibility(View.VISIBLE);
        animateIn(overlay);
        startMultilingualHintCycle(overlay);
        if (focusInputBar) {
            View inputBox = overlay.findViewWithTag(TAG_INPUT_BOX);
            if (inputBox != null) inputBox.post(inputBox::requestFocus);
        } else {
            focusFirstPoster(overlay);
        }
    }

    private static void startMultilingualHintCycle(FrameLayout overlay) {
        EditText et = findTagged(overlay, TAG_EDIT_TEXT);
        if (et == null) return;
        Runnable[] holder = new Runnable[1];
        holder[0] = () -> tickMultilingualHint(overlay, et, holder[0]);
        et.postDelayed(holder[0], 3600);
    }

    private static void tickMultilingualHint(FrameLayout overlay, EditText et, Runnable self) {
        if (overlay.getVisibility() != View.VISIBLE) return;
        View resetBtn = overlay.findViewWithTag(TAG_RESET_BTN);
        boolean inThread = resetBtn != null && resetBtn.getVisibility() == View.VISIBLE;
        if (!inThread && !et.hasFocus() && et.getText().length() == 0) {
            cycleHintOnEditText(overlay, et);
        }
        et.postDelayed(self, 3600);
    }

    private static void cycleHintOnEditText(FrameLayout overlay, EditText et) {
        AuraDb db = AuraDb.getInstance(overlay.getContext());
        List<String> hints = getHintsForProfile(db, db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE));
        Object tagObj = et.getTag(TAG_HINT_IDX);
        int idx = (tagObj instanceof Integer) ? ((Integer) tagObj + 1) % hints.size() : 0;
        et.setTag(TAG_HINT_IDX, idx);
        fadeToNextHint(et, hints.get(idx));
    }

    private static void fadeToNextHint(EditText et, String nextHint) {
        et.animate().alpha(0.25f).setDuration(220).withEndAction(() -> {
            et.setHint(nextHint);
            et.animate().alpha(1f).setDuration(260).start();
        }).start();
    }

    public static void dismiss(FrameLayout overlay) {
        EditText et = findTagged(overlay, TAG_EDIT_TEXT);
        if (et != null) {
            hideKeyboard(overlay.getContext(), et);
            et.setFocusable(false);
            et.setFocusableInTouchMode(false);
        }
        animateOut(overlay);
    }

    public static boolean isVisible(FrameLayout overlay) {
        return overlay.getVisibility() == View.VISIBLE;
    }

    public static void setLoading(FrameLayout overlay, boolean loading, String message) {
        View v = overlay.findViewWithTag(TAG_LOADING);
        if (v != null) v.setVisibility(loading ? View.VISIBLE : View.GONE);
        TextView txt = findTagged(overlay, TAG_LOADING_TEXT);
        if (txt != null && message != null && !message.isEmpty()) txt.setText(message);
        animateLoadingPanel(overlay.findViewWithTag(TAG_LOADING_PANEL), loading);
        View gridRows = overlay.findViewWithTag(TAG_RESULTS);
        if (gridRows != null) {
            gridRows.animate().alpha(loading ? 0.08f : 1f).setDuration(180).start();
        }
    }

    private static void animateLoadingPanel(View panel, boolean loading) {
        if (panel == null) return;
        if (loading) {
            panel.setVisibility(View.VISIBLE);
            panel.animate().alpha(1f).setDuration(160).start();
        } else {
            panel.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> panel.setVisibility(View.GONE)).start();
        }
    }

    public static void setConversationTurns(FrameLayout overlay, JSONArray turns, Context ctx) {
        EditText et = findTagged(overlay, TAG_EDIT_TEXT);
        View resetBtn = overlay.findViewWithTag(TAG_RESET_BTN);
        String latestUserQuery = extractLatestUserQuery(turns);
        int userTurnCount = countUserTurns(turns);
        updateConversationInputHint(et, latestUserQuery);
        if (resetBtn != null) {
            resetBtn.setVisibility(userTurnCount > 0 ? View.VISIBLE : View.GONE);
        }
    }

    private static String extractLatestUserQuery(JSONArray turns) {
        String latest = "";
        if (turns == null) return latest;
        for (int i = 0; i < turns.length(); i++) {
            JSONObject t = turns.optJSONObject(i);
            if (t != null && "user".equalsIgnoreCase(t.optString("role"))) {
                String q = t.optString("text", "").trim();
                if (!q.isEmpty()) latest = q;
            }
        }
        return latest;
    }

    private static int countUserTurns(JSONArray turns) {
        int count = 0;
        if (turns == null) return 0;
        for (int i = 0; i < turns.length(); i++) {
            JSONObject t = turns.optJSONObject(i);
            if (t != null && "user".equalsIgnoreCase(t.optString("role"))
                    && !t.optString("text", "").trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static void updateConversationInputHint(EditText et, String latestUserQuery) {
        if (et == null || et.hasFocus()) return;
        et.setText("");
        et.setHint(!latestUserQuery.isEmpty()
            ? (latestUserQuery + "  \u00B7  Ask follow-up in any language\u2026")
            : MULTILINGUAL_HINTS[0]);
    }

    public static void setResults(FrameLayout overlay, JSONArray results,
                                  Context ctx, SearchCallback cb) {
        LinearLayout gridRows = findTagged(overlay, TAG_RESULTS);
        if (gridRows == null) return;
        populatePosterGrid(overlay, gridRows, results, ctx, cb);
        if (overlay.getVisibility() == View.VISIBLE) {
            focusFirstPoster(overlay);
        }
    }

    public static void setFollowUps(FrameLayout overlay, List<String> chips,
                                    SearchCallback cb, Context ctx) {
        LinearLayout row = findTagged(overlay, TAG_CHIPS);
        if (row == null) return;
        populateChips(overlay, row, chips, cb, ctx);
    }

    public static void addMessage(FrameLayout overlay, String sender,
                                  String text, Context ctx) {
        // Minimalist UI: AI reasoning is applied silently to the ranked posters without a text banner
    }

    private static LinearLayout buildContent(Context ctx, FrameLayout root, SearchCallback cb) {
        LinearLayout col = createContentColumn(ctx);
        col.addView(buildMinimalTopBar(ctx, root, cb));
        col.addView(buildBodyFrame(ctx), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private static FrameLayout buildBodyFrame(Context ctx) {
        FrameLayout bodyFrame = new FrameLayout(ctx);
        bodyFrame.setClipChildren(false);
        bodyFrame.setClipToPadding(false);
        bodyFrame.addView(buildResultsScroll(ctx), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bodyFrame.addView(buildLoadingBanner(ctx));
        return bodyFrame;
    }

    private static LinearLayout createContentColumn(Context ctx) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 6));
        col.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return col;
    }

    private static LinearLayout buildLoadingBanner(Context ctx) {
        LinearLayout col = AgenticOrbView.buildFloatingLoaderColumn(ctx, TAG_LOADING_TEXT, 0);
        col.setTag(TAG_LOADING_PANEL);
        col.setVisibility(View.GONE);
        col.setAlpha(0f);
        return col;
    }

    // ── Single Minimalist Top Bar (Search Input + Follow-Up Pills + Close) ─

    private static LinearLayout buildMinimalTopBar(Context ctx, FrameLayout root, SearchCallback cb) {
        LinearLayout bar = createTopBarRow(ctx);
        LinearLayout inputBox = buildSearchInputPill(ctx, cb);
        LinearLayout.LayoutParams ibLp = GlassTheme.wrapParams();
        ibLp.rightMargin = dp(ctx, 8);
        bar.addView(inputBox, ibLp);
        TextView micBtn = addVoiceMicToBar(ctx, bar, cb);
        LinearLayout chipsRow = addChipsScrollToBar(ctx, bar);
        TextView resetBtn = addResetButtonToBar(ctx, bar, cb);
        TextView closeBtn = addCloseButtonToBar(ctx, bar, cb);
        bindTopBarDpad(root, inputBox, micBtn, chipsRow, resetBtn, closeBtn, cb);
        return bar;
    }

    private static TextView addVoiceMicToBar(Context ctx, LinearLayout bar, SearchCallback cb) {
        TextView micBtn = VoiceSearchHelper.buildMicButton(ctx, () -> {
            if (cb != null) cb.onVoiceSearchClicked();
        });
        micBtn.setTag(TAG_MIC_BTN);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36));
        mLp.rightMargin = dp(ctx, 12);
        bar.addView(micBtn, mLp);
        return micBtn;
    }

    private static LinearLayout createTopBarRow(Context ctx) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setPadding(0, 0, 0, dp(ctx, 14));
        return bar;
    }

    // 1. Flagship AI Search / Follow-Up Input Pill
    private static LinearLayout buildSearchInputPill(Context ctx, SearchCallback cb) {
        LinearLayout inputBox = createInputBoxContainer(ctx);
        TextView aiBadge = createAiPillBadge(ctx);
        EditText et = createSearchEditText(ctx);
        TextView icon = createSearchMagnifierIcon(ctx);
        assembleInputPillViews(ctx, inputBox, aiBadge, et, icon);
        bindInputPillInteractions(ctx, inputBox, et, aiBadge, icon, cb);
        return inputBox;
    }

    private static LinearLayout createInputBoxContainer(Context ctx) {
        LinearLayout inputBox = new LinearLayout(ctx);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setTag(TAG_INPUT_BOX);
        inputBox.setFocusable(true);
        inputBox.setClickable(true);
        inputBox.setBackground(GlassTheme.focusRounded(
            Color.argb(195, 20, 26, 38), Color.argb(48, 255, 255, 255), dp(ctx, 1), dp(ctx, 19)));
        inputBox.setPadding(dp(ctx, 7), dp(ctx, 5), dp(ctx, 6), dp(ctx, 5));
        return inputBox;
    }

    private static TextView createAiPillBadge(Context ctx) {
        TextView aiBadge = GlassTheme.makeText(ctx, "\u2726 AI", 9.5f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        aiBadge.setGravity(Gravity.CENTER);
        aiBadge.setPadding(dp(ctx, 9), dp(ctx, 3.5f), dp(ctx, 9), dp(ctx, 3.5f));
        aiBadge.setBackground(GlassTheme.solidRounded(Color.argb(38, 255, 255, 255), dp(ctx, 12)));
        return aiBadge;
    }

    private static EditText createSearchEditText(Context ctx) {
        EditText et = new EditText(ctx);
        et.setTag(TAG_EDIT_TEXT);
        et.setHint(MULTILINGUAL_HINTS[0]);
        et.setHintTextColor(GlassTheme.TEXT_SECONDARY);
        et.setTextColor(GlassTheme.TEXT_PRIMARY);
        et.setTextSize(11.5f);
        configureSearchEditTextInput(ctx, et);
        return et;
    }

    private static void configureSearchEditTextInput(Context ctx, EditText et) {
        et.setSingleLine(true);
        et.setBackground(null);
        et.setPadding(0, 0, dp(ctx, 8), 0);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        et.setFocusable(false);
        et.setFocusableInTouchMode(false);
    }

    // Right Circular Magnifying Glass Icon inside search pill
    private static TextView createSearchMagnifierIcon(Context ctx) {
        TextView icon = GlassTheme.makeText(ctx, "\u2315", 13.5f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(GlassTheme.solidRounded(Color.argb(38, 255, 255, 255), dp(ctx, 13)));
        return icon;
    }

    private static void assembleInputPillViews(Context ctx, LinearLayout inputBox,
                                               TextView aiBadge, EditText et, TextView icon) {
        LinearLayout.LayoutParams abLp = GlassTheme.wrapParams();
        abLp.rightMargin = dp(ctx, 10);
        inputBox.addView(aiBadge, abLp);
        inputBox.addView(et, new LinearLayout.LayoutParams(dp(ctx, 288), ViewGroup.LayoutParams.WRAP_CONTENT));
        inputBox.addView(createInlineSearchSpinner(ctx));
        inputBox.addView(icon, new LinearLayout.LayoutParams(dp(ctx, 26), dp(ctx, 26)));
    }

    // Subtle inline spinner inside the input pill when searching
    private static ProgressBar createInlineSearchSpinner(Context ctx) {
        ProgressBar pb = new ProgressBar(ctx);
        int pbSize = dp(ctx, 14);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(pbSize, pbSize);
        pbLp.rightMargin = dp(ctx, 8);
        pb.setLayoutParams(pbLp);
        pb.getIndeterminateDrawable().setTint(Color.WHITE);
        pb.setTag(TAG_LOADING);
        pb.setVisibility(View.GONE);
        return pb;
    }

    private static void bindInputPillInteractions(Context ctx, LinearLayout inputBox, EditText et,
                                                  TextView aiBadge, TextView icon, SearchCallback cb) {
        Runnable activateTyping = () -> activateEditTextTyping(ctx, et);
        inputBox.setOnClickListener(v -> activateTyping.run());
        et.setOnClickListener(v -> activateTyping.run());
        et.setOnEditorActionListener((v, actionId, ev) -> handleSearchEditorAction(ctx, inputBox, et, cb));
        et.setOnKeyListener((v, code, ev) -> handleSearchEditTextBack(ctx, inputBox, et, code, ev));
        inputBox.setOnFocusChangeListener((v, hasFocus) ->
            updateInputPillFocus(ctx, v, aiBadge, icon, hasFocus));
    }

    private static void activateEditTextTyping(Context ctx, EditText et) {
        et.setFocusableInTouchMode(true);
        et.setFocusable(true);
        et.requestFocus();
        et.setSelection(et.getText().length());
        showKeyboard(ctx, et);
    }

    private static boolean handleSearchEditorAction(Context ctx, LinearLayout inputBox,
                                                    EditText et, SearchCallback cb) {
        String q = et.getText().toString().trim();
        deactivateEditTextTyping(ctx, et);
        if (!q.isEmpty() && cb != null) {
            et.setText("");
            cb.onFollowUpClicked(q);
        } else {
            inputBox.requestFocus();
        }
        return true;
    }

    private static void deactivateEditTextTyping(Context ctx, EditText et) {
        hideKeyboard(ctx, et);
        et.setFocusable(false);
        et.setFocusableInTouchMode(false);
    }

    private static boolean handleSearchEditTextBack(Context ctx, LinearLayout inputBox,
                                                    EditText et, int code, KeyEvent ev) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BACK) {
            hideKeyboard(ctx, et);
            et.setFocusable(false);
            et.setFocusableInTouchMode(false);
            inputBox.requestFocus();
            return true;
        }
        return false;
    }

    private static void updateInputPillFocus(Context ctx, View v, TextView aiBadge,
                                             TextView icon, boolean hasFocus) {
        float r = dp(ctx, 19);
        v.setBackground(hasFocus
            ? GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV, Color.WHITE, dp(ctx, 2.5f), r)
            : GlassTheme.focusRounded(Color.argb(195, 20, 26, 38), Color.argb(48, 255, 255, 255), dp(ctx, 1), r));
        int pillBg = hasFocus ? Color.WHITE : Color.argb(38, 255, 255, 255);
        int pillFg = hasFocus ? GlassTheme.TEXT_ON_LIGHT : GlassTheme.TEXT_PRIMARY;
        aiBadge.setBackground(GlassTheme.solidRounded(pillBg, dp(ctx, 12)));
        aiBadge.setTextColor(pillFg);
        icon.setBackground(GlassTheme.solidRounded(pillBg, dp(ctx, 13)));
        icon.setTextColor(pillFg);
    }

    // 2. Inline Follow-Up Pills (in the same single top bar!)
    private static LinearLayout addChipsScrollToBar(Context ctx, LinearLayout bar) {
        HorizontalScrollView chipsScroll = new HorizontalScrollView(ctx);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setFocusable(false);
        LinearLayout chipsRow = createChipsRow(ctx);
        chipsScroll.addView(chipsRow);
        LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        csLp.rightMargin = dp(ctx, 10);
        bar.addView(chipsScroll, csLp);
        return chipsRow;
    }

    private static LinearLayout createChipsRow(Context ctx) {
        LinearLayout chipsRow = new LinearLayout(ctx);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        chipsRow.setTag(TAG_CHIPS);
        return chipsRow;
    }

    // 3. Subtle Reset Pill (only visible when a thread is active)
    private static TextView addResetButtonToBar(Context ctx, LinearLayout bar, SearchCallback cb) {
        TextView resetBtn = GlassTheme.makeGlassButton(ctx, "\u21BA Reset");
        resetBtn.setTag(TAG_RESET_BTN);
        resetBtn.setTextSize(10.5f);
        resetBtn.setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6));
        resetBtn.setVisibility(View.GONE);
        resetBtn.setOnClickListener(v -> { if (cb != null) cb.onResetConversation(); });
        LinearLayout.LayoutParams rLp = GlassTheme.wrapParams();
        rLp.rightMargin = dp(ctx, 8);
        bar.addView(resetBtn, rLp);
        return resetBtn;
    }

    // 4. Minimalist Close Pill
    private static TextView addCloseButtonToBar(Context ctx, LinearLayout bar, SearchCallback cb) {
        TextView closeBtn = GlassTheme.makeGlassButton(ctx, "\u2715");
        closeBtn.setTag(TAG_CLOSE);
        closeBtn.setTextSize(11f);
        closeBtn.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        closeBtn.setOnClickListener(v -> { if (cb != null) cb.onDismiss(); });
        bar.addView(closeBtn, GlassTheme.wrapParams());
        return closeBtn;
    }

    private static void bindTopBarDpad(FrameLayout root, View inputBox, View micBtn, LinearLayout chipsRow,
                                       View resetBtn, View closeBtn, SearchCallback cb) {
        inputBox.setOnKeyListener((v, code, ev) ->
            handleInputBoxDpad(root, micBtn, cb, code, ev));
        micBtn.setOnKeyListener((v, code, ev) ->
            handleMicBtnDpad(root, inputBox, chipsRow, resetBtn, closeBtn, cb, code, ev));
        resetBtn.setOnKeyListener((v, code, ev) ->
            handleResetBtnDpad(root, micBtn, chipsRow, closeBtn, cb, code, ev));
        closeBtn.setOnKeyListener((v, code, ev) ->
            handleCloseBtnDpad(root, micBtn, chipsRow, resetBtn, cb, code, ev));
    }

    private static boolean handleInputBoxDpad(FrameLayout root, View micBtn, SearchCallback cb, int code, KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK) { if (cb != null) cb.onDismiss(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) { focusFirstPoster(root); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return micBtn.requestFocus();
        return false;
    }

    private static boolean handleMicBtnDpad(FrameLayout root, View inputBox, LinearLayout chipsRow, View resetBtn,
                                            View closeBtn, SearchCallback cb, int code, KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK) { if (cb != null) cb.onDismiss(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) { focusFirstPoster(root); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) return inputBox.requestFocus();
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (chipsRow.getChildCount() > 0) chipsRow.getChildAt(0).requestFocus();
            else if (resetBtn.getVisibility() == View.VISIBLE) resetBtn.requestFocus();
            else closeBtn.requestFocus();
            return true;
        }
        return false;
    }

    private static boolean handleResetBtnDpad(FrameLayout root, View inputBox, LinearLayout chipsRow,
                                              View closeBtn, SearchCallback cb, int code, KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK) { if (cb != null) cb.onDismiss(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) { focusFirstPoster(root); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            int n = chipsRow.getChildCount();
            (n > 0 ? chipsRow.getChildAt(n - 1) : inputBox).requestFocus();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { closeBtn.requestFocus(); return true; }
        return false;
    }

    private static boolean handleCloseBtnDpad(FrameLayout root, View inputBox, LinearLayout chipsRow,
                                              View resetBtn, SearchCallback cb, int code, KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK) { if (cb != null) cb.onDismiss(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) { focusFirstPoster(root); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (resetBtn.getVisibility() == View.VISIBLE) resetBtn.requestFocus();
            else if (chipsRow.getChildCount() > 0) chipsRow.getChildAt(chipsRow.getChildCount() - 1).requestFocus();
            else inputBox.requestFocus();
            return true;
        }
        return false;
    }

    private static void populateChips(FrameLayout overlay, LinearLayout row,
                                      List<String> aiChips, SearchCallback cb, Context ctx) {
        row.removeAllViews();
        List<String> list = buildFollowUpChipList(aiChips);
        for (int i = 0; i < list.size(); i++) {
            row.addView(buildFollowUpChipView(overlay, row, list.get(i), i, list.size(), cb, ctx));
        }
    }

    private static List<String> buildFollowUpChipList(List<String> aiChips) {
        List<String> list = new ArrayList<>();
        if (aiChips != null) {
            for (String c : aiChips) {
                if (c != null && !c.trim().isEmpty() && list.size() < 3) list.add(c.trim());
            }
        }
        for (String f : new String[]{"Only on Netflix", "Shorter under 2h", "IMDb 8.0+"}) {
            if (list.size() < 3 && !containsIgnoreCase(list, f)) list.add(f);
        }
        return list;
    }

    private static boolean containsIgnoreCase(List<String> list, String target) {
        for (String s : list) {
            if (s.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private static TextView buildFollowUpChipView(FrameLayout overlay, LinearLayout row, String text,
                                                  int idx, int count, SearchCallback cb, Context ctx) {
        TextView chip = GlassTheme.makeGlassButton(ctx, text);
        chip.setTextSize(10.5f);
        chip.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.setMargins(0, 0, dp(ctx, 7), 0);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> { if (cb != null) cb.onFollowUpClicked(text); });
        chip.setOnKeyListener((v, code, ev) -> handleChipKey(overlay, row, idx, count, cb, code, ev));
        return chip;
    }

    private static boolean handleChipKey(FrameLayout overlay, LinearLayout row, int idx, int count,
                                         SearchCallback cb, int code, KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK) { if (cb != null) cb.onDismiss(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) { focusFirstPoster(overlay); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) return moveChipFocusLeft(overlay, row, idx);
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return moveChipFocusRight(overlay, row, idx, count);
        return false;
    }

    private static boolean moveChipFocusLeft(FrameLayout overlay, LinearLayout row, int idx) {
        if (idx > 0) {
            row.getChildAt(idx - 1).requestFocus();
        } else {
            View micBtn = overlay.findViewWithTag(TAG_MIC_BTN);
            View target = micBtn != null ? micBtn : overlay.findViewWithTag(TAG_INPUT_BOX);
            if (target != null) target.requestFocus();
        }
        return true;
    }

    private static boolean moveChipFocusRight(FrameLayout overlay, LinearLayout row, int idx, int count) {
        if (idx < count - 1) {
            row.getChildAt(idx + 1).requestFocus();
            return true;
        }
        View resetBtn = overlay.findViewWithTag(TAG_RESET_BTN);
        View closeBtn = overlay.findViewWithTag(TAG_CLOSE);
        if (resetBtn != null && resetBtn.getVisibility() == View.VISIBLE) resetBtn.requestFocus();
        else if (closeBtn != null) closeBtn.requestFocus();
        return true;
    }

    // ── Minimalist 5-Column Cinema Poster Grid ───────────────────────────

    private static ScrollView buildResultsScroll(Context ctx) {
        RailBuilder.SmoothVerticalScrollView sv = new RailBuilder.SmoothVerticalScrollView(ctx);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        sv.setVerticalScrollBarEnabled(false);
        sv.setFocusable(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setTag(TAG_SCROLL);
        sv.addView(createGridRowsContainer(ctx), GlassTheme.matchWrapParams());
        return sv;
    }

    private static LinearLayout createGridRowsContainer(Context ctx) {
        LinearLayout gridRows = new LinearLayout(ctx);
        gridRows.setOrientation(LinearLayout.VERTICAL);
        gridRows.setClipChildren(false);
        gridRows.setClipToPadding(false);
        gridRows.setPadding(0, dp(ctx, 6), dp(ctx, 0), dp(ctx, 6));
        gridRows.setTag(TAG_RESULTS);
        return gridRows;
    }

    private static void populatePosterGrid(FrameLayout overlay, LinearLayout gridRows,
                                           JSONArray results, Context ctx, SearchCallback cb) {
        gridRows.removeAllViews();
        if (results == null || results.length() == 0) return;
        int n = Math.min(results.length(), COLS * 2);
        int totalRows = (n + COLS - 1) / COLS;
        for (int r = 0; r < totalRows; r++) {
            LinearLayout rowLayout = buildPosterGridRow(overlay, gridRows, results, r, totalRows, n, ctx, cb);
            animateGridRowIn(rowLayout, gridRows, r, ctx);
        }
    }

    private static LinearLayout buildPosterGridRow(FrameLayout overlay, LinearLayout gridRows,
                                                   JSONArray results, int r, int totalRows, int n,
                                                   Context ctx, SearchCallback cb) {
        LinearLayout rowLayout = createPosterRowContainer(ctx);
        for (int c = 0; c < COLS; c++) {
            int idx = r * COLS + c;
            addPosterCellOrSpacer(overlay, gridRows, rowLayout, results, r, c, totalRows, idx, n, ctx, cb);
        }
        return rowLayout;
    }

    private static LinearLayout createPosterRowContainer(Context ctx) {
        LinearLayout rowLayout = new LinearLayout(ctx);
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);
        rowLayout.setClipChildren(false);
        rowLayout.setClipToPadding(false);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(ctx, 10);
        rowLayout.setLayoutParams(rowLp);
        return rowLayout;
    }

    private static void addPosterCellOrSpacer(FrameLayout overlay, LinearLayout gridRows, LinearLayout rowLayout,
                                              JSONArray results, int r, int c, int totalRows,
                                              int idx, int n, Context ctx, SearchCallback cb) {
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
            0, idx < n ? ViewGroup.LayoutParams.WRAP_CONTENT : 1, 1f);
        if (c < COLS - 1) cellLp.rightMargin = dp(ctx, 12);
        JSONObject item = (idx < n) ? results.optJSONObject(idx) : null;
        if (item != null) {
            LinearLayout card = buildMinimalPosterCard(overlay, gridRows, r, c, totalRows, item, ctx, cb);
            rowLayout.addView(card, cellLp);
        } else {
            rowLayout.addView(new View(ctx), cellLp);
        }
    }

    private static void animateGridRowIn(LinearLayout rowLayout, LinearLayout gridRows, int r, Context ctx) {
        rowLayout.setAlpha(0f);
        rowLayout.setTranslationY(dp(ctx, 14));
        gridRows.addView(rowLayout);
        rowLayout.animate().alpha(1f).translationY(0f).setStartDelay(r * 45L)
            .setDuration(240).setInterpolator(GlassTheme.EASE).start();
    }

    private static LinearLayout buildMinimalPosterCard(
            FrameLayout overlay, LinearLayout gridRows,
            int rowIdx, int colIdx, int totalRows,
            JSONObject item, Context ctx, SearchCallback cb) {
        LinearLayout card = createPosterCardContainer(ctx);
        FrameLayout imgFrame = createPosterCardImageFrame(ctx, item);
        card.addView(imgFrame, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, POSTER_H)));
        TextView subTv = createPosterCaptionView(ctx, item);
        card.addView(subTv, GlassTheme.matchWrapParams());
        bindPosterCardEvents(overlay, gridRows, card, imgFrame, subTv, rowIdx, colIdx, totalRows, item, ctx, cb);
        return card;
    }

    private static LinearLayout createPosterCardContainer(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setFocusable(true);
        card.setClickable(true);
        return card;
    }

    private static FrameLayout createPosterCardImageFrame(Context ctx, JSONObject item) {
        FrameLayout imgFrame = new FrameLayout(ctx);
        imgFrame.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_LIGHT, dp(ctx, 1), dp(ctx, 12)));
        addInsetImageView(ctx, imgFrame, createGridPosterImageView(ctx, item.optString("poster_url", "")));
        return imgFrame;
    }

    private static ImageView createGridPosterImageView(Context ctx, String url) {
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(GlassTheme.SURFACE_ELEV);
        ImageLoader.applyRoundedCorners(iv, dp(ctx, 10));
        if (!url.isEmpty()) ImageLoader.load(ctx, url, iv);
        return iv;
    }

    private static void addInsetImageView(Context ctx, FrameLayout imgFrame, ImageView iv) {
        int inset = dp(ctx, 2);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ivLp.setMargins(inset, inset, inset, inset);
        imgFrame.addView(iv, ivLp);
    }

    // Minimalist Genre / Year Caption Only (no OTT badge, no rating badge, no truncated title)
    private static TextView createPosterCaptionView(Context ctx, JSONObject item) {
        int year = item.optInt("year", 0);
        String genres = item.optString("genres", "");
        String firstGenre = genres.isEmpty() ? "" : genres.split(",")[0].trim();
        String sub = (!firstGenre.isEmpty() && year > 0) ? (firstGenre + " \u00B7 " + year)
                   : (!firstGenre.isEmpty() ? firstGenre : (year > 0 ? String.valueOf(year) : "Featured"));
        TextView subTv = GlassTheme.makeText(ctx, sub, 9.5f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1);
        subTv.setGravity(Gravity.CENTER_HORIZONTAL);
        subTv.setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), 0);
        return subTv;
    }

    private static void bindPosterCardEvents(FrameLayout overlay, LinearLayout gridRows,
                                             LinearLayout card, FrameLayout imgFrame, TextView subTv,
                                             int rowIdx, int colIdx, int totalRows,
                                             JSONObject item, Context ctx, SearchCallback cb) {
        card.setOnClickListener(v -> { if (cb != null) cb.onResultClicked(item); });
        card.setOnFocusChangeListener((v, hasFocus) ->
            updatePosterCardFocus(overlay, v, imgFrame, subTv, hasFocus, ctx));
        card.setOnKeyListener((v, code, ev) ->
            handlePosterCardKey(overlay, gridRows, rowIdx, colIdx, totalRows, cb, code, ev));
    }

    private static void updatePosterCardFocus(FrameLayout overlay, View v, FrameLayout imgFrame,
                                              TextView subTv, boolean hasFocus, Context ctx) {
        float rad = dp(ctx, 12);
        imgFrame.setBackground(GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV,
            hasFocus ? Color.WHITE : GlassTheme.BORDER_LIGHT, dp(ctx, hasFocus ? 2.5f : 1), rad));
        imgFrame.setElevation(hasFocus ? dp(ctx, 14) : 0);
        subTv.setTextColor(hasFocus ? Color.WHITE : GlassTheme.TEXT_SECONDARY);
        float scale = hasFocus ? 1.04f : 1f;
        v.animate().scaleX(scale).scaleY(scale).setDuration(160).setInterpolator(GlassTheme.EASE).start();
        if (hasFocus) scrollResultIntoView(overlay, (View) v.getParent());
    }

    private static boolean handlePosterCardKey(FrameLayout overlay, LinearLayout gridRows,
                                               int rowIdx, int colIdx, int totalRows,
                                               SearchCallback cb, int code, KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK) { if (cb != null) cb.onDismiss(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
            return movePosterGridHorizontal(gridRows, rowIdx, colIdx, code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
        }
        return handlePosterVerticalKey(overlay, gridRows, rowIdx, colIdx, totalRows, code);
    }

    private static boolean movePosterGridHorizontal(LinearLayout gridRows, int rowIdx, int colIdx, int delta) {
        if (colIdx + delta >= 0) {
            focusIfPresent(getGridCardAt(gridRows, rowIdx, colIdx + delta));
        }
        return true;
    }

    private static boolean handlePosterVerticalKey(FrameLayout overlay, LinearLayout gridRows,
                                                   int rowIdx, int colIdx, int totalRows, int code) {
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            return movePosterGridDown(gridRows, rowIdx, colIdx, totalRows);
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            focusIfPresent(rowIdx > 0 ? getGridCardAt(gridRows, rowIdx - 1, colIdx)
                                      : overlay.findViewWithTag(TAG_INPUT_BOX));
            return true;
        }
        return false;
    }

    private static boolean movePosterGridDown(LinearLayout gridRows, int rowIdx, int colIdx, int totalRows) {
        if (rowIdx + 1 < totalRows) {
            View target = getGridCardAt(gridRows, rowIdx + 1, colIdx);
            focusIfPresent(target != null ? target : getGridCardAt(gridRows, rowIdx + 1, 0));
        }
        return true;
    }

    private static void focusIfPresent(View target) {
        if (target != null) target.requestFocus();
    }

    private static View getGridCardAt(LinearLayout gridRows, int r, int c) {
        if (r < 0 || r >= gridRows.getChildCount()) return null;
        View row = gridRows.getChildAt(r);
        if (!(row instanceof ViewGroup)) return null;
        ViewGroup vg = (ViewGroup) row;
        if (c < 0 || c >= vg.getChildCount()) return null;
        View child = vg.getChildAt(c);
        return child.isFocusable() ? child : null;
    }

    private static void focusFirstPoster(FrameLayout overlay) {
        LinearLayout gridRows = findTagged(overlay, TAG_RESULTS);
        if (gridRows != null) {
            View first = getGridCardAt(gridRows, 0, 0);
            if (first != null) {
                first.post(first::requestFocus);
                return;
            }
        }
        View inputBox = overlay.findViewWithTag(TAG_INPUT_BOX);
        if (inputBox != null) inputBox.post(inputBox::requestFocus);
    }

    private static void scrollResultIntoView(FrameLayout overlay, View rowView) {
        ScrollView sv = findTagged(overlay, TAG_SCROLL);
        if (sv == null || rowView == null || sv.getHeight() <= 0) return;
        int curY = sv.getScrollY();
        int targetY = computeVerticalScrollTarget(overlay.getContext(), rowView, curY, sv.getHeight());
        if (targetY != curY) {
            ObjectAnimator anim = ObjectAnimator.ofInt(sv, "scrollY", curY, targetY);
            anim.setDuration(180);
            anim.setInterpolator(GlassTheme.EASE);
            anim.start();
        }
    }

    private static int computeVerticalScrollTarget(Context ctx, View rowView, int curY, int h) {
        int top = rowView.getTop();
        int bot = rowView.getBottom();
        if (bot > curY + h - dp(ctx, 12)) return Math.max(0, bot - h + dp(ctx, 18));
        if (top < curY + dp(ctx, 12)) return Math.max(0, top - dp(ctx, 12));
        return curY;
    }

    private static void showKeyboard(Context ctx, View v) {
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        } catch (Exception ignored) {}
    }

    private static void hideKeyboard(Context ctx, View v) {
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    private static void animateIn(FrameLayout overlay) {
        overlay.animate().cancel();
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(180).setInterpolator(GlassTheme.EASE).start();
    }

    private static void animateOut(FrameLayout overlay) {
        overlay.animate().cancel();
        overlay.setAlpha(1f);
        overlay.setVisibility(View.GONE);
    }

    private static int dp(Context ctx, float v) {
        return GlassTheme.dp(ctx, v);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findTagged(View root, int tag) {
        return (T) root.findViewWithTag(tag);
    }
}
