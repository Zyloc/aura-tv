package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive Dark-Glass Hero Carousel for Aura TV.
 * Uses dual crossfading + parallax backdrop ImageViews, animated metadata transitions,
 * a topmost 4-sided platinum focus border overlay, and deterministic D-pad routing.
 */
public final class HeroSliderBuilder {

    private static final int AUTO_INTERVAL = 7500;
    private static final Interpolator HERO_EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    private static final class HeroState {
        List<JSONObject> items = new ArrayList<>();
        int index = 0;
        JSONObject currentItem;
        Runnable runnable;
        HeroCallback callback;
        ImageView bgImageA;
        ImageView bgImageB;
        boolean activeIsA = true;
        LinearLayout infoCol;
        TextView tagView;
        TextView metaView;
        TextView titleView;
        TextView synopsisView;
        TextView provenanceView;
        LinearLayout dotsRow;
        View focusBorderOverlay;
    }

    public interface HeroCallback {
        void onWatchClicked(JSONObject item);
        void onWatchedClicked(JSONObject item);
        void onSimilarClicked(JSONObject item);
        void onMoreClicked(JSONObject item);
        void onSlideChanged(int index);
        default void onMoveUpToTopBar() {}
        default void onMoveDownToFirstRail() {}
    }

    public static FrameLayout build(Context ctx, HeroCallback callback) {
        FrameLayout hero = createContainer(ctx);
        HeroState st = new HeroState();
        st.callback = callback;
        hero.setTag(st);
        addBackgroundLayers(hero, st, ctx);
        addGradientOverlays(hero, ctx);
        addInfoOverlay(hero, st, ctx);
        addSlideDotsLayer(hero, st, ctx);
        addTopmostFocusBorderOverlay(hero, st, ctx);
        bindHeroCardInteraction(hero, st, ctx);
        return hero;
    }

    public static void requestInitialFocus(FrameLayout hero) {
        if (hero != null) hero.requestFocus();
    }

    public static void setItems(FrameLayout hero, List<JSONObject> items,
                                 Context ctx, HeroCallback callback) {
        HeroState st = getState(hero);
        st.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        st.index = 0;
        if (callback != null) st.callback = callback;
        if (!st.items.isEmpty()) showSlide(hero, 0, 0, ctx, false);
    }

    public static void showItemDirect(FrameLayout hero, JSONObject item,
                                       Context ctx, HeroCallback callback) {
        if (item == null) return;
        HeroState st = getState(hero);
        if (callback != null) st.callback = callback;
        if (st.currentItem != null &&
                item.optString("title", "").equalsIgnoreCase(st.currentItem.optString("title", ""))) {
            return;
        }
        st.currentItem = item;
        syncIndexIfMatching(st, item);
        bindItemToViews(hero, st, item, 1, ctx, true);
        updateDots(st, st.index, st.items.size(), ctx);
    }

    public static void nextSlide(FrameLayout hero) {
        HeroState st = getState(hero);
        if (st.items.isEmpty()) return;
        st.index = (st.index + 1) % st.items.size();
        showSlide(hero, st.index, 1, hero.getContext(), true);
    }

    public static void prevSlide(FrameLayout hero) {
        HeroState st = getState(hero);
        if (st.items.isEmpty()) return;
        st.index = (st.index - 1 + st.items.size()) % st.items.size();
        showSlide(hero, st.index, -1, hero.getContext(), true);
    }

    public static void startAutoRotate(FrameLayout hero, Handler handler) {
        stopAutoRotate(hero, handler);
        HeroState st = getState(hero);
        st.runnable = new Runnable() {
            @Override
            public void run() {
                nextSlide(hero);
                handler.postDelayed(this, AUTO_INTERVAL);
            }
        };
        handler.postDelayed(st.runnable, AUTO_INTERVAL);
    }

    public static void stopAutoRotate(FrameLayout hero, Handler handler) {
        HeroState st = getState(hero);
        if (st.runnable != null) handler.removeCallbacks(st.runnable);
    }

    private static HeroState getState(FrameLayout hero) {
        if (hero.getTag() instanceof HeroState) return (HeroState) hero.getTag();
        HeroState st = new HeroState();
        hero.setTag(st);
        return st;
    }

    private static void syncIndexIfMatching(HeroState st, JSONObject item) {
        String target = item.optString("title", "");
        for (int i = 0; i < st.items.size(); i++) {
            if (target.equalsIgnoreCase(st.items.get(i).optString("title", ""))) {
                st.index = i;
                return;
            }
        }
    }

