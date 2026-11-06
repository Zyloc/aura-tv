package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared glass-morphism theme constants and factory methods for Aura TV.
 * Apple-inspired light glass aesthetic with warm near-white background.
 */
public final class GlassTheme {

    private GlassTheme() { }

    // ── Ambient backgrounds ──────────────────────────────────────────────
    public static final int BG_AMBIENT   = 0xFFF0F1F6;
    public static final int BG_HERO_DARK = 0xFF0F0D20;

    // ── Glass panel alphas ───────────────────────────────────────────────
    public static final int GLASS_PANEL  = Color.argb(140, 255, 255, 255);
    public static final int GLASS_CARD   = Color.argb(115, 255, 255, 255);
    public static final int GLASS_SUBTLE = Color.argb(77, 255, 255, 255);

    // ── Text colors ──────────────────────────────────────────────────────
    public static final int TEXT_PRIMARY   = 0xFF1A1A2E;
    public static final int TEXT_SECONDARY = 0xFF5A5E70;
    public static final int TEXT_MUTED     = 0xFF8E92A4;
    public static final int TEXT_HINT      = 0xFFB0B4C0;

    // ── Accent ───────────────────────────────────────────────────────────
    public static final int ACCENT       = 0xFF6366F1;
    public static final int ACCENT_LIGHT = 0x1A6366F1;
    public static final int ACCENT_BG    = Color.argb(25, 99, 102, 241);

    // ── Platform colors ──────────────────────────────────────────────────
    public static final int COLOR_NETFLIX  = 0xFFE50914;
    public static final int COLOR_PRIME    = 0xFF00A8E1;
    public static final int COLOR_HOTSTAR  = 0xFF1D4ED8;
    public static final int COLOR_APPLE_TV = 0xFF1A1A1A;
    public static final int COLOR_YOUTUBE  = 0xFFFF0000;
    public static final int COLOR_SONYLIV  = 0xFF141414;
    public static final int COLOR_ZEE5     = 0xFF8B22AE;

    // ── Utility colors ───────────────────────────────────────────────────
    public static final int COLOR_IMDB    = 0xFFF5C518;
    public static final int COLOR_SUCCESS = 0xFF16A34A;
    public static final int COLOR_WARN    = 0xFFD97706;

    // ── Borders / dividers ───────────────────────────────────────────────
    public static final int BORDER_LIGHT = Color.argb(12, 0, 0, 0);
    public static final int DIVIDER      = Color.argb(10, 0, 0, 0);

    // ── Typefaces ────────────────────────────────────────────────────────
    public static final Typeface FONT_BOLD    = Typeface.create("sans-serif", Typeface.BOLD);
    public static final Typeface FONT_MEDIUM  = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface FONT_REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    // ── Interpolators ────────────────────────────────────────────────────
    public static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    // ── Dimension constants (dp) ─────────────────────────────────────────
    public static final int NAV_COLLAPSED_W = 56;
    public static final int NAV_EXPANDED_W  = 200;
    public static final int HERO_HEIGHT     = 245;
    public static final int POSTER_W        = 112;
    public static final int POSTER_H        = 160;

    // ── dp → px ──────────────────────────────────────────────────────────

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
        TextView tv = makeText(ctx, text, 11f, TEXT_MUTED, FONT_MEDIUM, 1);
        tv.setLetterSpacing(0.08f);
        tv.setAllCaps(true);
        return tv;
    }

    public static TextView makeMuted(Context ctx, String text) {
        return makeText(ctx, text, 13f, TEXT_MUTED, FONT_REGULAR, 2);
    }

    // ── Drawable factories ───────────────────────────────────────────────

    public static GradientDrawable glassPanel(float radiusDp) {
        return solidRounded(GLASS_PANEL, radiusDp);
    }

    public static GradientDrawable glassCard(float radiusDp) {
        return solidRounded(GLASS_CARD, radiusDp);
    }

    public static GradientDrawable glassSubtle(float radiusDp) {
        return solidRounded(GLASS_SUBTLE, radiusDp);
    }

    public static GradientDrawable solidRounded(int color, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius(radiusDp);
        return gd;
    }

    public static GradientDrawable accentButton(float radiusDp) {
        return solidRounded(ACCENT, radiusDp);
    }

    public static GradientDrawable glassButton(float radiusDp) {
        GradientDrawable gd = solidRounded(GLASS_CARD, radiusDp);
        gd.setStroke(1, BORDER_LIGHT);
        return gd;
    }

    // ── Button factories ─────────────────────────────────────────────────

    public static TextView makePrimaryButton(Context ctx, String text) {
        TextView btn = makeText(ctx, text, 14f, Color.WHITE, FONT_MEDIUM, 1);
        btn.setBackground(accentButton(dp(ctx, 8)));
        btn.setGravity(Gravity.CENTER);
        int h = dp(ctx, 8), v = dp(ctx, 10);
        btn.setPadding(h, v, h, v);
        btn.setFocusable(true);
        return btn;
    }

    public static TextView makeGlassButton(Context ctx, String text) {
        TextView btn = makeText(ctx, text, 14f, TEXT_PRIMARY, FONT_MEDIUM, 1);
        btn.setBackground(glassButton(dp(ctx, 8)));
        btn.setGravity(Gravity.CENTER);
        int h = dp(ctx, 8), v = dp(ctx, 10);
        btn.setPadding(h, v, h, v);
        btn.setFocusable(true);
        return btn;
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
