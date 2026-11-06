package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared glass-morphism theme constants and native TV focus helpers for Aura TV.
 */
public final class GlassTheme {

    private GlassTheme() { }

    // ── Ambient backgrounds ──────────────────────────────────────────────
    public static final int BG_AMBIENT   = 0xFFEBEDF5;
    public static final int BG_HERO_DARK = 0xFF161826;

    // ── Glass panel alphas ───────────────────────────────────────────────
    public static final int GLASS_PANEL  = Color.argb(215, 255, 255, 255);
    public static final int GLASS_CARD   = Color.argb(235, 255, 255, 255);
    public static final int GLASS_SUBTLE = Color.argb(175, 255, 255, 255);

    // ── Text colors ──────────────────────────────────────────────────────
    public static final int TEXT_PRIMARY   = 0xFF111424;
    public static final int TEXT_SECONDARY = 0xFF45495E;
    public static final int TEXT_MUTED     = 0xFF6E738A;
    public static final int TEXT_HINT      = 0xFF9499B0;

    // ── Accent & Focus ───────────────────────────────────────────────────
    public static final int ACCENT       = 0xFF4F46E5;
    public static final int ACCENT_FOCUS = 0xFF4338CA;
    public static final int ACCENT_LIGHT = 0x264F46E5;
    public static final int ACCENT_BG    = Color.argb(40, 79, 70, 229);
    public static final int FOCUS_RING   = 0xFF4F46E5;

    // ── Platform colors ──────────────────────────────────────────────────
    public static final int COLOR_NETFLIX  = 0xFFE50914;
    public static final int COLOR_PRIME    = 0xFF00A8E1;
    public static final int COLOR_HOTSTAR  = 0xFF1D4ED8;
    public static final int COLOR_APPLE_TV = 0xFF1A1A1A;
    public static final int COLOR_YOUTUBE  = 0xFFFF0000;
    public static final int COLOR_SONYLIV  = 0xFF141414;
    public static final int COLOR_ZEE5     = 0xFF8B22AE;

    // ── Utility colors ───────────────────────────────────────────────────
    public static final int COLOR_IMDB    = 0xFFD97706;
    public static final int COLOR_SUCCESS = 0xFF16A34A;
    public static final int COLOR_WARN    = 0xFFD97706;

    // ── Borders / dividers ───────────────────────────────────────────────
    public static final int BORDER_LIGHT  = Color.argb(28, 17, 20, 36);
    public static final int BORDER_STRONG = Color.argb(45, 17, 20, 36);
    public static final int DIVIDER       = Color.argb(22, 17, 20, 36);

    // ── Typefaces ────────────────────────────────────────────────────────
    public static final Typeface FONT_BOLD    = Typeface.create("sans-serif", Typeface.BOLD);
    public static final Typeface FONT_MEDIUM  = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface FONT_REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    // ── Interpolators ────────────────────────────────────────────────────
    public static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    // ── Dimension constants (dp) ─────────────────────────────────────────
    public static final int NAV_COLLAPSED_W = 64;
    public static final int NAV_EXPANDED_W  = 200;
    public static final int HERO_HEIGHT     = 205;
    public static final int POSTER_W        = 118;
    public static final int POSTER_H        = 168;

    public static int dp(Context ctx, float dp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // ── Typography factories ─────────────────────────────────────────────

    public static TextView makeText(Context ctx, String text, float sizeSp,
                                    int color, Typeface face, int maxLines) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(face);
        tv.setMaxLines(maxLines);
        return tv;
    }

    public static TextView makeLabel(Context ctx, String text) {
        TextView tv = makeText(ctx, text, 11f, TEXT_MUTED, FONT_BOLD, 1);
        tv.setLetterSpacing(0.06f);
        tv.setAllCaps(true);
        return tv;
    }

    public static TextView makeMuted(Context ctx, String text) {
        return makeText(ctx, text, 12f, TEXT_MUTED, FONT_REGULAR, 2);
    }

    // ── Drawable factories ───────────────────────────────────────────────

    public static GradientDrawable glassPanel(float radiusPx) {
        GradientDrawable gd = solidRounded(GLASS_PANEL, radiusPx);
        gd.setStroke(1, BORDER_LIGHT);
        return gd;
    }

    public static GradientDrawable glassCard(float radiusPx) {
        GradientDrawable gd = solidRounded(GLASS_CARD, radiusPx);
        gd.setStroke(1, BORDER_STRONG);
        return gd;
    }

    public static GradientDrawable glassSubtle(float radiusPx) {
        GradientDrawable gd = solidRounded(GLASS_SUBTLE, radiusPx);
        gd.setStroke(1, BORDER_LIGHT);
        return gd;
    }

    public static GradientDrawable solidRounded(int color, float radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius(radiusPx);
        return gd;
    }

    public static GradientDrawable focusRounded(int bgColor, int strokeColor, int strokePx, float radiusPx) {
        GradientDrawable gd = solidRounded(bgColor, radiusPx);
        gd.setStroke(strokePx, strokeColor);
        return gd;
    }

    public static GradientDrawable accentButton(float radiusPx) {
        return solidRounded(ACCENT, radiusPx);
    }

    public static GradientDrawable glassButton(float radiusPx) {
        GradientDrawable gd = solidRounded(GLASS_CARD, radiusPx);
        gd.setStroke(2, BORDER_STRONG);
        return gd;
    }

    // ── Button factories with unmistakable TV D-pad focus ────────────────

    public static TextView makePrimaryButton(Context ctx, String text) {
        TextView btn = makeText(ctx, text, 13f, Color.WHITE, FONT_BOLD, 1);
        float r = dp(ctx, 10);
        btn.setBackground(accentButton(r));
        btn.setGravity(Gravity.CENTER);
        int h = dp(ctx, 14), v = dp(ctx, 8);
        btn.setPadding(h, v, h, v);
        btn.setFocusable(true);
        btn.setClickable(true);
        applyPrimaryButtonFocus(ctx, btn, r);
        return btn;
    }

    public static TextView makeGlassButton(Context ctx, String text) {
        TextView btn = makeText(ctx, text, 13f, TEXT_PRIMARY, FONT_BOLD, 1);
        float r = dp(ctx, 10);
        btn.setBackground(glassButton(r));
        btn.setGravity(Gravity.CENTER);
        int h = dp(ctx, 14), v = dp(ctx, 8);
        btn.setPadding(h, v, h, v);
        btn.setFocusable(true);
        btn.setClickable(true);
        applyGlassButtonFocus(ctx, btn, r);
        return btn;
    }

    private static void applyPrimaryButtonFocus(Context ctx, TextView btn, float r) {
        btn.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                btn.setBackground(focusRounded(ACCENT, TEXT_PRIMARY, dp(ctx, 3), r));
                btn.setElevation(dp(ctx, 10));
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(140).start();
            } else {
                btn.setBackground(accentButton(r));
                btn.setElevation(dp(ctx, 2));
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
        });
    }

    private static void applyGlassButtonFocus(Context ctx, TextView btn, float r) {
        btn.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                btn.setBackground(focusRounded(ACCENT, TEXT_PRIMARY, dp(ctx, 2), r));
                btn.setTextColor(Color.WHITE);
                btn.setElevation(dp(ctx, 10));
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(140).start();
            } else {
                btn.setBackground(glassButton(r));
                btn.setTextColor(TEXT_PRIMARY);
                btn.setElevation(0);
                v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
        });
    }

    // ── Layout param helpers ─────────────────────────────────────────────

    public static LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams matchWrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weightParams(float weight) {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }
}
