package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Cinema-grade animated bottom subtitle bar for AuraTtsNarrator.
 * Displays only the active sentence and highlights words in real time as they are spoken.
 */
public final class LiveSubtitleBar {

    private static final String TAG_PILL = "live_sub_pill";
    private static final String TAG_BADGE = "live_sub_badge";
    private static final String TAG_TEXT = "live_sub_text";

    private static final int COLOR_SPOKEN   = Color.rgb(248, 250, 252);
    private static final int COLOR_ACTIVE   = Color.rgb(245, 197, 24);
    private static final int COLOR_UPCOMING = Color.rgb(126, 140, 160);

    private LiveSubtitleBar() {}

    public static FrameLayout build(Context ctx) {
        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setVisibility(View.GONE);
        wrap.setFocusable(false);
        wrap.setClickable(false);
        LinearLayout pill = buildSubtitlePill(ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = GlassTheme.dp(ctx, 30);
        wrap.addView(pill, lp);
        return wrap;
    }

    private static LinearLayout buildSubtitlePill(Context ctx) {
        LinearLayout pill = new LinearLayout(ctx);
        pill.setTag(TAG_PILL);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        int padH = GlassTheme.dp(ctx, 22);
        int padV = GlassTheme.dp(ctx, 12);
        pill.setPadding(padH, padV, padH, padV);
        pill.setBackground(createPillBackground(ctx));
        pill.addView(buildLiveBadge(ctx));
        pill.addView(buildSentenceTextView(ctx));
        return pill;
    }

    private static GradientDrawable createPillBackground(Context ctx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.argb(242, 8, 12, 22));
        gd.setCornerRadius(GlassTheme.dp(ctx, 18));
        gd.setStroke(GlassTheme.dp(ctx, 1.5f), Color.argb(145, 245, 197, 24));
        return gd;
    }

    private static TextView buildLiveBadge(Context ctx) {
        TextView badge = GlassTheme.makeText(ctx, "\uD83D\uDD0A LIVE", 10.5f,
            COLOR_ACTIVE, GlassTheme.FONT_BOLD, 1);
        badge.setTag(TAG_BADGE);
        int padH = GlassTheme.dp(ctx, 10);
        int padV = GlassTheme.dp(ctx, 4);
        badge.setPadding(padH, padV, padH, padV);
        badge.setBackground(GlassTheme.solidRounded(Color.argb(42, 245, 197, 24), GlassTheme.dp(ctx, 10)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = GlassTheme.dp(ctx, 14);
        badge.setLayoutParams(lp);
        return badge;
    }

    private static TextView buildSentenceTextView(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTag(TAG_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tv.setTypeface(GlassTheme.FONT_MEDIUM);
        tv.setTextColor(COLOR_SPOKEN);
        tv.setMaxLines(2);
        tv.setMaxWidth(GlassTheme.dp(ctx, 760));
        tv.setLineSpacing(0f, 1.18f);
        return tv;
    }

    public static void animateNewSentence(FrameLayout wrap, String sentence) {
        if (wrap == null || sentence == null || sentence.isEmpty()) return;
        ensureVisible(wrap);
        TextView tv = wrap.findViewWithTag(TAG_TEXT);
        if (tv == null) return;
        tv.setAlpha(0.3f);
        tv.setTranslationY(GlassTheme.dp(wrap.getContext(), 6));
        renderHighlightedSpan(tv, sentence, 0, findFirstWordEnd(sentence));
        tv.animate().alpha(1f).translationY(0f).setDuration(150).setInterpolator(GlassTheme.EASE).start();
        pulseBadge(wrap.findViewWithTag(TAG_BADGE));
    }

    public static void updateWordHighlight(FrameLayout wrap, String sentence, int wordStart, int wordEnd) {
        if (wrap == null || sentence == null || sentence.isEmpty()) return;
        ensureVisible(wrap);
        TextView tv = wrap.findViewWithTag(TAG_TEXT);
        if (tv != null) {
            renderHighlightedSpan(tv, sentence, wordStart, wordEnd);
        }
    }

    public static void hide(FrameLayout wrap) {
        if (wrap == null || wrap.getVisibility() != View.VISIBLE) return;
        wrap.animate().cancel();
        wrap.animate().alpha(0f).translationY(GlassTheme.dp(wrap.getContext(), 10))
            .setDuration(180).setInterpolator(GlassTheme.EASE)
            .withEndAction(() -> wrap.setVisibility(View.GONE)).start();
    }

    private static void ensureVisible(FrameLayout wrap) {
        if (wrap.getVisibility() == View.VISIBLE) return;
        wrap.animate().cancel();
        wrap.setAlpha(0f);
        wrap.setTranslationY(GlassTheme.dp(wrap.getContext(), 10));
        wrap.setVisibility(View.VISIBLE);
        wrap.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(GlassTheme.EASE).start();
    }

    private static void pulseBadge(View badge) {
        if (badge == null) return;
        badge.animate().cancel();
        badge.setAlpha(0.65f);
        badge.animate().alpha(1f).setDuration(240).start();
    }

    public static SpannableString buildHighlightedSpan(String sentence, int wordStart, int wordEnd) {
        int len = sentence != null ? sentence.length() : 0;
        SpannableString span = new SpannableString(sentence != null ? sentence : "");
        if (len == 0) return span;
        int ws = Math.max(0, Math.min(wordStart, len));
        int we = Math.max(ws, Math.min(wordEnd, len));
        applySpokenRange(span, ws);
        applyActiveWordRange(span, ws, we);
        applyUpcomingRange(span, we, len);
        return span;
    }

    private static void renderHighlightedSpan(TextView tv, String sentence, int wordStart, int wordEnd) {
        tv.setText(buildHighlightedSpan(sentence, wordStart, wordEnd));
    }

    private static void applySpokenRange(SpannableString span, int ws) {
        if (ws > 0) {
            span.setSpan(new ForegroundColorSpan(COLOR_SPOKEN), 0, ws, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void applyActiveWordRange(SpannableString span, int ws, int we) {
        if (we > ws) {
            span.setSpan(new ForegroundColorSpan(COLOR_ACTIVE), ws, we, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new StyleSpan(Typeface.BOLD), ws, we, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void applyUpcomingRange(SpannableString span, int we, int len) {
        if (len > we) {
            span.setSpan(new ForegroundColorSpan(COLOR_UPCOMING), we, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static int findFirstWordEnd(String s) {
        int idx = s.indexOf(' ');
        return idx > 0 ? idx : s.length();
    }
}
