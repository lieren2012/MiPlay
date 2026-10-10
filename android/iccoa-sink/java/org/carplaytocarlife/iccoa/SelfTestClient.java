package org.carplaytocarlife.iccoa;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback test: a fake car answers the whole AirPlay session.
 *
 * It plays the accessory over two connections - the session one and the event
 * channel - so the test covers the order the reference app uses:
 * auth-setup (here refused, which is what sends the client to pairing),
 * pair-setup, pair-verify, SETUP /setup, GET /info, POST /command,
 * SETUP / with the screen stream, and RECORD. The event channel then pushes a
 * HID report down, which has to come out as a touch.
 */
public final class SelfTestClient {

    private static int failures;

    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(0);
        final int port = server.getLocalPort();
        final CountDownLatch sessionReady = new CountDownLatch(1);
        final CountDownLatch tunnelSeen = new CountDownLatch(1);
        final CountDownLatch keyFrameAsked = new CountDownLatch(1);
        final AtomicBoolean keyFramesAccepted = new AtomicBoolean(true);
        final AtomicInteger voiceStatus = new AtomicInteger();
        final CountDownLatch eventHandlerDone = new CountDownLatch(1);
        final AtomicInteger uiUrlStatus = new AtomicInteger();
        final AtomicInteger userAgentOnInfo = new AtomicInteger();
        final AtomicInteger sessionOnAudio = new AtomicInteger();
        final AtomicInteger feedbacks = new AtomicInteger();
        final AtomicInteger feedbackSession = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<byte[]> tunnelBytes =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<byte[]> tunnelOut =
                new java.util.concurrent.atomic.AtomicReference<>();
        final AtomicInteger announcedScreenOwner = new AtomicInteger();
        final AtomicInteger ownerAfterCarTakes = new AtomicInteger();
        final AtomicInteger ownerAfterPhoneTakes = new AtomicInteger();
        final AtomicInteger entityAfterCarTakes = new AtomicInteger();
        final AtomicInteger entityAfterPhoneTakes = new AtomicInteger();
        final AtomicInteger unhandledModesStatus = new AtomicInteger();
        final AtomicInteger unknownCommandStatus = new AtomicInteger();
        final AtomicInteger touchReportStatus = new AtomicInteger();
        final AtomicInteger strangerReportStatus = new AtomicInteger();
        final AtomicInteger rejectedDeviceReportStatus = new AtomicInteger();
        final AtomicInteger keyDeviceReportStatus = new AtomicInteger();
        final CountDownLatch eventAnswered = new CountDownLatch(1);
        final CountDownLatch touchSeen = new CountDownLatch(1);
        final StringBuilder log = new StringBuilder();
        final java.util.List<String> methods = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicBoolean eventRequested = new AtomicBoolean();
        final AtomicInteger videoPort = new AtomicInteger();
        final AtomicInteger audioPort = new AtomicInteger();
        final AtomicInteger audioOffers = new AtomicInteger();
        final AtomicInteger firstOfferFormat = new AtomicInteger();

