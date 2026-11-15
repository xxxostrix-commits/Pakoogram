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
 * Pakoogram: единый кэш палитры аватарок.
 *
 * <p>Из аватарки (битмап, векторная аватарка или {@link AvatarDrawable}) вычисляется
 * основной яркий/насыщенный цвет, из которого затем строятся гармоничные оттенки:</p>
 *
 * <ul>
 *     <li>{@link PaletteData#getDominantColor()} — исходный доминирующий цвет;</li>
 *     <li>{@link PaletteData#getChatBackgroundTop(int)} / {@link PaletteData#getChatBackgroundBottom(int)}
 *     — приглушённый тёмный градиент фона переписки;</li>
 *     <li>{@link PaletteData#getGlassPanelColor(boolean)} — оттенок матовых стеклянных панелей
 *     (шапка чата, панель ввода, шапка профиля);</li>
 *     <li>{@link PaletteData#getAccentColor()} — насыщенный акцентный цвет;</li>
 *     <li>{@link PaletteData#getGlowColor(int)} — яркий цвет для свечения аватарки.</li>
 * </ul>
 *
 * <p>Готовые палитры кешируются в {@link LruCache} по {@code dialogId}, поэтому список
 * чатов, переписка и профиль используют одни и те же цвета и не пересчитывают пиксели
 * аватарки на каждом кадре.</p>
 */
public final class PakoogramAvatarPalette {

    /** Размер общего LRU-кэша палитр. */
    public static final int CACHE_SIZE = 512;

    private static final LruCache<Long, PaletteData> CACHE = new LruCache<>(CACHE_SIZE);

    private PakoogramAvatarPalette() {
        // утилитарный класс
    }

    /* ------------------------------------------------------------------ API */

    /** @return закешированная палитра для диалога или {@code null}, если её ещё нет. */
    public static PaletteData get(long dialogId) {
        if (dialogId == 0) {
            return null;
        }
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
     * Вычисляет доминирующий цвет битмапа, усредняя только яркие и насыщенные пиксели
     * (бледный фон и тёмные края не влияют на результат).
     *
     * @return цвет в формате {@link Color#rgb(int, int, int)} или {@code 0}, если
     *         выделить цвет не удалось.
     */
    static int extractDominantColor(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return 0;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            return 0;
        }
        final int samples = 24;
        final float stepX = Math.max(1f, bitmap.getWidth() / (float) samples);
        final float stepY = Math.max(1f, bitmap.getHeight() / (float) samples);
        final float[] hsv = new float[3];
        float red = 0f, green = 0f, blue = 0f, weightSum = 0f;
        try {
            for (int y = 0; y < samples; y++) {
                final int pixelY = Math.min(bitmap.getHeight() - 1, (int) (y * stepY));
                for (int x = 0; x < samples; x++) {
                    final int pixelX = Math.min(bitmap.getWidth() - 1, (int) (x * stepX));
                    final int pixel = bitmap.getPixel(pixelX, pixelY);
                    if (Color.alpha(pixel) < 160) {
                        continue;
                    }
                    final int pixelRed = Color.red(pixel);
                    final int pixelGreen = Color.green(pixel);
                    final int pixelBlue = Color.blue(pixel);
                    Color.RGBToHSV(pixelRed, pixelGreen, pixelBlue, hsv);
                    if (hsv[1] < 0.25f || hsv[2] < 0.3f) {
                        continue;
                    }
                    final float weight = hsv[1] * hsv[2];
                    red += pixelRed * weight;
                    green += pixelGreen * weight;
                    blue += pixelBlue * weight;
                    weightSum += weight;
                }
            }
        } catch (Exception ignore) {
            return 0;
        }
        if (weightSum <= 0f) {
            return 0;
        }
        return Color.rgb(
                Math.min(255, Math.round(red / weightSum)),
                Math.min(255, Math.round(green / weightSum)),
                Math.min(255, Math.round(blue / weightSum)));
    }

    /* --------------------------------------------------------------- internal */

    private static PaletteData build(long dialogId, int color, boolean fromBitmap) {
        final float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        final float hue = hsv[0];
        // Даже у серых аватарок оставляем заметный оттенок, иначе тема будет выглядеть «грязной».
        final float saturation = clamp(hsv[1], 0.32f, 1f);
        final float value = clamp(hsv[2], 0.55f, 1f);

        final int accent = hsl(hue, clamp(saturation * 1.1f, 0f, 1f), clamp(value * 0.54f, 0f, 0.62f));
        final int glass = hsl(hue, clamp(saturation * 0.42f, 0.1f, 0.5f), 0.21f);
        final int glow = hsl(hue, clamp(0.75f + saturation * 0.25f, 0f, 1f), 0.56f);

        return new PaletteData(dialogId, color, accent, glass, glow, hue, saturation, value, fromBitmap);
    }

    private static int hsl(float hue, float saturation, float lightness) {
        return ColorUtils.HSLToColor(new float[]{
                hue,
                clamp(saturation, 0f, 1f),
                clamp(lightness, 0f, 1f)
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

        private final float hue;
        private final float saturation;
        private final float value;

        PaletteData(long dialogId, int dominantColor, int accentColor, int glassColor, int glowColor,
                    float hue, float saturation, float value, boolean fromBitmap) {
            this.dialogId = dialogId;
            this.dominantColor = dominantColor;
            this.accentColor = accentColor;
            this.glassColor = glassColor;
            this.glowColor = glowColor;
            this.hue = hue;
            this.saturation = saturation;
            this.value = value;
            this.fromBitmap = fromBitmap;
        }

        public int getDominantColor() {
            return dominantColor;
        }

        public int getAccentColor() {
            return accentColor;
        }

        public int getGlowColor(int brightnessPercent) {
            final float factor = clamp(brightnessPercent / 100f, 0f, 1f);
            return hsl(hue,
                    clamp(0.72f + 0.28f * factor, 0f, 1f),
                    clamp(0.42f + 0.20f * factor, 0f, 0.72f));
        }

        /**
         * Верхний (чуть более светлый) цвет градиента переписки.
         *
         * @param saturationPercent насыщенность фона из настроек, 0..100
         */
        public int getChatBackgroundTop(int saturationPercent) {
            final float factor = 0.35f + 0.65f * clamp(saturationPercent / 100f, 0f, 1f);
            return hsl(hue,
                    clamp(saturation * 0.55f * factor, 0.05f, 0.5f),
                    0.19f);
        }

        /**
         * Нижний (самый глубокий) цвет градиента переписки.
         *
         * @param saturationPercent насыщенность фона из настроек, 0..100
         */
        public int getChatBackgroundBottom(int saturationPercent) {
            final float factor = 0.35f + 0.65f * clamp(saturationPercent / 100f, 0f, 1f);
            return hsl(hue,
                    clamp(saturation * 0.45f * factor, 0.04f, 0.42f),
                    0.095f);
        }

        /** Оттенок матового стекла (шапка/панель ввода) для текущей темы. */
        public int getGlassPanelColor(boolean dark) {
            return dark
                    ? hsl(hue, clamp(saturation * 0.32f, 0.06f, 0.34f), 0.17f)
                    : hsl(hue, clamp(saturation * 0.22f, 0.05f, 0.3f), 0.9f);
        }

        /** Фон экрана профиля: глубокий в тёмной теме, пастельный в светлой. */
        public int getProfileBackgroundColor(boolean dark) {
            return dark
                    ? hsl(hue, clamp(saturation * 0.32f, 0.05f, 0.3f), 0.115f)
                    : hsl(hue, clamp(saturation * 0.20f, 0.04f, 0.25f), 0.955f);
        }

        /** Фон карточек разделов профиля. */
        public int getProfileCardColor(boolean dark) {
            return dark
                    ? hsl(hue, clamp(saturation * 0.28f, 0.04f, 0.26f), 0.165f)
                    : hsl(hue, clamp(saturation * 0.14f, 0.02f, 0.2f), 0.982f);
        }

        /** Верхняя область профиля: достаточно тёмная, чтобы белый текст читался. */
        public int getHeaderColor() {
            return hsl(hue, clamp(saturation * 0.55f, 0.12f, 0.55f), 0.215f);
        }

        public int getHeaderGradientTop() {
            return hsl(hue, clamp(saturation * 0.55f, 0.12f, 0.55f), 0.265f);
        }

        public int getHeaderGradientBottom() {
            return hsl(hue, clamp(saturation * 0.5f, 0.1f, 0.5f), 0.145f);
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
            }
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
