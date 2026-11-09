package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Ambient 1080p Cinema Screensaver for Aura TV.
 * Prevents the TV from shutting down during idle periods while cycling through
 * full-bleed Ultra-HD backdrops with slow Ken-Burns motion and floating clock/metadata.
 */
public final class AmbientScreensaverOverlay {

    private static final long SLIDE_INTERVAL_MS = 9500L;

    private static final class SaverState {
        final Handler handler = new Handler(Looper.getMainLooper());
        List<JSONObject> items = new ArrayList<>();
        int index = 0;
        boolean activeIsA = true;
        ImageView bgA;
        ImageView bgB;
        TextView titleTv;
        TextView metaTv;
        TextView clockTv;
        LinearLayout infoHud;
        Runnable slideTicker;
    }

    private AmbientScreensaverOverlay() {}

    public static FrameLayout build(Context ctx, Runnable onWake) {
        FrameLayout root = new FrameLayout(ctx);
        root.setVisibility(View.GONE);
        root.setAlpha(0f);
        root.setBackgroundColor(Color.BLACK);
        root.setClickable(true);
        root.setFocusable(true);
        root.setKeepScreenOn(true);

        SaverState st = new SaverState();
        root.setTag(st);

        st.bgA = new ImageView(ctx);
        st.bgA.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(st.bgA, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        st.bgB = new ImageView(ctx);
        st.bgB.setScaleType(ImageView.ScaleType.CENTER_CROP);
        st.bgB.setAlpha(0f);
        root.addView(st.bgB, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Bottom cinema vignette gradient
        View bottomScrim = new View(ctx);
        GradientDrawable gd = new GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{
                Color.argb(235, 8, 10, 16),
                Color.argb(145, 8, 10, 16),
                Color.TRANSPARENT
            });
        bottomScrim.setBackground(gd);
        root.addView(bottomScrim, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, GlassTheme.dp(ctx, 210), Gravity.BOTTOM));

        // Bottom HUD row (Left: Artwork Title + Genre, Right: Large Ambient Clock)
        LinearLayout hud = new LinearLayout(ctx);
        hud.setOrientation(LinearLayout.HORIZONTAL);
        hud.setGravity(Gravity.BOTTOM);
        hud.setPadding(GlassTheme.dp(ctx, 36), 0, GlassTheme.dp(ctx, 36), GlassTheme.dp(ctx, 28));
        st.infoHud = hud;

        LinearLayout leftCol = new LinearLayout(ctx);
        leftCol.setOrientation(LinearLayout.VERTICAL);

        TextView pill = GlassTheme.makeText(ctx, "\u2726  AURA AMBIENT CINEMA", 9.5f,
            Color.argb(200, 148, 163, 184), GlassTheme.FONT_BOLD, 1);
        pill.setLetterSpacing(0.12f);
        leftCol.addView(pill, GlassTheme.wrapParams());

        st.titleTv = GlassTheme.makeText(ctx, "", 24f,
            Color.WHITE, GlassTheme.FONT_BOLD, 1);
        LinearLayout.LayoutParams tLp = GlassTheme.wrapParams();
        tLp.topMargin = GlassTheme.dp(ctx, 4);
        leftCol.addView(st.titleTv, tLp);

        st.metaTv = GlassTheme.makeText(ctx, "", 11.5f,
            GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1);
        LinearLayout.LayoutParams mLp = GlassTheme.wrapParams();
        mLp.topMargin = GlassTheme.dp(ctx, 2);
        leftCol.addView(st.metaTv, mLp);

        hud.addView(leftCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout rightCol = new LinearLayout(ctx);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.END);

        st.clockTv = GlassTheme.makeText(ctx, "", 28f,
            Color.WHITE, GlassTheme.FONT_BOLD, 1);
        rightCol.addView(st.clockTv, GlassTheme.wrapParams());

        TextView hint = GlassTheme.makeText(ctx, "Press any button to resume", 10f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
        rightCol.addView(hint, GlassTheme.wrapParams());

        hud.addView(rightCol, GlassTheme.wrapParams());

        root.addView(hud, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        root.setOnClickListener(v -> {
            dismiss(root);
            if (onWake != null) onWake.run();
        });
        return root;
    }

    public static void setItems(FrameLayout overlay, List<JSONObject> items) {
        if (overlay == null || !(overlay.getTag() instanceof SaverState)) return;
        SaverState st = (SaverState) overlay.getTag();
        if (items != null && !items.isEmpty()) {
            st.items = new ArrayList<>(items);
        }
    }

    public static boolean isVisible(FrameLayout overlay) {
        return overlay != null && overlay.getVisibility() == View.VISIBLE;
    }

    public static void show(FrameLayout overlay, Context ctx) {
        if (overlay == null || !(overlay.getTag() instanceof SaverState)) return;
        SaverState st = (SaverState) overlay.getTag();
        if (st.items.isEmpty()) return;

        overlay.setVisibility(View.VISIBLE);
        overlay.animate().cancel();
        overlay.animate().alpha(1f).setDuration(550).setInterpolator(GlassTheme.EASE).start();
        overlay.requestFocus();

        showSlide(overlay, st, ctx, false);
        stopTicker(st);
        st.slideTicker = new Runnable() {
            @Override
            public void run() {
                if (overlay.getVisibility() != View.VISIBLE) return;
                st.index = (st.index + 1) % st.items.size();
                showSlide(overlay, st, ctx, true);
                st.handler.postDelayed(this, SLIDE_INTERVAL_MS);
            }
        };
        st.handler.postDelayed(st.slideTicker, SLIDE_INTERVAL_MS);
    }

    public static void dismiss(FrameLayout overlay) {
        if (overlay == null || !(overlay.getTag() instanceof SaverState)) return;
        SaverState st = (SaverState) overlay.getTag();
        stopTicker(st);
        if (overlay.getVisibility() == View.VISIBLE) {
            overlay.animate().cancel();
            overlay.animate()
                .alpha(0f)
                .setDuration(220)
                .withEndAction(() -> overlay.setVisibility(View.GONE))
                .start();
        }
    }

    private static void stopTicker(SaverState st) {
        if (st.slideTicker != null) {
            st.handler.removeCallbacks(st.slideTicker);
            st.slideTicker = null;
        }
    }

    private static void showSlide(FrameLayout overlay, SaverState st, Context ctx, boolean crossfade) {
        if (st.items.isEmpty()) return;
        JSONObject item = st.items.get(st.index % st.items.size());
        String title = item.optString("title", "Featured Cinema");
        int year = item.optInt("year", 0);
        String genres = item.optString("genres", "");
        String meta = (year > 0 && !genres.isEmpty()) ? (year + "  \u00B7  " + genres)
                    : (year > 0 ? String.valueOf(year) : genres);

        st.titleTv.setText(title);
        st.metaTv.setText(meta);
        st.clockTv.setText(new SimpleDateFormat("h:mm a", Locale.US).format(new Date()));

        // Subtle pixel-shift burn-in protection (-4dp to +4dp)
        float shiftX = ((st.index % 3) - 1) * GlassTheme.dp(ctx, 3);
        float shiftY = (((st.index / 3) % 3) - 1) * GlassTheme.dp(ctx, 2);
        st.infoHud.animate().translationX(shiftX).translationY(shiftY).setDuration(800).start();

        String url = item.optString("backdrop_url", "");
        if (url.isEmpty()) url = item.optString("poster_url", "");

        ImageView incoming = st.activeIsA ? st.bgA : st.bgB;
        ImageView outgoing = st.activeIsA ? st.bgB : st.bgA;
        st.activeIsA = !st.activeIsA;

        incoming.animate().cancel();
        outgoing.animate().cancel();
        ImageLoader.loadHero(ctx, url, incoming);

        incoming.setScaleX(1.0f);
        incoming.setScaleY(1.0f);
        if (!crossfade) {
            incoming.setAlpha(1f);
            outgoing.setAlpha(0f);
        } else {
            incoming.setAlpha(0f);
            incoming.animate().alpha(1f).setDuration(1100).start();
            outgoing.animate().alpha(0f).setDuration(1100).start();
        }
        incoming.animate()
            .scaleX(1.075f)
            .scaleY(1.075f)
            .setDuration(SLIDE_INTERVAL_MS + 800L)
            .start();
    }
}
