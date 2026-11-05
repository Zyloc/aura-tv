package com.aura.tvprobe;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

public class TvChannelPublisher {
    private final Context ctx;

    public TvChannelPublisher(Context ctx) {
        this.ctx = ctx;
    }

    public void publishRecommendations(JSONArray recos) {
        if (recos == null || recos.length() == 0) return;
        try {
            long channelId = ensureAuraChannel();
            if (channelId <= 0) return;

            Uri previewUri = Uri.parse("content://android.media.tv/preview_program");
            ctx.getContentResolver().delete(previewUri, "channel_id = ?", new String[]{String.valueOf(channelId)});

            for (int i = 0; i < recos.length(); i++) {
                JSONObject r = recos.optJSONObject(i);
                if (r == null) continue;
                insertPreviewProgram(previewUri, channelId, r, recos.length() - i);
            }
        } catch (Exception ignored) {}
    }

    private long ensureAuraChannel() {
        Uri chUri = TvContract.Channels.CONTENT_URI;
        try (Cursor c = ctx.getContentResolver().query(chUri, null, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String pkg = c.getString(c.getColumnIndexOrThrow("package_name"));
                    if (ctx.getPackageName().equals(pkg)) {
                        return c.getLong(c.getColumnIndexOrThrow("_id"));
                    }
                }
            }
        } catch (Exception ignored) {}

        ContentValues cv = new ContentValues();
        cv.put(TvContract.Channels.COLUMN_DISPLAY_NAME, "✨ Aura AI · Watch Next");
        cv.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
        cv.put("browsable", 1);
        Intent appIntent = new Intent(ctx, AuraActivity.class);
        cv.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, appIntent.toUri(Intent.URI_INTENT_SCHEME));
        Uri inserted = ctx.getContentResolver().insert(chUri, cv);
        long id = inserted != null ? ContentUris.parseId(inserted) : -1;
        if (id > 0) {
            try { TvContract.requestChannelBrowsable(ctx, id); } catch (Exception ignored) {}
        }
        return id;
    }

    private void insertPreviewProgram(Uri previewUri, long channelId, JSONObject r, int weight) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("channel_id", channelId);
            cv.put("type", 0);
            cv.put("title", r.optString("title"));
            cv.put("short_description", "[" + r.optString("app") + "] " + r.optString("reason"));
            String poster = r.optString("poster_url");
            if (!poster.isEmpty()) {
                cv.put("poster_art_uri", poster);
                cv.put("poster_art_aspect_ratio", 3);
            }
            String launchUri = r.optString("launch_uri");
            if (!launchUri.isEmpty()) {
                Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(launchUri));
                String pkg = r.optString("launch_pkg");
                if (!pkg.isEmpty()) viewIntent.setPackage(pkg);
                cv.put("intent_uri", viewIntent.toUri(Intent.URI_INTENT_SCHEME));
            }
            cv.put("weight", weight);
            cv.put("browsable", 1);
            ctx.getContentResolver().insert(previewUri, cv);
        } catch (Exception ignored) {}
    }
}
