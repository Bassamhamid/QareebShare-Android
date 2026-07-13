package com.bassam.qareebshare;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class TransferEngine {
    interface Callback {
        void onConnected(String peerName);

        void onIncomingOffer(String peerName, List<TransferItemInfo> items, long totalBytes);

        void onTransferStarted(boolean sending, int itemCount, long totalBytes);

        void onProgress(
                boolean sending,
                String itemName,
                int itemIndex,
                int itemCount,
                long transferredBytes,
                long totalBytes,
                long bytesPerSecond
        );

        void onItemCompleted(boolean sending, String itemName, int itemIndex, int itemCount);

        void onCompleted(boolean sending, int itemCount, long transferredBytes, String saveLocation);

        void onRejected();

        void onFailure(int messageResId);
    }

    static final int PORT = 8988;
    private static final int MAGIC = 0x51534833; // QSH3
    private static final int VERSION = 1;
    private static final int ROLE_SENDER = 1;
    private static final int ROLE_RECEIVER = 2;
    private static final int COMMAND_OFFER = 0x1001;
    private static final int COMMAND_RESPONSE = 0x1002;
    private static final int COMMAND_ITEM = 0x1003;
    private static final int COMMAND_ACK = 0x1004;
    private static final int COMMAND_COMPLETE = 0x1005;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int MAX_ITEMS = 10_000;
    private static final long DECISION_TIMEOUT_MS = 180_000L;

    private final Context context;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger generation = new AtomicInteger();
    private final Object decisionLock = new Object();

    private volatile ServerSocket activeServer;
    private volatile Socket activeSocket;
    private Boolean pendingDecision;

    TransferEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    void startServer(
            boolean localSender,
            String localName,
            List<TransferSource> outgoing,
            Callback callback
    ) {
        int run = restart();
        List<TransferSource> immutable = immutableSources(outgoing);
        executor.execute(() -> runServer(run, localSender, safeText(localName, "هاتف قريب"), immutable, callback));
    }

    void startClient(
            String host,
            boolean localSender,
            String localName,
            List<TransferSource> outgoing,
            Callback callback
    ) {
        int run = restart();
        List<TransferSource> immutable = immutableSources(outgoing);
        executor.execute(() -> runClient(
                run,
                host,
                localSender,
                safeText(localName, "هاتف قريب"),
                immutable,
                callback
        ));
    }

    void respondToOffer(boolean accept) {
        synchronized (decisionLock) {
            if (pendingDecision == null) {
                pendingDecision = accept;
                decisionLock.notifyAll();
            }
        }
    }

    void cancel() {
        generation.incrementAndGet();
        synchronized (decisionLock) {
            pendingDecision = false;
            decisionLock.notifyAll();
        }
        closeActiveResources();
    }

    void shutdown() {
        cancel();
        executor.shutdownNow();
    }

    private int restart() {
        cancel();
        synchronized (decisionLock) {
            pendingDecision = null;
        }
        return generation.get();
    }

    private void runServer(
            int run,
            boolean localSender,
            String localName,
            List<TransferSource> outgoing,
            Callback callback
    ) {
        try (ServerSocket server = new ServerSocket()) {
            activeServer = server;
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            server.setSoTimeout(180_000);

            try (Socket socket = server.accept()) {
                if (!isCurrent(run)) {
                    return;
                }
                activeSocket = socket;
                configure(socket);
                runSession(run, socket, true, localSender, localName, outgoing, callback);
            }
        } catch (SocketTimeoutException ignored) {
            failIfCurrent(run, callback, R.string.transfer_connection_timeout);
        } catch (IOException ignored) {
            failIfCurrent(run, callback, R.string.transfer_failed);
        } finally {
            activeServer = null;
            activeSocket = null;
        }
    }

    private void runClient(
            int run,
            String host,
            boolean localSender,
            String localName,
            List<TransferSource> outgoing,
            Callback callback
    ) {
        if (host == null || host.trim().isEmpty()) {
            failIfCurrent(run, callback, R.string.transfer_failed);
            return;
        }

        for (int attempt = 0; attempt < 40 && isCurrent(run); attempt++) {
            try (Socket socket = new Socket()) {
                activeSocket = socket;
                try {
                    socket.connect(new InetSocketAddress(host, PORT), 1_500);
                } catch (IOException connectionError) {
                    sleepBeforeRetry();
                    continue;
                }
                configure(socket);
                try {
                    runSession(run, socket, false, localSender, localName, outgoing, callback);
                } catch (IOException transferError) {
                    failIfCurrent(run, callback, R.string.transfer_failed);
                }
                return;
            } catch (IOException ignored) {
                failIfCurrent(run, callback, R.string.transfer_failed);
                return;
            } finally {
                activeSocket = null;
            }
        }
        failIfCurrent(run, callback, R.string.transfer_connection_timeout);
    }

    private void runSession(
            int run,
            Socket socket,
            boolean serverSide,
            boolean localSender,
            String localName,
            List<TransferSource> outgoing,
            Callback callback
    ) throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(
                socket.getInputStream(), BUFFER_SIZE));
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                socket.getOutputStream(), BUFFER_SIZE));

        PeerHello peer;
        if (serverSide) {
            peer = readHello(input);
            writeHello(output, localName, localSender);
        } else {
            writeHello(output, localName, localSender);
            peer = readHello(input);
        }

        if (peer.sender == localSender) {
            throw new IOException("Both peers selected the same transfer role");
        }
        if (!isCurrent(run)) {
            return;
        }
        callback.onConnected(peer.name);

        if (localSender) {
            runSendingSession(run, input, output, outgoing, callback);
        } else {
            runReceivingSession(run, input, output, peer.name, callback);
        }
    }

    private void runSendingSession(
            int run,
            DataInputStream input,
            DataOutputStream output,
            List<TransferSource> outgoing,
            Callback callback
    ) throws IOException {
        if (outgoing.isEmpty()) {
            throw new IOException("No transfer items");
        }

        String sessionId = UUID.randomUUID().toString();
        writeOffer(output, sessionId, outgoing);

        expectCommand(input, COMMAND_RESPONSE);
        boolean accepted = input.readBoolean();
        if (!accepted) {
            if (isCurrent(run)) {
                callback.onRejected();
            }
            return;
        }

        long totalBytes = knownTotal(outgoing);
        callback.onTransferStarted(true, outgoing.size(), totalBytes);
        ProgressTracker tracker = new ProgressTracker(true, outgoing.size(), totalBytes, callback);

        for (int index = 0; index < outgoing.size(); index++) {
            ensureCurrent(run);
            TransferSource source = outgoing.get(index);
            output.writeInt(COMMAND_ITEM);
            output.writeInt(index);

            MessageDigest digest = sha256();
            try (InputStream sourceInput = new BufferedInputStream(source.opener.open(), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = sourceInput.read(buffer)) != -1) {
                    ensureCurrent(run);
                    if (count == 0) {
                        continue;
                    }
                    output.writeInt(count);
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    tracker.add(source.info.displayName, index, count);
                }
            }

            output.writeInt(0);
            byte[] hash = digest.digest();
            output.writeInt(hash.length);
            output.write(hash);
            output.flush();

            expectCommand(input, COMMAND_ACK);
            int ackIndex = input.readInt();
            boolean valid = input.readBoolean();
            if (ackIndex != index || !valid) {
                throw new IOException("Receiver rejected transferred item");
            }
            callback.onItemCompleted(true, source.info.displayName, index + 1, outgoing.size());
        }

        output.writeInt(COMMAND_COMPLETE);
        output.flush();
        callback.onCompleted(true, outgoing.size(), tracker.transferredBytes(), "");
    }

    private void runReceivingSession(
            int run,
            DataInputStream input,
            DataOutputStream output,
            String peerName,
            Callback callback
    ) throws IOException {
        expectCommand(input, COMMAND_OFFER);
        readLimitedText(input, 80); // Session identifier, reserved for resume in the next batch.
        int itemCount = input.readInt();
        if (itemCount <= 0 || itemCount > MAX_ITEMS) {
            throw new IOException("Invalid item count");
        }

        ArrayList<TransferItemInfo> items = new ArrayList<>(itemCount);
        for (int index = 0; index < itemCount; index++) {
            items.add(readItemInfo(input));
        }
        long totalBytes = knownTotalInfo(items);

        synchronized (decisionLock) {
            pendingDecision = null;
        }
        callback.onIncomingOffer(peerName, Collections.unmodifiableList(items), totalBytes);
        boolean accepted = waitForDecision(run);

        output.writeInt(COMMAND_RESPONSE);
        output.writeBoolean(accepted);
        output.flush();
        if (!accepted) {
            callback.onRejected();
            return;
        }

        callback.onTransferStarted(false, itemCount, totalBytes);
        ProgressTracker tracker = new ProgressTracker(false, itemCount, totalBytes, callback);
        String saveLocation = "";

        for (int index = 0; index < itemCount; index++) {
            ensureCurrent(run);
            expectCommand(input, COMMAND_ITEM);
            int remoteIndex = input.readInt();
            if (remoteIndex != index) {
                throw new IOException("Unexpected item index");
            }

            TransferItemInfo item = items.get(index);
            ReceivedFileStore.Target target = ReceivedFileStore.create(context, item);
            boolean valid = false;
            try {
                MessageDigest digest = sha256();
                OutputStream fileOutput = new BufferedOutputStream(target.outputStream(), BUFFER_SIZE);
                byte[] buffer = new byte[BUFFER_SIZE];
                while (true) {
                    ensureCurrent(run);
                    int chunkSize = input.readInt();
                    if (chunkSize == 0) {
                        break;
                    }
                    if (chunkSize < 0 || chunkSize > BUFFER_SIZE) {
                        throw new IOException("Invalid transfer chunk");
                    }
                    input.readFully(buffer, 0, chunkSize);
                    fileOutput.write(buffer, 0, chunkSize);
                    digest.update(buffer, 0, chunkSize);
                    tracker.add(item.displayName, index, chunkSize);
                }
                fileOutput.flush();

                int hashLength = input.readInt();
                if (hashLength != 32) {
                    throw new IOException("Invalid digest length");
                }
                byte[] expectedHash = new byte[hashLength];
                input.readFully(expectedHash);
                valid = MessageDigest.isEqual(expectedHash, digest.digest());
                if (valid) {
                    target.commit();
                    saveLocation = target.displayLocation();
                } else {
                    target.abort();
                }
            } catch (IOException | RuntimeException error) {
                target.abort();
                throw error;
            } finally {
                output.writeInt(COMMAND_ACK);
                output.writeInt(index);
                output.writeBoolean(valid);
                output.flush();
            }

            if (!valid) {
                throw new IOException("Hash verification failed");
            }
            callback.onItemCompleted(false, item.displayName, index + 1, itemCount);
        }

        expectCommand(input, COMMAND_COMPLETE);
        callback.onCompleted(false, itemCount, tracker.transferredBytes(), saveLocation);
    }

    private void writeHello(DataOutputStream output, String deviceName, boolean sender) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeByte(sender ? ROLE_SENDER : ROLE_RECEIVER);
        output.writeUTF(safeText(deviceName, "هاتف قريب"));
        output.flush();
    }

    private PeerHello readHello(DataInputStream input) throws IOException {
        int magic;
        try {
            magic = input.readInt();
        } catch (EOFException error) {
            throw new IOException("Incomplete handshake", error);
        }
        int version = input.readInt();
        int role = input.readUnsignedByte();
        if (magic != MAGIC || version != VERSION) {
            throw new IOException("Unsupported peer");
        }
        if (role != ROLE_SENDER && role != ROLE_RECEIVER) {
            throw new IOException("Invalid peer role");
        }
        return new PeerHello(readLimitedText(input, 80), role == ROLE_SENDER);
    }

    private void writeOffer(
            DataOutputStream output,
            String sessionId,
            List<TransferSource> outgoing
    ) throws IOException {
        output.writeInt(COMMAND_OFFER);
        output.writeUTF(sessionId);
        output.writeInt(outgoing.size());
        for (TransferSource source : outgoing) {
            writeItemInfo(output, source.info);
        }
        output.flush();
    }

    private void writeItemInfo(DataOutputStream output, TransferItemInfo item) throws IOException {
        output.writeUTF(safeText(item.id, UUID.randomUUID().toString()));
        output.writeUTF(FileNameSanitizer.sanitize(item.displayName, "ملف"));
        output.writeUTF(safeText(item.mimeType, "application/octet-stream"));
        output.writeLong(item.size);
        output.writeByte(item.kind);
        output.writeUTF(safeText(item.groupId, ""));
        output.writeUTF(safeText(item.packageName, ""));
        output.writeUTF(safeText(item.partName, ""));
    }

    private TransferItemInfo readItemInfo(DataInputStream input) throws IOException {
        String id = readLimitedText(input, 100);
        String name = FileNameSanitizer.sanitize(readLimitedText(input, 240), "ملف");
        String mime = readLimitedText(input, 160);
        long size = input.readLong();
        int kind = input.readUnsignedByte();
        if (kind != TransferItemInfo.KIND_FILE && kind != TransferItemInfo.KIND_APP) {
            throw new IOException("Invalid item kind");
        }
        String groupId = readLimitedText(input, 180);
        String packageName = readLimitedText(input, 220);
        String partName = readLimitedText(input, 180);
        return new TransferItemInfo(id, name, mime, size, kind, groupId, packageName, partName);
    }

    private String readLimitedText(DataInputStream input, int maxLength) throws IOException {
        String value = input.readUTF();
        if (value.length() > maxLength) {
            throw new IOException("Text field is too long");
        }
        return value;
    }

    private void expectCommand(DataInputStream input, int expected) throws IOException {
        int actual = input.readInt();
        if (actual != expected) {
            throw new IOException("Unexpected protocol command");
        }
    }

    private boolean waitForDecision(int run) throws IOException {
        long deadline = System.currentTimeMillis() + DECISION_TIMEOUT_MS;
        synchronized (decisionLock) {
            while (pendingDecision == null && isCurrent(run)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    pendingDecision = false;
                    break;
                }
                try {
                    decisionLock.wait(Math.min(remaining, 1_000L));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Decision interrupted", error);
                }
            }
            ensureCurrent(run);
            return Boolean.TRUE.equals(pendingDecision);
        }
    }

    private void configure(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setReceiveBufferSize(BUFFER_SIZE);
        socket.setSendBufferSize(BUFFER_SIZE);
        socket.setSoTimeout(180_000);
    }

    private MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private long knownTotal(List<TransferSource> sources) {
        long total = 0L;
        for (TransferSource source : sources) {
            if (source.info.size < 0L) {
                return -1L;
            }
            if (Long.MAX_VALUE - total < source.info.size) {
                return -1L;
            }
            total += source.info.size;
        }
        return total;
    }

    private long knownTotalInfo(List<TransferItemInfo> items) {
        long total = 0L;
        for (TransferItemInfo item : items) {
            if (item.size < 0L) {
                return -1L;
            }
            if (Long.MAX_VALUE - total < item.size) {
                return -1L;
            }
            total += item.size;
        }
        return total;
    }

    private List<TransferSource> immutableSources(List<TransferSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(sources));
    }

    private void ensureCurrent(int run) throws IOException {
        if (!isCurrent(run)) {
            throw new IOException("Transfer cancelled");
        }
    }

    private boolean isCurrent(int run) {
        return generation.get() == run && !Thread.currentThread().isInterrupted();
    }

    private void failIfCurrent(int run, Callback callback, int messageResId) {
        if (isCurrent(run)) {
            callback.onFailure(messageResId);
        }
    }

    private String safeText(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            text = fallback == null ? "" : fallback;
        }
        return text.length() > 240 ? text.substring(0, 240) : text;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
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

    private static final class PeerHello {
        final String name;
        final boolean sender;

        PeerHello(String name, boolean sender) {
            this.name = name;
            this.sender = sender;
        }
    }

    private static final class ProgressTracker {
        private final boolean sending;
        private final int itemCount;
        private final long totalBytes;
        private final Callback callback;
        private long transferred;
        private long lastReportedBytes;
        private long lastReportedAt = System.nanoTime();
        private long lastCallbackAt;

        ProgressTracker(boolean sending, int itemCount, long totalBytes, Callback callback) {
            this.sending = sending;
            this.itemCount = itemCount;
            this.totalBytes = totalBytes;
            this.callback = callback;
        }

        void add(String itemName, int itemIndex, int count) {
            transferred += count;
            long now = System.nanoTime();
            if (now - lastCallbackAt < 250_000_000L && (totalBytes < 0L || transferred < totalBytes)) {
                return;
            }
            long elapsedNanos = Math.max(1L, now - lastReportedAt);
            long delta = Math.max(0L, transferred - lastReportedBytes);
            long bytesPerSecond = (long) (delta * 1_000_000_000.0d / elapsedNanos);
            lastCallbackAt = now;
            lastReportedAt = now;
            lastReportedBytes = transferred;
            callback.onProgress(
                    sending,
                    itemName,
                    itemIndex + 1,
                    itemCount,
                    transferred,
                    totalBytes,
                    bytesPerSecond
            );
        }

        long transferredBytes() {
            return transferred;
        }
    }
}
