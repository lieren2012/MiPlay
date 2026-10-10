package org.carplaytocarlife.iccoa;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The AirPlay video data tunnel: a TCP connection the sender opens to the
 * receiver's video port, carrying 128-byte-header frames whose bodies are
 * encrypted with the session's continuous AES-CTR stream. The key comes from
 * the handshake's base key and the IV from the base IV, exactly as the car
 * derives them.
 */
public final class AirplayVideoTunnel {

    /**
     * How long the stream may go without a frame before the car is treated as
     * stuck. The reference uses the same twenty seconds and ends the session.
     */
    public static final long STALL_MILLIS = 20_000;

    private final Socket socket;
    private final OutputStream output;
    private final AirplayStream stream;
    private long frames;
    private volatile long lastSentNanos = System.nanoTime();
    private volatile Thread watchdog;
    private volatile boolean closed;
    private long baseUnixMillis = -1;
    private long basePtsUs;

    public AirplayVideoTunnel(String host, int port, byte[] baseKey, byte[] baseIv, int counter)
            throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 5000);
        output = socket.getOutputStream();
        stream = AirplayStream.create(baseKey, baseIv, counter);
        Logs.add("video tunnel connected to " + host + ":" + port
                + " (stream counter " + counter + ")");
        startWatchdog();
    }

    public long frames() {
        return frames;
    }

    /** True when nothing has gone out for longer than the stall limit. */
    public static boolean stalled(long nowNanos, long lastSentNanos, long thresholdNanos) {
        return nowNanos - lastSentNanos >= thresholdNanos;
    }

    /** How long the stream has been quiet, in milliseconds. */
    public long quietMillis() {
        return (System.nanoTime() - lastSentNanos) / 1_000_000L;
    }

    /**
     * Watches for a car that stopped reading.
     *
     * Without this a write to a receiver that has stopped reading blocks for
     * ever: no log, no recovery, and the session looks alive while nothing moves.
     * Closing the socket is what makes the blocked write fail, so the sender
     * notices and the session can be rebuilt.
     */
    private void startWatchdog() {
        watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!closed) {
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException stopped) {
                        return;
                    }
                    if (stalled(System.nanoTime(), lastSentNanos,
                            STALL_MILLIS * 1_000_000L)) {
                        Logs.add("video send stalled for " + (STALL_MILLIS / 1000)
                                + "s; closing the tunnel so the sender unwinds");
                        close();
                        return;
                    }
                }
            }
        }, "video-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * Sends the stream's opening frame: the decoder configuration and the display
     * size, unencrypted. A receiver that never sees this cannot set up its
     * decoder, so it goes out before the first picture.
     *
     * {@code codecConfig} is an AVCDecoderConfigurationRecord, which is what the
     * car reads the payload as.
     */
    public void sendConfig(byte[] codecConfig, int width, int height) throws IOException {
        byte[] frame = AirplayStream.configFrame(codecConfig, width, height);
        output.write(frame);
        output.flush();
        lastSentNanos = System.nanoTime();
        Logs.add("video tunnel sent the config frame (" + codecConfig.length
                + " bytes of parameter sets, " + width + "x" + height + ")");
    }

    /**
     * Sends one access unit.
     *
     * The presentation time is the codec's own, anchored to the wall clock on the
     * first frame and written as NTP, which is what the receiver's timeline wants.
     */
    public void send(byte[] annexB, long timestampUs) throws IOException {
        byte[] frame = stream.videoFrame(annexB, ntp(timestampUs));
        output.write(frame);
        output.flush();
        lastSentNanos = System.nanoTime();
        frames++;
        if (frames == 1 || frames % 120 == 0) {
            Logs.add("video tunnel sent " + frames + " frames (last "
                    + annexB.length + " bytes, encrypted to " + frame.length + ")");
        }
    }

    /** Unix milliseconds for a codec presentation time, anchored on first use. */
    private long ntp(long timestampUs) {
        if (baseUnixMillis < 0) {
            baseUnixMillis = System.currentTimeMillis();
            basePtsUs = timestampUs;
        }
        long unixMillis = baseUnixMillis + (timestampUs - basePtsUs) / 1000;
        return AirplayStream.ntpTimestamp(unixMillis);
    }

    public boolean isConnected() {
        return socket.isConnected() && !socket.isClosed();
    }

    public void close() {
        closed = true;
        Thread running = watchdog;
        watchdog = null;
        if (running != null) {
            running.interrupt();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
