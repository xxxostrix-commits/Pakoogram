package org.telegram.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.LruCache;

import org.telegram.messenger.AndroidUtilities;

/**
 * Pakoogram: изолированная отрисовка «амбилайт»-свечения вокруг аватарок.
 *
 * <p>Класс не знает ничего о конкретных экранах: он умеет извлекать 24 цвета по кругу
 * из битмапа аватарки, рисовать мягкий градиент внутри карточки диалога и круговую ауру
 * вокруг аватарки в профиле. Все цвета кешируются по {@code long}-ключу, поэтому
 * повторная отрисовка не трогает пиксели битмапа.</p>
 */
public final class AmbilightAvatarGlow {

    /** Количество секторов вокруг аватарки. */
    public static final int SECTOR_COUNT = 24;

    /** Сколько цветовых колец держим в LRU-кэше. */
    private static final int CACHE_ENTRIES = 96;
    /** Ёмкость кэша в байтах: одно кольцо — 24 int по 4 байта. */
    private static final int CACHE_SIZE_BYTES = CACHE_ENTRIES * SECTOR_COUNT * 4;

    /** Радиусы выборки: 72%–94% от радиуса вписанной в битмап окружности. */
    private static final float[] SAMPLE_RADII = {0.72f, 0.795f, 0.87f, 0.94f};
    /** Сколько угловых проб берётся внутри каждого сектора. */
    private static final int ANGULAR_SAMPLES = 5;
    /** Минимальная альфа пикселя, который вообще участвует в анализе. */
    private static final int MIN_PIXEL_ALPHA = 32;

    /** Насколько сильно поднимается насыщенность в HSV, чтобы цвет не был серым. */
    private static final float SATURATION_BOOST = 1.55f;
    private static final float SATURATION_OFFSET = 0.18f;
    /** Минимальная яркость итогового цвета. */
    private static final float MIN_VALUE = 0.55f;

    private static final int[] TRANSPARENT_SECTORS = new int[SECTOR_COUNT];

    private static final LruCache<Long, int[]> CACHE = new LruCache<Long, int[]>(CACHE_SIZE_BYTES) {
        @Override
        protected int sizeOf(Long key, int[] value) {
            return value == null ? 0 : value.length * 4;
        }
    };

    private AmbilightAvatarGlow() {
        // утилитарный класс
    }

    /** @return закешированные 24 цвета для ключа или {@code null}, если их ещё нет. */
    public static int[] getCachedSectors(long cacheKey) {
        return CACHE.get(cacheKey);
    }

