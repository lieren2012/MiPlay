package org.carplaytocarlife.iccoa;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Desktop self test for the AirPlay message builders and plist bodies. */
public final class SelfTestAirplay {

    private static int failures;

    public static void main(String[] args) {
        AirplayMessages.Identity identity =
                new AirplayMessages.Identity("00:11:22:33:44:55", "iPhone", "iPhone14,2");

        // SETUP /setup: the session itself, before any stream exists
        Rtsp.Request setup = AirplayMessages.setupSession(identity, 2, "AB12-CD34", 50000, null);
        check("the session SETUP targets /setup", "SETUP".equals(setup.method)
                && "/setup".equals(setup.uri));
        Map<String, Bplist.Node> setupBody =
                AirplayMessages.plistBody(Rtsp.Request.parse(setup.serialize()).body);
        check("the session SETUP names the device",
                "00:11:22:33:44:55".equals(setupBody.get("deviceID").asString())
                        && "00:11:22:33:44:55".equals(setupBody.get("macAddress").asString()));
        check("the session SETUP carries the session uuid",
                "AB12-CD34".equals(setupBody.get("sessionUUID").asString()));
        check("the session SETUP carries the timing port",
                setupBody.get("timingPort").asInteger() == 50000
                        && "NTP".equals(setupBody.get("timingProtocol").asString()));
        check("the session SETUP declares the protocol version, not a phone build",
                AirplayMessages.COMPATIBILITY_VERSION.equals(
                        setupBody.get("osBuildVersion").asString())
                        && AirplayMessages.SOURCE_VERSION.equals(
                                setupBody.get("sourceVersion").asString()));
        check("the session SETUP says this is not screen mirroring",
                !setupBody.get("isScreenMirroringSession").asBool()
                        && !setupBody.get("statsCollectionEnabled").asBool());
        check("the session SETUP sends an empty feature array",
                setupBody.get("features").asArray().isEmpty());

        // the MFi fields ride along in the same dictionary
        Map<String, Bplist.Node> extra = new java.util.LinkedHashMap<>();
        extra.put("et", Bplist.Node.integer(16));
        extra.put("ekey", Bplist.Node.data(new byte[16]));
        extra.put("eiv", Bplist.Node.data(new byte[16]));
        Map<String, Bplist.Node> withKey = AirplayMessages.plistBody(AirplayMessages
                .setupSession(identity, 3, "AB12-CD34", 50000, extra).body);
        check("the MFi fields are merged into the session SETUP",
                withKey.get("et").asInteger() == 16
                        && withKey.get("ekey").asData().length == 16
                        && withKey.get("deviceID") != null);

        // GET /info and GET /event carry no body
        Rtsp.Request info = AirplayMessages.info(identity, 4);
        check("info is a GET with no body", "GET".equals(info.method)
                && "/info".equals(info.uri) && info.body.isEmpty());
        Rtsp.Request event = AirplayMessages.event(identity, 5);
        check("the event channel is a GET with no body", "GET".equals(event.method)
                && "/event".equals(event.uri) && event.body.isEmpty());

        // POST /command carries a plist
        Map<String, Bplist.Node> modes = new java.util.LinkedHashMap<>();
        modes.put("modes", Bplist.Node.dict(new java.util.LinkedHashMap<String, Bplist.Node>()));
        Rtsp.Request command = AirplayMessages.command(identity, 6, modes);
        check("a command posts a plist to /command", "POST".equals(command.method)
                && "/command".equals(command.uri)
                && AirplayMessages.plistBody(
                        Rtsp.Request.parse(command.serialize()).body).containsKey("modes"));

        // the screen stream is type 110 with a connection id and a latency
        Map<String, Bplist.Node> screen = AirplayMessages.screenStream(7, 100);
        check("the screen stream is type 110",
                screen.get("type").asInteger() == AirplayMessages.SCREEN_STREAM_TYPE);
        check("the screen stream carries its connection id and latency",
                screen.get("streamConnectionID").asInteger() == 7
                        && screen.get("latencyMs").asInteger() == 100);

        Rtsp.Request streams = AirplayMessages.setupStreams(identity, 8,
                java.util.Collections.singletonList(screen));
        Map<String, Bplist.Node> streamsBody =
                AirplayMessages.plistBody(Rtsp.Request.parse(streams.serialize()).body);
        check("the stream SETUP targets /", "SETUP".equals(streams.method)
                && "/".equals(streams.uri));
        check("the stream SETUP sends one stream entry",
                streamsBody.get("streams").asArray().size() == 1
                        && streamsBody.get("streams").asArray().get(0).asDict()
                                .get("type").asInteger() == 110);

        // RECORD / carries the session and no body
        Rtsp.Request record = AirplayMessages.recordSession(identity, 9, "ABC123");
        Rtsp.Request recordAgain = Rtsp.Request.parse(record.serialize());
        check("RECORD starts the session", "RECORD".equals(recordAgain.method)
                && "/".equals(recordAgain.uri) && recordAgain.body.isEmpty());
        check("RECORD keeps the session header", "ABC123".equals(recordAgain.header("Session")));

        // the port the car allocated is read back from its reply
        Map<String, Bplist.Node> reply = new java.util.LinkedHashMap<>();
        Map<String, Bplist.Node> allocated = new java.util.LinkedHashMap<>();
        allocated.put("type", Bplist.Node.integer(110));
        allocated.put("dataPort", Bplist.Node.integer(7100));
        reply.put("streams", Bplist.Node.array(
                java.util.Collections.singletonList(Bplist.Node.dict(allocated))));
        check("the allocated port is found by stream type",
                AirplayMessages.portOf(reply, 110, 0) == 7100);
        check("an absent stream type falls back", AirplayMessages.portOf(reply, 96, 0) == 0);

        // every request identifies the sender the way the reference does
        check("the session SETUP carries the user agent",
                AirplayMessages.USER_AGENT.equals(
                        Rtsp.Request.parse(setup.serialize()).header("User-Agent")));
        check("so does /info", AirplayMessages.USER_AGENT.equals(
                Rtsp.Request.parse(AirplayMessages.info(identity, 10).serialize())
                        .header("User-Agent")));
        check("so does /event", AirplayMessages.USER_AGENT.equals(
                Rtsp.Request.parse(AirplayMessages.event(identity, 11).serialize())
                        .header("User-Agent")));
        check("so does a command", AirplayMessages.USER_AGENT.equals(
                Rtsp.Request.parse(command.serialize()).header("User-Agent")));
        check("so does RECORD", AirplayMessages.USER_AGENT.equals(
                Rtsp.Request.parse(record.serialize()).header("User-Agent")));
        check("the user agent names the source version we announce",
                AirplayMessages.USER_AGENT.equals("AirPlay/" + AirplayMessages.SOURCE_VERSION),
                AirplayMessages.USER_AGENT);
        check("the session is not named before there is one",
                setup.header("Session") == null);
        check("RECORD names the session", "ABC123".equals(recordAgain.header("Session")));

        // pair-setup / pair-verify shapes
        Map<Integer, byte[]> pairItems = new java.util.LinkedHashMap<>();
        pairItems.put(Tlv8.STATE, Tlv8.byteValue(2));
        pairItems.put(Tlv8.METHOD, Tlv8.byteValue(1));
        Rtsp.Request pair = AirplayMessages.pairing("/pair-setup", 2, pairItems);
        check("pair-setup posts to /pair-setup", "/pair-setup".equals(pair.uri));
        Rtsp.Request pairParsed = Rtsp.Request.parse(pair.serialize());
        check("it carries the TLV8 content type",
                AirplayMessages.PAIRING_CONTENT_TYPE.equals(pairParsed.header("Content-Type")),
                String.valueOf(pairParsed.header("Content-Type")));
        check("and the HomeKit version header",
                "2".equals(pairParsed.header("X-Apple-HKP")));
        Map<Integer, byte[]> pairItemsBack = Tlv8.decode(
                pairParsed.body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        check("the state goes out in item 6",
                Tlv8.byteOf(pairItemsBack, Tlv8.STATE) == 2);
        check("the method goes out in item 0",
                Tlv8.byteOf(pairItemsBack, Tlv8.METHOD) == 1);
        check("pair-verify also names the PD attribute",
                "1".equals(AirplayMessages.pairing("/pair-verify", 3, pairItems)
                        .header("X-Apple-PD")));
        check("but pair-setup does not",
                AirplayMessages.pairing("/pair-setup", 2, pairItems)
                        .header("X-Apple-PD") == null);

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST AIRPLAY OK" : ("SELFTEST AIRPLAY FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
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
