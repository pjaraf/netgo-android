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
 */
@CapacitorPlugin(name = "UpdateInstaller")
public class UpdateInstallerPlugin extends Plugin {

    @PluginMethod
    public void downloadAndInstall(PluginCall call) {
        String url = call.getString("url");
        if (url == null) {
            call.reject("Missing url");
            return;
        }
        Activity activity = getActivity();

        new Thread(() -> {
            try {
                URL u = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    activity.runOnUiThread(() -> call.reject("HTTP " + responseCode));
                    return;
                }

                File outFile = new File(activity.getExternalFilesDir(null), "netgo-update.apk");
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
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
            } catch (Exception e) {
                activity.runOnUiThread(() -> call.reject("Download failed: " + e.getMessage()));
            }
        }).start();
    }
}