    /**
     * Возвращает 24 цвета по кругу аватарки. Если для ключа уже есть кольцо, битмап
     * не анализируется повторно.
     *
     * @param cacheKey стабильный ключ (например, id диалога/пользователя)
     * @param bitmap   битмап аватарки; {@code null} даёт полностью прозрачное кольцо
     */
    public static int[] getSectors(long cacheKey, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return transparentSectors();
        }
        final int[] cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        final int[] sectors = extractSectors(bitmap);
        CACHE.put(cacheKey, sectors);
        return sectors;
    }

    /** Полностью сбрасывает кэш цветовых колец. */
    public static void clearCache() {
        CACHE.evictAll();
    }

    /**
     * Извлекает 24 цвета из кольца на радиусе 72%–94% от центра аватарки.
     *
     * <p>Пиксели усредняются по нескольким радиусам и углам внутри сектора. Затем
     * насыщенность поднимается в HSV: даже серая/чёрная аватарка даёт живую
     * сине-фиолетовую ауру, а цветная — более сочные оттенки без «мыла».</p>
     */
    public static int[] extractSectors(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return transparentSectors();
        }

        Bitmap source = bitmap;
        boolean recycleSource = false;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return transparentSectors();
        }

        // Аватарки обычно маленькие, но на всякий случай ограничиваем объём выборки.
        final int maxSide = Math.max(width, height);
        if (maxSide > 192) {
            final float scale = 192f / maxSide;
            final int scaledWidth = Math.max(1, Math.round(width * scale));
            final int scaledHeight = Math.max(1, Math.round(height * scale));
            final Bitmap scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
            if (scaled != null && scaled != bitmap) {
                source = scaled;
                recycleSource = true;
                width = scaledWidth;
                height = scaledHeight;
            }
        }

        int[] pixels;
        try {
            pixels = new int[width * height];
            source.getPixels(pixels, 0, width, 0, 0, width, height);
        } catch (Throwable ignore) {
            // Hardware-битмапы нельзя читать напрямую — делаем программную копию.
            Bitmap copy = null;
            try {
                copy = source.copy(Bitmap.Config.ARGB_8888, false);
                if (copy == null) {
                    return transparentSectors();
                }
                width = copy.getWidth();
                height = copy.getHeight();
                pixels = new int[width * height];
                copy.getPixels(pixels, 0, width, 0, 0, width, height);
            } catch (Throwable ignored) {
                return transparentSectors();
            } finally {
                if (copy != null) {
                    copy.recycle();
                }
                if (recycleSource && source != bitmap && !source.isRecycled()) {
                    source.recycle();
                }
            }
        }
        if (recycleSource && source != bitmap && !source.isRecycled()) {
            source.recycle();
        }

        final float centerX = (width - 1) * 0.5f;
        final float centerY = (height - 1) * 0.5f;
        final float maxRadius = Math.min(width, height) * 0.5f;
        final float angleStep = (float) (Math.PI * 2.0 / SECTOR_COUNT);

        final int[] raw = new int[SECTOR_COUNT];
        final int[] counts = new int[SECTOR_COUNT];
        int totalCount = 0;

        for (int i = 0; i < SECTOR_COUNT; i++) {
            long red = 0;
            long green = 0;
            long blue = 0;
            int count = 0;
            for (float radiusFraction : SAMPLE_RADII) {
                final float radius = maxRadius * radiusFraction;
                for (int s = 0; s < ANGULAR_SAMPLES; s++) {
                    // Пробы распределены по всей ширине сектора, а не только по центру.
                    final float offset = (s - (ANGULAR_SAMPLES - 1) * 0.5f) / ANGULAR_SAMPLES;
                    final double angle = (i + 0.5f + offset) * angleStep;
                    final int x = (int) (centerX + Math.cos(angle) * radius);
                    final int y = (int) (centerY + Math.sin(angle) * radius);
                    if (x < 0 || y < 0 || x >= width || y >= height) {
                        continue;
                    }
                    final int pixel = pixels[y * width + x];
                    final int alpha = Color.alpha(pixel);
                    if (alpha < MIN_PIXEL_ALPHA) {
                        continue;
                    }
                    red += Color.red(pixel) * (long) alpha;
                    green += Color.green(pixel) * (long) alpha;
                    blue += Color.blue(pixel) * (long) alpha;
                    count += alpha;
                }
            }
            if (count > 0) {
                raw[i] = Color.rgb((int) (red / count), (int) (green / count), (int) (blue / count));
                counts[i] = count;
                totalCount += count;
            }
        }

        if (totalCount == 0) {
            return transparentSectors();
        }

        // Пустые сектора (например, углы прямоугольного битмапа) заполняем соседними.
        for (int i = 0; i < SECTOR_COUNT; i++) {
            if (counts[i] != 0) {
                continue;
            }
            int color = 0;
            for (int step = 1; step < SECTOR_COUNT; step++) {
                final int previous = (i - step + SECTOR_COUNT) % SECTOR_COUNT;
                if (counts[previous] != 0) {
                    color = raw[previous];
                    break;
                }
                final int next = (i + step) % SECTOR_COUNT;
                if (counts[next] != 0) {
                    color = raw[next];
                    break;
                }
            }
            raw[i] = color;
        }

        // Мягко сглаживаем стыки секторов, чтобы на градиенте не было резких полос.
        final int[] smoothed = new int[SECTOR_COUNT];
        for (int i = 0; i < SECTOR_COUNT; i++) {
            final int previous = raw[(i - 1 + SECTOR_COUNT) % SECTOR_COUNT];
            final int current = raw[i];
            final int next = raw[(i + 1) % SECTOR_COUNT];
            smoothed[i] = Color.rgb(
                    (Color.red(previous) + 2 * Color.red(current) + Color.red(next)) / 4,
                    (Color.green(previous) + 2 * Color.green(current) + Color.green(next)) / 4,
                    (Color.blue(previous) + 2 * Color.blue(current) + Color.blue(next)) / 4);
        }

        final float[] hsv = new float[3];
        final int[] result = new int[SECTOR_COUNT];
        for (int i = 0; i < SECTOR_COUNT; i++) {
            Color.colorToHSV(smoothed[i], hsv);
            if (hsv[1] < 0.12f) {
                // У серой/чёрной аватарки нет собственного оттенка — даём прохладную
                // сине-фиолетовую ауру, плавно меняющуюся по кругу.
                hsv[0] = (200f + i * (80f / SECTOR_COUNT)) % 360f;
                hsv[1] = 0.55f;
            } else {
                hsv[1] = Math.min(1f, hsv[1] * SATURATION_BOOST + SATURATION_OFFSET);
            }
            hsv[2] = Math.min(1f, Math.max(MIN_VALUE, hsv[2] * 1.05f));
            result[i] = Color.HSVToColor(255, hsv);
        }
        return result;
    }

    /**
     * Рисует внутри скруглённой карточки многосекционный градиент от 24 цветов аватарки.
     *
     * <p>Слева, вокруг аватарки, цвета максимально насыщенные; при движении вправо
     * градиент плавно затухает до полностью прозрачного {@code 0x00000000}, поэтому
     * текст сообщений справа остаётся идеально читаемым.</p>
     *
     * @param cardRect     прямоугольник карточки
     * @param cornerRadius радиус скругления карточки
     * @param sectors24    цвета, полученные из {@link #getSectors(long, Bitmap)}
     * @param intensity    общая сила свечения, {@code 0..1}
     */
    public static void drawCardLeftToRightGlow(Canvas canvas, RectF cardRect, float cornerRadius,
                                               int[] sectors24, float intensity) {
        if (canvas == null || cardRect == null || cardRect.width() <= 0f || cardRect.height() <= 0f) {
            return;
        }
        final float strength = clamp01(intensity);
        if (strength <= 0.001f) {
            return;
        }
        final int[] colors = normalizeSectors(sectors24);
        if (!hasVisibleSectors(colors)) {
            return;
        }

        // Центр аватарки слева: она занимает почти всю высоту карточки.
        final float avatarRadius = Math.min(cardRect.height() * 0.46f, cardRect.width() * 0.28f);
        final float centerX = cardRect.left + avatarRadius + AndroidUtilities.dpf2(8f);
        final float centerY = cardRect.centerY();

        final float fadeStart = Math.min(0.75f,
                (centerX + avatarRadius * 1.15f - cardRect.left) / cardRect.width());
        final float sweepRadius = (float) Math.hypot(
                Math.max(centerX - cardRect.left, cardRect.right - centerX),
                Math.max(centerY - cardRect.top, cardRect.bottom - centerY)) + AndroidUtilities.dpf2(2f);

        final int leftAlpha = Math.min(255, Math.round(255f * 0.62f * strength));
        final int middleAlpha = Math.min(255, Math.round(255f * 0.18f * strength));
        final float sectorStep = 360f / SECTOR_COUNT;

        final int saveCount = canvas.save();
        final Path clipPath = new Path();
        clipPath.addRoundRect(cardRect, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.clipPath(clipPath);

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        final Path wedge = new Path();
        final RectF oval = new RectF(centerX - sweepRadius, centerY - sweepRadius,
                centerX + sweepRadius, centerY + sweepRadius);

        for (int i = 0; i < SECTOR_COUNT; i++) {
            final int color = colors[i];
            if (Color.alpha(color) == 0) {
                continue;
            }
            final int rgb = color & 0x00FFFFFF;
            final int startColor = (leftAlpha << 24) | rgb;
            final int middleColor = (middleAlpha << 24) | rgb;
            paint.setShader(new LinearGradient(
                    cardRect.left, 0f, cardRect.right, 0f,
                    new int[]{startColor, middleColor, 0x00000000},
                    new float[]{0f, fadeStart, 1f},
                    Shader.TileMode.CLAMP));

            wedge.rewind();
            wedge.moveTo(centerX, centerY);
            // Небольшое перекрытие секторов убирает волосяные швы между ними.
            wedge.arcTo(oval, i * sectorStep - sectorStep * 0.5f - 0.6f, sectorStep + 1.2f);
            wedge.close();
            canvas.drawPath(wedge, paint);
        }

        paint.setShader(null);
        canvas.restoreToCount(saveCount);
    }

    /**
     * Рисует мягкое 24-секторное сияние вокруг круглой аватарки в профиле.
     *
     * <p>Используется {@link SweepGradient} с 24 цветами и три концентрических
     * полупрозрачных кольца — они дают радиальное затухание без резкой границы.
     * {@code phase} (0..1) управляет «дыханием»: яркость и радиус мягко пульсируют.</p>
     *
     * @param cx           центр аватарки по X
     * @param cy           центр аватарки по Y
     * @param avatarRadius радиус самой аватарки
     * @param spread       ширина сияния наружу от аватарки
     * @param sectors24    цвета, полученные из {@link #getSectors(long, Bitmap)}
     * @param phase        фаза дыхания, {@code 0..1}
     */
    public static void drawCircularProfileAura(Canvas canvas, float cx, float cy, float avatarRadius,
                                               float spread, int[] sectors24, float phase) {
        if (canvas == null || avatarRadius <= 0f || spread <= 0f) {
            return;
        }
        final int[] colors = normalizeSectors(sectors24);
        if (!hasVisibleSectors(colors)) {
            return;
        }

        final float breath = 0.5f + 0.5f * (float) Math.sin(clamp01(phase) * Math.PI * 2.0);
        final float alphaScale = 0.55f + 0.45f * breath;
        final float auraRadius = avatarRadius + spread * (0.18f + 0.10f * breath);

        final int[] sweepColors = new int[SECTOR_COUNT + 1];
        System.arraycopy(colors, 0, sweepColors, 0, SECTOR_COUNT);
        sweepColors[SECTOR_COUNT] = colors[0];

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setShader(new SweepGradient(cx, cy, sweepColors, null));

        // Три кольца: узкое яркое у аватарки, широкое и едва заметное снаружи.
        final float[] widths = {0.16f, 0.32f, 0.52f};
        final int[] alphas = {92, 48, 22};
        for (int i = 0; i < widths.length; i++) {
            paint.setStrokeWidth(spread * widths[i]);
            paint.setAlpha(Math.min(255, Math.round(alphas[i] * alphaScale)));
            canvas.drawCircle(cx, cy, auraRadius + spread * (i * 0.16f), paint);
        }

        // Тонкий внутренний ободок, прижимающий сияние к аватарке.
        paint.setStrokeWidth(AndroidUtilities.dpf2(1.2f));
        paint.setAlpha(Math.min(255, Math.round(110 * alphaScale)));
        canvas.drawCircle(cx, cy, avatarRadius + AndroidUtilities.dpf2(0.5f), paint);

        paint.setShader(null);
    }

    /* ---------------------------------------------------------------- утилиты */

    private static int[] normalizeSectors(int[] sectors24) {
        final int[] copy = new int[SECTOR_COUNT];
        if (sectors24 != null) {
            System.arraycopy(sectors24, 0, copy, 0, Math.min(sectors24.length, SECTOR_COUNT));
        }
        // Если пришёл неполный массив, пустые сектора заполняем первым видимым цветом,
        // чтобы в градиенте не появлялись чёрные клинья.
        int fallback = 0;
        for (int color : copy) {
            if (Color.alpha(color) != 0) {
                fallback = color;
                break;
            }
        }
        if (fallback != 0) {
            for (int i = 0; i < SECTOR_COUNT; i++) {
                if (Color.alpha(copy[i]) == 0) {
                    copy[i] = fallback;
                }
            }
        }
        return copy;
    }

    private static boolean hasVisibleSectors(int[] sectors24) {
        if (sectors24 == null) {
            return false;
        }
        for (int color : sectors24) {
            if (Color.alpha(color) != 0) {
                return true;
            }
        }
        return false;
    }

    private static int[] transparentSectors() {
        return TRANSPARENT_SECTORS;
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
