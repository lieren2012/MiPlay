package org.carplaytocarlife.iccoa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The iAP2 link layer over a byte stream: the frame header the reference app
 * calls LinkFrame, plus the identification exchange that opens the session.
 *
 * The wire format is already in {@link Iap2}; this class adds the state a
 * session needs - the sequence counter, the negotiated control session id, and
 * the fragmentation of a control message across link frames when it is larger
 * than the peer's advertised receive packet.
 */
public final class Iap2Link implements Runnable {

    /** The identification payload this phone advertises (control session 1). */
    public static final byte[] SYNC_PAYLOAD = {
        1, 0x7F, 0x08, 0x00, 0x07, (byte) 0xD0, 0x01, 0x00, 0x1E, 0x01, 0x01, 0x00, 0x02
    };

    /**
     * The responder variant, used when the accessory opens the link itself and
     * we only answer; it advertises control session 10.
     */
    public static final byte[] SYNC_PAYLOAD_RESPONDER = {
        1, 0x05, 0x08, 0x00, 0x05, (byte) 0xDC, 0x00, 0x49, 0x1E, 0x03, 0x0A, 0x00, 0x02
    };

    /** Default peer receive packet when the peer never says; matches the payloads. */
    public static final int DEFAULT_MAX_RECEIVE = 0x0800;

    public interface Listener {
        void onLog(String message);

        void onFrame(Iap2.Frame frame);

        void onControl(Iap2.Message message);

        void onSession(int session);
    }

    private final InputStream input;
    private final OutputStream output;
    private final Listener listener;
    private final Iap2Reader reader = new Iap2Reader();

    private volatile boolean running;
    private Thread thread;
    private int sequence = 1;
    private volatile int session;
    private int maxReceive = DEFAULT_MAX_RECEIVE;
    private int peerReceiveWindow;
    private byte[] ourPayload;
    private boolean sessionAnnounced;
    private long framesReceived;
    private long controlsSent;
    private boolean peerOpened;
    /** The sequence of the last frame the car sent: every frame we send acks it. */
    private volatile int lastReceivedSequence;

    public Iap2Link(InputStream input, OutputStream output, Listener listener) {
        this.input = input;
        this.output = output;
        this.listener = listener;
    }

    public int session() {
        return session;
    }

    public int sequence() {
        return sequence;
    }

    public int maxReceive() {
        return maxReceive;
    }

    public long framesReceived() {
        return framesReceived;
    }

    public long controlsSent() {
        return controlsSent;
    }

    /** The sequence of the last frame received; every frame we send acks it. */
    public int lastReceivedSequence() {
        return lastReceivedSequence;
    }

    /** True once the peer's identification payload set the session. */
    public boolean sessionOpen() {
        return session > 0;
    }

    /** True when the peer spoke first, which decides who sends 0x1D00. */
    public boolean peerOpened() {
        return peerOpened;
    }

