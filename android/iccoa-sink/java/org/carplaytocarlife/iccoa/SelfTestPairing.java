package org.carplaytocarlife.iccoa;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;

/**
 * Loopback test: this class plays the accessory (the car) so the whole pairing
 * exchange runs without hardware. Both sides derive the same keys, the proofs
 * and signatures have to validate, and tampered blobs must be rejected.
 */
public final class SelfTestPairing {

    private static int failures;

    public static void main(String[] args) {
        SecureRandom random = new SecureRandom();
        String pin = AirplayCrypto.SRP_PASSWORD;

        // ---- the accessory's side of SRP
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        BigInteger b = new BigInteger(256, random);
        BigInteger verifier = AirplayCrypto.srpVerifier(salt, AirplayCrypto.SRP_USER, pin);
        BigInteger B = AirplayCrypto.srpServerPublic(verifier, b);
        byte[] publicB = AirplayCrypto.pad(B);

        // ---- the controller (our code)
        AirplayPairing controller = new AirplayPairing(pin);
        byte[] publicA = controller.srpPublicKey();
        controller.acceptAccessoryParameters(salt, publicB);
        byte[] clientProof = controller.clientProof();

        // the accessory recomputes S and checks the proof
        BigInteger serverSecret = AirplayCrypto.srpServerSecret(verifier, b, new BigInteger(1, publicA));
        byte[] k = AirplayCrypto.sha512(AirplayCrypto.pad(serverSecret));
        byte[] hN = AirplayCrypto.sha512(AirplayCrypto.pad(AirplayCrypto.SRP_N));
        byte[] hG = AirplayCrypto.sha512(AirplayCrypto.pad(AirplayCrypto.SRP_G));
        byte[] hI = AirplayCrypto.sha512(AirplayCrypto.SRP_USER.getBytes());
        byte[] xor = new byte[hN.length];
        for (int i = 0; i < xor.length; i++) {
            xor[i] = (byte) (hN[i] ^ hG[i]);
        }
        byte[] expectedProof = AirplayCrypto.sha512(xor, hI, salt, publicA, publicB, k);
        check("accessory accepts the controller's SRP proof",
                Arrays.equals(clientProof, expectedProof));
        check("accessory derives the same session key",
                Arrays.equals(controller.sessionKey(),
                        AirplayCrypto.hkdfSha512(AirplayCrypto.pad(serverSecret),
                                AirplayCrypto.SALT_PAIR_SETUP_ENCRYPT,
                                AirplayCrypto.INFO_PAIR_SETUP_ENCRYPT, 32)));

        // ---- the encrypted exchange of long term keys (M5/M6)
        byte[] controllerBlob = controller.encryptSetup(AirplayCrypto.concat(
                controller.ownPairingId(), controller.ownEd25519PublicKey(),
                controller.signOwnKey(AirplayCrypto.SALT_CONTROLLER_SIGN,
                        AirplayCrypto.INFO_CONTROLLER_SIGN)));
        byte[] opened = AirplayPairing.open(controller.sessionKey(),
                AirplayPairing.PS_MSG05, controllerBlob);
        check("the accessory can open the controller's blob", opened != null);
        byte[] id = Arrays.copyOfRange(opened, 0, 16);
        byte[] publicKey = Arrays.copyOfRange(opened, 16, 48);
        byte[] signature = Arrays.copyOfRange(opened, 48, opened.length);
        byte[] signInfo = AirplayCrypto.hkdfSha512(controller.sessionKey(),
                AirplayCrypto.SALT_CONTROLLER_SIGN, AirplayCrypto.INFO_CONTROLLER_SIGN, 32);
        check("the controller's key signature verifies on the accessory",
                AirplayCrypto.ed25519Verify(publicKey,
                        AirplayCrypto.concat(signInfo, id, publicKey), signature));

        // the accessory answers with its own key material
        byte[] accessorySeed = new byte[32];
        random.nextBytes(accessorySeed);
        byte[] accessoryPublic = AirplayCrypto.ed25519PublicKey(accessorySeed);
        byte[] accessoryId = new byte[16];
        random.nextBytes(accessoryId);
        byte[] accessorySignInfo = AirplayCrypto.hkdfSha512(controller.sessionKey(),
                AirplayCrypto.SALT_ACCESSORY_SIGN, AirplayCrypto.INFO_ACCESSORY_SIGN, 32);
        byte[] accessorySignature = AirplayCrypto.ed25519Sign(accessorySeed,
                AirplayCrypto.concat(accessorySignInfo, accessoryId, accessoryPublic));
        byte[] accessoryBlob = AirplayPairing.seal(controller.sessionKey(),
                AirplayPairing.PS_MSG06,
                AirplayCrypto.concat(accessoryId, accessoryPublic, accessorySignature));
        byte[] accessoryOpened = controller.decryptSetup(accessoryBlob);
        byte[] accessoryIdBack = Arrays.copyOfRange(accessoryOpened, 0, 16);
        byte[] accessoryPublicBack = Arrays.copyOfRange(accessoryOpened, 16, 48);
        byte[] accessorySignatureBack = Arrays.copyOfRange(accessoryOpened, 48, accessoryOpened.length);
        check("the controller verifies the accessory's key",
                AirplayPairing.verifyAccessoryKey(controller.sessionKey(),
                        AirplayCrypto.SALT_ACCESSORY_SIGN, AirplayCrypto.INFO_ACCESSORY_SIGN,
                        accessoryPublicBack, accessoryIdBack, accessorySignatureBack));

        // ---- pair-verify
        byte[] accessoryEphemeralScalar = new byte[32];
        random.nextBytes(accessoryEphemeralScalar);
        byte[] accessoryEphemeralPublic = AirplayCrypto.x25519PublicKey(accessoryEphemeralScalar);
        byte[] verifyKeyAccessory = AirplayCrypto.hkdfSha512(
                AirplayCrypto.x25519SharedSecret(accessoryEphemeralScalar, controller.verifyPublicKey()),
                AirplayCrypto.SALT_PAIR_VERIFY_ENCRYPT, AirplayCrypto.INFO_PAIR_VERIFY_ENCRYPT, 32);
        byte[] verifyKeyController = controller.acceptVerifyPublicKey(accessoryEphemeralPublic);
        check("both sides derive the same pair-verify key",
                Arrays.equals(verifyKeyAccessory, verifyKeyController));

        // the signature covers our key, our id and the accessory's key, so the
        // accessory has to hand its ephemeral key back in
        byte[] verifyBlob = controller.encryptVerify(verifyKeyController, Tlv8.encode(
                new java.util.LinkedHashMap<Integer, byte[]>() {{
                    put(Tlv8.IDENTIFIER, controller.ownPairingId());
                    put(Tlv8.SIGNATURE,
                            controller.verifySignature(accessoryEphemeralPublic));
                }}));
        byte[] verifyOpened = AirplayPairing.open(verifyKeyAccessory, AirplayPairing.PV_MSG03, verifyBlob);
        check("the accessory can open the pair-verify blob", verifyOpened != null);
        Map<Integer, byte[]> verifyItems = Tlv8.decode(verifyOpened);
        check("the pair-verify signature verifies",
                AirplayPairing.verifyAccessoryVerify(controller.ownEd25519PublicKey(),
                        controller.verifyPublicKey(),
                        Tlv8.value(verifyItems, Tlv8.IDENTIFIER, 0),
                        accessoryEphemeralPublic,
                        Tlv8.value(verifyItems, Tlv8.SIGNATURE, 0)));

        // ---- tampering must be rejected
        byte[] tampered = controllerBlob.clone();
        tampered[tampered.length - 1] ^= 0x01;
        check("a tampered setup blob is rejected",
                AirplayPairing.open(controller.sessionKey(), AirplayPairing.PS_MSG05, tampered) == null);
        // ---- the TLV8 encoding the two endpoints actually speak
        java.util.LinkedHashMap<Integer, byte[]> items = new java.util.LinkedHashMap<>();
        items.put(Tlv8.METHOD, Tlv8.byteValue(1));
        byte[] big = new byte[384];
        random.nextBytes(big);
        items.put(Tlv8.PUBLIC_KEY, big);
        items.put(Tlv8.IDENTIFIER, new byte[0]);
        byte[] encoded = Tlv8.encode(items);
        // walk the body back: the 384 byte value has to arrive as repeated items of
        // the same type, none longer than 255, adding up to the original size
        int fragments = 0;
        int fragmentBytes = 0;
        int longest = 0;
        for (int i = 0; i + 1 < encoded.length; ) {
            int type = encoded[i] & 0xFF;
            int length = encoded[i + 1] & 0xFF;
            if (type == Tlv8.PUBLIC_KEY) {
                fragments++;
                fragmentBytes += length;
                longest = Math.max(longest, length);
            }
            i += 2 + length;
        }
        check("a 384 byte value goes out as fragments of at most 255",
                fragments == 2 && fragmentBytes == 384 && longest == 255,
                fragments + " fragments, " + fragmentBytes + " bytes, longest " + longest);
        Map<Integer, byte[]> back = Tlv8.decode(encoded);
        check("and comes back whole", Arrays.equals(back.get(Tlv8.PUBLIC_KEY), big));
        check("an empty value survives", back.get(Tlv8.IDENTIFIER).length == 0);
        check("the one byte items survive", Tlv8.byteOf(back, Tlv8.METHOD) == 1);
        check("a truncated body is refused",
                refused(Arrays.copyOf(encoded, encoded.length - 3)));
        check("a missing item is refused", missing());
        check("a value of the wrong size is refused", wrongSize());
        check("an item that is absent reads as -1",
                Tlv8.byteOf(back, Tlv8.ERROR) == -1);

        byte[] wrongKey = new byte[32];
        check("the wrong session key is rejected",
                AirplayPairing.open(wrongKey, AirplayPairing.PS_MSG05, controllerBlob) == null);

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST PAIRING OK" : ("SELFTEST PAIRING FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    /** True when a TLV body cannot be read. */
    private static boolean refused(byte[] body) {
        try {
            Tlv8.decode(body);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    /** True when a missing item is reported instead of read as empty. */
    private static boolean missing() {
        try {
            Tlv8.value(new java.util.LinkedHashMap<Integer, byte[]>(), Tlv8.SALT, 0);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    /** True when an item of the wrong length is refused. */
    private static boolean wrongSize() {
        try {
            java.util.LinkedHashMap<Integer, byte[]> items = new java.util.LinkedHashMap<>();
            items.put(Tlv8.PUBLIC_KEY, new byte[16]);
            Tlv8.value(items, Tlv8.PUBLIC_KEY, 32);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static void check(String name, boolean condition) {
        check(name, condition, "");
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }
}
