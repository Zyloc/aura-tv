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
 * Full-screen Smoked-Obsidian native TV Detail View shown when the user presses OK
 * on the Hero tile or any poster card.
 * Features a 2-column top layout (Ultra-HD Poster on the left, properly aligned
 * WRAP_CONTENT metadata pills, synopsis, action buttons, and rating pills on the right)
 * and a visual "More Like This" horizontal poster rail along the bottom.
 */
public final class DetailOverlay {

    private DetailOverlay() { }

    // ── Child-view tags ──────────────────────────────────────────────────
    private static final int TAG_PANEL        = 0xDE_0001;
    private static final int TAG_PLATFORM     = 0xDE_0002;
    private static final int TAG_IMDB_BADGE   = 0xDE_0003;
    private static final int TAG_META         = 0xDE_0004;
    private static final int TAG_TITLE        = 0xDE_0005;
    private static final int TAG_SYNOPSIS     = 0xDE_0006;
    private static final int TAG_REASON       = 0xDE_0007;
    private static final int TAG_ACTIONS      = 0xDE_0008;
    private static final int TAG_RATING_ROW   = 0xDE_0009;
    private static final int TAG_SIMILAR_ROW  = 0xDE_000A;
    private static final int TAG_POSTER_IMG   = 0xDE_000B;
    private static final int TAG_POSTER_MONO  = 0xDE_000C;

    private static final int POSTER_W = 168;
    private static final int POSTER_H = 248;
    private static final int SIM_W    = 112;
    private static final int SIM_H    = 162;

    // ── Callback ─────────────────────────────────────────────────────────

    public interface DetailCallback {
        void onWatchClicked(JSONObject item);
        default void onTrailerClicked(JSONObject item) {}
        void onWatchedToggle(JSONObject item);
        void onSimilarClicked(JSONObject item);
        void onRated(JSONObject item, int score);
        void onTraversalClicked(JSONObject similarItem);
        void onDismiss();
    }

    // ── Build ────────────────────────────────────────────────────────────

