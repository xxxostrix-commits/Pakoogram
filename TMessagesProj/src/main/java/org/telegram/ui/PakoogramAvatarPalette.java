package org.telegram.ui;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.LruCache;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.ImageReceiver;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.VectorAvatarThumbDrawable;

/**
 * Pakoogram: единый кэш адаптивной палитры аватарок.
 *
 * <p>Из аватарки (битмап, векторная аватарка или {@link AvatarDrawable}) вычисляется
 * доминирующий оттенок в пространстве HSV: тёмные ({@code V < 0.22}) и серые
 * ({@code S < 0.25}) пиксели игнорируются, поэтому красные аватарки дают чистый
 * рубиново-красный, а жёлтые — тёплый янтарно-золотой. Если насыщенных пикселей
 * нет (чёрно-белая или белая аватарка), используется фирменный фиолетовый
 * {@link #FALLBACK_ACCENT_COLOR}. Из оттенка затем строятся гармоничные цвета:</p>
 *
 * <ul>
 *     <li>{@link PaletteData#getDominantColor()} — исходный доминирующий цвет;</li>
 *     <li>{@link PaletteData#getChatBackgroundTop(int)} / {@link PaletteData#getChatBackgroundBottom(int)}
 *     — глубокий насыщенный градиент фона переписки (тёмная тема) или воздушный
 *     пастельный (светлая тема);</li>
 *     <li>{@link PaletteData#getGlassPanelColor(boolean)} — оттенок матовых стеклянных панелей
 *     (шапка чата, панель ввода, панель закреплённого сообщения);</li>
 *     <li>{@link PaletteData#getAccentColor()} — яркий акцентный цвет кнопок и бейджей;</li>
 *     <li>{@link PaletteData#getGlowColor(int)} — яркий цвет для свечения аватарки.</li>
 * </ul>
 *
 * <p>Готовые палитры кешируются в {@link LruCache} по {@code dialogId}, поэтому список
 * чатов, переписка и профиль используют одни и те же цвета и не пересчитывают пиксели
 * аватарки на каждом кадре. При переключении светлой/тёмной темы кэш сбрасывается:
 * цвета фонов и карточек в этих режимах строятся по-разному.</p>
 */
public final class PakoogramAvatarPalette {

    /** Размер общего LRU-кэша палитр. */
    public static final int CACHE_SIZE = 512;

    /** Фирменный фиолетовый акцент Pakoogram — палитра для чёрно-белых аватарок. */
    public static final int FALLBACK_ACCENT_COLOR = 0xFF7C4DFF;

