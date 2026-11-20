package org.telegram.ui.ActionBar;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.math.MathUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.PakoogramConfig;

/**
 * Pakoogram: эталонный движок переходов уровня ColorOS (120 Гц) с пружинной физикой
 * и Container Transform (морфинг карточки чата в полноэкранный чат и обратно).
 */
public class DreamTransitionEngine {

    /** Горизонтальный отступ капсулы карточки чата в dp (совместимо с метриками DialogCell). */
    public static final float PAKOGRAM_CARD_MARGIN_HORIZONTAL_DP = 10f;
    /** Вертикальный отступ капсулы карточки чата в dp (совместимо с метриками DialogCell). */
    public static final float PAKOGRAM_CARD_MARGIN_VERTICAL_DP = 3f;

    /** Целевое значение пружины (0 = старт / закрыто, 1 = финиш / открыто). */
    private static final float TARGET_OPEN = 1f;
    private static final float TARGET_CLOSED = 0f;

    /** Параметры затухающей пружины (рад/с и коэффициент демпфирования). */
    private static final float GEOMETRY_OMEGA = 20.0f;
    private static final float GEOMETRY_ZETA = 1.01f;

    private static final float CONTENT_OMEGA = 22.0f;
    private static final float CONTENT_ZETA = 1.00f;

    /** Порог остановки пружины по отклонению и скорости. */
    private static final float EPSILON_POS = 0.0008f;
    private static final float EPSILON_VEL = 0.008f;

    /** Глубина ухода заднего слоя (списка чатов) при открытии. */
    private static final float BACK_DEPTH_SCALE = 0.97f;
    private static final float BACK_PARALLAX_FACTOR = 0.08f;
    /** Стартовый/финальный масштаб контента в Container Transform. */
    private static final float CONTENT_START_SCALE = 0.86f;
    /** Радиус скругления карточки на экране, dp. */
    private static final float CARD_RADIUS_DP = 30f;
    /** Максимальная плотность затемнения заднего слоя (0..255). */
    private static final float SCRIM_MAX_ALPHA = 30f;
    /** Визуальный эквивалент BLUR_STRENGTH = 20 при 120 Гц без просадок GPU, dp. */
    private static final float BLUR_STRENGTH_DP = 13f;
    /** Базовый множитель скорости анимации. */
    private static final float ANIMATION_SPEED_BASE = 1.00f;

    private final ActionBarLayout layout;

    private final RectF capturedSourceRect = new RectF();
    private long capturedDialogId = 0L;
    private float capturedCardRadiusPx = 0f;
    private boolean hasCapturedSource = false;

    // Pakoogram: снимок нажатой карточки чата (DialogCell) для кроссфейда внутри
    // Container Transform. capturedCardBitmap снимается в captureSourceView и живёт
    // до ближайшего animateOpen; lastOpenedCardBitmap хранится до обратного закрытия
    // чата, а activeCardBitmap рисуется текущим переходом в drawChildWithMorph.
    @Nullable
    private Bitmap capturedCardBitmap;
    @Nullable
    private Bitmap lastOpenedCardBitmap;
    @Nullable
    private Bitmap activeCardBitmap;

    // Pakoogram: координаты плашки, в которую открывался чат. Храним их отдельно,
    // потому что capturedSourceRect очищается сразу после animateOpen, а обратный
    // морфинг при закрытии чата должен сжимать экран именно в эту плашку.
    private final RectF lastOpenedSourceRect = new RectF();
    private long lastOpenedDialogId = 0L;
    private float lastOpenedCardRadiusPx = 0f;
    private boolean hasLastOpenedSource = false;

    private final RectF activeSourceRect = new RectF();
    private long activeDialogId = 0L;
    private float activeCardRadiusPx = 0f;
    private boolean useContainerMorph = false;
    private boolean opening = true;
    private boolean running = false;

