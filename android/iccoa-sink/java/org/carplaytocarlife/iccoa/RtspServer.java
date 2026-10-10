package org.carplaytocarlife.iccoa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The RTSP server a Wi-Fi Display source (the phone) connects to.
 *
 * Both directions live on this one TCP connection: the phone sends requests and
 * this sink answers, and the sink also pushes its own requests (M3, M5) and
 * reads their responses.
 */
public final class RtspServer implements Runnable {

    public static final int DEFAULT_PORT = 7236;

    private final int port;
    private final WfdSession session;
    private volatile boolean running;
    private ServerSocket server;
    private Socket client;
    private InputStream input;
    private OutputStream output;

    public RtspServer(int port, WfdSession session) {
        this.port = port;
        this.session = session;
    }

    public int port() {
        return port;
    }

    public String clientAddress() {
        Socket socket = client;
        if (socket == null || socket.isClosed()) {
            return null;
        }
        InetAddress address = socket.getInetAddress();
        return address == null ? null : address.getHostAddress();
    }

    public void start() throws IOException {
        server = new ServerSocket(port);
        running = true;
        Thread thread = new Thread(this, "rtsp-server");
        thread.setDaemon(true);
        thread.start();
        Logs.add("RTSP listening on port " + port);
    }

    public void stop() {
        running = false;
        closeQuietly(client);
        closeQuietly(server);
        client = null;
    }

    @Override
    public void run() {
        while (running) {
            try {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                client = socket;
                input = socket.getInputStream();
                output = socket.getOutputStream();
                Logs.add("RTSP client connected: " + socket.getInetAddress().getHostAddress());
                serve(socket);
            } catch (IOException error) {
                if (running) {
                    Logs.add("RTSP accept/serve error: " + error);
                }
            } finally {
                closeQuietly(client);
                client = null;
                Logs.add("RTSP client disconnected");
            }
        }
    }

    private void serve(Socket socket) throws IOException {
        while (running && !socket.isClosed()) {
            String text = readMessage();
            if (text == null) {
                return;
            }
            Rtsp.Request request;
            try {
                request = Rtsp.Request.parse(text);
            } catch (RuntimeException error) {
                Logs.add("bad RTSP message: " + error.getMessage());
                return;
            }
            Logs.add("<- " + request.method + " (CSeq " + request.cseq + ")");
            WfdSession.Reply reply = session.handle(request);
            if (reply.response == null) {
                continue;
            }
            send(reply.response.serialize());
            Logs.add("-> " + reply.response.status + " " + reply.response.reason);
            for (Rtsp.Request followUp : reply.followUps) {
                send(followUp.serialize());
                Logs.add("-> " + followUp.method + " (CSeq " + followUp.cseq + ")");
                String answer = readMessage();
                if (answer == null) {
                    return;
                }
                try {
                    Rtsp.Response response = Rtsp.Response.parse(answer);
                    Logs.add("<- " + response.status + " " + response.reason
                            + " (CSeq " + response.cseq + ")");
                } catch (RuntimeException error) {
                    Logs.add("bad RTSP response: " + error.getMessage());
                }
            }
        }
    }

    private void send(String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /** Reads one complete RTSP message (headers plus Content-Length bytes). */
    private String readMessage() throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int consecutiveNewlines = 0;
        while (true) {
            int value = input.read();
            if (value < 0) {
                return head.size() == 0 ? null : head.toString("UTF-8");
            }
            head.write(value);
            if (value == '\n') {
                consecutiveNewlines++;
                if (consecutiveNewlines == 2) {
                    break;
                }
            } else if (value != '\r') {
                consecutiveNewlines = 0;
            }
        }
        String headers = head.toString("UTF-8");
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
            return headers;
        }
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int count = input.read(body, read, contentLength - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        return headers + new String(body, 0, read, StandardCharsets.UTF_8);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do while shutting down
        }
    }
}
