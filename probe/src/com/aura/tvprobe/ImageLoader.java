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
    private static final int RAIL_POSTER_W = 180;
    private static final int RAIL_POSTER_H = 260;
    private static final int HERO_POSTER_W = 380;
    private static final int HERO_POSTER_H = 540;
    private static final int MAX_DISK_FILES = 200;

    // 4 MB byte-measured LruCache; RGB_565 rail posters are ~90 KB each (~45 posters in RAM)
    private static final LruCache<String, Bitmap> MEM_CACHE = new LruCache<String, Bitmap>(4096) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getAllocationByteCount() / 1024);
        }
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
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
        loadInternal(ctx, urlStr, iv, RAIL_POSTER_W, RAIL_POSTER_H, "rail:");
    }

    public static void loadHero(final Context ctx, final String urlStr, final ImageView iv) {
        String hiResUrl = urlStr != null ? urlStr.replace("/s332/", "/s592/") : "";
        loadInternal(ctx, hiResUrl, iv, HERO_POSTER_W, HERO_POSTER_H, "hero:");
    }

    private static void loadInternal(final Context ctx, final String urlStr, final ImageView iv,
                                      final int reqW, final int reqH, final String prefix) {
        if (urlStr == null || urlStr.isEmpty()) {
            iv.setTag(null);
            iv.setImageBitmap(null);
            return;
        }
        final String cacheKey = prefix + urlStr;
        if (cacheKey.equals(iv.getTag()) && iv.getDrawable() != null) return;
        iv.setTag(cacheKey);
        Bitmap cached = MEM_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            iv.animate().cancel();
            iv.setAlpha(1f);
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageBitmap(null);
        POOL.submit(() -> {
            if (!cacheKey.equals(iv.getTag())) return;
            Bitmap bmp = loadFromDiskOrNet(ctx, urlStr, reqW, reqH);
            if (bmp != null) {
                try { bmp.prepareToDraw(); } catch (Throwable ignored) {}
                MEM_CACHE.put(cacheKey, bmp);
                UI.post(() -> {
                    if (cacheKey.equals(iv.getTag())) {
                        iv.setAlpha(0f);
                        iv.setImageBitmap(bmp);
                        iv.animate().alpha(1f).setDuration(150).start();
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
