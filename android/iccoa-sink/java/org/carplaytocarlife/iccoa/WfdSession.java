package org.carplaytocarlife.iccoa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Wi-Fi Display sink side of an ICCOA Carlink session.
 *
 * Parameter names come from the phone-side reverse engineering recorded in
 * {@code docs/iccoa-protocol-notes.md}; the M1..M7 order is the published Wi-Fi
 * Display flow. Values (codec bitmaps, JSON-ish extension payloads) are still
 * unverified, so they are conservative and may need adjusting against a real
 * phone.
 */
public final class WfdSession {

    public enum State {
        IDLE,
        OPTIONS_DONE,
        CAPABILITIES_SENT,
        READY,
        STREAMING,
        STOPPED
    }

    /** Exact parameter names the phone looks for on the sink side. */
    public static final String[] SINK_CAPABILITY_REPORTS = {
        "wfd_uibc_capability",
        "wfd_content_protection",
        "wfd_video_formats",
        "wfd_audio_codecs",
        "wfd_client_rtp_ports",
        "wfd_video_latency",
        "wfd_audio_latency",
        "iccoa_support_fusion",
        "iccoa_support_hevc",
        "osc_display_mode",
        "osc_rotates_with_content",
        "osc_backstage_projection_full",
        "osc_audio_encrypt",
        "osc_is_relay",
        "wfd_car_display_mode",
        "wfd_car_vars",
        "wfd_pc_display_mode",
        "wfd_av_raw_packet"
    };

    private final Listener listener;
    private State state = State.IDLE;
    private int rtpPort = 19000;
    private String sessionId = "0";
    private int outgoingCseq = 100;

    public interface Listener {
        void onLog(String message);

        void onState(State state);

        void onStreamingStart();

        void onStreamingStop();

        void onInputPort(int port);
    }

    public WfdSession(Listener listener) {
        this.listener = listener;
    }

    public State state() {
        return state;
    }

    public int rtpPort() {
        return rtpPort;
    }

    public void setRtpPort(int port) {
        this.rtpPort = port;
    }

    private void setState(State next) {
        state = next;
        listener.onState(next);
    }

    /** The M3 capability block this sink advertises. */
    public Rtsp.Parameters sinkCapabilities() {
        Rtsp.Parameters p = new Rtsp.Parameters();
        // Native H.264 (profile 0x02 = constrained baseline, level 0x04),
        // CEA 1920x1080p30 in the first resolution bit, no VESA/HH.
        p.set("wfd_video_formats",
                "00 00 02 04 00000010 00000000 00000000 00 0000 0000 00 none none");
        p.set("wfd_audio_codecs", "LPCM 00000002 00, AAC 00000001 00");
        p.set("wfd_client_rtp_ports", Rtsp.Transport.asParameter(rtpPort, rtpPort + 1));
        p.set("wfd_uibc_capability",
                "input_category_list=HIDC;generic_cap_list=Mouse,SingleTouch;"
                        + "hidc_cap_list=Mouse,None;port=" + (rtpPort + 2));
        p.set("wfd_content_protection", "none");
        p.set("wfd_video_latency", "50");
        p.set("wfd_audio_latency", "50");
        // Vendor extensions: keep them explicit even when disabled so the phone
        // can see the sink understood them.
        p.set("iccoa_support_fusion", "0");
        p.set("iccoa_support_hevc", "0");
        p.set("osc_display_mode", "vehicle");
        p.set("osc_rotates_with_content", "0");
        p.set("osc_backstage_projection_full", "0");
        p.set("osc_audio_encrypt", "0");
        p.set("osc_is_relay", "0");
        p.set("wfd_car_display_mode", "primary");
        p.set("wfd_car_vars", "{}");
        p.set("wfd_pc_display_mode", "secondary");
        p.set("wfd_av_raw_packet", "0");
        return p;
    }

    /** Which capability reports the block above does not carry. */
    public List<String> missingReports() {
        Rtsp.Parameters parameters = sinkCapabilities();
        List<String> missing = new ArrayList<>();
        for (String report : SINK_CAPABILITY_REPORTS) {
            String value = parameters.get(report);
            if (value == null || value.isEmpty()) {
                missing.add(report);
            }
        }
        return missing;
    }

