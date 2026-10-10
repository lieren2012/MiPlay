package org.carplaytocarlife.iccoa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mode snapshot and the car's screen ownership transfer.
 *
 * Getting this wrong is the kind of mistake that shows up as a black screen with
 * everything else working: the car asks to hand the screen over, and a 501 or a
 * differently shaped snapshot means the hand-over never completes.
 */
public final class SelfTestModes {

    private static int failures;

    public static void main(String[] args) {
        AirplayModes modes = new AirplayModes();
        check("the phone starts owning the screen",
                modes.screen() == AirplayModes.OWNED_BY_PHONE);
        check("the car starts owning the audio",
                modes.audio() == AirplayModes.OWNED_BY_CAR);

        Map<String, Bplist.Node> snapshot = modes.snapshot();
        check("the snapshot is a modesChanged command",
                "modesChanged".equals(snapshot.get("type").asString()));
        check("it carries params", snapshot.get("params") != null);
        Map<String, Bplist.Node> params = snapshot.get("params").asDict();
        List<Bplist.Node> resources = params.get("resources").asArray();
        check("there are two resources", resources.size() == 2);
        Map<String, Bplist.Node> screen = resources.get(0).asDict();
        check("the screen resource is number one and owned by the phone",
                screen.get("resourceID").asInteger() == 1
                        && screen.get("entity").asInteger() == 1
                        && screen.get("permanentEntity").asInteger() == 1);
        Map<String, Bplist.Node> audio = resources.get(1).asDict();
        check("the audio resource is number two and owned by the car",
                audio.get("resourceID").asInteger() == 2
                        && audio.get("entity").asInteger() == 2
                        && audio.get("permanentEntity").asInteger() == 2);
        List<Bplist.Node> appStates = params.get("appStates").asArray();
        check("there are three app states", appStates.size() == 3);
        Map<String, Bplist.Node> speech = appStates.get(0).asDict();
        check("the first app state is speech, off, with a negative speech mode",
                speech.get("appStateID").asInteger() == 1
                        && speech.get("entity").asInteger() == 0
                        && speech.get("speechMode").asInteger() == -1,
                speech.toString());
        check("the other app states are zeroed",
                appStates.get(1).asDict().get("entity").asInteger() == 0
                        && appStates.get(2).asDict().get("entity").asInteger() == 0);

        // the snapshot survives a plist round trip, negative value included
        Map<String, Bplist.Node> decoded = Bplist.decode(
                Bplist.encode(Bplist.Node.dict(snapshot))).asDict();
        long speechMode = decoded.get("params").asDict().get("appStates").asArray().get(0)
                .asDict().get("speechMode").asInteger();
        check("a negative plist integer survives the encoder", speechMode == -1,
                "read back " + speechMode);
        check("a negative integer is written as eight bytes",
                extraWidth(-1) == 7, String.valueOf(extraWidth(-1)));
        check("and so is any other negative value",
                extraWidth(Integer.MIN_VALUE) == 7);

        // ------------------------------------------ the car's transfer requests
        Map<String, Bplist.Node> afterCarTakes =
                modes.changeModes(transfer(AirplayModes.TRANSFER_CAR_TAKES));
        check("the car can take the screen",
                afterCarTakes != null && modes.screen() == AirplayModes.OWNED_BY_CAR);
        check("the reply names the car as the screen owner",
                entityOf(afterCarTakes) == AirplayModes.OWNED_BY_CAR,
                afterCarTakes == null ? "no reply" : ("entity=" + entityOf(afterCarTakes)));
        check("the reply carries a status and the new params",
                afterCarTakes != null && afterCarTakes.get("status").asInteger() == 0
                        && afterCarTakes.get("params") != null
                        && afterCarTakes.get("type") == null);
        check("and the phone can take it back",
                modes.changeModes(transfer(AirplayModes.TRANSFER_PHONE_TAKES)) != null
                        && modes.screen() == AirplayModes.OWNED_BY_PHONE);
        check("a temporary hand-over does not change what is permanent",
                modes.changeModes(transfer(AirplayModes.TRANSFER_CAR_TAKES_TEMPORARILY)) != null
                        && modes.screen() == AirplayModes.OWNED_BY_CAR
                        && modes.snapshot().get("params").asDict().get("resources")
                                .asArray().get(0).asDict().get("permanentEntity")
                                .asInteger() == AirplayModes.OWNED_BY_PHONE);
        check("restoring goes back to the permanent owner",
                modes.changeModes(transfer(AirplayModes.TRANSFER_RESTORE_PERMANENT)) != null
                        && modes.screen() == AirplayModes.OWNED_BY_PHONE);

        check("a transfer this side does not know is refused",
                modes.changeModes(transfer(7)) == null);
        check("a command about another resource is refused",
                modes.changeModes(changeModesFor(AirplayModes.RESOURCE_AUDIO, 2)) == null);
        check("two transfers at once are refused",
                modes.changeModes(changeModesBody(2, 3)) == null);
        check("a command with app states is left to the caller",
                modes.changeModes(withAppStates()) == null);
        check("a command without params is refused", modes.changeModes(null) == null);

        System.out.println("---- app log ----\n"
                + String.join("\n", Logs.snapshot()));
        System.out.println(failures == 0 ? "SELFTEST MODES OK" : (failures + " FAILED"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** The screen owner a reply names. */
    private static long entityOf(Map<String, Bplist.Node> reply) {
        if (reply == null || reply.get("params") == null) {
            return -1000;
        }
        return reply.get("params").asDict().get("resources").asArray().get(0).asDict()
                .get("entity").asInteger();
    }

    /** How much wider a value encodes than the smallest possible integer. */
    private static int extraWidth(long value) {
        return entry(value).length - entry(0xFFL).length;
    }

    private static byte[] entry(long value) {
        Map<String, Bplist.Node> dict = new LinkedHashMap<>();
        dict.put("v", Bplist.Node.integer(value));
        return Bplist.encode(Bplist.Node.dict(dict));
    }

    private static Map<String, Bplist.Node> transfer(int transferType) {
        return changeModesBody(transferType);
    }

    private static Map<String, Bplist.Node> changeModesBody(int... transferTypes) {
        return changeModesFor(AirplayModes.RESOURCE_SCREEN, transferTypes);
    }

    private static Map<String, Bplist.Node> changeModesFor(int resourceID,
            int... transferTypes) {
        List<Bplist.Node> resources = new ArrayList<>();
        for (int transferType : transferTypes) {
            Map<String, Bplist.Node> resource = new LinkedHashMap<>();
            resource.put("resourceID", Bplist.Node.integer(resourceID));
            resource.put("transferType", Bplist.Node.integer(transferType));
            resources.add(Bplist.Node.dict(resource));
        }
        Map<String, Bplist.Node> params = new LinkedHashMap<>();
        params.put("resources", Bplist.Node.array(resources));
        Map<String, Bplist.Node> body = new LinkedHashMap<>();
        body.put("type", Bplist.Node.string("changeModes"));
        body.put("params", Bplist.Node.dict(params));
        return body;
    }

    private static Map<String, Bplist.Node> withAppStates() {
        Map<String, Bplist.Node> body = changeModesBody(2);
        Map<String, Bplist.Node> params = body.get("params").asDict();
        List<Bplist.Node> states = new ArrayList<>();
        Map<String, Bplist.Node> state = new LinkedHashMap<>();
        state.put("appStateID", Bplist.Node.integer(1));
        state.put("entity", Bplist.Node.integer(1));
        states.add(Bplist.Node.dict(state));
        params.put("appStates", Bplist.Node.array(states));
        return body;
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

    private SelfTestModes() {
    }
}
