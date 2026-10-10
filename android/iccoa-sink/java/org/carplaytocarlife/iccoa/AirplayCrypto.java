package org.carplaytocarlife.iccoa;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/**
 * The cryptography AirPlay 2 pairing needs.
 *
 * Every constant here is evidence-based: the SRP group is RFC 5054's published
 * 3072-bit prime with g = 5 (the reference app uses BouncyCastle's SRP6 with the
 * standard groups), the pairing user name and PIN come from the reference app's
 * own string table ("Pair-Setup" / "Pair-Setup:3939"), and the HKDF salts and
 * info strings are the ones that app uses verbatim. X25519 and Ed25519 are
 * covered by the RFC 7748 / RFC 8032 test vectors in the self test.
 */
public final class AirplayCrypto {

    public static final String SRP_USER = "Pair-Setup";
    public static final String SRP_PASSWORD = "3939";

    /** RFC 5054, appendix A: the 3072-bit group. */
    public static final BigInteger SRP_N = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
          + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
          + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
          + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
          + "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
          + "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
          + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
          + "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33"
          + "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7"
          + "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864"
          + "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2"
          + "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16);
    public static final BigInteger SRP_G = BigInteger.valueOf(5);

    public static final String SALT_PAIR_SETUP_ENCRYPT = "Pair-Setup-Encrypt-Salt";
    public static final String INFO_PAIR_SETUP_ENCRYPT = "Pair-Setup-Encrypt-Info";
    public static final String SALT_CONTROLLER_SIGN = "Pair-Setup-Controller-Sign-Salt";
    public static final String INFO_CONTROLLER_SIGN = "Pair-Setup-Controller-Sign-Info";
    public static final String SALT_ACCESSORY_SIGN = "Pair-Setup-Accessory-Sign-Salt";
    public static final String INFO_ACCESSORY_SIGN = "Pair-Setup-Accessory-Sign-Info";
    public static final String SALT_PAIR_VERIFY_ENCRYPT = "Pair-Verify-Encrypt-Salt";
    public static final String INFO_PAIR_VERIFY_ENCRYPT = "Pair-Verify-Encrypt-Info";

    private AirplayCrypto() {
    }

