package com.aura.tvprobe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import org.json.JSONArray;

/**
 * Custom Canvas Telemetry Visualizations:
 * 1. Circular Arc Ring Gauge (RAM % and CPU %)
 * 2. Dual Time-Series Area & Line Chart (RAM + CPU History)
 * 3. Compact Left-Drawer Sparkline
 */
public final class TelemetryGraphView {

    private TelemetryGraphView() {}

    public static View createRingGauge(
            Context ctx, int pct, String label, String sub, int color) {
        return new RingGaugeView(ctx, pct, label, sub, color);
    }

    public static View createDualAreaChart(
            Context ctx, JSONArray ramSeries, JSONArray cpuSeries) {
        return new DualChartView(ctx, toIntArray(ramSeries, 74), toIntArray(cpuSeries, 22), false);
    }

    public static View createMiniSparkline(
            Context ctx, JSONArray ramSeries, JSONArray cpuSeries) {
        return new DualChartView(ctx, toIntArray(ramSeries, 74), toIntArray(cpuSeries, 22), true);
    }

    private static int[] toIntArray(JSONArray arr, int fallback) {
        if (arr == null || arr.length() < 2) return new int[]{fallback, fallback};
        int[] out = new int[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            out[i] = Math.max(2, Math.min(99, arr.optInt(i, fallback)));
        }
        return out;
    }

    private static final class RingGaugeView extends View {
        private final int pct;
        private final String label;
        private final String sub;
        private final int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        RingGaugeView(Context ctx, int pct, String label, String sub, int color) {
            super(ctx);
            this.pct = Math.max(0, Math.min(100, pct));
            this.label = label;
            this.sub = sub;
            this.color = color;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float pad = GlassTheme.dp(getContext(), 9);
            float size = Math.min(getWidth(), getHeight()) - pad * 2f;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            oval.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
            drawRingArcs(c);
            drawRingLabels(c, cx, cy);
        }

        private void drawRingArcs(Canvas c) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(GlassTheme.dp(getContext(), 7));
            paint.setColor(Color.argb(40, 255, 255, 255));
            c.drawArc(oval, 135f, 270f, false, paint);
            paint.setColor(color);
            c.drawArc(oval, 135f, (270f * pct) / 100f, false, paint);
        }

        private void drawRingLabels(Canvas c, float cx, float cy) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(GlassTheme.FONT_BOLD);
            paint.setTextSize(GlassTheme.dp(getContext(), 16));
            paint.setColor(GlassTheme.TEXT_PRIMARY);
            c.drawText(pct + "%", cx, cy + GlassTheme.dp(getContext(), 2), paint);
            drawRingSubtext(c, cx, cy);
        }

        private void drawRingSubtext(Canvas c, float cx, float cy) {
            paint.setTextSize(GlassTheme.dp(getContext(), 8.5f));
            paint.setColor(color);
            c.drawText(label, cx, cy - GlassTheme.dp(getContext(), 14), paint);
            paint.setTypeface(GlassTheme.FONT_MEDIUM);
            paint.setColor(GlassTheme.TEXT_SECONDARY);
            c.drawText(sub, cx, cy + GlassTheme.dp(getContext(), 16), paint);
        }
    }

    private static final class DualChartView extends View {
        private final int[] ram;
        private final int[] cpu;
        private final boolean mini;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        DualChartView(Context ctx, int[] ram, int[] cpu, boolean mini) {
            super(ctx);
            this.ram = ram;
            this.cpu = cpu;
            this.mini = mini;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            if (w <= 10 || h <= 10) return;
            if (!mini) drawGridAndLegend(c, w, h);
            drawSeriesAreaAndLine(c, ram, w, h, Color.rgb(72, 219, 151), 55);
            drawSeriesAreaAndLine(c, cpu, w, h, Color.rgb(56, 189, 248), 40);
        }

        private void drawGridAndLegend(Canvas c, float w, float h) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            p.setColor(Color.argb(28, 255, 255, 255));
            for (int i = 1; i <= 3; i++) {
                float y = h * (i / 4f);
                c.drawLine(0, y, w, y, p);
            }
            drawLegendText(c, w);
        }

        private void drawLegendText(Canvas c, float w) {
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(GlassTheme.FONT_BOLD);
            p.setTextSize(GlassTheme.dp(getContext(), 9f));
            p.setTextAlign(Paint.Align.RIGHT);
            p.setColor(Color.rgb(72, 219, 151));
            c.drawText("\u25CF RAM " + ram[ram.length - 1] + "%", w - GlassTheme.dp(getContext(), 72), GlassTheme.dp(getContext(), 11), p);
            p.setColor(Color.rgb(56, 189, 248));
            c.drawText("\u25CF CPU " + cpu[cpu.length - 1] + "%", w - GlassTheme.dp(getContext(), 4), GlassTheme.dp(getContext(), 11), p);
        }

        private void drawSeriesAreaAndLine(Canvas c, int[] data, float w, float h, int col, int alpha) {
            float topPad = mini ? 2f : GlassTheme.dp(getContext(), 16);
            float botPad = mini ? 2f : GlassTheme.dp(getContext(), 6);
            Path line = buildLinePath(data, w, h, topPad, botPad);
            Path area = new Path(line);
            area.lineTo(w, h - botPad);
            area.lineTo(0, h - botPad);
            area.close();
            fillAreaPath(c, area, h, col, alpha);
            strokeLinePath(c, line, data, w, h, topPad, botPad, col);
        }

        private Path buildLinePath(int[] data, float w, float h, float topPad, float botPad) {
            Path path = new Path();
            float usableH = Math.max(4f, h - topPad - botPad);
            float stepX = w / Math.max(1, data.length - 1);
            for (int i = 0; i < data.length; i++) {
                float x = i * stepX;
                float y = topPad + usableH * (1f - (data[i] / 100f));
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            return path;
        }

        private void fillAreaPath(Canvas c, Path area, float h, int col, int alpha) {
            p.setStyle(Paint.Style.FILL);
            int topCol = Color.argb(alpha, Color.red(col), Color.green(col), Color.blue(col));
            int botCol = Color.argb(0, Color.red(col), Color.green(col), Color.blue(col));
            p.setShader(new LinearGradient(0, 0, 0, h, topCol, botCol, Shader.TileMode.CLAMP));
            c.drawPath(area, p);
            p.setShader(null);
        }

        private void strokeLinePath(
                Canvas c, Path line, int[] data, float w, float h, float topPad, float botPad, int col) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(GlassTheme.dp(getContext(), mini ? 1.5f : 2.2f));
            p.setColor(col);
            c.drawPath(line, p);
            if (!mini) drawEndDot(c, data[data.length - 1], w, h, topPad, botPad, col);
        }

        private void drawEndDot(Canvas c, int lastVal, float w, float h, float topPad, float botPad, int col) {
            float usableH = Math.max(4f, h - topPad - botPad);
            float y = topPad + usableH * (1f - (lastVal / 100f));
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            c.drawCircle(w - GlassTheme.dp(getContext(), 3), y, GlassTheme.dp(getContext(), 3.2f), p);
        }
    }
}
