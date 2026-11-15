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

    public static final int DEFAULT_BLUR_INTENSITY = 85;
    public static final int DEFAULT_CORNER_RADIUS = 20;
    public static final boolean DEFAULT_SPRING_PHYSICS = true;
    public static final boolean DEFAULT_FLOATING_PANELS = true;
    public static final boolean DEFAULT_GHOST_MODE = false;

    public static final int MIN_BLUR_INTENSITY = 0;
    public static final int MAX_BLUR_INTENSITY = 100;
    public static final int MIN_CORNER_RADIUS = 0;
    public static final int MAX_CORNER_RADIUS = 40;

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

    /** Интенсивность размытия фона в процентах, по умолчанию {@value #DEFAULT_BLUR_INTENSITY}. */
    public static int getBlurIntensity() {
        return clamp(getPreferences().getInt(KEY_BLUR_INTENSITY, DEFAULT_BLUR_INTENSITY),
                MIN_BLUR_INTENSITY, MAX_BLUR_INTENSITY);
    }

    /** @param value процент размытия, зажимается в [{@value #MIN_BLUR_INTENSITY}; {@value #MAX_BLUR_INTENSITY}] */
    public static void setBlurIntensity(int value) {
        getPreferences().edit()
                .putInt(KEY_BLUR_INTENSITY, clamp(value, MIN_BLUR_INTENSITY, MAX_BLUR_INTENSITY))
                .apply();
    }

    /* ------------------------------------------------------------ cornerRadius */

    /** Радиус скругления элементов интерфейса в dp, по умолчанию {@value #DEFAULT_CORNER_RADIUS}. */
    public static int getCornerRadius() {
        return clamp(getPreferences().getInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS),
                MIN_CORNER_RADIUS, MAX_CORNER_RADIUS);
    }

    /** @param value радиус в dp, зажимается в [{@value #MIN_CORNER_RADIUS}; {@value #MAX_CORNER_RADIUS}] */
    public static void setCornerRadius(int value) {
        getPreferences().edit()
                .putInt(KEY_CORNER_RADIUS, clamp(value, MIN_CORNER_RADIUS, MAX_CORNER_RADIUS))
                .apply();
    }

    /* ------------------------------------------------------------ springPhysics */

    /** Пружинная (spring) анимация панелей, по умолчанию {@value #DEFAULT_SPRING_PHYSICS}. */
    public static boolean isSpringPhysicsEnabled() {
        return getPreferences().getBoolean(KEY_SPRING_PHYSICS, DEFAULT_SPRING_PHYSICS);
    }

    public static void setSpringPhysicsEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(KEY_SPRING_PHYSICS, enabled).apply();
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

    /* -------------------------------------------------------------------- сброс */

    /** Сбрасывает все настройки Pakoogram к значениям по умолчанию. */
    public static void resetToDefaults() {
        getPreferences().edit()
                .putInt(KEY_BLUR_INTENSITY, DEFAULT_BLUR_INTENSITY)
                .putInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS)
                .putBoolean(KEY_SPRING_PHYSICS, DEFAULT_SPRING_PHYSICS)
                .putBoolean(KEY_FLOATING_PANELS, DEFAULT_FLOATING_PANELS)
                .putBoolean(KEY_GHOST_MODE, DEFAULT_GHOST_MODE)
                .apply();
    }
}
