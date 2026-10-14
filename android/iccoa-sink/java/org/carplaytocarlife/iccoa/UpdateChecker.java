package org.carplaytocarlife.iccoa;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks the public MiPlay GitHub release without blocking the UI thread. */
public final class UpdateChecker {
    private static final String UPDATE_MANIFEST =
            "https://api.github.com/repos/lieren2012/MiPlay/contents/update.json?ref=main";

    public interface Callback {
        void onResult(ReleaseInfo release, String error);
    }

    public static final class ReleaseInfo {
        public final String tag;
        public final String name;
        public final String pageUrl;
        public final String apkUrl;

        ReleaseInfo(String tag, String name, String pageUrl, String apkUrl) {
            this.tag = tag;
            this.name = name;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
        }

        public String downloadUrl() {
            return apkUrl.length() == 0 ? pageUrl : apkUrl;
        }
    }

    private UpdateChecker() {
    }

    public static void check(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            ReleaseInfo release = null;
            String error = null;
            try {
                PackageInfo current = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                release = fetch(current.versionName == null ? "0.0.0" : current.versionName);
            } catch (Throwable failure) {
                error = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
            }
            ReleaseInfo result = release;
            String failure = error;
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> callback.onResult(result, failure));
        });
        executor.shutdown();
    }

    private static ReleaseInfo fetch(String currentVersion) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(UPDATE_MANIFEST).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "MiPlay/" + currentVersion);
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("GitHub HTTP " + code);
            }
            JSONObject file = new JSONObject(readAll(connection.getInputStream()));
            String encoded = file.optString("content", "").replaceAll("\\s", "");
            if (encoded.length() == 0) {
                throw new IllegalStateException("update manifest has no content");
            }
            String json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
            JSONObject object = new JSONObject(json);
            String tag = object.optString("versionName", "");
            if (tag.length() == 0 || !isNewer(currentVersion, tag)) {
                return null;
            }
            return new ReleaseInfo(tag, object.optString("name", tag),
                    object.optString("pageUrl", "https://github.com/lieren2012/MiPlay"),
                    object.optString("apkUrl", ""));
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private static boolean isNewer(String current, String tag) {
        int[] oldParts = versionParts(current);
        int[] newParts = versionParts(tag);
        for (int i = 0; i < 3; i++) {
            if (newParts[i] != oldParts[i]) {
                return newParts[i] > oldParts[i];
            }
        }
        return false;
    }

    private static int[] versionParts(String value) {
        String clean = value == null ? "0" : value.replaceFirst("^[vV]", "");
        String[] parts = clean.split("\\.");
        int[] result = new int[]{0, 0, 0};
        for (int i = 0; i < result.length && i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9].*", "");
            if (digits.length() > 0) {
                try {
                    result[i] = Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {
                    result[i] = 0;
                }
            }
        }
        return result;
    }
}
