package org.telegram.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.util.LruCache;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.VectorAvatarThumbDrawable;

/**
 * Pakoogram: единый кэш адаптивной палитры аватарок.
 *
 * <p>Аватарка анализируется в пространстве HSV. Каждый достаточно непрозрачный
 * пиксель попадает ровно в одну из трёх групп:</p>
 * <ul>
 *     <li><b>цветные</b> — {@code S >= 0.22} и {@code V >= 0.18}; они раскладываются
 *     по гистограмме из 24 корзин оттенка (шаг 15°);</li>
 *     <li><b>глубоко-чёрные</b> — {@code V < 0.15} и {@code S < 0.25};</li>
 *     <li><b>серые и белые</b> — {@code V >= 0.15} и {@code S < 0.22}.</li>
 * </ul>
 *
 * <p>По долям пикселей выбирается режим, и он уже никогда не подменяется
 * фиолетовым «заглушечным» цветом:</p>
 * <ul>
 *     <li>цветных {@code >= 10%} — {@link #MODE_COLORFUL}: чистая насыщенная
 *     палитра по самому заполненному бину гистограммы;</li>
 *     <li>цветных {@code < 10%}, чёрных {@code >= 65%} — {@link #MODE_AMOLED_BLACK}:
 *     чёрный фон переписки, графитовые пузыри и стеклянные панели;</li>
 *     <li>иначе — {@link #MODE_MONOCHROME_GRAY}: дымчато-серый монохром.</li>
 * </ul>
 *
 * <p>Светлая тема не является «вывернутой» тёмной: для каждого режима строятся
 * отдельные чистые светлые оттенки (жемчужно-серый и графит для чёрно-серых
 * аватарок, лёгкая пастель для цветных). Палитры кешируются по {@code dialogId};
 * при переключении светлой/тёмной темы кэш полностью очищается.</p>
 */
public final class PakoogramAvatarPalette {

    /** Размер общего LRU-кэша палитр. */
    public static final int CACHE_SIZE = 512;

    /** Режим: в аватарке достаточно цветных пикселей. */
    public static final int MODE_COLORFUL = 0;
    /** Режим: глубоко-чёрная аватарка (AMOLED Black). */
    public static final int MODE_AMOLED_BLACK = 1;
    /** Режим: чёрно-белая, серая или белая аватарка (Серый монохром). */
    public static final int MODE_MONOCHROME_GRAY = 2;

    /** Порог доли цветных пикселей для цветной палитры. */
    public static final float COLORFUL_PIXEL_THRESHOLD = 0.10f;
    /** Порог доли глубоко-чёрных пикселей для режима AMOLED Black. */
    public static final float AMOLED_BLACK_PIXEL_THRESHOLD = 0.65f;

    /** Цветные пиксели: минимальная насыщенность. */
    private static final float COLOR_MIN_SATURATION = 0.22f;
    /** Цветные пиксели: минимальная яркость. */
    private static final float COLOR_MIN_VALUE = 0.18f;
    /** Глубоко-чёрные пиксели: максимальная яркость. */
    private static final float BLACK_MAX_VALUE = 0.15f;
    /** Глубоко-чёрные пиксели: максимальная насыщенность. */
    private static final float BLACK_MAX_SATURATION = 0.25f;
    /** Серые/белые пиксели: максимальная насыщенность. */
    private static final float GRAY_MAX_SATURATION = 0.22f;
    /** Количество корзин гистограммы оттенка (по 15°). */
    private static final int HUE_BUCKETS = 24;

    /** Нейтральный графитовый акцент — используется только как крайний резерв. */
    public static final int NEUTRAL_ACCENT_COLOR = 0xFF26292E;

    private static final LruCache<Long, PaletteData> CACHE = new LruCache<>(CACHE_SIZE);

    /** Тёмная ли тема была активна при построении закешированных палитр. */
    private static boolean cacheDarkTheme;
    private static boolean cacheThemeModeResolved;

    private PakoogramAvatarPalette() {
        // утилитарный класс
    }