    public static FrameLayout build(Context ctx, DetailCallback cb) {
        FrameLayout root = new FrameLayout(ctx);
        root.setVisibility(View.GONE);
        root.setBackgroundColor(GlassTheme.BG_AMBIENT);
        root.setClickable(true);
        root.setFocusable(false);
        LinearLayout panel = buildMainStudioPanel(ctx);
        root.addView(panel, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    // ── Show / Dismiss / Visible ─────────────────────────────────────────

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

    private static void focusFirstActionButton(FrameLayout overlay) {
        LinearLayout actions = findTagged(overlay, TAG_ACTIONS);
        if (actions != null && actions.getChildCount() > 0) {
            View firstBtn = actions.getChildAt(0);
            firstBtn.post(firstBtn::requestFocus);
        }
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
        if (simRow == null) return;
        populateSimilarPosters(simRow, similar, ctx, cb);
    }

    public static void updateUserFeedback(FrameLayout overlay, JSONObject item,
                                          boolean isWatched, int userRating,
                                          Context ctx, DetailCallback cb) {
        if (overlay == null || item == null) return;
        LinearLayout actionsRow = findTagged(overlay, TAG_ACTIONS);
        if (actionsRow != null) populateActionButtons(overlay, actionsRow, item, isWatched, ctx, cb);
        LinearLayout rateRow = findTagged(overlay, TAG_RATING_ROW);
        if (rateRow != null) {
            rateRow.setVisibility(View.VISIBLE);
            populateRatingPills(overlay, rateRow, item, userRating, ctx, cb);
        }
    }

    public static void updatePosterUrl(FrameLayout overlay, String posterUrl, Context ctx) {
        if (overlay == null || posterUrl == null || posterUrl.isEmpty()) return;
        TextView posterMono = findTagged(overlay, TAG_POSTER_MONO);
        if (posterMono != null) posterMono.setVisibility(View.GONE);
        ImageView posterImg = findTagged(overlay, TAG_POSTER_IMG);
        if (posterImg != null) {
            ImageLoader.load(ctx, posterUrl, posterImg);
        }
    }

    // ── Studio Layout Construction ───────────────────────────────────────

    private static LinearLayout buildMainStudioPanel(Context ctx) {
        LinearLayout outer = createOuterStudioContainer(ctx);
        LinearLayout topCard = buildTopHeroCard(ctx);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.bottomMargin = dp(ctx, 14);
        outer.addView(topCard, topLp);
        // Bottom Visual "More Like This" Poster Rail
        outer.addView(buildSimilarSection(ctx), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    private static LinearLayout createOuterStudioContainer(Context ctx) {
        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setTag(TAG_PANEL);
        outer.setClipChildren(false);
        outer.setClipToPadding(false);
        outer.setPadding(dp(ctx, 28), dp(ctx, 20), dp(ctx, 28), dp(ctx, 16));
        return outer;
    }

    // Top 2-Column Hero Card (Left: UHD Poster, Right: Aligned Details + Actions)
    private static LinearLayout buildTopHeroCard(Context ctx) {
        LinearLayout topCard = new LinearLayout(ctx);
        topCard.setOrientation(LinearLayout.HORIZONTAL);
        topCard.setGravity(Gravity.TOP);
        topCard.setBackground(GlassTheme.glassPanel(dp(ctx, 18)));
        topCard.setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 22), dp(ctx, 18));
        topCard.setClipChildren(false);
        topCard.setClipToPadding(false);
        topCard.addView(buildLeftPosterCard(ctx));
        topCard.addView(buildRightDetailsColumn(ctx));
        return topCard;
    }

    private static FrameLayout buildLeftPosterCard(Context ctx) {
        FrameLayout frame = createLeftPosterFrame(ctx, dp(ctx, POSTER_W), dp(ctx, POSTER_H), dp(ctx, 14));
        addPosterPlaceholderAndImage(ctx, frame);
        return frame;
    }

    private static FrameLayout createLeftPosterFrame(Context ctx, int w, int h, float r) {
        FrameLayout frame = new FrameLayout(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = dp(ctx, 24);
        frame.setLayoutParams(lp);
        frame.setBackground(GlassTheme.solidRounded(GlassTheme.SURFACE_ELEV, r));
        applyRoundRectOutline(frame, r);
        return frame;
    }

    private static void applyRoundRectOutline(View view, float r) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
    }

    private static void addPosterPlaceholderAndImage(Context ctx, FrameLayout frame) {
        TextView mono = GlassTheme.makeText(ctx, "", 28f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_BOLD, 1);
        mono.setGravity(Gravity.CENTER);
        mono.setTag(TAG_POSTER_MONO);
        frame.addView(mono, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setTag(TAG_POSTER_IMG);
        frame.addView(img, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static LinearLayout buildRightDetailsColumn(Context ctx) {
        LinearLayout col = createDetailsColumnContainer(ctx);
        addBadgeAndMetaRow(ctx, col);
        addTitleAndSynopsisViews(ctx, col);
        addReasonBadgeView(ctx, col);
        addActionsAndRatingRows(ctx, col);
        return col;
    }

    private static LinearLayout createDetailsColumnContainer(Context ctx) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return col;
    }

    // 1. Badge & Metadata Row (WRAP_CONTENT pills, never stretched!)
    private static void addBadgeAndMetaRow(Context ctx, LinearLayout col) {
        LinearLayout metaRow = new LinearLayout(ctx);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        addPlatformAndImdbPills(ctx, metaRow);
        TextView metaText = GlassTheme.makeText(ctx, "", 11f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1);
        metaText.setTag(TAG_META);
        metaRow.addView(metaText, GlassTheme.wrapParams());
        LinearLayout.LayoutParams mrLp = GlassTheme.matchWrapParams();
        mrLp.bottomMargin = dp(ctx, 6);
        col.addView(metaRow, mrLp);
    }

    private static void addPlatformAndImdbPills(Context ctx, LinearLayout metaRow) {
        TextView platformPill = GlassTheme.makeText(ctx, "", 9.5f, Color.WHITE, GlassTheme.FONT_BOLD, 1);
        platformPill.setTag(TAG_PLATFORM);
        LinearLayout.LayoutParams platLp = GlassTheme.wrapParams();
        platLp.rightMargin = dp(ctx, 8);
        metaRow.addView(platformPill, platLp);
        metaRow.addView(createImdbBadgePill(ctx), createRightMarginParams(ctx, 10));
    }

    private static TextView createImdbBadgePill(Context ctx) {
        TextView imdbPill = GlassTheme.makeText(ctx, "", 9.5f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        imdbPill.setTag(TAG_IMDB_BADGE);
        imdbPill.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), dp(ctx, 6)));
        imdbPill.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        return imdbPill;
    }

    // 2. Title & 3. Story Synopsis
    private static void addTitleAndSynopsisViews(Context ctx, LinearLayout col) {
        TextView titleTv = GlassTheme.makeText(ctx, "", 22f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 2);
        titleTv.setTag(TAG_TITLE);
        col.addView(titleTv, createBottomMarginParams(ctx, 6));
        TextView synTv = GlassTheme.makeText(ctx, "", 11.5f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_REGULAR, 3);
        synTv.setLineSpacing(0f, 1.2f);
        synTv.setTag(TAG_SYNOPSIS);
        col.addView(synTv, createBottomMarginParams(ctx, 8));
    }

    private static LinearLayout.LayoutParams createBottomMarginParams(Context ctx, int bottomMarginDp) {
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.bottomMargin = dp(ctx, bottomMarginDp);
        return lp;
    }

    // 4. Compact 1-Line AI Recommendation Note
    private static void addReasonBadgeView(Context ctx, LinearLayout col) {
        TextView reasonTv = GlassTheme.makeText(ctx, "", 10.5f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_MEDIUM, 1);
        reasonTv.setTag(TAG_REASON);
        reasonTv.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), dp(ctx, 8)));
        reasonTv.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        LinearLayout.LayoutParams rLp = GlassTheme.wrapParams();
        rLp.bottomMargin = dp(ctx, 12);
        col.addView(reasonTv, rLp);
    }

    // 5. Primary Action Buttons Row & 6. Compact Rating Row
    private static void addActionsAndRatingRows(Context ctx, LinearLayout col) {
        LinearLayout actionsRow = createHorizontalTaggedRow(ctx, TAG_ACTIONS);
        LinearLayout.LayoutParams actLp = GlassTheme.matchWrapParams();
        actLp.bottomMargin = dp(ctx, 10);
        col.addView(actionsRow, actLp);
        LinearLayout rateRow = createHorizontalTaggedRow(ctx, TAG_RATING_ROW);
        col.addView(rateRow, GlassTheme.matchWrapParams());
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
        addSimilarHeader(ctx, sec);
        sec.addView(buildSimilarScrollRow(ctx), GlassTheme.matchWrapParams());
        return sec;
    }

    private static void addSimilarHeader(Context ctx, LinearLayout sec) {
        TextView header = GlassTheme.makeText(ctx, "MORE LIKE THIS", 10.5f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_BOLD, 1);
        header.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams hLp = GlassTheme.wrapParams();
        hLp.bottomMargin = dp(ctx, 8);
        sec.addView(header, hLp);
    }

    private static HorizontalScrollView buildSimilarScrollRow(Context ctx) {
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipChildren(false);
        hsv.setClipToPadding(false);
        hsv.setFocusable(false);
        hsv.addView(createSimilarRowContainer(ctx));
        return hsv;
    }

    private static LinearLayout createSimilarRowContainer(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setTag(TAG_SIMILAR_ROW);
        return row;
    }

    // ── Data Binding ─────────────────────────────────────────────────────

    private static void bindContent(FrameLayout overlay, JSONObject item,
                                    boolean isWatched, int userRating,
                                    Context ctx, DetailCallback cb) {
        bindPosterArtwork(overlay, item, ctx);
        hideLegacyBadges(overlay);
        bindTextMetadata(overlay, item);
        bindInteractiveRows(overlay, item, isWatched, userRating, ctx, cb);
        showSimilarLoadingPlaceholder(overlay, ctx);
    }

    // 1. Poster Artwork (Ultra-HD 718x1016 native resolution)
    private static void bindPosterArtwork(FrameLayout overlay, JSONObject item, Context ctx) {
        String title = item.optString("title", "Untitled");
        String url = item.optString("poster_url", "");
        if (url.isEmpty()) url = item.optString("backdrop_url", "");
        TextView posterMono = findTagged(overlay, TAG_POSTER_MONO);
        if (posterMono != null) {
            posterMono.setText(monogram(title));
            posterMono.setVisibility(url.isEmpty() ? View.VISIBLE : View.GONE);
        }
        loadPosterImageIntoView(findTagged(overlay, TAG_POSTER_IMG), url, ctx);
    }

    private static void loadPosterImageIntoView(ImageView posterImg, String url, Context ctx) {
        if (posterImg == null) return;
        posterImg.setBackgroundColor(GlassTheme.SURFACE_ELEV);
        ImageLoader.clear(posterImg);
        if (!url.isEmpty()) ImageLoader.load(ctx, url, posterImg);
    }

    // 2. Hide OTT & Rating pills for clean cinema presentation
    private static void hideLegacyBadges(FrameLayout overlay) {
        TextView platTv = findTagged(overlay, TAG_PLATFORM);
        if (platTv != null) platTv.setVisibility(View.GONE);
        TextView imdbTv = findTagged(overlay, TAG_IMDB_BADGE);
        if (imdbTv != null) imdbTv.setVisibility(View.GONE);
    }

    // 4. Meta String, 5. Title & Synopsis, 6. AI Recommendation Note
    private static void bindTextMetadata(FrameLayout overlay, JSONObject item) {
        TextView metaTv = findTagged(overlay, TAG_META);
        if (metaTv != null) metaTv.setText(buildCleanMeta(item));
        TextView titleTv = findTagged(overlay, TAG_TITLE);
        if (titleTv != null) titleTv.setText(item.optString("title", "Untitled"));
        TextView synTv = findTagged(overlay, TAG_SYNOPSIS);
        if (synTv != null) synTv.setText(resolveSynopsisText(item));
        TextView reasonTv = findTagged(overlay, TAG_REASON);
        if (reasonTv != null) reasonTv.setText("\u2726 " + resolveReasonText(item));
    }

    private static String resolveSynopsisText(JSONObject item) {
        String syn = item.optString("synopsis", "").trim();
        if (!syn.isEmpty()) return syn;
        String app = ContinueWatchingSelector.cleanPlatformName(
            item.optString("app", item.optString("app_name", "Streaming")));
        return "Streaming on " + app + ". Press Watch below to launch directly on your TV.";
    }

    private static String resolveReasonText(JSONObject item) {
        String reason = item.optString("reason", "").trim();
        if (!reason.isEmpty()) return reason;
        double sc = item.optDouble("imdb_score", 0);
        return sc > 0
            ? "Matched to your taste profile · Rated \u2605 " + String.format("%.1f", sc) + " on IMDb"
            : "Matched to your watch history and preferred streaming apps";
    }

    // 7. Action Buttons & 8. Interactive Rating Row
    private static void bindInteractiveRows(FrameLayout overlay, JSONObject item,
                                            boolean isWatched, int userRating,
                                            Context ctx, DetailCallback cb) {
        LinearLayout actionsRow = findTagged(overlay, TAG_ACTIONS);
        if (actionsRow != null) populateActionButtons(overlay, actionsRow, item, isWatched, ctx, cb);
        LinearLayout rateRow = findTagged(overlay, TAG_RATING_ROW);
        if (rateRow != null) {
            rateRow.setVisibility(View.VISIBLE);
            populateRatingPills(overlay, rateRow, item, userRating, ctx, cb);
        }
    }

    // 9. Animated inline loader while "More Like This" fetches
    private static void showSimilarLoadingPlaceholder(FrameLayout overlay, Context ctx) {
        LinearLayout simRow = findTagged(overlay, TAG_SIMILAR_ROW);
        if (simRow == null) return;
        simRow.removeAllViews();
        simRow.addView(createSimilarLoadingBox(ctx));
    }

    private static LinearLayout createSimilarLoadingBox(Context ctx) {
        LinearLayout loadBox = new LinearLayout(ctx);
        loadBox.setOrientation(LinearLayout.HORIZONTAL);
        loadBox.setGravity(Gravity.CENTER_VERTICAL);
        loadBox.setPadding(dp(ctx, 4), dp(ctx, 18), dp(ctx, 12), dp(ctx, 18));
        loadBox.addView(createGoldSpinner(ctx));
        loadBox.addView(GlassTheme.makeText(ctx, "Finding similar shows & movies\u2026",
            11f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1));
        return loadBox;
    }

    private static ProgressBar createGoldSpinner(Context ctx) {
        ProgressBar pb = new ProgressBar(ctx);
        int s = dp(ctx, 18);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(s, s);
        pbLp.rightMargin = dp(ctx, 10);
        pb.setLayoutParams(pbLp);
        pb.getIndeterminateDrawable().setTint(GlassTheme.GOLD_ACCENT);
        return pb;
    }

    private static void populateActionButtons(FrameLayout overlay, LinearLayout row,
                                              JSONObject item, boolean isWatched,
                                              Context ctx, DetailCallback cb) {
        row.removeAllViews();
        addWatchAndSimilarButtons(overlay, row, item, ctx, cb);
        String watchedLabel = isWatched ? "\u2713  Watched" : "+  Mark Watched";
        TextView watchedBtn = buildActionButton(ctx, watchedLabel, false);
        watchedBtn.setOnClickListener(v -> cb.onWatchedToggle(item));
        row.addView(watchedBtn);
        bindActionButtonsDpad(overlay, row, cb);
    }

    private static void addWatchAndSimilarButtons(FrameLayout overlay, LinearLayout row,
                                                  JSONObject item, Context ctx, DetailCallback cb) {
        TextView watchBtn = buildActionButton(ctx, "\u25B6  Watch", true);
        watchBtn.setOnClickListener(v -> cb.onWatchClicked(item));
        row.addView(watchBtn);
        TextView trailerBtn = buildActionButton(ctx, "\u25B6  Watch Trailer", false);
        trailerBtn.setOnClickListener(v -> cb.onTrailerClicked(item));
        row.addView(trailerBtn);
        row.addView(createExploreSimilarButton(overlay, item, ctx, cb));
    }

    private static TextView createExploreSimilarButton(FrameLayout overlay, JSONObject item,
                                                       Context ctx, DetailCallback cb) {
        TextView simBtn = buildActionButton(ctx, "\u2726  Explore Similar", false);
        simBtn.setOnClickListener(v -> {
            dismiss(overlay);
            cb.onSimilarClicked(item);
        });
        return simBtn;
    }

    private static void bindActionButtonsDpad(FrameLayout overlay, LinearLayout row, DetailCallback cb) {
        for (int i = 0; i < row.getChildCount(); i++) {
            final int idx = i;
            row.getChildAt(i).setOnKeyListener((v, code, ev) ->
                handleActionButtonKey(overlay, row, idx, code, ev, cb));
        }
    }

    private static boolean handleActionButtonKey(FrameLayout overlay, LinearLayout row, int idx,
                                                 int code, KeyEvent ev, DetailCallback cb) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK) { dismiss(overlay); cb.onDismiss(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return focusHorizontalChild(row, idx + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1), 0);
        }
        return code == KeyEvent.KEYCODE_DPAD_DOWN && moveDownFromActions(overlay);
    }

    private static boolean focusHorizontalChild(LinearLayout row, int targetIdx, int minIdx) {
        if (targetIdx >= minIdx && targetIdx < row.getChildCount()) {
            row.getChildAt(targetIdx).requestFocus();
        }
        return true;
    }

    private static boolean moveDownFromActions(FrameLayout overlay) {
        LinearLayout rateRow = findTagged(overlay, TAG_RATING_ROW);
        if (rateRow != null && rateRow.getVisibility() == View.VISIBLE && rateRow.getChildCount() > 1) {
            rateRow.getChildAt(1).requestFocus();
            return true;
        }
        return focusFirstSimilarIfAvailable(overlay);
    }

    private static boolean focusFirstSimilarIfAvailable(View root) {
        LinearLayout simRow = findTagged(root, TAG_SIMILAR_ROW);
        if (simRow != null && simRow.getChildCount() > 0 && simRow.getChildAt(0).isFocusable()) {
            simRow.getChildAt(0).requestFocus();
            return true;
        }
        return false;
    }

    private static TextView buildActionButton(Context ctx, String text, boolean primary) {
        float r = dp(ctx, 10);
        int bgColor = primary ? GlassTheme.GOLD_ACCENT : GlassTheme.SURFACE_ELEV;
        int txtColor = primary ? GlassTheme.TEXT_ON_LIGHT : GlassTheme.TEXT_PRIMARY;
        int borderColor = primary ? GlassTheme.GOLD_ACCENT : GlassTheme.BORDER_LIGHT;
        TextView btn = createBaseActionButton(ctx, text, txtColor, bgColor, borderColor, r);
        bindActionButtonFocus(ctx, btn, bgColor, borderColor, r);
        return btn;
    }

    private static TextView createBaseActionButton(Context ctx, String text, int txtColor,
                                                   int bgColor, int borderColor, float r) {
        TextView btn = GlassTheme.makeText(ctx, text, 11.5f, txtColor, GlassTheme.FONT_BOLD, 1);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(ctx, 16), dp(ctx, 9), dp(ctx, 16), dp(ctx, 9));
        btn.setBackground(GlassTheme.focusRounded(bgColor, borderColor, dp(ctx, 1), r));
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setLayoutParams(createRightMarginParams(ctx, 10));
        return btn;
    }

    private static void bindActionButtonFocus(Context ctx, TextView btn, int bgColor, int borderColor, float r) {
        btn.setOnFocusChangeListener((v, hasFocus) -> {
            int stroke = hasFocus ? Color.WHITE : borderColor;
            int sw = hasFocus ? dp(ctx, 2.5f) : dp(ctx, 1);
            v.setBackground(GlassTheme.focusRounded(bgColor, stroke, sw, r));
            float scale = hasFocus ? 1.05f : 1.0f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(130).start();
        });
    }

    private static void populateRatingPills(FrameLayout overlay, LinearLayout row,
                                            JSONObject item, int userRating,
                                            Context ctx, DetailCallback cb) {
        row.removeAllViews();
        TextView lbl = GlassTheme.makeText(ctx, "YOUR RATING:", 9.5f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_BOLD, 1);
        row.addView(lbl, createRightMarginParams(ctx, 10));
        for (int s : new int[]{6, 7, 8, 9, 10}) {
            row.addView(buildRatingPill(ctx, item, s, userRating == s, cb));
        }
        bindRatingRowKeys(overlay, row, cb);
    }

    private static TextView buildRatingPill(Context ctx, JSONObject item, int score,
                                            boolean selected, DetailCallback cb) {
        float r = dp(ctx, 8);
        int bg = selected ? GlassTheme.GOLD_ACCENT : GlassTheme.GLASS_CARD;
        int fg = selected ? GlassTheme.TEXT_ON_LIGHT : GlassTheme.TEXT_SECONDARY;
        TextView pill = GlassTheme.makeText(ctx, "\u2605 " + score, 10f, fg, GlassTheme.FONT_BOLD, 1);
        styleRatingPill(ctx, pill, bg, r);
        pill.setOnClickListener(v -> cb.onRated(item, score));
        return pill;
    }

    private static void styleRatingPill(Context ctx, TextView pill, int bg, float r) {
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        pill.setBackground(GlassTheme.focusRounded(bg, GlassTheme.BORDER_LIGHT, dp(ctx, 1), r));
        pill.setFocusable(true);
        pill.setClickable(true);
        pill.setLayoutParams(createRightMarginParams(ctx, 6));
        pill.setOnFocusChangeListener((v, hasFocus) -> v.setBackground(GlassTheme.focusRounded(
            bg, hasFocus ? Color.WHITE : GlassTheme.BORDER_LIGHT, hasFocus ? dp(ctx, 2) : dp(ctx, 1), r)));
    }

    private static void bindRatingRowKeys(FrameLayout overlay, LinearLayout row, DetailCallback cb) {
        for (int i = 1; i < row.getChildCount(); i++) {
            final int idx = i;
            row.getChildAt(i).setOnKeyListener((v, code, ev) ->
                handleRatingPillKey(overlay, row, idx, code, ev, cb));
        }
    }

    private static boolean handleRatingPillKey(FrameLayout overlay, LinearLayout row, int idx,
                                               int code, KeyEvent ev, DetailCallback cb) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK) { dismiss(overlay); cb.onDismiss(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return focusHorizontalChild(row, idx + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1), 1);
        }
        return handleRatingVerticalKey(overlay, code);
    }

    private static boolean handleRatingVerticalKey(FrameLayout overlay, int code) {
        if (code == KeyEvent.KEYCODE_DPAD_UP) return focusFirstActionIfPresent(overlay);
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) return focusFirstSimilarIfAvailable(overlay);
        return false;
    }

    private static boolean focusFirstActionIfPresent(View root) {
        LinearLayout actions = findTagged(root, TAG_ACTIONS);
        if (actions != null && actions.getChildCount() > 0) {
            actions.getChildAt(0).requestFocus();
            return true;
        }
        return false;
    }

    private static void populateSimilarPosters(LinearLayout row, JSONArray items,
                                               Context ctx, DetailCallback cb) {
        row.removeAllViews();
        if (items == null || items.length() == 0) {
            addEmptySimilarHint(row, ctx);
            return;
        }
        int count = Math.min(items.length(), 8);
        for (int i = 0; i < count; i++) {
            addAnimatedSimilarCard(row, items.optJSONObject(i), i, count, ctx, cb);
        }
    }

    private static void addEmptySimilarHint(LinearLayout row, Context ctx) {
        row.addView(GlassTheme.makeText(ctx,
            "Press 'Explore Similar' above to discover related titles.", 10.5f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_REGULAR, 1));
    }

    private static void addAnimatedSimilarCard(LinearLayout row, JSONObject sim, int i, int count,
                                               Context ctx, DetailCallback cb) {
        if (sim == null) return;
        View card = buildSimilarPosterCard(row, sim, i, count, ctx, cb);
        card.setAlpha(0f);
        card.setTranslationY(dp(ctx, 12));
        row.addView(card);
        card.animate().alpha(1f).translationY(0f).setStartDelay(i * 35L)
            .setDuration(240).setInterpolator(GlassTheme.EASE).start();
    }

    private static View buildSimilarPosterCard(LinearLayout parentRow, JSONObject sim,
                                               int idx, int total,
                                               Context ctx, DetailCallback cb) {
        int w = dp(ctx, SIM_W), h = dp(ctx, SIM_H);
        float r = dp(ctx, 10);
        LinearLayout wrap = createSimilarCardWrapper(ctx, w);
        FrameLayout frame = createSimilarImageFrame(ctx, sim, w, h, r);
        wrap.addView(frame);
        TextView titleTv = createSimilarCaptionView(ctx, sim);
        wrap.addView(titleTv);
        bindSimilarCardEvents(parentRow, wrap, frame, titleTv, sim, idx, r, ctx, cb);
        return wrap;
    }

    private static LinearLayout createSimilarCardWrapper(Context ctx, int w) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setFocusable(true);
        wrap.setClickable(true);
        wrap.setClipChildren(false);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        wLp.rightMargin = dp(ctx, 12);
        wrap.setLayoutParams(wLp);
        return wrap;
    }

    private static FrameLayout createSimilarImageFrame(Context ctx, JSONObject sim, int w, int h, float r) {
        FrameLayout frame = new FrameLayout(ctx);
        frame.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        frame.setBackground(GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_LIGHT, dp(ctx, 1), r));
        applyRoundRectOutline(frame, r);
        frame.addView(createSimilarImageView(ctx, sim), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private static ImageView createSimilarImageView(Context ctx, JSONObject sim) {
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(GlassTheme.SURFACE_ELEV);
        loadSimilarImage(ctx, sim, img);
        return img;
    }

    private static void loadSimilarImage(Context ctx, JSONObject sim, ImageView img) {
        String url = sim.optString("poster_url", "");
        if (url.isEmpty()) url = sim.optString("backdrop_url", "");
        if (!url.isEmpty()) ImageLoader.load(ctx, url, img);
    }

    private static TextView createSimilarCaptionView(Context ctx, JSONObject sim) {
        TextView titleTv = GlassTheme.makeText(ctx, formatSimilarCaption(sim), 9.5f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1);
        titleTv.setGravity(Gravity.CENTER_HORIZONTAL);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = GlassTheme.matchWrapParams();
        tLp.topMargin = dp(ctx, 4);
        titleTv.setLayoutParams(tLp);
        return titleTv;
    }

    private static String formatSimilarCaption(JSONObject sim) {
        String genres = sim.optString("genres", "");
        String firstGenre = genres.isEmpty() ? "" : genres.split(",")[0].trim();
        int year = sim.optInt("year", 0);
        if (!firstGenre.isEmpty() && year > 0) return firstGenre + " \u00B7 " + year;
        return !firstGenre.isEmpty() ? firstGenre : (year > 0 ? String.valueOf(year) : "Similar");
    }

    private static void bindSimilarCardEvents(LinearLayout parentRow, LinearLayout wrap, FrameLayout frame,
                                              TextView titleTv, JSONObject sim, int idx, float r,
                                              Context ctx, DetailCallback cb) {
        wrap.setOnFocusChangeListener((v, hasFocus) ->
            updateSimilarCardFocus(v, frame, titleTv, hasFocus, r, ctx));
        wrap.setOnClickListener(v -> cb.onTraversalClicked(sim));
        wrap.setOnKeyListener((v, code, ev) -> handleSimilarCardKey(parentRow, idx, code, ev, cb));
    }

    private static void updateSimilarCardFocus(View v, FrameLayout frame, TextView titleTv,
                                               boolean hasFocus, float r, Context ctx) {
        int stroke = hasFocus ? Color.WHITE : GlassTheme.BORDER_LIGHT;
        int sw = hasFocus ? dp(ctx, 2.5f) : dp(ctx, 1);
        frame.setBackground(GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV, stroke, sw, r));
        titleTv.setTextColor(hasFocus ? Color.WHITE : GlassTheme.TEXT_SECONDARY);
        float scale = hasFocus ? 1.05f : 1.0f;
        v.animate().scaleX(scale).scaleY(scale).setDuration(140).start();
    }

    private static boolean handleSimilarCardKey(LinearLayout parentRow, int idx, int code,
                                                KeyEvent ev, DetailCallback cb) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_BACK) { cb.onDismiss(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return focusHorizontalChild(parentRow, idx + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1), 0);
        }
        return code == KeyEvent.KEYCODE_DPAD_UP && moveUpFromSimilar(parentRow.getRootView());
    }

    private static boolean moveUpFromSimilar(View root) {
        LinearLayout rateRow = findTagged(root, TAG_RATING_ROW);
        if (rateRow != null && rateRow.getVisibility() == View.VISIBLE && rateRow.getChildCount() > 1) {
            rateRow.getChildAt(1).requestFocus();
            return true;
        }
        return focusFirstActionIfPresent(root);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static LinearLayout.LayoutParams createRightMarginParams(Context ctx, int rightMarginDp) {
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.rightMargin = dp(ctx, rightMarginDp);
        return lp;
    }

    private static String buildCleanMeta(JSONObject item) {
        StringBuilder sb = new StringBuilder();
        int year = item.optInt("year", 0);
        if (year > 0) sb.append(year);
        String type = item.optString("type", "");
        if (!type.isEmpty()) appendMetaToken(sb, type.equalsIgnoreCase("movie") ? "Movie" : "Series");
        int rt = item.optInt("runtime_min", 0);
        if (rt > 0) appendMetaToken(sb, rt + " min");
        String genres = item.optString("genres", "");
        if (!genres.isEmpty()) appendMetaToken(sb, genres);
        return sb.toString();
    }

    private static void appendMetaToken(StringBuilder sb, String token) {
        if (sb.length() > 0) sb.append("  \u00B7  ");
        sb.append(token);
    }

    private static String monogram(String s) {
        if (s == null || s.trim().isEmpty()) return "TV";
        String clean = s.trim().toUpperCase();
        return clean.length() >= 2 ? clean.substring(0, 2) : clean;
    }

    private static void animateIn(FrameLayout overlay, Context ctx) {
        overlay.animate().cancel();
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(220).setInterpolator(GlassTheme.EASE).start();
        animatePanelIn(findTagged(overlay, TAG_PANEL), ctx);
    }

    private static void animatePanelIn(View panel, Context ctx) {
        if (panel == null) return;
        panel.animate().cancel();
        panel.setAlpha(0f);
        panel.setScaleX(0.95f);
        panel.setScaleY(0.95f);
        panel.setTranslationY(dp(ctx, 18));
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(320)
            .setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)).start();
    }

    private static int dp(Context ctx, float v) {
        return GlassTheme.dp(ctx, v);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findTagged(View root, int tag) {
        return root != null ? (T) root.findViewWithTag(tag) : null;
    }
}
