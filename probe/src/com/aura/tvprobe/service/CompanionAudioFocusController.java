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

/**
 * Owns exclusive background audio muting and MediaSession pausing while
 * Catch Me Up is loading or speaking so only TTS audio is audible.
 */
public final class CompanionAudioFocusController {

    private static final AudioManager.OnAudioFocusChangeListener FOCUS_LISTENER = change -> {};
    private static volatile boolean sSilenceActive = false;
    private static volatile AudioFocusRequest sActiveFocusRequest;
    private static volatile String sPausedExternalPkg = "";

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
        pauseAllExternalMediaSessions(ctx);
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) requestExclusiveAudioFocus(am);
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

    public static void pauseAllExternalMediaSessions(Context ctx) {
        if (ctx == null) return;
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            ComponentName comp = new ComponentName(ctx, MediaProbeService.class);
            for (MediaController mc : msm.getActiveSessions(comp)) {
                pauseControllerIfActiveExternal(mc);
            }
        } catch (Throwable ignored) {}
    }

    public static void pauseControllerIfActiveExternal(MediaController mc) {
        if (mc == null || isInternalOrTtsPackage(mc.getPackageName())) return;
        PlaybackState st = mc.getPlaybackState();
        if (st != null && isActivePlaybackState(st.getState())) {
            sPausedExternalPkg = mc.getPackageName();
            try { mc.getTransportControls().pause(); } catch (Throwable ignored) {}
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
                am.requestAudioFocus(FOCUS_LISTENER, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
            }
        } catch (Throwable ignored) {}
    }

    private static AudioFocusRequest buildExclusiveFocusRequest() {
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
        return new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
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
