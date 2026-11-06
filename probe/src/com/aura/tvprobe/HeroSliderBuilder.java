package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the full-bleed hero slider section with crossfade transitions,
 * auto-rotate, and AI recommendation overlay.
 */
public final class HeroSliderBuilder {

    private static final String TAG_BG = "hero_bg";
    private static final String TAG_INFO = "hero_info";
    private static final String TAG_DOTS = "hero_dots";
    private static final int CROSSFADE_OUT = 250;
    private static final int CROSSFADE_IN = 350;
    private static final int INFO_SLIDE = 300;
    private static final int AUTO_INTERVAL = 10000;

    private static final class HeroState {
        List<JSONObject> items = new ArrayList<>();
        int index = 0;
        Runnable runnable;
        HeroCallback callback;
    }

    public interface HeroCallback {
        void onWatchClicked(JSONObject item);
        void onWatchedClicked(JSONObject item);
        void onSimilarClicked(JSONObject item);
        void onMoreClicked(JSONObject item);
        void onSlideChanged(int index);
    }

    public static FrameLayout build(Context ctx, HeroCallback callback) {
        FrameLayout hero = createContainer(ctx);
        HeroState st = new HeroState();
        st.callback = callback;
        hero.setTag(st);
        addBackgroundLayer(hero, ctx);
        addGradientOverlays(hero, ctx);
        addTrailerButton(hero, ctx, callback);
        addInfoOverlay(hero, ctx, callback, null);
        addDotsLayer(hero, ctx);
        return hero;
    }

    public static void setItems(FrameLayout hero, List<JSONObject> items,
                                 Context ctx, HeroCallback callback) {
        HeroState st = getState(hero);
        st.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        st.index = 0;
        if (callback != null) st.callback = callback;
        if (!st.items.isEmpty()) showSlide(hero, 0, ctx, st.callback);
    }

    public static void showItemDirect(FrameLayout hero, JSONObject item,
                                       Context ctx, HeroCallback callback) {
        if (item == null) return;
        HeroState st = getState(hero);
        if (callback != null) st.callback = callback;
        crossfadeBackground(hero, item, ctx);
        animateInfoOverlay(hero, item, ctx, st.callback);
    }

    public static void nextSlide(FrameLayout hero) {
        HeroState st = getState(hero);
        if (st.items.isEmpty()) return;
        st.index = (st.index + 1) % st.items.size();
        showSlide(hero, st.index, hero.getContext(), st.callback);
    }

    public static void prevSlide(FrameLayout hero) {
        HeroState st = getState(hero);
        if (st.items.isEmpty()) return;
        st.index = (st.index - 1 + st.items.size()) % st.items.size();
        showSlide(hero, st.index, hero.getContext(), st.callback);
    }

    public static int getCurrentSlide(FrameLayout hero) {
        return getState(hero).index;
    }

