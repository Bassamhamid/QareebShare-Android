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
    private static final long PREPARATION_TIMEOUT_MS = 12_000L;
    private static final long CONNECT_TIMEOUT_MS = 45_000L;
    private static final long CONNECTION_INFO_POLL_MS = 750L;
    private static final int MAX_CONNECT_ATTEMPTS = 2;
    private static final int MAX_DISCOVERY_ATTEMPTS = 2;

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
    private boolean cleanupInProgress;
    private boolean connectIssued;
    private int generation;
    private int connectAttempt;
    private int discoveryAttempt;
    private String activePeerName = "";
    private PeerDevice targetPeer;

    private final Runnable preparationTimeout = () -> {
        if (!cleanupInProgress) {
            return;
        }
        cleanupInProgress = false;
        fail(R.string.session_try_again);
    };

    private final Runnable connectTimeout = () -> {
        if (state != State.CONNECTING && state != State.HANDSHAKING) {
            return;
        }
        cleanupInProgress = false;
        connectIssued = false;
        cancelConnectSilently();
        closeTransport();
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
                () -> {
                    cleanupInProgress = false;
                    connectIssued = false;
                    fail(R.string.session_connection_lost);
                }
        );

        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
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
            generation++;
            cancelAllTimeouts();
            cleanupInProgress = false;
            connectIssued = false;
            closeTransport();
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
        int expectedGeneration = generation;
        connectAttempt = 0;
        discoveryAttempt = 0;
        targetPeer = null;
        activePeerName = "";
        connectIssued = false;
        cancelAllTimeouts();
        closeTransport();
        peerMap.clear();
        listener.onPeersChanged(Collections.emptyList());
        setState(State.PREPARING, "");

        prepareEnvironment(expectedGeneration, () -> configureServiceDiscovery(expectedGeneration));
    }

    void connect(PeerDevice peer) {
        if (peer == null || peer.address.isEmpty() || !peer.isAvailable()) {
            listener.onFriendlyMessage(R.string.session_device_unavailable);
            return;
        }
        if (!isWifiEnabled()) {
            setState(State.NEEDS_WIFI, "");
            listener.onWifiRequired();
            return;
        }
        if (state == State.CONNECTING || state == State.HANDSHAKING
                || state == State.CONNECTED || state == State.DISCONNECTING) {
            return;
        }

        generation++;
        int expectedGeneration = generation;
        targetPeer = peer;
        connectAttempt = 0;
        activePeerName = peer.name;
        connectIssued = false;
        cancelAllTimeouts();
        setState(State.CONNECTING, activePeerName);
        mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);

        prepareEnvironment(expectedGeneration, () -> performConnect(expectedGeneration));
    }

    void cancelCurrentAction() {
        if (state == State.CONNECTED) {
            disconnect();
            return;
        }
        generation++;
        cancelAllTimeouts();
        cleanupInProgress = false;
        connectIssued = false;
        cancelConnectSilently();
        closeTransport();
        cleanupDiscovery();
        removeGroupSilently();
        activePeerName = "";
        targetPeer = null;
        setState(State.IDLE, "");
    }

    void disconnect() {
        if (state == State.IDLE || state == State.DISCONNECTING) {
            return;
        }
        generation++;
        cancelAllTimeouts();
        cleanupInProgress = true;
        connectIssued = false;
        setState(State.DISCONNECTING, activePeerName);
        closeTransport();
        cleanupDiscovery();
        cancelConnectSilently();
        removeExistingGroup(generation, () -> {
            cleanupInProgress = false;
            activePeerName = "";
            targetPeer = null;
            setState(isWifiEnabled() ? State.IDLE : State.NEEDS_WIFI, "");
        });
    }

    void shutdown() {
        generation++;
        cancelAllTimeouts();
        cleanupInProgress = false;
        connectIssued = false;
        unregister();
        cleanupDiscovery();
        cancelConnectSilently();
        closeTransport();
        removeGroupSilently();
    }

    private void prepareEnvironment(int expectedGeneration, Runnable onReady) {
        if (!isGenerationCurrent(expectedGeneration)) {
            return;
        }
        cleanupInProgress = true;
        mainHandler.removeCallbacks(preparationTimeout);
        mainHandler.postDelayed(preparationTimeout, PREPARATION_TIMEOUT_MS);

        clearDiscoveryState(expectedGeneration, () -> cancelPendingConnection(
                expectedGeneration,
                () -> removeExistingGroup(expectedGeneration, () -> {
                    if (!isGenerationCurrent(expectedGeneration)) {
                        return;
                    }
                    cleanupInProgress = false;
                    mainHandler.removeCallbacks(preparationTimeout);
                    onReady.run();
                })
        ));
    }

    private void clearDiscoveryState(int expectedGeneration, Runnable next) {
        if (!isGenerationCurrent(expectedGeneration)) {
            return;
        }
        serviceRequest = null;
        runAction(
                listener -> manager.stopPeerDiscovery(channel, listener),
                expectedGeneration,
                () -> runAction(
                        listener -> manager.clearServiceRequests(channel, listener),
                        expectedGeneration,
                        () -> runAction(
                                listener -> manager.clearLocalServices(channel, listener),
                                expectedGeneration,
                                next
                        )
                )
        );
    }

    private void cancelPendingConnection(int expectedGeneration, Runnable next) {
        runAction(
                listener -> manager.cancelConnect(channel, listener),
                expectedGeneration,
                next
        );
    }

    private void removeExistingGroup(int expectedGeneration, Runnable next) {
        if (!isGenerationCurrent(expectedGeneration)) {
            return;
        }
        try {
            manager.requestGroupInfo(channel, group -> {
                if (!isGenerationCurrent(expectedGeneration)) {
                    return;
                }
                if (group == null) {
                    next.run();
                    return;
                }
                removeGroupWithRetry(expectedGeneration, 0, next);
            });
        } catch (RuntimeException error) {
            next.run();
        }
    }

    private void removeGroupWithRetry(int expectedGeneration, int attempt, Runnable next) {
        if (!isGenerationCurrent(expectedGeneration)) {
            return;
        }
        try {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    if (isGenerationCurrent(expectedGeneration)) {
                        next.run();
                    }
                }

                @Override
                public void onFailure(int reason) {
                    if (!isGenerationCurrent(expectedGeneration)) {
                        return;
                    }
                    if (reason == WifiP2pManager.BUSY && attempt == 0) {
                        mainHandler.postDelayed(
                                () -> removeGroupWithRetry(expectedGeneration, 1, next),
                                500L
                        );
                    } else {
                        next.run();
                    }
                }
            });
        } catch (RuntimeException error) {
            next.run();
        }
    }

    private interface ActionStarter {
        void start(WifiP2pManager.ActionListener listener);
    }

    private void runAction(ActionStarter starter, int expectedGeneration, Runnable next) {
        if (!isGenerationCurrent(expectedGeneration)) {
            return;
        }
        try {
            starter.start(new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    if (isGenerationCurrent(expectedGeneration)) {
                        next.run();
                    }
                }

                @Override
                public void onFailure(int reason) {
                    if (isGenerationCurrent(expectedGeneration)) {
                        next.run();
                    }
                }
            });
        } catch (RuntimeException error) {
            next.run();
        }
    }

    private void configureServiceDiscovery(int expectedGeneration) {
        if (!isGenerationCurrent(expectedGeneration) || !isWifiEnabled()) {
            return;
        }
        discoveryAttempt++;

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
                    if (!isGenerationCurrent(expectedGeneration)
                            || state != State.DISCOVERING
                            || !SERVICE_INSTANCE.equals(instanceName)
                            || registrationType == null
                            || !registrationType.startsWith(SERVICE_TYPE)) {
                        return;
                    }
                    addPeer(device);
                },
                (fullDomainName, txtRecordMap, device) -> {
                    if (!isGenerationCurrent(expectedGeneration)
                            || state != State.DISCOVERING
                            || txtRecordMap == null
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
                handleDiscoveryFailure(expectedGeneration, reason);
            }
        });
    }

    private void addServiceRequestAndDiscover(int expectedGeneration) {
        if (!isGenerationCurrent(expectedGeneration)) {
            return;
        }
        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE);
        manager.addServiceRequest(channel, serviceRequest, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        if (isGenerationCurrent(expectedGeneration)) {
                            setState(State.DISCOVERING, "");
                        }
                    }

                    @Override
                    public void onFailure(int reason) {
                        handleDiscoveryFailure(expectedGeneration, reason);
                    }
                });
            }

            @Override
            public void onFailure(int reason) {
                handleDiscoveryFailure(expectedGeneration, reason);
            }
        });
    }

    private void handleDiscoveryFailure(int expectedGeneration, int reason) {
        if (!isGenerationCurrent(expectedGeneration)) {
            return;
        }
        if (reason == WifiP2pManager.BUSY && discoveryAttempt < MAX_DISCOVERY_ATTEMPTS) {
            setState(State.PREPARING, "");
            prepareEnvironment(expectedGeneration, () -> configureServiceDiscovery(expectedGeneration));
            return;
        }
        fail(messageForReason(reason));
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

    private void performConnect(int expectedGeneration) {
        PeerDevice peer = targetPeer;
        if (!isGenerationCurrent(expectedGeneration)
                || peer == null
                || state != State.CONNECTING) {
            return;
        }
        connectAttempt++;
        connectIssued = true;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = peer.address;
        config.wps.setup = WpsInfo.PBC;
        config.groupOwnerIntent = 7;
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                pollConnectionInfo(expectedGeneration);
            }

            @Override
            public void onFailure(int reason) {
                connectIssued = false;
                if (!isGenerationCurrent(expectedGeneration)) {
                    return;
                }
                if (reason == WifiP2pManager.BUSY
                        && connectAttempt < MAX_CONNECT_ATTEMPTS) {
                    prepareEnvironment(expectedGeneration, () -> performConnect(expectedGeneration));
                } else {
                    fail(messageForReason(reason));
                }
            }
        });
    }

    private void pollConnectionInfo(int expectedGeneration) {
        if (!isGenerationCurrent(expectedGeneration)
                || (state != State.CONNECTING && state != State.HANDSHAKING)
                || transportStarted) {
            return;
        }
        try {
            manager.requestConnectionInfo(channel, info -> {
                if (!isGenerationCurrent(expectedGeneration)) {
                    return;
                }
                if (info != null && info.groupFormed) {
                    handleConnectionInfo(expectedGeneration, info);
                } else {
                    mainHandler.postDelayed(
                            () -> pollConnectionInfo(expectedGeneration),
                            CONNECTION_INFO_POLL_MS
                    );
                }
            });
        } catch (RuntimeException error) {
            mainHandler.postDelayed(
                    () -> pollConnectionInfo(expectedGeneration),
                    CONNECTION_INFO_POLL_MS
            );
        }
    }

    private void handleConnectionInfo(int expectedGeneration, WifiP2pInfo info) {
        if (!isGenerationCurrent(expectedGeneration)
                || info == null
                || !info.groupFormed
                || transportStarted) {
            return;
        }
        cleanupInProgress = false;
        connectIssued = true;
        cleanupDiscovery();
        transportStarted = true;
        setState(State.HANDSHAKING, activePeerName);

        final SessionTransport[] holder = new SessionTransport[1];
        SessionTransport newTransport = new SessionTransport(
                localDeviceName(),
                new SessionTransport.Callback() {
                    @Override
                    public void onConnected(String peerName) {
                        mainHandler.post(() -> {
                            if (transport != holder[0]
                                    || !isGenerationCurrent(expectedGeneration)) {
                                return;
                            }
                            cancelAllTimeouts();
                            activePeerName = peerName;
                            setState(State.CONNECTED, peerName);
                        });
                    }

                    @Override
                    public void onClosed(boolean unexpected) {
                        mainHandler.post(() -> {
                            if (transport != holder[0]
                                    || !isGenerationCurrent(expectedGeneration)) {
                                return;
                            }
                            transport = null;
                            transportStarted = false;
                            connectIssued = false;
                            cancelAllTimeouts();
                            if (state == State.DISCONNECTING || state == State.IDLE) {
                                return;
                            }
                            activePeerName = "";
                            setState(State.ERROR, "");
                            listener.onFriendlyMessage(
                                    unexpected
                                            ? R.string.session_connection_lost
                                            : R.string.session_connection_failed
                            );
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
            manager.clearServiceRequests(channel, ignored);
            manager.clearLocalServices(channel, ignored);
            serviceRequest = null;
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

    private void closeTransport() {
        SessionTransport current = transport;
        transport = null;
        transportStarted = false;
        if (current != null) {
            current.closeByUser();
        }
    }

    private void cancelAllTimeouts() {
        mainHandler.removeCallbacks(preparationTimeout);
        mainHandler.removeCallbacks(connectTimeout);
    }

    private void fail(int messageResId) {
        cancelAllTimeouts();
        cleanupInProgress = false;
        connectIssued = false;
        closeTransport();
        cleanupDiscovery();
        cancelConnectSilently();
        removeGroupSilently();
        activePeerName = "";
        setState(State.ERROR, "");
        listener.onFriendlyMessage(messageResId);
    }

    private void setState(State newState, String peerName) {
        state = newState;
        listener.onStateChanged(newState, peerName == null ? "" : peerName);
    }

    private boolean isGenerationCurrent(int expectedGeneration) {
        return expectedGeneration == generation && isWifiEnabled();
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
                    generation++;
                    cancelAllTimeouts();
                    cleanupInProgress = false;
                    connectIssued = false;
                    closeTransport();
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
                    int expectedGeneration = generation;
                    manager.requestConnectionInfo(
                            channel,
                            info -> handleConnectionInfo(expectedGeneration, info)
                    );
                    return;
                }

                if (cleanupInProgress) {
                    return;
                }

                if (state == State.CONNECTED || state == State.HANDSHAKING) {
                    cancelAllTimeouts();
                    closeTransport();
                    connectIssued = false;
                    activePeerName = "";
                    setState(State.ERROR, "");
                    listener.onFriendlyMessage(R.string.session_connection_lost);
                }
                // During CONNECTING, transient disconnected broadcasts are expected while
                // Android negotiates or removes a previous group. The explicit timeout and
                // connection-info polling decide whether the attempt really failed.
            }
        }
    }
}
