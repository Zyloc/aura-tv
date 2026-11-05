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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageLoader {
    // Target thumbnail dimensions: 168x252 RGB_565 = ~84 KB per poster!
    // 14 posters in memory = ~1.15 MB total RAM.
    private static final int POSTER_W = 168;
    private static final int POSTER_H = 252;

    // Strict 2 MB (2048 KB) byte-measured LruCache so bitmap heap stays under 2 MB
    private static final LruCache<String, Bitmap> MEM_CACHE = new LruCache<String, Bitmap>(2048) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getAllocationByteCount() / 1024);
        }
    };

    // 2 low-priority background threads so image decode NEVER starves UI / RenderThread
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
        if (urlStr == null || urlStr.isEmpty()) {
            iv.setTag(null);
            iv.setImageBitmap(null);
            return;
        }
        if (urlStr.equals(iv.getTag()) && iv.getDrawable() != null) {
            return;
        }
        iv.setTag(urlStr);
        Bitmap cached = MEM_CACHE.get(urlStr);
        if (cached != null && !cached.isRecycled()) {
            iv.animate().cancel();
            iv.setAlpha(1f);
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageBitmap(null);
        POOL.submit(() -> {
            if (!urlStr.equals(iv.getTag())) return;
            Bitmap bmp = loadFromDiskOrNet(ctx, urlStr, POSTER_W, POSTER_H);
            if (bmp != null) {
                try { bmp.prepareToDraw(); } catch ( Throwable ignored) {}
                MEM_CACHE.put(urlStr, bmp);
                UI.post(() -> {
                    if (urlStr.equals(iv.getTag())) {
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
            File dir = new File(ctx.getCacheDir(), "posters");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "img_" + Math.abs(urlStr.hashCode()) + ".jpg");
            if (f.exists() && f.length() > 400) {
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
            return decodeCompact(f, reqW, reqH);
        } catch (Exception e) {
            return null;
        }
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

            if (raw.getWidth() > reqW + 24 || raw.getHeight() > reqH + 36) {
                Bitmap scaled = Bitmap.createScaledBitmap(raw, reqW, reqH, true);
                if (scaled != raw) {
                    raw.recycle();
                }
                return scaled;
            }
            return raw;
        } catch (Exception e) {
            return null;
        }
    }
}
