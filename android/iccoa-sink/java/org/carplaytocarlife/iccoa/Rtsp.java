package org.carplaytocarlife.iccoa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RTSP framing and the {@code text/parameters} body used by Wi-Fi Display.
 *
 * Deliberately free of Android APIs so the same code can be exercised with a
 * plain JDK (see {@code SelfTest}) and inside the APK.
 */
public final class Rtsp {

    private Rtsp() {
    }

    /** An ordered {@code name: value} block, which is what a text/parameters body is. */
    public static final class Parameters {
        private final Map<String, String> map = new LinkedHashMap<>();

        public void set(String name, String value) {
            map.put(name, value);
        }

        public String get(String name) {
            return map.get(name);
        }

        public boolean has(String name) {
            return map.containsKey(name);
        }

        public void remove(String name) {
            map.remove(name);
        }

        public int size() {
            return map.size();
        }

        public Iterable<Map.Entry<String, String>> entries() {
            return map.entrySet();
        }

        public static Parameters parse(String body) {
            Parameters parameters = new Parameters();
            if (body == null) {
                return parameters;
            }
            for (String raw : body.replace("\r\n", "\n").split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                parameters.set(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
            return parameters;
        }

        public String serialize() {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                out.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
            return out.toString();
        }
    }

    /** An RTSP request. */
    public static final class Request {
        public String method = "";
        public String uri = "";
        public int cseq;
        public final List<String[]> headers = new ArrayList<>();
        public String body = "";
        /** A request that runs on a timer is not worth two log lines every time. */
        public boolean quiet;

        public String header(String name) {
            for (String[] header : headers) {
                if (header[0].equalsIgnoreCase(name)) {
                    return header[1];
                }
            }
            return null;
        }

        public Parameters parameters() {
            return Parameters.parse(body);
        }

        public String serialize() {
            StringBuilder out = new StringBuilder();
            out.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n");
            out.append("CSeq: ").append(cseq).append("\r\n");
            for (String[] header : headers) {
                out.append(header[0]).append(": ").append(header[1]).append("\r\n");
            }
            if (!body.isEmpty()) {
                out.append("Content-Length: ").append(body.length()).append("\r\n");
            }
            out.append("\r\n").append(body);
            return out.toString();
        }

        public static Request parse(String text) {
            String head = text;
            String body = "";
            int split = indexOfBlankLine(text);
            if (split >= 0) {
                head = text.substring(0, split);
                body = text.substring(blankLineEnd(text, split));
            }
            String[] lines = head.replace("\r\n", "\n").split("\n");
            if (lines.length == 0 || lines[0].trim().isEmpty()) {
                throw new IllegalArgumentException("empty RTSP request");
            }
            String[] parts = lines[0].trim().split("\\s+");
            if (parts.length < 2) {
                throw new IllegalArgumentException("malformed request line: " + lines[0]);
            }
            Request request = new Request();
            request.method = parts[0].toUpperCase();
            request.uri = parts[1];
            boolean sawCseq = false;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    throw new IllegalArgumentException("malformed header: " + line);
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("CSeq")) {
                    request.cseq = Integer.parseInt(value);
                    sawCseq = true;
                }
                request.headers.add(new String[] {name, value});
            }
            if (!sawCseq) {
                throw new IllegalArgumentException("CSeq header is missing");
            }
            request.body = body;
            return request;
        }
    }

    /** An RTSP response. */
    public static final class Response {
        public int status = 200;
        public String reason = "OK";
        public int cseq;
        public final List<String[]> headers = new ArrayList<>();
        public String body = "";

        public Response(int status, String reason, int cseq) {
            this.status = status;
            this.reason = reason;
            this.cseq = cseq;
        }

        public void header(String name, String value) {
            headers.add(new String[] {name, value});
        }

        public String header(String name) {
            for (String[] header : headers) {
                if (header[0].equalsIgnoreCase(name)) {
                    return header[1];
                }
            }
            return null;
        }

