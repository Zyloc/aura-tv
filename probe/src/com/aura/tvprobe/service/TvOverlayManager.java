package com.aura.tvprobe;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class TvOverlayManager {
    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private View currentBanner;

    public TvOverlayManager(Context ctx) {
        this.ctx = ctx;
    }

    public void showUpNextCard(final String badge, final String title, final String reason, final int durationSec) {
        mainHandler.post(() -> renderOverlay(badge, title, reason, durationSec));
    }

    private void renderOverlay(String badge, String title, String reason, int durationSec) {
        dismissOverlay();
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            LinearLayout card = buildCardLayout(badge, title, reason);
            WindowManager.LayoutParams lp = buildLayoutParams();
            wm.addView(card, lp);
            currentBanner = card;
            mainHandler.postDelayed(this::dismissOverlay, Math.max(3, durationSec) * 1000L);
        } catch (Exception ignored) {}
    }

    private LinearLayout buildCardLayout(String badge, String title, String reason) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(36, 28, 36, 28);
        box.setBackground(buildCardBackground());
        box.addView(makeText(badge.toUpperCase(), 11f, "#A599FF", true));
        box.addView(makeText(title, 18f, "#FFFFFF", true));
        if (reason != null && !reason.isEmpty()) {
            box.addView(makeText(reason, 13f, "#B0B0C0", false));
        }
        return box;
    }

    private GradientDrawable buildCardBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6141418"));
        bg.setCornerRadius(24f);
        bg.setStroke(2, Color.parseColor("#7C6EF7"));
        return bg;
    }

    private TextView makeText(String txt, float sizeSp, String hexColor, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(txt);
        tv.setTextSize(sizeSp);
        tv.setTextColor(Color.parseColor(hexColor));
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 4, 0, 4);
        return tv;
    }

    private WindowManager.LayoutParams buildLayoutParams() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            680, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.x = 48;
        lp.y = 48;
        return lp;
    }

    public void dismissOverlay() {
        if (currentBanner != null) {
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(currentBanner);
            } catch (Exception ignored) {}
            currentBanner = null;
        }
    }

    public boolean launchDeepLink(String uriStr, String pkg) {
        return launchNativeStreamingApp(ctx, uriStr, pkg, "");
    }

    public static boolean launchNativeStreamingApp(Context ctx, String uriStr, String pkgOrApp, String title) {
        String pkg = resolveStreamingPackage(pkgOrApp);
        // 1. If we have a real TvProvider intent: URI for this exact app, launch it
        if (tryLaunchIntentSchemeUri(ctx, uriStr)) return true;
        // 2. If we have a target streaming app package (Hotstar, Netflix, Prime, Apple TV+, SonyLIV, ZEE5, etc.)
        if (tryLaunchTargetStreamingPkg(ctx, pkg, title)) return true;
        // 3. Fallback for SmartTube / YouTube or generic deep link
        return tryLaunchFallbackViewIntent(ctx, uriStr, pkg, title);
    }

    private static String resolveStreamingPackage(String pkgOrApp) {
        return (pkgOrApp != null && pkgOrApp.contains("."))
            ? pkgOrApp
            : AuraDb.resolveAppPackage(pkgOrApp);
    }

    private static boolean tryLaunchIntentSchemeUri(Context ctx, String uriStr) {
        if (uriStr == null || !uriStr.startsWith("intent:")) return false;
        try {
            Intent i = Intent.parseUri(uriStr, Intent.URI_INTENT_SCHEME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static boolean tryLaunchTargetStreamingPkg(Context ctx, String pkg, String title) {
        if (pkg == null || pkg.isEmpty() || pkg.contains("smarttube") || pkg.contains("youtube")) return false;
        if ("com.netflix.ninja".equals(pkg) && title != null && !title.isEmpty()) {
            if (tryLaunchNetflixSearchUri(ctx, pkg, title)) return true;
        }
        return tryLaunchLeanbackOrStandardPkg(ctx, pkg);
    }

    private static boolean tryLaunchNetflixSearchUri(Context ctx, String pkg, String title) {
        try {
            Uri uri = Uri.parse("nflx://www.netflix.com/search?q=" + Uri.encode(title));
            Intent nf = new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg);
            nf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(nf);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static boolean tryLaunchLeanbackOrStandardPkg(Context ctx, String pkg) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            Intent lb = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (lb == null) lb = pm.getLaunchIntentForPackage(pkg);
            if (lb == null) return false;
            lb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(lb);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static boolean tryLaunchFallbackViewIntent(Context ctx, String uriStr, String pkg, String title) {
        try {
            String target = resolveFallbackTargetUrl(uriStr, title);
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pkg != null && !pkg.isEmpty()) intent.setPackage(pkg);
            else if (target.contains("youtube.com")) intent.setPackage("org.smarttube.stable");
            ctx.startActivity(intent);
            return true;
        } catch (Exception e) { return false; }
    }

    private static String resolveFallbackTargetUrl(String uriStr, String title) {
        if (uriStr != null && !uriStr.isEmpty()) return uriStr;
        String safeTitle = title != null ? title : "";
        return "https://www.youtube.com/results?search_query=" + Uri.encode(safeTitle + " official trailer");
    }
}