    private static FrameLayout createContainer(Context ctx) {
        FrameLayout hero = new FrameLayout(ctx);
        int h = dp(ctx, GlassTheme.HERO_HEIGHT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h);
        lp.setMargins(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), dp(ctx, 8));
        hero.setLayoutParams(lp);
        float radius = dp(ctx, 18);
        hero.setBackground(GlassTheme.solidRounded(GlassTheme.BG_HERO_DARK, radius));
        hero.setElevation(dp(ctx, 10));
        hero.setFocusable(true);
        hero.setClickable(true);
        hero.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        hero.setClipToOutline(true);
        hero.setClipChildren(true);
        return hero;
    }

    private static void addTopmostFocusBorderOverlay(FrameLayout hero, HeroState st, Context ctx) {
        View border = new View(ctx);
        border.setFocusable(false);
        border.setClickable(false);
        float radius = dp(ctx, 18);
        border.setBackground(GlassTheme.focusRounded(
            Color.TRANSPARENT, GlassTheme.BORDER_STRONG, dp(ctx, 1), radius));
        st.focusBorderOverlay = border;
        hero.addView(border, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static void bindHeroCardInteraction(FrameLayout hero, HeroState st, Context ctx) {
        float radius = dp(ctx, 18);
        hero.setOnClickListener(v -> {
            if (st.callback != null && st.currentItem != null) {
                st.callback.onMoreClicked(st.currentItem);
            }
        });
        hero.setOnFocusChangeListener((v, focused) -> {
            if (st.focusBorderOverlay != null) {
                int stroke = focused ? Color.WHITE : GlassTheme.BORDER_STRONG;
                int sw = focused ? GlassTheme.dp(ctx, 2.5f) : dp(ctx, 1);
                st.focusBorderOverlay.setBackground(
                    GlassTheme.focusRounded(Color.TRANSPARENT, stroke, sw, radius));
            }
            hero.setElevation(focused ? dp(ctx, 16) : dp(ctx, 8));
            hero.animate()
                .scaleX(focused ? 1.012f : 1.0f)
                .scaleY(focused ? 1.012f : 1.0f)
                .setDuration(220)
                .setInterpolator(HERO_EASE)
                .start();
        });
        hero.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                nextSlide(hero);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                prevSlide(hero);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (st.callback != null) st.callback.onMoveUpToTopBar();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (st.callback != null) st.callback.onMoveDownToFirstRail();
                return true;
            }
            return false;
        });
    }

    private static void addBackgroundLayers(FrameLayout hero, HeroState st, Context ctx) {
        FrameLayout.LayoutParams lpA = new FrameLayout.LayoutParams(
            dp(ctx, 620), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        ImageView bgA = new ImageView(ctx);
        bgA.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgA.setBackgroundColor(GlassTheme.BG_HERO_DARK);
        st.bgImageA = bgA;
        hero.addView(bgA, lpA);

        FrameLayout.LayoutParams lpB = new FrameLayout.LayoutParams(
            dp(ctx, 620), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        ImageView bgB = new ImageView(ctx);
        bgB.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgB.setBackgroundColor(Color.TRANSPARENT);
        bgB.setAlpha(0f);
        st.bgImageB = bgB;
        hero.addView(bgB, lpB);
    }

    private static void addGradientOverlays(FrameLayout hero, Context ctx) {
        View leftFade = new View(ctx);
        GradientDrawable gd = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{
                GlassTheme.BG_HERO_DARK,
                Color.argb(245, 19, 24, 34),
                Color.argb(140, 19, 24, 34),
                Color.TRANSPARENT
            });
        leftFade.setBackground(gd);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 360), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        lp.rightMargin = dp(ctx, 260);
        hero.addView(leftFade, lp);

        View bottomFade = new View(ctx);
        GradientDrawable bgd = new GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{GlassTheme.BG_HERO_DARK, Color.TRANSPARENT});
        bottomFade.setBackground(bgd);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 54), Gravity.BOTTOM);
        hero.addView(bottomFade, blp);
    }

    private static void addInfoOverlay(FrameLayout hero, HeroState st, Context ctx) {
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.BOTTOM);
        info.setClipChildren(false);
        info.setClipToPadding(false);
        st.infoCol = info;

        buildBadgeMetaRow(info, st, ctx);
        st.titleView = GlassTheme.makeText(ctx, "", 28f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 2);
        st.titleView.setPadding(0, 0, 0, dp(ctx, 6));
        info.addView(st.titleView, GlassTheme.wrapParams());

        st.synopsisView = GlassTheme.makeText(ctx, "", 12.5f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_REGULAR, 3);
        st.synopsisView.setLineSpacing(0, 1.2f);
        st.synopsisView.setPadding(0, 0, 0, dp(ctx, 4));
        info.addView(st.synopsisView, GlassTheme.matchWrapParams());

        st.provenanceView = GlassTheme.makeText(ctx, "", 10f, GlassTheme.GOLD_ACCENT, GlassTheme.FONT_MEDIUM, 1);
        st.provenanceView.setVisibility(View.GONE);
        info.addView(st.provenanceView, GlassTheme.matchWrapParams());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 510), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        lp.setMargins(dp(ctx, 28), dp(ctx, 16), 0, dp(ctx, 20));
        hero.addView(info, lp);
    }

    private static void buildBadgeMetaRow(LinearLayout info, HeroState st, Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setVisibility(View.GONE);

        st.tagView = GlassTheme.makeText(ctx, "STREAMING", 9.5f, Color.WHITE, GlassTheme.FONT_BOLD, 1);
        st.tagView.setVisibility(View.GONE);
        row.addView(st.tagView, GlassTheme.wrapParams());

        st.metaView = GlassTheme.makeText(ctx, "", 11.5f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_BOLD, 1);
        st.metaView.setVisibility(View.GONE);
        row.addView(st.metaView, GlassTheme.wrapParams());
        info.addView(row, GlassTheme.wrapParams());
    }

    private static void addSlideDotsLayer(FrameLayout hero, HeroState st, Context ctx) {
        LinearLayout dots = new LinearLayout(ctx);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        st.dotsRow = dots;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.setMargins(0, 0, dp(ctx, 20), dp(ctx, 14));
        hero.addView(dots, lp);
    }

    private static void updateDots(HeroState st, int activeIdx, int total, Context ctx) {
        if (st.dotsRow == null) return;
        int cap = Math.min(total, 6);
        if (st.dotsRow.getChildCount() != cap) {
            st.dotsRow.removeAllViews();
            for (int i = 0; i < cap; i++) {
                View dot = new View(ctx);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 6), dp(ctx, 6));
                lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
                st.dotsRow.addView(dot, lp);
            }
        }
        for (int i = 0; i < cap; i++) {
            View dot = st.dotsRow.getChildAt(i);
            int w = (i == activeIdx) ? dp(ctx, 20) : dp(ctx, 6);
            int h = dp(ctx, 6);
            int color = (i == activeIdx) ? Color.WHITE : Color.argb(85, 255, 255, 255);
            dot.setBackground(GlassTheme.solidRounded(color, dp(ctx, 3)));
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            if (lp != null && lp.width != w) {
                lp.width = w;
                lp.height = h;
                dot.setLayoutParams(lp);
            }
        }
    }

    private static void showSlide(FrameLayout hero, int idx, int dir, Context ctx, boolean animate) {
        HeroState st = getState(hero);
        if (st.items.isEmpty() || idx >= st.items.size()) return;
        JSONObject item = st.items.get(idx);
        st.currentItem = item;
        bindItemToViews(hero, st, item, dir, ctx, animate);
        updateDots(st, idx, st.items.size(), ctx);
        if (st.callback != null) st.callback.onSlideChanged(idx);
    }

    private static void bindItemToViews(FrameLayout hero, HeroState st,
                                         JSONObject item, int dir, Context ctx, boolean animate) {
        String app = cleanPlatformName(item.optString("app", item.optString("app_name", "Streaming")));
        if (st.tagView != null) {
            st.tagView.setText(app.toUpperCase());
            st.tagView.setBackground(GlassTheme.solidRounded(getPlatformColor(app), dp(ctx, 5)));
        }
        if (st.metaView != null) {
            st.metaView.setText("   " + buildMetaString(item));
        }
        if (st.titleView != null) {
            String series = item.optString("series_title", "");
            String rawTitle = item.optString("title", "");
            String dispTitle = (!series.isEmpty() && !series.equalsIgnoreCase(rawTitle) && !rawTitle.isEmpty())
                ? (series + " \u00B7 " + rawTitle)
                : (!series.isEmpty() ? series : rawTitle);
            st.titleView.setText(dispTitle);
        }
        if (st.synopsisView != null) {
            String syn = item.optString("synopsis", item.optString("reason", ""));
            st.synopsisView.setText(syn);
        }
        if (st.provenanceView != null) {
            st.provenanceView.setVisibility(View.GONE);
        }

        int slideDir = (dir == 0) ? 1 : dir;

        // Smooth native transition on the left info column
        if (animate && st.infoCol != null) {
            st.infoCol.animate().cancel();
            st.infoCol.setAlpha(0.12f);
            st.infoCol.setTranslationX(slideDir * dp(ctx, 18));
            st.infoCol.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(380)
                .setInterpolator(HERO_EASE)
                .start();
        }

        // Dual-layer crossfade + subtle parallax glide on the 16:9 UHD Hero artwork
        String url = item.optString("backdrop_url", "");
        if (url.isEmpty()) url = item.optString("poster_url", "");
        if (st.bgImageA != null && st.bgImageB != null) {
            if (!animate) {
                st.activeIsA = true;
                st.bgImageA.animate().cancel();
                st.bgImageB.animate().cancel();
                st.bgImageA.setAlpha(1f);
                st.bgImageA.setTranslationX(0f);
                st.bgImageA.setScaleX(1f);
                st.bgImageA.setScaleY(1f);
                st.bgImageB.setAlpha(0f);
                ImageLoader.loadHero(ctx, url, st.bgImageA);
            } else {
                ImageView outgoing = st.activeIsA ? st.bgImageA : st.bgImageB;
                ImageView incoming = st.activeIsA ? st.bgImageB : st.bgImageA;
                st.activeIsA = !st.activeIsA;

                outgoing.animate().cancel();
                incoming.animate().cancel();

                ImageLoader.loadHero(ctx, url, incoming);

                incoming.setAlpha(0f);
                incoming.setTranslationX(slideDir * dp(ctx, 24));
                incoming.setScaleX(1.045f);
                incoming.setScaleY(1.045f);

                outgoing.animate()
                    .alpha(0f)
                    .translationX(-slideDir * dp(ctx, 16))
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(420)
                    .setInterpolator(HERO_EASE)
                    .start();

                incoming.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(440)
                    .setInterpolator(HERO_EASE)
                    .start();
            }
        }
    }

    static String cleanPlatformName(String raw) {
        return ContinueWatchingSelector.cleanPlatformName(raw);
    }

    static String buildMetaString(JSONObject item) {
        StringBuilder sb = new StringBuilder();
        String rating = extractRatingText(item);
        if (!rating.isEmpty()) sb.append(rating).append(" \u00B7 ");
        int year = item.optInt("year", 0);
        if (year > 0) sb.append(year).append(" \u00B7 ");
        sb.append(capitalize(item.optString("type", "Series")));
        int runtime = item.optInt("runtime_min", 0);
        if (runtime > 0) sb.append(" \u00B7 ").append(runtime).append("m");
        String genres = item.optString("genres", "");
        if (!genres.isEmpty()) {
            sb.append(" \u00B7 ").append(genres.split(",")[0].trim());
        }
        return sb.toString();
    }

    static String extractRatingText(JSONObject item) {
        double score = item.optDouble("imdb_score", item.optDouble("imdb_raw", 0));
        if (score > 0) return "\u2605 " + String.format("%.1f", score);
        String r = item.optString("rating", "");
        if (!r.isEmpty()) return r.startsWith("\u2605") ? r : ("\u2605 " + r);
        return "";
    }

    static int getPlatformColor(String app) {
        if (app == null) return 0xFF334155;
        String lower = app.toLowerCase();
        if (lower.contains("netflix")) return GlassTheme.COLOR_NETFLIX;
        if (lower.contains("prime")) return GlassTheme.COLOR_PRIME;
        if (lower.contains("hotstar") || lower.contains("jio")) return GlassTheme.COLOR_HOTSTAR;
        if (lower.contains("apple")) return GlassTheme.COLOR_APPLE_TV;
        if (lower.contains("youtube") || lower.contains("smarttube"))
            return GlassTheme.COLOR_YOUTUBE;
        if (lower.contains("sony")) return GlassTheme.COLOR_SONYLIV;
        if (lower.contains("zee")) return GlassTheme.COLOR_ZEE5;
        if (lower.contains("airtel") || lower.contains("xstream")) return 0xFFDC2626;
        if (lower.contains("stremio")) return 0xFF7C3AED;
        return 0xFF334155;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static int dp(Context ctx, int dpVal) {
        return GlassTheme.dp(ctx, dpVal);
    }

    private HeroSliderBuilder() {}
}
