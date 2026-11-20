package com.aura.tvprobe;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

/**
 * Owns reliable background OTT video pausing and exclusive audio focus while
 * Catch Me Up is loading or speaking, with a post-Katniss pause watchdog and
 * auto-resume when narration completes or is cancelled.
 */
public final class CompanionAudioFocusController {

    private static final AudioManager.OnAudioFocusChangeListener FOCUS_LISTENER = change -> {};
    private static volatile boolean sSilenceActive = false;
    private static volatile AudioFocusRequest sActiveFocusRequest;
    private static volatile String sPausedExternalPkg = "";
    private static volatile Handler sMainHandler;

    private CompanionAudioFocusController() {}

    public static boolean isCompanionSilenceActive() {
        return sSilenceActive;
    }

    public static String getLastPausedExternalPkg() {
        return sPausedExternalPkg;
    }

    public static synchronized void engageCompanionSilence(Context ctx) {
        sSilenceActive = true;
        if (ctx == null) return;
        forcePauseAndClaimFocus(ctx);
        schedulePauseWatchdog(ctx);
    }

    public static synchronized void ensureBackgroundPausedBeforeSpeech(Context ctx) {
        if (!sSilenceActive || ctx == null) return;
        forcePauseAndClaimFocus(ctx);
    }

    public static synchronized void releaseCompanionSilence(Context ctx) {
        sSilenceActive = false;
        String resumePkg = sPausedExternalPkg;
        sPausedExternalPkg = "";
        if (ctx == null) return;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) abandonExclusiveAudioFocus(am);
        if (!resumePkg.isEmpty()) resumePausedExternalSession(ctx, resumePkg);
    }

    private static void forcePauseAndClaimFocus(Context ctx) {
        boolean pausedAny = pauseAllExternalMediaSessions(ctx);
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        requestExclusiveAudioFocus(am);
        if (pausedAny || am.isMusicActive()) dispatchHardwarePauseKey(am);
    }

    private static void schedulePauseWatchdog(Context ctx) {
        Handler h = getMainHandlerSafe();
        if (h == null) return;
        final Context appCtx = ctx.getApplicationContext();
        h.postDelayed(() -> { if (sSilenceActive) forcePauseAndClaimFocus(appCtx); }, 600L);
        h.postDelayed(() -> { if (sSilenceActive) forcePauseAndClaimFocus(appCtx); }, 1400L);
    }

    private static Handler getMainHandlerSafe() {
        if (sMainHandler != null) return sMainHandler;
        try { sMainHandler = new Handler(Looper.getMainLooper()); } catch (Throwable ignored) {}
        return sMainHandler;
    }

    public static boolean pauseAllExternalMediaSessions(Context ctx) {
        if (ctx == null) return false;
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            return msm != null && pauseActiveControllers(msm.getActiveSessions(new ComponentName(ctx, MediaProbeService.class)));
        } catch (Throwable ignored) { return false; }
    }

    private static boolean pauseActiveControllers(java.util.List<MediaController> controllers) {
        boolean paused = false;
        for (MediaController mc : controllers) {
            if (pauseControllerIfActiveExternal(mc)) paused = true;
        }
        return paused;
    }

    public static boolean pauseControllerIfActiveExternal(MediaController mc) {
        if (mc == null || isInternalOrTtsPackage(mc.getPackageName())) return false;
        PlaybackState st = mc.getPlaybackState();
        if (st != null && isActivePlaybackState(st.getState())) {
            sPausedExternalPkg = mc.getPackageName();
            try { mc.getTransportControls().pause(); } catch (Throwable ignored) {}
            return true;
        }
        return false;
    }

    public static void dispatchHardwarePauseKey(AudioManager am) {
        if (am == null) return;
        try {
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
        } catch (Throwable ignored) {}
    }

    private static void resumePausedExternalSession(Context ctx, String targetPkg) {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            for (MediaController mc : msm.getActiveSessions(new ComponentName(ctx, MediaProbeService.class))) {
                resumeControllerIfPaused(mc, targetPkg);
            }
        } catch (Throwable ignored) {}
    }

    private static void resumeControllerIfPaused(MediaController mc, String targetPkg) {
        if (mc == null || !targetPkg.equals(mc.getPackageName())) return;
        PlaybackState st = mc.getPlaybackState();
        if (st != null && st.getState() == PlaybackState.STATE_PAUSED) {
            try { mc.getTransportControls().play(); } catch (Throwable ignored) {}
        }
    }

    public static boolean isActivePlaybackState(int state) {
        return state == PlaybackState.STATE_PLAYING
            || state == PlaybackState.STATE_BUFFERING
            || state == PlaybackState.STATE_FAST_FORWARDING
            || state == PlaybackState.STATE_CONNECTING;
    }

    private static boolean isInternalOrTtsPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return "com.aura.tvprobe".equals(pkg) || pkg.startsWith("com.google.android.tts");
    }

    private static void requestExclusiveAudioFocus(AudioManager am) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (sActiveFocusRequest == null) sActiveFocusRequest = buildExclusiveFocusRequest();
                am.requestAudioFocus(sActiveFocusRequest);
            } else {
                am.requestAudioFocus(FOCUS_LISTENER, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Throwable ignored) {}
    }

    private static AudioFocusRequest buildExclusiveFocusRequest() {
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
        return new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(FOCUS_LISTENER)
            .build();
    }

    private static void abandonExclusiveAudioFocus(AudioManager am) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && sActiveFocusRequest != null) {
                am.abandonAudioFocusRequest(sActiveFocusRequest);
            } else {
                am.abandonAudioFocus(FOCUS_LISTENER);
            }
        } catch (Throwable ignored) {}
    }
}
