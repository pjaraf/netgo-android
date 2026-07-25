package com.netgo.mobile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads the update APK directly inside the app (in the background,
 * reporting progress to JS) and hands it straight to Android's package
 * installer — this sidesteps unreliable TV browsers/third-party downloader
 * apps (some Android TV boxes route external downloads to whatever app is
 * registered for it, like "Downloader", which has its own confusing UI).
 *
 * The build produces one small APK per CPU architecture plus a universal
 * one. Given the universal download URL, this tries the much smaller
 * architecture-specific APK for this exact device first (e.g.
 * "app-arm64-v8a-debug.apk" instead of the full "app-debug.apk"), falling
 * back to the universal one automatically if that specific file isn't
 * available for any reason.
 */
@CapacitorPlugin(name = "UpdateInstaller")
public class UpdateInstallerPlugin extends Plugin {

    @PluginMethod
    public void downloadAndInstall(PluginCall call) {
        String universalUrl = call.getString("url");
        if (universalUrl == null) {
            call.reject("Missing url");
            return;
        }
        Activity activity = getActivity();

        // Check the install permission BEFORE downloading anything — no
        // point downloading tens of MB just to find out we can't install it.
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            Intent settingsIntent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settingsIntent);
            call.reject("PERMISSION_NEEDED");
            return;
        }

        new Thread(() -> {
            File outFile = new File(activity.getExternalFilesDir(null), "netgo-update.apk");
            String abiUrl = null;
            try {
                String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : null;
                if (abi != null) {
                    abiUrl = universalUrl.replace("app-debug.apk", "app-" + abi + "-debug.apk");
                }
            } catch (Exception ignored) { }

            boolean downloaded = false;
            String lastError = "";

            if (abiUrl != null) {
                try {
                    download(abiUrl, outFile);
                    downloaded = true;
                } catch (Exception e) {
                    lastError = e.getMessage();
                }
            }

            if (!downloaded) {
                try {
                    download(universalUrl, outFile);
                    downloaded = true;
                } catch (Exception e) {
                    lastError = e.getMessage();
                }
            }

            if (!downloaded) {
                final String err = lastError;
                activity.runOnUiThread(() -> call.reject("Download failed: " + err));
                return;
            }

            activity.runOnUiThread(() -> {
                try {
                    Uri apkUri = FileProvider.getUriForFile(
                            activity, activity.getPackageName() + ".fileprovider", outFile);
                    Intent installIntent = new Intent(Intent.ACTION_VIEW);
                    installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(installIntent);

                    JSObject ret = new JSObject();
                    ret.put("started", true);
                    call.resolve(ret);
                } catch (Exception e) {
                    call.reject("Install failed: " + e.getMessage());
                }
            });
        }).start();
    }

    /** Downloads a URL to the given file, reporting progress to JS as it goes. */
    private void download(String urlStr, File outFile) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.connect();
        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("HTTP " + responseCode + " for " + urlStr);
        }

        int totalBytes = conn.getContentLength(); // -1 if unknown
        long downloadedBytes = 0;
        long lastReported = 0;

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
                downloadedBytes += len;

                // Report progress at most ~10 times/second, not on every chunk.
                long now = System.currentTimeMillis();
                if (now - lastReported > 100) {
                    lastReported = now;
                    int percent = totalBytes > 0 ? (int) (downloadedBytes * 100 / totalBytes) : -1;
                    JSObject data = new JSObject();
                    data.put("percent", percent);
                    data.put("downloadedBytes", downloadedBytes);
                    data.put("totalBytes", totalBytes);
                    notifyListeners("downloadProgress", data);
                }
            }
        }

        JSObject finalData = new JSObject();
        finalData.put("percent", 100);
        finalData.put("downloadedBytes", downloadedBytes);
        finalData.put("totalBytes", totalBytes);
        notifyListeners("downloadProgress", finalData);
    }
}
