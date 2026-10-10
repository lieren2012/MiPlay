package org.carplaytocarlife.iccoa;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The accessory (car) side of the tests: a throwaway P-256 identity and an
 * auth-setup responder.
 *
 * It exists so the whole key exchange can run on a desktop: the phone derives
 * its screen key from what this side produces, and this side can check that the
 * key it receives wrapped really is the one the phone used.
 */
public final class SelfTestAccessory {

    /** A PKCS#12 holding the test accessory key and certificate. */
    private static final String KEYSTORE =             "MIIEJgIBAzCCA9AGCSqGSIb3DQEHAaCCA8EEggO9MIIDuTCCATAGCSqGSIb3DQEHAaCCASEEggEd"
            +             "MIIBGTCCARUGCyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDAr"
            +             "BBSWc9gSoJQSl9h09/dILZJnDLMweQICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoE"
            +             "EEKlrotY4Oxidif0k3ptBCcEUP8ZY5gT+5S9giqFWkte0tRx/F8gI9npIBYvmcDohEVti7albp7+"
            +             "bb+skM7Wmib0JlapXhtXrNwEXFqcNDTci+l4XMQQxjyvEH+6vusCBRn6MUYwIQYJKoZIhvcNAQkU"
            +             "MRQeEgBhAGMAYwBlAHMAcwBvAHIAeTAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzkxNjA4OTc2MDQ4"
            +             "MIICgQYJKoZIhvcNAQcGoIICcjCCAm4CAQAwggJnBgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkw"
            +             "OAYJKoZIhvcNAQUMMCsEFO+xNUkY5U/EnD6mXJ1WzdCCzoNzAgInEAIBIDAMBggqhkiG9w0CCQUA"
            +             "MB0GCWCGSAFlAwQBKgQQb3osxaIQ4HNjHKcDHc4PLYCCAfAwmKLrQ6erV7WgXtONS2tQX5GAc/4K"
            +             "QnKp6YeHksSGwOWACpCiRFa6VpVJYvuhsYwJKxKXjwKC2yFCUXcHEVyeDoH4339tJzx5LPQT+gZh"
            +             "+pFOUplNCFBqlt7/dwSmH/ci0xh4f5M69oxiIXUr7/qI8Vv8M991GJZsbpKrAXfcbC4QvEewXZm4"
            +             "OtJfMBx3Nm9TAlQfZBpTduiBfo3JWlv43uugDNHj1Cr3twfZD+xPWHAyyci+IKb8lhFhlMRhzje5"
            +             "Qh5eH/CmgbDHGu2W4LZVEVLzg85dXBwyrZvsZVK7VK6FqU+8e0yPfnY+JUnqGD77wPW7IOmhh5sy"
            +             "BpVdFSmjQ++9rV+GL3GM2ZkU16NZd88fS10zv9MCv0SwVDN2cCyHm1KdC3vUTrhM1B9ghDv86IXk"
            +             "YlVzxWoTDAWUMhwTW1XF0EKIkI/yrFWqEsZ6vbtPs7SpQxskOBGDALuKclCdh+ZKWavR4CiqNMcy"
            +             "f8qc05Vfg0OhIXaI/iangkKfiIY9kC+5LZbibnMlEq6FpwEg7jHBFZ4Zat8bA1t161Xr35NPlzhj"
            +             "6760Ex+yOlg3/Fh80uJG6gO1zb7rvPKxcw1h8VmbXV0algEueKqdlZgoU5Jr/fWNdeZkZoofhXN7"
            +             "iIEoCtBNatiwJR5Ormika7qAME0wMTANBglghkgBZQMEAgEFAAQglatYVzweRE0XxbR/S59mmX8l"
            +             "0VOqyq3yk/rfghEwn6YEFMCL47/uADBE7edqKvlcE7IojZM+AgInEA==";

    private static final char[] PASSWORD = "test123".toCharArray();
    private static KeyStore store;

    static synchronized KeyStore store() throws Exception {
        if (store == null) {
            KeyStore loaded = KeyStore.getInstance("PKCS12");
            loaded.load(new ByteArrayInputStream(java.util.Base64.getDecoder().decode(KEYSTORE)),
                    PASSWORD);
            store = loaded;
        }
        return store;
    }

    static PrivateKey key() throws Exception {
        return (PrivateKey) store().getKey("accessory", PASSWORD);
    }

    static X509Certificate certificate() throws Exception {
        return (X509Certificate) store().getCertificate("accessory");
    }

    /** The car's answer to {@code POST /auth-setup}, plus its wrapping cipher. */
    static final class Answer {
        final byte[] body;
        private final Cipher wrapping;

        Answer(byte[] body, Cipher wrapping) {
            this.body = body;
            this.wrapping = wrapping;
        }

        /** What the car recovers from {@code ekey}: it must be the phone's base key. */
        byte[] unwrap(byte[] ekey) {
            return wrapping.update(ekey);
        }
    }

    /**
     * Builds the response the way a car does: its own ephemeral key, its
     * certificate chain, and a signature over both public keys encrypted with
     * the shared AES-CTR stream.
     */
    static Answer authSetup(byte[] requestBody) throws Exception {
        if (requestBody.length != 33 || requestBody[0] != 1) {
            throw new IllegalArgumentException("unexpected auth-setup request");
        }
        byte[] clientPublic = Arrays.copyOfRange(requestBody, 1, 33);
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        byte[] accessoryPublic = AirplayCrypto.x25519PublicKey(seed);
        byte[] shared = AirplayCrypto.x25519SharedSecret(seed, clientPublic);
        byte[] key = Arrays.copyOf(digest("SHA-1", new byte[][] {
                "AES-KEY".getBytes(java.nio.charset.StandardCharsets.US_ASCII), shared}), 16);
        byte[] iv = Arrays.copyOf(digest("SHA-1", new byte[][] {
                "AES-IV".getBytes(java.nio.charset.StandardCharsets.US_ASCII), shared}), 16);
        Cipher wrapping = Cipher.getInstance("AES/CTR/NoPadding");
        wrapping.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] signed = digest("SHA-256", new byte[][] {accessoryPublic, clientPublic});
        Signature signer = Signature.getInstance("NONEwithECDSA");
        signer.initSign(key());
        signer.update(signed);
        byte[] encrypted = wrapping.update(signer.sign());
        byte[] chain = certificate().getEncoded();
        ByteBuffer out = ByteBuffer.allocate(32 + 4 + chain.length + 4 + encrypted.length)
                .order(ByteOrder.BIG_ENDIAN);
        out.put(accessoryPublic);
        out.putInt(chain.length);
        out.put(chain);
        out.putInt(encrypted.length);
        out.put(encrypted);
        return new Answer(out.array(), wrapping);
    }

    /** The same response with a corrupted signature, to test the refusal path. */
    static byte[] badSignature(byte[] requestBody) throws Exception {
        Answer good = authSetup(requestBody);
        // the encrypted signature is the last field; flipping its tail breaks it
        byte[] body = good.body.clone();
        body[body.length - 1] ^= 0x5A;
        return body;
    }

    private static byte[] digest(String algorithm, byte[][] parts) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        for (byte[] part : parts) {
            digest.update(part);
        }
        return digest.digest();
    }

    private SelfTestAccessory() {
    }
}
