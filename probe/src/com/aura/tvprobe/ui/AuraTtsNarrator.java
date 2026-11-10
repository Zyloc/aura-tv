package com.aura.tvprobe;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Native Android TV Neural Voice Narrator bound to Google Speech Services (com.google.android.tts).
 * Streams sentence-by-sentence spoken summaries with synchronized word-by-word live subtitle callbacks.
 */
public final class AuraTtsNarrator {

    public interface SubtitleListener {
        void onSentenceStart(String sentence);
        void onWordHighlight(String sentence, int wordStart, int wordEnd);
        void onNarrationStop();
    }

    private static final String GOOGLE_TTS_PKG = "com.google.android.tts";
    private static volatile Handler sUiHandler;
    private static volatile TextToSpeech sTts;
    private static volatile boolean sReady = false;
    private static volatile String sPendingSpeech = "";
    private static volatile String sLastSpokenScript = "";
    private static volatile Runnable sOnCompleteCallback;
    private static volatile SubtitleListener sSubtitleListener;
    private static List<String> sSentences = new ArrayList<>();
    private static int sActiveIdx = -1;
    private static long sSentenceStartMs = 0L;
    private static boolean sNativeRangeActive = false;

    private static final Runnable WORD_TICKER = AuraTtsNarrator::tickWordHighlight;

    private AuraTtsNarrator() {}

    private static Handler getMainHandlerSafe() {
        if (sUiHandler != null) return sUiHandler;
        try { sUiHandler = new Handler(Looper.getMainLooper()); } catch (Throwable ignored) {}
        return sUiHandler;
    }

    private static void postToMain(Runnable r) {
        Handler h = getMainHandlerSafe();
        if (h != null) h.post(r);
        else r.run();
    }

    private static void postDelayedToMain(Runnable r, long delayMs) {
        Handler h = getMainHandlerSafe();
        if (h != null) h.postDelayed(r, delayMs);
    }

    private static void removeMainCallbacks(Runnable r) {
        Handler h = getMainHandlerSafe();
        if (h != null) h.removeCallbacks(r);
    }

    public static void setSubtitleListener(SubtitleListener listener) {
        sSubtitleListener = listener;
    }

