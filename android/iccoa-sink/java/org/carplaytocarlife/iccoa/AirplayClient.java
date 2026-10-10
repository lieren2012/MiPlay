package org.carplaytocarlife.iccoa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The AirPlay sender's RTSP client: it connects to the car's AirPlay server and
 * walks the handshake.
 *
 * Pairing (SRP + Curve25519, as the reference app does with BouncyCastle) is not
 * plugged in yet - the steps are laid out in order and a car's rejection is
 * logged with the reason, so the missing piece is visible rather than silent.
 */
public final class AirplayClient implements Runnable {

    public interface Listener {
        void onLog(String message);

        void onState(String state);

        void onSessionReady(String sessionId);

        void onTouch(HidTouch.Touch touch);

        /**
         * Raw iAP2 bytes the car sent down the AirPlay tunnel.
         *
         * After Wi-Fi is up the iAP2 conversation moves here: the car wraps it
         * in POST /command rather than keeping the Bluetooth link busy.
         */
        void onIapTunnel(byte[] bytes);

        /**
         * The car asked the encoder for a key frame.
         *
         * @return true when an encoder took the request; the car is answered
         *         accordingly, the way the reference answers with the result of
         *         its own request
         */
        boolean onKeyFrameRequested();
    }

    private final String host;
    private final int port;
    private final AirplayMessages.Identity identity;
    private final Listener listener;
    private volatile boolean running;
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private int cseq = 10;
    private String sessionId;

    public AirplayClient(String host, int port, AirplayMessages.Identity identity, Listener listener) {
        this.host = host;
        this.port = port;
        this.identity = identity;
        this.listener = listener;
    }

    public void start() {
        running = true;
        Thread thread = new Thread(this, "airplay-client");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        closeQuietly(socket);
    }

    public String sessionId() {
        return sessionId;
    }

    @Override
    public void run() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
            input = socket.getInputStream();
            output = socket.getOutputStream();
            log("AirPlay connected to " + host + ":" + port);
            listener.onState("connected");

            // The MFi key exchange comes first: it is what a CarPlay accessory
            // uses, and it is the only path that hands the car the screen key.
            // A receiver without /auth-setup answers 404 and the HomeKit pairing
            // flow below takes over instead.
            AirplayAuth auth = null;
            try {
                auth = authSetup();
            } catch (Exception error) {
                log("auth-setup unavailable (" + error + "); falling back to pairing");
            }
            if (!running) {
                return;
            }
            if (auth == null) {
                if (pairWithCar()) {
                    log("pairing finished");
                } else {
                    log("pairing did not complete; the car will probably refuse the session");
                }
                if (!running) {
                    return;
                }
            }

            int timingPort = 0;
            try {
                timing = new AirplayTiming(new java.net.DatagramSocket(),
                        new AirplayTiming.Listener() {
                            @Override
                            public void onLog(String message) {
                                log(message);
                            }
                        });
                timing.start();
                timingPort = timing.port();
            } catch (Exception error) {
                log("cannot start the timing responder: " + error);
            }

            // 1. the session itself
            sessionUuid = AirplayMessages.sessionUuid();
            Map<String, Bplist.Node> sessionReply = exchange(AirplayMessages.setupSession(
                    identity, nextCseq(), sessionUuid, timingPort,
                    auth == null ? null : auth.setupFields()));
            if (sessionReply == null) {
                log("no answer to SETUP /setup; stopping");
                return;
            }
            String session = lastSession;
            if (session == null) {
                log("SETUP /setup answered without a Session header; stopping");
                return;
            }
            sessionId = session;
            log("session established: " + sessionId + " (uuid " + sessionUuid + ")");
            notePeerTimingPort(sessionReply);
            noteAudioMask(sessionReply);

            // 2. the display capabilities; the car only serves this inside a session
            Map<String, Bplist.Node> info = exchange(AirplayMessages.info(identity, nextCseq()));
            readDisplayCapabilities(info);
            notePeerTimingPort(info);
            noteAudioMask(info);
            if (info != null && !info.isEmpty()) {
                log("GET /info fields=" + info.keySet()
                        + " width=" + (info.get("width") == null ? "?" : info.get("width"))
                        + " height=" + (info.get("height") == null ? "?" : info.get("height"))
                        + " streams=" + (info.get("streams") == null
                                ? "?" : info.get("streams").asArray().size()));
            }

            // 3. the event channel, on its own connection
            startEventChannel();

            // 4. the modes this side will use
            try {
                Map<String, Bplist.Node> modes = exchange(AirplayMessages.command(
                        identity, nextCseq(), modesChanged()));
                log("modesChanged accepted; reply fields="
                        + (modes == null ? "{}" : modes.keySet().toString()));
            } catch (Exception error) {
                log("the car refused modesChanged: " + error);
            }

            // 5. the screen stream
            connectionId = 1;
            Map<String, Bplist.Node> streams = exchange(AirplayMessages.setupStreams(identity,
                    nextCseq(), java.util.Collections.singletonList(AirplayMessages.screenStream(
                            connectionId, AirplayMessages.SCREEN_LATENCY_MS))));
            if (streams == null) {
                log("no answer to the stream SETUP; stopping");
                return;
            }
            videoPort = AirplayMessages.portOf(streams, AirplayMessages.SCREEN_STREAM_TYPE,
                    AirplayMessages.VIDEO_PORT);
            audioPort = AirplayMessages.portOf(streams, 96, 0);
            this.auth = auth;
            log("streams agreed: screen type 110, connection " + connectionId + ", port "
                    + videoPort);

            // 6. the audio stream: the car picks one of the PCM offers
            negotiateAudio();

            // 6. start it
            exchange(AirplayMessages.recordSession(identity, nextCseq(), sessionId));
            log("RECORD accepted; the media path can start");
            // from here the car may hand the iAP2 conversation over to this
            // connection; Bluetooth stays up as the fallback
            tunnelArmed = true;
            log("iAP tunnel armed; waiting for the car's first iAPSendMessage; "
                    + "Bluetooth retained");
            listener.onState("recording");
            listener.onSessionReady(sessionId);

