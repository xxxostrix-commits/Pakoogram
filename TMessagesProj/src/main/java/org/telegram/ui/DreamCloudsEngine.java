package org.telegram.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import org.telegram.messenger.AndroidUtilities;

import java.util.Random;

/**
 * Pakoogram: детальный пиксель-арт кучевых облаков на фоне профиля.
 *
 * <p>Каждое облако собирается из 5–7 перекрывающихся круглых клубов: центральные
 * выше, боковые ниже. Внутри силуэта рисуются округлые завитки-контуры, снизу —
 * глубокая тень, а по бокам и у основания тянутся заострённые пиксельные хвостики
 * с отдельными «отстоящими» пикселями на концах. Переходы между цветовыми зонами
 * разбиваются классическим шахматным дизерингом {@code ((x + y) & 1) == 0}.</p>
 *
 * <p>Битмапы рисуются с {@code setFilterBitmap(false)} и {@code setAntiAlias(false)},
 * поэтому пиксели остаются чёткими квадратами. Облака почти статичны: они лишь
 * слегка колышутся вокруг фиксированных якорей по бесшовной синусоиде.</p>
 */
public final class DreamCloudsEngine {

    /** Количество облаков на экране профиля. */
    public static final int CLOUD_COUNT = 8;

    private static final int MIN_WIDTH = 68;
    private static final int MAX_WIDTH = 88;
    private static final int MIN_HEIGHT = 36;
    private static final int MAX_HEIGHT = 46;
    /** Размер одного пикселя облака в dp. */
    private static final float PIXEL_SIZE_DP = 2.4f;

    /** Фиксированные якоря облаков в долях ширины и высоты экрана. */
    private static final float[] ANCHOR_X = {
            0.16f, 0.84f, 0.20f, 0.82f, 0.18f, 0.80f, 0.24f, 0.78f
    };
    private static final float[] ANCHOR_Y = {
            0.12f, 0.15f, 0.28f, 0.33f, 0.50f, 0.56f, 0.72f, 0.78f
    };

    private static Bitmap[] cloudsLight;
    private static Bitmap[] cloudsDark;

    private static final Paint cloudPaint = new Paint();
    private static final RectF rectTmp = new RectF();

    static {
        // Пиксель-арт: никакого сглаживания и билинейной фильтрации.
        cloudPaint.setAntiAlias(false);
        cloudPaint.setFilterBitmap(false);
        cloudPaint.setDither(false);
    }

    private DreamCloudsEngine() {
        // утилитарный класс
    }

    /**
     * Рисует облака поверх фона профиля.
     *
     * <p>Положение каждого облака задаётся фиксированным якорем в долях экрана,
     * а анимация лишь слегка колышет его по бесшовной синусоиде. Итоговый
     * прямоугольник округляется до целых пикселей, чтобы пиксельная сетка не
     * дрожала. Параметры аватарки сохранены для совместимости с вызывающим
     * кодом и больше не влияют на позиционирование.</p>
     *
     * @param width        ширина экрана
     * @param height       высота экрана
     * @param avatarCx     центр аватарки по X (не используется)
     * @param avatarCy     центр аватарки по Y (не используется)
     * @param avatarRadius радиус аватарки (не используется)
     * @param phase        фаза анимации {@code 0..1} (полный цикл — 14 секунд)
     * @param isDark       тёмная ли тема
     */
    public static void draw(Canvas canvas, float width, float height,
                            float avatarCx, float avatarCy, float avatarRadius,
                            float phase, boolean isDark) {
        if (canvas == null || width <= 0f || height <= 0f) {
            return;
        }
        ensureBitmaps(isDark);
        final Bitmap[] clouds = isDark ? cloudsDark : cloudsLight;
        if (clouds == null) {
            return;
        }

        // Пиксель-арт масштабируется целыми пикселями экрана и только
        // nearest-neighbour: облака остаются острыми квадратиками.
        final float basePixel = AndroidUtilities.dpf2(PIXEL_SIZE_DP);
        for (int i = 0; i < CLOUD_COUNT; i++) {
            final Bitmap bitmap = clouds[i];
            if (bitmap == null) {
                continue;
            }
            final float cloudWidth = bitmap.getWidth() * basePixel;
            final float cloudHeight = bitmap.getHeight() * basePixel;
            final float anchorX = ANCHOR_X[i] * width;
            final float anchorY = ANCHOR_Y[i] * height;

            // Бесшовное колыхание на месте: только целые множители phase,
            // поэтому на стыке цикла нет рывка.
            final float dx = (float) Math.sin((phase + i * 0.17f) * Math.PI * 2.0) * AndroidUtilities.dpf2(3.5f);
            final float dy = (float) Math.cos((phase + i * 0.23f) * Math.PI * 2.0) * AndroidUtilities.dpf2(2.2f);

            final float left = Math.round(anchorX - cloudWidth * 0.5f + dx);
            final float top = Math.round(anchorY - cloudHeight * 0.5f + dy);
            rectTmp.set(left, top,
                    left + Math.round(cloudWidth),
                    top + Math.round(cloudHeight));
            canvas.drawBitmap(bitmap, null, rectTmp, cloudPaint);
        }
    }

