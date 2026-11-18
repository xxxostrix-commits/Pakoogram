package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Pakoogram: настройки форка.
 *
 * <p>Значения хранятся в отдельном {@link SharedPreferences} файле {@code pakoogram_config},
 * поэтому они не пересекаются со стандартными preference-файлами Telegram.</p>
 *
 * <p>Все методы статические, чтение/запись идёт напрямую в SharedPreferences.</p>
 */
public class PakoogramConfig {

    /** Имя SharedPreferences-файла с настройками Pakoogram. */
    public static final String PREFS_NAME = "pakoogram_config";

    public static final String KEY_BLUR_INTENSITY = "blurIntensity";
    public static final String KEY_CORNER_RADIUS = "cornerRadius";
    public static final String KEY_SPRING_PHYSICS = "springPhysics";
    public static final String KEY_FLOATING_PANELS = "floatingPanels";
    public static final String KEY_GHOST_MODE = "ghostMode";
    /** Стеклянные карточки чатов в списке диалогов. */
    public static final String KEY_GLASS_CHAT_CARDS = "glassChatCards";
    /** Интенсивность градиента на карточках чатов, 0..100%. */
    public static final String KEY_CHAT_CARD_GRADIENT_INTENSITY = "chatCardGradientIntensity";
    /** Радиус скругления карточек чатов в dp. */
    public static final String KEY_CHAT_CARD_CORNER_RADIUS = "chatCardCornerRadius";
    /** Адаптивная тема чата и профиля по палитре аватарки собеседника. */
    public static final String KEY_ADAPTIVE_CHAT_THEME = "adaptiveChatTheme";
    /** Насыщенность градиента фона переписки, 0..100%. */
    public static final String KEY_CHAT_BACKGROUND_SATURATION = "chatBackgroundSaturation";
    /** Яркость свечения аватарки в профиле, 0..100%. */
    public static final String KEY_AVATAR_GLOW_BRIGHTNESS = "avatarGlowBrightness";
    /** Анимации переходов ColorOS: морфинг экранов чата и профиля из карточки/шапки. */
    public static final String KEY_TRANSITION_ANIMATIONS = "transitionAnimations";
    /** Пружинистая физика нажатий на карточки чатов и кнопки действий. */
    public static final String KEY_SPRING_PRESS = "springPress";
    /** Сила размытия уходящего на задний план экрана при переходах, 0..40. */
    public static final String KEY_TRANSITION_BLUR = "transitionBlur";
    /** Скорость анимаций переходов в процентах, по умолчанию {@value #DEFAULT_ANIMATION_SPEED} (1.0x). */
    public static final String KEY_ANIMATION_SPEED = "animationSpeed";

    public static final int DEFAULT_BLUR_INTENSITY = 85;
    public static final int DEFAULT_CORNER_RADIUS = 20;
    public static final boolean DEFAULT_SPRING_PHYSICS = true;
    public static final boolean DEFAULT_FLOATING_PANELS = true;
    public static final boolean DEFAULT_GHOST_MODE = false;
    public static final boolean DEFAULT_GLASS_CHAT_CARDS = true;
    /**
     * Прозрачность матовой стеклянной подложки карточки чата в списке диалогов.
     * Значение передаётся в {@link DreamGlassSurface#drawGlassCard}.
     */
    public static final float glassOpacity = 0.72f;
    public static final int DEFAULT_CHAT_CARD_GRADIENT_INTENSITY = 100;
    public static final int DEFAULT_CHAT_CARD_CORNER_RADIUS = 16;
    public static final boolean DEFAULT_ADAPTIVE_CHAT_THEME = true;
    public static final int DEFAULT_CHAT_BACKGROUND_SATURATION = 55;
    public static final int DEFAULT_AVATAR_GLOW_BRIGHTNESS = 70;
    public static final boolean DEFAULT_TRANSITION_ANIMATIONS = true;
    public static final boolean DEFAULT_SPRING_PRESS = true;
    public static final int DEFAULT_TRANSITION_BLUR = 26;
    /** Множитель скорости анимаций по умолчанию: строго 1.0x. */
    public static final float DEFAULT_ANIMATION_SPEED_FACTOR = 1.0f;
    /** Скорость анимаций по умолчанию в процентах (100% = 1.0x). */
    public static final int DEFAULT_ANIMATION_SPEED = (int) (DEFAULT_ANIMATION_SPEED_FACTOR * 100f);

