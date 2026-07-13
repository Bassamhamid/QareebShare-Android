package com.bassam.qareebshare;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

final class PeersAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private List<PeerDevice> peers = Collections.emptyList();

    PeersAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    void replace(List<PeerDevice> newPeers) {
        peers = newPeers == null ? Collections.emptyList() : newPeers;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return peers.size();
    }

    @Override
    public PeerDevice getItem(int position) {
        return peers.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean isEnabled(int position) {
        return getItem(position).isAvailable();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.row_peer, parent, false);
            holder = new ViewHolder();
            holder.name = convertView.findViewById(R.id.peer_name);
            holder.state = convertView.findViewById(R.id.peer_state);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        PeerDevice peer = getItem(position);
        holder.name.setText(peer.name);
        holder.state.setText(statusText(peer.status));
        convertView.setAlpha(peer.isAvailable() ? 1.0f : 0.55f);
        return convertView;
    }

    private int statusText(int status) {
        switch (status) {
            case WifiP2pDevice.CONNECTED:
                return R.string.peer_connected;
            case WifiP2pDevice.INVITED:
                return R.string.peer_invited;
            case WifiP2pDevice.AVAILABLE:
                return R.string.peer_available;
            case WifiP2pDevice.FAILED:
                return R.string.peer_failed;
            case WifiP2pDevice.UNAVAILABLE:
            default:
                return R.string.peer_unavailable;
        }
    }

    private static final class ViewHolder {
        TextView name;
        TextView state;
    }
}