    /**
     * Палитры строятся по-разному для светлой и тёмной темы, поэтому при смене
     * темы кэш полностью сбрасывается: иначе светлая палитра осталась бы на экране
     * после переключения на тёмную (и наоборот).
     */
    private static void ensureThemeMode() {
        final boolean dark = Theme.isCurrentThemeDark();
        if (!cacheThemeModeResolved) {
            cacheThemeModeResolved = true;
            cacheDarkTheme = dark;
            return;
        }
        if (dark != cacheDarkTheme) {
            cacheDarkTheme = dark;
            CACHE.evictAll();
        }
    }

    /* ------------------------------------------------------------------ API */

    /** @return закешированная палитра для диалога или {@code null}, если её ещё нет. */
    public static PaletteData get(long dialogId) {
        if (dialogId == 0) {
            return null;
        }
        ensureThemeMode();
        return CACHE.get(dialogId);
    }

    /**
     * Возвращает палитру диалога. Если аватарка уже загружена битмапом, цвет берётся из
     * битмапа (и это результат запоминается). Иначе используются векторная аватарка или
     * {@link AvatarDrawable} — то есть цвета, которые видны пользователю прямо сейчас.
     *
     * <p>Если закешированная палитра была построена без битмапа, а битмап уже появился,
     * палитра пересчитывается и заменяется в кэше.</p>
     */
    public static PaletteData getOrCreate(long dialogId, ImageReceiver imageReceiver, AvatarDrawable avatarDrawable) {
        ensureThemeMode();
        final PaletteData cached = get(dialogId);
        final Bitmap bitmap = imageReceiver != null ? imageReceiver.getBitmap() : null;
        final boolean hasBitmap = bitmap != null && !bitmap.isRecycled();
        if (cached != null && (cached.fromBitmap || !hasBitmap)) {
            return cached;
        }

        Analysis analysis = null;
        if (hasBitmap) {
            analysis = analyze(bitmap);
        }
        if (analysis == null) {
            int color = 0;
            if (imageReceiver != null) {
                final android.graphics.drawable.Drawable drawable = imageReceiver.getDrawable();
                if (drawable instanceof VectorAvatarThumbDrawable) {
                    color = ((VectorAvatarThumbDrawable) drawable).gradientTools.getAverageColor();
                } else if (drawable instanceof AvatarDrawable) {
                    color = ((AvatarDrawable) drawable).getColor2();
                }
            }
            if (color == 0 && avatarDrawable != null) {
                color = avatarDrawable.getColor2();
            }
            if (color == 0) {
                return cached;
            }
            analysis = Analysis.fromColor(color);
        }
        final PaletteData data = build(dialogId, analysis, analysis.dominantColor,
                hasBitmap && analysis.fromBitmap);
        if (dialogId != 0) {
            CACHE.put(dialogId, data);
        }
        return data;
    }

    /**
     * Палитра для диалога без готового {@link ImageReceiver}: используются данные
     * пользователя/чата и сгенерированная {@link AvatarDrawable}. Нужна экранам,
     * которые открываются отдельно от профиля (например, медиа).
     */
    public static PaletteData getOrCreateForDialog(int currentAccount, long dialogId) {
        if (dialogId == 0) {
            return null;
        }
        final PaletteData cached = get(dialogId);
        if (cached != null) {
            return cached;
        }
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        try {
            if (dialogId > 0) {
                final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
                if (user != null) {
                    avatarDrawable.setInfo(currentAccount, user);
                }
            } else {
                final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
                if (chat != null) {
                    avatarDrawable.setInfo(currentAccount, chat);
                }
            }
        } catch (Exception ignore) {
            // данные ещё не загружены — ниже сработает нейтральный резерв
        }
        return getOrCreate(dialogId, null, avatarDrawable);
    }

    /** Строит палитру по известному цвету (например, по автоматической аватарке). */
    public static PaletteData fromColor(long dialogId, int color) {
        ensureThemeMode();
        if (color == 0) {
            return get(dialogId);
        }
        final PaletteData cached = get(dialogId);
        if (cached != null) {
            return cached;
        }
        final Analysis analysis = Analysis.fromColor(color);
        final PaletteData data = build(dialogId, analysis, analysis.dominantColor, false);
        if (dialogId != 0) {
            CACHE.put(dialogId, data);
        }
        return data;
    }

