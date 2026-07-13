package com.bassam.qareebshare;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.List;

/** Coordinates exactly one role at a time: host or joiner. */
@SuppressLint("MissingPermission")
final class SessionController {
    enum State {
        IDLE,
        NEEDS_WIFI,
        HOST_PREPARING,
        HOST_WAITING,
        JOIN_PREPARING,
        JOIN_SEARCHING,
        JOIN_CONNECTING,
        HANDSHAKING,
        CONNECTED,
        DISCONNECTING,
        ERROR
    }

    private enum Role {
        NONE,
        HOST,
        JOINER
    }

    interface Listener {
        void onStateChanged(State state, String peerName);
        void onPeersChanged(List<PeerDevice> peers);
        void onFriendlyMessage(int messageResId);
        void onWifiRequired();
    }

    private final Activity activity;
    private final Listener listener;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final WifiManager wifiManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final IntentFilter intentFilter = new IntentFilter();
    private final BroadcastReceiver receiver = new P2pReceiver();

    private final HostSessionController hostController;
    private final JoinSessionController joinController;

    private Role role = Role.NONE;
    private State state = State.IDLE;
    private ActiveSessionTransport transport;
    private boolean receiverRegistered;
    private boolean transportStarting;
    private String activePeerName = "";

    SessionController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        manager = (WifiP2pManager) activity.getSystemService(Context.WIFI_P2P_SERVICE);
        wifiManager = (WifiManager) activity.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        channel = manager == null ? null : manager.initialize(
                activity,
                activity.getMainLooper(),
                () -> fail(R.string.session_connection_lost)
        );

        if (manager != null && channel != null) {
            hostController = new HostSessionController(
                    manager,
                    channel,
                    mainHandler,
                    localDeviceName(),
                    new HostCallbacks()
            );
            joinController = new JoinSessionController(
                    manager,
                    channel,
                    mainHandler,
                    new JoinCallbacks()
            );
        } else {
            hostController = null;
            joinController = null;
        }

        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    boolean isSupported() {
        return manager != null && channel != null
                && hostController != null && joinController != null;
    }

    boolean isWifiEnabled() {
        return wifiManager != null && wifiManager.isWifiEnabled();
    }

    State getState() {
        return state;
    }

    void register() {
        if (!isSupported() || receiverRegistered) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(receiver, intentFilter);
        }
        receiverRegistered = true;
        refreshWifiState();
        requestCurrentConnectionInfo();
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

    void refreshWifiState() {
        if (!isSupported()) {
            fail(R.string.session_not_supported);
            return;
        }
        if (!isWifiEnabled()) {
            closeTransport(true);
            setState(State.NEEDS_WIFI, "");
            listener.onWifiRequired();
        } else if (state == State.NEEDS_WIFI) {
            setState(State.IDLE, "");
        }
    }

    void createSession() {
        if (!prepareForAction()) {
            return;
        }
        stopRoleWithoutState(() -> {
            role = Role.HOST;
            activePeerName = "";
            listener.onPeersChanged(Collections.emptyList());
            hostController.start();
        });
    }

    void findSessions() {
        if (!prepareForAction()) {
            return;
        }
        stopRoleWithoutState(() -> {
            role = Role.JOINER;
            activePeerName = "";
            joinController.start();
        });
    }

    void join(PeerDevice host) {
        if (role != Role.JOINER || state != State.JOIN_SEARCHING) {
            return;
        }
        if (host == null || !host.isAvailable()) {
            listener.onFriendlyMessage(R.string.session_device_unavailable);
            return;
        }
        activePeerName = host.name;
        joinController.join(host);
    }

    void cancelCurrentAction() {
        if (state == State.CONNECTED) {
            disconnect();
            return;
        }
        setState(State.DISCONNECTING, activePeerName);
        closeTransport(true);
        stopRoleWithoutState(() -> {
            role = Role.NONE;
            activePeerName = "";
            listener.onPeersChanged(Collections.emptyList());
            setState(State.IDLE, "");
        });
    }

