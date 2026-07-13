package com.bassam.qareebshare;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class TransferEngine {
    interface Callback {
        void onPairingRequired(String peerName, String pairingCode);

        void onPairingRejected();

        void onConnected(String peerName);

        void onIncomingOffer(String peerName, List<TransferItemInfo> items, long totalBytes);

        void onTransferStarted(boolean sending, int itemCount, long totalBytes, long resumedBytes);

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

        void onCompleted(
                boolean sending,
                int itemCount,
                long transferredBytes,
                String saveLocation,
                String transferId,
                int appCount
        );

        void onRejected();

        void onFailure(int messageResId);
    }

    static final int PORT = 8988;
    private static final int MAGIC = 0x51534834; // QSH4
    private static final int VERSION = 1;
    private static final int FRAME_PAIR_DECISION = 0x2001;
    private static final int FRAME_OFFER = 0x2002;
    private static final int FRAME_RESPONSE = 0x2003;
    private static final int FRAME_ITEM_START = 0x2004;
    private static final int FRAME_ITEM_CHUNK = 0x2005;
    private static final int FRAME_ITEM_END = 0x2006;
    private static final int FRAME_ACK = 0x2007;
    private static final int FRAME_COMPLETE = 0x2008;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int MAX_ITEMS = 10_000;
    private static final int MAX_STRING_BYTES = 16 * 1024;
    private static final int MAX_KEY_BYTES = 1024;
    private static final long DECISION_TIMEOUT_MS = 180_000L;

    private final Context context;
    private final TransferHistoryStore historyStore;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger generation = new AtomicInteger();
    private final Object pairingLock = new Object();
    private final Object offerLock = new Object();

    private volatile ServerSocket activeServer;
    private volatile Socket activeSocket;
    private Boolean pendingPairingDecision;
    private Boolean pendingOfferDecision;

    TransferEngine(Context context) {
        this.context = context.getApplicationContext();
        this.historyStore = new TransferHistoryStore(this.context);
        executor.execute(() -> ReceivedFileStore.cleanupStale(this.context));
    }

    void startServer(
            boolean localSender,
            String localName,
            List<TransferSource> outgoing,
            Callback callback
    ) {
        int run = restart();
        List<TransferSource> immutable = immutableSources(outgoing);
        executor.execute(() -> runServer(
                run,
                localSender,
                safeText(localName, "هاتف قريب"),
                immutable,
                callback
        ));
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

    void respondToPairing(boolean accept) {
        synchronized (pairingLock) {
            if (pendingPairingDecision == null) {
                pendingPairingDecision = accept;
                pairingLock.notifyAll();
            }
        }
    }

    void respondToOffer(boolean accept) {
        synchronized (offerLock) {
            if (pendingOfferDecision == null) {
                pendingOfferDecision = accept;
                offerLock.notifyAll();
            }
        }
    }

    void cancel() {
        generation.incrementAndGet();
        synchronized (pairingLock) {
            pendingPairingDecision = false;
            pairingLock.notifyAll();
        }
        synchronized (offerLock) {
            pendingOfferDecision = false;
            offerLock.notifyAll();
        }
        closeActiveResources();
    }

    void shutdown() {
        cancel();
        executor.shutdownNow();
        historyStore.close();
    }

    private int restart() {
        cancel();
        synchronized (pairingLock) {
            pendingPairingDecision = null;
        }
        synchronized (offerLock) {
            pendingOfferDecision = null;
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
                runSession(run, socket, false, localSender, localName, outgoing, callback);
                return;
            } catch (IOException ignored) {
                if (attempt >= 39) {
                    failIfCurrent(run, callback, R.string.transfer_failed);
                }
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

        try {
            CryptoSession.LocalMaterial localCrypto = CryptoSession.createLocal(localSender);
            PeerHello peer;
            if (serverSide) {
                peer = readHello(input);
                writeHello(output, localName, localCrypto);
            } else {
                writeHello(output, localName, localCrypto);
                peer = readHello(input);
            }
            if (peer.sender == localSender) {
                throw new IOException("Both peers selected the same transfer role");
            }
            if (!isCurrent(run)) {
                return;
            }

            CryptoSession.Result crypto = CryptoSession.derive(
                    localCrypto,
                    peer.publicKey,
                    peer.nonce
            );
            SecureChannel secure = new SecureChannel(
                    input,
                    output,
                    crypto.key,
                    crypto.sendPrefix,
                    crypto.receivePrefix
            );

            synchronized (pairingLock) {
                pendingPairingDecision = null;
            }
            callback.onPairingRequired(peer.name, crypto.pairingCode);
            boolean localPairAccepted = awaitPairingDecision(run);
            secure.writeFrame(FRAME_PAIR_DECISION, frame -> frame.writeBoolean(localPairAccepted));
            SecureChannel.Frame peerPairFrame = secure.readFrame();
            requireType(peerPairFrame, FRAME_PAIR_DECISION);
            boolean peerPairAccepted = peerPairFrame.payloadInput().readBoolean();
            if (!localPairAccepted || !peerPairAccepted) {
                if (isCurrent(run)) {
                    callback.onPairingRejected();
                }
                return;
            }

            callback.onConnected(peer.name);
            if (localSender) {
                runSendingSession(run, secure, peer.name, outgoing, callback);
            } else {
                runReceivingSession(run, secure, peer.name, callback);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failIfCurrent(run, callback, R.string.transfer_cancelled);
        } catch (IOException error) {
            failIfCurrent(run, callback, R.string.transfer_failed);
        } catch (Exception error) {
            failIfCurrent(run, callback, R.string.secure_connection_failed);
        }
    }

    private void runSendingSession(
            int run,
            SecureChannel secure,
            String peerName,
            List<TransferSource> outgoing,
            Callback callback
    ) throws Exception {
        if (outgoing.isEmpty()) {
            throw new IOException("No transfer items");
        }
        String transferId = TransferSourceResolver.transferId(outgoing);
        long totalBytes = totalSizeSources(outgoing);
        secure.writeFrame(FRAME_OFFER, frame -> {
            writeString(frame, transferId);
            frame.writeInt(outgoing.size());
            for (TransferSource source : outgoing) {
                writeItem(frame, source.info);
            }
        });

        SecureChannel.Frame responseFrame = secure.readFrame();
        requireType(responseFrame, FRAME_RESPONSE);
        DataInputStream response = responseFrame.payloadInput();
        boolean accepted = response.readBoolean();
        int offsetCount = response.readInt();
        if (!accepted) {
            callback.onRejected();
            return;
        }
        if (offsetCount != outgoing.size()) {
            throw new IOException("Invalid resume offset count");
        }
        long[] offsets = new long[offsetCount];
        long resumedBytes = 0L;
        for (int index = 0; index < offsets.length; index++) {
            long requested = response.readLong();
            long size = outgoing.get(index).info.size;
            if (requested < 0L || (size >= 0L && requested > size)) {
                throw new IOException("Invalid resume offset");
            }
            if (size < 0L) {
                requested = 0L;
            }
            offsets[index] = requested;
            resumedBytes += requested;
        }

        callback.onTransferStarted(true, outgoing.size(), totalBytes, resumedBytes);
        long transferred = resumedBytes;
        ProgressEmitter progress = new ProgressEmitter(callback, true, totalBytes, transferred);
        byte[] buffer = new byte[BUFFER_SIZE];

        for (int index = 0; index < outgoing.size(); index++) {
            ensureCurrent(run);
            TransferSource source = outgoing.get(index);
            TransferItemInfo item = source.info;
            long offset = offsets[index];
            final int itemIndex = index;
            secure.writeFrame(FRAME_ITEM_START, frame -> {
                frame.writeInt(itemIndex);
                frame.writeLong(offset);
            });

            byte[] fullHash;
            MessageDigest streamingDigest = null;
            if (offset > 0L) {
                fullHash = hashSource(source);
            } else {
                fullHash = null;
                streamingDigest = MessageDigest.getInstance("SHA-256");
            }

            long finalLength = offset;
            try (InputStream sourceInput = source.openAt(offset)) {
                int read;
                while ((read = sourceInput.read(buffer)) != -1) {
                    ensureCurrent(run);
                    if (streamingDigest != null) {
                        streamingDigest.update(buffer, 0, read);
                    }
                    final int chunkLength = read;
                    secure.writeFrame(FRAME_ITEM_CHUNK, frame -> {
                        frame.writeInt(itemIndex);
                        frame.writeInt(chunkLength);
                        frame.write(buffer, 0, chunkLength);
                    });
                    finalLength += read;
                    transferred += read;
                    progress.emit(
                            item.displayName,
                            index + 1,
                            outgoing.size(),
                            transferred,
                            false
                    );
                }
            }
            if (fullHash == null) {
                fullHash = streamingDigest.digest();
            }
            final long completedLength = finalLength;
            final byte[] completedHash = fullHash;
            secure.writeFrame(FRAME_ITEM_END, frame -> {
                frame.writeInt(itemIndex);
                frame.writeLong(completedLength);
                frame.writeInt(completedHash.length);
                frame.write(completedHash);
            });

            SecureChannel.Frame ackFrame = secure.readFrame();
            requireType(ackFrame, FRAME_ACK);
            DataInputStream ack = ackFrame.payloadInput();
            if (ack.readInt() != index || !ack.readBoolean()) {
                throw new IOException("Receiver rejected transferred item");
            }
            progress.emit(item.displayName, index + 1, outgoing.size(), transferred, true);
            callback.onItemCompleted(true, item.displayName, index + 1, outgoing.size());
        }

        secure.writeFrame(FRAME_COMPLETE, frame -> writeString(frame, transferId));
        historyStore.recordSending(transferId, peerName, outgoing, transferred);
        callback.onCompleted(
                true,
                outgoing.size(),
                transferred,
                "",
                transferId,
                0
        );
    }

    private void runReceivingSession(
            int run,
            SecureChannel secure,
            String peerName,
            Callback callback
    ) throws Exception {
        SecureChannel.Frame offerFrame = secure.readFrame();
        requireType(offerFrame, FRAME_OFFER);
        DataInputStream offer = offerFrame.payloadInput();
        String transferId = readString(offer);
        int itemCount = offer.readInt();
        if (itemCount <= 0 || itemCount > MAX_ITEMS) {
            throw new IOException("Invalid transfer item count");
        }
        ArrayList<TransferItemInfo> items = new ArrayList<>(itemCount);
        for (int index = 0; index < itemCount; index++) {
            items.add(readItem(offer));
        }
        long totalBytes = totalSizeItems(items);

        synchronized (offerLock) {
            pendingOfferDecision = null;
        }
        callback.onIncomingOffer(peerName, Collections.unmodifiableList(items), totalBytes);
        boolean accepted = awaitOfferDecision(run);
        long[] offsets = new long[itemCount];
        long resumedBytes = 0L;
        if (accepted) {
            ReceivedFileStore.cleanupStale(context);
            for (int index = 0; index < items.size(); index++) {
                offsets[index] = ReceivedFileStore.resumeOffset(context, transferId, items.get(index));
                resumedBytes += offsets[index];
            }
        }
        secure.writeFrame(FRAME_RESPONSE, frame -> {
            frame.writeBoolean(accepted);
            frame.writeInt(offsets.length);
            for (long offset : offsets) {
                frame.writeLong(offset);
            }
        });
        if (!accepted) {
            callback.onRejected();
            return;
        }

        callback.onTransferStarted(false, itemCount, totalBytes, resumedBytes);
        long transferred = resumedBytes;
        ProgressEmitter progress = new ProgressEmitter(callback, false, totalBytes, transferred);
        ArrayList<ReceivedFileStore.StoredLocation> locations = new ArrayList<>(itemCount);
        String displayLocation = "";

        for (int expectedIndex = 0; expectedIndex < itemCount; expectedIndex++) {
            ensureCurrent(run);
            SecureChannel.Frame startFrame = secure.readFrame();
            requireType(startFrame, FRAME_ITEM_START);
            DataInputStream start = startFrame.payloadInput();
            int itemIndex = start.readInt();
            long requestedOffset = start.readLong();
            if (itemIndex != expectedIndex || requestedOffset != offsets[expectedIndex]) {
                throw new IOException("Invalid item resume position");
            }

            TransferItemInfo item = items.get(expectedIndex);
            ReceivedFileStore.Partial partial = ReceivedFileStore.openPartial(
                    context,
                    transferId,
                    item,
                    requestedOffset
            );
            boolean published = false;
            try {
                while (true) {
                    ensureCurrent(run);
                    SecureChannel.Frame frame = secure.readFrame();
                    if (frame.type == FRAME_ITEM_CHUNK) {
                        DataInputStream chunk = frame.payloadInput();
                        if (chunk.readInt() != expectedIndex) {
                            throw new IOException("Unexpected item chunk");
                        }
                        int length = chunk.readInt();
                        if (length < 0 || length > BUFFER_SIZE) {
                            throw new IOException("Invalid item chunk length");
                        }
                        byte[] data = new byte[length];
                        chunk.readFully(data);
                        partial.write(data, 0, data.length);
                        transferred += data.length;
                        progress.emit(
                                item.displayName,
                                expectedIndex + 1,
                                itemCount,
                                transferred,
                                false
                        );
                        continue;
                    }
                    if (frame.type != FRAME_ITEM_END) {
                        throw new IOException("Unexpected transfer frame");
                    }
                    DataInputStream end = frame.payloadInput();
                    if (end.readInt() != expectedIndex) {
                        throw new IOException("Unexpected item completion");
                    }
                    long finalLength = end.readLong();
                    int hashLength = end.readInt();
                    if (hashLength != 32) {
                        throw new IOException("Invalid item hash");
                    }
                    byte[] expectedHash = new byte[hashLength];
                    end.readFully(expectedHash);
                    partial.finishWriting();

                    File staged = partial.file();
                    boolean lengthMatches = staged.length() == finalLength
                            && (item.size < 0L || item.size == finalLength);
                    boolean hashMatches = lengthMatches
                            && MessageDigest.isEqual(expectedHash, hashFile(staged));
                    if (!hashMatches) {
                        partial.discard();
                        final int failedIndex = expectedIndex;
                        secure.writeFrame(FRAME_ACK, ack -> {
                            ack.writeInt(failedIndex);
                            ack.writeBoolean(false);
                        });
                        throw new IOException("Transferred item failed verification");
                    }

                    ReceivedFileStore.StoredLocation location = ReceivedFileStore.publish(
                            context,
                            item,
                            partial
                    );
                    locations.add(location);
                    if (displayLocation.isEmpty()) {
                        displayLocation = location.displayLocation;
                    }
                    published = true;
                    final int verifiedIndex = expectedIndex;
                    secure.writeFrame(FRAME_ACK, ack -> {
                        ack.writeInt(verifiedIndex);
                        ack.writeBoolean(true);
                    });
                    progress.emit(
                            item.displayName,
                            expectedIndex + 1,
                            itemCount,
                            transferred,
                            true
                    );
                    callback.onItemCompleted(
                            false,
                            item.displayName,
                            expectedIndex + 1,
                            itemCount
                    );
                    break;
                }
            } finally {
                if (!published) {
                    partial.closeKeep();
                }
            }
        }

        SecureChannel.Frame completeFrame = secure.readFrame();
        requireType(completeFrame, FRAME_COMPLETE);
        String confirmedTransferId = readString(completeFrame.payloadInput());
        if (!transferId.equals(confirmedTransferId)) {
            throw new IOException("Transfer identifier mismatch");
        }

        int appCount = appCount(items);
        historyStore.recordReceiving(
                transferId,
                peerName,
                items,
                locations,
                transferred,
                displayLocation
        );
        callback.onCompleted(
                false,
                itemCount,
                transferred,
                displayLocation,
                transferId,
                appCount
        );
    }

    private boolean awaitPairingDecision(int run) throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + DECISION_TIMEOUT_MS;
        synchronized (pairingLock) {
            while (pendingPairingDecision == null && isCurrent(run)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    throw new IOException("Pairing decision timed out");
                }
                pairingLock.wait(Math.min(remaining, 1_000L));
            }
            return Boolean.TRUE.equals(pendingPairingDecision) && isCurrent(run);
        }
    }

    private boolean awaitOfferDecision(int run) throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + DECISION_TIMEOUT_MS;
        synchronized (offerLock) {
            while (pendingOfferDecision == null && isCurrent(run)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    throw new IOException("Offer decision timed out");
                }
                offerLock.wait(Math.min(remaining, 1_000L));
            }
            return Boolean.TRUE.equals(pendingOfferDecision) && isCurrent(run);
        }
    }

    private void writeHello(
            DataOutputStream output,
            String localName,
            CryptoSession.LocalMaterial localCrypto
    ) throws IOException {
        byte[] publicKey = localCrypto.keyPair.getPublic().getEncoded();
        if (publicKey.length > MAX_KEY_BYTES) {
            throw new IOException("Local public key is too large");
        }
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeBoolean(localCrypto.sender);
        writeString(output, localName);
        output.writeInt(publicKey.length);
        output.write(publicKey);
        output.writeInt(localCrypto.nonce.length);
        output.write(localCrypto.nonce);
        output.flush();
    }

    private PeerHello readHello(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) {
            throw new IOException("Unsupported transfer protocol");
        }
        boolean sender = input.readBoolean();
        String name = safeText(readString(input), "هاتف قريب");
        int keyLength = input.readInt();
        if (keyLength < 64 || keyLength > MAX_KEY_BYTES) {
            throw new IOException("Invalid public key length");
        }
        byte[] publicKey = new byte[keyLength];
        input.readFully(publicKey);
        int nonceLength = input.readInt();
        if (nonceLength != CryptoSession.NONCE_LENGTH) {
            throw new IOException("Invalid nonce length");
        }
        byte[] nonce = new byte[nonceLength];
        input.readFully(nonce);
        return new PeerHello(name, sender, publicKey, nonce);
    }

    private static void writeItem(DataOutputStream output, TransferItemInfo item) throws IOException {
        writeString(output, item.id);
        writeString(output, item.displayName);
        writeString(output, item.mimeType);
        output.writeLong(item.size);
        output.writeInt(item.kind);
        writeString(output, item.groupId);
        writeString(output, item.packageName);
        writeString(output, item.partName);
    }

    private static TransferItemInfo readItem(DataInputStream input) throws IOException {
        String id = readString(input);
        String displayName = FileNameSanitizer.sanitize(readString(input), "ملف");
        String mimeType = readString(input);
        long size = input.readLong();
        int kind = input.readInt();
        String groupId = readString(input);
        String packageName = readString(input);
        String partName = readString(input);
        if (id.isEmpty() || size < -1L
                || (kind != TransferItemInfo.KIND_FILE && kind != TransferItemInfo.KIND_APP)) {
            throw new IOException("Invalid transfer item metadata");
        }
        return new TransferItemInfo(
                id,
                displayName,
                mimeType,
                size,
                kind,
                groupId,
                packageName,
                partName
        );
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = safe(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Text field is too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid text field length");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] hashSource(TransferSource source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = source.openAt(0L)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static byte[] hashFile(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static long totalSizeSources(List<TransferSource> sources) {
        long total = 0L;
        for (TransferSource source : sources) {
            if (source.info.size < 0L) {
                return -1L;
            }
            total += source.info.size;
            if (total < 0L) {
                return -1L;
            }
        }
        return total;
    }

    private static long totalSizeItems(List<TransferItemInfo> items) {
        long total = 0L;
        for (TransferItemInfo item : items) {
            if (item.size < 0L) {
                return -1L;
            }
            total += item.size;
            if (total < 0L) {
                return -1L;
            }
        }
        return total;
    }

    private static int appCount(List<TransferItemInfo> items) {
        Set<String> packages = new LinkedHashSet<>();
        for (TransferItemInfo item : items) {
            if (item.kind != TransferItemInfo.KIND_APP) {
                continue;
            }
            String key = item.packageName.isEmpty() ? item.groupId : item.packageName;
            if (!key.isEmpty()) {
                packages.add(key);
            }
        }
        return packages.size();
    }

    private static void requireType(SecureChannel.Frame frame, int expected) throws IOException {
        if (frame.type != expected) {
            throw new IOException("Unexpected encrypted frame");
        }
    }

    private static void configure(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(180_000);
        socket.setReceiveBufferSize(BUFFER_SIZE * 2);
        socket.setSendBufferSize(BUFFER_SIZE * 2);
    }

    private boolean isCurrent(int run) {
        return run == generation.get() && !Thread.currentThread().isInterrupted();
    }

    private void ensureCurrent(int run) throws IOException {
        if (!isCurrent(run)) {
            throw new IOException("Transfer cancelled");
        }
    }

    private void failIfCurrent(int run, Callback callback, int messageResId) {
        if (isCurrent(run)) {
            callback.onFailure(messageResId);
        }
    }

    private void closeActiveResources() {
        ServerSocket server = activeServer;
        activeServer = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
        Socket socket = activeSocket;
        activeSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<TransferSource> immutableSources(List<TransferSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(sources));
    }

    private static String safeText(String value, String fallback) {
        String safe = safe(value).trim();
        if (safe.isEmpty()) {
            return fallback;
        }
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class PeerHello {
        final String name;
        final boolean sender;
        final byte[] publicKey;
        final byte[] nonce;

        PeerHello(String name, boolean sender, byte[] publicKey, byte[] nonce) {
            this.name = name;
            this.sender = sender;
            this.publicKey = publicKey;
            this.nonce = nonce;
        }
    }

    private static final class ProgressEmitter {
        private final Callback callback;
        private final boolean sending;
        private final long totalBytes;
        private long lastTimeNanos;
        private long lastBytes;

        ProgressEmitter(Callback callback, boolean sending, long totalBytes, long initialBytes) {
            this.callback = callback;
            this.sending = sending;
            this.totalBytes = totalBytes;
            this.lastTimeNanos = System.nanoTime();
            this.lastBytes = initialBytes;
        }

        void emit(
                String itemName,
                int itemIndex,
                int itemCount,
                long transferred,
                boolean force
        ) {
            long now = System.nanoTime();
            long elapsed = now - lastTimeNanos;
            if (!force && elapsed < 250_000_000L) {
                return;
            }
            long bytesPerSecond = elapsed <= 0L ? 0L
                    : (long) ((transferred - lastBytes) * 1_000_000_000.0 / elapsed);
            callback.onProgress(
                    sending,
                    itemName,
                    itemIndex,
                    itemCount,
                    transferred,
                    totalBytes,
                    Math.max(0L, bytesPerSecond)
            );
            lastTimeNanos = now;
            lastBytes = transferred;
        }
    }
}