    public static final int MIN_BLUR_INTENSITY = 0;
    public static final int MAX_BLUR_INTENSITY = 100;
    public static final int MIN_CORNER_RADIUS = 0;
    public static final int MAX_CORNER_RADIUS = 40;
    public static final int MIN_CHAT_CARD_GRADIENT_INTENSITY = 0;
    public static final int MAX_CHAT_CARD_GRADIENT_INTENSITY = 100;
    public static final int MIN_CHAT_CARD_CORNER_RADIUS = 0;
    public static final int MAX_CHAT_CARD_CORNER_RADIUS = 24;
    public static final int MIN_CHAT_BACKGROUND_SATURATION = 0;
    public static final int MAX_CHAT_BACKGROUND_SATURATION = 100;
    public static final int MIN_AVATAR_GLOW_BRIGHTNESS = 0;
    public static final int MAX_AVATAR_GLOW_BRIGHTNESS = 100;
    public static final int MIN_TRANSITION_BLUR = 0;
    public static final int MAX_TRANSITION_BLUR = 40;
    /** Минимальный множитель скорости анимаций: 0.5x. */
    public static final float MIN_ANIMATION_SPEED_FACTOR = 0.5f;
    /** Максимальный множитель скорости анимаций: 1.5x. */
    public static final float MAX_ANIMATION_SPEED_FACTOR = 1.5f;
    /** Минимальная скорость анимаций в процентах (50% = 0.5x). */
    public static final int MIN_ANIMATION_SPEED = (int) (MIN_ANIMATION_SPEED_FACTOR * 100f);
    /** Максимальная скорость анимаций в процентах (150% = 1.5x). */
    public static final int MAX_ANIMATION_SPEED = (int) (MAX_ANIMATION_SPEED_FACTOR * 100f);
    /** Шаг ползунка «Скорость анимаций» в процентах: 10% = 0.1x. */
    public static final int ANIMATION_SPEED_STEP = 10;

    private static SharedPreferences preferences;

    private PakoogramConfig() {
        // утилитарный класс
    }