    /**
     * Handles one request and returns the response plus any requests this sink
     * has to send back (M3 after the capability query, M5 after the selection).
     */
    public Reply handle(Rtsp.Request request) {
        Reply reply = new Reply();
        switch (request.method) {
            case "OPTIONS": {
                Rtsp.Response response = new Rtsp.Response(200, "OK", request.cseq);
                response.header("Public",
                        "org.wfa.wfd1.0, OPTIONS, GET_PARAMETER, SET_PARAMETER, "
                                + "SETUP, PLAY, PAUSE, TEARDOWN");
                reply.response = response;
                setState(State.OPTIONS_DONE);
                listener.onLog("M1 OPTIONS -> 200 (advertised methods)");
                break;
            }
            case "GET_PARAMETER": {
                Rtsp.Request wanted = new Rtsp.Request();
                wanted.method = "GET_PARAMETER";
                wanted.uri = request.uri;
                wanted.cseq = request.cseq;
                Rtsp.Parameters asked = request.parameters();
                listener.onLog("M2 GET_PARAMETER asks for " + asked.size() + " parameters");
                reply.response = new Rtsp.Response(200, "OK", request.cseq);
                // M3: answer with our own capability block.
                Rtsp.Request m3 = new Rtsp.Request();
                m3.method = "SET_PARAMETER";
                m3.uri = request.uri;
                m3.cseq = outgoingCseq++;
                m3.headers.add(new String[] {"Content-Type", "text/parameters"});
                m3.body = sinkCapabilities().serialize();
                reply.followUps.add(m3);
                setState(State.CAPABILITIES_SENT);
                listener.onLog("M3 SET_PARAMETER (sink capabilities) queued, "
                        + sinkCapabilities().size() + " parameters");
                List<String> missing = missingReports();
                if (!missing.isEmpty()) {
                    listener.onLog("still missing: " + missing);
                }
                break;
            }
            case "SET_PARAMETER": {
                Rtsp.Parameters parameters = request.parameters();
                listener.onLog("M4 SET_PARAMETER with " + parameters.size() + " parameters");
                for (Map.Entry<String, String> entry : parameters.entries()) {
                    listener.onLog("   " + entry.getKey() + " = " + abbrev(entry.getValue()));
                }
                reply.response = new Rtsp.Response(200, "OK", request.cseq);
                Rtsp.Request m5 = new Rtsp.Request();
                m5.method = "SET_PARAMETER";
                m5.uri = request.uri;
                m5.cseq = outgoingCseq++;
                m5.headers.add(new String[] {"Content-Type", "text/parameters"});
                m5.body = "wfd_trigger_method: SETUP\n";
                reply.followUps.add(m5);
                listener.onLog("M5 SET_PARAMETER wfd_trigger_method: SETUP queued");
                setState(State.READY);
                break;
            }
            case "SETUP": {
                Rtsp.Transport transport = Rtsp.Transport.parse(request.header("Transport"));
                Rtsp.Response response = new Rtsp.Response(200, "OK", request.cseq);
                response.header("Session", sessionId + ";timeout=60");
                response.header("Transport",
                        transport.describe(rtpPort, rtpPort + 1));
                reply.response = response;
                listener.onLog("M6 SETUP -> 200, client_port=" + transport.clientPort0
                        + "-" + transport.clientPort1 + " server_port=" + rtpPort
                        + "-" + (rtpPort + 1));
                break;
            }
            case "PLAY": {
                Rtsp.Response response = new Rtsp.Response(200, "OK", request.cseq);
                response.header("Session", sessionId);
                reply.response = response;
                listener.onLog("M7 PLAY -> 200, streaming");
                setState(State.STREAMING);
                listener.onStreamingStart();
                break;
            }
            case "PAUSE": {
                Rtsp.Response response = new Rtsp.Response(200, "OK", request.cseq);
                response.header("Session", sessionId);
                reply.response = response;
                listener.onLog("PAUSE -> 200");
                break;
            }
            case "TEARDOWN": {
                Rtsp.Response response = new Rtsp.Response(200, "OK", request.cseq);
                response.header("Session", sessionId);
                reply.response = response;
                listener.onLog("TEARDOWN -> 200");
                setState(State.STOPPED);
                listener.onStreamingStop();
                break;
            }
            default: {
                Rtsp.Response response = new Rtsp.Response(501, "Not Implemented", request.cseq);
                reply.response = response;
                listener.onLog("unsupported method " + request.method + " -> 501");
                break;
            }
        }
        return reply;
    }

    /** What to send back for one request: a response plus optional requests. */
    public static final class Reply {
        public Rtsp.Response response;
        public final List<Rtsp.Request> followUps = new ArrayList<>();
    }

    private static String abbrev(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 60 ? value : value.substring(0, 57) + "...";
    }
}
