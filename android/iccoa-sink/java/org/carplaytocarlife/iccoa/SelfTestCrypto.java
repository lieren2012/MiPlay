package org.carplaytocarlife.iccoa;

import java.math.BigInteger;
import java.security.KeyPair;
import java.util.Arrays;

/** Desktop self test for the AirPlay pairing crypto, against published vectors. */
public final class SelfTestCrypto {

    private static int failures;

    public static void main(String[] args) {
        // ---- RFC 7748 section 5.2 X25519 vector
        byte[] a = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] b = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        byte[] publicA = AirplayCrypto.x25519PublicKey(a);
        byte[] publicB = AirplayCrypto.x25519PublicKey(b);
        
        
        check("X25519 public key A matches RFC 7748",
                Arrays.equals(publicA, hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")));
        check("X25519 public key B matches RFC 7748",
                Arrays.equals(publicB, hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")));
        byte[] secretAB = AirplayCrypto.x25519SharedSecret(a, publicB);
        byte[] secretBA = AirplayCrypto.x25519SharedSecret(b, publicA);
        check("X25519 shared secret matches RFC 7748",
                Arrays.equals(secretAB, hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")));
        check("X25519 agreement is symmetric", Arrays.equals(secretAB, secretBA));

        // ---- RFC 8032 section 7.1 Ed25519 test 1 (empty message)
        byte[] seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        
        byte[] edPublic = AirplayCrypto.ed25519PublicKey(seed);
        check("Ed25519 public key matches RFC 8032",
                Arrays.equals(edPublic, hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")));
        byte[] signature = AirplayCrypto.ed25519Sign(seed, new byte[0]);
        check("Ed25519 signature matches RFC 8032",
                Arrays.equals(signature, hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                        + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")));
        check("Ed25519 verifies its own signature",
                AirplayCrypto.ed25519Verify(edPublic, new byte[0], signature));
        check("Ed25519 rejects a corrupted signature",
                !AirplayCrypto.ed25519Verify(edPublic, new byte[] {1}, signature));

        // ---- HKDF-SHA512 is deterministic and sized correctly
        byte[] ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] first = AirplayCrypto.hkdfSha512(ikm, AirplayCrypto.SALT_PAIR_SETUP_ENCRYPT,
                AirplayCrypto.INFO_PAIR_SETUP_ENCRYPT, 32);
        byte[] second = AirplayCrypto.hkdfSha512(ikm, AirplayCrypto.SALT_PAIR_SETUP_ENCRYPT,
                AirplayCrypto.INFO_PAIR_SETUP_ENCRYPT, 32);
        check("HKDF-SHA512 is deterministic", Arrays.equals(first, second) && first.length == 32);
        byte[] other = AirplayCrypto.hkdfSha512(ikm, AirplayCrypto.SALT_PAIR_VERIFY_ENCRYPT,
                AirplayCrypto.INFO_PAIR_VERIFY_ENCRYPT, 32);
        check("HKDF changes with the salt/info", !Arrays.equals(first, other));
        check("HKDF produces long output", AirplayCrypto.hkdfSha512(ikm, "s", "i", 80).length == 80);

        // ---- SRP: the two sides must agree (RFC 5054 arithmetic)
        byte[] salt = hex("BEB25379D1A8581EB5A727673A2441EE");
        BigInteger serverB = null;
        BigInteger aScalar = new BigInteger("60975527035CF2AD1989806F0407210BC81EDC04E2762A56AFD529DDDA2D4393", 16);
        BigInteger bScalar = new BigInteger("E487CB59D31AC550471E81F00F6928E01DDA08E974A004F49E61F5D105284D20", 16);
        BigInteger x = AirplayCrypto.srpX(salt, AirplayCrypto.SRP_USER, AirplayCrypto.SRP_PASSWORD);
        BigInteger verifier = AirplayCrypto.srpVerifier(salt, AirplayCrypto.SRP_USER,
                AirplayCrypto.SRP_PASSWORD);
        BigInteger A = AirplayCrypto.srpPublic(aScalar);
        serverB = AirplayCrypto.srpServerPublic(verifier, bScalar);
        BigInteger clientSecret = AirplayCrypto.srpClientSecret(aScalar, x, serverB);
        BigInteger serverSecret = AirplayCrypto.srpServerSecret(verifier, bScalar, A);
        check("SRP client and server agree on S", clientSecret.equals(serverSecret));
        check("SRP secret is a full-size group element",
                clientSecret.bitLength() > 3000 && clientSecret.signum() > 0);
        check("SRP public key is g^a mod N", A.equals(AirplayCrypto.SRP_G.modPow(aScalar, AirplayCrypto.SRP_N)));
        check("SRP group is 3072 bits", AirplayCrypto.SRP_N.bitLength() == 3072);
        check("SRP group generator is 5", AirplayCrypto.SRP_G.intValue() == 5);
        check("SRP padding is the group size", AirplayCrypto.pad(A).length == 384);

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST CRYPTO OK" : ("SELFTEST CRYPTO FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static byte[] hex(String text) {
        if (text.length() % 2 != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }
}
