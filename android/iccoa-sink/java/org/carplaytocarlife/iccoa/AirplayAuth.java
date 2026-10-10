package org.carplaytocarlife.iccoa;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AirPlay's {@code /auth-setup} step - the MFi key exchange a CarPlay accessory
 * expects before any screen data - and the screen cipher that hangs off it.
 *
 * The flow, as the reference CarWith -> CarPlay app implements it:
 *
 * <pre>
 *   phone -> POST /auth-setup   0x01 || X25519(client, 32)
 *   car   -> 32 bytes auxiliary public key
 *            int32 length + X.509 chain
 *            int32 length + encrypted signature
 *   shared   = X25519(client, car)
 *   AES-KEY  = SHA-1("AES-KEY" || shared)[0..16]
 *   AES-IV   = SHA-1("AES-IV"  || shared)[0..16]
 *   signature = AES-CTR(decrypt, AES-KEY/AES-IV).update(encrypted)
 *   digest    = SHA-256("car public" || "our public"), or SHA-1 when the chain
 *               is longer than 640 bytes
 *   the signature is checked against the car's leaf certificate (the trust chain
 *   itself is deliberately not verified - there is no Apple anchor on this side)
 * </pre>
 *
 * The same CTR cipher then wraps a random 16 byte base key, and
 * {@code {et: 16, ekey, eiv}} goes into the session SETUP so the car can unwrap
 * it. The screen cipher is the reference's derivation:
 *
 * <pre>
 *   key(n) = SHA-512("AirPlayStreamKey" + n || baseKey)[0..16]
 *   iv(n)  = SHA-512("AirPlayStreamIV"  + n || baseIv )[0..16]
 *   AES-CTR(key(n), iv(n)) over the whole session
 * </pre>
 *
 * The CTR continuation matters: the car expects {@code ekey} to be the next 16
 * bytes of the keystream the signature was decrypted with, not a fresh stream.
 */
public final class AirplayAuth {

    public static final int MAX_CERTIFICATE = 65536;
    public static final int MAX_PAYLOAD = 8192;
    /** The MFi key encapsulation type the car accepts for the screen stream. */
    public static final int ENCRYPTION_TYPE = 16;

    public interface Listener {
        void onLog(String message);
    }

    /** The client half of the exchange: our ephemeral key and the request body. */
    public static final class Request {
        public final byte[] seed;
        public final byte[] publicKey;
        public final byte[] body;

        Request(byte[] seed, byte[] publicKey, byte[] body) {
            this.seed = seed;
            this.publicKey = publicKey;
            this.body = body;
        }
    }

    private final byte[] baseKey = new byte[16];
    private final byte[] baseIv = new byte[16];
    private final byte[] shared;
    private final Cipher wrapping;
    private final int consumed;
    private boolean used;

    private AirplayAuth(byte[] shared, Cipher wrapping, int consumed) {
        this.shared = shared;
        this.wrapping = wrapping;
        this.consumed = consumed;
        SecureRandom random = new SecureRandom();
        random.nextBytes(baseKey);
        random.nextBytes(baseIv);
    }

