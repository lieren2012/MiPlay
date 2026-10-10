package org.carplaytocarlife.iccoa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * Hosts the ICCOA Carlink sink: RTSP session, RTP video, UIBC input.
 *
 * The CarPlay side of the bridge is not implemented here; this half receives a
 * phone that projects through ICCOA Carlink and shows it.
 */
public final class SinkService extends Service implements WfdSession.Listener {

    public static final String ACTION_START = "org.carplaytocarlife.iccoa.START";
    public static final String ACTION_STOP = "org.carplaytocarlife.iccoa.STOP";
    public static final String EXTRA_PROJECTION_RESULT = "projection-result";
    public static final String EXTRA_PROJECTION_DATA = "projection-data";
    private static final String CHANNEL_ID = "iccoa-sink";
    private static final int NOTIFICATION_ID = 1;
    private static final String[] SERVICE_TYPES = {"_wfd._tcp", "_carlink._tcp"};

    private static SinkService instance;

    private WfdSession session;
    private RtspServer server;
    private RtpReceiver receiver;
    private H264Decoder decoder;
    private EasyConnSource easyConn;
    private UibcChannel uibc;
    private NsdManager nsd;
    private CarplayBluetooth carplayBluetooth;
    private AirplayBootstrap bootstrap;
    private AirplayClient airplayClient;
    private H264Encoder encoder;
    private VideoSender videoSender;
    private AirplayVideoTunnel videoTunnel;
    private CarWifi carWifi;
    private CarWifiConfig carConfig;
    private CarplayControlListener invited;
    private CarplayAdvertiser advertiser;
    private Iap2Reader.ControlReader tunnelReader;
    private boolean tunnelHandedOver;
    private SurfaceEncoder surfaceEncoder;
    private boolean videoConfigSent;
    /** How many times a failed session is rebuilt before this side gives up. */
    public static final int MAX_SESSION_RETRIES = 3;
    /**
     * How long to wait between attempts.
     *
     * The reference's reconnection cadence is twenty seconds a round
     * ("Next-round RFCOMM connect timeout=20000ms"), which is a sane wait for a
     * car that just dropped a session.
     */
    public static final long RETRY_DELAY_MILLIS = 20000;

    /** True when another attempt is worth making. */
    public static boolean shouldRetry(int attempts, int maximum) {
        return attempts < maximum;
    }

    private int sessionAttempts;
    /** Bumped on every start and stop, so a stale retry cannot touch a new run. */
    private volatile int generation;
    private int videoWidth = AirplayMessages.VIDEO_WIDTH;
    private int videoHeight = AirplayMessages.VIDEO_HEIGHT;
    private int videoFps = SurfaceEncoder.PREFERRED_FPS;
    private int videoDensity = 160;
    private ScreenSource screen;
    private android.content.Intent projectionData;
    private int projectionResultCode;
    private Thread audioThread;
    private volatile boolean audioRunning;
    private java.net.DatagramSocket videoSocket;
    private String carHost;
    private int carVideoPort = 7100;
    private int sourceWidth = 1280;
    private int sourceHeight = 720;
    private int sourceFps = 30;
    private byte[] sourceKeyframe;
    private long sourceKeyframeTimestampUs;
    private final java.util.List<NsdManager.RegistrationListener> registrations = new java.util.ArrayList<>();

    public static SinkService instance() {
        return instance;
    }

    public static void start(Context context) {
        start(context, 0, null);
    }

    /** Starts the service, handing over the screen capture consent if there is one. */
    public static void start(Context context, int resultCode, Intent projection) {
        Intent intent = new Intent(context, SinkService.class);
        intent.setAction(ACTION_START);
        if (projection != null) {
            intent.putExtra(EXTRA_PROJECTION_RESULT, resultCode);
            intent.putExtra(EXTRA_PROJECTION_DATA, projection);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, SinkService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Logs.add("service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSink();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra(EXTRA_PROJECTION_DATA)) {
            projectionResultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT, 0);
            projectionData = intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
            Logs.add("screen capture consent received (result=" + projectionResultCode + ")");
        }
        startForegroundTyped(NOTIFICATION_ID, buildNotification(), foregroundType());
        startSink();
        return START_STICKY;
    }

