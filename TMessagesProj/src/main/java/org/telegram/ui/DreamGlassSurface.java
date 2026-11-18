package org.telegram.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;

import java.util.Random;

/**
 * Pakoogram: изолированная отрисовка «стеклянных» карточек.
 *
 * <p>Карточка собирается из матовой подложки с вертикальным градиентом,
 * опционального настоящего размытия фона и процедурного зерна. Класс не зависит
 * от темы конкретного экрана — цвет и прозрачность передаются снаружи.</p>
 */
public final class DreamGlassSurface {

    /** Размер тайла процедурного зерна. */
    private static final int GRAIN_SIZE = 32;
    /** Альфа зерна: заметно только как «матовость», не как шум. */
    private static final int GRAIN_ALPHA = 11;

    private static Bitmap grainBitmap;
    private static BitmapShader grainShader;
    /** Временный прямоугольник для растягивания размытого фона на весь экран. */
    private static final RectF screenRectTmp = new RectF();

    private DreamGlassSurface() {
        // утилитарный класс
    }

    /**
     * Рисует стеклянную карточку без размытия фона.
     *
     * @param rect               прямоугольник карточки
     * @param cornerRadius       радиус скругления
     * @param isDark             тёмная ли тема
     * @param opacityMultiplier  множитель прозрачности, ограничивается {@code 0.15f..0.95f}
     */
    public static void drawGlassCard(Canvas canvas, RectF rect, float cornerRadius,
                                     boolean isDark, float opacityMultiplier) {
        drawFrostedGlassCard(canvas, rect, cornerRadius, isDark, opacityMultiplier,
                null, 0, 0);
    }

    /**
     * Рисует матовую карточку с настоящим размытием фона под ней.
     *
     * <p>Область карточки обрезается скруглённым {@link Path}. Внутри неё
     * растягивается уменьшенный размытый кадр экрана, поверх которого лежит
     * плотная дымка и процедурное зерно. Никакой белой обводки нет.</p>
     *
     * @param rect                прямоугольник карточки
     * @param cornerRadius        радиус скругления
     * @param isDark              тёмная ли тема
     * @param opacityMultiplier   множитель прозрачности, ограничивается {@code 0.15f..0.95f}
     * @param blurredBackground   уже размытый кадр экрана или {@code null}
     * @param screenWidth         ширина экрана, на которую растягивается кадр
     * @param screenHeight        высота экрана, на которую растягивается кадр
     */
    public static void drawFrostedGlassCard(Canvas canvas, RectF rect, float cornerRadius,
                                            boolean isDark, float opacityMultiplier,
                                            Bitmap blurredBackground,
                                            int screenWidth, int screenHeight) {
        if (canvas == null || rect == null || rect.width() <= 0f || rect.height() <= 0f) {
            return;
        }
        final float opacity = clamp(opacityMultiplier, 0.15f, 0.95f);

        // Плотная матовая дымка: под карточкой остаются различимыми только
        // мягкие пятна облаков и ауры.
        final float topBaseAlpha = isDark ? 0.56f : 0.62f;
        final float bottomBaseAlpha = isDark ? 0.68f : 0.74f;
        final int topAlpha = Math.min(255, Math.round(255f * topBaseAlpha * opacity));
        final int bottomAlpha = Math.min(255, Math.round(255f * bottomBaseAlpha * opacity));
        final int topRgb = isDark ? 0x2A2638 : 0xF5F8FF;
        final int bottomRgb = isDark ? 0x1B1826 : 0xE6EEF8;
        final int topColor = (topAlpha << 24) | topRgb;
        final int bottomColor = (bottomAlpha << 24) | bottomRgb;

        final int saveCount = canvas.save();
        final Path clip = new Path();
        clip.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.clipPath(clip);

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);

        // Настоящее размытие: уменьшенный кадр растягивается на весь экран,
        // а clipPath оставляет видимой только область карточки.
        if (blurredBackground != null && !blurredBackground.isRecycled()
                && screenWidth > 0 && screenHeight > 0) {
            paint.setFilterBitmap(true);
            screenRectTmp.set(0f, 0f, screenWidth, screenHeight);
            canvas.drawBitmap(blurredBackground, null, screenRectTmp, paint);
            paint.setFilterBitmap(false);
        }

        paint.setShader(new LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                topColor, bottomColor, Shader.TileMode.CLAMP));
        canvas.drawRect(rect, paint);

        // Текстура матового зерна поверх дымки.
        paint.setShader(grainShader());
        canvas.drawRect(rect, paint);
        canvas.restoreToCount(saveCount);
    }

    /**
     * Применяет размытие к содержимому {@code target} на Android 12+.
     * На более старых версиях вызов безопасно игнорируется.
     *
     * @param target   вид, к которому применяется эффект
     * @param radiusDp радиус размытия в dp; {@code <= 0} снимает эффект
     */
    public static void applyBackgroundBlur(View target, float radiusDp) {
        if (target == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyBlur31(target, radiusDp);
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static void applyBlur31(View target, float radiusDp) {
        if (radiusDp <= 0f) {
            target.setRenderEffect(null);
            return;
        }
        final float radius = AndroidUtilities.dpf2(radiusDp);
        target.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
    }

    /* ---------------------------------------------------------------- зерно */

    private static BitmapShader grainShader() {
        if (grainShader == null) {
            if (grainBitmap == null) {
                grainBitmap = createGrainBitmap();
            }
            grainShader = new BitmapShader(grainBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            // Тайл 32×32 должен выглядеть одинаково на экранах любой плотности.
            final Matrix matrix = new Matrix();
            final float scale = Math.max(1f, AndroidUtilities.density);
            matrix.setScale(scale, scale);
            grainShader.setLocalMatrix(matrix);
        }
        return grainShader;
    }

    private static Bitmap createGrainBitmap() {
        final int[] pixels = new int[GRAIN_SIZE * GRAIN_SIZE];
        final Random random = new Random(0x5EEDF00DL);
        for (int i = 0; i < pixels.length; i++) {
            final int value = random.nextInt(256);
            // Фиксированная альфа 11 и случайная яркость дают мягкое монохромное зерно.
            pixels[i] = (GRAIN_ALPHA << 24) | (value << 16) | (value << 8) | value;
        }
        return Bitmap.createBitmap(pixels, GRAIN_SIZE, GRAIN_SIZE, Bitmap.Config.ARGB_8888);
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
