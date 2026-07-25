package com.netgo.mobile;

import android.content.Intent;
import com.getcapacitor.JSArray;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Bridges the web UI to a native, full-codec video player (libVLC).
 *
 * JS side calls:
 *   window.Capacitor.Plugins.VlcPlayer.play({
 *     queue: [{ url, title }, { url, title }, ...],
 *     startIndex: 0
 *   })
 *
 * A single movie or live channel is just a queue of length 1. A series is a
 * queue of all its episodes in order, so the native player can auto-advance
 * to the next episode (or close and return to the app when the queue ends).
 *
 * The queue/categories data is handed to VlcPlayerActivity through a static
 * field (pendingQueue/pendingCategories) rather than an Intent extra —
 * Android Intents go through Binder IPC, which has a hard ~1MB size limit
 * ("TransactionTooLargeException"), and a provider with a few thousand live
 * channels easily produces a JSON payload bigger than that, which was
 * crashing the whole app on launch. A static field is just an in-memory
 * reference within the same process, so there's no size limit at all.
 */
@CapacitorPlugin(name = "VlcPlayer")
public class VlcPlayerPlugin extends Plugin {

    static JSArray pendingQueue;
    static JSArray pendingCategories;

    @PluginMethod
    public void play(PluginCall call) {
        JSArray queue = call.getArray("queue");
        Integer startIndex = call.getInt("startIndex", 0);
        String deviceCode = call.getString("deviceCode", "");

        if (queue == null || queue.length() == 0) {
            call.reject("Falta la cola de reproducción (queue)");
            return;
        }

        pendingQueue = queue;
        Intent intent = new Intent(getContext(), VlcPlayerActivity.class);
        intent.putExtra("startIndex", startIndex);
        intent.putExtra("deviceCode", deviceCode);
        getActivity().startActivity(intent);
        call.resolve();
    }

    /**
     * Like play(), but for live TV: receives EVERY category and its
     * channels (not just one queue), so the fullscreen player can show a
     * channel list / category list overlay (remote's Right button) without
     * needing to go back to the web app to switch categories.
     */
    @PluginMethod
    public void playLive(PluginCall call) {
        JSArray categories = call.getArray("categories");
        Integer catIndex = call.getInt("catIndex", 0);
        Integer itemIndex = call.getInt("itemIndex", 0);
        String deviceCode = call.getString("deviceCode", "");

        if (categories == null || categories.length() == 0) {
            call.reject("Falta la lista de categorías");
            return;
        }

        pendingCategories = categories;
        Intent intent = new Intent(getContext(), VlcPlayerActivity.class);
        intent.putExtra("catIndex", catIndex);
        intent.putExtra("startIndex", itemIndex);
        intent.putExtra("isLive", true);
        intent.putExtra("deviceCode", deviceCode);
        getActivity().startActivity(intent);
        call.resolve();
    }
}
