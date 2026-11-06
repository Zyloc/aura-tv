package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/**
 * Dropdown glass overlay for search results, follow-up chips, and
 * conversational messages.  All methods are static; the overlay's
 * state lives inside tagged child views of the root FrameLayout.
 */
public final class SearchOverlay {

    private SearchOverlay() { }

    // ── Child-view tags ──────────────────────────────────────────────────
    private static final int TAG_LOADING   = 0x5E_0001;
    private static final int TAG_RESULTS   = 0x5E_0002;
    private static final int TAG_CHIPS     = 0x5E_0003;
    private static final int TAG_MESSAGES  = 0x5E_0004;

    // ── Dimensions (dp) ──────────────────────────────────────────────────
    private static final int OVERLAY_W     = 800;
    private static final int OVERLAY_MAX_H = 600;
    private static final int RADIUS        = 16;
    private static final int THUMB_W       = 44;
    private static final int THUMB_H       = 64;
    private static final int PAD           = 16;

    // ── Callback ─────────────────────────────────────────────────────────

    public interface SearchCallback {
        void onResultClicked(JSONObject item);
        void onFollowUpClicked(String query);
        void onDismiss();
    }

    // ── Build ────────────────────────────────────────────────────────────

    /** Creates the overlay root (initially GONE). */
    public static FrameLayout build(Context ctx, SearchCallback cb) {
        FrameLayout root = createRoot(ctx, cb);
        root.addView(buildInnerCard(ctx));
        return root;
    }

    // ── Show / Dismiss / Visible ─────────────────────────────────────────

    public static void show(FrameLayout overlay, Context ctx) {
        overlay.setVisibility(View.VISIBLE);
        animateIn(overlay);
    }

    public static void dismiss(FrameLayout overlay) {
        animateOut(overlay);
    }

    public static boolean isVisible(FrameLayout overlay) {
        return overlay.getVisibility() == View.VISIBLE;
    }

    // ── Content setters ──────────────────────────────────────────────────

    public static void setLoading(FrameLayout overlay, boolean loading, String message) {
        View v = overlay.findViewWithTag(TAG_LOADING);
        if (v == null) return;
        updateLoadingState(v, loading, message);
    }

    public static void setResults(FrameLayout overlay, JSONArray results,
                                  Context ctx, SearchCallback cb) {
        LinearLayout list = findTagged(overlay, TAG_RESULTS);
        if (list == null) return;
        populateResults(list, results, ctx, cb);
    }

    public static void setFollowUps(FrameLayout overlay, List<String> chips,
                                    SearchCallback cb, Context ctx) {
        LinearLayout row = findTagged(overlay, TAG_CHIPS);
        if (row == null) return;
        populateChips(row, chips, cb, ctx);
    }

