package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.WeakHashMap;

/**
 * Pakoogram: изолированный движок анимаций интерфейса.
 *
 * <p>Здесь собрана вся математика «жидких» переходов в стиле ColorOS / HyperOS и
 * пружинистой физики нажатий, чтобы не размазывать её по огромным классам экранов.</p>
 *
 * <p>Состав:</p>
 * <ul>
 *     <li>{@link #captureOrigin(View)} — запоминает экранный прямоугольник нажатого элемента;</li>
 *     <li>{@link #animateScreenTransition(boolean, View, View, BaseFragment, BaseFragment, Runnable)} —
 *     морфинг экранов (раскрытие из карточки/шапки, уход вглубь с затемнением и живым размытием);</li>
 *     <li>{@link #attachSpringPress(View)} — пружинистое сжатие элемента при нажатии.</li>
 * </ul>
 */
public final class PakoogramAnimations {

    /* ------------------------------------------------------------ пружина нажатий */

    /** Масштаб, до которого сжимается элемент при касании. */
    private static final float PRESS_SCALE = 0.96f;
    private static final long PRESS_DOWN_DURATION = 110L;
    private static final long PRESS_UP_DURATION = 260L;
    /** Натяжение пружины возврата: небольшой перелёт за 1.0f делает отпускание «живым». */
    private static final float PRESS_OVERSHOOT_TENSION = 1.6f;

    /* ------------------------------------------------------------ переходы экранов */

    /** Масштаб уходящего на задний план экрана. */
    private static final float OUTGOING_SCALE = 0.93f;
    /** Максимальное затемнение нижнего слоя (доля непрозрачности чёрной подложки). */
    private static final float OUTGOING_DIM_ALPHA = 0.42f;
    /** Масштаб, с которого начинается раскрытие, если прямоугольник-источник неизвестен. */
    private static final float FALLBACK_START_SCALE = 0.86f;
    /** Базовая длительность перехода до применения множителя скорости из настроек. */
    private static final long BASE_TRANSITION_DURATION = 360L;
    /** Скругление раскрывающегося экрана, которое «разгибается» до нуля. */
    private static final int CORNER_RADIUS_DP = 24;
    /** Сколько живёт запомненный прямоугольник: защита от «протухшего» источника. */
    private static final long ORIGIN_MAX_AGE_MS = 2000L;

    private static final Rect capturedOrigin = new Rect();
    private static boolean hasCapturedOrigin;
    private static long capturedOriginTime;

    /** Уже обработанные элементы: повторная привязка слушателя при переиспользовании ячейки не нужна. */
    private static final WeakHashMap<View, Boolean> springPressViews = new WeakHashMap<>();

    private static TimeInterpolator colorOsInterpolator;

    private PakoogramAnimations() {
        // утилитарный класс
    }

    /* ---------------------------------------------------------------- captureOrigin */

