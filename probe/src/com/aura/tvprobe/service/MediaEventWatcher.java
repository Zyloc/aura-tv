package com.aura.tvprobe;

import android.content.ComponentName;
import android.content.Context;
import android.database.ContentObserver;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Event-driven watcher for Android TV media playback and provider changes.
 * Eliminates periodic polling by subscribing to MediaSessionManager,
 * MediaController.Callback, and ContentObserver events.
 */
public final class MediaEventWatcher implements MediaSessionManager.OnActiveSessionsChangedListener {

    private static final Uri URI_WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final Uri URI_PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final long PROVIDER_DEBOUNCE_MS = 1500L;

    private final Context ctx;
    private final ComponentName component;
    private final AuraDb db;
    private final SessionInspector inspector;
    private final HandlerThread workerThread;
    private final Handler workerHandler;
    private final Map<String, TrackedController> controllers = new HashMap<>();
    private final Runnable debouncedProviderSync = this::executeProviderSync;
    private ContentObserver providerObserver;
    private boolean sessionListenerRegistered = false;
    private boolean providerObserverRegistered = false;
    private volatile boolean katnissWatcherRunning = false;
    private Process katnissLogProcess;

    public MediaEventWatcher(Context ctx, ComponentName component, AuraDb db, SessionInspector inspector) {
        this.ctx = ctx.getApplicationContext();
        this.component = component;
        this.db = db;
        this.inspector = inspector;
        this.workerThread = new HandlerThread("AuraMediaEvents");
        this.workerThread.start();
        this.workerHandler = new Handler(this.workerThread.getLooper());
    }

    public void registerAll() {
        workerHandler.post(() -> {
            registerSessionListenerSafe();
            registerProviderObserversSafe();
            startKatnissVoiceWatcherIfNeeded();
        });
    }

    public void unregisterAll() {
        katnissWatcherRunning = false;
        stopKatnissLogProcessSafe();
        workerHandler.post(() -> {
            unregisterSessionListenerSafe();
            unregisterProviderObserversSafe();
            clearAllControllerCallbacks();
        });
    }

    private void startKatnissVoiceWatcherIfNeeded() {
        if (katnissWatcherRunning) return;
        katnissWatcherRunning = true;
        Thread t = new Thread(this::runKatnissVoiceLogLoop, "AuraKatnissVoiceWatcher");
        t.setDaemon(true);
        t.start();
    }

    private void runKatnissVoiceLogLoop() {
        while (katnissWatcherRunning) {
            streamKatnissTranscriptsOnce();
            try { Thread.sleep(250L); } catch (InterruptedException ignored) {}
        }
    }

    private void streamKatnissTranscriptsOnce() {
        try {
            katnissLogProcess = new ProcessBuilder(
                "logcat", "-T", "1", "-s",
                "katniss_interactor_StreamingTextView:I",
                "katniss_interactor_KatnissConversationStateListenerImpl:I",
                "katniss_search_KatnissIpcConnection:I",
                "katniss_search_PreviewTextResponseFragment:I",
                "katniss_interactor_AppFlowEventHandlerInteractor:I").start();
            readKatnissProcessStream(katnissLogProcess);
        } catch (Throwable ignored) {
        } finally {
            stopKatnissLogProcessSafe();
        }
    }

