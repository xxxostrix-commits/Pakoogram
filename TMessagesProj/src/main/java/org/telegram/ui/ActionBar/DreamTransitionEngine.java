package org.telegram.ui.ActionBar;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

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
    private static final float GEOMETRY_OMEGA = 22.5f;
    private static final float GEOMETRY_ZETA = 0.86f;

    private static final float CONTENT_OMEGA = 26.5f;
    private static final float CONTENT_ZETA = 0.95f;

    /** Порог остановки пружины по отклонению и скорости. */
    private static final float EPSILON_POS = 0.0008f;
    private static final float EPSILON_VEL = 0.008f;

    /** Глубина ухода заднего слоя (списка чатов) при открытии. */
    private static final float BACK_DEPTH_SCALE = 0.94f;
    private static final float BACK_PARALLAX_FACTOR = 0.14f;
    private static final float FALLBACK_START_SCALE = 0.91f;
    private static final float FALLBACK_CORNER_RADIUS_DP = 32f;

    private final ActionBarLayout layout;

    private final RectF capturedSourceRect = new RectF();
    private long capturedDialogId = 0L;
    private float capturedCardRadiusPx = 0f;
    private boolean hasCapturedSource = false;

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
        } catch (Throwable e) {
            FileLog.e(e);
            clearCapturedSource();
        }
    }

    public void clearCapturedSource() {
        capturedSourceRect.setEmpty();
        capturedDialogId = 0L;
        capturedCardRadiusPx = 0f;
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
            clearCapturedSource();
        } else {
            activeSourceRect.setEmpty();
            activeCardRadiusPx = dp(FALLBACK_CORNER_RADIUS_DP);
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
            useContainerMorph = true;
        } else {
            activeSourceRect.setEmpty();
            activeCardRadiusPx = dp(FALLBACK_CORNER_RADIUS_DP);
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

        float speed = MathUtils.clamp(PakoogramConfig.getAnimationSpeed(), 0.5f, 1.5f);
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

        float clampedG = MathUtils.clamp(g, 0f, 1.25f);
        float clampedC = MathUtils.clamp(c, 0f, 1f);

        if (opening) {
            // Открытие: g идёт 0 -> 1.
            if (useContainerMorph) {
                // Aspect-preserving масштаб: содержимое не сплющивается и не растягивается.
                float startScaleX = Math.max(0.05f, activeSourceRect.width() / w);
                float startScaleY = Math.max(0.05f, activeSourceRect.height() / h);
                float startScale = Math.max(startScaleX, startScaleY);
                float scale = lerp(startScale, 1f, clampedG);

                float targetCenterX = w * 0.5f;
                float targetCenterY = h * 0.5f;
                float startCenterX = activeSourceRect.centerX();
                float startCenterY = activeSourceRect.centerY();

                float tx = lerp(startCenterX - targetCenterX, 0f, clampedG);
                float ty = lerp(startCenterY - targetCenterY, 0f, clampedG);

                fg.setPivotX(w * 0.5f);
                fg.setPivotY(h * 0.5f);
                fg.setTranslationX(tx);
                fg.setTranslationY(ty);
                fg.setScaleX(scale);
                fg.setScaleY(scale);

                // Плавный кроссфейд содержимого в первые ~35% пути разворота.
                float contentAlpha = MathUtils.clamp(clampedC / 0.35f, 0.15f, 1f);
                fg.setAlpha(contentAlpha);
            } else {
                float scale = lerp(FALLBACK_START_SCALE, 1f, clampedG);
                float tx = lerp(w * 0.12f, 0f, clampedG);
                fg.setPivotX(w * 0.5f);
                fg.setPivotY(h * 0.5f);
                fg.setTranslationX(tx);
                fg.setTranslationY(0f);
                fg.setScaleX(scale);
                fg.setScaleY(scale);
                fg.setAlpha(MathUtils.clamp(clampedC / 0.4f, 0f, 1f));
            }

            if (bg != null) {
                bg.setVisibility(View.VISIBLE);
                bg.setAlpha(1f);
                bg.setPivotX(w * 0.5f);
                bg.setPivotY(h * 0.5f);
                float backScale = lerp(1f, BACK_DEPTH_SCALE, MathUtils.clamp(clampedG, 0f, 1f));
                bg.setScaleX(backScale);
                bg.setScaleY(backScale);
                bg.setTranslationX(-w * BACK_PARALLAX_FACTOR * MathUtils.clamp(clampedG, 0f, 1f));
                bg.setTranslationY(0f);
                updateBackBlur(bg, MathUtils.clamp(clampedG, 0f, 1f));
            }
        } else {
            // Закрытие: в ActionBarLayout при closeLastFragment верхний закрываемый экран
            // перемещён в containerViewBack, а возвращаемый экран (список чатов) — в containerView.
            float closeT = MathUtils.clamp(clampedG, 0f, 1f);
            float closeC = MathUtils.clamp(clampedC, 0f, 1f);

            if (bg != null) {
                bg.setVisibility(View.VISIBLE);
                if (useContainerMorph) {
                    float endScaleX = Math.max(0.05f, activeSourceRect.width() / w);
                    float endScaleY = Math.max(0.05f, activeSourceRect.height() / h);
                    float endScale = Math.max(endScaleX, endScaleY);
                    float scale = lerp(1f, endScale, closeT);

                    float startCenterX = w * 0.5f;
                    float startCenterY = h * 0.5f;
                    float endCenterX = activeSourceRect.centerX();
                    float endCenterY = activeSourceRect.centerY();

                    bg.setPivotX(w * 0.5f);
                    bg.setPivotY(h * 0.5f);
                    bg.setTranslationX(lerp(0f, endCenterX - startCenterX, closeT));
                    bg.setTranslationY(lerp(0f, endCenterY - startCenterY, closeT));
                    bg.setScaleX(scale);
                    bg.setScaleY(scale);
                    bg.setAlpha(lerp(1f, 0f, MathUtils.clamp((closeC - 0.55f) / 0.45f, 0f, 1f)));
                } else {
                    float scale = lerp(1f, FALLBACK_START_SCALE, closeT);
                    bg.setPivotX(w * 0.5f);
                    bg.setPivotY(h * 0.5f);
                    bg.setTranslationX(lerp(0f, w * 0.62f, closeT));
                    bg.setTranslationY(0f);
                    bg.setScaleX(scale);
                    bg.setScaleY(scale);
                    bg.setAlpha(lerp(1f, 0f, closeC));
                }
            }

            // Список чатов выплывает из глубины обратно в масштаб 1.0 и чёткость.
            fg.setVisibility(View.VISIBLE);
            fg.setAlpha(1f);
            fg.setPivotX(w * 0.5f);
            fg.setPivotY(h * 0.5f);
            float backToFrontScale = lerp(BACK_DEPTH_SCALE, 1f, closeT);
            fg.setScaleX(backToFrontScale);
            fg.setScaleY(backToFrontScale);
            fg.setTranslationX(lerp(-w * BACK_PARALLAX_FACTOR, 0f, closeT));
            fg.setTranslationY(0f);
            updateBackBlur(fg, 1f - closeT);
        }

        layout.invalidate();
        if (bg != null) {
            bg.invalidate();
        }
        fg.invalidate();
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

        // Позиционируемый/морфируемый верхний слой: при открытии это fg, при закрытии — bg.
        final View morphedView = opening ? fg : bg;
        final View depthView = opening ? bg : fg;

        if (child == morphedView) {
            float morphT = opening ? g : (1f - g);
            float radius;
            if (useContainerMorph) {
                tmpRect.set(
                        lerp(activeSourceRect.left, 0f, morphT),
                        lerp(activeSourceRect.top, 0f, morphT),
                        lerp(activeSourceRect.right, w, morphT),
                        lerp(activeSourceRect.bottom, h, morphT)
                );
                radius = lerp(activeCardRadiusPx, 0f, morphT);
            } else {
                float insetX = lerp(w * 0.04f, 0f, morphT);
                float insetY = lerp(h * 0.04f, 0f, morphT);
                tmpRect.set(insetX, insetY, w - insetX, h - insetY);
                radius = lerp(dp(FALLBACK_CORNER_RADIUS_DP), 0f, morphT);
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

            boolean drawn = invoker.draw(canvas, child, drawingTime);
            canvas.restoreToCount(saveCount);
            return drawn;
        }

        if (child == depthView) {
            float depthT = opening ? g : (1f - g);
            int saveCount = canvas.save();
            backClipPath.rewind();
            tmpRect.set(0f, 0f, w, h);
            float backRadius = lerp(0f, dp(18f), depthT);
            backClipPath.addRoundRect(tmpRect, backRadius, backRadius, Path.Direction.CW);
            canvas.clipPath(backClipPath);

            boolean drawn = invoker.draw(canvas, child, drawingTime);

            int scrimAlpha = (int) (72f * depthT);
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
        int blurStrength = PakoogramConfig.getTransitionBlurStrength();
        float radius = blurStrength <= 0 ? 0f : dp(blurStrength * 0.35f) * MathUtils.clamp(intensity, 0f, 1f);
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

        resetSingleView(layout.containerView);
        resetSingleView(layout.containerViewBack);
        lastBackBlurApplied = false;
        lastBlurRadius = -1f;
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