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
        // A few safe tweaks that specifically help on weak/generic TV
        // boxes: explicit hardware-accelerated compositing (some cheap
        // boxes ship WebViews that default more conservatively), turning
        // off features this app never uses (form-data saving, geolocation)
        // so the WebView isn't holding onto memory for nothing, and a
        // slightly bigger render-ahead cache so scrolling through long
        // rows doesn't have to keep re-painting from scratch.
        getBridge().getWebView().setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
        getBridge().getWebView().getSettings().setSaveFormData(false);
        getBridge().getWebView().getSettings().setGeolocationEnabled(false);
        getBridge().getWebView().getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        // WebViews default to a WHITE background before/between paints —
        // that's the white flash that was showing up whenever returning
        // from the fullscreen native player (or any other activity
        // transition). Setting it to the app's own dark background means
        // there's never a white frame to see, no matter the timing.
        getBridge().getWebView().setBackgroundColor(0xFF0B1B26);
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
        //
        // evaluateJavascript's callback receives the result as its JSON
        // representation, so a JS boolean `true` comes back as the STRING
        // "true" WITH the quote characters included (i.e. \"true\"), not
        // the bare word true. Comparing against "true" without quotes
        // never matched, so this was always falling through to the
        // exit-app flow — even while just browsing Películas/Series/etc.
        // — which is what was causing the blank white screen.
        getBridge().getWebView().evaluateJavascript(
                "(window.handleTVBack ? window.handleTVBack() : false)",
                result -> {
                    if (!"\"true\"".equals(result)) {
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
            // finishAffinity() alone closes every screen but can still
            // leave the process sitting in memory in the background —
            // this makes sure it's fully gone, not just off-screen.
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } else {
            lastBackPressTime = now;
            android.widget.Toast.makeText(this, "Presiona atrás de nuevo para salir", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
