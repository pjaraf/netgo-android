package com.netgo.mobile;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.mediarouter.app.MediaRouteButton;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaSeekOptions;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders native video ON TOP of the WebView. Supports: play/pause, seek,
 * a fullscreen toggle (rotates to landscape, hides system bars), swipe
 * left/right to move between items in the queue, auto-hiding controls
 * (tap the video to show/hide them), and casting to a Chromecast /
 * Android TV device via Google Cast (same system YouTube uses) using
 * Google's default receiver — no custom receiver app needed since we're
 * just casting direct video URLs.
 */
@CapacitorPlugin(name = "InlineVlcPlayer")
public class InlineVlcPlayerPlugin extends Plugin {

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout videoLayout;
    private FrameLayout container;
    private FrameLayout maintenanceView;
    private Runnable maintenanceRunnable;
    private static final long MAINTENANCE_TIMEOUT_MS = 10000;
    private GestureDetector gestureDetector;

    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView titleView;
    private ImageButton playPauseBtn;
    private TextView fullscreenBtn;
    private SeekBar seekBar;
    private TextView timeCurView;
    private TextView timeDurView;
    private TextView liveBadgeView;
    private TextView castBadgeView;
    private LinearLayout progressRow;

    private final List<String> urls = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private int currentIndex = 0;
    private boolean userSeeking = false;

    private boolean isFullscreen = false;
    private FrameLayout.LayoutParams savedLp;

    private boolean controlsVisible = true;
    private Runnable hideControlsRunnable;

    private float touchDownX, touchDownY;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable progressTicker;

    // ---------- Google Cast ----------
    private CastContext castContext;
    private CastSession currentCastSession;
    private boolean isCasting = false;

