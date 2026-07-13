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

    void onIncomingOffer(String peerName, List<TransferItemInfo> items, long totalBytes);

    void onTransferStarted(boolean sending, int itemCount, long totalBytes);

    void onTransferProgress(
            boolean sending,
            String itemName,
            int itemIndex,
            int itemCount,
            long transferredBytes,
            long totalBytes,
            long bytesPerSecond
    );

    void onTransferItemCompleted(boolean sending, String itemName, int itemIndex, int itemCount);

    void onTransferCompleted(
            boolean sending,
            int itemCount,
            long transferredBytes,
            String saveLocation
    );

    void onTransferRejected();

    void onDisconnected();

    void onWifiDirectError(int messageResId);
}
