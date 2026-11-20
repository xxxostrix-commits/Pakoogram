package org.telegram.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import org.telegram.messenger.AndroidUtilities;

public final class DreamGlassEngine {

    public static final int TEXT_PRIMARY = 0xFF23354A;
    public static final int TEXT_SECONDARY = 0xFF475D75;
    public static final int TEXT_SUBTITLE = 0xFF6B8098;
    public static final int ICON_PRIMARY = 0xFF334C68;
    public static final int ICON_PASTEL = 0xFF7B8EC4;
    public static final int DIVIDER_COLOR = 0x266A88AE;
    public static final int ACCENT_PASTEL = 0xFF7E9CE6;

    private static final int VEIL_WHITE_BLUE = 0x96F7FBFF;
    private static final int VEIL_PINK_TINT = 0x42FCE4EE;
    private static final int RIM_PINK_WHITE = 0xD8FFF0F6;
    private static final int RIM_SKY_BLUE = 0xC8CDE8FF;
    private static final int TOUCH_GLOW_COLOR = 0x66FFFFFF;

    private static final float RIM_OMEGA = 14.5f;
    private static final float RIM_ZETA = 0.72f;
    private static final float PRESS_OMEGA = 24.0f;
    private static final float PRESS_ZETA = 0.78f;
    private static final float PRESSED_SCALE = 0.985f;

    private final Paint veilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint touchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF cardRect = new RectF();
    private final RectF scaledRect = new RectF();

    private final LinearGradient rimGradient;
    private final Matrix rimMatrix = new Matrix();

    private RadialGradient touchGradient;
    private float cachedTouchX = Float.NaN;
    private float cachedTouchY = Float.NaN;
    private float cachedTouchRadius = -1f;
    private int cachedTouchAlpha = -1;

    private float rimOffsetTarget = 0f;
    private float rimOffsetPos = 0f;
    private float rimOffsetVel = 0f;
    private float rimBoostTarget = 0f;
    private float rimBoostPos = 0f;
    private float rimBoostVel = 0f;

    private boolean pressed = false;
    private float pressPos = 0f;
    private float pressVel = 0f;
    private float touchX = 0f;
    private float touchY = 0f;
    private float touchIntensity = 0f;

    private float springPosOut;
    private float springVelOut;

    public DreamGlassEngine() {
        veilPaint.setStyle(Paint.Style.FILL);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(AndroidUtilities.dpf2(1.25f));
        touchPaint.setStyle(Paint.Style.FILL);

        rimGradient = new LinearGradient(
                -0.5f, 0f, 0.5f, 0f,
                new int[]{RIM_PINK_WHITE, 0xB8FFFFFF, RIM_SKY_BLUE},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        );
    }

    public void onScroll(float scrollDeltaPx) {
        if (Float.isNaN(scrollDeltaPx) || Float.isInfinite(scrollDeltaPx)) {
            return;
        }
        final float normalized = clamp(scrollDeltaPx / Math.max(1f, AndroidUtilities.dpf2(18f)), -1.5f, 1.5f);
        rimOffsetTarget = clamp(rimOffsetTarget + normalized * 0.55f, -1f, 1f);
        rimBoostTarget = clamp(Math.max(rimBoostTarget, Math.abs(normalized)), 0f, 1f);
    }

    public boolean step(float dtSeconds) {
        final float dt = clamp(dtSeconds, 0.001f, 0.05f);

        rimOffsetTarget *= (float) Math.exp(-8.5f * dt);
        rimBoostTarget *= (float) Math.exp(-7.5f * dt);

        stepSpring1D(rimOffsetPos, rimOffsetVel, dt, RIM_OMEGA, RIM_ZETA, rimOffsetTarget);
        rimOffsetPos = springPosOut;
        rimOffsetVel = springVelOut;

        stepSpring1D(rimBoostPos, rimBoostVel, dt, RIM_OMEGA * 0.9f, RIM_ZETA, rimBoostTarget);
        rimBoostPos = clamp(springPosOut, 0f, 1.2f);
        rimBoostVel = springVelOut;

        final float pressTarget = pressed ? 1f : 0f;
        stepSpring1D(pressPos, pressVel, dt, PRESS_OMEGA, PRESS_ZETA, pressTarget);
        pressPos = clamp(springPosOut, 0f, 1.15f);
        pressVel = springVelOut;

        if (pressed) {
            touchIntensity = Math.min(1f, touchIntensity + dt * 9f);
        } else {
            touchIntensity = Math.max(0f, touchIntensity - dt * 5.5f);
        }

        return Math.abs(rimOffsetPos) > 0.002f
                || Math.abs(rimOffsetVel) > 0.005f
                || Math.abs(rimBoostPos) > 0.002f
                || Math.abs(rimBoostVel) > 0.005f
                || Math.abs(pressPos - pressTarget) > 0.001f
                || Math.abs(pressVel) > 0.005f
                || touchIntensity > 0.002f;
    }

    public void onTouchDown(float x, float y) {
        pressed = true;
        touchX = x;
        touchY = y;
        touchIntensity = Math.max(touchIntensity, 0.35f);
    }

    public void onTouchMove(float x, float y) {
        if (!pressed) {
            return;
        }
        touchX = x;
        touchY = y;
    }

    public void onTouchUp() {
        pressed = false;
    }

    public float getPressScale() {
        final float clampedPress = clamp(pressPos, 0f, 1.1f);
        return 1f - (1f - PRESSED_SCALE) * clampedPress;
    }

