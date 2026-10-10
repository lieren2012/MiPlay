package org.carplaytocarlife.iccoa;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * The AirPlay 2 pairing exchange (pair-setup with SRP, then pair-verify).
 *
 * Composition follows the published AirPlay pairing description; the salts,
 * info strings, user name and PIN are the ones the reference app uses verbatim.
 * The SRP group is RFC 5054's 3072-bit prime and the AEAD is ChaCha20-Poly1305
 * (BouncyCastle, the same provider the reference app ships). Everything is
 * exercised by a loopback test that plays the accessory side.
 */
public final class AirplayPairing {

    /**
     * The four nonces the pairing flow seals and opens with.
     *
     * Each direction has its own label (the reference passes "PS-Msg05" when it
     * seals and "PS-Msg06" when it opens, and the same split for pair-verify),
     * and the label sits at offset 4 of a 12 byte nonce. Using one nonce for both
     * directions still round-trips against a friendly peer and fails against a
     * real one.
     */
    public static final byte[] PS_MSG05 = AirplayCrypto.label("PS-Msg05");
    public static final byte[] PS_MSG06 = AirplayCrypto.label("PS-Msg06");
    public static final byte[] PV_MSG02 = AirplayCrypto.label("PV-Msg02");
    public static final byte[] PV_MSG03 = AirplayCrypto.label("PV-Msg03");

    /** What the controller learns about the accessory after pair-setup. */
    public static final class Accessory {
        public byte[] pairingId;
        public byte[] ed25519PublicKey;
    }

    private final String pin;
    private final SecureRandom random = new SecureRandom();
    private BigInteger a;
    private BigInteger A;
    private byte[] salt;
    private byte[] accessoryPublicB;
    private BigInteger sharedSecret;
    private byte[] sessionKey;
    private byte[] ownEd25519Seed;
    private byte[] ownEd25519Public;
    private byte[] ownPairingId;
    private byte[] ownX25519Scalar;
    private byte[] ownX25519Public;

    public AirplayPairing(String pin) {
        this.pin = pin;
    }

    /**
     * Step 1: the controller commits its SRP public key.
     *
     * Idempotent on purpose: the same A (and therefore the same a) has to be
     * used for the shared secret and for the proof, otherwise the two sides
     * derive different transcripts.
     */
    public byte[] srpPublicKey() {
        if (A == null) {
            a = new BigInteger(256, random);
            A = AirplayCrypto.srpPublic(a);
        }
        return AirplayCrypto.pad(A);
    }

    /** Step 2: the accessory answered with its salt and public key. */
    public void acceptAccessoryParameters(byte[] salt, byte[] publicB) {
        srpPublicKey();     // make sure a and A exist before deriving the secret
        this.salt = salt;
        this.accessoryPublicB = publicB;
        BigInteger x = AirplayCrypto.srpX(salt, AirplayCrypto.SRP_USER, pin);
        this.sharedSecret = AirplayCrypto.srpClientSecret(a, x,
                new BigInteger(1, publicB));
        this.sessionKey = AirplayCrypto.hkdfSha512(pad(sharedSecret),
                AirplayCrypto.SALT_PAIR_SETUP_ENCRYPT, AirplayCrypto.INFO_PAIR_SETUP_ENCRYPT, 32);
    }

    /** Step 3: the controller's proof of knowing the password. */
    public byte[] clientProof() {
        byte[] hN = AirplayCrypto.sha512(AirplayCrypto.pad(AirplayCrypto.SRP_N));
        byte[] hG = AirplayCrypto.sha512(AirplayCrypto.pad(AirplayCrypto.SRP_G));
        byte[] hI = AirplayCrypto.sha512(AirplayCrypto.SRP_USER.getBytes(StandardCharsets.UTF_8));
        byte[] xor = new byte[hN.length];
        for (int i = 0; i < xor.length; i++) {
            xor[i] = (byte) (hN[i] ^ hG[i]);
        }
        byte[] k = AirplayCrypto.sha512(pad(sharedSecret));
        return AirplayCrypto.sha512(xor, hI, salt, AirplayCrypto.pad(A), accessoryPublicB, k);
    }

    /** Step 4: the accessory's expected proof, M2 = H(A || M1 || K) per RFC 5054. */
    public byte[] expectedServerProof() {
        byte[] k = AirplayCrypto.sha512(pad(sharedSecret));
        return AirplayCrypto.sha512(AirplayCrypto.pad(A), clientProof(), k);
    }

    public boolean verifyServerProof(byte[] proof) {
        return java.util.Arrays.equals(proof, expectedServerProof());
    }

    public byte[] sessionKey() {
        return sessionKey;
    }

    /** The controller's long term key material and id. */
    public byte[] ownEd25519PublicKey() {
        if (ownEd25519Public == null) {
            ownEd25519Seed = new byte[32];
            random.nextBytes(ownEd25519Seed);
            ownEd25519Public = AirplayCrypto.ed25519PublicKey(ownEd25519Seed);
        }
        return ownEd25519Public;
    }

    public byte[] ownPairingId() {
        if (ownPairingId == null) {
            ownPairingId = new byte[16];
            random.nextBytes(ownPairingId);
        }
        return ownPairingId;
    }

