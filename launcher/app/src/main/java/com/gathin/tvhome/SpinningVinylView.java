package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

/**
 * Circular spinning vinyl record view that displays the playing song's cover art
 * (or falls back to the playing app's icon) inside a rotating vinyl disc.
 */
public final class SpinningVinylView extends View {

    private final Paint vinylPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();

    private Bitmap currentBitmap;
    private BitmapShader coverShader;
    private float angle = 0f;
    private boolean spinning = false;

    public SpinningVinylView(Context context) {
        this(context, null);
    }

    public SpinningVinylView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;

        vinylPaint.setStyle(Paint.Style.FILL);
        vinylPaint.setColor(0xFF121216);

        groovePaint.setStyle(Paint.Style.STROKE);
        groovePaint.setStrokeWidth(Math.max(1f, 0.8f * density));
        groovePaint.setColor(0x2EFFFFFF);

        sheenPaint.setStyle(Paint.Style.FILL);

        coverPaint.setStyle(Paint.Style.FILL);
        coverPaint.setFilterBitmap(true);

        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1f, 1.1f * density));
        rimPaint.setColor(0x66FFFFFF);

        holePaint.setStyle(Paint.Style.FILL);
        holePaint.setColor(0xFF121216);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            sheenPaint.setShader(new LinearGradient(
                    0f, 0f, w, h,
                    new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF},
                    new float[]{0.32f, 0.50f, 0.68f},
                    Shader.TileMode.CLAMP));
            updateCoverShader();
        }
    }

    public void setArtworkOrIcon(Bitmap artwork, Drawable fallbackIcon) {
        Bitmap target = null;
        if (artwork != null && !artwork.isRecycled()) {
            target = artwork;
        } else if (fallbackIcon != null) {
            target = drawableToBitmap(fallbackIcon);
        }
        if (target != currentBitmap) {
            currentBitmap = target;
            if (target != null && !target.isRecycled()) {
                coverShader = new BitmapShader(target, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                updateCoverShader();
            } else {
                coverShader = null;
                coverPaint.setShader(null);
            }
            invalidate();
        }
    }

    private void updateCoverShader() {
        if (coverShader == null || currentBitmap == null || currentBitmap.isRecycled()) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float radius = Math.min(w, h) / 2f;
        float coverRadius = radius * 0.72f;
        float diameter = coverRadius * 2f;
        int bw = currentBitmap.getWidth();
        int bh = currentBitmap.getHeight();
        if (bw <= 0 || bh <= 0) return;

        float scale = Math.max(diameter / (float) bw, diameter / (float) bh);
        float dx = (w - bw * scale) * 0.5f;
        float dy = (h - bh * scale) * 0.5f;
        shaderMatrix.reset();
        shaderMatrix.setScale(scale, scale);
        shaderMatrix.postTranslate(dx, dy);
        coverShader.setLocalMatrix(shaderMatrix);
        coverPaint.setShader(coverShader);
    }

    private Bitmap drawableToBitmap(Drawable d) {
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) return b;
        }
        int size = 96;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return bmp;
    }

    public void setSpinning(boolean shouldSpin) {
        if (this.spinning == shouldSpin) return;
        this.spinning = shouldSpin;
        if (shouldSpin) {
            postInvalidateOnAnimation();
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
        float r = Math.min(cx, cy) - 1f;
        if (r <= 2f) return;

        canvas.save();
        canvas.rotate(angle, cx, cy);

        // Outer vinyl disc
        canvas.drawCircle(cx, cy, r, vinylPaint);
        canvas.drawCircle(cx, cy, r, sheenPaint);
        canvas.drawCircle(cx, cy, r * 0.90f, groovePaint);
        canvas.drawCircle(cx, cy, r * 0.81f, groovePaint);
        canvas.drawCircle(cx, cy, r, rimPaint);

        // Inner circular album cover or app icon
        float coverR = r * 0.72f;
        if (coverPaint.getShader() != null) {
            canvas.drawCircle(cx, cy, coverR, coverPaint);
        } else {
            vinylPaint.setColor(0xFF282832);
            canvas.drawCircle(cx, cy, coverR, vinylPaint);
            vinylPaint.setColor(0xFF121216);
        }
        canvas.drawCircle(cx, cy, coverR, rimPaint);

        // Center vinyl spindle hole
        float holeR = r * 0.14f;
        canvas.drawCircle(cx, cy, holeR, holePaint);
        canvas.drawCircle(cx, cy, holeR, rimPaint);

        canvas.restore();

        if (spinning) {
            angle = (angle + 1.3f) % 360f;
            postInvalidateOnAnimation();
        }
    }
}
