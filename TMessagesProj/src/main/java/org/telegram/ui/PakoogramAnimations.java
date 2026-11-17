package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.graphics.Outline;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBarLayout;
import org.telegram.ui.ActionBar.BaseFragment;
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
 *     морфинг экранов: окно обрезается по анимированному {@link ViewOutlineProvider} в границы
 *     карточки и одновременно масштабируется вокруг её центра, нижний слой уходит вглубь;</li>
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

    /** Масштаб нижнего слоя: во время перехода он слегка «уходит вглубь». */
    private static final float BACK_LAYER_SCALE = 0.95f;
    /** Прозрачность нижнего слоя в глубине — лёгкое затемнение вместо отдельной шторки. */
    private static final float BACK_LAYER_ALPHA = 0.75f;
    /** До этой доли пути при закрытии окно остаётся полностью непрозрачным. */
    private static final float CLOSE_ALPHA_HOLD_PROGRESS = 0.65f;
    /** Минимальный масштаб окна при сворачивании в карточку (даже на очень узких карточках). */
    private static final float MIN_MORPH_SCALE = 0.72f;
    /** Базовая длительность открытия до деления на множитель скорости из настроек. */
    private static final long BASE_OPEN_DURATION = 330L;
    /** Базовая длительность закрытия до деления на множитель скорости из настроек. */
    private static final long BASE_CLOSE_DURATION = 300L;
    /** Радиус «запасной» капсулы, если прямоугольник карточки так и не был запомнен. */
    private static final int FALLBACK_CAPSULE_RADIUS_DP = 18;
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
    /** Радиус скругления капсулы в пикселях. */
    private static float capturedCornerRadius;

    /** Уже обработанные элементы: повторная привязка слушателя при переиспользовании ячейки не нужна. */
    private static final WeakHashMap<View, Boolean> springPressViews = new WeakHashMap<>();
    /** Контейнеры, которые прямо сейчас обрезаются в капсулу. */
    private static final WeakHashMap<View, Boolean> morphingViews = new WeakHashMap<>();

    private static TimeInterpolator colorOsInterpolator;

    private PakoogramAnimations() {
        // утилитарный класс
    }

    /* ---------------------------------------------------------------- captureOrigin */

    /**
     * Запоминает точную «капсулу» нажатой карточки чата — внутренний прямоугольник,
     * ограниченный теми же боковыми отступами, что рисует {@link DialogCell}
     * ({@code PAKOGRAM_CARD_MARGIN_HORIZONTAL} / {@code PAKOGRAM_CARD_MARGIN_VERTICAL}),
     * её радиус скругления из {@link PakoogramConfig} и id диалога.
     *
     * <p>Координаты считаются строго в локальном пространстве ближайшего
     * {@link ActionBarLayout}: {@code getLocationInWindow} карточки минус
     * {@code getLocationInWindow} контейнера. Так сдвиг окна на высоту статус-бара
     * (и любые смещения самого окна) не попадает в прямоугольник перехода.</p>
     *
     * <p>Из этого прямоугольника затем раскрывается чат ({@code open == true}) и в него же
     * сворачивается при закрытии ({@code open == false}). Для не-карточек (аватарка/шапка
     * профиля) берётся весь прямоугольник вида, а радиус — половина меньшей стороны,
     * чтобы капсула выглядела круглой/вытянутой, как иконка на рабочем столе.</p>
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
        float cornerRadius;
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
            // Тот же радиус, что рисует сама карточка: иначе капсула морфинга не совпадёт с ней.
            cornerRadius = Math.min(AndroidUtilities.dp(PakoogramConfig.getChatCardCornerRadius()),
                    Math.min(capturedOrigin.width(), capturedOrigin.height()) / 2f);
        } else {
            capturedOrigin.set(cardLeft, cardTop, cardRight, cardBottom);
            cornerRadius = Math.min(view.getWidth(), view.getHeight()) / 2f;
        }
        if (capturedOrigin.width() <= 0f || capturedOrigin.height() <= 0f) {
            clearOrigin();
            return;
        }
        capturedContainerView = originContainer != null ? new WeakReference<>(originContainer) : null;
        capturedDialogId = dialogId;
        capturedCornerRadius = Math.max(0f, cornerRadius);
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
        capturedCornerRadius = 0f;
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
     * Создаёт и сразу запускает морфинг экранов «сворачивание приложения в капсулу».
     *
     * <p>Переход описывается единым прогрессом {@code p}: {@code 0f} — полный экран,
     * {@code 1f} — карточка. Рамка обрезки ({@code currentRect} в {@link ViewOutlineProvider})
     * интерполируется от полного экрана {@code [0, 0, width, height]} до внутреннего
     * прямоугольника карточки чата ({@link #captureOrigin(View)}), а радиус скругления —
     * от {@code 0} до радиуса карточки. Одновременно всё окно масштабируется
     * {@code 1.0f -> Math.max(ширина капсулы / ширина контейнера, 0.72f)} вокруг центра
     * капсулы, поэтому шапка чата и переписка уменьшаются вместе с окном и не обрезаются
     * краем рамки. Никаких ручных {@code translationX/Y} не используется — точку опоры
     * задаёт сам контейнер.</p>
     *
     * <p>Прозрачность: при открытии окно непрозрачно с первого кадра; при закрытии оно
     * остаётся непрозрачным первые 65% пути и гаснет до {@code 0.0f} только на последних
     * 35%, уже внутри карточки. Нижний слой уходит вглубь ({@code 0.95f}) и темнеет
     * ({@code 0.75f}), а при закрытии возвращается обратно.</p>
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

        // Запомненную цель достаём до сброса эффектов, а координаты меряем уже после него:
        // остаточные scale/translation контейнера исказили бы getLocationInWindow.
        final RectF capturedTarget = resolveTargetRect(morphFragment, open);
        final boolean usedCapturedOrigin = capturedTarget != null;
        final RectF fullRect = new RectF(0f, 0f, morphView.getWidth(), morphView.getHeight());
        final RectF targetRect = new RectF();
        final float targetRadius;

        // Сброс возможных хвостов предыдущих переходов: дальше двигаем только обрезку,
        // масштаб окна, альфу и нижний слой. Translation не трогаем вовсе — центр задаёт pivot.
        resetScreenEffects(incoming);
        resetScreenEffects(outgoing);

        if (capturedTarget != null) {
            toLocalRect(morphView, capturedTarget, targetRect);
            // Тот же радиус, что у нарисованной карточки (включая пользовательский 0dp).
            targetRadius = capturedCornerRadius;
        } else {
            fallbackCapsule(morphView, targetRect);
            targetRadius = AndroidUtilities.dp(FALLBACK_CAPSULE_RADIUS_DP);
        }
        // Точка опоры — центр капсулы: масштабируется всё окно вокруг неё, без translationX/Y,
        // которые сбивали бы центр и давали сдвиг на высоту статус-бара.
        morphView.setPivotX(targetRect.centerX());
        morphView.setPivotY(targetRect.centerY());
        final RectF frameRect = new RectF();

        final long duration = getTransitionDuration(open);

        // Метка нужна уже во время первого кадра: LayoutContainer не рисует статичную тень шапки.
        morphingViews.put(morphView, Boolean.TRUE);

        // Первый кадр ставим сразу: при открытии окно уже плотно стоит в капсуле (alpha = 1),
        // при закрытии — ещё полноэкранное, поэтому не мелькает необработанный кадр.
        applyTransitionFrame(open, morphView, backView, fullRect, targetRect,
                targetRadius, frameRect, open ? 1f : 0f);

        final AnimatorSet animatorSet = new AnimatorSet();
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(getColorOsInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                final Object value = animation.getAnimatedValue();
                final float progress = value instanceof Float ? clamp01((Float) value) : 0f;
                // p: 0 — полный экран, 1 — карточка. При открытии время идёт 0 -> 1,
                // поэтому путь к полному экрану — обратный.
                final float p = open ? 1f - progress : progress;
                applyTransitionFrame(open, morphView, backView, fullRect, targetRect,
                        targetRadius, frameRect, p);
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
                // Отменённый переход тоже обязан оставить контейнеры «чистыми».
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
                // Эффекты снимаются всегда: экран не должен остаться обрезанным, сжатым,
                // сдвинутым, повёрнутым вокруг капсулы или прозрачным.
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
     * Применяет один кадр перехода. Прогресс {@code p} единый для обоих направлений:
     * {@code 0f} — полный экран, {@code 1f} — карточка. При открытии он идёт от {@code 1}
     * к {@code 0}, при закрытии — от {@code 0} к {@code 1}, поэтому вся геометрия
     * считается одной и той же формулой и кадры стыкуются без скачков.
     *
     * @param open         направление перехода (влияет только на прозрачность)
     * @param morphView    передний слой, который обрезается по контуру и масштабируется
     * @param backView     нижний слой, уходящий вглубь
     * @param fullRect     полный прямоугольник экрана в координатах {@code morphView}
     * @param targetRect   прямоугольник капсулы в координатах {@code morphView}
     * @param targetRadius радиус скругления капсулы
     * @param frameRect    переиспользуемый буфер текущего прямоугольника обрезки
     * @param progress     прогресс пути 0..1 (0 — полный экран, 1 — карточка)
     */
    private static void applyTransitionFrame(boolean open, View morphView, View backView,
                                             RectF fullRect, RectF targetRect, float targetRadius,
                                             RectF frameRect, float progress) {
        final float p = clamp01(progress);

        // 1. Рамка обрезки: настоящая обрезка окна от полного экрана до капсулы карточки,
        //    радиус скругления растёт от 0 до радиуса карточки.
        lerpRect(frameRect, fullRect, targetRect, p);
        applyCapsuleClip(morphView, frameRect, targetRadius * p);

        // 2. Масштаб всего окна вокруг центра капсулы (pivot задан один раз в
        //    animateScreenTransition). Шапка и переписка уменьшаются вместе с окном,
        //    поэтому не обрезаются краем сужающейся рамки. Никаких translationX/Y.
        final float targetScale = Math.max(
                targetRect.width() / Math.max(1f, fullRect.width()),
                MIN_MORPH_SCALE);
        final float scale = lerp(1f, targetScale, p);
        morphView.setScaleX(scale);
        morphView.setScaleY(scale);

        // 3. Прозрачность. Открытие всегда плотное с первого кадра; закрытие остаётся
        //    непрозрачным первые 65% пути и гаснет только на последних 35%, внутри карточки.
        final float alpha;
        if (open) {
            alpha = 1f;
        } else if (p <= CLOSE_ALPHA_HOLD_PROGRESS) {
            alpha = 1f;
        } else {
            alpha = clamp01((1f - p) / (1f - CLOSE_ALPHA_HOLD_PROGRESS));
        }
        morphView.setAlpha(alpha);
        morphView.invalidate();

        // 4. Нижний слой уходит вглубь и затемняется; при раскрытии возвращается обратно.
        final float backProgress = 1f - p;
        backView.setScaleX(lerp(1f, BACK_LAYER_SCALE, backProgress));
        backView.setScaleY(lerp(1f, BACK_LAYER_SCALE, backProgress));
        backView.setAlpha(lerp(1f, BACK_LAYER_ALPHA, backProgress));
        backView.invalidate();
    }

    /**
     * @return {@code true}, если контейнер прямо сейчас обрезается в капсулу.
     *         Нужен, чтобы статичная тень шапки не рисовалась поверх сжимающегося окна.
     */
    public static boolean isMorphing(View view) {
        return view != null && morphingViews.containsKey(view);
    }

    /**
     * Принудительно снимает с контейнера все эффекты перехода: масштаб, прозрачность, сдвиги,
     * точку опоры, обрезку по контуру и размытие. Нужен и по завершении аниматора, и при
     * аварийном обрыве перехода в {@code ActionBarLayout} — иначе остаётся остаточное
     * изображение (сжатый, смещённый или полупрозрачный слой).
     */
    public static void resetScreenEffects(View view) {
        if (view == null) {
            return;
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
        view.setClipToOutline(false);
        if (view.getOutlineProvider() != ViewOutlineProvider.BACKGROUND) {
            view.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        }
        view.invalidateOutline();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null);
        }
        view.invalidate();
    }

    /**
     * Запускает анимацию открытия строго после первой раскладки нового фрагмента:
     * ждём {@code OnPreDraw}, когда контейнер и его контент уже получили реальные размеры,
     * и только тогда стартуем. До первого кадра окно уже обрезано в капсулу и непрозрачно,
     * поэтому полноэкранный необработанный кадр не мелькает.
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
     * Обрезает view по текущему прямоугольнику капсулы через {@link ViewOutlineProvider}.
     * Контур задаётся в локальных координатах вида и затем трансформируется вместе с ним,
     * поэтому рамка обрезки и масштаб окна всегда движутся синхронно.
     */
    private static void applyCapsuleClip(View view, RectF rect, float radius) {
        if (view == null) {
            return;
        }
        CapsuleOutlineProvider provider;
        if (view.getOutlineProvider() instanceof CapsuleOutlineProvider) {
            provider = (CapsuleOutlineProvider) view.getOutlineProvider();
        } else {
            provider = new CapsuleOutlineProvider();
            view.setOutlineProvider(provider);
        }
        provider.set(rect, radius);
        if (!view.getClipToOutline()) {
            view.setClipToOutline(true);
        }
        view.invalidateOutline();
    }

    /**
     * Переводит прямоугольник капсулы из координат контейнера, в котором он был запомнен
     * ({@link ActionBarLayout}), в локальные координаты вида {@code view}. Обычно контейнер
     * перехода — прямой ребёнок {@link ActionBarLayout} с размерами во весь экран, поэтому
     * смещение нулевое, но вычисляем его честно через {@code getLocationInWindow}.
     */
    private static void toLocalRect(View view, RectF containerLocalRect, RectF out) {
        final int[] viewLocation = new int[2];
        final int[] containerLocation = new int[2];
        view.getLocationInWindow(viewLocation);
        final View container = capturedContainerView != null ? capturedContainerView.get() : null;
        if (container != null) {
            container.getLocationInWindow(containerLocation);
        } else {
            containerLocation[0] = 0;
            containerLocation[1] = 0;
        }
        final float offsetX = containerLocation[0] - viewLocation[0];
        final float offsetY = containerLocation[1] - viewLocation[1];
        out.set(
                containerLocalRect.left + offsetX,
                containerLocalRect.top + offsetY,
                containerLocalRect.right + offsetX,
                containerLocalRect.bottom + offsetY);
    }

    /** Запасная центральная капсула, если прямоугольник карточки так и не был запомнен. */
    private static void fallbackCapsule(View view, RectF out) {
        final float width = view.getWidth();
        final float height = view.getHeight();
        final float capsuleWidth = width * FALLBACK_CAPSULE_WIDTH_RATIO;
        final float capsuleHeight = height * FALLBACK_CAPSULE_HEIGHT_RATIO;
        final float left = (width - capsuleWidth) / 2f;
        final float top = (height - capsuleHeight) / 2f;
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
     * {@code Math.round(330f / speed)} мс на открытие и {@code Math.round(300f / speed)} мс на закрытие.
     *
     * @param open {@code true} — раскрытие экрана, {@code false} — закрытие
     */
    public static long getTransitionDuration(boolean open) {
        final float speed = Math.max(0.01f, PakoogramConfig.getAnimationSpeed());
        final long base = open ? BASE_OPEN_DURATION : BASE_CLOSE_DURATION;
        return Math.max(1L, Math.round(base / speed));
    }

    /** Длительность открытия экрана: 330 мс, поделённые на множитель скорости. */
    public static long getOpenTransitionDuration() {
        return getTransitionDuration(true);
    }

    /** Длительность закрытия экрана: 300 мс, поделённые на множитель скорости. */
    public static long getCloseTransitionDuration() {
        return getTransitionDuration(false);
    }

    /** Плавная кривая морфинга: {@code PathInterpolator(0.25, 0.1, 0.25, 1.0)}. */
    private static TimeInterpolator getColorOsInterpolator() {
        if (colorOsInterpolator == null) {
            TimeInterpolator interpolator;
            try {
                interpolator = new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f);
            } catch (Throwable ignore) {
                // Часть прошивок отвергает нестандартные контрольные точки X: берём ту же кривую,
                // посчитанную вручную, чтобы переход не ломался.
                interpolator = new CubicBezierInterpolator(0.25f, 0.1f, 0.25f, 1.0f);
            }
            colorOsInterpolator = interpolator;
        }
        return colorOsInterpolator;
    }

    /**
     * Провайдер контура капсулы: прямоугольник и радиус меняются каждый кадр, поэтому
     * окно «сворачивается» в карточку по-настоящему, а не растворяется в воздухе.
     */
    private static final class CapsuleOutlineProvider extends ViewOutlineProvider {

        private final RectF capsuleRect = new RectF();
        private float radius;

        void set(RectF rect, float newRadius) {
            capsuleRect.set(rect);
            radius = newRadius;
        }

        @Override
        public void getOutline(View view, Outline outline) {
            final int width = view.getWidth();
            final int height = view.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            if (capsuleRect.width() <= 0f || capsuleRect.height() <= 0f) {
                outline.setRect(0, 0, width, height);
                return;
            }
            final int left = Math.round(capsuleRect.left);
            final int top = Math.round(capsuleRect.top);
            final int right = Math.round(capsuleRect.right);
            final int bottom = Math.round(capsuleRect.bottom);
            if (right <= left || bottom <= top) {
                // Капсула полностью за пределами вида — пустой контур, окно не рисуется.
                outline.setRect(left, top, left + 1, top + 1);
                return;
            }
            final float rounded = Math.min(radius, Math.min(right - left, bottom - top) / 2f);
            if (rounded < 0.5f) {
                outline.setRect(left, top, right, bottom);
            } else {
                outline.setRoundRect(left, top, right, bottom, rounded);
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
}
