package org.carplaytocarlife.iccoa;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The RTSP reader against the answers a receiver should not be trusted to get
 * right: a different protocol prefix, a missing reason phrase, chunked framing,
 * a header block that never ends.
 *
 * A session that goes wrong here goes wrong quietly - the stream ends up one
 * message out of step and every later exchange reads the previous answer - so
 * the reader is tested on its own rather than only through a whole session.
 */
public final class SelfTestRtsp {

    private static int failures;

    public static void main(String[] args) throws Exception {
        String ok = read("RTSP/1.0 200 OK\r\nCSeq: 5\r\nContent-Length: 0\r\n\r\n");
        Rtsp.Response response = Rtsp.Response.parse(ok);
        check("a plain reply parses", response.status == 200 && response.cseq == 5);

        String http = read("HTTP/1.1 200 OK\r\nCSeq: 6\r\n\r\n");
        check("a reply in the other protocol parses too",
                Rtsp.Response.parse(http).status == 200);
        check("and keeps its cseq", Rtsp.Response.parse(http).cseq == 6);

        String noReason = read("RTSP/1.0 453\r\nCSeq: 7\r\n\r\n");
        check("a status without a reason phrase parses",
                Rtsp.Response.parse(noReason).status == 453);

        String withBody = read("RTSP/1.0 200 OK\r\nCSeq: 8\r\nContent-Length: 4\r\n\r\n"
                + "body");
        check("the body is read to its announced length",
                Rtsp.Response.parse(withBody).body.equals("body"));

        // a body that looks like the end of the headers must not truncate it
        String blank = "a\r\n\r\nb";
        int blankLength = blank.getBytes(StandardCharsets.ISO_8859_1).length;
        String tricky = read("RTSP/1.0 200 OK\r\nCSeq: 9\r\nContent-Length: " + blankLength
                + "\r\n\r\n" + blank);
        check("a body containing a blank line is read whole",
                Rtsp.Response.parse(tricky).body.equals(blank),
                Rtsp.Response.parse(tricky).body.length() + " of " + blankLength);

        String bodyNoLength = read("RTSP/1.0 200 OK\r\nCSeq: 20\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n");
        check("a body with no length still parses as a message",
                Rtsp.Response.parse(bodyNoLength).status == 200);

        check("chunked framing is refused", refused(
                "RTSP/1.0 200 OK\r\nCSeq: 10\r\nTransfer-Encoding: chunked\r\n\r\n"));
        check("an endless header block is refused", refusedEndless());
        check("an empty stream reads as nothing", read("") == null);
        check("a half written header line is not lost",
                read("RTSP/1.0 200 OK\r\nCSeq: 11\r\n") != null);

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST RTSP OK" : (failures + " FAILED"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static String read(String text) throws Exception {
        InputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
        return AirplayClient.readMessageFrom(in);
    }

    private static boolean refused(String text) {
        try {
            read(text);
            return false;
        } catch (Exception expected) {
            return expected.getMessage() != null
                    && expected.getMessage().contains("chunked");
        }
    }

    private static boolean refusedEndless() {
        StringBuilder text = new StringBuilder("RTSP/1.0 200 OK\r\n");
        for (int i = 0; i < 20000; i++) {
            text.append("X-Pad-").append(i).append(": filler\r\n");
        }
        try {
            read(text.toString());
            return false;
        } catch (Exception expected) {
            return expected.getMessage() != null
                    && expected.getMessage().contains("header block");
        }
    }

    private static void check(String name, boolean condition) {
        check(name, condition, "");
    }

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name + (detail.isEmpty() ? "" : "  " + detail));
        }
    }

    private SelfTestRtsp() {
    }
}
