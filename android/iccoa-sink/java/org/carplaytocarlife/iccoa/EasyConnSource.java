package org.carplaytocarlife.iccoa;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The local OPPO EasyConnection source used when the phone is the only
 * Android device in the setup.
 *
 * OCar exposes its C2P control and capture sockets on loopback.  The old sink
 * only listened for a second phone on WFD/RTSP, so pressing Start could never
 * produce a frame in this one-phone arrangement.  This class performs the
 * same small, ordinary-app handshake as the tested probe and exposes each
 * length-prefixed Annex-B access unit to the CarPlay side.
 */
public final class EasyConnSource {
    private static final String OCAR_PACKAGE = "com.oplus.ocar";
    private static final String OCAR_ACTIVITY =
            "com.oplus.ocar.connect.ec.EcAccessoryFoundActivity";
    private static final int CONTROL_PORT = 10922;
    private static final int DATA_PORT = 10920;
    private static final int MEDIA_PORT = 10921;
    private static final int MAX_PACKET = 4 * 1024 * 1024;

    public interface Listener {
        void onLog(String message);
        void onFormat(int width, int height, int fps);
        void onFrame(byte[] annexB, long timestampUs, boolean keyframe);
    }

    private final Service service;
    private final Listener listener;
    private volatile boolean running;
    private Thread worker;
    private Socket control;
    private Socket reverse;
    private Socket media;
    private Socket data;