    private static synchronized SharedPreferences getPreferences() {
        if (preferences == null) {
            Context context = ApplicationLoader.applicationContext;
            preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        return preferences;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    /* ------------------------------------------------------------ blurIntensity */

    /**
     * @deprecated Дублирующая настройка «Интенсивность размытия» убрана из экрана настроек.
     * Метод оставлен как алиас на основную настройку {@link #getTransitionBlurStrength()}:
     * значение переводится из шкалы 0..100% в шкалу
     * [{@value #MIN_TRANSITION_BLUR}; {@value #MAX_TRANSITION_BLUR}].
     */
    @Deprecated
    public static int getBlurIntensity() {
        return Math.round(getTransitionBlurStrength() * 100f / MAX_TRANSITION_BLUR);
    }

    /**
     * @deprecated Дублирующая настройка «Интенсивность размытия» убрана из экрана настроек.
     * Метод оставлен как алиас на основную настройку {@link #setTransitionBlurStrength(int)}.
     * @param value процент размытия, зажимается в [{@value #MIN_BLUR_INTENSITY}; {@value #MAX_BLUR_INTENSITY}]
     */
    @Deprecated
    public static void setBlurIntensity(int value) {
        int percent = clamp(value, MIN_BLUR_INTENSITY, MAX_BLUR_INTENSITY);
        setTransitionBlurStrength(Math.round(percent * (float) MAX_TRANSITION_BLUR / 100f));
    }

    /* ------------------------------------------------------------ cornerRadius */

    /**
     * @deprecated Дублирующая настройка «Радиус скругления» убрана из экрана настроек.
     * Метод оставлен как алиас на основную настройку {@link #getChatCardCornerRadius()}.
     */
    @Deprecated
    public static int getCornerRadius() {
        return getChatCardCornerRadius();
    }

    /**
     * @deprecated Дублирующая настройка «Радиус скругления» убрана из экрана настроек.
     * Метод оставлен как алиас на основную настройку {@link #setChatCardCornerRadius(int)}.
     * @param value радиус в dp, зажимается в [{@value #MIN_CHAT_CARD_CORNER_RADIUS}; {@value #MAX_CHAT_CARD_CORNER_RADIUS}]
     */
    @Deprecated
    public static void setCornerRadius(int value) {
        setChatCardCornerRadius(value);
    }

    /* ------------------------------------------------------------ springPhysics */

    /**
     * @deprecated Дублирующая настройка «Пружинная анимация» убрана из экрана настроек.
     * Метод оставлен как алиас на основную настройку {@link #isSpringPressEnabled()}.
     */
    @Deprecated
    public static boolean isSpringPhysicsEnabled() {
        return isSpringPressEnabled();
    }

    /**
     * @deprecated Дублирующая настройка «Пружинная анимация» убрана из экрана настроек.
     * Метод оставлен как алиас на основную настройку {@link #setSpringPressEnabled(boolean)}.
     */
    @Deprecated
    public static void setSpringPhysicsEnabled(boolean enabled) {
        setSpringPressEnabled(enabled);
    }

    /** Алиас для {@link #setSpringPhysicsEnabled(boolean)}. */
    public static void setSpringPhysics(boolean enabled) {
        setSpringPhysicsEnabled(enabled);
    }

    /** Алиас для {@link #isSpringPhysicsEnabled()}. */
    public static boolean getSpringPhysics() {
        return isSpringPhysicsEnabled();
    }

    /* ----------------------------------------------------------- floatingPanels */

    /** «Плавающие» панели (floating panels), по умолчанию {@value #DEFAULT_FLOATING_PANELS}. */
    public static boolean isFloatingPanelsEnabled() {
        return getPreferences().getBoolean(KEY_FLOATING_PANELS, DEFAULT_FLOATING_PANELS);
    }

    public static void setFloatingPanelsEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_FLOATING_PANELS, enabled).apply();
    }

    /** Алиас для {@link #setFloatingPanelsEnabled(boolean)}. */
    public static void setFloatingPanels(boolean enabled) {
        setFloatingPanelsEnabled(enabled);
    }

    /** Алиас для {@link #isFloatingPanelsEnabled()}. */
    public static boolean getFloatingPanels() {
        return isFloatingPanelsEnabled();
    }

    /* ---------------------------------------------------------------- ghostMode */

    /** Режим «призрака» (ghost mode), по умолчанию {@value #DEFAULT_GHOST_MODE}. */
    public static boolean isGhostModeEnabled() {
        return getPreferences().getBoolean(KEY_GHOST_MODE, DEFAULT_GHOST_MODE);
    }

