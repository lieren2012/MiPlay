package org.carplaytocarlife.iccoa;

import javax.crypto.Cipher;

/** Desktop self test for the AirPlay stream cipher and the video envelope. */
public final class SelfTestStream {

    private static int failures;

    public static void main(String[] args) throws Exception {
        byte[] baseKey = new byte[16];
        byte[] baseIv = new byte[16];
        for (int i = 0; i < baseKey.length; i++) {
            baseKey[i] = (byte) (0xA0 + i);
            baseIv[i] = (byte) (0x30 + i);
        }

        byte[] key1 = AirplayStream.derive(1, AirplayStream.STREAM_KEY_LABEL, baseKey);
        byte[] key2 = AirplayStream.derive(1, AirplayStream.STREAM_KEY_LABEL, baseKey);
        byte[] iv1 = AirplayStream.derive(1, AirplayStream.STREAM_IV_LABEL, baseIv);
        check("key derivation is deterministic", java.util.Arrays.equals(key1, key2));
        check("key and IV differ", !java.util.Arrays.equals(key1, iv1));
        check("the IV comes from its own secret",
                !java.util.Arrays.equals(iv1,
                        AirplayStream.derive(1, AirplayStream.STREAM_IV_LABEL, baseKey)));
        check("derived material is 16 bytes", key1.length == 16 && iv1.length == 16);
        byte[] keyOtherCounter = AirplayStream.derive(2, AirplayStream.STREAM_KEY_LABEL, baseKey);
        check("the counter changes the key", !java.util.Arrays.equals(key1, keyOtherCounter));
        byte[] otherBase = baseKey.clone();
        otherBase[0] ^= 0x01;
        check("the base key changes the key",
                !java.util.Arrays.equals(key1,
                        AirplayStream.derive(1, AirplayStream.STREAM_KEY_LABEL, otherBase)));

        // the envelope: 128-byte header, length at 0, timestamp at 8, body after
        AirplayStream stream = AirplayStream.create(baseKey, baseIv, 1);
        byte[] body = new byte[300];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        byte[] frame = stream.videoFrame(body, 123_456_789L);
        check("frame carries the 128 byte header",
                frame.length == AirplayStream.VIDEO_HEADER_LENGTH + body.length);
        check("length is at offset 0 little endian",
                AirplayStream.frameLength(frame) == body.length);
        check("timestamp is at offset 8 little endian (an NTP value)",
                AirplayStream.frameTimestamp(frame) == 123_456_789L);

        // the body must be encrypted, and must decrypt with a fresh matching cipher
        byte[] encrypted = java.util.Arrays.copyOfRange(frame, AirplayStream.VIDEO_HEADER_LENGTH,
                frame.length);
        check("the body is not sent in the clear", !java.util.Arrays.equals(encrypted, body));

        Cipher decryptor = AirplayStream.decryptor(baseKey, baseIv, 1);
        byte[] first = decryptor.update(encrypted);
        check("a fresh decryptor reproduces the first frame", java.util.Arrays.equals(first, body));

        // CTR continuation: the second frame continues the keystream, so a
        // decryptor that was used for the first frame decrypts the second one
        byte[] secondBody = new byte[180];
        for (int i = 0; i < secondBody.length; i++) {
            secondBody[i] = (byte) (0x5A ^ i);
        }
        byte[] secondFrame = stream.videoFrame(secondBody, 234_567_890L);
        check("the frame counter advances", stream.frames() == 2);
        byte[] secondEncrypted = java.util.Arrays.copyOfRange(secondFrame,
                AirplayStream.VIDEO_HEADER_LENGTH, secondFrame.length);
        byte[] secondBack = decryptor.update(secondEncrypted);
        check("CTR continues across frames (one continuous keystream)",
                java.util.Arrays.equals(secondBack, secondBody));
        check("the second timestamp is carried",
                AirplayStream.frameTimestamp(secondFrame) == 234_567_890L);

        // the IV has to matter: the same body under another base IV must differ
        AirplayStream otherIv = AirplayStream.create(baseKey, baseIv.clone(), 1);
        otherIv.videoFrame(body, 1L);
        byte[] sameBodyOtherIv = java.util.Arrays.copyOfRange(
                AirplayStream.create(baseKey, new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9}, 1)
                        .videoFrame(body, 1L),
                AirplayStream.VIDEO_HEADER_LENGTH, 128 + body.length);
        check("a different base IV gives a different keystream",
                !java.util.Arrays.equals(sameBodyOtherIv, encrypted));

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST STREAM OK" : ("SELFTEST STREAM FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
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
