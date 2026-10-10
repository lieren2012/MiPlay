package org.carplaytocarlife.iccoa;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

/**
 * Finds the car's AirPlay receiver over mDNS.
 *
 * A wireless CarPlay head unit publishes an AirPlay service; browsing for it is
 * how the phone learns the address and port to connect to. The reference app
 * does the same from its network package (ModernCarDiscovery / LegacyCarDiscovery).
 */
public final class AirplayBootstrap {

    public static final String SERVICE_TYPE = "_airplay._tcp";
    public static final String LEGACY_SERVICE_TYPE = "_raop._tcp";

    public interface Listener {
        void onLog(String message);

        void onReceiverFound(String host, int port, String name);
    }

    private final NsdManager nsd;
    private final Listener listener;
    /**
     * Every discovery this instance started.
     *
     * One field was not enough: the loop below registers two service types, and
     * keeping only the last one left the first discovery running for the life of
     * the process - so a second start registered it again, and a third, until
     * some ROMs refuse the registration outright.
     */
    private final java.util.List<NsdManager.DiscoveryListener> discoveries =
            new java.util.ArrayList<>();

    public AirplayBootstrap(Context context, Listener listener) {
        this.listener = listener;
        Object service = context.getSystemService(Context.NSD_SERVICE);
        this.nsd = service instanceof NsdManager ? (NsdManager) service : null;
    }

    public void discover() {
        if (nsd == null) {
            log("NSD unavailable; the AirPlay receiver cannot be discovered");
            return;
        }
        for (String type : new String[] {SERVICE_TYPE, LEGACY_SERVICE_TYPE}) {
            NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    log("mDNS discovery failed for " + serviceType + " (error " + errorCode + ")");
                }
                @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }
                @Override public void onDiscoveryStarted(String serviceType) {
                    log("mDNS browsing " + serviceType);
                }
                @Override public void onDiscoveryStopped(String serviceType) {
                }
                @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
                    log("AirPlay receiver lost: " + serviceInfo.getServiceName());
                }
                @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                    log("AirPlay candidate: " + serviceInfo.getServiceName()
                            + " (" + serviceInfo.getServiceType() + ")");
                    resolve(serviceInfo);
                }
            };
            discoveries.add(listener);
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (Throwable error) {
                discoveries.remove(listener);
                log("cannot browse " + type + ": " + error);
            }
        }
    }

    private void resolve(NsdServiceInfo serviceInfo) {
        nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                log("resolve failed for " + info.getServiceName() + " (error " + errorCode + ")");
            }
            @Override public void onServiceResolved(NsdServiceInfo info) {
                String host = info.getHost() == null ? null : info.getHost().getHostAddress();
                if (host == null || info.getPort() <= 0) {
                    log("resolved " + info.getServiceName() + " without a usable endpoint");
                    return;
                }
                log("AirPlay receiver resolved: " + info.getServiceName()
                        + " at " + host + ":" + info.getPort());
                AirplayBootstrap.this.listener.onReceiverFound(host, info.getPort(),
                        info.getServiceName());
            }
        });
    }

    public void stop() {
        if (nsd == null) {
            discoveries.clear();
            return;
        }
        if (!discoveries.isEmpty()) {
            log("stopping " + discoveries.size() + " discovery subscription(s)");
        }
        for (NsdManager.DiscoveryListener listener : discoveries) {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Throwable ignored) {
                // best effort while shutting down
            }
        }
        discoveries.clear();
    }

    private void log(String message) {
        listener.onLog(message);
    }
}