    public static void addMessage(FrameLayout overlay, String sender,
                                  String text, Context ctx) {
        LinearLayout msgs = findTagged(overlay, TAG_MESSAGES);
        if (msgs == null) return;
        appendMessage(msgs, sender, text, ctx);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Private construction helpers
    // ═══════════════════════════════════════════════════════════════════

    private static FrameLayout createRoot(Context ctx, SearchCallback cb) {
        FrameLayout root = new FrameLayout(ctx);
        root.setVisibility(View.GONE);
        root.setClickable(true);
        root.setOnClickListener(v -> dismiss(root));
        attachKeyHandler(root, cb);
        return root;
    }

    private static void attachKeyHandler(FrameLayout root, SearchCallback cb) {
        root.setFocusable(true);
        root.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK) {
                dismiss(root);
                if (cb != null) cb.onDismiss();
                return true;
            }
            return false;
        });
    }

    private static LinearLayout buildInnerCard(Context ctx) {
        LinearLayout card = createCardContainer(ctx);
        card.addView(buildLoadingRow(ctx));
        card.addView(buildResultsScroll(ctx));
        card.addView(buildChipsScroll(ctx));
        card.addView(buildMessagesContainer(ctx));
        return card;
    }

    private static LinearLayout createCardContainer(Context ctx) {
        int dp = GlassTheme.dp(ctx, 1);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GlassTheme.glassPanel(RADIUS * dp));
        card.setElevation(GlassTheme.dp(ctx, 12));
        card.setPadding(PAD * dp, PAD * dp, PAD * dp, PAD * dp);
        configureCardLayout(card, ctx);
        return card;
    }

    private static void configureCardLayout(LinearLayout card, Context ctx) {
        int w = GlassTheme.dp(ctx, OVERLAY_W);
        int h = GlassTheme.dp(ctx, OVERLAY_MAX_H);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.TOP | Gravity.START;
        card.setLayoutParams(lp);
        card.setClickable(true);
    }

    // ── Loading row ──────────────────────────────────────────────────────

    private static LinearLayout buildLoadingRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(TAG_LOADING);
        row.setVisibility(View.GONE);
        addProgressAndLabel(row, ctx);
        return row;
    }

    private static void addProgressAndLabel(LinearLayout row, Context ctx) {
        int size = GlassTheme.dp(ctx, 18);
        ProgressBar pb = new ProgressBar(ctx);
        pb.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        pb.getIndeterminateDrawable().setTint(GlassTheme.ACCENT);
        row.addView(pb);
        TextView lbl = GlassTheme.makeMuted(ctx, "");
        lbl.setPadding(GlassTheme.dp(ctx, 8), 0, 0, 0);
        row.addView(lbl);
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

    // ── Results scroll ───────────────────────────────────────────────────

    private static ScrollView buildResultsScroll(Context ctx) {
        ScrollView sv = new ScrollView(ctx);
        sv.setLayoutParams(GlassTheme.matchWrapParams());
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag(TAG_RESULTS);
        sv.addView(list);
        return sv;
    }

    private static void populateResults(LinearLayout list, JSONArray results,
                                        Context ctx, SearchCallback cb) {
        list.removeAllViews();
        if (results == null) return;
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item != null) list.addView(buildResultCard(item, ctx, cb));
        }
    }

    // ── Single result card ───────────────────────────────────────────────

    private static LinearLayout buildResultCard(JSONObject item, Context ctx,
                                                SearchCallback cb) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        styleResultCard(card, ctx);
        addResultContent(card, item, ctx);
        card.setOnClickListener(v -> cb.onResultClicked(item));
        return card;
    }

    private static void styleResultCard(LinearLayout card, Context ctx) {
        card.setBackground(GlassTheme.glassSubtle(dp(ctx, 10)));
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.bottomMargin = dp(ctx, 6);
        card.setLayoutParams(lp);
        card.setFocusable(true);
        applyFocusHighlight(card);
    }

    private static void addResultContent(LinearLayout card, JSONObject item,
                                         Context ctx) {
        card.addView(buildThumbnailPlaceholder(ctx));
        card.addView(buildResultTextBlock(item, ctx));
    }

    private static View buildThumbnailPlaceholder(Context ctx) {
        View thumb = new View(ctx);
        int w = dp(ctx, THUMB_W), h = dp(ctx, THUMB_H);
        thumb.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        thumb.setBackground(GlassTheme.solidRounded(GlassTheme.BORDER_LIGHT, dp(ctx, 6)));
        return thumb;
    }

    private static LinearLayout buildResultTextBlock(JSONObject item, Context ctx) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(ctx, 10), 0, 0, 0);
        col.setLayoutParams(GlassTheme.weightParams(1f));
        addResultTitle(col, item, ctx);
        addResultMeta(col, item, ctx);
        addPlatformBadge(col, item, ctx);
        addResultReason(col, item, ctx);
        return col;
    }

    private static void addResultTitle(LinearLayout col, JSONObject item, Context ctx) {
        String title = item.optString("title", "Unknown");
        col.addView(GlassTheme.makeText(ctx, title, 13f,
                GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1));
    }

    private static void addResultMeta(LinearLayout col, JSONObject item, Context ctx) {
        String meta = buildMetaString(item);
        if (!meta.isEmpty()) col.addView(GlassTheme.makeMuted(ctx, meta));
    }

    private static String buildMetaString(JSONObject item) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, item, "year", "");
        appendIfPresent(sb, item, "type", "");
        appendScore(sb, item);
        appendRuntime(sb, item);
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, JSONObject o,
                                        String key, String suffix) {
        String v = o.optString(key, "");
        if (!v.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(v).append(suffix);
        }
    }

    private static void appendScore(StringBuilder sb, JSONObject item) {
        double score = item.optDouble("imdb_score", 0);
        if (score > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("★ ").append(String.format("%.1f", score));
        }
    }

    private static void appendRuntime(StringBuilder sb, JSONObject item) {
        int rt = item.optInt("runtime_min", 0);
        if (rt > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(rt).append("m");
        }
    }

    private static void addPlatformBadge(LinearLayout col, JSONObject item,
                                         Context ctx) {
        String app = item.optString("app", "");
        if (app.isEmpty()) return;
        col.addView(buildBadge(app, ctx));
    }

    private static TextView buildBadge(String app, Context ctx) {
        int color = resolvePlatformColor(app);
        TextView badge = GlassTheme.makeText(ctx, app.toUpperCase(), 9f,
                Color.WHITE, GlassTheme.FONT_MEDIUM, 1);
        badge.setBackground(GlassTheme.solidRounded(color, dp(ctx, 4)));
        badge.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.topMargin = dp(ctx, 3);
        badge.setLayoutParams(lp);
        return badge;
    }

    private static void addResultReason(LinearLayout col, JSONObject item,
                                        Context ctx) {
        String reason = item.optString("reason", "");
        if (reason.isEmpty()) return;
        TextView tv = GlassTheme.makeText(ctx, reason, 11f,
                GlassTheme.TEXT_MUTED, GlassTheme.FONT_REGULAR, 1);
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.topMargin = dp(ctx, 2);
        tv.setLayoutParams(lp);
        col.addView(tv);
    }

    // ── Follow-up chips ──────────────────────────────────────────────────

    private static HorizontalScrollView buildChipsScroll(Context ctx) {
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setLayoutParams(GlassTheme.matchWrapParams());
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setTag(TAG_CHIPS);
        row.setVisibility(View.GONE);
        hsv.addView(row);
        return hsv;
    }

    private static void populateChips(LinearLayout row, List<String> chips,
                                      SearchCallback cb, Context ctx) {
        row.removeAllViews();
        if (chips == null || chips.isEmpty()) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);
        for (String chip : chips) row.addView(buildChip(chip, cb, ctx));
    }

    private static TextView buildChip(String text, SearchCallback cb, Context ctx) {
        TextView chip = GlassTheme.makeGlassButton(ctx, "→ " + text);
        chip.setTextSize(12f);
        chip.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.rightMargin = dp(ctx, 6);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> cb.onFollowUpClicked(text));
        applyFocusHighlight(chip);
        return chip;
    }

    // ── Conversation messages ────────────────────────────────────────────

    private static LinearLayout buildMessagesContainer(Context ctx) {
        LinearLayout msgs = new LinearLayout(ctx);
        msgs.setOrientation(LinearLayout.VERTICAL);
        msgs.setTag(TAG_MESSAGES);
        msgs.setLayoutParams(GlassTheme.matchWrapParams());
        return msgs;
    }

    private static void appendMessage(LinearLayout msgs, String sender,
                                      String text, Context ctx) {
        msgs.setVisibility(View.VISIBLE);
        boolean isUser = "user".equalsIgnoreCase(sender);
        msgs.addView(buildBubble(text, isUser, ctx));
    }

    private static TextView buildBubble(String text, boolean isUser, Context ctx) {
        int bg = isUser ? GlassTheme.ACCENT : GlassTheme.GLASS_CARD;
        int fg = isUser ? Color.WHITE : GlassTheme.TEXT_PRIMARY;
        TextView tv = GlassTheme.makeText(ctx, text, 13f, fg,
                GlassTheme.FONT_REGULAR, 10);
        tv.setBackground(GlassTheme.solidRounded(bg, dp(ctx, 12)));
        tv.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        configureBubbleLayout(tv, isUser, ctx);
        return tv;
    }

    private static void configureBubbleLayout(TextView tv, boolean isUser,
                                              Context ctx) {
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.gravity = isUser ? Gravity.END : Gravity.START;
        lp.topMargin = dp(ctx, 4);
        lp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
        tv.setLayoutParams(lp);
    }

    // ── Animations ───────────────────────────────────────────────────────

    private static void animateIn(FrameLayout overlay) {
        overlay.setAlpha(0f);
        overlay.setTranslationY(-dp(overlay.getContext(), 20));
        overlay.animate()
               .alpha(1f).translationY(0f)
               .setDuration(250).setInterpolator(GlassTheme.EASE)
               .start();
    }

    private static void animateOut(FrameLayout overlay) {
        overlay.animate()
               .alpha(0f).translationY(-dp(overlay.getContext(), 20))
               .setDuration(200).setInterpolator(GlassTheme.EASE)
               .withEndAction(() -> overlay.setVisibility(View.GONE))
               .start();
    }

    // ── Focus highlight ──────────────────────────────────────────────────

    private static void applyFocusHighlight(View v) {
        v.setOnFocusChangeListener((view, focused) ->
            view.setAlpha(focused ? 1f : 0.85f));
    }

    // ── Platform colors ──────────────────────────────────────────────────

    private static int resolvePlatformColor(String app) {
        if (app == null) return GlassTheme.ACCENT;
        switch (app.toLowerCase()) {
            case "netflix":  return GlassTheme.COLOR_NETFLIX;
            case "prime":    return GlassTheme.COLOR_PRIME;
            case "hotstar":  return GlassTheme.COLOR_HOTSTAR;
            case "apple tv": return GlassTheme.COLOR_APPLE_TV;
            case "youtube":  return GlassTheme.COLOR_YOUTUBE;
            case "sonyliv":  return GlassTheme.COLOR_SONYLIV;
            case "zee5":     return GlassTheme.COLOR_ZEE5;
            default:         return GlassTheme.ACCENT;
        }
    }

    // ── Shorthand ────────────────────────────────────────────────────────

    private static int dp(Context ctx, float v) {
        return GlassTheme.dp(ctx, v);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findTagged(View root, int tag) {
        return (T) root.findViewWithTag(tag);
    }
}
