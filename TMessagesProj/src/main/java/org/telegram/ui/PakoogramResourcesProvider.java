package org.telegram.ui;

import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;

/**
 * Pakoogram: провайдер цветов, который перекрашивает переписку, профиль, медиа и
 * избранное в палитру текущего диалога.
 *
 * <p>Провайдер оборачивает родительский {@link Theme.ResourcesProvider}: всё, что
 * не перечислено ниже (текст, иконки, разделители, селекторы), остаётся тем же,
 * поэтому контраст интерфейса не ломается.</p>
 */
public final class PakoogramResourcesProvider implements Theme.ResourcesProvider {

    private final Theme.ResourcesProvider delegate;
    private final long dialogId;
    private PakoogramAvatarPalette.PaletteData palette;

    public PakoogramResourcesProvider(Theme.ResourcesProvider delegate, long dialogId) {
        this.delegate = delegate;
        this.dialogId = dialogId;
    }

    public long getDialogId() {
        return dialogId;
    }

    public PakoogramAvatarPalette.PaletteData getPalette() {
        return palette;
    }

    public void setPalette(PakoogramAvatarPalette.PaletteData palette) {
        this.palette = palette;
    }

    /** Совместимость со старым вызовом: тёмность берётся из самой палитры. */
    public void setPalette(PakoogramAvatarPalette.PaletteData palette, boolean dark) {
        this.palette = palette;
    }

    /**
     * Возвращает только переопределённый цвет или {@code 0}, если ключ должен
     * остаться родительским. Используется {@code ChatActivity.ThemeDelegate},
     * чтобы уже созданные view тоже получили палитру без пересоздания.
     */
    public int getOverrideColor(int key) {
        return resolve(key);
    }

    @Override
    public int getColor(int key) {
        final int override = resolve(key);
        return override != 0 ? override : delegateColor(key);
    }

    @Override
    public int getColorOrDefault(int key) {
        final int override = resolve(key);
        return override != 0 ? override : delegateColor(key);
    }

    @Override
    public int getCurrentColor(int key) {
        final int override = resolve(key);
        return override != 0 ? override : delegateColor(key);
    }

    private int delegateColor(int key) {
        return delegate != null ? delegate.getColor(key) : Theme.getColor(key);
    }

    private int resolve(int key) {
        final PakoogramAvatarPalette.PaletteData p = palette;
        if (p == null) {
            return 0;
        }
        final boolean dark = p.isDark();

        // ---------------------------------------------------------- пузыри
        if (key == Theme.key_chat_inBubble) {
            return p.getInBubbleColor(dark);
        }
        if (key == Theme.key_chat_inBubbleSelected) {
            return p.getInBubbleSelectedColor(dark);
        }
        if (key == Theme.key_chat_outBubble) {
            return p.getOutBubbleColor(dark);
        }
        if (key == Theme.key_chat_outBubbleSelected) {
            return p.getOutBubbleSelectedColor(dark);
        }
        if (key == Theme.key_chat_outBubbleGradient1) {
            return p.getOutBubbleGradient1(dark);
        }
        if (key == Theme.key_chat_outBubbleGradient2) {
            return p.getOutBubbleGradient2(dark);
        }
        if (key == Theme.key_chat_outBubbleGradient3) {
            return p.getOutBubbleGradient3(dark);
        }
        if (key == Theme.key_chat_messageTextIn) {
            return p.getInBubbleTextColor(dark);
        }
        if (key == Theme.key_chat_messageTextOut) {
            return p.getOutBubbleTextColor(dark);
        }
        if (key == Theme.key_chat_inTimeText || key == Theme.key_chat_inTimeSelectedText) {
            return p.getInBubbleSecondaryTextColor(dark);
        }
        if (key == Theme.key_chat_outTimeText || key == Theme.key_chat_outTimeSelectedText) {
            return p.getOutBubbleSecondaryTextColor(dark);
        }
        if (key == Theme.key_chat_inReplyLine) {
            return p.getInReplyLineColor(dark);
        }
        if (key == Theme.key_chat_outReplyLine || key == Theme.key_chat_outReplyLine2) {
            return p.getOutReplyLineColor(dark);
        }
        if (key == Theme.key_chat_inReplyNameText) {
            return p.getAccentColor();
        }
        if (key == Theme.key_chat_outReplyNameText) {
            return p.getOutBubbleTextColor(dark);
        }
        if (key == Theme.key_chat_serviceBackground
                || key == Theme.key_chat_serviceBackgroundSelected) {
            return p.getServiceBackgroundColor(dark);
        }

        // ------------------------------------------------ элементы чата
        if (key == Theme.key_actionBarDefault) {
            return p.getChatHeaderColor(dark);
        }
        if (key == Theme.key_chat_topPanelBackground) {
            return p.getGlassPanelColor(dark);
        }
        if (key == Theme.key_chat_messagePanelBackground) {
            return p.getMessagePanelColor(dark);
        }
        if (key == Theme.key_chat_messagePanelSend) {
            return p.getAccentColor();
        }
        if (key == Theme.key_chat_messagePanelText) {
            return p.getPanelTextColor(dark);
        }
        if (key == Theme.key_chat_messagePanelHint) {
            return Theme.multAlpha(p.getPanelTextColor(dark), 0.55f);
        }
        if (key == Theme.key_chat_messagePanelIcons) {
            return p.getPanelIconColor(dark);
        }

        // ------------------------------------------ шапка профиля и медиа
        if (key == Theme.key_actionBarDefaultTitle || key == Theme.key_actionBarDefaultIcon) {
            return p.getHeaderTextColor();
        }
        if (key == Theme.key_actionBarDefaultSubtitle) {
            return p.getHeaderSubtitleColor();
        }

        // ---------------------------------------------- профиль и медиа
        if (key == Theme.key_windowBackgroundGray) {
            return p.getProfileBackgroundColor(dark);
        }
        if (key == Theme.key_windowBackgroundWhite) {
            return p.getProfileCardColor(dark);
        }
        if (key == Theme.key_profile_tabSelectedLine || key == Theme.key_profile_tabSelectedText) {
            return p.getAccentColor();
        }
        if (key == Theme.key_profile_tabSelector) {
            return Theme.multAlpha(p.getAccentColor(), 0.16f);
        }
        if (key == Theme.key_profile_tabText) {
            return ColorUtils.blendARGB(Theme.getColor(key), p.getAccentColor(), dark ? 0.45f : 0.35f);
        }
        if (key == Theme.key_featuredStickers_addButton) {
            return p.getAccentColor();
        }
        if (key == Theme.key_featuredStickers_addButtonPressed) {
            return ColorUtils.blendARGB(p.getAccentColor(), 0xFF000000, 0.16f);
        }
        if (key == Theme.key_sharedMedia_startStopLoadIcon) {
            return p.getAccentColor();
        }
        if (key == Theme.key_sharedMedia_linkPlaceholder
                || key == Theme.key_sharedMedia_photoPlaceholder) {
            return p.getMediaCardColor(dark);
        }
        if (key == Theme.key_sharedMedia_linkPlaceholderText) {
            return ColorUtils.blendARGB(Theme.getColor(key), p.getAccentColor(), 0.35f);
        }
        return 0;
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
        return palette != null ? palette.isDark()
                : delegate != null ? delegate.isDark() : Theme.isCurrentThemeDark();
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
