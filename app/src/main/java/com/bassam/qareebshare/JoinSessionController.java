package com.bassam.qareebshare;

import android.annotation.SuppressLint;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Handler;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns only the "find and join a session" role. It never advertises or creates a group. */
@SuppressLint("MissingPermission")
final class JoinSessionController {
    interface Callback {
        void onPreparing();
        void onSearching();
        void onHostsChanged(List<PeerDevice> hosts);
        void onConnecting(String hostName);
        void onHostAddressReady(String hostAddress);
        void onFailure(int messageResId);
    }

    private static final long REFRESH_INTERVAL_MS = 12_000L;
    private static final long CONNECT_TIMEOUT_MS = 45_000L;

    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Handler handler;
    private final P2pSessionCleaner cleaner;
    private final Callback callback;
    private final Map<String, PeerDevice> hosts = new LinkedHashMap<>();

    private WifiP2pDnsSdServiceRequest serviceRequest;
    private int generation;
    private int connectAttempt;
    private boolean clientStarted;
    private PeerDevice targetHost;

    private final Runnable refreshDiscovery = new Runnable() {
        @Override
        public void run() {
            int token = generation;
            if (serviceRequest == null || targetHost != null) {
                return;
            }
            discover(token, false);
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private final Runnable connectionTimeout;

    JoinSessionController(
            WifiP2pManager manager,
            WifiP2pManager.Channel channel,
            Handler handler,
            Callback callback
    ) {
        this.manager = manager;
        this.channel = channel;
        this.handler = handler;
        this.callback = callback;
        cleaner = new P2pSessionCleaner(manager, channel, handler);
        connectionTimeout = () -> {
            if (targetHost == null || clientStarted) {
                return;
            }
            this.callback.onFailure(R.string.session_connection_timed_out);
            cancelConnectionOnly();
        };
    }

    void start() {
        int token = ++generation;
        connectAttempt = 0;
        clientStarted = false;
        targetHost = null;
        hosts.clear();
        callback.onHostsChanged(Collections.emptyList());
        callback.onPreparing();
        cleaner.clean(() -> configureDiscovery(token));
    }

    void join(PeerDevice host) {
        if (host == null || host.address.isEmpty() || targetHost != null) {
            return;
        }
        targetHost = host;
        connectAttempt = 0;
        clientStarted = false;
        callback.onConnecting(host.name);
        stopDiscoveryOnly();
        handler.removeCallbacks(connectionTimeout);
        handler.postDelayed(connectionTimeout, CONNECT_TIMEOUT_MS);
        connectToHost(generation);
    }

    void stop(Runnable completion) {
        generation++;
        handler.removeCallbacks(refreshDiscovery);
        handler.removeCallbacks(connectionTimeout);
        targetHost = null;
        clientStarted = false;
        serviceRequest = null;
        cleaner.clean(completion::run);
    }

    void onConnectionInfo(WifiP2pInfo info) {
        if (targetHost == null || clientStarted || info == null || !info.groupFormed) {
            return;
        }
        if (info.isGroupOwner) {
            callback.onFailure(R.string.session_wrong_connection_role);
            cancelConnectionOnly();
            return;
        }
        InetAddress address = info.groupOwnerAddress;
        String hostAddress = address == null ? null : address.getHostAddress();
        if (hostAddress == null || hostAddress.trim().isEmpty()) {
            return;
        }
        clientStarted = true;
        handler.removeCallbacks(connectionTimeout);
        callback.onHostAddressReady(hostAddress);
    }

    void onConnectionLost() {
        if (targetHost != null && !clientStarted) {
            callback.onFailure(R.string.session_connection_failed);
        }
    }

    private void configureDiscovery(int token) {
        if (token != generation) {
            return;
        }
        manager.setDnsSdResponseListeners(
                channel,
                (instanceName, registrationType, device) -> {
                    if (token != generation
                            || !HostSessionController.SERVICE_INSTANCE.equals(instanceName)
                            || registrationType == null
                            || !registrationType.startsWith(HostSessionController.SERVICE_TYPE)) {
                        return;
                    }
                    addHost(device, null);
                },
                (fullDomainName, record, device) -> {
                    if (token != generation || record == null) {
                        return;
                    }
                    if (!"qareebshare".equals(record.get("app"))
                            || !"host".equals(record.get("role"))
                            || !HostSessionController.PROTOCOL_VERSION.equals(record.get("protocol"))) {
                        return;
                    }
                    addHost(device, record.get("name"));
                }
        );

        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(
                HostSessionController.SERVICE_TYPE
        );
        try {
            manager.addServiceRequest(channel, serviceRequest, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    discover(token, true);
                }

                @Override
                public void onFailure(int reason) {
                    if (token == generation) {
                        callback.onFailure(messageForReason(reason));
                    }
                }
            });
        } catch (RuntimeException error) {
            callback.onFailure(R.string.session_search_failed);
        }
    }

