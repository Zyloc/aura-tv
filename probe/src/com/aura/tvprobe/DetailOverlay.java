package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Right slide-over panel showing full show/movie details when a poster
 * is selected.  All methods are static; state lives in tagged child
 * views of the root FrameLayout.
 */
public final class DetailOverlay {

    private DetailOverlay() { }

    // ── Child-view tags ──────────────────────────────────────────────────
    private static final int TAG_SCROLL    = 0xDE_0001;
    private static final int TAG_PLATFORM  = 0xDE_0002;
    private static final int TAG_META      = 0xDE_0003;
    private static final int TAG_TITLE     = 0xDE_0004;
    private static final int TAG_GENRES    = 0xDE_0005;
    private static final int TAG_ACTIONS   = 0xDE_0006;
    private static final int TAG_RATING    = 0xDE_0007;
    private static final int TAG_REASON    = 0xDE_0008;
    private static final int TAG_SYNOPSIS  = 0xDE_0009;
    private static final int TAG_SIMILAR   = 0xDE_000A;

    // ── Dimensions (dp) ──────────────────────────────────────────────────
    private static final int PANEL_W   = 580;
    private static final int RADIUS    = 20;
    private static final int PAD       = 28;
    private static final int RATE_SIZE = 32;
    private static final int RATE_H    = 28;

    // ── Callback ─────────────────────────────────────────────────────────

    public interface DetailCallback {
        void onWatchClicked(JSONObject item);
        void onWatchedToggle(JSONObject item);
        void onSimilarClicked(JSONObject item);
        void onRated(JSONObject item, int score);
        void onTraversalClicked(JSONObject similarItem);
        void onDismiss();
    }

    // ── Build ────────────────────────────────────────────────────────────

    /** Creates the overlay root (initially GONE, right-aligned full height). */
    public static FrameLayout build(Context ctx, DetailCallback cb) {
        FrameLayout root = createRoot(ctx, cb);
        root.addView(buildPanel(ctx));
        return root;
    }

    // ── Show / Dismiss / Visible ─────────────────────────────────────────

    public static void showItem(FrameLayout overlay, JSONObject item,
                                boolean isWatched, int userRating,
                                Context ctx, DetailCallback cb) {
        bindContent(overlay, item, isWatched, userRating, ctx, cb);
        overlay.setVisibility(View.VISIBLE);
        animateIn(overlay, ctx);
        LinearLayout actions = findTagged(overlay, TAG_ACTIONS);
        if (actions != null && actions.getChildCount() > 0) {
            View firstBtn = actions.getChildAt(0);
            firstBtn.post(firstBtn::requestFocus);
        }
    }

    public static void dismiss(FrameLayout overlay) {
        animateOut(overlay);
    }

    public static boolean isVisible(FrameLayout overlay) {
        return overlay.getVisibility() == View.VISIBLE;
    }

