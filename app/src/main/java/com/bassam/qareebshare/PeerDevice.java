package com.bassam.qareebshare;

import android.net.wifi.p2p.WifiP2pDevice;

final class PeerDevice {
    final String name;
    final String address;
    final int status;

    PeerDevice(String name, String address, int status) {
        this.name = name == null || name.trim().isEmpty() ? "هاتف قريب" : name.trim();
        this.address = address == null ? "" : address;
        this.status = status;
    }

    boolean isAvailable() {
        return status == WifiP2pDevice.AVAILABLE || status == WifiP2pDevice.INVITED;
    }
}