    private void readKatnissProcessStream(Process proc) throws Exception {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (katnissWatcherRunning && (line = br.readLine()) != null) {
                handleKatnissLogLine(line);
            }
        }
    }

    private void handleKatnissLogLine(String line) {
        String spoken = VoiceSearchHelper.extractKatnissRecognizedText(line);
        if (!spoken.isEmpty()) {
            AuraActivity.onGlobalVoiceSearchQuery(ctx, spoken);
        } else if (VoiceSearchHelper.isKatnissResponseTakeoverLog(line)) {
            AuraActivity.suppressKatnissOverlayNow(ctx);
        }
    }

    private void stopKatnissLogProcessSafe() {
        try {
            if (katnissLogProcess != null) katnissLogProcess.destroy();
        } catch (Throwable ignored) {}
    }

    private void registerSessionListenerSafe() {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            if (!sessionListenerRegistered) {
                msm.addOnActiveSessionsChangedListener(this, component, workerHandler);
                sessionListenerRegistered = true;
            }
            onActiveSessionsChanged(msm.getActiveSessions(component));
        } catch (Throwable ignored) {}
    }

    private void unregisterSessionListenerSafe() {
        try {
            if (!sessionListenerRegistered) return;
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm != null) msm.removeOnActiveSessionsChangedListener(this);
            sessionListenerRegistered = false;
        } catch (Throwable ignored) {}
    }

    private void registerProviderObserversSafe() {
        if (providerObserverRegistered) return;
        try {
            providerObserver = new ContentObserver(workerHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    scheduleDebouncedProviderSync();
                }
            };
            ctx.getContentResolver().registerContentObserver(URI_WATCH_NEXT, true, providerObserver);
            ctx.getContentResolver().registerContentObserver(URI_PREVIEW, true, providerObserver);
            providerObserverRegistered = true;
        } catch (Throwable ignored) {}
    }

    private void unregisterProviderObserversSafe() {
        if (!providerObserverRegistered || providerObserver == null) return;
        try {
            ctx.getContentResolver().unregisterContentObserver(providerObserver);
            providerObserverRegistered = false;
        } catch (Throwable ignored) {}
    }

    private void scheduleDebouncedProviderSync() {
        workerHandler.removeCallbacks(debouncedProviderSync);
        workerHandler.postDelayed(debouncedProviderSync, PROVIDER_DEBOUNCE_MS);
    }

    private void executeProviderSync() {
        try {
            String prof = db.getSetting("active_profile", AuraDb.DEFAULT_PROFILE);
            inspector.forceSyncProvidersAndNowPlaying(prof);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> activeList) {
        if (activeList == null) return;
        for (MediaController mc : activeList) {
            attachControllerIfNeeded(mc);
        }
    }

    private void attachControllerIfNeeded(MediaController mc) {
        if (mc == null || ContinueWatchingSelector.isExcludedPackage(mc.getPackageName())) return;
        String key = mc.getPackageName() + "@" + System.identityHashCode(mc.getSessionToken());
        if (controllers.containsKey(key)) return;
        TrackedController tc = new TrackedController(key, mc);
        controllers.put(key, tc);
        mc.registerCallback(tc, workerHandler);
        tc.captureAndPersistCurrentState();
    }

    private void clearAllControllerCallbacks() {
        for (TrackedController tc : controllers.values()) {
            try { tc.mc.unregisterCallback(tc); } catch (Throwable ignored) {}
        }
        controllers.clear();
    }

    private final class TrackedController extends MediaController.Callback {
        private final String key;
        private final MediaController mc;
        private JSONObject lastValidSnapshot;
        private long playStartElapsedMs = 0L;
        private long playStartPosMs = 0L;

        TrackedController(String key, MediaController mc) {
            this.key = key;
            this.mc = mc;
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            handleStateTransition(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            captureAndPersistCurrentState();
        }

        @Override
        public void onSessionDestroyed() {
            flushFinalPositionOnExit();
            try { mc.unregisterCallback(this); } catch (Throwable ignored) {}
            controllers.remove(key);
        }

        void captureAndPersistCurrentState() {
            JSONObject snap = inspector.serializeControllerSafe(mc);
            if (snap == null) return;
            updatePlayTimerFromState(mc.getPlaybackState(), snap.optLong("position_ms", 0L));
            if (hasMeaningfulMetadata(snap)) lastValidSnapshot = snap;
            if (snap.optLong("position_ms", 0L) >= 60_000L) inspector.persistSerializedSession(snap);
        }

        private void handleStateTransition(PlaybackState state) {
            long livePosMs = resolveTransitionPositionMs(state);
            updatePlayTimerFromState(state, livePosMs);
            JSONObject snap = inspector.serializeControllerSafe(mc);
            if (snap != null && hasMeaningfulMetadata(snap)) lastValidSnapshot = snap;
            persistSnapshotAtPosition(lastValidSnapshot != null ? lastValidSnapshot : snap, livePosMs);
        }

        private long resolveTransitionPositionMs(PlaybackState state) {
            long reported = SessionInspector.computeLivePositionMs(state);
            if (reported >= 60_000L) return reported;
            return extrapolateElapsedPositionMs();
        }

        private void updatePlayTimerFromState(PlaybackState state, long currentPosMs) {
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                playStartElapsedMs = SystemClock.elapsedRealtime();
                playStartPosMs = Math.max(currentPosMs, playStartPosMs);
            } else if (playStartElapsedMs > 0L) {
                playStartPosMs = extrapolateElapsedPositionMs();
                playStartElapsedMs = 0L;
            }
        }

        private long extrapolateElapsedPositionMs() {
            if (playStartElapsedMs <= 0L) return playStartPosMs;
            long delta = Math.max(0L, SystemClock.elapsedRealtime() - playStartElapsedMs);
            return playStartPosMs + Math.min(delta, 14_400_000L);
        }

        private void flushFinalPositionOnExit() {
            long finalPosMs = extrapolateElapsedPositionMs();
            persistSnapshotAtPosition(lastValidSnapshot, finalPosMs);
            scheduleDebouncedProviderSync();
        }

        private void persistSnapshotAtPosition(JSONObject baseSnap, long posMs) {
            if (baseSnap == null || posMs < 60_000L) return;
            try {
                baseSnap.put("position_ms", posMs);
                inspector.persistSerializedSession(baseSnap);
            } catch (Throwable ignored) {}
        }

        private boolean hasMeaningfulMetadata(JSONObject snap) {
            JSONObject meta = snap.optJSONObject("metadata");
            if (meta == null) return false;
            return !meta.optString("android.media.metadata.TITLE").isEmpty()
                || !meta.optString("android.media.metadata.DISPLAY_TITLE").isEmpty()
                || !meta.optString("com.amazon.alexa.externalmediaplayer.metadata.PLAYBACK_SOURCE_ID").isEmpty();
        }
    }
}
