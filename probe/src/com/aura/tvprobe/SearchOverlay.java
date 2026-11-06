package com.aura.tvprobe;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
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
 * Full-Canvas Frosted Obsidian Agentic Search & Multi-Turn Follow-Up Studio.
 * Features:
 * 1. Built-in D-pad-safe conversational input bar (click OK to type, never hijacks hover).
 * 2. Multi-turn conversation breadcrumb trail (Turn 1 › Turn 2 › Turn 3) + Reset Topic button.
 * 3. 1-click Follow-Up Refinement chips for effortless TV remote narrowing.
 * 4. 2-Column Widescreen Cinema Grid with large posters, platform badges, and AI match reasons.
 */
public final class SearchOverlay {

    private SearchOverlay() { }

    private static final int TAG_LOADING     = 0x5E_0001;
    private static final int TAG_RESULTS     = 0x5E_0002;
    private static final int TAG_CHIPS       = 0x5E_0003;
    private static final int TAG_MESSAGES    = 0x5E_0004;
    private static final int TAG_CLOSE       = 0x5E_0005;
    private static final int TAG_SCROLL      = 0x5E_0006;
    private static final int TAG_INPUT_BOX   = 0x5E_0007;
    private static final int TAG_EDIT_TEXT   = 0x5E_0008;
    private static final int TAG_TRAIL_ROW   = 0x5E_0009;
    private static final int TAG_RESET_BTN   = 0x5E_000A;

    private static final int RADIUS  = 16;
    private static final int THUMB_W = 62;
    private static final int THUMB_H = 88;
    private static final int PAD     = 16;

    public interface SearchCallback {
        void onResultClicked(JSONObject item);
        void onFollowUpClicked(String query);
        void onResetConversation();
        void onDismiss();
    }

    public static FrameLayout build(Context ctx, SearchCallback cb) {
        FrameLayout root = createRoot(ctx, cb);
        root.addView(buildInnerCard(ctx, root, cb));
        return root;
    }

    public static void show(FrameLayout overlay, Context ctx, boolean focusInputBar) {
        overlay.setVisibility(View.VISIBLE);
        animateIn(overlay);
        if (focusInputBar) {
            View inputBox = overlay.findViewWithTag(TAG_INPUT_BOX);
            if (inputBox != null) inputBox.post(inputBox::requestFocus);
        } else {
            LinearLayout list = findTagged(overlay, TAG_RESULTS);
            if (list != null && list.getChildCount() > 0) {
                View firstRow = list.getChildAt(0);
                if (firstRow instanceof ViewGroup && ((ViewGroup) firstRow).getChildCount() > 0) {
                    View firstCard = ((ViewGroup) firstRow).getChildAt(0);
                    firstCard.post(firstCard::requestFocus);
                    return;
                }
            }
            View inputBox = overlay.findViewWithTag(TAG_INPUT_BOX);
            if (inputBox != null) inputBox.post(inputBox::requestFocus);
        }
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
        if (v == null) return;
        updateLoadingState(v, loading, message);
    }

