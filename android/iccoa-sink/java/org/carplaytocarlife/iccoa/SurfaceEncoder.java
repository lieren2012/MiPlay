package org.carplaytocarlife.iccoa;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * H.264 encoder whose input is a Surface.
 *
 * The car wants an AirPlay video stream: H.264 access units, Annex-B, with the
 * access unit boundaries preserved (the packetiser in {@link VideoSender} does
 * the fragmentation). Rendering into {@link #inputSurface()} is how a real
 * content source feeds it - a MediaProjection virtual display, an OpenGL
 * context, a MediaPlayer - and it keeps colour conversion inside the codec
 * instead of in Java.
 */
public final class SurfaceEncoder {

    /** Receives Annex-B access units in presentation order. */
    public interface Sink {
        void onAccessUnit(byte[] annexB, long timestampUs, boolean keyframe);
    }

    private final int width;
    private final int height;
    /** The reference asks the encoder to repeat a still frame this often (seconds). */
    public static final int I_FRAME_INTERVAL_SECONDS = 2;

    private final int fps;
    private final int bitRate;

    /**
     * The bitrate the reference asks for: a bits-per-pixel rule clamped to a
     * workable range, rather than a fixed number that starves a big screen.
     */
    public static int bitRateFor(int width, int height, int fps) {
        long rule = ((long) width * height * fps) / 8;
        if (rule < 2_000_000L) {
            return 2_000_000;
        }
        if (rule > 12_000_000L) {
            return 12_000_000;
        }
        return (int) rule;
    }

    /** The share of the phone's own screen size the reference encodes by default. */
    public static final int DEFAULT_SCALE_PERCENT = 100;
    /** No dimension goes below this. */
    public static final int MIN_DIMENSION = 128;

    /**
     * The size to encode at.
     *
     * It is the phone's own screen, scaled - not the size the car reports in
     * /info. The reference computes
     * {@code max(((scale * size) / 100 / 2) * 2, 128)}, i.e. the display size
     * rounded down to an even number at the chosen percentage. Encoding at the
     * car's panel size instead would send it a picture whose aspect ratio does
     * not match the phone, which shows up as a stretched or cropped screen.
     *
     * @param scalePercent one of the reference's 50, 75 or 100
     */
    public static int[] encodeSize(int displayWidth, int displayHeight, int scalePercent) {
        return new int[] {
            evenAtLeast((scalePercent * Math.max(displayWidth, 1)) / 100),
            evenAtLeast((scalePercent * Math.max(displayHeight, 1)) / 100)
        };
    }

    private static int evenAtLeast(int value) {
        return Math.max((value / 2) * 2, MIN_DIMENSION);
    }

    /**
     * The density the virtual display is created with.
     *
     * A mirror is pixel for pixel when the virtual display carries the real
     * display's density, and downscaling by a percentage should keep the logical
     * layout the same and only shrink the pixels - so the density follows the
     * scale instead of being a fixed number.
     */
    public static int encodeDensity(int realDensityDpi, int scalePercent) {
        int density = (Math.max(realDensityDpi, 1) * scalePercent) / 100;
        return Math.max(density, 1);
    }

    /** The frame rates the reference is willing to encode at. */
    public static final int[] SUPPORTED_FPS = {15, 30, 60};
    /** What this side would like when the car does not limit it. */
    public static final int PREFERRED_FPS = 30;

    /**
     * The frame rate to encode at.
     *
     * The reference takes {@code min(its own preference, the car's announced
     * value clamped to 1..60)}. A car that announces nothing usable - or a value
     * below the slowest rate worth streaming - leaves the preference alone rather
     * than dropping the stream to one frame a second.
     */
    public static int selectedFps(int preferred, int carAnnounced) {
        int wanted = carAnnounced >= SUPPORTED_FPS[0] ? Math.min(carAnnounced, 60) : preferred;
        return Math.min(preferred, wanted);
    }

    private MediaCodec codec;
    private Surface surface;
    private Thread drainer;
    private volatile boolean running;
    private long frames;
    /**
     * The encoder's own configuration bytes, when it has any.
     *
     * Most encoders also prepend the parameter sets to a key frame, but not all
     * of them do, and without them the car has nothing to set its decoder up
     * with. Whatever the codec reports as csd is kept here so the stream can
     * always be introduced properly.
     */
    private volatile byte[] codecConfig;

    public SurfaceEncoder(int width, int height, int fps, int bitRate) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitRate = bitRate;
    }

    public boolean start(final Sink sink) {
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS);
            // a car decoder is a plain H.264 decoder: baseline, no B frames, and
            // a repeat so a still screen still produces access units
            format.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            format.setFloat("max-fps-to-encoder", fps);
            format.setLong("repeat-previous-frame-after", 1_000_000L / fps);
            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();
            Logs.add("surface encoder started " + width + "x" + height + "@" + fps
                    + " at " + bitRate + " bit/s");
        } catch (Throwable error) {
            Logs.add("cannot start the surface encoder: " + error);
            release();
            return false;
        }
        running = true;
        drainer = new Thread(new Runnable() {
            @Override
            public void run() {
                drain(sink);
            }
        }, "h264-drain");
        drainer.setDaemon(true);
        drainer.start();
        return true;
    }

    /** The Surface a producer renders into, or null before a successful start. */
    public Surface inputSurface() {
        return surface;
    }

    public boolean isRunning() {
        return running && codec != null;
    }

    public long frames() {
        return frames;
    }

    /** The codec's own configuration bytes (SPS and PPS), or null if it gave none. */
    public byte[] codecConfig() {
        return codecConfig;
    }

    /** Asks the codec for an IDR frame, used when the car joins or recovers. */
    public void requestKeyFrame() {
        MediaCodec target = codec;
        if (target == null) {
            return;
        }
        try {
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            target.setParameters(parameters);
        } catch (Throwable error) {
            Logs.add("cannot request a key frame: " + error);
        }
    }

    public void stop() {
        running = false;
        if (drainer != null) {
            drainer.interrupt();
            drainer = null;
        }
        release();
        Logs.add("surface encoder stopped after " + frames + " access units");
    }

    private void release() {
        if (surface != null) {
            try {
                surface.release();
            } catch (Throwable ignored) {
            }
            surface = null;
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
    }

    /** Keeps the csd buffers the codec reports as the stream's configuration. */
    private void captureConfig(MediaFormat format) {
        java.nio.ByteBuffer zero = format.getByteBuffer("csd-0");
        java.nio.ByteBuffer one = format.getByteBuffer("csd-1");
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        for (java.nio.ByteBuffer part : new java.nio.ByteBuffer[] {zero, one}) {
            if (part == null) {
                continue;
            }
            byte[] bytes = new byte[part.remaining()];
            part.get(bytes);
            joined.write(bytes, 0, bytes.length);
        }
        byte[] bytes = joined.toByteArray();
        if (bytes.length > 0 && codecConfig == null) {
            codecConfig = bytes;
            Logs.add("encoder format reports " + bytes.length
                    + " bytes of codec configuration: " + Logs.hex(bytes,
                            Math.min(bytes.length, 32)));
        }
    }

    /** Pulls encoded access units out of the codec until stopped. */
    private void drain(Sink sink) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long sent = 0;
        try {
            while (running) {
                int out = codec.dequeueOutputBuffer(info, 20000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    captureConfig(codec.getOutputFormat());
                    continue;
                }
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (out < 0) {
                    continue;
                }
                ByteBuffer encoded = codec.getOutputBuffer(out);
                if (encoded != null && info.size > 0) {
                    byte[] unit = new byte[info.size];
                    encoded.position(info.offset);
                    encoded.limit(info.offset + info.size);
                    encoded.get(unit);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (config && codecConfig == null) {
                        // some encoders hand the parameter sets over as a buffer
                        codecConfig = unit.clone();
                        Logs.add("encoder reported " + unit.length
                                + " bytes of codec configuration");
                    }
                    boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            || config;
                    frames++;
                    sink.onAccessUnit(unit, info.presentationTimeUs, keyframe);
                    sent++;
                    if (sent == 1 || sent % 300 == 0) {
                        Logs.add("surface encoder produced " + sent + " access units (last "
                                + unit.length + " bytes, keyframe=" + keyframe + ")");
                    }
                }
                codec.releaseOutputBuffer(out, false);
            }
        } catch (Throwable error) {
            if (running) {
                Logs.add("encoder drain ended: " + error);
            }
        }
    }
}
