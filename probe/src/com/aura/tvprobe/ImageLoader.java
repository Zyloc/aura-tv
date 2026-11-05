package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
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
    private static final LruCache<String, Bitmap> MEM_CACHE = new LruCache<>(80);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
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

    public static void load(final Context ctx, final String urlStr, final ImageView iv) {
        iv.setImageBitmap(null);
        if (urlStr == null || urlStr.isEmpty()) return;
        iv.setTag(urlStr);
        Bitmap cached = MEM_CACHE.get(urlStr);
        if (cached != null && !cached.isRecycled()) {
            iv.setImageBitmap(cached);
            return;
        }
        POOL.submit(() -> {
            Bitmap bmp = loadFromDiskOrNet(ctx, urlStr, 300, 450);
            if (bmp != null) {
                MEM_CACHE.put(urlStr, bmp);
                UI.post(() -> {
                    if (urlStr.equals(iv.getTag())) iv.setImageBitmap(bmp);
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
                Bitmap b = decodeSampled(f, reqW, reqH);
                if (b != null) return b;
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(4500);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            }
            return decodeSampled(f, reqW, reqH);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeSampled(File f, int reqW, int reqH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int sample = 1;
            if (bounds.outHeight > reqH || bounds.outWidth > reqW) {
                int halfH = bounds.outHeight / 2;
                int halfW = bounds.outWidth / 2;
                while ((halfH / sample) >= reqH && (halfW / sample) >= reqW) {
                    sample *= 2;
                }
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Exception e) {
            return null;
        }
    }
}
