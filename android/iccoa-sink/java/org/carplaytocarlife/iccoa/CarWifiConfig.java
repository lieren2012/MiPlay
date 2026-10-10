package org.carplaytocarlife.iccoa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the car hands over during the iAP2 bootstrap, once it has decided this
 * phone may join: the Wi-Fi credentials of the network it opened for CarPlay,
 * and - in the same message - the AirPlay receiver's address and port.
 *
 * That is why mDNS is only a fallback here: a car that answers the bootstrap
 * tells us exactly where to connect.
 */
public final class CarWifiConfig {

    /** Security values the reference implementation accepts (WEP is refused). */
    public static final int SECURITY_OPEN = 0;
    public static final int SECURITY_WPA = 2;
    public static final int SECURITY_WPA_ENTERPRISE = 3;
    public static final int SECURITY_WPA3 = 4;

    public final String ssid;
    public final String password;
    public final int security;
    public final int channel;
    public final List<String> addresses;
    public final int port;
    public final String receiverId;
    public final String receiverPublicKey;

    public CarWifiConfig(String ssid, String password, int security, int channel,
            List<String> addresses, int port, String receiverId, String receiverPublicKey) {
        this.ssid = ssid == null ? "" : ssid;
        this.password = password == null ? "" : password;
        this.security = security;
        this.channel = channel;
        this.addresses = addresses == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(addresses));
        this.port = port;
        this.receiverId = receiverId;
        this.receiverPublicKey = receiverPublicKey;
    }

    public boolean isOpen() {
        return security == SECURITY_OPEN;
    }

    public boolean hasEndpoint() {
        return port > 0 && !addresses.isEmpty();
    }

    public String firstAddress() {
        return addresses.isEmpty() ? null : addresses.get(0);
    }

    /**
     * Fills in what a later message left out, the way the reference merges an
     * incoming configuration with the one already held.
     */
    public CarWifiConfig merge(CarWifiConfig incoming) {
        if (incoming == null) {
            return this;
        }
        String mergedSsid = incoming.ssid.isEmpty() ? ssid : incoming.ssid;
        String mergedPassword = incoming.password.isEmpty() ? password : incoming.password;
        int mergedSecurity = incoming.security == 0 && this.security != 0 && incoming.password.isEmpty()
                ? this.security : incoming.security;
        int mergedChannel = incoming.channel == 0 ? channel : incoming.channel;
        List<String> mergedAddresses = incoming.addresses.isEmpty() ? addresses : incoming.addresses;
        int mergedPort = incoming.port == 0 ? port : incoming.port;
        String mergedReceiverId = incoming.receiverId == null ? receiverId : incoming.receiverId;
        String mergedReceiverKey = incoming.receiverPublicKey == null
                ? receiverPublicKey : incoming.receiverPublicKey;
        return new CarWifiConfig(mergedSsid, mergedPassword, mergedSecurity, mergedChannel,
                mergedAddresses, mergedPort, mergedReceiverId, mergedReceiverKey);
    }

    /** Log line that deliberately leaves the password out. */
    public String describe() {
        return "ssid=" + ssid + " credential=" + (password.isEmpty() ? "open" : "present")
                + " security=" + security + " channel=" + channel
                + " endpoints=" + addresses + " port=" + port
                + " receiver=" + receiverId;
    }
}
