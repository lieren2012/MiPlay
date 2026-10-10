package org.carplaytocarlife.iccoa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The RTSP messages an AirPlay sender exchanges with the car, with their binary
 * plist bodies.
 *
 * The reference app implements the same messages in its airplay package
 * (RtspConnection + Plists + SetupProof + ControlCommands); the shapes below
 * follow the publicly documented AirPlay 2 flow. Field *names* are stable, but
 * the exact value sets (features bitmasks, model strings) are unverified until a
 * car answers, so they are built from named constants that are easy to adjust.
 */
public final class AirplayMessages {

    /** The receiver's AirPlay port; discovery finds the real one. */
    public static final int AIRPLAY_PORT = 7000;
    public static final String RTSP_URI = "rtsp://localhost/stream";

    /** The geometry the sender declares and the encoder produces. */
    public static final int VIDEO_WIDTH = 1280;
    public static final int VIDEO_HEIGHT = 720;
    public static final int VIDEO_FPS = 30;
    public static final int VIDEO_PORT = 7100;
    public static final int AUDIO_PORT = 7101;
    /** Fallback control port; the real one is the server socket we open. */
    public static final int CONTROL_PORT = 7102;

    /** The device identity the flight is built around. */
    public static final class Identity {
        public final String deviceId;
        public final String name;
        public final String model;

        public Identity(String deviceId, String name, String model) {
            this.deviceId = deviceId;
            this.name = name;
            this.model = model;
        }
    }

    private AirplayMessages() {
    }

    /** The content type the pairing endpoints take: TLV8 items, not a plist. */
    public static final String PAIRING_CONTENT_TYPE = "application/pairing+tlv8";

