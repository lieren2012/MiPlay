package org.carplaytocarlife.iccoa;

/**
 * A small PCM tone generator, used as the audio source until a real capture
 * path replaces it. Produces 16-bit big-endian frames, which is what the
 * AirPlay audio packetiser expects.
 */
public final class PcmToneSource {

    public static final int RATE = 44100;
    public static final int CHANNELS = 2;
    public static final int FRAME_SAMPLES = 1024;

    private final int rate;
    private final int channels;
    private final int frameSamples;
    private final double frequencyHz;
    private long sampleIndex;
    private double phase;

    public PcmToneSource(double frequencyHz) {
        this(frequencyHz, RATE, CHANNELS, FRAME_SAMPLES);
    }

    /** The negotiated shape: the car decides the rate and the channel count. */
    public PcmToneSource(double frequencyHz, int rate, int channels, int frameSamples) {
        this.frequencyHz = frequencyHz;
        this.rate = rate;
        this.channels = channels;
        this.frameSamples = frameSamples;
    }

    public int rate() {
        return rate;
    }

    public int channels() {
        return channels;
    }

    public int frameSamples() {
        return frameSamples;
    }

    /** One frame of interleaved 16-bit big-endian samples. */
    public byte[] nextFrame() {
        if (isSilent()) {
            return new byte[frameSamples * channels * 2];
        }
        byte[] frame = new byte[frameSamples * channels * 2];
        double step = 2 * Math.PI * frequencyHz / rate;
        int offset = 0;
        for (int i = 0; i < frameSamples; i++) {
            short value = (short) (Math.sin(phase) * 8000);
            for (int channel = 0; channel < channels; channel++) {
                frame[offset++] = (byte) (value >> 8);
                frame[offset++] = (byte) value;
            }
            phase += step;
            if (phase > 2 * Math.PI) {
                phase -= 2 * Math.PI;
            }
        }
        sampleIndex += frameSamples;
        return frame;
    }

    private boolean silent = true;

    public void setSilent(boolean value) {
        silent = value;
    }

    public boolean isSilent() {
        return silent;
    }

    public long frames() {
        return sampleIndex / frameSamples;
    }

    /** The RTP timestamp a frame starts at, in the audio clock. */
    public long timestamp() {
        return sampleIndex;
    }
}
