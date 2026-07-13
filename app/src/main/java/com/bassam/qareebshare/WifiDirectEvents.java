package com.bassam.qareebshare;

import java.util.List;

interface WifiDirectEvents {
    void onWifiDirectStateChanged(boolean enabled);

    void onDiscoveryStarted();

    void onPeersUpdated(List<PeerDevice> peers);

    void onReceiverReady();

    void onConnecting(PeerDevice peer);

    void onLinkEstablished(boolean groupOwner);

    void onHandshakeCompleted(String peerName);

    void onDisconnected();

    void onWifiDirectError(int messageResId);
}
