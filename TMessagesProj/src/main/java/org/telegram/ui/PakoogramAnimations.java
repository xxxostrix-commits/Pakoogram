package org.telegram.ui;

import android.animation.TimeInterpolator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.WeakHashMap;

/**
 * Pakoogram: лёгкие анимации UI-элементов.
 *
 * <p>Здесь остались только вспомогательные методы, не вмешивающиеся в навигацию:
 * пружинистое сжатие кнопок/иконок при нажатии и набор интерполяторов/утилит для
 * плавных переходов внутри отдельных элементов. Переходы экранов полностью
 * отданы штатному {@code ActionBarLayout} Telegram.</p>
 */
public final class PakoogramAnimations {

    /** Масштаб, до которого сжимается элемент при касании. */
    private static final float PRESS_SCALE = 0.96f;
    private static final long PRESS_DOWN_DURATION = 110L;
    private static final long PRESS_UP_DURATION = 260L;
    /** Натяжение пружины возврата: небольшой перелёт за 1.0f делает отпускание «живым». */
    private static final float PRESS_OVERSHOOT_TENSION = 1.6f;

    /** Уже обработанные элементы: повторная привязка слушателя при переиспользовании ячейки не нужна. */
    private static final WeakHashMap<View, Boolean> springPressViews = new WeakHashMap<>();

    private static TimeInterpolator smoothInterpolator;

    private PakoogramAnimations() {
        // утилитарный класс
    }

    /**
     * Включает пружинистую физику нажатий: на {@code ACTION_DOWN} элемент плавно сжимается
     * до {@value #PRESS_SCALE}, на {@code ACTION_UP}/{@code ACTION_CANCEL} возвращается к {@code 1.0f}
     * с небольшим перелётом.
     *
     * <p>Слушатель всегда возвращает {@code false}, поэтому не перехватывает скролл, свайпы,
     * долгое нажатие и штатную обработку касаний самой ячейки.</p>
     */
    public static void attachSpringPress(final View view) {
        // Элементы со своим StateListAnimator'ом (например, кнопки ButtonWithCounterView)
        // уже имеют физику нажатия — второй аниматор только конфликтовал бы с ней.
        if (view == null || view.getStateListAnimator() != null || springPressViews.containsKey(view)) {
            return;
        }
        springPressViews.put(view, Boolean.TRUE);
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!PakoogramConfig.isSpringPressEnabled()) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().cancel();
                        v.animate()
                                .scaleX(PRESS_SCALE)
                                .scaleY(PRESS_SCALE)
                                .setDuration(PRESS_DOWN_DURATION)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().cancel();
                        v.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(PRESS_UP_DURATION)
                                .setInterpolator(new OvershootInterpolator(PRESS_OVERSHOOT_TENSION))
                                .start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /**
     * Плавная кривая для небольших внутренних анимаций:
     * {@code PathInterpolator(0.22, 1.0, 0.36, 1.0)} с запасным вариантом для прошивок,
     * отвергающих нестандартные контрольные точки.
     */
    public static TimeInterpolator getSmoothInterpolator() {
        if (smoothInterpolator == null) {
            TimeInterpolator interpolator;
            try {
                interpolator = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);
            } catch (Throwable ignore) {
                interpolator = new CubicBezierInterpolator(0.22f, 1.0f, 0.36f, 1.0f);
            }
            smoothInterpolator = interpolator;
        }
        return smoothInterpolator;
    }

    /** Линейная интерполяция между {@code start} и {@code end}. */
    public static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    /** Ограничивает значение диапазоном {@code [0, 1]}. */
    public static float clamp01(float value) {
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }
}
