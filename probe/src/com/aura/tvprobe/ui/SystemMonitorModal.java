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
 * Clean, Graph-Driven RAM & CPU Monitor Modal.
 * Features Circular Arc Ring Gauges, Live Dual Area/Line Chart,
 * streamlined single-line background app rows, and compact cleanup bars.
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
        scheduleLiveGraphTick(hostContainer, dynamicBody, ctx, db);
    }

    private static void scheduleLiveGraphTick(
            FrameLayout host, LinearLayout body, Context ctx, AuraDb db) {
        host.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (host.getVisibility() != View.VISIBLE || body.getChildCount() == 0) return;
                JSONObject snap = SystemResourceMonitor.collectLightweightTelemetry(ctx, db);
                body.removeViewAt(0);
                body.addView(buildVisualTelemetryHero(ctx, snap), 0);
                host.postDelayed(this, 2500L);
            }
        }, 2500L);
    }

    private static LinearLayout createModalContainer(Context ctx) {
        LinearLayout p = new LinearLayout(ctx);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(ctx, 26), dp(ctx, 20), dp(ctx, 26), dp(ctx, 18));
        GradientDrawable bg = GlassTheme.solidRounded(Color.argb(246, 10, 13, 21), dp(ctx, 18));
        bg.setStroke(dp(ctx, 1), GlassTheme.BORDER_STRONG);
        p.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(ctx, 32), dp(ctx, 22), dp(ctx, 32), dp(ctx, 22));
        p.setLayoutParams(lp);
        return p;
    }

    private static LinearLayout buildModalHeader(
            Context ctx, AuraDb db, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        LinearLayout hdr = new LinearLayout(ctx);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(0, 0, 0, dp(ctx, 12));
        hdr.addView(GlassTheme.makeText(ctx, "\u26A1 System Monitor", 17f,
            GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1), GlassTheme.weightParams(1f));
        hdr.addView(buildBoostNowButton(ctx, db, host, actions, refresh), rightMargin(ctx, 8));
        hdr.addView(buildAutoGuardToggleButton(ctx, db, host, actions, refresh), rightMargin(ctx, 8));
        hdr.addView(buildCloseButton(ctx, host, actions), GlassTheme.wrapParams());
        return hdr;
    }

    private static TextView buildBoostNowButton(
            Context ctx, AuraDb db, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        TextView btn = makeActionPill(ctx, "\u26A1 Boost RAM", GlassTheme.GOLD_ACCENT, host, actions);
        btn.setOnClickListener(v -> {
            JSONObject res = SystemResourceMonitor.executeRamBoost(ctx, db, "MANUAL", true);
            int freed = res.optInt("freed_mb", 0);
            String msg = freed > 0 ? ("Freed +" + freed + " MB") : "Memory already clean";
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
        body.addView(buildVisualTelemetryHero(ctx, snap));
        body.addView(buildColumnsRow(ctx, db, snap, host, actions, refresh));
    }

    private static LinearLayout buildVisualTelemetryHero(Context ctx, JSONObject snap) {
        LinearLayout card = createGlassCard(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(ctx, 12);
        card.setLayoutParams(cardLp);
        addRingGaugesToHero(ctx, snap, card);
        addTrendChartToHero(ctx, snap, card);
        return card;
    }

    private static void addRingGaugesToHero(Context ctx, JSONObject snap, LinearLayout card) {
        int ramPct = snap.optInt("used_ram_pct", 76);
        int availMb = snap.optInt("avail_ram_mb", 430);
        int cpuPct = snap.optInt("cpu_load_pct", 24);
        String freq = snap.optString("cpu_freq_str", "1.1 GHz").replace("4\u00D7 ", "");
        View ramRing = TelemetryGraphView.createRingGauge(ctx, ramPct, "RAM", availMb + "M Free", pickPressureColor(ramPct));
        View cpuRing = TelemetryGraphView.createRingGauge(ctx, cpuPct, "CPU", freq, Color.rgb(56, 189, 248));
        card.addView(ramRing, new LinearLayout.LayoutParams(dp(ctx, 96), dp(ctx, 92)));
        LinearLayout.LayoutParams cpuLp = new LinearLayout.LayoutParams(dp(ctx, 96), dp(ctx, 92));
        cpuLp.leftMargin = dp(ctx, 6);
        cpuLp.rightMargin = dp(ctx, 16);
        card.addView(cpuRing, cpuLp);
    }

    private static void addTrendChartToHero(Context ctx, JSONObject snap, LinearLayout card) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        String summary = snap.optInt("used_ram_mb", 1400) + " / " + snap.optInt("total_ram_mb", 1831)
            + " MB Used  \u00B7  ZRAM " + snap.optInt("zram_used_mb") + "M  \u00B7  Aura " + snap.optInt("aura_pss_mb", 16) + "M";
        col.addView(GlassTheme.makeText(ctx, summary, 10f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1));
        View chart = TelemetryGraphView.createDualAreaChart(ctx, snap.optJSONArray("ram_series"), snap.optJSONArray("cpu_series"));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 74));
        cLp.topMargin = dp(ctx, 4);
        col.addView(chart, cLp);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private static LinearLayout buildColumnsRow(
            Context ctx, AuraDb db, JSONObject snap, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftLp.rightMargin = dp(ctx, 10);
        row.addView(buildCandidatesColumn(ctx, db, snap, host, actions, refresh), leftLp);
        row.addView(buildHistoryColumn(ctx, snap.optJSONArray("history")),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static LinearLayout buildCandidatesColumn(
            Context ctx, AuraDb db, JSONObject snap, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        LinearLayout col = createGlassCard(ctx);
        int reclaim = snap.optInt("reclaimable_est_mb", 0);
        String hdr = "BACKGROUND APPS" + (reclaim > 0 ? ("  \u00B7  ~" + reclaim + " MB Reclaimable") : "");
        col.addView(GlassTheme.makeText(ctx, hdr, 10f, GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1));
        int shown = appendActionableCandidateRows(ctx, db, snap.optJSONArray("candidates"), col, host, actions, refresh);
        if (shown == 0) {
            col.addView(GlassTheme.makeText(ctx, "No idle background apps holding memory.", 11f, GlassTheme.TEXT_SECONDARY, GlassTheme.FONT_MEDIUM, 1));
        }
        col.addView(GlassTheme.makeText(ctx, "\u2713 Active video, voice & core services protected", 9.5f, Color.rgb(72, 219, 151), GlassTheme.FONT_MEDIUM, 1));
        return col;
    }

    private static int appendActionableCandidateRows(
            Context ctx, AuraDb db, JSONArray cands, LinearLayout col,
            FrameLayout host, ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        if (cands == null) return 0;
        int count = 0;
        for (int i = 0; i < cands.length() && count < 5; i++) {
            JSONObject c = cands.optJSONObject(i);
            if (c == null || c.optInt("tier", 0) == 0) continue;
            col.addView(buildCandidateProcessRow(ctx, db, c, host, actions, refresh));
            count++;
        }
        return count;
    }

    private static LinearLayout buildCandidateProcessRow(
            Context ctx, AuraDb db, JSONObject c, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 5), 0, dp(ctx, 5));
        String title = c.optString("name") + "  \u00B7  ~" + c.optInt("est_ram_mb") + " MB";
        row.addView(GlassTheme.makeText(ctx, title, 11.5f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_BOLD, 1), GlassTheme.weightParams(1f));
        if (!c.optBoolean("protected", true)) row.addView(buildKillSingleButton(ctx, db, c, host, actions, refresh));
        else row.addView(GlassTheme.makeText(ctx, "Playing", 9.5f, Color.rgb(72, 219, 151), GlassTheme.FONT_BOLD, 1));
        return row;
    }

    private static TextView buildKillSingleButton(
            Context ctx, AuraDb db, JSONObject c, FrameLayout host,
            ModalOverlayBuilder.ModalActions actions, Runnable refresh) {
        String pkg = c.optString("pkg");
        String name = c.optString("name");
        TextView btn = makeActionPill(ctx, "Free", Color.rgb(255, 115, 115), host, actions);
        btn.setOnClickListener(v -> {
            SystemResourceMonitor.killSinglePackage(ctx, db, pkg);
            Toast.makeText(ctx, "Freed " + name, Toast.LENGTH_SHORT).show();
            refresh.run();
        });
        return btn;
    }

    private static LinearLayout buildHistoryColumn(Context ctx, JSONArray hist) {
        LinearLayout col = createGlassCard(ctx);
        col.addView(GlassTheme.makeText(ctx, "CLEANUP HISTORY", 10f, GlassTheme.GOLD_ACCENT, GlassTheme.FONT_BOLD, 1));
        if (hist == null || hist.length() == 0) {
            col.addView(GlassTheme.makeText(ctx, "No cleanups yet.", 11f, GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1));
            return col;
        }
        for (int i = 0; i < Math.min(5, hist.length()); i++) {
            JSONObject h = hist.optJSONObject(i);
            if (h != null) col.addView(buildHistoryEventRow(ctx, h));
        }
        return col;
    }

    private static LinearLayout buildHistoryEventRow(Context ctx, JSONObject h) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(ctx, 5), 0, dp(ctx, 5));
        int freed = Math.max(8, h.optInt("freed_mb", 40));
        String timeStr = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(h.optLong("timestamp_sec") * 1000L));
        String label = "+" + freed + " MB  \u00B7  " + h.optString("killed_apps") + "  (" + timeStr + ")";
        row.addView(buildMiniFreedBar(ctx, freed), rightMargin(ctx, 8));
        row.addView(GlassTheme.makeText(ctx, label, 10.5f, GlassTheme.TEXT_PRIMARY, GlassTheme.FONT_MEDIUM, 1));
        return row;
    }

    private static View buildMiniFreedBar(Context ctx, int freedMb) {
        View bar = new View(ctx);
        int w = dp(ctx, Math.min(48, Math.max(10, freedMb / 5)));
        bar.setLayoutParams(new LinearLayout.LayoutParams(w, dp(ctx, 6)));
        bar.setBackground(GlassTheme.solidRounded(Color.rgb(72, 219, 151), dp(ctx, 3)));
        return bar;
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
        TextView tv = GlassTheme.makeText(ctx, text, 10.5f, accentColor, GlassTheme.FONT_BOLD, 1);
        tv.setPadding(dp(ctx, 11), dp(ctx, 5), dp(ctx, 11), dp(ctx, 5));
        float r = dp(ctx, 9);
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
