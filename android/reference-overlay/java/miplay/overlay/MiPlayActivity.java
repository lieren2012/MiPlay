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
import android.widget.FrameLayout;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MiPlay UI overlay. The original CarWith/CarLife APK remains in classes.dex. */
public final class MiPlayActivity extends Activity {
    private static final int BG = Color.rgb(239, 244, 255);
    private static final int CARD = Color.rgb(250, 252, 255);
    private static final int TEXT = Color.rgb(18, 24, 42);
    private static final int MUTED = Color.rgb(94, 103, 126);
    private static final int BLUE = Color.rgb(48, 70, 128);
    private static final int SELECTED_NAV = Color.rgb(226, 215, 255);
    private static final String UPDATE_API =
            "https://api.github.com/repos/lieren2012/MiPlay/contents/update.json?ref=main";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> logLines = new ArrayList<>();
    private final List<TextView> navItems = new ArrayList<>();
    private TextView clock;
    private TextView connectionState;
    private TextView homeConnectionTitle;
    private TextView carName;
    private TextView actionLog;
    private TextView logsView;
    private ScrollView logScroll;
    private TextView settingsVersion;
    private TextView settingsCar;
    private TextView settingsPermission;
    private TextView updateStatus;
    private Button updateDownload;
    private String selectedMac = "";
    private String selectedName = "";
    private String pendingDownloadUrl = "";
    private int currentPage = 0;

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
        updateClock();
        addLog("MiPlay 已启动，已载入用户提供 APK 的 CarWith/CarLife 底层");
        handler.postDelayed(this::checkUpdate, 1200);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
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

