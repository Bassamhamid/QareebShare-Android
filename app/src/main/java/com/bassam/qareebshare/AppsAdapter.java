package com.bassam.qareebshare;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.Set;

final class AppsAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final Set<String> selectedPackages;
    private List<AppEntry> apps = Collections.emptyList();

    AppsAdapter(Context context, Set<String> selectedPackages) {
        this.inflater = LayoutInflater.from(context);
        this.selectedPackages = selectedPackages;
    }

    void replace(List<AppEntry> newApps) {
        apps = newApps == null ? Collections.emptyList() : newApps;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return apps.size();
    }

    @Override
    public AppEntry getItem(int position) {
        return apps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.row_app, parent, false);
            holder = new ViewHolder();
            holder.icon = convertView.findViewById(R.id.app_icon);
            holder.label = convertView.findViewById(R.id.app_label);
            holder.packageName = convertView.findViewById(R.id.app_package);
            holder.check = convertView.findViewById(R.id.app_check);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        AppEntry app = getItem(position);
        holder.icon.setImageDrawable(app.icon != null ? app.icon : new ColorDrawable(0x00000000));
        holder.label.setText(app.label);
        holder.packageName.setText(app.packageName);

        boolean selected = selectedPackages.contains(app.packageName);
        holder.check.setBackgroundResource(selected
                ? R.drawable.bg_checkbox_checked
                : R.drawable.bg_checkbox_unchecked);
        holder.check.setText(selected ? "✓" : "");
        return convertView;
    }

    private static final class ViewHolder {
        ImageView icon;
        TextView label;
        TextView packageName;
        TextView check;
    }
}