    private final SessionManagerListener<CastSession> sessionManagerListener = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarted(CastSession session, String sessionId) { onCastConnected(session); }
        @Override public void onSessionResumed(CastSession session, boolean wasSuspended) { onCastConnected(session); }
        @Override public void onSessionEnded(CastSession session, int error) { onCastDisconnected(); }
        @Override public void onSessionSuspended(CastSession session, int reason) {}
        @Override public void onSessionStarting(CastSession session) {}
        @Override public void onSessionStartFailed(CastSession session, int error) {}
        @Override public void onSessionEnding(CastSession session) {}
        @Override public void onSessionResuming(CastSession session, String sessionId) {}
        @Override public void onSessionResumeFailed(CastSession session, int error) {}
    };

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
            if (container != null && !isFullscreen) applyRect(call);
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
            togglePlayPause();
            ret.put("isPlaying", isCasting ? isRemotePlaying() : (mediaPlayer != null && mediaPlayer.isPlaying()));
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
            if (isFullscreen) exitFullscreen(getActivity());
            stopTicker();
            cancelAutoHide();
            cancelMaintenanceTimer();
            if (castContext != null) {
                try { castContext.getSessionManager().removeSessionManagerListener(sessionManagerListener, CastSession.class); }
                catch (Exception ignored) {}
            }
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
            maintenanceView = null;
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

        gestureDetector = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControlsVisibility();
                return true;
            }
        });

        videoLayout.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownX = event.getX();
                    touchDownY = event.getY();
                    break;
                case MotionEvent.ACTION_UP:
                    float dx = event.getX() - touchDownX;
                    float dy = event.getY() - touchDownY;
                    if (Math.abs(dx) > dp(activity, 50) && Math.abs(dx) > Math.abs(dy)) {
                        if (dx < 0) goToNext(); else goToPrevious();
                    }
                    break;
            }
            return true;
        });

        buildControls(activity, container);
        buildMaintenanceView(activity, container);

        root.addView(container, new FrameLayout.LayoutParams(0, 0));

        ArrayList<String> options = new ArrayList<>();
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--rtsp-tcp");
        // A bigger buffer than before: 600ms was tuned for fast start on
        // good WiFi, but on slow/flaky mobile data it caused constant
        // rebuffering. 2500ms is a safer middle ground — still starts
        // reasonably fast, but absorbs slow-network hiccups much better.
        options.add("--network-caching=2500");
        options.add("--live-caching=2500");
        options.add("--file-caching=1000");
        options.add("--http-reconnect");
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        options.add("--no-stats");
        libVLC = new LibVLC(activity, options);
        mediaPlayer = new MediaPlayer(libVLC);
        mediaPlayer.attachViews(videoLayout, null, false, true);

        mediaPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.EndReached) {
                getActivity().runOnUiThread(this::advanceOrNotifyEnd);
            } else if (event.type == MediaPlayer.Event.Playing) {
                getActivity().runOnUiThread(() -> {
                    playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
                    cancelMaintenanceTimer();
                    if (maintenanceView != null) maintenanceView.setVisibility(View.GONE);
                });
            } else if (event.type == MediaPlayer.Event.Paused) {
                getActivity().runOnUiThread(() -> playPauseBtn.setImageResource(android.R.drawable.ic_media_play));
            }
        });

        startTicker();

        // ---- Google Cast setup (safe no-op if Play Services / Cast unavailable) ----
        try {
            castContext = CastContext.getSharedInstance(activity);
            castContext.getSessionManager().addSessionManagerListener(sessionManagerListener, CastSession.class);
            currentCastSession = castContext.getSessionManager().getCurrentCastSession();
            if (currentCastSession != null && currentCastSession.isConnected()) onCastConnected(currentCastSession);
        } catch (Exception e) {
            castContext = null; // Device without Google Play Services / Cast — button just won't be added.
        }
    }

    private void buildControls(Activity activity, FrameLayout parent) {
        int pad = dp(activity, 10);

        topBar = new LinearLayout(activity);
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

        // Cast button (only added if Google Cast is actually available on this device)
        if (castContext == null) {
            try {
                CastContext probe = CastContext.getSharedInstance(activity);
                castContext = probe;
            } catch (Exception ignored) {}
        }
        try {
            MediaRouteButton castButton = new MediaRouteButton(activity);
            // The old 2-arg overload silently swallows ModuleUnavailableException
            // if the Cast module fails to load — which would look exactly like
            // "button works but finds zero devices" with no error anywhere. This
            // version reports failures instead of hiding them.
            CastButtonFactory.setUpMediaRouteButton(
                    activity.getApplicationContext(),
                    androidx.core.content.ContextCompat.getMainExecutor(activity),
                    castButton
            ).addOnFailureListener(e ->
                    android.util.Log.e("NetGoCast", "Cast module failed to load: " + e.getMessage(), e)
            );
            topBar.addView(castButton, new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 36)));
        } catch (Exception ignored) {
            // Cast not available on this device — simply no cast button, rest of the app works normally.
        }

        fullscreenBtn = new TextView(activity);
        fullscreenBtn.setText("⤢");
        fullscreenBtn.setTextColor(Color.WHITE);
        fullscreenBtn.setTextSize(20);
        fullscreenBtn.setGravity(Gravity.CENTER);
        fullscreenBtn.setPadding(pad, 0, pad, 0);
        fullscreenBtn.setOnClickListener(v -> { toggleFullscreen(activity); scheduleAutoHide(); });
        topBar.addView(fullscreenBtn, new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 36)));

        ImageButton closeBtn = new ImageButton(activity);
        closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeBtn.setBackgroundColor(Color.TRANSPARENT);
        closeBtn.setOnClickListener(v -> {
            if (isFullscreen) exitFullscreen(activity);
            container.setVisibility(View.GONE);
            notifyListeners("ended", new JSObject());
        });
        topBar.addView(closeBtn, new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36)));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        parent.addView(topBar, topLp);

        bottomBar = new LinearLayout(activity);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackgroundColor(0x99000000);
        bottomBar.setPadding(pad, pad, pad, pad);

        LinearLayout controlsRow = new LinearLayout(activity);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER);

        ImageButton seekBackBtn = new ImageButton(activity);
        seekBackBtn.setImageResource(android.R.drawable.ic_media_rew);
        seekBackBtn.setBackgroundColor(Color.TRANSPARENT);
        seekBackBtn.setOnClickListener(v -> { doSeekBy(-10); scheduleAutoHide(); });

        playPauseBtn = new ImageButton(activity);
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFFFF8A3D);
        playPauseBtn.setBackground(circle);
        playPauseBtn.setOnClickListener(v -> { togglePlayPause(); scheduleAutoHide(); });

        ImageButton seekFwdBtn = new ImageButton(activity);
        seekFwdBtn.setImageResource(android.R.drawable.ic_media_ff);
        seekFwdBtn.setBackgroundColor(Color.TRANSPARENT);
        seekFwdBtn.setOnClickListener(v -> { doSeekBy(10); scheduleAutoHide(); });

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
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; cancelAutoHide(); }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                long duration = getCurrentDuration();
                long newPos = (long) (duration * (sb.getProgress() / 1000.0));
                if (duration > 0) {
                    if (isCasting) seekRemote(newPos);
                    else if (mediaPlayer != null) mediaPlayer.setTime(newPos);
                }
                scheduleAutoHide();
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
        liveBadgeView.setText("● EN VIVO — desliza para cambiar de canal · toca el video para ocultar los controles");
        liveBadgeView.setTextColor(0xFFFF6B6B);
        liveBadgeView.setTextSize(10);
        liveBadgeView.setGravity(Gravity.CENTER);
        liveBadgeView.setPadding(0, dp(activity, 6), 0, 0);
        liveBadgeView.setVisibility(View.GONE);
        bottomBar.addView(liveBadgeView);

        castBadgeView = new TextView(activity);
        castBadgeView.setText("📺 Transmitiendo a la TV");
        castBadgeView.setTextColor(0xFF3DDC84);
        castBadgeView.setTextSize(11);
        castBadgeView.setGravity(Gravity.CENTER);
        castBadgeView.setPadding(0, dp(activity, 6), 0, 0);
        castBadgeView.setVisibility(View.GONE);
        bottomBar.addView(castBadgeView);

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.gravity = Gravity.BOTTOM;
        parent.addView(bottomBar, bottomLp);
    }

    // ---------- Google Cast logic ----------
    private void onCastConnected(CastSession session) {
        currentCastSession = session;
        isCasting = true;
        if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
        if (castBadgeView != null) castBadgeView.setVisibility(View.VISIBLE);
        castCurrentItem();
    }

    private void onCastDisconnected() {
        currentCastSession = null;
        isCasting = false;
        if (castBadgeView != null) castBadgeView.setVisibility(View.GONE);
        if (mediaPlayer != null) mediaPlayer.play();
    }

    private void castCurrentItem() {
        if (currentCastSession == null || urls.isEmpty()) return;
        RemoteMediaClient remoteMediaClient = currentCastSession.getRemoteMediaClient();
        if (remoteMediaClient == null) return;

        String url = urls.get(currentIndex);
        String title = titles.get(currentIndex);
        String contentType = url.contains(".m3u8") ? "application/x-mpegURL" : "video/mp4";

        MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        metadata.putString(MediaMetadata.KEY_TITLE, title);

        MediaInfo mediaInfo = new MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(contentType)
                .setMetadata(metadata)
                .build();

        MediaLoadRequestData loadRequestData = new MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build();

        remoteMediaClient.load(loadRequestData);
        if (titleView != null) titleView.setText(title);
    }

    private boolean isRemotePlaying() {
        if (currentCastSession == null) return false;
        RemoteMediaClient rmc = currentCastSession.getRemoteMediaClient();
        return rmc != null && rmc.isPlaying();
    }

    private void seekRemote(long positionMs) {
        if (currentCastSession == null) return;
        RemoteMediaClient rmc = currentCastSession.getRemoteMediaClient();
        if (rmc == null) return;
        rmc.seek(new MediaSeekOptions.Builder().setPosition(positionMs).build());
    }

    private long getCurrentDuration() {
        if (isCasting && currentCastSession != null) {
            RemoteMediaClient rmc = currentCastSession.getRemoteMediaClient();
            if (rmc != null && rmc.getMediaStatus() != null) return rmc.getMediaStatus().getMediaInfo().getStreamDuration();
            return 0;
        }
        return mediaPlayer != null ? mediaPlayer.getLength() : 0;
    }

    // ---------- Auto-hide controls ----------
    private void showControls() {
        controlsVisible = true;
        if (topBar != null) topBar.setVisibility(View.VISIBLE);
        if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
        scheduleAutoHide();
    }

    private void hideControls() {
        controlsVisible = false;
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
    }

    private void toggleControlsVisibility() {
        if (controlsVisible) {
            cancelAutoHide();
            hideControls();
        } else {
            showControls();
        }
    }

    private void scheduleAutoHide() {
        cancelAutoHide();
        hideControlsRunnable = this::hideControls;
        handler.postDelayed(hideControlsRunnable, 3500);
    }

    private void cancelAutoHide() {
        if (hideControlsRunnable != null) handler.removeCallbacks(hideControlsRunnable);
    }

    private void toggleFullscreen(Activity activity) {
        if (!isFullscreen) {
            savedLp = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) container.getLayoutParams());
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            container.setLayoutParams(lp);
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            hideSystemBars(activity);
            fullscreenBtn.setText("⤡");
            isFullscreen = true;
        } else {
            exitFullscreen(activity);
        }
    }

    private void exitFullscreen(Activity activity) {
        if (savedLp != null) container.setLayoutParams(savedLp);
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        showSystemBars(activity);
        fullscreenBtn.setText("⤢");
        isFullscreen = false;
    }

    private void hideSystemBars(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showSystemBars(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private int dp(Activity activity, int value) {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        return (int) (value * dm.density);
    }

    private void togglePlayPause() {
        if (isCasting && currentCastSession != null) {
            RemoteMediaClient rmc = currentCastSession.getRemoteMediaClient();
            if (rmc == null) return;
            if (rmc.isPlaying()) rmc.pause(); else rmc.play();
            return;
        }
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
        if (isCasting && currentCastSession != null) {
            RemoteMediaClient rmc = currentCastSession.getRemoteMediaClient();
            if (rmc == null || rmc.getMediaStatus() == null) return;
            long newPos = rmc.getApproximateStreamPosition() + (deltaSeconds * 1000L);
            seekRemote(Math.max(0, newPos));
            return;
        }
        if (mediaPlayer == null) return;
        long duration = mediaPlayer.getLength();
        long newTime = mediaPlayer.getTime() + (deltaSeconds * 1000L);
        if (newTime < 0) newTime = 0;
        if (duration > 0 && newTime > duration) newTime = duration;
        mediaPlayer.setTime(newTime);
    }

    private void goToNext() {
        if (currentIndex + 1 < urls.size()) {
            currentIndex++;
            loadCurrent();
            showControls();
        }
    }

    private void goToPrevious() {
        if (currentIndex - 1 >= 0) {
            currentIndex--;
            loadCurrent();
            showControls();
        }
    }

    private void applyRect(PluginCall call) {
        if (container == null || isFullscreen) return;
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
        if (urls.isEmpty()) return;
        titleView.setText(titles.get(currentIndex));
        if (maintenanceView != null) maintenanceView.setVisibility(View.GONE);
        scheduleMaintenanceTimer();

        if (isCasting) {
            castCurrentItem();
        } else if (mediaPlayer != null) {
            Media media = new Media(libVLC, android.net.Uri.parse(urls.get(currentIndex)));
            media.setHWDecoderEnabled(true, false);
            mediaPlayer.setMedia(media);
            media.release();
            mediaPlayer.play();
        }

        JSObject data = new JSObject();
        data.put("title", titles.get(currentIndex));
        data.put("index", currentIndex);
        data.put("count", urls.size());
        notifyListeners("trackChanged", data);
    }

    // ---------- "Canal en mantenimiento" (stuck-loading) screen ----------
    private void buildMaintenanceView(Activity activity, FrameLayout parent) {
        maintenanceView = new FrameLayout(activity);
        maintenanceView.setBackgroundColor(0xFF0B1B26);
        maintenanceView.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        ImageView logo = new ImageView(activity);
        int logoSize = dp(activity, 56);
        try {
            logo.setImageResource(activity.getResources().getIdentifier(
                    "ic_launcher_foreground", "mipmap", activity.getPackageName()));
        } catch (Exception ignored) { }
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoLp.bottomMargin = dp(activity, 14);
        col.addView(logo, logoLp);

        TextView msg = new TextView(activity);
        msg.setText("Canal en mantenimiento");
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(15);
        msg.setTypeface(msg.getTypeface(), android.graphics.Typeface.BOLD);
        msg.setGravity(Gravity.CENTER);
        col.addView(msg);

        TextView sub = new TextView(activity);
        sub.setText("No pudimos cargar esta señal");
        sub.setTextColor(0xFF9FB6C4);
        sub.setTextSize(11);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(activity, 6);
        col.addView(sub, subLp);

        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.gravity = Gravity.CENTER;
        maintenanceView.addView(col, colLp);

        // Sits above the video but below the top/bottom control bars.
        parent.addView(maintenanceView, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void scheduleMaintenanceTimer() {
        cancelMaintenanceTimer();
        maintenanceRunnable = () -> {
            if (maintenanceView != null) {
                maintenanceView.setAlpha(0f);
                maintenanceView.setVisibility(View.VISIBLE);
                maintenanceView.animate().alpha(1f).setDuration(200).start();
            }
        };
        handler.postDelayed(maintenanceRunnable, MAINTENANCE_TIMEOUT_MS);
    }

    private void cancelMaintenanceTimer() {
        if (maintenanceRunnable != null) handler.removeCallbacks(maintenanceRunnable);
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
                if (!userSeeking) {
                    long duration = getCurrentDuration();
                    long position;
                    boolean playing;
                    if (isCasting && currentCastSession != null) {
                        RemoteMediaClient rmc = currentCastSession.getRemoteMediaClient();
                        position = rmc != null ? rmc.getApproximateStreamPosition() : 0;
                        playing = rmc != null && rmc.isPlaying();
                    } else if (mediaPlayer != null) {
                        position = mediaPlayer.getTime();
                        playing = mediaPlayer.isPlaying();
                    } else {
                        position = 0; playing = false;
                    }
                    boolean isLive = duration <= 0;
                    progressRow.setVisibility(isLive ? View.GONE : View.VISIBLE);
                    liveBadgeView.setVisibility((isLive && !isCasting) ? View.VISIBLE : View.GONE);
                    if (!isLive) {
                        timeCurView.setText(fmt(position));
                        timeDurView.setText(fmt(duration));
                        seekBar.setProgress((int) (1000.0 * position / duration));
                    }
                    JSObject data = new JSObject();
                    data.put("position", position);
                    data.put("duration", duration);
                    data.put("isPlaying", playing);
                    notifyListeners("progress", data);
                }
                handler.postDelayed(this, 500);
            }
        };
        handler.post(progressTicker);
        showControls();
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