    /** Пиксели темнее этого значения {@code V} не участвуют в анализе (тени, тёмные края). */
    private static final float MIN_VALUE = 0.22f;
    /** Пиксели серее этого значения {@code S} не участвуют в анализе (фон, блики, ч/б фото). */
    private static final float MIN_SATURATION = 0.25f;
    /** Количество корзин гистограммы оттенка (по 30°). */
    private static final int HUE_BUCKETS = 12;

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
     *
     * @param dialogId      идентификатор диалога (пользователь или чат), ключ кэша
     * @param imageReceiver получатель аватарки, может быть {@code null}
     * @param avatarDrawable запасная сгенерированная аватарка, может быть {@code null}
     */
    public static PaletteData getOrCreate(long dialogId, ImageReceiver imageReceiver, AvatarDrawable avatarDrawable) {
        ensureThemeMode();
        final PaletteData cached = get(dialogId);
        final Bitmap bitmap = imageReceiver != null ? imageReceiver.getBitmap() : null;
        final boolean hasBitmap = bitmap != null && !bitmap.isRecycled();
        if (cached != null && (cached.fromBitmap || !hasBitmap)) {
            return cached;
        }

        int color = 0;
        boolean fromBitmap = false;
        if (hasBitmap) {
            color = extractDominantColor(bitmap);
            fromBitmap = color != 0;
        }
        if (color == 0 && imageReceiver != null) {
            final Drawable drawable = imageReceiver.getDrawable();
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
        final PaletteData data = build(dialogId, color, fromBitmap);
        if (dialogId != 0) {
            CACHE.put(dialogId, data);
        }
        return data;
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
        final PaletteData data = build(dialogId, color, false);
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
    }
    /* ------------------------------------------------------------- extraction */

    /**
     * Вычисляет доминирующий оттенок битмапа в пространстве HSV.
     *
     * <p>Пиксели, которые темнее {@link #MIN_VALUE} или серее {@link #MIN_SATURATION},
     * пропускаются — тени, тёмные края и бледный фон не размывают цвет. Из оставшихся
     * строится гистограмма оттенков с весом {@code S * V}: побеждает самый насыщенный
     * доминирующий оттенок, а итоговый цвет собирается обратно из {@code hue} с
     * повышенной насыщенностью. Поэтому красная аватарка даёт чистый рубиново-красный
     * (а не коричневый от усреднения RGB), а жёлтая — тёплый янтарно-золотой
     * (а не болотно-зелёный).</p>
     *
     * @return цвет в формате {@link Color#rgb(int, int, int)} или
     *         {@link #FALLBACK_ACCENT_COLOR}, если насыщенных пикселей не нашлось
     *         (чёрно-белая или белая аватарка).
     */
    static int extractDominantColor(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return 0;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            return 0;
        }
        final int samples = 32;
        final float[] hsv = new float[3];
        final float[] bucketWeight = new float[HUE_BUCKETS];
        final float[] bucketHueSin = new float[HUE_BUCKETS];
        final float[] bucketHueCos = new float[HUE_BUCKETS];
        final float[] bucketSaturation = new float[HUE_BUCKETS];
        final float[] bucketValue = new float[HUE_BUCKETS];
        try {
            for (int y = 0; y < samples; y++) {
                final int pixelY = Math.min(bitmap.getHeight() - 1, y * bitmap.getHeight() / samples);
                for (int x = 0; x < samples; x++) {
                    final int pixelX = Math.min(bitmap.getWidth() - 1, x * bitmap.getWidth() / samples);
                    final int pixel = bitmap.getPixel(pixelX, pixelY);
                    if (Color.alpha(pixel) < 160) {
                        continue;
                    }
                    Color.colorToHSV(pixel, hsv);
                    // Слишком тёмные и слишком серые пиксели цвет не задают.
                    if (hsv[2] < MIN_VALUE || hsv[1] < MIN_SATURATION) {
                        continue;
                    }
                    final int bucket = Math.min(HUE_BUCKETS - 1, (int) (hsv[0] / (360f / HUE_BUCKETS)));
                    final float weight = hsv[1] * hsv[2];
                    final double hueRadians = Math.toRadians(hsv[0]);
                    bucketWeight[bucket] += weight;
                    bucketHueSin[bucket] += (float) Math.sin(hueRadians) * weight;
                    bucketHueCos[bucket] += (float) Math.cos(hueRadians) * weight;
                    bucketSaturation[bucket] += hsv[1] * weight;
                    bucketValue[bucket] += hsv[2] * weight;
                }
            }
        } catch (Exception ignore) {
            return 0;
        }

        // Ищем самую «тяжёлую» корзину вместе с соседними: оттенок на границе корзин
        // не должен проигрывать из-за округления.
        int bestBucket = -1;
        float bestWeight = 0f;
        for (int b = 0; b < HUE_BUCKETS; b++) {
            final float weight = bucketWeight[b]
                    + 0.5f * bucketWeight[(b + HUE_BUCKETS - 1) % HUE_BUCKETS]
                    + 0.5f * bucketWeight[(b + 1) % HUE_BUCKETS];
            if (weight > bestWeight) {
                bestWeight = weight;
                bestBucket = b;
            }
        }
        if (bestBucket == -1 || bestWeight <= 0f) {
            // Насыщенных пикселей нет: чёрно-белая (или белая) аватарка.
            return FALLBACK_ACCENT_COLOR;
        }

        float weightSum = 0f;
        float hueSinSum = 0f;
        float hueCosSum = 0f;
        float saturationSum = 0f;
        float valueSum = 0f;
        for (int offset = -1; offset <= 1; offset++) {
            final int b = ((bestBucket + offset) % HUE_BUCKETS + HUE_BUCKETS) % HUE_BUCKETS;
            // Соседние корзины уступают доминирующей, но помогают сгладить оттенок.
            final float neighborFactor = offset == 0 ? 1f : 0.5f;
            weightSum += bucketWeight[b] * neighborFactor;
            hueSinSum += bucketHueSin[b] * neighborFactor;
            hueCosSum += bucketHueCos[b] * neighborFactor;
            saturationSum += bucketSaturation[b] * neighborFactor;
            valueSum += bucketValue[b] * neighborFactor;
        }
        if (weightSum <= 0f) {
            return FALLBACK_ACCENT_COLOR;
        }
        float hue = (float) Math.toDegrees(Math.atan2(hueSinSum, hueCosSum));
        if (hue < 0f) {
            hue += 360f;
        }
        // Собираем чистый насыщенный оттенок: усреднение RGB здесь не используется,
        // поэтому цвет не «загрязняется» соседними пикселями.
        final float saturation = clamp(saturationSum / weightSum * 1.12f, 0.55f, 1f);
        final float value = clamp(valueSum / weightSum * 1.04f, 0.62f, 1f);
        return Color.HSVToColor(new float[]{hue, saturation, value});
    }

