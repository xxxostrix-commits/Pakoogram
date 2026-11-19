package org.telegram.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;

/**
 * Pakoogram: изолированная отрисовка «стеклянных» карточек.
 *
 * <p>Карточка собирается из чистой полупрозрачной заливки и опционального
 * настоящего размытия фона. Класс не зависит от темы конкретного экрана —
 * цвет и прозрачность передаются снаружи.</p>
 */
public final class DreamGlassSurface {

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
     * чистая полупрозрачная заливка без обводки и зерна.</p>
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

        // Чистая полупрозрачная заливка поверх размытия: без градиента,
        // белой обводки и процедурного зерна.
        final int fillColor = isDark
                ? Color.argb(Math.round(135 * opacity), 26, 28, 36)
                : Color.argb(Math.round(155 * opacity), 255, 255, 255);
        paint.setShader(null);
        paint.setColor(fillColor);
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