    public static void startAutoRotate(FrameLayout hero, Handler handler) {
        stopAutoRotate(hero, handler);
        HeroState st = getState(hero);
        st.runnable = createAutoRotateRunnable(hero, handler);
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

    private static FrameLayout createContainer(Context ctx) {
        FrameLayout hero = new FrameLayout(ctx);
        int h = dp(ctx, GlassTheme.HERO_HEIGHT);
        hero.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h));
        hero.setClipChildren(true);
        hero.setClipToPadding(true);
        return hero;
    }

    private static void addBackgroundLayer(FrameLayout hero, Context ctx) {
        ImageView bg = new ImageView(ctx);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setBackgroundColor(GlassTheme.BG_AMBIENT);
        bg.setTag(TAG_BG);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 480), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        hero.addView(bg, lp);
    }

    private static void addGradientOverlays(FrameLayout hero, Context ctx) {
        addLeftFade(hero, ctx);
        addBottomFade(hero, ctx);
    }

    private static void addBottomFade(FrameLayout hero, Context ctx) {
        View fade = new View(ctx);
        GradientDrawable gd = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{Color.TRANSPARENT, GlassTheme.BG_AMBIENT});
        fade.setBackground(gd);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 90), Gravity.BOTTOM);
        hero.addView(fade, lp);
    }

    private static void addLeftFade(FrameLayout hero, Context ctx) {
        View fade = new View(ctx);
        int solidAmb = GlassTheme.BG_AMBIENT;
        int semiAmb = withAlpha(GlassTheme.BG_AMBIENT, 180);
        GradientDrawable gd = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{solidAmb, semiAmb, Color.TRANSPARENT});
        fade.setBackground(gd);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 260), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        lp.rightMargin = dp(ctx, 220);
        hero.addView(fade, lp);
    }

    private static void addTrailerButton(FrameLayout hero, Context ctx, HeroCallback cb) {
        LinearLayout trailerBox = new LinearLayout(ctx);
        trailerBox.setOrientation(LinearLayout.VERTICAL);
        trailerBox.setGravity(Gravity.CENTER);
        addTrailerCircle(hero, trailerBox, ctx, cb);
        addTrailerLabel(trailerBox, ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.setMargins(0, dp(ctx, 52), dp(ctx, 24), 0);
        hero.addView(trailerBox, lp);
    }

    private static void addTrailerCircle(FrameLayout hero, LinearLayout box,
                                          Context ctx, HeroCallback cb) {
        TextView circle = GlassTheme.makeText(ctx, "\u25B6", 16, GlassTheme.TEXT_PRIMARY,
                                               GlassTheme.FONT_BOLD, 1);
        circle.setGravity(Gravity.CENTER);
        circle.setBackground(GlassTheme.glassButton(dp(ctx, 22)));
        int s = dp(ctx, 42);
        circle.setFocusable(true);
        circle.setOnClickListener(v -> {
            HeroState st = getState(hero);
            if (!st.items.isEmpty() && st.callback != null) {
                st.callback.onWatchClicked(st.items.get(st.index));
            }
        });
        box.addView(circle, new LinearLayout.LayoutParams(s, s));
    }

    private static void addTrailerLabel(LinearLayout box, Context ctx) {
        TextView label = GlassTheme.makeText(ctx, "Trailer", 10, GlassTheme.TEXT_SECONDARY,
                                              GlassTheme.FONT_MEDIUM, 1);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(ctx, 3), 0, 0);
        box.addView(label, GlassTheme.wrapParams());
    }

    private static void addInfoOverlay(FrameLayout hero, Context ctx,
                                        HeroCallback callback, JSONObject item) {
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setTag(TAG_INFO);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(ctx, 24), dp(ctx, 44), 0, dp(ctx, 8));
        hero.addView(info, lp);
        if (item != null) populateInfo(info, ctx, item, callback);
    }

    private static void populateInfo(LinearLayout info, Context ctx,
                                      JSONObject item, HeroCallback callback) {
        info.removeAllViews();
        addPlatformTag(info, ctx, item);
        addTitle(info, ctx, item);
        addMetaLine(info, ctx, item);
        addAiReasonBlock(info, ctx, item);
        addActionButtons(info, ctx, item, callback);
    }

    private static void addPlatformTag(LinearLayout info, Context ctx, JSONObject item) {
        String app = item.optString("app", "Streaming").toUpperCase();
        int color = getPlatformColor(app);
        TextView tag = GlassTheme.makeText(ctx, app, 9, Color.WHITE, GlassTheme.FONT_BOLD, 1);
        tag.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        tag.setBackground(GlassTheme.solidRounded(color, dp(ctx, 6)));
        info.addView(tag, GlassTheme.wrapParams());
    }

    private static void addTitle(LinearLayout info, Context ctx, JSONObject item) {
        String title = item.optString("title", "");
        TextView tv = GlassTheme.makeText(ctx, title, 24, GlassTheme.TEXT_PRIMARY,
                                           GlassTheme.FONT_BOLD, 1);
        tv.setPadding(0, dp(ctx, 2), 0, dp(ctx, 1));
        info.addView(tv, GlassTheme.wrapParams());
    }

    private static void addMetaLine(LinearLayout info, Context ctx, JSONObject item) {
        String meta = buildMetaString(item);
        TextView tv = GlassTheme.makeText(ctx, meta, 11, GlassTheme.TEXT_SECONDARY,
                                           GlassTheme.FONT_MEDIUM, 1);
        tv.setPadding(0, 0, 0, dp(ctx, 4));
        info.addView(tv, GlassTheme.wrapParams());
    }

    private static String buildMetaString(JSONObject item) {
        StringBuilder sb = new StringBuilder();
        String rating = extractRatingText(item);
        if (!rating.isEmpty()) sb.append(rating).append(" \u00B7 ");
        int year = item.optInt("year", 0);
        if (year > 0) sb.append(year).append(" \u00B7 ");
        sb.append(capitalize(item.optString("type", "Series")));
        int runtime = item.optInt("runtime_min", 0);
        if (runtime > 0) sb.append(" \u00B7 ").append(runtime).append("m");
        appendGenres(sb, item);
        return sb.toString();
    }

    static String extractRatingText(JSONObject item) {
        double score = item.optDouble("imdb_score", item.optDouble("imdb_raw", 0));
        if (score > 0) return "\u2605 " + String.format("%.1f", score);
        String r = item.optString("rating", "");
        if (!r.isEmpty()) return r.startsWith("\u2605") ? r : ("\u2605 " + r);
        return "";
    }

    private static void appendGenres(StringBuilder sb, JSONObject item) {
        String genres = item.optString("genres", "");
        if (!genres.isEmpty()) {
            String first = genres.split(",")[0].trim();
            sb.append(" \u00B7 ").append(first);
        }
    }

    private static void addAiReasonBlock(LinearLayout info, Context ctx, JSONObject item) {
        String reason = item.optString("reason", item.optString("synopsis", ""));
        if (reason.isEmpty()) return;
        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackground(GlassTheme.glassCard(dp(ctx, 8)));
        int p = dp(ctx, 8);
        block.setPadding(p, dp(ctx, 5), p, dp(ctx, 5));
        addReasonLabel(block, ctx);
        addReasonText(block, ctx, reason);
        LinearLayout.LayoutParams lp = GlassTheme.matchWrapParams();
        lp.setMargins(0, 0, 0, dp(ctx, 8));
        info.addView(block, lp);
    }

    private static void addReasonLabel(LinearLayout block, Context ctx) {
        TextView label = GlassTheme.makeText(ctx, "WHY AURA RECOMMENDS THIS", 8,
                                              GlassTheme.ACCENT, GlassTheme.FONT_BOLD, 1);
        block.addView(label, GlassTheme.wrapParams());
    }

    private static void addReasonText(LinearLayout block, Context ctx, String reason) {
        TextView tv = GlassTheme.makeText(ctx, reason, 11, GlassTheme.TEXT_SECONDARY,
                                           GlassTheme.FONT_REGULAR, 2);
        tv.setPadding(0, dp(ctx, 2), 0, 0);
        block.addView(tv, GlassTheme.wrapParams());
    }

    private static void addActionButtons(LinearLayout info, Context ctx,
                                          JSONObject item, HeroCallback callback) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addBtn(row, GlassTheme.makePrimaryButton(ctx, "\u25B6 Watch"),
            v -> { if (callback != null) callback.onWatchClicked(item); }, ctx);
        addBtn(row, GlassTheme.makeGlassButton(ctx, "\u2713 Watched"),
            v -> { if (callback != null) callback.onWatchedClicked(item); }, ctx);
        addBtn(row, GlassTheme.makeGlassButton(ctx, "\u2192 Similar"),
            v -> { if (callback != null) callback.onSimilarClicked(item); }, ctx);
        addBtn(row, GlassTheme.makeGlassButton(ctx, "\u22EF More"),
            v -> { if (callback != null) callback.onMoreClicked(item); }, ctx);
        info.addView(row, GlassTheme.wrapParams());
    }

    private static void addBtn(LinearLayout row, TextView btn,
                                View.OnClickListener click, Context ctx) {
        btn.setTextSize(12f);
        int h = dp(ctx, 12), v = dp(ctx, 6);
        btn.setPadding(h, v, h, v);
        btn.setOnClickListener(click);
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.setMargins(0, 0, dp(ctx, 8), 0);
        row.addView(btn, lp);
    }

    private static void addDotsLayer(FrameLayout hero, Context ctx) {
        LinearLayout dots = new LinearLayout(ctx);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        dots.setTag(TAG_DOTS);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.setMargins(0, 0, dp(ctx, 28), dp(ctx, 20));
        hero.addView(dots, lp);
    }

    private static void updateDots(FrameLayout hero, int activeIdx, int total, Context ctx) {
        LinearLayout dots = findByTag(hero, TAG_DOTS);
        if (dots == null) return;
        dots.removeAllViews();
        int cap = Math.min(total, 6);
        for (int i = 0; i < cap; i++) {
            addDot(dots, ctx, i == activeIdx);
        }
    }

    private static void addDot(LinearLayout dots, Context ctx, boolean active) {
        View dot = new View(ctx);
        int w = active ? dp(ctx, 20) : dp(ctx, 6);
        int h = dp(ctx, 6);
        int color = active ? GlassTheme.ACCENT : withAlpha(GlassTheme.TEXT_PRIMARY, 40);
        dot.setBackground(GlassTheme.solidRounded(color, dp(ctx, 3)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
        dots.addView(dot, lp);
    }

    private static void showSlide(FrameLayout hero, int idx, Context ctx, HeroCallback cb) {
        HeroState st = getState(hero);
        if (st.items.isEmpty() || idx >= st.items.size()) return;
        JSONObject item = st.items.get(idx);
        crossfadeBackground(hero, item, ctx);
        animateInfoOverlay(hero, item, ctx, cb);
        updateDots(hero, idx, st.items.size(), ctx);
        if (cb != null) cb.onSlideChanged(idx);
    }

    private static void crossfadeBackground(FrameLayout hero, JSONObject item, Context ctx) {
        ImageView bg = findByTag(hero, TAG_BG);
        if (bg == null) return;
        loadPosterImage(bg, item, ctx);
    }

    private static void loadPosterImage(ImageView bg, JSONObject item, Context ctx) {
        String url = item.optString("poster_url", "");
        if (!url.isEmpty()) {
            ImageLoader.loadHero(ctx, url, bg);
        } else {
            bg.setImageBitmap(null);
        }
    }

    private static void animateInfoOverlay(FrameLayout hero, JSONObject item,
                                            Context ctx, HeroCallback cb) {
        LinearLayout info = findByTag(hero, TAG_INFO);
        if (info == null) return;
        info.setAlpha(0f);
        info.setTranslationY(dp(ctx, 12));
        populateInfo(info, ctx, item, cb);
        info.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(INFO_SLIDE)
            .setInterpolator(GlassTheme.EASE)
            .start();
    }

    private static Runnable createAutoRotateRunnable(FrameLayout hero, Handler handler) {
        return new Runnable() {
            @Override
            public void run() {
                nextSlide(hero);
                handler.postDelayed(this, AUTO_INTERVAL);
            }
        };
    }

    static int getPlatformColor(String app) {
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

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static FrameLayout.LayoutParams matchParams() {
        return new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findByTag(View root, String tag) {
        if (tag.equals(root.getTag())) return (T) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                T found = findByTag(g.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int dp(Context ctx, int dpVal) {
        return GlassTheme.dp(ctx, dpVal);
    }

    private HeroSliderBuilder() {}
}
