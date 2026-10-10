package org.carplaytocarlife.iccoa;

import java.io.ByteArrayInputStream;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The iAP2 session a CarPlay accessory expects to talk to.
 *
 * Message ids and shapes come from the reference CarWith -> CarPlay app's own
 * bootstrap (docs/apk-analysis-carwith-carplay.md); the flow is:
 *
 * <pre>
 *   phone -> 0x1D00 request identification
 *   car   -> 0x1D01 identification (capability lists in parameters 6 and 7)
 *   phone -> 0x1D02 identification accepted
 *   phone -> 0xAA00 request authentication
 *   car   -> 0xAA01 certificate chain (parameter 0, DER)
 *   phone -> 0xAA02 challenge (32 bytes for P-256, 20 for RSA)
 *   car   -> 0xAA03 signature over the challenge
 *   phone -> 0xAA05 authentication succeeded
 *   phone -> 0x4E09/0x4E0A/0x4E0C/0x4E0E/0x4E0D announcements, [0x4300],
 *            0x5702 "give me the Wi-Fi"
 *   car   -> 0x4301 wireless start (nested Wi-Fi parameters in 1, port in 2,
 *            receiver id in 3, receiver public key in 4)
 *          or 0x5703 Wi-Fi parameters flat (ssid 1, password 2, security 3,
 *            channel 4)
 * </pre>
 *
 * The car's signature is checked against its leaf certificate. The MFi trust
 * chain is deliberately not verified, exactly as the reference does with its
 * "development-trust" mode: this is a phone pretending to be one, so there is
 * no Apple trust anchor to check against.
 */
public final class CarplayBootstrap {

    public static final int MSG_REQUEST_IDENTIFICATION = 0x1D00;
    public static final int MSG_IDENTIFICATION = 0x1D01;
    public static final int MSG_IDENTIFICATION_ACCEPTED = 0x1D02;
    public static final int MSG_IDENTIFICATION_REJECTED = 0x1D03;
    public static final int MSG_REQUEST_AUTHENTICATION = 0xAA00;
    public static final int MSG_AUTH_CERTIFICATE = 0xAA01;
    public static final int MSG_AUTH_CHALLENGE = 0xAA02;
    public static final int MSG_AUTH_SIGNATURE = 0xAA03;
    public static final int MSG_AUTH_FAILED = 0xAA04;
    public static final int MSG_AUTH_SUCCEEDED = 0xAA05;
    public static final int MSG_WIRELESS_AVAILABILITY = 0x4300;
    public static final int MSG_WIRELESS_START = 0x4301;
    public static final int MSG_DEVICE_NAME = 0x4E09;
    public static final int MSG_LANGUAGE = 0x4E0A;
    public static final int MSG_DEVICE_UUID = 0x4E0C;
    public static final int MSG_TRANSPORT_FLAG = 0x4E0D;
    public static final int MSG_TRANSPORT_ID = 0x4E0E;
    public static final int MSG_NOW_PLAYING_SUBSCRIBE = 0x5000;
    public static final int MSG_NOW_PLAYING_UPDATE = 0x5001;
    public static final int MSG_NOW_PLAYING_UNSUBSCRIBE = 0x5002;
    public static final int MSG_WIRELESS_REQUEST = 0x5700;
    public static final int MSG_WIRELESS_REQUEST_ANSWER = 0x5701;
    public static final int MSG_WIRELESS_START_REQUEST = 0x5702;
    public static final int MSG_WIRELESS_WIFI = 0x5703;

    /** Parameter id carrying the accessory's "sends" list inside 0x1D01. */
    public static final int IDENTIFICATION_SENDS = 6;
    /** Parameter id carrying the accessory's "receives" list inside 0x1D01. */
    public static final int IDENTIFICATION_RECEIVES = 7;

    public interface Listener {
        void onLog(String message);

        void onConfig(CarWifiConfig config);

        void onReady();
    }

    /** Writes one control message; the link layer wraps and fragments it. */
    public interface Sender {
        void send(int messageId, List<Iap2.Parameter> parameters);
    }

    private final String deviceName;
    private final String deviceUuid;
    private final String transportId;
    private final Listener listener;

    private boolean identified;
    private boolean authenticated;
    private boolean certificateRequested;
    private boolean availabilityPublished;
    private boolean modern;
    private boolean failed;
    private List<Iap2.Parameter> accessorySends = new ArrayList<>();
    private List<Iap2.Parameter> accessoryReceives = new ArrayList<>();
    private List<X509Certificate> certificates = new ArrayList<>();
    private byte[] challenge;
    private CarWifiConfig config;

