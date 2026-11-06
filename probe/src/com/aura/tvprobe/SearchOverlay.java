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

    private static final int COLS     = 5;
    private static final int POSTER_W = 162;
    private static final int POSTER_H = 228;

    public interface SearchCallback {
        void onResultClicked(JSONObject item);
        void onFollowUpClicked(String query);
        void onResetConversation();
        void onDismiss();
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
        if (focusInputBar) {
            View inputBox = overlay.findViewWithTag(TAG_INPUT_BOX);
            if (inputBox != null) inputBox.post(inputBox::requestFocus);
        } else {
            focusFirstPoster(overlay);
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
        v.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    public static void setConversationTurns(FrameLayout overlay, JSONArray turns, Context ctx) {
        EditText et = findTagged(overlay, TAG_EDIT_TEXT);
        View resetBtn = overlay.findViewWithTag(TAG_RESET_BTN);
        String latestUserQuery = "";
        int userTurnCount = 0;
        if (turns != null) {
            for (int i = 0; i < turns.length(); i++) {
                JSONObject t = turns.optJSONObject(i);
                if (t != null && "user".equalsIgnoreCase(t.optString("role"))) {
                    String q = t.optString("text", "").trim();
                    if (!q.isEmpty()) {
                        latestUserQuery = q;
                        userTurnCount++;
                    }
                }
            }
        }
        if (et != null && !et.hasFocus()) {
            et.setText("");
            if (!latestUserQuery.isEmpty()) {
                et.setHint(latestUserQuery + "  \u00B7  Ask follow-up\u2026");
            } else {
                et.setHint("Search or ask anything\u2026");
            }
        }
        if (resetBtn != null) {
            resetBtn.setVisibility(userTurnCount > 0 ? View.VISIBLE : View.GONE);
        }
    }

    public static void setResults(FrameLayout overlay, JSONArray results,
                                  Context ctx, SearchCallback cb) {
        LinearLayout gridRows = findTagged(overlay, TAG_RESULTS);
        if (gridRows == null) return;
        populatePosterGrid(overlay, gridRows, results, ctx, cb);
        focusFirstPoster(overlay);
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
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(dp(ctx, 24), dp(ctx, 18), dp(ctx, 24), dp(ctx, 16));
        col.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(buildMinimalTopBar(ctx, root, cb));
        col.addView(buildResultsScroll(ctx));
        return col;
    }

    // ── Single Minimalist Top Bar (Search Input + Follow-Up Pills + Close) ─

    private static LinearLayout buildMinimalTopBar(Context ctx, FrameLayout root, SearchCallback cb) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setPadding(0, 0, 0, dp(ctx, 14));

        // 1. Clean Search / Follow-Up Input Pill
        LinearLayout inputBox = new LinearLayout(ctx);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setTag(TAG_INPUT_BOX);
        inputBox.setFocusable(true);
        inputBox.setClickable(true);
        float r = dp(ctx, 20);
        inputBox.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), r));
        inputBox.setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7));

        TextView icon = GlassTheme.makeText(ctx, "\u2315  ", 13f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_BOLD, 1);
        inputBox.addView(icon, GlassTheme.wrapParams());

        EditText et = new EditText(ctx);
        et.setTag(TAG_EDIT_TEXT);
        et.setHint("Search or ask anything\u2026");
        et.setHintTextColor(GlassTheme.TEXT_MUTED);
        et.setTextColor(GlassTheme.TEXT_PRIMARY);
        et.setTextSize(12f);
        et.setSingleLine(true);
        et.setBackground(null);
        et.setPadding(0, 0, dp(ctx, 6), 0);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        et.setFocusable(false);
        et.setFocusableInTouchMode(false);
        inputBox.addView(et, new LinearLayout.LayoutParams(dp(ctx, 240), ViewGroup.LayoutParams.WRAP_CONTENT));

        // Subtle inline spinner inside the input pill when searching
        ProgressBar pb = new ProgressBar(ctx);
        int pbSize = dp(ctx, 14);
        pb.setLayoutParams(new LinearLayout.LayoutParams(pbSize, pbSize));
        pb.getIndeterminateDrawable().setTint(GlassTheme.GOLD_ACCENT);
        pb.setTag(TAG_LOADING);
        pb.setVisibility(View.GONE);
        inputBox.addView(pb);

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
            int sw = hasFocus ? dp(ctx, 2.5f) : dp(ctx, 1);
            v.setBackground(GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV, stroke, sw, r));
        });

        LinearLayout.LayoutParams ibLp = GlassTheme.wrapParams();
        ibLp.rightMargin = dp(ctx, 10);
        bar.addView(inputBox, ibLp);

        // 2. Inline Follow-Up Pills (in the same single top bar!)
        HorizontalScrollView chipsScroll = new HorizontalScrollView(ctx);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setFocusable(false);
        chipsScroll.setClipChildren(false);
        LinearLayout chipsRow = new LinearLayout(ctx);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        chipsRow.setTag(TAG_CHIPS);
        chipsScroll.addView(chipsRow);
        LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        csLp.rightMargin = dp(ctx, 10);
        bar.addView(chipsScroll, csLp);

        // 3. Subtle Reset Pill (only visible when a thread is active)
        TextView resetBtn = GlassTheme.makeGlassButton(ctx, "\u21BA Reset");
        resetBtn.setTag(TAG_RESET_BTN);
        resetBtn.setTextSize(10.5f);
        resetBtn.setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6));
        resetBtn.setVisibility(View.GONE);
        resetBtn.setOnClickListener(v -> {
            if (cb != null) cb.onResetConversation();
        });
        LinearLayout.LayoutParams rLp = GlassTheme.wrapParams();
        rLp.rightMargin = dp(ctx, 8);
        bar.addView(resetBtn, rLp);

        // 4. Minimalist Close Pill
        TextView closeBtn = GlassTheme.makeGlassButton(ctx, "\u2715");
        closeBtn.setTag(TAG_CLOSE);
        closeBtn.setTextSize(11f);
        closeBtn.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        closeBtn.setOnClickListener(v -> {
            if (cb != null) cb.onDismiss();
        });
        bar.addView(closeBtn, GlassTheme.wrapParams());

        bindTopBarDpad(root, inputBox, chipsRow, resetBtn, closeBtn, cb);
        return bar;
    }

    private static void bindTopBarDpad(FrameLayout root, View inputBox, LinearLayout chipsRow,
                                       View resetBtn, View closeBtn, SearchCallback cb) {
        inputBox.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (cb != null) cb.onDismiss();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusFirstPoster(root);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (chipsRow.getChildCount() > 0) chipsRow.getChildAt(0).requestFocus();
                else if (resetBtn.getVisibility() == View.VISIBLE) resetBtn.requestFocus();
                else closeBtn.requestFocus();
                return true;
            }
            return false;
        });

        resetBtn.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (cb != null) cb.onDismiss();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusFirstPoster(root);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (chipsRow.getChildCount() > 0) {
                    chipsRow.getChildAt(chipsRow.getChildCount() - 1).requestFocus();
                } else {
                    inputBox.requestFocus();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                closeBtn.requestFocus();
                return true;
            }
            return false;
        });

        closeBtn.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (cb != null) cb.onDismiss();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusFirstPoster(root);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (resetBtn.getVisibility() == View.VISIBLE) resetBtn.requestFocus();
                else if (chipsRow.getChildCount() > 0) chipsRow.getChildAt(chipsRow.getChildCount() - 1).requestFocus();
                else inputBox.requestFocus();
                return true;
            }
            return false;
        });
    }

    private static void populateChips(FrameLayout overlay, LinearLayout row,
                                      List<String> aiChips, SearchCallback cb, Context ctx) {
        row.removeAllViews();
        List<String> list = new ArrayList<>();
        if (aiChips != null) {
            for (String c : aiChips) {
                if (c != null && !c.trim().isEmpty() && list.size() < 4) list.add(c.trim());
            }
        }
        String[] fallbacks = {"Only on Netflix", "Shorter under 2h", "IMDb 8.0+", "Darker tone"};
        for (String f : fallbacks) {
            if (list.size() >= 4) break;
            boolean exists = false;
            for (String s : list) {
                if (s.equalsIgnoreCase(f)) { exists = true; break; }
            }
            if (!exists) list.add(f);
        }

        for (int i = 0; i < list.size(); i++) {
            final String text = list.get(i);
            final int idx = i;
            final int count = list.size();
            TextView chip = GlassTheme.makeGlassButton(ctx, text);
            chip.setTextSize(10.5f);
            chip.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
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
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusFirstPoster(overlay);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (idx > 0) row.getChildAt(idx - 1).requestFocus();
                    else {
                        View inputBox = overlay.findViewWithTag(TAG_INPUT_BOX);
                        if (inputBox != null) inputBox.requestFocus();
                    }
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (idx < count - 1) {
                        row.getChildAt(idx + 1).requestFocus();
                    } else {
                        View resetBtn = overlay.findViewWithTag(TAG_RESET_BTN);
                        View closeBtn = overlay.findViewWithTag(TAG_CLOSE);
                        if (resetBtn != null && resetBtn.getVisibility() == View.VISIBLE) {
                            resetBtn.requestFocus();
                        } else if (closeBtn != null) {
                            closeBtn.requestFocus();
                        }
                    }
                    return true;
                }
                return false;
            });
            row.addView(chip);
        }
    }

    // ── Minimalist 5-Column Cinema Poster Grid ───────────────────────────

    private static ScrollView buildResultsScroll(Context ctx) {
        RailBuilder.SmoothVerticalScrollView sv = new RailBuilder.SmoothVerticalScrollView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(lp);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFocusable(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setTag(TAG_SCROLL);

        LinearLayout gridRows = new LinearLayout(ctx);
        gridRows.setOrientation(LinearLayout.VERTICAL);
        gridRows.setClipChildren(false);
        gridRows.setClipToPadding(false);
        gridRows.setTag(TAG_RESULTS);
        sv.addView(gridRows, GlassTheme.matchWrapParams());
        return sv;
    }

    private static void populatePosterGrid(FrameLayout overlay, LinearLayout gridRows,
                                           JSONArray results, Context ctx, SearchCallback cb) {
        gridRows.removeAllViews();
        if (results == null || results.length() == 0) return;
        int n = results.length();
        int totalRows = (n + COLS - 1) / COLS;

        for (int r = 0; r < totalRows; r++) {
            LinearLayout rowLayout = new LinearLayout(ctx);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setClipChildren(false);
            rowLayout.setClipToPadding(false);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(ctx, 16);
            rowLayout.setLayoutParams(rowLp);

            for (int c = 0; c < COLS; c++) {
                int idx = r * COLS + c;
                if (idx < n) {
                    JSONObject item = results.optJSONObject(idx);
                    if (item != null) {
                        LinearLayout card = buildMinimalPosterCard(
                            overlay, gridRows, r, c, totalRows, item, ctx, cb);
                        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        if (c < COLS - 1) cLp.rightMargin = dp(ctx, 14);
                        card.setLayoutParams(cLp);
                        rowLayout.addView(card);
                    }
                } else {
                    View spacer = new View(ctx);
                    LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, 1, 1f);
                    if (c < COLS - 1) sLp.rightMargin = dp(ctx, 14);
                    rowLayout.addView(spacer, sLp);
                }
            }
            gridRows.addView(rowLayout);
        }
    }

    private static LinearLayout buildMinimalPosterCard(
            FrameLayout overlay, LinearLayout gridRows,
            int rowIdx, int colIdx, int totalRows,
            JSONObject item, Context ctx, SearchCallback cb) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setFocusable(true);
        card.setClickable(true);

        FrameLayout imgFrame = new FrameLayout(ctx);
        float rad = dp(ctx, 12);
        imgFrame.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_LIGHT, dp(ctx, 1), rad));

        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(GlassTheme.SURFACE_ELEV);
        ImageLoader.applyRoundedCorners(iv, dp(ctx, 10));
        String url = item.optString("poster_url", "");
        if (!url.isEmpty()) ImageLoader.load(ctx, url, iv);
        int inset = dp(ctx, 2);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ivLp.setMargins(inset, inset, inset, inset);
        imgFrame.addView(iv, ivLp);

        // Minimalist Platform Badge (Top-Left)
        String app = HeroSliderBuilder.cleanPlatformName(
            item.optString("app", item.optString("app_name", "")));
        if (!app.isEmpty()) {
            TextView pill = GlassTheme.makeText(ctx, app.toUpperCase(), 7.5f,
                Color.WHITE, GlassTheme.FONT_BOLD, 1);
            pill.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
            pill.setBackground(GlassTheme.solidRounded(HeroSliderBuilder.getPlatformColor(app), dp(ctx, 4)));
            FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pLp.gravity = Gravity.TOP | Gravity.START;
            pLp.setMargins(dp(ctx, 6), dp(ctx, 6), 0, 0);
            imgFrame.addView(pill, pLp);
        }

        // Minimalist Rating Pill (Top-Right)
        String rating = HeroSliderBuilder.extractRatingText(item);
        if (!rating.isEmpty()) {
            TextView rBadge = GlassTheme.makeText(ctx, rating, 8.5f,
                GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1);
            rBadge.setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2));
            rBadge.setBackground(GlassTheme.solidRounded(Color.argb(220, 11, 14, 20), dp(ctx, 5)));
            FrameLayout.LayoutParams rLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.gravity = Gravity.TOP | Gravity.END;
            rLp.setMargins(0, dp(ctx, 6), dp(ctx, 6), 0);
            imgFrame.addView(rBadge, rLp);
        }

        card.addView(imgFrame, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, POSTER_H)));

        // Minimalist Title (1 line)
        TextView title = GlassTheme.makeText(ctx, item.optString("title", ""),
            11.5f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(ctx, 2), dp(ctx, 6), dp(ctx, 2), 0);
        card.addView(title, GlassTheme.matchWrapParams());

        // Minimalist Subtitle (Year · Genre)
        int year = item.optInt("year", 0);
        String genres = item.optString("genres", "");
        String firstGenre = genres.isEmpty() ? "" : genres.split(",")[0].trim();
        String sub = (year > 0 && !firstGenre.isEmpty()) ? (year + " \u00B7 " + firstGenre)
                   : (year > 0 ? String.valueOf(year) : firstGenre);
        if (!sub.isEmpty()) {
            TextView subTv = GlassTheme.makeText(ctx, sub, 9.5f,
                GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
            subTv.setPadding(dp(ctx, 2), dp(ctx, 1), dp(ctx, 2), 0);
            card.addView(subTv, GlassTheme.matchWrapParams());
        }

        card.setOnClickListener(v -> {
            if (cb != null) cb.onResultClicked(item);
        });

        card.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                imgFrame.setBackground(GlassTheme.focusRounded(
                    GlassTheme.SURFACE_ELEV, Color.WHITE, dp(ctx, 2.5f), rad));
                imgFrame.setElevation(dp(ctx, 14));
                title.setTextColor(Color.WHITE);
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(160).setInterpolator(GlassTheme.EASE).start();
                scrollResultIntoView(overlay, (View) v.getParent());
            } else {
                imgFrame.setBackground(GlassTheme.focusRounded(
                    GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_LIGHT, dp(ctx, 1), rad));
                imgFrame.setElevation(0);
                title.setTextColor(GlassTheme.TEXT_PRIMARY);
                v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(GlassTheme.EASE).start();
            }
        });

        card.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (cb != null) cb.onDismiss();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                View target = getGridCardAt(gridRows, rowIdx, colIdx + 1);
                if (target != null) target.requestFocus();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (colIdx > 0) {
                    View target = getGridCardAt(gridRows, rowIdx, colIdx - 1);
                    if (target != null) target.requestFocus();
                }
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
        overlay.animate().alpha(1f).setDuration(180).setInterpolator(GlassTheme.EASE).start();
    }

    private static void animateOut(FrameLayout overlay) {
        overlay.animate().alpha(0f).setDuration(150).setInterpolator(GlassTheme.EASE)
               .withEndAction(() -> overlay.setVisibility(View.GONE)).start();
    }

    private static int dp(Context ctx, float v) {
        return GlassTheme.dp(ctx, v);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findTagged(View root, int tag) {
        return (T) root.findViewWithTag(tag);
    }
}