    /**
     * Запоминает экранные координаты и размеры нажатого элемента — карточки чата в списке
     * диалогов или шапки/аватарки в чате. Из этого прямоугольника затем раскрывается новый экран.
     *
     * @param view нажатый элемент; {@code null} или элемент без размеров сбрасывает запомненное значение
     */
    public static void captureOrigin(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            hasCapturedOrigin = false;
            return;
        }
        final int[] location = new int[2];
        view.getLocationOnScreen(location);
        capturedOrigin.set(
                location[0], location[1],
                location[0] + view.getWidth(), location[1] + view.getHeight());
        hasCapturedOrigin = true;
        capturedOriginTime = SystemClock.uptimeMillis();
    }

    /** Сбрасывает запомненный прямоугольник (например, после завершения перехода). */
    public static void clearOrigin() {
        hasCapturedOrigin = false;
    }

    /**
     * @param target экран, в системе координат которого нужен прямоугольник
     * @return копия запомненного прямоугольника или {@code null}, если он неизвестен/устарел
     */
    private static Rect getOriginInView(View target) {
        if (!hasCapturedOrigin || target == null
                || SystemClock.uptimeMillis() - capturedOriginTime > ORIGIN_MAX_AGE_MS) {
            return null;
        }
        final int[] location = new int[2];
        target.getLocationOnScreen(location);
        final Rect rect = new Rect(capturedOrigin);
        rect.offset(-location[0], -location[1]);
        return rect;
    }

    /* ------------------------------------------------------------- attachSpringPress */

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

    /* ------------------------------------------------------- animateScreenTransition */

    /**
     * @return {@code true}, если для этого экрана нужно играть ColorOS-переход
     *         (включён в настройках, и это чат или профиль)
     */
    public static boolean canAnimateTransition(BaseFragment fragment) {
        if (fragment == null || !PakoogramConfig.isTransitionAnimationsEnabled()) {
            return false;
        }
        return fragment instanceof ChatActivity || fragment instanceof ProfileActivity;
    }

    /**
     * Создаёт и сразу запускает морфинг экранов.
     *
     * <p>Открытие: новый экран раскрывается из запомненного прямоугольника (масштаб от размера
     * карточки/шапки до {@code 1.0f}) со «разгибанием» скругления углов, а нижний слой уходит
     * вглубь, затемняется и размывается.</p>
     *
     * <p>Закрытие — обратный процесс: закрываемый экран сворачивается назад в карточку,
     * а возвращающийся экран выходит из глубины и постепенно выбирает размытие.</p>
     *
     * @param open             направление перехода
     * @param incoming         контейнер появляющегося экрана
     * @param outgoing         контейнер уходящего экрана
     * @param incomingFragment появляющийся фрагмент (для штатных progress-колбэков)
     * @param outgoingFragment уходящий фрагмент
     * @param onEnd            вызывается после завершения и полного сброса эффектов
     * @return аниматор для присвоения {@code currentAnimation} или {@code null}, если играть нечего
     */
    public static AnimatorSet animateScreenTransition(final boolean open, final View incoming, final View outgoing,
                                                      final BaseFragment incomingFragment, final BaseFragment outgoingFragment,
                                                      final Runnable onEnd) {
        if (incoming == null || outgoing == null) {
            return null;
        }
        final Rect incomingOrigin = getOriginInView(incoming);
        final Rect outgoingOrigin = getOriginInView(outgoing);
        final long duration = getTransitionDuration();
        final float blurStrength = PakoogramConfig.getTransitionBlurStrength();
        final float cornerRadius = AndroidUtilities.dp(CORNER_RADIUS_DP);

        // Сброс возможных хвостов предыдущих переходов: дальше двигаем только масштаб/альфу/размытие.
        incoming.setTranslationX(0f);
        incoming.setAlpha(1f);
        outgoing.setTranslationX(0f);
        outgoing.setAlpha(1f);

        if (open) {
            setPivot(incoming, incomingOrigin);
            setPivot(outgoing, null);
        } else {
            setPivot(incoming, null);
            setPivot(outgoing, outgoingOrigin);
        }

        final ColorDrawable incomingDim = new ColorDrawable(Color.BLACK);
        final ColorDrawable outgoingDim = new ColorDrawable(Color.BLACK);
        if (open) {
            outgoingDim.setAlpha(0);
            outgoing.getOverlay().add(outgoingDim);
        } else {
            incomingDim.setAlpha(Math.round(OUTGOING_DIM_ALPHA * 255f));
            incoming.getOverlay().add(incomingDim);
        }

        // Первый кадр ставим сразу, чтобы не было вспышки необработанного состояния.
        applyTransitionFrame(open, incoming, outgoing, incomingDim, outgoingDim,
                incomingOrigin, outgoingOrigin, cornerRadius, blurStrength, 0f);

        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(getColorOsInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                final Object value = animation.getAnimatedValue();
                final float progress = value instanceof Float ? clamp01((Float) value) : 0f;
                applyTransitionFrame(open, incoming, outgoing, incomingDim, outgoingDim,
                        incomingOrigin, outgoingOrigin, cornerRadius, blurStrength, progress);
                if (incomingFragment != null) {
                    incomingFragment.onTransitionAnimationProgress(true, progress);
                }
                if (outgoingFragment != null) {
                    outgoingFragment.onTransitionAnimationProgress(false, progress);
                }
            }
        });

        final AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(animator);
        animatorSet.setDuration(duration);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                // Эффекты снимаются всегда: экран не должен остаться мыльным, сжатым или затемнённым.
                resetTransitionView(incoming, incomingDim);
                resetTransitionView(outgoing, outgoingDim);
                if (cancelled) {
                    return;
                }
                if (onEnd != null) {
                    onEnd.run();
                }
            }
        });
        animatorSet.start();
        return animatorSet;
    }

    /**
     * Применяет один кадр перехода. Открытие и закрытие считаются по одной и той же математике,
     * только роли контейнеров меняются местами.
     */
    private static void applyTransitionFrame(boolean open, View incoming, View outgoing,
                                             ColorDrawable incomingDim, ColorDrawable outgoingDim,
                                             Rect incomingOrigin, Rect outgoingOrigin,
                                             float cornerRadius, float blurStrength, float progress) {
        final float p = clamp01(progress);
        if (open) {
            // Точка и масштаб пересчитываются каждый кадр: первый кадр может прийти до layout.
            setPivot(incoming, incomingOrigin);
            final float startScale = computeOriginScale(incoming, incomingOrigin);
            // Новый экран раскрывается из прямоугольника нажатого элемента.
            final float incomingScale = lerp(startScale, 1f, p);
            incoming.setScaleX(incomingScale);
            incoming.setScaleY(incomingScale);
            applyCornerRadius(incoming, cornerRadius * (1f - p));
            incoming.invalidate();
            // Нижний слой уходит вглубь, затемняется и размывается.
            final float outgoingScale = lerp(1f, OUTGOING_SCALE, p);
            outgoing.setScaleX(outgoingScale);
            outgoing.setScaleY(outgoingScale);
            outgoingDim.setAlpha(Math.round(OUTGOING_DIM_ALPHA * 255f * p));
            setViewBlur(outgoing, blurStrength * p);
            outgoing.invalidate();
        } else {
            // Возвращающийся экран выходит из глубины и постепенно выбирает размытие.
            setPivot(incoming, null);
            final float incomingScale = lerp(OUTGOING_SCALE, 1f, p);
            incoming.setScaleX(incomingScale);
            incoming.setScaleY(incomingScale);
            incomingDim.setAlpha(Math.round(OUTGOING_DIM_ALPHA * 255f * (1f - p)));
            setViewBlur(incoming, blurStrength * (1f - p));
            incoming.invalidate();
            // Закрываемый экран сворачивается назад в карточку с обратным скруглением.
            setPivot(outgoing, outgoingOrigin);
            final float endScale = computeOriginScale(outgoing, outgoingOrigin);
            final float outgoingScale = lerp(1f, endScale, p);
            outgoing.setScaleX(outgoingScale);
            outgoing.setScaleY(outgoingScale);
            // Почти не гасим закрываемый экран, пока он сворачивается: виден сам морфинг в карточку.
            outgoing.setAlpha(1f - 0.85f * p);
            applyCornerRadius(outgoing, cornerRadius * p);
            outgoing.invalidate();
        }
    }

    /** Полностью снимает все эффекты перехода с контейнера (в том числе живое размытие). */
    private static void resetTransitionView(View view, ColorDrawable dim) {
        if (view == null) {
            return;
        }
        if (dim != null) {
            view.getOverlay().remove(dim);
        }
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setAlpha(1f);
        view.setPivotX(view.getWidth() / 2f);
        view.setPivotY(view.getHeight() / 2f);
        if (view.getClipToOutline()) {
            view.setClipToOutline(false);
        }
        view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        view.invalidateOutline();
        setViewBlur(view, 0f);
        view.invalidate();
    }

    /** Скругление через {@link ViewOutlineProvider}: радиус плавно «разгибается» до нуля. */
    private static void applyCornerRadius(View view, float radius) {
        if (view == null) {
            return;
        }
        if (radius < 0.5f) {
            if (view.getClipToOutline()) {
                view.setClipToOutline(false);
            }
            if (view.getOutlineProvider() instanceof RoundedOutlineProvider) {
                view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            }
            view.invalidateOutline();
            return;
        }
        if (!(view.getOutlineProvider() instanceof RoundedOutlineProvider)) {
            view.setOutlineProvider(new RoundedOutlineProvider());
            view.setClipToOutline(true);
        }
        ((RoundedOutlineProvider) view.getOutlineProvider()).radius = radius;
        view.invalidateOutline();
    }

    /** Ставит точку масштабирования в центр нажатого элемента или в центр экрана. */
    private static void setPivot(View view, Rect origin) {
        if (view == null) {
            return;
        }
        if (origin != null) {
            view.setPivotX(origin.centerX());
            view.setPivotY(origin.centerY());
        } else {
            view.setPivotX(view.getWidth() / 2f);
            view.setPivotY(view.getHeight() / 2f);
        }
    }

    /** Масштаб, при котором экран по размеру совпадает с нажатой карточкой/шапкой. */
    private static float computeOriginScale(View view, Rect origin) {
        if (origin == null || view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return FALLBACK_START_SCALE;
        }
        final float scale = Math.max(
                origin.width() / (float) view.getWidth(),
                origin.height() / (float) view.getHeight());
        return clamp(scale, 0.2f, 1f);
    }

    /** Живое размытие доступно только с Android 12; ниже — только масштаб и затемнение. */
    private static void setViewBlur(View view, float radius) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        applyBlur(view, radius);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static void applyBlur(View view, float radius) {
        view.setRenderEffect(radius > 0.5f
                ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                : null);
    }

    /** Базовая длительность 360 мс делится на множитель скорости из настроек (0.5x..1.5x). */
    private static long getTransitionDuration() {
        final float speed = PakoogramConfig.getAnimationSpeed();
        return Math.max(120L, (long) (BASE_TRANSITION_DURATION / Math.max(0.25f, speed)));
    }

    /** Кривая быстрого подрыва и «маслянистого» довода ColorOS. */
    private static TimeInterpolator getColorOsInterpolator() {
        if (colorOsInterpolator == null) {
            TimeInterpolator interpolator;
            try {
                interpolator = new PathInterpolator(0.2f, 0.9f, 0.1f, 1.0f);
            } catch (Throwable ignore) {
                // Часть прошивок отвергает немонотонные контрольные точки X: берём ту же кривую,
                // посчитанную вручную, чтобы переход не ломался.
                interpolator = new CubicBezierInterpolator(0.2f, 0.9f, 0.1f, 1.0f);
            }
            colorOsInterpolator = interpolator;
        }
        return colorOsInterpolator;
    }

    /** Провайдер контура со скруглёнными углами: радиус меняется каждый кадр. */
    private static final class RoundedOutlineProvider extends ViewOutlineProvider {

        private float radius;

        @Override
        public void getOutline(View view, Outline outline) {
            final int width = view.getWidth();
            final int height = view.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            final float r = Math.min(radius, Math.min(width, height) / 2f);
            if (r <= 0f) {
                outline.setRect(0, 0, width, height);
            } else {
                outline.setRoundRect(0, 0, width, height, r);
            }
        }
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private static float clamp01(float value) {
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
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
