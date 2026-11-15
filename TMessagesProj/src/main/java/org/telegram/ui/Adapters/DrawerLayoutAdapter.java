package org.telegram.ui.Adapters;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.PakoogramSettingsActivity;

import java.util.ArrayList;

/**
 * Адаптер бокового меню (drawer).
 *
 * <p>Помимо стандартных пунктов здесь добавлен пункт «Настройки Pakoogram», который
 * открывает {@link PakoogramSettingsActivity} через родительский фрагмент
 * (см. {@link #setParentFragment(BaseFragment)}).</p>
 *
 * <p>Внешний обработчик кликов можно задать через {@link #setOnItemClickListener(OnItemClickListener)} —
 * в этом случае адаптер не открывает экран сам, а отдаёт id пункта наружу.</p>
 */
public class DrawerLayoutAdapter extends RecyclerListView.SelectionAdapter {

    /** id пункта меню «Настройки Pakoogram». */
    public static final int ITEM_PAKOGRAM_SETTINGS = 1000;

    private static final int VIEW_TYPE_ITEM = 0;

    private final Context mContext;
    private final ArrayList<ItemInner> items = new ArrayList<>();

    private BaseFragment parentFragment;
    private OnItemClickListener onItemClickListener;

    public interface OnItemClickListener {
        void onDrawerItemClick(int id);
    }

    public static class ItemInner {

        public final int id;
        public final CharSequence text;

        public ItemInner(int id, CharSequence text) {
            this.id = id;
            this.text = text;
        }
    }

    public DrawerLayoutAdapter(Context context) {
        this(context, null);
    }

    public DrawerLayoutAdapter(Context context, BaseFragment parentFragment) {
        mContext = context;
        this.parentFragment = parentFragment;
        updateItems();
    }

    public void setParentFragment(BaseFragment fragment) {
        parentFragment = fragment;
    }

    public BaseFragment getParentFragment() {
        return parentFragment;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        onItemClickListener = listener;
    }

    private void updateItems() {
        items.clear();
        items.add(new ItemInner(ITEM_PAKOGRAM_SETTINGS, "Настройки Pakoogram"));
        notifyDataSetChanged();
    }

    public ItemInner getItem(int position) {
        if (position < 0 || position >= items.size()) {
            return null;
        }
        return items.get(position);
    }

    public int getItemInnerId(int position) {
        ItemInner item = getItem(position);
        return item != null ? item.id : 0;
    }

    public CharSequence getItemText(int position) {
        ItemInner item = getItem(position);
        return item != null ? item.text : null;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return VIEW_TYPE_ITEM;
    }

    @Override
    public long getItemId(int position) {
        ItemInner item = getItem(position);
        return item != null ? item.id : 0;
    }

    @Override
    public boolean isEnabled(RecyclerView.ViewHolder holder) {
        return true;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        TextView textView = new TextView(mContext);
        textView.setTextSize(15);
        textView.setSingleLine(true);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        textView.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), 0);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        textView.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48)));
        return new RecyclerListView.Holder(textView);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        final ItemInner item = getItem(position);
        if (item == null) {
            return;
        }
        TextView textView = (TextView) holder.itemView;
        textView.setText(item.text);
        textView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onDrawerItemClicked(item.id);
            }
        });
    }

    private void onDrawerItemClicked(int id) {
        if (onItemClickListener != null) {
            onItemClickListener.onDrawerItemClick(id);
            return;
        }
        if (id == ITEM_PAKOGRAM_SETTINGS) {
            openPakoogramSettings();
        }
    }

    /**
     * Открывает экран настроек Pakoogram в текущем стеке навигации.
     * Без {@link #setParentFragment(BaseFragment) родительского фрагмента} открыть экран нельзя,
     * поэтому в этом случае клик просто игнорируется.
     */
    private void openPakoogramSettings() {
        if (parentFragment != null) {
            parentFragment.presentFragment(new PakoogramSettingsActivity());
        }
    }
}

