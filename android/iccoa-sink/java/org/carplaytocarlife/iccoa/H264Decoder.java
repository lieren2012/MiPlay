package org.carplaytocarlife.iccoa;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;

/** Decodes the H.264 the phone sends into a SurfaceView. */
public final class H264Decoder {

    private static final String MIME = "video/avc";

    private MediaCodec codec;
    private Surface surface;
    private byte[] csd;
    private long frames;
    private boolean failed;

    public synchronized void setSurface(Surface newSurface) {
        if (surface == newSurface) {
            return;
        }
        release();
        surface = newSurface;
        failed = false;
        Logs.add("decoder surface " + (newSurface == null ? "cleared" : "set"));
    }

    public synchronized void feed(byte[] annexB, long timestampUs) {
        if (surface == null || failed || annexB == null || annexB.length == 0) {
            return;
        }
        byte[] spsPps = collectParameterSets(annexB);
        try {
            if (codec == null) {
                if (spsPps == null) {
                    // Wait for SPS/PPS before configuring, otherwise the codec
                    // usually rejects the first frames.
                    if (frames == 0) {
                        Logs.add("waiting for SPS/PPS before starting the decoder");
                    }
                    return;
                }
                start(spsPps);
            }
            drain();
            int index = codec.dequeueInputBuffer(10000);
            if (index < 0) {
                return;
            }
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || buffer.capacity() < annexB.length) {
                codec.queueInputBuffer(index, 0, 0, timestampUs, 0);
                return;
            }
            buffer.clear();
            buffer.put(annexB);
            codec.queueInputBuffer(index, 0, annexB.length, timestampUs, 0);
            frames++;
            if (frames == 1 || frames % 120 == 0) {
                Logs.add("decoder fed " + frames + " access units (last "
                        + annexB.length + " bytes)");
            }
            drain();
        } catch (Throwable error) {
            failed = true;
            Logs.add("decoder error: " + error);
            release();
        }
    }

    public synchronized long frames() {
        return frames;
    }

    public synchronized void release() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable ignored) {
                // stopping a codec that never started is fine
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
                // nothing useful to do
            }
            codec = null;
        }
        csd = null;
    }

    private void start(byte[] spsPps) throws Exception {
        csd = spsPps;
        codec = MediaCodec.createDecoderByType(MIME);
        MediaFormat format = MediaFormat.createVideoFormat(MIME, 1920, 1080);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(spsPps));
        codec.configure(format, surface, null, 0);
        codec.start();
        Logs.add("decoder started (" + spsPps.length + " bytes of SPS/PPS)");
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index >= 0) {
                codec.releaseOutputBuffer(index, true);
            } else {
                return;
            }
        }
    }

    /** Pulls SPS (7) and PPS (8) out of an Annex-B buffer, if present. */
    private byte[] collectParameterSets(byte[] annexB) {
        java.io.ByteArrayOutputStream out = null;
        int offset = 0;
        while (offset + 4 <= annexB.length) {
            if (annexB[offset] != 0 || annexB[offset + 1] != 0 || annexB[offset + 2] != 1) {
                offset++;
                continue;
            }
            int start = offset;
            int next = offset + 3;
            while (next + 4 <= annexB.length
                    && !(annexB[next] == 0 && annexB[next + 1] == 0 && annexB[next + 2] == 1)) {
                next++;
            }
            int nalType = annexB[offset + 3] & 0x1F;
            if (nalType == 7 || nalType == 8) {
                if (out == null) {
                    out = new java.io.ByteArrayOutputStream();
                }
                out.write(annexB, start, next - start);
            }
            offset = next;
        }
        if (out == null) {
            return csd;
        }
        return out.toByteArray();
    }
}
