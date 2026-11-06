package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Interactive Hero Carousel for Aura TV v2.
 * Clean native TV feel without cluttered recommendation boxes; includes Prev/Next controls and auto-slide.
 */
public final class HeroSliderBuilder {

    private static final String TAG_BG = "hero_bg";
    private static final String TAG_INFO = "hero_info";
    private static final String TAG_DOTS = "hero_dots";
    private static final String TAG_WATCH_BTN = "hero_watch_btn";
    private static final int INFO_SLIDE = 220;
    private static final int AUTO_INTERVAL = 7000;

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
        addInfoOverlay(hero, ctx, callback, null);
        addSlideControlsLayer(hero, ctx);
        return hero;
    }

    public static void requestInitialFocus(FrameLayout hero) {
        View watchBtn = findByTag(hero, TAG_WATCH_BTN);
        if (watchBtn != null) watchBtn.requestFocus();
    }

    public static void setItems(FrameLayout hero, List<JSONObject> items,
                                 Context ctx, HeroCallback callback) {
        HeroState st = getState(hero);
        st.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        st.index = 0;
        if (callback != null) st.callback = callback;
        if (!st.items.isEmpty()) showSlide(hero, 0, ctx, st.callback, false);
    }

    public static void showItemDirect(FrameLayout hero, JSONObject item,
                                       Context ctx, HeroCallback callback) {
        if (item == null) return;
        HeroState st = getState(hero);
        if (callback != null) st.callback = callback;
        crossfadeBackground(hero, item, ctx);
        LinearLayout info = findByTag(hero, TAG_INFO);
        if (info != null) populateInfo(hero, info, ctx, item, st.callback, false);
    }

    public static void nextSlide(FrameLayout hero) {
        HeroState st = getState(hero);
        if (st.items.isEmpty()) return;
        st.index = (st.index + 1) % st.items.size();
        showSlide(hero, st.index, hero.getContext(), st.callback, false);
    }

    public static void prevSlide(FrameLayout hero) {
        HeroState st = getState(hero);
        if (st.items.isEmpty()) return;
        st.index = (st.index - 1 + st.items.size()) % st.items.size();
        showSlide(hero, st.index, hero.getContext(), st.callback, false);
    }

    public static int getCurrentSlide(FrameLayout hero) {
        return getState(hero).index;
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

    private static FrameLayout createContainer(Context ctx) {
        FrameLayout hero = new FrameLayout(ctx);
        int h = dp(ctx, GlassTheme.HERO_HEIGHT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h);
        lp.setMargins(dp(ctx, 16), dp(ctx, 4), dp(ctx, 16), dp(ctx, 6));
        hero.setLayoutParams(lp);
        float radius = dp(ctx, 18);
        hero.setBackground(GlassTheme.focusRounded(Color.WHITE, GlassTheme.BORDER_STRONG, 1, radius));
        hero.setElevation(dp(ctx, 6));
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

    private static void addBackgroundLayer(FrameLayout hero, Context ctx) {
        ImageView bg = new ImageView(ctx);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setBackgroundColor(Color.WHITE);
        bg.setTag(TAG_BG);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 440), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        hero.addView(bg, lp);
    }

    private static void addGradientOverlays(FrameLayout hero, Context ctx) {
        View leftFade = new View(ctx);
        GradientDrawable gd = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.WHITE, Color.argb(220, 255, 255, 255), Color.TRANSPARENT});
        leftFade.setBackground(gd);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 260), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        lp.rightMargin = dp(ctx, 180);
        hero.addView(leftFade, lp);
    }

    private static void addInfoOverlay(FrameLayout hero, Context ctx,
                                        HeroCallback callback, JSONObject item) {
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setClipChildren(false);
        info.setClipToPadding(false);
        info.setTag(TAG_INFO);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 540), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        lp.setMargins(dp(ctx, 22), dp(ctx, 8), 0, dp(ctx, 8));
        hero.addView(info, lp);
        if (item != null) populateInfo(hero, info, ctx, item, callback, false);
    }

    private static void populateInfo(FrameLayout hero, LinearLayout info, Context ctx,
                                      JSONObject item, HeroCallback callback, boolean keepWatchFocus) {
        boolean hadFocus = info.hasFocus();
        info.removeAllViews();
        addBadgeAndMetaRow(info, ctx, item);
        addTitle(info, ctx, item);
        addSynopsisLine(info, ctx, item);
        addActionButtons(hero, info, ctx, item, callback);
        if (hadFocus || keepWatchFocus) {
            View watchBtn = findByTag(info, TAG_WATCH_BTN);
            if (watchBtn != null) watchBtn.requestFocus();
        }
    }

    private static void addBadgeAndMetaRow(LinearLayout info, Context ctx, JSONObject item) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String app = item.optString("app", "Streaming").toUpperCase();
        TextView tag = GlassTheme.makeText(ctx, app, 9f, Color.WHITE, GlassTheme.FONT_BOLD, 1);
        tag.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        tag.setBackground(GlassTheme.solidRounded(getPlatformColor(app), dp(ctx, 6)));
        row.addView(tag, GlassTheme.wrapParams());

        TextView meta = GlassTheme.makeText(ctx, "   " + buildMetaString(item), 11f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_BOLD, 1);
        row.addView(meta, GlassTheme.wrapParams());
        info.addView(row, GlassTheme.wrapParams());
    }

    private static void addTitle(LinearLayout info, Context ctx, JSONObject item) {
        String title = item.optString("title", "");
        TextView tv = GlassTheme.makeText(ctx, title, 26f, GlassTheme.TEXT_PRIMARY,
                                           GlassTheme.FONT_BOLD, 1);
        tv.setPadding(0, dp(ctx, 4), 0, dp(ctx, 2));
        info.addView(tv, GlassTheme.wrapParams());
    }

    private static void addSynopsisLine(LinearLayout info, Context ctx, JSONObject item) {
        String syn = item.optString("synopsis", item.optString("reason", ""));
        if (syn.isEmpty()) return;
        TextView tv = GlassTheme.makeText(ctx, syn, 11.5f, GlassTheme.TEXT_SECONDARY,
                                           GlassTheme.FONT_REGULAR, 2);
        tv.setPadding(0, 0, 0, dp(ctx, 10));
        info.addView(tv, GlassTheme.matchWrapParams());
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

    private static void addActionButtons(FrameLayout hero, LinearLayout info, Context ctx,
                                          JSONObject item, HeroCallback callback) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
        TextView watchBtn = GlassTheme.makePrimaryButton(ctx, "\u25B6 Watch Now");
        watchBtn.setTag(TAG_WATCH_BTN);
        addBtn(row, watchBtn, v -> { if (callback != null) callback.onWatchClicked(item); }, ctx);
        addBtn(row, GlassTheme.makeGlassButton(ctx, "\u2713 Watched"),
            v -> { if (callback != null) callback.onWatchedClicked(item); }, ctx);
        addBtn(row, GlassTheme.makeGlassButton(ctx, "\u22EF Details"),
            v -> { if (callback != null) callback.onMoreClicked(item); }, ctx);
        addBtn(row, GlassTheme.makeGlassButton(ctx, "\u2039"),
            v -> prevSlide(hero), ctx);
        addBtn(row, GlassTheme.makeGlassButton(ctx, "\u203A"),
            v -> nextSlide(hero), ctx);
        info.addView(row, GlassTheme.wrapParams());
    }

    private static void addBtn(LinearLayout row, TextView btn,
                                View.OnClickListener click, Context ctx) {
        btn.setTextSize(11.5f);
        int h = dp(ctx, 12), v = dp(ctx, 6);
        btn.setPadding(h, v, h, v);
        btn.setOnClickListener(click);
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.setMargins(0, 0, dp(ctx, 8), 0);
        row.addView(btn, lp);
    }

    private static void addSlideControlsLayer(FrameLayout hero, Context ctx) {
        LinearLayout dots = new LinearLayout(ctx);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        dots.setTag(TAG_DOTS);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.setMargins(0, 0, dp(ctx, 18), dp(ctx, 12));
        hero.addView(dots, lp);
    }

    private static void updateDots(FrameLayout hero, int activeIdx, int total, Context ctx) {
        LinearLayout dots = findByTag(hero, TAG_DOTS);
        if (dots == null) return;
        dots.removeAllViews();
        int cap = Math.min(total, 6);
        for (int i = 0; i < cap; i++) {
            View dot = new View(ctx);
            int w = (i == activeIdx) ? dp(ctx, 20) : dp(ctx, 6);
            int h = dp(ctx, 6);
            int color = (i == activeIdx) ? GlassTheme.ACCENT : Color.argb(90, 17, 20, 36);
            dot.setBackground(GlassTheme.solidRounded(color, dp(ctx, 3)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
            dots.addView(dot, lp);
        }
    }

    private static void showSlide(FrameLayout hero, int idx, Context ctx,
                                   HeroCallback cb, boolean focusWatch) {
        HeroState st = getState(hero);
        if (st.items.isEmpty() || idx >= st.items.size()) return;
        JSONObject item = st.items.get(idx);
        crossfadeBackground(hero, item, ctx);
        LinearLayout info = findByTag(hero, TAG_INFO);
        if (info != null) {
            populateInfo(hero, info, ctx, item, cb, focusWatch);
        }
        updateDots(hero, idx, st.items.size(), ctx);
        if (cb != null) cb.onSlideChanged(idx);
    }

    private static void crossfadeBackground(FrameLayout hero, JSONObject item, Context ctx) {
        ImageView bg = findByTag(hero, TAG_BG);
        if (bg == null) return;
        String url = item.optString("poster_url", "");
        if (!url.isEmpty()) {
            ImageLoader.loadHero(ctx, url, bg);
        } else {
            bg.setImageBitmap(null);
        }
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
