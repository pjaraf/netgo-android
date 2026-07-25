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
 * Downloads the update APK directly inside the app and hands it straight to
 * Android's package installer — this sidesteps unreliable TV browsers,
 * which sometimes truncate/corrupt the download when it's handed off via
 * an external "_system" browser intent (a known issue on several Android
 * TV / Google TV boxes' lightweight built-in browsers).
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
                    // Android 8+ requires the app itself to hold this permission
                    // before it can prompt the user to install another APK.
                    if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                        Intent settingsIntent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(settingsIntent);
                        call.reject("Necesita permiso para instalar — actívalo y toca Actualizar de nuevo.");
                        return;
                    }

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

    /** Downloads a URL to the given file, throwing if it fails or isn't HTTP 200. */
    private void download(String urlStr, File outFile) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.connect();
        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("HTTP " + responseCode + " for " + urlStr);
        }
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
        }
    }
}
