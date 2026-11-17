package org.telegram.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
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
 * <p>Карточка собирается из трёх слоёв: матовая подложка с вертикальным градиентом,
 * процедурное зерно и тонкий световой блик по контуру. Класс не зависит от темы
 * конкретного экрана — цвет и прозрачность передаются снаружи.</p>
 */
public final class DreamGlassSurface {

    /** Размер тайла процедурного зерна. */
    private static final int GRAIN_SIZE = 32;
    /** Альфа зерна: заметно только как «матовость», не как шум. */
    private static final int GRAIN_ALPHA = 8;

    private static Bitmap grainBitmap;
    private static BitmapShader grainShader;

    private DreamGlassSurface() {
        // утилитарный класс
    }

    /**
     * Рисует стеклянную карточку.
     *
     * @param rect               прямоугольник карточки
     * @param cornerRadius       радиус скругления
     * @param isDark             тёмная ли тема
     * @param opacityMultiplier  множитель прозрачности, ограничивается {@code 0.15f..0.95f}
     */
    public static void drawGlassCard(Canvas canvas, RectF rect, float cornerRadius,
                                     boolean isDark, float opacityMultiplier) {
        if (canvas == null || rect == null || rect.width() <= 0f || rect.height() <= 0f) {
            return;
        }
        final float opacity = clamp(opacityMultiplier, 0.15f, 0.95f);

        // Слой 1. Матовая подложка: сверху воздушнее и светлее, снизу плотнее.
        final float topBaseAlpha = isDark ? 0.58f : 0.54f;
        final float bottomBaseAlpha = isDark ? 0.82f : 0.76f;
        final int topAlpha = Math.min(255, Math.round(255f * topBaseAlpha * opacity));
        final int bottomAlpha = Math.min(255, Math.round(255f * bottomBaseAlpha * opacity));
        final int topRgb = isDark ? 0x252A33 : 0xFFFFFF;
        final int bottomRgb = isDark ? 0x12151B : 0xE9EEF6;
        final int topColor = (topAlpha << 24) | topRgb;
        final int bottomColor = (bottomAlpha << 24) | bottomRgb;

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                topColor, bottomColor, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint);

        // Слой 2. Процедурное зерно 32×32 с alpha = 8: стекло перестаёт быть плоским.
        paint.setShader(grainShader());
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint);

        // Слой 3. Тонкий блик по краю: сверху слева ярче, снизу справа почти гаснет.
        paint.setShader(new LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                0x66FFFFFF, 0x0DFFFFFF, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(AndroidUtilities.dpf2(1f));
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint);
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
            // Фиксированная альфа 8 и случайная яркость дают мягкое монохромное зерно.
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