    public static void setConversationTurns(FrameLayout overlay, JSONArray turns, Context ctx) {
        LinearLayout trail = findTagged(overlay, TAG_TRAIL_ROW);
        if (trail == null) return;
        trail.removeAllViews();
        List<String> userTurns = new ArrayList<>();
        if (turns != null) {
            for (int i = 0; i < turns.length(); i++) {
                JSONObject t = turns.optJSONObject(i);
                if (t != null && "user".equalsIgnoreCase(t.optString("role"))) {
                    String q = t.optString("text", "").trim();
                    if (!q.isEmpty()) userTurns.add(q);
                }
            }
        }
        if (userTurns.isEmpty()) {
            TextView badge = GlassTheme.makeText(ctx,
                "\u2726 Start a search or click any refinement chip below to build a multi-turn thread",
                10f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
            trail.addView(badge, GlassTheme.wrapParams());
            return;
        }
        TextView threadLbl = GlassTheme.makeText(ctx, "ACTIVE THREAD:  ", 9.5f,
            GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1);
        trail.addView(threadLbl, GlassTheme.wrapParams());
        for (int i = 0; i < userTurns.size(); i++) {
            if (i > 0) {
                TextView sep = GlassTheme.makeText(ctx, "  \u203A  ", 11f,
                    GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1);
                trail.addView(sep, GlassTheme.wrapParams());
            }
            boolean isLatest = (i == userTurns.size() - 1);
            String prefix = (i == 0) ? "\uD83D\uDCAC " : "\u21B3 ";
            TextView pill = GlassTheme.makeText(ctx, prefix + userTurns.get(i), 10f,
                isLatest ? Color.WHITE : GlassTheme.TEXT_SECONDARY,
                isLatest ? GlassTheme.FONT_BOLD : GlassTheme.FONT_MEDIUM, 1);
            int bg = isLatest ? Color.argb(65, 245, 158, 11) : GlassTheme.SURFACE_ELEV;
            int stroke = isLatest ? GlassTheme.GOLD_ACCENT : GlassTheme.BORDER_LIGHT;
            pill.setBackground(GlassTheme.focusRounded(bg, stroke, dp(ctx, 1), dp(ctx, 8)));
            pill.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
            trail.addView(pill, GlassTheme.wrapParams());
        }
    }

    public static void setResults(FrameLayout overlay, JSONArray results,
                                  Context ctx, SearchCallback cb) {
        LinearLayout gridRows = findTagged(overlay, TAG_RESULTS);
        if (gridRows == null) return;
        populateTwoColumnGrid(overlay, gridRows, results, ctx, cb);
        if (gridRows.getChildCount() > 0) {
            View firstRow = gridRows.getChildAt(0);
            if (firstRow instanceof ViewGroup && ((ViewGroup) firstRow).getChildCount() > 0) {
                View firstCard = ((ViewGroup) firstRow).getChildAt(0);
                firstCard.post(firstCard::requestFocus);
            }
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
        LinearLayout msgs = findTagged(overlay, TAG_MESSAGES);
        if (msgs == null) return;
        appendMessage(msgs, sender, text, ctx);
    }

    private static FrameLayout createRoot(Context ctx, SearchCallback cb) {
        FrameLayout root = new FrameLayout(ctx);
        root.setVisibility(View.GONE);
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        root.setClickable(true);
        root.setOnClickListener(v -> {
            dismiss(root);
            if (cb != null) cb.onDismiss();
        });
        return root;
    }

    private static LinearLayout buildInnerCard(Context ctx, FrameLayout root, SearchCallback cb) {
        LinearLayout card = createCardContainer(ctx);
        card.addView(buildTopCommandBar(ctx, root, cb));
        card.addView(buildThreadAndAiBanner(ctx));
        card.addView(buildLoadingRow(ctx));
        card.addView(buildFollowUpRefinementBar(ctx));
        card.addView(buildResultsScroll(ctx));
        return card;
    }

    private static LinearLayout createCardContainer(Context ctx) {
        int dp = GlassTheme.dp(ctx, 1);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GlassTheme.glassPanel(RADIUS * dp));
        card.setElevation(GlassTheme.dp(ctx, 26));
        card.setPadding(PAD * dp, dp(ctx, 12), PAD * dp, dp(ctx, 12));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        card.setLayoutParams(lp);
        card.setClickable(true);
        return card;
    }

    // ── 1. Top Conversational Follow-Up Input & Control Bar ──────────────

    private static LinearLayout buildTopCommandBar(Context ctx, FrameLayout root, SearchCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(ctx, 8));

        // D-pad safe conversational input box: only opens keyboard on DPAD_CENTER click!
        LinearLayout inputBox = new LinearLayout(ctx);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setTag(TAG_INPUT_BOX);
        inputBox.setFocusable(true);
        inputBox.setClickable(true);
        float r = dp(ctx, 12);
        inputBox.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1.2f), r));
        inputBox.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));

        TextView icon = GlassTheme.makeText(ctx, "\u2328\uFE0F ", 12f,
            GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1);
        inputBox.addView(icon, GlassTheme.wrapParams());

        EditText et = new EditText(ctx);
        et.setTag(TAG_EDIT_TEXT);
        et.setHint("Type a follow-up (e.g. 'Only on Netflix', 'Shorter under 2h') or press OK to edit\u2026");
        et.setHintTextColor(GlassTheme.TEXT_MUTED);
        et.setTextColor(GlassTheme.TEXT_PRIMARY);
        et.setTextSize(12f);
        et.setSingleLine(true);
        et.setBackground(null);
        et.setPadding(dp(ctx, 4), 0, dp(ctx, 6), 0);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // Critical: keep EditText non-focusable during normal D-pad navigation so Gboard never hijacks hover
        et.setFocusable(false);
        et.setFocusableInTouchMode(false);
        inputBox.addView(et, GlassTheme.weightParams(1f));

        TextView askBadge = GlassTheme.makeText(ctx, "Press OK to Type", 9.5f,
            GlassTheme.TEXT_ON_LIGHT, GlassTheme.FONT_BOLD, 1);
        askBadge.setBackground(GlassTheme.solidRounded(Color.WHITE, dp(ctx, 7)));
        askBadge.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        inputBox.addView(askBadge, GlassTheme.wrapParams());

        Runnable activateTyping = () -> {
            et.setFocusableInTouchMode(true);
            et.setFocusable(true);
            et.requestFocus();
            et.setSelection(et.getText().length());
            showKeyboard(ctx, et);
        };
        inputBox.setOnClickListener(v -> activateTyping.run());
        et.setOnClickListener(v -> activateTyping.run());

        et.setOnEditorActionListener((v, actionId, ev) -> {
            String q = et.getText().toString().trim();
            hideKeyboard(ctx, et);
            et.setFocusable(false);
            et.setFocusableInTouchMode(false);
            if (!q.isEmpty() && cb != null) {
                et.setText("");
                cb.onFollowUpClicked(q);
            } else {
                inputBox.requestFocus();
            }
            return true;
        });

        et.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BACK) {
                hideKeyboard(ctx, et);
                et.setFocusable(false);
                et.setFocusableInTouchMode(false);
                inputBox.requestFocus();
                return true;
            }
            return false;
        });

        inputBox.setOnFocusChangeListener((v, hasFocus) -> {
            int stroke = hasFocus ? Color.WHITE : GlassTheme.BORDER_STRONG;
            int sw = hasFocus ? dp(ctx, 2.5f) : dp(ctx, 1.2f);
            v.setBackground(GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV, stroke, sw, r));
        });

        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ibLp.rightMargin = dp(ctx, 8);
        row.addView(inputBox, ibLp);

        // Reset Thread Button
        TextView resetBtn = GlassTheme.makeGlassButton(ctx, "\u21BA New Topic");
        resetBtn.setTag(TAG_RESET_BTN);
        resetBtn.setTextSize(10.5f);
        resetBtn.setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6));
        resetBtn.setOnClickListener(v -> {
            et.setText("");
            if (cb != null) cb.onResetConversation();
        });
        LinearLayout.LayoutParams rLp = GlassTheme.wrapParams();
        rLp.rightMargin = dp(ctx, 8);
        row.addView(resetBtn, rLp);

        // Close Button
        TextView closeBtn = GlassTheme.makeGlassButton(ctx, "\u2715 Close");
        closeBtn.setTag(TAG_CLOSE);
        closeBtn.setTextSize(10.5f);
        closeBtn.setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6));
        closeBtn.setOnClickListener(v -> {
            if (cb != null) cb.onDismiss();
        });
        row.addView(closeBtn, GlassTheme.wrapParams());

        bindTopBarDpad(root, inputBox, resetBtn, closeBtn, cb);
        return row;
    }

    private static void bindTopBarDpad(FrameLayout root, View inputBox,
                                       View resetBtn, View closeBtn, SearchCallback cb) {
        View.OnKeyListener topKey = (v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (cb != null) cb.onDismiss();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                LinearLayout chips = findTagged(root, TAG_CHIPS);
                if (chips != null && chips.getChildCount() > 0) {
                    chips.getChildAt(0).requestFocus();
                    return true;
                }
                focusFirstGridCard(root);
                return true;
            }
            if (v == inputBox && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                resetBtn.requestFocus();
                return true;
            }
            if (v == resetBtn && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                inputBox.requestFocus();
                return true;
            }
            if (v == resetBtn && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                closeBtn.requestFocus();
                return true;
            }
            if (v == closeBtn && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                resetBtn.requestFocus();
                return true;
            }
            return false;
        };
        inputBox.setOnKeyListener(topKey);
        resetBtn.setOnKeyListener(topKey);
        closeBtn.setOnKeyListener(topKey);
    }

    // ── 2. Multi-Turn Breadcrumb Trail & AI Concierge Synthesis ──────────

    private static LinearLayout buildThreadAndAiBanner(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(ctx, 4));

        HorizontalScrollView trailScroll = new HorizontalScrollView(ctx);
        trailScroll.setHorizontalScrollBarEnabled(false);
        trailScroll.setFocusable(false);
        LinearLayout trailRow = new LinearLayout(ctx);
        trailRow.setOrientation(LinearLayout.HORIZONTAL);
        trailRow.setGravity(Gravity.CENTER_VERTICAL);
        trailRow.setTag(TAG_TRAIL_ROW);
        trailRow.setPadding(0, 0, 0, dp(ctx, 5));
        trailScroll.addView(trailRow);
        box.addView(trailScroll, GlassTheme.matchWrapParams());

        LinearLayout msgs = new LinearLayout(ctx);
        msgs.setOrientation(LinearLayout.VERTICAL);
        msgs.setTag(TAG_MESSAGES);
        box.addView(msgs, GlassTheme.matchWrapParams());
        return box;
    }

    // ── 3. Follow-Up Refinement Strip (1-Click Conversational Follow-Ups) ─

    private static LinearLayout buildFollowUpRefinementBar(Context ctx) {
        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(Gravity.CENTER_VERTICAL);
        wrapper.setPadding(0, dp(ctx, 2), 0, dp(ctx, 6));

        TextView lbl = GlassTheme.makeText(ctx, "\u21B3 FOLLOW UP:  ", 9.5f,
            GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1);
        wrapper.addView(lbl, GlassTheme.wrapParams());

        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFocusable(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(TAG_CHIPS);
        row.setClipChildren(false);
        hsv.addView(row);

        wrapper.addView(hsv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return wrapper;
    }

    private static void populateChips(FrameLayout overlay, LinearLayout row,
                                      List<String> aiChips, SearchCallback cb, Context ctx) {
        row.removeAllViews();
        List<String> combined = new ArrayList<>();
        if (aiChips != null) {
            for (String c : aiChips) {
                if (c != null && !c.trim().isEmpty()) combined.add(c.trim());
            }
        }
        // Always include high-utility conversational follow-up refinements for 1-click narrowing
        String[] quickRefinements = {
            "Only on Netflix & Prime",
            "Shorter under 2 hours",
            "IMDb 8.0+ Only",
            "Darker & Grittier tone",
            "Only Series",
            "Only Movies"
        };
        for (String q : quickRefinements) {
            boolean exists = false;
            for (String ex : combined) {
                if (ex.equalsIgnoreCase(q)) { exists = true; break; }
            }
            if (!exists && combined.size() < 7) combined.add(q);
        }

        for (int i = 0; i < combined.size(); i++) {
            final String text = combined.get(i);
            final int idx = i;
            final int count = combined.size();
            boolean isAiPrimary = (aiChips != null && i < aiChips.size());
            String prefix = isAiPrimary ? "\u2726 " : "\u21B3 ";
            TextView chip = GlassTheme.makeGlassButton(ctx, prefix + text);
            chip.setTextSize(10.5f);
            chip.setPadding(dp(ctx, 11), dp(ctx, 5), dp(ctx, 11), dp(ctx, 5));
            LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
            lp.setMargins(0, 0, dp(ctx, 7), 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                if (cb != null) cb.onFollowUpClicked(text);
            });
            chip.setOnKeyListener((v, code, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_BACK) {
                    if (cb != null) cb.onDismiss();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && idx > 0) {
                    row.getChildAt(idx - 1).requestFocus();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && idx < count - 1) {
                    row.getChildAt(idx + 1).requestFocus();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    View inputBox = overlay.findViewWithTag(TAG_INPUT_BOX);
                    if (inputBox != null) inputBox.requestFocus();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusFirstGridCard(overlay);
                    return true;
                }
                return false;
            });
            row.addView(chip);
        }
    }

    // ── 4. 2-Column Widescreen Cinema Results Grid ───────────────────────

    private static ScrollView buildResultsScroll(Context ctx) {
        RailBuilder.SmoothVerticalScrollView sv = new RailBuilder.SmoothVerticalScrollView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(ctx, 2);
        sv.setLayoutParams(lp);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFocusable(false);
        sv.setTag(TAG_SCROLL);
        LinearLayout gridRows = new LinearLayout(ctx);
        gridRows.setOrientation(LinearLayout.VERTICAL);
        gridRows.setTag(TAG_RESULTS);
        sv.addView(gridRows, GlassTheme.matchWrapParams());
        return sv;
    }

    private static void populateTwoColumnGrid(FrameLayout overlay, LinearLayout gridRows,
                                              JSONArray results, Context ctx, SearchCallback cb) {
        gridRows.removeAllViews();
        if (results == null || results.length() == 0) return;
        int n = results.length();
        int totalRows = (n + 1) / 2;

        for (int r = 0; r < totalRows; r++) {
            LinearLayout rowLayout = new LinearLayout(ctx);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setClipChildren(false);
            rowLayout.setClipToPadding(false);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(ctx, 8);
            rowLayout.setLayoutParams(rowLp);

            for (int c = 0; c < 2; c++) {
                int idx = r * 2 + c;
                if (idx < n) {
                    JSONObject item = results.optJSONObject(idx);
                    if (item != null) {
                        LinearLayout card = buildCinemaResultCard(
                            overlay, gridRows, r, c, totalRows, item, ctx, cb);
                        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        if (c == 0) cLp.rightMargin = dp(ctx, 8);
                        card.setLayoutParams(cLp);
                        rowLayout.addView(card);
                    }
                } else {
                    View spacer = new View(ctx);
                    rowLayout.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }
            gridRows.addView(rowLayout);
        }
    }

    private static LinearLayout buildCinemaResultCard(
            FrameLayout overlay, LinearLayout gridRows,
            int rowIdx, int colIdx, int totalRows,
            JSONObject item, Context ctx, SearchCallback cb) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        card.setFocusable(true);
        card.setClickable(true);
        float rad = dp(ctx, 12);
        card.setBackground(GlassTheme.focusRounded(
            GlassTheme.GLASS_CARD, GlassTheme.BORDER_LIGHT, dp(ctx, 1), rad));

        card.addView(buildThumbnailView(item, ctx));
        card.addView(buildCardDetails(item, ctx), GlassTheme.weightParams(1f));

        card.setOnClickListener(v -> {
            if (cb != null) cb.onResultClicked(item);
        });
        // Long-press OK on any result card runs an instant "More like <Title>" follow-up!
        card.setOnLongClickListener(v -> {
            String title = item.optString("title", "");
            if (!title.isEmpty() && cb != null) {
                cb.onFollowUpClicked("More like " + title);
                return true;
            }
            return false;
        });

        card.setOnFocusChangeListener((v, hasFocus) -> {
            int bg = hasFocus ? GlassTheme.SURFACE_ELEV : GlassTheme.GLASS_CARD;
            int stroke = hasFocus ? Color.WHITE : GlassTheme.BORDER_LIGHT;
            int sw = hasFocus ? dp(ctx, 2.5f) : dp(ctx, 1);
            v.setBackground(GlassTheme.focusRounded(bg, stroke, sw, rad));
            v.setElevation(hasFocus ? dp(ctx, 12) : dp(ctx, 2));
            if (hasFocus) scrollResultIntoView(overlay, (View) v.getParent());
        });

        card.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (cb != null) cb.onDismiss();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && colIdx == 0) {
                View target = getGridCardAt(gridRows, rowIdx, 1);
                if (target != null) target.requestFocus();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && colIdx == 1) {
                View target = getGridCardAt(gridRows, rowIdx, 0);
                if (target != null) target.requestFocus();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (rowIdx + 1 < totalRows) {
                    View target = getGridCardAt(gridRows, rowIdx + 1, colIdx);
                    if (target == null) target = getGridCardAt(gridRows, rowIdx + 1, 0);
                    if (target != null) target.requestFocus();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (rowIdx > 0) {
                    View target = getGridCardAt(gridRows, rowIdx - 1, colIdx);
                    if (target != null) target.requestFocus();
                    return true;
                }
                LinearLayout chips = findTagged(overlay, TAG_CHIPS);
                if (chips != null && chips.getChildCount() > 0) {
                    chips.getChildAt(0).requestFocus();
                    return true;
                }
                View inputBox = overlay.findViewWithTag(TAG_INPUT_BOX);
                if (inputBox != null) inputBox.requestFocus();
                return true;
            }
            return false;
        });

        return card;
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

    private static void focusFirstGridCard(FrameLayout overlay) {
        LinearLayout gridRows = findTagged(overlay, TAG_RESULTS);
        if (gridRows != null) {
            View first = getGridCardAt(gridRows, 0, 0);
            if (first != null) first.requestFocus();
        }
    }

    private static ImageView buildThumbnailView(JSONObject item, Context ctx) {
        ImageView iv = new ImageView(ctx);
        int w = dp(ctx, THUMB_W);
        int h = dp(ctx, THUMB_H);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = dp(ctx, 11);
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, dp(ctx, 8)));
        ImageLoader.applyRoundedCorners(iv, dp(ctx, 8));
        String url = item.optString("poster_url", "");
        if (!url.isEmpty()) ImageLoader.load(ctx, url, iv);
        return iv;
    }

    private static LinearLayout buildCardDetails(JSONObject item, Context ctx) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(buildTopMetaRow(item, ctx));

        TextView title = GlassTheme.makeText(ctx, item.optString("title", ""),
            13.5f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        title.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));
        col.addView(title);

        String reason = item.optString("reason", item.optString("synopsis", ""));
        if (!reason.isEmpty()) {
            TextView rTv = GlassTheme.makeText(ctx, reason, 10f,
                GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_REGULAR, 2);
            col.addView(rTv);
        }

        TextView hint = GlassTheme.makeText(ctx,
            "\u25B6 OK: Watch & Details   \u00B7   Hold OK: Follow-up \"More Like This\"",
            8.5f, GlassTheme.GOLD_ACCENT, GlassTheme.FONT_MEDIUM, 1);
        hint.setPadding(0, dp(ctx, 3), 0, 0);
        col.addView(hint);
        return col;
    }

    private static LinearLayout buildTopMetaRow(JSONObject item, Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String app = HeroSliderBuilder.cleanPlatformName(
            item.optString("app", item.optString("app_name", "")));
        if (!app.isEmpty()) {
            int badgeColor = HeroSliderBuilder.getPlatformColor(app);
            TextView pill = GlassTheme.makeText(ctx, app.toUpperCase(), 7.5f,
                Color.WHITE, GlassTheme.FONT_BOLD, 1);
            pill.setBackground(GlassTheme.solidRounded(badgeColor, dp(ctx, 4)));
            pill.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
            row.addView(pill, GlassTheme.wrapParams());
        }
        String meta = HeroSliderBuilder.buildMetaString(item);
        if (!meta.isEmpty()) {
            TextView metaTv = GlassTheme.makeText(ctx,
                (app.isEmpty() ? "" : "  ") + meta, 9.5f,
                GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1);
            row.addView(metaTv, GlassTheme.wrapParams());
        }
        return row;
    }

    private static LinearLayout buildLoadingRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 3), 0, dp(ctx, 5));
        row.setTag(TAG_LOADING);
        row.setVisibility(View.GONE);
        int size = GlassTheme.dp(ctx, 15);
        ProgressBar pb = new ProgressBar(ctx);
        pb.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        pb.getIndeterminateDrawable().setTint(GlassTheme.GOLD_ACCENT);
        row.addView(pb);
        TextView lbl = GlassTheme.makeMuted(ctx, "");
        lbl.setPadding(GlassTheme.dp(ctx, 8), 0, 0, 0);
        row.addView(lbl);
        return row;
    }

    private static void updateLoadingState(View row, boolean loading, String msg) {
        row.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading && row instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) row;
            if (ll.getChildCount() > 1 && ll.getChildAt(1) instanceof TextView) {
                ((TextView) ll.getChildAt(1)).setText(msg != null ? msg : "");
            }
        }
    }

    private static void appendMessage(LinearLayout msgs, String sender,
                                      String text, Context ctx) {
        if ("user".equalsIgnoreCase(sender)) return;
        msgs.removeAllViews();
        msgs.setVisibility(View.VISIBLE);
        TextView tv = GlassTheme.makeText(ctx, "\u2726 Aura AI: " + text, 10.5f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 2);
        tv.setBackground(GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, dp(ctx, 8)));
        tv.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        msgs.addView(tv, GlassTheme.matchWrapParams());
    }

    private static void scrollResultIntoView(FrameLayout overlay, View rowView) {
        ScrollView sv = findTagged(overlay, TAG_SCROLL);
        if (sv == null || rowView == null) return;
        int top = rowView.getTop();
        int bot = rowView.getBottom();
        int curY = sv.getScrollY();
        int h = sv.getHeight();
        if (h <= 0) return;
        int targetY = curY;
        if (bot > curY + h - dp(overlay.getContext(), 12)) {
            targetY = Math.max(0, bot - h + dp(overlay.getContext(), 18));
        } else if (top < curY + dp(overlay.getContext(), 12)) {
            targetY = Math.max(0, top - dp(overlay.getContext(), 12));
        }
        if (targetY != curY) {
            ObjectAnimator anim = ObjectAnimator.ofInt(sv, "scrollY", curY, targetY);
            anim.setDuration(180);
            anim.setInterpolator(GlassTheme.EASE);
            anim.start();
        }
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
        overlay.setAlpha(0f);
        overlay.setTranslationY(-dp(overlay.getContext(), 12));
        overlay.animate()
               .alpha(1f).translationY(0f)
               .setDuration(190).setInterpolator(GlassTheme.EASE)
               .start();
    }

    private static void animateOut(FrameLayout overlay) {
        overlay.animate()
               .alpha(0f).translationY(-dp(overlay.getContext(), 12))
               .setDuration(160).setInterpolator(GlassTheme.EASE)
               .withEndAction(() -> overlay.setVisibility(View.GONE))
               .start();
    }

    private static int dp(Context ctx, float v) {
        return GlassTheme.dp(ctx, v);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findTagged(View root, int tag) {
        return (T) root.findViewWithTag(tag);
    }
}