    /* ------------------------------------------------------------- генерация */

    private static void ensureBitmaps(boolean isDark) {
        if (isDark) {
            if (cloudsDark == null) {
                cloudsDark = buildClouds(true);
            }
        } else if (cloudsLight == null) {
            cloudsLight = buildClouds(false);
        }
    }

    private static Bitmap[] buildClouds(boolean isDark) {
        final Bitmap[] result = new Bitmap[CLOUD_COUNT];
        for (int i = 0; i < CLOUD_COUNT; i++) {
            result[i] = createCloudBitmap(i, isDark);
        }
        return result;
    }

    /**
     * Собирает одно облако: 5–7 круглых клубов, пиксельные хвостики по бокам и
     * снизу, внутренние завитки и ручная раскраска в четыре оттенка с дизерингом.
     */
    private static Bitmap createCloudBitmap(int index, boolean isDark) {
        final Random random = new Random(0x9E3779B97F4A7C15L * (index + 1) ^ 0xC0FFEE11L);
        final int width = MIN_WIDTH + random.nextInt(MAX_WIDTH - MIN_WIDTH + 1);
        final int height = MIN_HEIGHT + random.nextInt(MAX_HEIGHT - MIN_HEIGHT + 1);
        final boolean[] solid = new boolean[width * height];
        final boolean[] tail = new boolean[width * height];
        final boolean[] detached = new boolean[width * height];
        final byte[] owner = new byte[width * height];

        // 5–7 перекрывающихся круглых клубов: центральные выше, боковые ниже.
        final int puffs = 5 + random.nextInt(3);
        final float[] puffCx = new float[puffs];
        final float[] puffCy = new float[puffs];
        final float[] puffR = new float[puffs];
        for (int p = 0; p < puffs; p++) {
            final float t = puffs > 1 ? p / (float) (puffs - 1) : 0.5f;
            final float dome = (float) Math.sin(t * Math.PI);
            final float cx = width * (0.30f + 0.40f * t)
                    + (random.nextFloat() - 0.5f) * width * 0.04f;
            final float cy = height * (0.56f - 0.17f * dome)
                    + (random.nextFloat() - 0.5f) * height * 0.06f;
            final float radius = Math.min(height * 0.26f, width * 0.12f)
                    * (0.85f + 0.20f * random.nextFloat());
            puffCx[p] = cx;
            puffCy[p] = cy;
            puffR[p] = radius;
            final int minX = Math.max(0, (int) Math.floor(cx - radius));
            final int maxX = Math.min(width - 1, (int) Math.ceil(cx + radius));
            final int minY = Math.max(0, (int) Math.floor(cy - radius));
            final int maxY = Math.min(height - 1, (int) Math.ceil(cy + radius));
            for (int y = minY; y <= maxY; y++) {
                final float ny = y + 0.5f - cy;
                for (int x = minX; x <= maxX; x++) {
                    final float nx = x + 0.5f - cx;
                    if (nx * nx + ny * ny <= radius * radius) {
                        solid[y * width + x] = true;
                        owner[y * width + x] = (byte) (p + 1);
                    }
                }
            }
        }

        // Заострённые пиксельные хвостики по бокам и у основания.
        addPixelTails(solid, tail, detached, width, height, random);

        // Палитра: облака светлые бело-голубые в обеих темах.
        final int highlightColor = isDark ? 0xFFF0F6FF : 0xFFFFFFFF;
        final int bodyColor = isDark ? 0xFFD4E9FC : 0xFFE6F4FF;
        final int midShadowColor = isDark ? 0xFFA2C9F0 : 0xFFB8E0FC;
        final int deepShadowColor = isDark ? 0xFF78A5D8 : 0xFF8CC2F0;

        // Округлые дуги-завитки внутри тела и на стыках клубов.
        final boolean[] swirlCore = new boolean[width * height];
        final boolean[] swirlEdge = new boolean[width * height];
        buildSwirlMasks(solid, puffCx, puffCy, puffR, puffs, width, height, swirlCore, swirlEdge);

        final int[] pixels = new int[width * height];
        paintCloudPixels(pixels, solid, tail, detached, owner, puffCx, puffCy, puffR,
                swirlCore, swirlEdge, width, height, index,
                highlightColor, bodyColor, midShadowColor, deepShadowColor);
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    /**
     * Раскрашивает пиксели силуэта: блик на верхушках клубов, светлое тело,
     * средняя тень в завитках и на нижней части, глубокая тень в основании.
     */
    private static void paintCloudPixels(int[] pixels, boolean[] solid, boolean[] tail,
                                         boolean[] detached, byte[] owner,
                                         float[] puffCx, float[] puffCy, float[] puffR,
                                         boolean[] swirlCore, boolean[] swirlEdge,
                                         int width, int height, int index,
                                         int highlightColor, int bodyColor,
                                         int midShadowColor, int deepShadowColor) {
        for (int x = 0; x < width; x++) {
            int top = -1;
            int bottom = -1;
            for (int y = 0; y < height; y++) {
                if (solid[y * width + x]) {
                    if (top < 0) {
                        top = y;
                    }
                    bottom = y;
                }
            }
            if (top < 0) {
                continue;
            }
            final int columnHeight = bottom - top + 1;
            final int deepRows = 2 + ((x + index) & 1);
            final int deepStart = bottom - deepRows + 1;
            final int midStart = bottom - Math.max(3, Math.round(columnHeight * 0.34f)) + 1;
            for (int y = top; y <= bottom; y++) {
                final int idx = y * width + x;
                if (!solid[idx]) {
                    continue;
                }
                if (detached[idx]) {
                    pixels[idx] = ((x + y) & 1) == 0 ? midShadowColor : bodyColor;
                    continue;
                }
                if (tail[idx]) {
                    pixels[idx] = y <= top + 1 || y < bottom
                            ? bodyColor : midShadowColor;
                    continue;
                }
                int capBottomRow = -1;
                boolean highlight = false;
                if (owner[idx] > 0) {
                    final int p = owner[idx] - 1;
                    final int depth = 2 + ((index + p) % 3);
                    final float capTop = puffCy[p] - puffR[p];
                    capBottomRow = (int) Math.floor(capTop + depth);
                    final float dx = x + 0.5f - puffCx[p];
                    // Верхние 2–4 пикселя клуба, особенно сверху-слева, — блик.
                    highlight = (y <= capBottomRow && dx <= puffR[p] * 0.55f)
                            || (y <= capBottomRow && dx <= puffR[p] * 0.85f
                            && ((x + y) & 1) == 0);
                }
                int color;
                if (y >= deepStart) {
                    color = y == deepStart
                            ? (((x + y) & 1) == 0 ? deepShadowColor : midShadowColor)
                            : deepShadowColor;
                } else if (y >= midStart) {
                    color = y == midStart
                            ? (((x + y) & 1) == 0 ? midShadowColor : bodyColor)
                            : midShadowColor;
                } else if (highlight) {
                    color = y == capBottomRow
                            ? (((x + y) & 1) == 0 ? highlightColor : bodyColor)
                            : highlightColor;
                } else {
                    color = bodyColor;
                }
                if (y < deepStart) {
                    if (swirlCore[idx]) {
                        color = midShadowColor;
                    } else if (swirlEdge[idx]) {
                        color = ((x + y) & 1) == 0 ? midShadowColor : bodyColor;
                    }
                }
                pixels[idx] = color;
            }
        }
    }

    /** Строит маски округлых завитков: дуги клубов и стыки-швы между ними. */
    private static void buildSwirlMasks(boolean[] solid, float[] puffCx, float[] puffCy,
                                        float[] puffR, int puffs, int width, int height,
                                        boolean[] swirlCore, boolean[] swirlEdge) {
        for (int p = 0; p < puffs; p++) {
            final float inner = puffR[p] * 0.58f;
            final int minX = Math.max(0, (int) Math.floor(puffCx[p] - inner - 2f));
            final int maxX = Math.min(width - 1, (int) Math.ceil(puffCx[p] + inner + 2f));
            final int minY = Math.max(0, (int) Math.floor(puffCy[p] - 1f));
            final int maxY = Math.min(height - 1, (int) Math.ceil(puffCy[p] + inner + 2f));
            for (int y = minY; y <= maxY; y++) {
                final float dy = y + 0.5f - puffCy[p];
                if (dy <= 0f) {
                    continue;
                }
                for (int x = minX; x <= maxX; x++) {
                    final int idx = y * width + x;
                    if (!solid[idx]) {
                        continue;
                    }
                    final float dx = x + 0.5f - puffCx[p];
                    final float distance = (float) Math.sqrt(dx * dx + dy * dy);
                    final float band = Math.abs(distance - inner);
                    if (band <= 0.8f) {
                        swirlCore[idx] = true;
                    } else if (band <= 1.8f) {
                        swirlEdge[idx] = true;
                    }
                }
            }
        }
        // Стыки клубов: короткие вертикальные дуги-швы между соседними центрами.
        for (int p = 0; p + 1 < puffs; p++) {
            final float seamX = (puffCx[p] + puffCx[p + 1]) * 0.5f;
            final float seamTop = Math.min(puffCy[p], puffCy[p + 1]);
            final float seamBottom = Math.max(puffCy[p], puffCy[p + 1])
                    + Math.min(puffR[p], puffR[p + 1]) * 0.55f;
            final int minX = Math.max(0, (int) Math.floor(seamX - 1.5f));
            final int maxX = Math.min(width - 1, (int) Math.ceil(seamX + 1.5f));
            final int minY = Math.max(0, (int) Math.floor(seamTop));
            final int maxY = Math.min(height - 1, (int) Math.ceil(seamBottom));
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    final int idx = y * width + x;
                    if (!solid[idx] || Math.abs(x + 0.5f - seamX) > 1.1f) {
                        continue;
                    }
                    if (((x + y) & 1) == 0) {
                        swirlCore[idx] = true;
                    } else {
                        swirlEdge[idx] = true;
                    }
                }
            }
        }
    }

    /**
     * Добавляет заострённые пиксельные хвостики: сужающиеся горизонтальные
     * полоски по бокам и у основания плюс отдельные отстоящие пиксели на концах.
     */
    private static void addPixelTails(boolean[] solid, boolean[] tail, boolean[] detached,
                                      int width, int height, Random random) {
        int minX = width;
        int maxX = -1;
        int minY = height;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (solid[y * width + x]) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        if (maxX < 0) {
            return;
        }
        final int centerY = (minY + maxY) / 2;

        // Два уровня боковых хвостиков: середина силуэта и нижняя треть.
        addHorizontalTail(solid, tail, detached, width, height, -1, centerY - 1, random);
        addHorizontalTail(solid, tail, detached, width, height, 1, centerY + 1, random);
        addHorizontalTail(solid, tail, detached, width, height, -1,
                Math.max(minY + 4, maxY - 4), random);
        addHorizontalTail(solid, tail, detached, width, height, 1,
                Math.max(minY + 5, maxY - 3), random);

        // Пара отдельных пикселей под основанием — классические «брызги».
        final int spray = 2 + random.nextInt(3);
        for (int i = 0; i < spray; i++) {
            final int x = minX + 3 + random.nextInt(Math.max(1, maxX - minX - 5));
            final int y = Math.min(height - 1, maxY + 1 + random.nextInt(2));
            if (!solid[y * width + x]) {
                solid[y * width + x] = true;
                detached[y * width + x] = true;
            }
        }
    }

    /** Один сужающийся хвостик с разрывом и 2–4 отдельными пикселями на конце. */
    private static void addHorizontalTail(boolean[] solid, boolean[] tail, boolean[] detached,
                                          int width, int height, int dir, int row, Random random) {
        int y = row;
        int edge = -1;
        for (int attempt = 0; attempt < 7 && edge < 0; attempt++) {
            final int candidate = row + ((attempt & 1) == 0 ? (attempt + 1) / 2 : -(attempt + 1) / 2);
            if (candidate < 0 || candidate >= height) {
                continue;
            }
            final int found = findEdge(solid, width, candidate, dir);
            if (found >= 0) {
                edge = found;
                y = candidate;
            }
        }
        if (edge < 0) {
            return;
        }
        final int length = 3 + random.nextInt(3);
        final int thickness = 1 + random.nextInt(3);
        for (int k = 0; k < length; k++) {
            final int rows = Math.max(1, thickness - (k * thickness) / length);
            final int xx = edge + dir * k;
            for (int t = 0; t < rows; t++) {
                final int yy = y + t;
                if (xx < 0 || xx >= width || yy < 0 || yy >= height) {
                    continue;
                }
                final int idx = yy * width + xx;
                if (!solid[idx]) {
                    solid[idx] = true;
                    tail[idx] = true;
                }
            }
        }
        // Разрыв в один пиксель, затем отдельные отстоящие пиксели на конце.
        final int startX = edge + dir * (length + 1);
        final int count = 2 + random.nextInt(3);
        for (int d = 0; d < count; d++) {
            final int xx = startX + dir * d * 2;
            final int yy = y + (d & 1);
            if (xx < 0 || xx >= width || yy < 0 || yy >= height) {
                continue;
            }
            final int idx = yy * width + xx;
            solid[idx] = true;
            detached[idx] = true;
        }
    }

    /** Возвращает координату пикселя сразу за силуэтом в строке {@code y}. */
    private static int findEdge(boolean[] solid, int width, int y, int dir) {
        final int row = y * width;
        if (dir < 0) {
            for (int x = 0; x < width; x++) {
                if (solid[row + x]) {
                    return x - 1;
                }
            }
        } else {
            for (int x = width - 1; x >= 0; x--) {
                if (solid[row + x]) {
                    return x + 1;
                }
            }
        }
        return -1;
    }

}
