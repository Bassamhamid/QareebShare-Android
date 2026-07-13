package com.bassam.qareebshare;

import android.annotation.SuppressLint;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.os.Handler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Owns only the "create a session" role. It never searches or joins. */
@SuppressLint("MissingPermission")
final class HostSessionController {
    interface Callback {
        void onPreparing();
        void onGroupReady();
        void onWaiting();
        void onFailure(int messageResId);
    }

    static final String SERVICE_INSTANCE = "QareebShareHost";
    static final String SERVICE_TYPE = "_qareebshare._tcp";
    static final String PROTOCOL_VERSION = "2";

    private static final int MAX_GROUP_POLLS = 36;

    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Handler handler;
    private final P2pSessionCleaner cleaner;
    private final Callback callback;
    private final String localName;

    private int generation;
    private boolean groupReady;
    private boolean advertised;

    HostSessionController(
            WifiP2pManager manager,
            WifiP2pManager.Channel channel,
            Handler handler,
            String localName,
            Callback callback
    ) {
        this.manager = manager;
        this.channel = channel;
        this.handler = handler;
        this.localName = localName;
        this.callback = callback;
        cleaner = new P2pSessionCleaner(manager, channel, handler);
    }

    void start() {
        int token = ++generation;
        groupReady = false;
        advertised = false;
        callback.onPreparing();
        cleaner.clean(() -> createGroup(token));
    }

    void stop(Runnable completion) {
        generation++;
        groupReady = false;
        advertised = false;
        cleaner.clean(completion::run);
    }

    void onConnectionInfo(WifiP2pInfo info) {
        if (info == null || !info.groupFormed || !info.isGroupOwner) {
            return;
        }
        if (!groupReady) {
            groupReady = true;
            callback.onGroupReady();
        }
        if (!advertised) {
            advertise(generation);
        }
    }

    private void createGroup(int token) {
        if (token != generation) {
            return;
        }
        try {
            manager.createGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    if (token == generation) {
                        pollGroup(token, 0);
                    }
                }

                @Override
                public void onFailure(int reason) {
                    if (token == generation) {
                        callback.onFailure(messageForReason(reason));
                    }
                }
            });
        } catch (RuntimeException error) {
            callback.onFailure(R.string.session_could_not_create);
        }
    }

    private void pollGroup(int token, int attempt) {
        if (token != generation) {
            return;
        }
        if (attempt >= MAX_GROUP_POLLS) {
            callback.onFailure(R.string.session_could_not_create);
            return;
        }
        try {
            manager.requestGroupInfo(channel, group -> {
                if (token != generation) {
                    return;
                }
                if (isReadyOwnerGroup(group)) {
                    if (!groupReady) {
                        groupReady = true;
                        callback.onGroupReady();
                    }
                    advertise(token);
                } else {
                    handler.postDelayed(() -> pollGroup(token, attempt + 1), 500L);
                }
            });
        } catch (RuntimeException error) {
            handler.postDelayed(() -> pollGroup(token, attempt + 1), 500L);
        }
    }

    private boolean isReadyOwnerGroup(WifiP2pGroup group) {
        return group != null && group.isGroupOwner();
    }

    private void advertise(int token) {
        if (token != generation || advertised) {
            return;
        }
        Map<String, String> record = new LinkedHashMap<>();
        record.put("app", "qareebshare");
        record.put("role", "host");
        record.put("protocol", PROTOCOL_VERSION);
        record.put("name", localName);
        record.put("session", UUID.randomUUID().toString());

        WifiP2pDnsSdServiceInfo serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                SERVICE_INSTANCE,
                SERVICE_TYPE,
                record
        );
        try {
            manager.addLocalService(channel, serviceInfo, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    if (token != generation) {
                        return;
                    }
                    advertised = true;
                    callback.onWaiting();
                }

                @Override
                public void onFailure(int reason) {
                    if (token == generation) {
                        callback.onFailure(messageForReason(reason));
                    }
                }
            });
        } catch (RuntimeException error) {
            callback.onFailure(R.string.session_could_not_create);
        }
    }

    private int messageForReason(int reason) {
        if (reason == WifiP2pManager.BUSY) {
            return R.string.session_phone_busy;
        }
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
            return R.string.session_not_supported;
        }
        return R.string.session_could_not_create;
    }
}