    public static synchronized void warmup(Context ctx) {
        if (sTts != null || ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        try {
            sTts = new TextToSpeech(appCtx, status -> onTtsInit(appCtx, status), GOOGLE_TTS_PKG);
        } catch (Throwable t) {
            initFallbackTts(appCtx);
        }
    }

    private static void initFallbackTts(Context appCtx) {
        try {
            sTts = new TextToSpeech(appCtx, status -> onTtsInit(appCtx, status));
        } catch (Throwable ignored) {}
    }

    private static void onTtsInit(Context appCtx, int status) {
        if (status != TextToSpeech.SUCCESS || sTts == null) return;
        configureTtsVoice();
        sReady = true;
        flushPendingSpeechIfAny(appCtx);
    }

    private static void flushPendingSpeechIfAny(Context appCtx) {
        if (sPendingSpeech != null && !sPendingSpeech.isEmpty()) {
            String pending = sPendingSpeech;
            sPendingSpeech = "";
            speak(appCtx, pending, sOnCompleteCallback);
        }
    }

    private static void configureTtsVoice() {
        try {
            sTts.setLanguage(Locale.US);
            sTts.setSpeechRate(0.98f);
            sTts.setPitch(1.0f);
            attachProgressListener();
        } catch (Throwable ignored) {}
    }

    private static void attachProgressListener() {
        sTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { handleUtteranceStart(id); }
            @Override public void onDone(String id) { handleUtteranceDone(id); }
            @Override public void onError(String id) { handleUtteranceDone(id); }
            @Override public void onRangeStart(String id, int start, int end, int frame) {
                handleUtteranceRange(id, start, end);
            }
        });
    }

    private static void handleUtteranceStart(String id) {
        int idx = parseUtteranceIndex(id);
        if (idx >= 0) {
            postToMain(() -> activateSentenceSubtitle(idx));
        }
    }

    private static void handleUtteranceRange(String id, int start, int end) {
        int idx = parseUtteranceIndex(id);
        if (idx < 0 || idx >= sSentences.size()) return;
        sNativeRangeActive = true;
        String sentence = sSentences.get(idx);
        postToMain(() -> dispatchWordHighlight(sentence, start, end));
    }

    private static void handleUtteranceDone(String id) {
        if ("utt_final".equals(id)) {
            postToMain(AuraTtsNarrator::finishNarrationUi);
        }
    }

    private static int parseUtteranceIndex(String id) {
        if (id == null) return -1;
        if ("utt_final".equals(id)) return sSentences.size() - 1;
        if (id.startsWith("utt_")) {
            try { return Integer.parseInt(id.substring(4)); } catch (Exception ignored) {}
        }
        return -1;
    }

    public static synchronized void speak(Context ctx, String script, Runnable onComplete) {
        if (script == null || script.trim().isEmpty()) return;
        sLastSpokenScript = cleanSpokenText(script);
        sOnCompleteCallback = onComplete;
        sSentences = splitIntoSentences(sLastSpokenScript);
        VoiceSearchHelper.unmuteStreamMusicNow(ctx);
        postToMain(() -> activateSentenceSubtitle(0));
        warmup(ctx);
        if (!sReady || sTts == null) {
            sPendingSpeech = sLastSpokenScript;
            return;
        }
        requestAudioFocusSafe(ctx);
        enqueueSentences();
    }

    public static List<String> splitIntoSentences(String cleanScript) {
        List<String> list = new ArrayList<>();
        if (cleanScript == null || cleanScript.isEmpty()) return list;
        for (String s : cleanScript.split("(?<=[.!?])\\s+")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) list.add(trimmed);
        }
        return list;
    }

    private static void enqueueSentences() {
        try {
            sTts.stop();
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
            for (int i = 0; i < sSentences.size(); i++) {
                String id = (i == sSentences.size() - 1) ? "utt_final" : ("utt_" + i);
                int mode = (i == 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
                sTts.speak(sSentences.get(i), mode, params, id);
            }
        } catch (Throwable ignored) {}
    }

    private static void activateSentenceSubtitle(int idx) {
        if (idx < 0 || idx >= sSentences.size()) return;
        if (sActiveIdx == idx && (System.currentTimeMillis() - sSentenceStartMs) < 400L) return;
        sActiveIdx = idx;
        sSentenceStartMs = System.currentTimeMillis();
        sNativeRangeActive = false;
        String sentence = sSentences.get(idx);
        if (sSubtitleListener != null) sSubtitleListener.onSentenceStart(sentence);
        removeMainCallbacks(WORD_TICKER);
        postDelayedToMain(WORD_TICKER, 65L);
    }

    private static void tickWordHighlight() {
        if (sActiveIdx < 0 || sActiveIdx >= sSentences.size()) return;
        String sentence = sSentences.get(sActiveIdx);
        long elapsed = System.currentTimeMillis() - sSentenceStartMs;
        long estDuration = Math.max(1400L, sentence.length() * 62L);
        if (!sNativeRangeActive) {
            updateEstimatedWordSpan(sentence, elapsed, estDuration);
        }
        if (elapsed >= estDuration + 350L) {
            advanceFallbackSentenceIfNeeded();
        } else {
            postDelayedToMain(WORD_TICKER, 65L);
        }
    }

    private static void updateEstimatedWordSpan(String sentence, long elapsed, long estDuration) {
        int len = sentence.length();
        int targetChar = (int) Math.min(len - 1, (elapsed * len) / Math.max(1L, estDuration));
        int[] bounds = findWordBoundsAt(sentence, targetChar);
        dispatchWordHighlight(sentence, bounds[0], bounds[1]);
    }

    public static int[] findWordBoundsAt(String s, int pos) {
        if (s == null || s.isEmpty()) return new int[]{0, 0};
        int p = Math.max(0, Math.min(pos, s.length() - 1));
        while (p > 0 && Character.isWhitespace(s.charAt(p))) p--;
        int start = p;
        while (start > 0 && !Character.isWhitespace(s.charAt(start - 1))) start--;
        int end = p;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end))) end++;
        return new int[]{start, end};
    }

    private static void advanceFallbackSentenceIfNeeded() {
        if (sActiveIdx + 1 < sSentences.size()) {
            activateSentenceSubtitle(sActiveIdx + 1);
        } else if (!isSpeaking()) {
            finishNarrationUi();
        }
    }

    private static void dispatchWordHighlight(String sentence, int start, int end) {
        if (sSubtitleListener != null) {
            sSubtitleListener.onWordHighlight(sentence, start, end);
        }
    }

    private static void finishNarrationUi() {
        removeMainCallbacks(WORD_TICKER);
        sActiveIdx = -1;
        if (sSubtitleListener != null) sSubtitleListener.onNarrationStop();
        if (sOnCompleteCallback != null) sOnCompleteCallback.run();
    }

    public static synchronized boolean toggleSpeakOrStop(Context ctx, Runnable onComplete) {
        if (isSpeaking() || sActiveIdx >= 0) {
            stop(ctx);
            return false;
        }
        if (!sLastSpokenScript.isEmpty()) {
            speak(ctx, sLastSpokenScript, onComplete);
            return true;
        }
        return false;
    }

    public static synchronized boolean isSpeaking() {
        try {
            return sReady && sTts != null && sTts.isSpeaking();
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized void stop(Context ctx) {
        sPendingSpeech = "";
        sActiveIdx = -1;
        removeMainCallbacks(WORD_TICKER);
        postToMain(() -> { if (sSubtitleListener != null) sSubtitleListener.onNarrationStop(); });
        try {
            if (sTts != null) sTts.stop();
        } catch (Throwable ignored) {}
        abandonAudioFocusSafe(ctx);
    }

    private static void requestAudioFocusSafe(Context ctx) {
        if (ctx == null) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Throwable ignored) {}
    }

    private static void abandonAudioFocusSafe(Context ctx) {
        if (ctx == null) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.abandonAudioFocus(null);
        } catch (Throwable ignored) {}
    }

    public static String cleanSpokenText(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[*#_`\u2726\uD83D\uDD0A\u25B6]", " ").replaceAll("\\s+", " ").trim();
    }
}
