package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Full-bleed, AI-first Cinema Detail Canvas (zero outer card boxes or duplicate chrome).
 * Renders edge-to-edge UHD backdrop artwork behind dual directional scrims,
 * one tight metadata line, bold display title, AI taste pitch, 3 primary actions +
 * 3-tap emotional reaction row, and a minimalist bottom "More Like This" dock.
 */
public final class DetailOverlay {

    private DetailOverlay() { }

    private static final int TAG_PANEL       = 0xDE_0001;
    private static final int TAG_META        = 0xDE_0004;
    private static final int TAG_TITLE       = 0xDE_0005;
    private static final int TAG_SYNOPSIS    = 0xDE_0006;
    private static final int TAG_REASON      = 0xDE_0007;
    private static final int TAG_ACTIONS     = 0xDE_0008;
    private static final int TAG_RATING_ROW  = 0xDE_0009;
    private static final int TAG_SIMILAR_ROW = 0xDE_000A;
    private static final int TAG_POSTER_IMG  = 0xDE_000B;

    private static final int SIM_W = 104;
    private static final int SIM_H = 150;
    private static final int AI_CYAN = Color.parseColor("#7DD3FC");

    public interface DetailCallback {
        void onWatchClicked(JSONObject item);
        default void onTrailerClicked(JSONObject item) {}
        default void onCatchMeUpClicked(JSONObject item) {}
        default void onNotInterested(JSONObject item) {}
        void onWatchedToggle(JSONObject item);
        void onSimilarClicked(JSONObject item);
        void onRated(JSONObject item, int score);
        void onTraversalClicked(JSONObject similarItem);
        void onDismiss();
    }

    public static FrameLayout build(Context ctx, DetailCallback cb) {
        FrameLayout root = new FrameLayout(ctx);
        root.setVisibility(View.GONE);
        root.setBackgroundColor(Color.parseColor("#06080E"));
        root.setClickable(true);
        root.setFocusable(false);
        addFullBleedBackdropAndScrims(ctx, root);
        root.addView(buildCinemaForegroundPanel(ctx), matchParentParams());
        return root;
    }

    public static void showItem(FrameLayout overlay, JSONObject item,
                                boolean isWatched, int userRating,
                                Context ctx, DetailCallback cb) {
        if (overlay == null || item == null) return;
        overlay.setTag(item.optString("title", ""));
        bindContent(overlay, item, isWatched, userRating, ctx, cb);
        overlay.setVisibility(View.VISIBLE);
        animateIn(overlay, ctx);
        focusFirstActionButton(overlay);
    }

    public static boolean isShowingTitle(FrameLayout overlay, String title) {
        if (overlay == null || overlay.getVisibility() != View.VISIBLE || title == null) return false;
        Object tag = overlay.getTag();
        return tag instanceof String && title.equalsIgnoreCase((String) tag);
    }

    public static void dismiss(FrameLayout overlay) {
        if (overlay == null) return;
        overlay.setTag(null);
        overlay.setVisibility(View.GONE);
    }

    public static boolean isVisible(FrameLayout overlay) {
        return overlay != null && overlay.getVisibility() == View.VISIBLE;
    }

    public static void setSimilarItems(FrameLayout overlay, JSONArray similar,
                                       Context ctx, DetailCallback cb) {
        if (overlay == null) return;
        LinearLayout simRow = findTagged(overlay, TAG_SIMILAR_ROW);
        if (simRow != null) populateSimilarPosters(simRow, similar, ctx, cb);
    }

    public static void updateUserFeedback(FrameLayout overlay, JSONObject item,
                                          boolean isWatched, int userRating,
                                          Context ctx, DetailCallback cb) {
        if (overlay == null || item == null) return;
        LinearLayout actions = findTagged(overlay, TAG_ACTIONS);
        if (actions != null) populateActionButtons(overlay, actions, item, isWatched, ctx, cb);
        LinearLayout reactions = findTagged(overlay, TAG_RATING_ROW);
        if (reactions != null) populateReactionPills(overlay, reactions, item, userRating, ctx, cb);
    }

    public static void updatePosterUrl(FrameLayout overlay, String posterUrl, Context ctx) {
        if (overlay == null || posterUrl == null || posterUrl.isEmpty()) return;
        ImageView bg = findTagged(overlay, TAG_POSTER_IMG);
        if (bg != null && bg.getDrawable() == null) ImageLoader.load(ctx, posterUrl, bg);
    }