    public static void setSimilarItems(FrameLayout overlay, JSONArray similar,
                                       Context ctx, DetailCallback cb) {
        LinearLayout container = findTagged(overlay, TAG_SIMILAR);
        if (container == null) return;
        populateSimilar(container, similar, ctx, cb);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Panel construction
    // ═══════════════════════════════════════════════════════════════════

    private static FrameLayout createRoot(Context ctx, DetailCallback cb) {
        FrameLayout root = new FrameLayout(ctx);
        root.setVisibility(View.GONE);
        root.setClickable(true);
        root.setOnClickListener(v -> { dismiss(root); cb.onDismiss(); });
        attachKeyHandler(root, cb);
        return root;
    }

    private static void attachKeyHandler(FrameLayout root, DetailCallback cb) {
        root.setFocusable(true);
        root.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                dismiss(root);
                cb.onDismiss();
                return true;
            }
            return false;
        });
    }

    private static ScrollView buildPanel(Context ctx) {
        ScrollView sv = createPanelScroll(ctx);
        LinearLayout col = createPanelColumn(ctx);
        addAllSections(col, ctx);
        sv.addView(col);
        return sv;
    }

    private static ScrollView createPanelScroll(Context ctx) {
        int w = dp(ctx, PANEL_W);
        ScrollView sv = new ScrollView(ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                w, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.END;
        sv.setLayoutParams(lp);
        sv.setVerticalScrollBarEnabled(false);
        sv.setTag(TAG_SCROLL);
        sv.setClickable(true);
        applyPanelBackground(sv, ctx);
        return sv;
    }

    private static void applyPanelBackground(ScrollView sv, Context ctx) {
        GradientDrawable bg = GlassTheme.glassPanel(dp(ctx, RADIUS));
        float r = dp(ctx, RADIUS);
        bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        sv.setBackground(bg);
        sv.setElevation(dp(ctx, 16));
    }

    private static LinearLayout createPanelColumn(Context ctx) {
        int pad = dp(ctx, PAD);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        col.setLayoutParams(GlassTheme.matchWrapParams());
        return col;
    }

    private static void addAllSections(LinearLayout col, Context ctx) {
        col.addView(taggedText(ctx, TAG_PLATFORM, "", 10f,
                Color.WHITE, GlassTheme.FONT_MEDIUM));
        col.addView(taggedText(ctx, TAG_META, "", 13f,
                GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_REGULAR));
        col.addView(taggedText(ctx, TAG_TITLE, "", 30f,
                GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD));
        col.addView(taggedText(ctx, TAG_GENRES, "", 13f,
                GlassTheme.TEXT_MUTED, GlassTheme.FONT_REGULAR));
        col.addView(buildActionsRow(ctx));
        col.addView(buildRatingSection(ctx));
        col.addView(buildReasonSection(ctx));
        col.addView(buildSynopsisSection(ctx));
        col.addView(buildSimilarSection(ctx));
    }

    // ── Tagged text helper ───────────────────────────────────────────────

    private static TextView taggedText(Context ctx, int tag, String text,
                                       float sp, int color,
                                       android.graphics.Typeface face) {
        TextView tv = GlassTheme.makeText(ctx, text, sp, color, face, 3);
        tv.setTag(tag);
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.bottomMargin = dp(ctx, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ── Actions row ──────────────────────────────────────────────────────

    private static LinearLayout buildActionsRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setTag(TAG_ACTIONS);
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.topMargin = dp(ctx, 12);
        lp.bottomMargin = dp(ctx, 16);
        row.setLayoutParams(lp);
        return row;
    }

    private static void populateActions(LinearLayout row, JSONObject item,
                                        boolean isWatched, Context ctx,
                                        DetailCallback cb) {
        row.removeAllViews();
        addWatchButton(row, item, ctx, cb);
        addWatchedToggle(row, item, isWatched, ctx, cb);
        addSimilarButton(row, item, ctx, cb);
        addBackButton(row, ctx, cb);
    }

    private static void addWatchButton(LinearLayout row, JSONObject item,
                                       Context ctx, DetailCallback cb) {
        String app = item.optString("app", "");
        String label = app.isEmpty() ? "▶ Watch" : "▶ Watch on " + app;
        TextView btn = GlassTheme.makePrimaryButton(ctx, label);
        btn.setOnClickListener(v -> cb.onWatchClicked(item));
        addBtnToRow(row, btn, ctx);
    }

    private static void addWatchedToggle(LinearLayout row, JSONObject item,
                                         boolean isWatched, Context ctx,
                                         DetailCallback cb) {
        String label = isWatched ? "✓ Watched" : "✓ Unwatched";
        TextView btn = GlassTheme.makeGlassButton(ctx, label);
        btn.setOnClickListener(v -> cb.onWatchedToggle(item));
        addBtnToRow(row, btn, ctx);
    }

    private static void addSimilarButton(LinearLayout row, JSONObject item,
                                         Context ctx, DetailCallback cb) {
        TextView btn = GlassTheme.makeGlassButton(ctx, "→ Similar");
        btn.setOnClickListener(v -> cb.onSimilarClicked(item));
        addBtnToRow(row, btn, ctx);
    }

    private static void addBackButton(LinearLayout row, Context ctx,
                                      DetailCallback cb) {
        TextView btn = GlassTheme.makeGlassButton(ctx, "← Back");
        btn.setOnClickListener(v -> cb.onDismiss());
        addBtnToRow(row, btn, ctx);
    }

    private static void addBtnToRow(LinearLayout row, TextView btn, Context ctx) {
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.rightMargin = dp(ctx, 8);
        btn.setLayoutParams(lp);
        applyFocusHighlight(btn);
        row.addView(btn);
    }

    // ── Rating section ───────────────────────────────────────────────────

    private static LinearLayout buildRatingSection(Context ctx) {
        LinearLayout section = new LinearLayout(ctx);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setTag(TAG_RATING);
        section.addView(GlassTheme.makeLabel(ctx, "RATE THIS SHOW (1-10)"));
        section.addView(buildRatingRow(ctx));
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.bottomMargin = dp(ctx, 16);
        section.setLayoutParams(lp);
        return section;
    }

    private static LinearLayout buildRatingRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 6), 0, 0);
        for (int i = 1; i <= 10; i++) row.addView(buildRateButton(i, ctx));
        return row;
    }

    private static TextView buildRateButton(int num, Context ctx) {
        TextView btn = GlassTheme.makeText(ctx, String.valueOf(num), 12f,
                GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1);
        btn.setGravity(Gravity.CENTER);
        int w = dp(ctx, RATE_SIZE), h = dp(ctx, RATE_H);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = dp(ctx, 4);
        btn.setLayoutParams(lp);
        btn.setBackground(GlassTheme.glassCard(dp(ctx, 6)));
        btn.setFocusable(true);
        applyFocusHighlight(btn);
        return btn;
    }

    private static void applyRating(LinearLayout section, JSONObject item,
                                    int userRating, DetailCallback cb) {
        if (section.getChildCount() < 2) return;
        LinearLayout row = (LinearLayout) section.getChildAt(1);
        for (int i = 0; i < row.getChildCount(); i++) {
            int score = i + 1;
            bindRateButton((TextView) row.getChildAt(i), score, userRating);
            row.getChildAt(i).setOnClickListener(v -> cb.onRated(item, score));
        }
    }

    private static void bindRateButton(TextView btn, int score, int active) {
        boolean selected = score == active;
        boolean highlight = score <= active && active > 0;
        int bg = selected ? GlassTheme.GOLD_ACCENT
               : highlight ? GlassTheme.SURFACE_ELEV : GlassTheme.GLASS_CARD;
        int fg = selected ? GlassTheme.TEXT_ON_LIGHT : GlassTheme.TEXT_SECONDARY;
        btn.setBackground(GlassTheme.solidRounded(bg, 6));
        btn.setTextColor(fg);
    }

    // ── Reason section ───────────────────────────────────────────────────

    private static LinearLayout buildReasonSection(Context ctx) {
        LinearLayout section = new LinearLayout(ctx);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setTag(TAG_REASON);
        addReasonLabel(section, ctx);
        addReasonCard(section, ctx);
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.bottomMargin = dp(ctx, 16);
        section.setLayoutParams(lp);
        return section;
    }

    private static void addReasonLabel(LinearLayout section, Context ctx) {
        TextView lbl = GlassTheme.makeLabel(ctx, "WHY AURA RECOMMENDS THIS");
        lbl.setTextColor(GlassTheme.GOLD_ACCENT);
        section.addView(lbl);
    }

    private static void addReasonCard(LinearLayout section, Context ctx) {
        TextView card = GlassTheme.makeText(ctx, "", 14f,
                GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_REGULAR, 8);
        card.setBackground(GlassTheme.glassSubtle(dp(ctx, 10)));
        card.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10));
        card.setLineSpacing(0, 1.6f);
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.topMargin = dp(ctx, 6);
        card.setLayoutParams(lp);
        section.addView(card);
    }

    private static void setReasonText(LinearLayout section, JSONObject item) {
        if (section.getChildCount() < 2) return;
        TextView card = (TextView) section.getChildAt(1);
        String reason = item.optString("reason", "");
        if (reason.isEmpty()) reason = buildFallbackReason(item);
        card.setText(reason);
    }

    private static String buildFallbackReason(JSONObject item) {
        StringBuilder sb = new StringBuilder("Trending");
        double score = item.optDouble("imdb_score", 0);
        if (score > 0) sb.append(" · ★ ").append(String.format("%.1f", score));
        return sb.toString();
    }

    // ── Synopsis section ─────────────────────────────────────────────────

    private static LinearLayout buildSynopsisSection(Context ctx) {
        LinearLayout section = new LinearLayout(ctx);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setTag(TAG_SYNOPSIS);
        section.addView(GlassTheme.makeLabel(ctx, "STORY"));
        addSynopsisText(section, ctx);
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.bottomMargin = dp(ctx, 16);
        section.setLayoutParams(lp);
        return section;
    }

    private static void addSynopsisText(LinearLayout section, Context ctx) {
        TextView tv = GlassTheme.makeText(ctx, "", 13f,
                GlassTheme.TEXT_MUTED, GlassTheme.FONT_REGULAR, 6);
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.topMargin = dp(ctx, 4);
        tv.setLayoutParams(lp);
        section.addView(tv);
    }

    // ── Similar section ──────────────────────────────────────────────────

    private static LinearLayout buildSimilarSection(Context ctx) {
        LinearLayout section = new LinearLayout(ctx);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setTag(TAG_SIMILAR);
        section.addView(GlassTheme.makeLabel(ctx, "EXPLORE SIMILAR"));
        return section;
    }

    private static void populateSimilar(LinearLayout container, JSONArray items,
                                        Context ctx, DetailCallback cb) {
        clearSimilarCards(container);
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject sim = items.optJSONObject(i);
            if (sim != null) container.addView(buildSimilarCard(sim, ctx, cb));
        }
    }

    private static void clearSimilarCards(LinearLayout container) {
        while (container.getChildCount() > 1) {
            container.removeViewAt(container.getChildCount() - 1);
        }
    }

    private static LinearLayout buildSimilarCard(JSONObject sim, Context ctx,
                                                 DetailCallback cb) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(GlassTheme.glassCard(dp(ctx, 10)));
        styleSimilarCard(card, ctx);
        addSimilarText(card, sim, ctx);
        addArrow(card, ctx);
        card.setOnClickListener(v -> cb.onTraversalClicked(sim));
        return card;
    }

    private static void styleSimilarCard(LinearLayout card, Context ctx) {
        int pad = dp(ctx, 12);
        float r = dp(ctx, 10);
        card.setPadding(pad, dp(ctx, 10), pad, dp(ctx, 10));
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.topMargin = dp(ctx, 6);
        card.setLayoutParams(lp);
        card.setFocusable(true);
        card.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                card.setBackground(GlassTheme.focusRounded(
                    GlassTheme.SURFACE_ELEV, Color.WHITE, dp(ctx, 2), r));
            } else {
                card.setBackground(GlassTheme.glassCard(r));
            }
        });
    }

    private static void addSimilarText(LinearLayout card, JSONObject sim,
                                       Context ctx) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(GlassTheme.weightParams(1f));
        String titleYear = sim.optString("title", "") + " (" +
                           sim.optInt("year", 0) + ")";
        col.addView(GlassTheme.makeText(ctx, titleYear, 13f,
                GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1));
        col.addView(GlassTheme.makeMuted(ctx, buildSimilarMeta(sim)));
        card.addView(col);
    }

    private static String buildSimilarMeta(JSONObject sim) {
        StringBuilder sb = new StringBuilder();
        double score = sim.optDouble("imdb_score", 0);
        if (score > 0) sb.append("★ ").append(String.format("%.1f", score));
        String app = sim.optString("app", "");
        if (!app.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(app);
        }
        return sb.toString();
    }

    private static void addArrow(LinearLayout card, Context ctx) {
        TextView arrow = GlassTheme.makeText(ctx, "→", 18f,
                GlassTheme.TEXT_MUTED, GlassTheme.FONT_REGULAR, 1);
        card.addView(arrow);
    }

    // ── Data binding ─────────────────────────────────────────────────────

    private static void bindContent(FrameLayout overlay, JSONObject item,
                                    boolean isWatched, int userRating,
                                    Context ctx, DetailCallback cb) {
        bindPlatformTag(overlay, item, ctx);
        bindMetaLine(overlay, item);
        bindTitle(overlay, item);
        bindGenres(overlay, item);
        bindActions(overlay, item, isWatched, ctx, cb);
        bindRating(overlay, item, userRating, cb);
        bindReason(overlay, item);
        bindSynopsis(overlay, item);
    }

    private static void bindPlatformTag(FrameLayout overlay, JSONObject item,
                                        Context ctx) {
        TextView tv = findTagged(overlay, TAG_PLATFORM);
        if (tv == null) return;
        String app = item.optString("app", "");
        tv.setText(app.toUpperCase());
        int color = resolvePlatformColor(app);
        tv.setBackground(GlassTheme.solidRounded(color, dp(ctx, 4)));
        tv.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
    }

    private static void bindMetaLine(FrameLayout overlay, JSONObject item) {
        TextView tv = findTagged(overlay, TAG_META);
        if (tv != null) tv.setText(buildMetaString(item));
    }

    private static void bindTitle(FrameLayout overlay, JSONObject item) {
        TextView tv = findTagged(overlay, TAG_TITLE);
        if (tv != null) tv.setText(item.optString("title", ""));
    }

    private static void bindGenres(FrameLayout overlay, JSONObject item) {
        TextView tv = findTagged(overlay, TAG_GENRES);
        if (tv != null) tv.setText(item.optString("genres", ""));
    }

    private static void bindActions(FrameLayout overlay, JSONObject item,
                                    boolean isWatched, Context ctx,
                                    DetailCallback cb) {
        LinearLayout row = findTagged(overlay, TAG_ACTIONS);
        if (row != null) populateActions(row, item, isWatched, ctx, cb);
    }

    private static void bindRating(FrameLayout overlay, JSONObject item,
                                   int userRating, DetailCallback cb) {
        LinearLayout section = findTagged(overlay, TAG_RATING);
        if (section != null) applyRating(section, item, userRating, cb);
    }

    private static void bindReason(FrameLayout overlay, JSONObject item) {
        LinearLayout section = findTagged(overlay, TAG_REASON);
        if (section != null) setReasonText(section, item);
    }

    private static void bindSynopsis(FrameLayout overlay, JSONObject item) {
        LinearLayout section = findTagged(overlay, TAG_SYNOPSIS);
        if (section == null || section.getChildCount() < 2) return;
        ((TextView) section.getChildAt(1))
                .setText(item.optString("synopsis", ""));
    }

    // ── Meta string builder ──────────────────────────────────────────────

    private static String buildMetaString(JSONObject item) {
        StringBuilder sb = new StringBuilder();
        appendField(sb, item, "year");
        appendField(sb, item, "type");
        appendRuntime(sb, item);
        appendScore(sb, item);
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, JSONObject o, String key) {
        String v = o.optString(key, "");
        if (!v.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(v);
        }
    }

    private static void appendRuntime(StringBuilder sb, JSONObject item) {
        int rt = item.optInt("runtime_min", 0);
        if (rt > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(rt).append("m");
        }
    }

    private static void appendScore(StringBuilder sb, JSONObject item) {
        double sc = item.optDouble("imdb_score", 0);
        if (sc > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("★ ").append(String.format("%.1f", sc));
        }
    }

    // ── Animations ───────────────────────────────────────────────────────

    private static void animateIn(FrameLayout overlay, Context ctx) {
        ScrollView panel = findTagged(overlay, TAG_SCROLL);
        if (panel == null) return;
        int dist = dp(ctx, PANEL_W);
        panel.setTranslationX(dist);
        panel.animate().translationX(0f)
             .setDuration(300).setInterpolator(GlassTheme.EASE).start();
    }

    private static void animateOut(FrameLayout overlay) {
        ScrollView panel = findTagged(overlay, TAG_SCROLL);
        if (panel == null) { overlay.setVisibility(View.GONE); return; }
        float dist = panel.getWidth();
        panel.animate().translationX(dist)
             .setDuration(250).setInterpolator(GlassTheme.EASE)
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
        return HeroSliderBuilder.getPlatformColor(app);
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
