package org.carplaytocarlife.iccoa;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.nio.ByteBuffer;

/**
 * H.264 encoder fed with YUV frames generated in Java.
 *
 * Deliberately does not use a Surface or MediaProjection: the first video path
 * has to work without extra permissions, and a synthetic pattern proves the
 * pipeline end to end. A real content source can later feed the same sink.
 */
public final class H264Encoder {

    public interface Sink {
        void onAccessUnit(byte[] annexB, long timestampUs, boolean keyframe);
    }

    private MediaCodec codec;
    private Thread feeder;
    private volatile boolean running;
    private final int width;
    private final int height;
    private final int fps;

    public H264Encoder(int width, int height, int fps) {
        this.width = width;
        this.height = height;
        this.fps = fps;
    }

    public boolean start(final Sink sink) {
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, SurfaceEncoder.bitRateFor(width, height, fps));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,
                    SurfaceEncoder.I_FRAME_INTERVAL_SECONDS);
            format.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            Logs.add("encoder started " + width + "x" + height + "@" + fps);
        } catch (Throwable error) {
            Logs.add("cannot start the encoder: " + error);
            codec = null;
            return false;
        }
        running = true;
        feeder = new Thread(new Runnable() {
            @Override
            public void run() {
                pump(sink);
            }
        }, "h264-encoder");
        feeder.setDaemon(true);
        feeder.start();
        return true;
    }

    public void stop() {
        running = false;
        if (feeder != null) {
            feeder.interrupt();
            feeder = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            codec = null;
        }
        Logs.add("encoder stopped");
    }

    public boolean isRunning() {
        return running && codec != null;
    }

    private void pump(Sink sink) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long frame = 0;
        long sent = 0;
        long frameIntervalUs = 1_000_000L / fps;
        try {
            while (running) {
                int index = codec.dequeueInputBuffer(10000);
                if (index >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(index);
                    if (buffer != null) {
                        fillPattern(buffer, (int) frame);
                        codec.queueInputBuffer(index, 0, buffer.capacity(),
                                frame * frameIntervalUs, 0);
                        frame++;
                    }
                }
                while (true) {
                    int out = codec.dequeueOutputBuffer(info, 0);
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER
                            || out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        break;
                    }
                    if (out < 0) {
                        break;
                    }
                    ByteBuffer encoded = codec.getOutputBuffer(out);
                    if (encoded != null && info.size > 0) {
                        byte[] unit = new byte[info.size];
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        encoded.get(unit);
                        boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                || config;
                        sink.onAccessUnit(unit, info.presentationTimeUs, keyframe);
                        sent++;
                        if (sent == 1 || sent % 120 == 0) {
                            Logs.add("encoder produced " + sent + " access units (last "
                                    + unit.length + " bytes, keyframe=" + keyframe + ")");
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                }
                Thread.sleep(Math.max(1, frameIntervalUs / 1000));
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable error) {
            Logs.add("encoder loop ended: " + error);
        }
    }

    /** A moving gradient so the car shows something recognisable. */
    private void fillPattern(ByteBuffer buffer, int frame) {
        buffer.clear();
        int pixels = width * height;
        int shift = frame * 4;
        for (int y = 0; y < height; y++) {
            int luma = 16 + ((y * 219 / height + shift) % 220);
            for (int x = 0; x < width; x++) {
                int value = (luma + x * 3 / 4) & 0xFF;
                buffer.put((byte) Math.max(16, Math.min(235, value)));
            }
        }
        for (int i = 0; i < pixels / 2; i++) {
            int value = 128 + ((frame + i / 64) % 32) - 16;
            buffer.put((byte) Math.max(16, Math.min(240, value)));
        }
        for (int i = 0; i < pixels / 2; i++) {
            int value = 128 - ((frame + i / 64) % 32) + 16;
            buffer.put((byte) Math.max(16, Math.min(240, value)));
        }
    }
}