    /** The signature the accessory checks: over (signInfo || pairingId || ltpk). */
    public byte[] signOwnKey(String salt, String info) {
        byte[] signInfo = AirplayCrypto.hkdfSha512(sessionKey, salt, info, 32);
        return AirplayCrypto.ed25519Sign(ownEd25519Seed,
                AirplayCrypto.concat(signInfo, ownPairingId(), ownEd25519PublicKey()));
    }

    /** Verifies the accessory's signature over its own triple. */
    public static boolean verifyAccessoryKey(byte[] sessionKey, String salt, String info,
            byte[] accessoryEd25519, byte[] accessoryPairingId, byte[] signature) {
        byte[] signInfo = AirplayCrypto.hkdfSha512(sessionKey, salt, info, 32);
        return AirplayCrypto.ed25519Verify(accessoryEd25519,
                AirplayCrypto.concat(signInfo, accessoryPairingId, accessoryEd25519), signature);
    }

    /** Seals the controller blob the way the reference does (label PS-Msg05). */
    public byte[] encryptSetup(byte[] plaintext) {
        return seal(sessionKey, PS_MSG05, plaintext);
    }

    /** Opens the accessory blob (label PS-Msg06, not PS-Msg05). */
    public byte[] decryptSetup(byte[] ciphertext) {
        byte[] plaintext = open(sessionKey, PS_MSG06, ciphertext);
        if (plaintext == null) {
            throw new IllegalStateException("pair-setup blob failed authentication");
        }
        return plaintext;
    }

    // --------------------------------------------------------- pair-verify

    public byte[] verifyPublicKey() {
        if (ownX25519Scalar == null) {
            ownX25519Scalar = new byte[32];
            random.nextBytes(ownX25519Scalar);
            ownX25519Public = AirplayCrypto.x25519PublicKey(ownX25519Scalar);
        }
        return ownX25519Public;
    }

    /** pair-verify step 2: derive the shared key from the accessory's ephemeral key. */
    public byte[] acceptVerifyPublicKey(byte[] accessoryEphemeral) {
        byte[] secret = AirplayCrypto.x25519SharedSecret(ownX25519Scalar, accessoryEphemeral);
        return AirplayCrypto.hkdfSha512(secret, AirplayCrypto.SALT_PAIR_VERIFY_ENCRYPT,
                AirplayCrypto.INFO_PAIR_VERIFY_ENCRYPT, 32);
    }

    /** Seals the pair-verify blob (label PV-Msg03). */
    public byte[] encryptVerify(byte[] verifyKey, byte[] plaintext) {
        return seal(verifyKey, PV_MSG03, plaintext);
    }

    /** Opens the accessory's pair-verify blob (label PV-Msg02). */
    public byte[] decryptVerify(byte[] verifyKey, byte[] ciphertext) {
        byte[] plaintext = open(verifyKey, PV_MSG02, ciphertext);
        if (plaintext == null) {
            throw new IllegalStateException("pair-verify blob failed authentication");
        }
        return plaintext;
    }

    /**
     * pair-verify step 3: the signature proving who we are.
     *
     * The transcript is (our ephemeral public key || our pairing id || the car's
     * ephemeral public key) - the reference signs all three, so a signature over
     * the first two alone is refused by a car that checks it properly.
     */
    public byte[] verifySignature(byte[] carEphemeralPublic) {
        return AirplayCrypto.ed25519Sign(ownEd25519Seed, AirplayCrypto.concat(
                ownX25519Public, ownPairingId(), carEphemeralPublic));
    }

    /**
     * Checks the accessory's own pair-verify signature.
     *
     * The car signs (its ephemeral public key || its pairing id || our ephemeral
     * public key) with the long term key pair-setup handed us. The reference
     * verifies it before it trusts the channel; so does this side.
     */
    public static boolean verifyAccessoryVerify(byte[] accessoryEd25519,
            byte[] carEphemeralPublic, byte[] carPairingId, byte[] ourEphemeralPublic,
            byte[] signature) {
        return AirplayCrypto.ed25519Verify(accessoryEd25519, AirplayCrypto.concat(
                carEphemeralPublic, carPairingId, ourEphemeralPublic), signature);
    }

    // ------------------------------------------------------------- AEAD

    public static byte[] seal(byte[] key, byte[] nonce, byte[] plaintext) {
        ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
        cipher.init(true, new AEADParameters(new KeyParameter(key), 128, nonce));
        byte[] out = new byte[cipher.getOutputSize(plaintext.length)];
        int written = cipher.processBytes(plaintext, 0, plaintext.length, out, 0);
        try {
            cipher.doFinal(out, written);
        } catch (Exception error) {
            throw new IllegalStateException("sealing failed", error);
        }
        return out;
    }

    public static byte[] open(byte[] key, byte[] nonce, byte[] ciphertext) {
        ChaCha20Poly1305 cipher = new ChaCha20Poly1305();
        cipher.init(false, new AEADParameters(new KeyParameter(key), 128, nonce));
        byte[] out = new byte[cipher.getOutputSize(ciphertext.length)];
        try {
            int written = cipher.processBytes(ciphertext, 0, ciphertext.length, out, 0);
            int total = written + cipher.doFinal(out, written);
            return java.util.Arrays.copyOf(out, total);
        } catch (Exception error) {
            return null;
        }
    }

    private static byte[] pad(BigInteger value) {
        return AirplayCrypto.pad(value);
    }
}
