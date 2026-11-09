package com.aura.tvprobe;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

public class TvChannelPublisher {
    private static final Uri CHANNELS_URI = Uri.parse("content://android.media.tv/channel");
    private static final Uri PREVIEW_PROGRAMS_URI = Uri.parse("content://android.media.tv/preview_program");
    private final Context ctx;
    private final AuraDb db;

    public TvChannelPublisher(Context ctx) {
        this(ctx, AuraDb.getInstance(ctx));
    }

    public TvChannelPublisher(Context ctx, AuraDb db) {
        this.ctx = ctx.getApplicationContext();
        this.db = db;
    }

    public long ensureOrCreateChannel() {
        long cached = parseCachedChannelId();
        if (cached > 0 && verifyChannelExists(cached)) return cached;
        long existing = findExistingAuraChannel();
        if (existing > 0) return saveAndReturnChannelId(existing);
        return createNewAuraChannel();
    }

    private long parseCachedChannelId() {
        try {
            return Long.parseLong(db.getSetting("tv_channel_id", "-1"));
        } catch (Exception e) {
            return -1L;
        }
    }

    private long findExistingAuraChannel() {
        try (Cursor c = ctx.getContentResolver().query(
                CHANNELS_URI, new String[]{"_id", "display_name"}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if ("Aura For You".equalsIgnoreCase(c.getString(1))) return c.getLong(0);
            }
        } catch (Exception ignored) {}
        return -1L;
    }

    private long createNewAuraChannel() {
        try {
            Uri inserted = ctx.getContentResolver().insert(CHANNELS_URI, buildChannelValues());
            if (inserted == null) return -1L;
            long id = ContentUris.parseId(inserted);
            requestBrowsableSafe(id);
            return saveAndReturnChannelId(id);
        } catch (Exception e) {
            return -1L;
        }
    }

    private ContentValues buildChannelValues() {
        ContentValues cv = new ContentValues();
        cv.put("package_name", ctx.getPackageName());
        cv.put(" display_name".trim(), "Aura For You");
        cv.put("description", "Personalized AI recommendations across your streaming apps");
        cv.put("type", "TYPE_PREVIEW");
        cv.put("service_type", "SERVICE_TYPE_AUDIO_VIDEO");
        cv.put("app_link_intent_uri", "intent:#Intent;component=com.aura.tvprobe/.AuraActivity;end");
        return cv;
    }

    private void requestBrowsableSafe(long channelId) {
        try {
            TvContract.requestChannelBrowsable(ctx, channelId);
        } catch (Throwable ignored) {}
    }

    private long saveAndReturnChannelId(long id) {
        db.setSetting("tv_channel_id", String.valueOf(id));
        return id;
    }

    private boolean verifyChannelExists(long id) {
        try (Cursor c = ctx.getContentResolver().query(
                ContentUris.withAppendedId(CHANNELS_URI, id), new String[]{"_id"}, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    public void publishRecommendations(JSONArray recos) {
        if (recos == null || recos.length() == 0) return;
        try {
            long channelId = ensureOrCreateChannel();
            if (channelId <= 0) return;
            ContentResolver cr = ctx.getContentResolver();
            cr.delete(PREVIEW_PROGRAMS_URI, "channel_id = ?", new String[]{String.valueOf(channelId)});
            insertPreviewPrograms(cr, channelId, recos);
        } catch (Exception ignored) {}
    }

    private void insertPreviewPrograms(ContentResolver cr, long channelId, JSONArray recos) {
        for (int i = 0; i < recos.length() && i < 10; i++) {
            JSONObject r = recos.optJSONObject(i);
            if (r != null) {
                cr.insert(PREVIEW_PROGRAMS_URI, buildProgramValues(channelId, r, i));
            }
        }
    }

    private ContentValues buildProgramValues(long channelId, JSONObject r, int index) {
        String title = r.optString("title", "Recommended");
        String app = r.optString("app", r.optString("badge", "Streaming"));
        ContentValues cv = new ContentValues();
        cv.put("channel_id", channelId);
        cv.put("type", 0);
        cv.put("title", title + " · " + app);
        cv.put("short_description", r.optString("reason", r.optString("synopsis", "")));
        cv.put("poster_art_aspect_ratio", 3);
        cv.put("weight", 100 - index);
        return populateProgramArtworkAndIntent(cv, r);
    }

    private ContentValues populateProgramArtworkAndIntent(ContentValues cv, JSONObject r) {
        String poster = r.optString("poster_url", "");
        String uri = r.optString("launch_uri", "");
        if (!poster.isEmpty()) cv.put("poster_art_uri", poster);
        cv.put("intent_uri", !uri.isEmpty()
            ? uri
            : "intent:#Intent;component=com.aura.tvprobe/.AuraActivity;end");
        return cv;
    }
}
