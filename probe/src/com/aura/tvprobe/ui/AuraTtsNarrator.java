package com.aura.tvprobe;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;

/**
 * Native Android TV Neural Voice Narrator bound to Google Speech Services (com.google.android.tts).
 * Streams sentence-by-sentence spoken summaries over the TV speakers with audio focus management.
 */
public final class AuraTtsNarrator {

    private static final String GOOGLE_TTS_PKG = "com.google.android.tts";
    private static volatile TextToSpeech sTts;
    private static volatile boolean sReady = false;
    private static volatile String sPendingSpeech = "";
    private static volatile String sLastSpokenScript = "";
    private static volatile Runnable sOnCompleteCallback;

    private AuraTtsNarrator() {}

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
            @Override public void onStart(String utteranceId) {}
            @Override public void onDone(String utteranceId) {
                if ("utt_final".equals(utteranceId) && sOnCompleteCallback != null) {
                    sOnCompleteCallback.run();
                }
            }
            @Override public void onError(String utteranceId) {}
        });
    }

    public static synchronized void speak(Context ctx, String script, Runnable onComplete) {
        if (script == null || script.trim().isEmpty()) return;
        sLastSpokenScript = cleanSpokenText(script);
        sOnCompleteCallback = onComplete;
        warmup(ctx);
        if (!sReady || sTts == null) {
            sPendingSpeech = sLastSpokenScript;
            return;
        }
        requestAudioFocusSafe(ctx);
        enqueueSentences(sLastSpokenScript);
    }

    private static void enqueueSentences(String cleanScript) {
        try {
            sTts.stop();
            String[] sentences = cleanScript.split("(?<=[.!?])\\s+");
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
            for (int i = 0; i < sentences.length; i++) {
                String s = sentences[i].trim();
                if (s.isEmpty()) continue;
                String id = (i == sentences.length - 1) ? "utt_final" : ("utt_" + i);
                int mode = (i == 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
                sTts.speak(s, mode, params, id);
            }
        } catch (Throwable ignored) {}
    }

    public static synchronized boolean toggleSpeakOrStop(Context ctx, Runnable onComplete) {
        if (isSpeaking()) {
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
        return raw.replaceAll("[*#_`✦🔊▶]", " ").replaceAll("\\s+", " ").trim();
    }
}