        Thread fakeCar = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        final Socket socket = server.accept();
                        final InputStream in = socket.getInputStream();
                        final OutputStream out = socket.getOutputStream();
                        final String first = readMessage(in);
                        if (first == null) {
                            continue;
                        }
                        Thread worker = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                if (first.startsWith("GET /event")) {
                                    serveEvent(first, in, out, eventRequested, touchSeen,
                                            eventAnswered, tunnelSeen, sessionReady,
                                            ownerAfterCarTakes, ownerAfterPhoneTakes,
                                            entityAfterCarTakes, entityAfterPhoneTakes,
                                            unhandledModesStatus, unknownCommandStatus,
                                            touchReportStatus, strangerReportStatus,
                                            rejectedDeviceReportStatus, keyDeviceReportStatus,
                                            eventHandlerDone, uiUrlStatus, voiceStatus);
                                } else {
                                    serveSession(first, in, out, methods, sessionReady,
                                            videoPort, audioPort, audioOffers, firstOfferFormat, tunnelOut,
                                            announcedScreenOwner, userAgentOnInfo,
                                            sessionOnAudio, feedbacks, feedbackSession);
                                }
                            }
                        }, "fake-car");
                        worker.setDaemon(true);
                        worker.start();
                    }
                } catch (Exception error) {
                    // the test closes the server when it is done
                }
            }
        }, "fake-car-accept");
        fakeCar.setDaemon(true);
        fakeCar.start();

        // ------------------------------------------------- the /info display shape
        // the bits that do not need a socket: which entry the car means, and
        // what a car that leaves the frame-rate out is taken to mean
        Map<String, Bplist.Node> panel = new LinkedHashMap<>();
        panel.put("type", Bplist.Node.integer(0));
        panel.put("widthPixels", Bplist.Node.integer(1280));
        panel.put("heightPixels", Bplist.Node.integer(720));
        Map<String, Bplist.Node> withoutCeiling = new LinkedHashMap<>();
        withoutCeiling.put("displays", Bplist.Node.array(
                java.util.Collections.singletonList(Bplist.Node.dict(panel))));
        AirplayMessages.Display read = AirplayMessages.firstDisplay(withoutCeiling);
        check("a display without maxFPS is capped at the reference's 15",
                read != null && read.maxFps == AirplayMessages.DEFAULT_MAX_FPS,
                read == null ? "no display" : String.valueOf(read.maxFps));
        check("and its pixel size is kept",
                read != null && read.width == 1280 && read.height == 720 && read.type == 0);
        Map<String, Bplist.Node> nameless = new LinkedHashMap<>();
        Map<String, Bplist.Node> panelWithoutType = new LinkedHashMap<>();
        panelWithoutType.put("widthPixels", Bplist.Node.integer(800));
        panelWithoutType.put("heightPixels", Bplist.Node.integer(480));
        nameless.put("displays", Bplist.Node.array(
                java.util.Collections.singletonList(Bplist.Node.dict(panelWithoutType))));
        check("a display that names no type is still usable",
                AirplayMessages.firstDisplay(nameless) != null
                        && AirplayMessages.firstDisplay(nameless).type == -1);
        Map<String, Bplist.Node> sizeless = new LinkedHashMap<>();
        sizeless.put("displays", Bplist.Node.array(java.util.Arrays.asList(
                Bplist.Node.string("not a display"),
                Bplist.Node.dict(new LinkedHashMap<String, Bplist.Node>()))));
        check("an entry without a pixel size is not guessed at",
                AirplayMessages.firstDisplay(sizeless) == null);
        check("no displays list means no display",
                AirplayMessages.firstDisplay(new LinkedHashMap<String, Bplist.Node>()) == null
                        && AirplayMessages.firstDisplay(null) == null);

        AirplayMessages.Identity identity =
                new AirplayMessages.Identity("00:11:22:33:44:55", "iPhone", "Android17,1");
        final AirplayClient[] clientRef = new AirplayClient[1];
        AirplayClient client = new AirplayClient("127.0.0.1", port, identity,
                new AirplayClient.Listener() {
                    @Override public void onLog(String message) {
                        log.append(message).append('\n');
                    }
                    @Override public void onState(String state) {
                        log.append("state -> ").append(state).append('\n');
                    }
                    @Override public void onSessionReady(String sessionId) {
                        log.append("session ready: ").append(sessionId).append('\n');
                        sessionReady.countDown();
                    }
                    @Override public void onTouch(HidTouch.Touch touch) {
                        log.append("car touch: ").append(touch.describe()).append('\n');
                        touchSeen.countDown();
                    }
                    @Override public boolean onKeyFrameRequested() {
                        log.append("key frame requested\n");
                        keyFrameAsked.countDown();
                        return keyFramesAccepted.get();
                    }
                    @Override public void onIapTunnel(byte[] bytes) {
                        tunnelBytes.set(bytes);
                        log.append("iAP tunnel: ").append(bytes.length).append(" bytes\n");
                        tunnelSeen.countDown();
                    }
                });
        clientRef[0] = client;
        client.start();

        boolean finished = sessionReady.await(20, TimeUnit.SECONDS);
        check("the whole session ran to RECORD", finished);
        check("the session id is kept", "FAKESESSION".equals(client.sessionId()));
        check("the car's allocated screen port is used", videoPort.get() == 7100
                || client.videoPort() == 7100, "client saw " + client.videoPort());
        check("the screen connection id is 1", client.connectionId() == 1);
        check("the audio stream was negotiated after one refusal",
                client.audioPort() == 7101 && audioOffers.get() == 2,
                "port=" + client.audioPort() + " offers=" + audioOffers.get());
        check("the format the car announced is the one offered first",
                firstOfferFormat.get() == 2048, String.valueOf(firstOfferFormat.get()));
        check("the announced format was turned down, so the fallback was taken",
                client.audioFormat() != null && client.audioFormat().format == 32768
                        && client.audioFormat().rate == 48000
                        && client.audioFormat().channels == 2,
                client.audioFormat() == null ? "none" : client.audioFormat().describe());
        check("the car's own mask was logged",
                log.toString().contains("the car announced PCM formats mask=0x800"),
                log.toString());
        // this run took the pairing path, so there is no MFiSAP shared secret and
        // therefore no audio key: the stream is agreed but the sound is skipped
        check("with no MFiSAP key the audio is skipped instead of sent unopenable",
                client.audioFormat() != null && client.audioKey() == null);
        check("the display the car reported is kept",
                client.infoWidth() == 1920 && client.infoHeight() == 720,
                client.infoWidth() + "x" + client.infoHeight());
        check("the car's own frame-rate ceiling came off that display",
                client.infoFps() == 30, String.valueOf(client.infoFps()));
        // the type-0 entry was deliberately listed second: reading the first
        // one instead would show 640x480 here
        check("the display with type 0 is the one that was taken",
                log.toString().contains("the car's display 1920x720 maxFPS=30 (displays[1].type=0)"),
                log.toString());
        check("no MFiSAP key, because the car refused auth-setup", client.auth() == null);
        check("the client said the car has no auth-setup",
                log.toString().contains("auth-setup unavailable"), log.toString());
        check("pairing ran instead", log.toString().contains("SRP proofs verified on both sides"));
        check("the session was established before /info",
                log.toString().contains("session established: FAKESESSION"), log.toString());
        check("the event channel was opened", eventRequested.get());
        // the car's commands are answered on the event thread, so allow it to get
        // there rather than sampling the latch the instant the session is up
        check("the event channel answered the car", eventAnswered.await(5, TimeUnit.SECONDS));
        check("a touch arrived over the event channel", touchSeen.await(5, TimeUnit.SECONDS)
                && log.toString().contains("car touch: touch id=1 down x=2048 y=1024"),
                log.toString());
        check("the car's descriptor was parsed and registered",
                log.toString().contains("HID device registered uuid=car-hid-1")
                        && log.toString().contains("fingers=1"), log.toString());
        check("the report was decoded through that descriptor",
                log.toString().contains("HID touch finger 0:"), log.toString());
        check("the car's key frame request came through",
                keyFrameAsked.await(5, TimeUnit.SECONDS));
        // the car's later commands arrive on the event thread, so let it finish
        // before reading what they produced
        check("the car side finished its commands",
                eventHandlerDone.await(10, TimeUnit.SECONDS));
        check("voice input is refused rather than answered as done",
                voiceStatus.get() == 501, String.valueOf(voiceStatus.get()));
        check("a device with no descriptor is skipped, not guessed at",
                log.toString().contains("came without a uuid or descriptor; skipped"),
                log.toString());
        check("a descriptor this side cannot read is declined",
                log.toString().contains("HID descriptor unsupported for car-hid-3"),
                log.toString());
        check("a report for an unknown device injects nothing",
                log.toString().contains("no descriptor registered for car-hid-4"),
                log.toString());
        check("the refusal is explained in the log",
                log.toString().contains("voice input, which is not implemented"), log.toString());
        check("the phone announced itself as the screen owner",
                announcedScreenOwner.get() == AirplayModes.OWNED_BY_PHONE,
                String.valueOf(announcedScreenOwner.get()));
        check("the car's screen request was answered", ownerAfterCarTakes.get() == 1,
                String.valueOf(ownerAfterCarTakes.get()));
        check("the car's second screen request was answered too",
                ownerAfterPhoneTakes.get() == 1,
                String.valueOf(ownerAfterPhoneTakes.get()));
        // an accepted changeModes comes back as the new modes with status 0,
        // the way the reference answers it: the car takes the screen (entity 2)
        // and then hands it back (entity 1)
        check("an accepted mode change answers with the new modes",
                entityAfterCarTakes.get() == AirplayModes.OWNED_BY_CAR,
                String.valueOf(entityAfterCarTakes.get()));
        check("and the hand-back answers with the phone owning the screen",
                entityAfterPhoneTakes.get() == AirplayModes.OWNED_BY_PHONE,
                String.valueOf(entityAfterPhoneTakes.get()));
        check("a report that decodes is answered 200",
                touchReportStatus.get() == 200, String.valueOf(touchReportStatus.get()));
        check("a report for a device the car never described is refused with 501",
                strangerReportStatus.get() == 501, String.valueOf(strangerReportStatus.get()));
        check("and so is one whose descriptor was rejected",
                rejectedDeviceReportStatus.get() == 501,
                String.valueOf(rejectedDeviceReportStatus.get()));
        check("a report for a device that is not a touch screen is refused with 501",
                keyDeviceReportStatus.get() == 501, String.valueOf(keyDeviceReportStatus.get()));
        check("a command type this side does not implement is refused with 501",
                unknownCommandStatus.get() == 501, String.valueOf(unknownCommandStatus.get()));
        check("a mode change this side cannot apply is refused with 501",
                unhandledModesStatus.get() == 501, String.valueOf(unhandledModesStatus.get()));
        check("the car saw the user agent on /info", userAgentOnInfo.get() == 1,
                String.valueOf(userAgentOnInfo.get()));
        check("the car saw the session named on the audio SETUP",
                sessionOnAudio.get() == 1, String.valueOf(sessionOnAudio.get()));
        long feedbackDeadline = System.currentTimeMillis() + 6000;
        while (feedbacks.get() == 0 && System.currentTimeMillis() < feedbackDeadline) {
            Thread.sleep(50);
        }
        check("the session keep-alive is being sent",
                feedbacks.get() >= 1, "seen " + feedbacks.get());
        check("the keep-alive names the session",
                feedbackSession.get() == 1, String.valueOf(feedbackSession.get()));
        check("the client ended up owning the screen again",
                client.modes().screen() == AirplayModes.OWNED_BY_PHONE);
        check("a UI url this side cannot serve is refused, like the reference",
                uiUrlStatus.get() == 501, String.valueOf(uiUrlStatus.get()));
        check("the requested url was logged",
                log.toString().contains("the car asked for a UI url=com.example.thing"),
                log.toString());
        check("the client reported recording", log.toString().contains("state -> recording"));
        check("the tunnel is armed once the session is up", client.tunnelArmed());
        check("the car's iAP bytes came out of the tunnel",
                tunnelSeen.await(5, TimeUnit.SECONDS) && client.tunnelBytesIn() > 0,
                "bytes=" + client.tunnelBytesIn());
        byte[] inbound = tunnelBytes.get();
        check("the tunnel handed over exactly the car's bytes",
                inbound != null && inbound.length == client.tunnelBytesIn()
                        && inbound[0] == 0x40 && inbound[1] == 0x40,
                inbound == null ? "none" : Logs.hex(inbound, Math.min(8, inbound.length)));
        byte[] outbound = Iap2.control(CarplayBootstrap.MSG_REQUEST_IDENTIFICATION,
                new java.util.ArrayList<Iap2.Parameter>());
        check("a reply goes back over the tunnel", client.tunnelIap(outbound));
        long deadline = System.currentTimeMillis() + 3000;
        while (tunnelOut.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        check("the reply carried the iAPSendMessage envelope",
                tunnelOut.get() != null && java.util.Arrays.equals(tunnelOut.get(), outbound),
                tunnelOut.get() == null ? "nothing" : "different bytes");
        check("the tunnel counted what it sent", client.tunnelBytesOut() == outbound.length,
                String.valueOf(client.tunnelBytesOut()));

        String expected = "[POST /auth-setup, POST /pair-setup, POST /pair-setup, POST /pair-setup, "
                + "POST /pair-verify, POST /pair-verify, SETUP /setup, GET /info, POST /command, "
                + "SETUP /, SETUP /, SETUP /, RECORD /, POST /command]";
        // the keep-alive runs on its own timer, so it may land anywhere after
        // RECORD; the protocol order is what this checks
        java.util.List<String> protocol = new java.util.ArrayList<>();
        for (String method : methods) {
            if (!"POST /feedback".equals(method)) {
                protocol.add(method);
            }
        }
        check("the request order matches the reference flow",
                expected.equals(protocol.toString()), protocol.toString());

        client.stop();
        server.close();
        System.out.println("---- client log ----\n" + log);
        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST CLIENT OK"
                : ("SELFTEST CLIENT FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Serves the session connection, in the order the client asks. */
    private static void serveSession(String first, InputStream in, OutputStream out,
            java.util.List<String> methods, CountDownLatch sessionReady, AtomicInteger videoPort,
            AtomicInteger audioPort, AtomicInteger audioOffers, AtomicInteger firstOfferFormat,
            java.util.concurrent.atomic.AtomicReference<byte[]> tunnelOut,
            AtomicInteger announcedScreenOwner, AtomicInteger userAgentOnInfo,
            AtomicInteger sessionOnAudio, AtomicInteger feedbacks,
            AtomicInteger feedbackSession) {
        Accessory accessory = new Accessory();
        try {
            String request = first;
            while (request != null) {
                String[] parts = request.split("\\s+", 3);
                String method = parts[0];
                String uri = parts.length > 1 ? parts[1] : "";
                methods.add(method + " " + uri);
                int cseq = cseqOf(request);
                if ("POST".equals(method) && uri.startsWith("/auth-setup")) {
                    // a receiver without the MFi endpoint answers 404
                    reply(out, 404, "Not Found", cseq, null, null);
                    request = readMessage(in);
                    continue;
                }
                if ("POST".equals(method) && uri.startsWith("/pair-")) {
                    // The pairing endpoints speak TLV8, and only under the headers
                    // the reference sends: a body in any other shape is refused the
                    // way a real accessory refuses it - so a client that goes back
                    // to plists fails this test instead of passing it.
                    String lower = request.toLowerCase(java.util.Locale.ROOT);
                    boolean shaped = lower.contains("application/pairing+tlv8")
                            && lower.contains("x-apple-hkp: 2")
                            && (!uri.startsWith("/pair-verify")
                                    || lower.contains("x-apple-pd: 1"));
                    if (!shaped) {
                        reply(out, 470, "Bad Request", cseq, null, null);
                        request = readMessage(in);
                        continue;
                    }
                    Map<Integer, byte[]> answer = accessory.answer(uri,
                            Tlv8.decode(tlvBody(request)));
                    if (answer == null) {
                        reply(out, 200, "OK", cseq, null, null);
                    } else {
                        pairingReply(out, 200, "OK", cseq, Tlv8.encode(answer));
                    }
                    request = readMessage(in);
                    continue;
                }
                if ("SETUP".equals(method) && "/setup".equals(uri)) {
                    reply(out, 200, "OK", cseq, "FAKESESSION", plist(new LinkedHashMap<>()));
                    request = readMessage(in);
                    continue;
                }
                if ("GET".equals(method) && "/info".equals(uri)) {
                    userAgentOnInfo.set(headerValue(request, "User-Agent") != null
                            && headerValue(request, "User-Agent").startsWith("AirPlay/") ? 1 : -1);
                    Map<String, Bplist.Node> info = new LinkedHashMap<>();
                    // The shape the reference reads: a list of displays, each with
                    // its pixel size, a type (0 = the screen this phone drives) and
                    // a frame-rate ceiling. The type-0 one is listed second on
                    // purpose, so taking the first entry instead would be caught.
                    Map<String, Bplist.Node> sparePanel = new LinkedHashMap<>();
                    sparePanel.put("type", Bplist.Node.integer(1));
                    sparePanel.put("widthPixels", Bplist.Node.integer(640));
                    sparePanel.put("heightPixels", Bplist.Node.integer(480));
                    Map<String, Bplist.Node> mainPanel = new LinkedHashMap<>();
                    mainPanel.put("type", Bplist.Node.integer(0));
                    mainPanel.put("widthPixels", Bplist.Node.integer(1920));
                    mainPanel.put("heightPixels", Bplist.Node.integer(720));
                    mainPanel.put("maxFPS", Bplist.Node.integer(30));
                    info.put("displays", Bplist.Node.array(java.util.Arrays.asList(
                            Bplist.Node.dict(sparePanel), Bplist.Node.dict(mainPanel))));
                    // and the audio formats it can decode, as the mask the car uses:
                    // only 2048 (PCM/44100/2ch) is set, so that is the offer the
                    // client should put first
                    Map<String, Bplist.Node> announcedAudio = new LinkedHashMap<>();
                    announcedAudio.put("type",
                            Bplist.Node.integer(AirplayMessages.AUDIO_STREAM_TYPE));
                    announcedAudio.put("audioType",
                            Bplist.Node.string(AirplayMessages.AUDIO_TYPE_MEDIA));
                    announcedAudio.put("audioOutputFormats", Bplist.Node.integer(2048));
                    info.put("streams", Bplist.Node.array(
                            java.util.Collections.singletonList(Bplist.Node.dict(announcedAudio))));
                    reply(out, 200, "OK", cseq, null, plist(info));
                    request = readMessage(in);
                    continue;
                }
                if ("POST".equals(method) && "/command".equals(uri)) {
                    recordIapEnvelope(request, tunnelOut);
                    recordModesShape(request, announcedScreenOwner);
                    reply(out, 200, "OK", cseq, null, plist(new LinkedHashMap<>()));
                    request = readMessage(in);
                    continue;
                }
                if ("SETUP".equals(method) && "/".equals(uri)) {
                    long requestedType = requestedStreamType(request);
                    if (requestedType == AirplayMessages.AUDIO_STREAM_TYPE) {
                        sessionOnAudio.set("FAKESESSION".equals(
                                headerValue(request, "Session")) ? 1 : -1);
                        if (audioOffers.incrementAndGet() == 1) {
                            // the car turns the first PCM offer down, and the test
                            // remembers which format that was
                            firstOfferFormat.set((int) offeredFormat(request));
                            reply(out, 400, "Bad Request", cseq, null, null);
                        } else {
                            Map<String, Bplist.Node> stream = new LinkedHashMap<>();
                            stream.put("type", Bplist.Node.integer(
                                    AirplayMessages.AUDIO_STREAM_TYPE));
                            stream.put("dataPort", Bplist.Node.integer(7101));
                            stream.put("streamConnectionID", Bplist.Node.integer(2));
                            Map<String, Bplist.Node> body = new LinkedHashMap<>();
                            body.put("streams", Bplist.Node.array(
                                    java.util.Collections.singletonList(Bplist.Node.dict(stream))));
                            audioPort.set(7101);
                            reply(out, 200, "OK", cseq, null, plist(body));
                        }
                    } else {
                        Map<String, Bplist.Node> stream = new LinkedHashMap<>();
                        stream.put("type", Bplist.Node.integer(
                                AirplayMessages.SCREEN_STREAM_TYPE));
                        stream.put("dataPort", Bplist.Node.integer(7100));
                        Map<String, Bplist.Node> body = new LinkedHashMap<>();
                        body.put("streams", Bplist.Node.array(
                                java.util.Collections.singletonList(Bplist.Node.dict(stream))));
                        videoPort.set(7100);
                        reply(out, 200, "OK", cseq, null, plist(body));
                    }
                    request = readMessage(in);
                    continue;
                }
                if ("POST".equals(method) && "/feedback".equals(uri)) {
                    feedbacks.incrementAndGet();
                    feedbackSession.set("FAKESESSION".equals(headerValue(request, "Session"))
                            ? 1 : -1);
                    reply(out, 200, "OK", cseq, null, null);
                    request = readMessage(in);
                    continue;
                }
                if ("RECORD".equals(method)) {
                    reply(out, 200, "OK", cseq, "FAKESESSION", null);
                    sessionReady.countDown();
                    request = readMessage(in);
                    continue;
                }
                reply(out, 200, "OK", cseq, null, null);
                request = readMessage(in);
            }
        } catch (Exception error) {
            // the client hanging up ends the loop
        }
    }

    /**
     * Serves the event channel: answers GET /event, then pushes a command with a
     * six byte HID report, which the client has to turn into a touch.
     */
    private static void serveEvent(String first, InputStream in, OutputStream out,
            AtomicBoolean requested, CountDownLatch touchSeen, CountDownLatch answered,
            CountDownLatch tunnelSeen, CountDownLatch sessionReady,
            AtomicInteger ownerAfterCarTakes, AtomicInteger ownerAfterPhoneTakes,
            AtomicInteger entityAfterCarTakes, AtomicInteger entityAfterPhoneTakes,
            AtomicInteger unhandledModesStatus, AtomicInteger unknownCommandStatus,
            AtomicInteger touchReportStatus, AtomicInteger strangerReportStatus,
            AtomicInteger rejectedDeviceReportStatus, AtomicInteger keyDeviceReportStatus,
            CountDownLatch done, AtomicInteger uiUrlStatus, AtomicInteger voiceStatus) {
        try {
            requested.set(true);
            reply(out, 200, "OK", cseqOf(first), "FAKESESSION", null);
            // the car hands over its HID device, descriptor included
            Map<String, Bplist.Node> device = new LinkedHashMap<>();
            device.put("uuid", Bplist.Node.string("car-hid-1"));
            device.put("name", Bplist.Node.string("Test touch"));
            device.put("hidDescriptor", Bplist.Node.data(HidTouch.DESCRIPTOR));
            Map<String, Bplist.Node> requestUi = new LinkedHashMap<>();
            requestUi.put("hidDevices", Bplist.Node.array(
                    java.util.Collections.singletonList(Bplist.Node.dict(device))));
            send(out, 77, envelopeOf("requestUI", requestUi));
            readMessage(in);
            // and then a report that only its descriptor can decode
            byte[] report = new byte[] {1, 1, 0x00, 0x08, 0x00, 0x04};
            Map<String, Bplist.Node> hid = new LinkedHashMap<>();
            hid.put("uuid", Bplist.Node.string("car-hid-1"));
            hid.put("hidReport", Bplist.Node.data(report));
            send(out, 78, envelopeOf("hidSendReport", hid));
            String answer = readReply(in, 78);
            touchReportStatus.set(statusOf(answer));
            if (answer != null) {
                answered.countDown();
            }
            // the car only hands the conversation over once the session is up
            sessionReady.await(10, TimeUnit.SECONDS);
            Thread.sleep(200);
            byte[] iap = Iap2.control(CarplayBootstrap.MSG_WIRELESS_REQUEST,
                    new java.util.ArrayList<Iap2.Parameter>());
            Map<String, Bplist.Node> params = new LinkedHashMap<>();
            params.put("data", Bplist.Node.data(iap));
            Map<String, Bplist.Node> envelope = new LinkedHashMap<>();
            envelope.put("type", Bplist.Node.string("iAPSendMessage"));
            envelope.put("params", Bplist.Node.dict(params));
            send(out, 80, envelope);
            readMessage(in);
            send(out, 79, envelopeOf("forceKeyFrame", new LinkedHashMap<>()));
            readMessage(in);
            send(out, 84, envelopeOf("requestSiri", new LinkedHashMap<>()));
            String voiceReply = readReply(in, 84);
            voiceStatus.set(voiceReply == null ? 0
                    : (voiceReply.startsWith("RTSP/1.0 501") ? 501 : 200));
            // the car takes the screen resource, then gives it back
            Map<String, Bplist.Node> take = new LinkedHashMap<>();
            Map<String, Bplist.Node> takeParams = new LinkedHashMap<>();
            takeParams.put("resources", Bplist.Node.array(java.util.Collections.singletonList(
                    Bplist.Node.dict(transfer(AirplayModes.TRANSFER_CAR_TAKES)))));
            take.put("params", Bplist.Node.dict(takeParams));
            send(out, 81, envelopeOf("changeModes", take));
            String takeReply = readReply(in, 81);
            ownerAfterCarTakes.set(takeReply == null ? -1 : 1);
            entityAfterCarTakes.set(acceptedEntity(takeReply));
            Map<String, Bplist.Node> give = new LinkedHashMap<>();
            Map<String, Bplist.Node> giveParams = new LinkedHashMap<>();
            giveParams.put("resources", Bplist.Node.array(java.util.Collections.singletonList(
                    Bplist.Node.dict(transfer(AirplayModes.TRANSFER_PHONE_TAKES)))));
            give.put("params", Bplist.Node.dict(giveParams));
            send(out, 82, envelopeOf("changeModes", give));
            String giveReply = readReply(in, 82);
            ownerAfterPhoneTakes.set(giveReply == null ? -1 : 1);
            entityAfterPhoneTakes.set(acceptedEntity(giveReply));
            // a transfer this side cannot apply: it has to be refused, and the
            // refusal has to be the reference's 501 - the screen owner must not
            // change just because the car asked for something else
            Map<String, Bplist.Node> strange = new LinkedHashMap<>();
            Map<String, Bplist.Node> strangeParams = new LinkedHashMap<>();
            strangeParams.put("resources", Bplist.Node.array(
                    java.util.Collections.singletonList(Bplist.Node.dict(transfer(7)))));
            strange.put("params", Bplist.Node.dict(strangeParams));
            send(out, 88, envelopeOf("changeModes", strange));
            String strangeReply = readReply(in, 88);
            unhandledModesStatus.set(strangeReply == null ? 0
                    : (strangeReply.startsWith("RTSP/1.0 501") ? 501 : 200));
            // the car asks for a UI this side cannot serve
            Map<String, Bplist.Node> uiRequest = new LinkedHashMap<>();
            uiRequest.put("url", Bplist.Node.string("com.example.thing"));
            // a device the car describes badly, and a report for a device it
            // never described: both have to be declined without guessing
            Map<String, Bplist.Node> halfDevice = new LinkedHashMap<>();
            halfDevice.put("uuid", Bplist.Node.string("car-hid-2"));
            Map<String, Bplist.Node> badRequest = new LinkedHashMap<>();
            badRequest.put("hidDevices", Bplist.Node.array(java.util.Collections.singletonList(
                    Bplist.Node.dict(halfDevice))));
            send(out, 85, envelopeOf("requestUI", badRequest));
            readReply(in, 85);
            Map<String, Bplist.Node> unsupported = new LinkedHashMap<>();
            unsupported.put("uuid", Bplist.Node.string("car-hid-3"));
            unsupported.put("hidDescriptor", Bplist.Node.data(new byte[] {
                0x05, 0x0D, 0x09, 0x04, (byte) 0xA1, 0x01, (byte) 0x85, 0x01, 0x09, 0x51,
                0x15, 0x00, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
                (byte) 0xC0}));
            Map<String, Bplist.Node> unsupportedRequest = new LinkedHashMap<>();
            unsupportedRequest.put("hidDevices", Bplist.Node.array(
                    java.util.Collections.singletonList(Bplist.Node.dict(unsupported))));
            send(out, 86, envelopeOf("requestUI", unsupportedRequest));
            readReply(in, 86);
            Map<String, Bplist.Node> stranger = new LinkedHashMap<>();
            stranger.put("uuid", Bplist.Node.string("car-hid-4"));
            stranger.put("hidReport", Bplist.Node.data(new byte[] {1, 1, 0, 8, 0, 4}));
            send(out, 87, envelopeOf("hidSendReport", stranger));
            strangerReportStatus.set(statusOf(readReply(in, 87)));
            // a report for a device whose descriptor this side rejected: there is
            // nothing to decode it with, and the reference answers 501 for that
            Map<String, Bplist.Node> rejectedReport = new LinkedHashMap<>();
            rejectedReport.put("uuid", Bplist.Node.string("car-hid-3"));
            rejectedReport.put("hidReport", Bplist.Node.data(new byte[] {1, 1, 0, 8, 0, 4}));
            send(out, 90, envelopeOf("hidSendReport", rejectedReport));
            rejectedDeviceReportStatus.set(statusOf(readReply(in, 90)));
            // a device whose descriptor parses but describes no touch screen: the
            // reference has a key decoder for those, this side does not, so its
            // reports are answered 501 rather than dropped silently
            Map<String, Bplist.Node> keyboard = new LinkedHashMap<>();
            keyboard.put("uuid", Bplist.Node.string("car-hid-5"));
            keyboard.put("name", Bplist.Node.string("Test keys"));
            // a consumer-control button: absolute and variable (so the parser
            // takes it) but with no X/Y fields, which makes it a key device
            keyboard.put("hidDescriptor", Bplist.Node.data(new byte[] {
                0x05, 0x0C, 0x09, 0x01, (byte) 0xA1, 0x01, 0x05, 0x0C, 0x09, (byte) 0xE9,
                0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x01, (byte) 0x81, 0x02,
                (byte) 0xC0}));
            Map<String, Bplist.Node> keyboardRequest = new LinkedHashMap<>();
            keyboardRequest.put("hidDevices", Bplist.Node.array(
                    java.util.Collections.singletonList(Bplist.Node.dict(keyboard))));
            send(out, 91, envelopeOf("requestUI", keyboardRequest));
            readReply(in, 91);
            Map<String, Bplist.Node> keyReport = new LinkedHashMap<>();
            keyReport.put("uuid", Bplist.Node.string("car-hid-5"));
            keyReport.put("hidReport", Bplist.Node.data(new byte[] {0x01}));
            send(out, 92, envelopeOf("hidSendReport", keyReport));
            keyDeviceReportStatus.set(statusOf(readReply(in, 92)));
            // a command type this side does not implement: the reference answers
            // 501 for one of those, so the same answer has to go out here
            send(out, 89, envelopeOf("somethingNew", new LinkedHashMap<>()));
            String unknownReply = readReply(in, 89);
            unknownCommandStatus.set(unknownReply == null ? 0
                    : (unknownReply.startsWith("RTSP/1.0 501") ? 501 : 200));
            send(out, 83, envelopeOf("requestUI", uiRequest));
            String uiReply = readReply(in, 83);
            uiUrlStatus.set(uiReply == null ? 0
                    : (uiReply.startsWith("RTSP/1.0 501") ? 501 : 200));
            tunnelSeen.await(5, TimeUnit.SECONDS);
        } catch (Exception error) {
            // the client closing the channel ends it
        } finally {
            done.countDown();
        }
    }

    /**
     * Reads until the reply for {@code expectedCseq} arrives.
     *
     * The phone may answer something else in between (a tunnel command of its
     * own, for instance), so a real client matches on CSeq instead of assuming
     * the next message is its answer.
     */
    private static String readReply(InputStream in, int expectedCseq) throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            String message = readMessage(in);
            if (message == null) {
                return null;
            }
            if (message.startsWith("RTSP/1.0") && cseqOf(message) == expectedCseq) {
                return message;
            }
        }
        return null;
    }

    private static void reply(OutputStream out, int status, String reason, int cseq,
            String session, byte[] payload) throws Exception {
        StringBuilder head = new StringBuilder("RTSP/1.0 " + status + " " + reason + "\r\n")
                .append("CSeq: ").append(cseq).append("\r\n");
        if (session != null) {
            head.append("Session: ").append(session).append(";timeout=60\r\n");
        }
        if (payload != null) {
            head.append("Content-Type: application/x-apple-binary-plist\r\n")
                    .append("Content-Length: ").append(payload.length).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (payload != null) {
            out.write(payload);
        }
        out.flush();
    }

    /** The bytes of a request body, which for pairing is a TLV block. */
    private static byte[] tlvBody(String request) {
        int split = request.indexOf("\r\n\r\n");
        if (split < 0) {
            throw new IllegalArgumentException("pairing request carried no body");
        }
        return request.substring(split + 4).getBytes(StandardCharsets.ISO_8859_1);
    }

    /** A pairing reply: same as a normal one, with the TLV8 content type. */
    private static void pairingReply(OutputStream out, int status, String reason, int cseq,
            byte[] payload) throws Exception {
        StringBuilder head = new StringBuilder("RTSP/1.0 " + status + " " + reason + "\r\n")
                .append("CSeq: ").append(cseq).append("\r\n")
                .append("Content-Type: ").append(AirplayMessages.PAIRING_CONTENT_TYPE)
                .append("\r\n")
                .append("Content-Length: ").append(payload.length).append("\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(payload);
        out.flush();
    }

    private static Map<String, Bplist.Node> transfer(int transferType) {
        Map<String, Bplist.Node> resource = new LinkedHashMap<>();
        resource.put("resourceID", Bplist.Node.integer(AirplayModes.RESOURCE_SCREEN));
        resource.put("transferType", Bplist.Node.integer(transferType));
        return resource;
    }

    /** Stores the screen owner the phone announced in its modesChanged command. */
    private static void recordModesShape(String request, AtomicInteger owner) {
        try {
            int split = request.indexOf("\r\n\r\n");
            if (split < 0) {
                return;
            }
            Map<String, Bplist.Node> body = Bplist.decode(
                    request.substring(split + 4).getBytes(StandardCharsets.ISO_8859_1)).asDict();
            Bplist.Node type = body.get("type");
            if (type == null || !"modesChanged".equals(type.asString())) {
                return;
            }
            owner.set((int) body.get("params").asDict().get("resources").asArray().get(0)
                    .asDict().get("entity").asInteger());
        } catch (RuntimeException notAPlist) {
            // not the modes command
        }
    }

    /** The status code of a reply line, or 0 when there was no reply. */
    private static int statusOf(String reply) {
        if (reply == null || !reply.startsWith("RTSP/1.0 ")) {
            return 0;
        }
        try {
            return Integer.parseInt(reply.substring(9, 12));
        } catch (RuntimeException notAStatus) {
            return 0;
        }
    }

    /**
     * The screen entity in an accepted changeModes reply, or -1 when the reply
     * is not the {@code {status:0, params:...}} shape the reference answers with.
     */
    private static int acceptedEntity(String reply) {
        if (reply == null || !reply.startsWith("RTSP/1.0 200")) {
            return -1;
        }
        int split = reply.indexOf("\r\n\r\n");
        if (split < 0) {
            return -1;
        }
        try {
            Map<String, Bplist.Node> body = AirplayMessages.plistBody(reply.substring(split + 4));
            if (body.get("status") == null || body.get("status").asInteger() != 0) {
                return -1;
            }
            return (int) body.get("params").asDict().get("resources").asArray().get(0)
                    .asDict().get("entity").asInteger();
        } catch (RuntimeException notAPlist) {
            return -1;
        }
    }

    /** Sends one command envelope down an event channel. */
    private static void send(OutputStream out, int cseq, Map<String, Bplist.Node> envelope)
            throws Exception {
        byte[] payload = plist(envelope);
        out.write(("POST /command RTSP/1.0\r\nCSeq: " + cseq + "\r\n"
                + "Content-Type: application/x-apple-binary-plist\r\nContent-Length: "
                + payload.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(payload);
        out.flush();
    }

    /** A command envelope with a type and a plist body. */
    private static Map<String, Bplist.Node> envelopeOf(String type,
            Map<String, Bplist.Node> body) {
        Map<String, Bplist.Node> envelope = new LinkedHashMap<>(body);
        envelope.put("type", Bplist.Node.string(type));
        return envelope;
    }

    /** Stores the iAP bytes of an outbound iAPSendMessage, if this is one. */
    private static void recordIapEnvelope(String request,
            java.util.concurrent.atomic.AtomicReference<byte[]> out) {
        try {
            int split = request.indexOf("\r\n\r\n");
            if (split < 0) {
                return;
            }
            Map<String, Bplist.Node> body = Bplist.decode(
                    request.substring(split + 4).getBytes(StandardCharsets.ISO_8859_1)).asDict();
            Bplist.Node type = body.get("type");
            if (type == null || !"iAPSendMessage".equals(type.asString())) {
                return;
            }
            Bplist.Node data = body.get("params").asDict().get("data");
            if (data != null) {
                out.set(data.asData());
            }
        } catch (RuntimeException notAPlist) {
            // not an envelope
        }
    }

    /** One header of a request, or null when it is missing. */
    private static String headerValue(String request, String name) {
        for (String line : request.replace("\r\n", "\n").split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** Reads the stream type a SETUP / asks for, or 0 when it asks for none. */
    /** The audioFormat the phone put in the first stream of its SETUP. */
    private static long offeredFormat(String request) {
        try {
            int split = request.indexOf("\r\n\r\n");
            if (split < 0) {
                return 0;
            }
            Map<String, Bplist.Node> body = Bplist.decode(
                    request.substring(split + 4).getBytes(StandardCharsets.ISO_8859_1)).asDict();
            Bplist.Node streams = body.get("streams");
            if (streams == null || streams.asArray().isEmpty()) {
                return 0;
            }
            Bplist.Node format = streams.asArray().get(0).asDict().get("audioFormat");
            return format == null ? 0 : format.asInteger();
        } catch (RuntimeException notAPlist) {
            return 0;
        }
    }

    private static long requestedStreamType(String request) {
        try {
            int split = request.indexOf("\r\n\r\n");
            if (split < 0) {
                return 0;
            }
            Map<String, Bplist.Node> body = Bplist.decode(
                    request.substring(split + 4).getBytes(StandardCharsets.ISO_8859_1)).asDict();
            Bplist.Node streams = body.get("streams");
            if (streams == null || streams.asArray().isEmpty()) {
                return 0;
            }
            Bplist.Node type = streams.asArray().get(0).asDict().get("type");
            return type == null ? 0 : type.asInteger();
        } catch (RuntimeException notAPlist) {
            return 0;
        }
    }

    private static int cseqOf(String message) {
        for (String line : message.replace("\r\n", "\n").split("\n")) {
            if (line.toLowerCase().startsWith("cseq:")) {
                try {
                    return Integer.parseInt(line.substring(5).trim());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /** The accessory (car) side of the pairing exchange. */
    private static final class Accessory {
        private final byte[] salt = new byte[16];
        private final BigInteger b;
        private final BigInteger verifier;
        private byte[] sessionKey;
        private BigInteger controllerPublicA;

        Accessory() {
            new java.security.SecureRandom().nextBytes(salt);
            b = new BigInteger(256, new java.security.SecureRandom());
            verifier = AirplayCrypto.srpVerifier(salt, AirplayCrypto.SRP_USER,
                    AirplayCrypto.SRP_PASSWORD);
        }

        /**
         * Answers one pairing step, the way a real accessory does.
         *
         * Everything here is TLV8 with the state item checked, and each blob is
         * sealed and opened under its own label - the two things a plist-based
         * fake accessory would silently let a broken client get away with.
         */
        Map<Integer, byte[]> answer(String uri, Map<Integer, byte[]> items) {
            int state = Tlv8.byteOf(items, Tlv8.STATE);
            if (uri.startsWith("/pair-setup")) {
                if (state == 1) {
                    if (Tlv8.byteOf(items, Tlv8.METHOD) != 1) {
                        return error(Tlv8.ERROR);
                    }
                    Map<Integer, byte[]> reply = new java.util.LinkedHashMap<>();
                    reply.put(Tlv8.STATE, Tlv8.byteValue(2));
                    reply.put(Tlv8.SALT, salt);
                    reply.put(Tlv8.PUBLIC_KEY,
                            AirplayCrypto.pad(AirplayCrypto.srpServerPublic(verifier, b)));
                    return reply;
                }
                if (state == 3) {
                    controllerPublicA = new BigInteger(1, Tlv8.value(items, Tlv8.PUBLIC_KEY, 384));
                    BigInteger secret = AirplayCrypto.srpServerSecret(verifier, b, controllerPublicA);
                    sessionKey = AirplayCrypto.hkdfSha512(AirplayCrypto.pad(secret),
                            AirplayCrypto.SALT_PAIR_SETUP_ENCRYPT,
                            AirplayCrypto.INFO_PAIR_SETUP_ENCRYPT, 32);
                    byte[] k = AirplayCrypto.sha512(AirplayCrypto.pad(secret));
                    byte[] hN = AirplayCrypto.sha512(AirplayCrypto.pad(AirplayCrypto.SRP_N));
                    byte[] hG = AirplayCrypto.sha512(AirplayCrypto.pad(AirplayCrypto.SRP_G));
                    byte[] hI = AirplayCrypto.sha512(AirplayCrypto.SRP_USER.getBytes());
                    byte[] xor = new byte[hN.length];
                    for (int i = 0; i < xor.length; i++) {
                        xor[i] = (byte) (hN[i] ^ hG[i]);
                    }
                    byte[] m1 = AirplayCrypto.sha512(xor, hI, salt,
                            AirplayCrypto.pad(controllerPublicA),
                            AirplayCrypto.pad(AirplayCrypto.srpServerPublic(verifier, b)), k);
                    Map<Integer, byte[]> reply = new java.util.LinkedHashMap<>();
                    reply.put(Tlv8.STATE, Tlv8.byteValue(4));
                    reply.put(Tlv8.PROOF, AirplayCrypto.sha512(
                            AirplayCrypto.pad(controllerPublicA), m1, k));
                    return reply;
                }
                if (state == 5) {
                    byte[] opened = AirplayPairing.open(sessionKey, AirplayPairing.PS_MSG05,
                            Tlv8.value(items, Tlv8.ENCRYPTED_DATA, 0));
                    if (opened == null) {
                        return error(Tlv8.ERROR);
                    }
                    Map<Integer, byte[]> controller = Tlv8.decode(opened);
                    controllerId = Tlv8.value(controller, Tlv8.IDENTIFIER, 0);
                    controllerKey = Tlv8.value(controller, Tlv8.PUBLIC_KEY, 32);
                    byte[] signInfo = AirplayCrypto.hkdfSha512(sessionKey,
                            AirplayCrypto.SALT_CONTROLLER_SIGN, AirplayCrypto.INFO_CONTROLLER_SIGN, 32);
                    if (!AirplayCrypto.ed25519Verify(controllerKey,
                            AirplayCrypto.concat(signInfo, controllerId, controllerKey),
                            Tlv8.value(controller, Tlv8.SIGNATURE, 0))) {
                        return error(Tlv8.ERROR);
                    }
                    byte[] seed = new byte[32];
                    byte[] id = new byte[16];
                    new java.security.SecureRandom().nextBytes(seed);
                    new java.security.SecureRandom().nextBytes(id);
                    accessoryId = id;
                    accessorySeed = seed;
                    byte[] accessoryKey = AirplayCrypto.ed25519PublicKey(seed);
                    byte[] accessorySignInfo = AirplayCrypto.hkdfSha512(sessionKey,
                            AirplayCrypto.SALT_ACCESSORY_SIGN, AirplayCrypto.INFO_ACCESSORY_SIGN, 32);
                    Map<Integer, byte[]> ours = new java.util.LinkedHashMap<>();
                    ours.put(Tlv8.IDENTIFIER, id);
                    ours.put(Tlv8.PUBLIC_KEY, accessoryKey);
                    ours.put(Tlv8.SIGNATURE, AirplayCrypto.ed25519Sign(seed,
                            AirplayCrypto.concat(accessorySignInfo, id, accessoryKey)));
                    Map<Integer, byte[]> reply = new java.util.LinkedHashMap<>();
                    reply.put(Tlv8.STATE, Tlv8.byteValue(6));
                    reply.put(Tlv8.ENCRYPTED_DATA, AirplayPairing.seal(sessionKey,
                            AirplayPairing.PS_MSG06, Tlv8.encode(ours)));
                    return reply;
                }
                return null;
            }
            if (uri.startsWith("/pair-verify")) {
                if (state == 1) {
                    byte[] scalar = new byte[32];
                    new java.security.SecureRandom().nextBytes(scalar);
                    ephemeralScalar = scalar;
                    ephemeralPublic = AirplayCrypto.x25519PublicKey(scalar);
                    controllerVerifyPublic = Tlv8.value(items, Tlv8.PUBLIC_KEY, 32);
                    verifyKey = AirplayCrypto.hkdfSha512(
                            AirplayCrypto.x25519SharedSecret(scalar, controllerVerifyPublic),
                            AirplayCrypto.SALT_PAIR_VERIFY_ENCRYPT,
                            AirplayCrypto.INFO_PAIR_VERIFY_ENCRYPT, 32);
                    byte[] seed = accessorySeed;
                    Map<Integer, byte[]> proof = new java.util.LinkedHashMap<>();
                    proof.put(Tlv8.IDENTIFIER, accessoryId);
                    proof.put(Tlv8.SIGNATURE, AirplayCrypto.ed25519Sign(seed,
                            AirplayCrypto.concat(ephemeralPublic, accessoryId,
                                    controllerVerifyPublic)));
                    Map<Integer, byte[]> reply = new java.util.LinkedHashMap<>();
                    reply.put(Tlv8.STATE, Tlv8.byteValue(2));
                    reply.put(Tlv8.PUBLIC_KEY, ephemeralPublic);
                    reply.put(Tlv8.ENCRYPTED_DATA, AirplayPairing.seal(verifyKey,
                            AirplayPairing.PV_MSG02, Tlv8.encode(proof)));
                    return reply;
                }
                if (state == 3) {
                    byte[] opened = AirplayPairing.open(verifyKey, AirplayPairing.PV_MSG03,
                            Tlv8.value(items, Tlv8.ENCRYPTED_DATA, 0));
                    if (opened == null) {
                        return error(Tlv8.ERROR);
                    }
                    Map<Integer, byte[]> proof = Tlv8.decode(opened);
                    byte[] controllerProofId = Tlv8.value(proof, Tlv8.IDENTIFIER, 0);
                    if (!java.util.Arrays.equals(controllerProofId, controllerId)
                            || !AirplayCrypto.ed25519Verify(controllerKey,
                                    AirplayCrypto.concat(controllerVerifyPublic, controllerId,
                                            ephemeralPublic),
                                    Tlv8.value(proof, Tlv8.SIGNATURE, 0))) {
                        return error(Tlv8.ERROR);
                    }
                    Map<Integer, byte[]> reply = new java.util.LinkedHashMap<>();
                    reply.put(Tlv8.STATE, Tlv8.byteValue(4));
                    return reply;
                }
                return null;
            }
            return null;
        }

        /** A TLV error item, which is how the pairing endpoints refuse. */
        private Map<Integer, byte[]> error(int code) {
            Map<Integer, byte[]> reply = new java.util.LinkedHashMap<>();
            reply.put(Tlv8.ERROR, Tlv8.byteValue(code));
            return reply;
        }
        private byte[] ephemeralScalar;
        private byte[] ephemeralPublic;
        private byte[] controllerVerifyPublic;
        private byte[] verifyKey;
        private byte[] controllerId;
        private byte[] controllerKey;
        private byte[] accessoryId;
        private byte[] accessorySeed;
    }

    private static byte[] plist(Map<String, Bplist.Node> body) {
        return Bplist.encode(Bplist.Node.dict(body));
    }

    private static Map<String, Bplist.Node> plistBody(String request) {
        int split = request.indexOf("\r\n\r\n");
        if (split < 0) {
            return new java.util.LinkedHashMap<>();
        }
        String body = request.substring(split + 4);
        if (body.isEmpty()) {
            return new java.util.LinkedHashMap<>();
        }
        return Bplist.decode(body.getBytes(StandardCharsets.ISO_8859_1)).asDict();
    }

    private static String readMessage(InputStream in) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int newlines = 0;
        while (newlines < 2) {
            int value = in.read();
            if (value < 0) {
                return head.size() == 0 ? null : head.toString("ISO-8859-1");
            }
            head.write(value);
            if (value == '\n') {
                newlines++;
            } else if (value != '\r') {
                newlines = 0;
            }
        }
        String headers = head.toString("ISO-8859-1");
        int contentLength = 0;
        for (String line : headers.replace("\r\n", "\n").split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        if (contentLength <= 0) {
            return headers;
        }
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int count = in.read(body, read, contentLength - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        return headers + new String(body, 0, read, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** Connects back to the port SETUP announced and sends one HID touch report. */
    private static boolean sendTouch(int port, java.util.concurrent.CountDownLatch seen) {
        if (port <= 0) {
            return false;
        }
        try (Socket socket = new Socket("127.0.0.1", port)) {
            byte[] report = new byte[HidTouch.REPORT_LENGTH];
            report[0] = 1;
            report[1] = 1;
            report[2] = 0x08;
            report[3] = 0x00;
            report[4] = 0x04;
            report[5] = 0x00;
            byte[] message = Iap2.control(0x1C00, java.util.Collections.singletonList(
                    new Iap2.Parameter(0x0040, report)));
            socket.getOutputStream().write(message);
            socket.getOutputStream().flush();
            return seen.await(3, TimeUnit.SECONDS);
        } catch (Exception error) {
            return false;
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
}
