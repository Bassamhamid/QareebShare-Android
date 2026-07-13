package com.bassam.qareebshare;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

final class HistoryAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final Context context;
    private final ArrayList<TransferHistoryStore.Entry> entries = new ArrayList<>();

    HistoryAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    void replace(List<TransferHistoryStore.Entry> values) {
        entries.clear();
        if (values != null) {
            entries.addAll(values);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public TransferHistoryStore.Entry getItem(int position) {
        return entries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        Holder holder;
        if (view == null) {
            view = inflater.inflate(R.layout.row_history, parent, false);
            holder = new Holder();
            holder.title = view.findViewById(R.id.text_history_title);
            holder.details = view.findViewById(R.id.text_history_details);
            holder.time = view.findViewById(R.id.text_history_time);
            view.setTag(holder);
        } else {
            holder = (Holder) view.getTag();
        }

        TransferHistoryStore.Entry entry = getItem(position);
        boolean sending = entry.direction == TransferHistoryStore.DIRECTION_SEND;
        holder.title.setText(context.getString(
                sending ? R.string.history_sent_to : R.string.history_received_from,
                entry.peerName.isEmpty() ? context.getString(R.string.nearby_device) : entry.peerName
        ));
        String details = context.getString(
                R.string.history_entry_details,
                entry.itemCount,
                FormatUtils.bytes(entry.totalBytes)
        );
        if (entry.appCount > 0 && !sending) {
            details += " • " + context.getString(R.string.history_apps_installable, entry.appCount);
        }
        holder.details.setText(details);
        holder.time.setText(DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT
        ).format(new Date(entry.createdAt)));
        return view;
    }

    private static final class Holder {
        TextView title;
        TextView details;
        TextView time;
    }
}
