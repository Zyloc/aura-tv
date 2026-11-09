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

/**
 * Ultra-HD 32-bit ARGB_8888 Poster & Hero Backdrop Loader for Aura TV.
 * Supports configurable quality tiers ("ultra", "balanced", "saver") from Aura Settings.
 */
public class ImageLoader {
    private static volatile String sQualityMode = "ultra"; // "ultra" | "balanced" | "saver"

    private static final int ULTRA_RAIL_W = 280;
    private static final int ULTRA_RAIL_H = 396;
    private static final int ULTRA_HERO_W = 960;
    private static final int ULTRA_HERO_H = 540;
    private static final int MAX_DISK_FILES = 180;

    private static final LruCache<String, Bitmap> MEM_CACHE = new LruCache<String, Bitmap>(8192) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getAllocationByteCount() / 1024);
        }
    };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, ImageLoader::createLoaderThread);

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static Thread createLoaderThread(Runnable r) {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "AuraPosterLoader");
        t.setDaemon(true);
        return t;
    }

    public static void setQualityMode(String mode) {
        if (mode != null && !mode.equalsIgnoreCase(sQualityMode)) {
            sQualityMode = mode.toLowerCase();
            MEM_CACHE.evictAll();
        }
    }

    public static String getQualityMode() {
        return sQualityMode;
    }

    public static String upgradePosterUrl(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return "";
        if (urlStr.contains("/backdrop/")) return upgradeBackdropUrl(urlStr);
        if ("saver".equals(sQualityMode)) {
            return urlStr.replace("/s592/", "/s332/").replace("/s718/", "/s332/");
        }
        return urlStr.replace("/s166/", "/s718/").replace("/s332/", "/s718/").replace("/s592/", "/s718/");
    }

    private static String upgradeBackdropUrl(String urlStr) {
        if ("saver".equals(sQualityMode)) {
            return urlStr.replace("/s1440/", "/s640/").replace("/s1920/", "/s640/");
        }
        return urlStr.replace("/s640/", "/s1920/").replace("/s480/", "/s1920/").replace("/s1440/", "/s1920/");
    }

    public static String downgradeFallbackUrl(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return "";
        if (urlStr.contains("/backdrop/")) {
            return urlStr.replace("/s1920/", "/s1440/");
        }
        return urlStr.replace("/s718/", "/s592/");
    }

    public static void applyRoundedCorners(View iv, final float radiusPx) {
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

    public static void clear(final ImageView iv) {
        if (iv == null) return;
        iv.animate().cancel();
        iv.setTag(null);
        iv.setImageBitmap(null);
        iv.setImageDrawable(null);
        iv.setAlpha(1f);
    }

    public static void load(final Context ctx, final String urlStr, final ImageView iv) {
        String hdUrl = upgradePosterUrl(urlStr);
        String fbUrl = downgradeFallbackUrl(hdUrl);
        int w = "saver".equals(sQualityMode) ? 200 : ("balanced".equals(sQualityMode) ? 240 : ULTRA_RAIL_W);
        int h = "saver".equals(sQualityMode) ? 280 : ("balanced".equals(sQualityMode) ? 340 : ULTRA_RAIL_H);
        loadInternal(ctx, hdUrl, fbUrl, iv, w, h, "rail:" + sQualityMode + ":");
    }

    public static void loadHero(final Context ctx, final String urlStr, final ImageView iv) {
        if (urlStr == null || urlStr.isEmpty()) {
            resetImageView(iv);
            return;
        }
        String hiResUrl = upgradePosterUrl(urlStr);
        if (applyCachedHeroIfAvailable(iv, hiResUrl)) return;
        applyCachedRailPreview(iv, hiResUrl);
        loadInternal(ctx, hiResUrl, downgradeFallbackUrl(hiResUrl), iv, ULTRA_HERO_W, ULTRA_HERO_H, "hero:" + sQualityMode + ":");
    }

    private static void resetImageView(ImageView iv) {
        iv.setTag(null);
        iv.setImageBitmap(null);
    }

    private static boolean applyCachedHeroIfAvailable(ImageView iv, String hiResUrl) {
        String heroKey = "hero:" + sQualityMode + ":" + hiResUrl;
        Bitmap cachedHero = MEM_CACHE.get(heroKey);
        if (cachedHero != null && !cachedHero.isRecycled()) {
            iv.setTag(heroKey);
            iv.setImageBitmap(cachedHero);
            return true;
        }
        return false;
    }

    private static void applyCachedRailPreview(ImageView iv, String hiResUrl) {
        Bitmap cachedRail = MEM_CACHE.get("rail:" + sQualityMode + ":" + hiResUrl);
        iv.setImageBitmap((cachedRail != null && !cachedRail.isRecycled()) ? cachedRail : null);
    }

    private static void loadInternal(final Context ctx, final String primaryUrl, final String fallbackUrl,
                                     final ImageView iv, final int reqW, final int reqH, final String prefix) {
        if (primaryUrl == null || primaryUrl.isEmpty()) {
            resetImageView(iv);
            return;
        }
        final String cacheKey = prefix + primaryUrl;
        if (cacheKey.equals(iv.getTag()) && iv.getDrawable() != null && !prefix.startsWith("hero:")) return;
        iv.setTag(cacheKey);
        if (applyMemCacheHit(iv, cacheKey, prefix)) return;
        if (!prefix.startsWith("hero:")) iv.setImageBitmap(null);
        POOL.submit(() -> executeAsyncLoad(ctx, primaryUrl, fallbackUrl, iv, reqW, reqH, prefix, cacheKey));
    }

    private static boolean applyMemCacheHit(ImageView iv, String cacheKey, String prefix) {
        Bitmap cached = MEM_CACHE.get(cacheKey);
        if (cached == null || cached.isRecycled()) return false;
        if (!prefix.startsWith("hero:")) {
            iv.animate().cancel();
            iv.setAlpha(1f);
        }
        iv.setImageBitmap(cached);
        return true;
    }

    private static void executeAsyncLoad(Context ctx, String primaryUrl, String fallbackUrl,
                                         ImageView iv, int reqW, int reqH, String prefix, String cacheKey) {
        if (!cacheKey.equals(iv.getTag())) return;
        Bitmap bmp = loadWithFallback(ctx, primaryUrl, fallbackUrl, reqW, reqH);
        if (bmp == null) return;
        try { bmp.prepareToDraw(); } catch (Throwable ignored) {}
        MEM_CACHE.put(cacheKey, bmp);
        UI.post(() -> deliverLoadedBitmap(iv, cacheKey, prefix, bmp));
    }

    private static Bitmap loadWithFallback(Context ctx, String primaryUrl, String fallbackUrl, int reqW, int reqH) {
        Bitmap bmp = loadFromDiskOrNet(ctx, primaryUrl, reqW, reqH);
        if (bmp == null && fallbackUrl != null && !fallbackUrl.equals(primaryUrl)) {
            bmp = loadFromDiskOrNet(ctx, fallbackUrl, reqW, reqH);
        }
        return bmp;
    }

    private static void deliverLoadedBitmap(ImageView iv, String cacheKey, String prefix, Bitmap bmp) {
        if (!cacheKey.equals(iv.getTag())) return;
        if (!prefix.startsWith("hero:")) {
            iv.setAlpha(0.25f);
            iv.setImageBitmap(bmp);
            iv.animate().alpha(1f).setDuration(220).start();
        } else {
            iv.setImageBitmap(bmp);
        }
    }

    private static Bitmap loadFromDiskOrNet(Context ctx, String urlStr, int reqW, int reqH) {
        try {
            File dir = getOrCreateCacheDir(ctx);
            File f = new File(dir, "poster_uhd_" + Math.abs(urlStr.hashCode()) + ".jpg");
            Bitmap diskBmp = loadFromDiskIfValid(f, reqW, reqH);
            return diskBmp != null ? diskBmp : fetchAndDecode(dir, f, urlStr, reqW, reqH);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap fetchAndDecode(File dir, File f, String urlStr, int reqW, int reqH) throws Exception {
        if (!downloadToFile(urlStr, f)) return null;
        evictOldCacheFiles(dir);
        return decodeCrisp(f, reqW, reqH);
    }

    private static File getOrCreateCacheDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "posters_uhd_v3");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static Bitmap loadFromDiskIfValid(File f, int reqW, int reqH) {
        if (f.exists() && f.length() > 600) {
            f.setLastModified(System.currentTimeMillis());
            return decodeCrisp(f, reqW, reqH);
        }
        return null;
    }

    private static boolean downloadToFile(String urlStr, File f) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(4500);
        conn.setReadTimeout(4500);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) return false;
        copyConnectionStream(conn, f);
        return true;
    }

    private static void copyConnectionStream(HttpURLConnection conn, File f) throws Exception {
        try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
        }
    }

    private static void evictOldCacheFiles(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= MAX_DISK_FILES) return;
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < files.length - MAX_DISK_FILES; i++) {
                files[i].delete();
            }
        } catch (Exception ignored) {}
    }

    private static Bitmap decodeCrisp(File f, int reqW, int reqH) {
        try {
            BitmapFactory.Options bounds = readImageBounds(f);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = computeSampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH);
            Bitmap raw = decodeWithSample(f, sample);
            return scaleIfOversized(raw, reqW);
        } catch (Exception e) {
            return null;
        }
    }

    private static BitmapFactory.Options readImageBounds(File f) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        return bounds;
    }

    private static int computeSampleSize(int outW, int outH, int reqW, int reqH) {
        int sample = 1;
        while ((outH / (sample * 2)) >= reqH && (outW / (sample * 2)) >= reqW) {
            sample *= 2;
        }
        return sample;
    }

    // Use full 32-bit ARGB_8888 in Ultra/Balanced modes for zero color banding and razor-sharp text
    private static Bitmap decodeWithSample(File f, int sample) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = "saver".equals(sQualityMode)
            ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        opts.inScaled = false;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }

    private static Bitmap scaleIfOversized(Bitmap raw, int reqW) {
        if (raw == null || raw.getWidth() <= reqW + 64) return raw;
        float scale = (float) reqW / (float) raw.getWidth();
        int targetH = Math.max(1, Math.round(raw.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(raw, reqW, targetH, true);
        if (scaled != raw) raw.recycle();
        return scaled;
    }
}
