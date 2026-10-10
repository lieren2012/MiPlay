package org.carplaytocarlife.iccoa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which side owns the screen and the audio, as the car expects to be told.
 *
 * The reference keeps exactly these two resources and three app states, sends
 * the snapshot as POST /command {@code modesChanged}, and answers the car's
 * {@code changeModes} with the updated snapshot. The numbers are the protocol's:
 *
 * <pre>
 *   entity 1 = this phone owns the resource, 2 = the car does
 *   transferType 1 = the car takes the screen (2, permanently)
 *                2 = the phone takes the screen (1, permanently)
 *                3 = the car takes it temporarily (2)
 *                4 = back to whoever had it permanently
 * </pre>
 *
 * The defaults matter: the reference starts with the screen owned by the phone
 * and the audio by the car, so a car that never asks for anything still gets a
 * mirroring session.
 */
public final class AirplayModes {

    public static final int RESOURCE_SCREEN = 1;
    public static final int RESOURCE_AUDIO = 2;

    public static final int OWNED_BY_PHONE = 1;
    public static final int OWNED_BY_CAR = 2;

    public static final int TRANSFER_CAR_TAKES = 1;
    public static final int TRANSFER_PHONE_TAKES = 2;
    public static final int TRANSFER_CAR_TAKES_TEMPORARILY = 3;
    public static final int TRANSFER_RESTORE_PERMANENT = 4;

    private int screen = OWNED_BY_PHONE;
    private int permanentScreen = OWNED_BY_PHONE;
    private int audio = OWNED_BY_CAR;
    private int permanentAudio = OWNED_BY_CAR;
    private boolean speech;

    public int screen() {
        return screen;
    }

    public int audio() {
        return audio;
    }

    public boolean speech() {
        return speech;
    }

    /** The modesChanged body, field for field as the reference builds it. */
    public Map<String, Bplist.Node> snapshot() {
        Map<String, Bplist.Node> body = new LinkedHashMap<>();
        body.put("type", Bplist.Node.string("modesChanged"));
        body.put("params", Bplist.Node.dict(params()));
        return body;
    }

    /**
     * The reply to the car's changeModes.
     *
     * The reference answers with the new state under a status field, not with a
     * modesChanged command of its own: {@code {status: 0, params: {...}}}, where
     * the params are the same ones the snapshot carries.
     */
    public Map<String, Bplist.Node> changeReply() {
        Map<String, Bplist.Node> reply = new LinkedHashMap<>();
        reply.put("status", Bplist.Node.integer(0));
        reply.put("params", Bplist.Node.dict(params()));
        return reply;
    }

    /** The resources and app states, without a command envelope. */
    public Map<String, Bplist.Node> params() {
        List<Bplist.Node> resources = new ArrayList<>();
        resources.add(resource(RESOURCE_SCREEN, screen, permanentScreen));
        resources.add(resource(RESOURCE_AUDIO, audio, permanentAudio));

        List<Bplist.Node> appStates = new ArrayList<>();
        Map<String, Bplist.Node> speechState = new LinkedHashMap<>();
        speechState.put("appStateID", Bplist.Node.integer(1));
        speechState.put("entity", Bplist.Node.integer(speech ? 1 : 0));
        speechState.put("speechMode", Bplist.Node.integer(speech ? 2 : -1));
        appStates.add(Bplist.Node.dict(speechState));
        Map<String, Bplist.Node> second = new LinkedHashMap<>();
        second.put("appStateID", Bplist.Node.integer(2));
        second.put("entity", Bplist.Node.integer(0));
        appStates.add(Bplist.Node.dict(second));
        Map<String, Bplist.Node> third = new LinkedHashMap<>();
        third.put("appStateID", Bplist.Node.integer(3));
        third.put("entity", Bplist.Node.integer(0));
        appStates.add(Bplist.Node.dict(third));

        Map<String, Bplist.Node> params = new LinkedHashMap<>();
        params.put("resources", Bplist.Node.array(resources));
        params.put("appStates", Bplist.Node.array(appStates));
        return params;
    }

    private static Bplist.Node resource(int id, int entity, int permanent) {
        Map<String, Bplist.Node> resource = new LinkedHashMap<>();
        resource.put("resourceID", Bplist.Node.integer(id));
        resource.put("entity", Bplist.Node.integer(entity));
        resource.put("permanentEntity", Bplist.Node.integer(permanent));
        return Bplist.Node.dict(resource);
    }

    /**
     * Applies one transfer of the screen resource.
     *
     * Returns false for a type this side does not know, which the caller can
     * turn into a refusal instead of pretending the ownership changed.
     */
    public boolean applyTransfer(int transferType) {
        switch (transferType) {
            case TRANSFER_CAR_TAKES:
                screen = OWNED_BY_CAR;
                permanentScreen = OWNED_BY_CAR;
                return true;
            case TRANSFER_PHONE_TAKES:
                screen = OWNED_BY_PHONE;
                permanentScreen = OWNED_BY_PHONE;
                return true;
            case TRANSFER_CAR_TAKES_TEMPORARILY:
                screen = OWNED_BY_CAR;
                return true;
            case TRANSFER_RESTORE_PERMANENT:
                screen = permanentScreen;
                return true;
            default:
                return false;
        }
    }

    /**
     * Handles the car's changeModes command.
     *
     * The reply is the new state with a status field, the way the reference
     * answers; a command that asks for something else (app states, another
     * resource, an unknown transfer) is left unhandled so the caller can say so.
     */
    public Map<String, Bplist.Node> changeModes(Map<String, Bplist.Node> command) {
        if (command == null || command.get("params") == null) {
            return null;
        }
        Map<String, Bplist.Node> params = command.get("params").asDict();
        Bplist.Node appStates = params.get("appStates");
        if (appStates != null && !appStates.asArray().isEmpty()) {
            return null;
        }
        Bplist.Node resources = params.get("resources");
        if (resources == null || resources.asArray().isEmpty()) {
            return null;
        }
        List<Integer> transfers = new ArrayList<>();
        for (Bplist.Node node : resources.asArray()) {
            Map<String, Bplist.Node> resource;
            try {
                resource = node.asDict();
            } catch (RuntimeException notADict) {
                return null;
            }
            Bplist.Node id = resource.get("resourceID");
            Bplist.Node transfer = resource.get("transferType");
            if (id == null || transfer == null || id.asInteger() != RESOURCE_SCREEN) {
                return null;
            }
            transfers.add((int) transfer.asInteger());
        }
        if (transfers.size() != 1) {
            return null;
        }
        return applyTransfer(transfers.get(0)) ? changeReply() : null;
    }
}