    /** Starts the reader; nothing is written until {@link #openLink} or a reply. */
    public void start() {
        running = true;
        thread = new Thread(this, "iap2-link");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    /** Sends the identification frame that opens the link from our side. */
    public synchronized void openLink(byte[] syncPayload) throws IOException {
        ourPayload = syncPayload;
        if (syncPayload != null && session == 0) {
            try {
                session = Iap2.controlSession(syncPayload);
                announceSession();
            } catch (RuntimeException ignored) {
                // an unusable payload still opens the link; the car may set the session
            }
        }
        byte[] frame = Iap2.frame(Iap2.SYN, sequence, 0, 0, syncPayload);
        sequence = (sequence + 1) & 0xFF;
        write(frame, "syn");
    }

    /**
     * Opens the link only if the car stays silent for a moment.
     *
     * A CarPlay accessory either waits for the phone or takes the lead itself;
     * this covers both without guessing, and it mirrors the reference app's
     * choice between an initiating and a responder round.
     */
    public void openWhenPeerSilent(final byte[] syncPayload, final long delayMillis) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                long deadline = System.currentTimeMillis() + delayMillis;
                try {
                    while (running && !peerOpened && System.currentTimeMillis() < deadline) {
                        Thread.sleep(50);
                    }
                    if (!running || peerOpened) {
                        listener.onLog("iAP2 the car opened the link; answering instead of "
                                + "initiating");
                        return;
                    }
                    openLink(syncPayload);
                } catch (InterruptedException ignored) {
                } catch (Exception error) {
                    listener.onLog("iAP2 cannot open the link: " + error);
                }
            }
        }, "iap2-open");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * A bare acknowledgement of the last frame received.
     *
     * The reference sends one after it has taken a frame in, with the session
     * field left at zero and the ack field naming the sequence it is answering.
     */
    private synchronized void acknowledge() throws IOException {
        byte[] frame = Iap2.frame(Iap2.ACK, sequence, lastReceivedSequence, 0, null);
        sequence = (sequence + 1) & 0xFF;
        write(frame, "ack " + lastReceivedSequence);
    }

    /** Acknowledges the peer's identification frame, the way LinkFrame(192) does. */
    private synchronized void acceptPeerSession(byte[] syncPayload) throws IOException {
        byte[] frame = Iap2.frame(Iap2.SYN | Iap2.ACK, sequence, 0, 0, syncPayload);
        sequence = (sequence + 1) & 0xFF;
        write(frame, "syn+ack");
    }

    /** Queues one control message, fragmenting it to the peer's receive packet. */
    public synchronized void sendControl(int messageId, List<Iap2.Parameter> parameters)
            throws IOException {
        byte[] payload = Iap2.control(messageId, parameters);
        int capacity = Math.max(64, maxReceive - Iap2.LINK_HEADER_LENGTH);
        controlsSent++;
        for (int offset = 0; offset < payload.length; offset += capacity) {
            int length = Math.min(capacity, payload.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(payload, offset, chunk, 0, length);
            // a data frame carries the ack flag and, in its ack field, the last
            // sequence the car sent us - the reference fills that field the same way
            byte[] frame = Iap2.frame(Iap2.ACK, sequence, lastReceivedSequence, session, chunk);
            sequence = (sequence + 1) & 0xFF;
            write(frame, String.format("csm 0x%04X %d/%d", messageId,
                    offset / capacity + 1, (payload.length + capacity - 1) / capacity));
        }
        listener.onLog(String.format("iAP2 -> 0x%04X %d params (%d bytes)",
                messageId, parameters.size(), payload.length));
    }

    private void write(byte[] frame, String label) throws IOException {
        output.write(frame);
        output.flush();
        listener.onLog("iAP2 TX " + label + " " + Logs.hex(frame, frame.length));
    }

    @Override
    public void run() {
        byte[] buffer = new byte[4096];
        try {
            while (running) {
                int count = input.read(buffer);
                if (count < 0) {
                    listener.onLog("iAP2 link closed by the car after "
                            + framesReceived + " frames");
                    return;
                }
                if (count == 0) {
                    continue;
                }
                List<Iap2.Frame> frames;
                try {
                    frames = reader.feed(buffer, count);
                } catch (RuntimeException error) {
                    listener.onLog("iAP2 the car sent a frame we cannot parse: " + error);
                    continue;
                }
                for (Iap2.Frame frame : frames) {
                    framesReceived++;
                    lastReceivedSequence = frame.sequence;
                    listener.onFrame(frame);
                    listener.onLog("iAP2 RX " + frame.describe()
                            + " payload=" + Logs.hex(frame.payload, frame.payload.length));
                    if (frame.has(Iap2.SYN)) {
                        onPeerSync(frame);
                    }
                    if (frame.payload.length == 0) {
                        continue;
                    }
                    deliver(frame);
                    // tell the car we took its frame in, the way the reference does
                    acknowledge();
                }
            }
        } catch (Exception error) {
            if (running) {
                listener.onLog("iAP2 link ended: " + error);
            }
        }
    }

    /** Tells the listener about the session, once. */
    private void announceSession() {
        if (!sessionAnnounced && session > 0) {
            sessionAnnounced = true;
            listener.onSession(session);
        }
    }

    private void onPeerSync(Iap2.Frame frame) {
        byte[] payload = frame.payload;
        peerOpened = true;
        if (payload.length < 13) {
            listener.onLog("iAP2 the car's identification payload is too short to describe a "
                    + "session; answering with ours anyway");
            peerOpened = true;
            try {
                acceptPeerSession(ourPayload == null ? SYNC_PAYLOAD_RESPONDER : ourPayload);
            } catch (Exception error) {
                listener.onLog("iAP2 cannot answer the car's identification: " + error);
            }
            return;
        }
        peerReceiveWindow = payload[1] & 0xFF;
        maxReceive = Math.min(4096, Iap2.readWord(payload, 2));
        if (maxReceive <= 0) {
            maxReceive = DEFAULT_MAX_RECEIVE;
        }
        listener.onLog("iAP2 the car's link description: version=" + (payload[0] & 0xFF)
                + " receive-window=" + peerReceiveWindow
                + " max-receive-packet=" + maxReceive
                + " retransmission-ms=" + Iap2.readWord(payload, 4)
                + " ack-timeout-ms=" + Iap2.readWord(payload, 6)
                + " max-retries=" + (payload[8] & 0xFF)
                + " max-cumulative-acks=" + (payload[9] & 0xFF));
        int negotiated = 0;
        try {
            negotiated = Iap2.controlSession(payload);
        } catch (RuntimeException error) {
            listener.onLog("iAP2 cannot read a control session from the car's payload: " + error);
        }
        if (negotiated > 0) {
            if (session == 0) {
                session = negotiated;
                listener.onLog("iAP2 control session offered by the car: " + session);
                announceSession();
            } else if (session != negotiated) {
                listener.onLog("iAP2 the car offers session " + negotiated
                        + "; keeping ours (" + session + ")");
            }
        }
        if (!frame.has(Iap2.ACK)) {
            // echo the car's own payload when it agrees with ours, so both ends
            // keep the same session id; otherwise hand it ours to adopt
            byte[] answer = negotiated > 0 && negotiated == session
                    ? payload
                    : (ourPayload == null ? SYNC_PAYLOAD_RESPONDER : ourPayload);
            try {
                acceptPeerSession(answer);
            } catch (Exception error) {
                listener.onLog("iAP2 cannot answer the car's identification: " + error);
            }
        }
    }

    /** Decodes a payload as a control message and hands it to the listener. */
    private void deliver(Iap2.Frame frame) {
        try {
            Iap2.Message message = Iap2.decodeControl(frame.payload);
            listener.onControl(message);
        } catch (RuntimeException error) {
            listener.onLog("iAP2 payload is not a control message: " + error);
        }
    }

    /** Takes the control session from the peer payload, for callers that peek. */
    public static int sessionOf(byte[] syncPayload) {
        return Iap2.controlSession(syncPayload);
    }

    /** The capability list a peer advertises, as integers. */
    public static List<Integer> capabilityIds(byte[] value) {
        List<Integer> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (int offset = 0; offset + 1 < value.length; offset += 2) {
            out.add(((value[offset] & 0xFF) << 8) | (value[offset + 1] & 0xFF));
        }
        return out;
    }
}
