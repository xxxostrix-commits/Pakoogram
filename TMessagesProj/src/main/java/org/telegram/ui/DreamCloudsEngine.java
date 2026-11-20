package org.telegram.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.util.Random;

public final class DreamCloudsEngine {

    public static final int SKY_TOP_COLOR = 0xFFD9EEFF;
    public static final int SKY_CENTER_COLOR = 0xFFFBF9FC;
    public static final int SKY_HORIZON_COLOR = 0xFFFCE2EC;

    private static final int FAR_CLOUD_COUNT = 4;
    private static final int MID_CLOUD_COUNT = 5;
    private static final int NEAR_CLOUD_COUNT = 4;

    private static final int BLUR_SCALE = 8;
    private static final int STACK_BLUR_RADIUS = 10;

    private static final Paint skyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint puffPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint mistPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint blurDrawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private static final RectF skyRect = new RectF();
    private static final RectF puffRect = new RectF();
    private static final Rect blurSrcRect = new Rect();
    private static final RectF blurDstRect = new RectF();

    private static LinearGradient skyGradient;
    private static float cachedSkyWidth = -1f;
    private static float cachedSkyHeight = -1f;

    private static float scrollOffsetPx = 0f;
    private static float lastBlurPhase = -1f;

    private static final CloudNode[] FAR_CLOUDS = createLayer(FAR_CLOUD_COUNT, 0x11A8C7E8L, 0.32f, 0.52f, 0.14f, 0.24f);
    private static final CloudNode[] MID_CLOUDS = createLayer(MID_CLOUD_COUNT, 0x22E5B8CEL, 0.24f, 0.44f, 0.10f, 0.18f);
    private static final CloudNode[] NEAR_CLOUDS = createLayer(NEAR_CLOUD_COUNT, 0x33B8D8F8L, 0.20f, 0.38f, 0.08f, 0.15f);

    private static Bitmap blurBitmap;
    private static Canvas blurCanvas;
    private static int blurViewportWidth;
    private static int blurViewportHeight;
    private static boolean blurDirty = true;

    private DreamCloudsEngine() {
    }

    public static void setScrollOffset(float offsetPx) {
        if (Float.isNaN(offsetPx) || Float.isInfinite(offsetPx)) {
            return;
        }
        if (Math.abs(scrollOffsetPx - offsetPx) > 0.05f) {
            scrollOffsetPx = offsetPx;
            blurDirty = true;
        }
    }

    public static void onScrollDelta(float deltaY) {
        if (Float.isNaN(deltaY) || Float.isInfinite(deltaY) || deltaY == 0f) {
            return;
        }
        scrollOffsetPx += deltaY;
        blurDirty = true;
    }

    public static void invalidateBlurBuffer() {
        blurDirty = true;
    }

    public static void draw(Canvas canvas, float width, float height, float phase, int unusedTint) {
        draw(canvas, width, height, phase);
    }

    public static void draw(Canvas canvas, float width, float height, float phase) {
        if (canvas == null || width <= 0f || height <= 0f) {
            return;
        }
        drawSkyAndClouds(canvas, width, height, phase, scrollOffsetPx);

        final int w = Math.round(width);
        final int h = Math.round(height);
        boolean phaseChanged = lastBlurPhase < 0f || Math.abs(phase - lastBlurPhase) > 0.002f;
        if (blurDirty || phaseChanged || blurBitmap == null || blurViewportWidth != w || blurViewportHeight != h) {
            updateBlurBuffer(width, height, phase, scrollOffsetPx, w, h);
            lastBlurPhase = phase;
            blurDirty = false;
        }
    }

    public static Bitmap getBlurredBackground() {
        return blurBitmap;
    }

    public static int getBlurViewportWidth() {
        return blurViewportWidth;
    }

    public static int getBlurViewportHeight() {
        return blurViewportHeight;
    }

