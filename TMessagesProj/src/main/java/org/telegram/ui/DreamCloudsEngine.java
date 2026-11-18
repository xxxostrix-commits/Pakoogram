package org.telegram.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import org.telegram.messenger.AndroidUtilities;

import java.util.Random;

/**
 * Pakoogram: пиксель-арт облака на фоне профиля.
 *
 * <p>Десять облаков генерируются процедурно: несколько перекрывающихся «клубов»
 * с плоским основанием. Каждый пиксель закрашивается вручную — верхний контур
 * светлее тела, нижние ряды темнее, поэтому облако читается объёмным даже без
 * сглаживания. Битмапы рисуются с {@code setFilterBitmap(false)}, поэтому при
 * увеличении остаются чёткими квадратными пикселями.</p>
 *
 * <p>Позиции распределены по всей высоте экрана, а вокруг аватарки остаётся
 * свободная круглая зона радиусом {@code avatarRadius * 1.65f}: облака
 * выталкиваются за её границу и не заплывают на аватарку даже во время дрейфа.</p>
 */
public final class DreamCloudsEngine {

    /** Количество облаков на экране профиля. */
    public static final int CLOUD_COUNT = 10;
    /** Множитель свободной зоны вокруг аватарки. */
    public static final float AVATAR_CLEAR_FACTOR = 1.65f;

    private static final int MIN_WIDTH = 24;
    private static final int MAX_WIDTH = 36;
    private static final int MIN_HEIGHT = 14;
    private static final int MAX_HEIGHT = 20;
    /** Размер одного пикселя облака в dp. */
    private static final float PIXEL_SIZE_DP = 2.1f;
    /** Отступ облаков от краёв экрана в dp. */
    private static final float EDGE_INSET_DP = 6f;

    private static Bitmap[] cloudsLight;
    private static Bitmap[] cloudsDark;
    private static Bitmap shadowBitmap;

    private static final Paint cloudPaint = new Paint();
    private static final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF rectTmp = new RectF();

    static {
        // Пиксель-арт: никакого сглаживания и билинейной фильтрации.
        cloudPaint.setAntiAlias(false);
        cloudPaint.setFilterBitmap(false);
        cloudPaint.setDither(false);
        shadowPaint.setFilterBitmap(true);
    }

    private DreamCloudsEngine() {
        // утилитарный класс
    }

    /**
     * Рисует облака поверх фона профиля.
     *
     * @param width        ширина экрана
     * @param height       высота экрана
     * @param avatarCx     центр аватарки по X
     * @param avatarCy     центр аватарки по Y
     * @param avatarRadius радиус аватарки; {@code 0} отключает свободную зону
     * @param phase        фаза анимации {@code 0..1} (полный цикл — 24 секунды)
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

        final float clearRadius = avatarRadius > 0f ? avatarRadius * AVATAR_CLEAR_FACTOR : 0f;
        final float basePixel = AndroidUtilities.dpf2(PIXEL_SIZE_DP);
        final float inset = AndroidUtilities.dpf2(EDGE_INSET_DP);
        final float verticalInset = AndroidUtilities.dpf2(18f);
        final float verticalSpan = Math.max(1f, height - verticalInset * 2f);
        final float horizontalSpan = Math.max(0f, width * 0.22f - inset);

        for (int i = 0; i < CLOUD_COUNT; i++) {
            final Bitmap bitmap = clouds[i];
            if (bitmap == null) {
                continue;
            }
            final float scale = basePixel * (0.84f + 0.07f * ((i * 7) % 5));
            final float cloudWidth = bitmap.getWidth() * scale;
            final float cloudHeight = bitmap.getHeight() * scale;

            // Каждое облако живёт в своей горизонтальной полосе — они покрывают
            // весь экран: от шапки до нижней карточки информации.
            final float band = (i + 0.5f) / CLOUD_COUNT;
            final float baseY = verticalInset + band * verticalSpan;
            final boolean left = (i % 2 == 0);
            final float sideJitter = ((i * 37) % 11) / 10f;
            float cx = left
                    ? inset + cloudWidth * 0.5f + sideJitter * horizontalSpan
                    : width - inset - cloudWidth * 0.5f - sideJitter * horizontalSpan;

            // Медленный дрейф по горизонтали и покачивание по вертикали.
            cx += (float) Math.sin((phase + i * 0.113f) * Math.PI * 2.0) * AndroidUtilities.dpf2(9f);
            final float cy = baseY + (float) Math.sin((phase * 0.7f + i * 0.19f) * Math.PI * 2.0)
                    * AndroidUtilities.dpf2(5f);

            final float halfDiagonal = (float) Math.hypot(cloudWidth, cloudHeight) * 0.5f;
            final float safeRadius = clearRadius > 0f ? clearRadius + halfDiagonal : 0f;
            final float drawCx = keepOutsideAvatar(cx, cy, cloudWidth, avatarCx, avatarCy,
                    safeRadius, width, inset);
            // Если облако не помещается сбоку (очень узкий экран), оно уводится
            // ещё и по вертикали: центр удаляется на safeRadius, поэтому весь
            // прямоугольник остаётся вне круга radius * 1.65f.
            float drawCy = cy;
            if (safeRadius > 0f) {
                final float dx = drawCx - avatarCx;
                if (Math.abs(dx) < safeRadius) {
                    final float neededDy = (float) Math.sqrt(safeRadius * safeRadius - dx * dx);
                    if (Math.abs(drawCy - avatarCy) < neededDy) {
                        drawCy = avatarCy + (drawCy >= avatarCy ? neededDy : -neededDy);
                        final float minY = cloudHeight * 0.5f + AndroidUtilities.dpf2(4f);
                        final float maxY = height - cloudHeight * 0.5f - AndroidUtilities.dpf2(4f);
                        if (minY <= maxY) {
                            drawCy = Math.max(minY, Math.min(maxY, drawCy));
                        }
                    }
                }
            }

            // Мягкая тень снизу: отдельный маленький битмап растягивается под облаком.
            if (shadowBitmap != null) {
                rectTmp.set(
                        drawCx - cloudWidth * 0.55f,
                        drawCy + cloudHeight * 0.18f,
                        drawCx + cloudWidth * 0.55f,
                        drawCy + cloudHeight * 0.52f
                );
                canvas.drawBitmap(shadowBitmap, null, rectTmp, shadowPaint);
            }

            final int saveCount = canvas.save();
            // Лёгкое покачивание: не больше пары градусов, чтобы пиксели не «плыли».
            canvas.rotate((float) Math.sin((phase * 1.3f + i * 0.23f) * Math.PI * 2.0) * 2.2f, drawCx, drawCy);
            rectTmp.set(
                    drawCx - cloudWidth * 0.5f,
                    drawCy - cloudHeight * 0.5f,
                    drawCx + cloudWidth * 0.5f,
                    drawCy + cloudHeight * 0.5f
            );
            canvas.drawBitmap(bitmap, null, rectTmp, cloudPaint);
            canvas.restoreToCount(saveCount);
        }
    }

    /**
     * Выталкивает облако из свободной круглой зоны вокруг аватарки.
     * Облако сдвигается в ту сторону, где оно уже находится, и при этом
     * остаётся полностью в пределах экрана.
     */
    private static float keepOutsideAvatar(float cx, float cy, float cloudWidth,
                                           float avatarCx, float avatarCy, float safeRadius,
                                           float width, float inset) {
        if (safeRadius <= 0f) {
            return cx;
        }
        final float dy = cy - avatarCy;
        if (Math.abs(dy) >= safeRadius) {
            return cx;
        }
        final float dx = cx - avatarCx;
        final float needDx = (float) Math.sqrt(safeRadius * safeRadius - dy * dy);
        if (dx >= needDx || dx <= -needDx) {
            return cx;
        }
        float pushed = avatarCx + (dx >= 0f ? needDx : -needDx);
        final float minCx = inset + cloudWidth * 0.5f;
        final float maxCx = width - inset - cloudWidth * 0.5f;
        if (minCx <= maxCx) {
            pushed = Math.max(minCx, Math.min(maxCx, pushed));
        }
        return pushed;
    }