    public static void setGhostModeEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_GHOST_MODE, enabled).apply();
    }

    /** Алиас для {@link #setGhostModeEnabled(boolean)}. */
    public static void setGhostMode(boolean enabled) {
        setGhostModeEnabled(enabled);
    }

    /** Алиас для {@link #isGhostModeEnabled()}. */
    public static boolean getGhostMode() {
        return isGhostModeEnabled();
    }

    /* ------------------------------------------------------- glassChatCards */

    /** Стеклянные карточки чатов в списке диалогов, по умолчанию {@value #DEFAULT_GLASS_CHAT_CARDS}. */
    public static boolean isGlassChatCardsEnabled() {
        return getPreferences().getBoolean(KEY_GLASS_CHAT_CARDS, DEFAULT_GLASS_CHAT_CARDS);
    }

    public static void setGlassChatCardsEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_GLASS_CHAT_CARDS, enabled).apply();
    }

    /* ------------------------------------------- chatCardGradientIntensity */

    /** Интенсивность градиента на карточках чатов в процентах, по умолчанию {@value #DEFAULT_CHAT_CARD_GRADIENT_INTENSITY}. */
    public static int getChatCardGradientIntensity() {
        return clamp(getPreferences().getInt(KEY_CHAT_CARD_GRADIENT_INTENSITY, DEFAULT_CHAT_CARD_GRADIENT_INTENSITY),
                MIN_CHAT_CARD_GRADIENT_INTENSITY, MAX_CHAT_CARD_GRADIENT_INTENSITY);
    }

    /** @param value процент градиента, зажимается в [{@value #MIN_CHAT_CARD_GRADIENT_INTENSITY}; {@value #MAX_CHAT_CARD_GRADIENT_INTENSITY}] */
    public static void setChatCardGradientIntensity(int value) {
        getPreferences().edit()
                .putInt(KEY_CHAT_CARD_GRADIENT_INTENSITY, clamp(value,
                        MIN_CHAT_CARD_GRADIENT_INTENSITY, MAX_CHAT_CARD_GRADIENT_INTENSITY))
                .apply();
    }

    /* ------------------------------------------------ chatCardCornerRadius */

    /** Радиус скругления карточек чатов в dp, по умолчанию {@value #DEFAULT_CHAT_CARD_CORNER_RADIUS}. */
    public static int getChatCardCornerRadius() {
        return clamp(getPreferences().getInt(KEY_CHAT_CARD_CORNER_RADIUS, DEFAULT_CHAT_CARD_CORNER_RADIUS),
                MIN_CHAT_CARD_CORNER_RADIUS, MAX_CHAT_CARD_CORNER_RADIUS);
    }

    /** @param value радиус в dp, зажимается в [{@value #MIN_CHAT_CARD_CORNER_RADIUS}; {@value #MAX_CHAT_CARD_CORNER_RADIUS}] */
    public static void setChatCardCornerRadius(int value) {
        getPreferences().edit()
                .putInt(KEY_CHAT_CARD_CORNER_RADIUS, clamp(value,
                        MIN_CHAT_CARD_CORNER_RADIUS, MAX_CHAT_CARD_CORNER_RADIUS))
                .apply();
    }

    /* ------------------------------------------------------ adaptiveChatTheme */

    /** Адаптивная тема чата и профиля, по умолчанию {@value #DEFAULT_ADAPTIVE_CHAT_THEME}. */
    public static boolean isAdaptiveChatThemeEnabled() {
        return getPreferences().getBoolean(KEY_ADAPTIVE_CHAT_THEME, DEFAULT_ADAPTIVE_CHAT_THEME);
    }

    public static void setAdaptiveChatThemeEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_ADAPTIVE_CHAT_THEME, enabled).apply();
    }

    /* ------------------------------------------------ chatBackgroundSaturation */

    /** Насыщенность фона переписки в процентах, по умолчанию {@value #DEFAULT_CHAT_BACKGROUND_SATURATION}. */
    public static int getChatBackgroundSaturation() {
        return clamp(getPreferences().getInt(KEY_CHAT_BACKGROUND_SATURATION, DEFAULT_CHAT_BACKGROUND_SATURATION),
                MIN_CHAT_BACKGROUND_SATURATION, MAX_CHAT_BACKGROUND_SATURATION);
    }

    /** @param value процент насыщенности, зажимается в [{@value #MIN_CHAT_BACKGROUND_SATURATION}; {@value #MAX_CHAT_BACKGROUND_SATURATION}] */
    public static void setChatBackgroundSaturation(int value) {
        getPreferences().edit()
                .putInt(KEY_CHAT_BACKGROUND_SATURATION, clamp(value,
                        MIN_CHAT_BACKGROUND_SATURATION, MAX_CHAT_BACKGROUND_SATURATION))
                .apply();
    }

    /* ----------------------------------------------------- avatarGlowBrightness */

    /** Яркость свечения аватарки в процентах, по умолчанию {@value #DEFAULT_AVATAR_GLOW_BRIGHTNESS}. */
    public static int getAvatarGlowBrightness() {
        return clamp(getPreferences().getInt(KEY_AVATAR_GLOW_BRIGHTNESS, DEFAULT_AVATAR_GLOW_BRIGHTNESS),
                MIN_AVATAR_GLOW_BRIGHTNESS, MAX_AVATAR_GLOW_BRIGHTNESS);
    }

    /**
     * Сила градиентного свечения карточки чата в диапазоне {@code 0..1}.
     * Является производной от настройки яркости свечения аватарки, поэтому
     * ползунок «Яркость свечения аватарки» управляет и карточками диалогов.
     */
    public static float avatarGlowIntensity() {
        return getAvatarGlowBrightness() / 100f;
    }

    /** @param value процент яркости, зажимается в [{@value #MIN_AVATAR_GLOW_BRIGHTNESS}; {@value #MAX_AVATAR_GLOW_BRIGHTNESS}] */
    public static void setAvatarGlowBrightness(int value) {
        getPreferences().edit()
                .putInt(KEY_AVATAR_GLOW_BRIGHTNESS, clamp(value,
                        MIN_AVATAR_GLOW_BRIGHTNESS, MAX_AVATAR_GLOW_BRIGHTNESS))
                .apply();
    }

    /* ------------------------------------------------------ transitionAnimations */

    /** Анимации переходов ColorOS, по умолчанию {@value #DEFAULT_TRANSITION_ANIMATIONS}. */
    public static boolean isTransitionAnimationsEnabled() {
        return getPreferences().getBoolean(KEY_TRANSITION_ANIMATIONS, DEFAULT_TRANSITION_ANIMATIONS);
    }

    public static void setTransitionAnimationsEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_TRANSITION_ANIMATIONS, enabled).apply();
    }

    /* -------------------------------------------------------------- springPress */

    /** Пружинистая физика нажатий, по умолчанию {@value #DEFAULT_SPRING_PRESS}. */
    public static boolean isSpringPressEnabled() {
        return getPreferences().getBoolean(KEY_SPRING_PRESS, DEFAULT_SPRING_PRESS);
    }

    public static void setSpringPressEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_SPRING_PRESS, enabled).apply();
    }

    /* ----------------------------------------------------------- transitionBlur */

    /** Сила размытия фона при переходах, по умолчанию {@value #DEFAULT_TRANSITION_BLUR}. */
    public static int getTransitionBlurStrength() {
        return clamp(getPreferences().getInt(KEY_TRANSITION_BLUR, DEFAULT_TRANSITION_BLUR),
                MIN_TRANSITION_BLUR, MAX_TRANSITION_BLUR);
    }

    /** @param value сила размытия, зажимается в [{@value #MIN_TRANSITION_BLUR}; {@value #MAX_TRANSITION_BLUR}] */
    public static void setTransitionBlurStrength(int value) {
        getPreferences().edit()
                .putInt(KEY_TRANSITION_BLUR, clamp(value, MIN_TRANSITION_BLUR, MAX_TRANSITION_BLUR))
                .apply();
    }

    /* ----------------------------------------------------------- animationSpeed */

    /** Скорость анимаций в процентах (50..150), по умолчанию {@value #DEFAULT_ANIMATION_SPEED} (1.0x). */
    public static int getAnimationSpeedPercent() {
        int stored = getPreferences().getInt(KEY_ANIMATION_SPEED, DEFAULT_ANIMATION_SPEED);
        return normalizeAnimationSpeedPercent(stored);
    }

    /** @param value скорость в процентах, зажимается в [{@value #MIN_ANIMATION_SPEED}; {@value #MAX_ANIMATION_SPEED}] */
    public static void setAnimationSpeedPercent(int value) {
        getPreferences().edit()
                .putInt(KEY_ANIMATION_SPEED, normalizeAnimationSpeedPercent(value))
                .apply();
    }

    /**
     * Устанавливает множитель скорости анимаций.
     *
     * @param factor множитель, зажимается в [{@value #MIN_ANIMATION_SPEED_FACTOR};
     *               {@value #MAX_ANIMATION_SPEED_FACTOR}] и округляется до шага 0.1x
     */
    public static void setAnimationSpeed(float factor) {
        float clamped = Math.max(MIN_ANIMATION_SPEED_FACTOR,
                Math.min(MAX_ANIMATION_SPEED_FACTOR, factor));
        setAnimationSpeedPercent(Math.round(clamped * 100f));
    }

    /** Множитель скорости анимаций: {@code 0.5f}..{@code 1.5f}, по умолчанию {@code 1.0f}. */
    public static float getAnimationSpeed() {
        return getAnimationSpeedPercent() / 100f;
    }

    /**
     * Зажимает процент скорости в [{@value #MIN_ANIMATION_SPEED}; {@value #MAX_ANIMATION_SPEED}]
     * и выравнивает по шагу ползунка {@value #ANIMATION_SPEED_STEP} (10% = 0.1x).
     */
    private static int normalizeAnimationSpeedPercent(int percent) {
        int clamped = clamp(percent, MIN_ANIMATION_SPEED, MAX_ANIMATION_SPEED);
        int steps = Math.round((clamped - MIN_ANIMATION_SPEED) / (float) ANIMATION_SPEED_STEP);
        int snapped = MIN_ANIMATION_SPEED + steps * ANIMATION_SPEED_STEP;
        return clamp(snapped, MIN_ANIMATION_SPEED, MAX_ANIMATION_SPEED);
    }

    /* -------------------------------------------------------------------- сброс */

    /** Сбрасывает все настройки Pakoogram к значениям по умолчанию. */
    public static void resetToDefaults() {
        // KEY_BLUR_INTENSITY, KEY_CORNER_RADIUS и KEY_SPRING_PHYSICS — legacy-ключи
        // удалённых дублирующих пунктов; пишем их для совместимости со старыми сборками.
        getPreferences().edit()
                .putInt(KEY_BLUR_INTENSITY, DEFAULT_BLUR_INTENSITY)
                .putInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS)
                .putBoolean(KEY_SPRING_PHYSICS, DEFAULT_SPRING_PHYSICS)
                .putBoolean(KEY_FLOATING_PANELS, DEFAULT_FLOATING_PANELS)
                .putBoolean(KEY_GHOST_MODE, DEFAULT_GHOST_MODE)
                .putBoolean(KEY_GLASS_CHAT_CARDS, DEFAULT_GLASS_CHAT_CARDS)
                .putInt(KEY_CHAT_CARD_GRADIENT_INTENSITY, DEFAULT_CHAT_CARD_GRADIENT_INTENSITY)
                .putInt(KEY_CHAT_CARD_CORNER_RADIUS, DEFAULT_CHAT_CARD_CORNER_RADIUS)
                .putBoolean(KEY_ADAPTIVE_CHAT_THEME, DEFAULT_ADAPTIVE_CHAT_THEME)
                .putInt(KEY_CHAT_BACKGROUND_SATURATION, DEFAULT_CHAT_BACKGROUND_SATURATION)
                .putInt(KEY_AVATAR_GLOW_BRIGHTNESS, DEFAULT_AVATAR_GLOW_BRIGHTNESS)
                .putBoolean(KEY_TRANSITION_ANIMATIONS, DEFAULT_TRANSITION_ANIMATIONS)
                .putBoolean(KEY_SPRING_PRESS, DEFAULT_SPRING_PRESS)
                .putInt(KEY_TRANSITION_BLUR, DEFAULT_TRANSITION_BLUR)
                .putInt(KEY_ANIMATION_SPEED, DEFAULT_ANIMATION_SPEED)
                .apply();
    }
}
