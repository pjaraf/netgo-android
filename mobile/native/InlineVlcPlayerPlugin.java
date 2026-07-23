package com.netgo.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

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
import java.util.Locale;

@CapacitorPlugin(name = "InlineVlcPlayer")
public class InlineVlcPlayerPlugin extends Plugin {

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout videoLayout;
    private FrameLayout container;

    private TextView titleView;
    private ImageButton playPauseBtn;
    private SeekBar seekBar;
    private TextView timeCurView;
    private TextView timeDurView;
    private TextView liveBadgeView;
    private LinearLayout progressRow;

    private final List<String> urls = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private int currentIndex = 0;
    private boolean userSeeking = false;

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
                togglePlayPause();
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
        getActivity().runOnUiThread(() -> doSeekBy(deltaSeconds));
        call.resolve();
    }

    @PluginMethod
    public void seekTo(PluginCall call) {
        long positionMs = call.getData().optLong("positionMs", 0);
        getActivity().runOnUiThread(() -> {
            if (mediaPlayer != null) mediaPlayer.setTime(positionMs);
        });
        call.resolve();
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
        Activity activity = getActivity();
        FrameLayout root = activity.findViewById(android.R.id.content);

        container = new FrameLayout(activity);
        videoLayout = new VLCVideoLayout(activity);
        container.addView(videoLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildControls(activity, container);

        root.addView(container, new FrameLayout.LayoutParams(0, 0));

        ArrayList<String> options = new ArrayList<>();
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--rtsp-tcp");
        options.add("--network-caching=1500");
        libVLC = new LibVLC(activity, options);
        mediaPlayer = new MediaPlayer(libVLC);
        mediaPlayer.attachViews(videoLayout, null, false, true);

        mediaPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.EndReached) {
                getActivity().runOnUiThread(this::advanceOrNotifyEnd);
            } else if (event.type == MediaPlayer.Event.Playing) {
                getActivity().runOnUiThread(() -> playPauseBtn.setImageResource(android.R.drawable.ic_media_pause));
            } else if (event.type == MediaPlayer.Event.Paused) {
                getActivity().runOnUiThread(() -> playPauseBtn.setImageResource(android.R.drawable.ic_media_play));
            }
        });

        startTicker();
    }

    private void buildControls(Activity activity, FrameLayout parent) {
        int pad = dp(activity, 10);

        LinearLayout topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0x99000000);
        topBar.setPadding(pad, pad, pad, pad);

        titleView = new TextView(activity);
        titleView.setText("Reproduciendo");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(13);
        titleView.setMaxLines(1);
        topBar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton closeBtn = new ImageButton(activity);
        closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeBtn.setBackgroundColor(Color.TRANSPARENT);
        closeBtn.setOnClickListener(v -> {
            container.setVisibility(View.GONE);
            notifyListeners("ended", new JSObject());
        });
        topBar.addView(closeBtn, new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36)));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        parent.addView(topBar, topLp);

        LinearLayout bottomBar = new LinearLayout(activity);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackgroundColor(0x99000000);
        bottomBar.setPadding(pad, pad, pad, pad);

        LinearLayout controlsRow = new LinearLayout(activity);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER);

        ImageButton seekBackBtn = new ImageButton(activity);
        seekBackBtn.setImageResource(android.R.drawable.ic_media_rew);
        seekBackBtn.setBackgroundColor(Color.TRANSPARENT);
        seekBackBtn.setOnClickListener(v -> doSeekBy(-10));

        playPauseBtn = new ImageButton(activity);
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFFFF8A3D);
        playPauseBtn.setBackground(circle);
        playPauseBtn.setOnClickListener(v -> togglePlayPause());

        ImageButton seekFwdBtn = new ImageButton(activity);
        seekFwdBtn.setImageResource(android.R.drawable.ic_media_ff);
        seekFwdBtn.setBackgroundColor(Color.TRANSPARENT);
        seekFwdBtn.setOnClickListener(v -> doSeekBy(10));

        LinearLayout.LayoutParams sideBtnLp = new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44));
        sideBtnLp.setMargins(dp(activity, 16), 0, dp(activity, 16), 0);
        LinearLayout.LayoutParams mainBtnLp = new LinearLayout.LayoutParams(dp(activity, 54), dp(activity, 54));

        controlsRow.addView(seekBackBtn, sideBtnLp);
        controlsRow.addView(playPauseBtn, mainBtnLp);
        controlsRow.addView(seekFwdBtn, sideBtnLp);
        bottomBar.addView(controlsRow);

        progressRow = new LinearLayout(activity);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setPadding(0, dp(activity, 8), 0, 0);

        timeCurView = new TextView(activity);
        timeCurView.setText("00:00");
        timeCurView.setTextColor(Color.WHITE);
        timeCurView.setTextSize(10);

        seekBar = new SeekBar(activity);
        seekBar.setMax(1000);
        seekBar.getProgressDrawable().setColorFilter(0xFFFF8A3D, PorterDuff.Mode.SRC_IN);
        seekBar.getThumb().setColorFilter(0xFFFF8A3D, PorterDuff.Mode.SRC_IN);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (mediaPlayer != null) {
                    long duration = mediaPlayer.getLength();
                    if (duration > 0) mediaPlayer.setTime((long) (duration * (sb.getProgress() / 1000.0)));
                }
            }
        });

        timeDurView = new TextView(activity);
        timeDurView.setText("00:00");
        timeDurView.setTextColor(Color.WHITE);
        timeDurView.setTextSize(10);

        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.setMargins(dp(activity, 6), 0, dp(activity, 6), 0);
        progressRow.addView(timeCurView, timeLp);
        progressRow.addView(seekBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progressRow.addView(timeDurView, timeLp);
        bottomBar.addView(progressRow);

        liveBadgeView = new TextView(activity);
        liveBadgeView.setText("● EN VIVO");
        liveBadgeView.setTextColor(0xFFFF6B6B);
        liveBadgeView.setTextSize(11);
        liveBadgeView.setGravity(Gravity.CENTER);
        liveBadgeView.setPadding(0, dp(activity, 6), 0, 0);
        liveBadgeView.setVisibility(View.GONE);
        bottomBar.addView(liveBadgeView);

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.gravity = Gravity.BOTTOM;
        parent.addView(bottomBar, bottomLp);
    }

    private int dp(Activity activity, int value) {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        return (int) (value * dm.density);
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            playPauseBtn.setImageResource(android.R.drawable.ic_media_play);
        } else {
            mediaPlayer.play();
            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        }
    }

    private void doSeekBy(int deltaSeconds) {
        if (mediaPlayer == null) return;
        long duration = mediaPlayer.getLength();
        long newTime = mediaPlayer.getTime() + (deltaSeconds * 1000L);
        if (newTime < 0) newTime = 0;
        if (duration > 0 && newTime > duration) newTime = duration;
        mediaPlayer.setTime(newTime);
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
        container.setVisibility(View.VISIBLE);
    }

    private void loadCurrent() {
        if (mediaPlayer == null || urls.isEmpty()) return;
        Media media = new Media(libVLC, android.net.Uri.parse(urls.get(currentIndex)));
        media.setHWDecoderEnabled(true, false);
        mediaPlayer.setMedia(media);
        media.release();
        mediaPlayer.play();

        titleView.setText(titles.get(currentIndex));

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
            if (container != null) container.setVisibility(View.GONE);
            notifyListeners("ended", new JSObject());
        }
    }

    private void startTicker() {
        stopTicker();
        progressTicker = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && !userSeeking) {
                    long duration = mediaPlayer.getLength();
                    long position = mediaPlayer.getTime();
                    boolean isLive = duration <= 0;
                    progressRow.setVisibility(isLive ? View.GONE : View.VISIBLE);
                    liveBadgeView.setVisibility(isLive ? View.VISIBLE : View.GONE);
                    if (!isLive) {
                        timeCurView.setText(fmt(position));
                        timeDurView.setText(fmt(duration));
                        seekBar.setProgress((int) (1000.0 * position / duration));
                    }
                    JSObject data = new JSObject();
                    data.put("position", position);
                    data.put("duration", duration);
                    data.put("isPlaying", mediaPlayer.isPlaying());
                    notifyListeners("progress", data);
                }
                handler.postDelayed(this, 500);
            }
        };
        handler.post(progressTicker);
    }

    private String fmt(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        if (h > 0) return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s);
        return String.format(Locale.getDefault(), "%02d:%02d", m, s);
    }

    private void stopTicker() {
        if (progressTicker != null) handler.removeCallbacks(progressTicker);
    }
}
