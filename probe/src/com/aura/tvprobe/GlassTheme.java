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
 * Smoked Obsidian & Frosted Slate native TV theme for Aura TV.
 * Designed after Apple tvOS dark glass & Sony Bravia — high contrast, zero neon clutter.
 */
public final class GlassTheme {

    private GlassTheme() { }

    // ── Ambient backgrounds (Smoked Obsidian & Slate) ────────────────────
    public static final int BG_AMBIENT   = 0xFF0B0E14;
    public static final int BG_HERO_DARK = 0xFF131822;
    public static final int SURFACE_ELEV = 0xFF1C2331;

    // ── Frosted dark glass surfaces ──────────────────────────────────────
    public static final int GLASS_PANEL  = Color.argb(242, 18, 23, 33);
    public static final int GLASS_CARD   = Color.argb(230, 26, 32, 44);
    public static final int GLASS_SUBTLE = Color.argb(185, 22, 27, 38);

    // ── Text colors ──────────────────────────────────────────────────────
    public static final int TEXT_PRIMARY   = 0xFFF8FAFC;
    public static final int TEXT_SECONDARY = 0xFFCBD5E1;
    public static final int TEXT_MUTED     = 0xFF94A3B8;
    public static final int TEXT_HINT      = 0xFF64748B;
    public static final int TEXT_ON_LIGHT  = 0xFF0B0E14;

    // ── Professional Accent & Focus (Crisp Platinum White + Warm Gold) ───
    public static final int ACCENT       = 0xFFF8FAFC;
    public static final int ACCENT_FOCUS = 0xFFFFFFFF;
    public static final int ACCENT_LIGHT = Color.argb(42, 248, 250, 252);
    public static final int ACCENT_BG    = Color.argb(32, 248, 250, 252);
    public static final int FOCUS_RING   = 0xFFFFFFFF;
    public static final int GOLD_ACCENT  = 0xFFF59E0B;

    // ── Platform colors ──────────────────────────────────────────────────
    public static final int COLOR_NETFLIX  = 0xFFE50914;
    public static final int COLOR_PRIME    = 0xFF00A8E1;
    public static final int COLOR_HOTSTAR  = 0xFF2563EB;
    public static final int COLOR_APPLE_TV = 0xFF334155;
    public static final int COLOR_YOUTUBE  = 0xFFDC2626;
    public static final int COLOR_SONYLIV  = 0xFF4F46E5;
    public static final int COLOR_ZEE5     = 0xFF9333EA;

    // ── Utility colors ───────────────────────────────────────────────────
    public static final int COLOR_IMDB    = 0xFFF59E0B;
    public static final int COLOR_SUCCESS = 0xFF10B981;
    public static final int COLOR_WARN    = 0xFFF59E0B;

    // ── Borders / dividers (Subtle specular glass hairlines) ─────────────
    public static final int BORDER_LIGHT  = Color.argb(34, 255, 255, 255);
    public static final int BORDER_STRONG = Color.argb(64, 255, 255, 255);
    public static final int DIVIDER       = Color.argb(26, 255, 255, 255);

    // ── Typefaces ────────────────────────────────────────────────────────
    public static final Typeface FONT_BOLD    = Typeface.create("sans-serif", Typeface.BOLD);
    public static final Typeface FONT_MEDIUM  = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface FONT_REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    // ── Interpolators ────────────────────────────────────────────────────
    public static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.8f);

    // ── Dimension constants (dp) ─────────────────────────────────────────
    public static final int NAV_COLLAPSED_W = 52;
    public static final int NAV_EXPANDED_W  = 228;
    public static final int HERO_HEIGHT     = 212;
    public static final int POSTER_W        = 122;
    public static final int POSTER_H        = 174;

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
        TextView tv = makeText(ctx, text, 10.5f, TEXT_MUTED, FONT_BOLD, 1);
        tv.setLetterSpacing(0.08f);
        tv.setAllCaps(true);
        return tv;
    }

    public static TextView makeMuted(Context ctx, String text) {
        return makeText(ctx, text, 11.5f, TEXT_MUTED, FONT_REGULAR, 2);
    }

    // ── Drawable factories ───────────────────────────────────────────────

    public static GradientDrawable glassPanel(float radiusPx) {
        GradientDrawable gd = solidRounded(GLASS_PANEL, radiusPx);
        gd.setStroke(1, BORDER_STRONG);
        return gd;
    }

    public static GradientDrawable glassCard(float radiusPx) {
        GradientDrawable gd = solidRounded(GLASS_CARD, radiusPx);
        gd.setStroke(1, BORDER_LIGHT);
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

    public static GradientDrawable primaryPill(float radiusPx) {
        return solidRounded(0xFFF1F5F9, radiusPx);
    }

    public static GradientDrawable glassButton(float radiusPx) {
        GradientDrawable gd = solidRounded(GLASS_CARD, radiusPx);
        gd.setStroke(1, BORDER_STRONG);
        return gd;
    }

    // ── Button factories with unmistakable Apple-TV style focus ──────────

    public static TextView makePrimaryButton(Context ctx, String text) {
        TextView btn = makeText(ctx, text, 12.5f, TEXT_ON_LIGHT, FONT_BOLD, 1);
        float r = dp(ctx, 10);
        btn.setBackground(primaryPill(r));
        btn.setGravity(Gravity.CENTER);
        int h = dp(ctx, 14), v = dp(ctx, 7);
        btn.setPadding(h, v, h, v);
        btn.setFocusable(true);
        btn.setClickable(true);
        applyPrimaryButtonFocus(ctx, btn, r);
        return btn;
    }

    public static TextView makeGlassButton(Context ctx, String text) {
        TextView btn = makeText(ctx, text, 12f, TEXT_PRIMARY, FONT_BOLD, 1);
        float r = dp(ctx, 10);
        btn.setBackground(glassButton(r));
        btn.setGravity(Gravity.CENTER);
        int h = dp(ctx, 13), v = dp(ctx, 7);
        btn.setPadding(h, v, h, v);
        btn.setFocusable(true);
        btn.setClickable(true);
        applyGlassButtonFocus(ctx, btn, r);
        return btn;
    }

    private static void applyPrimaryButtonFocus(Context ctx, TextView btn, float r) {
        btn.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                btn.setBackground(focusRounded(Color.WHITE, GOLD_ACCENT, dp(ctx, 2.5f), r));
                btn.setTextColor(TEXT_ON_LIGHT);
                btn.setElevation(dp(ctx, 12));
                v.animate().scaleX(1.07f).scaleY(1.07f).setDuration(150).setInterpolator(EASE).start();
            } else {
                btn.setBackground(primaryPill(r));
                btn.setTextColor(TEXT_ON_LIGHT);
                btn.setElevation(dp(ctx, 2));
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(EASE).start();
            }
        });
    }

    private static void applyGlassButtonFocus(Context ctx, TextView btn, float r) {
        btn.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                btn.setBackground(focusRounded(Color.WHITE, Color.WHITE, dp(ctx, 2), r));
                btn.setTextColor(TEXT_ON_LIGHT);
                btn.setElevation(dp(ctx, 10));
                v.animate().scaleX(1.07f).scaleY(1.07f).setDuration(150).setInterpolator(EASE).start();
            } else {
                btn.setBackground(glassButton(r));
                btn.setTextColor(TEXT_PRIMARY);
                btn.setElevation(0);
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(EASE).start();
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
