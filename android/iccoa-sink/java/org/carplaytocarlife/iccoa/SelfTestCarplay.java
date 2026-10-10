package org.carplaytocarlife.iccoa;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/** Desktop self test for the CarPlay-side discovery codec and binary plist. */
public final class SelfTestCarplay {

    private static int failures;

    public static void main(String[] args) throws Exception {
        // ---- mDNS: query -> parse -> respond -> parse
        byte[] query = Mdns.buildQuery("_carplay-ctrl._tcp.local");
        Mdns.Message parsed = Mdns.parse(query);
        check("query has one question", parsed.questions.size() == 1);
        check("query asks for _carplay-ctrl._tcp.local PTR",
                "_carplay-ctrl._tcp.local".equals(parsed.questions.get(0).name)
                        && parsed.questions.get(0).type == Mdns.TYPE_PTR,
                parsed.questions.get(0).name);

        String id = "3f2a9c1b";
        String instance = Mdns.instanceName(id);
        String host = Mdns.hostName(id);
        byte[] response = Mdns.buildResponse(parsed.questions.get(0), instance, host, 7100,
                new byte[] {(byte) 192, (byte) 168, 43, 5});
        Mdns.Message answered = Mdns.parse(response);
        check("response is marked as a response", answered.response);
        check("response carries 4 records", answered.records.size() == 4,
                "records=" + answered.records.size());

        String ptrTarget = null;
        String srvTarget = null;
        int srvPort = -1;
        byte[] address = null;
        for (Mdns.Record record : answered.records) {
            if (record.type == Mdns.TYPE_PTR) {
                ptrTarget = new String(record.rdata, "UTF-8").isEmpty() ? null : record.target();
            } else if (record.type == Mdns.TYPE_SRV) {
                srvTarget = record.target();
                srvPort = record.port();
            } else if (record.type == Mdns.TYPE_A) {
                address = record.address();
            }
        }
        check("PTR points at the instance", instance.equals(ptrTarget), String.valueOf(ptrTarget));
        check("SRV target is the host name", host.equals(srvTarget), String.valueOf(srvTarget));
        check("SRV port is 7100", srvPort == 7100, "port=" + srvPort);
        check("A record carries our address", address != null && address.length == 4
                && (address[0] & 0xFF) == 192 && (address[3] & 0xFF) == 5);
        check("the question class is kept", parsed.questions.get(0).qclass == Mdns.CLASS_IN,
                String.valueOf(parsed.questions.get(0).qclass));

        // ---- the advertisement this phone sends so the car can find it
        String advertised = CarplayAdvertiser.SERVICE_TYPE;
        check("the advertised service is the one the reference uses",
                advertised.equals("_carplay-ctrl._tcp.local"), advertised);
        // the instance has to live under the advertised service, or a client that
        // resolves it gets a name that does not belong to any service
        check("our instance name sits under the advertised service",
                Mdns.instanceName("x").endsWith("." + CarplayAdvertiser.SERVICE_TYPE)
                        && Mdns.instanceName("x").startsWith("CarPlay Android "),
                Mdns.instanceName("x"));
        check("a question about the service is ours",
                CarplayAdvertiser.answersName("_carplay-ctrl._tcp.local", instance, host)
                        && CarplayAdvertiser.answersName("_CarPlay-Ctrl._TCP.local.", instance,
                                host));
        check("a question about our instance or host is ours too",
                CarplayAdvertiser.answersName(instance + ".", instance, host)
                        && CarplayAdvertiser.answersName(host, instance, host));
        check("another service is not ours",
                !CarplayAdvertiser.answersName("_airplay._tcp.local", instance, host)
                        && !CarplayAdvertiser.answersName("carplay-other.local", instance, host)
                        && !CarplayAdvertiser.answersName(null, instance, host));
        Mdns.Question unicast = new Mdns.Question("_carplay-ctrl._tcp.local", Mdns.TYPE_PTR);
        unicast.qclass = Mdns.CLASS_IN | 0x8000;
        check("a question may ask for a unicast answer",
                CarplayAdvertiser.unicastWanted(unicast));
        Mdns.Question multicast = new Mdns.Question("_carplay-ctrl._tcp.local", Mdns.TYPE_PTR);
        check("and normally does not", !CarplayAdvertiser.unicastWanted(multicast));
        check("the address advertised is four bytes",
                CarplayAdvertiser.localIpv4().length == 4);
        check("the announcement interval is the reference's",
                CarplayAdvertiser.ANNOUNCE_INTERVAL_MILLIS == 2000);

        // ---- binary plist: encode here, decode both here and with Python
        java.util.Map<String, Bplist.Node> dict = new java.util.LinkedHashMap<>();
        dict.put("name", Bplist.Node.string("CarPlay Android 3f2a9c1b"));
        dict.put("port", Bplist.Node.integer(7100));
        dict.put("screen", Bplist.Node.bool(true));
        dict.put("challenge", Bplist.Node.data(new byte[] {1, 2, 3, 4, 5}));
        java.util.List<Bplist.Node> list = new java.util.ArrayList<>();
        list.add(Bplist.Node.string("HIDC"));
        list.add(Bplist.Node.integer(1));
        dict.put("capabilities", Bplist.Node.array(list));
        byte[] plist = Bplist.encode(Bplist.Node.dict(dict));

        String plistPath = args.length > 0 ? args[0] : "carplay-selftest.plist";
        Files.write(Paths.get(plistPath), plist);
        System.out.println("wrote " + plistPath + " (" + plist.length
                + " bytes) for Python cross-check");

        Bplist.Node decoded = Bplist.decode(plist);
        Map<String, Bplist.Node> back = decoded.asDict();
        check("plist round trip: string", "CarPlay Android 3f2a9c1b".equals(back.get("name").asString()));
        check("plist round trip: int", back.get("port").asInteger() == 7100);
        check("plist round trip: bool", back.get("screen").asBool());
        check("plist round trip: data", back.get("challenge").asData().length == 5);
        check("plist round trip: array", back.get("capabilities").asArray().size() == 2);
        check("plist round trip: nested string",
                "HIDC".equals(back.get("capabilities").asArray().get(0).asString()));

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST CARPLAY OK" : ("SELFTEST CARPLAY FAILED: " + failures));
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
