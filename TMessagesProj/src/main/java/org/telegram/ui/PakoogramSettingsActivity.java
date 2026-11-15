package org.telegram.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * Pakoogram: экран настроек форка.
 *
 * <p>Класс наследуется от {@link BaseFragment} (это фрагмент Telegram-навигации, несмотря
 * на суффикс {@code Activity} в имени) и открывается через
 * {@code presentFragment(new PakoogramSettingsActivity())}.</p>
 *
 * <p>Экран построен на стандартных элементах Telegram: {@link RecyclerListView},
 * {@link HeaderCell}, {@link TextCheckCell} и {@link TextSettingsCell}. Числовые параметры
 * ({@code blurIntensity}, {@code cornerRadius}) выбираются по клику из списка допустимых
 * значений, булевы переключаются сразу.</p>
 */
public class PakoogramSettingsActivity extends BaseFragment {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_CHECK = 1;
    private static final int VIEW_TYPE_SETTINGS = 2;

    private static final int ROW_HEADER_APPEARANCE = 0;
    private static final int ROW_BLUR_INTENSITY = 1;
    private static final int ROW_CORNER_RADIUS = 2;
    private static final int ROW_HEADER_INTERFACE = 3;
    private static final int ROW_SPRING_PHYSICS = 4;
    private static final int ROW_FLOATING_PANELS = 5;
    private static final int ROW_GHOST_MODE = 6;
    private static final int ROW_HEADER_ABOUT = 7;
    private static final int ROW_COUNT = 8;

    private static final int[] BLUR_INTENSITY_VALUES =
            {0, 10, 20, 30, 40, 50, 60, 70, 75, 80, 85, 90, 95, 100};
    private static final int[] CORNER_RADIUS_VALUES =
            {0, 2, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40};

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
            case ROW_BLUR_INTENSITY:
                showIntValueSelector("Интенсивность размытия", BLUR_INTENSITY_VALUES,
                        PakoogramConfig.getBlurIntensity(), new IntValueListener() {
                            @Override
                            public void onValueSelected(int value) {
                                PakoogramConfig.setBlurIntensity(value);
                            }
                        });
                break;
            case ROW_CORNER_RADIUS:
                showIntValueSelector("Радиус скругления", CORNER_RADIUS_VALUES,
                        PakoogramConfig.getCornerRadius(), new IntValueListener() {
                            @Override
                            public void onValueSelected(int value) {
                                PakoogramConfig.setCornerRadius(value);
                            }
                        });
                break;
            case ROW_SPRING_PHYSICS: {
                boolean enabled = !PakoogramConfig.isSpringPhysicsEnabled();
                PakoogramConfig.setSpringPhysicsEnabled(enabled);
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

    private void showIntValueSelector(CharSequence title, final int[] values, int currentValue,
                                      final IntValueListener listener) {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] labels = new CharSequence[values.length];
        int checkedIndex = 0;
        for (int a = 0; a < values.length; a++) {
            labels[a] = String.valueOf(values[a]);
            if (values[a] == currentValue) {
                checkedIndex = a;
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setSingleChoiceItems(labels, checkedIndex, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which >= 0 && which < values.length) {
                    listener.onValueSelected(values[which]);
                    if (listAdapter != null) {
                        listAdapter.notifyDataSetChanged();
                    }
                }
                dialog.dismiss();
            }
        });
        builder.setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        builder.show();
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
                case ROW_SPRING_PHYSICS:
                case ROW_FLOATING_PANELS:
                case ROW_GHOST_MODE:
                    return VIEW_TYPE_CHECK;
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
                    if (position == ROW_SPRING_PHYSICS) {
                        checkCell.setTextAndCheck("Пружинная анимация",
                                PakoogramConfig.isSpringPhysicsEnabled(), false);
                    } else if (position == ROW_FLOATING_PANELS) {
                        checkCell.setTextAndCheck("Плавающие панели",
                                PakoogramConfig.isFloatingPanelsEnabled(), false);
                    } else {
                        checkCell.setTextAndCheck("Режим призрака",
                                PakoogramConfig.isGhostModeEnabled(), true);
                    }
                    break;
                }
                case VIEW_TYPE_SETTINGS:
                default: {
                    TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                    if (position == ROW_BLUR_INTENSITY) {
                        settingsCell.setTextAndValue("Интенсивность размытия",
                                PakoogramConfig.getBlurIntensity() + "%", true);
                    } else if (position == ROW_CORNER_RADIUS) {
                        settingsCell.setTextAndValue("Радиус скругления",
                                PakoogramConfig.getCornerRadius() + " dp", true);
                    } else {
                        settingsCell.setTextAndValue("Версия Pakoogram", "1.0", false);
                    }
                    break;
                }
            }
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