    /* --------------------------------------------------------------- internal */

    private static PaletteData build(long dialogId, int color, boolean fromBitmap) {
        final boolean dark = Theme.isCurrentThemeDark();
        final float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        final float hue = Float.isNaN(hsv[0]) ? 0f : hsv[0];
        // Даже у серых аватарок оставляем заметный оттенок, иначе тема будет выглядеть «грязной».
        final float saturation = clamp(hsv[1], 0.35f, 1f);
        final float value = clamp(hsv[2], dark ? 0.62f : 0.7f, 1f);

        // Тёмная тема: глубокий насыщенный фон и очень яркий акцент для кнопок.
        // Светлая тема: воздушные пастельные фоны/карточки и насыщенный, но не кислотный акцент.
        final int accent = dark
                ? hsvColor(hue, 0.8f, 0.95f)
                : hsvColor(hue, clamp(saturation * 0.9f, 0.58f, 0.88f), 0.82f);
        final int glass = dark
                ? hsvColor(hue, clamp(saturation * 0.72f, 0.36f, 0.62f), 0.17f)
                : hsvColor(hue, clamp(saturation * 0.24f, 0.12f, 0.2f), 0.97f);
        final int glow = dark
                ? hsvColor(hue, 0.86f, 0.98f)
                : hsvColor(hue, 0.6f, 0.95f);

        return new PaletteData(dialogId, color, accent, glass, glow, hue, saturation, value, dark, fromBitmap);
    }

