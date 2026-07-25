package com.netgo.mobile;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.google.android.gms.cast.framework.CastContext;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VlcPlayerPlugin.class);
        registerPlugin(InlineVlcPlayerPlugin.class);
        registerPlugin(UpdateInstallerPlugin.class);
        registerPlugin(DeviceIdPlugin.class);
        super.onCreate(savedInstanceState);
        getBridge().getWebView().getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Android 13+ requires this permission at runtime (not just declared
        // in the manifest) for the Google Cast SDK to actually find any
        // devices — without it, casting silently finds zero results even
        // when the network and TV are otherwise working fine. Requesting it
        // early (on app start) means it's already granted by the time the
        // user taps the cast button.
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 4201);
            }
        }

        // Initialize Cast as early as possible (app start), not lazily when
        // the user first taps play — apps like YouTube/Netflix start
        // scanning for Cast devices from launch, giving discovery time to
        // find devices in the background before the user ever opens the
        // device picker. Initializing it late was likely why our cast
        // dialog found zero devices even on networks/TVs that work fine
        // with other apps.
        try {
            CastContext.getSharedInstance(this);
        } catch (Exception ignored) {
            // Device without Google Play Services / Cast support — safe to ignore.
        }
    }

    private long lastBackPressTime = 0;

    @Override
    public void onBackPressed() {
        // The inline video player has no visible close button anymore, so
        // the phone's back button/gesture is now the only way to close it.
        if (InlineVlcPlayerPlugin.handleBackPress()) {
            return;
        }

        // Press back twice (within 2s) to exit — and, importantly, this
        // NEVER falls through to the default WebView back-navigation
        // behavior (super.onBackPressed()), which is what was likely
        // ending up opening "Downloader" on some Android TV boxes.
        long now = System.currentTimeMillis();
        if (now - lastBackPressTime < 2000) {
            // Explicitly go to the system home screen (where all the other
            // apps are) instead of just finishing — finishing alone let
            // Android decide what to show next, which was landing on
            // "Downloader" (likely still sitting in the task history from
            // when it was used to install this app) instead of the home
            // screen.
            android.content.Intent homeIntent = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            homeIntent.addCategory(android.content.Intent.CATEGORY_HOME);
            homeIntent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
            finishAffinity();
        } else {
            lastBackPressTime = now;
            android.widget.Toast.makeText(this, "Presiona atrás de nuevo para salir", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
