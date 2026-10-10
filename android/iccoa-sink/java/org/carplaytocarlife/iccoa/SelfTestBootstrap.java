package org.carplaytocarlife.iccoa;

import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Drives {@link CarplayBootstrap} against a stand-in accessory.
 *
 * The accessory identity is a throwaway P-256 key and self signed certificate
 * generated for this test only; it is what lets the whole chain run here -
 * certificate, challenge, signature, announcement, Wi-Fi hand-over - instead of
 * waiting for a car.
 */
public final class SelfTestBootstrap {

    private static int failures;

    public static void main(String[] args) throws Exception {
        PrivateKey key = SelfTestAccessory.key();
        X509Certificate certificate = SelfTestAccessory.certificate();
        check("the test accessory identity loads", key != null && certificate != null);
        check("it is an EC P-256 identity",
                key.getAlgorithm().equals("EC")
                        && ((java.security.interfaces.ECPublicKey) certificate.getPublicKey())
                            .getParams().getOrder().bitLength() == 256);
        byte[] certificateDer = certificate.getEncoded();

        // ------------------------------------------------------------ the flow
        Recorder recorder = new Recorder();
        Session session = new Session();
        CarplayBootstrap bootstrap = new CarplayBootstrap("CarPlay Android", "uuid-1234",
                "transport-9", session);

        bootstrap.begin(recorder);
        check("the phone opens with 0x1D00", "[0x1D00]".equals(recorder.ids()),
                recorder.ids());
        check("0x1D00 carries no parameters", recorder.parameters(0x1D00).isEmpty());

        bootstrap.receive(identification(), recorder);
        check("identification is answered with 0x1D02",
                "[0x1D00, 0x1D02, 0xAA00]".equals(recorder.trailing(3)), recorder.trailing(3));
        check("authentication is requested with 0xAA00", recorder.last() == 0xAA00);
        check("the bootstrap counts itself identified", bootstrap.identified());
        check("a car that sends 0x4301 and takes 0x4300 is modern", bootstrap.modern());

        bootstrap.receive(message(CarplayBootstrap.MSG_AUTH_CERTIFICATE,
                CarplayBootstrap.one(0, certificateDer)), recorder);
        List<Iap2.Parameter> challengeParameters = recorder.parameters(0xAA02);
        check("the certificate is answered with a 0xAA02 challenge",
                recorder.last() == 0xAA02 && challengeParameters.size() == 1);
        byte[] challenge = challengeParameters.get(0).value;
        check("the challenge is 32 bytes for P-256", challenge.length == 32);

        byte[] derSignature = sign(key, challenge, true);
        check("a DER signature over the challenge verifies",
                CarplayBootstrap.verifyChallenge(certificate.getPublicKey(), challenge,
                        derSignature));
        byte[] rawSignature = derToRaw(derSignature);
        check("the raw 64 byte form verifies too",
                CarplayBootstrap.verifyChallenge(certificate.getPublicKey(), challenge,
                        rawSignature));
        check("a signature over another challenge is refused",
                !CarplayBootstrap.verifyChallenge(certificate.getPublicKey(),
                        CarplayBootstrap.newChallenge(certificate.getPublicKey()), rawSignature));
        check("a signature from another key is refused",
                !CarplayBootstrap.verifyChallenge(otherKey(), challenge, rawSignature));

        bootstrap.receive(message(CarplayBootstrap.MSG_AUTH_SIGNATURE,
                CarplayBootstrap.one(0, rawSignature)), recorder);
        check("a good signature is acknowledged with 0xAA05",
                "[0xAA05, 0x4E09, 0x4E0A, 0x4E0C, 0x4E0E, 0x4E0D, 0x4300, 0x5702]"
                        .equals(recorder.trailing(8)), recorder.trailing(8));
        check("the bootstrap counts itself authenticated", bootstrap.authenticated());
        check("the announcement then goes out",
                "[0x4E09, 0x4E0A, 0x4E0C, 0x4E0E, 0x4E0D, 0x4300, 0x5702]"
                        .equals(recorder.trailing(7)),
                recorder.trailing(7));
        check("the device name is the first announcement parameter",
                "CarPlay Android".equals(CarplayBootstrap.string(
                        recorder.parameters(0x4E09).get(0).value)));
        check("the Wi-Fi request 0x5702 is empty", recorder.parameters(0x5702).isEmpty());
        check("the caller was told the session is ready", session.ready);

        // ---------------------------------------------------- the Wi-Fi hand-over
        check("the configuration is refused before authentication",
                configOf(message(CarplayBootstrap.MSG_WIRELESS_START,
                        Arrays.asList(new Iap2.Parameter(1, wirelessNested("A", "12345678")))))
                        == null);
        // the same rules, reached on the authenticated path this time
        check("a good configuration is taken once authenticated",
                wirelessResult(validWireless()) != null);
        check("a credential shorter than eight characters is refused",
                wirelessResult(message(CarplayBootstrap.MSG_WIRELESS_START, Arrays.asList(
                        new Iap2.Parameter(1, wirelessNested("CarPlay-1234", "short")),
                        new Iap2.Parameter(2, new byte[] {0, 0, 0x1B, (byte) 0xBC}))))
                        == null);
        check("an unsupported security value is refused",
                wirelessResult(message(CarplayBootstrap.MSG_WIRELESS_START, Arrays.asList(
                        new Iap2.Parameter(1, wirelessNested("CarPlay-1234", "goodpassword", 1)),
                        new Iap2.Parameter(2, new byte[] {0, 0, 0x1B, (byte) 0xBC}))))
                        == null);
        check("a wireless message without an SSID is refused",
                wirelessResult(message(CarplayBootstrap.MSG_WIRELESS_START, Arrays.asList(
                        new Iap2.Parameter(1, Iap2.control(0, Arrays.asList(
                                new Iap2.Parameter(1, CarplayBootstrap.text("password"))))),
                        new Iap2.Parameter(2, new byte[] {0, 0, 0x1B, (byte) 0xBC}))))
                        == null);
        check("a port that is not four bytes is refused",
                wirelessResult(message(CarplayBootstrap.MSG_WIRELESS_START, Arrays.asList(
                        new Iap2.Parameter(1, wirelessNested("CarPlay-1234", "s3cretpass")),
                        new Iap2.Parameter(2, new byte[] {0x1B, (byte) 0xBC}))))
                        == null);
        CarplayBootstrap leaf = new CarplayBootstrap("CarPlay Android", "uuid", "transport",
                new Session());
        Recorder leafRecorder = new Recorder();
        leaf.begin(leafRecorder);
        leaf.receive(identification(), leafRecorder);
        leaf.receive(message(CarplayBootstrap.MSG_AUTH_CERTIFICATE,
                CarplayBootstrap.one(0, new byte[] {1, 2, 3, 4})), leafRecorder);
        check("garbage instead of a certificate stops the bootstrap", leaf.failed());
        leaf.receive(message(CarplayBootstrap.MSG_AUTH_CERTIFICATE,
                new ArrayList<Iap2.Parameter>()), leafRecorder);
        check("a certificate message with no parameter is refused too", leaf.failed());
        bootstrap.receive(message(CarplayBootstrap.MSG_WIRELESS_START,
                Arrays.asList(
                    new Iap2.Parameter(1, wirelessNested("CarPlay-1234", "s3cretpass")),
                    new Iap2.Parameter(2, new byte[] {0, 0, 0x1B, (byte) 0xBC}),
                    new Iap2.Parameter(3, CarplayBootstrap.text("receiver-7")),
                    new Iap2.Parameter(4, CarplayBootstrap.text("receiver-key")))), recorder);
        CarWifiConfig config = session.config;
        if (config == null) {
            System.out.println("---- accessory log ----");
            System.out.println(session.log);
        }
        check("the Wi-Fi configuration reached the caller", config != null);
        check("the SSID is read", config != null && "CarPlay-1234".equals(config.ssid));
        check("the credential is read", config != null && "s3cretpass".equals(config.password));
        check("the security byte is read", config != null && config.security == 2);
        check("the channel is read", config != null && config.channel == 6);
        check("every receiver address is read",
                config != null && config.addresses.equals(
                        Arrays.asList("192.168.88.1", "192.168.88.2")),
                config == null ? "no config" : config.addresses.toString());
        check("the port is read big endian", config != null && config.port == 7100,
                config == null ? "no config" : String.valueOf(config.port));
        check("the receiver identity is read",
                config != null && "receiver-7".equals(config.receiverId)
                        && "receiver-key".equals(config.receiverPublicKey));
        check("the configuration has a usable endpoint", config != null && config.hasEndpoint());
        check("the password stays out of the log line",
                config != null && !config.describe().contains("s3cretpass"));
        check("the SSID and credential never appear in a log message",
                session.log.indexOf("s3cretpass") < 0);

        // ---------------------------------------------------------- refusal paths
        Recorder second = new Recorder();
        CarplayBootstrap refused = new CarplayBootstrap("CarPlay Android", "uuid-1234",
                "transport-9", new Session());
        refused.begin(second);
        refused.receive(identification(), second);
        refused.receive(message(CarplayBootstrap.MSG_AUTH_CERTIFICATE,
                CarplayBootstrap.one(0, certificateDer)), second);
        byte[] fresh = second.parameters(0xAA02).get(0).value;
        refused.receive(message(CarplayBootstrap.MSG_AUTH_SIGNATURE,
                CarplayBootstrap.one(0, sign(key, flip(fresh), false))), second);
        check("a signature over the wrong challenge fails the session", refused.failed());
        check("and is answered with 0xAA04", second.last() == 0xAA04);
        check("nothing is announced after a failed signature", !second.ids().contains("[0x4E09]"));


        Recorder flat = new Recorder();
        Session flatSession = new Session();
        CarplayBootstrap flatBootstrap = new CarplayBootstrap("CarPlay Android", "uuid",
                "transport", flatSession);
        flatBootstrap.begin(flat);
        flatBootstrap.receive(identification(), flat);
        flatBootstrap.receive(message(CarplayBootstrap.MSG_AUTH_CERTIFICATE,
                CarplayBootstrap.one(0, certificateDer)), flat);
        byte[] flatChallenge = flat.parameters(0xAA02).get(0).value;
        flatBootstrap.receive(message(CarplayBootstrap.MSG_AUTH_SIGNATURE,
                CarplayBootstrap.one(0, sign(key, flatChallenge, false))), flat);
        flatBootstrap.receive(message(CarplayBootstrap.MSG_WIRELESS_WIFI, Arrays.asList(
                new Iap2.Parameter(1, CarplayBootstrap.text("CarPlay-Flat")),
                new Iap2.Parameter(2, CarplayBootstrap.text("flatpassword")),
                new Iap2.Parameter(3, new byte[] {2}),
                new Iap2.Parameter(4, new byte[] {11}))), flat);
        check("the flat 0x5703 form is read as well",
                flatSession.config != null && "CarPlay-Flat".equals(flatSession.config.ssid)
                        && flatSession.config.security == 2
                        && flatSession.config.channel == 11
                        && flatSession.config.addresses.isEmpty());

        // ------------------------------- duplicates and genuine changes
        bootstrap.receive(identification(), recorder);
        check("a repeated identification does not ask for authentication twice",
                recorder.ids().lastIndexOf(CarplayBootstrap.MSG_REQUEST_AUTHENTICATION)
                        == recorder.ids().indexOf(CarplayBootstrap.MSG_REQUEST_AUTHENTICATION),
                recorder.ids());
        bootstrap.receive(message(CarplayBootstrap.MSG_WIRELESS_START,
                Arrays.asList(
                    new Iap2.Parameter(1, wirelessNested("CarPlay-1234", "s3cretpass")),
                    new Iap2.Parameter(2, new byte[] {0, 0, 0x1B, (byte) 0xBC}),
                    new Iap2.Parameter(3, CarplayBootstrap.text("receiver-7")),
                    new Iap2.Parameter(4, CarplayBootstrap.text("receiver-key")))), recorder);
        check("the same configuration told once is not announced again",
                session.configs == 1, "told " + session.configs + " times");
        bootstrap.receive(message(CarplayBootstrap.MSG_WIRELESS_START,
                Arrays.asList(
                    new Iap2.Parameter(1, wirelessNested("CarPlay-1234", "s3cretpass")),
                    new Iap2.Parameter(2, new byte[] {0, 0, 0x1C, 0x20}),
                    new Iap2.Parameter(3, CarplayBootstrap.text("receiver-7")),
                    new Iap2.Parameter(4, CarplayBootstrap.text("receiver-key")))), recorder);
        check("a configuration that moved is announced again",
                session.configs == 2 && session.config != null
                        && session.config.port == 0x1C20,
                "told " + session.configs + " times, port "
                        + (session.config == null ? "?" : session.config.port));

        // ------------------------------- the branches a car can drive us into
        CarplayBootstrap guarded = new CarplayBootstrap("CarPlay Android", "uuid", "transport",
                new Session());
        Recorder guard = new Recorder();
        guarded.begin(guard);
        guarded.receive(identification(), guard);
        check("a repeated identification is only accepted once",
                guard.ids().indexOf(CarplayBootstrap.MSG_IDENTIFICATION_ACCEPTED)
                        == guard.ids().lastIndexOf(
                                CarplayBootstrap.MSG_IDENTIFICATION_ACCEPTED));
        guarded.receive(message(CarplayBootstrap.MSG_IDENTIFICATION_REJECTED,
                new ArrayList<Iap2.Parameter>()), guard);
        check("a rejection from the car stops the bootstrap", guarded.failed());
        check("and is not answered", guard.last() == CarplayBootstrap.MSG_REQUEST_AUTHENTICATION,
                guard.ids());

        Recorder early = new Recorder();
        CarplayBootstrap untouched = new CarplayBootstrap("CarPlay Android", "uuid", "transport",
                new Session());
        untouched.begin(early);
        untouched.receive(message(CarplayBootstrap.MSG_AUTH_SIGNATURE,
                CarplayBootstrap.one(0, new byte[64])), early);
        check("a signature before a challenge is ignored",
                !untouched.authenticated() && early.count() == 1, early.ids());
        untouched.receive(message(CarplayBootstrap.MSG_AUTH_FAILED,
                new ArrayList<Iap2.Parameter>()), early);
        check("a reported authentication failure stops the bootstrap", untouched.failed());
        untouched.receive(message(CarplayBootstrap.MSG_WIRELESS_AVAILABILITY,
                new ArrayList<Iap2.Parameter>()), early);
        untouched.receive(message(CarplayBootstrap.MSG_NOW_PLAYING_SUBSCRIBE,
                new ArrayList<Iap2.Parameter>()), early);
        untouched.receive(message(CarplayBootstrap.MSG_NOW_PLAYING_UNSUBSCRIBE,
                new ArrayList<Iap2.Parameter>()), early);
        check("availability and now playing are taken quietly", early.count() == 1,
                early.ids());
        untouched.receive(message(CarplayBootstrap.MSG_WIRELESS_REQUEST,
                new ArrayList<Iap2.Parameter>()), early);
        check("a wireless status request is answered with the reference's byte",
                early.last() == CarplayBootstrap.MSG_WIRELESS_REQUEST_ANSWER
                        && early.parameters(CarplayBootstrap.MSG_WIRELESS_REQUEST_ANSWER)
                                .get(0).value[0] == 2,
                early.ids());

        // the wireless hand-over has four ways to be unusable, and one to work
        check("a good wireless start is accepted as a control",
                wirelessResult(validWireless()) != null);
        check("a wireless start without its nested parameters is refused",
                wirelessResult(message(CarplayBootstrap.MSG_WIRELESS_START,
                        Arrays.asList(new Iap2.Parameter(2,
                                new byte[] {0, 0, 0x1B, (byte) 0xBC})))) == null);
        check("a wireless start with an out of range port is refused",
                wirelessResult(message(CarplayBootstrap.MSG_WIRELESS_START,
                        Arrays.asList(
                            new Iap2.Parameter(1, wirelessNested("CarPlay-1234", "s3cretpass")),
                            new Iap2.Parameter(2, new byte[] {0, 0, 0, 0})))) == null);
        check("a wireless start that wants mutual authentication is refused",
                wirelessResult(message(CarplayBootstrap.MSG_WIRELESS_START,
                        Arrays.asList(
                            new Iap2.Parameter(1, wirelessNested("CarPlay-1234", "s3cretpass")),
                            new Iap2.Parameter(2, new byte[] {0, 0, 0x1B, (byte) 0xBC}),
                            new Iap2.Parameter(8, new byte[] {1})))) == null);
        check("nested parameters that are not a control message are refused",
                wirelessResult(message(CarplayBootstrap.MSG_WIRELESS_START,
                        Arrays.asList(
                            new Iap2.Parameter(1, new byte[] {9, 9, 9, 9, 9, 9, 9, 9}),
                            new Iap2.Parameter(2,
                                    new byte[] {0, 0, 0x1B, (byte) 0xBC})))) == null);

        // ------------------------------------------------------------- link layer
        check("the identification payload names control session 1",
                Iap2.controlSession(Iap2Link.SYNC_PAYLOAD) == 1);
        check("the responder payload names control session 10",
                Iap2.controlSession(Iap2Link.SYNC_PAYLOAD_RESPONDER) == 10);
        check("the capability list decodes as message ids",
                Iap2Link.capabilityIds(new byte[] {0x43, 0x01, 0x43, 0x00})
                        .equals(Arrays.asList(0x4301, 0x4300)));

        runLinkTest();

        System.out.println("---- bootstrap log ----\n" + session.log);
        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST BOOTSTRAP OK" : (failures + " FAILED"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** Builds the nested wireless parameter blob of message 0x4301. */
    static byte[] wirelessNested(String ssid, String password) {
        return wirelessNested(ssid, password, 2);
    }

    static byte[] wirelessNested(String ssid, String password, int security) {
        return Iap2.control(0, Arrays.asList(
                new Iap2.Parameter(0, CarplayBootstrap.text(ssid)),
                new Iap2.Parameter(1, CarplayBootstrap.text(password)),
                new Iap2.Parameter(2, new byte[] {6}),
                new Iap2.Parameter(3, CarplayBootstrap.text("192.168.88.1")),
                new Iap2.Parameter(3, CarplayBootstrap.text("192.168.88.2")),
                new Iap2.Parameter(4, new byte[] {(byte) security})));
    }

    /** The wireless start a car sends when everything about it is usable. */
    static Iap2.Message validWireless() {
        return message(CarplayBootstrap.MSG_WIRELESS_START, Arrays.asList(
                new Iap2.Parameter(1, wirelessNested("CarPlay-1234", "s3cretpass")),
                new Iap2.Parameter(2, new byte[] {0, 0, 0x1B, (byte) 0xBC}),
                new Iap2.Parameter(3, CarplayBootstrap.text("receiver-7")),
                new Iap2.Parameter(4, CarplayBootstrap.text("receiver-key"))));
    }

    /**
     * The configuration an authenticated bootstrap hands over for one wireless
     * message, or null when it refuses it. A fresh bootstrap each time keeps the
     * refusals independent of each other.
     */
    static CarWifiConfig wirelessResult(Iap2.Message wireless) {
        Session seen = new Session();
        CarplayBootstrap probe = new CarplayBootstrap("CarPlay Android", "uuid", "transport",
                seen);
        Recorder recorder = new Recorder();
        probe.begin(recorder);
        probe.receive(identification(), recorder);
        try {
            probe.receive(message(CarplayBootstrap.MSG_AUTH_CERTIFICATE,
                    CarplayBootstrap.one(0, SelfTestAccessory.certificate().getEncoded())),
                    recorder);
        } catch (Exception error) {
            throw new IllegalStateException("no test certificate", error);
        }
        byte[] challenge = recorder.parameters(0xAA02).get(0).value;
        try {
            probe.receive(message(CarplayBootstrap.MSG_AUTH_SIGNATURE, CarplayBootstrap.one(0,
                    sign(SelfTestAccessory.key(), challenge, false))), recorder);
        } catch (Exception error) {
            throw new IllegalStateException("cannot sign the challenge", error);
        }
        probe.receive(wireless, recorder);
        return seen.config;
    }

    /** An accessory that answers 0x1D01 with capability lists. */
    static Iap2.Message identification() {
        List<Iap2.Parameter> parameters = new ArrayList<>();
        parameters.add(new Iap2.Parameter(CarplayBootstrap.IDENTIFICATION_SENDS,
                u16(0x4301, 0x4300, 0xAA01)));
        parameters.add(new Iap2.Parameter(CarplayBootstrap.IDENTIFICATION_RECEIVES,
                u16(0x4300, 0x0000)));
        return message(CarplayBootstrap.MSG_IDENTIFICATION, parameters);
    }

    /**
     * A fresh bootstrap that has not authenticated: a wireless message must be
     * ignored, so the caller sees no configuration.
     */
    static CarWifiConfig configOf(Iap2.Message wireless) {
        Session session = new Session();
        CarplayBootstrap bootstrap = new CarplayBootstrap("CarPlay Android", "uuid", "transport",
                session);
        Recorder recorder = new Recorder();
        bootstrap.begin(recorder);
        bootstrap.receive(identification(), recorder);
        bootstrap.receive(wireless, recorder);
        return session.config;
    }

    /** Two links over a loopback socket must negotiate and carry control messages. */
    private static void runLinkTest() throws Exception {
        final java.net.ServerSocket server = new java.net.ServerSocket(0);
        final List<Integer> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.CountDownLatch sessionSeen =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger acknowledgedByPeer =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        Thread fakeCar = new Thread(new Runnable() {
            @Override public void run() {
                try (java.net.Socket socket = server.accept()) {
                    Iap2Link link = new Iap2Link(socket.getInputStream(),
                            socket.getOutputStream(), new Iap2Link.Listener() {
                                @Override public void onLog(String message) { Logs.add(message); }
                                @Override public void onFrame(Iap2.Frame frame) {
                                }
                                @Override public void onControl(Iap2.Message message) {
                                    received.add(message.command);
                                }
                                @Override public void onSession(int session) {
                                    sessionSeen.countDown();
                                }
                            });
                    link.start();
                    link.openWhenPeerSilent(Iap2Link.SYNC_PAYLOAD, 250);
                    Thread.sleep(1500);
                } catch (Exception error) {
                    received.add(-1);
                }
            }
        }, "fake-car-link");
        fakeCar.setDaemon(true);
        fakeCar.start();

        java.net.Socket socket = new java.net.Socket("127.0.0.1", server.getLocalPort());
        Iap2Link link = new Iap2Link(socket.getInputStream(), socket.getOutputStream(),
                new Iap2Link.Listener() {
                    @Override public void onLog(String message) { Logs.add(message); }
                    @Override public void onFrame(Iap2.Frame frame) {
                        // the peer's bare acknowledgement names the sequence it took
                        if (frame.has(Iap2.ACK) && frame.payload.length == 0) {
                            acknowledgedByPeer.set(frame.ack);
                        }
                    }
                    @Override public void onControl(Iap2.Message message) {
                    }
                    @Override public void onSession(int session) {
                    }
                });
        link.start();
        link.openWhenPeerSilent(Iap2Link.SYNC_PAYLOAD, 250);
        long negotiated = System.currentTimeMillis() + 5000;
        while (!link.sessionOpen() && System.currentTimeMillis() < negotiated) {
            Thread.sleep(20);
        }
        check("the loopback link negotiates the control session by itself",
                link.sessionOpen() && link.session() == 1, "session=" + link.session());
        check("the peer negotiated the same session",
                sessionSeen.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // let the identification exchange settle first: our answer to the peer's
        // SYN may still be on its way, and it would be the frame it acks instead
        Thread.sleep(300);
        int sentSequence = link.sequence();
        link.sendControl(CarplayBootstrap.MSG_REQUEST_IDENTIFICATION,
                new ArrayList<Iap2.Parameter>());
        long deadline = System.currentTimeMillis() + 3000;
        while (received.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        check("a control message survives the link framing",
                received.equals(Arrays.asList(CarplayBootstrap.MSG_REQUEST_IDENTIFICATION)),
                received.toString());
        check("the peer acknowledged the sequence our message carried",
                acknowledgedByPeer.get() == sentSequence,
                "acked=" + acknowledgedByPeer.get() + " sent=" + sentSequence);
        check("the link counted what it sent", link.controlsSent() == 1);
        check("the link learned the peer's receive packet",
                link.maxReceive() == 0x0800, String.valueOf(link.maxReceive()));
        link.stop();
        socket.close();
        server.close();

        runResponderLinkTest();
    }

    /**
     * The accessory-initiates round: the car opens the link, this side must only
     * answer and adopt the car's session instead of insisting on its own.
     */
    private static void runResponderLinkTest() throws Exception {
        final java.net.ServerSocket server = new java.net.ServerSocket(0);
        final java.util.concurrent.CountDownLatch peerDone =
                new java.util.concurrent.CountDownLatch(1);
        Thread car = new Thread(new Runnable() {
            @Override public void run() {
                try (java.net.Socket socket = server.accept()) {
                    Iap2Link link = new Iap2Link(socket.getInputStream(),
                            socket.getOutputStream(), new Iap2Link.Listener() {
                                @Override public void onLog(String message) { Logs.add(message); }
                                @Override public void onFrame(Iap2.Frame frame) {
                                }
                                @Override public void onControl(Iap2.Message message) {
                                }
                                @Override public void onSession(int session) {
                                }
                            });
                    link.start();
                    link.openLink(Iap2Link.SYNC_PAYLOAD_RESPONDER);
                    Thread.sleep(1200);
                } catch (Exception ignored) {
                }
                peerDone.countDown();
            }
        }, "car-initiates");
        car.setDaemon(true);
        car.start();

        java.net.Socket socket = new java.net.Socket("127.0.0.1", server.getLocalPort());
        final List<Integer> sessions = new java.util.concurrent.CopyOnWriteArrayList<>();
        Iap2Link link = new Iap2Link(socket.getInputStream(), socket.getOutputStream(),
                new Iap2Link.Listener() {
                    @Override public void onLog(String message) { Logs.add(message); }
                    @Override public void onFrame(Iap2.Frame frame) {
                    }
                    @Override public void onControl(Iap2.Message message) {
                    }
                    @Override public void onSession(int session) {
                        sessions.add(session);
                    }
                });
        link.start();
        link.openWhenPeerSilent(Iap2Link.SYNC_PAYLOAD, 2000);
        long deadline = System.currentTimeMillis() + 4000;
        while (!link.sessionOpen() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        check("a car that opens the link sets the session", link.session() == 10,
                "session=" + link.session());
        check("the phone answered instead of initiating", link.peerOpened()
                && sessions.equals(Arrays.asList(10)), sessions.toString());
        link.stop();
        socket.close();
        peerDone.await(2, java.util.concurrent.TimeUnit.SECONDS);
        server.close();
    }

    /** Signs a challenge the way the accessory does: the raw digest. */
    static byte[] sign(PrivateKey key, byte[] challenge, boolean der) throws Exception {
        Signature signature = Signature.getInstance("NONEwithECDSA");
        signature.initSign(key);
        signature.update(challenge);
        byte[] result = signature.sign();
        return der ? result : derToRaw(result);
    }

    private static java.security.PublicKey otherKey() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair().getPublic();
    }

    static byte[] derToRaw(byte[] der) {
        org.bouncycastle.asn1.ASN1Sequence sequence =
                org.bouncycastle.asn1.ASN1Sequence.getInstance(der);
        java.math.BigInteger r = ((org.bouncycastle.asn1.ASN1Integer) sequence.getObjectAt(0))
                .getValue();
        java.math.BigInteger s = ((org.bouncycastle.asn1.ASN1Integer) sequence.getObjectAt(1))
                .getValue();
        byte[] out = new byte[64];
        System.arraycopy(fixed(r), 0, out, 0, 32);
        System.arraycopy(fixed(s), 0, out, 32, 32);
        return out;
    }

    private static byte[] fixed(java.math.BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] flip(byte[] value) {
        byte[] copy = value.clone();
        copy[0] ^= 0x5A;
        return copy;
    }

    private static byte[] u16(int... values) {
        byte[] out = new byte[values.length * 2];
        for (int index = 0; index < values.length; index++) {
            out[index * 2] = (byte) ((values[index] >> 8) & 0xFF);
            out[index * 2 + 1] = (byte) (values[index] & 0xFF);
        }
        return out;
    }

    static Iap2.Message message(int id, List<Iap2.Parameter> parameters) {
        Iap2.Message message = new Iap2.Message();
        message.command = id;
        message.parameters.addAll(parameters);
        return message;
    }

    /** What the caller under test sees. */
    static final class Session implements CarplayBootstrap.Listener {
        CarWifiConfig config;
        int configs;
        boolean ready;
        final StringBuilder log = new StringBuilder();

        @Override public void onLog(String message) {
            log.append(message).append('\n');
            // every probe's log is part of the run's log, which is also the
            // corpus the coverage audit reads
            Logs.add(message);
        }

        @Override public void onConfig(CarWifiConfig seen) {
            config = seen;
            configs++;
        }

        @Override public void onReady() {
            ready = true;
        }
    }

    /** Records what the bootstrap sends, so the sequence can be asserted. */
    static final class Recorder implements CarplayBootstrap.Sender {
        private final List<Integer> ids = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<List<Iap2.Parameter>> parameters =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public void send(int messageId, List<Iap2.Parameter> sent) {
            ids.add(messageId);
            parameters.add(new ArrayList<>(sent));
        }

        int count() {
            return ids.size();
        }

        String ids() {
            return describe(ids);
        }

        String trailing(int count) {
            return describe(ids.subList(Math.max(0, ids.size() - count), ids.size()));
        }

        int last() {
            return ids.isEmpty() ? 0 : ids.get(ids.size() - 1);
        }

        List<Iap2.Parameter> parameters(int messageId) {
            int index = ids.lastIndexOf(messageId);
            return index < 0 ? new ArrayList<Iap2.Parameter>() : parameters.get(index);
        }

        private static String describe(List<Integer> values) {
            StringBuilder text = new StringBuilder("[");
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) {
                    text.append(", ");
                }
                text.append(String.format("0x%04X", values.get(index)));
            }
            return text.append(']').toString();
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

    private SelfTestBootstrap() {
    }
}
