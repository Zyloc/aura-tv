package com.aura.tvprobe;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Native Android TV Voice Narrator bound to Google Speech Services (com.google.android.tts).
 * Synchronizes subtitles word-by-word using hardware PCM frame offsets + AudioManager output latency.
 */
public final class AuraTtsNarrator {

    public interface SubtitleListener {
        void onSentenceStart(String sentence);
        void onWordHighlight(String sentence, int wordStart, int wordEnd);
        void onNarrationStop();
    }

    private static final String TAG = "AuraTTS";
    private static final String GOOGLE_TTS_PKG = "com.google.android.tts";
    private static final long ACOUSTIC_SPEAKER_MARGIN_MS = 140L;
    private static volatile Handler sUiHandler;
    private static volatile TextToSpeech sTts;
    private static volatile boolean sReady = false;
    private static volatile String sSelectedVoiceName = "";
    private static volatile String sPendingSpeech = "";
    private static volatile String sLastSpokenScript = "";
    private static volatile Runnable sOnCompleteCallback;
    private static volatile SubtitleListener sSubtitleListener;
    private static volatile int sSpeechEpoch = 0;
    private static volatile long sOutputLatencyMs = 320L;

    private static List<String> sSentences = new ArrayList<>();
    private static List<List<SubtitleWordTracker.WordToken>> sSentenceTokens = new ArrayList<>();
    private static int[] sSampleRates = new int[0];
    private static long[] sPcmBytes = new long[0];
    private static long[] sUtteranceStartMs = new long[0];
    private static boolean[] sRangeSeen = new boolean[0];
    private static int sDisplayedSentenceIdx = -1;
    private static int sDisplayedWordIdx = -1;

    private AuraTtsNarrator() {}

    private static Handler getMainHandlerSafe() {
        if (sUiHandler != null) return sUiHandler;
        try { sUiHandler = new Handler(Looper.getMainLooper()); } catch (Throwable ignored) {}
        return sUiHandler;
    }

    private static void postDelayedToMain(Runnable r, long delayMs) {
        Handler h = getMainHandlerSafe();
        if (h == null) { if (delayMs <= 0L) r.run(); return; }
        if (delayMs <= 0L) h.post(r);
        else h.postDelayed(r, delayMs);
    }

    private static void clearScheduledUiEvents() {
        Handler h = getMainHandlerSafe();
        if (h != null) h.removeCallbacksAndMessages(null);
    }

    public static void setSubtitleListener(SubtitleListener listener) {
        sSubtitleListener = listener;
    }

    public static String getSelectedVoiceName() {
        return sSelectedVoiceName;
    }