    /** Цвет из пространства HSV (насыщенность и яркость, а не «сплющенный» HSL). */
    private static int hsvColor(float hue, float saturation, float value) {
        return Color.HSVToColor(new float[]{
                (hue % 360f + 360f) % 360f,
                clamp(saturation, 0f, 1f),
                clamp(value, 0f, 1f)
        });
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
    /* ----------------------------------------------------------- PaletteData */

    /** Готовая палитра одного диалога. */
    public static final class PaletteData {

        public final long dialogId;
        /** Доминирующий цвет аватарки. */
        public final int dominantColor;
        /** Насыщенный акцентный цвет. */
        public final int accentColor;
        /** Оттенок стеклянных панелей (без альфы). */
        public final int glassColor;
        /** Яркий цвет свечения (без альфы). */
        public final int glowColor;
        /** Был ли доминирующий цвет извлечён из настоящего битмапа. */
        public final boolean fromBitmap;
        /** Построена ли палитра для тёмной темы. */
        public final boolean dark;

        private final float hue;
        private final float saturation;
        private final float value;

        PaletteData(long dialogId, int dominantColor, int accentColor, int glassColor, int glowColor,
                    float hue, float saturation, float value, boolean dark, boolean fromBitmap) {
            this.dialogId = dialogId;
            this.dominantColor = dominantColor;
            this.accentColor = accentColor;
            this.glassColor = glassColor;
            this.glowColor = glowColor;
            this.hue = hue;
            this.saturation = saturation;
            this.value = value;
            this.dark = dark;
            this.fromBitmap = fromBitmap;
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

        public int getGlowColor(int brightnessPercent) {
            final float factor = clamp(brightnessPercent / 100f, 0f, 1f);
            return dark
                    ? hsvColor(hue, 0.86f, clamp(0.62f + 0.33f * factor, 0f, 1f))
                    : hsvColor(hue, 0.58f, clamp(0.7f + 0.2f * factor, 0f, 1f));
        }

        /**
         * Верхний (чуть более светлый) цвет градиента переписки.
         *
         * @param saturationPercent насыщенность фона из настроек, 0..100
         */
        public int getChatBackgroundTop(int saturationPercent) {
            final float factor = 0.35f + 0.65f * clamp(saturationPercent / 100f, 0f, 1f);
            if (dark) {
                // Глубокий, но насыщенный тёмный фон: V держим в диапазоне 0.14..0.18.
                return hsvColor(hue, clamp(saturation * 0.85f * factor, 0.42f, 0.86f), 0.18f);
            }
            // Светлая тема: воздушный пастельный фон (S 0.12..0.20, V 0.96..0.98).
            return hsvColor(hue, clamp(saturation * 0.3f * factor, 0.12f, 0.2f), 0.975f);
        }

        /**
         * Нижний (самый глубокий) цвет градиента переписки.
         *
         * @param saturationPercent насыщенность фона из настроек, 0..100
         */
        public int getChatBackgroundBottom(int saturationPercent) {
            final float factor = 0.35f + 0.65f * clamp(saturationPercent / 100f, 0f, 1f);
            if (dark) {
                return hsvColor(hue, clamp(saturation * 0.75f * factor, 0.38f, 0.8f), 0.145f);
            }
            return hsvColor(hue, clamp(saturation * 0.28f * factor, 0.12f, 0.2f), 0.955f);
        }

        /** Оттенок матового стекла (шапка/панель ввода) для текущей темы. */
        public int getGlassPanelColor(boolean dark) {
            if (dark) {
                return hsvColor(hue, clamp(saturation * 0.72f, 0.36f, 0.62f), 0.17f);
            }
            return hsvColor(hue, clamp(saturation * 0.24f, 0.12f, 0.2f), 0.97f);
        }

        /** Фон экрана профиля: глубокий в тёмной теме, пастельный в светлой. */
        public int getProfileBackgroundColor(boolean dark) {
            if (dark) {
                return hsvColor(hue, clamp(saturation * 0.6f, 0.3f, 0.55f), 0.145f);
            }
            return hsvColor(hue, clamp(saturation * 0.22f, 0.12f, 0.2f), 0.965f);
        }

        /** Фон карточек разделов профиля. */
        public int getProfileCardColor(boolean dark) {
            if (dark) {
                return hsvColor(hue, clamp(saturation * 0.52f, 0.26f, 0.5f), 0.19f);
            }
            return hsvColor(hue, clamp(saturation * 0.2f, 0.12f, 0.2f), 0.982f);
        }

        /** Верхняя область профиля: достаточно контрастная для текста в обеих темах. */
        public int getHeaderColor() {
            if (dark) {
                return hsvColor(hue, clamp(saturation * 0.85f, 0.45f, 0.85f), 0.235f);
            }
            // В светлой теме шапка воздушная и пастельная: текст профиля здесь тёмный.
            return hsvColor(hue, clamp(saturation * 0.45f, 0.22f, 0.4f), 0.9f);
        }

        public int getHeaderGradientTop() {
            if (dark) {
                return hsvColor(hue, clamp(saturation * 0.85f, 0.45f, 0.85f), 0.3f);
            }
            return hsvColor(hue, clamp(saturation * 0.45f, 0.22f, 0.4f), 0.86f);
        }

        public int getHeaderGradientBottom() {
            if (dark) {
                // Почти совпадает с фоном тела профиля: шапка переходит в список без шва.
                return hsvColor(hue, clamp(saturation * 0.75f, 0.38f, 0.75f), 0.155f);
            }
            return hsvColor(hue, clamp(saturation * 0.3f, 0.15f, 0.28f), 0.95f);
        }
    }
    /* --------------------------------------------------- themed provider */

    /**
     * Провайдер ресурсов, который подмешивает палитру аватарки в фоны профиля.
     *
     * <p>Переопределяются только фоновые ключи, все остальные (текст, иконки,
     * разделители) делегируются исходному провайдеру, поэтому контраст текста
     * сохраняется в обеих темах.</p>
     */
    public static final class ThemedResourcesProvider implements Theme.ResourcesProvider {

        private final Theme.ResourcesProvider delegate;
        private PaletteData palette;
        private boolean dark;

        public ThemedResourcesProvider(Theme.ResourcesProvider delegate) {
            this.delegate = delegate;
        }

        public void setPalette(PaletteData palette, boolean dark) {
            this.palette = palette;
            this.dark = dark;
        }

        private int resolve(int key) {
            final PaletteData p = palette;
            if (p != null) {
                if (key == Theme.key_windowBackgroundGray) {
                    return p.getProfileBackgroundColor(dark);
                }
                if (key == Theme.key_windowBackgroundWhite) {
                    return p.getProfileCardColor(dark);
                }
                if (key == Theme.key_actionBarDefault) {
                    return p.getHeaderColor();
                }
                // Нижняя панель вкладок профиля («Подарки / Медиа / Избранное»):
                // активный индикатор, активный/неактивный текст и мягкий селектор.
                if (key == Theme.key_profile_tabSelectedLine || key == Theme.key_profile_tabSelectedText) {
                    return p.getAccentColor();
                }
                if (key == Theme.key_profile_tabSelector) {
                    return Theme.multAlpha(p.getAccentColor(), 0.16f);
                }
                if (key == Theme.key_profile_tabText) {
                    return ColorUtils.blendARGB(delegateColor(key), p.getAccentColor(), dark ? 0.45f : 0.35f);
                }
                // Нижняя плавающая кнопка («Отправить подарок») и другие акцентные кнопки профиля.
                if (key == Theme.key_featuredStickers_addButton) {
                    return p.getAccentColor();
                }
                if (key == Theme.key_featuredStickers_addButtonPressed) {
                    return ColorUtils.blendARGB(p.getAccentColor(), Color.BLACK, 0.16f);
                }
            }
            return delegateColor(key);
        }

        private int delegateColor(int key) {
            return delegate != null ? delegate.getColor(key) : Theme.getColor(key);
        }

        @Override
        public int getColor(int key) {
            return resolve(key);
        }

        @Override
        public int getColorOrDefault(int key) {
            return resolve(key);
        }

        @Override
        public int getCurrentColor(int key) {
            return resolve(key);
        }

        @Override
        public void setAnimatedColor(int key, int color) {
            if (delegate != null) {
                delegate.setAnimatedColor(key, color);
            }
        }

        @Override
        public Drawable getDrawable(String drawableKey) {
            return delegate != null ? delegate.getDrawable(drawableKey) : null;
        }

        @Override
        public Paint getPaint(String paintKey) {
            return delegate != null ? delegate.getPaint(paintKey) : Theme.getThemePaint(paintKey);
        }

        @Override
        public boolean hasGradientService() {
            return delegate != null && delegate.hasGradientService();
        }

        @Override
        public boolean isDark() {
            return palette != null ? dark : delegate != null ? delegate.isDark() : Theme.isCurrentThemeDark();
        }

        @Override
        public void applyServiceShaderMatrix(int w, int h, float translationX, float translationY) {
            if (delegate != null) {
                delegate.applyServiceShaderMatrix(w, h, translationX, translationY);
            } else {
                Theme.applyServiceShaderMatrix(w, h, translationX, translationY);
            }
        }

        @Override
        public ColorFilter getAnimatedEmojiColorFilter() {
            return delegate != null
                    ? delegate.getAnimatedEmojiColorFilter()
                    : Theme.chat_animatedEmojiTextColorFilter;
        }

        @Override
        public boolean isCompactMode() {
            return delegate != null ? delegate.isCompactMode() : Theme.isCompactMode(null);
        }
    }
}