    /**
     * The foreground type this start needs.
     *
     * mediaProjection is only claimed once the user has actually consented:
     * Android 14 rejects the type when there is no projection behind it.
     */
    private int foregroundType() {
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (Build.VERSION.SDK_INT < 29) {
            return type;
        }
        return projectionData == null
                ? type
                : type | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
    }

    /** startForeground with a type needs API 29; older releases take two arguments. */
    private void startForegroundTyped(int id, Notification notification, int type) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(id, notification, type);
        } else {
            startForeground(id, notification);
        }
    }

    @Override
    public void onDestroy() {
        stopSink();
        instance = null;
        Logs.add("service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void setSurface(Surface surface) {
        if (decoder != null) {
            decoder.setSurface(surface);
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public WfdSession session() {
        return session;
    }

    public void sendTouch(int action, int x, int y) {
        if (uibc != null) {
            uibc.touch(action, x, y);
        }
    }

    private void startSink() {
        if (server != null) {
            return;
        }
        generation++;
        sessionAttempts = 0;
        session = new WfdSession(this);
        server = new RtspServer(RtspServer.DEFAULT_PORT, session);
        decoder = new H264Decoder();
        try {
            server.start();
        } catch (Exception error) {
            Logs.add("cannot start RTSP server: " + error);
            server = null;
            return;
        }
        receiver = new RtpReceiver(session.rtpPort(), (annexB, timestampUs) -> {
            H264Decoder target = decoder;
            if (target != null) {
                target.feed(annexB, timestampUs);
            }
        });
        try {
            receiver.start();
        } catch (Exception error) {
            Logs.add("cannot start RTP receiver: " + error);
        }
        List<String> addresses = localAddresses();
        Logs.add("local addresses: " + addresses);
        Logs.add("waiting for a phone: RTSP tcp/" + RtspServer.DEFAULT_PORT
                + ", RTP udp/" + session.rtpPort());
        startEasyConnSource();
        advertise(advertisedName(), RtspServer.DEFAULT_PORT);
        startCarplayIdentity();
        Logs.add(TouchInjector.ready()
                ? "input injection is ready; the car can drive this phone"
                : "input injection is off: enable the input service in accessibility settings");
        startDiscovery();
    }

    /** Starts the phone-local EasyConnection source used by the one-phone path. */
    private void startEasyConnSource() {
        if (easyConn != null) {
            return;
        }
        easyConn = new EasyConnSource(this, new EasyConnSource.Listener() {
            @Override
            public void onLog(String message) {
                Logs.add(message);
            }

            @Override
            public void onFormat(int width, int height, int fps) {
                sourceWidth = width;
                sourceHeight = height;
                sourceFps = fps;
                Logs.add("易连视频格式: " + width + "x" + height + "@" + fps);
            }

            @Override
            public void onFrame(byte[] annexB, long timestampUs, boolean keyframe) {
                H264Decoder target = decoder;
                if (target != null) {
                    target.feed(annexB, timestampUs);
                }
                if (keyframe) {
                    sourceKeyframe = annexB.clone();
                    sourceKeyframeTimestampUs = timestampUs;
                }
                AirplayVideoTunnel tunnel = videoTunnel;
                if (tunnel != null && tunnel.isConnected()) {
                    sendVideo(annexB, timestampUs, keyframe, carHost, carVideoPort);
                }
            }
        });
        easyConn.start();
        Logs.add("已启用本机易连输入；等待 OPPO 易连端口");
    }

    private void stopSink() {
        generation++;
        unadvertise();
        if (carplayBluetooth != null) {
            carplayBluetooth.stop();
            carplayBluetooth = null;
        }
        stopVideo();
        if (advertiser != null) {
            advertiser.stop();
            advertiser = null;
        }
        if (invited != null) {
            invited.stop();
            invited = null;
        }
        TouchInjector.reset();
        tunnelHandedOver = false;
        tunnelReader = null;
        carConfig = null;
        if (carWifi != null) {
            carWifi.close();
            carWifi = null;
        }
        projectionData = null;
        if (airplayClient != null) {
            airplayClient.stop();
            airplayClient = null;
        }
        if (bootstrap != null) {
            bootstrap.stop();
            bootstrap = null;
        }
        if (receiver != null) {
            receiver.stop();
            receiver = null;
        }
        if (easyConn != null) {
            easyConn.stop();
            easyConn = null;
        }
        sourceKeyframe = null;
        sourceKeyframeTimestampUs = 0;
        if (server != null) {
            server.stop();
            server = null;
        }
        if (decoder != null) {
            decoder.release();
            decoder = null;
        }
        if (uibc != null) {
            uibc.close();
            uibc = null;
        }
        session = null;
    }

    // ---------------------------------------------------------------- session

    @Override
    public void onLog(String message) {
        Logs.add(message);
    }

    @Override
    public void onState(WfdSession.State state) {
        Logs.add("state -> " + state);
    }

    @Override
    public void onStreamingStart() {
        String peer = server == null ? null : server.clientAddress();
        if (peer != null) {
            uibc = new UibcChannel(peer, session.rtpPort() + 2);
            uibc.connect();
        }
    }

    @Override
    public void onStreamingStop() {
        if (uibc != null) {
            uibc.close();
            uibc = null;
        }
    }

    @Override
    public void onInputPort(int port) {
        Logs.add("peer (source) announced UIBC port " + port);
    }

    // ------------------------------------------------------------------ misc

    /**
     * Generates and logs this device's pairing identity. Running once on the
     * phone proves the bundled crypto works there, and the keys are what the
     * pairing exchange will send to the car.
     */
    private void carriageIdentity() {
        try {
            byte[] edSeed = new byte[32];
            byte[] xScalar = new byte[32];
            java.security.SecureRandom random = new java.security.SecureRandom();
            random.nextBytes(edSeed);
            random.nextBytes(xScalar);
            Logs.add("pairing identity ed25519="
                    + CarplayBluetooth.hex(AirplayCrypto.ed25519PublicKey(edSeed), 32));
            Logs.add("pairing identity x25519 ="
                    + CarplayBluetooth.hex(AirplayCrypto.x25519PublicKey(xScalar), 32));
            Logs.add("pairing user=" + AirplayCrypto.SRP_USER
                    + " pin=" + AirplayCrypto.SRP_PASSWORD
                    + " srp group bits=" + AirplayCrypto.SRP_N.bitLength());

            // Prove the whole pairing stack runs on this device: a key schedule,
            // an authenticated blob round trip and an Ed25519 sign/verify.
            byte[] key = AirplayCrypto.hkdfSha512(edSeed,
                    AirplayCrypto.SALT_PAIR_SETUP_ENCRYPT, AirplayCrypto.INFO_PAIR_SETUP_ENCRYPT, 32);
            byte[] blob = AirplayPairing.seal(key, AirplayPairing.PS_MSG05,
                    AirplayCrypto.concat(edSeed, xScalar));
            byte[] opened = AirplayPairing.open(key, AirplayPairing.PS_MSG05, blob);
            byte[] signature = AirplayCrypto.ed25519Sign(edSeed, new byte[] {1, 2, 3});
            boolean ok = opened != null
                    && java.util.Arrays.equals(opened, AirplayCrypto.concat(edSeed, xScalar))
                    && AirplayCrypto.ed25519Verify(AirplayCrypto.ed25519PublicKey(edSeed),
                            new byte[] {1, 2, 3}, signature)
                    && AirplayPairing.open(new byte[32], AirplayPairing.PS_MSG05, blob) == null;
            Logs.add("pairing crypto self-check: " + (ok ? "OK" : "FAILED"));
        } catch (Throwable error) {
            Logs.add("pairing crypto unavailable: " + error);
        }
    }

    /** Publishes the CarPlay SDP records so a stock phone looks like an iPhone. */
    private void startCarplayIdentity() {
        Object manager = getSystemService(Context.BLUETOOTH_SERVICE);
        if (!(manager instanceof android.bluetooth.BluetoothManager)) {
            Logs.add("no Bluetooth service; CarPlay identity not published");
            return;
        }
        carplayBluetooth = new CarplayBluetooth(
                ((android.bluetooth.BluetoothManager) manager).getAdapter(), deviceId());
        carplayBluetooth.setListener(new CarplayBluetooth.Listener() {
            @Override
            public void onLog(String message) {
                Logs.add(message);
            }

            @Override
            public void onPeerConnected(android.bluetooth.BluetoothSocket socket, String description) {
                Logs.add("CarPlay peer connected (" + description + "); RFCOMM link is up");
            }

            @Override
            public void onWirelessConfig(CarWifiConfig config) {
                onCarWireless(config);
            }
        });
        carriageIdentity();
        carplayBluetooth.setName("iPhone");
        carplayBluetooth.start();
        startAdvertising();
    }

    /**
     * The car moved the iAP2 conversation onto the AirPlay connection.
     *
     * The same bootstrap keeps running, only its transport changes: what it
     * sends goes back through the tunnel, and the Bluetooth link stays as the
     * fallback for as long as it is up.
     */
    private void onTunnelBytes(byte[] bytes) {
        CarplayBluetooth bluetooth = carplayBluetooth;
        if (bluetooth == null || bluetooth.bootstrap() == null) {
            Logs.add("iAP bytes arrived on the tunnel but there is no bootstrap to feed");
            return;
        }
        if (!tunnelHandedOver) {
            tunnelHandedOver = true;
            Logs.add("the car handed iAP2 to the AirPlay tunnel; "
                    + "Bluetooth retained as the fallback");
        }
        if (tunnelReader == null) {
            tunnelReader = new Iap2Reader.ControlReader();
        }
        for (Iap2.Message message : tunnelReader.feed(bytes, bytes.length)) {
            Logs.add("iAP tunnel message 0x" + Integer.toHexString(message.command)
                    + " parameters=" + message.parameters.size());
            bluetooth.bootstrap().receive(message, tunnelSender());
        }
    }

    /** The bootstrap's replies: the tunnel first, Bluetooth if that is all there is. */
    private CarplayBootstrap.Sender tunnelSender() {
        return new CarplayBootstrap.Sender() {
            @Override
            public void send(int messageId, java.util.List<Iap2.Parameter> parameters) {
                AirplayClient session = airplayClient;
                if (session != null && session.tunnelArmed()
                        && session.tunnelIap(Iap2.control(messageId, parameters))) {
                    return;
                }
                CarplayBluetooth bluetooth = carplayBluetooth;
                if (bluetooth != null && bluetooth.sendControl(messageId, parameters)) {
                    Logs.add("iAP 0x" + Integer.toHexString(messageId)
                            + " went out over Bluetooth");
                    return;
                }
                Logs.add("iAP 0x" + Integer.toHexString(messageId)
                        + " has no transport: tunnel closed and Bluetooth down");
            }
        };
    }

    /**
     * Advertises this phone as a CarPlay controller on the local network.
     *
     * The port has to exist before it is advertised, so the listener starts
     * first; whatever the car sends on it is logged as bytes.
     */
    private void startAdvertising() {
        invited = new CarplayControlListener(new CarplayControlListener.Listener() {
            @Override
            public void onLog(String message) {
                Logs.add(message);
            }

            @Override
            public void onControlBytes(java.net.Socket socket, byte[] bytes, int length) {
                Logs.add("the car said " + length + " bytes on the invited port");
            }
        });
        if (!invited.start()) {
            invited = null;
            return;
        }
        advertiser = new CarplayAdvertiser(this, invited.port(), deviceId(),
                new CarplayAdvertiser.Listener() {
                    @Override
                    public void onLog(String message) {
                        Logs.add(message);
                    }
                });
        advertiser.start();
    }

    /** Called by the ACL_CONNECTED receiver. */
    public void onCarConnected(String address) {
        if (carplayBluetooth != null) {
            carplayBluetooth.onDeviceConnected(address);
        }
    }

    /** Browses for the car's AirPlay receiver and starts a session when found. */
    private void startDiscovery() {
        bootstrap = new AirplayBootstrap(this, new AirplayBootstrap.Listener() {
            @Override
            public void onLog(String message) {
                Logs.add(message);
            }

            @Override
            public void onReceiverFound(String host, int port, String name) {
                if (airplayClient != null) {
                    return;
                }
                Logs.add("starting an AirPlay session with " + name + " at " + host + ":" + port);
                carHost = host;
                startAirplaySession(host, port);
            }
        });
        bootstrap.discover();
    }

    /** The phone identity the car sees; also the iAP2 device uuid. */
    private String deviceId() {
        String id = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        if (id == null || id.length() < 12) {
            id = "00:11:22:33:44:55";
        }
        return id;
    }

    /**
     * The car offered its network and told us where its AirPlay receiver sits.
     *
     * Joining has to happen first: the endpoint is only reachable from inside
     * the car's network. mDNS stays as the fallback for a car that never
     * answered the bootstrap.
     */
    private void onCarWireless(final CarWifiConfig config) {
        carConfig = config;
        Logs.add("the car offered its network: " + config.describe());
        if (carWifi != null) {
            carWifi.close();
        }
        carWifi = new CarWifi(this, new CarWifi.Listener() {
            @Override
            public void onLog(String message) {
                Logs.add(message);
            }

            @Override
            public void onConnected(String ssid, String localAddress) {
                if (airplayClient != null) {
                    Logs.add("an AirPlay session is already running; keeping it");
                    return;
                }
                if (config.hasEndpoint()) {
                    Logs.add("connecting to the receiver the car named: "
                            + config.firstAddress() + ":" + config.port);
                    carHost = config.firstAddress();
                    startAirplaySession(config.firstAddress(), config.port);
                } else {
                    Logs.add("the car named no endpoint; the mDNS result is used instead");
                }
            }

            @Override
            public void onFailed(String reason) {
                Logs.add("cannot join the car's network: " + reason);
            }
        });
        carWifi.connect(config);
    }

    /** Runs the AirPlay handshake (pairing included) against the car. */
    private void startAirplaySession(String host, int port) {
        if (airplayClient != null) {
            Logs.add("an AirPlay session is already running; ignoring " + host + ":" + port);
            return;
        }
        final String id = deviceId();
        AirplayMessages.Identity identity = new AirplayMessages.Identity(
                id, "iPhone", android.os.Build.MODEL == null ? "iPhone" : android.os.Build.MODEL);
        airplayClient = new AirplayClient(host, port, identity, new AirplayClient.Listener() {
            @Override
            public void onLog(String message) {
                Logs.add(message);
            }

            @Override
            public void onState(String state) {
                Logs.add("airplay state -> " + state);
                if (state != null && state.startsWith("failed")) {
                    scheduleSessionRetry(host, port);
                }
            }

            @Override
            public void onSessionReady(String sessionId) {
                Logs.add("AirPlay session " + sessionId + " is ready; starting video");
                sessionAttempts = 0;
                startVideo();
            }

            @Override
            public void onTouch(HidTouch.Touch touch) {
                Logs.add("car touch " + touch.describe() + " -> " + TouchInjector.apply(touch));
            }

            @Override
            public void onIapTunnel(byte[] bytes) {
                onTunnelBytes(bytes);
            }

            @Override
            public boolean onKeyFrameRequested() {
                SurfaceEncoder encoder = surfaceEncoder;
                if (encoder == null || !encoder.isRunning()) {
                    return false;
                }
                encoder.requestKeyFrame();
                Logs.add("key frame requested by the car");
                return true;
            }
        });
        airplayClient.start();
    }

    /**
     * Rebuilds a session that failed, a few times.
     *
     * A car drops the AirPlay session for its own reasons (a channel change, a
     * Wi-Fi hiccup) and there is nothing to fix on this side, so the honest
     * behaviour is to try again a couple of times and then say so instead of
     * sitting on a dead session. The generation counter keeps a retry that was
     * scheduled before a Stop from touching the next run.
     */
    private void scheduleSessionRetry(final String host, final int port) {
        if (!shouldRetry(sessionAttempts, MAX_SESSION_RETRIES)) {
            Logs.add("the AirPlay session failed " + sessionAttempts
                    + " times; not retrying any more");
            return;
        }
        sessionAttempts++;
        final int mine = generation;
        Logs.add("retrying the AirPlay session in " + (RETRY_DELAY_MILLIS / 1000)
                + "s (attempt " + sessionAttempts + " of " + MAX_SESSION_RETRIES + ")");
        Thread retry = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS);
                } catch (InterruptedException stopped) {
                    return;
                }
                if (mine != generation || server == null) {
                    Logs.add("the retry was overtaken by a stop or a restart; dropping it");
                    return;
                }
                startAirplaySession(host, port);
            }
        }, "airplay-retry");
        retry.setDaemon(true);
        retry.start();
    }

    /**
     * Starts the video producer.
     *
     * With screen capture consent the phone's own screen is mirrored into the
     * encoder's input surface, so the car shows what the phone shows - the
     * CarWith/ICCOA projection included. Without consent the synthetic pattern
     * still proves the pipeline. Either way the access units leave through the
     * same tunnel.
     */
    private void startVideo() {
        stopVideo();
        videoConfigSent = false;
        // the reference encodes the phone's own screen scaled by a percentage,
        // not the size the car reports: matching the car's panel would change the
        // aspect ratio of what the phone sends
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        Object windowManager = getSystemService(Context.WINDOW_SERVICE);
        if (windowManager instanceof android.view.WindowManager) {
            ((android.view.WindowManager) windowManager).getDefaultDisplay()
                    .getRealMetrics(metrics);
        }
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            metrics = getResources().getDisplayMetrics();
        }
        // the density has to follow the phone's own, or the mirrored content is
        // rendered at a different logical size than it has on the phone
        videoDensity = SurfaceEncoder.encodeDensity(metrics.densityDpi,
                SurfaceEncoder.DEFAULT_SCALE_PERCENT);
        int[] size = SurfaceEncoder.encodeSize(metrics.widthPixels, metrics.heightPixels,
                SurfaceEncoder.DEFAULT_SCALE_PERCENT);
        videoWidth = size[0];
        videoHeight = size[1];
        videoFps = SurfaceEncoder.selectedFps(SurfaceEncoder.PREFERRED_FPS,
                airplayClient == null ? 0 : airplayClient.infoFps());
        Logs.add("encoding at " + videoWidth + "x" + videoHeight + " at " + videoDensity
                + "dpi (phone screen " + metrics.widthPixels + "x" + metrics.heightPixels
                + " at " + SurfaceEncoder.DEFAULT_SCALE_PERCENT + "%)"
                + (airplayClient != null && airplayClient.infoWidth() > 0
                        ? "; the car reports " + airplayClient.infoWidth() + "x"
                            + airplayClient.infoHeight() + " of its own" : ""));
        carVideoPort = airplayClient == null
                ? AirplayMessages.VIDEO_PORT : airplayClient.videoPort();
        try {
            videoSocket = new java.net.DatagramSocket();
        } catch (Exception error) {
            Logs.add("cannot open the video socket: " + error);
            return;
        }
        videoSender = new VideoSender(1);
        final String host = carHost;
        final int port = carVideoPort;
        try {
            if (host != null && airplayClient != null) {
                AirplayAuth auth = airplayClient.auth();
                if (auth != null) {
                    videoTunnel = new AirplayVideoTunnel(host, port,
                            auth.baseKey(), auth.baseIv(), airplayClient.connectionId());
                } else {
                    Logs.add("no MFiSAP key; the screen is sent with the pairing derived key, "
                            + "which a car will not be able to unwrap");
                    videoTunnel = new AirplayVideoTunnel(host, port,
                            airplayClient.streamBaseKey(), airplayClient.streamBaseKey(),
                            airplayClient.connectionId());
                }
            }
        } catch (Exception error) {
            Logs.add("cannot open the video tunnel: " + error);
        }
        if (easyConn != null && easyConn.isRunning()) {
            videoWidth = sourceWidth;
            videoHeight = sourceHeight;
            videoFps = sourceFps;
            Logs.add("using EasyConnection as the video source: " + videoWidth + "x"
                    + videoHeight + "@" + videoFps);
            startAudio(host);
            return;
        }
        if (projectionData != null) {
            SurfaceEncoder candidate = new SurfaceEncoder(videoWidth, videoHeight,
                    videoFps,
                    SurfaceEncoder.bitRateFor(videoWidth, videoHeight, videoFps));
            boolean started = candidate.start(new SurfaceEncoder.Sink() {
                @Override
                public void onAccessUnit(byte[] annexB, long timestampUs, boolean keyframe) {
                    sendVideo(annexB, timestampUs, keyframe, host, port);
                }
            });
            if (started) {
                ScreenSource source = new ScreenSource(this, videoWidth, videoHeight,
                        videoDensity);
                if (source.start(projectionResultCode, projectionData, candidate)) {
                    surfaceEncoder = candidate;
                    screen = source;
                    Logs.add("video path running from the live screen: "
                            + videoWidth + "x" + videoHeight + "@" + videoFps + " -> " + host + ":" + port);
                } else {
                    Logs.add("screen capture refused; falling back to the pattern");
                    candidate.stop();
                }
            }
        }
        if (surfaceEncoder == null) {
            encoder = new H264Encoder(videoWidth, videoHeight, videoFps);
            if (encoder.start(new H264Encoder.Sink() {
                @Override
                public void onAccessUnit(byte[] annexB, long timestampUs, boolean keyframe) {
                    sendVideo(annexB, timestampUs, keyframe, host, port);
                }
            })) {
                Logs.add("video path running on the synthetic pattern: "
                        + videoWidth + "x" + videoHeight + "@" + videoFps + " -> " + host + ":" + port);
            }
        }
        startAudio(host);
    }

    /** One place where encoded access units leave for the car. */
    private void sendVideo(byte[] annexB, long timestampUs, boolean keyframe, String host,
            int port) {
        // the decoder needs the configuration before the first picture: the
        // encoder's own csd when it reported one, otherwise the parameter sets it
        // carries on a key frame
        if (!videoConfigSent && videoTunnel != null && videoTunnel.isConnected()) {
            SurfaceEncoder surface = surfaceEncoder;
            byte[] csd = surface == null ? null : surface.codecConfig();
            String source = "the encoder's csd";
            byte[][] parameterSets = csd == null ? null : H264Config.parameterSets(csd);
            if (parameterSets == null && keyframe) {
                parameterSets = H264Config.parameterSets(annexB);
                source = "a key frame";
            }
            if (parameterSets != null) {
                try {
                    // the payload is an AVCDecoderConfigurationRecord, not the
                    // raw parameter sets: that is what the car reads it as
                    videoTunnel.sendConfig(H264Config.avcc(parameterSets[0], parameterSets[1]),
                            videoWidth, videoHeight);
                    videoConfigSent = true;
                    Logs.add("video configuration sent from " + source + ": sps "
                            + parameterSets[0].length + " bytes, pps "
                            + parameterSets[1].length + " bytes");
                } catch (Exception error) {
                    Logs.add("cannot send the video config frame: " + error);
                }
            }
        }
        try {
            if (videoTunnel != null && videoTunnel.isConnected()) {
                videoTunnel.send(annexB, timestampUs);
            } else if (host != null && videoSocket != null && videoSender != null) {
                videoSender.send(videoSocket, java.net.InetAddress.getByName(host),
                        port, annexB, timestampUs);
            }
        } catch (Exception error) {
            Logs.add("video send failed: " + error);
        }
    }

    /**
     * Audio path: a PCM tone sealed with ChaCha20-Poly1305 and sent to the
     * receiver's audio port. A real capture path can replace the tone source
     * without touching the packetiser.
     */
    /**
     * Audio path: a PCM tone sealed with ChaCha20-Poly1305 and sent to the port
     * the car allocated for the media stream.
     *
     * The key is the DataStream output key both ends derive from the handshake's
     * shared secret, and the frame shape (rate, channels, samples per frame) is
     * whatever the car accepted during the audio negotiation. With no accepted
     * offer, or no key, there is simply no sound instead of a broken stream.
     */
    private void startAudio(final String host) {
        if (host == null || airplayClient == null) {
            return;
        }
        final int port = airplayClient.audioPort();
        final AirplayMessages.PcmFormat format = airplayClient.audioFormat();
        final byte[] key = airplayClient.audioKey();
        if (port <= 0 || format == null) {
            Logs.add("no audio stream was agreed with the car; skipping the audio path");
            return;
        }
        if (key == null || key.length != AudioSender.KEY_LENGTH) {
            Logs.add("no audio key from the handshake; skipping the audio path");
            return;
        }
        audioRunning = true;
        audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                PcmToneSource source = new PcmToneSource(440.0, format.rate, format.channels,
                        format.samplesPerFrame());
                AudioSender sender = new AudioSender(AirplayMessages.AUDIO_STREAM_TYPE);
                java.net.DatagramSocket socket = null;
                long sequence = 0;
                long nonceCounter = 0;
                try {
                    socket = new java.net.DatagramSocket();
                    java.net.InetAddress address = java.net.InetAddress.getByName(host);
                    long frameIntervalUs = 1_000_000L * source.frameSamples() / format.rate;
                    Logs.add("audio path running: " + format.describe() + " -> " + host + ":" + port
                            + " every " + (frameIntervalUs / 1000) + "ms");
                    while (audioRunning) {
                        byte[] pcm = source.nextFrame();
                        byte[] packet = sender.packet(pcm, key, (int) (sequence & 0xFFFF),
                                source.timestamp(), nonceCounter);
                        sender.send(socket, address, port, packet);
                        sequence++;
                        nonceCounter++;
                        if (sequence == 1 || sequence % 200 == 0) {
                            Logs.add("audio sent " + sequence + " packets (last "
                                    + packet.length + " bytes)");
                        }
                        Thread.sleep(Math.max(1, frameIntervalUs / 1000));
                    }
                } catch (InterruptedException ignored) {
                } catch (Exception error) {
                    Logs.add("audio path ended: " + error);
                } finally {
                    if (socket != null) {
                        socket.close();
                    }
                }
            }
        }, "airplay-audio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    private void stopVideo() {
        audioRunning = false;
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }
        if (videoTunnel != null) {
            videoTunnel.close();
            videoTunnel = null;
        }
        if (encoder != null) {
            encoder.stop();
            encoder = null;
        }
        if (surfaceEncoder != null) {
            surfaceEncoder.stop();
            surfaceEncoder = null;
        }
        if (screen != null) {
            screen.stop();
            screen = null;
        }
        if (videoSocket != null) {
            videoSocket.close();
            videoSocket = null;
        }
        videoSender = null;
    }

    private String advertisedName() {
        String model = Build.MODEL == null ? "android" : Build.MODEL.replace(' ', '-');
        return "ICCOA-Sink-" + model;
    }

    private void advertise(String name, int port) {
        Object manager = getSystemService(Context.NSD_SERVICE);
        if (!(manager instanceof NsdManager)) {
            Logs.add("NSD unavailable; connect manually to the address above");
            return;
        }
        nsd = (NsdManager) manager;
        for (String type : SERVICE_TYPES) {
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(name);
            info.setServiceType(type);
            info.setPort(port);
            NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo registered) {
                    Logs.add("NSD registered " + registered.getServiceName()
                            + " (" + registered.getServiceType() + ")");
                }
                @Override public void onRegistrationFailed(NsdServiceInfo failed, int errorCode) {
                    Logs.add("NSD registration failed for " + failed.getServiceType()
                            + " (error " + errorCode + ", often needs NEARBY_WIFI_DEVICES)");
                }
                @Override public void onServiceUnregistered(NsdServiceInfo unregistered) {
                    Logs.add("NSD unregistered " + unregistered.getServiceType());
                }
                @Override public void onUnregistrationFailed(NsdServiceInfo failed, int errorCode) {
                }
            };
            registrations.add(listener);
            try {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (Throwable error) {
                Logs.add("NSD register threw: " + error);
            }
        }
    }

    private void unadvertise() {
        if (nsd == null) {
            return;
        }
        for (NsdManager.RegistrationListener listener : registrations) {
            try {
                nsd.unregisterService(listener);
            } catch (Throwable ignored) {
                // nothing useful to do
            }
        }
        registrations.clear();
        nsd = null;
    }

    private List<String> localAddresses() {
        List<String> addresses = new java.util.ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        addresses.add(network.getName() + " " + address.getHostAddress());
                    }
                }
            }
        } catch (Exception error) {
            Logs.add("address enumeration failed: " + error);
        }
        Object wifi = getSystemService(Context.WIFI_SERVICE);
        if (wifi instanceof WifiManager) {
            WifiManager manager = (WifiManager) wifi;
            if (!manager.isWifiEnabled()) {
                Logs.add("Wi-Fi is disabled; the phone cannot reach this sink");
            }
        }
        return addresses;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "ICCOA sink", NotificationManager.IMPORTANCE_LOW));
            }
        }
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("ICCOA sink running")
                .setContentText("RTSP tcp/" + RtspServer.DEFAULT_PORT + " - waiting for a phone")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }
}
