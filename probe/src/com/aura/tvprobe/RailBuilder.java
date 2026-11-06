package com.aura.tvprobe;

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
import android.widget.TextView;
import org.json.JSONObject;
import java.util.List;

/**
 * Builds horizontal content rails: continue watching, apps dock, and poster rows.
 * Glass-themed, D-pad navigable with focus-driven horizontal scrolling.
 */
public final class RailBuilder {

    private static final String TAG_ITEMS = "rail_items";
    private static final String TAG_SCROLL = "rail_scroll";
    private static final int CARD_GAP = 10;
    private static final int APP_SIZE = 46;
    private static final int APP_RADIUS = 12;
    private static final int PROGRESS_H = 3;
    private static final int MAX_CONTINUE = 4;

    // ── Callback interface ──────────────────────────────────────────────

    public interface RailCallback {
        void onPosterClicked(JSONObject item, View posterView);
        void onPosterFocused(JSONObject item, View posterView);
        void onAppClicked(String packageName);
        void onContinueWatchingClicked(JSONObject item);
    }

    // ── Public API ──────────────────────────────────────────────────────

    public static LinearLayout buildContinueWatching(Context ctx, List<JSONObject> items,
                                                      RailCallback cb) {
        LinearLayout rail = createRailContainer(ctx);
        addRailLabel(rail, ctx, "Continue");
        LinearLayout row = createHorizontalRow(ctx);
        int count = Math.min(items.size(), MAX_CONTINUE);
        for (int i = 0; i < count; i++) {
            addContinueCard(row, ctx, items.get(i), cb);
        }
        wrapInScrollView(rail, row, ctx);
        return rail;
    }

    public static LinearLayout buildAppsDock(Context ctx, List<JSONObject> installedApps,
                                              RailCallback cb) {
        LinearLayout rail = createRailContainer(ctx);
        addRailLabel(rail, ctx, "Apps");
        LinearLayout row = createHorizontalRow(ctx);
        for (JSONObject app : installedApps) {
            addAppTile(row, ctx, app, cb);
        }
        addAllAppsTile(row, ctx, cb);
        wrapInScrollView(rail, row, ctx);
        return rail;
    }

    public static LinearLayout buildContentRail(Context ctx, String title, String accentIcon,
                                                  List<JSONObject> items, RailCallback cb) {
        LinearLayout rail = createRailContainer(ctx);
        addRailHeader(rail, ctx, title, accentIcon);
        LinearLayout row = createHorizontalRow(ctx);
        for (JSONObject item : items) {
            addPosterCard(row, ctx, item, cb);
        }
        wrapInScrollView(rail, row, ctx);
        rail.setTag(items);
        return rail;
    }

    public static int getFocusedIndex(LinearLayout rail) {
        LinearLayout row = findScrollContent(rail);
        if (row == null) return -1;
        return findFocusedChild(row);
    }

    // ── Rail container ──────────────────────────────────────────────────

    private static LinearLayout createRailContainer(Context ctx) {
        LinearLayout rail = new LinearLayout(ctx);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        return rail;
    }