            // the session is kept alive from here on; a receiver may treat a
            // session that stops feeding back as gone
            startFeedback();

            // A real session keeps the RTSP connection open: the car can send
            // events on it and the control channel has to stay listening while
            // video flows. Tearing the socket down here would end the session.
            keepSessionOpen();
        } catch (Exception error) {
            if (running) {
                log("AirPlay session ended: " + error);
                listener.onState("failed: " + error);
            }
        } finally {
            if (event != null) {
                try {
                    event.socket.close();
                } catch (IOException ignored) {
                    // shutting down
                }
                event = null;
            }
            if (timing != null) {
                timing.stop();
                timing = null;
            }
            closeQuietly(socket);
        }
    }

    /**
     * Reads the screen the car says it has.
     *
     * The reference reads {@code displays[...]}: the entry whose type is 0 is
     * the one this phone drives, and its {@code widthPixels},
     * {@code heightPixels} and {@code maxFPS} are what the video is sized and
     * capped by (a missing maxFPS means 15). A receiver that answers with flat
     * width/height fields instead is still understood, and the log says which
     * of the two shapes was used - that line is how a real car tells us.
     */
    private void readDisplayCapabilities(Map<String, Bplist.Node> info) {
        if (info == null) {
            return;
        }
        AirplayMessages.Display display = AirplayMessages.firstDisplay(info);
        if (display != null) {
            infoWidth = display.width;
            infoHeight = display.height;
            infoFps = display.maxFps;
            log("the car's display " + display.width + "x" + display.height
                    + " maxFPS=" + display.maxFps + " (displays[" + display.index
                    + "].type=" + display.type + ")");
            return;
        }
        infoWidth = integer(info, "width");
        infoHeight = integer(info, "height");
        for (String key : new String[] {"maxFPS", "maxFps", "max-fps", "fps", "refreshRate"}) {
            int value = integer(info, key);
            if (value > 0) {
                infoFps = value;
                break;
            }
        }
        if (infoWidth > 0 && infoHeight > 0) {
            log("the car's display " + infoWidth + "x" + infoHeight + " maxFPS="
                    + infoFps + " (flat /info fields, no displays list)");
        } else {
            log("the car's /info names no display size; the phone screen and its scale "
                    + "decide the encode size, as before");
        }
    }

    /**
     * Notes the PCM formats the car says it can decode.
     *
     * The mask lives on the car's own description of its streams, so it is read
     * from every reply that may carry one (the session SETUP and /info). The
     * reference offers only the first candidate the mask allows; this side offers
     * that one first and the rest after it, so a stale mask cannot leave the
     * session mute.
     */
    private void noteAudioMask(Map<String, Bplist.Node> reply) {
        long mask = AirplayMessages.audioOutputMask(reply);
        if (mask == 0 || mask == carAudioMask) {
            return;
        }
        carAudioMask = mask;
        AirplayMessages.PcmFormat announced = AirplayMessages.formatFromMask(mask);
        log("the car announced PCM formats mask=0x" + Long.toHexString(mask) + " (it can take "
                + (announced == null ? "none of the four this side knows"
                        : announced.describe()) + ")");
    }

    /** The feedback keep-alive interval; the reference uses two seconds. */
    public static final long FEEDBACK_INTERVAL_MILLIS = 2000;
    /** One log line every this many keep-alives, so the log stays readable. */
    public static final long FEEDBACK_LOG_EVERY = 15;

    /** How long a request waits for the reader thread to hand its reply over. */
    public static final long REPLY_TIMEOUT_MILLIS = 5000;

    /** Idles until the car hangs up or the session is stopped. */
    private void keepSessionOpen() {
        dispatching = true;
        while (running) {
            String message;
            try {
                message = readMessage();
            } catch (Exception error) {
                if (running) {
                    log("session read ended: " + error);
                }
                return;
            }
            if (message == null) {
                log("the car closed the session");
                return;
            }
            if (message.startsWith("RTSP/1.")) {
                handOver(message);
            } else {
                // the car may also ask us things on the session socket
                log("session socket carried a request: "
                        + message.replace("\r\n", " | ").trim());
                answerOnSession(message);
            }
        }
    }

    /** Files a reply so the thread that asked for it can pick it up. */
    private void handOver(String message) {
        try {
            Rtsp.Response response = Rtsp.Response.parse(message);
            String cseqHeader = response.header("CSeq");
            adoptSession(response);
            int cseq = cseqHeader == null ? -1 : Integer.parseInt(cseqHeader.trim());
            if (cseq >= 0) {
                replies.put(cseq, response);
            } else {
                log("session socket carried " + message.length() + " bytes without a CSeq");
            }
        } catch (RuntimeException notAResponse) {
            log("session socket carried something unparsable: "
                    + message.replace("\r\n", " | ").trim().substring(0,
                            Math.min(120, message.trim().length())));
        }
    }

    /** Answers a request the car put on the session socket. */
    private void answerOnSession(String message) {
        String cseq = header(message, "CSeq");
        String type = eventType(message);
        int status = handleEvent(type, message);
        try {
            synchronized (this) {
                output.write(replyFor(cseq, status).getBytes(StandardCharsets.ISO_8859_1));
                output.flush();
            }
            log("session request answered with " + status);
        } catch (Exception error) {
            log("cannot answer the car's session request: " + error);
        }
    }

    private String lastSession;
    private int videoPort = 7100;
    private int audioPort;
    private byte[] streamBaseKey;
    private AirplayTiming timing;
    private AirplayAuth auth;
    private EventChannel event;
    private String sessionUuid;
    private int connectionId = 1;
    private int infoWidth;
    private int infoHeight;
    private int infoFps;
    private int peerTimingPort;
    private int audioConnectionId;
    /** The PCM formats the car announced, as a bit mask; 0 until it says. */
    private long carAudioMask;
    private AirplayMessages.PcmFormat audioFormat;
    /** The display uuid the car may scope its HID devices with. */
    private String displayUUID;
    private volatile boolean tunnelArmed;
    /** Replies the reader thread has taken off the session socket, by CSeq. */
    private final java.util.Map<Integer, Rtsp.Response> replies =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean dispatching;
    private long tunnelBytesIn;
    /** Which side owns the screen and the audio, as the car is told. */
    private final AirplayModes modes = new AirplayModes();
    /** The body the next reply should carry, when a command produces one. */
    private volatile Map<String, Bplist.Node> replyBody;
    /** The car's HID devices, by uuid: its own descriptor is what decodes them. */
    private final java.util.Map<String, HidDescriptor> hidDevices =
            new java.util.concurrent.ConcurrentHashMap<>();
    private long tunnelBytesOut;

    /** The AirPlay timing responder, started with the session. */
    public AirplayTiming timing() {
        return timing;
    }

    /** The 16-byte key the video/audio stream ciphers derive from. */
    public byte[] streamBaseKey() {
        if (streamBaseKey == null) {
            byte[] material = AirplayCrypto.hkdfSha512(
                    pairing == null ? new byte[32] : pairing.sessionKey(),
                    "AirPlayStreamKeyBase", "AirPlayStreamKeyBase", 16);
            streamBaseKey = material;
        }
        return streamBaseKey;
    }
    public int videoPort() {
        return videoPort;
    }

    /** The audio port the car allocated, or 0 when it opened no audio stream. */
    public int audioPort() {
        return audioPort;
    }

    /** The MFi key material, once auth-setup succeeded; null otherwise. */
    public AirplayAuth auth() {
        return auth;
    }

    /** The stream connection id the screen SETUP announced. */
    public int connectionId() {
        return connectionId;
    }

    public String sessionUuid() {
        return sessionUuid;
    }

    /** The display width the car reported in /info, or 0 when it said nothing. */
    public int infoWidth() {
        return infoWidth;
    }

    /** The display height the car reported in /info, or 0 when it said nothing. */
    public int infoHeight() {
        return infoHeight;
    }

    /**
     * The frame rate ceiling the car announced, or 0 when it announced none.
     *
     * The field name is not confirmed against a car: these are the plausible
     * ones, the whole /info dictionary is logged either way, and a car that
     * names it differently shows up in that log.
     */
    public int infoFps() {
        return infoFps;
    }

    /**
     * The timing port the car announced, or 0 when it announced none.
     *
     * The reference checks for this field and logs
     * "peer timing-port-present=<bool>"; nothing is sent to it, because the
     * reference only ever answers timing requests rather than making them.
     */
    public int peerTimingPort() {
        return peerTimingPort;
    }

    /** The audio format the car accepted, or null when it accepted none. */
    public AirplayMessages.PcmFormat audioFormat() {
        return audioFormat;
    }

    /** The connection id the audio stream was negotiated with. */
    public int audioConnectionId() {
        return audioConnectionId;
    }

    /** True once the session is up and the car may use the iAP tunnel. */
    public boolean tunnelArmed() {
        return tunnelArmed;
    }

    public long tunnelBytesIn() {
        return tunnelBytesIn;
    }

    public long tunnelBytesOut() {
        return tunnelBytesOut;
    }

    /**
     * Sends raw iAP2 bytes to the car over the AirPlay connection.
     *
     * The envelope is the reference's: a POST to /command whose plist body is
     * {@code {type: "iAPSendMessage", params: {data: <bytes>}}}. Returns false
     * when the session is gone, so the caller can fall back to Bluetooth.
     */
    public boolean tunnelIap(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return true;
        }
        Map<String, Bplist.Node> params = new LinkedHashMap<>();
        params.put("data", Bplist.Node.data(bytes));
        Map<String, Bplist.Node> body = new LinkedHashMap<>();
        body.put("type", Bplist.Node.string("iAPSendMessage"));
        body.put("params", Bplist.Node.dict(params));
        try {
            Map<String, Bplist.Node> reply = exchange(AirplayMessages.command(
                    identity, nextCseq(), body));
            tunnelBytesOut += bytes.length;
            log("iAP tunnel -> " + bytes.length + " bytes; reply fields="
                    + (reply == null ? "{}" : reply.keySet().toString()));
            return true;
        } catch (Exception error) {
            log("iAP tunnel send failed (" + error + "); Bluetooth stays the fallback");
            return false;
        }
    }

    /** The ChaCha key for the audio stream, or null when there is no key. */
    public byte[] audioKey() {
        return auth == null || audioFormat == null
                ? null : auth.streamOutputKey(audioConnectionId);
    }
    private AirplayPairing pairing;
    /**
     * The pair-verify channel key, once the car has proved itself.
     *
     * The reference encrypts the session connection with it from here on
     * ({@code Control-Salt} / {@code Control-Read-Encryption-Key} /
     * {@code Control-Write-Encryption-Key}); this side keeps the value so the
     * next step has it, and the log says plainly that the channel is not
     * encrypted yet.
     */
    private byte[] verifyKeyEstablished;
    private byte[] accessoryEd25519;
    private byte[] accessoryPairingId;
    /**
     * Runs pair-setup and pair-verify against the car.
     *
     * Both endpoints speak TLV8, not plists, and the reference is the only known
     * working caller: item 6 is the state, the step numbers go 1, 3, 5 for
     * pair-setup and 1, 3 for pair-verify, the car has to answer with the next
     * state, and each blob is sealed with its own label. A body in any other shape
     * is a pairing failure on a real car, so every step is logged with the items
     * it carried and the ones that came back.
     */
    private boolean pairWithCar() {
        try {
            pairing = new AirplayPairing(AirplayCrypto.SRP_PASSWORD);

            // pair-setup step 1: state 1, method 1 (PairSetup)
            Map<Integer, byte[]> step1Items = new LinkedHashMap<>();
            step1Items.put(Tlv8.METHOD, Tlv8.byteValue(1));
            Map<Integer, byte[]> step1 = pairingStep("/pair-setup", 1, step1Items);
            if (step1 == null) {
                return false;
            }
            byte[] salt = Tlv8.value(step1, Tlv8.SALT, 0);
            byte[] publicB = Tlv8.value(step1, Tlv8.PUBLIC_KEY, 0);
            pairing.acceptAccessoryParameters(salt, publicB);
            log("SRP: got salt=" + Logs.hex(salt, salt.length)
                    + " B=" + Logs.hex(publicB, publicB.length) + " (truncated)");

            // pair-setup step 3: our public key plus the proof
            Map<Integer, byte[]> step3Items = new LinkedHashMap<>();
            step3Items.put(Tlv8.PUBLIC_KEY, pairing.srpPublicKey());
            step3Items.put(Tlv8.PROOF, pairing.clientProof());
            Map<Integer, byte[]> step3 = pairingStep("/pair-setup", 3, step3Items);
            if (step3 == null) {
                return false;
            }
            byte[] serverProof = Tlv8.value(step3, Tlv8.PROOF, 0);
            if (!pairing.verifyServerProof(serverProof)) {
                log("car's SRP proof does not match; wrong PIN or a different group");
                return false;
            }
            log("SRP proofs verified on both sides");

            // pair-setup step 5: the long term keys travel sealed, as a TLV inside
            // the sealed blob, under the label PS-Msg05
            Map<Integer, byte[]> ourKeys = new LinkedHashMap<>();
            ourKeys.put(Tlv8.IDENTIFIER, pairing.ownPairingId());
            ourKeys.put(Tlv8.PUBLIC_KEY, pairing.ownEd25519PublicKey());
            ourKeys.put(Tlv8.SIGNATURE, pairing.signOwnKey(AirplayCrypto.SALT_CONTROLLER_SIGN,
                    AirplayCrypto.INFO_CONTROLLER_SIGN));
            Map<Integer, byte[]> step5Items = new LinkedHashMap<>();
            step5Items.put(Tlv8.ENCRYPTED_DATA,
                    pairing.encryptSetup(Tlv8.encode(ourKeys)));
            Map<Integer, byte[]> step5 = pairingStep("/pair-setup", 5, step5Items);
            if (step5 == null) {
                return false;
            }
            Map<Integer, byte[]> accessoryKeys = Tlv8.decode(pairing.decryptSetup(
                    Tlv8.value(step5, Tlv8.ENCRYPTED_DATA, 0)));
            accessoryPairingId = Tlv8.value(accessoryKeys, Tlv8.IDENTIFIER, 0);
            accessoryEd25519 = Tlv8.value(accessoryKeys, Tlv8.PUBLIC_KEY, 32);
            if (!AirplayPairing.verifyAccessoryKey(pairing.sessionKey(),
                    AirplayCrypto.SALT_ACCESSORY_SIGN, AirplayCrypto.INFO_ACCESSORY_SIGN,
                    accessoryEd25519, accessoryPairingId,
                    Tlv8.value(accessoryKeys, Tlv8.SIGNATURE, 0))) {
                log("car's long term key signature does not verify");
                return false;
            }
            log("pair-setup complete: accessory key "
                    + Logs.hex(accessoryEd25519, accessoryEd25519.length));

            // pair-verify step 1: our ephemeral key
            Map<Integer, byte[]> verify1Items = new LinkedHashMap<>();
            verify1Items.put(Tlv8.PUBLIC_KEY, pairing.verifyPublicKey());
            Map<Integer, byte[]> verify1 = pairingStep("/pair-verify", 1, verify1Items);
            if (verify1 == null) {
                return false;
            }
            byte[] carEphemeral = Tlv8.value(verify1, Tlv8.PUBLIC_KEY, 0);
            byte[] verifyKey = pairing.acceptVerifyPublicKey(carEphemeral);
            Map<Integer, byte[]> carProof = Tlv8.decode(
                    pairing.decryptVerify(verifyKey, Tlv8.value(verify1,
                            Tlv8.ENCRYPTED_DATA, 0)));
            byte[] carPairingId = Tlv8.value(carProof, Tlv8.IDENTIFIER, 0);
            if (!AirplayPairing.verifyAccessoryVerify(accessoryEd25519, carEphemeral,
                    carPairingId, pairing.verifyPublicKey(),
                    Tlv8.value(carProof, Tlv8.SIGNATURE, 0))) {
                log("car's pair-verify signature does not verify");
                return false;
            }
            log("pair-verify key established");

            // pair-verify step 3: our own proof, sealed under PV-Msg03
            Map<Integer, byte[]> ourProof = new LinkedHashMap<>();
            ourProof.put(Tlv8.IDENTIFIER, pairing.ownPairingId());
            ourProof.put(Tlv8.SIGNATURE, pairing.verifySignature(carEphemeral));
            Map<Integer, byte[]> verify3Items = new LinkedHashMap<>();
            verify3Items.put(Tlv8.ENCRYPTED_DATA,
                    pairing.encryptVerify(verifyKey, Tlv8.encode(ourProof)));
            if (pairingStep("/pair-verify", 3, verify3Items) == null) {
                return false;
            }
            log("pair-verify complete");
            log("the control channel is NOT encrypted: the reference turns on"
                    + " Control-Salt read/write keys here, this side does not yet"
                    + " (a car that insists would drop the session at the next request)");
            verifyKeyEstablished = verifyKey;
            return true;
        } catch (Exception error) {
            log("pairing failed: " + error);
            return false;
        }
    }

    /**
     * Sends one pairing step and checks the state the car answered with.
     *
     * The reference refuses a reply whose item 6 is not exactly the next state,
     * and reports item 7 (error) as the reason. Both are done here, because a
     * pairing that "succeeds" on a state mismatch leaves the session keyed to
     * nothing.
     */
    private Map<Integer, byte[]> pairingStep(String path, int state,
            Map<Integer, byte[]> items) throws IOException {
        Map<Integer, byte[]> body = new LinkedHashMap<>();
        body.put(Tlv8.STATE, Tlv8.byteValue(state));
        body.putAll(items);
        Rtsp.Response response = send(AirplayMessages.pairing(path, nextCseq(), body));
        if (response == null) {
            log("the car hung up during " + path + " step " + state);
            return null;
        }
        Map<Integer, byte[]> answer;
        try {
            answer = AirplayMessages.tlvBody(response.body);
        } catch (RuntimeException notTlv) {
            log(path + " step " + state + " answered " + response.status
                    + " with a body that is not TLV8: " + notTlv.getMessage());
            return null;
        }
        int error = Tlv8.byteOf(answer, Tlv8.ERROR);
        if (error > 0) {
            log(path + " step " + state + " refused with TLV error " + error);
            return null;
        }
        int answered = Tlv8.byteOf(answer, Tlv8.STATE);
        if (answered != state + 1) {
            log(path + " step " + state + " answered with state " + answered
                    + " instead of " + (state + 1));
            return null;
        }
        log("pairing " + path + " state " + state + "->" + answered + " items="
                + answer.keySet());
        return answer;
    }

    private synchronized int nextCseq() {
        return cseq++;
    }

    private static String challenge() {
        byte[] random = new byte[16];
        new java.security.SecureRandom().nextBytes(random);
        return java.util.Base64.getEncoder().encodeToString(random);
    }

    private void log(String message) {
        listener.onLog(message);
    }

    /** Sends one request and returns the parsed reply; null when the car hung up. */
    /**
     * Sends one request and returns the reply.
     *
     * Once the session is up one reader thread owns the socket (the car can
     * send us things on it), so a request from any other thread waits for that
     * thread to hand the reply over, matched by CSeq. Before that, the caller
     * reads its own reply, which is the simple case.
     */
    private synchronized Rtsp.Response send(Rtsp.Request request) throws IOException {
        // once a session exists every request names it, the way the reference
        // does; the request that creates the session obviously cannot
        if (lastSession != null && request.header("Session") == null) {
            request.headers.add(new String[] {"Session", lastSession});
        }
        output.write(request.serialize().getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
        if (!request.quiet) {
            log("-> " + request.method + " " + request.uri + " (CSeq " + request.cseq + ")");
        }
        if (!dispatching) {
            return response(readMessage(), request);
        }
        long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Rtsp.Response reply = replies.remove(request.cseq);
            if (reply != null) {
                check(reply, request);
                return reply;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("no reply to " + request.method + " " + request.uri
                + " within " + REPLY_TIMEOUT_MILLIS + "ms");
    }

    /** Turns one raw reply into a response, or returns null when the car hung up. */
    private Rtsp.Response response(String text, Rtsp.Request request) throws IOException {
        if (text == null) {
            return null;
        }
        Rtsp.Response response = Rtsp.Response.parse(text);
        adoptSession(response);
        if (!request.quiet) {
            log("<- " + response.status + " " + response.reason
                    + (lastSession == null ? "" : (" session=" + lastSession)));
        }
        check(response, request);
        return response;
    }

    /**
     * Takes the session id a reply carries.
     *
     * A reply that omits the header leaves the session alone: the reference only
     * assigns when the header is there, and clearing it here would drop the
     * session from every later request the moment a car answers without it.
     */
    private void adoptSession(Rtsp.Response response) {
        String header = response.header("Session");
        if (header == null) {
            return;
        }
        String id = header.split(";")[0].trim();
        if (id.isEmpty() || id.equals(lastSession)) {
            return;
        }
        lastSession = id;
        log("session " + id + " adopted from the car");
    }

    private static void check(Rtsp.Response response, Rtsp.Request request) throws IOException {
        if (!(response.status >= 200 && response.status < 300)) {
            throw new IOException("car rejected " + request.method + " " + request.uri + ": "
                    + response.status + " " + response.reason);
        }
    }

    /** Sends one request and returns the plist body of the reply, if any. */
    private Map<String, Bplist.Node> exchange(Rtsp.Request request) throws IOException {
        Rtsp.Response response = send(request);
        if (response == null) {
            return null;
        }
        Map<String, Bplist.Node> body = new LinkedHashMap<>();
        if (!response.body.isEmpty()) {
            try {
                body = AirplayMessages.plistBody(response.body);
            } catch (RuntimeException ignored) {
                // some replies carry no plist body
            }
        }
        return body;
    }

    /**
     * The MFi key exchange.
     *
     * A car without the endpoint answers 404 or 501, which is the signal to use
     * the HomeKit pairing flow instead; anything else is a real failure.
     */
    private AirplayAuth authSetup() throws Exception {
        AirplayAuth.Request request = AirplayAuth.request();
        Rtsp.Request message = new Rtsp.Request();
        message.method = "POST";
        message.uri = "/auth-setup";
        message.cseq = nextCseq();
        message.headers.add(new String[] {"X-Apple-Device-ID", identity.deviceId});
        message.headers.add(new String[] {"Content-Type", "application/octet-stream"});
        message.body = new String(request.body, StandardCharsets.ISO_8859_1);
        Rtsp.Response response;
        try {
            response = send(message);
        } catch (IOException refused) {
            throw new IOException("the car has no usable /auth-setup: " + refused.getMessage());
        }
        if (response == null) {
            throw new IOException("the car closed the connection during auth-setup");
        }
        return AirplayAuth.open(request,
                response.body.getBytes(StandardCharsets.ISO_8859_1),
                new AirplayAuth.Listener() {
                    @Override
                    public void onLog(String message) {
                        log(message);
                    }
                });
    }

    /** The mode snapshot this side sends, exactly as the reference builds it. */
    private Map<String, Bplist.Node> modesChanged() {
        return modes.snapshot();
    }

    /** Which side owns the screen and the audio right now. */
    public AirplayModes modes() {
        return modes;
    }

    /**
     * Asks for the media audio stream.
     *
     * The order comes from what the car announced: the format its own mask allows
     * goes first, and the rest follow as a fallback, because a car that keeps
     * turning offers down used to end up silent with a single guess. Every
     * rejection only costs the sound, so the whole order is tried.
     */
    private void negotiateAudio() {
        audioConnectionId = 2;
        java.util.List<AirplayMessages.PcmFormat> offers =
                AirplayMessages.offerOrder(carAudioMask);
        if (carAudioMask == 0) {
            log("the car announced no audio formats; offering the four PCM formats in order");
        }
        for (AirplayMessages.PcmFormat candidate : offers) {
            Map<String, Bplist.Node> reply;
            try {
                reply = exchange(AirplayMessages.setupStreams(identity, nextCseq(),
                        java.util.Collections.singletonList(AirplayMessages.audioStream(
                                AirplayMessages.AUDIO_STREAM_TYPE,
                                AirplayMessages.AUDIO_TYPE_MEDIA, candidate,
                                audioConnectionId, AirplayMessages.SCREEN_LATENCY_MS))));
            } catch (IOException refused) {
                log("the car refused " + candidate.describe() + ": " + refused.getMessage());
                continue;
            }
            int port = AirplayMessages.portOf(reply, AirplayMessages.AUDIO_STREAM_TYPE, 0);
            if (port <= 0) {
                log("the car accepted " + candidate.describe()
                        + " without allocating a port; trying the next offer");
                continue;
            }
            long echoed = AirplayMessages.connectionIdOf(reply,
                    AirplayMessages.AUDIO_STREAM_TYPE, audioConnectionId);
            if (echoed != audioConnectionId) {
                log("the car echoed audio connection id " + echoed + " instead of "
                        + audioConnectionId + "; using its value");
                audioConnectionId = (int) echoed;
            }
            audioPort = port;
            audioFormat = candidate;
            log("audio agreed: " + candidate.describe() + ", connection " + audioConnectionId
                    + ", port " + audioPort);
            return;
        }
        log("no audio offer was accepted; this session will have no sound");
    }

    /**
     * Sends the feedback keep-alive the reference runs.
     *
     * Two seconds apart, no body, for as long as the session lives. It is quiet
     * in the log - a timer that runs for hours would bury everything else - and
     * it stops at the first failure, leaving the failure path to the session
     * itself.
     */
    private void startFeedback() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                long sent = 0;
                while (running) {
                    try {
                        Thread.sleep(FEEDBACK_INTERVAL_MILLIS);
                        if (!running) {
                            return;
                        }
                        exchange(AirplayMessages.feedback(identity, nextCseq()));
                        sent++;
                        if (sent == 1) {
                            log("feedback keep-alive running (every "
                                    + (FEEDBACK_INTERVAL_MILLIS / 1000) + "s)");
                        } else if (sent % FEEDBACK_LOG_EVERY == 0) {
                            log("feedback keep-alive sent " + sent + " times");
                        }
                    } catch (InterruptedException stopped) {
                        return;
                    } catch (Exception error) {
                        if (running) {
                            log("feedback keep-alive stopped: " + error);
                        }
                        return;
                    }
                }
            }
        }, "airplay-feedback");
        thread.setDaemon(true);
        thread.start();
    }

    /** Opens the second RTSP connection the car pushes its own requests on. */
    private void startEventChannel() {
        try {
            Socket eventSocket = new Socket();
            eventSocket.connect(new InetSocketAddress(host, port), 5000);
            eventSocket.setTcpNoDelay(true);
            event = new EventChannel(eventSocket);
            Thread thread = new Thread(event, "airplay-events");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception error) {
            log("cannot open the event channel: " + error);
        }
    }

    /**
     * Reads what the car sends on the event channel and answers each request.
     *
     * This is where a car asks for things mid session, and where the reference
     * app reads the HID reports behind touch input. Every message is logged in
     * full, and any six byte value in a plist is offered to the touch decoder.
     */
    private final class EventChannel implements Runnable {
        private final Socket socket;

        EventChannel(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                out.write(AirplayMessages.event(identity, nextCseq()).serialize()
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                log("event channel open");
                while (running) {
                    String message = readMessageFrom(in);
                    if (message == null) {
                        log("the car closed the event channel");
                        return;
                    }
                    log("event <- " + message.replace("\r\n", " | ").trim());
                    String cseq = header(message, "CSeq");
                    String type = eventType(message);
                    int status = handleEvent(type, message);
                    out.write(replyFor(cseq, status).getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    inspectEvent(message);
                }
            } catch (Exception error) {
                if (running) {
                    log("event channel ended: " + error);
                }
            }
        }
    }

    /** The command type of an event message, or null when it carries none. */
    private static String eventType(String message) {
        Map<String, Bplist.Node> body = eventBody(message);
        if (body == null) {
            return null;
        }
        Bplist.Node type = body.get("type");
        if (type == null) {
            return null;
        }
        try {
            return type.asString();
        } catch (RuntimeException notAString) {
            return null;
        }
    }

    /**
     * Runs one command the car sent.
     *
     * Two of them change something on this side: the iAP tunnel, and a
     * changeModes this side can apply (answered with the new modes, the way the
     * reference answers it). Everything else is graded honestly - a request
     * this side cannot carry out gets 501, never a 200 that pretends it
     * happened. The reference grades its own answers the same way.
     */
    private int handleEvent(String type, String message) {
        if (type == null) {
            // no plist, or no type: a bare POST /feedback from the car, which
            // the reference answers 200 without dispatching anything
            return 200;
        }
        if ("iAPSendMessage".equals(type)) {
            if (!dispatching) {
                // the session reader is not up yet, so a reply could not be
                // matched; the car will send again once it is
                log("the car sent iAP over the tunnel before the session reader was up; "
                        + "dropping this one");
                return 200;
            }
            byte[] bytes = eventIapPayload(message);
            if (bytes == null) {
                log("the car's iAPSendMessage carried no params.data");
                return 200;
            }
            tunnelBytesIn += bytes.length;
            log("iAP tunnel <- " + bytes.length + " bytes: " + Logs.hex(bytes,
                    Math.min(bytes.length, 64)));
            listener.onIapTunnel(bytes);
            return 200;
        }
        if ("requestUI".equals(type)) {
            registerHidDevices(message);
            // the car may also ask for a UI this side does not have; the
            // reference answers 501 for a url it cannot serve, and the car
            // accepts that, so the same answer goes out here
            String url = stringField(message, "url");
            if (url != null) {
                log("the car asked for a UI url=" + url
                        + "; this side has none to serve, answering 501");
                return 501;
            }
            return 200;
        }
        if ("hidSendReport".equals(type)) {
            // The reference answers 200 only when the report turned into a
            // touch (or a key) it could hand on, and 501 when the device has no
            // matching descriptor or the bytes do not decode. Saying 200 for a
            // report that went nowhere would hide the one line that explains a
            // dead touchscreen.
            return decodeHidReport(message) ? 200 : 501;
        }
        if ("forceKeyFrame".equals(type)) {
            boolean taken = listener.onKeyFrameRequested();
            log("the car asked for a key frame; taken=" + taken);
            if (!taken) {
                log("no encoder is running yet; answering 501");
                return 501;
            }
            return 200;
        }
        if ("requestSiri".equals(type)) {
            // the reference turns the microphone on here and dispatches a voice
            // key; this side has no microphone path, and answering "done" for
            // something that did not happen is worse than saying so
            log("the car asked for voice input, which is not implemented on this side; "
                    + "answering 501");
            return 501;
        }
        if ("changeModes".equals(type)) {
            Map<String, Bplist.Node> reply = modes.changeModes(eventBody(message));
            if (reply == null) {
                log("the car asked to change modes in a way this side does not handle; "
                        + "answering 501, the way the reference refuses it");
                return 501;
            }
            log("the screen is now owned by "
                    + (modes.screen() == AirplayModes.OWNED_BY_PHONE ? "this phone" : "the car")
                    + "; answering with the new modes");
            replyBody = reply;
            return 200;
        }
        // The reference answers 501 to a command type it does not implement
        // (its dispatcher falls through to "unsupported type; response=501"),
        // and only ever answers 200 for one it carried out. A message with no
        // type stays 200: that is how a bare POST /feedback is answered.
        log("the car sent a " + type
                + " command, which this side does not implement; answering 501");
        return 501;
    }

    /**
     * Registers the car's HID devices.
     *
     * The descriptor inside each device is the car's, and it is what its later
     * hidSendReport payloads have to be read with; a device whose descriptor we
     * cannot parse is kept out rather than guessed at.
     */
    private void registerHidDevices(String message) {
        Map<String, Bplist.Node> body = eventBody(message);
        if (body == null || body.get("hidDevices") == null) {
            log("requestUI carried no hidDevices list");
            return;
        }
        for (Bplist.Node node : body.get("hidDevices").asArray()) {
            Map<String, Bplist.Node> device;
            try {
                device = node.asDict();
            } catch (RuntimeException notADict) {
                continue;
            }
            Bplist.Node uuidNode = device.get("uuid");
            Bplist.Node descriptorNode = device.get("hidDescriptor");
            String uuid = uuidNode == null ? null : uuidNode.asString();
            byte[] descriptor = descriptorNode == null ? null : descriptorNode.asData();
            if (uuid == null || descriptor == null) {
                log("a HID device came without a uuid or descriptor; skipped");
                continue;
            }
            Bplist.Node name = device.get("name");
            Bplist.Node display = device.get("displayUUID");
            if (display != null && displayUUID != null
                    && !display.asString().equals(displayUUID)) {
                log("HID device " + uuid + " belongs to another display; skipped");
                continue;
            }
            try {
                HidDescriptor parsed = HidDescriptor.parse(descriptor);
                hidDevices.put(uuid, parsed);
                log("HID device registered uuid=" + uuid
                        + " name=" + (name == null ? "?" : name.asString())
                        + " " + parsed.describe()
                        + " touch=" + parsed.isTouchScreen()
                        + " descriptor=" + Logs.hex(descriptor,
                                Math.min(descriptor.length, 64)));
            } catch (RuntimeException unsupported) {
                log("HID descriptor unsupported for " + uuid + ": " + unsupported.getMessage()
                        + "; raw descriptor=" + Logs.hex(descriptor,
                                Math.min(descriptor.length, 64)));
            }
        }
    }

    /** Decodes one of the car's reports with the descriptor it registered. */
    private boolean decodeHidReport(String message) {
        Map<String, Bplist.Node> body = eventBody(message);
        if (body == null) {
            return false;
        }
        Bplist.Node uuidNode = body.get("uuid");
        Bplist.Node reportNode = body.get("hidReport");
        String uuid = uuidNode == null ? null : uuidNode.asString();
        byte[] report = reportNode == null ? null : reportNode.asData();
        if (report == null) {
            log("the car's hidSendReport carried no binary hidReport; answering 501");
            return false;
        }
        log("HID report device=" + uuid + " bytes=" + Logs.hex(report,
                Math.min(report.length, 32)));
        HidDescriptor descriptor = uuid == null ? null : hidDevices.get(uuid);
        if (descriptor == null) {
            log("no descriptor registered for " + uuid
                    + "; the report is logged but no touch is injected; answering 501");
            return false;
        }
        if (!descriptor.isTouchScreen()) {
            log("the descriptor for " + uuid + " is not a touch screen; this side does not"
                    + " decode key reports yet; answering 501");
            return false;
        }
        boolean decoded = false;
        for (int finger = 0; finger < descriptor.fingers(); finger++) {
            HidTouch.Touch touch;
            try {
                touch = descriptor.decodeTouch(report, finger);
            } catch (RuntimeException error) {
                log("cannot decode the HID report as finger " + finger + ": " + error
                        + "; answering 501");
                return decoded;
            }
            if (touch == null) {
                // that finger is not in this descriptor; not a failure
                continue;
            }
            log("HID touch finger " + finger + ": " + touch.describe());
            listener.onTouch(touch);
            // a report that decodes is answered 200 even with no finger down:
            // lifting off sends one too, and the reference grades it the same way
            decoded = true;
        }
        return decoded;
    }

    /** A string field of an event body, or null when it is missing. */
    private static String stringField(String message, String key) {
        Map<String, Bplist.Node> body = eventBody(message);
        if (body == null || body.get(key) == null) {
            return null;
        }
        try {
            return body.get(key).asString();
        } catch (RuntimeException notAString) {
            return null;
        }
    }

    /** The bytes inside an iAPSendMessage's params.data. */
    private static byte[] eventIapPayload(String message) {
        Map<String, Bplist.Node> body = eventBody(message);
        if (body == null || body.get("params") == null) {
            return null;
        }
        Bplist.Node data = body.get("params").asDict().get("data");
        if (data == null) {
            return null;
        }
        try {
            return data.asData();
        } catch (RuntimeException notData) {
            return null;
        }
    }

    /** The plist body of an event message, or null when there is none. */
    private static Map<String, Bplist.Node> eventBody(String message) {
        int split = message.indexOf("\r\n\r\n");
        if (split < 0 || split + 4 >= message.length()) {
            return null;
        }
        try {
            return AirplayMessages.plistBody(message.substring(split + 4));
        } catch (RuntimeException notPlist) {
            return null;
        }
    }

    /**
     * A reply with the body a command produced, or none when it is refused.
     *
     * Two answers exist on this path: 200 for a command that was carried out,
     * and 501 for one that was not. 501 is the reference's own refusal (it
     * sends nothing else here), and a refusal must not carry a plist body.
     */
    private String replyFor(String cseq, int status) {
        String head = "RTSP/1.0 " + status + " " + (status == 200 ? "OK" : "Not Implemented")
                + "\r\nCSeq: " + (cseq == null ? "1" : cseq) + "\r\n";
        if (status != 200) {
            return head + "\r\n";
        }
        Map<String, Bplist.Node> body = replyBody;
        replyBody = null;
        byte[] payload = Bplist.encode(Bplist.Node.dict(body == null
                ? new LinkedHashMap<String, Bplist.Node>() : body));
        return head + "Content-Type: application/x-apple-binary-plist\r\nContent-Length: "
                + payload.length + "\r\n\r\n"
                + new String(payload, StandardCharsets.ISO_8859_1);
    }

    /** Logs the shape of an event message; the decoding happens in the handlers. */
    private void inspectEvent(String message) {
        Map<String, Bplist.Node> plist = eventBody(message);
        if (plist == null) {
            log("event body (not a plist): "
                    + message.substring(Math.min(120, message.length())).length() + " bytes");
            return;
        }
        StringBuilder shape = new StringBuilder();
        for (Map.Entry<String, Bplist.Node> entry : plist.entrySet()) {
            if (shape.length() > 0) {
                shape.append(", ");
            }
            shape.append(entry.getKey());
        }
        log("event plist fields=[" + shape + "]");
    }

    /** Notes whether the car named a timing port, the way the reference does. */
    private void notePeerTimingPort(Map<String, Bplist.Node> reply) {
        if (peerTimingPort > 0) {
            return;
        }
        int port = integer(reply, "timingPort");
        if (port > 0) {
            peerTimingPort = port;
        }
        log("peer timing-port-present=" + (peerTimingPort > 0)
                + (peerTimingPort > 0 ? (" port=" + peerTimingPort) : "")
                + "; this side only answers timing requests");
    }

    /** A plist integer, or 0 when the key is missing. */
    private static int integer(Map<String, Bplist.Node> body, String key) {
        Bplist.Node node = body == null ? null : body.get(key);
        if (node == null) {
            return 0;
        }
        try {
            return (int) node.asInteger();
        } catch (RuntimeException notANumber) {
            return 0;
        }
    }

    private static String header(String message, String name) {
        for (String line : message.replace("\r\n", "\n").split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /**
     * The largest header block accepted.
     *
     * The reference refuses anything past its own cap; without one a malformed
     * stream would be swallowed until the process runs out of room.
     */
    public static final int MAX_HEADER_BYTES = 64 * 1024;

    /** Reads one RTSP message from an arbitrary stream. */
    static String readMessageFrom(InputStream stream) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int newlines = 0;
        while (newlines < 2) {
            int value = stream.read();
            if (value < 0) {
                return head.size() == 0 ? null : head.toString("ISO-8859-1");
            }
            head.write(value);
            if (head.size() > MAX_HEADER_BYTES) {
                throw new IOException("the RTSP header block passed " + MAX_HEADER_BYTES
                        + " bytes without ending");
            }
            if (value == '\n') {
                newlines++;
            } else if (value != '\r') {
                newlines = 0;
            }
        }
        String headers = head.toString("ISO-8859-1");
        // the reference refuses chunked framing as well: without a length there
        // is no way to know where the body ends, and guessing corrupts the stream
        if (headers.toLowerCase().contains("transfer-encoding: chunked")) {
            throw new IOException("the receiver answered with chunked framing, which is not "
                    + "supported");
        }
        int contentLength = 0;
        for (String line : headers.replace("\r\n", "\n").split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    contentLength = 0;
                }
            }
        }
        if (contentLength <= 0) {
            if (headers.toLowerCase().contains("content-type:")) {
                Logs.add("an RTSP message announced a body but no length; the stream may be "
                        + "out of step");
            }
            return headers;
        }
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int count = stream.read(body, read, contentLength - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        return headers + new String(body, 0, read, StandardCharsets.ISO_8859_1);
    }

    private String readMessage() throws IOException {
        return readMessageFrom(input);
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // shutting down
        }
    }
}
