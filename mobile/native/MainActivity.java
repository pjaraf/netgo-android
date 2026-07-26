package com.netgo.mobile;

import android.os.Bundle;
import android.webkit.WebSettings;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VlcPlayerPlugin.class);
        registerPlugin(InlineVlcPlayerPlugin.class);
        registerPlugin(UpdateInstallerPlugin.class);
        registerPlugin(DeviceIdPlugin.class);
        super.onCreate(savedInstanceState);
        getBridge().getWebView().getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
    }

    private long lastBackPressTime = 0;

    @Override
    public void onBackPressed() {
        // The inline video player has no visible close button anymore, so
        // the phone's back button/gesture is now the only way to close it.
        if (InlineVlcPlayerPlugin.handleBackPress()) {
            return;
        }

        // Let the web app try to handle this first — on TV, if we're
        // anywhere other than the home screen, "back" should return there
        // instead of exiting the app.
        getBridge().getWebView().evaluateJavascript(
                "(window.handleTVBack ? window.handleTVBack() : false)",
                result -> {
                    if (!"true".equals(result)) {
                        handleAppExitBack();
                    }
                }
        );
    }

    private void handleAppExitBack() {
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
