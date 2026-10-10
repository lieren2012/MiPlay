package org.carplaytocarlife.iccoa;

import java.io.ByteArrayOutputStream;

/**
 * The decoder configuration an AirPlay receiver is handed before it can decode
 * anything: an AVCDecoderConfigurationRecord, not raw parameter sets.
 *
 * The reference builds it from the encoder's csd buffers with its own
 * {@code avcc(sps, pps)} and logs "Video codec configuration sent sps-bytes=...
 * pps-bytes=...". Sending the Annex-B parameter sets instead looks almost right
 * and is exactly the kind of thing that shows up as a stream the car accepts and
 * never decodes.
 *
 * <pre>
 *   01 &lt;sps[1]&gt; &lt;sps[2]&gt; &lt;sps[3]&gt; FF E1  u16 length, SPS   01  u16 length, PPS
 *   ^  profile  compat   level    ^  ^ numSPS=1                        ^ numPPS=1
 *   configurationVersion=1, lengthSizeMinusOne=3
 * </pre>
 *
 * The parameter sets go in without their start codes, so {@code sps[0]} is the
 * NAL header byte (0x67) and {@code avcc} reads profile, compatibility and level
 * from the three bytes after it.
 */
public final class H264Config {

    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;

    private H264Config() {
    }

    /**
     * Builds the AVCDecoderConfigurationRecord.
     *
     * @param sps sequence parameter set, NAL header included
     * @param pps picture parameter set, NAL header included
     */
    public static byte[] avcc(byte[] sps, byte[] pps) {
        if (sps == null || sps.length < 4 || sps.length >= 65536) {
            throw new IllegalArgumentException("an SPS has to be 4..65535 bytes");
        }
        if (pps == null || pps.length < 1 || pps.length >= 65536) {
            throw new IllegalArgumentException("a PPS has to be 1..65535 bytes");
        }
        if ((sps[0] & 0x1F) != NAL_SPS || (pps[0] & 0x1F) != NAL_PPS) {
            throw new IllegalArgumentException("expected an SPS followed by a PPS, got NAL types "
                    + (sps[0] & 0x1F) + " and " + (pps[0] & 0x1F));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        out.write(sps[1] & 0xFF);
        out.write(sps[2] & 0xFF);
        out.write(sps[3] & 0xFF);
        out.write(0xFF);   // lengthSizeMinusOne = 3
        out.write(0xE1);   // numOfSequenceParameterSets = 1
        out.write((sps.length >> 8) & 0xFF);
        out.write(sps.length & 0xFF);
        out.write(sps, 0, sps.length);
        out.write(1);      // numOfPictureParameterSets
        out.write((pps.length >> 8) & 0xFF);
        out.write(pps.length & 0xFF);
        out.write(pps, 0, pps.length);
        return out.toByteArray();
    }

    /**
     * The first SPS and the first PPS of an access unit, without start codes, or
     * null when the access unit carries neither.
     *
     * Encoders put them at the front of a key frame, in either order and with
     * three or four byte start codes, which is what this walks.
     */
    public static byte[][] parameterSets(byte[] annexB) {
        if (annexB == null || annexB.length < 8) {
            return null;
        }
        byte[] sps = null;
        byte[] pps = null;
        int offset = 0;
        while (offset < annexB.length) {
            int start = startCodeLength(annexB, offset);
            if (start == 0) {
                // a slice or trailing data: stop at the first non parameter set
                if (sps != null || pps != null) {
                    return sps != null && pps != null ? new byte[][] {sps, pps} : null;
                }
                return null;
            }
            int type = annexB[offset + start] & 0x1F;
            if (type != NAL_SPS && type != NAL_PPS) {
                return sps != null && pps != null ? new byte[][] {sps, pps} : null;
            }
            int end = offset + start;
            while (end + 3 < annexB.length && startCodeLength(annexB, end) == 0) {
                end++;
            }
            if (end + 3 >= annexB.length) {
                end = annexB.length;
            }
            byte[] nal = new byte[end - (offset + start)];
            System.arraycopy(annexB, offset + start, nal, 0, nal.length);
            if (type == NAL_SPS && sps == null) {
                sps = nal;
            } else if (type == NAL_PPS && pps == null) {
                pps = nal;
            }
            offset = end;
        }
        return sps != null && pps != null ? new byte[][] {sps, pps} : null;
    }

    /** 3 or 4 for a start code, 0 when there is none at this offset. */
    public static int startCodeLength(byte[] data, int offset) {
        if (offset + 4 <= data.length && data[offset] == 0 && data[offset + 1] == 0
                && data[offset + 2] == 0 && data[offset + 3] == 1) {
            return 4;
        }
        if (offset + 3 <= data.length && data[offset] == 0 && data[offset + 1] == 0
                && data[offset + 2] == 1) {
            return 3;
        }
        return 0;
    }
}
