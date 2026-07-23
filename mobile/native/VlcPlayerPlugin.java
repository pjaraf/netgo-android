package com.netgo.mobile;

import android.content.Intent;
import com.getcapacitor.JSArray;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

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
}
