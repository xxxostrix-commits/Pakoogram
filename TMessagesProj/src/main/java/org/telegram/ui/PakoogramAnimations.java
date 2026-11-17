package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBarLayout;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Pakoogram: изолированный движок анимаций интерфейса.
 *
 * <p>Здесь собрана вся математика «жидких» переходов в стиле ColorOS / HyperOS и
 * пружинистой физики нажатий, чтобы не размазывать её по огромным классам экранов.</p>
 *
 * <p>Состав:</p>
 * <ul>
 *     <li>{@link #captureOrigin(View)} — запоминает точную «капсулу» нажатой карточки чата
 *     (внутренний прямоугольник со всеми боковыми отступами) строго в локальных координатах
 *     {@link ActionBarLayout}, её радиус и id диалога;</li>
 *     <li>{@link #animateScreenTransition(boolean, View, View, BaseFragment, BaseFragment, Runnable)} —
 *     морфинг «капсулы»: скруглённая плашка цвета фона чата раскрывается из карточки диалога
 *     на весь экран, а сам {@code ChatActivity} не обрезается и не масштабируется — он лишь
 *     плавно проявляется поверх плашки;</li>
 *     <li>{@link #drawMorphOverlay(Canvas)} — отрисовка текущего кадра плашки-капсулы
 *     поверх списка диалогов и под содержимым переднего экрана;</li>
 *     <li>{@link #attachSpringPress(View)} — пружинистое сжатие элемента при нажатии;</li>
 *     <li>{@link #resetScreenEffects(View)} — принудительный сброс эффектов перехода с контейнера.</li>
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

    /** Радиус плашки-капсулы в начале/конце морфинга, в dp. */
    private static final int CAPSULE_CORNER_RADIUS_DP = 18;
    /** С этой доли пути при открытии начинает проявляться содержимое чата. */
    private static final float OPEN_CONTENT_ALPHA_START = 0.45f;
    /** За эту долю пути при закрытии содержимое чата полностью растворяется. */
    private static final float CLOSE_CONTENT_ALPHA_END = 0.35f;
    /** С этой доли пути при закрытии плашка начинает мягко гаснуть поверх карточки. */
    private static final float CLOSE_OVERLAY_ALPHA_END_START = 0.80f;
    /** Базовая длительность открытия до деления на множитель скорости из настроек. */
    private static final long BASE_OPEN_DURATION = 320L;
    /** Базовая длительность закрытия до деления на множитель скорости из настроек. */
    private static final long BASE_CLOSE_DURATION = 290L;
    /** Размеры запасной капсулы в долях экрана — используется только как fallback. */
    private static final float FALLBACK_CAPSULE_WIDTH_RATIO = 0.55f;
    private static final float FALLBACK_CAPSULE_HEIGHT_RATIO = 0.22f;
    /** Сколько живёт запомненная карточка как стартовая точка открытия. */
    private static final long ORIGIN_MAX_AGE_MS = 2000L;
    /** Сколько живёт запомненная капсула как цель закрытия, если она не привязана к чату. */
    private static final long CLOSE_TARGET_MAX_AGE_MS = 15 * 60 * 1000L;

    /**
     * Прямоугольник «капсулы» нажатой карточки (уже с боковыми отступами) в локальных
     * координатах {@link ActionBarLayout}, в котором карточка была нажата.
     */
    private static final RectF capturedOrigin = new RectF();
    /** Контейнер, относительно которого записаны координаты {@link #capturedOrigin}. */
    private static WeakReference<View> capturedContainerView;
    private static boolean hasCapturedOrigin;
    private static long capturedOriginTime;
    /** id чата, которому принадлежит капсула; {@code 0} — источник без привязки к диалогу. */
    private static long capturedDialogId;

    /** Уже обработанные элементы: повторная привязка слушателя при переиспользовании ячейки не нужна. */
    private static final WeakHashMap<View, Boolean> springPressViews = new WeakHashMap<>();
    /** Контейнеры, которые прямо сейчас участвуют в морфинге. */
    private static final WeakHashMap<View, Boolean> morphingViews = new WeakHashMap<>();

    /** Текущий кадр плашки-капсулы в локальных координатах {@link ActionBarLayout}. */
    private static final RectF morphRect = new RectF();
    private static float morphRadius;
    private static float morphAlpha;
    /** {@code true}, пока плашку нужно рисовать в {@code ActionBarLayout.drawChild}. */
    private static boolean morphOverlayVisible;
    /**
     * Передний контейнер перехода. Плашка рисуется прямо перед ним: она оказывается
     * поверх списка диалогов, но под содержимым чата, поэтому альфа чата видна корректно.
     */
    private static WeakReference<View> morphFrontView;
    private static final Paint morphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /**
     * Плашка за кадр должна нарисоваться ровно один раз: обычно её рисует
     * {@code ActionBarLayout.drawChild} перед передним контейнером, а вызов в конце
     * {@code dispatchDraw} служит только страховкой.
     */
    private static boolean morphOverlayDrawnInFrame;

    private static TimeInterpolator colorOsInterpolator;

    private PakoogramAnimations() {
        // утилитарный класс
    }

    /* ---------------------------------------------------------------- captureOrigin */

    /**
     * Запоминает точную «капсулу» нажатой карточки чата — внутренний прямоугольник,
     * ограниченный теми же боковыми отступами, что рисует {@link DialogCell}
     * ({@code PAKOGRAM_CARD_MARGIN_HORIZONTAL} / {@code PAKOGRAM_CARD_MARGIN_VERTICAL}),
     * и id диалога.
     *
     * <p>Координаты считаются строго в локальном пространстве ближайшего
     * {@link ActionBarLayout}: {@code getLocationInWindow} карточки минус
     * {@code getLocationInWindow} контейнера. Так сдвиг окна на высоту статус-бара
     * (и любые смещения самого окна) не попадает в прямоугольник перехода.</p>
     *
     * <p>Из этого прямоугольника затем раскрывается чат ({@code open == true}) и в него же
     * сворачивается при закрытии ({@code open == false}). Для не-карточек (аватарка/шапка
     * профиля) берётся весь прямоугольник вида.</p>
     *
     * @param view нажатый элемент; {@code null} или элемент без размеров сбрасывает запомненное значение
     */
    public static void captureOrigin(View view) {
        captureOrigin(view, null);
    }

    /**
     * Запоминает «капсулу» элемента в локальных координатах явно переданного контейнера
     * перехода (обычно {@link ActionBarLayout}).
     *
     * @param view      нажатый элемент
     * @param container контейнер перехода; {@code null} — искать {@link ActionBarLayout} по иерархии
     */
    public static void captureOrigin(View view, View container) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            clearOrigin();
            return;
        }
        final View originContainer = container != null ? container : findTransitionContainer(view);
        final int[] cardLocation = new int[2];
        final int[] containerLocation = new int[2];
        view.getLocationInWindow(cardLocation);
        if (originContainer != null) {
            originContainer.getLocationInWindow(containerLocation);
        } else {
            // Контейнер перехода ещё не найден — работаем в координатах окна.
            containerLocation[0] = 0;
            containerLocation[1] = 0;
        }
        final float cardLeft = cardLocation[0] - containerLocation[0];
        final float cardTop = cardLocation[1] - containerLocation[1];
        final float cardRight = cardLeft + view.getWidth();
        final float cardBottom = cardTop + view.getHeight();
        long dialogId = 0;
        if (view instanceof DialogCell) {
            final DialogCell cell = (DialogCell) view;
            dialogId = cell.getDialogId();
            // Внутренний прямоугольник карточки: ровно те же отступы, что в DialogCell.drawPakoogramCard().
            final float insetHorizontal = AndroidUtilities.dp(DialogCell.PAKOGRAM_CARD_MARGIN_HORIZONTAL);
            final float insetVertical = AndroidUtilities.dpf2(DialogCell.PAKOGRAM_CARD_MARGIN_VERTICAL);
            capturedOrigin.set(
                    cardLeft + insetHorizontal,
                    cardTop + insetVertical,
                    cardRight - insetHorizontal,
                    cardBottom - insetVertical);
        } else {
            capturedOrigin.set(cardLeft, cardTop, cardRight, cardBottom);
        }
        if (capturedOrigin.width() <= 0f || capturedOrigin.height() <= 0f) {
            clearOrigin();
            return;
        }
        capturedContainerView = originContainer != null ? new WeakReference<>(originContainer) : null;
        capturedDialogId = dialogId;
        hasCapturedOrigin = true;
        capturedOriginTime = SystemClock.uptimeMillis();
    }

    /**
     * Ищет ближайший {@link ActionBarLayout} над элементом — контейнер, в локальных
     * координатах которого запоминается капсула.
     */
    private static View findTransitionContainer(View view) {
        ViewParent parent = view != null ? view.getParent() : null;
        while (parent instanceof View) {
            final View parentView = (View) parent;
            if (parentView instanceof ActionBarLayout) {
                return parentView;
            }
            parent = parentView.getParent();
        }
        return null;
    }

    /** Сбрасывает запомненную капсулу (например, после завершения закрытия чата). */
    public static void clearOrigin() {
        hasCapturedOrigin = false;
        capturedDialogId = 0;
        capturedContainerView = null;
    }

    /**
     * Подбирает запомненную капсулу для перехода.
     *
     * <ul>
     *     <li>при открытии карточка должна быть «свежей» — тап и запуск чата идут впритык;</li>
     *     <li>при закрытии капсула того же чата используется без ограничения по времени —
     *     человек может сидеть в переписке сколько угодно;</li>
     *     <li>если капсула принадлежит другому чату, переход её не использует.</li>
     * </ul>
     *
     * @param fragment открываемый/закрываемый экран, чью карточку ищем
     * @param open     направление перехода
     * @return прямоугольник капсулы в координатах контейнера или {@code null}, если он не подходит
     */
    private static RectF resolveTargetRect(BaseFragment fragment, boolean open) {
        if (!hasCapturedOrigin) {
            return null;
        }
        final long age = SystemClock.uptimeMillis() - capturedOriginTime;
        final long dialogId = fragment instanceof ChatActivity ? ((ChatActivity) fragment).getDialogId() : 0;
        if (capturedDialogId != 0) {
            // Капсула принадлежит конкретному чату: чужой чат из неё не раскрываем и в неё не сворачиваем.
            if (dialogId != 0 && dialogId != capturedDialogId) {
                return null;
            }
            if (open && age > ORIGIN_MAX_AGE_MS) {
                return null;
            }
        } else if (age > (open ? ORIGIN_MAX_AGE_MS : CLOSE_TARGET_MAX_AGE_MS)) {
            return null;
        }
        return new RectF(capturedOrigin);
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
     * Создаёт и сразу запускает морфинг «капсулы» между списком диалогов и экраном чата.
     *
     * <p>Сам экран чата не масштабируется и не обрезается: его {@code scaleX/Y} всегда
     * остаются {@code 1.0f}. Вместо этого в {@link ActionBarLayout} рисуется скруглённая
     * плашка цвета {@code Theme.key_windowBackgroundWhite}, прямоугольник которой
     * интерполируется между полным экраном {@code [0, 0, width, height]} и внутренним
     * прямоугольником карточки чата ({@link #captureOrigin(View)}), а радиус скругления —
     * между {@code AndroidUtilities.dp(18)} и {@code 0f}.</p>
     *
     * <p>При открытии плашка разжимается из карточки на весь экран, а содержимое чата
     * проявляется поверх неё только во второй половине пути ({@code 0.45f..1f}), поэтому
     * пустой экран и спиннер загрузки не мелькают. При закрытии содержимое чата гаснет
     * за первые 35% пути, затем плашка сжимается обратно в карточку, а на последних 20%
     * мягко растворяется уже поверх неё.</p>
     *
     * <p>Анимация открытия стартует только после первой раскладки нового фрагмента
     * (через {@code OnPreDraw}), чтобы шапка и переписка подтягивались уже из реальных
     * размеров, а не из нулевого layout.</p>
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
        return animateScreenTransition(open, incoming, outgoing, incomingFragment, outgoingFragment, onEnd, false);
    }

    /**
     * Вариант перехода с поддержкой завершения свайпа назад: если {@code swipeBack == true},
     * передний контейнер уже сдвинут вправо, поэтому его сдвиг сохраняется на время
     * быстрого растворения содержимого и обнуляется только после того, как альфа станет нулевой.
     */
    public static AnimatorSet animateScreenTransition(final boolean open, final View incoming, final View outgoing,
                                                      final BaseFragment incomingFragment, final BaseFragment outgoingFragment,
                                                      final Runnable onEnd, final boolean swipeBack) {
        if (incoming == null || outgoing == null) {
            return null;
        }
        // Морфится всегда «передний» слой: при открытии — новый экран, при закрытии — уходящий.
        final View morphView = open ? incoming : outgoing;
        final View backView = open ? outgoing : incoming;
        final BaseFragment morphFragment = open ? incomingFragment : outgoingFragment;
        if (morphView.getWidth() <= 0 || morphView.getHeight() <= 0) {
            return null;
        }

        // Запомненную цель достаём до сброса эффектов: остаточные translation контейнера
        // исказили бы getLocationInWindow. Прямоугольник хранится в координатах ActionBarLayout,
        // ровно там же, где drawMorphOverlay будет рисовать плашку.
        final RectF capturedTarget = resolveTargetRect(morphFragment, open);
        final boolean usedCapturedOrigin = capturedTarget != null;
        final RectF fullRect = new RectF();
        final RectF targetRect = new RectF();
        // Сдвиг, накопленный свайпом назад: возвращать его в 0 сразу нельзя, иначе экран
        // прыгнет вбок в момент отпускания пальца.
        final float swipeTranslation = swipeBack && !open ? morphView.getTranslationX() : 0f;

        // Сброс возможных хвостов предыдущих переходов. Экран чата после этого всегда
        // стоит без масштаба, сдвигов и обрезки — морфится только нарисованная плашка.
        resetScreenEffects(incoming);
        resetScreenEffects(outgoing);
        if (swipeTranslation != 0f) {
            morphView.setTranslationX(swipeTranslation);
        }

        resolveMorphFullRect(morphView, fullRect);
        if (capturedTarget != null) {
            targetRect.set(capturedTarget);
        } else {
            fallbackCapsule(fullRect, targetRect);
        }
        final float targetRadius = Math.min(AndroidUtilities.dp(CAPSULE_CORNER_RADIUS_DP),
                Math.min(targetRect.width(), targetRect.height()) / 2f);

        final long duration = getTransitionDuration(open);

        // Метка нужна уже во время первого кадра: LayoutContainer не рисует статичную тень шапки.
        morphingViews.put(morphView, Boolean.TRUE);
        // Плашка рисуется в ActionBarLayout.drawChild прямо перед передним контейнером:
        // над списком диалогов, но под содержимым чата.
        morphFrontView = new WeakReference<>(morphView);
        morphOverlayVisible = true;

        // Первый кадр ставим сразу: при открытии плашка уже совпадает с карточкой,
        // при закрытии — перекрывает весь экран, поэтому необработанный кадр не мелькает.
        applyTransitionFrame(open, morphView, backView, fullRect, targetRect, targetRadius,
                0f, swipeTranslation, swipeBack);

        final AnimatorSet animatorSet = new AnimatorSet();
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        // Линейный ход времени: геометрию интерполируем отдельной кривой внутри кадра,
        // а пороги прозрачности считаем по «сырому» прогрессу 0.45f / 0.35f / 0.80f.
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                final Object value = animation.getAnimatedValue();
                final float progress = value instanceof Float ? clamp01((Float) value) : 0f;
                applyTransitionFrame(open, morphView, backView, fullRect, targetRect,
                        targetRadius, progress, swipeTranslation, swipeBack);
                if (incomingFragment != null) {
                    incomingFragment.onTransitionAnimationProgress(true, progress);
                }
                if (outgoingFragment != null) {
                    outgoingFragment.onTransitionAnimationProgress(false, progress);
                }
            }
        });
        animatorSet.playTogether(animator);
        animatorSet.setDuration(duration);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            private boolean wasCancelled;
            private boolean effectsReset;

            @Override
            public void onAnimationCancel(Animator animation) {
                wasCancelled = true;
                // Отменённый переход тоже обязан оставить контейнеры и плашку «чистыми».
                resetEffects();
                if (open) {
                    outgoing.setVisibility(View.GONE);
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                resetEffects();
                if (wasCancelled) {
                    return;
                }
                if (!open && usedCapturedOrigin) {
                    // Капсула отработала как цель закрытия — держать её дальше не нужно.
                    clearOrigin();
                }
                if (onEnd != null) {
                    onEnd.run();
                }
                if (open) {
                    // Нижний слой после раскрытия больше не нужен: пока он видим, он
                    // участвует в layout и оставляет остаточный кадр морфинга.
                    outgoing.setVisibility(View.GONE);
                }
            }

            private void resetEffects() {
                if (effectsReset) {
                    return;
                }
                effectsReset = true;
                // Плашка выключается первой: после перехода на экране не должно остаться
                // ни её кадра, ни сжатого/полупрозрачного/сдвинутого контейнера чата.
                clearMorphOverlay();
                resetScreenEffects(morphView);
                resetScreenEffects(backView);
            }
        });
        // Открытие играем строго после первой раскладки нового фрагмента; закрытие —
        // сразу, уходящий экран уже разложен.
        if (open) {
            startTransitionWhenLaidOut(animatorSet, morphView);
        } else {
            animatorSet.start();
        }
        return animatorSet;
    }

    /**
     * Применяет один кадр морфинга плашки.
     *
     * <p>{@code progress} — «сырой» линейный прогресс аниматора {@code 0..1}. Геометрия
     * плашки интерполируется кривой {@link #getColorOsInterpolator()}, а прозрачности
     * содержимого и самой плашки считаются по фиксированным порогам, чтобы фазы
     * «проявление чата» и «растворение чата» начинались предсказуемо.</p>
     *
     * @param open         направление перехода
     * @param morphView    передний слой (экран чата); только его alpha меняется
     * @param backView     нижний слой (список диалогов); остаётся без масштаба и затемнения
     * @param fullRect     полный прямоугольник экрана в координатах ActionBarLayout
     * @param targetRect   прямоугольник карточки чата в координатах ActionBarLayout
     * @param targetRadius радиус скругления карточки в пикселях
     * @param progress     линейный прогресс аниматора 0..1
     * @param swipeTranslation сдвиг, накопленный свайпом назад (0 для обычных переходов)
     * @param swipeBack    {@code true}, если закрытие завершает жест свайпа назад
     */
    private static void applyTransitionFrame(boolean open, View morphView, View backView,
                                             RectF fullRect, RectF targetRect, float targetRadius,
                                             float progress, float swipeTranslation, boolean swipeBack) {
        final float t = clamp01(progress);
        final float geometry = getColorOsInterpolator().getInterpolation(t);

        // 1. Плашка: при открытии разжимается из карточки на весь экран, при закрытии —
        //    сжимается обратно. Радиус идёт от dp(18) к 0 и наоборот.
        if (open) {
            lerpRect(morphRect, targetRect, fullRect, geometry);
            morphRadius = lerp(targetRadius, 0f, geometry);
        } else {
            lerpRect(morphRect, fullRect, targetRect, geometry);
            morphRadius = lerp(0f, targetRadius, geometry);
        }

        // 2. Содержимое чата: scale всегда 1.0f, translation сбрасывается — сам экран
        //    не деформируется и не обрезается, он лишь проявляется/растворяется.
        morphView.setScaleX(1f);
        morphView.setScaleY(1f);
        // При завершении свайпа назад сохраняем накопленный сдвиг, пока содержимое ещё
        // видно; когда альфа дошла до нуля, сдвиг можно безопасно обнулить.
        if (swipeBack && !open && t < CLOSE_CONTENT_ALPHA_END) {
            morphView.setTranslationX(swipeTranslation);
        } else {
            morphView.setTranslationX(0f);
        }
        morphView.setTranslationY(0f);
        final float contentAlpha;
        if (open) {
            contentAlpha = t <= OPEN_CONTENT_ALPHA_START
                    ? 0f
                    : CubicBezierInterpolator.EASE_OUT.getInterpolation(
                            clamp01((t - OPEN_CONTENT_ALPHA_START) / (1f - OPEN_CONTENT_ALPHA_START)));
        } else {
            contentAlpha = t >= CLOSE_CONTENT_ALPHA_END
                    ? 0f
                    : 1f - CubicBezierInterpolator.EASE_OUT.getInterpolation(
                            clamp01(t / CLOSE_CONTENT_ALPHA_END));
        }
        morphView.setAlpha(contentAlpha);

        // 3. Альфа самой плашки. При открытии она остаётся плотной под проявляющимся чатом;
        //    при закрытии — гаснет только на последних 20% пути, уже поверх карточки.
        if (open) {
            morphAlpha = 1f;
        } else if (t <= CLOSE_OVERLAY_ALPHA_END_START) {
            morphAlpha = 1f;
        } else {
            morphAlpha = clamp01((1f - t) / (1f - CLOSE_OVERLAY_ALPHA_END_START));
        }

        // 4. Нижний слой (список диалогов) не масштабируется и не затемняется: плашка
        //    сжимается ровно в координаты нарисованной карточки.
        backView.setScaleX(1f);
        backView.setScaleY(1f);
        backView.setTranslationX(0f);
        backView.setTranslationY(0f);
        backView.setAlpha(1f);

        morphView.invalidate();
        backView.invalidate();
        // Плашка рисуется в родителе (ActionBarLayout), поэтому просим перерисовать и его:
        // иначе при alpha = 0 у чата кадр плашки мог бы не обновиться.
        final ViewParent parent = morphView.getParent();
        if (parent instanceof View) {
            ((View) parent).invalidate();
        }
    }

    /**
     * @return {@code true}, если контейнер прямо сейчас участвует в морфинге.
     *         Нужен, чтобы статичная тень шапки не рисовалась поверх переходного кадра.
     */
    public static boolean isMorphing(View view) {
        return view != null && morphingViews.containsKey(view);
    }

    /**
     * @return {@code true}, если плашку нужно нарисовать прямо перед этим контейнером.
     *         {@link ActionBarLayout} вызывает {@link #drawMorphOverlay(Canvas)} в своём
     *         {@code drawChild} для этого вида: так плашка оказывается над списком диалогов,
     *         но под содержимым чата, и его плавное проявление/растворение видно корректно.
     */
    public static boolean isMorphFrontContainer(View view) {
        return morphOverlayVisible && view != null && morphFrontView != null
                && morphFrontView.get() == view;
    }

    /** @return {@code true}, пока активен морфинг и плашку нужно рисовать. */
    public static boolean isMorphOverlayVisible() {
        return morphOverlayVisible;
    }

    /** Начинает новый кадр отрисовки: плашка снова может быть нарисована один раз. */
    public static void beginMorphOverlayFrame() {
        morphOverlayDrawnInFrame = false;
    }

    /**
     * Рисует текущий кадр скруглённой плашки-капсулы цветом фона чата.
     * Координаты {@link #morphRect} хранятся в локальном пространстве
     * {@link ActionBarLayout}, поэтому canvas должен быть в тех же координатах.
     *
     * <p>За один кадр плашка рисуется только один раз: штатно её вызывает
     * {@code ActionBarLayout.drawChild} перед передним контейнером, а вызов в конце
     * {@code dispatchDraw} ничего не дублирует.</p>
     */
    public static void drawMorphOverlay(Canvas canvas) {
        if (!morphOverlayVisible || canvas == null || morphAlpha <= 0f) {
            return;
        }
        if (morphOverlayDrawnInFrame) {
            return;
        }
        if (morphRect.width() <= 0f || morphRect.height() <= 0f) {
            return;
        }
        final int alpha = Math.round(255f * clamp01(morphAlpha));
        if (alpha <= 0) {
            return;
        }
        morphOverlayDrawnInFrame = true;
        morphPaint.setStyle(Paint.Style.FILL);
        morphPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        morphPaint.setAlpha(alpha);
        final float radius = Math.max(0f, Math.min(morphRadius,
                Math.min(morphRect.width(), morphRect.height()) / 2f));
        canvas.drawRoundRect(morphRect, radius, radius, morphPaint);
    }

    /** Выключает отрисовку плашки и отпускает ссылку на передний контейнер. */
    private static void clearMorphOverlay() {
        morphOverlayVisible = false;
        morphFrontView = null;
        morphAlpha = 0f;
    }

    /**
     * Принудительно снимает с контейнера все эффекты перехода: масштаб, прозрачность, сдвиги
     * и точку опоры. Плашка при этом выключается, если контейнер был её передним слоем.
     * Нужен и по завершении аниматора, и при аварийном обрыве перехода в
     * {@code ActionBarLayout} — иначе остаётся остаточный кадр перехода.
     */
    public static void resetScreenEffects(View view) {
        if (view == null) {
            return;
        }
        if (morphFrontView != null && morphFrontView.get() == view) {
            clearMorphOverlay();
        }
        morphingViews.remove(view);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setAlpha(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        // Точка опоры возвращается в центр контейнера: следующий переход выставит её заново.
        view.setPivotX(view.getWidth() / 2f);
        view.setPivotY(view.getHeight() / 2f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null);
        }
        view.invalidate();
    }

    /**
     * Запускает анимацию открытия строго после первой раскладки нового фрагмента:
     * ждём {@code OnPreDraw}, когда контейнер и его контент уже получили реальные размеры,
     * и только тогда стартуем. До первого кадра содержимое чата прозрачно, а плашка уже
     * стоит в прямоугольнике карточки, поэтому пустой экран не мелькает.
     */
    private static void startTransitionWhenLaidOut(final AnimatorSet animation, final View container) {
        if (animation == null) {
            return;
        }
        if (isContentLaidOut(container)) {
            animation.start();
            return;
        }
        final boolean[] cancelled = {false};
        final boolean[] started = {false};
        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animator) {
                cancelled[0] = true;
            }
        });
        final Runnable start = new Runnable() {
            @Override
            public void run() {
                // Переход мог быть принудительно завершён до первой раскладки: тогда
                // resetScreenEffects уже снял метку морфинга и запускать аниматор нельзя.
                if (cancelled[0] || started[0]
                        || (container != null && !morphingViews.containsKey(container))) {
                    return;
                }
                started[0] = true;
                animation.start();
            }
        };
        if (container != null) {
            final ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    final ViewTreeObserver observer = container.getViewTreeObserver();
                    if (observer.isAlive()) {
                        observer.removeOnPreDrawListener(this);
                    }
                    start.run();
                    return true;
                }
            };
            container.getViewTreeObserver().addOnPreDrawListener(listener);
            // Страховка: если pre-draw не придёт (например, контейнер остался невидимым),
            // переход всё равно должен доиграть, а не зависнуть в первом кадре.
            container.postDelayed(start, 64L);
        } else {
            start.run();
        }
    }

    /** @return {@code true}, если контейнер и хотя бы один его видимый ребёнок уже разложены */
    private static boolean isContentLaidOut(View container) {
        if (container == null || container.getWidth() <= 0 || container.getHeight() <= 0) {
            return false;
        }
        if (!(container instanceof ViewGroup)) {
            return true;
        }
        final ViewGroup group = (ViewGroup) container;
        for (int a = 0, count = group.getChildCount(); a < count; a++) {
            final View child = group.getChildAt(a);
            if (child == null || child.getVisibility() == View.GONE) {
                continue;
            }
            if (child.getWidth() > 0 && child.getHeight() > 0) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ геометрия */

    /**
     * Полный прямоугольник экрана в координатах {@link ActionBarLayout}. Плашка рисуется
     * именно в них, поэтому берём размеры запомненного контейнера, а если его нет —
     * размеры самого переднего вида (обычно они совпадают).
     */
    private static void resolveMorphFullRect(View morphView, RectF out) {
        final View container = capturedContainerView != null ? capturedContainerView.get() : null;
        if (container != null && container.getWidth() > 0 && container.getHeight() > 0) {
            out.set(0f, 0f, container.getWidth(), container.getHeight());
            return;
        }
        out.set(0f, 0f, morphView.getWidth(), morphView.getHeight());
    }

    /** Запасная центральная капсула, если прямоугольник карточки так и не был запомнен. */
    private static void fallbackCapsule(RectF fullRect, RectF out) {
        final float width = fullRect.width();
        final float height = fullRect.height();
        final float capsuleWidth = width * FALLBACK_CAPSULE_WIDTH_RATIO;
        final float capsuleHeight = height * FALLBACK_CAPSULE_HEIGHT_RATIO;
        final float left = fullRect.left + (width - capsuleWidth) / 2f;
        final float top = fullRect.top + (height - capsuleHeight) / 2f;
        out.set(left, top, left + capsuleWidth, top + capsuleHeight);
    }

    private static void lerpRect(RectF out, RectF from, RectF to, float progress) {
        out.set(
                lerp(from.left, to.left, progress),
                lerp(from.top, to.top, progress),
                lerp(from.right, to.right, progress),
                lerp(from.bottom, to.bottom, progress));
    }

    /* ----------------------------------------------------------- длительность перехода */

    /**
     * Длительность перехода с учётом множителя скорости из настроек (0.5x..1.5x):
     * {@code Math.round(320f / speed)} мс на открытие и {@code Math.round(290f / speed)} мс на закрытие.
     *
     * @param open {@code true} — раскрытие экрана, {@code false} — закрытие
     */
    public static long getTransitionDuration(boolean open) {
        final float speed = Math.max(0.01f, PakoogramConfig.getAnimationSpeed());
        final long base = open ? BASE_OPEN_DURATION : BASE_CLOSE_DURATION;
        return Math.max(1L, Math.round(base / speed));
    }

    /** Длительность открытия экрана: 320 мс, поделённые на множитель скорости. */
    public static long getOpenTransitionDuration() {
        return getTransitionDuration(true);
    }

    /** Длительность закрытия экрана: 290 мс, поделённые на множитель скорости. */
    public static long getCloseTransitionDuration() {
        return getTransitionDuration(false);
    }

    /** Плавная кривая морфинга: {@code PathInterpolator(0.22, 1.0, 0.36, 1.0)}. */
    private static TimeInterpolator getColorOsInterpolator() {
        if (colorOsInterpolator == null) {
            TimeInterpolator interpolator;
            try {
                interpolator = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);
            } catch (Throwable ignore) {
                // Часть прошивок отвергает нестандартные контрольные точки X: берём ту же кривую,
                // посчитанную вручную, чтобы переход не ломался.
                interpolator = new CubicBezierInterpolator(0.22f, 1.0f, 0.36f, 1.0f);
            }
            colorOsInterpolator = interpolator;
        }
        return colorOsInterpolator;
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
}
