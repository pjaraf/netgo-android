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
 */
@CapacitorPlugin(name = "VlcPlayer")
public class VlcPlayerPlugin extends Plugin {

    @PluginMethod
    public void play(PluginCall call) {
        JSArray queue = call.getArray("queue");
        Integer startIndex = call.getInt("startIndex", 0);

        if (queue == null || queue.length() == 0) {
            call.reject("Falta la cola de reproducción (queue)");
            return;
        }

        Intent intent = new Intent(getContext(), VlcPlayerActivity.class);
        intent.putExtra("queueJson", queue.toString());
        intent.putExtra("startIndex", startIndex);
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

        if (categories == null || categories.length() == 0) {
            call.reject("Falta la lista de categorías");
            return;
        }

        Intent intent = new Intent(getContext(), VlcPlayerActivity.class);
        intent.putExtra("categoriesJson", categories.toString());
        intent.putExtra("catIndex", catIndex);
        intent.putExtra("startIndex", itemIndex);
        intent.putExtra("isLive", true);
        getActivity().startActivity(intent);
        call.resolve();
    }
}
