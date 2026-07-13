package com.bassam.qareebshare;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class WifiDirectController {
    private final Activity activity;
    private final WifiDirectEvents events;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final IntentFilter intentFilter = new IntentFilter();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LocalHandshake handshake = new LocalHandshake();
    private final BroadcastReceiver receiver = new P2pReceiver();

    private final ArrayList<PeerDevice> peers = new ArrayList<>();
    private boolean receiverRegistered;
    private boolean handshakeStarted;

    WifiDirectController(Activity activity, WifiDirectEvents events) {
        this.activity = activity;
        this.events = events;
        manager = (WifiP2pManager) activity.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager == null ? null : manager.initialize(activity, activity.getMainLooper(), () -> {
            events.onWifiDirectError(R.string.connection_channel_lost);
        });

        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    boolean isAvailable() {
        return manager != null && channel != null;
    }

    List<PeerDevice> currentPeers() {
        return new ArrayList<>(peers);
    }

    void register() {
        if (!isAvailable() || receiverRegistered) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(receiver, intentFilter);
        }
        receiverRegistered = true;
    }

    void unregister() {
        if (!receiverRegistered) {
            return;
        }
        try {
            activity.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        receiverRegistered = false;
    }

    void discoverPeers() {
        if (!isAvailable()) {
            events.onWifiDirectError(R.string.wifi_direct_not_supported);
            return;
        }
        events.onDiscoveryStarted();
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                requestPeers();
            }

            @Override
            public void onFailure(int reason) {
                events.onWifiDirectError(messageForReason(reason));
            }
        });
    }

    void connect(PeerDevice peer) {
        if (!isAvailable() || peer == null || peer.address.isEmpty()) {
            return;
        }

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = peer.address;
        config.wps.setup = WpsInfo.PBC;
        config.groupOwnerIntent = 0;

        events.onConnecting(peer);
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                // The connection broadcast confirms when the group is formed.
            }

            @Override
            public void onFailure(int reason) {
                events.onWifiDirectError(messageForReason(reason));
            }
        });
    }

    void startReceiver() {
        if (!isAvailable()) {
            events.onWifiDirectError(R.string.wifi_direct_not_supported);
            return;
        }
        stopHandshake();
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                createReceiverGroup();
            }

            @Override
            public void onFailure(int reason) {
                createReceiverGroup();
            }
        });
    }

    void stopAll() {
        peers.clear();
        events.onPeersUpdated(Collections.emptyList());
        stopHandshake();
        if (!isAvailable()) {
            events.onDisconnected();
            return;
        }
        WifiP2pManager.ActionListener ignored = new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onFailure(int reason) {
            }
        };
        manager.stopPeerDiscovery(channel, ignored);
        manager.cancelConnect(channel, ignored);
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                events.onDisconnected();
            }

            @Override
            public void onFailure(int reason) {
                events.onDisconnected();
            }
        });
    }

    void release() {
        unregister();
        stopAll();
        handshake.shutdown();
    }

    private void createReceiverGroup() {
        manager.createGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                events.onReceiverReady();
                manager.requestConnectionInfo(channel, WifiDirectController.this::handleConnectionInfo);
            }

            @Override
            public void onFailure(int reason) {
                if (reason == WifiP2pManager.BUSY) {
                    manager.requestConnectionInfo(channel, info -> {
                        if (info != null && info.groupFormed) {
                            events.onReceiverReady();
                            handleConnectionInfo(info);
                        } else {
                            events.onWifiDirectError(R.string.connection_busy);
                        }
                    });
                } else {
                    events.onWifiDirectError(messageForReason(reason));
                }
            }
        });
    }

    private void requestPeers() {
        if (!isAvailable()) {
            return;
        }
        manager.requestPeers(channel, peerList -> {
            peers.clear();
            for (WifiP2pDevice device : peerList.getDeviceList()) {
                peers.add(new PeerDevice(device.deviceName, device.deviceAddress, device.status));
            }
            Collections.sort(peers, (left, right) -> left.name.compareToIgnoreCase(right.name));
            events.onPeersUpdated(new ArrayList<>(peers));
        });
    }

    private void handleConnectionInfo(WifiP2pInfo info) {
        if (info == null || !info.groupFormed) {
            return;
        }
        events.onLinkEstablished(info.isGroupOwner);
        if (handshakeStarted) {
            return;
        }
        handshakeStarted = true;

        LocalHandshake.Callback callback = new LocalHandshake.Callback() {
            @Override
            public void onSuccess(String peerName) {
                mainHandler.post(() -> events.onHandshakeCompleted(peerName));
            }

            @Override
            public void onFailure() {
                mainHandler.post(() -> {
                    handshakeStarted = false;
                    events.onWifiDirectError(R.string.handshake_failed);
                });
            }
        };

        String localName = Build.MANUFACTURER + " " + Build.MODEL;
        if (info.isGroupOwner) {
            handshake.startServer(localName, callback);
        } else {
            InetAddress ownerAddress = info.groupOwnerAddress;
            String host = ownerAddress == null ? null : ownerAddress.getHostAddress();
            handshake.startClient(host, localName, callback);
        }
    }

    private void stopHandshake() {
        handshakeStarted = false;
        handshake.stop();
    }

    private int messageForReason(int reason) {
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            return R.string.wifi_direct_not_supported;
        }
        if (reason == WifiP2pManager.BUSY) {
            return R.string.connection_busy;
        }
        return R.string.connection_failed;
    }

    private final class P2pReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                events.onWifiDirectStateChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                return;
            }

            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                requestPeers();
                return;
            }

            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo networkInfo;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    networkInfo = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            NetworkInfo.class
                    );
                } else {
                    networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                }

                if (networkInfo != null && networkInfo.isConnected()) {
                    manager.requestConnectionInfo(channel, WifiDirectController.this::handleConnectionInfo);
                } else {
                    stopHandshake();
                    events.onDisconnected();
                }
            }
        }
    }
}