    public void drawGlass(Canvas canvas, RectF bounds, float cornerRadiusDp,
                          float viewportW, float viewportH, float opacityMultiplier) {
        if (canvas == null || bounds == null || bounds.width() <= 1f || bounds.height() <= 1f) {
            return;
        }

        final float opacity = clamp(opacityMultiplier, 0.25f, 1.0f);
        final boolean isTouchedCard = (pressed || touchIntensity > 0.005f)
                && bounds.contains(touchX, touchY);
        final float scale = isTouchedCard ? getPressScale() : 1f;

        cardRect.set(bounds);
        if (Math.abs(scale - 1f) > 0.0001f) {
            final float cx = cardRect.centerX();
            final float cy = cardRect.centerY();
            final float halfW = cardRect.width() * 0.5f * scale;
            final float halfH = cardRect.height() * 0.5f * scale;
            scaledRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        } else {
            scaledRect.set(cardRect);
        }

        final float radiusPx = Math.min(
                AndroidUtilities.dpf2(cornerRadiusDp),
                Math.min(scaledRect.width(), scaledRect.height()) * 0.5f
        );

        final int saveCount = canvas.save();
        clipPath.rewind();
        clipPath.addRoundRect(scaledRect, radiusPx, radiusPx, Path.Direction.CW);
        canvas.clipPath(clipPath);

        DreamCloudsEngine.drawBlurredSlice(canvas, scaledRect, viewportW, viewportH);

        veilPaint.setColor(applyAlphaMultiplier(VEIL_WHITE_BLUE, opacity));
        canvas.drawRect(scaledRect, veilPaint);

        veilPaint.setColor(applyAlphaMultiplier(VEIL_PINK_TINT, opacity * 0.85f));
        canvas.drawRect(scaledRect, veilPaint);

        if (isTouchedCard && touchIntensity > 0.005f) {
            drawTouchHighlight(canvas, scaledRect, touchIntensity * opacity);
        }

        drawRimHighlight(canvas, scaledRect, radiusPx, opacity);

        canvas.restoreToCount(saveCount);
    }

    private void drawRimHighlight(Canvas canvas, RectF rect, float radiusPx, float opacity) {
        final float rimWidthDp = clamp(1.15f + 0.35f * Math.min(1f, rimBoostPos), 1.0f, 1.5f);
        rimPaint.setStrokeWidth(AndroidUtilities.dpf2(rimWidthDp));

        final float angleDeg = 32f + rimOffsetPos * 28f;
        final float span = Math.max(1f, (rect.width() + rect.height()) * 0.76f);
        final int alpha255 = Math.round(clamp((0.65f + 0.35f * Math.min(1f, rimBoostPos)) * opacity, 0.2f, 1f) * 255f);

        rimMatrix.reset();
        rimMatrix.setScale(span, 1f);
        rimMatrix.postRotate(angleDeg);
        rimMatrix.postTranslate(rect.centerX(), rect.centerY());
        rimGradient.setLocalMatrix(rimMatrix);

        rimPaint.setShader(rimGradient);
        rimPaint.setAlpha(alpha255);
        canvas.drawRoundRect(rect, radiusPx, radiusPx, rimPaint);
        rimPaint.setShader(null);
        rimPaint.setAlpha(255);
    }

    private void drawTouchHighlight(Canvas canvas, RectF rect, float strength) {
        final float radius = Math.max(AndroidUtilities.dpf2(48f), Math.max(rect.width(), rect.height()) * 0.55f);
        final int alpha = Math.round(clamp(strength, 0f, 1f) * Color.alpha(TOUCH_GLOW_COLOR));
        if (alpha <= 0) {
            return;
        }
        if (touchGradient == null
                || cachedTouchX != touchX || cachedTouchY != touchY
                || cachedTouchRadius != radius || cachedTouchAlpha != alpha) {
            cachedTouchX = touchX;
            cachedTouchY = touchY;
            cachedTouchRadius = radius;
            cachedTouchAlpha = alpha;
            touchGradient = new RadialGradient(
                    touchX, touchY, radius,
                    new int[]{applyAlpha(TOUCH_GLOW_COLOR, alpha), 0x00FFFFFF},
                    new float[]{0f, 1f},
                    Shader.TileMode.CLAMP
            );
        }
        touchPaint.setShader(touchGradient);
        canvas.drawRect(rect, touchPaint);
        touchPaint.setShader(null);
    }

    private void stepSpring1D(float pos, float vel, float dt, float omega, float zeta, float target) {
        final float displacement = pos - target;
        final float omegaSq = omega * omega;
        final float accel = -omegaSq * displacement - 2f * zeta * omega * vel;
        final float nextVel = vel + accel * dt;
        final float nextPos = pos + nextVel * dt;
        this.springPosOut = nextPos;
        this.springVelOut = nextVel;
    }

    private static int applyAlphaMultiplier(int color, float mult) {
        final int baseAlpha = Color.alpha(color);
        final int outAlpha = Math.max(0, Math.min(255, Math.round(baseAlpha * clamp(mult, 0f, 1f))));
        return (outAlpha << 24) | (color & 0x00FFFFFF);
    }

    private static int applyAlpha(int color, int alpha255) {
        final int clamped = Math.max(0, Math.min(255, alpha255));
        return (clamped << 24) | (color & 0x00FFFFFF);
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}