    /**
     * One pairing step.
     *
     * The reference puts the state in item 6, always sends {@code X-Apple-HKP: 2}
     * and adds {@code X-Apple-PD: 1} on /pair-verify, and uses the TLV8 content
     * type. Both endpoints are strict about this: a plist body, or the old
     * {@code application/octet-stream}, is answered with an error item.
     */
    public static Rtsp.Request pairing(String path, int cseq, Map<Integer, byte[]> items) {
        Rtsp.Request request = new Rtsp.Request();
        request.method = "POST";
        request.uri = path;
        request.cseq = cseq;
        request.headers.add(new String[] {"User-Agent", USER_AGENT});
        request.headers.add(new String[] {"Content-Type", PAIRING_CONTENT_TYPE});
        request.headers.add(new String[] {"X-Apple-HKP", "2"});
        if ("/pair-verify".equals(path)) {
            request.headers.add(new String[] {"X-Apple-PD", "1"});
        }
        request.body = new String(Tlv8.encode(items),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        return request;
    }

    /** The items a pairing reply carries, or null when it is not TLV at all. */
    public static Map<Integer, byte[]> tlvBody(String body) {
        if (body == null || body.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return Tlv8.decode(body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    /** The protocol compatibility version the car keys its behaviour on. */
    public static final String COMPATIBILITY_VERSION = "21A329";
    public static final String SOURCE_VERSION = "320.17";
    public static final String OS_NAME = "Android";
    public static final String OS_VERSION = "17.0";
    /** AirPlay stream types: the screen is 110, 130 is an optional tunnel. */
    public static final int SCREEN_STREAM_TYPE = 110;
    public static final int TUNNEL_STREAM_TYPE = 130;
    /** The latency the reference asks the car to hold for the screen stream. */
    public static final int SCREEN_LATENCY_MS = 100;

    /**
     * The user agent every request carries.
     *
     * The reference builds its request line as
     * {@code <method> <uri> RTSP/1.0}, then CSeq, then
     * {@code User-Agent: AirPlay/320.17} - the same version string it announces
     * as sourceVersion - and a Session header once there is a session.
     */
    public static final String USER_AGENT = "AirPlay/" + SOURCE_VERSION;

    /** An upper case random session uuid, the way the reference names a session. */
    public static String sessionUuid() {
        return java.util.UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * SETUP /setup: the session itself, before any stream exists.
     *
     * The car answers with the Session header; only after that does it accept
     * GET /info and the stream SETUP. {@code extra} carries the MFiSAP fields
     * ({@code et}, {@code ekey}, {@code eiv}) when the key exchange succeeded.
     */
    public static Rtsp.Request setupSession(Identity identity, int cseq, String sessionUuid,
            int timingPort, Map<String, Bplist.Node> extra) {
        Map<String, Bplist.Node> body = new LinkedHashMap<>();
        body.put("deviceID", Bplist.Node.string(identity.deviceId));
        body.put("macAddress", Bplist.Node.string(identity.deviceId));
        body.put("name", Bplist.Node.string(identity.name));
        body.put("model", Bplist.Node.string(identity.model));
        // a protocol compatibility version, not the real Android build
        body.put("osBuildVersion", Bplist.Node.string(COMPATIBILITY_VERSION));
        body.put("osVersion", Bplist.Node.string(OS_VERSION));
        body.put("osName", Bplist.Node.string(OS_NAME));
        body.put("sessionUUID", Bplist.Node.string(sessionUuid));
        body.put("sourceVersion", Bplist.Node.string(SOURCE_VERSION));
        body.put("timingPort", Bplist.Node.integer(timingPort));
        body.put("timingProtocol", Bplist.Node.string("NTP"));
        body.put("isScreenMirroringSession", Bplist.Node.bool(false));
        body.put("statsCollectionEnabled", Bplist.Node.bool(false));
        body.put("features", Bplist.Node.array(new ArrayList<Bplist.Node>()));
        if (extra != null) {
            body.putAll(extra);
        }
        return request("SETUP", "/setup", identity, cseq, body);
    }

    /** GET /info: the car's display capabilities, valid only inside a session. */
    public static Rtsp.Request info(Identity identity, int cseq) {
        return request("GET", "/info", identity, cseq, new LinkedHashMap<String, Bplist.Node>());
    }

    /** GET /event: a second connection the car can push its own requests down. */
    public static Rtsp.Request event(Identity identity, int cseq) {
        return request("GET", "/event", identity, cseq, new LinkedHashMap<String, Bplist.Node>());
    }

    /**
     * POST /feedback: the session keep-alive.
     *
     * The reference sends this every two seconds for as long as the session
     * lives, with no body; a receiver is entitled to treat a session that stops
     * feeding back as gone.
     */
    public static Rtsp.Request feedback(Identity identity, int cseq) {
        Rtsp.Request request = request("POST", "/feedback", identity, cseq,
                new LinkedHashMap<String, Bplist.Node>());
        request.quiet = true;
        return request;
    }

    /** POST /command: control traffic inside the session (modes, iAP tunnelling). */
    public static Rtsp.Request command(Identity identity, int cseq,
            Map<String, Bplist.Node> body) {
        return request("POST", "/command", identity, cseq, body);
    }

    /** The screen stream: a type, a connection id and a latency the car honours. */
    public static Map<String, Bplist.Node> screenStream(long connectionId, int latencyMs) {
        Map<String, Bplist.Node> stream = new LinkedHashMap<>();
        stream.put("type", Bplist.Node.integer(SCREEN_STREAM_TYPE));
        stream.put("streamConnectionID", Bplist.Node.integer(connectionId));
        stream.put("latencyMs", Bplist.Node.integer(latencyMs));
        return stream;
    }

    /** The audio stream type for media playback. */
    public static final int AUDIO_STREAM_TYPE = 96;
    public static final String AUDIO_TYPE_MEDIA = "media";
    /** The compression type the reference asks for (1 = PCM). */
    public static final int AUDIO_COMPRESSION = 1;

    /**
     * One PCM offer. The reference tries four of these in order and keeps the
     * one the car accepts, so the field is a format tag rather than a bit depth.
     */
    public static final class PcmFormat {
        public final long format;
        public final int rate;
        public final int channels;

        public PcmFormat(long format, int rate, int channels) {
            this.format = format;
            this.rate = rate;
            this.channels = channels;
        }

        /** Samples per frame: the reference asks for 200 frames a second. */
        public int samplesPerFrame() {
            return rate / 200;
        }

        public String describe() {
            return "PCM/" + rate + "/" + channels + "ch format=" + format;
        }
    }

    /** The candidates, in the order the reference offers them. */
    public static final PcmFormat[] PCM_CANDIDATES = {
        new PcmFormat(32768, 48000, 2),
        new PcmFormat(2048, 44100, 2),
        new PcmFormat(16384, 48000, 1),
        new PcmFormat(1024, 44100, 1)
    };

    /** The bit mask a car uses to list the PCM formats it can decode. */
    public static final String AUDIO_OUTPUT_FORMATS = "audioOutputFormats";

    /**
     * The PCM formats the car says it can take.
     *
     * A car that describes its streams names, on the audio entry it would take
     * (type 96 / audioType "media"), an {@code audioOutputFormats} mask: one bit
     * per format, using the same numbers this side offers. The reference reads
     * that mask and offers the first candidate it allows instead of guessing, so
     * the mask is looked for in whatever reply describes the car's streams.
     * 0 means the car said nothing.
     */
    public static long audioOutputMask(Map<String, Bplist.Node> reply) {
        if (reply == null) {
            return 0;
        }
        List<?> streams = asList(reply.get("streams"));
        if (streams == null) {
            return 0;
        }
        long mask = 0;
        for (Object entry : streams) {
            Map<?, ?> stream = asMap(entry);
            if (stream == null || number(stream.get("type"), -1) != AUDIO_STREAM_TYPE) {
                continue;
            }
            Object audioType = stream.get("audioType");
            if (audioType != null && !AUDIO_TYPE_MEDIA.equals(text(audioType))) {
                continue;
            }
            mask |= numberLong(stream.get(AUDIO_OUTPUT_FORMATS), 0);
        }
        return mask;
    }

    /** The first candidate the car's mask allows, or null when it allows none. */
    public static PcmFormat formatFromMask(long mask) {
        if (mask == 0) {
            return null;
        }
        for (PcmFormat candidate : PCM_CANDIDATES) {
            if ((candidate.format & mask) != 0) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The order the PCM formats are offered in.
     *
     * The car's own announcement comes first, which is what the reference
     * offers and all it offers; the other candidates follow as a fallback, so a
     * car whose mask is stale still gets a chance at sound instead of silence.
     */
    public static List<PcmFormat> offerOrder(long mask) {
        List<PcmFormat> order = new ArrayList<>();
        PcmFormat announced = formatFromMask(mask);
        if (announced != null) {
            order.add(announced);
        }
        for (PcmFormat candidate : PCM_CANDIDATES) {
            if (candidate != announced) {
                order.add(candidate);
            }
        }
        return order;
    }

    /** One audio stream entry, as the reference negotiates it. */
    public static Map<String, Bplist.Node> audioStream(int streamType, String audioType,
            PcmFormat format, long connectionId, int latencyMs) {
        Map<String, Bplist.Node> stream = new LinkedHashMap<>();
        stream.put("type", Bplist.Node.integer(streamType));
        stream.put("audioType", Bplist.Node.string(audioType));
        stream.put("audioFormat", Bplist.Node.integer(format.format));
        stream.put("streamConnectionID", Bplist.Node.integer(connectionId));
        stream.put("audioLatencyMs", Bplist.Node.integer(latencyMs));
        stream.put("spf", Bplist.Node.integer(format.samplesPerFrame()));
        stream.put("sr", Bplist.Node.integer(format.rate));
        stream.put("ct", Bplist.Node.integer(AUDIO_COMPRESSION));
        return stream;
    }

    /**
     * Reads the stream connection id the car echoed back, so a mismatch can be
     * logged instead of silently streaming into the wrong stream.
     */
    public static long connectionIdOf(Map<String, Bplist.Node> reply, int type, long fallback) {
        if (reply == null || reply.get("streams") == null) {
            return fallback;
        }
        for (Bplist.Node stream : reply.get("streams").asArray()) {
            Map<String, Bplist.Node> map = stream.asDict();
            if (map.get("type") != null && map.get("type").asInteger() == type
                    && map.get("streamConnectionID") != null) {
                return map.get("streamConnectionID").asInteger();
            }
        }
        return fallback;
    }

    /** SETUP /: asks the car to open the streams and allocate their ports. */
    public static Rtsp.Request setupStreams(Identity identity, int cseq,
            List<Map<String, Bplist.Node>> streams) {
        List<Bplist.Node> nodes = new ArrayList<>();
        for (Map<String, Bplist.Node> stream : streams) {
            nodes.add(Bplist.Node.dict(stream));
        }
        Map<String, Bplist.Node> body = new LinkedHashMap<>();
        body.put("streams", Bplist.Node.array(nodes));
        return request("SETUP", "/", identity, cseq, body);
    }

    /** RECORD /: starts the session; the session header carries the state. */
    public static Rtsp.Request recordSession(Identity identity, int cseq, String sessionId) {
        Rtsp.Request request = request("RECORD", "/", identity, cseq,
                new LinkedHashMap<String, Bplist.Node>());
        if (sessionId != null) {
            request.headers.add(new String[] {"Session", sessionId});
        }
        return request;
    }

    /** Reads the port the car allocated for a stream type. */
    public static int portOf(Map<String, Bplist.Node> reply, int type, int fallback) {
        if (reply == null || reply.get("streams") == null) {
            return fallback;
        }
        for (Bplist.Node stream : reply.get("streams").asArray()) {
            Map<String, Bplist.Node> map = stream.asDict();
            if (map.get("type") != null && map.get("type").asInteger() == type) {
                for (String key : new String[] {"dataPort", "controlPort", "port"}) {
                    if (map.get(key) != null) {
                        return (int) map.get(key).asInteger();
                    }
                }
            }
        }
        return fallback;
    }

    /** The display type the phone drives; the reference looks for this one. */
    public static final int MAIN_DISPLAY_TYPE = 0;

    /**
     * The frame rate assumed when the car does not say.
     *
     * The reference reads {@code maxFPS} off the display and falls back to 15,
     * so a car that leaves the field out is not treated as "no ceiling".
     */
    public static final int DEFAULT_MAX_FPS = 15;

    /** The screen the car describes in its /info reply. */
    public static final class Display {
        /** Where it sat in the car's list, for the log. */
        public final int index;
        public final int type;
        public final int width;
        public final int height;
        public final int maxFps;

        Display(int index, int type, int width, int height, int maxFps) {
            this.index = index;
            this.type = type;
            this.width = width;
            this.height = height;
            this.maxFps = maxFps;
        }
    }

    /**
     * The display the car means, or null when its /info names none.
     *
     * The reference walks {@code displays} and takes the entry whose
     * {@code type} is 0 - the screen the phone drives - falling back to the
     * first entry it can read. Only an entry that carries {@code widthPixels}
     * and {@code heightPixels} counts: a car describing its screen in some
     * other shape is logged and left alone rather than guessed at. The
     * {@code maxFPS} on it is the car's own ceiling for video.
     */
    public static Display firstDisplay(Map<String, Bplist.Node> info) {
        if (info == null || info.get("displays") == null) {
            return null;
        }
        List<?> listed = asList(info.get("displays"));
        if (listed == null) {
            return null;
        }
        Display first = null;
        int index = 0;
        for (Object entry : listed) {
            Display display = displayAt(index, entry);
            index++;
            if (display == null) {
                continue;
            }
            if (display.type == MAIN_DISPLAY_TYPE) {
                return display;
            }
            if (first == null) {
                first = display;
            }
        }
        return first;
    }

    private static Display displayAt(int index, Object entry) {
        Map<?, ?> map = asMap(entry);
        if (map == null) {
            return null;
        }
        int width = number(map.get("widthPixels"), 0);
        int height = number(map.get("heightPixels"), 0);
        if (width <= 0 || height <= 0) {
            return null;
        }
        int maxFps = number(map.get("maxFPS"), DEFAULT_MAX_FPS);
        if (maxFps <= 0) {
            maxFps = DEFAULT_MAX_FPS;
        }
        return new Display(index, number(map.get("type"), -1), width, height, maxFps);
    }

    /** A plist integer, or the fallback when the value is not a number. */
    private static int number(Object value, int fallback) {
        Object raw = unwrap(value);
        return raw instanceof Number ? ((Number) raw).intValue() : fallback;
    }
    /** A plist integer as a long, or the fallback when it is not a number. */
    private static long numberLong(Object value, long fallback) {
        Object raw = unwrap(value);
        return raw instanceof Number ? ((Number) raw).longValue() : fallback;
    }

    /** The string behind a decoded plist value, or null for anything else. */
    private static String text(Object value) {
        Object raw = unwrap(value);
        return raw instanceof String ? (String) raw : null;
    }

    /** The dictionary behind a decoded plist value, or null for anything else. */
    private static Map<?, ?> asMap(Object value) {
        Object raw = unwrap(value);
        return raw instanceof Map ? (Map<?, ?>) raw : null;
    }

    /** The list behind a decoded plist value, or null for anything else. */
    private static List<?> asList(Object value) {
        Object raw = unwrap(value);
        return raw instanceof List ? (List<?>) raw : null;
    }

    private static Object unwrap(Object value) {
        return value instanceof Bplist.Node ? ((Bplist.Node) value).value : value;
    }

    /** One request with an optional binary plist body. */
    private static Rtsp.Request request(String method, String uri, Identity identity, int cseq,
            Map<String, Bplist.Node> body) {
        Rtsp.Request request = new Rtsp.Request();
        request.method = method;
        request.uri = uri;
        request.cseq = cseq;
        request.headers.add(new String[] {"X-Apple-Device-ID", identity.deviceId});
        request.headers.add(new String[] {"User-Agent", USER_AGENT});
        if (body != null && !body.isEmpty()) {
            request.headers.add(new String[] {"Content-Type", "application/x-apple-binary-plist"});
            request.body = new String(Bplist.encode(Bplist.Node.dict(body)),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        return request;
    }

    /** RECORD, with the negotiated headers. */
    public static Rtsp.Request record(Identity identity, int cseq, String sessionId,
            String audioLatency, String videoLatency) {
        Rtsp.Request request = new Rtsp.Request();
        request.method = "RECORD";
        request.uri = RTSP_URI;
        request.cseq = cseq;
        request.headers.add(new String[] {"X-Apple-Device-ID", identity.deviceId});
        request.headers.add(new String[] {"Session", sessionId});
        if (audioLatency != null) {
            request.headers.add(new String[] {"Audio-Latency", audioLatency});
        }
        if (videoLatency != null) {
            request.headers.add(new String[] {"Video-Latency", videoLatency});
        }
        return request;
    }

    /** A generic POST with a plist body, which is how pairing steps are sent. */
    public static Rtsp.Request post(String path, int cseq, String deviceId,
            Map<String, Bplist.Node> body) {
        Rtsp.Request request = new Rtsp.Request();
        request.method = "POST";
        request.uri = path;
        request.cseq = cseq;
        request.headers.add(new String[] {"X-Apple-Device-ID", deviceId});
        request.headers.add(new String[] {"User-Agent", USER_AGENT});
        request.headers.add(new String[] {"Content-Type", "application/octet-stream"});
        request.body = new String(Bplist.encode(Bplist.Node.dict(body)),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        return request;
    }

    /** Decodes a plist body coming back from the car. */
    public static Map<String, Bplist.Node> plistBody(String body) {
        if (body == null || body.isEmpty()) {
            return new LinkedHashMap<>();
        }
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        if (bytes.length < 8 || bytes[0] != 'b') {
            throw new IllegalArgumentException("reply body is not a binary plist");
        }
        return Bplist.decode(bytes).asDict();
    }
}
