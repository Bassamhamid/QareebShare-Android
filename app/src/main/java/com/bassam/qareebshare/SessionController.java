package com.bassam.qareebshare;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressLint("MissingPermission")
final class SessionController {
    enum State {
        IDLE,
        NEEDS_WIFI,
        PREPARING,
        DISCOVERING,
        CONNECTING,
        HANDSHAKING,
        CONNECTED,
        DISCONNECTING,
        ERROR
    }

    interface Listener {
        void onStateChanged(State state, String peerName);
        void onPeersChanged(List<PeerDevice> peers);
        void onFriendlyMessage(int messageResId);
        void onWifiRequired();
    }

    private static final String SERVICE_INSTANCE = "QareebShare";
    private static final String SERVICE_TYPE = "_qareebshare._tcp";
    private static final long CONNECT_TIMEOUT_MS = 40_000L;

    private final Activity activity;
    private final Listener listener;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final WifiManager wifiManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final IntentFilter intentFilter = new IntentFilter();
    private final Map<String, PeerDevice> peerMap = new LinkedHashMap<>();
    private final BroadcastReceiver receiver = new P2pReceiver();

    private WifiP2pDnsSdServiceRequest serviceRequest;
    private SessionTransport transport;
    private State state = State.IDLE;
    private boolean receiverRegistered;
    private boolean transportStarted;
    private int generation;
    private int connectAttempt;
    private String activePeerName = "";
    private PeerDevice targetPeer;