    public EasyConnSource(Service service, Listener listener) {
        this.service = service;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::runLoop, "easyconn-source");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        closeAll();
        Thread t = worker;
        if (t != null) t.interrupt();
        worker = null;
    }

    public boolean isRunning() {
        return running;
    }

    private void runLoop() {
        boolean activated = false;
        while (running) {
            try {
                if (!activated) {
                    try {
                        connectAndCapture();
                        activated = true;
                    } catch (Exception first) {
                        closeAll();
                        log("易连本地端口尚未就绪: " + message(first));
                        launchOcar();
                        activated = true;
                        sleep(1200);
                        continue;
                    }
                } else {
                    connectAndCapture();
                }
            } catch (Exception error) {
                if (running) log("易连输入已断开: " + message(error) + "; 3 秒后重试");
                closeAll();
                sleep(3000);
            }
        }
        closeAll();
        log("易连输入已停止");
    }

    private void connectAndCapture() throws Exception {
        log("连接本机易连控制端口 127.0.0.1:" + CONTROL_PORT);
        control = connect(CONTROL_PORT, 4000);
        sendEcp(control, 0x10000, new byte[0]);
        EcpPacket hello = readEcp(control);
        require(hello.command == 0x10001, "控制握手响应 0x" + hex(hello.command));

        String receiverId = service.getSharedPreferences("easyconn", Context.MODE_PRIVATE)
                .getString("receiver_id", null);
        if (receiverId == null) {
            receiverId = UUID.randomUUID().toString();
            service.getSharedPreferences("easyconn", Context.MODE_PRIVATE)
                    .edit().putString("receiver_id", receiverId).apply();
        }
        JSONObject info = new JSONObject();
        info.put("sdkVersion", "iccoa-sink");
        info.put("licenseId", "");
        info.put("pxcVersion", "iccoa-sink");
        info.put("key", "");
        info.put("uuid", "local-iccoa-sink");
        info.put("supportMic", false);
        info.put("dpi", 160);
        info.put("enableDPI", false);
        info.put("HUID", receiverId);
        info.put("HUName", "ICCOA Sink");
        info.put("channel", "default");
        info.put("carBrand", "");
        info.put("carModel", "");
        info.put("carConfig", "");
        info.put("appCheckLicense", 0);
        info.put("screenType", 1);
        info.put("package_name", service.getPackageName());
        info.put("version_code", "1");
        info.put("version_name", "0.1.0");
        info.put("transportType", 0);
        info.put("flavor", Integer.MIN_VALUE);
        byte[] body = info.toString().getBytes(StandardCharsets.UTF_8);
        sendEcp(control, 0x10010, body);
        EcpPacket phone = readEcp(control);
        require(phone.command == 0x10011, "易连客户端信息响应 0x" + hex(phone.command));
        JSONObject phoneInfo = new JSONObject(new String(phone.body, StandardCharsets.UTF_8));
        String encryptedHuid = phoneInfo.optString("encryptedHUID", "");
        if (encryptedHuid.length() == 0) throw new IOException("encryptedHUID 为空");
        log("易连控制握手成功，已取得加密 HUID");

        reverse = connect(CONTROL_PORT, 4000);
        sendEcp(reverse, 0x20000, new byte[0]);
        EcpPacket reverseHello = readEcp(reverse);
        require(reverseHello.command == 0x20001,
                "易连反向握手响应 0x" + hex(reverseHello.command));
        Thread reverseReader = new Thread(() -> drainReverse(reverse), "easyconn-reverse");
        reverseReader.setDaemon(true);
        reverseReader.start();

        media = connect(MEDIA_PORT, 4000);
        byte[] config = captureConfig(encryptedHuid);
        sendMedia(media, 0x10, config);
        MediaPacket configured = readMedia(media);
        require(configured.command == 0x11,
                "易连视频配置响应 0x" + hex(configured.command));
        int width = configured.body.length >= 8
                ? getU16(configured.body, 4) : 1280;
        int height = configured.body.length >= 8
                ? getU16(configured.body, 6) : 720;
        listener.onFormat(width, height, 30);
        sendMedia(media, 0x70, new byte[0]);
        MediaPacket started = readMedia(media);
        require(started.command == 0x71,
                "易连视频启动响应 0x" + hex(started.command));
        log("易连视频已启动: " + width + "x" + height + "@30");

        data = connect(DATA_PORT, 4000);
        DataInputStream input = new DataInputStream(data.getInputStream());
        long frame = 0;
        while (running) {
            int length;
            try {
                length = Integer.reverseBytes(input.readInt());
            } catch (EOFException eof) {
                throw eof;
            }
            if (length <= 0 || length > MAX_PACKET) {
                throw new IOException("易连视频帧长度异常: " + length);
            }
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            long timestampUs = SystemClock.elapsedRealtimeNanos() / 1000L;
            boolean keyframe = isKeyframe(bytes);
            listener.onFrame(bytes, timestampUs, keyframe);
            frame++;
            if (frame == 1 || frame % 60 == 0) log("已收到易连视频帧 " + frame + " (" + length + " bytes)");
        }
    }

    private void drainReverse(Socket socket) {
        try {
            while (running && socket != null && !socket.isClosed()) {
                EcpPacket packet = readEcp(socket);
                // 0x03000000 is OCar's heartbeat request.  Its paired command
                // is the only response needed to keep the source alive.
                if (packet.command == 0x03000000) sendEcp(socket, 0x03000001, new byte[0]);
            }
        } catch (Exception error) {
            if (running) log("易连反向控制结束: " + message(error));
        }
    }

    private void launchOcar() {
        try {
            Intent intent = new Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED");
            intent.setClassName(OCAR_PACKAGE, OCAR_ACTIVITY);
            intent.putExtra("android.hardware.usb.extra.DEVICE", diagnosticDevice());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            service.startActivity(intent);
            log("已唤起 OPPO 易连入口，等待本机服务端口");
        } catch (Throwable error) {
            log("无法唤起 OPPO 易连入口: " + message(error)
                    + "；请先手动打开亿连/无线连接");
        }
    }

    private static UsbDevice diagnosticDevice() {
        Parcel p = Parcel.obtain();
        try {
            p.writeString("/local-iccoa-sink");
            p.writeInt(0); p.writeInt(0); p.writeInt(0); p.writeInt(0); p.writeInt(0);
            p.writeString("Local ICCOA sink");
            p.writeString("Synthetic connection event");
            p.writeString("1.0");
            p.writeStrongBinder(new Binder());
            p.writeParcelableArray(new UsbConfiguration[0], 0);
            for (int i = 0; i < 5; i++) p.writeInt(0);
            p.setDataPosition(0);
            return UsbDevice.CREATOR.createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    private static byte[] captureConfig(String encryptedHuid) {
        byte[] id = encryptedHuid.getBytes(StandardCharsets.UTF_8);
        ByteBuffer config = ByteBuffer.allocate(32 + id.length).order(ByteOrder.LITTLE_ENDIAN);
        config.putShort((short) 1280).putShort((short) 720)
                .putInt(30).putInt(2).putInt(2)
                .putShort((short) 10).putShort((short) 100)
                .putInt(3000000)
                .put(new byte[] {0, 0, 1, 0, 0, 0, 0, 0})
                .put(id);
        return config.array();
    }

    private static boolean isKeyframe(byte[] bytes) {
        for (int i = 0; i + 4 < bytes.length; i++) {
            int start = 0;
            if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1) start = i + 3;
            else if (i + 4 < bytes.length && bytes[i] == 0 && bytes[i + 1] == 0
                    && bytes[i + 2] == 0 && bytes[i + 3] == 1) start = i + 4;
            if (start != 0 && start < bytes.length && (bytes[start] & 0x1f) == 5) return true;
        }
        return false;
    }

    private static Socket connect(int port, int timeout) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress("127.0.0.1", port), timeout);
            socket.setSoTimeout(8000);
            return socket;
        } catch (IOException error) {
            try { socket.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    private static void sendEcp(Socket socket, int command, byte[] body) throws IOException {
        int length = 16 + body.length;
        ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(length).putInt(command ^ length).putInt(0);
        socket.getOutputStream().write(header.array());
        if (body.length != 0) socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    private static EcpPacket readEcp(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        byte[] header = new byte[16];
        input.readFully(header);
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = h.getInt();
        int length = h.getInt();
        int xor = h.getInt();
        h.getInt();
        if (length < 16 || length > 1024 * 1024 || xor != (command ^ length)) {
            throw new IOException("易连控制包头无效");
        }
        byte[] body = new byte[length - 16];
        input.readFully(body);
        return new EcpPacket(command, body);
    }

    private static void sendMedia(Socket socket, int command, byte[] body) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) command).putShort((short) body.length).putInt(0);
        socket.getOutputStream().write(header.array());
        if (body.length != 0) socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    private static MediaPacket readMedia(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        byte[] header = new byte[8];
        input.readFully(header);
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = h.getShort() & 0xffff;
        int length = h.getShort() & 0xffff;
        h.getInt();
        if (length > 1024 * 1024) throw new IOException("易连媒体响应过大");
        byte[] body = new byte[length];
        input.readFully(body);
        return new MediaPacket(command, body);
    }

    private static int getU16(byte[] body, int offset) {
        return (body[offset] & 0xff) | ((body[offset + 1] & 0xff) << 8);
    }

    private static void require(boolean condition, String text) throws IOException {
        if (!condition) throw new IOException(text);
    }

    private void closeAll() {
        close(control); close(reverse); close(media); close(data);
        control = reverse = media = data = null;
    }

    private static void close(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { }
    }

    private void log(String message) {
        if (listener != null) listener.onLog(message);
        Log.i("ICCOASink", message);
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null ? error.toString() : text;
    }

    private static String hex(int value) {
        return String.format(java.util.Locale.US, "%08x", value);
    }

    private static final class EcpPacket {
        final int command; final byte[] body;
        EcpPacket(int command, byte[] body) { this.command = command; this.body = body; }
    }

    private static final class MediaPacket {
        final int command; final byte[] body;
        MediaPacket(int command, byte[] body) { this.command = command; this.body = body; }
    }
}
