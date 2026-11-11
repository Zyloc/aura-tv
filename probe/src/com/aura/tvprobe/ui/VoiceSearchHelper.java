package com.aura.tvprobe;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.database.MatrixCursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.BaseColumns;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/**
 * Handles Option 1 (in-app Bluetooth remote microphone recognition via
 * RecognizerIntent.ACTION_RECOGNIZE_SPEECH) and Option 2 (Google Assistant
 * global searchable ContentProvider + voice search handoff intents).
 */
public final class VoiceSearchHelper {

    public static final int REQ_VOICE_SEARCH = 0xA077;
    public static final String SEARCH_AUTHORITY = "com.aura.tvprobe.search";
    private static final String ACTION_GMS_SEARCH = "com.google.android.gms.actions.SEARCH_ACTION";
    private static final String ACTION_MEDIA_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH";
    public static final String[] SUGGEST_COLUMNS = {
        BaseColumns._ID,
        SearchManager.SUGGEST_COLUMN_TEXT_1,
        SearchManager.SUGGEST_COLUMN_TEXT_2,
        SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
        SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
        SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
        SearchManager.SUGGEST_COLUMN_DURATION
    };

    private VoiceSearchHelper() {}

    public static boolean startVoiceRecognition(Activity activity) {
        try {
            activity.startActivityForResult(buildRecognizerIntent(), REQ_VOICE_SEARCH);
            return true;
        } catch (Throwable e) {
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

    public static String pickTopVoiceMatch(List<String> matches) {
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
        if (q.isEmpty()) q = extractDirectOrSuggestionQuery(intent);
        if (!q.isEmpty()) clearConsumedQueryExtras(intent);
        return q;
    }

    private static String extractDirectOrSuggestionQuery(Intent intent) {
        String direct = intent.getStringExtra("aura_voice_query");
        if (direct != null && !direct.trim().isEmpty()) return direct.trim();
        return extractSuggestionViewQuery(intent);
    }

    private static String extractSuggestionViewQuery(Intent intent) {
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return "";
        String extraKey = intent.getStringExtra(SearchManager.EXTRA_DATA_KEY);
        if (extraKey != null && !extraKey.trim().isEmpty()) return extraKey.trim();
        Uri data = intent.getData();
        return (data != null && data.getLastPathSegment() != null) ? data.getLastPathSegment().trim() : "";
    }

    public static String resolveAssistantQuery(String action, String searchQuery,
                                               String extraText, List<String> extraResults) {
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

    public static String extractProviderQuery(Uri uri, String[] selectionArgs) {
        if (selectionArgs != null && selectionArgs.length > 0 && selectionArgs[0] != null) {
            String arg = selectionArgs[0].trim();
            if (!arg.isEmpty()) return arg;
        }
        return extractQueryFromSuggestUri(uri);
    }

    public static String extractQueryFromSuggestUri(Uri uri) {
        if (uri == null) return "";
        String last = uri.getLastPathSegment();
        if (last == null || "search_suggest_query".equals(last)) return "";
        return Uri.decode(last).trim();
    }

    public static MatrixCursor buildSuggestionsCursor(String query, JSONArray items) {
        MatrixCursor cursor = new MatrixCursor(SUGGEST_COLUMNS);
        addDirectAgenticQueryRow(cursor, query);
        if (items == null) return cursor;
        for (int i = 0; i < items.length() && i < 8; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) addItemRowToCursor(cursor, i + 1, item);
        }
        return cursor;
    }

    private static void addDirectAgenticQueryRow(MatrixCursor cursor, String query) {
        if (query == null || query.trim().isEmpty()) return;
        String clean = query.trim();
        cursor.addRow(new Object[]{
            0L, clean, "Aura AI Agentic Search", "", clean, 2026, 0L
        });
    }

    private static void addItemRowToCursor(MatrixCursor cursor, int id, JSONObject item) {
        String title = item.optString("title", "");
        if (title.isEmpty()) return;
        String app = item.optString("app", "Aura AI");
        String poster = item.optString("poster_url", "");
        int year = item.optInt("year", 2024);
        cursor.addRow(new Object[]{(long) id, title, app, poster, title, year, 0L});
    }

    private static void clearConsumedQueryExtras(Intent intent) {
        try {
            intent.removeExtra(SearchManager.QUERY);
            intent.removeExtra(Intent.EXTRA_TEXT);
            intent.removeExtra("aura_voice_query");
            intent.setAction(Intent.ACTION_MAIN);
        } catch (Throwable ignored) {}
    }

    public static boolean isRemoteVoiceOrSearchKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST;
    }

    public static String extractKatnissRecognizedText(String logLine) {
        if (logLine == null) return "";
        if (logLine.contains("state[FINAL_RESULT]")) return extractQuotedField(logLine, "recognized_speech: \"");
        int idx = logLine.indexOf("setFinalRecognizedText(");
        if (idx < 0) return "";
        int start = idx + 23;
        int end = logLine.lastIndexOf(')');
        return (end > start) ? logLine.substring(start, end).trim() : "";
    }

    private static volatile boolean sTtsUnmutedOverride = false;

    public static boolean isKatnissResponseTakeoverLog(String line) {
        if (line == null) return false;
        return isKatnissTextOrTtsTakeover(line) || isKatnissClientOpTakeover(line);
    }

    private static boolean isKatnissTextOrTtsTakeover(String line) {
        return line.contains("Starting the connection") || line.contains("VIEW_ADD_TEXT_START")
            || line.contains("TEXT_FRAGMENT") || line.contains("PreviewTextResponseFragment")
            || line.contains("LIBAS_TTS_PLAYBACK_STARTED") || line.contains("onRespondingStarted");
    }

    private static boolean isKatnissClientOpTakeover(String line) {
        return line.contains("LIBAS_CLIENT_OPS_RECEIVED") || line.contains("Preparing ClientOp:")
            || line.contains("Executing ClientOp:") || line.contains("MEDIA_HANDLER_EXECUTION_PREPARED")
            || line.contains("ASSISTANT_TV_EXECUTE_ACTION") || line.contains("ASSISTANT_TV_INTERACTION_EXECUTION_START");
    }

    public static void silenceAssistantAudio(Context ctx, android.os.Handler ui) {
        if (ctx == null || ui == null || AuraTtsNarrator.isSpeaking()) return;
        sTtsUnmutedOverride = false;
        muteAssistantStreamsNow(ctx, true);
        for (long d : new long[]{700L, 1500L, 2400L, 3300L, 4100L}) {
            ui.postDelayed(() -> { if (!sTtsUnmutedOverride && !AuraTtsNarrator.isSpeaking()) muteAssistantStreamsNow(ctx, true); }, d);
        }
        ui.postDelayed(() -> muteAssistantStreamsNow(ctx, false), 5000L);
    }

    public static void unmuteStreamMusicNow(Context ctx) {
        sTtsUnmutedOverride = true;
        muteAssistantStreamsNow(ctx, false);
    }

    private static void muteAssistantStreamsNow(Context ctx, boolean mute) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int dir = mute ? android.media.AudioManager.ADJUST_MUTE : android.media.AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, 0);
            if (mute) am.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
            else am.abandonAudioFocus(null);
        } catch (Throwable ignored) {}
    }

    private static String extractQuotedField(String line, String marker) {
        int idx = line.indexOf(marker);
        if (idx < 0) return "";
        int start = idx + marker.length();
        int end = line.indexOf('"', start);
        return (end > start) ? line.substring(start, end).trim() : "";
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