    public CarplayBootstrap(String deviceName, String deviceUuid, String transportId,
            Listener listener) {
        this.deviceName = deviceName;
        this.deviceUuid = deviceUuid;
        this.transportId = transportId;
        this.listener = listener;
    }

    public boolean identified() {
        return identified;
    }

    public boolean authenticated() {
        return authenticated;
    }

    public boolean modern() {
        return modern;
    }

    public boolean failed() {
        return failed;
    }

    public CarWifiConfig config() {
        return config;
    }

    /** The accessory's advertised message lists, as "0xNNNN" text. */
    public List<String> accessoryMessages(boolean sends) {
        List<String> out = new ArrayList<>();
        for (int id : messageIds(sends ? accessorySends : accessoryReceives)) {
            out.add(String.format("0x%04X", id));
        }
        return out;
    }

    /** Opens the session: the first thing a phone says on the link. */
    public void begin(Sender send) {
        log("bootstrap -> 0x1D00 request identification");
        send.send(MSG_REQUEST_IDENTIFICATION, new ArrayList<Iap2.Parameter>());
    }

    /** Feeds one decoded control message from the car. */
    public void receive(Iap2.Message message, Sender send) {
        if (message == null) {
            return;
        }
        switch (message.command) {
            case MSG_IDENTIFICATION:
                onIdentification(message, send);
                break;
            case MSG_IDENTIFICATION_REJECTED:
                failed = true;
                log("bootstrap <- 0x1D03 the car rejected identification");
                break;
            case MSG_AUTH_CERTIFICATE:
                onCertificate(message, send);
                break;
            case MSG_AUTH_SIGNATURE:
                onSignature(message, send);
                break;
            case MSG_AUTH_FAILED:
                failed = true;
                log("bootstrap <- 0xAA04 the car reported authentication failure");
                break;
            case MSG_WIRELESS_START:
            case MSG_WIRELESS_WIFI:
                onWireless(message);
                break;
            case MSG_WIRELESS_REQUEST:
                log("bootstrap <- 0x5700 the car asks for the wireless status");
                send.send(MSG_WIRELESS_REQUEST_ANSWER,
                        one(0, new byte[] {2}));
                break;
            case MSG_WIRELESS_AVAILABILITY:
                log("bootstrap <- 0x4300 wireless availability");
                break;
            case MSG_NOW_PLAYING_SUBSCRIBE:
                log("bootstrap <- 0x5000 the car subscribes to now playing ("
                        + "media=" + describedIds(message, 0)
                        + " playback=" + describedIds(message, 1) + ")");
                break;
            case MSG_NOW_PLAYING_UNSUBSCRIBE:
                log("bootstrap <- 0x5002 the car unsubscribes from now playing");
                break;
            default:
                log(String.format("bootstrap <- unhandled 0x%04X parameters=%s",
                        message.command, parameterIds(message)));
                break;
        }
    }

    // --------------------------------------------------------------- identity

    private void onIdentification(Iap2.Message message, Sender send) {
        accessorySends = parametersById(message, IDENTIFICATION_SENDS);
        accessoryReceives = parametersById(message, IDENTIFICATION_RECEIVES);
        if (accessorySends == null) {
            accessorySends = new ArrayList<>();
        }
        if (accessoryReceives == null) {
            accessoryReceives = new ArrayList<>();
        }
        List<Integer> sends = messageIds(accessorySends);
        List<Integer> receives = messageIds(accessoryReceives);
        // "modern" means the car both sends 0x4301 and accepts 0x4300
        modern = sends.contains(MSG_WIRELESS_START)
                && receives.contains(MSG_WIRELESS_AVAILABILITY);
        log("bootstrap <- 0x1D01 the car sends " + ids(sends) + " and receives " + ids(receives)
                + "; modern=" + modern
                + " start=" + sends.contains(MSG_WIRELESS_START)
                + " availability=" + receives.contains(MSG_WIRELESS_AVAILABILITY));
        identified = true;
        send.send(MSG_IDENTIFICATION_ACCEPTED, new ArrayList<Iap2.Parameter>());
        if (!certificateRequested) {
            certificateRequested = true;
            log("bootstrap -> 0xAA00 request authentication");
            send.send(MSG_REQUEST_AUTHENTICATION, new ArrayList<Iap2.Parameter>());
        }
    }

    // ----------------------------------------------------------- MFi handshake

