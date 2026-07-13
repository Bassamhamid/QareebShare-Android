package com.bassam.qareebshare;

import android.annotation.SuppressLint;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;

import java.util.concurrent.atomic.AtomicBoolean;

/** Performs Wi-Fi P2P cleanup in a deterministic sequence. */
@SuppressLint("MissingPermission")
final class P2pSessionCleaner {
    interface Completion {
        void onComplete();
    }

    private interface Operation {
        void run(WifiP2pManager.ActionListener listener);
    }

    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Handler handler;
    private int generation;

    P2pSessionCleaner(
            WifiP2pManager manager,
            WifiP2pManager.Channel channel,
            Handler handler
    ) {
        this.manager = manager;
        this.channel = channel;
        this.handler = handler;
    }

    void cancelPending() {
        generation++;
    }

    void clean(Completion completion) {
        final int token = ++generation;
        final AtomicBoolean delivered = new AtomicBoolean(false);
        final Runnable finish = () -> {
            if (token != generation || !delivered.compareAndSet(false, true)) {
                return;
            }
            generation++;
            completion.onComplete();
        };

        runStep(token, listener -> manager.stopPeerDiscovery(channel, listener), () ->
                runStep(token, listener -> manager.clearServiceRequests(channel, listener), () ->
                        runStep(token, listener -> manager.clearLocalServices(channel, listener), () ->
                                runStep(token, listener -> manager.cancelConnect(channel, listener), () ->
                                        removeExistingGroup(token, finish)
                                )
                        )
                )
        );

        // A vendor implementation must not be able to leave the UI blocked forever.
        handler.postDelayed(finish, 6_000L);
    }

    private void removeExistingGroup(int token, Runnable next) {
        if (token != generation) {
            return;
        }
        try {
            manager.requestGroupInfo(channel, group -> {
                if (token != generation) {
                    return;
                }
                if (group == null) {
                    next.run();
                    return;
                }
                removeGroup(token, group, next);
            });
        } catch (RuntimeException error) {
            next.run();
        }
    }

    private void removeGroup(int token, WifiP2pGroup group, Runnable next) {
        if (token != generation || group == null) {
            return;
        }
        runStep(token, listener -> manager.removeGroup(channel, listener), () ->
                handler.postDelayed(next, 250L)
        );
    }

    private void runStep(int token, Operation operation, Runnable next) {
        if (token != generation) {
            return;
        }
        try {
            operation.run(new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    if (token == generation) {
                        next.run();
                    }
                }

                @Override
                public void onFailure(int reason) {
                    if (token == generation) {
                        next.run();
                    }
                }
            });
        } catch (RuntimeException error) {
            if (token == generation) {
                next.run();
            }
        }
    }
}
