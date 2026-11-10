package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Interactive Frosted-Glass System Resource & RAM Guardian Modal.
 * Displays live RAM/CPU gauges, one-click RAM Boost, Auto-Guard toggle,
 * per-process Kill controls, and the persistent SQLite RAM Cleanup History log.
 */
public final class SystemMonitorModal {

    private SystemMonitorModal() {}

    public static void show(
            FrameLayout hostContainer, Context ctx, AuraDb db,
            ModalOverlayBuilder.ModalActions actions) {
        hostContainer.removeAllViews();
        hostContainer.setVisibility(View.VISIBLE);
        LinearLayout panel = createModalContainer(ctx);
        LinearLayout dynamicBody = new LinearLayout(ctx);
        dynamicBody.setOrientation(LinearLayout.VERTICAL);
        Runnable[] refreshRef = new Runnable[1];
        refreshRef[0] = () -> renderSnapshotIntoBody(dynamicBody, ctx, db, hostContainer, actions, refreshRef[0]);
        panel.addView(buildModalHeader(ctx, db, hostContainer, actions, refreshRef[0]));
        panel.addView(wrapVerticalScroll(ctx, dynamicBody), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        hostContainer.addView(panel);
        refreshRef[0].run();
    }

    private static LinearLayout createModalContainer(Context ctx) {
        LinearLayout p = new LinearLayout(ctx);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(ctx, 28), dp(ctx, 22), dp(ctx, 28), dp(ctx, 20));
        GradientDrawable bg = GlassTheme.solidRounded(Color.argb(246, 10, 13, 21), dp(ctx, 18));
        bg.setStroke(dp(ctx, 1), GlassTheme.BORDER_STRONG);
        p.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(ctx, 28), dp(ctx, 20), dp(ctx, 28), dp(ctx, 20));
        p.setLayoutParams(lp);
        return p;
    }

    private static LinearLayout buildModalHeader(
            Context ctx, AuraDb db, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(0, 0, 0, dp(ctx, 14));
        hdr.addView(buildHeaderTitleCol(ctx), GlassTheme.weightParams(1f));
        hdr.addView(buildBoostNowButton(ctx, db, host, actions, refresh), rightMargin(ctx, 8));
        hdr.addView(buildAutoGuardToggleButton(ctx, db, host, actions, refresh), rightMargin(ctx, 8));
        hdr.addView(buildCloseButton(ctx, host, actions), GlassTheme.wrapParams());
        return hdr;
    }

    private static LinearLayout buildHeaderTitleCol(Context ctx) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(GlassTheme.makeText(ctx, "\u26A1 RAM & CPU Guardian", 18f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1));
        col.addView(GlassTheme.makeText(ctx,
            "4-Tier Process Protection \u00B7 Protects Active Video & Voice \u00B7 Auto-Trims Idle Background Bloat",
            10.5f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1));
        return col;
    }

    private static TextView buildBoostNowButton(
            Context ctx, AuraDb db, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        TextView btn = makeActionPill(ctx, "\u26A1 Boost Now", GlassTheme.GOLD_ACCENT, host, actions);
        btn.setOnClickListener(v -> {
            JSONObject res = SystemResourceMonitor.executeRamBoost(ctx, db, "MANUAL", true);
            int freed = res.optInt("freed_mb", 0);
            String apps = res.optString("killed_apps", "");
            String msg = freed > 0 ? ("Freed ~" + freed + " MB (" + apps + ")") : "System already optimized!";
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            refresh.run();
        });
        btn.post(btn::requestFocus);
        return btn;
    }

    private static TextView buildAutoGuardToggleButton(
            Context ctx, AuraDb db, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        boolean on = SystemResourceMonitor.isAutoGuardEnabled(db);
        TextView btn = makeActionPill(ctx, "Auto-Guard: " + (on ? "ON \u2713" : "OFF"),
            on ? Color.rgb(72, 219, 151) : GlassTheme.TEXT_MUTED, host, actions);
        btn.setOnClickListener(v -> {
            boolean next = !SystemResourceMonitor.isAutoGuardEnabled(db);
            SystemResourceMonitor.setAutoGuardEnabled(db, next);
            btn.setText("Auto-Guard: " + (next ? "ON \u2713" : "OFF"));
            refresh.run();
        });
        return btn;
    }

    private static TextView buildCloseButton(Context ctx, FrameLayout host, ModalOverlayBuilder.ModalActions actions) {
        TextView btn = makeActionPill(ctx, "\u2715 Close", GlassTheme.TEXT_SECONDARY, host, actions);
        btn.setOnClickListener(v -> ModalOverlayBuilder.closeModal(host, actions));
        return btn;
    }

    private static void renderSnapshotIntoBody(
            LinearLayout body, Context ctx, AuraDb db, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        body.removeAllViews();
        JSONObject snap = SystemResourceMonitor.collectTelemetrySnapshot(ctx, db);
        body.addView(buildTopGaugesRow(ctx, snap));
        body.addView(buildColumnsRow(ctx, db, snap, host, actions, refresh));
    }

    private static LinearLayout buildTopGaugesRow(Context ctx, JSONObject snap) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(ctx, 14));
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftLp.rightMargin = dp(ctx, 10);
        row.addView(buildRamGaugeCard(ctx, snap), leftLp);
        row.addView(buildCpuGaugeCard(ctx, snap), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static LinearLayout buildRamGaugeCard(Context ctx, JSONObject snap) {
        LinearLayout card = createGlassCard(ctx);
        int used = snap.optInt("used_ram_mb", 1410);
        int tot = snap.optInt("total_ram_mb", 1831);
        int avail = snap.optInt("avail_ram_mb", 421);
        int pct = snap.optInt("used_ram_pct", 77);
        card.addView(GlassTheme.makeText(ctx, "SYSTEM MEMORY (RAM & ZRAM)", 9.5f, GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1));
        card.addView(GlassTheme.makeText(ctx, used + " / " + tot + " MB Used (" + avail + " MB Available)",
            14f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1));
        card.addView(buildProgressBar(ctx, pct, pickPressureColor(pct)));
        String detail = "App Heaps: " + snap.optInt("app_heaps_mb") + " MB \u00B7 Page Cache: " + snap.optInt("page_cache_mb")
            + " MB \u00B7 ZRAM: " + snap.optInt("zram_used_mb") + "/" + snap.optInt("zram_total_mb")
            + " MB \u00B7 Aura: " + snap.optInt("aura_pss_mb", 16) + " MB";
        card.addView(GlassTheme.makeText(ctx, detail, 10f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1));
        return card;
    }

    private static LinearLayout buildCpuGaugeCard(Context ctx, JSONObject snap) {
        LinearLayout card = createGlassCard(ctx);
        int cpuPct = snap.optInt("cpu_load_pct", 24);
        String freq = snap.optString("cpu_freq_str", "4\u00D7 1.10 GHz");
        double auraCpu = snap.optDouble("aura_cpu_pct", 0.1);
        int reclaimEst = snap.optInt("reclaimable_est_mb", 0);
        card.addView(GlassTheme.makeText(ctx, "4-CORE ARM CPU & GUARDIAN STATUS", 9.5f, GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1));
        card.addView(GlassTheme.makeText(ctx, "CPU Load: " + cpuPct + "% \u00B7 Clock: " + freq,
            14f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1));
        card.addView(buildProgressBar(ctx, cpuPct, pickPressureColor(cpuPct)));
        String sub = "Aura App CPU: " + auraCpu + "% \u00B7 Reclaimable Background RAM: ~" + reclaimEst + " MB";
        card.addView(GlassTheme.makeText(ctx, sub, 10f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1));
        return card;
    }

    private static View buildProgressBar(Context ctx, int pct, int fillColor) {
        FrameLayout track = new FrameLayout(ctx);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 7));
        tLp.topMargin = dp(ctx, 6);
        tLp.bottomMargin = dp(ctx, 6);
        track.setLayoutParams(tLp);
        track.setBackground(GlassTheme.solidRounded(Color.argb(60, 255, 255, 255), dp(ctx, 4)));
        View fill = new View(ctx);
        fill.setBackground(GlassTheme.solidRounded(fillColor, dp(ctx, 4)));
        int w = Math.max(dp(ctx, 12), (dp(ctx, 380) * Math.min(100, Math.max(4, pct))) / 100);
        track.addView(fill, new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
        return track;
    }

    private static LinearLayout buildColumnsRow(
            Context ctx, AuraDb db, JSONObject snap, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f);
        leftLp.rightMargin = dp(ctx, 10);
        row.addView(buildCandidatesColumn(ctx, db, snap.optJSONArray("candidates"), host, actions, refresh), leftLp);
        row.addView(buildHistoryColumn(ctx, snap.optJSONArray("history")),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f));
        return row;
    }

    private static LinearLayout buildCandidatesColumn(
            Context ctx, AuraDb db, JSONArray cands, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        LinearLayout col = createGlassCard(ctx);
        col.addView(GlassTheme.makeText(ctx, "DETECTED PROCESSES & TIER POLICY", 10.5f,
            GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1));
        if (cands == null || cands.length() == 0) {
            col.addView(GlassTheme.makeText(ctx, "No background processes holding RAM.", 11f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1));
            return col;
        }
        for (int i = 0; i < cands.length(); i++) {
            JSONObject c = cands.optJSONObject(i);
            if (c != null) col.addView(buildCandidateProcessRow(ctx, db, c, host, actions, refresh));
        }
        return col;
    }

    private static LinearLayout buildCandidateProcessRow(
            Context ctx, AuraDb db, JSONObject c, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        row.addView(buildCandidateInfoCol(ctx, c), GlassTheme.weightParams(1f));
        if (!c.optBoolean("protected", true)) {
            row.addView(buildKillSingleButton(ctx, db, c, host, actions, refresh));
        } else {
            row.addView(GlassTheme.makeText(ctx, "PROTECTED", 9.5f, Color.rgb(72, 219, 151), GlassTheme.FONT_BOLD, 1));
        }
        return row;
    }

    private static LinearLayout buildCandidateInfoCol(Context ctx, JSONObject c) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        String title = c.optString("name") + "  \u00B7  ~" + c.optInt("est_ram_mb") + " MB";
        col.addView(GlassTheme.makeText(ctx, title, 11.5f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1));
        col.addView(GlassTheme.makeText(ctx, "Tier " + c.optInt("tier") + " \u00B7 " + c.optString("reason"),
            9.5f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1));
        return col;
    }

    private static TextView buildKillSingleButton(
            Context ctx, AuraDb db, JSONObject c, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        String pkg = c.optString("pkg");
        String name = c.optString("name");
        TextView btn = makeActionPill(ctx, "Kill & Free", Color.rgb(255, 115, 115), host, actions);
        btn.setOnClickListener(v -> {
            SystemResourceMonitor.killSinglePackage(ctx, db, pkg);
            Toast.makeText(ctx, "Terminated background " + name, Toast.LENGTH_SHORT).show();
            refresh.run();
        });
        return btn;
    }

    private static LinearLayout buildHistoryColumn(Context ctx, JSONArray hist) {
        LinearLayout col = createGlassCard(ctx);
        col.addView(GlassTheme.makeText(ctx, "RAM CLEANUP HISTORY LOG", 10.5f,
            GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1));
        if (hist == null || hist.length() == 0) {
            col.addView(GlassTheme.makeText(ctx, "No cleanup events recorded yet. Click 'Boost Now' to test.",
                10.5f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 2));
            return col;
        }
        for (int i = 0; i < Math.min(10, hist.length()); i++) {
            JSONObject h = hist.optJSONObject(i);
            if (h != null) col.addView(buildHistoryEventRow(ctx, h));
        }
        return col;
    }

    private static LinearLayout buildHistoryEventRow(Context ctx, JSONObject h) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
        String timeStr = new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(h.optLong("timestamp_sec") * 1000L));
        String top = "[" + h.optString("trigger_type") + "] +" + h.optInt("freed_mb") + " MB Freed \u00B7 " + timeStr;
        String sub = "Killed: " + h.optString("killed_apps") + " (" + h.optInt("avail_before_mb")
            + "M \u2192 " + h.optInt("avail_after_mb") + "M Avail \u00B7 " + h.optString("active_context") + ")";
        row.addView(GlassTheme.makeText(ctx, top, 11f, Color.rgb(72, 219, 151), GlassTheme.FONT_BOLD, 1));
        row.addView(GlassTheme.makeText(ctx, sub, 9.5f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 2));
        return row;
    }

    private static LinearLayout createGlassCard(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        GradientDrawable bg = GlassTheme.solidRounded(Color.argb(175, 19, 25, 38), dp(ctx, 12));
        bg.setStroke(dp(ctx, 1), GlassTheme.BORDER_STRONG);
        card.setBackground(bg);
        return card;
    }

    private static TextView makeActionPill(
            Context ctx, String text, int accentColor, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions) {
        TextView tv = GlassTheme.makeText(ctx, text, 11f, accentColor, GlassTheme.FONT_BOLD, 1);
        tv.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6));
        float r = dp(ctx, 10);
        tv.setBackground(GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), r));
        tv.setFocusable(true);
        tv.setClickable(true);
        bindPillFocusAndBack(ctx, tv, accentColor, r, host, actions);
        return tv;
    }

    private static void bindPillFocusAndBack(
            Context ctx, TextView tv, int accentColor, float r, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions) {
        tv.setOnFocusChangeListener((v, focused) -> {
            tv.setBackground(focused
                ? GlassTheme.focusRounded(Color.WHITE, Color.WHITE, dp(ctx, 2), r)
                : GlassTheme.focusRounded(GlassTheme.SURFACE_ELEV, GlassTheme.BORDER_STRONG, dp(ctx, 1), r));
            tv.setTextColor(focused ? GlassTheme.TEXT_ON_LIGHT : accentColor);
        });
        tv.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BACK) {
                ModalOverlayBuilder.closeModal(host, actions);
                return true;
            }
            return false;
        });
    }

    private static ScrollView wrapVerticalScroll(Context ctx, View child) {
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.addView(child, GlassTheme.matchWrapParams());
        return sv;
    }

    private static int pickPressureColor(int pct) {
        if (pct >= 85) return Color.rgb(255, 107, 107);
        if (pct >= 72) return GlassTheme.GOLD_ACCENT;
        return Color.rgb(72, 219, 151);
    }

    private static LinearLayout.LayoutParams rightMargin(Context ctx, int dpVal) {
        LinearLayout.LayoutParams lp = GlassTheme.wrapParams();
        lp.rightMargin = dp(ctx, dpVal);
        return lp;
    }

    private static int dp(Context ctx, float v) {
        return GlassTheme.dp(ctx, v);
    }
}
