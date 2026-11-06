package com.aura.tvprobe;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/**
 * Frosted Dark-Glass Agentic Search Overlay for Aura TV.
 * Traps D-pad focus inside the overlay, auto-focuses the first result card when loaded,
 * and uses verified platform badge colors with zero white-on-white bleed.
 */
public final class SearchOverlay {

    private SearchOverlay() { }

    private static final int TAG_LOADING   = 0x5E_0001;
    private static final int TAG_RESULTS   = 0x5E_0002;
    private static final int TAG_CHIPS     = 0x5E_0003;
    private static final int TAG_MESSAGES  = 0x5E_0004;
    private static final int TAG_CLOSE     = 0x5E_0005;
    private static final int TAG_SCROLL    = 0x5E_0006;

    private static final int OVERLAY_W     = 820;
    private static final int OVERLAY_MAX_H = 455;
    private static final int RADIUS        = 16;
    private static final int THUMB_W       = 46;
    private static final int THUMB_H       = 66;
    private static final int PAD           = 16;

    public interface SearchCallback {
        void onResultClicked(JSONObject item);
        void onFollowUpClicked(String query);
        void onDismiss();
    }

    public static FrameLayout build(Context ctx, SearchCallback cb) {
        FrameLayout root = createRoot(ctx, cb);
        root.addView(buildInnerCard(ctx, cb));
        return root;
    }

    public static void show(FrameLayout overlay, Context ctx) {
        LinearLayout msgs = findTagged(overlay, TAG_MESSAGES);
        if (msgs != null) msgs.removeAllViews();
        overlay.setVisibility(View.VISIBLE);
        animateIn(overlay);
        View closeBtn = overlay.findViewWithTag(TAG_CLOSE);
        if (closeBtn != null) closeBtn.post(closeBtn::requestFocus);
    }

