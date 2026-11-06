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
 * Interactive Dark-Glass Hero Carousel for Aura TV.
 * Persistent buttons guarantee clicking Prev/Next never resets D-pad focus,
 * and every slide immediately displays its own matching artwork.
 */
public final class HeroSliderBuilder {

    private static final int AUTO_INTERVAL = 8000;

    private static final class HeroState {
        List<JSONObject> items = new ArrayList<>();
        int index = 0;
        JSONObject currentItem;
        Runnable runnable;
        HeroCallback callback;
        ImageView bgImage;
        LinearLayout infoCol;
        TextView tagView;
        TextView metaView;
        TextView titleView;
        TextView synopsisView;
        TextView provenanceView;
        TextView watchBtn;
        LinearLayout dotsRow;
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
        addBackgroundLayer(hero, st, ctx);
        addGradientOverlays(hero, ctx);
        addInfoOverlay(hero, st, ctx);
        addSlideDotsLayer(hero, st, ctx);
        return hero;
    }

    public static void requestInitialFocus(FrameLayout hero) {
        HeroState st = getState(hero);
        if (st.watchBtn != null) st.watchBtn.requestFocus();
    }

    public static void setItems(FrameLayout hero, List<JSONObject> items,
                                 Context ctx, HeroCallback callback) {
        HeroState st = getState(hero);
        st.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        st.index = 0;
        if (callback != null) st.callback = callback;
        if (!st.items.isEmpty()) showSlide(hero, 0, ctx, false);
    }

    public static void showItemDirect(FrameLayout hero, JSONObject item,
                                       Context ctx, HeroCallback callback) {
        if (item == null) return;
        HeroState st = getState(hero);
        if (callback != null) st.callback = callback;
        st.currentItem = item;
        syncIndexIfMatching(st, item);
        bindItemToViews(hero, st, item, ctx, true);
        updateDots(st, st.index, st.items.size(), ctx);
    }

    public static void nextSlide(FrameLayout hero) {
        HeroState st = getState(hero);
        if (st.items.isEmpty()) return;
        st.index = (st.index + 1) % st.items.size();
        showSlide(hero, st.index, hero.getContext(), true);
    }

    public static void prevSlide(FrameLayout hero) {
        HeroState st = getState(hero);
        if (st.items.isEmpty()) return;
        st.index = (st.index - 1 + st.items.size()) % st.items.size();
        showSlide(hero, st.index, hero.getContext(), true);
    }

