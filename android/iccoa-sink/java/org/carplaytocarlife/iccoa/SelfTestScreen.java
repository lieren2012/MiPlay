package org.carplaytocarlife.iccoa;

import javax.crypto.Cipher;

/**
 * The screen stream's framing, as the car expects it.
 *
 * Two things here are easy to get subtly wrong and impossible to notice without
 * a car: the timestamp is NTP (seconds since 1900 in the high half), not a
 * microsecond counter, and the stream has to open with a config frame carrying
 * the parameter sets, in the clear, before any picture.
 */
public final class SelfTestScreen {

    private static int failures;

    public static void main(String[] args) throws Exception {
        // ---------------------------------------------------------- the timeline
        long unixMillis = 1_700_000_000_000L;
        long ntp = AirplayStream.ntpTimestamp(unixMillis);
        check("the seconds half is Unix time shifted to 1900",
                (ntp >>> 32) == unixMillis / 1000 + 2208988800L,
                "high=" + (ntp >>> 32));
        check("the fraction half is the milliseconds scaled to 2^32",
                ((ntp & 0xFFFFFFFFL) * 1000 >>> 32) == 0);
        check("the NTP value reads back as the same instant",
                AirplayStream.ntpMillis(ntp) == unixMillis,
                "read back " + AirplayStream.ntpMillis(ntp));
        check("a microsecond counter would land in the wrong half",
                (1_700_000_000_000_000L >>> 32) != unixMillis / 1000 + 2208988800L);

        // -------------------------------------------------------------- the frame
        AirplayStream stream = AirplayStream.create(new byte[16], new byte[16], 1);
        byte[] body = new byte[256];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i ^ 0x5A);
        }
        byte[] frame = stream.videoFrame(body, ntp);
        check("the frame is the 128 byte header plus the body",
                frame.length == AirplayStream.VIDEO_HEADER_LENGTH + body.length);
        check("the length is at offset 0 little endian",
                AirplayStream.frameLength(frame) == body.length);
        check("the timestamp is at offset 8 little endian", frame[8] == (byte) ntp
                && frame[15] == (byte) (ntp >>> 56));
        check("the timestamp reads back as NTP",
                AirplayStream.ntpMillis(AirplayStream.frameTimestamp(frame)) == unixMillis);
        check("the header's first bytes are the length little endian",
                frame[0] == (byte) (body.length & 0xFF)
                        && frame[1] == (byte) ((body.length >> 8) & 0xFF)
                        && frame[2] == 0 && frame[3] == 0);
        byte[] sealed = java.util.Arrays.copyOfRange(frame, 128, frame.length);
        check("the body is encrypted", !java.util.Arrays.equals(sealed, body));
        Cipher back = AirplayStream.decryptor(new byte[16], new byte[16], 1);
        check("the car's cipher recovers the body",
                java.util.Arrays.equals(back.update(sealed), body));

        // ------------------------------------------------------- the config frame
        // the payload is an AVCDecoderConfigurationRecord, not raw parameter sets
        byte[] sps = new byte[] {0x67, 0x42, 0x00, 0x1E, (byte) 0x95, (byte) 0xA0};
        byte[] pps = new byte[] {0x68, (byte) 0xCE, 0x3C, (byte) 0x80};
        byte[] avcc = H264Config.avcc(sps, pps);
        check("the configuration starts with version 1",
                avcc[0] == 1);
        check("profile, compatibility and level come from the SPS",
                avcc[1] == 0x42 && avcc[2] == 0x00 && avcc[3] == 0x1E);
        check("the length size and one SPS are declared",
                avcc[4] == (byte) 0xFF && avcc[5] == (byte) 0xE1);
        check("the SPS length is a big endian u16",
                avcc[6] == 0 && avcc[7] == sps.length);
        check("the SPS is carried whole, header byte included",
                avcc[8] == 0x67 && avcc[8 + sps.length - 1] == sps[sps.length - 1]);
        int ppsCount = 8 + sps.length;
        check("one PPS is declared after the SPS", avcc[ppsCount] == 1);
        check("the PPS length is a big endian u16",
                avcc[ppsCount + 1] == 0 && avcc[ppsCount + 2] == pps.length);
        check("the PPS is carried whole",
                avcc[ppsCount + 3] == 0x68
                        && avcc[ppsCount + 3 + pps.length - 1] == pps[pps.length - 1]);
        check("the configuration is exactly as long as its parts",
                avcc.length == 6 + 2 + sps.length + 1 + 2 + pps.length);

        byte[] config = AirplayStream.configFrame(avcc, 1280, 720);
        check("the config frame is the header plus the configuration",
                config.length == 128 + avcc.length);
        check("the config length is at offset 0",
                AirplayStream.configLength(config) == avcc.length);
        check("offset 4 carries version 1", config[4] == 1);
        check("offset 6 carries the flags the reference logs", config[6] == 4);
        float[] size = AirplayStream.configSize(config);
        check("the display size is two floats", size[0] == 1280f && size[1] == 720f);
        check("the configuration is sent in the clear",
                java.util.Arrays.equals(java.util.Arrays.copyOfRange(config, 128,
                        config.length), avcc));

        // ------------------------------- pulling the parameter sets out again
        byte[] accessUnit = concat(
                new byte[] {0, 0, 0, 1}, sps,
                new byte[] {0, 0, 0, 1}, pps,
                new byte[] {0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33});
        byte[][] sets = H264Config.parameterSets(accessUnit);
        check("the SPS and PPS are found at the front of a key frame",
                sets != null && java.util.Arrays.equals(sets[0], sps)
                        && java.util.Arrays.equals(sets[1], pps));
        check("the start codes are not part of the sets",
                sets[0][0] == 0x67 && sets[0].length == sps.length);
        check("a frame without parameter sets yields nothing",
                H264Config.parameterSets(new byte[] {0, 0, 0, 1, 0x41, 0x09, 0x10, 0x11}) == null);
        check("three byte start codes are understood",
                parameterSetsMatch(concat(
                        new byte[] {0, 0, 1}, new byte[] {0x67, 0x42, 0x00, 0x1E},
                        new byte[] {0, 0, 1}, new byte[] {0x68, (byte) 0xCE},
                        new byte[] {0, 0, 1, 0x65}),
                        new byte[] {0x67, 0x42, 0x00, 0x1E}, new byte[] {0x68, (byte) 0xCE}));
        check("a PPS before the SPS is still picked up",
                parameterSetsMatch(concat(
                        new byte[] {0, 0, 0, 1}, new byte[] {0x68, (byte) 0xCE},
                        new byte[] {0, 0, 0, 1}, new byte[] {0x67, 0x42, 0x00, 0x1E},
                        new byte[] {0, 0, 0, 1, 0x65}),
                        new byte[] {0x67, 0x42, 0x00, 0x1E}, new byte[] {0x68, (byte) 0xCE}));
        check("a payload with no start code yields nothing",
                H264Config.parameterSets(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}) == null);
        check("an SPS without a PPS yields nothing",
                H264Config.parameterSets(concat(
                        new byte[] {0, 0, 0, 1}, sps,
                        new byte[] {0, 0, 0, 1, 0x65, 0x11})) == null);

        // an encoder that only reports csd gives exactly this: SPS then PPS, no slice
        byte[] csd = concat(new byte[] {0, 0, 0, 1}, sps, new byte[] {0, 0, 0, 1}, pps);
        byte[][] fromCsd = H264Config.parameterSets(csd);
        check("a csd blob of parameter sets alone is understood",
                fromCsd != null && java.util.Arrays.equals(fromCsd[0], sps)
                        && java.util.Arrays.equals(fromCsd[1], pps));
        check("and turns into the same configuration as a key frame's",
                java.util.Arrays.equals(H264Config.avcc(fromCsd[0], fromCsd[1]), avcc));
        check("a csd blob with only an SPS yields nothing",
                H264Config.parameterSets(concat(new byte[] {0, 0, 0, 1}, sps)) == null);

        check("a configuration for the wrong NAL types is refused",
                refusedConfig(new byte[] {0x65, 0x42, 0x00, 0x1E}, pps));
        check("a configuration without an SPS is refused",
                refusedConfig(new byte[] {0x67, 0x42}, pps));
        check("a configuration without a PPS is refused",
                refusedConfig(sps, new byte[0]));

        // ------------------------------------------------- the encoder settings
        check("the bitrate follows the reference's bits-per-pixel rule",
                SurfaceEncoder.bitRateFor(1280, 720, 30) == 3_456_000,
                String.valueOf(SurfaceEncoder.bitRateFor(1280, 720, 30)));
        check("a tiny frame still gets a workable bitrate",
                SurfaceEncoder.bitRateFor(320, 240, 10) == 2_000_000);
        check("a huge frame is capped",
                SurfaceEncoder.bitRateFor(1920, 1200, 60) == 12_000_000);
        check("the reference's key frame interval is two seconds",
                SurfaceEncoder.I_FRAME_INTERVAL_SECONDS == 2);

        // the encode size is the phone's own screen scaled, not the car's panel
        int[] full = SurfaceEncoder.encodeSize(1080, 2400,
                SurfaceEncoder.DEFAULT_SCALE_PERCENT);
        check("at 100% the phone's screen is used as it is",
                full[0] == 1080 && full[1] == 2400, full[0] + "x" + full[1]);
        check("the default scale is 100 percent",
                SurfaceEncoder.DEFAULT_SCALE_PERCENT == 100);
        int[] threeQuarters = SurfaceEncoder.encodeSize(1080, 2400, 75);
        check("at 75% it is three quarters, rounded down to even",
                threeQuarters[0] == 810 && threeQuarters[1] == 1800,
                threeQuarters[0] + "x" + threeQuarters[1]);
        int[] half = SurfaceEncoder.encodeSize(1080, 2400, 50);
        check("at 50% it is half", half[0] == 540 && half[1] == 1200);
        int[] oddScreen = SurfaceEncoder.encodeSize(1081, 2401, 100);
        check("an odd screen size is rounded down",
                oddScreen[0] == 1080 && oddScreen[1] == 2400);
        int[] tiny = SurfaceEncoder.encodeSize(100, 100, 100);
        check("a tiny screen still gets the floor",
                tiny[0] == SurfaceEncoder.MIN_DIMENSION
                        && tiny[1] == SurfaceEncoder.MIN_DIMENSION);
        check("the phone's aspect ratio is what the stream carries",
                full[0] * 2400 == full[1] * 1080);

        // the virtual display's density follows the phone's, scaled with the size,
        // or the mirrored content is rendered at a different logical size
        check("at 100% the phone's own density is used",
                SurfaceEncoder.encodeDensity(440, 100) == 440);
        check("at 50% it is halved", SurfaceEncoder.encodeDensity(440, 50) == 220);
        check("at 75% it is three quarters",
                SurfaceEncoder.encodeDensity(440, 75) == 330);
        check("a device that reports no density still gets a usable one",
                SurfaceEncoder.encodeDensity(0, 100) == 1
                        && SurfaceEncoder.encodeDensity(1, 50) == 1);

        // the video watchdog: a car that stops reading must not wedge the sender
        long threshold = AirplayVideoTunnel.STALL_MILLIS * 1_000_000L;
        check("the stall limit matches the reference's twenty seconds",
                AirplayVideoTunnel.STALL_MILLIS == 20_000);
        check("a fresh stream is not stalled",
                !AirplayVideoTunnel.stalled(0, 0, threshold));
        check("a stream quiet for just under the limit is not stalled",
                !AirplayVideoTunnel.stalled(threshold - 1, 0, threshold));
        check("a stream quiet for exactly the limit is stalled",
                AirplayVideoTunnel.stalled(threshold, 0, threshold));
        check("and beyond it certainly is",
                AirplayVideoTunnel.stalled(threshold * 2, 0, threshold));
        check("a stream that keeps sending is never stalled",
                !AirplayVideoTunnel.stalled(threshold * 3, threshold * 3 - 1_000_000L,
                        threshold));

        // the car may cap the frame rate, and only downwards
        check("a car that announces nothing leaves the preference alone",
                SurfaceEncoder.selectedFps(30, 0) == 30);
        check("a car that announces less lowers it",
                SurfaceEncoder.selectedFps(30, 15) == 15);
        check("a car that announces more does not raise it",
                SurfaceEncoder.selectedFps(30, 60) == 30);
        check("the announced value is clamped to an hour's worth of frames",
                SurfaceEncoder.selectedFps(60, 240) == 60);
        check("an unusably low announcement is ignored",
                SurfaceEncoder.selectedFps(30, 1) == 30);
        check("the preferred rate is one the reference supports",
                java.util.Arrays.binarySearch(SurfaceEncoder.SUPPORTED_FPS,
                        SurfaceEncoder.PREFERRED_FPS) >= 0,
                String.valueOf(SurfaceEncoder.PREFERRED_FPS));

        // a failed session is retried, but only a few times
        check("a failed session is retried while attempts remain",
                SinkService.shouldRetry(0, SinkService.MAX_SESSION_RETRIES)
                        && SinkService.shouldRetry(2, SinkService.MAX_SESSION_RETRIES));
        check("and given up on at the limit",
                !SinkService.shouldRetry(SinkService.MAX_SESSION_RETRIES,
                        SinkService.MAX_SESSION_RETRIES)
                        && !SinkService.shouldRetry(9, SinkService.MAX_SESSION_RETRIES));
        check("the retry limit is small enough not to loop for ever",
                SinkService.MAX_SESSION_RETRIES > 0 && SinkService.MAX_SESSION_RETRIES <= 5);
        check("attempts use the reference's reconnection cadence",
                SinkService.RETRY_DELAY_MILLIS == 20000
                        && CarplayBluetooth.CONNECT_RETRY_DELAY_MILLIS == 20000,
                SinkService.RETRY_DELAY_MILLIS + " / "
                        + CarplayBluetooth.CONNECT_RETRY_DELAY_MILLIS);
        check("a failed Bluetooth connect is retried, but not for ever",
                CarplayBluetooth.shouldRetryConnect(0)
                        && CarplayBluetooth.shouldRetryConnect(
                                CarplayBluetooth.MAX_CONNECT_ATTEMPTS - 1)
                        && !CarplayBluetooth.shouldRetryConnect(
                                CarplayBluetooth.MAX_CONNECT_ATTEMPTS));

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST SCREEN OK" : (failures + " FAILED"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static boolean parameterSetsMatch(byte[] accessUnit, byte[] sps, byte[] pps) {
        byte[][] sets = H264Config.parameterSets(accessUnit);
        return sets != null && java.util.Arrays.equals(sets[0], sps)
                && java.util.Arrays.equals(sets[1], pps);
    }

    private static boolean refusedConfig(byte[] sps, byte[] pps) {
        try {
            H264Config.avcc(sps, pps);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
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

    private SelfTestScreen() {
    }
}