    void disconnect() {
        if (state == State.IDLE || state == State.DISCONNECTING) {
            return;
        }
        setState(State.DISCONNECTING, activePeerName);
        closeTransport(true);
        stopRoleWithoutState(() -> {
            role = Role.NONE;
            activePeerName = "";
            listener.onPeersChanged(Collections.emptyList());
            setState(State.IDLE, "");
        });
    }

    void shutdown() {
        unregister();
        closeTransport(true);
        if (!isSupported()) {
            return;
        }
        if (role == Role.HOST) {
            hostController.stop(() -> { });
        } else if (role == Role.JOINER) {
            joinController.stop(() -> { });
        }
        role = Role.NONE;
    }

    private boolean prepareForAction() {
        if (!isSupported()) {
            fail(R.string.session_not_supported);
            return false;
        }
        if (!isWifiEnabled()) {
            setState(State.NEEDS_WIFI, "");
            listener.onWifiRequired();
            return false;
        }
        if (state == State.DISCONNECTING) {
            return false;
        }
        closeTransport(true);
        return true;
    }

    private void stopRoleWithoutState(Runnable completion) {
        if (!isSupported()) {
            completion.run();
            return;
        }
        if (role == Role.HOST) {
            hostController.stop(completion);
        } else if (role == Role.JOINER) {
            joinController.stop(completion);
        } else {
            completion.run();
        }
    }

    private void startHostTransport() {
        if (role != Role.HOST || transportStarting || transport != null) {
            return;
        }
        transportStarting = true;
        createTransport(true, null);
    }

    private void startClientTransport(String hostAddress) {
        if (role != Role.JOINER || transportStarting || transport != null) {
            return;
        }
        transportStarting = true;
        setState(State.HANDSHAKING, activePeerName);
        createTransport(false, hostAddress);
    }

    private void createTransport(boolean server, String hostAddress) {
        final ActiveSessionTransport[] holder = new ActiveSessionTransport[1];
        ActiveSessionTransport created = new ActiveSessionTransport(
                localDeviceName(),
                new ActiveSessionTransport.Callback() {
                    @Override
                    public void onConnected(String peerName) {
                        mainHandler.post(() -> {
                            if (transport != holder[0]) {
                                return;
                            }
                            transportStarting = false;
                            activePeerName = peerName;
                            setState(State.CONNECTED, peerName);
                        });
                    }

                    @Override
                    public void onClosed(boolean unexpected) {
                        mainHandler.post(() -> handleTransportClosed(holder[0], unexpected));
                    }
                }
        );
        holder[0] = created;
        transport = created;
        if (server) {
            created.startServer();
        } else {
            created.startClient(hostAddress);
        }
    }

    private void handleTransportClosed(
            ActiveSessionTransport closedTransport,
            boolean unexpected
    ) {
        if (transport != closedTransport) {
            return;
        }
        transport = null;
        transportStarting = false;
        if (state == State.DISCONNECTING || state == State.IDLE) {
            return;
        }

        if (role == Role.HOST) {
            boolean hadPeer = state == State.CONNECTED || state == State.HANDSHAKING;
            activePeerName = "";
            setState(State.HOST_WAITING, "");
            startHostTransport();
            if (unexpected && hadPeer) {
                listener.onFriendlyMessage(R.string.session_peer_left);
            }
            return;
        }

        if (role == Role.JOINER) {
            activePeerName = "";
            joinController.stop(() -> {
                role = Role.NONE;
                setState(State.IDLE, "");
                if (unexpected) {
                    listener.onFriendlyMessage(R.string.session_connection_lost);
                }
            });
        }
    }

    private void closeTransport(boolean userInitiated) {
        ActiveSessionTransport current = transport;
        transport = null;
        transportStarting = false;
        if (current != null) {
            current.closeByUser();
        }
    }

    private void requestCurrentConnectionInfo() {
        if (!isSupported() || role == Role.NONE) {
            return;
        }
        try {
            manager.requestConnectionInfo(channel, this::handleConnectionInfo);
        } catch (RuntimeException ignored) {
        }
    }

    private void handleConnectionInfo(WifiP2pInfo info) {
        if (role == Role.HOST) {
            hostController.onConnectionInfo(info);
        } else if (role == Role.JOINER) {
            joinController.onConnectionInfo(info);
        }
    }

