package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.LruCache;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageLoader {
    private static final int RAIL_POSTER_W = 190;
    private static final int RAIL_POSTER_H = 274;
    private static final int HERO_POSTER_W = 460;
    private static final int HERO_POSTER_H = 650;
    private static final int MAX_DISK_FILES = 200;

    private static final LruCache<String, Bitmap> MEM_CACHE = new LruCache<String, Bitmap>(5120) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getAllocationByteCount() / 1024);
        }
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "AuraPosterLoader");
        t.setDaemon(true);
        return t;
    });

    private static final Handler UI = new Handler(Looper.getMainLooper());

    public static void applyRoundedCorners(ImageView iv, final float radiusPx) {
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        iv.setClipToOutline(true);
    }

    public static void trimMemory() {
        MEM_CACHE.evictAll();
    }

    public static void load(final Context ctx, final String urlStr, final ImageView iv) {
        loadInternal(ctx, urlStr, urlStr, iv, RAIL_POSTER_W, RAIL_POSTER_H, "rail:");
    }

    public static void loadHero(final Context ctx, final String urlStr, final ImageView iv) {
        if (urlStr == null || urlStr.isEmpty()) {
            iv.setTag(null);
            iv.setImageBitmap(null);
            return;
        }
        String hiResUrl = urlStr.replace("/s332/", "/s592/");
        String heroKey = "hero:" + hiResUrl;
        Bitmap cachedHero = MEM_CACHE.get(heroKey);
        if (cachedHero != null && !cachedHero.isRecycled()) {
            iv.setTag(heroKey);
            iv.animate().cancel();
            iv.setAlpha(1f);
            iv.setImageBitmap(cachedHero);
            return;
        }
        // Immediately show matching rail poster if in memory so wrong show's image never lingers
        Bitmap cachedRail = MEM_CACHE.get("rail:" + urlStr);
        if (cachedRail != null && !cachedRail.isRecycled()) {
            iv.setImageBitmap(cachedRail);
            iv.setAlpha(0.92f);
        } else {
            iv.setImageBitmap(null);
        }
        loadInternal(ctx, hiResUrl, urlStr, iv, HERO_POSTER_W, HERO_POSTER_H, "hero:");
    }

    private static void loadInternal(final Context ctx, final String primaryUrl, final String fallbackUrl,
                                      final ImageView iv, final int reqW, final int reqH, final String prefix) {
        if (primaryUrl == null || primaryUrl.isEmpty()) {
            iv.setTag(null);
            iv.setImageBitmap(null);
            return;
        }
        final String cacheKey = prefix + primaryUrl;
        if (cacheKey.equals(iv.getTag()) && iv.getDrawable() != null && !"hero:".equals(prefix)) return;
        iv.setTag(cacheKey);
        Bitmap cached = MEM_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            iv.animate().cancel();
            iv.setAlpha(1f);
            iv.setImageBitmap(cached);
            return;
        }
        if (!"hero:".equals(prefix)) {
            iv.setImageBitmap(null);
        }
        POOL.submit(() -> {
            if (!cacheKey.equals(iv.getTag())) return;
            Bitmap bmp = loadFromDiskOrNet(ctx, primaryUrl, reqW, reqH);
            if (bmp == null && fallbackUrl != null && !fallbackUrl.equals(primaryUrl)) {
                bmp = loadFromDiskOrNet(ctx, fallbackUrl, reqW, reqH);
            }
            if (bmp != null) {
                try { bmp.prepareToDraw(); } catch (Throwable ignored) {}
                MEM_CACHE.put(cacheKey, bmp);
                final Bitmap finalBmp = bmp;
                UI.post(() -> {
                    if (cacheKey.equals(iv.getTag())) {
                        iv.setAlpha(0.25f);
                        iv.setImageBitmap(finalBmp);
                        iv.animate().alpha(1f).setDuration(180).start();
                    }
                });
            }
        });
    }

    private static Bitmap loadFromDiskOrNet(Context ctx, String urlStr, int reqW, int reqH) {
        try {
            File dir = new File(ctx.getCacheDir(), "posters_hd");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "poster_hd_" + Math.abs(urlStr.hashCode()) + ".jpg");
            if (f.exists() && f.length() > 400) {
                f.setLastModified(System.currentTimeMillis());
                Bitmap b = decodeCompact(f, reqW, reqH);
                if (b != null) return b;
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(f)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            }
            evictOldCacheFiles(dir);
            return decodeCompact(f, reqW, reqH);
        } catch (Exception e) {
            return null;
        }
    }

    private static void evictOldCacheFiles(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= MAX_DISK_FILES) return;
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int toDelete = files.length - MAX_DISK_FILES;
            for (int i = 0; i < toDelete; i++) {
                files[i].delete();
            }
        } catch (Exception ignored) {}
    }

    private static Bitmap decodeCompact(File f, int reqW, int reqH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while ((bounds.outHeight / (sample * 2)) >= reqH && (bounds.outWidth / (sample * 2)) >= reqW) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap raw = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (raw == null) return null;

            if (raw.getWidth() > reqW + 32) {
                float scale = (float) reqW / (float) raw.getWidth();
                int targetH = Math.max(1, Math.round(raw.getHeight() * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(raw, reqW, targetH, true);
                if (scaled != raw) raw.recycle();
                return scaled;
            }
            return raw;
        } catch (Exception e) {
            return null;
        }
    }
}
