package com.netgo.mobile;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VlcPlayerPlugin.class);
        registerPlugin(InlineVlcPlayerPlugin.class);
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
    }
}