        FrameLayout pageHost = new FrameLayout(this);
        View homePage = buildHomePage();
        View settingsPage = buildSettingsPage();
        View logPage = buildLogPage();
        pageHost.addView(homePage, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(-1, -1);
        settingsPage.setVisibility(View.GONE);
        pageHost.addView(settingsPage, settingsParams);
        FrameLayout.LayoutParams logParams = new FrameLayout.LayoutParams(-1, -1);
        logPage.setVisibility(View.GONE);
        pageHost.addView(logPage, logParams);
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(20), dp(8), dp(20), dp(10));
        addNav(nav, "⌂\n首页", true, v -> showPage(0));
        addNav(nav, "☷\n设置", false, v -> showPage(1));
        addNav(nav, "▤\n日志", false, v -> showPage(2));
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(78)));
        return root;
    }

    private View buildHomePage() {
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
        homeConnectionTitle = text("尚未连接", 28, TEXT, true);
        homeConnectionTitle.setPadding(0, dp(22), 0, dp(2));
        welcome.addView(homeConnectionTitle, new LinearLayout.LayoutParams(-1, dp(52)));
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
        actionLog = text("最近状态：等待操作", 14, MUTED, false);
        actionLog.setPadding(dp(4), dp(18), dp(4), dp(8));
        content.addView(actionLog);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private View buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(24), dp(8), dp(24), dp(24));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("设置", 30, TEXT, true);
        title.setPadding(0, dp(18), 0, dp(4));
        content.addView(title);
        content.addView(text("连接参数与版本信息", 17, MUTED, false), new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout appCard = card();
        appCard.addView(text("应用信息", 21, TEXT, true), new LinearLayout.LayoutParams(-1, dp(42)));
        settingsVersion = text("当前版本：" + versionName(), 16, MUTED, false);
        appCard.addView(settingsVersion, new LinearLayout.LayoutParams(-1, dp(40)));
        appCard.addView(text("包名：com.baidu.carlife.xiaomi", 16, MUTED, false), new LinearLayout.LayoutParams(-1, dp(40)));
        content.addView(appCard, new LinearLayout.LayoutParams(-1, -2));

        Space gap1 = new Space(this);
        content.addView(gap1, new LinearLayout.LayoutParams(1, dp(16)));
        LinearLayout connectionCard = card();
        connectionCard.addView(text("连接设置", 21, TEXT, true), new LinearLayout.LayoutParams(-1, dp(42)));
        connectionCard.addView(text("参考底层：用户提供的 CarWith / CarLife APK", 16, MUTED, false), new LinearLayout.LayoutParams(-1, dp(40)));
        connectionCard.addView(text("连接服务：CarlifeConnectService", 16, MUTED, false), new LinearLayout.LayoutParams(-1, dp(40)));
        settingsCar = text("车机：未选择", 16, MUTED, false);
        connectionCard.addView(settingsCar, new LinearLayout.LayoutParams(-1, dp(42)));
        Button choose = button("选择已配对车机", false);
        choose.setOnClickListener(v -> chooseCar());
        connectionCard.addView(choose, new LinearLayout.LayoutParams(-1, dp(56)));
        content.addView(connectionCard, new LinearLayout.LayoutParams(-1, -2));

        Space gap2 = new Space(this);
        content.addView(gap2, new LinearLayout.LayoutParams(1, dp(16)));
        LinearLayout permissionCard = card();
        permissionCard.addView(text("权限状态", 21, TEXT, true), new LinearLayout.LayoutParams(-1, dp(42)));
        settingsPermission = text("附近设备：选择车机时检查", 16, MUTED, false);
        permissionCard.addView(settingsPermission, new LinearLayout.LayoutParams(-1, dp(42)));
        content.addView(permissionCard, new LinearLayout.LayoutParams(-1, -2));

        Space gap3 = new Space(this);
        content.addView(gap3, new LinearLayout.LayoutParams(1, dp(16)));
        LinearLayout updateCard = card();
        updateCard.addView(text("版本更新", 21, TEXT, true), new LinearLayout.LayoutParams(-1, dp(42)));
        updateStatus = text("尚未检查更新", 16, MUTED, false);
        updateStatus.setPadding(0, 0, 0, dp(8));
        updateCard.addView(updateStatus, new LinearLayout.LayoutParams(-1, dp(42)));
        Button check = button("检查更新", true);
        check.setOnClickListener(v -> checkUpdate());
        updateCard.addView(check, new LinearLayout.LayoutParams(-1, dp(56)));
        updateDownload = button("下载新版本", false);
        updateDownload.setVisibility(View.GONE);
        updateDownload.setOnClickListener(v -> openPendingDownload());
        updateCard.addView(updateDownload, new LinearLayout.LayoutParams(-1, dp(56)));
        content.addView(updateCard, new LinearLayout.LayoutParams(-1, -2));

        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private View buildLogPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(8), dp(24), dp(12));

        TextView title = text("日志", 30, TEXT, true);
        title.setPadding(0, dp(18), 0, dp(4));
        page.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(text("实时连接事件", 17, MUTED, false), new LinearLayout.LayoutParams(0, dp(48), 1));
        Button clear = button("清空日志", false);
        clear.setTextSize(14);
        clear.setOnClickListener(v -> clearLogs());
        toolbar.addView(clear, new LinearLayout.LayoutParams(dp(120), dp(48)));
        page.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(58)));

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackground(background(CARD, 22));
        logsView = text("暂无日志", 14, TEXT, false);
        logsView.setGravity(Gravity.TOP | Gravity.START);
        logsView.setTypeface(Typeface.MONOSPACE);
        logsView.setPadding(dp(18), dp(18), dp(18), dp(18));
        logScroll.addView(logsView, new ScrollView.LayoutParams(-1, -2));
        page.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        refreshLogs();
        return page;
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
        item.setBackground(selected ? background(SELECTED_NAV, 24) : null);
        item.setOnClickListener(listener);
        navItems.add(item);
        nav.addView(item, new LinearLayout.LayoutParams(0, dp(60), 1));
    }

    private void showPage(int page) {
        currentPage = page;
        ViewGroup navParent = (ViewGroup) navItems.get(0).getParent();
        ViewGroup root = (ViewGroup) navParent.getParent();
        ViewGroup host = (ViewGroup) root.getChildAt(1);
        host.getChildAt(0).setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        host.getChildAt(1).setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        host.getChildAt(2).setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < navItems.size(); i++) {
            navItems.get(i).setBackground(i == page ? background(SELECTED_NAV, 24) : null);
        }
        if (page == 2 && logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void updateClock() {
        if (clock != null) {
            SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.getDefault());
            clock.setText(format.format(new Date()));
        }
        handler.postDelayed(this::updateClock, 1000);
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (Exception error) {
            return "unknown";
        }
    }

    private void addLog(String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> addLog(message));
            return;
        }
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        logLines.add(stamp + "  " + message);
        refreshLogs();
        if (actionLog != null) actionLog.setText("最近状态：" + message);
        if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void refreshLogs() {
        if (logsView == null) return;
        if (logLines.isEmpty()) {
            logsView.setText("暂无日志");
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (String line : logLines) builder.append(line).append('\n');
        logsView.setText(builder.toString());
    }

    private void clearLogs() {
        logLines.clear();
        refreshLogs();
        if (actionLog != null) actionLog.setText("最近状态：日志已清空");
    }

    private void chooseCar() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) throw new IllegalStateException("Bluetooth unavailable");
            List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
            Collections.sort(devices, (a, b) -> safeDeviceName(a).compareToIgnoreCase(safeDeviceName(b)));
            if (devices.isEmpty()) {
                addLog("没有读取到已配对车机");
                Toast.makeText(this, "没有已配对的车机", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) labels[i] = safeDeviceName(devices.get(i));
            new AlertDialog.Builder(this).setTitle("选择车机").setItems(labels, (d, which) -> {
                BluetoothDevice device = devices.get(which);
                selectedName = safeDeviceName(device);
                selectedMac = device.getAddress();
                carName.setText(selectedName + " · " + selectedMac);
                if (settingsCar != null) settingsCar.setText("车机：" + selectedName + " · " + selectedMac);
                if (settingsPermission != null) settingsPermission.setText("附近设备：已读取已配对设备");
                addLog("已选择车机：" + selectedName + "，MAC：" + selectedMac);
            }).show();
        } catch (Throwable error) {
            addLog("读取车机失败：" + error.getClass().getSimpleName());
            if (settingsPermission != null) settingsPermission.setText("附近设备：读取失败，请检查权限");
            Toast.makeText(this, "无法读取蓝牙设备，请授予附近设备权限", Toast.LENGTH_LONG).show();
        }
    }

    private String safeDeviceName(BluetoothDevice device) {
        String name = device.getName();
        return name == null || name.length() == 0 ? "未命名车机" : name;
    }

    private void startReferenceConnection() {
        if (selectedName.length() == 0) addLog("尚未选择车机，仍尝试调用参考连接服务");
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
            if (homeConnectionTitle != null) homeConnectionTitle.setText("正在连接");
            addLog("已调用参考 APK 的 CarLife 连接服务");
        } catch (Throwable error) {
            connectionState.setText("连接失败");
            if (homeConnectionTitle != null) homeConnectionTitle.setText("连接失败");
            addLog("连接入口失败：" + error.getClass().getSimpleName());
        }
    }

    private void checkUpdate() {
        if (updateStatus != null) updateStatus.setText("正在检查更新…");
        addLog("开始检查公开更新清单");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(UPDATE_API).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                StringBuilder builder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) builder.append(line);
                }
                String response = builder.toString();
                Matcher contentMatcher = Pattern.compile("\\\"content\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response);
                if (contentMatcher.find()) {
                    response = new String(Base64.decode(contentMatcher.group(1), Base64.DEFAULT), StandardCharsets.UTF_8);
                }
                Matcher matcher = Pattern.compile("\\\"versionName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response);
                if (!matcher.find()) throw new IllegalStateException("versionName missing");
                String latest = matcher.group(1);
                String current = versionName();
                String downloadUrl = "https://github.com/lieren2012/MiPlay";
                Matcher linkMatcher = Pattern.compile("\\\"apkUrl\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response);
                if (linkMatcher.find()) downloadUrl = linkMatcher.group(1);
                final String targetUrl = downloadUrl;
                if (isNewer(current, latest)) {
                    runOnUiThread(() -> {
                        pendingDownloadUrl = targetUrl;
                        if (updateStatus != null) updateStatus.setText("发现新版本：" + latest);
                        if (updateDownload != null) updateDownload.setVisibility(View.VISIBLE);
                        addLog("发现新版本：" + latest);
                    });
                } else {
                    runOnUiThread(() -> {
                        if (updateStatus != null) updateStatus.setText("已是最新版本：" + current);
                        addLog("更新检查完成，当前已是最新版本");
                    });
                }
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    if (updateStatus != null) updateStatus.setText("检查失败，请稍后重试");
                    addLog("更新检查失败：" + error.getClass().getSimpleName());
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void openPendingDownload() {
        if (pendingDownloadUrl.length() == 0) return;
        addLog("打开新版本下载地址");
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pendingDownloadUrl)));
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