    private void discover(int token, boolean announceState) {
        if (token != generation || serviceRequest == null || targetHost != null) {
            return;
        }
        try {
            manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    if (token != generation) {
                        return;
                    }
                    if (announceState) {
                        callback.onSearching();
                        handler.removeCallbacks(refreshDiscovery);
                        handler.postDelayed(refreshDiscovery, REFRESH_INTERVAL_MS);
                    }
                }

                @Override
                public void onFailure(int reason) {
                    if (token != generation) {
                        return;
                    }
                    if (reason == WifiP2pManager.BUSY && !announceState) {
                        return;
                    }
                    callback.onFailure(messageForReason(reason));
                }
            });
        } catch (RuntimeException error) {
            callback.onFailure(R.string.session_search_failed);
        }
    }

    private void addHost(WifiP2pDevice device, String advertisedName) {
        if (device == null || device.deviceAddress == null
                || device.deviceAddress.trim().isEmpty() || targetHost != null) {
            return;
        }
        String name = advertisedName;
        if (name == null || name.trim().isEmpty()) {
            name = device.deviceName;
        }
        PeerDevice peer = new PeerDevice(name, device.deviceAddress, device.status);
        hosts.put(peer.address, peer);
        ArrayList<PeerDevice> result = new ArrayList<>(hosts.values());
        Collections.sort(result, (left, right) -> left.name.compareToIgnoreCase(right.name));
        callback.onHostsChanged(result);
    }

    private void connectToHost(int token) {
        if (token != generation || targetHost == null) {
            return;
        }
        connectAttempt++;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = targetHost.address;
        config.wps.setup = WpsInfo.PBC;
        // The host already owns an autonomous group. This device must never compete for ownership.
        config.groupOwnerIntent = 0;

        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    pollConnectionInfo(token, 0);
                }

                @Override
                public void onFailure(int reason) {
                    if (token != generation) {
                        return;
                    }
                    if (reason == WifiP2pManager.BUSY && connectAttempt < 3) {
                        cancelConnectThen(() -> handler.postDelayed(
                                () -> connectToHost(token),
                                1_000L
                        ));
                    } else {
                        callback.onFailure(messageForReason(reason));
                    }
                }
            });
        } catch (RuntimeException error) {
            callback.onFailure(R.string.session_connection_failed);
        }
    }

    private void pollConnectionInfo(int token, int attempt) {
        if (token != generation || targetHost == null || clientStarted) {
            return;
        }
        if (attempt >= 90) {
            return;
        }
        try {
            manager.requestConnectionInfo(channel, info -> {
                if (token != generation || clientStarted) {
                    return;
                }
                onConnectionInfo(info);
                if (!clientStarted) {
                    handler.postDelayed(() -> pollConnectionInfo(token, attempt + 1), 500L);
                }
            });
        } catch (RuntimeException error) {
            handler.postDelayed(() -> pollConnectionInfo(token, attempt + 1), 500L);
        }
    }

    private void stopDiscoveryOnly() {
        handler.removeCallbacks(refreshDiscovery);
        try {
            manager.stopPeerDiscovery(channel, ignoredListener());
            if (serviceRequest != null) {
                manager.removeServiceRequest(channel, serviceRequest, ignoredListener());
                serviceRequest = null;
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void cancelConnectionOnly() {
        generation++;
        handler.removeCallbacks(refreshDiscovery);
        handler.removeCallbacks(connectionTimeout);
        targetHost = null;
        clientStarted = false;
        try {
            manager.cancelConnect(channel, ignoredListener());
        } catch (RuntimeException ignored) {
        }
    }

    private void cancelConnectThen(Runnable next) {
        try {
            manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    next.run();
                }

                @Override
                public void onFailure(int reason) {
                    next.run();
                }
            });
        } catch (RuntimeException error) {
            next.run();
        }
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

    private int messageForReason(int reason) {
        if (reason == WifiP2pManager.BUSY) {
            return R.string.session_phone_busy;
        }
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            return R.string.session_not_supported;
        }
        return R.string.session_search_failed;
    }
}
