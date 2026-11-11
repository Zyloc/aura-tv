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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/**
 * Non-focus-stealing Android TV window overlay manager (TYPE_APPLICATION_OVERLAY).
 * Renders Up Next cards, In-App Voice Intent pills, and In-App Show Companion
 * recaps with synchronized floating word-by-word LiveSubtitleBar narration.
 */
public class TvOverlayManager {
    private static final String TAG_SUBTEXT = "aura_overlay_subtext";
    private static volatile TvOverlayManager sActiveInstance;

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoDismissTask = this::dismissOverlay;
    private View currentBanner;
    private FrameLayout floatingSubtitleWrap;

    public TvOverlayManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sActiveInstance = this;
    }

    public static TvOverlayManager peekInstance() {
        return sActiveInstance;
    }

    public static void dismissActiveInstanceOverlays() {
        TvOverlayManager inst = sActiveInstance;
        if (inst != null) inst.dismissCompanionOverlays();
    }

    public void showUpNextCard(String badge, String title, String reason, int durationSec) {
        mainHandler.post(() -> renderCardAt(
            badge, title, reason, Gravity.BOTTOM | Gravity.END, 48, 48, 640, "#7C6EF7", durationSec));
    }

    public void showVoiceStatusPill(String badge, String headline, String subtext, int durationSec) {
        mainHandler.post(() -> renderCardAt(
            badge, headline, subtext, Gravity.TOP | Gravity.END, 36, 32, 520, "#F5C518", durationSec));
    }

    public void updateVoiceStatusSubtext(String subtext) {
        mainHandler.post(() -> applySubtextIfVisible(subtext));
    }

    private void applySubtextIfVisible(String subtext) {
        if (currentBanner == null || subtext == null || subtext.isEmpty()) return;
        View v = currentBanner.findViewWithTag(TAG_SUBTEXT);
        if (v instanceof TextView) ((TextView) v).setText(subtext);
    }

    public void showInAppCompanionRecap(JSONObject recap) {
        if (recap == null) return;
        mainHandler.post(() -> presentCompanionRecapOnMain(recap));
    }

    private void presentCompanionRecapOnMain(JSONObject recap) {
        String headline = recap.optString("headline", "Catch Me Up");
        String body = buildCompanionCardBody(recap);
        renderCardAt("\u2726 AURA SHOW COMPANION", headline, body,
            Gravity.TOP | Gravity.END, 36, 32, 560, "#F5C518", 150);
        String spoken = recap.optString("spoken_narration", "").trim();
        if (!spoken.isEmpty()) startFloatingSubtitleNarration(spoken);
    }

    private String buildCompanionCardBody(JSONObject recap) {
        String xray = recap.optString("cast_xray", "").trim();
        String beats = recap.optString("current_episode_beats", recap.optString("story_so_far", "")).trim();
        if (!xray.isEmpty() && !beats.isEmpty()) return xray + "\n" + beats;
        return !xray.isEmpty() ? xray : beats;
    }

    private void startFloatingSubtitleNarration(String spoken) {
        attachFloatingSubtitleWindow();
        bindFloatingSubtitleListener();
        AuraTtsNarrator.speak(ctx, spoken, this::dismissCompanionOverlays);
    }

    private void attachFloatingSubtitleWindow() {
        removeFloatingSubtitleWindow();
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            floatingSubtitleWrap = LiveSubtitleBar.build(ctx);
            wm.addView(floatingSubtitleWrap, buildSubtitleWindowParams());
        } catch (Exception ignored) { floatingSubtitleWrap = null; }
    }

    private void bindFloatingSubtitleListener() {
        AuraTtsNarrator.setSubtitleListener(new AuraTtsNarrator.SubtitleListener() {
            @Override public void onSentenceStart(String s) { LiveSubtitleBar.animateNewSentence(floatingSubtitleWrap, s); }
            @Override public void onWordHighlight(String s, int ws, int we) { LiveSubtitleBar.updateWordHighlight(floatingSubtitleWrap, s, ws, we); }
            @Override public void onNarrationStop() { LiveSubtitleBar.hide(floatingSubtitleWrap); }
        });
    }

    private WindowManager.LayoutParams buildSubtitleWindowParams() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = 24;
        return lp;
    }

    public void dismissCompanionOverlays() {
        mainHandler.post(() -> {
            dismissOverlay();
            removeFloatingSubtitleWindow();
            AuraActivity.rebindLauncherSubtitleIfActive();
        });
    }

    private void removeFloatingSubtitleWindow() {
        if (floatingSubtitleWrap == null) return;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.removeView(floatingSubtitleWrap);
        } catch (Exception ignored) {}
        floatingSubtitleWrap = null;
    }

    private void renderCardAt(String badge, String title, String reason,
                              int gravity, int x, int y, int widthPx, String strokeHex, int durationSec) {
        dismissOverlay();
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            LinearLayout card = buildCardLayout(badge, title, reason, strokeHex);
            wm.addView(card, buildWindowParams(gravity, x, y, widthPx));
            currentBanner = card;
            mainHandler.postDelayed(autoDismissTask, Math.max(3, durationSec) * 1000L);
        } catch (Exception ignored) {}
    }

    private LinearLayout buildCardLayout(String badge, String title, String reason, String strokeHex) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(30, 22, 30, 22);
        box.setBackground(buildCardBackground(strokeHex));
        box.addView(makeText(badge, 10.5f, strokeHex, true, 1));
        box.addView(makeText(title, 15.5f, "#F8FAFC", true, 2));
        box.addView(makeSubtextView(reason));
        return box;
    }

    private TextView makeSubtextView(String reason) {
        TextView sub = makeText(reason != null ? reason : "", 12.5f, "#CBD5E1", false, 3);
        sub.setTag(TAG_SUBTEXT);
        if (reason == null || reason.isEmpty()) sub.setVisibility(View.GONE);
        return sub;
    }

    private GradientDrawable buildCardBackground(String strokeHex) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EB0C101A"));
        bg.setCornerRadius(22f);
        bg.setStroke(2, Color.parseColor(strokeHex));
        return bg;
    }

    private TextView makeText(String txt, float sizeSp, String hexColor, boolean bold, int maxLines) {
        TextView tv = new TextView(ctx);
        tv.setText(txt != null ? txt : "");
        tv.setTextSize(sizeSp);
        tv.setTextColor(Color.parseColor(hexColor));
        tv.setMaxLines(maxLines);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 3, 0, 3);
        return tv;
    }

    private WindowManager.LayoutParams buildWindowParams(int gravity, int x, int y, int widthPx) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            widthPx, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.x = x;
        lp.y = y;
        return lp;
    }

    public void dismissOverlay() {
        mainHandler.removeCallbacks(autoDismissTask);
        if (currentBanner == null) return;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.removeView(currentBanner);
        } catch (Exception ignored) {}
        currentBanner = null;
    }

    public boolean launchDeepLink(String uriStr, String pkg) {
        return launchNativeStreamingApp(ctx, uriStr, pkg, "");
    }

    public static boolean launchNativeStreamingApp(Context ctx, String uriStr, String pkgOrApp, String title) {
        String pkg = resolveStreamingPackage(pkgOrApp);
        if (tryLaunchIntentSchemeUri(ctx, uriStr)) return true;
        if (tryLaunchTargetStreamingPkg(ctx, pkg, title)) return true;
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
