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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/**
 * Android TV window overlay manager (TYPE_APPLICATION_OVERLAY).
 * Shows the frameless AgenticOrbView loader with live agentic function steps while
 * Catch Me Up fetches & summarizes (cancellable via BACK with zero timeout), then
 * hands off cleanly to background-free progressive LiveSubtitleBar speech text.
 */
public class TvOverlayManager {
    private static final String TAG_SUBTEXT = "aura_overlay_subtext";
    private static final int TAG_ORB_STEP = 0x7F0B1001;
    private static volatile TvOverlayManager sActiveInstance;

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoDismissTask = this::dismissOverlay;
    private View currentBanner;
    private FrameLayout floatingSubtitleWrap;
    private Runnable activeCancelAction;

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
            badge, headline, subtext, Gravity.TOP | Gravity.END, 36, 32, 520, "#38BDF8", durationSec));
    }

    public void showCompanionLoader(String spokenQuery, String initialStep, Runnable onBackCancel) {
        mainHandler.post(() -> mountCompanionLoaderOnMain(spokenQuery, initialStep, onBackCancel));
    }

    private void mountCompanionLoaderOnMain(String spokenQuery, String initialStep, Runnable onBackCancel) {
        dismissOverlay();
        removeFloatingSubtitleWindow();
        activeCancelAction = wrapCancelAction(onBackCancel);
        FrameLayout root = buildCompanionLoaderRoot(spokenQuery, initialStep);
        LiveSubtitleBar.attachBackCancelHandler(root, activeCancelAction);
        addWindowViewSafe(root, buildFullScreenFocusableParams());
        currentBanner = root;
    }

    private Runnable wrapCancelAction(Runnable onBackCancel) {
        return () -> {
            if (onBackCancel != null) onBackCancel.run();
            AuraTtsNarrator.stop(ctx);
            dismissCompanionOverlays();
        };
    }

    private FrameLayout buildCompanionLoaderRoot(String spokenQuery, String initialStep) {
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.argb(175, 6, 9, 18));
        LinearLayout col = AgenticOrbView.buildFloatingLoaderColumn(ctx, TAG_ORB_STEP, 0);
        TextView stepTv = col.findViewWithTag(TAG_ORB_STEP);
        if (stepTv != null) stepTv.setText(initialStep != null ? initialStep : "Preparing Catch Me Up\u2026");
        col.addView(buildCancelHintCaption(spokenQuery));
        root.addView(col);
        return root;
    }

    private TextView buildCancelHintCaption(String spokenQuery) {
        String q = (spokenQuery != null && !spokenQuery.isEmpty()) ? ("\u201C" + spokenQuery + "\u201D  \u00B7  ") : "";
        TextView hint = GlassTheme.makeText(ctx, q + "Press BACK to cancel", 13.0f,
            Color.argb(190, 203, 213, 225), GlassTheme.FONT_REGULAR, 1);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = GlassTheme.dp(ctx, 10);
        hint.setLayoutParams(lp);
        return hint;
    }

    public void updateVoiceStatusSubtext(String subtext) {
        mainHandler.post(() -> applySubtextIfVisible(subtext));
    }

    private void applySubtextIfVisible(String subtext) {
        if (currentBanner == null || subtext == null || subtext.isEmpty()) return;
        View orbStep = currentBanner.findViewWithTag(TAG_ORB_STEP);
        if (orbStep instanceof TextView) ((TextView) orbStep).setText(subtext);
        View v = currentBanner.findViewWithTag(TAG_SUBTEXT);
        if (v instanceof TextView) ((TextView) v).setText(subtext);
    }

    public void showInAppCompanionRecap(JSONObject recap) {
        showInAppCompanionRecap(recap, null);
    }

    public void showInAppCompanionRecap(JSONObject recap, Runnable onBackCancel) {
        if (recap == null) return;
        mainHandler.post(() -> presentCompanionRecapOnMain(recap, onBackCancel));
    }

    private void presentCompanionRecapOnMain(JSONObject recap, Runnable onBackCancel) {
        String spoken = recap.optString("spoken_narration", "").trim();
        if (spoken.isEmpty()) { dismissCompanionOverlays(); return; }
        if (onBackCancel != null || activeCancelAction == null) activeCancelAction = wrapCancelAction(onBackCancel);
        ensureLoaderUntilSpeechStarts(recap.optString("title", ""));
        startFloatingSubtitleNarration(spoken);
    }

    private void ensureLoaderUntilSpeechStarts(String showTitle) {
        String msg = "Starting Google Speech narration" + (showTitle.isEmpty() ? "\u2026" : (" for " + showTitle + "\u2026"));
        if (currentBanner != null && currentBanner.findViewWithTag(TAG_ORB_STEP) != null) {
            applySubtextIfVisible(msg);
        } else {
            mountCompanionLoaderOnMain("", msg, null);
        }
    }

    private void startFloatingSubtitleNarration(String spoken) {
        attachFloatingSubtitleWindow();
        bindFloatingSubtitleListener();
        AuraTtsNarrator.speak(ctx, spoken, this::dismissCompanionOverlays);
    }

    private void attachFloatingSubtitleWindow() {
        removeFloatingSubtitleWindow();
        floatingSubtitleWrap = LiveSubtitleBar.build(ctx);
        LiveSubtitleBar.attachBackCancelHandler(floatingSubtitleWrap,
            activeCancelAction != null ? activeCancelAction : wrapCancelAction(null));
        if (!addWindowViewSafe(floatingSubtitleWrap, buildSubtitleWindowParams())) {
            floatingSubtitleWrap = null;
        }
    }

    private void bindFloatingSubtitleListener() {
        AuraTtsNarrator.setSubtitleListener(new AuraTtsNarrator.SubtitleListener() {
            @Override public void onSentenceStart(String s) {
                dismissOverlay();
                LiveSubtitleBar.animateNewSentence(floatingSubtitleWrap, s);
            }
            @Override public void onWordHighlight(String s, int ws, int we) {
                LiveSubtitleBar.updateWordHighlight(floatingSubtitleWrap, s, ws, we);
            }
            @Override public void onNarrationStop() { LiveSubtitleBar.hide(floatingSubtitleWrap); }
        });
    }

    private WindowManager.LayoutParams buildFullScreenFocusableParams() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        return new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
    }

    private WindowManager.LayoutParams buildSubtitleWindowParams() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            GlassTheme.dp(ctx, 920), GlassTheme.dp(ctx, 128),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = 12;
        return lp;
    }

    private boolean addWindowViewSafe(View view, WindowManager.LayoutParams lp) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return false;
            wm.addView(view, lp);
            return true;
        } catch (Exception ignored) { return false; }
    }

    public void dismissCompanionOverlays() {
        mainHandler.post(() -> {
            activeCancelAction = null;
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
        LinearLayout card = buildCardLayout(badge, title, reason, strokeHex);
        if (addWindowViewSafe(card, buildWindowParams(gravity, x, y, widthPx))) {
            currentBanner = card;
            if (durationSec > 0) mainHandler.postDelayed(autoDismissTask, Math.max(3, durationSec) * 1000L);
        }
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
