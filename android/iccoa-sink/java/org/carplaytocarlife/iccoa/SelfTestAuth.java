package org.carplaytocarlife.iccoa;

import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Map;

import javax.crypto.Cipher;

/**
 * Runs the auth-setup exchange against {@link SelfTestAccessory}.
 *
 * This is the step that decides whether the car can decrypt the screen at all:
 * the phone generates a base key, the accessory unwraps it with the CTR stream
 * the signature was encrypted with, and both derive the screen cipher from it.
 */
public final class SelfTestAuth {

    private static int failures;

    public static void main(String[] args) throws Exception {
        AirplayAuth.Request request = AirplayAuth.request();
        check("the request is a version byte plus a 32 byte key",
                request.body.length == 33 && request.body[0] == 1
                        && request.publicKey.length == 32);
        check("the request key is the X25519 public key of the seed",
                Arrays.equals(request.publicKey, AirplayCrypto.x25519PublicKey(request.seed)));

        SelfTestAccessory.Answer answer = SelfTestAccessory.authSetup(request.body);
        AirplayAuth auth = AirplayAuth.open(request, answer.body, quiet());
        check("a well formed answer is accepted", auth != null);
        check("the wrapping stream starts after the signature",
                auth.offset() == answer.body.length - 32 - 4
                        - certificateBytes() - 4,
                "offset=" + auth.offset());

        Map<String, Bplist.Node> fields = auth.setupFields();
        check("the setup fields are et/ekey/eiv",
                fields.size() == 3 && fields.containsKey("et")
                        && fields.containsKey("ekey") && fields.containsKey("eiv"));
        check("et is 16", fields.get("et").asInteger() == 16);
        byte[] ekey = fields.get("ekey").asData();
        byte[] eiv = fields.get("eiv").asData();
        check("ekey is one AES block", ekey != null && ekey.length == 16);
        check("eiv is one AES block", eiv != null && eiv.length == 16);
        check("eiv is the base iv", Arrays.equals(eiv, auth.baseIv()));
        check("the car recovers the base key from ekey",
                Arrays.equals(answer.unwrap(ekey), auth.baseKey()),
                "recovered=" + Logs.hex(answer.unwrap(ekey), 16)
                        + " expected=" + Logs.hex(auth.baseKey(), 16));
        check("the base key is not sent in the clear", !Arrays.equals(ekey, auth.baseKey()));
        check("the base key and iv differ", !Arrays.equals(auth.baseKey(), auth.baseIv()));

        boolean reused = false;
        try {
            auth.setupFields();
        } catch (RuntimeException expected) {
            reused = true;
        }
        check("the base key is only wrapped once", reused);

        // the screen cipher both ends derive
        byte[] plain = new byte[512];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (i * 7);
        }
        Cipher encryptor = auth.streamCipher(1);
        byte[] sealed = encryptor.update(plain);
        check("the screen cipher is not the identity", !Arrays.equals(sealed, plain));
        Cipher decryptor = AirplayAuth.streamDecryptor(auth.baseKey(), auth.baseIv(), 1);
        check("the car's side reproduces the plaintext",
                Arrays.equals(decryptor.update(sealed), plain));
        Cipher wrongIv = AirplayAuth.streamDecryptor(auth.baseKey(), auth.baseIv(), 2);
        check("another stream index gives another keystream",
                !Arrays.equals(wrongIv.update(sealed), plain));
        check("the base iv is what the IV derivation uses",
                !Arrays.equals(AirplayAuth.streamDecryptor(auth.baseKey(), auth.baseKey(), 1)
                        .update(sealed), plain));

        // ------------------------------------- the per stream keys (audio, video)
        byte[] shared = auth.sharedSecret();
        check("the shared secret is kept from the exchange", shared.length == 32);
        byte[] output = auth.streamOutputKey(2);
        check("the stream key is 32 bytes", output.length == 32);
        check("the key matches an independent HKDF over the same secret",
                java.util.Arrays.equals(output, referenceHkdf(shared,
                        "DataStream-Salt2", "DataStream-Output-Encryption-Key")));
        check("the input and output keys differ",
                !java.util.Arrays.equals(output, auth.streamInputKey(2)));
        check("another connection id gives another key",
                !java.util.Arrays.equals(output, auth.streamOutputKey(3)));
        check("the shared secret is not the key", !java.util.Arrays.equals(shared, output));

        // refusal paths
        boolean refused = false;
        try {
            AirplayAuth.open(AirplayAuth.request(),
                    SelfTestAccessory.badSignature(AirplayAuth.request().body), quiet());
        } catch (IllegalStateException expected) {
            refused = true;
        }
        check("a bad signature stops the exchange", refused);
        boolean shortRefused = false;
        try {
            AirplayAuth.open(request, new byte[8], quiet());
        } catch (IllegalArgumentException expected) {
            shortRefused = true;
        }
        check("a short answer is refused", shortRefused);

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST AUTH OK" : (failures + " FAILED"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    /**
     * The reference's HKDF, written out again here: HMAC-SHA512 with the salt as
     * the key gives the PRK, then one round over info || 0x01 gives the key. If
     * the implementation and this disagree, one of them changed.
     */
    private static byte[] referenceHkdf(byte[] secret, String salt, String info)
            throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA512");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                salt.getBytes(java.nio.charset.StandardCharsets.US_ASCII), "HmacSHA512"));
        byte[] prk = mac.doFinal(secret);
        byte[] message = new byte[info.length() + 1];
        System.arraycopy(info.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, message,
                0, info.length());
        message[info.length()] = 1;
        mac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA512"));
        return java.util.Arrays.copyOf(mac.doFinal(message), 32);
    }

    private static int certificateBytes() throws Exception {
        X509Certificate certificate = SelfTestAccessory.certificate();
        return certificate.getEncoded().length;
    }

    private static AirplayAuth.Listener quiet() {
        return new AirplayAuth.Listener() {
            @Override
            public void onLog(String message) { Logs.add(message); }
        };
    }

    private static void check(String name, boolean condition) {
        check(name, condition, "");
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name + (detail.isEmpty() ? "" : "  " + detail));
        }
    }

    private SelfTestAuth() {
    }
}