    public static void drawBlurredSlice(Canvas canvas, RectF targetRect, float viewportW, float viewportH) {
        if (canvas == null || targetRect == null || blurBitmap == null || blurBitmap.isRecycled()) {
            return;
        }
        if (viewportW <= 0f || viewportH <= 0f || targetRect.width() <= 0f || targetRect.height() <= 0f) {
            return;
        }

        final int bw = blurBitmap.getWidth();
        final int bh = blurBitmap.getHeight();
        final float normLeft = clamp01(targetRect.left / viewportW);
        final float normTop = clamp01(targetRect.top / viewportH);
        final float normRight = clamp01(targetRect.right / viewportW);
        final float normBottom = clamp01(targetRect.bottom / viewportH);

        int srcLeft = Math.round(normLeft * bw);
        int srcTop = Math.round(normTop * bh);
        int srcRight = Math.round(normRight * bw);
        int srcBottom = Math.round(normBottom * bh);

        if (srcRight <= srcLeft) {
            srcRight = Math.min(bw, srcLeft + 1);
        }
        if (srcBottom <= srcTop) {
            srcBottom = Math.min(bh, srcTop + 1);
        }

        blurSrcRect.set(srcLeft, srcTop, srcRight, srcBottom);
        blurDstRect.set(targetRect);
        canvas.drawBitmap(blurBitmap, blurSrcRect, blurDstRect, blurDrawPaint);
    }

    private static void drawSkyAndClouds(Canvas canvas, float width, float height, float phase, float scrollPx) {
        ensureSkyGradient(width, height);
        skyRect.set(0f, 0f, width, height);
        skyPaint.setShader(skyGradient);
        canvas.drawRect(skyRect, skyPaint);
        skyPaint.setShader(null);

        drawMistLayer(canvas, FAR_CLOUDS, width, height, phase, scrollPx, 0.14f, 0.25f);
        drawVolumetricLayer(canvas, MID_CLOUDS, width, height, phase, scrollPx, 0.35f, 0.55f);
        drawNearLayer(canvas, NEAR_CLOUDS, width, height, phase, scrollPx, 0.65f, 0.85f);
    }

    private static void ensureSkyGradient(float width, float height) {
        if (skyGradient != null && cachedSkyWidth == width && cachedSkyHeight == height) {
            return;
        }
        cachedSkyWidth = width;
        cachedSkyHeight = height;
        skyGradient = new LinearGradient(
                0f, 0f, 0f, height,
                new int[]{SKY_TOP_COLOR, SKY_CENTER_COLOR, SKY_HORIZON_COLOR},
                new float[]{0f, 0.54f, 1f},
                Shader.TileMode.CLAMP
        );
    }

    private static void drawMistLayer(Canvas canvas, CloudNode[] layer, float width, float height,
                                      float phase, float scrollPx, float speedMult, float parallaxFactor) {
        final float parallaxY = -scrollPx * parallaxFactor;
        for (int i = 0; i < layer.length; i++) {
            final CloudNode c = layer[i];
            final float driftX = wrapDrift(phase * c.driftSpeed * speedMult + c.phaseOffset,
                    width + c.baseWidth * 1.6f) - c.baseWidth * 0.8f;
            final float swayY = (float) Math.sin((phase * 2f + c.phaseOffset) * Math.PI * 2.0)
                    * AndroidUtilities.dpf2(2.5f);
            final float baseY = c.anchorY * height + swayY + parallaxY;

            for (int p = 0; p < c.puffCount; p++) {
                final float px = driftX + c.puffOffsetX[p] * c.baseWidth;
                final float py = baseY + c.puffOffsetY[p] * c.baseHeight;
                final float r = c.puffRadius[p] * c.baseHeight;

                mistPaint.setColor(0x38FFFFFF);
                canvas.drawCircle(px, py - r * 0.14f, r * 1.15f, mistPaint);
                mistPaint.setColor(0x2EE8D8F5);
                canvas.drawCircle(px, py + r * 0.24f, r * 1.05f, mistPaint);
            }
        }
    }