    public static void dismiss(FrameLayout overlay) {
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

    public static void setResults(FrameLayout overlay, JSONArray results,
                                  Context ctx, SearchCallback cb) {
        LinearLayout list = findTagged(overlay, TAG_RESULTS);
        if (list == null) return;
        populateResults(overlay, list, results, ctx, cb);
        if (list.getChildCount() > 0) {
            View first = list.getChildAt(0);
            first.post(first::requestFocus);
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

    private static LinearLayout buildInnerCard(Context ctx, SearchCallback cb) {
        LinearLayout card = createCardContainer(ctx);
        card.addView(buildHeaderRow(ctx, cb));
        card.addView(buildLoadingRow(ctx));
        card.addView(buildMessagesContainer(ctx));
        card.addView(buildChipsScroll(ctx));
        card.addView(buildResultsScroll(ctx));
        return card;
    }

    private static LinearLayout buildHeaderRow(Context ctx, SearchCallback cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(ctx, 8));

        TextView sourceHdr = GlassTheme.makeText(ctx,
            "\u2726 Agent Search \u00B7 Live JustWatch India Graph + Gemini AI + Your Watch History",
            11f, GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1);
        row.addView(sourceHdr, GlassTheme.weightParams(1f));

        TextView closeBtn = GlassTheme.makeGlassButton(ctx, "\u2715 Close");
        closeBtn.setTag(TAG_CLOSE);
        closeBtn.setTextSize(10.5f);
        closeBtn.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        closeBtn.setOnClickListener(v -> {
            if (cb != null) cb.onDismiss();
        });
        bindOverlayBackKey(closeBtn, cb);
        row.addView(closeBtn, GlassTheme.wrapParams());
        return row;
    }

    private static LinearLayout createCardContainer(Context ctx) {
        int dp = GlassTheme.dp(ctx, 1);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GlassTheme.glassPanel(RADIUS * dp));
        card.setElevation(GlassTheme.dp(ctx, 24));
        card.setPadding(PAD * dp, dp(ctx, 12), PAD * dp, dp(ctx, 12));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        card.setLayoutParams(lp);
        card.setClickable(true);
        return card;
    }

    private static LinearLayout buildLoadingRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 4), 0, dp(ctx, 6));
        row.setTag(TAG_LOADING);
        row.setVisibility(View.GONE);
        int size = GlassTheme.dp(ctx, 16);
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

    private static ScrollView buildResultsScroll(Context ctx) {
        RailBuilder.SmoothVerticalScrollView sv = new RailBuilder.SmoothVerticalScrollView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(ctx, 6);
        sv.setLayoutParams(lp);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFocusable(false);
        sv.setTag(TAG_SCROLL);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag(TAG_RESULTS);
        sv.addView(list, GlassTheme.matchWrapParams());
        return sv;
    }

    private static void populateResults(FrameLayout overlay, LinearLayout list,
                                        JSONArray results, Context ctx, SearchCallback cb) {
        list.removeAllViews();
        if (results == null) return;
        int n = results.length();
        for (int i = 0; i < n; i++) {
            JSONObject item = results.optJSONObject(i);
            if (item != null) {
                list.addView(buildResultCard(overlay, list, i, n, item, ctx, cb));
            }
        }
    }

    private static LinearLayout buildResultCard(FrameLayout overlay, LinearLayout list,
                                                int idx, int total, JSONObject item,
                                                Context ctx, SearchCallback cb) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7));
        styleResultCard(overlay, card, ctx);
        card.addView(buildThumbnailView(item, ctx));
        card.addView(buildResultTextBlock(item, ctx));
        card.setOnClickListener(v -> cb.onResultClicked(item));
        card.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK) {
                cb.onDismiss();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (idx + 1 < list.getChildCount()) {
                    list.getChildAt(idx + 1).requestFocus();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (idx > 0) {
                    list.getChildAt(idx - 1).requestFocus();
                } else {
                    focusChipsOrClose(overlay);
                }
                return true;
            }
            return false;
        });
        return card;
    }

    private static void focusChipsOrClose(FrameLayout overlay) {
        LinearLayout chips = findTagged(overlay, TAG_CHIPS);
        if (chips != null && chips.getVisibility() == View.VISIBLE && chips.getChildCount() > 0) {
            chips.getChildAt(0).requestFocus();
            return;
        }
        View close = overlay.findViewWithTag(TAG_CLOSE);
        if (close != null) close.requestFocus();
    }

    private static void styleResultCard(FrameLayout overlay, LinearLayout card, Context ctx) {
        float rad = dp(ctx, 10);
        card.setBackground(GlassTheme.glassSubtle(rad));
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.bottomMargin = dp(ctx, 6);
        card.setLayoutParams(lp);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                card.setBackground(GlassTheme.focusRounded(
                    GlassTheme.SURFACE_ELEV, Color.WHITE, dp(ctx, 2), rad));
                scrollResultIntoView(overlay, v, ctx);
            } else {
                card.setBackground(GlassTheme.glassSubtle(rad));
            }
        });
    }

    private static void scrollResultIntoView(FrameLayout overlay, View card, Context ctx) {
        ScrollView sv = findTagged(overlay, TAG_SCROLL);
        if (sv == null) return;
        int targetY = Math.max(0, card.getTop() - dp(ctx, 36));
        ObjectAnimator anim = ObjectAnimator.ofInt(sv, "scrollY", sv.getScrollY(), targetY);
        anim.setDuration(180);
        anim.setInterpolator(GlassTheme.EASE);
        anim.start();
    }

    private static View buildThumbnailView(JSONObject item, Context ctx) {
        ImageView thumb = new ImageView(ctx);
        int w = dp(ctx, THUMB_W), h = dp(ctx, THUMB_H);
        thumb.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(GlassTheme.SURFACE_ELEV);
        ImageLoader.applyRoundedCorners(thumb, dp(ctx, 6));
        String url = item.optString("poster_url", "");
        if (!url.isEmpty()) ImageLoader.load(ctx, url, thumb);
        return thumb;
    }

    private static LinearLayout buildResultTextBlock(JSONObject item, Context ctx) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(ctx, 12), 0, 0, 0);
        col.setLayoutParams(GlassTheme.weightParams(1f));

        LinearLayout topRow = new LinearLayout(ctx);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        String app = HeroSliderBuilder.cleanPlatformName(item.optString("app", ""));
        if (!app.isEmpty()) {
            topRow.addView(buildBadge(app, ctx));
        }
        String meta = buildMetaString(item);
        if (!meta.isEmpty()) {
            TextView metaTv = GlassTheme.makeText(ctx,
                (app.isEmpty() ? "" : "   ") + meta, 10.5f,
                GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_BOLD, 1);
            topRow.addView(metaTv, GlassTheme.wrapParams());
        }
        col.addView(topRow, GlassTheme.wrapParams());

        String title = item.optString("title", "Unknown");
        TextView titleTv = GlassTheme.makeText(ctx, title, 13.5f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        titleTv.setPadding(0, dp(ctx, 2), 0, dp(ctx, 1));
        col.addView(titleTv, GlassTheme.wrapParams());

        String reason = item.optString("reason", item.optString("synopsis", ""));
        if (!reason.isEmpty()) {
            TextView tv = GlassTheme.makeText(ctx, reason, 11f,
                GlassTheme.TEXT_MUTED, GlassTheme.FONT_REGULAR, 1);
            col.addView(tv, GlassTheme.matchWrapParams());
        }
        return col;
    }

    private static String buildMetaString(JSONObject item) {
        StringBuilder sb = new StringBuilder();
        double score = item.optDouble("imdb_score", 0);
        if (score > 0) sb.append("\u2605 ").append(String.format("%.1f", score));
        int year = item.optInt("year", 0);
        if (year > 0) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(year);
        }
        String type = item.optString("type", "");
        if (!type.isEmpty()) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(type);
        }
        int rt = item.optInt("runtime_min", 0);
        if (rt > 0) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(rt).append("m");
        }
        return sb.toString();
    }

    private static TextView buildBadge(String app, Context ctx) {
        int color = HeroSliderBuilder.getPlatformColor(app);
        TextView badge = GlassTheme.makeText(ctx, app.toUpperCase(), 8.5f,
                Color.WHITE, GlassTheme.FONT_BOLD, 1);
        badge.setBackground(GlassTheme.solidRounded(color, dp(ctx, 4)));
        badge.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
        return badge;
    }

    private static HorizontalScrollView buildChipsScroll(Context ctx) {
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFocusable(false);
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.topMargin = dp(ctx, 4);
        hsv.setLayoutParams(lp);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2));
        row.setTag(TAG_CHIPS);
        row.setVisibility(View.GONE);
        hsv.addView(row);
        return hsv;
    }

    private static void populateChips(FrameLayout overlay, LinearLayout row,
                                      List<String> chips, SearchCallback cb, Context ctx) {
        row.removeAllViews();
        if (chips == null || chips.isEmpty()) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);
        for (String chip : chips) {
            TextView btn = GlassTheme.makeGlassButton(ctx, "\u2192 " + chip);
            btn.setTextSize(10.5f);
            btn.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
            LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
            lp.rightMargin = dp(ctx, 6);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> cb.onFollowUpClicked(chip));
            btn.setOnKeyListener((v, code, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_BACK) {
                    cb.onDismiss();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    LinearLayout list = findTagged(overlay, TAG_RESULTS);
                    if (list != null && list.getChildCount() > 0) {
                        list.getChildAt(0).requestFocus();
                        return true;
                    }
                }
                return false;
            });
            row.addView(btn);
        }
    }

    private static LinearLayout buildMessagesContainer(Context ctx) {
        LinearLayout msgs = new LinearLayout(ctx);
        msgs.setOrientation(LinearLayout.VERTICAL);
        msgs.setTag(TAG_MESSAGES);
        msgs.setLayoutParams(GlassTheme.matchWrapParams());
        return msgs;
    }

    private static void appendMessage(LinearLayout msgs, String sender,
                                      String text, Context ctx) {
        if ("user".equalsIgnoreCase(sender)) return; // Keep overlay compact: show AI answer bubble
        msgs.removeAllViews();
        msgs.setVisibility(View.VISIBLE);
        TextView tv = GlassTheme.makeText(ctx, text, 11.5f, GlassTheme.TEXT_SECONDARY,
                GlassTheme.FONT_REGULAR, 2);
        tv.setBackground(GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, dp(ctx, 8)));
        tv.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        msgs.addView(tv, GlassTheme.matchWrapParams());
    }

    private static void bindOverlayBackKey(View v, SearchCallback cb) {
        v.setOnKeyListener((view, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BACK) {
                if (cb != null) cb.onDismiss();
                return true;
            }
            return false;
        });
    }

    private static void animateIn(FrameLayout overlay) {
        overlay.setAlpha(0f);
        overlay.setTranslationY(-dp(overlay.getContext(), 14));
        overlay.animate()
               .alpha(1f).translationY(0f)
               .setDuration(200).setInterpolator(GlassTheme.EASE)
               .start();
    }

    private static void animateOut(FrameLayout overlay) {
        overlay.animate()
               .alpha(0f).translationY(-dp(overlay.getContext(), 14))
               .setDuration(170).setInterpolator(GlassTheme.EASE)
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