    /** Builds the request body: a version byte and our ephemeral public key. */
    public static Request request() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        byte[] publicKey = AirplayCrypto.x25519PublicKey(seed);
        byte[] body = new byte[1 + publicKey.length];
        body[0] = 1;
        System.arraycopy(publicKey, 0, body, 1, publicKey.length);
        return new Request(seed, publicKey, body);
    }

    /**
     * Parses the car's answer and derives the wrapping cipher.
     *
     * @throws IllegalStateException when the car's signature does not verify
     */
    public static AirplayAuth open(Request request, byte[] response, Listener listener)
            throws Exception {
        if (response == null || response.length < 42) {
            throw new IllegalArgumentException("auth-setup response is only "
                    + (response == null ? 0 : response.length) + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN);
        byte[] accessoryPublic = new byte[32];
        buffer.get(accessoryPublic);
        byte[] chain = field(buffer, MAX_CERTIFICATE);
        byte[] encrypted = field(buffer, MAX_PAYLOAD);
        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException("auth-setup response carries "
                    + buffer.remaining() + " unsupported trailing bytes");
        }
        byte[] shared = AirplayCrypto.x25519SharedSecret(request.seed, accessoryPublic);
        byte[] key = Arrays.copyOf(
                digest("SHA-1", new byte[][] {"AES-KEY".getBytes(java.nio.charset.StandardCharsets.US_ASCII), shared}), 16);
        byte[] iv = Arrays.copyOf(
                digest("SHA-1", new byte[][] {"AES-IV".getBytes(java.nio.charset.StandardCharsets.US_ASCII), shared}), 16);
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] signature = cipher.update(encrypted);

        X509Certificate leaf = leafCertificate(chain);
        String digestName = chain.length <= 640 ? "SHA-256" : "SHA-1";
        byte[] signed = digest(digestName, new byte[][] {accessoryPublic, request.publicKey});
        boolean valid = CarplayBootstrap.verifyChallenge(leaf.getPublicKey(), signed, signature);
        listener.onLog("MFiSAP certificate-bytes=" + chain.length + " key="
                + leaf.getPublicKey().getAlgorithm() + " digest=" + digestName
                + " wrapped-bytes=" + encrypted.length + " signature-valid=" + valid
                + "; trust-chain-verified=false");
        if (!valid) {
            throw new IllegalStateException("the car's auth-setup signature does not verify");
        }
        return new AirplayAuth(shared, cipher, encrypted.length);
    }

    /**
     * The per-stream ChaCha20-Poly1305 key for what this side sends.
     *
     * Both ends derive it from the shared secret with the stream's connection id,
     * which is why one key serves the audio stream and the ChaCha video path:
     * {@code hkdf(shared, "DataStream-Salt" + id, "DataStream-Output-Encryption-Key")}.
     */
    public byte[] streamOutputKey(int connectionId) {
        return AirplayCrypto.hkdfSha512(shared, "DataStream-Salt" + connectionId,
                "DataStream-Output-Encryption-Key", 32);
    }

    /** The key the car encrypts what it sends us with, for the mic path. */
    public byte[] streamInputKey(int connectionId) {
        return AirplayCrypto.hkdfSha512(shared, "DataStream-Salt" + connectionId,
                "DataStream-Input-Encryption-Key", 32);
    }

    /** The X25519 secret both ends share; never logged. */
    byte[] sharedSecret() {
        return shared.clone();
    }

    /** The random base key the car received wrapped; the screen key comes from it. */
    public byte[] baseKey() {
        return baseKey.clone();
    }

    /** The random base IV; the screen IV comes from it. */
    public byte[] baseIv() {
        return baseIv.clone();
    }

    /** True once the base key has been handed to the car. */
    public boolean used() {
        return used;
    }

    public int consumedBytes() {
        return consumed;
    }

    /** How many bytes of the wrapping keystream the signature already consumed. */
    public int offset() {
        return consumed;
    }

    /**
     * {@code {et, ekey, eiv}} for the session SETUP: the car unwraps baseKey with
     * the same CTR stream, continuing right after the signature.
     */
    public Map<String, Bplist.Node> setupFields() {
        if (used) {
            throw new IllegalStateException("the MFiSAP setup key was already wrapped");
        }
        used = true;
        byte[] wrapped = wrapping.update(baseKey);
        if (wrapped == null || wrapped.length != 16) {
            throw new IllegalStateException("the wrapping cipher returned "
                    + (wrapped == null ? "nothing" : wrapped.length + " bytes"));
        }
        Map<String, Bplist.Node> fields = new LinkedHashMap<>();
        fields.put("et", Bplist.Node.integer(ENCRYPTION_TYPE));
        fields.put("ekey", Bplist.Node.data(wrapped));
        fields.put("eiv", Bplist.Node.data(baseIv));
        return fields;
    }

    /** The AES-CTR cipher for stream {@code counter}, as the car derives it. */
    public Cipher streamCipher(int counter) throws Exception {
        byte[] key = AirplayStream.derive(counter, AirplayStream.STREAM_KEY_LABEL, baseKey);
        byte[] iv = AirplayStream.derive(counter, AirplayStream.STREAM_IV_LABEL, baseIv);
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher;
    }

    /** The matching decryptor, used by tests and to read our own stream back. */
    public static Cipher streamDecryptor(byte[] baseKey, byte[] baseIv, int counter)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(AirplayStream.derive(counter, AirplayStream.STREAM_KEY_LABEL,
                        baseKey), "AES"),
                new IvParameterSpec(AirplayStream.derive(counter, AirplayStream.STREAM_IV_LABEL,
                        baseIv)));
        return cipher;
    }

    /** Reads the int32 length prefixed field the response is made of. */
    static byte[] field(ByteBuffer buffer, int maximum) {
        if (buffer.remaining() < 4) {
            throw new IllegalArgumentException("truncated auth-setup field length");
        }
        int length = buffer.getInt();
        if (length < 1 || length > maximum || length > buffer.remaining()) {
            throw new IllegalArgumentException("invalid auth-setup field length " + length);
        }
        byte[] value = new byte[length];
        buffer.get(value);
        return value;
    }

    private static X509Certificate leafCertificate(byte[] chain) throws Exception {
        java.util.List<X509Certificate> certificates = new java.util.ArrayList<>();
        for (java.security.cert.Certificate parsed : CertificateFactory.getInstance("X.509")
                .generateCertificates(new java.io.ByteArrayInputStream(chain))) {
            certificates.add((X509Certificate) parsed);
        }
        X509Certificate leaf = null;
        for (X509Certificate certificate : certificates) {
            if (certificate.getBasicConstraints() < 0) {
                if (leaf != null) {
                    throw new IllegalArgumentException("more than one leaf certificate");
                }
                leaf = certificate;
            }
        }
        if (leaf == null) {
            throw new IllegalArgumentException("no leaf certificate in the chain");
        }
        return leaf;
    }

    private static byte[] digest(String algorithm, byte[][] parts) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        for (byte[] part : parts) {
            digest.update(part);
        }
        return digest.digest();
    }
}
