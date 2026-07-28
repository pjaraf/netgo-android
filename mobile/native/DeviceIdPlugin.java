package com.netgo.mobile;

import android.os.Build;
import android.provider.Settings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Reads the device's Android ID — a per-device + per-app-signing-key
 * identifier. Unlike a hardware MAC address (which Android has blocked
 * regular apps from reading since Android 6-8, for WiFi, Bluetooth, and
 * Ethernet alike), this is actually accessible, and — as long as the app
 * keeps using the same signing key across builds (ours is pinned) — it
 * stays the same even if the app is uninstalled and reinstalled on the
 * same device. It only changes on a factory reset.
 */
@CapacitorPlugin(name = "DeviceId")
public class DeviceIdPlugin extends Plugin {

    @PluginMethod
    public void getId(PluginCall call) {
        String id = Settings.Secure.getString(getContext().getContentResolver(), Settings.Secure.ANDROID_ID);
        JSObject ret = new JSObject();
        ret.put("id", id != null ? id : "");
        call.resolve(ret);
    }

    /** The real brand + model, straight from Android — used to label a TV
     *  in the "enviar a la TV" device picker (e.g., "Xiaomi MIBOX4",
     *  "NVIDIA SHIELD Android TV"), instead of trying to guess it from the
     *  WebView's user-agent string, which many cheap TV boxes simplify or
     *  don't set reliably. */
    @PluginMethod
    public void getDeviceInfo(PluginCall call) {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
        String model = Build.MODEL != null ? Build.MODEL : "";
        // Avoid duplicating the brand name if the model already includes it
        // (e.g., manufacturer "Xiaomi", model "Xiaomi MIBOX4").
        String label;
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase()) || manufacturer.isEmpty()) {
            label = model;
        } else {
            label = manufacturer + " " + model;
        }
        JSObject ret = new JSObject();
        ret.put("manufacturer", manufacturer);
        ret.put("model", model);
        ret.put("label", label.trim());
        call.resolve(ret);
    }
}
