package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Single-responsibility builder for the Home Top Bar (3-dash hamburger button,
 * centered AI Search Pill with focus styling, and right-aligned clock).
 */
public final class TopBarBuilder {

    public static final class TopBarViews {
        public final LinearLayout bar;
        public final TextView hamburger;
        public final LinearLayout searchBox;
        public final TextView searchLabel;
        public final TextView clockLabel;

        TopBarViews(LinearLayout bar, TextView hamburger, LinearLayout searchBox,
                    TextView searchLabel, TextView clockLabel) {
            this.bar = bar;
            this.hamburger = hamburger;
            this.searchBox = searchBox;
            this.searchLabel = searchLabel;
            this.clockLabel = clockLabel;
        }
    }

    private TopBarBuilder() {}

    public static TopBarViews build(Context ctx, FrameLayout navPanel, FrameLayout heroSlider,
                                    List<String> initialHints, Runnable onSearchClicked) {
        LinearLayout bar = createBarContainer(ctx);
        TextView hamburger = NavPanelBuilder.createTopBarHamburger(navPanel, ctx);
        bar.addView(hamburger);
        bar.addView(new View(ctx), GlassTheme.weightParams(1f));

        String firstHint = (initialHints != null && !initialHints.isEmpty())
            ? initialHints.get(0) : "Search movies, shows, or ask by mood\u2026";
        TextView[] labelHolder = new TextView[1];
        LinearLayout searchBox = buildSearchBox(ctx, firstHint, labelHolder, onSearchClicked);
        bar.addView(searchBox);
        bar.addView(new View(ctx), GlassTheme.weightParams(1f));

        TextView clock = buildClockLabel(ctx);
        bar.addView(clock, GlassTheme.wrapParams());
        bindTopBarDpad(hamburger, searchBox, heroSlider);
        return new TopBarViews(bar, hamburger, searchBox, labelHolder[0], clock);
    }

    private static LinearLayout createBarContainer(Context ctx) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 6));
        return bar;
    }

    private static TextView buildClockLabel(Context ctx) {
        TextView clock = GlassTheme.makeText(ctx, "", 12f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_BOLD, 1);
        clock.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        clock.setPadding(dp(ctx, 8), 0, 0, 0);
        return clock;
    }

    private static LinearLayout buildSearchBox(Context ctx, String initialText,
                                               TextView[] outLabel, Runnable onSearchClicked) {
        LinearLayout box = createSearchBoxContainer(ctx);
        TextView aiBadge = createAiBadge(ctx);
        TextView label = createSearchLabel(ctx, initialText);
        TextView icon = createMagnifierIcon(ctx);
        outLabel[0] = label;

        LinearLayout.LayoutParams abLp = GlassTheme.wrapParams();
        abLp.rightMargin = dp(ctx, 10);
        box.addView(aiBadge, abLp);
        box.addView(label, GlassTheme.weightParams(1f));
        box.addView(icon, new LinearLayout.LayoutParams(dp(ctx, 26), dp(ctx, 26)));

        box.setOnClickListener(v -> { if (onSearchClicked != null) onSearchClicked.run(); });
        bindSearchBoxFocus(ctx, box, aiBadge, label, icon);
        return box;
    }

    private static LinearLayout createSearchBoxContainer(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        float r = dp(ctx, 19);
        int shadedBg = Color.argb(185, 20, 26, 38);
        int subtleBorder = Color.argb(46, 255, 255, 255);
        box.setBackground(GlassTheme.focusRounded(shadedBg, subtleBorder, dp(ctx, 1), r));
        box.setElevation(dp(ctx, 6));
        box.setPadding(dp(ctx, 7), 0, dp(ctx, 6), 0);
        box.setFocusable(true);
        box.setClickable(true);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 388), dp(ctx, 36)));
        return box;
    }

    private static TextView createAiBadge(Context ctx) {
        TextView aiBadge = GlassTheme.makeText(ctx, "\u2726 AI", 9.5f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        aiBadge.setGravity(Gravity.CENTER);
        aiBadge.setPadding(dp(ctx, 9), dp(ctx, 3.5f), dp(ctx, 9), dp(ctx, 3.5f));
        aiBadge.setBackground(GlassTheme.solidRounded(Color.argb(38, 255, 255, 255), dp(ctx, 12)));
        return aiBadge;
    }

    private static TextView createSearchLabel(Context ctx, String initialText) {
        TextView label = GlassTheme.makeText(ctx, initialText, 11f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(0, 0, dp(ctx, 8), 0);
        return label;
    }

    private static TextView createMagnifierIcon(Context ctx) {
        TextView icon = GlassTheme.makeText(ctx, "\u2315", 13.5f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(GlassTheme.solidRounded(Color.argb(38, 255, 255, 255), dp(ctx, 13)));
        return icon;
    }

    private static void bindSearchBoxFocus(Context ctx, LinearLayout box, TextView aiBadge,
                                           TextView label, TextView icon) {
        float r = dp(ctx, 19);
        int shadedBg = Color.argb(185, 20, 26, 38);
        int subtleBorder = Color.argb(46, 255, 255, 255);
        int pillIdle = Color.argb(38, 255, 255, 255);
        box.setOnFocusChangeListener((v, focused) -> {
            box.setBackground(focused
                ? GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV, Color.WHITE, dp(ctx, 2.5f), r)
                : GlassTheme.focusRounded(shadedBg, subtleBorder, dp(ctx, 1), r));
            label.setTextColor(focused ? Color.WHITE : GlassTheme.TEXT_SECONDARY);
            aiBadge.setBackground(GlassTheme.solidRounded(focused ? Color.WHITE : pillIdle, dp(ctx, 12)));
            aiBadge.setTextColor(focused ? GlassTheme.TEXT_ON_LIGHT : GlassTheme.TEXT_PRIMARY);
            icon.setBackground(GlassTheme.solidRounded(focused ? Color.WHITE : pillIdle, dp(ctx, 13)));
            icon.setTextColor(focused ? GlassTheme.TEXT_ON_LIGHT : GlassTheme.TEXT_PRIMARY);
            float scale = focused ? 1.03f : 1f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
        });
    }

    private static void bindTopBarDpad(TextView hamburger, LinearLayout searchBox, FrameLayout heroSlider) {
        hamburger.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return searchBox.requestFocus();
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                HeroSliderBuilder.requestInitialFocus(heroSlider);
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP;
        });
        searchBox.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return hamburger.requestFocus();
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                HeroSliderBuilder.requestInitialFocus(heroSlider);
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP;
        });
    }

    private static int dp(Context ctx, float v) {
        return GlassTheme.dp(ctx, v);
    }
}