    /**
     * The twelve byte nonce a label names.
     *
     * Four zero bytes, then the ASCII label - the convention the reference
     * derives its pairing nonces with ({@code AirCrypto.label}). "PS-Msg05",
     * "PS-Msg06", "PV-Msg02" and "PV-Msg03" are the four the pairing flow uses,
     * and they differ per direction: sealing the controller blob and opening the
     * accessory one with the same nonce is a real car's authentication failure.
     */
    public static byte[] label(String name) {
        byte[] nonce = new byte[12];
        byte[] text = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, nonce, 4, Math.min(text.length, 8));
        return nonce;
    }

    public static byte[] sha512(byte[]... chunks) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            for (byte[] chunk : chunks) {
                digest.update(chunk);
            }
            return digest.digest();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-512 unavailable", error);
        }
    }

    /** HKDF with SHA-512, which is what AirPlay 2 uses for its key schedule. */
    public static byte[] hkdfSha512(byte[] ikm, String salt, String info, int length) {
        byte[] saltBytes = salt == null ? new byte[0] : salt.getBytes(StandardCharsets.UTF_8);
        byte[] infoBytes = info == null ? new byte[0] : info.getBytes(StandardCharsets.UTF_8);
        byte[] prk = hmacSha512(saltBytes.length == 0 ? new byte[64] : saltBytes, ikm);
        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        for (int counter = 1; offset < length; counter++) {
            previous = hmacSha512(prk, concat(previous, infoBytes, new byte[] {(byte) counter}));
            int take = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, output, offset, take);
            offset += take;
        }
        return output;
    }

    private static byte[] hmacSha512(byte[] key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA512");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA512"));
            return mac.doFinal(data);
        } catch (Exception error) {
            throw new IllegalStateException("HmacSHA512 unavailable", error);
        }
    }

    public static byte[] concat(byte[]... chunks) {
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, offset, chunk.length);
            offset += chunk.length;
        }
        return out;
    }

    // ------------------------------------------------------------- X25519

    public static byte[] x25519PublicKey(byte[] scalar) {
        return new X25519PrivateKeyParameters(scalar, 0).generatePublicKey().getEncoded();
    }

    public static byte[] x25519SharedSecret(byte[] scalar, byte[] peerPublicLittleEndian) {
        X25519PrivateKeyParameters own = new X25519PrivateKeyParameters(scalar, 0);
        X25519PublicKeyParameters peer = new X25519PublicKeyParameters(peerPublicLittleEndian, 0);
        byte[] secret = new byte[32];
        own.generateSecret(peer, secret, 0);
        return secret;
    }

    // ------------------------------------------------------------ Ed25519

    public static byte[] ed25519PublicKey(byte[] seed) {
        return new Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().getEncoded();
    }

    public static byte[] ed25519Sign(byte[] seed, byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, new Ed25519PrivateKeyParameters(seed, 0));
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    public static boolean ed25519Verify(byte[] publicKey, byte[] message, byte[] signature) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        signer.update(message, 0, message.length);
        return signer.verifySignature(signature);
    }

    // ---------------------------------------------------------------- SRP

    /** x = H(salt || H(user ":" password)) as in RFC 5054. */
    public static BigInteger srpX(byte[] salt, String user, String password) {
        byte[] inner = sha512((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new BigInteger(1, sha512(salt, inner));
    }

    /** k = H(N || PAD(g)). */
    public static BigInteger srpK() {
        return new BigInteger(1, sha512(SRP_N.toByteArray(), pad(SRP_G)));
    }

    /** u = H(PAD(A) || PAD(B)). */
    public static BigInteger srpU(BigInteger a, BigInteger b) {
        return new BigInteger(1, sha512(pad(a), pad(b)));
    }

    public static BigInteger srpPublic(BigInteger exponent) {
        return SRP_G.modPow(exponent, SRP_N);
    }

    public static BigInteger srpVerifier(byte[] salt, String user, String password) {
        return SRP_G.modPow(srpX(salt, user, password), SRP_N);
    }

    /** Server side: B = k*v + g^b mod N (used by the self test). */
    public static BigInteger srpServerPublic(BigInteger verifier, BigInteger b) {
        BigInteger k = srpK();
        return k.multiply(verifier).add(SRP_G.modPow(b, SRP_N)).mod(SRP_N);
    }

    /** Client shared secret: S = (B - k*g^x)^(a + u*x) mod N. */
    public static BigInteger srpClientSecret(BigInteger a, BigInteger x, BigInteger B) {
        BigInteger k = srpK();
        BigInteger u = srpU(srpPublic(a), B);
        BigInteger base = B.subtract(k.multiply(SRP_G.modPow(x, SRP_N))).mod(SRP_N);
        return base.modPow(a.add(u.multiply(x)), SRP_N);
    }

    /** Server shared secret: S = (A * v^u)^b mod N. */
    public static BigInteger srpServerSecret(BigInteger verifier, BigInteger b, BigInteger A) {
        BigInteger u = srpU(A, srpServerPublic(verifier, b));
        return A.multiply(verifier.modPow(u, SRP_N)).mod(SRP_N).modPow(b, SRP_N);
    }

    public static byte[] pad(BigInteger value) {
        byte[] raw = value.toByteArray();
        int size = (SRP_N.bitLength() + 7) / 8;
        if (raw.length == size) {
            return raw;
        }
        byte[] out = new byte[size];
        if (raw.length > size) {
            System.arraycopy(raw, raw.length - size, out, 0, size);
        } else {
            System.arraycopy(raw, 0, out, size - raw.length, raw.length);
        }
        return out;
    }
}