    public static void showRecapInDetail(FrameLayout overlay, JSONObject recap) {
        if (overlay == null || recap == null) return;
        TextView reasonTv = findTagged(overlay, TAG_REASON);
        if (reasonTv == null) return;
        String headline = recap.optString("headline", "Catch Me Up");
        String xray = recap.optString("cast_xray", "");
        reasonTv.setText("\u2726  " + headline + (xray.isEmpty() ? "" : " \u00B7 " + xray));
    }

    // ── Full-Bleed Cinema Canvas Construction ────────────────────────────

    private static void addFullBleedBackdropAndScrims(Context ctx, FrameLayout root) {
        ImageView bg = new ImageView(ctx);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setTag(TAG_POSTER_IMG);
        root.addView(bg, matchParentParams());
        root.addView(buildScrimView(ctx, GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.parseColor("#F506080E"), Color.parseColor("#CC06080E"), Color.parseColor("#2406080E")}), matchParentParams());
        root.addView(buildScrimView(ctx, GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{Color.parseColor("#FA06080E"), Color.parseColor("#B806080E"), Color.TRANSPARENT}), matchParentParams());
    }

    private static View buildScrimView(Context ctx, GradientDrawable.Orientation dir, int[] colors) {
        View v = new View(ctx);
        v.setBackground(new GradientDrawable(dir, colors));
        return v;
    }

    private static LinearLayout buildCinemaForegroundPanel(Context ctx) {
        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setTag(TAG_PANEL);
        outer.setClipChildren(false);
        outer.setClipToPadding(false);
        outer.setPadding(dp(ctx, 48), dp(ctx, 34), dp(ctx, 48), dp(ctx, 20));
        outer.addView(buildHeroTextAndControls(ctx), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.addView(buildSimilarSection(ctx), GlassTheme.matchWrapParams());
        return outer;
    }

    private static LinearLayout buildHeroTextAndControls(Context ctx) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setClipChildren(false);
        addCinemaMetaTitleAndPitch(ctx, col);
        addCinemaActionAndReactionRows(ctx, col);
        return col;
    }

    private static void addCinemaMetaTitleAndPitch(Context ctx, LinearLayout col) {
        TextView meta = GlassTheme.makeText(ctx, "", 10.5f, Color.parseColor("#CBD5E1"), GlassTheme.FONT_BOLD, 1);
        meta.setLetterSpacing(0.08f);
        meta.setTag(TAG_META);
        col.addView(meta, bottomMarginLp(ctx, 8));
        col.addView(buildCinemaTitleView(ctx), widthCapLp(ctx, 600, 10));
        col.addView(buildAiPitchView(ctx), widthCapLp(ctx, 560, 8));
        col.addView(buildSynopsisView(ctx), widthCapLp(ctx, 540, 18));
    }

    private static TextView buildCinemaTitleView(Context ctx) {
        TextView tv = GlassTheme.makeText(ctx, "", 32f, Color.WHITE, GlassTheme.FONT_BOLD, 2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setShadowLayer(12f, 0f, 3f, Color.argb(180, 0, 0, 0));
        tv.setTag(TAG_TITLE);
        return tv;
    }

    private static TextView buildAiPitchView(Context ctx) {
        TextView tv = GlassTheme.makeText(ctx, "", 13f, AI_CYAN, GlassTheme.FONT_MEDIUM, 2);
        tv.setLineSpacing(0f, 1.25f);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setTag(TAG_REASON);
        return tv;
    }

    private static TextView buildSynopsisView(Context ctx) {
        TextView tv = GlassTheme.makeText(ctx, "", 11.5f, Color.parseColor("#B8C2D4"), GlassTheme.FONT_REGULAR, 2);
        tv.setLineSpacing(0f, 1.24f);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setTag(TAG_SYNOPSIS);
        return tv;
    }

    private static void addCinemaActionAndReactionRows(Context ctx, LinearLayout col) {
        col.addView(createHorizontalTaggedRow(ctx, TAG_ACTIONS), bottomMarginLp(ctx, 12));
        col.addView(createHorizontalTaggedRow(ctx, TAG_RATING_ROW), GlassTheme.matchWrapParams());
    }

    private static LinearLayout createHorizontalTaggedRow(Context ctx, int tag) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setTag(tag);
        return row;
    }

    private static LinearLayout buildSimilarSection(Context ctx) {
        LinearLayout sec = new LinearLayout(ctx);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setClipChildren(false);
        sec.setClipToPadding(false);
        TextView hdr = GlassTheme.makeText(ctx, "MORE LIKE THIS", 9.5f,
            Color.parseColor("#94A3B8"), GlassTheme.FONT_BOLD, 1);
        hdr.setLetterSpacing(0.12f);
        sec.addView(hdr, bottomMarginLp(ctx, 8));
        sec.addView(buildSimilarScrollRow(ctx), GlassTheme.matchWrapParams());
        return sec;
    }

    private static HorizontalScrollView buildSimilarScrollRow(Context ctx) {
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        hsv.setFocusable(false);
        LinearLayout row = createHorizontalTaggedRow(ctx, TAG_SIMILAR_ROW);
        row.setClipToPadding(false);
        hsv.addView(row);
        return hsv;
    }

    // ── Data Binding ─────────────────────────────────────────────────────

    private static void bindContent(FrameLayout overlay, JSONObject item,
                                    boolean isWatched, int userRating,
                                    Context ctx, DetailCallback cb) {
        bindFullBleedArtwork(overlay, item, ctx);
        bindCinemaText(overlay, item);
        updateUserFeedback(overlay, item, isWatched, userRating, ctx, cb);
        showSimilarLoadingPlaceholder(overlay, ctx);
    }

    private static void bindFullBleedArtwork(FrameLayout overlay, JSONObject item, Context ctx) {
        ImageView bg = findTagged(overlay, TAG_POSTER_IMG);
        if (bg == null) return;
        String url = item.optString("backdrop_url", "").trim();
        if (url.isEmpty()) url = item.optString("poster_url", "").trim();
        ImageLoader.clear(bg);
        if (!url.isEmpty()) ImageLoader.load(ctx, url, bg);
    }

    private static void bindCinemaText(FrameLayout overlay, JSONObject item) {
        TextView meta = findTagged(overlay, TAG_META);
        if (meta != null) meta.setText(buildUnifiedCinemaMeta(item));
        TextView title = findTagged(overlay, TAG_TITLE);
        if (title != null) title.setText(resolveDisplayTitle(item));
        TextView pitch = findTagged(overlay, TAG_REASON);
        if (pitch != null) pitch.setText("\u2726  " + resolveReasonText(item));
        TextView syn = findTagged(overlay, TAG_SYNOPSIS);
        if (syn != null) syn.setText(resolveSynopsisText(item));
    }

    public static String buildUnifiedCinemaMeta(JSONObject item) {
        StringBuilder sb = new StringBuilder();
        String app = ContinueWatchingSelector.cleanPlatformName(item.optString("app", item.optString("app_name", "")));
        if (!app.isEmpty() && !"Streaming".equalsIgnoreCase(app)) sb.append(app.toUpperCase());
        double imdb = item.optDouble("imdb_raw", item.optDouble("imdb_score", 0.0));
        if (imdb > 0) appendToken(sb, "\u2605 " + String.format("%.1f", imdb));
        if (item.optInt("year", 0) > 0) appendToken(sb, String.valueOf(item.optInt("year")));
        if (item.optInt("runtime_min", 0) > 0) appendToken(sb, item.optInt("runtime_min") + "m");
        appendFirstTwoGenres(sb, item.optString("genres", ""));
        return sb.toString();
    }

    private static void appendFirstTwoGenres(StringBuilder sb, String genres) {
        if (genres == null || genres.trim().isEmpty()) return;
        String[] parts = genres.split(",");
        String compact = parts.length >= 2 ? (parts[0].trim() + ", " + parts[1].trim()) : parts[0].trim();
        if (!compact.isEmpty()) appendToken(sb, compact);
    }

    private static String resolveDisplayTitle(JSONObject item) {
        String s = item.optString("series_title", "").trim();
        return !s.isEmpty() ? s : item.optString("title", "Untitled").trim();
    }

    private static String resolveSynopsisText(JSONObject item) {
        String syn = item.optString("synopsis", "").trim();
        if (!syn.isEmpty()) return syn;
        String app = ContinueWatchingSelector.cleanPlatformName(item.optString("app", item.optString("app_name", "Streaming")));
        return "Streaming on " + app + ". Press Watch to launch directly on your TV.";
    }

    private static String resolveReasonText(JSONObject item) {
        String reason = item.optString("reason", "").trim();
        if (!reason.isEmpty()) return reason;
        return "Curated by Aura AI for your taste profile";
    }

    // ── Primary Actions (3 Clean Pills + Subtle Dismiss) ─────────────────

    private static void populateActionButtons(FrameLayout overlay, LinearLayout row,
                                              JSONObject item, boolean isWatched,
                                              Context ctx, DetailCallback cb) {
        row.removeAllViews();
        row.addView(buildPrimaryWatchBtn(item, ctx, cb));
        row.addView(buildSecondaryAiOrTrailerBtn(item, ctx, cb));
        row.addView(buildWatchedToggleBtn(item, isWatched, ctx, cb));
        row.addView(buildCompactNotForMeBtn(overlay, item, ctx, cb));
        bindActionButtonsDpad(overlay, row, cb);
    }

    private static TextView buildPrimaryWatchBtn(JSONObject item, Context ctx, DetailCallback cb) {
        String app = ContinueWatchingSelector.cleanPlatformName(item.optString("app", item.optString("app_name", "")));
        String label = (!app.isEmpty() && !"Streaming".equalsIgnoreCase(app)) ? ("\u25B6  Watch on " + app) : "\u25B6  Watch";
        TextView btn = buildPillButton(ctx, label, true);
        btn.setOnClickListener(v -> cb.onWatchClicked(item));
        return btn;
    }

    private static TextView buildSecondaryAiOrTrailerBtn(JSONObject item, Context ctx, DetailCallback cb) {
        boolean inProgress = item.optInt("position_sec", 0) >= 60 || item.optInt("episode", 0) > 0;
        String label = inProgress ? "\u2726  Catch Me Up" : "\u25B6  Trailer";
        TextView btn = buildPillButton(ctx, label, false);
        btn.setOnClickListener(v -> {
            if (inProgress) cb.onCatchMeUpClicked(item);
            else cb.onTrailerClicked(item);
        });
        return btn;
    }

    private static TextView buildWatchedToggleBtn(JSONObject item, boolean isWatched, Context ctx, DetailCallback cb) {
        TextView btn = buildPillButton(ctx, isWatched ? "\u2713  Watched" : "+  Watched", false);
        btn.setOnClickListener(v -> cb.onWatchedToggle(item));
        return btn;
    }

    private static TextView buildCompactNotForMeBtn(FrameLayout overlay, JSONObject item, Context ctx, DetailCallback cb) {
        TextView btn = buildPillButton(ctx, "\u2715  Not for me", false);
        btn.setOnClickListener(v -> { dismiss(overlay); cb.onNotInterested(item); });
        return btn;
    }

    private static TextView buildPillButton(Context ctx, String text, boolean primary) {
        float r = dp(ctx, 22);
        int bg = primary ? Color.WHITE : Color.argb(46, 255, 255, 255);
        int fg = primary ? Color.parseColor("#06080E") : Color.WHITE;
        int border = primary ? Color.WHITE : Color.argb(65, 255, 255, 255);
        TextView btn = GlassTheme.makeText(ctx, text, 11.5f, fg, GlassTheme.FONT_BOLD, 1);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10));
        btn.setBackground(GlassTheme.focusRounded(bg, border, dp(ctx, 1), r));
        configurePillFocus(ctx, btn, bg, border, r);
        return btn;
    }

    private static void configurePillFocus(Context ctx, TextView btn, int bg, int border, float r) {
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setLayoutParams(rightMarginLp(ctx, 10));
        btn.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(GlassTheme.focusRounded(bg, hasFocus ? Color.WHITE : border, dp(ctx, hasFocus ? 2.5f : 1f), r));
            float s = hasFocus ? 1.05f : 1.0f;
            v.animate().scaleX(s).scaleY(s).setDuration(130).start();
        });
    }

    // ── 3-Tap Emotional Reaction Row (Replaces 5 Numeric Star Buttons) ───

    private static void populateReactionPills(FrameLayout overlay, LinearLayout row,
                                              JSONObject item, int userRating,
                                              Context ctx, DetailCallback cb) {
        row.removeAllViews();
        row.setVisibility(View.VISIBLE);
        row.addView(buildReactionChip(ctx, "\u2605  Loved it", userRating >= 9, () -> cb.onRated(item, 10)));
        row.addView(buildReactionChip(ctx, "\u2713  Liked it", userRating >= 7 && userRating < 9, () -> cb.onRated(item, 8)));
        row.addView(buildReactionChip(ctx, "\u2726  Explore Similar", false, () -> { dismiss(overlay); cb.onSimilarClicked(item); }));
        bindReactionRowKeys(overlay, row, cb);
    }

    private static TextView buildReactionChip(Context ctx, String label, boolean selected, Runnable action) {
        float r = dp(ctx, 16);
        int bg = selected ? AI_CYAN : Color.argb(26, 255, 255, 255);
        int fg = selected ? Color.parseColor("#06080E") : Color.parseColor("#CBD5E1");
        TextView chip = GlassTheme.makeText(ctx, label, 10f, fg, GlassTheme.FONT_BOLD, 1);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        chip.setBackground(GlassTheme.focusRounded(bg, Color.argb(40, 255, 255, 255), dp(ctx, 1), r));
        configurePillFocus(ctx, chip, bg, Color.argb(40, 255, 255, 255), r);
        chip.setOnClickListener(v -> action.run());
        return chip;
    }

    // ── DPAD Key Navigation ──────────────────────────────────────────────

    private static void bindActionButtonsDpad(FrameLayout overlay, LinearLayout row, DetailCallback cb) {
        for (int i = 0; i < row.getChildCount(); i++) {
            final int idx = i;
            row.getChildAt(i).setOnKeyListener((v, code, ev) -> handleActionKey(overlay, row, idx, code, ev, cb));
        }
    }

    private static boolean handleActionKey(FrameLayout overlay, LinearLayout row, int idx,
                                           int code, KeyEvent ev, DetailCallback cb) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK) { dismiss(overlay); cb.onDismiss(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return focusChildAt(row, idx + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
        }
        return code == KeyEvent.KEYCODE_DPAD_DOWN && focusFirstChild(findTagged(overlay, TAG_RATING_ROW));
    }

    private static void bindReactionRowKeys(FrameLayout overlay, LinearLayout row, DetailCallback cb) {
        for (int i = 0; i < row.getChildCount(); i++) {
            final int idx = i;
            row.getChildAt(i).setOnKeyListener((v, code, ev) -> handleReactionKey(overlay, row, idx, code, ev, cb));
        }
    }

    private static boolean handleReactionKey(FrameLayout overlay, LinearLayout row, int idx,
                                             int code, KeyEvent ev, DetailCallback cb) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK) { dismiss(overlay); cb.onDismiss(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return focusChildAt(row, idx + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) return focusFirstChild(findTagged(overlay, TAG_ACTIONS));
        return code == KeyEvent.KEYCODE_DPAD_DOWN && focusFirstChild(findTagged(overlay, TAG_SIMILAR_ROW));
    }

    // ── Bottom Similar Posters Dock ──────────────────────────────────────

    private static void showSimilarLoadingPlaceholder(FrameLayout overlay, Context ctx) {
        LinearLayout row = findTagged(overlay, TAG_SIMILAR_ROW);
        if (row == null) return;
        row.removeAllViews();
        ProgressBar pb = new ProgressBar(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18));
        lp.rightMargin = dp(ctx, 10);
        row.addView(pb, lp);
        row.addView(GlassTheme.makeText(ctx, "Finding similar titles\u2026", 10.5f,
            Color.parseColor("#94A3B8"), GlassTheme.FONT_MEDIUM, 1));
    }

    private static void populateSimilarPosters(LinearLayout row, JSONArray items, Context ctx, DetailCallback cb) {
        row.removeAllViews();
        if (items == null || items.length() == 0) return;
        for (int i = 0; i < items.length() && i < 10; i++) {
            JSONObject sim = items.optJSONObject(i);
            if (sim != null) row.addView(buildSimilarPosterCard(row, sim, i, ctx, cb));
        }
    }

    private static LinearLayout buildSimilarPosterCard(LinearLayout parentRow, JSONObject sim,
                                                       int idx, Context ctx, DetailCallback cb) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setFocusable(true);
        wrap.setClickable(true);
        wrap.setLayoutParams(rightMarginLp(ctx, 12));
        FrameLayout frame = buildSimilarPosterFrame(ctx, sim);
        TextView title = buildSimilarTitleCaption(ctx, sim.optString("title", ""));
        wrap.addView(frame);
        wrap.addView(title);
        bindSimilarCardEvents(parentRow, wrap, frame, title, sim, idx, ctx, cb);
        return wrap;
    }

    private static FrameLayout buildSimilarPosterFrame(Context ctx, JSONObject sim) {
        float r = dp(ctx, 10);
        FrameLayout frame = new FrameLayout(ctx);
        frame.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, SIM_W), dp(ctx, SIM_H)));
        frame.setBackground(GlassTheme.solidRounded(Color.parseColor("#141926"), r));
        applyRoundOutline(frame, r);
        attachPosterImage(ctx, frame, sim.optString("poster_url", ""));
        return frame;
    }

    private static void attachPosterImage(Context ctx, FrameLayout frame, String url) {
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(img, matchParentParams());
        if (!url.isEmpty()) ImageLoader.load(ctx, url, img);
    }

    private static TextView buildSimilarTitleCaption(Context ctx, String title) {
        TextView tv = GlassTheme.makeText(ctx, title, 9.5f, Color.parseColor("#94A3B8"), GlassTheme.FONT_MEDIUM, 1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp(ctx, 2), dp(ctx, 5), dp(ctx, 2), 0);
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, SIM_W), ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private static void bindSimilarCardEvents(LinearLayout parentRow, LinearLayout wrap, FrameLayout frame,
                                              TextView titleTv, JSONObject sim, int idx, Context ctx, DetailCallback cb) {
        float r = dp(ctx, 10);
        wrap.setOnFocusChangeListener((v, f) -> {
            frame.setBackground(GlassTheme.focusRounded(Color.parseColor("#141926"), f ? Color.WHITE : Color.TRANSPARENT, dp(ctx, f ? 2.5f : 0f), r));
            titleTv.setTextColor(f ? Color.WHITE : Color.parseColor("#94A3B8"));
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(140).start();
        });
        wrap.setOnClickListener(v -> cb.onTraversalClicked(sim));
        wrap.setOnKeyListener((v, code, ev) -> handleSimilarKey(parentRow, idx, code, ev, cb));
    }

    private static boolean handleSimilarKey(LinearLayout row, int idx, int code, KeyEvent ev, DetailCallback cb) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK) { cb.onDismiss(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return focusChildAt(row, idx + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1));
        }
        return code == KeyEvent.KEYCODE_DPAD_UP && focusFirstChild(findTagged(row.getRootView(), TAG_RATING_ROW));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static void focusFirstActionButton(FrameLayout overlay) {
        LinearLayout actions = findTagged(overlay, TAG_ACTIONS);
        if (actions != null && actions.getChildCount() > 0) actions.getChildAt(0).post(actions.getChildAt(0)::requestFocus);
    }

    private static boolean focusChildAt(LinearLayout row, int idx) {
        if (row != null && idx >= 0 && idx < row.getChildCount()) row.getChildAt(idx).requestFocus();
        return true;
    }

    private static boolean focusFirstChild(LinearLayout row) {
        if (row == null || row.getChildCount() == 0 || !row.getChildAt(0).isFocusable()) return false;
        row.getChildAt(0).requestFocus();
        return true;
    }

    private static void applyRoundOutline(View v, float r) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline out) {
                out.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
    }

    private static void appendToken(StringBuilder sb, String token) {
        if (sb.length() > 0) sb.append("  \u00B7  ");
        sb.append(token);
    }

    private static FrameLayout.LayoutParams matchParentParams() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams bottomMarginLp(Context ctx, int dpVal) {
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.bottomMargin = dp(ctx, dpVal);
        return lp;
    }

    private static LinearLayout.LayoutParams widthCapLp(Context ctx, int widthDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, bottomDp);
        return lp;
    }

    private static LinearLayout.LayoutParams rightMarginLp(Context ctx, int dpVal) {
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.rightMargin = dp(ctx, dpVal);
        return lp;
    }

    private static void animateIn(FrameLayout overlay, Context ctx) {
        overlay.animate().cancel();
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(220).setInterpolator(GlassTheme.EASE).start();
    }

    private static int dp(Context ctx, float v) {
        return GlassTheme.dp(ctx, v);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findTagged(View root, int tag) {
        return root != null ? (T) root.findViewWithTag(tag) : null;
    }
}
