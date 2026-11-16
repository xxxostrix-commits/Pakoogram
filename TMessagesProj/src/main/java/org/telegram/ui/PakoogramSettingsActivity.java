package org.telegram.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.Locale;

/**
 * Pakoogram: экран настроек форка.
 *
 * <p>Класс наследуется от {@link BaseFragment} (это фрагмент Telegram-навигации, несмотря
 * на суффикс {@code Activity} в имени) и открывается через
 * {@code presentFragment(new PakoogramSettingsActivity())}.</p>
 *
 * <p>Экран построен на стандартных элементах Telegram: {@link RecyclerListView},
 * {@link HeaderCell}, {@link TextCheckCell} и {@link TextSettingsCell}. Числовые параметры
 * настраиваются ползунками {@link PakoogramSliderCell}, булевы переключаются сразу.</p>
 */
public class PakoogramSettingsActivity extends BaseFragment {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_CHECK = 1;
    private static final int VIEW_TYPE_SETTINGS = 2;
    private static final int VIEW_TYPE_SLIDER = 3;

    private static final int ROW_HEADER_APPEARANCE = 0;
    private static final int ROW_GLASS_CHAT_CARDS = 1;
    private static final int ROW_CHAT_CARD_GRADIENT = 2;
    private static final int ROW_CHAT_CARD_CORNER_RADIUS = 3;
    private static final int ROW_HEADER_INTERFACE = 4;
    private static final int ROW_TRANSITION_ANIMATIONS = 5;
    private static final int ROW_SPRING_PRESS = 6;
    private static final int ROW_TRANSITION_BLUR = 7;
    private static final int ROW_ANIMATION_SPEED = 8;
    private static final int ROW_FLOATING_PANELS = 9;
    private static final int ROW_GHOST_MODE = 10;
    private static final int ROW_ADAPTIVE_CHAT_THEME = 11;
    private static final int ROW_CHAT_BACKGROUND_SATURATION = 12;
    private static final int ROW_AVATAR_GLOW_BRIGHTNESS = 13;
    private static final int ROW_HEADER_ABOUT = 14;
    private static final int ROW_COUNT = 15;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackArrowDrawable(Theme.getColor(Theme.key_actionBarDefaultIcon)));
        actionBar.setTitle("Настройки Pakoogram");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment(true);
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter = new ListAdapter(context));
        listView.setOnItemClickListener(new RecyclerListView.OnItemClickListener() {
            @Override
            public void onItemClick(View view, int position) {
                onRowClick(view, position);
            }
        });
        frameLayout.addView(listView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void onRowClick(View view, int position) {
        switch (position) {
            case ROW_GLASS_CHAT_CARDS: {
                boolean enabled = !PakoogramConfig.isGlassChatCardsEnabled();
                PakoogramConfig.setGlassChatCardsEnabled(enabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(enabled);
                }
                notifyDialogsListChanged();
                break;
            }
            case ROW_ADAPTIVE_CHAT_THEME: {
                boolean enabled = !PakoogramConfig.isAdaptiveChatThemeEnabled();
                PakoogramConfig.setAdaptiveChatThemeEnabled(enabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(enabled);
                }
                notifyDialogsListChanged();
                break;
            }
            case ROW_TRANSITION_ANIMATIONS: {
                boolean enabled = !PakoogramConfig.isTransitionAnimationsEnabled();
                PakoogramConfig.setTransitionAnimationsEnabled(enabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(enabled);
                }
                break;
            }
            case ROW_SPRING_PRESS: {
                boolean enabled = !PakoogramConfig.isSpringPressEnabled();
                PakoogramConfig.setSpringPressEnabled(enabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(enabled);
                }
                break;
            }
            case ROW_FLOATING_PANELS: {
                boolean enabled = !PakoogramConfig.isFloatingPanelsEnabled();
                PakoogramConfig.setFloatingPanelsEnabled(enabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(enabled);
                }
                break;
            }
            case ROW_GHOST_MODE: {
                boolean enabled = !PakoogramConfig.isGhostModeEnabled();
                PakoogramConfig.setGhostModeEnabled(enabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(enabled);
                }
                break;
            }
            default:
                break;
        }
    }

    private interface IntValueListener {
        void onValueSelected(int value);
    }

    /** Pakoogram: просит список диалогов перерисоваться после изменения внешнего вида карточек. */
    private static void notifyDialogsListChanged() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public int getItemViewType(int position) {
            switch (position) {
                case ROW_HEADER_APPEARANCE:
                case ROW_HEADER_INTERFACE:
                case ROW_HEADER_ABOUT:
                    return VIEW_TYPE_HEADER;
                case ROW_FLOATING_PANELS:
                case ROW_GHOST_MODE:
                case ROW_GLASS_CHAT_CARDS:
                case ROW_ADAPTIVE_CHAT_THEME:
                case ROW_TRANSITION_ANIMATIONS:
                case ROW_SPRING_PRESS:
                    return VIEW_TYPE_CHECK;
                case ROW_CHAT_CARD_GRADIENT:
                case ROW_CHAT_CARD_CORNER_RADIUS:
                case ROW_CHAT_BACKGROUND_SATURATION:
                case ROW_AVATAR_GLOW_BRIGHTNESS:
                case ROW_TRANSITION_BLUR:
                case ROW_ANIMATION_SPEED:
                    return VIEW_TYPE_SLIDER;
                default:
                    return VIEW_TYPE_SETTINGS;
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == VIEW_TYPE_CHECK || type == VIEW_TYPE_SETTINGS;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    break;
                case VIEW_TYPE_CHECK:
                    view = new TextCheckCell(mContext);
                    break;
                case VIEW_TYPE_SETTINGS:
                    view = new TextSettingsCell(mContext);
                    break;
                case VIEW_TYPE_SLIDER:
                    view = new PakoogramSliderCell(mContext);
                    break;
                default:
                    view = new TextSettingsCell(mContext);
                    break;
            }
            view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    headerCell.setText(position == ROW_HEADER_APPEARANCE
                            ? "Оформление"
                            : position == ROW_HEADER_INTERFACE ? "Интерфейс" : "О программе");
                    break;
                }
                case VIEW_TYPE_CHECK: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    if (position == ROW_GLASS_CHAT_CARDS) {
                        checkCell.setTextAndCheck("Стеклянные карточки чатов",
                                PakoogramConfig.isGlassChatCardsEnabled(), false);
                    } else if (position == ROW_ADAPTIVE_CHAT_THEME) {
                        checkCell.setTextAndCheck("Адаптивная тема чата и профиля",
                                PakoogramConfig.isAdaptiveChatThemeEnabled(), false);
                    } else if (position == ROW_TRANSITION_ANIMATIONS) {
                        checkCell.setTextAndCheck("Анимации переходов ColorOS",
                                PakoogramConfig.isTransitionAnimationsEnabled(), false);
                    } else if (position == ROW_SPRING_PRESS) {
                        checkCell.setTextAndCheck("Пружинистая физика нажатий",
                                PakoogramConfig.isSpringPressEnabled(), false);
                    } else if (position == ROW_FLOATING_PANELS) {
                        checkCell.setTextAndCheck("Плавающие панели",
                                PakoogramConfig.isFloatingPanelsEnabled(), false);
                    } else {
                        checkCell.setTextAndCheck("Режим призрака",
                                PakoogramConfig.isGhostModeEnabled(), true);
                    }
                    break;
                }
                case VIEW_TYPE_SLIDER: {
                    PakoogramSliderCell sliderCell = (PakoogramSliderCell) holder.itemView;
                    if (position == ROW_CHAT_CARD_GRADIENT) {
                        sliderCell.setContent("Интенсивность градиента", "%",
                                PakoogramConfig.MIN_CHAT_CARD_GRADIENT_INTENSITY,
                                PakoogramConfig.MAX_CHAT_CARD_GRADIENT_INTENSITY,
                                PakoogramConfig.getChatCardGradientIntensity(),
                                new IntValueListener() {
                                    @Override
                                    public void onValueSelected(int value) {
                                        PakoogramConfig.setChatCardGradientIntensity(value);
                                    }
                                },
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        notifyDialogsListChanged();
                                    }
                                });
                    } else if (position == ROW_CHAT_CARD_CORNER_RADIUS) {
                        sliderCell.setContent("Скругление карточек", " dp",
                                PakoogramConfig.MIN_CHAT_CARD_CORNER_RADIUS,
                                PakoogramConfig.MAX_CHAT_CARD_CORNER_RADIUS,
                                PakoogramConfig.getChatCardCornerRadius(),
                                new IntValueListener() {
                                    @Override
                                    public void onValueSelected(int value) {
                                        PakoogramConfig.setChatCardCornerRadius(value);
                                    }
                                },
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        notifyDialogsListChanged();
                                    }
                                });
                    } else if (position == ROW_CHAT_BACKGROUND_SATURATION) {
                        sliderCell.setContent("Насыщенность фона чата", "%",
                                PakoogramConfig.MIN_CHAT_BACKGROUND_SATURATION,
                                PakoogramConfig.MAX_CHAT_BACKGROUND_SATURATION,
                                PakoogramConfig.getChatBackgroundSaturation(),
                                new IntValueListener() {
                                    @Override
                                    public void onValueSelected(int value) {
                                        PakoogramConfig.setChatBackgroundSaturation(value);
                                    }
                                },
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        notifyDialogsListChanged();
                                    }
                                });
                    } else if (position == ROW_TRANSITION_BLUR) {
                        sliderCell.setContent("Сила размытия фона", "",
                                PakoogramConfig.MIN_TRANSITION_BLUR,
                                PakoogramConfig.MAX_TRANSITION_BLUR,
                                PakoogramConfig.getTransitionBlurStrength(),
                                new IntValueListener() {
                                    @Override
                                    public void onValueSelected(int value) {
                                        PakoogramConfig.setTransitionBlurStrength(value);
                                    }
                                },
                                null);
                    } else if (position == ROW_ANIMATION_SPEED) {
                        sliderCell.setContent("Скорость анимаций", "x",
                                PakoogramConfig.MIN_ANIMATION_SPEED,
                                PakoogramConfig.MAX_ANIMATION_SPEED,
                                PakoogramConfig.getAnimationSpeedPercent(),
                                PakoogramConfig.ANIMATION_SPEED_STEP,
                                new IntValueListener() {
                                    @Override
                                    public void onValueSelected(int value) {
                                        PakoogramConfig.setAnimationSpeedPercent(value);
                                    }
                                },
                                null,
                                new PakoogramSliderCell.ValueFormatter() {
                                    @Override
                                    public String format(int value) {
                                        return String.format(Locale.US, "%.1fx", value / 100f);
                                    }
                                });
                    } else {
                        sliderCell.setContent("Яркость свечения аватарки", "%",
                                PakoogramConfig.MIN_AVATAR_GLOW_BRIGHTNESS,
                                PakoogramConfig.MAX_AVATAR_GLOW_BRIGHTNESS,
                                PakoogramConfig.getAvatarGlowBrightness(),
                                new IntValueListener() {
                                    @Override
                                    public void onValueSelected(int value) {
                                        PakoogramConfig.setAvatarGlowBrightness(value);
                                    }
                                },
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        notifyDialogsListChanged();
                                    }
                                });
                    }
                    break;
                }
                case VIEW_TYPE_SETTINGS:
                default: {
                    TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                    settingsCell.setTextAndValue("Версия Pakoogram", "1.0", false);
                    break;
                }
            }
        }
    }

    /**
     * Pakoogram: строка настроек с ползунком.
     *
     * <p>Показывает название параметра и его текущее значение, а под ними — {@link SeekBar}.
     * Значение сохраняется в {@link PakoogramConfig} сразу при перетаскивании, поэтому
     * список диалогов обновляется в реальном времени.</p>
     */
    private static class PakoogramSliderCell extends LinearLayout {

        private final TextView titleView;
        private final TextView valueView;
        private final SeekBar seekBar;
        private IntValueListener listener;
        private Runnable onTrackingStopped;
        private ValueFormatter valueFormatter;
        private String valueSuffix = "";
        private int minValue;
        private int maxValue = 100;
        private int stepValue = 1;
        private boolean ignoreProgress;

        /** Pakoogram: форматирование значения ползунка (например, {@code 1.0x} для скорости). */
        interface ValueFormatter {
            String format(int value);
        }

        PakoogramSliderCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(8), AndroidUtilities.dp(21), AndroidUtilities.dp(2));

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(HORIZONTAL);
            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            header.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

            valueView = new TextView(context);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
            header.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            seekBar = new SeekBar(context);
            if (Build.VERSION.SDK_INT >= 21) {
                final int accentColor = Theme.getColor(Theme.key_switchTrackChecked);
                seekBar.setProgressTintList(ColorStateList.valueOf(accentColor));
                seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(Theme.getColor(Theme.key_switchTrack)));
                seekBar.setThumbTintList(ColorStateList.valueOf(accentColor));
            }
            addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    final int value = getValue(progress);
                    valueView.setText(formatValue(value));
                    if (fromUser && !ignoreProgress && listener != null) {
                        listener.onValueSelected(value);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    if (onTrackingStopped != null) {
                        onTrackingStopped.run();
                    }
                }
            });
        }

        void setContent(String title, String suffix, int min, int max, int value,
                        IntValueListener listener, Runnable onTrackingStopped) {
            setContent(title, suffix, min, max, value, 1, listener, onTrackingStopped, null);
        }

        void setContent(String title, String suffix, int min, int max, int value,
                        IntValueListener listener, Runnable onTrackingStopped, ValueFormatter formatter) {
            setContent(title, suffix, min, max, value, 1, listener, onTrackingStopped, formatter);
        }

        /**
         * @param step шаг ползунка в единицах значения (например, {@code 10} для 0.1x
         *             при хранении скорости анимаций в процентах)
         */
        void setContent(String title, String suffix, int min, int max, int value, int step,
                        IntValueListener listener, Runnable onTrackingStopped, ValueFormatter formatter) {
            this.listener = listener;
            this.onTrackingStopped = onTrackingStopped;
            this.valueFormatter = formatter;
            this.valueSuffix = suffix == null ? "" : suffix;
            this.stepValue = Math.max(1, step);
            this.minValue = min;
            this.maxValue = Math.max(min + this.stepValue, max);

            final int currentValue = clampValue(value);
            ignoreProgress = true;
            seekBar.setMax((this.maxValue - this.minValue) / this.stepValue);
            seekBar.setProgress(progressForValue(currentValue));
            ignoreProgress = false;

            titleView.setText(title);
            valueView.setText(formatValue(currentValue));
        }

        private String formatValue(int value) {
            return valueFormatter != null ? valueFormatter.format(value) : value + valueSuffix;
        }

        /** Приводит значение к сетке шага и зажимает в [{@code minValue}; {@code maxValue}]. */
        private int clampValue(int value) {
            if (value <= minValue) {
                return minValue;
            }
            final int steps = Math.round((value - minValue) / (float) stepValue);
            final int snapped = minValue + Math.max(0, steps) * stepValue;
            return Math.min(maxValue, snapped);
        }

        private int progressForValue(int value) {
            final int progress = Math.round((value - minValue) / (float) stepValue);
            return Math.max(0, Math.min(seekBar.getMax(), progress));
        }

        private int getValue(int progress) {
            return clampValue(minValue + progress * stepValue);
        }
    }

    /** Простая стрелка «назад», чтобы не зависеть от отсутствующих drawable-ресурсов. */
    private static class BackArrowDrawable extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        BackArrowDrawable(int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float width = bounds.width();
            float height = bounds.height();
            path.reset();
            path.moveTo(width * 0.62f, height * 0.28f);
            path.lineTo(width * 0.38f, height * 0.5f);
            path.lineTo(width * 0.62f, height * 0.72f);
            canvas.drawPath(path, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return AndroidUtilities.dp(24);
        }

        @Override
        public int getIntrinsicHeight() {
            return AndroidUtilities.dp(24);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
