package com.bassam.qareebshare;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KeyAgreement;
import javax.crypto.spec.SecretKeySpec;

final class CryptoSession {
    static final int NONCE_LENGTH = 32;

    static final class LocalMaterial {
        final boolean sender;
        final KeyPair keyPair;
        final byte[] nonce;

        LocalMaterial(boolean sender, KeyPair keyPair, byte[] nonce) {
            this.sender = sender;
            this.keyPair = keyPair;
            this.nonce = nonce;
        }
    }

    static final class Result {
        final SecretKeySpec key;
        final byte[] sendPrefix;
        final byte[] receivePrefix;
        final String pairingCode;

        Result(SecretKeySpec key, byte[] sendPrefix, byte[] receivePrefix, String pairingCode) {
            this.key = key;
            this.sendPrefix = sendPrefix;
            this.receivePrefix = receivePrefix;
            this.pairingCode = pairingCode;
        }
    }

    private CryptoSession() {
    }

    static LocalMaterial createLocal(boolean sender) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        byte[] nonce = new byte[NONCE_LENGTH];
        new SecureRandom().nextBytes(nonce);
        return new LocalMaterial(sender, generator.generateKeyPair(), nonce);
    }

    static Result derive(LocalMaterial local, byte[] peerPublicKey, byte[] peerNonce) throws Exception {
        if (peerPublicKey == null || peerPublicKey.length < 64 || peerPublicKey.length > 1024) {
            throw new IllegalArgumentException("Invalid peer public key");
        }
        if (peerNonce == null || peerNonce.length != NONCE_LENGTH) {
            throw new IllegalArgumentException("Invalid peer nonce");
        }

        KeyFactory factory = KeyFactory.getInstance("EC");
        PublicKey peerKey = factory.generatePublic(new X509EncodedKeySpec(peerPublicKey));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(local.keyPair.getPrivate());
        agreement.doPhase(peerKey, true);
        byte[] sharedSecret = agreement.generateSecret();

        byte[] senderNonce = local.sender ? local.nonce : peerNonce;
        byte[] receiverNonce = local.sender ? peerNonce : local.nonce;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("QareebShare/QSH4/master".getBytes(StandardCharsets.UTF_8));
        digest.update(senderNonce);
        digest.update(receiverNonce);
        digest.update(sharedSecret);
        byte[] master = digest.digest();

        SecretKeySpec key = new SecretKeySpec(master, "AES");
        byte[] senderPrefix = prefix(master, "sender-to-receiver");
        byte[] receiverPrefix = prefix(master, "receiver-to-sender");
        byte[] pairDigest = labeledDigest(master, "pairing-code");
        int numeric = ByteBuffer.wrap(pairDigest, 0, 4).getInt() & 0x7fffffff;
        String code = String.format(java.util.Locale.US, "%06d", numeric % 1_000_000);

        return new Result(
                key,
                local.sender ? senderPrefix : receiverPrefix,
                local.sender ? receiverPrefix : senderPrefix,
                code
        );
    }

    private static byte[] prefix(byte[] master, String label) throws Exception {
        byte[] digest = labeledDigest(master, label);
        byte[] result = new byte[4];
        System.arraycopy(digest, 0, result, 0, result.length);
        return result;
    }

    private static byte[] labeledDigest(byte[] master, String label) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(master);
        digest.update(label.getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }
}