    private static void drawVolumetricLayer(Canvas canvas, CloudNode[] layer, float width, float height,
                                            float phase, float scrollPx, float speedMult, float parallaxFactor) {
        final float parallaxY = -scrollPx * parallaxFactor;
        for (int i = 0; i < layer.length; i++) {
            final CloudNode c = layer[i];
            final float span = width + c.baseWidth * 1.8f;
            final float driftX = wrapDrift(phase * c.driftSpeed * speedMult + c.phaseOffset, span)
                    - c.baseWidth * 0.9f;
            final float swayY = (float) Math.sin((phase * 2f + c.phaseOffset * 1.3f) * Math.PI * 2.0)
                    * AndroidUtilities.dpf2(4.0f);
            final float baseY = c.anchorY * height + swayY + parallaxY;

            for (int p = 0; p < c.puffCount; p++) {
                final float px = driftX + c.puffOffsetX[p] * c.baseWidth;
                final float py = baseY + c.puffOffsetY[p] * c.baseHeight;
                final float r = c.puffRadius[p] * c.baseHeight;

                puffPaint.setColor(0x66E7C6DC);
                canvas.drawCircle(px, py + r * 0.34f, r * 0.96f, puffPaint);
                puffPaint.setColor(0x52CBBCE8);
                canvas.drawCircle(px + r * 0.12f, py + r * 0.46f, r * 0.82f, puffPaint);

                puffPaint.setColor(0xCCF7FBFF);
                canvas.drawCircle(px, py + r * 0.04f, r * 0.94f, puffPaint);

                puffPaint.setColor(0xEEFFFFFF);
                canvas.drawCircle(px - r * 0.12f, py - r * 0.26f, r * 0.72f, puffPaint);
            }
        }
    }

    private static void drawNearLayer(Canvas canvas, CloudNode[] layer, float width, float height,
                                      float phase, float scrollPx, float speedMult, float parallaxFactor) {
        final float parallaxY = -scrollPx * parallaxFactor;
        for (int i = 0; i < layer.length; i++) {
            final CloudNode c = layer[i];
            final float span = width + c.baseWidth * 1.8f;
            final float driftX = wrapDrift(phase * c.driftSpeed * speedMult * 1.45f + c.phaseOffset, span)
                    - c.baseWidth * 0.9f;
            final float swayY = (float) Math.sin((phase * 3f + c.phaseOffset * 1.7f) * Math.PI * 2.0)
                    * AndroidUtilities.dpf2(6.0f);
            final float baseY = c.anchorY * height + swayY + parallaxY;

            for (int p = 0; p < c.puffCount; p++) {
                final float px = driftX + c.puffOffsetX[p] * c.baseWidth;
                final float py = baseY + c.puffOffsetY[p] * c.baseHeight;
                final float rx = c.puffRadius[p] * c.baseHeight * 1.18f;
                final float ry = c.puffRadius[p] * c.baseHeight * 0.78f;

                puffRect.set(px - rx, py - ry, px + rx, py + ry);
                puffPaint.setColor(0x78FFFFFF);
                canvas.drawOval(puffRect, puffPaint);

                puffRect.set(px - rx * 0.7f, py + ry * 0.15f, px + rx * 0.8f, py + ry * 0.95f);
                puffPaint.setColor(0x45F3C9DC);
                canvas.drawOval(puffRect, puffPaint);
            }
        }
    }

