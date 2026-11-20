package com.aura.tvprobe;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.List;

/**
 * Native-feeling TV rails with hardware-accelerated ObjectAnimator transitions
 * (disabling Android's synchronous ScrollView snap jump), dark-glass app tiles,
 * diverse Continue Watching cards, and high-contrast platinum-white focus rings.
 */
public final class RailBuilder {

    private static final String TAG_SCROLL = "rail_scroll";
    private static final String TAG_BANNER_BOX = "app_banner_box";
    private static final int TAG_TARGET_SCROLL_X    = 0x7F0B0001;
    private static final int TAG_SCROLL_ANIM        = 0x7F0B0002;
    private static final int TAG_LAST_FOCUSED_INDEX = 0x7F0B0003;
    private static final int CARD_GAP = 10;
    private static final int APP_RADIUS = 11;
    private static final int PROGRESS_H = 3;
    private static final int MAX_CONTINUE = 5;

    public interface RailCallback {
        void onPosterClicked(JSONObject item, View posterView);
        void onPosterFocused(JSONObject item, View posterView);
        void onAppClicked(String packageName);
        void onContinueWatchingClicked(JSONObject item);
        default void onMoveUpFromTopRail() {}
        default void onMoveLeftToNav() {}
    }

    /**
     * Custom ScrollView that suppresses Android's instant synchronous focus jump
     * and internal FocusFinder arrowScroll so all vertical rail transitions stay
     * strictly under deterministic D-pad control.
     */
    public static final class SmoothVerticalScrollView extends ScrollView {
        public SmoothVerticalScrollView(Context context) {
            super(context);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }

        @Override
        public boolean executeKeyEvent(KeyEvent event) {
            return false;
        }
    }

    /**
     * Custom HorizontalScrollView that suppresses instant synchronous focus jump
     * and internal arrowScroll so horizontal card traversal never leaks focus.
     */
    private static final class SmoothHorizontalScrollView extends HorizontalScrollView {
        SmoothHorizontalScrollView(Context context) {
            super(context);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }

        @Override
        public boolean executeKeyEvent(KeyEvent event) {
            return false;
        }
    }

