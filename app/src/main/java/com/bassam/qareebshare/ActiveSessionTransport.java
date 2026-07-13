package com.bassam.qareebshare;

import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps one bidirectional channel alive for the entire active session. */
final class ActiveSessionTransport {
    interface Callback {
        void onConnected(String peerName);
        void onClosed(boolean unexpected);
    }

    private static final int MAGIC = 0x51534853; // QSHS
    private static final int VERSION = 2;
    private static final int TYPE_PING = 1;
    private static final int TYPE_PONG = 2;
    private static final int TYPE_BYE = 3;
    static final int PORT = 8991;
    private static final long HEARTBEAT_INTERVAL_MS = 5_000L;
    private static final long HEARTBEAT_TIMEOUT_MS = 22_000L;

    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean callbackDelivered = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private final String localName;
    private final Callback callback;
    private volatile Socket socket;
    private volatile ServerSocket serverSocket;
    private volatile DataInputStream input;
    private volatile DataOutputStream output;
    private volatile long lastReadAt;
    private volatile boolean connected;
    private volatile boolean userClosed;

    ActiveSessionTransport(String localName, Callback callback) {
        this.localName = normalizeName(localName);
        this.callback = callback;
    }

    void startServer() {
        ioExecutor.execute(() -> {
            try {
                ServerSocket server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                server.setSoTimeout(1_000);
                serverSocket = server;
                while (!closed.get()) {
                    try {
                        Socket accepted = server.accept();
                        establish(accepted);
                        return;
                    } catch (SocketTimeoutException ignored) {
                        // Wake periodically so cancellation is immediate.
                    }
                }
            } catch (IOException error) {
                closeInternal(true);
            }
        });
    }

    void startClient(String host) {
        ioExecutor.execute(() -> {
            if (host == null || host.trim().isEmpty()) {
                closeInternal(true);
                return;
            }
            long deadline = SystemClock.elapsedRealtime() + 35_000L;
            while (!closed.get() && SystemClock.elapsedRealtime() < deadline) {
                Socket candidate = new Socket();
                try {
                    candidate.connect(new InetSocketAddress(host, PORT), 2_500);
                    establish(candidate);
                    return;
                } catch (IOException error) {
                    closeQuietly(candidate);
                    SystemClock.sleep(500L);
                }
            }
            closeInternal(true);
        });
    }

    void closeByUser() {
        userClosed = true;
        sendFrame(TYPE_BYE);
        closeInternal(false);
    }

    private void establish(Socket connectedSocket) throws IOException {
        if (closed.get()) {
            closeQuietly(connectedSocket);
            return;
        }
        socket = connectedSocket;
        connectedSocket.setTcpNoDelay(true);
        connectedSocket.setKeepAlive(true);
        connectedSocket.setSoTimeout(15_000);

        DataOutputStream localOutput = new DataOutputStream(
                new BufferedOutputStream(connectedSocket.getOutputStream())
        );
        DataInputStream localInput = new DataInputStream(
                new BufferedInputStream(connectedSocket.getInputStream())
        );
        output = localOutput;
        input = localInput;

        synchronized (writeLock) {
            localOutput.writeInt(MAGIC);
            localOutput.writeInt(VERSION);
            localOutput.writeUTF(localName);
            localOutput.flush();
        }

        int peerMagic = localInput.readInt();
        int peerVersion = localInput.readInt();
        String peerName = localInput.readUTF();
        if (peerMagic != MAGIC || peerVersion != VERSION) {
            throw new IOException("Unsupported peer protocol");
        }

        connectedSocket.setSoTimeout(0);
        connected = true;
        lastReadAt = SystemClock.elapsedRealtime();
        startHeartbeat();
        callback.onConnected(normalizeName(peerName));
        readLoop();
    }

    private void startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!connected || closed.get()) {
                return;
            }
            long silentFor = SystemClock.elapsedRealtime() - lastReadAt;
            if (silentFor > HEARTBEAT_TIMEOUT_MS) {
                closeInternal(true);
                return;
            }
            sendFrame(TYPE_PING);
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                int type = input.readInt();
                lastReadAt = SystemClock.elapsedRealtime();
                if (type == TYPE_PING) {
                    sendFrame(TYPE_PONG);
                } else if (type == TYPE_BYE) {
                    closeInternal(false);
                    return;
                }
            }
        } catch (EOFException error) {
            closeInternal(!userClosed);
        } catch (IOException error) {
            closeInternal(!userClosed);
        }
    }

    private void sendFrame(int type) {
        DataOutputStream localOutput = output;
        if (localOutput == null || closed.get()) {
            return;
        }
        synchronized (writeLock) {
            try {
                localOutput.writeInt(type);
                localOutput.flush();
            } catch (IOException error) {
                closeInternal(!userClosed);
            }
        }
    }

    private void closeInternal(boolean unexpected) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        connected = false;
        closeQuietly(input);
        closeQuietly(output);
        closeQuietly(socket);
        closeQuietly(serverSocket);
        heartbeatExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        if (callbackDelivered.compareAndSet(false, true)) {
            callback.onClosed(unexpected);
        }
    }

    private static String normalizeName(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "هاتف قريب";
        }
        return value.trim();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(ServerSocket value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (IOException ignored) {
        }
    }
}