    private static LinearLayout createHorizontalRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        return row;
    }

    private static void wrapInScrollView(LinearLayout rail, LinearLayout row, Context ctx) {
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setTag(TAG_SCROLL);
        scroll.addView(row, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rail.addView(scroll, GlassTheme.matchWrapParams());
    }

    // ── Labels & headers ────────────────────────────────────────────────

    private static void addRailLabel(LinearLayout rail, Context ctx, String text) {
        TextView label = GlassTheme.makeText(ctx, text, 12, GlassTheme.TEXT_SECONDARY,
                                              GlassTheme.FONT_BOLD, 1);
        label.setPadding(dp(ctx, 4), 0, 0, dp(ctx, 6));
        rail.addView(label, GlassTheme.wrapParams());
    }

    private static void addRailHeader(LinearLayout rail, Context ctx,
                                       String title, String icon) {
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(ctx, 4), 0, 0, dp(ctx, 6));
        addHeaderIcon(header, ctx, icon);
        addHeaderTitle(header, ctx, title);
        addHeaderArrow(header, ctx);
        rail.addView(header, GlassTheme.wrapParams());
    }

    private static void addHeaderIcon(LinearLayout header, Context ctx, String icon) {
        if (icon == null || icon.isEmpty()) return;
        TextView iconTv = GlassTheme.makeText(ctx, icon, 14, GlassTheme.ACCENT,
                                               GlassTheme.FONT_BOLD, 1);
        iconTv.setPadding(0, 0, dp(ctx, 6), 0);
        header.addView(iconTv, GlassTheme.wrapParams());
    }

    private static void addHeaderTitle(LinearLayout header, Context ctx, String title) {
        TextView tv = GlassTheme.makeText(ctx, title, 14, GlassTheme.TEXT_PRIMARY,
                                           GlassTheme.FONT_BOLD, 1);
        header.addView(tv, GlassTheme.wrapParams());
    }

    private static void addHeaderArrow(LinearLayout header, Context ctx) {
        TextView arrow = GlassTheme.makeText(ctx, " ›", 16, GlassTheme.TEXT_MUTED,
                                              GlassTheme.FONT_REGULAR, 1);
        header.addView(arrow, GlassTheme.wrapParams());
    }

    // ── Continue watching card ──────────────────────────────────────────

    private static void addContinueCard(LinearLayout row, Context ctx,
                                         JSONObject item, RailCallback cb) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GlassTheme.glassCard(12));
        card.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 6));
        addContinueContent(card, ctx, item);
        addProgressBar(card, ctx, item);
        applyCardInteraction(card, item, cb, true);
        addCardToRow(row, card, ctx, dp(ctx, 160), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void addContinueContent(LinearLayout card, Context ctx, JSONObject item) {
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView play = GlassTheme.makeText(ctx, "▶", 14, GlassTheme.ACCENT,
                                             GlassTheme.FONT_BOLD, 1);
        play.setPadding(0, 0, dp(ctx, 6), 0);
        top.addView(play, GlassTheme.wrapParams());
        addContinueLabels(top, ctx, item);
        card.addView(top, GlassTheme.wrapParams());
    }

    private static void addContinueLabels(LinearLayout top, Context ctx, JSONObject item) {
        LinearLayout labels = new LinearLayout(ctx);
        labels.setOrientation(LinearLayout.VERTICAL);
        String series = item.optString("series_title", "");
        String rawTitle = !series.isEmpty() ? series : item.optString("title", "Resume");
        TextView title = GlassTheme.makeText(ctx, rawTitle, 11, GlassTheme.TEXT_PRIMARY,
                                              GlassTheme.FONT_BOLD, 1);
        labels.addView(title, GlassTheme.wrapParams());
        String epInfo = formatEpisodeInfo(item);
        if (!epInfo.isEmpty()) {
            TextView ep = GlassTheme.makeText(ctx, epInfo, 9, GlassTheme.TEXT_MUTED,
                                               GlassTheme.FONT_REGULAR, 1);
            labels.addView(ep, GlassTheme.wrapParams());
        }
        top.addView(labels, GlassTheme.wrapParams());
    }

    private static String formatEpisodeInfo(JSONObject item) {
        int s = item.optInt("season", 0);
        int e = item.optInt("episode", 0);
        String app = item.optString("app_name", item.optString("app", ""));
        if (s > 0 && e > 0) return "S" + s + " E" + e + (app.isEmpty() ? "" : " \u00B7 " + app);
        return app;
    }

    private static void addProgressBar(LinearLayout card, Context ctx, JSONObject item) {
        int pct = computeProgressPct(item);
        FrameLayout bar = new FrameLayout(ctx);
        int h = dp(ctx, PROGRESS_H);
        bar.setBackground(GlassTheme.solidRounded(GlassTheme.DIVIDER, 2));
        View fill = new View(ctx);
        fill.setBackground(GlassTheme.solidRounded(GlassTheme.ACCENT, 2));
        FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(0, h);
        bar.addView(fill, fillLp);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h);
        barLp.setMargins(0, dp(ctx, 6), 0, 0);
        card.addView(bar, barLp);
        bar.post(() -> setProgressWidth(fill, bar, pct));
    }

    private static int computeProgressPct(JSONObject item) {
        int pct = item.optInt("progress_pct", -1);
        if (pct >= 0) return pct;
        int pos = item.optInt("position_sec", 0);
        int dur = item.optInt("duration_sec", 0);
        return dur > 0 ? Math.min(100, Math.max(8, (pos * 100) / dur)) : 35;
    }

    private static void setProgressWidth(View fill, FrameLayout bar, int pct) {
        int totalW = bar.getWidth();
        if (totalW <= 0) return;
        ViewGroup.LayoutParams lp = fill.getLayoutParams();
        lp.width = (int) (totalW * Math.max(0, Math.min(100, pct)) / 100f);
        fill.setLayoutParams(lp);
    }

    // ── Apps dock ───────────────────────────────────────────────────────

    private static void addAppTile(LinearLayout row, Context ctx, JSONObject app, RailCallback cb) {
        String name = app.optString("name", "");
        String pkg = app.optString("package", "");
        int color = parseColorSafe(app.optString("color", "#333333"));
        FrameLayout tile = createAppTileFrame(ctx, color);
        addAppContent(tile, ctx, name);
        tile.setElevation(dp(ctx, 3));
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setOnClickListener(v -> cb.onAppClicked(pkg));
        applyTileFocus(tile, ctx);
        addTileToRow(row, tile, ctx);
    }

    private static FrameLayout createAppTileFrame(Context ctx, int color) {
        FrameLayout tile = new FrameLayout(ctx);
        tile.setBackground(GlassTheme.solidRounded(color, APP_RADIUS));
        return tile;
    }

    private static void addAppContent(FrameLayout tile, Context ctx, String name) {
        String lower = name.toLowerCase();
        String display = getAppDisplayText(lower, name);
        int textSize = getAppTextSize(lower);
        TextView label = GlassTheme.makeText(ctx, display, textSize, Color.WHITE,
                                              GlassTheme.FONT_BOLD, 2);
        label.setGravity(Gravity.CENTER);
        tile.addView(label, matchWrapParams());
    }

    private static String getAppDisplayText(String lower, String name) {
        if (lower.contains("netflix")) return "N";
        if (lower.contains("prime")) return "prime\nvideo";
        if (lower.contains("hotstar")) return "★\nHOTSTAR";
        if (lower.contains("apple")) return "tv+";
        if (lower.contains("youtube") || lower.contains("smarttube")) return "▶";
        if (lower.contains("sony")) return "SONY\nLIV";
        if (lower.contains("zee")) return "ZEE5";
        return name.length() > 3 ? name.substring(0, 2) : name;
    }

    private static int getAppTextSize(String lower) {
        if (lower.contains("netflix")) return 24;
        if (lower.contains("youtube") || lower.contains("smarttube")) return 20;
        if (lower.contains("hotstar") || lower.contains("sony")) return 7;
        if (lower.contains("prime")) return 8;
        return 10;
    }

    private static void addAllAppsTile(LinearLayout row, Context ctx, RailCallback cb) {
        FrameLayout tile = new FrameLayout(ctx);
        tile.setBackground(createDashedBorder(ctx));
        TextView plus = GlassTheme.makeText(ctx, "+", 20, GlassTheme.TEXT_MUTED,
                                             GlassTheme.FONT_REGULAR, 1);
        plus.setGravity(Gravity.CENTER);
        tile.addView(plus, matchWrapParams());
        tile.setFocusable(true);
        tile.setClickable(true);
        applyTileFocus(tile, ctx);
        addTileToRow(row, tile, ctx);
    }

    private static void addTileToRow(LinearLayout row, FrameLayout tile, Context ctx) {
        int s = dp(ctx, APP_SIZE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.setMargins(0, 0, dp(ctx, CARD_GAP), 0);
        row.addView(tile, lp);
    }

    private static void applyTileFocus(FrameLayout tile, Context ctx) {
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.08f : 1f);
            v.setScaleY(hasFocus ? 1.08f : 1f);
        });
    }

    // ── Content rail poster card ────────────────────────────────────────

    private static void addPosterCard(LinearLayout row, Context ctx,
                                       JSONObject item, RailCallback cb) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        addPosterImage(card, ctx, item);
        addPosterInfo(card, ctx, item);
        applyCardInteraction(card, item, cb, false);
        int w = dp(ctx, GlassTheme.POSTER_W);
        addCardToRow(row, card, ctx, w, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void addPosterImage(LinearLayout card, Context ctx, JSONObject item) {
        FrameLayout imgFrame = new FrameLayout(ctx);
        int w = dp(ctx, GlassTheme.POSTER_W);
        int h = dp(ctx, GlassTheme.POSTER_H);
        ImageView iv = createPosterImageView(ctx, w, h);
        loadPoster(iv, item, ctx);
        imgFrame.addView(iv, new FrameLayout.LayoutParams(w, h));
        addPlatformDot(imgFrame, ctx, item);
        addImdbBadge(imgFrame, ctx, item);
        card.addView(imgFrame, new LinearLayout.LayoutParams(w, h));
    }

    private static ImageView createPosterImageView(Context ctx, int w, int h) {
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(GlassTheme.DIVIDER);
        ImageLoader.applyRoundedCorners(iv, dp(ctx, 8));
        return iv;
    }

    private static void loadPoster(ImageView iv, JSONObject item, Context ctx) {
        String url = item.optString("poster_url", "");
        if (!url.isEmpty()) ImageLoader.load(ctx, url, iv);
    }

    // ── Platform dot & IMDb badge ───────────────────────────────────────

    private static void addPlatformDot(FrameLayout frame, Context ctx, JSONObject item) {
        int color = getPlatformColor(item.optString("app", ""));
        View dot = new View(ctx);
        int s = dp(ctx, 7);
        dot.setBackground(GlassTheme.solidRounded(color, 4));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(dp(ctx, 6), dp(ctx, 6), 0, 0);
        frame.addView(dot, lp);
    }

    private static void addImdbBadge(FrameLayout frame, Context ctx, JSONObject item) {
        String txt = HeroSliderBuilder.extractRatingText(item);
        if (txt.isEmpty()) return;
        TextView badge = GlassTheme.makeText(ctx, txt, 8,
                                              GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        badge.setPadding(dp(ctx, 4), dp(ctx, 2), dp(ctx, 4), dp(ctx, 2));
        badge.setBackground(GlassTheme.glassPanel(dp(ctx, 5)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.setMargins(0, dp(ctx, 6), dp(ctx, 6), 0);
        frame.addView(badge, lp);
    }

    // ── Poster info (below image) ───────────────────────────────────────

    private static void addPosterInfo(LinearLayout card, Context ctx, JSONObject item) {
        TextView title = GlassTheme.makeText(ctx, item.optString("title", ""),
                                              11, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        title.setPadding(dp(ctx, 2), dp(ctx, 4), 0, 0);
        card.addView(title, GlassTheme.wrapParams());
        addPosterMeta(card, ctx, item);
    }

    private static void addPosterMeta(LinearLayout card, Context ctx, JSONObject item) {
        String meta = buildPosterMetaText(item);
        if (meta.isEmpty()) return;
        TextView tv = GlassTheme.makeText(ctx, meta, 9, GlassTheme.TEXT_MUTED,
                                           GlassTheme.FONT_REGULAR, 1);
        tv.setPadding(dp(ctx, 2), 0, 0, 0);
        card.addView(tv, GlassTheme.wrapParams());
    }

    private static String buildPosterMetaText(JSONObject item) {
        StringBuilder sb = new StringBuilder();
        int year = item.optInt("year", 0);
        if (year > 0) sb.append(year);
        String type = item.optString("type", "");
        if (!type.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(capitalize(type));
        }
        return sb.toString();
    }

    // ── Card interaction & focus ─────────────────────────────────────────

    private static void applyCardInteraction(View card, JSONObject item,
                                              RailCallback cb, boolean isContinue) {
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(v -> handleCardClick(v, item, cb, isContinue));
        card.setOnFocusChangeListener((v, hasFocus) -> handleCardFocus(v, item, cb, hasFocus));
        setupHorizontalDpad(card);
    }

    private static void handleCardClick(View v, JSONObject item,
                                         RailCallback cb, boolean isContinue) {
        if (isContinue) {
            cb.onContinueWatchingClicked(item);
        } else {
            cb.onPosterClicked(item, v);
        }
    }

    private static void handleCardFocus(View v, JSONObject item,
                                          RailCallback cb, boolean hasFocus) {
        applyPosterFocusStyle(v, hasFocus);
        if (hasFocus && cb != null) cb.onPosterFocused(item, v);
        if (hasFocus) scrollToChild(v);
    }

    private static void applyPosterFocusStyle(View card, boolean focused) {
        if (focused) {
            card.setBackground(GlassTheme.solidRounded(GlassTheme.ACCENT_LIGHT, 8));
            card.setElevation(dp(card.getContext(), 6));
        } else {
            card.setBackground(null);
            card.setElevation(0);
        }
    }

    private static void scrollToChild(View child) {
        if (child.getParent() == null) return;
        View row = (View) child.getParent();
        if (row.getParent() instanceof HorizontalScrollView) {
            HorizontalScrollView scroll = (HorizontalScrollView) row.getParent();
            scroll.smoothScrollTo(Math.max(0, child.getLeft() - dp(child.getContext(), 16)), 0);
        }
    }

    // ── D-pad horizontal navigation ─────────────────────────────────────

    private static void setupHorizontalDpad(View card) {
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            return handleHorizontalNav(v, keyCode);
        });
    }

    private static boolean handleHorizontalNav(View v, int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return moveSibling(v, -1);
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return moveSibling(v, 1);
        return false;
    }

    private static boolean moveSibling(View current, int dir) {
        ViewGroup parent = (ViewGroup) current.getParent();
        if (parent == null) return false;
        int idx = parent.indexOfChild(current) + dir;
        if (idx >= 0 && idx < parent.getChildCount()) {
            return parent.getChildAt(idx).requestFocus();
        }
        return false;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static void addCardToRow(LinearLayout row, View card, Context ctx, int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(0, 0, dp(ctx, CARD_GAP), 0);
        row.addView(card, lp);
    }

    private static LinearLayout findScrollContent(LinearLayout rail) {
        for (int i = 0; i < rail.getChildCount(); i++) {
            if (rail.getChildAt(i) instanceof HorizontalScrollView) {
                HorizontalScrollView scroll = (HorizontalScrollView) rail.getChildAt(i);
                if (scroll.getChildCount() > 0 && scroll.getChildAt(0) instanceof LinearLayout) {
                    return (LinearLayout) scroll.getChildAt(0);
                }
            }
        }
        return null;
    }

    private static int findFocusedChild(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i).hasFocus()) return i;
        }
        return -1;
    }

    private static android.graphics.drawable.GradientDrawable createDashedBorder(Context ctx) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(ctx, APP_RADIUS));
        d.setStroke(dp(ctx, 1), GlassTheme.TEXT_MUTED, dp(ctx, 4), dp(ctx, 3));
        d.setColor(Color.TRANSPARENT);
        return d;
    }

    private static int getPlatformColor(String app) {
        if (app == null) return GlassTheme.ACCENT;
        String lower = app.toLowerCase();
        if (lower.contains("netflix")) return GlassTheme.COLOR_NETFLIX;
        if (lower.contains("prime")) return GlassTheme.COLOR_PRIME;
        if (lower.contains("hotstar")) return GlassTheme.COLOR_HOTSTAR;
        if (lower.contains("apple")) return GlassTheme.COLOR_APPLE_TV;
        if (lower.contains("youtube") || lower.contains("smarttube"))
            return GlassTheme.COLOR_YOUTUBE;
        if (lower.contains("sony")) return GlassTheme.COLOR_SONYLIV;
        if (lower.contains("zee")) return GlassTheme.COLOR_ZEE5;
        return GlassTheme.ACCENT;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static int parseColorSafe(String hex) {
        try { return Color.parseColor(hex); } catch (Exception e) { return Color.GRAY; }
    }

    private static FrameLayout.LayoutParams matchWrapParams() {
        return new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static int dp(Context ctx, int dpVal) {
        return GlassTheme.dp(ctx, dpVal);
    }

    private RailBuilder() {}
}
