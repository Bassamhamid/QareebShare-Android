package com.bassam.qareebshare;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

final class SessionPeerAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final ArrayList<PeerDevice> items = new ArrayList<>();

    SessionPeerAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void replace(List<PeerDevice> peers) {
        items.clear();
        if (peers != null) {
            items.addAll(peers);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public PeerDevice getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.row_session_peer, parent, false);
            holder = new ViewHolder();
            holder.name = convertView.findViewById(R.id.text_peer_name);
            holder.subtitle = convertView.findViewById(R.id.text_peer_subtitle);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        PeerDevice peer = getItem(position);
        holder.name.setText(peer.name);
        holder.subtitle.setText(R.string.session_tap_to_connect);
        convertView.setEnabled(peer.isAvailable());
        convertView.setAlpha(peer.isAvailable() ? 1.0f : 0.55f);
        return convertView;
    }

    private static final class ViewHolder {
        TextView name;
        TextView subtitle;
    }
}