    private static int getAvailableWidth(Context ctx) {
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(ctx, 600), screenW - dp(ctx, 40));
    }

    private static int computeColumnWidth(Context ctx, int cols) {
        int availW = getAvailableWidth(ctx);
        int gap = dp(ctx, CARD_GAP);
        return (availW - gap * (cols - 1)) / cols;
    }

    public static LinearLayout buildContinueWatching(Context ctx, List<JSONObject> items,
                                                     RailCallback cb) {
        LinearLayout rail = createRailContainer(ctx);
        addRailHeader(rail, ctx, "Continue Watching", null);
        LinearLayout row = createHorizontalRow(ctx);
        int count = Math.min(items.size(), MAX_CONTINUE);
        int cardW = computeColumnWidth(ctx, 5);
        for (int i = 0; i < count; i++) {
            addContinueCard(row, ctx, items.get(i), cb, cardW, i == count - 1);
        }
        wrapInScrollView(rail, row, ctx);
        return rail;
    }

    public static LinearLayout buildAppsDock(Context ctx, List<JSONObject> installedApps,
                                             RailCallback cb) {
        LinearLayout rail = createRailContainer(ctx);
        addRailHeader(rail, ctx, "Apps", null);
        LinearLayout row = createHorizontalRow(ctx);
        int appW = computeColumnWidth(ctx, 8);
        int appH = Math.max(dp(ctx, 46), Math.round(appW * 0.49f));
        for (int i = 0; i < installedApps.size(); i++) {
            addAppTile(row, ctx, installedApps.get(i), cb, appW, appH, i == installedApps.size() - 1);
        }
        wrapInScrollView(rail, row, ctx);
        return rail;
    }

    public static LinearLayout buildContentRail(Context ctx, String title, String subtitle,
                                                List<JSONObject> items, RailCallback cb) {
        LinearLayout rail = createRailContainer(ctx);
        addRailHeader(rail, ctx, title, subtitle);
        LinearLayout row = createHorizontalRow(ctx);
        populateContentRailRow(row, ctx, items, cb);
        wrapInScrollView(rail, row, ctx);
        rail.setTag(items);
        return rail;
    }

    private static void populateContentRailRow(LinearLayout row, Context ctx, List<JSONObject> items, RailCallback cb) {
        int posterW = computeColumnWidth(ctx, 7);
        int posterH = Math.round(posterW * 1.44f);
        for (int i = 0; i < items.size(); i++) {
            addPosterCard(row, ctx, items.get(i), cb, posterW, posterH, i == items.size() - 1);
        }
    }

    public static boolean focusFirstItem(LinearLayout rail) {
        return focusItemAt(rail, 0);
    }

    public static boolean focusItemAt(LinearLayout rail, int cardIndex) {
        LinearLayout row = findScrollContent(rail);
        if (row != null && row.getChildCount() > 0) {
            int idx = Math.max(0, Math.min(cardIndex, row.getChildCount() - 1));
            View target = row.getChildAt(idx);
            target.post(target::requestFocus);
            return target.requestFocus();
        }
        return false;
    }

    private static LinearLayout createRailContainer(Context ctx) {
        LinearLayout rail = new LinearLayout(ctx);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setClipChildren(false);
        rail.setClipToPadding(false);
        rail.setPadding(0, dp(ctx, 3), 0, dp(ctx, 4));
        return rail;
    }

    private static LinearLayout createHorizontalRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(0, dp(ctx, 4), 0, dp(ctx, 6));
        return row;
    }

    private static void wrapInScrollView(LinearLayout rail, LinearLayout row, Context ctx) {
        SmoothHorizontalScrollView scroll = new SmoothHorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setTag(TAG_SCROLL);
        scroll.addView(row, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rail.addView(scroll, GlassTheme.matchWrapParams());
    }

    private static void addRailHeader(LinearLayout rail, Context ctx,
                                      String title, String subtitle) {
        LinearLayout header = createRailHeaderRow(ctx, title);
        if (subtitle != null && subtitle.length() > 2) {
            header.addView(GlassTheme.makeText(ctx, "   \u00B7   " + subtitle, 9.5f,
                GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1), GlassTheme.wrapParams());
        }
        rail.addView(header, GlassTheme.wrapParams());
    }

    private static LinearLayout createRailHeaderRow(Context ctx, String title) {
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(ctx, 2));
        header.addView(GlassTheme.makeText(ctx, title, 11.5f, GlassTheme.TEXT_SECONDARY,
            GlassTheme.FONT_BOLD, 1), GlassTheme.wrapParams());
        return header;
    }

    // ── Continue watching card (Responsive 5-Col Grid + Dark Glass) ──────

    private static void addContinueCard(LinearLayout row, Context ctx,
                                        JSONObject item, RailCallback cb,
                                        int cardW, boolean isLast) {
        float r = dp(ctx, 10);
        LinearLayout card = createContinueCardBox(ctx, r);
        addContinueTopBadge(card, ctx, item);
        addContinueTitle(card, ctx, item);
        addProgressBar(card, ctx, item);
        bindCardFocus(card, item, cb, true, r, ctx);
        addCardToRow(row, card, ctx, cardW, ViewGroup.LayoutParams.WRAP_CONTENT, isLast);
    }

    private static LinearLayout createContinueCardBox(Context ctx, float r) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GlassTheme.focusRounded(
            GlassTheme.GLASS_CARD, GlassTheme.BORDER_STRONG, dp(ctx, 1), r));
        card.setElevation(dp(ctx, 4));
        int padH = dp(ctx, 9), padV = dp(ctx, 6);
        card.setPadding(padH, padV, padH, padV);
        return card;
    }

    private static void addContinueTopBadge(LinearLayout card, Context ctx, JSONObject item) {
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(createContinueAppPill(ctx, item), GlassTheme.wrapParams());
        top.addView(createContinueProgressLabel(ctx, item), GlassTheme.wrapParams());
        card.addView(top, GlassTheme.wrapParams());
    }

    private static TextView createContinueAppPill(Context ctx, JSONObject item) {
        String rawApp = item.optString("app_name", item.optString("app", "Resume"));
        String app = HeroSliderBuilder.cleanPlatformName(rawApp);
        int color = HeroSliderBuilder.getPlatformColor(app);
        TextView pill = GlassTheme.makeText(ctx, "\u25B6 " + app.toUpperCase(), 7.5f,
            Color.WHITE, GlassTheme.FONT_BOLD, 1);
        pill.setBackground(GlassTheme.solidRounded(color, dp(ctx, 3)));
        pill.setPadding(dp(ctx, 5), dp(ctx, 1.5f), dp(ctx, 5), dp(ctx, 1.5f));
        return pill;
    }

    private static TextView createContinueProgressLabel(Context ctx, JSONObject item) {
        int pct = computeProgressPct(item);
        String ep = formatSeasonEp(item);
        String rightText = !ep.isEmpty() ? (ep + " \u00B7 " + pct + "%") : (pct + "%");
        return GlassTheme.makeText(ctx, "  " + rightText, 8.5f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
    }

    private static void addContinueTitle(LinearLayout card, Context ctx, JSONObject item) {
        String series = item.optString("series_title", "");
        String rawTitle = !series.isEmpty() ? series : item.optString("title", "Continue Watching");
        TextView title = GlassTheme.makeText(ctx, rawTitle, 10.5f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        title.setPadding(0, dp(ctx, 4), 0, dp(ctx, 5));
        card.addView(title, GlassTheme.matchWrapParams());
    }

    private static String formatSeasonEp(JSONObject item) {
        int s = item.optInt("season", 0);
        int e = item.optInt("episode", 0);
        if (s > 0 && e > 0) return "S" + s + ":E" + e;
        return "";
    }

    private static void addProgressBar(LinearLayout card, Context ctx, JSONObject item) {
        int pct = computeProgressPct(item);
        FrameLayout bar = new FrameLayout(ctx);
        int h = dp(ctx, PROGRESS_H);
        bar.setBackground(GlassTheme.solidRounded(GlassTheme.DIVIDER, dp(ctx, 2)));
        View fill = new View(ctx);
        fill.setBackground(GlassTheme.solidRounded(GlassTheme.GOLD_ACCENT, dp(ctx, 2)));
        bar.addView(fill, new FrameLayout.LayoutParams(0, h));
        card.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        bar.post(() -> updateProgressFillWidth(bar, fill, pct, ctx));
    }

    private static void updateProgressFillWidth(FrameLayout bar, View fill, int pct, Context ctx) {
        int totalW = bar.getWidth();
        if (totalW <= 0) return;
        ViewGroup.LayoutParams lp = fill.getLayoutParams();
        lp.width = Math.max(dp(ctx, 14), (int) (totalW * Math.max(12, Math.min(100, pct)) / 100f));
        fill.setLayoutParams(lp);
    }

    private static int computeProgressPct(JSONObject item) {
        int pct = item.optInt("progress_pct", -1);
        if (pct >= 0) return pct;
        int pos = item.optInt("position_sec", 0);
        int dur = item.optInt("duration_sec", 0);
        if (dur > 0 && pos > 0) return Math.min(98, Math.max(8, (pos * 100) / dur));
        return 35;
    }

    // ── Apps dock (Responsive 8-Col Tiles + Crisp White Focus Border) ─

    private static void addAppTile(LinearLayout row, Context ctx, JSONObject app,
                                   RailCallback cb, int appW, int appH, boolean isLast) {
        String name = app.optString("name", "App");
        String pkg = app.optString("package", "");
        LinearLayout wrapper = createAppTileWrapper(ctx);
        FrameLayout bannerBox = buildAppVisualBox(ctx, pkg, name, parseColorSafe(app.optString("color", "#1E293B")));
        bannerBox.setTag(TAG_BANNER_BOX);
        wrapper.addView(bannerBox, new LinearLayout.LayoutParams(appW, appH));
        TextView label = createAppTileLabel(ctx, name);
        wrapper.addView(label, GlassTheme.wrapParams());
        bindAppTileFocus(wrapper, bannerBox, label, pkg, cb, ctx);
        addCardToRow(row, wrapper, ctx, appW, ViewGroup.LayoutParams.WRAP_CONTENT, isLast);
    }

    private static LinearLayout createAppTileWrapper(Context ctx) {
        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        wrapper.setClipChildren(false);
        wrapper.setPadding(0, dp(ctx, 1), 0, dp(ctx, 1));
        return wrapper;
    }

    private static TextView createAppTileLabel(Context ctx, String name) {
        TextView label = GlassTheme.makeText(ctx, name, 9f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(ctx, 3), 0, 0);
        return label;
    }

    private static FrameLayout buildAppVisualBox(Context ctx, String pkg, String name, int color) {
        float rad = dp(ctx, APP_RADIUS);
        FrameLayout box = createBaseAppBox(ctx, rad);
        if (tryAddBannerImage(ctx, box, pkg, rad)) return box;
        if (tryAddIconImage(ctx, box, pkg)) return box;
        addFallbackAppText(ctx, box, name, color, rad);
        return box;
    }

    private static FrameLayout createBaseAppBox(Context ctx, float rad) {
        FrameLayout box = new FrameLayout(ctx);
        box.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), rad));
        box.setElevation(dp(ctx, 4));
        applyViewRoundedCorners(box, rad);
        return box;
    }

    private static void applyViewRoundedCorners(View v, float rad) {
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), rad);
            }
        });
        v.setClipToOutline(true);
    }

    private static boolean tryAddBannerImage(Context ctx, FrameLayout box, String pkg, float rad) {
        Drawable banner = loadNativeBanner(ctx, pkg);
        if (banner == null) return false;
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(banner);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ImageLoader.applyRoundedCorners(iv, rad - dp(ctx, 2));
        box.addView(iv, createInsetMatchParams(dp(ctx, 2)));
        return true;
    }

    private static FrameLayout.LayoutParams createInsetMatchParams(int inset) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(inset, inset, inset, inset);
        return lp;
    }

    private static boolean tryAddIconImage(Context ctx, FrameLayout box, String pkg) {
        Drawable icon = loadNativeIcon(ctx, pkg);
        if (icon == null) return false;
        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int s = dp(ctx, 36);
        box.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        return true;
    }

    private static void addFallbackAppText(Context ctx, FrameLayout box, String name, int color, float rad) {
        box.setBackground(GlassTheme.focusRounded(color, GlassTheme.BORDER_STRONG, dp(ctx, 1), rad));
        TextView tv = GlassTheme.makeText(ctx, name, 11.5f, Color.WHITE, GlassTheme.FONT_BOLD, 1);
        tv.setGravity(Gravity.CENTER);
        box.addView(tv, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static Drawable loadNativeBanner(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable b = loadDirectOrLeanbackBanner(pm, pkg);
            return b != null ? b : queryActivityBanners(pm, pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private static Drawable loadDirectOrLeanbackBanner(PackageManager pm, String pkg) throws Exception {
        Drawable b = pm.getApplicationBanner(pkg);
        if (b != null) return b;
        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
        b = pm.getApplicationBanner(ai);
        if (b != null) return b;
        android.content.Intent leanback = pm.getLeanbackLaunchIntentForPackage(pkg);
        return (leanback != null && leanback.getComponent() != null)
            ? pm.getActivityBanner(leanback.getComponent()) : null;
    }

    private static Drawable queryActivityBanners(PackageManager pm, String pkg) {
        android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN).setPackage(pkg);
        List<android.content.pm.ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        if (ris == null) return null;
        for (android.content.pm.ResolveInfo ri : ris) {
            Drawable b = ri.activityInfo != null ? ri.activityInfo.loadBanner(pm) : null;
            if (b != null) return b;
        }
        return null;
    }

    private static Drawable loadNativeIcon(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private static void bindAppTileFocus(LinearLayout wrapper, FrameLayout bannerBox,
                                         TextView label, String pkg,
                                         RailCallback cb, Context ctx) {
        wrapper.setFocusable(true);
        wrapper.setClickable(true);
        float rad = dp(ctx, APP_RADIUS);
        wrapper.setOnClickListener(v -> cb.onAppClicked(pkg));
        wrapper.setOnFocusChangeListener((v, focused) ->
            updateAppTileFocusState(v, bannerBox, label, focused, rad, ctx));
        setupRailDpad(wrapper, cb);
    }

    private static void updateAppTileFocusState(View v, FrameLayout bannerBox, TextView label,
                                                boolean focused, float rad, Context ctx) {
        bannerBox.setBackground(GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV,
            focused ? Color.WHITE : GlassTheme.BORDER_STRONG, dp(ctx, focused ? 2.5f : 1), rad));
        bannerBox.setElevation(dp(ctx, focused ? 12 : 4));
        label.setTextColor(focused ? Color.WHITE : GlassTheme.TEXT_SECONDARY);
        float scale = focused ? 1.08f : 1f;
        v.animate().scaleX(scale).scaleY(scale).setDuration(170).setInterpolator(GlassTheme.EASE).start();
        if (focused) scrollToChild(v);
    }

    // ── Content rail poster card (Responsive 7-Col Grid + Focus Ring) ────

    private static void addPosterCard(LinearLayout row, Context ctx,
                                      JSONObject item, RailCallback cb,
                                      int posterW, int posterH, boolean isLast) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setPadding(0, dp(ctx, 1), 0, dp(ctx, 1));
        FrameLayout imgFrame = addPosterImage(card, ctx, item, posterW, posterH);
        TextView titleView = addPosterInfo(card, ctx, item);
        bindPosterFocus(card, imgFrame, titleView, item, cb, ctx);
        addCardToRow(row, card, ctx, posterW, ViewGroup.LayoutParams.WRAP_CONTENT, isLast);
    }

    private static FrameLayout addPosterImage(LinearLayout card, Context ctx,
                                              JSONObject item, int w, int h) {
        FrameLayout imgFrame = new FrameLayout(ctx);
        imgFrame.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_LIGHT, dp(ctx, 1), dp(ctx, 11)));
        ImageView iv = createPosterImageView(ctx, item.optString("poster_url", ""));
        imgFrame.addView(iv, createInsetMatchParams(dp(ctx, 2)));
        card.addView(imgFrame, new LinearLayout.LayoutParams(w, h));
        return imgFrame;
    }

    private static ImageView createPosterImageView(Context ctx, String url) {
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(GlassTheme.SURFACE_ELEV);
        ImageLoader.applyRoundedCorners(iv, dp(ctx, 9));
        if (!url.isEmpty()) ImageLoader.load(ctx, url, iv);
        return iv;
    }

    private static void bindPosterFocus(LinearLayout card, FrameLayout imgFrame,
                                        TextView titleView, JSONObject item,
                                        RailCallback cb, Context ctx) {
        card.setFocusable(true);
        card.setClickable(true);
        float rad = dp(ctx, 11);
        card.setOnClickListener(v -> cb.onPosterClicked(item, v));
        card.setOnFocusChangeListener((v, hasFocus) ->
            updatePosterFocusState(v, imgFrame, titleView, item, cb, hasFocus, rad, ctx));
        setupRailDpad(card, cb);
    }

    private static void updatePosterFocusState(View v, FrameLayout imgFrame, TextView titleView,
                                               JSONObject item, RailCallback cb,
                                               boolean hasFocus, float rad, Context ctx) {
        imgFrame.setBackground(GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV,
            hasFocus ? Color.WHITE : GlassTheme.BORDER_LIGHT, dp(ctx, hasFocus ? 2.5f : 1), rad));
        imgFrame.setElevation(hasFocus ? dp(ctx, 14) : 0);
        if (titleView != null) titleView.setTextColor(hasFocus ? Color.WHITE : GlassTheme.TEXT_PRIMARY);
        float scale = hasFocus ? 1.06f : 1f;
        v.animate().scaleX(scale).scaleY(scale).setDuration(170).setInterpolator(GlassTheme.EASE).start();
        if (hasFocus) {
            if (cb != null) cb.onPosterFocused(item, v);
            scrollToChild(v);
        }
    }

    private static TextView addPosterInfo(LinearLayout card, Context ctx, JSONObject item) {
        String meta = buildPosterMetaText(item);
        TextView tv = GlassTheme.makeText(ctx, meta, 9.5f, GlassTheme.TEXT_SECONDARY,
                                          GlassTheme.FONT_MEDIUM, 1);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setPadding(dp(ctx, 2), dp(ctx, 4), dp(ctx, 2), 0);
        card.addView(tv, GlassTheme.matchWrapParams());
        return tv;
    }

    private static String buildPosterMetaText(JSONObject item) {
        String genres = item.optString("genres", "");
        String firstGenre = genres.isEmpty() ? "" : genres.split(",")[0].trim();
        int year = item.optInt("year", 0);
        if (!firstGenre.isEmpty() && year > 0) return firstGenre + " \u00B7 " + year;
        if (!firstGenre.isEmpty()) return firstGenre;
        if (year > 0) return String.valueOf(year);
        return "Featured";
    }

    // ── Unmistakable Platinum Focus Ring + Smooth Animated Scrolling ─────

    private static void bindCardFocus(View card, JSONObject item, RailCallback cb,
                                      boolean isContinue, float radiusPx, Context ctx) {
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(v -> {
            if (isContinue) cb.onContinueWatchingClicked(item);
            else cb.onPosterClicked(item, v);
        });
        card.setOnFocusChangeListener((v, hasFocus) ->
            updateCardFocusState(v, item, cb, isContinue, hasFocus, radiusPx, ctx));
        setupRailDpad(card, cb);
    }

    private static void updateCardFocusState(View v, JSONObject item, RailCallback cb,
                                             boolean isContinue, boolean hasFocus, float radiusPx, Context ctx) {
        if (hasFocus) {
            applyCardFocusedStyle(v, item, cb, isContinue, radiusPx, ctx);
        } else {
            applyCardUnfocusedStyle(v, isContinue, radiusPx, ctx);
        }
        float scale = hasFocus ? 1.07f : 1f;
        v.animate().scaleX(scale).scaleY(scale).setDuration(170).setInterpolator(GlassTheme.EASE).start();
    }

    private static void applyCardFocusedStyle(View v, JSONObject item, RailCallback cb,
                                              boolean isContinue, float radiusPx, Context ctx) {
        int bg = isContinue ? GlassTheme.SURFACE_ELEV : GlassTheme.ACCENT_BG;
        v.setBackground(GlassTheme.focusRounded(bg, Color.WHITE, dp(ctx, 2.5f), radiusPx));
        v.setElevation(dp(ctx, 14));
        if (cb != null) cb.onPosterFocused(item, v);
        scrollToChild(v);
    }

    private static void applyCardUnfocusedStyle(View v, boolean isContinue, float radiusPx, Context ctx) {
        v.setBackground(isContinue
            ? GlassTheme.focusRounded(GlassTheme.GLASS_CARD, GlassTheme.BORDER_STRONG, dp(ctx, 1), radiusPx)
            : null);
        v.setElevation(isContinue ? dp(ctx, 4) : 0);
    }

    private static void scrollToChild(View child) {
        if (!(child.getParent() instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) child.getParent();
        if (!(row.getParent() instanceof HorizontalScrollView)) return;
        HorizontalScrollView scroll = (HorizontalScrollView) row.getParent();
        row.setTag(TAG_LAST_FOCUSED_INDEX, row.indexOfChild(child));
        if (row.getWidth() <= 0 || child.getWidth() <= 0) {
            scroll.post(() -> scrollToChild(child));
            return;
        }
        applyHorizontalScroll(scroll, row, child);
    }

    private static void applyHorizontalScroll(HorizontalScrollView scroll, ViewGroup row, View child) {
        Context ctx = child.getContext();
        int viewportW = scroll.getWidth() > 0 ? scroll.getWidth() : getAvailableWidth(ctx);
        int targetX = computeTargetScrollX(
            row.indexOfChild(child), row.getChildCount(),
            child.getLeft(), child.getRight(),
            getTargetScrollX(scroll), viewportW, row.getWidth(), dp(ctx, 18));
        scroll.setTag(TAG_TARGET_SCROLL_X, targetX);
        cancelRunningAnim(scroll);
        if (targetX != scroll.getScrollX()) animateScrollX(scroll, targetX);
    }

    public static int computeTargetScrollX(int idx, int count, int childLeft, int childRight,
                                           int curTargetX, int viewportW, int rowW, int margin) {
        int maxScroll = Math.max(0, rowW - viewportW);
        if (idx <= 0 || maxScroll <= 0) return 0;
        if (idx == count - 1) return maxScroll;
        int targetX = adjustScrollWindow(childLeft, childRight, curTargetX, viewportW, margin);
        return Math.max(0, Math.min(targetX, maxScroll));
    }

    private static int adjustScrollWindow(int childLeft, int childRight, int curTargetX, int viewportW, int margin) {
        if (childLeft - margin < curTargetX) return Math.max(0, childLeft - margin);
        if (childRight + margin > curTargetX + viewportW) return Math.max(0, childRight + margin - viewportW);
        return curTargetX;
    }

    private static void animateScrollX(HorizontalScrollView scroll, int targetX) {
        ObjectAnimator anim = ObjectAnimator.ofInt(scroll, "scrollX", scroll.getScrollX(), targetX);
        anim.setDuration(200);
        anim.setInterpolator(GlassTheme.EASE);
        scroll.setTag(TAG_SCROLL_ANIM, anim);
        anim.start();
    }

    private static int getTargetScrollX(HorizontalScrollView scroll) {
        if (scroll == null) return 0;
        Object tag = scroll.getTag(TAG_TARGET_SCROLL_X);
        if (tag instanceof Integer) return (Integer) tag;
        return scroll.getScrollX();
    }

    private static void cancelRunningAnim(View view) {
        if (view == null) return;
        Object tag = view.getTag(TAG_SCROLL_ANIM);
        if (tag instanceof ObjectAnimator) {
            ((ObjectAnimator) tag).cancel();
            view.setTag(TAG_SCROLL_ANIM, null);
        }
    }

    private static void setupRailDpad(View card, RailCallback cb) {
        card.setOnKeyListener((v, keyCode, event) -> handleRailCardKey(v, keyCode, event, cb));
    }

    private static boolean handleRailCardKey(View v, int keyCode, KeyEvent event, RailCallback cb) {
        if (!isDpadKey(keyCode)) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return moveSibling(v, -1);
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return moveSibling(v, 1);
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return moveVerticalRail(v, 1, cb);
        return moveVerticalRail(v, -1, cb);
    }

    private static boolean isDpadKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private static boolean moveSibling(View current, int dir) {
        if (!(current.getParent() instanceof ViewGroup)) return false;
        ViewGroup parent = (ViewGroup) current.getParent();
        int nextIdx = Math.max(0, Math.min(parent.getChildCount() - 1, parent.indexOfChild(current) + dir));
        View target = parent.getChildAt(nextIdx);
        if (target != null && target != current) return target.requestFocus() || true;
        if (!current.isFocused()) current.requestFocus();
        scrollToChild(current);
        return true;
    }

    private static boolean moveVerticalRail(View currentItem, int railDir, RailCallback cb) {
        LinearLayout rail = findParentRail(currentItem);
        if (rail == null || !(rail.getParent() instanceof LinearLayout)) return false;
        LinearLayout railsContainer = (LinearLayout) rail.getParent();
        int targetIdx = railsContainer.indexOfChild(rail) + railDir;
        if (targetIdx < 0 && cb != null) cb.onMoveUpFromTopRail();
        else if (targetIdx >= 0 && targetIdx < railsContainer.getChildCount()) {
            focusClosestInTargetRail(currentItem, railsContainer.getChildAt(targetIdx));
        }
        return true;
    }

    private static LinearLayout findParentRail(View currentItem) {
        if (!(currentItem.getParent() instanceof ViewGroup)) return null;
        ViewGroup row = (ViewGroup) currentItem.getParent();
        if (!(row.getParent() instanceof View)) return null;
        View scroll = (View) row.getParent();
        return (scroll.getParent() instanceof LinearLayout) ? (LinearLayout) scroll.getParent() : null;
    }

    public static JSONObject resolveFocusedItem(View focused) {
        if (focused == null || !(focused.getParent() instanceof ViewGroup)) return null;
        LinearLayout rail = findParentRail(focused);
        if (rail == null || !(rail.getTag() instanceof List<?>)) return null;
        int idx = ((ViewGroup) focused.getParent()).indexOfChild(focused);
        List<?> items = (List<?>) rail.getTag();
        if (idx < 0 || idx >= items.size() || !(items.get(idx) instanceof JSONObject)) return null;
        return (JSONObject) items.get(idx);
    }

    private static void focusClosestInTargetRail(View currentItem, View nextRailView) {
        if (!(nextRailView instanceof LinearLayout)) return;
        LinearLayout nextRow = findScrollContent((LinearLayout) nextRailView);
        if (nextRow != null && nextRow.getChildCount() > 0) {
            View targetChild = findClosestChildInNextRow(currentItem, (ViewGroup) currentItem.getParent(), nextRow);
            scrollOuterVerticallyAnimated(nextRailView);
            targetChild.requestFocus();
        }
    }

    private static View findClosestChildInNextRow(View currentItem, ViewGroup curRow, LinearLayout nextRow) {
        int curScrollX = getRowScrollX(curRow);
        int nextScrollX = getRowScrollX(nextRow);
        if (curRow.indexOfChild(currentItem) <= 0 && curScrollX == 0) {
            return nextRow.getChildAt(0);
        }
        int targetViewportX = (currentItem.getLeft() + currentItem.getRight()) / 2 - curScrollX;
        return pickClosestChildByViewportX(nextRow, nextScrollX, targetViewportX);
    }

    private static int getRowScrollX(ViewGroup row) {
        return (row.getParent() instanceof HorizontalScrollView)
            ? getTargetScrollX((HorizontalScrollView) row.getParent()) : 0;
    }

    private static View pickClosestChildByViewportX(LinearLayout nextRow, int nextScrollX, int targetViewportX) {
        View best = nextRow.getChildAt(0);
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < nextRow.getChildCount(); i++) {
            View cand = nextRow.getChildAt(i);
            int dist = Math.abs((cand.getLeft() + cand.getRight()) / 2 - nextScrollX - targetViewportX);
            if (dist < bestDist) { bestDist = dist; best = cand; }
        }
        return best;
    }

    private static void scrollOuterVerticallyAnimated(View targetRail) {
        ScrollView sv = findAncestorScrollView(targetRail);
        if (sv == null) return;
        int railsTopOffset = ((View) targetRail.getParent()).getTop();
        int targetY = Math.max(0, railsTopOffset + targetRail.getTop() - dp(targetRail.getContext(), 28));
        cancelRunningAnim(sv);
        ObjectAnimator anim = ObjectAnimator.ofInt(sv, "scrollY", sv.getScrollY(), targetY);
        anim.setDuration(240);
        anim.setInterpolator(GlassTheme.EASE);
        sv.setTag(TAG_SCROLL_ANIM, anim);
        anim.start();
    }

    private static ScrollView findAncestorScrollView(View start) {
        View cur = start;
        while (cur != null) {
            if (cur.getParent() instanceof ScrollView) return (ScrollView) cur.getParent();
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return null;
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

    private static void addCardToRow(LinearLayout row, View card, Context ctx,
                                     int w, int h, boolean isLast) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(0, 0, isLast ? 0 : dp(ctx, CARD_GAP), 0);
        row.addView(card, lp);
    }

    private static int parseColorSafe(String hex) {
        try { return Color.parseColor(hex); } catch (Exception e) { return Color.DKGRAY; }
    }

    private static int dp(Context ctx, float dpVal) {
        return GlassTheme.dp(ctx, dpVal);
    }

    private RailBuilder() {}
}