    /** Строит палитру по {@link AvatarDrawable} (цвета сгенерированной аватарки). */
    public static PaletteData fromAvatarDrawable(long dialogId, AvatarDrawable avatarDrawable) {
        if (avatarDrawable == null) {
            return get(dialogId);
        }
        return fromColor(dialogId, avatarDrawable.getColor2());
    }

    /** Полностью очищает общий кэш палитр. */
    public static void clear() {
        CACHE.evictAll();
        cacheThemeModeResolved = false;
    }

    /* ------------------------------------------------------------- extraction */

    /**
     * Сканирует битмап в HSV и возвращает доли цветных, глубоко-чёрных и серых
     * пикселей вместе с доминирующим оттенком. Выборка равномерная (48×48), чтобы
     * анализ не зависел от размера аватарки и не тормозил список чатов.
     */
    static Analysis analyze(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            return null;
        }
        final int samples = 48;
        final float[] hsv = new float[3];
        final int[] bucketCount = new int[HUE_BUCKETS];
        final float[] bucketHueSin = new float[HUE_BUCKETS];
        final float[] bucketHueCos = new float[HUE_BUCKETS];
        final float[] bucketSaturation = new float[HUE_BUCKETS];
        final float[] bucketValue = new float[HUE_BUCKETS];
        int total = 0;
        int colorful = 0;
        int deepBlack = 0;
        int grayWhite = 0;
        try {
            for (int y = 0; y < samples; y++) {
                final int pixelY = Math.min(bitmap.getHeight() - 1, y * bitmap.getHeight() / samples);
                for (int x = 0; x < samples; x++) {
                    final int pixelX = Math.min(bitmap.getWidth() - 1, x * bitmap.getWidth() / samples);
                    final int pixel = bitmap.getPixel(pixelX, pixelY);
                    if (Color.alpha(pixel) < 160) {
                        continue;
                    }
                    total++;
                    Color.colorToHSV(pixel, hsv);
                    final float s = hsv[1];
                    final float v = hsv[2];
                    if (s >= COLOR_MIN_SATURATION && v >= COLOR_MIN_VALUE) {
                        colorful++;
                        final int bucket = Math.min(HUE_BUCKETS - 1,
                                (int) (hsv[0] / (360f / HUE_BUCKETS)));
                        bucketCount[bucket]++;
                        final double hueRadians = Math.toRadians(hsv[0]);
                        bucketHueSin[bucket] += (float) Math.sin(hueRadians);
                        bucketHueCos[bucket] += (float) Math.cos(hueRadians);
                        bucketSaturation[bucket] += s;
                        bucketValue[bucket] += v;
                    } else if (v < BLACK_MAX_VALUE && s < BLACK_MAX_SATURATION) {
                        deepBlack++;
                    } else if (v >= BLACK_MAX_VALUE && s < GRAY_MAX_SATURATION) {
                        grayWhite++;
                    }
                }
            }
        } catch (Exception ignore) {
            return null;
        }
        if (total == 0) {
            return null;
        }
        final float colorfulRatio = colorful / (float) total;
        final float blackRatio = deepBlack / (float) total;
        final float grayRatio = grayWhite / (float) total;

        int bestBucket = -1;
        int bestCount = 0;
        for (int b = 0; b < HUE_BUCKETS; b++) {
            if (bucketCount[b] > bestCount) {
                bestCount = bucketCount[b];
                bestBucket = b;
            }
        }