    private void onCertificate(Iap2.Message message, Sender send) {
        byte[] chain = message.find(0) == null ? null : message.find(0).value;
        if (chain == null || chain.length == 0) {
            log("bootstrap <- 0xAA01 without a certificate in parameter 0; unsupported");
            return;
        }
        try {
            certificates = new ArrayList<>();
            for (java.security.cert.Certificate parsed : CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(chain))) {
                certificates.add((X509Certificate) parsed);
            }
            X509Certificate leaf = leafCertificate();
            if (leaf == null) {
                log("bootstrap <- 0xAA01 with no leaf certificate; stopping");
                failed = true;
                return;
            }
            log("bootstrap <- 0xAA01 certificate bytes=" + chain.length
                    + " chain=" + certificates.size() + " key=" + leaf.getPublicKey().getAlgorithm()
                    + " subject=" + leaf.getSubjectDN()
                    + "; trust-chain deliberately not verified");
            challenge = newChallenge(leaf.getPublicKey());
            log("bootstrap -> 0xAA02 challenge " + challenge.length + " bytes");
            send.send(MSG_AUTH_CHALLENGE, one(0, challenge));
        } catch (Throwable error) {
            log("bootstrap cannot read the car's certificate: " + error);
            failed = true;
        }
    }

    private void onSignature(Iap2.Message message, Sender send) {
        if (challenge == null) {
            log("bootstrap <- 0xAA03 without us having sent a challenge; ignored");
            return;
        }
        byte[] response = message.find(0) == null ? null : message.find(0).value;
        X509Certificate leaf = leafCertificate();
        boolean valid = response != null && leaf != null
                && verifyChallenge(leaf, challenge, response);
        log("bootstrap <- 0xAA03 signature valid=" + valid
                + "; the accessory proved it holds the private key");
        if (!valid) {
            send.send(MSG_AUTH_FAILED, new ArrayList<Iap2.Parameter>());
            failed = true;
            return;
        }
        authenticated = true;
        send.send(MSG_AUTH_SUCCEEDED, new ArrayList<Iap2.Parameter>());
        if (identified) {
            publish(send);
        }
    }

    private void publish(Sender send) {
        if (availabilityPublished) {
            return;
        }
        availabilityPublished = true;
        send.send(MSG_DEVICE_NAME, one(0, text(deviceName)));
        send.send(MSG_LANGUAGE, one(0, text("zh")));
        send.send(MSG_DEVICE_UUID, one(0, text(deviceUuid)));
        send.send(MSG_TRANSPORT_ID, one(0, text(transportId)));
        send.send(MSG_TRANSPORT_FLAG, one(0, new byte[] {1}));
        if (modern) {
            send.send(MSG_WIRELESS_AVAILABILITY, availability());
        }
        send.send(MSG_WIRELESS_START_REQUEST, new ArrayList<Iap2.Parameter>());
        log("bootstrap announced as " + deviceName + " and asked for the Wi-Fi configuration");
        listener.onReady();
    }

    // -------------------------------------------------------------- wireless

    private void onWireless(Iap2.Message message) {
        if (!authenticated || !identified) {
            log(String.format("bootstrap <- 0x%04X before authentication; not joining",
                    message.command));
            return;
        }
        CarWifiConfig incoming;
        if (message.command == MSG_WIRELESS_START) {
            incoming = parseStart(message);
        } else {
            incoming = parseWifi(message);
        }
        if (incoming == null) {
            return;
        }
        // a later flat message may carry only credentials: keep what we already know
        CarWifiConfig merged = config == null ? incoming : config.merge(incoming);
        if (config != null && sameMetadata(config, merged)) {
            log("bootstrap the car repeated the same Wi-Fi configuration; keeping the session");
            return;
        }
        config = merged;
        log("bootstrap Wi-Fi from 0x" + Integer.toHexString(message.command) + ": "
                + merged.describe());
        listener.onConfig(merged);
    }

    private CarWifiConfig parseStart(Iap2.Message message) {
        byte[] nested = message.find(1) == null ? null : message.find(1).value;
        if (nested == null) {
            log("bootstrap 0x4301 carried no wireless parameters");
            return null;
        }
        // the reference insists the port is four bytes and lands in 1..65535;
        // a message that says otherwise is dropped rather than half used
        byte[] portBytes = message.find(2) == null ? null : message.find(2).value;
        int port = 0;
        if (portBytes != null) {
            if (portBytes.length != 4) {
                log("bootstrap 0x4301 named a port that is not four bytes");
                return null;
            }
            long value = readU32(portBytes);
            if (value < 1 || value > 65535) {
                log("bootstrap 0x4301 named an out of range port " + value);
                return null;
            }
            port = (int) value;
        }
        byte[] mutual = message.find(8) == null ? null : message.find(8).value;
        if (mutual != null && mutual.length > 0 && mutual[0] == 1) {
            log("bootstrap 0x4301 requires mutual authentication, which this side cannot do");
            return null;
        }
        List<Iap2.Parameter> inner;
        try {
            inner = Iap2.decodeControl(nested).parameters;
        } catch (RuntimeException error) {
            log("bootstrap 0x4301 nested parameters do not decode: " + error);
            return null;
        }
        return config(inner, 0, 1, 4, 2, 3, port,
                string(valueOf(message.find(3))), string(valueOf(message.find(4))));
    }

    private CarWifiConfig parseWifi(Iap2.Message message) {
        return config(message.parameters, 1, 2, 3, 4, -1, 0, null, null);
    }

    private CarWifiConfig config(List<Iap2.Parameter> parameters, int ssidField, int passwordField,
            int securityField, int channelField, int addressField, int port,
            String receiverId, String receiverPublicKey) {
        String ssid = string(field(parameters, ssidField));
        if (ssid == null || ssid.isEmpty()) {
            log("bootstrap the car's wireless message has no SSID; ignored");
            return null;
        }
        String password = string(field(parameters, passwordField));
        if (password == null) {
            password = "";
        }
        int security = byteField(parameters, securityField, 0);
        if (security != CarWifiConfig.SECURITY_OPEN
                && security != CarWifiConfig.SECURITY_WPA
                && security != CarWifiConfig.SECURITY_WPA_ENTERPRISE
                && security != CarWifiConfig.SECURITY_WPA3) {
            log("bootstrap unsupported Wi-Fi security " + security + " (WEP is not supported)");
            return null;
        }
        if (!password.isEmpty() && password.length() < 8) {
            log("bootstrap the Wi-Fi credential is shorter than 8 characters; refused");
            return null;
        }
        List<String> addresses = new ArrayList<>();
        if (addressField >= 0) {
            for (Iap2.Parameter parameter : parameters) {
                if (parameter.id == addressField && addresses.size() < 16) {
                    String address = string(parameter.value);
                    if (address != null && !address.isEmpty()) {
                        addresses.add(address);
                    }
                }
            }
        }
        return new CarWifiConfig(ssid, password, security,
                byteField(parameters, channelField, 0), addresses, port, receiverId,
                receiverPublicKey);
    }

    // ------------------------------------------------------------------ crypto

    /** 32 bytes for P-256, 20 for RSA: the digest the accessory signs directly. */
    static byte[] newChallenge(PublicKey key) {
        int length = key instanceof ECPublicKey ? 32 : 20;
        byte[] value = new byte[length];
        new SecureRandom().nextBytes(value);
        return value;
    }

    /**
     * Verifies the car's signature over the challenge.
     *
     * Both key types sign the challenge as an already computed digest, so the
     * "NONEwith" algorithms are the right ones. A 64 byte ECDSA response is the
     * raw (r, s) pair and is wrapped into DER first; BouncyCastle builds it, the
     * same library the reference app uses.
     */
    static boolean verifyChallenge(X509Certificate certificate, byte[] challenge,
            byte[] response) {
        return verifyChallenge(certificate.getPublicKey(), challenge, response);
    }

    /** The verification itself, over whatever key the car proved it holds. */
    static boolean verifyChallenge(PublicKey key, byte[] challenge, byte[] response) {
        try {
            if (key instanceof ECPublicKey) {
                ECPublicKey ec = (ECPublicKey) key;
                if (ec.getParams().getOrder().bitLength() != 256 || challenge.length != 32) {
                    Logs.add("bootstrap: unsupported EC curve or challenge length");
                    return false;
                }
                byte[] der = response.length == 64 ? rawToDer(response) : response;
                Signature signature = Signature.getInstance("NONEwithECDSA");
                signature.initVerify(key);
                signature.update(challenge);
                return signature.verify(der);
            }
            if (key instanceof RSAPublicKey) {
                byte[] prefix;
                if (challenge.length == 20) {
                    prefix = DIGEST_INFO_SHA1;
                } else if (challenge.length == 32) {
                    prefix = DIGEST_INFO_SHA256;
                } else {
                    Logs.add("bootstrap: unsupported RSA challenge length " + challenge.length);
                    return false;
                }
                Signature signature = Signature.getInstance("NONEwithRSA");
                signature.initVerify(key);
                signature.update(concat(prefix, challenge));
                return signature.verify(response);
            }
            Logs.add("bootstrap: the car's certificate holds an unsupported key");
            return false;
        } catch (Throwable error) {
            Logs.add("bootstrap cannot verify the car's signature: " + error);
            return false;
        }
    }

    private static final byte[] DIGEST_INFO_SHA1 = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E, 0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14
    };

    private static final byte[] DIGEST_INFO_SHA256 = {
        0x30, 0x31, 0x30, 0x0D, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02,
        0x01, 0x05, 0x00, 0x04, 0x20
    };

    private static byte[] rawToDer(byte[] raw) {
        org.bouncycastle.asn1.ASN1Integer[] parts = {
            new org.bouncycastle.asn1.ASN1Integer(new java.math.BigInteger(1,
                    Arrays.copyOfRange(raw, 0, 32))),
            new org.bouncycastle.asn1.ASN1Integer(new java.math.BigInteger(1,
                    Arrays.copyOfRange(raw, 32, 64)))
        };
        try {
            return new org.bouncycastle.asn1.DERSequence(parts).getEncoded();
        } catch (java.io.IOException error) {
            throw new IllegalStateException("cannot encode the signature: " + error);
        }
    }

    private X509Certificate leafCertificate() {
        for (X509Certificate certificate : certificates) {
            if (certificate.getBasicConstraints() < 0) {
                return certificate;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private List<Iap2.Parameter> availability() {
        List<Iap2.Parameter> inner = new ArrayList<>();
        inner.add(new Iap2.Parameter(0, new byte[] {1}));
        inner.add(new Iap2.Parameter(1, text(transportId)));
        byte[] full = Iap2.control(0, inner);
        return one(1, Arrays.copyOfRange(full, 6, full.length));
    }

    static List<Iap2.Parameter> one(int id, byte[] value) {
        List<Iap2.Parameter> out = new ArrayList<>();
        out.add(new Iap2.Parameter(id, value));
        return out;
    }

    /** Copies a nested parameter section into a fresh list. */
    static List<Iap2.Parameter> parametersById(Iap2.Message message, int id) {
        List<Iap2.Parameter> out = new ArrayList<>();
        for (Iap2.Parameter parameter : message.parameters) {
            if (parameter.id == id) {
                out.add(parameter);
            }
        }
        return out;
    }

    /** Reads u16 pairs, the way the capability lists are encoded. */
    static List<Integer> messageIds(List<Iap2.Parameter> list) {
        List<Integer> out = new ArrayList<>();
        for (Iap2.Parameter parameter : list) {
            byte[] value = parameter.value;
            if (value == null || value.length % 2 != 0) {
                continue;
            }
            for (int offset = 0; offset + 1 < value.length; offset += 2) {
                out.add(((value[offset] & 0xFF) << 8) | (value[offset + 1] & 0xFF));
            }
        }
        return out;
    }

    private static String describedIds(Iap2.Message message, int id) {
        Iap2.Parameter parameter = message.find(id);
        if (parameter == null) {
            return "absent";
        }
        return ids(messageIds(one(parameter.id, parameter.value)));
    }

    private static String parameterIds(Iap2.Message message) {
        List<Integer> ids = new ArrayList<>();
        for (Iap2.Parameter parameter : message.parameters) {
            ids.add(parameter.id);
        }
        return ids.toString();
    }

    private static String ids(List<Integer> values) {
        StringBuilder text = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(String.format("0x%04X", values.get(index)));
        }
        return text.append(']').toString();
    }

    /** The value of a parameter, or null when the message does not carry it. */
    static byte[] valueOf(Iap2.Parameter parameter) {
        return parameter == null ? null : parameter.value;
    }

    static byte[] field(List<Iap2.Parameter> parameters, int id) {
        for (Iap2.Parameter parameter : parameters) {
            if (parameter.id == id) {
                return parameter.value;
            }
        }
        return null;
    }

    static int byteField(List<Iap2.Parameter> parameters, int id, int fallback) {
        byte[] value = field(parameters, id);
        return value == null || value.length == 0 ? fallback : (value[0] & 0xFF);
    }

    /** A "CSM string": UTF-8 up to the first NUL. */
    static String string(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }

    static byte[] text(String value) {
        byte[] body = value == null ? new byte[0]
                : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return concat(body, new byte[] {0});
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static long readU32(byte[] bytes) {
        long value = 0;
        for (int index = 0; index < 4; index++) {
            value = (value << 8) | (bytes[index] & 0xFF);
        }
        return value;
    }

    private static boolean sameMetadata(CarWifiConfig first, CarWifiConfig second) {
        return first.port == second.port
                && first.security == second.security
                && first.channel == second.channel
                && first.ssid.equals(second.ssid)
                && first.addresses.equals(second.addresses);
    }

    private void log(String message) {
        listener.onLog(message);
    }
}
