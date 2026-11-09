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

    private ValueAnimator animator;
    private float phase = 0f;

    public AgenticOrbView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;

        wavePaintA.setStyle(Paint.Style.STROKE);
        wavePaintA.setStrokeWidth(2.4f * d);
        wavePaintA.setColor(Color.argb(215, 129, 140, 248)); // Soft Indigo

        wavePaintB.setStyle(Paint.Style.STROKE);
        wavePaintB.setStrokeWidth(2.0f * d);
        wavePaintB.setColor(Color.argb(190, 56, 189, 248));  // Soft Cyan

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
        startLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isShown()) {
            startLoop();
        } else {
            stopLoop();
        }
    }

    public void startLoop() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2.0));
        animator.setDuration(2400);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
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
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float maxR = Math.min(w, h) * 0.44f;

        // 1. Breathing radial ambient aura
        float pulse = 0.88f + 0.12f * (float) Math.sin(phase * 2f);
        float glowR = Math.max(8f, maxR * 1.12f * pulse);
        glowPaint.setShader(new RadialGradient(
            cx, cy, glowR,
            new int[]{
                Color.argb(120, 99, 102, 241),
                Color.argb(55, 56, 189, 248),
                Color.TRANSPARENT
            },
            new float[]{0f, 0.58f, 1f},
            Shader.TileMode.CLAMP
        ));
        canvas.drawCircle(cx, cy, glowR, glowPaint);

        // 2. Morphing Agentic Neural Ring A & B
        buildMorphPath(wavePathA, cx, cy, maxR * 0.72f, 3, phase, 0.13f);
        buildMorphPath(wavePathB, cx, cy, maxR * 0.66f, 4, -phase * 1.3f, 0.11f);
        canvas.drawPath(wavePathA, wavePaintA);
        canvas.drawPath(wavePathB, wavePaintB);

        // 3. Counter-rotating orbital recognition arcs
        float orbitR = maxR * 0.88f;
        arcRect.set(cx - orbitR, cy - orbitR, cx + orbitR, cy + orbitR);
        float deg = (float) Math.toDegrees(phase);
        arcPaint.setColor(Color.argb(225, 167, 139, 250));
        canvas.drawArc(arcRect, deg * 1.5f, 68f, false, arcPaint);
        arcPaint.setColor(Color.argb(210, 56, 189, 248));
        canvas.drawArc(arcRect, deg * 1.5f + 180f, 68f, false, arcPaint);

        // 4. Orbiting satellite nodes
        for (int i = 0; i < 3; i++) {
            double ang = phase * 1.2 + i * (Math.PI * 2.0 / 3.0);
            float nx = cx + (float) Math.cos(ang) * (maxR * 0.88f);
            float ny = cy + (float) Math.sin(ang) * (maxR * 0.88f);
            canvas.drawCircle(nx, ny, maxR * 0.065f, nodePaint);
        }

        // 5. Breathing central core node
        float coreR = maxR * (0.19f + 0.045f * (float) Math.cos(phase * 3f));
        canvas.drawCircle(cx, cy, coreR, corePaint);
    }

    private static void buildMorphPath(Path out, float cx, float cy, float baseR,
                                       int lobes, float phase, float ampFrac) {
        out.reset();
        int steps = 64;
        for (int i = 0; i <= steps; i++) {
            double theta = (i * 2.0 * Math.PI) / steps;
            float wave = (float) Math.sin(theta * lobes + phase) * (baseR * ampFrac)
                       + (float) Math.cos(theta * (lobes + 2) - phase * 1.4f) * (baseR * ampFrac * 0.5f);
            float r = baseR + wave;
            float x = cx + (float) Math.cos(theta) * r;
            float y = cy + (float) Math.sin(theta) * r;
            if (i == 0) out.moveTo(x, y);
            else out.lineTo(x, y);
        }
        out.close();
    }

    /**
     * Builds a frameless, borderless centered Agentic Recognition column
     * (Orb + floating status text + subtle phase subcaption).
     */
    public static LinearLayout buildFloatingLoaderColumn(Context ctx, int statusTextTag, int subTextTag) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setClipChildren(false);
        col.setClipToPadding(false);

        AgenticOrbView orb = new AgenticOrbView(ctx);
        int orbSize = GlassTheme.dp(ctx, 92);
        LinearLayout.LayoutParams orbLp = new LinearLayout.LayoutParams(orbSize, orbSize);
        orbLp.bottomMargin = GlassTheme.dp(ctx, 14);
        col.addView(orb, orbLp);

        TextView badge = GlassTheme.makeText(ctx, "AURA AGENTIC RECOGNITION", 9.5f,
            Color.argb(210, 148, 163, 184), GlassTheme.FONT_BOLD, 1);
        badge.setLetterSpacing(0.14f);
        badge.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bLp = GlassTheme.wrapParams();
        bLp.bottomMargin = GlassTheme.dp(ctx, 6);
        col.addView(badge, bLp);

        TextView mainTxt = GlassTheme.makeText(ctx,
            "Analyzing your watch history & taste profile\u2026", 14.5f,
            Color.WHITE, GlassTheme.FONT_BOLD, 2);
        mainTxt.setGravity(Gravity.CENTER);
        if (statusTextTag != 0) mainTxt.setTag(statusTextTag);
        col.addView(mainTxt, GlassTheme.wrapParams());

        TextView subTxt = GlassTheme.makeText(ctx,
            "Synthesizing deep-linked matches across your streaming apps", 10.5f,
            GlassTheme.TEXT_MUTED, GlassTheme.FONT_MEDIUM, 1);
        subTxt.setGravity(Gravity.CENTER);
        if (subTextTag != 0) subTxt.setTag(subTextTag);
        LinearLayout.LayoutParams sLp = GlassTheme.wrapParams();
        sLp.topMargin = GlassTheme.dp(ctx, 4);
        col.addView(subTxt, sLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER);
        col.setLayoutParams(lp);
        return col;
    }
}