        final int mode;
        float hue = 0f;
        float saturation = 0f;
        float value = 0f;
        boolean hasHue = false;
        int dominantColor;
        if (colorfulRatio >= COLORFUL_PIXEL_THRESHOLD && bestBucket >= 0 && bestCount > 0) {
            mode = MODE_COLORFUL;
            hue = (float) Math.toDegrees(Math.atan2(
                    bucketHueSin[bestBucket], bucketHueCos[bestBucket]));
            if (hue < 0f) {
                hue += 360f;
            }
            saturation = bucketSaturation[bestBucket] / bestCount;
            value = bucketValue[bestBucket] / bestCount;
            hasHue = true;
            dominantColor = hsvColor(hue,
                    clamp(saturation * 1.15f, 0.62f, 1f),
                    clamp(value * 1.05f, 0.58f, 1f));
        } else if (blackRatio >= AMOLED_BLACK_PIXEL_THRESHOLD) {
            mode = MODE_AMOLED_BLACK;
            dominantColor = 0xFF050505;
        } else {
            mode = MODE_MONOCHROME_GRAY;
            dominantColor = 0xFF6E7681;
        }
        return new Analysis(mode, colorfulRatio, blackRatio, grayRatio,
                hue, saturation, value, dominantColor, hasHue, true);
    }

    /* --------------------------------------------------------------- internal */

    private static PaletteData build(long dialogId, Analysis analysis, int dominantColor, boolean fromBitmap) {
        final boolean dark = Theme.isCurrentThemeDark();
        final PaletteData data = new PaletteData(dialogId, analysis.mode, dominantColor, dark, fromBitmap,
                analysis.hue, analysis.saturation, analysis.value);
        data.computeAccent();
        return data;
    }

    /** Цвет из пространства HSV (насыщенность и яркость, а не «сплющенный» HSL). */
    private static int hsvColor(float hue, float saturation, float value) {
        return Color.HSVToColor(new float[]{
                (hue % 360f + 360f) % 360f,
                clamp(saturation, 0f, 1f),
                clamp(value, 0f, 1f)
        });
    }

    private static int setAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int blend(int color1, int color2, float ratio) {
        return ColorUtils.blendARGB(color1, color2, clamp(ratio, 0f, 1f));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Результат HSV-анализа одного битмапа. Для палитр, построенных по одному
     * известному цвету, доли заполняются искусственно (100% в нужной категории),
     * чтобы правила выбора режима оставались едиными.
     */
    static final class Analysis {

        final int mode;
        final float colorfulRatio;
        final float blackRatio;
        final float grayRatio;
        final float hue;
        final float saturation;
        final float value;
        final int dominantColor;
        final boolean hasHue;
        final boolean fromBitmap;

        Analysis(int mode, float colorfulRatio, float blackRatio, float grayRatio,
                 float hue, float saturation, float value, int dominantColor,
                 boolean hasHue, boolean fromBitmap) {
            this.mode = mode;
            this.colorfulRatio = colorfulRatio;
            this.blackRatio = blackRatio;
            this.grayRatio = grayRatio;
            this.hue = hue;
            this.saturation = saturation;
            this.value = value;
            this.dominantColor = dominantColor;
            this.hasHue = hasHue;
            this.fromBitmap = fromBitmap;
        }

        static Analysis fromColor(int color) {
            final float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            final float hue = Float.isNaN(hsv[0]) ? 0f : hsv[0];
            final float saturation = hsv[1];
            final float value = hsv[2];
            if (saturation >= COLOR_MIN_SATURATION && value >= COLOR_MIN_VALUE) {
                return new Analysis(MODE_COLORFUL, 1f, 0f, 0f,
                        hue, saturation, value, color, true, false);
            }
            if (value < BLACK_MAX_VALUE && saturation < BLACK_MAX_SATURATION) {
                return new Analysis(MODE_AMOLED_BLACK, 0f, 1f, 0f,
                        hue, saturation, value, 0xFF050505, false, false);
            }
            return new Analysis(MODE_MONOCHROME_GRAY, 0f, 0f, 1f,
                    hue, saturation, value, 0xFF6E7681, false, false);
        }
    }

    /* ----------------------------------------------------------- PaletteData */

    /** Готовая палитра одного диалога для текущей светлой/тёмной темы. */
    public static final class PaletteData {

        public final long dialogId;
        /** Доминирующий цвет аватарки (для чёрно-серых режимов — нейтральный). */
        public final int dominantColor;
        /** Был ли анализ выполнен по настоящему битмапу. */
        public final boolean fromBitmap;
        /** Построена ли палитра для тёмной темы. */
        public final boolean dark;

        private final int mode;
        private final float hue;
        private final float saturation;
        private final float value;
        private int accentColor;

        PaletteData(long dialogId, int mode, int dominantColor, boolean dark, boolean fromBitmap,
                    float hue, float saturation, float value) {
            this.dialogId = dialogId;
            this.mode = mode;
            this.dominantColor = dominantColor;
            this.dark = dark;
            this.fromBitmap = fromBitmap;
            this.hue = Float.isNaN(hue) ? 0f : hue;
            this.saturation = clamp(saturation, 0f, 1f);
            this.value = clamp(value, 0f, 1f);
        }

        private void computeAccent() {
            if (mode == MODE_AMOLED_BLACK) {
                accentColor = dark ? 0xFF26292E : 0xFF3E434A;
            } else if (mode == MODE_MONOCHROME_GRAY) {
                accentColor = 0xFF5C6470;
            } else if (dark) {
                accentColor = hsvColor(hue, 0.82f, 0.95f);
            } else {
                accentColor = hsvColor(hue, clamp(saturation * 0.78f, 0.55f, 0.82f), 0.72f);
            }
        }

        private boolean colorful() {
            return mode == MODE_COLORFUL;
        }

        public int getMode() {
            return mode;
        }

        public boolean isColorful() {
            return mode == MODE_COLORFUL;
        }

        public boolean isAmoledBlack() {
            return mode == MODE_AMOLED_BLACK;
        }

        public boolean isMonochromeGray() {
            return mode == MODE_MONOCHROME_GRAY;
        }

        public int getDominantColor() {
            return dominantColor;
        }

        public boolean isDark() {
            return dark;
        }

        public int getAccentColor() {
            return accentColor;
        }

        /** Контрастный текст на акцентной кнопке/исходящем пузыре. */
        public int getAccentTextColor() {
            return 0xFFFFFFFF;
        }

        /**
         * Яркий ореол вокруг аватарки. В AMOLED-режиме это обсидиановое кольцо
         * {@code #1C1E22}, в сером — чистый серый, в цветном — оттенок аватарки.
         */
        public int getGlowColor(int brightnessPercent) {
            final float factor = clamp(brightnessPercent / 100f, 0f, 1f);
            if (dark) {
                if (isAmoledBlack()) {
                    return 0xFF1C1E22;
                }
                if (isMonochromeGray()) {
                    return blend(0xFF6E7681, 0xFFC2C8D0, factor * 0.55f);
                }
                return hsvColor(hue, 0.86f, clamp(0.62f + 0.33f * factor, 0f, 1f));
            }
            if (isAmoledBlack()) {
                return blend(0xFFB8BFC7, 0xFFF1F3F5, factor * 0.6f);
            }
            if (isMonochromeGray()) {
                return blend(0xFFA8B0BA, 0xFFE3E7EB, factor * 0.6f);
            }
            return hsvColor(hue, 0.52f, clamp(0.72f + 0.2f * factor, 0f, 1f));
        }

        /** Внешний (самый прозрачный) слой свечения. */
        public int getGlowHaloColor(int brightnessPercent) {
            final float factor = clamp(brightnessPercent / 100f, 0f, 1f);
            if (dark) {
                if (isAmoledBlack()) {
                    return 0xFF000000;
                }
                if (isMonochromeGray()) {
                    return blend(0xFF2A2E33, 0xFF6E7681, factor * 0.5f);
                }
                return hsvColor(hue, 0.7f, clamp(0.35f + 0.25f * factor, 0f, 1f));
            }
            if (isAmoledBlack()) {
                return 0xFFE4E7EA;
            }
            if (isMonochromeGray()) {
                return 0xFFDDE1E5;
            }
            return hsvColor(hue, 0.28f, 0.96f);
        }

        /** Цвета радиального ореола: внешний, ядро, прозрачный край. */
        public int[] getGlowGradientColors(int brightnessPercent) {
            return new int[]{
                    getGlowHaloColor(brightnessPercent),
                    getGlowColor(brightnessPercent),
                    0
            };
        }

        /**
         * Верхний цвет фона переписки.
         *
         * @param saturationPercent насыщенность фона из настроек, 0..100
         */
        public int getChatBackgroundTop(int saturationPercent) {
            final float factor = 0.35f + 0.65f * clamp(saturationPercent / 100f, 0f, 1f);
            if (dark) {
                if (isAmoledBlack()) {
                    return 0xFF000000;
                }
                if (isMonochromeGray()) {
                    return 0xFF181A1D;
                }
                return hsvColor(hue, clamp(saturation * 0.82f * factor, 0.4f, 0.86f), 0.17f);
            }
            if (isAmoledBlack()) {
                return 0xFFF3F4F6;
            }
            if (isMonochromeGray()) {
                return 0xFFF2F3F5;
            }
            return hsvColor(hue, clamp(saturation * 0.3f * factor, 0.08f, 0.18f), 0.985f);
        }

        /** Нижний (самый глубокий) цвет фона переписки. */
        public int getChatBackgroundBottom(int saturationPercent) {
            final float factor = 0.35f + 0.65f * clamp(saturationPercent / 100f, 0f, 1f);
            if (dark) {
                if (isAmoledBlack()) {
                    return 0xFF000000;
                }
                if (isMonochromeGray()) {
                    return 0xFF141619;
                }
                return hsvColor(hue, clamp(saturation * 0.75f * factor, 0.38f, 0.8f), 0.145f);
            }
            if (isAmoledBlack()) {
                return 0xFFEDEFF2;
            }
            if (isMonochromeGray()) {
                return 0xFFEAECEF;
            }
            return hsvColor(hue, clamp(saturation * 0.28f * factor, 0.08f, 0.18f), 0.96f);
        }

        /** Оттенок матового стекла (шапка/панель ввода) для текущей темы. */
        public int getGlassPanelColor(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xFF0A0B0D;
                }
                if (isMonochromeGray()) {
                    return 0xFF202328;
                }
                return hsvColor(hue, clamp(saturation * 0.55f, 0.24f, 0.52f), 0.16f);
            }
            if (isAmoledBlack()) {
                return 0xFFF7F8F9;
            }
            if (isMonochromeGray()) {
                return 0xFFF6F7F8;
            }
            return hsvColor(hue, clamp(saturation * 0.22f, 0.08f, 0.18f), 0.97f);
        }

        /** Оттенок стекла верхней шапки чата. */
        public int getChatHeaderColor(boolean darkTheme) {
            return getGlassPanelColor(darkTheme);
        }

        /** Фон панели ввода сообщения. */
        public int getMessagePanelColor(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xFF08090A;
                }
                if (isMonochromeGray()) {
                    return 0xFF1C1F23;
                }
                return hsvColor(hue, clamp(saturation * 0.48f, 0.22f, 0.5f), 0.15f);
            }
            if (isAmoledBlack()) {
                return 0xFFFAFBFC;
            }
            if (isMonochromeGray()) {
                return 0xFFF4F5F7;
            }
            return hsvColor(hue, clamp(saturation * 0.2f, 0.06f, 0.16f), 0.985f);
        }

        /** Фон экрана профиля: глубокий в тёмной теме, светлый в светлой. */
        public int getProfileBackgroundColor(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xFF000000;
                }
                if (isMonochromeGray()) {
                    return 0xFF181A1D;
                }
                return hsvColor(hue, clamp(saturation * 0.5f, 0.22f, 0.5f), 0.13f);
            }
            if (isAmoledBlack()) {
                return 0xFFF5F6F8;
            }
            if (isMonochromeGray()) {
                return 0xFFF2F3F5;
            }
            return hsvColor(hue, clamp(saturation * 0.2f, 0.06f, 0.16f), 0.97f);
        }

        /** Фон карточек разделов профиля, медиа и избранного. */
        public int getProfileCardColor(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xFF0A0B0D;
                }
                if (isMonochromeGray()) {
                    return 0xFF202329;
                }
                return hsvColor(hue, clamp(saturation * 0.42f, 0.18f, 0.45f), 0.19f);
            }
            if (isAmoledBlack()) {
                return 0xFFFFFFFF;
            }
            if (isMonochromeGray()) {
                return 0xFFFFFFFF;
            }
            return hsvColor(hue, clamp(saturation * 0.14f, 0.04f, 0.14f), 0.995f);
        }

        public int getMediaBackgroundColor(boolean darkTheme) {
            return getProfileBackgroundColor(darkTheme);
        }

        public int getMediaCardColor(boolean darkTheme) {
            return getProfileCardColor(darkTheme);
        }

        /* ------------------------------------------------------ chat bubbles */

        /** Входящий пузырь: полупрозрачное стекло в оттенке палитры. */
        public int getInBubbleColor(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xF20E0F11;
                }
                if (isMonochromeGray()) {
                    return 0xF2262A30;
                }
                return setAlpha(hsvColor(hue, clamp(saturation * 0.42f, 0.18f, 0.48f), 0.2f), 0xF2);
            }
            if (isAmoledBlack() || isMonochromeGray()) {
                return 0xF2FFFFFF;
            }
            return setAlpha(hsvColor(hue, clamp(saturation * 0.14f, 0.04f, 0.14f), 0.99f), 0xF2);
        }

        public int getInBubbleSelectedColor(boolean darkTheme) {
            return setAlpha(blend(getInBubbleColor(darkTheme), accentColor, 0.14f), 0xFF);
        }

        /** Исходящий пузырь: акцент текущей палитры. */
        public int getOutBubbleColor(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xFF26292E;
                }
                if (isMonochromeGray()) {
                    return 0xFF5C6470;
                }
                return hsvColor(hue, 0.75f, 0.72f);
            }
            if (isAmoledBlack()) {
                return 0xFF3E434A;
            }
            if (isMonochromeGray()) {
                return 0xFF5C6470;
            }
            return hsvColor(hue, 0.62f, 0.68f);
        }

        public int getOutBubbleSelectedColor(boolean darkTheme) {
            return getOutBubbleGradient2(darkTheme);
        }

        public int getOutBubbleGradient1(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xFF2C3035;
                }
                if (isMonochromeGray()) {
                    return 0xFF6E7681;
                }
                return hsvColor(hue, 0.82f, 0.88f);
            }
            if (isAmoledBlack()) {
                return 0xFF4A5058;
            }
            if (isMonochromeGray()) {
                return 0xFF8A929D;
            }
            return hsvColor(hue, 0.52f, 0.8f);
        }

        public int getOutBubbleGradient2(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xFF26292E;
                }
                if (isMonochromeGray()) {
                    return 0xFF5C6470;
                }
                return hsvColor(hue, 0.78f, 0.72f);
            }
            if (isAmoledBlack()) {
                return 0xFF3E434A;
            }
            if (isMonochromeGray()) {
                return 0xFF7A828D;
            }
            return hsvColor(hue, 0.6f, 0.7f);
        }

        public int getOutBubbleGradient3(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xFF1E2125;
                }
                if (isMonochromeGray()) {
                    return 0xFF4A525D;
                }
                return hsvColor(hue, 0.7f, 0.58f);
            }
            if (isAmoledBlack()) {
                return 0xFF343941;
            }
            if (isMonochromeGray()) {
                return 0xFF69717C;
            }
            return hsvColor(hue, 0.55f, 0.62f);
        }

        public int getInBubbleTextColor(boolean darkTheme) {
            return darkTheme ? 0xFFF5F7FA : 0xFF1A1C1F;
        }

        public int getOutBubbleTextColor(boolean darkTheme) {
            return 0xFFFFFFFF;
        }

        public int getInBubbleSecondaryTextColor(boolean darkTheme) {
            return darkTheme ? 0xA6FFFFFF : 0x991A1C1F;
        }

        public int getOutBubbleSecondaryTextColor(boolean darkTheme) {
            return 0xBFFFFFFF;
        }

        public int getPanelTextColor(boolean darkTheme) {
            return darkTheme ? 0xFFF5F7FA : 0xFF1A1C1F;
        }

        public int getPanelIconColor(boolean darkTheme) {
            return darkTheme ? 0xFFE8ECF0 : 0xFF2A2E33;
        }

        public int getInReplyLineColor(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xFF4A4F57;
                }
                if (isMonochromeGray()) {
                    return 0xFFA8B0BA;
                }
                return hsvColor(hue, 0.6f, 0.82f);
            }
            if (isAmoledBlack()) {
                return 0xFF6E747C;
            }
            if (isMonochromeGray()) {
                return 0xFF8A929D;
            }
            return hsvColor(hue, 0.6f, 0.62f);
        }

        public int getOutReplyLineColor(boolean darkTheme) {
            if (darkTheme && isAmoledBlack()) {
                return 0xFF8A929C;
            }
            return 0xBFFFFFFF;
        }

        /** Полупрозрачная плашка служебных сообщений/даты. */
        public int getServiceBackgroundColor(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xCC141517;
                }
                if (isMonochromeGray()) {
                    return 0xCC25282D;
                }
                return setAlpha(blend(hsvColor(hue, 0.5f, 0.35f), accentColor, 0.45f), 0xCC);
            }
            if (isAmoledBlack()) {
                return 0xE6E3E6EA;
            }
            if (isMonochromeGray()) {
                return 0xE6E2E5E8;
            }
            return setAlpha(blend(0xFFFFFFFF, accentColor, 0.18f), 0xE6);
        }

        /* ------------------------------------------------ dialogs/profile cards */

        /**
         * Фон стеклянной карточки чата: {@code #050505} в AMOLED, дымчато-серый
         * {@code #6E7681} с прозрачностью в монохроме, тёмный оттенок аватарки в
         * цветном режиме.
         */
        public int getDialogCardColor(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xFF050505;
                }
                if (isMonochromeGray()) {
                    return 0x996E7681;
                }
                return setAlpha(hsvColor(hue, clamp(saturation * 0.28f, 0.12f, 0.3f), 0.105f), 0xF0);
            }
            if (isAmoledBlack()) {
                return 0xFFF7F8F9;
            }
            if (isMonochromeGray()) {
                return 0xFFEDEFF2;
            }
            return setAlpha(hsvColor(hue, clamp(saturation * 0.12f, 0.03f, 0.12f), 0.995f), 0xF2);
        }

        /** Цвет градиентной подсветки карточки чата. */
        public int getDialogCardAccentColor(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xFF26292E;
                }
                if (isMonochromeGray()) {
                    return 0xFF6E7681;
                }
                return accentColor;
            }
            if (isAmoledBlack()) {
                return 0xFF3E434A;
            }
            if (isMonochromeGray()) {
                return 0xFF7A828D;
            }
            return hsvColor(hue, 0.35f, 0.9f);
        }

        /** Тонкая стеклянная обводка карточки чата. */
        public int getDialogCardStrokeColor(boolean darkTheme) {
            if (darkTheme) {
                if (isAmoledBlack()) {
                    return 0xFF222428;
                }
                if (isMonochromeGray()) {
                    return 0x668E98A3;
                }
                return blend(accentColor, 0xFFFFFFFF, 0.55f);
            }
            if (isAmoledBlack()) {
                return 0xFFD5DAE0;
            }
            if (isMonochromeGray()) {
                return 0xFFC9CFD6;
            }
            return 0xFFE2E7ED;
        }

        /** Верхняя область профиля равна фону экрана — шапка без горизонтального шва. */
        public int getHeaderColor() {
            return getProfileBackgroundColor(dark);
        }

        public int getHeaderGradientTop() {
            final int background = getProfileBackgroundColor(dark);
            return blend(background, dark ? 0xFFFFFFFF : 0xFF000000, dark ? 0.025f : 0.012f);
        }

        public int getHeaderGradientBottom() {
            return getProfileBackgroundColor(dark);
        }

        public int getHeaderTextColor() {
            return dark ? 0xFFF5F7FA : 0xFF1A1C1F;
        }

        public int getHeaderSubtitleColor() {
            return dark ? 0xCCF5F7FA : 0x991A1C1F;
        }
    }
}
