package com.bassam.qareebshare;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class SecureChannel {
    static final int MAX_PLAINTEXT = 384 * 1024;
    private static final int TAG_BITS = 128;

    static final class Frame {
        final int type;
        final byte[] payload;

        Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }

        DataInputStream payloadInput() {
            return new DataInputStream(new ByteArrayInputStream(payload));
        }
    }

    interface PayloadWriter {
        void write(DataOutputStream output) throws IOException;
    }

    private final DataInputStream input;
    private final DataOutputStream output;
    private final SecretKeySpec key;
    private final byte[] sendPrefix;
    private final byte[] receivePrefix;
    private long sendSequence;
    private long receiveSequence;

    SecureChannel(
            DataInputStream input,
            DataOutputStream output,
            SecretKeySpec key,
            byte[] sendPrefix,
            byte[] receivePrefix
    ) {
        this.input = input;
        this.output = output;
        this.key = key;
        this.sendPrefix = sendPrefix.clone();
        this.receivePrefix = receivePrefix.clone();
    }

    synchronized void writeFrame(int type, PayloadWriter writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream frameOutput = new DataOutputStream(bytes);
        frameOutput.writeInt(type);
        if (writer != null) {
            writer.write(frameOutput);
        }
        frameOutput.flush();
        byte[] plain = bytes.toByteArray();
        if (plain.length > MAX_PLAINTEXT) {
            throw new IOException("Frame is too large");
        }

        long sequence = sendSequence++;
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, plain, sendPrefix, sequence);
        output.writeInt(encrypted.length);
        output.write(encrypted);
        output.flush();
    }

    Frame readFrame() throws IOException {
        int length = input.readInt();
        if (length < 20 || length > MAX_PLAINTEXT + 64) {
            throw new IOException("Invalid encrypted frame length");
        }
        byte[] encrypted = new byte[length];
        input.readFully(encrypted);
        long sequence = receiveSequence++;
        byte[] plain = crypt(Cipher.DECRYPT_MODE, encrypted, receivePrefix, sequence);
        DataInputStream frameInput = new DataInputStream(new ByteArrayInputStream(plain));
        int type = frameInput.readInt();
        byte[] payload = new byte[plain.length - 4];
        frameInput.readFully(payload);
        return new Frame(type, payload);
    }

    private byte[] crypt(int mode, byte[] data, byte[] prefix, long sequence) throws IOException {
        try {
            byte[] nonce = new byte[12];
            System.arraycopy(prefix, 0, nonce, 0, 4);
            ByteBuffer.wrap(nonce, 4, 8).putLong(sequence);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(ByteBuffer.allocate(12)
                    .putInt(0x51534834)
                    .putLong(sequence)
                    .array());
            return cipher.doFinal(data);
        } catch (AEADBadTagException error) {
            throw new IOException("Encrypted frame authentication failed", error);
        } catch (Exception error) {
            throw new IOException("Unable to process encrypted frame", error);
        }
    }
}
