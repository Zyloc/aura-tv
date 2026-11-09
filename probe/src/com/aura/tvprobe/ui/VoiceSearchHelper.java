package com.aura.tvprobe;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;

/**
 * Handles Option 1 (in-app Bluetooth remote microphone recognition via
 * RecognizerIntent.ACTION_RECOGNIZE_SPEECH) and Option 2 (Google Assistant
 * voice search handoff intents).
 */
public final class VoiceSearchHelper {

    public static final int REQ_VOICE_SEARCH = 0xA077;
    private static final String ACTION_GMS_SEARCH = "com.google.android.gms.actions.SEARCH_ACTION";
    private static final String ACTION_MEDIA_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH";

    private VoiceSearchHelper() {}

    public static boolean startVoiceRecognition(Activity activity) {
        try {
            Intent i = buildRecognizerIntent();
            activity.startActivityForResult(i, REQ_VOICE_SEARCH);
            return true;
        } catch (Throwable e) {
            Toast.makeText(activity, "Voice input unavailable on this device", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static Intent buildRecognizerIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak movie, show, or mood for Aura AI\u2026");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        return i;
    }

    public static String extractActivityResultQuery(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_VOICE_SEARCH || resultCode != Activity.RESULT_OK || data == null) {
            return "";
        }
        return pickTopVoiceMatch(data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS));
    }

    public static String pickTopVoiceMatch( java.util.List<String> matches) {
        if (matches == null || matches.isEmpty()) return "";
        for (String m : matches) {
            if (m != null && !m.trim().isEmpty()) return m.trim();
        }
        return "";
    }

    public static String extractAssistantSearchQuery(Intent intent) {
        if (intent == null) return "";
        String q = resolveAssistantQuery(
            intent.getAction(),
            intent.getStringExtra(SearchManager.QUERY),
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS));
        if (!q.isEmpty()) clearConsumedQueryExtras(intent);
        return q;
    }

    public static String resolveAssistantQuery(String action, String searchQuery,
                                               String extraText, java.util.List<String> extraResults) {
        if (!isSearchHandoffAction(action)) return "";
        if (searchQuery != null && !searchQuery.trim().isEmpty()) return searchQuery.trim();
        if (extraText != null && !extraText.trim().isEmpty()) return extraText.trim();
        return pickTopVoiceMatch(extraResults);
    }

    public static boolean isSearchHandoffAction(String action) {
        return Intent.ACTION_SEARCH.equals(action)
            || ACTION_GMS_SEARCH.equals(action)
            || ACTION_MEDIA_SEARCH.equals(action);
    }

    private static void clearConsumedQueryExtras(Intent intent) {
        try {
            intent.removeExtra(SearchManager.QUERY);
            intent.removeExtra(Intent.EXTRA_TEXT);
            intent.setAction(Intent.ACTION_MAIN);
        } catch (Throwable ignored) {}
    }

    public static boolean isRemoteVoiceOrSearchKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST;
    }

    public static TextView buildMicButton(Context ctx, Runnable onClick) {
        TextView btn = GlassTheme.makeText(ctx, "\uD83C\uDFA4", 13f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1);
        btn.setGravity(Gravity.CENTER);
        btn.setFocusable(true);
        btn.setClickable(true);
        int size = GlassTheme.dp(ctx, 36);
        btn.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        styleMicButtonFocus(ctx, btn);
        btn.setOnClickListener(v -> { if (onClick != null) onClick.run(); });
        return btn;
    }

    private static void styleMicButtonFocus(Context ctx, TextView btn) {
        float r = GlassTheme.dp(ctx, 18);
        btn.setBackground(GlassTheme.focusRounded(
            Color.argb(185, 20, 26, 38), Color.argb(46, 255, 255, 255), GlassTheme.dp(ctx, 1), r));
        btn.setOnFocusChangeListener((v, focused) -> {
            btn.setBackground(focused
                ? GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV, Color.WHITE, GlassTheme.dp(ctx, 2.5f), r)
                : GlassTheme.focusRounded(Color.argb(185, 20, 26, 38), Color.argb(46, 255, 255, 255), GlassTheme.dp(ctx, 1), r));
            float scale = focused ? 1.08f : 1f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
        });
    }
}