    private final Runnable connectTimeout = () -> {
        if (state != State.CONNECTING && state != State.HANDSHAKING) {
            return;
        }
        cancelConnectSilently();
        closeTransport(false);
        removeGroupSilently();
        fail(R.string.session_connection_timed_out);
    };

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

        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
    }

    boolean isSupported() {
        return manager != null && channel != null;
    }

    boolean isWifiEnabled() {
        return wifiManager != null && wifiManager.isWifiEnabled();
    }

    State getState() {
        return state;
    }

    String getActivePeerName() {
        return activePeerName;
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
            setState(State.NEEDS_WIFI, "");
            listener.onWifiRequired();
        } else if (state == State.NEEDS_WIFI) {
            setState(State.IDLE, "");
        }
    }

    void startSearch() {
        if (!isSupported()) {
            fail(R.string.session_not_supported);
            return;
        }
        if (!isWifiEnabled()) {
            setState(State.NEEDS_WIFI, "");
            listener.onWifiRequired();
            return;
        }
        if (state == State.CONNECTED) {
            return;
        }

        generation++;
        int currentGeneration = generation;
        connectAttempt = 0;
        targetPeer = null;
        activePeerName = "";
        cancelTimeout();
        closeTransport(false);
        peerMap.clear();
        listener.onPeersChanged(Collections.emptyList());
        setState(State.PREPARING, "");

        cleanupDiscovery();
        cancelConnectSilently();
        removeGroupSilently();
        mainHandler.postDelayed(
                () -> configureServiceDiscovery(currentGeneration),
                700L
        );
    }

    void connect(PeerDevice peer) {
        if (peer == null || peer.address.isEmpty()) {
            listener.onFriendlyMessage(R.string.session_device_unavailable);
            return;
        }
        if (!isWifiEnabled()) {
            setState(State.NEEDS_WIFI, "");
            listener.onWifiRequired();
            return;
        }
        if (state == State.CONNECTING || state == State.HANDSHAKING
                || state == State.CONNECTED) {
            return;
        }

        generation++;
        targetPeer = peer;
        connectAttempt = 0;
        activePeerName = peer.name;
        setState(State.CONNECTING, activePeerName);
        cleanupDiscovery();
        cancelConnectSilently();
        removeGroupSilently();
        mainHandler.removeCallbacks(connectTimeout);
        mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        mainHandler.postDelayed(this::performConnect, 650L);
    }

    void cancelCurrentAction() {
        if (state == State.CONNECTED) {
            disconnect();
            return;
        }
        generation++;
        cancelTimeout();
        cancelConnectSilently();
        closeTransport(false);
        removeGroupSilently();
        cleanupDiscovery();
        activePeerName = "";
        setState(State.IDLE, "");
    }

    void disconnect() {
        if (state == State.IDLE) {
            return;
        }
        generation++;
        setState(State.DISCONNECTING, activePeerName);
        cancelTimeout();
        SessionTransport current = transport;
        transport = null;
        transportStarted = false;
        if (current != null) {
            current.closeByUser();
        }
        cleanupDiscovery();
        cancelConnectSilently();
        removeGroupSilently();
        mainHandler.postDelayed(() -> {
            activePeerName = "";
            setState(State.IDLE, "");
        }, 500L);
    }

    void shutdown() {
        generation++;
        cancelTimeout();
        unregister();
        cleanupDiscovery();
        cancelConnectSilently();
        closeTransport(false);
        removeGroupSilently();
    }

    private void configureServiceDiscovery(int expectedGeneration) {
        if (expectedGeneration != generation || !isWifiEnabled()) {
            return;
        }

        Map<String, String> record = new LinkedHashMap<>();
        record.put("app", "qareebshare");
        record.put("version", "1");
        record.put("name", localDeviceName());
        WifiP2pDnsSdServiceInfo serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                SERVICE_INSTANCE,
                SERVICE_TYPE,
                record
        );

        manager.setDnsSdResponseListeners(
                channel,
                (instanceName, registrationType, device) -> {
                    if (!SERVICE_INSTANCE.equals(instanceName)
                            || registrationType == null
                            || !registrationType.startsWith(SERVICE_TYPE)) {
                        return;
                    }
                    addPeer(device);
                },
                (fullDomainName, txtRecordMap, device) -> {
                    if (txtRecordMap == null
                            || !"qareebshare".equals(txtRecordMap.get("app"))) {
                        return;
                    }
                    addPeer(device);
                }
        );

        manager.addLocalService(channel, serviceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                addServiceRequestAndDiscover(expectedGeneration);
            }

            @Override
            public void onFailure(int reason) {
                fail(messageForReason(reason));
            }
        });
    }

    private void addServiceRequestAndDiscover(int expectedGeneration) {
        if (expectedGeneration != generation) {
            return;
        }
        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE);
        manager.addServiceRequest(channel, serviceRequest, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        setState(State.DISCOVERING, "");
                    }

                    @Override
                    public void onFailure(int reason) {
                        if (reason == WifiP2pManager.BUSY) {
                            mainHandler.postDelayed(SessionController.this::startSearch, 1_200L);
                        } else {
                            fail(messageForReason(reason));
                        }
                    }
                });
            }

            @Override
            public void onFailure(int reason) {
                fail(messageForReason(reason));
            }
        });
    }

    private void addPeer(WifiP2pDevice device) {
        if (device == null || device.deviceAddress == null
                || device.deviceAddress.trim().isEmpty()) {
            return;
        }
        PeerDevice peer = new PeerDevice(
                device.deviceName,
                device.deviceAddress,
                device.status
        );
        peerMap.put(peer.address, peer);
        ArrayList<PeerDevice> result = new ArrayList<>(peerMap.values());
        Collections.sort(result, (left, right) -> left.name.compareToIgnoreCase(right.name));
        listener.onPeersChanged(result);
    }

    private void performConnect() {
        PeerDevice peer = targetPeer;
        if (peer == null || state != State.CONNECTING) {
            return;
        }
        connectAttempt++;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = peer.address;
        config.wps.setup = WpsInfo.PBC;
        config.groupOwnerIntent = 7;
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                // The connection broadcast confirms group formation.
            }

            @Override
            public void onFailure(int reason) {
                if (reason == WifiP2pManager.BUSY && connectAttempt < 2) {
                    cancelConnectSilently();
                    removeGroupSilently();
                    mainHandler.postDelayed(SessionController.this::performConnect, 1_200L);
                } else {
                    fail(messageForReason(reason));
                }
            }
        });
    }

    private void handleConnectionInfo(WifiP2pInfo info) {
        if (info == null || !info.groupFormed) {
            return;
        }
        cancelTimeout();
        cleanupDiscovery();
        if (transportStarted) {
            return;
        }
        transportStarted = true;
        setState(State.HANDSHAKING, activePeerName);
        final SessionTransport[] holder = new SessionTransport[1];
        SessionTransport newTransport = new SessionTransport(
                localDeviceName(),
                new SessionTransport.Callback() {
                    @Override
                    public void onConnected(String peerName) {
                        mainHandler.post(() -> {
                            if (transport != holder[0]) {
                                return;
                            }
                            activePeerName = peerName;
                            setState(State.CONNECTED, peerName);
                        });
                    }

                    @Override
                    public void onClosed(boolean unexpected) {
                        mainHandler.post(() -> {
                            if (transport != holder[0]) {
                                return;
                            }
                            transport = null;
                            transportStarted = false;
                            if (state == State.DISCONNECTING || state == State.IDLE) {
                                return;
                            }
                            activePeerName = "";
                            setState(State.IDLE, "");
                            if (unexpected) {
                                listener.onFriendlyMessage(R.string.session_connection_lost);
                            }
                        });
                    }
                }
        );
        holder[0] = newTransport;
        transport = newTransport;
        if (info.isGroupOwner) {
            newTransport.startServer();
        } else {
            InetAddress address = info.groupOwnerAddress;
            String host = address == null ? null : address.getHostAddress();
            newTransport.startClient(host);
        }
    }

    private void cleanupDiscovery() {
        if (!isSupported()) {
            return;
        }
        WifiP2pManager.ActionListener ignored = ignoredListener();
        try {
            manager.stopPeerDiscovery(channel, ignored);
            if (serviceRequest != null) {
                manager.removeServiceRequest(channel, serviceRequest, ignored);
                serviceRequest = null;
            }
            manager.clearServiceRequests(channel, ignored);
            manager.clearLocalServices(channel, ignored);
        } catch (RuntimeException ignoredError) {
        }
    }

    private void cancelConnectSilently() {
        if (!isSupported()) {
            return;
        }
        try {
            manager.cancelConnect(channel, ignoredListener());
        } catch (RuntimeException ignored) {
        }
    }

    private void removeGroupSilently() {
        if (!isSupported()) {
            return;
        }
        try {
            manager.requestGroupInfo(channel, group -> {
                if (group != null) {
                    manager.removeGroup(channel, ignoredListener());
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    private void closeTransport(boolean userInitiated) {
        SessionTransport current = transport;
        transport = null;
        transportStarted = false;
        if (current != null) {
            current.closeByUser();
        }
    }

    private void cancelTimeout() {
        mainHandler.removeCallbacks(connectTimeout);
    }

    private void fail(int messageResId) {
        cancelTimeout();
        closeTransport(false);
        activePeerName = "";
        setState(State.ERROR, "");
        listener.onFriendlyMessage(messageResId);
    }

    private void setState(State newState, String peerName) {
        state = newState;
        listener.onStateChanged(newState, peerName == null ? "" : peerName);
    }

    private int messageForReason(int reason) {
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            return R.string.session_not_supported;
        }
        if (reason == WifiP2pManager.BUSY) {
            return R.string.session_try_again;
        }
        return R.string.session_connection_failed;
    }

    private String localDeviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String value = (manufacturer + " " + model).trim();
        return value.isEmpty() ? "هاتف قريب" : value;
    }

    private WifiP2pManager.ActionListener ignoredListener() {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onFailure(int reason) {
            }
        };
    }

    private final class P2pReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int value = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                boolean enabled = value == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                if (!enabled) {
                    cancelTimeout();
                    closeTransport(false);
                    activePeerName = "";
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
                    manager.requestConnectionInfo(channel, SessionController.this::handleConnectionInfo);
                } else if (state == State.CONNECTED || state == State.CONNECTING
                        || state == State.HANDSHAKING) {
                    cancelTimeout();
                    closeTransport(false);
                    activePeerName = "";
                    setState(State.IDLE, "");
                    listener.onFriendlyMessage(R.string.session_connection_lost);
                }
            }
        }
    }
}