    private void handleDisconnectedBroadcast() {
        if (state == State.DISCONNECTING || state == State.IDLE
                || state == State.NEEDS_WIFI || role == Role.NONE) {
            return;
        }
        if (role == Role.HOST) {
            if (state == State.CONNECTED || state == State.HANDSHAKING) {
                ActiveSessionTransport current = transport;
                transport = null;
                transportStarting = false;
                if (current != null) {
                    current.closeByUser();
                }
                activePeerName = "";
                setState(State.HOST_WAITING, "");
                startHostTransport();
                listener.onFriendlyMessage(R.string.session_peer_left);
            }
            // A host group can exist while no client is connected. Do not tear it down.
            return;
        }
        if (role == Role.JOINER && state == State.CONNECTED) {
            closeTransport(true);
            joinController.stop(() -> {
                role = Role.NONE;
                activePeerName = "";
                setState(State.IDLE, "");
                listener.onFriendlyMessage(R.string.session_connection_lost);
            });
        }
        // Disconnected broadcasts while JOIN_CONNECTING can be transient. The
        // explicit connection timeout remains the source of truth.
    }

    private void setState(State newState, String peerName) {
        state = newState;
        listener.onStateChanged(newState, peerName == null ? "" : peerName);
    }

    private void fail(int messageResId) {
        Role failedRole = role;
        role = Role.NONE;
        activePeerName = "";
        closeTransport(true);
        if (failedRole == Role.HOST) {
            hostController.stop(() -> { });
        } else if (failedRole == Role.JOINER) {
            joinController.stop(() -> { });
        }
        setState(State.ERROR, "");
        listener.onFriendlyMessage(messageResId);
    }

    private String localDeviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.isEmpty()) {
            return activity.getString(R.string.session_nearby_phone);
        }
        if (!manufacturer.isEmpty()
                && !model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return manufacturer + " " + model;
        }
        return model;
    }

    private final class HostCallbacks implements HostSessionController.Callback {
        @Override
        public void onPreparing() {
            setState(State.HOST_PREPARING, "");
        }

        @Override
        public void onGroupReady() {
            if (role != Role.HOST) {
                return;
            }
            startHostTransport();
        }

        @Override
        public void onWaiting() {
            if (role == Role.HOST
                    && state != State.CONNECTED
                    && state != State.HANDSHAKING) {
                setState(State.HOST_WAITING, "");
            }
        }

        @Override
        public void onFailure(int messageResId) {
            if (role == Role.HOST) {
                fail(messageResId);
            }
        }
    }

    private final class JoinCallbacks implements JoinSessionController.Callback {
        @Override
        public void onPreparing() {
            setState(State.JOIN_PREPARING, "");
        }

        @Override
        public void onSearching() {
            if (role == Role.JOINER) {
                setState(State.JOIN_SEARCHING, "");
            }
        }

        @Override
        public void onHostsChanged(List<PeerDevice> hosts) {
            if (role == Role.JOINER) {
                listener.onPeersChanged(hosts);
            }
        }

        @Override
        public void onConnecting(String hostName) {
            if (role == Role.JOINER) {
                activePeerName = hostName;
                setState(State.JOIN_CONNECTING, hostName);
            }
        }

        @Override
        public void onHostAddressReady(String hostAddress) {
            if (role == Role.JOINER) {
                startClientTransport(hostAddress);
            }
        }

        @Override
        public void onFailure(int messageResId) {
            if (role == Role.JOINER) {
                fail(messageResId);
            }
        }
    }

    private final class P2pReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int value = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                );
                if (value != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    closeTransport(true);
                    setState(State.NEEDS_WIFI, "");
                    listener.onWifiRequired();
                } else if (state == State.NEEDS_WIFI) {
                    setState(State.IDLE, "");
                }
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
                    //noinspection deprecation
                    networkInfo = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO
                    );
                }
                if (networkInfo != null && networkInfo.isConnected()) {
                    requestCurrentConnectionInfo();
                } else {
                    handleDisconnectedBroadcast();
                }
            }
        }
    }
}
