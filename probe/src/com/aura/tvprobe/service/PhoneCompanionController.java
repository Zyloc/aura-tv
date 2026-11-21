package com.aura.tvprobe;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.view.KeyEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Locale;

/**
 * Modular HTTP handler for Aura TV's Phone Companion routes:
 * - /api/remote (D-pad, Home, Back, Volume, Play/Pause, HDMI 1/2, Phone-to-TV typing)
 * - /api/apps (installed TV streaming apps)
 * - /api/system & /api/system/boost (live RAM/CPU telemetry + 1-tap boost + non-secret config)
 * - /api/rate (quick thumbs-up / thumbs-down taste feedback)
 */
public final class PhoneCompanionController {

    private PhoneCompanionController() {}

    public static String tryHandleRoute(Context ctx, AuraDb db, Uri uri, String path) {
        if ("/api/remote".equals(path)) return handleRemote(ctx, uri);
        if ("/api/apps".equals(path)) return handleApps(ctx);
        if ("/api/system".equals(path)) return handleSystemInfo(ctx, db);
        if ("/api/system/boost".equals(path)) return handleSystemBoost(ctx, db);
        if ("/api/rate".equals(path)) return handleRate(db, uri);
        return null;
    }

    private static String handleRemote(Context ctx, Uri uri) {
        String hdmi = param(uri, "hdmi");
        if (!hdmi.isEmpty()) return openHdmi(ctx, hdmi);
        String text = param(uri, "text");
        if (!text.isEmpty()) return sendPhoneTextToTv(ctx, text);
        String key = param(uri, "key").toUpperCase(Locale.US);
        return dispatchRemoteKey(ctx, key);
    }

    private static String openHdmi(Context ctx, String hdmi) {
        int port = "2".equals(hdmi) ? 2 : 1;
        MediaDeepLinkLauncher.openHdmiInput(ctx, port);
        return "{\"ok\":true,\"hdmi\":" + port + "}";
    }

    private static String sendPhoneTextToTv(Context ctx, String text) {
        AuraActivity.onGlobalVoiceSearchQuery(ctx, text);
        return "{\"ok\":true,\"sent\":true}";
    }

    static String dispatchRemoteKey(Context ctx, String key) {
        if (ctx == null || key.isEmpty()) return "{\"ok\":false}";
        if ("HOME".equals(key)) { AuraActivity.onHardwareHomeKeyPressed(ctx); return "{\"ok\":true}"; }
        if ("VOL_UP".equals(key) || "VOL_DOWN".equals(key) || "MUTE".equals(key)) return adjustVolume(ctx, key);
        if ("PLAY_PAUSE".equals(key)) return sendMediaPlayPause(ctx);
        int code = mapKeyCode(key);
        if (code <= 0) return "{\"ok\":false,\"error\":\"unknown_key\"}";
        boolean ok = AuraActivity.dispatchRemoteKeyFromPhone(code);
        return "{\"ok\":" + ok + ",\"key\":\"" + key + "\"}";
    }

    static int mapKeyCode(String key) {
        if ("UP".equals(key)) return KeyEvent.KEYCODE_DPAD_UP;
        if ("DOWN".equals(key)) return KeyEvent.KEYCODE_DPAD_DOWN;
        if ("LEFT".equals(key)) return KeyEvent.KEYCODE_DPAD_LEFT;
        if ("RIGHT".equals(key)) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if ("OK".equals(key) || "ENTER".equals(key)) return KeyEvent.KEYCODE_DPAD_CENTER;
        return "BACK".equals(key) ? KeyEvent.KEYCODE_BACK : -1;
    }

    private static String adjustVolume(Context ctx, String key) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "{\"ok\":false}";
        int dir = "VOL_UP".equals(key) ? AudioManager.ADJUST_RAISE
            : ("VOL_DOWN".equals(key) ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
        return "{\"ok\":true,\"volume\":\"" + key + "\"}";
    }

    private static String sendMediaPlayPause(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "{\"ok\":false}";
        long now = android.os.SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
        return "{\"ok\":true,\"media\":\"PLAY_PAUSE\"}";
    }

    private static String handleApps(Context ctx) {
        JSONArray arr = new JSONArray();
        List<JSONObject> apps = ctx != null ? AppCatalogProvider.discoverTvApps(ctx) : new java.util.ArrayList<>();
        for (JSONObject a : apps) arr.put(a);
        try { return new JSONObject().put("apps", arr).toString(); } catch (Exception e) { return "{\"apps\":[]}"; }
    }

    private static String handleSystemInfo(Context ctx, AuraDb db) {
        JSONObject snap = SystemResourceMonitor.collectLightweightTelemetry(ctx, db);
        try {
            snap.put("region_country", RegionProviderRegistry.getCountryCode(db));
            snap.put("local_llm_url", db != null ? db.getSetting("local_llm_url", "") : "");
            snap.put("local_llm_model", db != null ? db.getSetting("local_llm_model", "llama3.2") : "llama3.2");
            snap.put("has_gemini_key", db != null && !db.getSetting("gemini_api_key", "").trim().isEmpty());
        } catch (Exception ignored) {}
        return snap.toString();
    }

    private static String handleSystemBoost(Context ctx, AuraDb db) {
        JSONObject res = SystemResourceMonitor.executeRamBoost(ctx, db, "PHONE", true);
        return res != null ? res.toString() : "{\"ok\":true,\"freed_mb\":0}";
    }

    private static String handleRate(AuraDb db, Uri uri) {
        String title = param(uri, "title");
        int rating = parseIntSafe(param(uri, "rating"), 0);
        if (db == null || title.isEmpty() || rating <= 0) return "{\"ok\":false}";
        try {
            JSONObject item = new JSONObject().put("title", title).put("user_rating", rating);
            db.markTitleWatched(item, true);
            return "{\"ok\":true,\"title\":" + JSONObject.quote(title) + ",\"rating\":" + rating + "}";
        } catch (Exception e) { return "{\"ok\":false}"; }
    }

    private static String param(Uri uri, String key) {
        String v = uri != null ? uri.getQueryParameter(key) : null;
        return v != null ? v.trim() : "";
    }

    private static int parseIntSafe(String raw, int fallback) {
        try { return Integer.parseInt(raw); } catch (Exception e) { return fallback; }
    }
}