    // Pakoogram: при закрытии из жеста/предиктивного Back роли контейнеров инвертированы:
    // закрываемый экран ещё лежит в containerView, а список чатов — в containerViewBack.
    private ViewGroup explicitMorphedView = null;
    private ViewGroup explicitDepthView = null;
    private float gestureStartTx = 0f, gestureStartTy = 0f, gestureStartScale = 1f;
    private boolean closingFromGesture = false;

    /** Текущее и целевое состояние пружин. */
    private float geomPos = 0f;
    private float geomVel = 0f;
    private float geomTarget = 1f;

    private float contentPos = 0f;
    private float contentVel = 0f;
    private float contentTarget = 1f;

    private long lastFrameTimeNanos = 0L;
    @Nullable
    private Runnable onCompleteCallback;

    private final Path morphClipPath = new Path();
    private final Path backClipPath = new Path();
    private final RectF tmpRect = new RectF();
    private final RectF tmpSourceScaled = new RectF();
    private final int[] tmpLoc = new int[2];

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private boolean lastBackBlurApplied = false;
    private float lastBlurRadius = -1f;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) {
                return;
            }
            stepFrame(frameTimeNanos);
            if (running) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    public DreamTransitionEngine(@NonNull ActionBarLayout layout) {
        this.layout = layout;
        scrimPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setStyle(Paint.Style.FILL);
        cardBgPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Захватывает экранные координаты плашки/карточки чата до вызова {@code presentFragment}.
     * Учитывает отступы капсулы и радиус скругления из {@link PakoogramConfig}.
     */
    public void captureSourceView(@Nullable View cell, long dialogId) {
        if (cell == null || !cell.isAttachedToWindow() || cell.getWidth() <= 0 || cell.getHeight() <= 0) {
            clearCapturedSource();
            return;
        }
        try {
            cell.getLocationOnScreen(tmpLoc);
            int[] hostLoc = new int[2];
            layout.getLocationOnScreen(hostLoc);

            float left = tmpLoc[0] - hostLoc[0] + dp(PAKOGRAM_CARD_MARGIN_HORIZONTAL_DP);
            float top = tmpLoc[1] - hostLoc[1] + dp(PAKOGRAM_CARD_MARGIN_VERTICAL_DP);
            float right = tmpLoc[0] - hostLoc[0] + cell.getWidth() - dp(PAKOGRAM_CARD_MARGIN_HORIZONTAL_DP);
            float bottom = tmpLoc[1] - hostLoc[1] + cell.getHeight() - dp(PAKOGRAM_CARD_MARGIN_VERTICAL_DP);

            if (right <= left + dp(8) || bottom <= top + dp(8)) {
                clearCapturedSource();
                return;
            }

            capturedSourceRect.set(left, top, right, bottom);
            capturedDialogId = dialogId;
            capturedCardRadiusPx = dp(PakoogramConfig.getChatCardCornerRadius());
            hasCapturedSource = true;

            // Pakoogram: снимаем Bitmap карточки без боковых отступов капсулы, чтобы при
            // Container Transform из неё «перетекал» живой чат, а не вырезалась скважина.
            // recycle() вручную не вызываем: снимок может использоваться в соседнем кадре,
            // старую память освободит GC.
            int cardW = Math.round(right - left);
            int cardH = Math.round(bottom - top);
            capturedCardBitmap = null;
            if (cardW > 0 && cardH > 0) {
                Bitmap bmp = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888);
                Canvas bmpCanvas = new Canvas(bmp);
                bmpCanvas.translate(-dp(PAKOGRAM_CARD_MARGIN_HORIZONTAL_DP), -dp(PAKOGRAM_CARD_MARGIN_VERTICAL_DP));
                cell.draw(bmpCanvas);
                capturedCardBitmap = bmp;
            }
        } catch (Throwable e) {
            FileLog.e(e);
            clearCapturedSource();
        }
    }

    public void clearCapturedSource() {
        capturedSourceRect.setEmpty();
        capturedDialogId = 0L;
        capturedCardRadiusPx = 0f;
        capturedCardBitmap = null;
        hasCapturedSource = false;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isOpening() {
        return opening;
    }

    /**
     * Запускает анимацию открытия нового фрагмента (поддерживает прерывание активного перехода).
     *
     * @param fragment   открываемый фрагмент
     * @param isChat     true, если фрагмент — чат ({@link ChatActivity})
     * @param onComplete коллбэк завершения (вызывается в {@code ActionBarLayout.onAnimationEndCheck})
     */
    public void animateOpen(@Nullable BaseFragment fragment, boolean isChat, @Nullable Runnable onComplete) {
        final boolean wasRunning = running;
        stopFrameLoop();

        this.opening = true;
        this.onCompleteCallback = onComplete;
        clearGestureRoleOverride();

        if (isChat && hasCapturedSource && !capturedSourceRect.isEmpty()) {
            activeSourceRect.set(capturedSourceRect);
            activeDialogId = capturedDialogId;
            activeCardRadiusPx = capturedCardRadiusPx;
            // Pakoogram: запоминаем ту же плашку для обратного морфинга при закрытии.
            lastOpenedSourceRect.set(capturedSourceRect);
            lastOpenedDialogId = capturedDialogId;
            lastOpenedCardRadiusPx = capturedCardRadiusPx;
            hasLastOpenedSource = true;
            useContainerMorph = true;
            // Pakoogram: снимок карточки забираем ДО clearCapturedSource(), иначе
            // кроссфейд в drawChildWithMorph будет нечем рисовать.
            activeCardBitmap = capturedCardBitmap;
            lastOpenedCardBitmap = capturedCardBitmap;
            clearCapturedSource();
        } else {
            activeSourceRect.setEmpty();
            activeCardRadiusPx = dp(CARD_RADIUS_DP);
            activeCardBitmap = null;
            useContainerMorph = false;
        }

        if (!wasRunning) {
            geomPos = 0f;
            geomVel = 0f;
            contentPos = 0f;
            contentVel = 0f;
        } else {
            // Прерывание: сохраняем текущую позицию и скорость, меняем цель на открытие.
            geomPos = MathUtils.clamp(geomPos, 0f, 1f);
            contentPos = MathUtils.clamp(contentPos, 0f, 1f);
        }

        geomTarget = TARGET_OPEN;
        contentTarget = TARGET_OPEN;

        applyVisualState(geomPos, contentPos);
        startFrameLoop();
    }

    /**
     * Запускает анимацию закрытия верхнего фрагмента (кнопка «Назад» и т.п.).
     * При наличии сохранённых координат плашки для этого чата сжимает экран обратно в неё.
     */
    public void animateClose(@Nullable BaseFragment closingFragment, long closingDialogId, @Nullable Runnable onComplete) {
        final boolean wasRunning = running;
        stopFrameLoop();

        this.opening = false;
        this.onCompleteCallback = onComplete;
        clearGestureRoleOverride();

        // capturedSourceRect к этому моменту уже очищен в animateOpen,
        // поэтому обратный морфинг берёт сохранённую плашку lastOpenedSourceRect.
        boolean canMorphBack = closingFragment instanceof ChatActivity
                && hasLastOpenedSource
                && !lastOpenedSourceRect.isEmpty()
                && (closingDialogId == 0L || lastOpenedDialogId == 0L || lastOpenedDialogId == closingDialogId);

        if (canMorphBack) {
            activeSourceRect.set(lastOpenedSourceRect);
            activeDialogId = lastOpenedDialogId;
            activeCardRadiusPx = lastOpenedCardRadiusPx;
            // Pakoogram: возвращаем снимок карточки, снятый при открытии этого чата.
            activeCardBitmap = lastOpenedCardBitmap;
            useContainerMorph = true;
        } else {
            activeSourceRect.setEmpty();
            activeCardRadiusPx = dp(CARD_RADIUS_DP);
            activeCardBitmap = null;
            useContainerMorph = false;
        }

        if (!wasRunning) {
            // Прогресс закрытия идёт от 0 (полноэкранный чат) к 1 (сжат в карточку / ушёл).
            geomPos = 0f;
            geomVel = 0f;
            contentPos = 0f;
            contentVel = 0f;
        } else {
            geomPos = MathUtils.clamp(geomPos, 0f, 1f);
            contentPos = MathUtils.clamp(contentPos, 0f, 1f);
        }

        geomTarget = TARGET_OPEN;
        contentTarget = TARGET_OPEN;

        applyVisualState(geomPos, contentPos);
        startFrameLoop();
    }

    /**
     * Pakoogram: плавное закрытие из свайпа-назад / системного Predictive Back.
     * В этом сценарии роли контейнеров инвертированы относительно {@link #animateClose}:
     * закрываемый экран ещё находится в {@code containerView}, а список чатов — в {@code containerViewBack}.
     *
     * @param closingView      контейнер с закрываемым экраном (containerView)
     * @param revealedView     контейнер с открываемым списком чатов (containerViewBack)
     * @param closingFragment  закрываемый фрагмент (для проверки Container Transform)
     * @param closingDialogId  id диалога закрываемого чата (0, если неизвестен)
     * @param startTx          текущий сдвиг закрываемого контейнера по X
     * @param startTy          текущий сдвиг закрываемого контейнера по Y
     * @param startScale       текущий масштаб закрываемого контейнера
     * @param onComplete       коллбэк завершения анимации
     */
    public void animateCloseFromGesture(@NonNull ViewGroup closingView, @NonNull ViewGroup revealedView,
                                        @Nullable BaseFragment closingFragment, long closingDialogId,
                                        float startTx, float startTy, float startScale,
                                        @Nullable Runnable onComplete) {
        stopFrameLoop();

        this.opening = false;
        this.closingFromGesture = true;
        this.explicitMorphedView = closingView;
        this.explicitDepthView = revealedView;
        this.gestureStartTx = startTx;
        this.gestureStartTy = startTy;
        this.gestureStartScale = startScale;
        this.onCompleteCallback = onComplete;

        // Проверка идентична animateClose: обратный морфинг возможен только для чата,
        // плашка которого была сохранена при открытии.
        boolean canMorphBack = closingFragment instanceof ChatActivity
                && hasLastOpenedSource
                && !lastOpenedSourceRect.isEmpty()
                && (closingDialogId == 0L || lastOpenedDialogId == 0L || lastOpenedDialogId == closingDialogId);

        if (canMorphBack) {
            activeSourceRect.set(lastOpenedSourceRect);
            activeDialogId = lastOpenedDialogId;
            activeCardRadiusPx = lastOpenedCardRadiusPx;
            // Pakoogram: снимок карточки нужен и при закрытии из жеста «Назад».
            activeCardBitmap = lastOpenedCardBitmap;
            useContainerMorph = true;
        } else {
            activeSourceRect.setEmpty();
            activeCardRadiusPx = dp(CARD_RADIUS_DP);
            activeCardBitmap = null;
            useContainerMorph = false;
        }

        geomPos = 0f;
        geomVel = 0f;
        contentPos = 0f;
        contentVel = 0f;
        geomTarget = TARGET_OPEN;
        contentTarget = TARGET_OPEN;

        applyVisualState(0f, 0f);
        startFrameLoop();
    }

    /** Pakoogram: сбрасывает переопределение ролей слоёв, использованное жестом «Назад». */
    private void clearGestureRoleOverride() {
        closingFromGesture = false;
        explicitMorphedView = null;
        explicitDepthView = null;
        gestureStartTx = 0f;
        gestureStartTy = 0f;
        gestureStartScale = 1f;
    }

    /**
     * Мгновенно завершает или отменяет переход и полностью сбрасывает все визуальные трансформации.
     */
    public void cancel() {
        stopFrameLoop();
        onCompleteCallback = null;
        resetVisualTransforms();
    }

    /**
     * Полный сброс состояния движка и всех трансформаций контейнеров.
     */
    public void reset() {
        stopFrameLoop();
        onCompleteCallback = null;
        // Pakoogram: lastOpenedSourceRect намеренно не очищаем — закрытие чата
        // всегда должно сжиматься обратно в плашку, из которой он открывался.
        resetVisualTransforms();
    }

    private void startFrameLoop() {
        running = true;
        lastFrameTimeNanos = System.nanoTime();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stopFrameLoop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    private void stepFrame(long frameTimeNanos) {
        long rawDeltaNs = frameTimeNanos - lastFrameTimeNanos;
        lastFrameTimeNanos = frameTimeNanos;

        if (rawDeltaNs <= 0L) {
            rawDeltaNs = 8_333_333L; // ~120 Гц по умолчанию
        } else if (rawDeltaNs > 50_000_000L) {
            rawDeltaNs = 16_666_666L; // защита от скачков при сворачивании/фоне
        }

        float speed = ANIMATION_SPEED_BASE * MathUtils.clamp(PakoogramConfig.getAnimationSpeed(), 0.5f, 1.5f);
        float totalDt = (rawDeltaNs / 1_000_000_000f) * speed;

        // Интегрируем суб-шагами до 4 мс для точности пружины на 60/120 Гц.
        final float maxSubStep = 0.004f;
        float remaining = totalDt;
        while (remaining > 0f) {
            float h = Math.min(remaining, maxSubStep);
            geomVel = integrateSpringVel(geomPos, geomVel, geomTarget, GEOMETRY_OMEGA, GEOMETRY_ZETA, h);
            geomPos += geomVel * h;

            contentVel = integrateSpringVel(contentPos, contentVel, contentTarget, CONTENT_OMEGA, CONTENT_ZETA, h);
            contentPos += contentVel * h;

            remaining -= h;
        }

        applyVisualState(geomPos, contentPos);

        boolean geomSettled = Math.abs(geomTarget - geomPos) < EPSILON_POS && Math.abs(geomVel) < EPSILON_VEL;
        boolean contentSettled = Math.abs(contentTarget - contentPos) < EPSILON_POS && Math.abs(contentVel) < EPSILON_VEL;

        if (geomSettled && contentSettled) {
            geomPos = geomTarget;
            geomVel = 0f;
            contentPos = contentTarget;
            contentVel = 0f;
            applyVisualState(geomPos, contentPos);
            stopFrameLoop();

            Runnable cb = onCompleteCallback;
            onCompleteCallback = null;
            if (cb != null) {
                cb.run();
            }
        }
    }

    private static float integrateSpringVel(float pos, float vel, float target, float omega, float zeta, float dt) {
        float displacement = pos - target;
        float accel = -(omega * omega) * displacement - 2f * zeta * omega * vel;
        return vel + accel * dt;
    }

    private void applyVisualState(float g, float c) {
        ViewGroup fg = layout.containerView;
        ViewGroup bg = layout.containerViewBack;
        if (fg == null) {
            return;
        }

        final float w = layout.getWidth() > 0 ? layout.getWidth() : AndroidUtilities.displaySize.x;
        final float h = layout.getHeight() > 0 ? layout.getHeight() : AndroidUtilities.displaySize.y;
        if (w <= 0f || h <= 0f) {
            return;
        }

        final float clampedG = MathUtils.clamp(g, 0f, 1.25f);
        final float clampedC = MathUtils.clamp(c, 0f, 1f);
        final float closeC = clampedC;
        final float closeT = MathUtils.clamp(clampedG, 0f, 1f);

        // Pakoogram: при обычном закрытии закрываемый экран лежит в bg, а список чатов — в fg.
        // При закрытии из жеста «Назад» роли контейнеров инвертированы и заданы явно.
        final ViewGroup morphed = explicitMorphedView != null ? explicitMorphedView : (opening ? fg : bg);
        final ViewGroup depth = explicitDepthView != null ? explicitDepthView : (opening ? bg : fg);

        if (morphed != null) {
            morphed.setVisibility(View.VISIBLE);
            if (opening) {
                // Container Transform и Fallback при открытии всегда полностью непрозрачны.
                morphed.setAlpha(1f);
                if (useContainerMorph) {
                    // Pakoogram: Container Transform целиком рисует drawChildWithMorph
                    // напрямую на Canvas, поэтому View-трансформы нейтральны — иначе
                    // карточка масштабировалась бы дважды.
                    morphed.setPivotX(w * 0.5f);
                    morphed.setPivotY(h * 0.5f);
                    morphed.setTranslationX(0f);
                    morphed.setTranslationY(0f);
                    morphed.setScaleX(1f);
                    morphed.setScaleY(1f);
                    morphed.setAlpha(1f);
                } else {
                    morphed.setPivotX(w * 0.5f);
                    morphed.setPivotY(h * 0.5f);
                    morphed.setTranslationX(lerp(w * 0.14f, 0f, clampedG));
                    morphed.setTranslationY(0f);
                    float s = lerp(CONTENT_START_SCALE, 1f, clampedG);
                    morphed.setScaleX(s);
                    morphed.setScaleY(s);
                }
            } else {
                final float startS = closingFromGesture ? gestureStartScale : 1f;
                final float startX = closingFromGesture ? gestureStartTx : 0f;
                final float startY = closingFromGesture ? gestureStartTy : 0f;
                if (useContainerMorph) {
                    // Pakoogram: сжатие обратно в карточку делает drawChildWithMorph,
                    // поэтому View остаётся без сдвигов/масштаба и полностью непрозрачным.
                    morphed.setPivotX(w * 0.5f);
                    morphed.setPivotY(h * 0.5f);
                    morphed.setTranslationX(0f);
                    morphed.setTranslationY(0f);
                    morphed.setScaleX(1f);
                    morphed.setScaleY(1f);
                    morphed.setAlpha(1f);
                } else {
                    // Погасание только в последние 15% закрытия — без «полупрозрачной каши».
                    morphed.setAlpha(closeC > 0.85f ? lerp(1f, 0f, (closeC - 0.85f) / 0.15f) : 1f);
                    morphed.setPivotX(w * 0.5f);
                    morphed.setPivotY(h * 0.5f);
                    morphed.setTranslationX(lerp(startX, w * 0.35f, closeT));
                    morphed.setTranslationY(lerp(startY, 0f, closeT));
                    float s = lerp(startS, CONTENT_START_SCALE, closeT);
                    morphed.setScaleX(s);
                    morphed.setScaleY(s);
                }
            }
        }

        // Задний слой уходит в глубину: масштаб + лёгкий parallax + blur.
        if (depth != null) {
            depth.setVisibility(View.VISIBLE);
            depth.setAlpha(1f);
            depth.setPivotX(w * 0.5f);
            depth.setPivotY(h * 0.5f);
            float depthT = opening ? MathUtils.clamp(clampedG, 0f, 1f) : (1f - closeT);
            float backScale = lerp(1f, BACK_DEPTH_SCALE, depthT);
            depth.setScaleX(backScale);
            depth.setScaleY(backScale);
            if (useContainerMorph) {
                depth.setTranslationX(0f);
            } else {
                depth.setTranslationX(-w * BACK_PARALLAX_FACTOR * depthT);
            }
            depth.setTranslationY(0f);
            updateBackBlur(depth, depthT);
        }

        layout.invalidate();
        if (bg != null) {
            bg.invalidate();
        }
        if (fg != null) {
            fg.invalidate();
        }
    }

    /**
     * Вызывается из {@link ActionBarLayout#drawChild(Canvas, View, long)} для отсечения
     * (clipPath/clipRect) по границе морфинга и скругления углов, а также отрисовки свечения/тени и scrim.
     */
    public boolean drawChildWithMorph(@NonNull Canvas canvas, @NonNull View child, long drawingTime,
                                      @NonNull ChildDrawInvoker invoker) {
        if (!running) {
            return invoker.draw(canvas, child, drawingTime);
        }

        final float w = layout.getWidth();
        final float h = layout.getHeight();
        if (w <= 0f || h <= 0f) {
            return invoker.draw(canvas, child, drawingTime);
        }

        final float g = MathUtils.clamp(geomPos, 0f, 1f);
        final ViewGroup fg = layout.containerView;
        final ViewGroup bg = layout.containerViewBack;

        // Pakoogram: при обычном переходе морфируется fg при открытии и bg при закрытии.
        // При закрытии из жеста «Назад» роли контейнеров заданы явно.
        final View morphedView = explicitMorphedView != null ? explicitMorphedView : (opening ? fg : bg);
        final View depthView = explicitDepthView != null ? explicitDepthView : (opening ? bg : fg);

        if (child == morphedView) {
            float morphT = opening ? g : (1f - g);
            float radius;
            if (useContainerMorph) {
                float startL = 0f;
                float startT = 0f;
                float startR = w;
                float startB = h;
                if (!opening && closingFromGesture) {
                    // При закрытии из жеста конечный прямоугольник совпадает с тем,
                    // в котором палец «держал» экран: сдвиг + масштаб жеста.
                    startL = gestureStartTx;
                    startT = gestureStartTy;
                    startR = gestureStartTx + w * gestureStartScale;
                    startB = gestureStartTy + h * gestureStartScale;
                }
                tmpRect.set(
                        lerp(activeSourceRect.left, startL, morphT),
                        lerp(activeSourceRect.top, startT, morphT),
                        lerp(activeSourceRect.right, startR, morphT),
                        lerp(activeSourceRect.bottom, startB, morphT)
                );
                radius = lerp(dp(CARD_RADIUS_DP), 0f, morphT);
            } else {
                tmpRect.set(0f, 0f, w, h);
                radius = lerp(dp(CARD_RADIUS_DP), 0f, morphT);
            }

            // Мягкая тень/свечение вокруг карточки во время полёта.
            float elevationFactor = (float) Math.sin(morphT * Math.PI);
            if (elevationFactor > 0.01f) {
                int shadowAlpha = (int) (46f * elevationFactor);
                shadowPaint.setColor(Color.argb(shadowAlpha, 0, 0, 0));
                float pad = dp(6f * elevationFactor);
                tmpSourceScaled.set(tmpRect.left - pad, tmpRect.top, tmpRect.right + pad, tmpRect.bottom + pad);
                canvas.drawRoundRect(tmpSourceScaled, radius + pad, radius + pad, shadowPaint);
            }

            int saveCount = canvas.save();
            morphClipPath.rewind();
            morphClipPath.addRoundRect(tmpRect, radius, radius, Path.Direction.CW);
            canvas.clipPath(morphClipPath);

            // Плотный фон темы внутри карточки — без прозрачных дыр в содержимом.
            cardBgPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            canvas.drawRect(tmpRect, cardBgPaint);

            if (!useContainerMorph) {
                boolean drawn = invoker.draw(canvas, child, drawingTime);
                canvas.restoreToCount(saveCount);
                return drawn;
            }

            // Pakoogram: живой чат (шапка + переписка) привязан к верху карточки и
            // масштабируется вместе с ней, а снимок DialogCell плавно растворяется поверх.
            boolean drawn = false;
            float contentAlpha = MathUtils.clamp((morphT - 0.10f) / 0.38f, 0f, 1f);
            if (contentAlpha > 0.01f) {
                int contentSave = canvas.save();
                canvas.translate(tmpRect.left, tmpRect.top);
                float uniformScale = Math.max(0.05f, tmpRect.width() / w);
                canvas.scale(uniformScale, uniformScale);
                if (contentAlpha < 0.99f) {
                    canvas.saveLayerAlpha(0f, 0f, w, h, Math.round(255f * contentAlpha));
                }
                drawn = invoker.draw(canvas, child, drawingTime);
                canvas.restoreToCount(contentSave);
            }

            float cardAlpha = 1f - MathUtils.clamp(morphT / 0.42f, 0f, 1f);
            if (activeCardBitmap != null && !activeCardBitmap.isRecycled() && cardAlpha > 0.01f) {
                int bmpSave = canvas.save();
                canvas.translate(tmpRect.left, tmpRect.top);
                float snapScale = tmpRect.width() / Math.max(1f, (float) activeCardBitmap.getWidth());
                canvas.scale(snapScale, snapScale);
                bitmapPaint.setAlpha(Math.round(255f * cardAlpha));
                canvas.drawBitmap(activeCardBitmap, 0f, 0f, bitmapPaint);
                canvas.restoreToCount(bmpSave);
            }
            canvas.restoreToCount(saveCount);
            return drawn;
        }

        if (child == depthView) {
            float depthT = opening ? g : (1f - g);
            int saveCount = canvas.save();
            backClipPath.rewind();
            tmpRect.set(0f, 0f, w, h);
            float backRadius = lerp(0f, dp(24f), depthT);
            backClipPath.addRoundRect(tmpRect, backRadius, backRadius, Path.Direction.CW);
            canvas.clipPath(backClipPath);

            boolean drawn = invoker.draw(canvas, child, drawingTime);

            int scrimAlpha = (int) (SCRIM_MAX_ALPHA * depthT);
            if (scrimAlpha > 0) {
                scrimPaint.setColor(Color.argb(scrimAlpha, 0, 0, 0));
                canvas.drawRect(0f, 0f, w, h, scrimPaint);
            }

            canvas.restoreToCount(saveCount);
            return drawn;
        }

        return invoker.draw(canvas, child, drawingTime);
    }

    private void updateBackBlur(@NonNull View targetView, float intensity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        float radius = dp(BLUR_STRENGTH_DP) * MathUtils.clamp(intensity, 0f, 1f);
        if (radius > 0.4f) {
            if (Math.abs(radius - lastBlurRadius) > 0.35f || !lastBackBlurApplied) {
                targetView.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
                lastBlurRadius = radius;
                lastBackBlurApplied = true;
            }
        } else if (lastBackBlurApplied) {
            targetView.setRenderEffect(null);
            lastBlurRadius = -1f;
            lastBackBlurApplied = false;
        }
    }

    private void resetVisualTransforms() {
        geomPos = 0f;
        geomVel = 0f;
        contentPos = 0f;
        contentVel = 0f;
        useContainerMorph = false;
        activeSourceRect.setEmpty();
        activeDialogId = 0L;
        activeCardRadiusPx = 0f;
        // activeCardBitmap сбрасываем, а lastOpenedCardBitmap оставляем —
        // он нужен для обратного закрытия чата в карточку.
        activeCardBitmap = null;

        // Pakoogram: снимаем переопределение ролей слоёв после жеста и обнуляем
        // трансформы, которые могли быть применены к «перевёрнутым» контейнерам.
        closingFromGesture = false;
        resetSingleView(explicitMorphedView);
        resetSingleView(explicitDepthView);
        explicitMorphedView = null;
        explicitDepthView = null;
        gestureStartTx = 0f;
        gestureStartTy = 0f;
        gestureStartScale = 1f;

        resetSingleView(layout.containerView);
        resetSingleView(layout.containerViewBack);
        lastBackBlurApplied = false;
        lastBlurRadius = -1f;
        // lastOpenedSourceRect намеренно не очищаем — он нужен для следующих закрытий.
    }

    private void resetSingleView(@Nullable View v) {
        if (v == null) {
            return;
        }
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(1f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setRenderEffect(null);
        }
    }

    @FunctionalInterface
    public interface ChildDrawInvoker {
        boolean draw(@NonNull Canvas canvas, @NonNull View child, long drawingTime);
    }
}