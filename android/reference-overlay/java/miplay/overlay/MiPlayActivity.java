package miplay.overlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MiPlay UI overlay. The original CarWith/CarLife APK remains in classes.dex. */
public final class MiPlayActivity extends Activity {
    private static final int BG = Color.rgb(239, 244, 255);
    private static final int CARD = Color.rgb(250, 252, 255);
    private static final int TEXT = Color.rgb(18, 24, 42);
    private static final int MUTED = Color.rgb(94, 103, 126);
    private static final int BLUE = Color.rgb(48, 70, 128);
    private static final String UPDATE_API =
            "https://api.github.com/repos/lieren2012/MiPlay/contents/update.json?ref=main";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView clock;
    private TextView connectionState;
    private TextView carName;
    private TextView actionLog;
    private String selectedMac = "";
    private String selectedName = "";

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private GradientDrawable background(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private Button button(String label, boolean filled) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(filled ? Color.WHITE : BLUE);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(52));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(background(filled ? BLUE : CARD, 28));
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(22), dp(24), dp(22));
        card.setBackground(background(CARD, 28));
        return card;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(buildPage());
        handler.postDelayed(this::checkUpdate, 1200);
    }

    private View buildPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(26), dp(18), dp(26), dp(12));
        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.ic_menu_directions);
        icon.setColorFilter(BLUE);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(54), dp(54));
        iconParams.rightMargin = dp(14);
        top.addView(icon, iconParams);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(text("MiPlay", 28, TEXT, true), new LinearLayout.LayoutParams(-2, dp(34)));
        titleBox.addView(text("车载连接 · 实验版本", 15, MUTED, false), new LinearLayout.LayoutParams(-2, dp(25)));
        top.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));
        connectionState = text("等待连接", 16, BLUE, true);
        connectionState.setGravity(Gravity.CENTER);
        connectionState.setPadding(dp(18), 0, dp(18), 0);
        connectionState.setBackground(background(Color.rgb(221, 231, 255), 24));
        top.addView(connectionState, new LinearLayout.LayoutParams(dp(112), dp(48)));
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(24), dp(8), dp(24), dp(12));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView headline = text("每一次出发，都从容连接", 30, TEXT, true);
        headline.setPadding(0, dp(18), 0, dp(10));
        content.addView(headline);
        content.addView(text("单屏画面与触摸验证 · Android 10+", 18, MUTED, false),
                new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout welcome = card();
        welcome.addView(text("欢迎上车", 21, MUTED, false), new LinearLayout.LayoutParams(-1, dp(38)));
        clock = text("--:--", 64, TEXT, false);
        welcome.addView(clock, new LinearLayout.LayoutParams(-1, dp(92)));
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(225, 229, 238));
        welcome.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        TextView stateTitle = text("尚未连接", 28, TEXT, true);
        stateTitle.setPadding(0, dp(22), 0, dp(2));
        welcome.addView(stateTitle, new LinearLayout.LayoutParams(-1, dp(52)));
        carName = text("选择已配对车机后开始", 17, MUTED, false);
        welcome.addView(carName, new LinearLayout.LayoutParams(-1, dp(42)));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button start = button("▶  开始连接", true);
        Button select = button("选择车机", false);
        actions.addView(start, new LinearLayout.LayoutParams(0, dp(56), 1));
        LinearLayout.LayoutParams selectParams = new LinearLayout.LayoutParams(0, dp(56), 0.8f);
        selectParams.leftMargin = dp(12);
        actions.addView(select, selectParams);
        welcome.addView(actions, new LinearLayout.LayoutParams(-1, dp(62)));
        start.setOnClickListener(v -> startReferenceConnection());
        select.setOnClickListener(v -> chooseCar());
        content.addView(welcome, new LinearLayout.LayoutParams(-1, -2));

        Space gap = new Space(this);
        content.addView(gap, new LinearLayout.LayoutParams(1, dp(18)));
        LinearLayout overview = card();
        overview.addView(text("传输概览", 27, TEXT, true), new LinearLayout.LayoutParams(-1, dp(58)));
        LinearLayout stats = new LinearLayout(this);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        addStat(stats, "0", "编码帧");
        addStat(stats, "0.0", "发送 MB");
        addStat(stats, "0", "输入事件");
        overview.addView(stats, new LinearLayout.LayoutParams(-1, dp(90)));
        overview.addView(text("分辨率等待车机公告", 16, MUTED, false), new LinearLayout.LayoutParams(-1, dp(38)));
        content.addView(overview, new LinearLayout.LayoutParams(-1, -2));
        actionLog = text("准备就绪", 14, MUTED, false);
        actionLog.setPadding(dp(4), dp(18), dp(4), dp(8));
        content.addView(actionLog);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(20), dp(8), dp(20), dp(10));
        addNav(nav, "⌂\n首页", true, v -> scroll.smoothScrollTo(0, 0));
        addNav(nav, "☷\n设置", false, v -> showSettings());
        addNav(nav, "▤\n日志", false, v -> showLog());
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(78)));
        return root;
    }

    private void addStat(LinearLayout row, String value, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(value, 34, TEXT, false), new LinearLayout.LayoutParams(-1, dp(52)));
        box.addView(text(label, 14, MUTED, false), new LinearLayout.LayoutParams(-1, dp(28)));
        row.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void addNav(LinearLayout nav, String label, boolean selected, View.OnClickListener listener) {
        TextView item = text(label, 15, TEXT, true);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(6), 0, 0);
        if (selected) item.setBackground(background(Color.rgb(226, 215, 255), 24));
        item.setOnClickListener(listener);
        nav.addView(item, new LinearLayout.LayoutParams(0, dp(60), 1));
    }

    private void updateClock() {
        if (clock != null) {
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
            clock.setText(format.format(new java.util.Date()));
        }
        handler.postDelayed(this::updateClock, 1000);
    }

    private void chooseCar() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) throw new IllegalStateException("Bluetooth unavailable");
            List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
            Collections.sort(devices, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            if (devices.isEmpty()) {
                Toast.makeText(this, "没有已配对的车机", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) labels[i] = devices.get(i).getName();
            new AlertDialog.Builder(this).setTitle("选择车机").setItems(labels, (d, which) -> {
                BluetoothDevice device = devices.get(which);
                selectedName = device.getName();
                selectedMac = device.getAddress();
                carName.setText(selectedName + " · " + selectedMac);
                actionLog.setText("已选择车机，等待开始连接");
            }).show();
        } catch (Throwable error) {
            Toast.makeText(this, "无法读取蓝牙设备，请授予附近设备权限", Toast.LENGTH_LONG).show();
        }
    }

    private void startReferenceConnection() {
        try {
            Class<?> service = Class.forName("com.baidu.carlife.service.CarlifeConnectService");
            Field tokenField = service.getDeclaredField("i");
            tokenField.setAccessible(true);
            Object token = tokenField.get(null);
            Method tokenMethod = token.getClass().getDeclaredMethod("k", Context.class);
            tokenMethod.setAccessible(true);
            String internalToken = (String) tokenMethod.invoke(token, this);
            Intent intent = new Intent().setComponent(new ComponentName(this, service))
                    .setAction("dev.carwith.bridge.CONNECT")
                    .putExtra("internal_token", internalToken)
                    .putExtra("bindPackageName", "com.miui.carlink")
                    .putExtra("bindClassName", "com.xiaomi.ucar.carlife.CarlifeConnService")
                    .putExtra("manufacturer", "xiaomi")
                    .putExtra("usb_type", 0)
                    .putExtra("is_box", false);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            connectionState.setText("连接中");
            actionLog.setText("已调用参考 APK 的 CarLife 连接服务");
        } catch (Throwable error) {
            connectionState.setText("连接失败");
            actionLog.setText("连接入口失败：" + error.getClass().getSimpleName());
        }
    }

    private void showSettings() {
        String version = "unknown";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName;
        } catch (Exception ignored) { }
        new AlertDialog.Builder(this).setTitle("MiPlay 设置")
                .setMessage("版本：" + version + "\n参考底层：CarWith / CarLife\n车机：" + (selectedName.length() == 0 ? "未选择" : selectedName))
                .setPositiveButton("检查更新", (d, w) -> checkUpdate())
                .setNegativeButton("关闭", null).show();
    }

    private void showLog() {
        new AlertDialog.Builder(this).setTitle("运行日志")
                .setMessage(actionLog == null ? "暂无日志" : actionLog.getText())
                .setPositiveButton("关闭", null).show();
    }

    private void checkUpdate() {
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(UPDATE_API).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                StringBuilder builder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = reader.readLine()) != null) builder.append(line);
                }
                String response = builder.toString();
                Matcher contentMatcher = Pattern.compile("\\\"content\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response);
                if (contentMatcher.find()) {
                    response = new String(Base64.decode(contentMatcher.group(1), Base64.DEFAULT), StandardCharsets.UTF_8);
                }
                Matcher matcher = Pattern.compile("\\\"versionName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response);
                if (!matcher.find()) return;
                String latest = matcher.group(1);
                String current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                String downloadUrl = "https://github.com/lieren2012/MiPlay";
                Matcher linkMatcher = Pattern.compile("\\\"apkUrl\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response);
                if (linkMatcher.find()) downloadUrl = linkMatcher.group(1);
                final String targetUrl = downloadUrl;
                if (isNewer(current, latest)) {
                    runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("发现新版本 " + latest)
                            .setMessage("检测到新的 MiPlay APK，是否打开下载地址？")
                            .setPositiveButton("下载", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))))
                            .setNegativeButton("稍后", null).show());
                }
            } catch (Throwable ignored) { }
        }).start();
    }

    private boolean isNewer(String oldVersion, String newVersion) {
        String[] oldParts = oldVersion.replaceAll("[^0-9.]", "").split("\\.");
        String[] newParts = newVersion.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < 3; i++) {
            int oldValue = i < oldParts.length && oldParts[i].length() > 0 ? Integer.parseInt(oldParts[i]) : 0;
            int newValue = i < newParts.length && newParts[i].length() > 0 ? Integer.parseInt(newParts[i]) : 0;
            if (oldValue != newValue) return newValue > oldValue;
        }
        return false;
    }
}
