package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.audiofx.Visualizer;
import android.util.AttributeSet;
import android.view.View;

/**
 * Lightweight, zero-allocation 60 FPS FFT audio spectrum visualizer for Screensaver and Home dock.
 */
public final class AudioSpectrumVisualizerView extends View {

    private static final int NUM_BARS = 48;
    private static final float SMOOTH_ATTACK = 0.32f;
    private static final float SMOOTH_DECAY = 0.14f;

    private final float[] targetLevels = new float[NUM_BARS];
    private final float[] currentLevels = new float[NUM_BARS];
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    private Visualizer visualizer;
    private boolean active;
    private float cornerRadiusPx;
    private float barGapPx;
    private float heightBoost = 1.0f;
    private float opacityScale = 1.0f;

    public AudioSpectrumVisualizerView(Context context) {
        this(context, null);
    }

    public AudioSpectrumVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        cornerRadiusPx = 4f * density;
        barGapPx = 4f * density;
        barPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;

        // Pre-allocate horizontal RGB neon spectrum gradient + vertical alpha mask (0 allocations in onDraw)
        int[] rgbColors = new int[]{
                0xFF00F5D4, // Neon Cyan
                0xFF00BBF9, // Electric Blue
                0xFF7B2CBF, // Neon Purple
                0xFFF15BB5, // Neon Magenta/Pink
                0xFFFF0055, // Neon Red
                0xFFFF9E00, // Vivid Orange
                0xFFFEE440, // Neon Yellow
                0xFF00F59B, // Neon Green
                0xFF00F5D4  // Back to Cyan
        };
        Shader horizontalRgb = new LinearGradient(
                0f, 0f, w, 0f,
                rgbColors, null, Shader.TileMode.CLAMP);

        // Higher visibility alpha gradient: ~90% at base, ~72% middle, soft fade at top tips
        int[] alphaMaskColors = new int[]{
                0x40FFFFFF, // Top peaks
                0xB8FFFFFF, // Middle (~0.72 alpha)
                0xE6FFFFFF  // Bottom edge (~0.90 alpha)
        };
        float[] alphaStops = new float[]{0f, 0.40f, 1f};
        Shader verticalFade = new LinearGradient(
                0f, 0f, 0f, h,
                alphaMaskColors, alphaStops, Shader.TileMode.CLAMP);

        barPaint.setShader(new ComposeShader(horizontalRgb, verticalFade, PorterDuff.Mode.DST_IN));
        barPaint.setAlpha(Math.round(255f * opacityScale));
    }

    public void setHeightBoost(float boost) {
        this.heightBoost = boost;
    }

    public void setOpacityScale(float scale) {
        this.opacityScale = Math.max(0.1f, Math.min(1f, scale));
        barPaint.setAlpha(Math.round(255f * this.opacityScale));
        invalidate();
    }

    /**
     * Starts or stops the native Visualizer depending on whether audio is actively playing.
     */
    public void setActive(boolean shouldBeActive) {
        if (shouldBeActive) {
            if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                releaseVisualizer();
                setVisibility(GONE);
                return;
            }
            if (visualizer == null) {
                initVisualizer();
            }
            if (visualizer != null && !active) {
                try {
                    visualizer.setEnabled(true);
                    active = true;
                    setVisibility(VISIBLE);
                    postInvalidateOnAnimation();
                } catch (Throwable t) {
                    releaseVisualizer();
                    setVisibility(GONE);
                }
            }
        } else {
            releaseVisualizer();
            for (int i = 0; i < NUM_BARS; i++) {
                targetLevels[i] = 0f;
            }
            postInvalidateOnAnimation();
        }
    }

    private void initVisualizer() {
        try {
            // Session 0 captures the global output mix on Android TV
            Visualizer v = new Visualizer(0);
            v.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = (range != null && range.length > 0) ? range[0] : 128; // 128 bytes min buffer
            v.setCaptureSize(captureSize);

            int maxRate = Visualizer.getMaxCaptureRate();
            int rate = Math.max(10000, maxRate / 2);

            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer vis, byte[] waveform, int samplingRate) {
                    // Unused: only FFT is captured
                }

                @Override
                public void onFftDataCapture(Visualizer vis, byte[] fft, int samplingRate) {
                    processFft(fft);
                }
            }, rate, false, true);

            visualizer = v;
        } catch (Throwable t) {
            visualizer = null;
        }
    }

    private void processFft(byte[] fft) {
        if (fft == null || fft.length < 4) return;
        int n = fft.length / 2; // Number of complex bins (64 for 128-byte buffer)
        int usableBins = Math.max(1, n - 1);
        for (int i = 0; i < NUM_BARS; i++) {
            float ratio = (float) i / (float) NUM_BARS;
            int bin = 1 + Math.min(usableBins - 1, Math.round((float) Math.pow(ratio, 1.35) * (usableBins - 1)));
            int idx = bin * 2;
            if (idx + 1 >= fft.length) idx = fft.length - 2;
            float r = fft[idx];
            float im = fft[idx + 1];
            float mag = (float) Math.hypot(r, im);
            float db = mag > 1f ? (float) (20.0 * Math.log10(mag)) : 0f;
            float freqBoost = (1.05f + 0.45f * ratio) * heightBoost;
            float raw = Math.min(1f, Math.max(0f, (db * freqBoost) / 34f));
            float norm = (float) Math.pow(raw, 0.84);
            targetLevels[i] = norm;
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        boolean stillAnimating = false;
        float totalGap = barGapPx * (NUM_BARS + 1);
        float barW = Math.max(2f, (w - totalGap) / (float) NUM_BARS);

        for (int i = 0; i < NUM_BARS; i++) {
            float target = active ? targetLevels[i] : 0f;
            float cur = currentLevels[i];
            float alpha = target > cur ? SMOOTH_ATTACK : SMOOTH_DECAY;
            cur += (target - cur) * alpha;
            if (cur < 0.004f && target == 0f) {
                cur = 0f;
            } else {
                stillAnimating = true;
            }
            currentLevels[i] = cur;

            if (cur > 0.004f) {
                float barH = Math.max(4f, cur * h);
                float left = barGapPx + i * (barW + barGapPx);
                float top = h - barH;
                barRect.set(left, top, left + barW, h + cornerRadiusPx);
                canvas.drawRoundRect(barRect, cornerRadiusPx, cornerRadiusPx, barPaint);
            }
        }

        if (active || stillAnimating) {
            postInvalidateOnAnimation();
        } else if (!active && getVisibility() == VISIBLE) {
            setVisibility(GONE);
        }
    }

    public void releaseVisualizer() {
        active = false;
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
            } catch (Throwable ignored) {
            }
            try {
                visualizer.release();
            } catch (Throwable ignored) {
            }
            visualizer = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseVisualizer();
        super.onDetachedFromWindow();
    }
}