        public Parameters parameters() {
            return Parameters.parse(body);
        }

        public String serialize() {
            StringBuilder out = new StringBuilder();
            out.append("RTSP/1.0 ").append(status).append(' ').append(reason).append("\r\n");
            out.append("CSeq: ").append(cseq).append("\r\n");
            for (String[] header : headers) {
                out.append(header[0]).append(": ").append(header[1]).append("\r\n");
            }
            if (!body.isEmpty()) {
                out.append("Content-Length: ").append(body.length()).append("\r\n");
            }
            out.append("\r\n").append(body);
            return out.toString();
        }

        public static Response parse(String text) {
            String head = text;
            String body = "";
            int split = indexOfBlankLine(text);
            if (split >= 0) {
                head = text.substring(0, split);
                body = text.substring(blankLineEnd(text, split));
            }
            String[] lines = head.replace("\r\n", "\n").split("\n");
            String[] parts = lines[0].trim().split("\\s+", 3);
            // a receiver is allowed to answer with HTTP/1.1; the reference accepts both
        if (parts.length < 2
                || (!parts[0].startsWith("RTSP/") && !parts[0].startsWith("HTTP/"))) {
                throw new IllegalArgumentException("malformed status line: " + lines[0]);
            }
            Response response = new Response(Integer.parseInt(parts[1]),
                    parts.length > 2 ? parts[2].trim() : "", 0);
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("CSeq")) {
                    response.cseq = Integer.parseInt(value);
                }
                response.headers.add(new String[] {name, value});
            }
            response.body = body;
            return response;
        }
    }

    /** RTP transport description, both the parameter form and the Transport header form. */
    public static final class Transport {
        public String protocol = "RTP/AVP/UDP;unicast";
        public int clientPort0;
        public int clientPort1;
        public String mode = "play";

        public static Transport parse(String text) {
            Transport transport = new Transport();
            if (text == null) {
                return transport;
            }
            String lower = text.toLowerCase();
            if (lower.contains("rtp/avp/tcp")) {
                transport.protocol = "RTP/AVP/TCP;unicast";
            }
            int marker = lower.indexOf("client_port=");
            if (marker >= 0) {
                String rest = text.substring(marker + "client_port=".length());
                int end = rest.length();
                for (int i = 0; i < rest.length(); i++) {
                    char c = rest.charAt(i);
                    if (c != '-' && (c < '0' || c > '9')) {
                        end = i;
                        break;
                    }
                }
                String range = rest.substring(0, end);
                String[] bounds = range.split("-");
                transport.clientPort0 = Integer.parseInt(bounds[0]);
                if (bounds.length > 1) {
                    transport.clientPort1 = Integer.parseInt(bounds[1]);
                }
            }
            int modeIndex = lower.indexOf("mode=");
            if (modeIndex >= 0) {
                String rest = text.substring(modeIndex + "mode=".length()).trim();
                int end = rest.indexOf(';');
                transport.mode = end < 0 ? rest : rest.substring(0, end);
            }
            return transport;
        }

        public String describe(int serverPort0, int serverPort1) {
            return protocol + ";client_port=" + clientPort0 + "-" + clientPort1
                    + ";server_port=" + serverPort0 + "-" + serverPort1;
        }

        /** The `wfd_client_rtp_ports` parameter form. */
        public static String asParameter(int port0, int port1) {
            return "RTP/AVP/UDP;unicast " + port0 + " " + port1 + " mode=play";
        }
    }

    private static int indexOfBlankLine(String text) {
        int rn = text.indexOf("\r\n\r\n");
        int nn = text.indexOf("\n\n");
        if (rn >= 0 && (nn < 0 || rn <= nn)) {
            return rn;
        }
        return nn;
    }

    private static int blankLineEnd(String text, int split) {
        return text.startsWith("\r\n\r\n", split) ? split + 4 : split + 2;
    }
}