    public static int getActiveWordIndex() {
        return sDisplayedWordIdx;
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
            selectBestSystemVoice();
            sTts.setSpeechRate(0.97f);
            sTts.setPitch(1.0f);
            attachProgressListener();
        } catch (Throwable ignored) {}
    }

    private static void selectBestSystemVoice() {
        try {
            Voice best = pickHighestScoringEnglishVoice(sTts.getVoices());
            if (best != null) {
                sTts.setVoice(best);
                sSelectedVoiceName = best.getName();
                logSafe("Selected system voice=" + best.getName() + " net=" + best.isNetworkConnectionRequired());
            }
        } catch (Throwable ignored) {}
    }

    private static Voice pickHighestScoringEnglishVoice(Set<Voice> voices) {
        if (voices == null || voices.isEmpty()) return null;
        Voice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Voice v : voices) {
            int score = scoreEnglishVoice(v);
            if (score > bestScore) { bestScore = score; best = v; }
        }
        return bestScore > 0 ? best : null;
    }

    public static int scoreEnglishVoice(Voice v) {
        if (v == null || v.getLocale() == null) return -1;
        return scoreVoiceAttributes(
            v.getLocale().getLanguage(), v.getLocale().getCountry(),
            v.getName(), v.getQuality(), v.isNetworkConnectionRequired());
    }

    public static int scoreVoiceAttributes(
            String language, String country, String rawName, int quality, boolean networkRequired) {
        if (language == null || !"en".equalsIgnoreCase(language)) return -1;
        String name = rawName != null ? rawName.toLowerCase(Locale.US) : "";
        int score = quality + (networkRequired ? 600 : 0);
        if (name.contains("lstm-embedded")) score -= 350;
        if (name.contains("wavenet") || name.contains("neural") || name.contains("iog") || name.contains("tpf") || name.contains("iom")) score += 120;
        return score + scoreEnglishCountry(country);
    }

    private static int scoreEnglishCountry(String country) {
        if ("US".equalsIgnoreCase(country)) return 90;
        if ("GB".equalsIgnoreCase(country)) return 75;
        if ("IN".equalsIgnoreCase(country)) return 65;
        return 30;
    }

    private static void attachProgressListener() {
        sTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onBeginSynthesis(String id, int sr, int fmt, int ch) { recordSynthFormat(id, sr); }
            @Override public void onAudioAvailable(String id, byte[] audio) { recordSynthBytes(id, audio); }
            @Override public void onStart(String id) { handleUtteranceStart(id); }
            @Override public void onRangeStart(String id, int start, int end, int frame) { handleUtteranceRange(id, start, end, frame); }
            @Override public void onDone(String id) { handleUtteranceDone(id); }
            @Override public void onError(String id) { handleUtteranceDone(id); }
        });
    }

    private static void recordSynthFormat(String id, int sampleRateHz) {
        int idx = parseUtteranceIndex(id);
        if (idx >= 0 && idx < sSampleRates.length && sampleRateHz > 0) {
            sSampleRates[idx] = sampleRateHz;
        }
    }

    private static void recordSynthBytes(String id, byte[] audio) {
        int idx = parseUtteranceIndex(id);
        if (idx >= 0 && idx < sPcmBytes.length && audio != null) {
            sPcmBytes[idx] += audio.length;
        }
    }

    private static void handleUtteranceStart(String id) {
        int idx = parseUtteranceIndex(id);
        if (idx < 0 || idx >= sUtteranceStartMs.length) return;
        final int epoch = sSpeechEpoch;
        sUtteranceStartMs[idx] = System.currentTimeMillis();
        postDelayedToMain(() -> startFallbackCounterIfNoRanges(epoch, idx), 650L);
    }

    private static void handleUtteranceRange(String id, int start, int end, int frame) {
        int idx = parseUtteranceIndex(id);
        if (idx < 0 || idx >= sSentenceTokens.size()) return;
        sRangeSeen[idx] = true;
        List<SubtitleWordTracker.WordToken> tokens = sSentenceTokens.get(idx);
        int wordIdx = SubtitleWordTracker.resolveWordIndex(tokens, start, end);
        long delayMs = computeAcousticDelayMs(idx, frame);
        final int epoch = sSpeechEpoch;
        postDelayedToMain(() -> renderSyncedWord(epoch, idx, wordIdx, frame), delayMs);
    }

    public static long computeFrameSyncDelayMs(
            long utteranceStartMs, long nowMs, int frame, int sampleRateHz, long outputLatencyMs) {
        int rangeRateHz = resolveRangeFrameRateHz(sampleRateHz);
        long waveformMs = (Math.max(0L, frame) * 1000L) / rangeRateHz;
        long baseStart = utteranceStartMs > 0L ? utteranceStartMs : nowMs;
        long playAtMs = baseStart + waveformMs + Math.max(0L, outputLatencyMs);
        return Math.max(0L, playAtMs - nowMs);
    }

    public static int resolveRangeFrameRateHz(int reportedSampleRateHz) {
        if (reportedSampleRateHz >= 44100) return 22050;
        return reportedSampleRateHz > 0 ? reportedSampleRateHz : 22050;
    }

    private static long computeAcousticDelayMs(int idx, int frame) {
        int sr = idx < sSampleRates.length ? sSampleRates[idx] : 48000;
        long startMs = idx < sUtteranceStartMs.length ? sUtteranceStartMs[idx] : 0L;
        return computeFrameSyncDelayMs(startMs, System.currentTimeMillis(), frame, sr, sOutputLatencyMs);
    }

    private static void renderSyncedWord(int epoch, int sentenceIdx, int wordIdx, int frame) {
        if (epoch != sSpeechEpoch || sentenceIdx >= sSentences.size() || wordIdx < 0) return;
        List<SubtitleWordTracker.WordToken> tokens = sSentenceTokens.get(sentenceIdx);
        if (wordIdx >= tokens.size()) return;
        notifySentenceTransitionIfNeeded(sentenceIdx);
        if (sentenceIdx == sDisplayedSentenceIdx && wordIdx < sDisplayedWordIdx) return;
        sDisplayedWordIdx = wordIdx;
        SubtitleWordTracker.WordToken tok = tokens.get(wordIdx);
        logSafe("SYNC sent=" + sentenceIdx + " word=" + (wordIdx + 1) + "/" + tokens.size() + " '" + tok.text + "' frame=" + frame);
        dispatchWordHighlight(sSentences.get(sentenceIdx), tok.charStart, tok.charEnd);
    }

    private static void notifySentenceTransitionIfNeeded(int sentenceIdx) {
        if (sentenceIdx == sDisplayedSentenceIdx) return;
        sDisplayedSentenceIdx = sentenceIdx;
        sDisplayedWordIdx = -1;
        if (sSubtitleListener != null) {
            sSubtitleListener.onSentenceStart(sSentences.get(sentenceIdx));
        }
    }

    private static void startFallbackCounterIfNoRanges(int epoch, int idx) {
        if (epoch != sSpeechEpoch || idx >= sRangeSeen.length || sRangeSeen[idx]) return;
        List<SubtitleWordTracker.WordToken> tokens = sSentenceTokens.get(idx);
        if (tokens.isEmpty()) return;
        long totalMs = estimateFallbackDurationMs(idx, tokens.size());
        long stepMs = Math.max(220L, totalMs / tokens.size());
        for (int w = 0; w < tokens.size(); w++) {
            final int wordIdx = w;
            postDelayedToMain(() -> renderFallbackWordIfStillNoRanges(epoch, idx, wordIdx), w * stepMs);
        }
    }

    private static void renderFallbackWordIfStillNoRanges(int epoch, int idx, int wordIdx) {
        if (epoch != sSpeechEpoch || idx >= sRangeSeen.length || sRangeSeen[idx]) return;
        renderSyncedWord(epoch, idx, wordIdx, -1);
    }

    private static long estimateFallbackDurationMs(int idx, int wordCount) {
        int sr = idx < sSampleRates.length && sSampleRates[idx] > 0 ? sSampleRates[idx] : 24000;
        long bytes = idx < sPcmBytes.length ? sPcmBytes[idx] : 0L;
        if (bytes > 0L) return (bytes * 1000L) / (sr * 2L);
        return wordCount * 410L;
    }

    private static void handleUtteranceDone(String id) {
        if (id != null && id.equals("utt_" + sSpeechEpoch + "_final")) {
            final int epoch = sSpeechEpoch;
            postDelayedToMain(() -> finishNarrationIfEpochMatches(epoch), sOutputLatencyMs + 180L);
        }
    }

    private static void finishNarrationIfEpochMatches(int epoch) {
        if (epoch != sSpeechEpoch) return;
        finishNarrationUi();
    }

    private static int parseUtteranceIndex(String id) {
        String prefix = "utt_" + sSpeechEpoch + "_";
        if (id == null || !id.startsWith(prefix)) return -1;
        String suffix = id.substring(prefix.length());
        if ("final".equals(suffix)) return sSentences.size() - 1;
        try { return Integer.parseInt(suffix); } catch (Exception ignored) { return -1; }
    }

    public static synchronized void speak(Context ctx, String script, Runnable onComplete) {
        if (script == null || script.trim().isEmpty()) return;
        sSpeechEpoch++;
        clearScheduledUiEvents();
        sOutputLatencyMs = queryAudioOutputLatencyMs(ctx);
        sLastSpokenScript = cleanSpokenText(script);
        sOnCompleteCallback = onComplete;
        prepareSentenceTrackingState(sLastSpokenScript);
        VoiceSearchHelper.unmuteStreamMusicNow(ctx);
        enqueueOrPendSpeech(ctx);
    }

    private static void prepareSentenceTrackingState(String cleanScript) {
        sSentences = splitIntoSentences(cleanScript);
        sSentenceTokens = new ArrayList<>(sSentences.size());
        for (String s : sSentences) sSentenceTokens.add(SubtitleWordTracker.tokenize(s));
        int n = sSentences.size();
        sSampleRates = new int[n];
        sPcmBytes = new long[n];
        sUtteranceStartMs = new long[n];
        sRangeSeen = new boolean[n];
        sDisplayedSentenceIdx = -1;
        sDisplayedWordIdx = -1;
    }

    private static long queryAudioOutputLatencyMs(Context ctx) {
        if (ctx == null) return 320L;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            Method m = AudioManager.class.getMethod("getOutputLatency", int.class);
            int halMs = (Integer) m.invoke(am, AudioManager.STREAM_MUSIC);
            return Math.max(260L, Math.min(580L, halMs + ACOUSTIC_SPEAKER_MARGIN_MS));
        } catch (Throwable ignored) {
            return 320L;
        }
    }

    private static void enqueueOrPendSpeech(Context ctx) {
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
            int ep = sSpeechEpoch;
            Bundle params = buildSynthesisParams();
            for (int i = 0; i < sSentences.size(); i++) {
                String id = "utt_" + ep + "_" + ((i == sSentences.size() - 1) ? "final" : i);
                int mode = (i == 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
                sTts.speak(sSentences.get(i), mode, params, id);
            }
        } catch (Throwable ignored) {}
    }

    private static Bundle buildSynthesisParams() {
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        params.putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "true");
        params.putInt(TextToSpeech.Engine.KEY_FEATURE_NETWORK_TIMEOUT_MS, 5500);
        return params;
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

    private static void dispatchWordHighlight(String sentence, int start, int end) {
        if (sSubtitleListener != null) {
            sSubtitleListener.onWordHighlight(sentence, start, end);
        }
    }

    private static void finishNarrationUi() {
        clearScheduledUiEvents();
        sDisplayedSentenceIdx = -1;
        sDisplayedWordIdx = -1;
        if (sSubtitleListener != null) sSubtitleListener.onNarrationStop();
        if (sOnCompleteCallback != null) sOnCompleteCallback.run();
    }

    public static synchronized boolean toggleSpeakOrStop(Context ctx, Runnable onComplete) {
        if (isSpeaking() || sDisplayedSentenceIdx >= 0) {
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
        sSpeechEpoch++;
        sPendingSpeech = "";
        sDisplayedSentenceIdx = -1;
        sDisplayedWordIdx = -1;
        clearScheduledUiEvents();
        postDelayedToMain(() -> { if (sSubtitleListener != null) sSubtitleListener.onNarrationStop(); }, 0L);
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
        String s = raw.replaceAll("\\[[^\\]]*\\]", " ")
            .replaceAll("(?i)\\bS(\\d{1,2})\\s*E(\\d{1,2})\\b", "Season $1, Episode $2")
            .replaceAll("(?i)\\bPremise:\\s*", "")
            .replace('\u00B7', ',');
        return s.replaceAll("[*#_`\u2726\uD83D\uDD0A\u25B6]", " ").replaceAll("\\s+", " ").trim();
    }

    private static void logSafe(String msg) {
        try { Log.i(TAG, msg); } catch (Throwable ignored) {}
    }
}
