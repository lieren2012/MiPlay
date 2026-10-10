package org.carplaytocarlife.iccoa;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;

import java.util.Collections;

/**
 * Joins the Wi-Fi network the car opened for CarPlay.
 *
 * The credentials arrive over Bluetooth (see {@link CarplayBootstrap}); from
 * here on the phone has to get on that network by itself, with no dialog, or
 * the hand-off to the AirPlay session never happens. On Android 10 and up that
 * is what network suggestions are for: the system picks the network up on its
 * own. Older releases take the deprecated explicit configuration path.
 */
public final class CarWifi {

    public interface Listener {
        void onLog(String message);

        void onConnected(String ssid, String localAddress);

        void onFailed(String reason);
    }

    private final Context context;
    private final Listener listener;

    private WifiNetworkSuggestion suggestion;
    private ConnectivityManager.NetworkCallback callback;
    private String targetSsid;
    private volatile boolean running;
    private boolean reported;

    public CarWifi(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    /** Asks the system to join the car's network, or reports that we are on it. */
    public boolean connect(CarWifiConfig config) {
        if (config == null || config.ssid.isEmpty()) {
            listener.onFailed("the car sent no SSID");
            return false;
        }
        Object service = context.getSystemService(Context.WIFI_SERVICE);
        if (!(service instanceof WifiManager)) {
            listener.onFailed("this device has no WifiManager");
            return false;
        }
        WifiManager wifi = (WifiManager) service;
        targetSsid = config.ssid;
        running = true;
        reported = false;
        String current = currentSsid(wifi);
        listener.onLog("Wi-Fi: currently on " + (current == null ? "nothing" : current)
                + ", the car wants " + config.ssid);
        if (config.ssid.equals(current)) {
            reportConnected(wifi);
            return true;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                addSuggestion(wifi, config);
            } else {
                addLegacyNetwork(wifi, config);
            }
        } catch (Throwable error) {
            listener.onFailed("cannot ask the system to join " + config.ssid + ": " + error);
            return false;
        }
        watchConnectivity();
        return true;
    }

    private void addSuggestion(WifiManager wifi, CarWifiConfig config) {
        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                .setSsid(config.ssid);
        if (config.isOpen()) {
            // an open network needs no credential
            builder.setIsHiddenSsid(false);
        } else if (config.security == CarWifiConfig.SECURITY_WPA3) {
            builder.setWpa3Passphrase(config.password);
        } else {
            builder.setWpa2Passphrase(config.password);
        }
        suggestion = builder.build();
        int status = wifi.addNetworkSuggestions(Collections.singletonList(suggestion));
        boolean accepted = status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
        listener.onLog("Wi-Fi: suggested " + config.ssid
                + (config.isOpen() ? " (open)" : " (WPA2/WPA3, credential supplied)")
                + " accepted=" + accepted + " status=" + status);
        if (!accepted) {
            listener.onFailed("the system refused the network suggestion (status " + status + ")");
        }
    }

    @SuppressWarnings("deprecation")
    private void addLegacyNetwork(WifiManager wifi, CarWifiConfig config) {
        WifiConfiguration legacy = new WifiConfiguration();
        legacy.SSID = '"' + config.ssid + '"';
        if (config.isOpen()) {
            legacy.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            legacy.preSharedKey = '"' + config.password + '"';
        }
        int id = wifi.addNetwork(legacy);
        listener.onLog("Wi-Fi: added " + config.ssid + " as network " + id);
        if (id >= 0) {
            wifi.enableNetwork(id, true);
        } else {
            listener.onFailed("the system refused the network configuration");
        }
    }

    private void watchConnectivity() {
        Object service = context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!(service instanceof ConnectivityManager)) {
            return;
        }
        ConnectivityManager manager = (ConnectivityManager) service;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                check();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                check();
            }
        };
        try {
            manager.registerDefaultNetworkCallback(callback);
        } catch (Throwable error) {
            listener.onLog("Wi-Fi: cannot watch the connection: " + error);
        }
    }

    /** Reports the connection once, the first time the car's SSID is really up. */
    private void check() {
        Object service = context.getSystemService(Context.WIFI_SERVICE);
        if (!(service instanceof WifiManager) || reported || !running) {
            return;
        }
        reportConnected((WifiManager) service);
    }

    private void reportConnected(WifiManager wifi) {
        String ssid = currentSsid(wifi);
        if (ssid == null || !ssid.equals(targetSsid)) {
            return;
        }
        reported = true;
        String local = localAddress(wifi);
        listener.onLog("Wi-Fi: joined " + ssid + " as " + local);
        listener.onConnected(ssid, local);
    }

    private static String currentSsid(WifiManager wifi) {
        try {
            WifiInfo info = wifi.getConnectionInfo();
            if (info == null || info.getSSID() == null) {
                return null;
            }
            String ssid = info.getSSID().replace("\"", "");
            return "<unknown ssid>".equals(ssid) ? null : ssid;
        } catch (Throwable error) {
            return null;
        }
    }

    private static String localAddress(WifiManager wifi) {
        try {
            WifiInfo info = wifi.getConnectionInfo();
            int value = info == null ? 0 : info.getIpAddress();
            return ((value & 0xFF) + "." + ((value >> 8) & 0xFF) + "."
                    + ((value >> 16) & 0xFF) + "." + ((value >> 24) & 0xFF));
        } catch (Throwable error) {
            return "unknown";
        }
    }

    public void close() {
        running = false;
        if (callback != null) {
            Object service = context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (service instanceof ConnectivityManager) {
                try {
                    ((ConnectivityManager) service).unregisterNetworkCallback(callback);
                } catch (Throwable ignored) {
                    // best effort while shutting down
                }
            }
            callback = null;
        }
        if (suggestion != null && Build.VERSION.SDK_INT >= 29) {
            Object service = context.getSystemService(Context.WIFI_SERVICE);
            if (service instanceof WifiManager) {
                try {
                    ((WifiManager) service).removeNetworkSuggestions(
                            Collections.singletonList(suggestion));
                } catch (Throwable ignored) {
                    // best effort while shutting down
                }
            }
            suggestion = null;
        }
        targetSsid = null;
    }
}