    private static void updateBlurBuffer(float width, float height, float phase, float scrollPx,
                                         int viewportW, int viewportH) {
        blurViewportWidth = viewportW;
        blurViewportHeight = viewportH;

        final int bw = Math.max(1, viewportW / BLUR_SCALE);
        final int bh = Math.max(1, viewportH / BLUR_SCALE);
        if (blurBitmap == null || blurBitmap.isRecycled()
                || blurBitmap.getWidth() != bw || blurBitmap.getHeight() != bh) {
            if (blurBitmap != null && !blurBitmap.isRecycled()) {
                blurBitmap.recycle();
            }
            blurBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            blurCanvas = new Canvas(blurBitmap);
        }

        blurBitmap.eraseColor(Color.TRANSPARENT);
        final int saveCount = blurCanvas.save();
        blurCanvas.scale(1f / BLUR_SCALE, 1f / BLUR_SCALE);
        drawSkyAndClouds(blurCanvas, width, height, phase, scrollPx);
        blurCanvas.restoreToCount(saveCount);

        Utilities.stackBlurBitmap(blurBitmap, STACK_BLUR_RADIUS);
    }

    private static CloudNode[] createLayer(int count, long seed,
                                           float minWidthFactor, float maxWidthFactor,
                                           float minHeightFactor, float maxHeightFactor) {
        final Random random = new Random(seed);
        final CloudNode[] nodes = new CloudNode[count];
        final int screenW = AndroidUtilities.displaySize.x > 0 ? AndroidUtilities.displaySize.x : 1080;
        final int screenH = AndroidUtilities.displaySize.y > 0 ? AndroidUtilities.displaySize.y : 2400;

        for (int i = 0; i < count; i++) {
            final float wFactor = minWidthFactor + random.nextFloat() * (maxWidthFactor - minWidthFactor);
            final float hFactor = minHeightFactor + random.nextFloat() * (maxHeightFactor - minHeightFactor);
            final int puffs = 4 + random.nextInt(3);
            final float[] ox = new float[puffs];
            final float[] oy = new float[puffs];
            final float[] rad = new float[puffs];

            for (int p = 0; p < puffs; p++) {
                final float t = puffs > 1 ? p / (float) (puffs - 1) : 0.5f;
                final float dome = (float) Math.sin(t * Math.PI);
                ox[p] = -0.32f + t * 0.64f + (random.nextFloat() - 0.5f) * 0.06f;
                oy[p] = 0.08f - dome * 0.22f + (random.nextFloat() - 0.5f) * 0.06f;
                rad[p] = (0.34f + 0.16f * dome) * (0.85f + 0.30f * random.nextFloat());
            }

            final float anchorY = 0.10f + (i / (float) Math.max(1, count)) * 0.78f
                    + (random.nextFloat() - 0.5f) * 0.06f;
            final float phaseOffset = i / (float) count + random.nextFloat() * 0.15f;
            final float driftSpeed = (screenW + screenH * 0.4f) * (0.75f + random.nextFloat() * 0.5f);

            nodes[i] = new CloudNode(
                    screenW * wFactor,
                    screenH * hFactor,
                    clamp01(anchorY),
                    phaseOffset,
                    driftSpeed,
                    puffs,
                    ox,
                    oy,
                    rad
            );
        }
        return nodes;
    }

    private static float wrapDrift(float distancePx, float spanPx) {
        if (spanPx <= 1f) {
            return 0f;
        }
        float mod = distancePx % spanPx;
        if (mod < 0f) {
            mod += spanPx;
        }
        return mod;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static final class CloudNode {
        final float baseWidth;
        final float baseHeight;
        final float anchorY;
        final float phaseOffset;
        final float driftSpeed;
        final int puffCount;
        final float[] puffOffsetX;
        final float[] puffOffsetY;
        final float[] puffRadius;

        CloudNode(float baseWidth, float baseHeight, float anchorY, float phaseOffset,
                  float driftSpeed, int puffCount, float[] puffOffsetX,
                  float[] puffOffsetY, float[] puffRadius) {
            this.baseWidth = baseWidth;
            this.baseHeight = baseHeight;
            this.anchorY = anchorY;
            this.phaseOffset = phaseOffset;
            this.driftSpeed = driftSpeed;
            this.puffCount = puffCount;
            this.puffOffsetX = puffOffsetX;
            this.puffOffsetY = puffOffsetY;
            this.puffRadius = puffRadius;
        }
    }
}
