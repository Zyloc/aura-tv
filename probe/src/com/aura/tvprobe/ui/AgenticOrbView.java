package com.aura.tvprobe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Frameless, floating Agentic Recognition Visualizer.
 * Renders an organic multi-layer neural waveform, counter-rotating orbital arcs,
 * and a breathing central core over a smoked-obsidian backdrop — with zero
 * rectangular alert boxes and zero third-party branding.
 */
public final class AgenticOrbView extends View {

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaintA = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaintB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePathA = new Path();
    private final Path wavePathB = new Path();
    private final RectF arcRect = new RectF();
    private final long startUptimeMs = SystemClock.uptimeMillis();

    private ValueAnimator animator;
    private float phase = 0f;
    private float cachedGlowRadius = -1f;

    public AgenticOrbView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        initWavePaints(d);
        initArcAndNodePaints(d);
    }

    private void initWavePaints(float d) {
        wavePaintA.setStyle(Paint.Style.STROKE);
        wavePaintA.setStrokeWidth(2.4f * d);
        wavePaintA.setColor(Color.argb(215, 129, 140, 248)); // Soft Indigo
        wavePaintB.setStyle(Paint.Style.STROKE);
        wavePaintB.setStrokeWidth(2.0f * d);
        wavePaintB.setColor(Color.argb(190, 56, 189, 248));  // Soft Cyan
    }

    private void initArcAndNodePaints(float d) {
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setStrokeWidth(2.6f * d);
        arcPaint.setColor(Color.argb(230, 192, 132, 252));   // Soft Violet
        corePaint.setStyle(Paint.Style.FILL);
        corePaint.setColor(Color.WHITE);
        nodePaint.setStyle(Paint.Style.FILL);
        nodePaint.setColor(Color.argb(235, 226, 232, 240));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isShown()) startLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && isEffectivelyVisible()) startLoop();
        else stopLoop();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isEffectivelyVisible()) {
            startLoop();
        } else {
            stopLoop();
        }
    }

    public void startLoop() {
        if (!isEffectivelyVisible()) return;
        postInvalidateOnAnimation();
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(60000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(this::onPhaseUpdate);
        animator.start();
    }

    private boolean isEffectivelyVisible() {
        if (getWindowVisibility() != VISIBLE || !isShown() || getAlpha() <= 0.05f) return false;
        android.view.ViewParent p = getParent();
        while (p instanceof View) {
            View v = (View) p;
            if (v.getVisibility() != VISIBLE || v.getAlpha() <= 0.05f) return false;
            p = v.getParent();
        }
        return true;
    }

    private void onPhaseUpdate(ValueAnimator a) {
        if (!isEffectivelyVisible()) {
            stopLoop();
            return;
        }
        phase = ((SystemClock.uptimeMillis() - startUptimeMs) % 360_000_000L) * 0.0026f;
        invalidate();
    }

    public void stopLoop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0 || !isEffectivelyVisible()) return;
        phase = ((SystemClock.uptimeMillis() - startUptimeMs) % 360_000_000L) * 0.0026f;
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float maxR = Math.min(getWidth(), getHeight()) * 0.44f;
        drawAllOrbLayers(canvas, cx, cy, maxR);
        if (isEffectivelyVisible()) postInvalidateOnAnimation();
    }

    private void drawAllOrbLayers(Canvas canvas, float cx, float cy, float maxR) {
        drawAmbientAura(canvas, cx, cy, maxR);
        drawNeuralRings(canvas, cx, cy, maxR);
        drawOrbitalArcs(canvas, cx, cy, maxR);
        drawSatelliteAndCoreNodes(canvas, cx, cy, maxR);
    }

    // 1. Breathing radial ambient aura
    private void drawAmbientAura(Canvas canvas, float cx, float cy, float maxR) {
        ensureGlowShader(cx, cy, maxR);
        float pulse = 0.88f + 0.12f * (float) Math.sin(phase * 2f);
        glowPaint.setAlpha((int) (255 * pulse));
        canvas.drawCircle(cx, cy, Math.max(8f, maxR * 1.12f * pulse), glowPaint);
    }

    private void ensureGlowShader(float cx, float cy, float maxR) {
        if (Math.abs(cachedGlowRadius - maxR) < 0.5f) return;
        cachedGlowRadius = maxR;
        int[] colors = {Color.argb(120, 99, 102, 241), Color.argb(55, 56, 189, 248), Color.TRANSPARENT};
        float[] stops = {0f, 0.58f, 1f};
        glowPaint.setShader(new RadialGradient(cx, cy, Math.max(8f, maxR * 1.24f), colors, stops, Shader.TileMode.CLAMP));
    }

    // 2. Morphing Agentic Neural Ring A & B
    private void drawNeuralRings(Canvas canvas, float cx, float cy, float maxR) {
        buildMorphPath(wavePathA, cx, cy, maxR * 0.72f, 3, phase, 0.13f);
        buildMorphPath(wavePathB, cx, cy, maxR * 0.66f, 4, -phase * 1.3f, 0.11f);
        canvas.drawPath(wavePathA, wavePaintA);
        canvas.drawPath(wavePathB, wavePaintB);
    }

    // 3. Counter-rotating orbital recognition arcs
    private void drawOrbitalArcs(Canvas canvas, float cx, float cy, float maxR) {
        float orbitR = maxR * 0.88f;
        arcRect.set(cx - orbitR, cy - orbitR, cx + orbitR, cy + orbitR);
        float deg = (float) Math.toDegrees(phase);
        arcPaint.setColor(Color.argb(225, 167, 139, 250));
        canvas.drawArc(arcRect, deg * 1.5f, 68f, false, arcPaint);
        arcPaint.setColor(Color.argb(210, 56, 189, 248));
        canvas.drawArc(arcRect, deg * 1.5f + 180f, 68f, false, arcPaint);
    }

    // 4. Orbiting satellite nodes & 5. Breathing central core node
    private void drawSatelliteAndCoreNodes(Canvas canvas, float cx, float cy, float maxR) {
        for (int i = 0; i < 3; i++) {
            double ang = phase * 1.2 + i * (Math.PI * 2.0 / 3.0);
            float nx = cx + (float) Math.cos(ang) * (maxR * 0.88f);
            float ny = cy + (float) Math.sin(ang) * (maxR * 0.88f);
            canvas.drawCircle(nx, ny, maxR * 0.065f, nodePaint);
        }
        float coreR = maxR * (0.19f + 0.045f * (float) Math.cos(phase * 3f));
        canvas.drawCircle(cx, cy, coreR, corePaint);
    }

    private static void buildMorphPath(Path out, float cx, float cy, float baseR,
                                       int lobes, float phase, float ampFrac) {
        out.reset();
        int steps = 64;
        for (int i = 0; i <= steps; i++) {
            double theta = (i * 2.0 * Math.PI) / steps;
            float r = baseR + computeWaveOffset(theta, baseR, lobes, phase, ampFrac);
            addMorphPoint(out, i, cx + (float) Math.cos(theta) * r, cy + (float) Math.sin(theta) * r);
        }
        out.close();
    }

    private static float computeWaveOffset(double theta, float baseR, int lobes, float phase, float ampFrac) {
        float w1 = (float) Math.sin(theta * lobes + phase) * (baseR * ampFrac);
        float w2 = (float) Math.cos(theta * (lobes + 2) - phase * 1.4f) * (baseR * ampFrac * 0.5f);
        return w1 + w2;
    }

    private static void addMorphPoint(Path out, int idx, float x, float y) {
        if (idx == 0) out.moveTo(x, y);
        else out.lineTo(x, y);
    }

    /**
     * Builds a frameless, borderless centered Agentic Recognition column
     * (Orb + floating status text + subtle phase subcaption).
     */
    public static LinearLayout buildFloatingLoaderColumn(Context ctx, int statusTextTag, int subTextTag) {
        LinearLayout col = createLoaderColumnContainer(ctx);
        addOrbChild(ctx, col);
        addMainStatusChild(ctx, col, statusTextTag);
        return col;
    }

    private static LinearLayout createLoaderColumnContainer(Context ctx) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return col;
    }

    private static void addOrbChild(Context ctx, LinearLayout col) {
        AgenticOrbView orb = new AgenticOrbView(ctx);
        int orbSize = GlassTheme.dp(ctx, 92);
        LinearLayout.LayoutParams orbLp = new LinearLayout.LayoutParams(orbSize, orbSize);
        orbLp.bottomMargin = GlassTheme.dp(ctx, 14);
        col.addView(orb, orbLp);
    }

    private static void addMainStatusChild(Context ctx, LinearLayout col, int statusTextTag) {
        TextView mainTxt = GlassTheme.makeText(ctx,
            "Finding best matches\u2026", 14.0f,
            Color.WHITE, GlassTheme.FONT_BOLD, 1);
        mainTxt.setGravity(Gravity.CENTER);
        if (statusTextTag != 0) mainTxt.setTag(statusTextTag);
        col.addView(mainTxt, GlassTheme.wrapParams());
    }
}