    /* ------------------------------------------------------------- генерация */

    private static void ensureBitmaps(boolean isDark) {
        if (shadowBitmap == null) {
            shadowBitmap = createShadowBitmap();
        }
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
     * Собирает одно облако: несколько эллиптических «клубов», плоское основание
     * и ручная раскраска пикселей (светлый верх, тёмный низ).
     */
    private static Bitmap createCloudBitmap(int index, boolean isDark) {
        final Random random = new Random(0x9E3779B97F4A7C15L * (index + 1) ^ 0xC0FFEE11L);
        final int width = MIN_WIDTH + random.nextInt(MAX_WIDTH - MIN_WIDTH + 1);
        final int height = MIN_HEIGHT + random.nextInt(MAX_HEIGHT - MIN_HEIGHT + 1);
        final boolean[] solid = new boolean[width * height];

        final int puffs = 3 + random.nextInt(3);
        for (int p = 0; p < puffs; p++) {
            final float puffX = width * (0.18f + 0.64f * random.nextFloat());
            final float puffY = height * (0.34f + 0.30f * random.nextFloat());
            final float puffRx = width * (0.16f + 0.14f * random.nextFloat());
            final float puffRy = height * (0.22f + 0.16f * random.nextFloat());
            for (int y = 0; y < height; y++) {
                final float ny = (y + 0.5f - puffY) / puffRy;
                for (int x = 0; x < width; x++) {
                    final float nx = (x + 0.5f - puffX) / puffRx;
                    if (nx * nx + ny * ny <= 1f) {
                        solid[y * width + x] = true;
                    }
                }
            }
        }

        // Плоское основание: облако не должно свисать отдельными пикселями.
        final int baseRow = Math.round(height * (0.70f + 0.14f * random.nextFloat()));
        for (int y = baseRow; y < height; y++) {
            for (int x = 0; x < width; x++) {
                solid[y * width + x] = false;
            }
        }

        final int highlightColor = isDark ? 0xC08A96B8 : 0xF4FFFFFF;
        final int bodyColor = isDark ? 0xB0465168 : 0xE8F4F8FF;
        final int bottomColor = isDark ? 0x9E2B3344 : 0xD8DCE8F6;

        final int[] pixels = new int[width * height];
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
            for (int y = top; y <= bottom; y++) {
                if (!solid[y * width + x]) {
                    continue;
                }
                final int color;
                if (y <= top + 1) {
                    // Светлый верхний контур: две верхние строки каждого столбца.
                    color = highlightColor;
                } else if (y >= bottom) {
                    // Мягкая тень снизу: нижняя строка столбца темнее тела.
                    color = bottomColor;
                } else {
                    color = bodyColor;
                }
                pixels[y * width + x] = color;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    /** Мягкая эллиптическая тень: радиальное затухание альфы без резких краёв. */
    private static Bitmap createShadowBitmap() {
        final int width = 64;
        final int height = 32;
        final int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            final float ny = (y + 0.5f) / (height * 0.5f) - 1f;
            for (int x = 0; x < width; x++) {
                final float nx = (x + 0.5f) / (width * 0.5f) - 1f;
                final float distance = nx * nx + ny * ny;
                if (distance >= 1f) {
                    continue;
                }
                final float falloff = 1f - distance;
                final int alpha = Math.round(76f * falloff * falloff);
                pixels[y * width + x] = alpha << 24;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
}
