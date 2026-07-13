package com.bassam.qareebshare;

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
import java.util.concurrent.atomic.AtomicInteger;

final class LocalHandshake {
    interface Callback {
        void onSuccess(String peerName);

        void onFailure();
    }

    static final int PORT = 8988;
    private static final int MAGIC = 0x51534832; // QSH2
    private static final int VERSION = 1;
    private static final int BUFFER_SIZE = 256 * 1024;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger generation = new AtomicInteger();

    private volatile ServerSocket activeServer;
    private volatile Socket activeSocket;

    void startServer(String localName, Callback callback) {
        int run = restart();
        executor.execute(() -> runServer(run, safeName(localName), callback));
    }

    void startClient(String host, String localName, Callback callback) {
        int run = restart();
        executor.execute(() -> runClient(run, host, safeName(localName), callback));
    }

    void stop() {
        generation.incrementAndGet();
        closeActiveResources();
    }

    void shutdown() {
        stop();
        executor.shutdownNow();
    }

    private int restart() {
        stop();
        return generation.get();
    }

    private void runServer(int run, String localName, Callback callback) {
        try (ServerSocket server = new ServerSocket()) {
            activeServer = server;
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            server.setSoTimeout(120_000);

            try (Socket socket = server.accept()) {
                if (!isCurrent(run)) {
                    return;
                }
                activeSocket = socket;
                configure(socket);
                String peer = receiveThenReply(socket, localName);
                if (isCurrent(run)) {
                    callback.onSuccess(peer);
                }
            }
        } catch (SocketTimeoutException ignored) {
            if (isCurrent(run)) {
                callback.onFailure();
            }
        } catch (IOException ignored) {
            if (isCurrent(run)) {
                callback.onFailure();
            }
        } finally {
            activeServer = null;
            activeSocket = null;
        }
    }

    private void runClient(int run, String host, String localName, Callback callback) {
        if (host == null || host.trim().isEmpty()) {
            if (isCurrent(run)) {
                callback.onFailure();
            }
            return;
        }

        for (int attempt = 0; attempt < 30 && isCurrent(run); attempt++) {
            try (Socket socket = new Socket()) {
                activeSocket = socket;
                socket.connect(new InetSocketAddress(host, PORT), 1_500);
                configure(socket);
                String peer = sendThenReceive(socket, localName);
                if (isCurrent(run)) {
                    callback.onSuccess(peer);
                }
                return;
            } catch (IOException ignored) {
                sleepBeforeRetry();
            } finally {
                activeSocket = null;
            }
        }

        if (isCurrent(run)) {
            callback.onFailure();
        }
    }

    private String receiveThenReply(Socket socket, String localName) throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(
                socket.getInputStream(), BUFFER_SIZE));
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                socket.getOutputStream(), BUFFER_SIZE));

        String peer = readHello(input);
        writeHello(output, localName);
        return peer;
    }

    private String sendThenReceive(Socket socket, String localName) throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(
                socket.getInputStream(), BUFFER_SIZE));
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                socket.getOutputStream(), BUFFER_SIZE));

        writeHello(output, localName);
        return readHello(input);
    }

    private void writeHello(DataOutputStream output, String deviceName) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeUTF(deviceName);
        output.flush();
    }

    private String readHello(DataInputStream input) throws IOException {
        int magic;
        try {
            magic = input.readInt();
        } catch (EOFException error) {
            throw new IOException("Incomplete handshake", error);
        }
        int version = input.readInt();
        if (magic != MAGIC || version != VERSION) {
            throw new IOException("Unsupported peer");
        }
        return safeName(input.readUTF());
    }

    private void configure(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setReceiveBufferSize(BUFFER_SIZE);
        socket.setSendBufferSize(BUFFER_SIZE);
        socket.setSoTimeout(15_000);
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isCurrent(int run) {
        return generation.get() == run && !Thread.currentThread().isInterrupted();
    }

    private String safeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "هاتف قريب";
        }
        String trimmed = name.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }

    private void closeActiveResources() {
        Socket socket = activeSocket;
        activeSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        ServerSocket server = activeServer;
        activeServer = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
    }
}
