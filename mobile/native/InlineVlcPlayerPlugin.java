package com.netgo.mobile;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

@CapacitorPlugin(name = "InlineVlcPlayer")
public class InlineVlcPlayerPlugin extends Plugin {

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout videoLayout;
    private FrameLayout container;

    private final List<String> urls = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private int currentIndex = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable progressTicker;

    @PluginMethod
    public void mount(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            ensureViewsCreated();
            applyRect(call);
            call.resolve();
        });
    }

    @PluginMethod
    public void updateRect(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (container != null) applyRect(call);
            call.resolve();
        });
    }

    @PluginMethod
    public void loadQueue(PluginCall call) {
        JSArray queue = call.getArray("queue");
        if (queue == null || queue.length() == 0) {
            call.reject("Falta la cola de reproducción (queue)");
            return;
        }
        int startIndex = call.getData().optInt("startIndex", 0);

        urls.clear();
        titles.clear();
        try {
            for (int i = 0; i < queue.length(); i++) {
                JSONObject obj = queue.getJSONObject(i);
                urls.add(obj.getString("url"));
                titles.add(obj.optString("title", "Reproduciendo"));
            }
        } catch (Exception e) {
            call.reject("Cola inválida: " + e.getMessage());
            return;
        }
        currentIndex = Math.max(0, Math.min(startIndex, urls.size() - 1));

        getActivity().runOnUiThread(() -> {
            ensureViewsCreated();
            loadCurrent();
            call.resolve();
        });
    }

    @PluginMethod
    public void playPause(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            JSObject ret = new JSObject();
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) mediaPlayer.pause(); else mediaPlayer.play();
                ret.put("isPlaying", mediaPlayer.isPlaying());
            } else {
                ret.put("isPlaying", false);
            }
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void seekBy(PluginCall call) {
        int deltaSeconds = call.getData().optInt("deltaSeconds", 10);
        getActivity().runOnUiThread(() -> {
            if (mediaPlayer != null) {
                long duration = mediaPlayer.getLength();
                long newTime = mediaPlayer.getTime() + (deltaSeconds * 1000L);
                if (newTime < 0) newTime = 0;
                if (duration > 0 && newTime > duration) newTime = duration;
                mediaPlayer.setTime(newTime);
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void seekTo(PluginCall call) {
        long positionMs = call.getData().optLong("positionMs", 0);
        getActivity().runOnUiThread(() -> {
            if (mediaPlayer != null) mediaPlayer.setTime(positionMs);
            call.resolve();
        });
    }

    @PluginMethod
    public void unmount(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            stopTicker();
            if (mediaPlayer != null) {
                mediaPlayer.stop();
                mediaPlayer.detachViews();
                mediaPlayer.release();
                mediaPlayer = null;
            }
            if (libVLC != null) {
                libVLC.release();
                libVLC = null;
            }
            if (container != null && container.getParent() != null) {
                ((ViewGroup) container.getParent()).removeView(container);
            }
            container = null;
            videoLayout = null;
            urls.clear();
            titles.clear();
            call.resolve();
        });
    }

    private void ensureViewsCreated() {
        if (container != null) return;

        FrameLayout root = getActivity().findViewById(android.R.id.content);

        WebView webView = getBridge().getWebView();
        webView.setBackgroundColor(Color.TRANSPARENT);

        videoLayout = new VLCVideoLayout(getActivity());
        container = new FrameLayout(getActivity());
        container.addView(videoLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(container, 0, new FrameLayout.LayoutParams(0, 0));

        ArrayList<String> options = new ArrayList<>();
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--rtsp-tcp");
        options.add("--network-caching=1500");
        libVLC = new LibVLC(getActivity(), options);
        mediaPlayer = new MediaPlayer(libVLC);
        mediaPlayer.attachViews(videoLayout, null, false, false);

        mediaPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.EndReached) {
                getActivity().runOnUiThread(this::advanceOrNotifyEnd);
            }
        });

        startTicker();
    }

    private void applyRect(PluginCall call) {
        if (container == null) return;
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        JSONObject data = call.getData();
        double x = data.optDouble("x", 0);
        double y = data.optDouble("y", 0);
        double w = data.optDouble("width", 0);
        double h = data.optDouble("height", 0);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
        lp.width = (int) Math.round(w * dm.density);
        lp.height = (int) Math.round(h * dm.density);
        lp.leftMargin = (int) Math.round(x * dm.density);
        lp.topMargin = (int) Math.round(y * dm.density);
        lp.gravity = Gravity.TOP | Gravity.START;
        container.setLayoutParams(lp);
    }

    private void loadCurrent() {
        if (mediaPlayer == null || urls.isEmpty()) return;
        Media media = new Media(libVLC, android.net.Uri.parse(urls.get(currentIndex)));
        media.setHWDecoderEnabled(true, false);
        mediaPlayer.setMedia(media);
        media.release();
        mediaPlayer.play();

        JSObject data = new JSObject();
        data.put("title", titles.get(currentIndex));
        data.put("index", currentIndex);
        data.put("count", urls.size());
        notifyListeners("trackChanged", data);
    }

    private void advanceOrNotifyEnd() {
        if (currentIndex + 1 < urls.size()) {
            currentIndex++;
            loadCurrent();
        } else {
            notifyListeners("ended", new JSObject());
        }
    }

    private void startTicker() {
        stopTicker();
        progressTicker = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null) {
                    JSObject data = new JSObject();
                    data.put("position", mediaPlayer.getTime());
                    data.put("duration", mediaPlayer.getLength());
                    data.put("isPlaying", mediaPlayer.isPlaying());
                    notifyListeners("progress", data);
                }
                handler.postDelayed(this, 500);
            }
        };
        handler.post(progressTicker);
    }

    private void stopTicker() {
        if (progressTicker != null) handler.removeCallbacks(progressTicker);
    }
}