    public static void startAutoRotate(FrameLayout hero, Handler handler) {
        stopAutoRotate(hero, handler);
        HeroState st = getState(hero);
        st.runnable = new Runnable() {
            @Override
            public void run() {
                if (!hero.hasFocus()) {
                    nextSlide(hero);
                }
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
        lp.setMargins(dp(ctx, 14), dp(ctx, 4), dp(ctx, 14), dp(ctx, 6));
        hero.setLayoutParams(lp);
        float radius = dp(ctx, 18);
        hero.setBackground(GlassTheme.focusRounded(
            GlassTheme.BG_HERO_DARK, GlassTheme.BORDER_STRONG, 1, radius));
        hero.setElevation(dp(ctx, 8));
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

    private static void addBackgroundLayer(FrameLayout hero, HeroState st, Context ctx) {
        ImageView bg = new ImageView(ctx);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setBackgroundColor(GlassTheme.BG_HERO_DARK);
        st.bgImage = bg;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 420), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        hero.addView(bg, lp);
    }

    private static void addGradientOverlays(FrameLayout hero, Context ctx) {
        View leftFade = new View(ctx);
        GradientDrawable gd = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{
                GlassTheme.BG_HERO_DARK,
                Color.argb(235, 19, 24, 34),
                Color.argb(140, 19, 24, 34),
                Color.TRANSPARENT
            });
        leftFade.setBackground(gd);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        lp.rightMargin = dp(ctx, 125);
        hero.addView(leftFade, lp);

        View bottomFade = new View(ctx);
        GradientDrawable bgd = new GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{GlassTheme.BG_HERO_DARK, Color.TRANSPARENT});
        bottomFade.setBackground(bgd);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 48), Gravity.BOTTOM);
        hero.addView(bottomFade, blp);
    }

    private static void addInfoOverlay(FrameLayout hero, HeroState st, Context ctx) {
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setClipChildren(false);
        info.setClipToPadding(false);
        st.infoCol = info;

        buildBadgeMetaRow(info, st, ctx);
        st.titleView = GlassTheme.makeText(ctx, "", 25f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        st.titleView.setPadding(0, dp(ctx, 4), 0, dp(ctx, 2));
        info.addView(st.titleView, GlassTheme.wrapParams());

        st.synopsisView = GlassTheme.makeText(ctx, "", 11.5f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_REGULAR, 2);
        st.synopsisView.setPadding(0, 0, 0, dp(ctx, 3));
        info.addView(st.synopsisView, GlassTheme.matchWrapParams());

        st.provenanceView = GlassTheme.makeText(ctx, "", 10f, GlassTheme.GOLD_ACCENT, GlassTheme.FONT_MEDIUM, 1);
        st.provenanceView.setPadding(0, 0, 0, dp(ctx, 8));
        info.addView(st.provenanceView, GlassTheme.matchWrapParams());

        buildActionButtonsRow(hero, info, st, ctx);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            dp(ctx, 560), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        lp.setMargins(dp(ctx, 22), dp(ctx, 8), 0, dp(ctx, 8));
        hero.addView(info, lp);
    }

    private static void buildBadgeMetaRow(LinearLayout info, HeroState st, Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        st.tagView = GlassTheme.makeText(ctx, "STREAMING", 9f, Color.WHITE, GlassTheme.FONT_BOLD, 1);
        st.tagView.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        st.tagView.setBackground(GlassTheme.solidRounded(GlassTheme.COLOR_NETFLIX, dp(ctx, 5)));
        row.addView(st.tagView, GlassTheme.wrapParams());

        st.metaView = GlassTheme.makeText(ctx, "", 11f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_BOLD, 1);
        row.addView(st.metaView, GlassTheme.wrapParams());
        info.addView(row, GlassTheme.wrapParams());
    }

    private static void buildActionButtonsRow(FrameLayout hero, LinearLayout info,
                                               HeroState st, Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 4), dp(ctx, 4));

        st.watchBtn = GlassTheme.makePrimaryButton(ctx, "\u25B6 Watch Now");
        addBtn(row, st.watchBtn, v -> {
            if (st.callback != null && st.currentItem != null) st.callback.onWatchClicked(st.currentItem);
        }, ctx);

        addBtn(row, GlassTheme.makeGlassButton(ctx, "\u2713 Watched"), v -> {
            if (st.callback != null && st.currentItem != null) st.callback.onWatchedClicked(st.currentItem);
        }, ctx);

        addBtn(row, GlassTheme.makeGlassButton(ctx, "\u22EF Details"), v -> {
            if (st.callback != null && st.currentItem != null) st.callback.onMoreClicked(st.currentItem);
        }, ctx);

        addBtn(row, GlassTheme.makeGlassButton(ctx, "\u2039 Prev"), v -> prevSlide(hero), ctx);
        addBtn(row, GlassTheme.makeGlassButton(ctx, "Next \u203A"), v -> nextSlide(hero), ctx);

        info.addView(row, GlassTheme.wrapParams());
    }

    private static void addBtn(LinearLayout row, TextView btn,
                                View.OnClickListener click, Context ctx) {
        btn.setTextSize(11f);
        int h = dp(ctx, 11), v = dp(ctx, 6);
        btn.setPadding(h, v, h, v);
        btn.setOnClickListener(click);
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.setMargins(0, 0, dp(ctx, 8), 0);
        row.addView(btn, lp);
    }

    private static void addSlideDotsLayer(FrameLayout hero, HeroState st, Context ctx) {
        LinearLayout dots = new LinearLayout(ctx);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER_VERTICAL);
        st.dotsRow = dots;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.setMargins(0, 0, dp(ctx, 18), dp(ctx, 12));
        hero.addView(dots, lp);
    }

    private static void updateDots(HeroState st, int activeIdx, int total, Context ctx) {
        if (st.dotsRow == null) return;
        st.dotsRow.removeAllViews();
        int cap = Math.min(total, 6);
        for (int i = 0; i < cap; i++) {
            View dot = new View(ctx);
            int w = (i == activeIdx) ? dp(ctx, 20) : dp(ctx, 6);
            int h = dp(ctx, 6);
            int color = (i == activeIdx) ? Color.WHITE : Color.argb(85, 255, 255, 255);
            dot.setBackground(GlassTheme.solidRounded(color, dp(ctx, 3)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
            st.dotsRow.addView(dot, lp);
        }
    }

    private static void showSlide(FrameLayout hero, int idx, Context ctx, boolean animateText) {
        HeroState st = getState(hero);
        if (st.items.isEmpty() || idx >= st.items.size()) return;
        JSONObject item = st.items.get(idx);
        st.currentItem = item;
        bindItemToViews(hero, st, item, ctx, animateText);
        updateDots(st, idx, st.items.size(), ctx);
        if (st.callback != null) st.callback.onSlideChanged(idx);
    }

    private static void bindItemToViews(FrameLayout hero, HeroState st,
                                         JSONObject item, Context ctx, boolean animate) {
        String app = cleanPlatformName(item.optString("app", item.optString("app_name", "Streaming")));
        if (st.tagView != null) {
            st.tagView.setText(app.toUpperCase());
            st.tagView.setBackground(GlassTheme.solidRounded(getPlatformColor(app), dp(ctx, 5)));
        }
        if (st.metaView != null) {
            st.metaView.setText("   " + buildMetaString(item));
        }
        if (st.titleView != null) {
            st.titleView.setText(item.optString("title", ""));
        }
        if (st.synopsisView != null) {
            String syn = item.optString("synopsis", item.optString("reason", ""));
            st.synopsisView.setText(syn);
        }
        if (st.provenanceView != null) {
            String reason = item.optString("reason", "");
            String prov = !reason.isEmpty()
                ? ("\u2726 " + reason)
                : "\u2726 Picked from your Watch History & JustWatch India Graph";
            st.provenanceView.setText(prov);
        }
        if (animate && st.titleView != null) {
            st.titleView.setAlpha(0.4f);
            st.titleView.animate().alpha(1f).setDuration(160).start();
        }
        if (st.bgImage != null) {
            String url = item.optString("poster_url", "");
            ImageLoader.loadHero(ctx, url, st.bgImage);
        }
    }

    static String cleanPlatformName(String raw) {
        if (raw == null || raw.isEmpty()) return "Streaming";
        int dot = raw.indexOf('\u00B7');
        if (dot > 0) raw = raw.substring(0, dot).trim();
        return raw;
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
