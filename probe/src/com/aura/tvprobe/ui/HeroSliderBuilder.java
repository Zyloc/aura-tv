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
        populateHeroLayers(hero, st, ctx);
        bindHeroCardInteraction(hero, st, ctx);
        return hero;
    }

    private static void populateHeroLayers(FrameLayout hero, HeroState st, Context ctx) {
        addBackgroundLayers(hero, st, ctx);
        addGradientOverlays(hero, ctx);
        addInfoOverlay(hero, st, ctx);
        addSlideDotsLayer(hero, st, ctx);
        addTopmostFocusBorderOverlay(hero, st, ctx);
    }

    public static void requestInitialFocus(FrameLayout hero) {
        if (hero != null) hero.requestFocus();
    }

    public static JSONObject getCurrentItem(FrameLayout hero) {
        if (hero == null || !(hero.getTag() instanceof HeroState)) return null;
        return ((HeroState) hero.getTag()).currentItem;
    }

    public static void setItems(FrameLayout hero, List<JSONObject> items,
                                Context ctx, HeroCallback callback) {
        HeroState st = getState(hero);
        st.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        st.index = 0;
        if (callback != null) st.callback = callback;
        if (!st.items.isEmpty()) showSlide(hero, 0, 0, ctx, false);
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
        if (hero == null || hero.getWindowVisibility() != View.VISIBLE) return;
        HeroState st = getState(hero);
        st.runnable = () -> tickAutoRotate(hero, handler, st);
        handler.postDelayed(st.runnable, AUTO_INTERVAL);
    }

    private static void tickAutoRotate(FrameLayout hero, Handler handler, HeroState st) {
        if (hero == null || hero.getWindowVisibility() != View.VISIBLE) {
            stopAutoRotate(hero, handler);
            return;
        }
        nextSlide(hero);
        if (st.runnable != null) handler.postDelayed(st.runnable, AUTO_INTERVAL);
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

    private static FrameLayout createContainer(Context ctx) {
        FrameLayout hero = new FrameLayout(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, GlassTheme.HERO_HEIGHT));
        lp.setMargins(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), dp(ctx, 8));
        hero.setLayoutParams(lp);
        styleHeroContainer(ctx, hero, dp(ctx, 18));
        return hero;
    }

    private static void styleHeroContainer(Context ctx, FrameLayout hero, float radius) {
        hero.setBackground(GlassTheme.solidRounded(GlassTheme.BG_HERO_DARK, radius));
        hero.setElevation(dp(ctx, 10));
        hero.setFocusable(true);
        hero.setClickable(true);
        applyRoundOutline(hero, radius);
        hero.setClipChildren(true);
    }

    private static void applyRoundOutline(View view, float radius) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    private static void addTopmostFocusBorderOverlay(FrameLayout hero, HeroState st, Context ctx) {
        View border = new View(ctx);
        border.setFocusable(false);
        border.setClickable(false);
        float radius = dp(ctx, 18);
        border.setBackground(GlassTheme.focusRounded(Color.TRANSPARENT, Color.TRANSPARENT, 0, radius));
        st.focusBorderOverlay = border;
        hero.addView(border, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static void bindHeroCardInteraction(FrameLayout hero, HeroState st, Context ctx) {
        float radius = dp(ctx, 18);
        hero.setOnClickListener(v -> {
            if (st.callback != null && st.currentItem != null) st.callback.onMoreClicked(st.currentItem);
        });
        hero.setOnFocusChangeListener((v, focused) -> updateHeroFocusState(hero, st, ctx, focused, radius));
        hero.setOnKeyListener((v, code, ev) -> handleHeroKey(hero, st, code, ev));
    }

    private static void updateHeroFocusState(FrameLayout hero, HeroState st, Context ctx,
                                             boolean focused, float radius) {
        if (st.focusBorderOverlay != null) {
            int stroke = focused ? Color.argb(190, 255, 255, 255) : Color.TRANSPARENT;
            int sw = focused ? GlassTheme.dp(ctx, 1.5f) : 0;
            st.focusBorderOverlay.setBackground(GlassTheme.focusRounded(Color.TRANSPARENT, stroke, sw, radius));
        }
        hero.setElevation(focused ? dp(ctx, 16) : dp(ctx, 8));
        float scale = focused ? 1.012f : 1.0f;
        hero.animate().scaleX(scale).scaleY(scale).setDuration(220).setInterpolator(HERO_EASE).start();
    }

    private static boolean handleHeroKey(FrameLayout hero, HeroState st, int code, KeyEvent ev) {
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { nextSlide(hero); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) { prevSlide(hero); return true; }
        return handleHeroVerticalKey(st, code);
    }

    private static boolean handleHeroVerticalKey(HeroState st, int code) {
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            if (st.callback != null) st.callback.onMoveUpToTopBar();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (st.callback != null) st.callback.onMoveDownToFirstRail();
            return true;
        }
        return false;
    }

    private static void addBackgroundLayers(FrameLayout hero, HeroState st, Context ctx) {
        st.bgImageA = createBackdropLayer(ctx, GlassTheme.BG_HERO_DARK, 1f);
        hero.addView(st.bgImageA, createBackdropParams(ctx));
        st.bgImageB = createBackdropLayer(ctx, Color.TRANSPARENT, 0f);
        hero.addView(st.bgImageB, createBackdropParams(ctx));
    }

    private static ImageView createBackdropLayer(Context ctx, int bgColor, float alpha) {
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(bgColor);
        iv.setAlpha(alpha);
        return iv;
    }

    private static FrameLayout.LayoutParams createBackdropParams(Context ctx) {
        return new FrameLayout.LayoutParams(
            dp(ctx, 620), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
    }

    private static void addGradientOverlays(FrameLayout hero, Context ctx) {
        addLeftFadeOverlay(hero, ctx);
        addBottomFadeOverlay(hero, ctx);
    }

    private static void addLeftFadeOverlay(FrameLayout hero, Context ctx) {
        View leftFade = new View(ctx);
        int[] colors = {
            GlassTheme.BG_HERO_DARK, Color.argb(245, 19, 24, 34),
            Color.argb(140, 19, 24, 34), Color.TRANSPARENT
        };
        leftFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 360), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        lp.rightMargin = dp(ctx, 260);
        hero.addView(leftFade, lp);
    }

    private static void addBottomFadeOverlay(FrameLayout hero, Context ctx) {
        View bottomFade = new View(ctx);
        int[] colors = {GlassTheme.BG_HERO_DARK, Color.TRANSPARENT};
        bottomFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, colors));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 54), Gravity.BOTTOM);
        hero.addView(bottomFade, blp);
    }

    private static void addInfoOverlay(FrameLayout hero, HeroState st, Context ctx) {
        LinearLayout info = createInfoColumn(ctx);
        st.infoCol = info;
        buildBadgeMetaRow(info, st, ctx);
        addInfoTextViews(info, st, ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 510), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        lp.setMargins(dp(ctx, 28), dp(ctx, 16), 0, dp(ctx, 20));
        hero.addView(info, lp);
    }

    private static LinearLayout createInfoColumn(Context ctx) {
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.BOTTOM);
        info.setClipChildren(false);
        info.setClipToPadding(false);
        return info;
    }

    private static void addInfoTextViews(LinearLayout info, HeroState st, Context ctx) {
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
    }

    private static void buildBadgeMetaRow(LinearLayout info, HeroState st, Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setVisibility(View.GONE);
        st.tagView = createHiddenHeroLabel(ctx, "STREAMING", 9.5f, Color.WHITE);
        st.metaView = createHiddenHeroLabel(ctx, "", 11.5f, GlassTheme.TEXT_SECONDARY);
        row.addView(st.tagView, GlassTheme.wrapParams());
        row.addView(st.metaView, GlassTheme.wrapParams());
        info.addView(row, GlassTheme.wrapParams());
    }

    private static TextView createHiddenHeroLabel(Context ctx, String text, float sizeSp, int color) {
        TextView tv = GlassTheme.makeText(ctx, text, sizeSp, color, GlassTheme.FONT_BOLD, 1);
        tv.setVisibility(View.GONE);
        return tv;
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
        ensureDotViews(st.dotsRow, cap, ctx);
        for (int i = 0; i < cap; i++) {
            styleDotView(st.dotsRow.getChildAt(i), i == activeIdx, ctx);
        }
    }

    private static void ensureDotViews(LinearLayout dotsRow, int cap, Context ctx) {
        if (dotsRow.getChildCount() == cap) return;
        dotsRow.removeAllViews();
        for (int i = 0; i < cap; i++) {
            View dot = new View(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 6), dp(ctx, 6));
            lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
            dotsRow.addView(dot, lp);
        }
    }

    private static void styleDotView(View dot, boolean active, Context ctx) {
        int w = active ? dp(ctx, 20) : dp(ctx, 6);
        int h = dp(ctx, 6);
        int color = active ? Color.WHITE : Color.argb(85, 255, 255, 255);
        dot.setBackground(GlassTheme.solidRounded(color, dp(ctx, 3)));
        ViewGroup.LayoutParams lp = dot.getLayoutParams();
        if (lp != null && lp.width != w) {
            lp.width = w;
            lp.height = h;
            dot.setLayoutParams(lp);
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
        populateHeroMetadataViews(st, item, ctx);
        int slideDir = (dir == 0) ? 1 : dir;
        if (animate && st.infoCol != null) {
            animateInfoColumn(st.infoCol, slideDir, ctx);
        }
        String url = item.optString("backdrop_url", "");
        if (url.isEmpty()) url = item.optString("poster_url", "");
        updateHeroBackdrops(st, url, slideDir, ctx, animate);
    }

    private static void populateHeroMetadataViews(HeroState st, JSONObject item, Context ctx) {
        String app = cleanPlatformName(item.optString("app", item.optString("app_name", "Streaming")));
        if (st.tagView != null) {
            st.tagView.setText(app.toUpperCase());
            st.tagView.setBackground(GlassTheme.solidRounded(getPlatformColor(app), dp(ctx, 5)));
        }
        if (st.metaView != null) st.metaView.setText("   " + buildMetaString(item));
        if (st.titleView != null) st.titleView.setText(resolveDisplayTitle(item));
        if (st.synopsisView != null) st.synopsisView.setText(item.optString("synopsis", item.optString("reason", "")));
        updateHeroConciergeBadge(st, item);
    }

    private static void updateHeroConciergeBadge(HeroState st, JSONObject item) {
        if (st.provenanceView == null) return;
        String tag = item.optString("hero_concierge_tag", "").trim();
        st.provenanceView.setText(tag);
        st.provenanceView.setVisibility(tag.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static String resolveDisplayTitle(JSONObject item) {
        String series = item.optString("series_title", "");
        String rawTitle = item.optString("title", "");
        if (!series.isEmpty() && !series.equalsIgnoreCase(rawTitle) && !rawTitle.isEmpty()) {
            return series + " \u00B7 " + rawTitle;
        }
        return !series.isEmpty() ? series : rawTitle;
    }

    // Smooth native transition on the left info column
    private static void animateInfoColumn(LinearLayout infoCol, int slideDir, Context ctx) {
        infoCol.animate().cancel();
        infoCol.setAlpha(0.12f);
        infoCol.setTranslationX(slideDir * dp(ctx, 18));
        infoCol.animate().alpha(1f).translationX(0f).setDuration(380).setInterpolator(HERO_EASE).start();
    }

    // Dual-layer crossfade + subtle parallax glide on the 16:9 UHD Hero artwork
    private static void updateHeroBackdrops(HeroState st, String url, int slideDir, Context ctx, boolean animate) {
        if (st.bgImageA == null || st.bgImageB == null) return;
        if (!animate) {
            setStaticHeroBackdrop(st, url, ctx);
        } else {
            crossfadeHeroBackdrop(st, url, slideDir, ctx);
        }
    }

    private static void setStaticHeroBackdrop(HeroState st, String url, Context ctx) {
        st.activeIsA = true;
        st.bgImageA.animate().cancel();
        st.bgImageB.animate().cancel();
        st.bgImageA.setAlpha(1f);
        st.bgImageA.setTranslationX(0f);
        st.bgImageA.setScaleX(1f);
        st.bgImageA.setScaleY(1f);
        st.bgImageB.setAlpha(0f);
        ImageLoader.loadHero(ctx, url, st.bgImageA);
    }

    private static void crossfadeHeroBackdrop(HeroState st, String url, int slideDir, Context ctx) {
        ImageView outgoing = st.activeIsA ? st.bgImageA : st.bgImageB;
        ImageView incoming = st.activeIsA ? st.bgImageB : st.bgImageA;
        st.activeIsA = !st.activeIsA;
        outgoing.animate().cancel();
        incoming.animate().cancel();
        ImageLoader.loadHero(ctx, url, incoming);
        animateOutgoingBackdrop(outgoing, slideDir, ctx);
        animateIncomingBackdrop(incoming, slideDir, ctx);
    }

    private static void animateOutgoingBackdrop(ImageView outgoing, int slideDir, Context ctx) {
        outgoing.animate()
            .alpha(0f).translationX(-slideDir * dp(ctx, 16))
            .scaleX(1.0f).scaleY(1.0f)
            .setDuration(420).setInterpolator(HERO_EASE).start();
    }

    private static void animateIncomingBackdrop(ImageView incoming, int slideDir, Context ctx) {
        incoming.setAlpha(0f);
        incoming.setTranslationX(slideDir * dp(ctx, 24));
        incoming.setScaleX(1.045f);
        incoming.setScaleY(1.045f);
        incoming.animate()
            .alpha(1f).translationX(0f)
            .scaleX(1.0f).scaleY(1.0f)
            .setDuration(440).setInterpolator(HERO_EASE).start();
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
        appendRuntimeAndGenre(sb, item);
        return sb.toString();
    }

    private static void appendRuntimeAndGenre(StringBuilder sb, JSONObject item) {
        int runtime = item.optInt("runtime_min", 0);
        if (runtime > 0) sb.append(" \u00B7 ").append(runtime).append("m");
        String genres = item.optString("genres", "");
        if (!genres.isEmpty()) {
            sb.append(" \u00B7 ").append(genres.split(",")[0].trim());
        }
    }

    static String extractRatingText(JSONObject item) {
        double score = item.optDouble("imdb_score", item.optDouble("imdb_raw", 0));
        if (score > 0) return "\u2605 " + String.format("%.1f", score);
        String r = item.optString("rating", "");
        if (!r.isEmpty()) return r.startsWith("\u2605") ? r : ("\u2605 " + r);
        return "";
    }

    static int getPlatformColor(String app) {
        return RegionProviderRegistry.getPlatformColorInt(app);
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
