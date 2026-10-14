package org.carplaytocarlife.iccoa;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Single screen: start/stop the sink, watch the log, see the decoded video. */
public final class MainActivity extends Activity {

    private static final int TOUCH_RANGE = 4095;
    private static final int REQUEST_PROJECTION = 2;

    private android.media.projection.MediaProjectionManager projectionManager;

    private TextView status;
    private TextView logView;
    private ScrollView scroll;
    private SurfaceView surface;
    private CarWithSourceAdapter carWithSource;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int lastLogSize;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            List<String> lines = Logs.snapshot();
            if (lines.size() != lastLogSize) {
                lastLogSize = lines.size();
                StringBuilder text = new StringBuilder();
                for (String line : lines) {
                    text.append(line).append('\n');
                }
                logView.setText(text.toString());
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
            SinkService service = SinkService.instance();
            WfdSession session = service == null ? null : service.session();
            status.setText(service == null
                    ? "stopped"
                    : ("running - " + (session == null ? "starting" : session.state().toString())
                        + " - RTSP tcp/" + RtspServer.DEFAULT_PORT
                        + ", RTP udp/" + (session == null ? "?" : session.rtpPort())));
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        scroll = findViewById(R.id.scroll);
        surface = findViewById(R.id.surface);
        carWithSource = new CarWithSourceAdapter(this, new CarWithSourceAdapter.Listener() {
            @Override
            public void onState(String state) {
                Logs.add("CarWith: " + state);
            }

            @Override
            public void onAudio(byte[] pcm, int source, int sampleRate, int channels, int format) {
                Logs.add("CarWith audio: " + sampleRate + "Hz/" + channels + "ch/" + pcm.length + "B");
            }

            @Override
            public void onAudioState(int source, int state) {
                Logs.add("CarWith audio state: " + source + "/" + state);
            }
        });
        projectionManager = (android.media.projection.MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
        Button start = findViewById(R.id.start);
        Button touch = findViewById(R.id.touch);
        Button stop = findViewById(R.id.stop);
        Button clear = findViewById(R.id.clear);
        Button update = findViewById(R.id.update);

        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                SinkService service = SinkService.instance();
                if (service != null) {
                    service.setSurface(holder.getSurface());
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                SinkService service = SinkService.instance();
                if (service != null) {
                    service.setSurface(null);
                }
            }
        });

        start.setOnClickListener(view -> {
            requestRuntimePermissions();
            carWithSource.bind();
            Logs.add("start requested");
            askForScreenCapture();
        });
        touch.setOnClickListener(view -> TouchInjector.requestPermission(this));
        stop.setOnClickListener(view -> {
            Logs.add("stop requested");
            carWithSource.close();
            SinkService.stop(this);
        });
        clear.setOnClickListener(view -> Logs.clear());
        update.setOnClickListener(view -> checkForUpdate(update, true));
        handler.postDelayed(() -> checkForUpdate(update, false), 800);
        handler.post(refresh);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresh);
        if (carWithSource != null) {
            carWithSource.close();
        }
        super.onDestroy();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        SinkService service = SinkService.instance();
        if (service == null || surface == null) {
            return super.onTouchEvent(event);
        }
        int width = Math.max(1, surface.getWidth());
        int height = Math.max(1, surface.getHeight());
        int x = (int) Math.min(TOUCH_RANGE, Math.max(0, event.getX() * TOUCH_RANGE / width));
        int y = (int) Math.min(TOUCH_RANGE, Math.max(0, event.getY() * TOUCH_RANGE / height));
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            service.sendTouch(1, x, y);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            service.sendTouch(0, x, y);
        } else if (action == MotionEvent.ACTION_MOVE) {
            service.sendTouch(2, x, y);
        }
        return true;
    }

    /**
     * Asks for screen capture consent and starts the sink with it.
     *
     * The consent belongs to the activity, but the projection outlives it: the
     * result Intent is handed to the service, which is what keeps mirroring
     * alive when the activity goes away.
     */
    private void askForScreenCapture() {
        SinkService running = SinkService.instance();
        if (projectionManager == null || (running != null && running.isRunning())) {
            SinkService.start(this);
            return;
        }
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(),
                    REQUEST_PROJECTION);
        } catch (Throwable error) {
            Logs.add("cannot ask for screen capture: " + error);
            SinkService.start(this);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            Logs.add("screen capture declined; the car will get the test pattern");
            SinkService.start(this);
            return;
        }
        Logs.add("screen capture granted");
        SinkService.start(this, resultCode, data);
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        java.util.List<String> wanted = new java.util.ArrayList<>();
        addIfMissing(wanted, Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(wanted, "android.permission.BLUETOOTH_CONNECT");
            addIfMissing(wanted, "android.permission.BLUETOOTH_SCAN");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(wanted, "android.permission.NEARBY_WIFI_DEVICES");
            addIfMissing(wanted, "android.permission.POST_NOTIFICATIONS");
        }
        if (!wanted.isEmpty()) {
            requestPermissions(wanted.toArray(new String[0]), 1);
        }
    }

    private void addIfMissing(java.util.List<String> wanted, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(permission);
        }
    }

    private void checkForUpdate(Button update, boolean manual) {
        update.setEnabled(false);
        update.setText("Checking...");
        if (manual) {
            Logs.add("checking GitHub updates");
        }
        UpdateChecker.check(this, (release, error) -> {
            update.setEnabled(true);
            if (error != null) {
                update.setText("Retry update");
                Logs.add("update check failed: " + error);
                return;
            }
            if (release == null) {
                update.setText("Up to date");
                if (manual) {
                    Logs.add("MiPlay is up to date");
                }
                return;
            }
            update.setText("Update " + release.tag);
            Logs.add("new APK: " + release.name);
            update.setOnClickListener(view -> openUpdate(release.downloadUrl()));
        });
    }

    private void openUpdate(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable error) {
            Logs.add("cannot open update page: " + error.getClass().getSimpleName());
        }
    }
}
