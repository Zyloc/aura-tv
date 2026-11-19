package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Frameless, background-free cinema subtitle overlay with word-to-word counter.
 * Reveals clean white text word-by-word in sync with audio (no underline, no highlight).
 */
public final class LiveSubtitleBar {

    private static final String TAG_TEXT = "live_sub_text";
    private static final int SUBTITLE_WIDTH_DP = 760;
    private static final int SUBTITLE_HEIGHT_DP = 82;
    private static final int BLACKOUT_STAGE_WIDTH_DP = 840;

    private LiveSubtitleBar() {}

    public static FrameLayout build(Context ctx) {
        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setVisibility(View.GONE);
        wrap.setBackgroundColor(Color.TRANSPARENT);
        TextView tv = buildOverlayTextView(ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            GlassTheme.dp(ctx, SUBTITLE_WIDTH_DP), GlassTheme.dp(ctx, SUBTITLE_HEIGHT_DP));
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = GlassTheme.dp(ctx, 26);
        wrap.addView(tv, lp);
        return wrap;
    }

    public static FrameLayout buildBlackoutStage(Context ctx) {
        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setVisibility(View.GONE);
        wrap.setBackgroundColor(Color.TRANSPARENT);
        TextView tv = buildBlackoutStageTextView(ctx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            GlassTheme.dp(ctx, BLACKOUT_STAGE_WIDTH_DP), FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        wrap.addView(tv, lp);
        return wrap;
    }

    public static void attachBackCancelHandler(FrameLayout wrap, Runnable onBackCancel) {
        if (wrap == null || onBackCancel == null) return;
        wrap.setFocusable(true);
        wrap.setFocusableInTouchMode(true);
        wrap.setOnKeyListener((v, keyCode, event) -> handleBackKey(keyCode, event, onBackCancel));
        wrap.post(wrap::requestFocus);
    }

    private static boolean handleBackKey(int keyCode, KeyEvent event, Runnable onBackCancel) {
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ESCAPE) return false;
        if (event != null && event.getAction() == KeyEvent.ACTION_UP) {
            onBackCancel.run();
        }
        return true;
    }

    private static TextView buildOverlayTextView(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTag(TAG_TEXT);
        tv.setBackgroundColor(Color.TRANSPARENT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21.5f);
        tv.setTypeface(GlassTheme.FONT_BOLD);
        tv.setTextColor(Color.WHITE);
        configureFixedLineBox(tv);
        return tv;
    }

    private static void configureFixedLineBox(TextView tv) {
        tv.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tv.setMinLines(2);
        tv.setMaxLines(2);
        tv.setLineSpacing(0f, 1.18f);
        tv.setShadowLayer(12f, 0f, 3f, Color.BLACK);
    }

    private static TextView buildBlackoutStageTextView(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTag(TAG_TEXT);
        tv.setBackgroundColor(Color.TRANSPARENT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24.5f);
        tv.setTypeface(GlassTheme.FONT_BOLD);
        tv.setTextColor(Color.WHITE);
        configureBlackoutStageBox(tv);
        return tv;
    }

    private static void configureBlackoutStageBox(TextView tv) {
        tv.setGravity(Gravity.CENTER);
        tv.setMinLines(2);
        tv.setMaxLines(3);
        tv.setLineSpacing(0f, 1.24f);
    }

    public static void animateNewSentence(FrameLayout wrap, String sentence) {
        List<SubtitleWordTracker.WordToken> tokens = SubtitleWordTracker.tokenize(sentence);
        renderWordByIndex(wrap, tokens, 0);
    }

    public static void updateWordHighlight(FrameLayout wrap, String sentence, int wordStart, int wordEnd) {
        List<SubtitleWordTracker.WordToken> tokens = SubtitleWordTracker.tokenize(sentence);
        int wordIdx = SubtitleWordTracker.resolveWordIndex(tokens, wordStart, wordEnd);
        renderWordByIndex(wrap, tokens, wordIdx);
    }

    public static void renderWordByIndex(
            FrameLayout wrap, List<SubtitleWordTracker.WordToken> tokens, int wordIdx) {
        if (wrap == null || tokens == null || tokens.isEmpty() || wordIdx < 0) return;
        ensureVisible(wrap);
        TextView tv = wrap.findViewWithTag(TAG_TEXT);
        if (tv == null) return;
        String pageText = SubtitleWordTracker.buildPageAtWord(tokens, wordIdx).text;
        if (!pageText.contentEquals(tv.getText())) tv.setText(pageText);
    }

    public static void hide(FrameLayout wrap) {
        if (wrap == null || wrap.getVisibility() != View.VISIBLE) return;
        wrap.animate().cancel();
        wrap.setVisibility(View.GONE);
    }

    private static void ensureVisible(FrameLayout wrap) {
        if (wrap.getVisibility() == View.VISIBLE) return;
        wrap.animate().cancel();
        wrap.setAlpha(1f);
        wrap.setTranslationY(0f);
        wrap.setVisibility(View.VISIBLE);
        if (wrap.isFocusable()) wrap.requestFocus();
    }

    public static String buildProgressiveText(String sentence, int wordEnd) {
        if (sentence == null || sentence.isEmpty()) return "";
        List<SubtitleWordTracker.WordToken> tokens = SubtitleWordTracker.tokenize(sentence);
        int clampedEnd = Math.max(findFirstWordEnd(sentence), Math.min(wordEnd, sentence.length()));
        int idx = SubtitleWordTracker.resolveWordIndex(tokens, Math.max(0, clampedEnd - 1), clampedEnd);
        return SubtitleWordTracker.buildPageAtWord(tokens, idx).text;
    }

    public static int findFirstWordEnd(String s) {
        if (s == null || s.isEmpty()) return 0;
        int idx = s.indexOf(' ');
        return idx > 0 ? idx : s.length();
    }
}
