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
    private static final int CARD_GAP = 12;
    private static final int APP_W = 114;
    private static final int APP_H = 62;
    private static final int APP_RADIUS = 12;
    private static final int PROGRESS_H = 4;
    private static final int MAX_CONTINUE = 8;

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
     * so all vertical rail transitions animate smoothly with ObjectAnimator.
     */
    public static final class SmoothVerticalScrollView extends ScrollView {
        public SmoothVerticalScrollView(Context context) {
            super(context);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }
    }

    /**
     * Custom HorizontalScrollView that suppresses instant synchronous focus jump
     * so horizontal card traversal glides smoothly.
     */
    private static final class SmoothHorizontalScrollView extends HorizontalScrollView {
        SmoothHorizontalScrollView(Context context) {
            super(context);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }
    }

    public static LinearLayout buildContinueWatching(Context ctx, List<JSONObject> items,
                                                      RailCallback cb) {
        LinearLayout rail = createRailContainer(ctx);
        addRailHeader(rail, ctx, "Continue Watching", null);
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
        addRailHeader(rail, ctx, "Apps", null);
        LinearLayout row = createHorizontalRow(ctx);
        for (JSONObject app : installedApps) {
            addAppTile(row, ctx, app, cb);
        }
        wrapInScrollView(rail, row, ctx);
        return rail;
    }

    public static LinearLayout buildContentRail(Context ctx, String title, String subtitle,
                                                  List<JSONObject> items, RailCallback cb) {
        LinearLayout rail = createRailContainer(ctx);
        addRailHeader(rail, ctx, title, subtitle);
        LinearLayout row = createHorizontalRow(ctx);
        for (JSONObject item : items) {
            addPosterCard(row, ctx, item, cb);
        }
        wrapInScrollView(rail, row, ctx);
        rail.setTag(items);
        return rail;
    }

    public static boolean focusFirstItem(LinearLayout rail) {
        LinearLayout row = findScrollContent(rail);
        if (row != null && row.getChildCount() > 0) {
            return row.getChildAt(0).requestFocus();
        }
        return false;
    }

    private static LinearLayout createRailContainer(Context ctx) {
        LinearLayout rail = new LinearLayout(ctx);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setClipChildren(false);
        rail.setClipToPadding(false);
        rail.setPadding(dp(ctx, 6), dp(ctx, 5), dp(ctx, 6), dp(ctx, 5));
        return rail;
    }

    private static LinearLayout createHorizontalRow(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 16), dp(ctx, 8));
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
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(ctx, 8), 0, 0, dp(ctx, 2));
        TextView tv = GlassTheme.makeText(ctx, title, 14f, GlassTheme.TEXT_PRIMARY,
                                           GlassTheme.FONT_BOLD, 1);
        header.addView(tv, GlassTheme.wrapParams());
        if (subtitle != null && !subtitle.isEmpty() && subtitle.length() > 2) {
            TextView sub = GlassTheme.makeText(ctx, "   \u00B7   " + subtitle, 10.5f,
                GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
            header.addView(sub, GlassTheme.wrapParams());
        }
        rail.addView(header, GlassTheme.wrapParams());
    }

    // ── Continue watching card (Diverse OTTs + Progress % + Dark Glass) ──

    private static void addContinueCard(LinearLayout row, Context ctx,
                                         JSONObject item, RailCallback cb) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        float r = dp(ctx, 12);
        card.setBackground(GlassTheme.focusRounded(
            GlassTheme.GLASS_CARD, GlassTheme.BORDER_STRONG, dp(ctx, 1), r));
        card.setElevation(dp(ctx, 4));
        int padH = dp(ctx, 12), padV = dp(ctx, 9);
        card.setPadding(padH, padV, padH, padV);
        addContinueTopBadge(card, ctx, item);
        addContinueTitle(card, ctx, item);
        addProgressBar(card, ctx, item);
        bindCardFocus(card, item, cb, true, r, ctx);
        addCardToRow(row, card, ctx, dp(ctx, 204), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void addContinueTopBadge(LinearLayout card, Context ctx, JSONObject item) {
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String rawApp = item.optString("app_name", item.optString("app", "Resume"));
        String app = HeroSliderBuilder.cleanPlatformName(rawApp);
        int color = HeroSliderBuilder.getPlatformColor(app);
        TextView pill = GlassTheme.makeText(ctx, "\u25B6 " + app.toUpperCase(), 8.5f,
            Color.WHITE, GlassTheme.FONT_BOLD, 1);
        pill.setBackground(GlassTheme.solidRounded(color, dp(ctx, 4)));
        pill.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
        top.addView(pill, GlassTheme.wrapParams());

        int pct = computeProgressPct(item);
        String ep = formatSeasonEp(item);
        String rightText = !ep.isEmpty() ? (ep + " \u00B7 " + pct + "%") : (pct + "% watched");
        TextView epTv = GlassTheme.makeText(ctx, "  " + rightText, 9.5f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
        top.addView(epTv, GlassTheme.wrapParams());
        card.addView(top, GlassTheme.wrapParams());
    }

    private static void addContinueTitle(LinearLayout card, Context ctx, JSONObject item) {
        String series = item.optString("series_title", "");
        String rawTitle = !series.isEmpty() ? series : item.optString("title", "Continue Watching");
        TextView title = GlassTheme.makeText(ctx, rawTitle, 12.5f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        title.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));
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
        bar.post(() -> {
            int totalW = bar.getWidth();
            if (totalW <= 0) return;
            ViewGroup.LayoutParams lp = fill.getLayoutParams();
            lp.width = Math.max(dp(ctx, 14), (int) (totalW * Math.max(12, Math.min(100, pct)) / 100f));
            fill.setLayoutParams(lp);
        });
    }

    private static int computeProgressPct(JSONObject item) {
        int pct = item.optInt("progress_pct", -1);
        if (pct >= 0) return pct;
        int pos = item.optInt("position_sec", 0);
        int dur = item.optInt("duration_sec", 0);
        if (dur > 0 && pos > 0) return Math.min(98, Math.max(8, (pos * 100) / dur));
        return 35;
    }

    // ── Apps dock (Clean Smoked-Slate Tiles + Crisp White Focus Border) ─

    private static void addAppTile(LinearLayout row, Context ctx, JSONObject app, RailCallback cb) {
        String name = app.optString("name", "App");
        String pkg = app.optString("package", "");
        int color = parseColorSafe(app.optString("color", "#1E293B"));
        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        wrapper.setClipChildren(false);
        wrapper.setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2));

        FrameLayout bannerBox = buildAppVisualBox(ctx, pkg, name, color);
        bannerBox.setTag(TAG_BANNER_BOX);
        wrapper.addView(bannerBox, new LinearLayout.LayoutParams(dp(ctx, APP_W), dp(ctx, APP_H)));

        TextView label = GlassTheme.makeText(ctx, name, 10.5f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(ctx, 5), 0, 0);
        wrapper.addView(label, GlassTheme.wrapParams());

        bindAppTileFocus(wrapper, bannerBox, label, pkg, cb, ctx);
        addCardToRow(row, wrapper, ctx, dp(ctx, APP_W + 6), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout buildAppVisualBox(Context ctx, String pkg, String name, int color) {
        FrameLayout box = new FrameLayout(ctx);
        float rad = dp(ctx, APP_RADIUS);
        box.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), rad));
        box.setElevation(dp(ctx, 4));
        box.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), rad);
            }
        });
        box.setClipToOutline(true);
        Drawable banner = loadNativeBanner(ctx, pkg);
        if (banner != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(banner);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ImageLoader.applyRoundedCorners(iv, rad - dp(ctx, 2));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            int inset = dp(ctx, 2);
            lp.setMargins(inset, inset, inset, inset);
            box.addView(iv, lp);
            return box;
        }
        Drawable icon = loadNativeIcon(ctx, pkg);
        if (icon != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = dp(ctx, 36);
            box.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
            return box;
        }
        box.setBackground(GlassTheme.focusRounded(color, GlassTheme.BORDER_STRONG, dp(ctx, 1), rad));
        TextView tv = GlassTheme.makeText(ctx, name, 11.5f, Color.WHITE, GlassTheme.FONT_BOLD, 1);
        tv.setGravity(Gravity.CENTER);
        box.addView(tv, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return box;
    }

    private static Drawable loadNativeBanner(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable b = pm.getApplicationBanner(pkg);
            if (b != null) return b;
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationBanner(ai);
        } catch (Exception e) {
            return null;
        }
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
        wrapper.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                bannerBox.setBackground(GlassTheme.focusRounded(
                    GlassTheme.SURFACE_ELEV, Color.WHITE, dp(ctx, 2.5f), rad));
                bannerBox.setElevation(dp(ctx, 12));
                label.setTextColor(Color.WHITE);
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(170).setInterpolator(GlassTheme.EASE).start();
                scrollToChild(v);
            } else {
                bannerBox.setBackground(GlassTheme.focusRounded(
                    GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), rad));
                bannerBox.setElevation(dp(ctx, 4));
                label.setTextColor(GlassTheme.TEXT_SECONDARY);
                v.animate().scaleX(1f).scaleY(1f).setDuration(170).setInterpolator(GlassTheme.EASE).start();
            }
        });
        setupRailDpad(wrapper, cb);
    }

    // ── Content rail poster card (Focus ring hugs poster artwork cleanly) ──

    private static void addPosterCard(LinearLayout row, Context ctx,
                                       JSONObject item, RailCallback cb) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        int p = dp(ctx, 2);
        card.setPadding(p, p, p, p);
        FrameLayout imgFrame = addPosterImage(card, ctx, item);
        TextView titleView = addPosterInfo(card, ctx, item);
        bindPosterFocus(card, imgFrame, titleView, item, cb, ctx);
        int w = dp(ctx, GlassTheme.POSTER_W + 6);
        addCardToRow(row, card, ctx, w, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout addPosterImage(LinearLayout card, Context ctx, JSONObject item) {
        FrameLayout imgFrame = new FrameLayout(ctx);
        int w = dp(ctx, GlassTheme.POSTER_W);
        int h = dp(ctx, GlassTheme.POSTER_H);
        float rad = dp(ctx, 11);
        imgFrame.setBackground(GlassTheme.focusRounded(
            GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_LIGHT, dp(ctx, 1), rad));
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(GlassTheme.SURFACE_ELEV);
        ImageLoader.applyRoundedCorners(iv, dp(ctx, 9));
        String url = item.optString("poster_url", "");
        if (!url.isEmpty()) ImageLoader.load(ctx, url, iv);
        int inset = dp(ctx, 2);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ivLp.setMargins(inset, inset, inset, inset);
        imgFrame.addView(iv, ivLp);
        addPlatformBadge(imgFrame, ctx, item);
        addImdbBadge(imgFrame, ctx, item);
        card.addView(imgFrame, new LinearLayout.LayoutParams(w, h));
        return imgFrame;
    }

    private static void bindPosterFocus(LinearLayout card, FrameLayout imgFrame,
                                         TextView titleView, JSONObject item,
                                         RailCallback cb, Context ctx) {
        card.setFocusable(true);
        card.setClickable(true);
        float rad = dp(ctx, 11);
        card.setOnClickListener(v -> cb.onPosterClicked(item, v));
        card.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                imgFrame.setBackground(GlassTheme.focusRounded(
                    GlassTheme.SURFACE_ELEV, Color.WHITE, dp(ctx, 2.5f), rad));
                imgFrame.setElevation(dp(ctx, 14));
                if (titleView != null) titleView.setTextColor(Color.WHITE);
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(170).setInterpolator(GlassTheme.EASE).start();
                if (cb != null) cb.onPosterFocused(item, v);
                scrollToChild(v);
            } else {
                imgFrame.setBackground(GlassTheme.focusRounded(
                    GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_LIGHT, dp(ctx, 1), rad));
                imgFrame.setElevation(0);
                if (titleView != null) titleView.setTextColor(GlassTheme.TEXT_PRIMARY);
                v.animate().scaleX(1f).scaleY(1f).setDuration(170).setInterpolator(GlassTheme.EASE).start();
            }
        });
        setupRailDpad(card, cb);
    }

    private static void addPlatformBadge(FrameLayout frame, Context ctx, JSONObject item) {
        String app = HeroSliderBuilder.cleanPlatformName(item.optString("app", ""));
        if (app.isEmpty()) return;
        int color = HeroSliderBuilder.getPlatformColor(app);
        TextView pill = GlassTheme.makeText(ctx, app.toUpperCase(), 7.5f,
            Color.WHITE, GlassTheme.FONT_BOLD, 1);
        pill.setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2));
        pill.setBackground(GlassTheme.solidRounded(color, dp(ctx, 4)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(dp(ctx, 5), dp(ctx, 5), 0, 0);
        frame.addView(pill, lp);
    }

    private static void addImdbBadge(FrameLayout frame, Context ctx, JSONObject item) {
        String txt = HeroSliderBuilder.extractRatingText(item);
        if (txt.isEmpty()) return;
        TextView badge = GlassTheme.makeText(ctx, txt, 8.5f,
                                              GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1);
        badge.setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2));
        badge.setBackground(GlassTheme.solidRounded(Color.argb(220, 11, 14, 20), dp(ctx, 5)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.setMargins(0, dp(ctx, 5), dp(ctx, 5), 0);
        frame.addView(badge, lp);
    }

    private static TextView addPosterInfo(LinearLayout card, Context ctx, JSONObject item) {
        TextView title = GlassTheme.makeText(ctx, item.optString("title", ""),
                                              11f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        title.setPadding(dp(ctx, 2), dp(ctx, 5), dp(ctx, 2), 0);
        card.addView(title, GlassTheme.matchWrapParams());
        String meta = buildPosterMetaText(item);
        if (!meta.isEmpty()) {
            TextView tv = GlassTheme.makeText(ctx, meta, 9.5f, GlassTheme.TEXT_MUTED,
                                               GlassTheme.FONT_MEDIUM, 1);
            tv.setPadding(dp(ctx, 2), 0, dp(ctx, 2), 0);
            card.addView(tv, GlassTheme.matchWrapParams());
        }
        return title;
    }

    private static String buildPosterMetaText(JSONObject item) {
        StringBuilder sb = new StringBuilder();
        int year = item.optInt("year", 0);
        if (year > 0) sb.append(year);
        String app = HeroSliderBuilder.cleanPlatformName(item.optString("app", ""));
        if (!app.isEmpty()) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(app);
        }
        return sb.toString();
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
        card.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                int bg = isContinue ? GlassTheme.SURFACE_ELEV : GlassTheme.ACCENT_BG;
                v.setBackground(GlassTheme.focusRounded(bg, Color.WHITE, dp(ctx, 2.5f), radiusPx));
                v.setElevation(dp(ctx, 14));
                v.animate().scaleX(1.07f).scaleY(1.07f).setDuration(170).setInterpolator(GlassTheme.EASE).start();
                if (cb != null) cb.onPosterFocused(item, v);
                scrollToChild(v);
            } else {
                if (isContinue) {
                    v.setBackground(GlassTheme.focusRounded(
                        GlassTheme.GLASS_CARD, GlassTheme.BORDER_STRONG, dp(ctx, 1), radiusPx));
                    v.setElevation(dp(ctx, 4));
                } else {
                    v.setBackground(null);
                    v.setElevation(0);
                }
                v.animate().scaleX(1f).scaleY(1f).setDuration(170).setInterpolator(GlassTheme.EASE).start();
            }
        });
        setupRailDpad(card, cb);
    }

    private static void scrollToChild(View child) {
        if (!(child.getParent() instanceof View)) return;
        View row = (View) child.getParent();
        if (row.getParent() instanceof HorizontalScrollView) {
            HorizontalScrollView scroll = (HorizontalScrollView) row.getParent();
            int targetX = Math.max(0, child.getLeft() - dp(child.getContext(), 32));
            ObjectAnimator anim = ObjectAnimator.ofInt(scroll, "scrollX", scroll.getScrollX(), targetX);
            anim.setDuration(220);
            anim.setInterpolator(GlassTheme.EASE);
            anim.start();
        }
    }

    private static void setupRailDpad(View card, RailCallback cb) {
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (!moveSibling(v, -1) && cb != null) cb.onMoveLeftToNav();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return moveSibling(v, 1);
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return moveVerticalRail(v, 1, cb);
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return moveVerticalRail(v, -1, cb);
            return false;
        });
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

    private static boolean moveVerticalRail(View currentItem, int railDir, RailCallback cb) {
        if (!(currentItem.getParent() instanceof ViewGroup)) return false;
        ViewGroup row = (ViewGroup) currentItem.getParent();
        if (!(row.getParent() instanceof View) || !(((View) row.getParent()).getParent() instanceof LinearLayout)) {
            return false;
        }
        LinearLayout rail = (LinearLayout) ((View) row.getParent()).getParent();
        if (!(rail.getParent() instanceof LinearLayout)) return false;
        LinearLayout railsContainer = (LinearLayout) rail.getParent();
        int railIdx = railsContainer.indexOfChild(rail);
        int targetIdx = railIdx + railDir;
        if (targetIdx < 0) {
            if (cb != null) cb.onMoveUpFromTopRail();
            return true;
        }
        if (targetIdx >= railsContainer.getChildCount()) return true;
        View nextRailView = railsContainer.getChildAt(targetIdx);
        if (nextRailView instanceof LinearLayout) {
            LinearLayout nextRow = findScrollContent((LinearLayout) nextRailView);
            if (nextRow != null && nextRow.getChildCount() > 0) {
                View targetChild = findClosestChildInNextRow(currentItem, row, nextRow);
                scrollOuterVerticallyAnimated(nextRailView);
                targetChild.requestFocus();
                return true;
            }
        }
        return false;
    }

    private static View findClosestChildInNextRow(View currentItem, ViewGroup curRow, LinearLayout nextRow) {
        int curScrollX = (curRow.getParent() instanceof HorizontalScrollView)
            ? ((HorizontalScrollView) curRow.getParent()).getScrollX() : 0;
        int nextScrollX = (nextRow.getParent() instanceof HorizontalScrollView)
            ? ((HorizontalScrollView) nextRow.getParent()).getScrollX() : 0;
        int targetViewportX = (currentItem.getLeft() + currentItem.getRight()) / 2 - curScrollX;
        View best = nextRow.getChildAt(0);
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < nextRow.getChildCount(); i++) {
            View cand = nextRow.getChildAt(i);
            int candViewportX = (cand.getLeft() + cand.getRight()) / 2 - nextScrollX;
            int dist = Math.abs(candViewportX - targetViewportX);
            if (dist < bestDist) {
                bestDist = dist;
                best = cand;
            }
        }
        return best;
    }

    private static void scrollOuterVerticallyAnimated(View targetRail) {
        View cur = targetRail;
        while (cur != null) {
            if (cur.getParent() instanceof ScrollView) {
                ScrollView sv = (ScrollView) cur.getParent();
                int railsTopOffset = ((View) targetRail.getParent()).getTop();
                int targetY = Math.max(0, railsTopOffset + targetRail.getTop() - dp(targetRail.getContext(), 28));
                ObjectAnimator anim = ObjectAnimator.ofInt(sv, "scrollY", sv.getScrollY(), targetY);
                anim.setDuration(260);
                anim.setInterpolator(GlassTheme.EASE);
                anim.start();
                return;
            }
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
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

    private static void addCardToRow(LinearLayout row, View card, Context ctx, int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(0, 0, dp(ctx, CARD_GAP), 0);
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
