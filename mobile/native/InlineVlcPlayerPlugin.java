package com.netgo.mobile;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders native video ON TOP of the WebView using ExoPlayer (Media3) —
 * the same player engine family TiviMate and most major Android IPTV
 * apps use. Supports: play/pause, seek, a fullscreen toggle (rotates to
 * landscape, hides system bars), swipe left/right to move between items
 * in the queue, and auto-hiding controls (tap the video to show/hide
 * them).
 */
@OptIn(markerClass = UnstableApi.class)
@CapacitorPlugin(name = "InlineVlcPlayer")
public class InlineVlcPlayerPlugin extends Plugin {

    private static InlineVlcPlayerPlugin currentInstance;

    @Override
    public void load() {
        currentInstance = this;
    }

    /** Called from MainActivity's back button handling — closes the video
     *  if one is open, returning true if it handled the press. */
    public static boolean handleBackPress() {
        if (currentInstance == null) return false;
        return currentInstance.closeIfOpen();
    }

    private boolean closeIfOpen() {
        if (container != null && container.getVisibility() == View.VISIBLE) {
            if (isFullscreen) exitFullscreen(getActivity());
            getActivity().getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            container.setVisibility(View.GONE);
            notifyListeners("ended", new JSObject());
            return true;
        }
        return false;
    }

    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private PlayerView videoLayout;
    private FrameLayout container;
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
    private boolean controlsEnabled = true;

    @PluginMethod
    public void mount(PluginCall call) {
        controlsEnabled = call.getData().optBoolean("showControls", true);
        getActivity().runOnUiThread(() -> {
            // Every fresh mount starts clean — never inherits a landscape/
            // fullscreen state left over from a previous video, so a new
            // selection from Películas/Series always begins in the
            // normal (portrait, on phone) view.
            if (isFullscreen) exitFullscreen(getActivity());
            else getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
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
            ret.put("isPlaying", player != null && player.isPlaying());
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
            if (player != null) player.seekTo(positionMs);
        });
        call.resolve();
    }

    @PluginMethod
    public void unmount(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (isFullscreen) exitFullscreen(getActivity());
            getActivity().getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            stopTicker();
            cancelAutoHide();
            if (videoLayout != null) videoLayout.setPlayer(null);
            if (player != null) {
                player.stop();
                player.release();
                player = null;
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

        // The fullscreen native player (VlcPlayerActivity) already keeps
        // the screen awake — this one (the phone's main player, and also
        // the TV home screen's live preview box) never did, so the device
        // was sleeping/locking mid-playback.
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        container = new FrameLayout(activity);
        // Inflated from XML specifically because that's the only way to
        // configure PlayerView's surface type — the default (SurfaceView)
        // renders as its own compositor layer that sits ABOVE the
        // WebView's own content regardless of normal Android z-ordering,
        // which was hiding the web-based title/cast/close buttons behind
        // the video. TextureView is a normal View that composites
        // properly alongside everything else.
        int layoutId = activity.getResources().getIdentifier("inline_player_view", "layout", activity.getPackageName());
        videoLayout = (PlayerView) android.view.LayoutInflater.from(activity).inflate(layoutId, container, false);
        videoLayout.setUseController(false);
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

        if (controlsEnabled) buildControls(activity, container);

        root.addView(container, new FrameLayout.LayoutParams(0, 0));

        // A bigger buffer than before: 600ms was tuned for fast start on
        // good WiFi, but on slow/flaky mobile data it caused constant
        // rebuffering. 2500ms is a safer middle ground — still starts
        // reasonably fast, but absorbs slow-network hiccups much better.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                // Same fix as the TV player: minBufferMs must be >= the
                // other two values or ExoPlayer throws at construction time.
                .setBufferDurationsMs(4000, 25000, 2000, 3500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(10000)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("NetGo/1.0 (Linux;Android) ExoPlayerLib/1.4.1");
        AdaptiveTrackSelection.Factory adaptiveFactory = new AdaptiveTrackSelection.Factory(
                15_000, 12_000, 25_000, 0.75f);
        trackSelector = new DefaultTrackSelector(activity, adaptiveFactory);
        // Same as the TV player — no artificial cap, so playback always
        // aims for each channel's real/native quality.
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
                .setMaxVideoBitrate(Integer.MAX_VALUE)
                .setForceHighestSupportedBitrate(false));

        androidx.media3.exoplayer.upstream.DefaultBandwidthMeter bandwidthMeter =
                new androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(activity)
                        .setInitialBitrateEstimate(8_000_000)
                        .build();

        player = new ExoPlayer.Builder(activity)
                .setRenderersFactory(new DefaultRenderersFactory(activity)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(activity)
                        .setDataSourceFactory(httpFactory)
                        .setLoadErrorHandlingPolicy(new IptvLoadErrorPolicy()))
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .setBandwidthMeter(bandwidthMeter)
                .build();
        videoLayout.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    getActivity().runOnUiThread(InlineVlcPlayerPlugin.this::advanceOrNotifyEnd);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                getActivity().runOnUiThread(() -> {
                    if (playPauseBtn != null) {
                        playPauseBtn.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
                    }
                    if (isPlaying) selectSpanishAudioTrack();
                });
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                // Same silent-recovery spirit as the fullscreen player —
                // just try the same item again rather than showing an
                // error state.
            }
        });

        startTicker();
    }

    // If the stream has more than one audio track, switch to a Spanish
    // Latin American one automatically — checked every time playback
    // starts, since a new item can have a different track layout than
    // the last one. Prefers a track explicitly labeled "Latino"/"es-419"
    // over a generic Spanish one (Spain dub), falling back to any
    // Spanish-labeled track if that's all there is.
    private void selectSpanishAudioTrack() {
        if (player == null || trackSelector == null) return;
        Tracks tracks = player.getCurrentTracks();

        java.util.regex.Pattern latino = java.util.regex.Pattern.compile(
                "latino|latin\\s*am|es-?419", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern anySpanish = java.util.regex.Pattern.compile(
                "espa|spanish|\\bspa\\b|\\bes\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

        Tracks.Group latinoGroup = null, spanishGroup = null;
        int latinoTrackIdx = 0, spanishTrackIdx = 0;
        int audioGroupCount = 0;

        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            audioGroupCount++;
            for (int i = 0; i < group.length; i++) {
                androidx.media3.common.Format fmt = group.getTrackFormat(i);
                String label = (fmt.label != null ? fmt.label : "") + " " + (fmt.language != null ? fmt.language : "");
                if (latinoGroup == null && latino.matcher(label).find()) { latinoGroup = group; latinoTrackIdx = i; }
                if (spanishGroup == null && anySpanish.matcher(label).find()) { spanishGroup = group; spanishTrackIdx = i; }
            }
        }
        if (audioGroupCount <= 1 && latinoGroup == null && spanishGroup == null) return;

        Tracks.Group chosenGroup = latinoGroup != null ? latinoGroup : spanishGroup;
        int chosenIdx = latinoGroup != null ? latinoTrackIdx : spanishTrackIdx;
        if (chosenGroup != null) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setOverrideForType(new TrackSelectionOverride(chosenGroup.getMediaTrackGroup(), chosenIdx)));
        }
    }

    private void buildControls(Activity activity, FrameLayout parent) {
        int pad = dp(activity, 10);

        topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable topFade = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB0000000, 0x00000000});
        topBar.setBackground(topFade);
        topBar.setPadding(pad, pad, pad, dp(activity, 28));

        // Title, cast, and close all have to be native views (not HTML) —
        // the video's native layer always renders above the WebView's own
        // content as a sibling view added later, so any web-based element
        // in this same strip ends up invisible/unclickable underneath it,
        // no matter how it's styled. This was the actual bug behind the
        // "TV" button, the title, and the close (✕) all being hidden.
        titleView = new TextView(activity);
        titleView.setText("Reproduciendo");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(13);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(dp(activity, 4));
        topBar.addView(titleView, titleLp);

        // "Enviar a TV" — the actual send logic still lives in JS (it
        // already talks to Firebase) — this just notifies it.
        TextView castBtn = new TextView(activity);
        castBtn.setText("TV");
        castBtn.setTextColor(Color.WHITE);
        castBtn.setTextSize(12);
        castBtn.setTypeface(castBtn.getTypeface(), android.graphics.Typeface.BOLD);
        castBtn.setGravity(Gravity.CENTER);
        GradientDrawable castBg = new GradientDrawable();
        castBg.setColor(0x40FF8A3D);
        castBg.setStroke(dp(activity, 1), 0xFFFF8A3D);
        castBg.setCornerRadius(dp(activity, 14));
        castBtn.setBackground(castBg);
        castBtn.setOnClickListener(v -> { notifyListeners("castRequested", new JSObject()); scheduleAutoHide(); });
        LinearLayout.LayoutParams castLp = new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 28));
        castLp.setMargins(dp(activity, 8), 0, dp(activity, 8), 0);
        topBar.addView(castBtn, castLp);

        fullscreenBtn = new TextView(activity);
        fullscreenBtn.setText("⤢");
        fullscreenBtn.setTextColor(Color.WHITE);
        fullscreenBtn.setTextSize(20);
        fullscreenBtn.setGravity(Gravity.CENTER);
        fullscreenBtn.setPadding(pad, 0, pad, 0);
        fullscreenBtn.setOnClickListener(v -> { toggleFullscreen(activity); scheduleAutoHide(); });
        topBar.addView(fullscreenBtn, new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 36)));

        TextView nativeCloseBtn = new TextView(activity);
        nativeCloseBtn.setText("✕");
        nativeCloseBtn.setTextColor(Color.WHITE);
        nativeCloseBtn.setTextSize(15);
        nativeCloseBtn.setGravity(Gravity.CENTER);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setShape(GradientDrawable.OVAL);
        closeBg.setColor(0x99060E14);
        nativeCloseBtn.setBackground(closeBg);
        nativeCloseBtn.setOnClickListener(v -> closeIfOpen());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 28));
        closeLp.setMarginStart(dp(activity, 8));
        topBar.addView(nativeCloseBtn, closeLp);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.gravity = Gravity.TOP;
        parent.addView(topBar, topLp);

        bottomBar = new LinearLayout(activity);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bottomFade = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xC0000000, 0x00000000});
        bottomBar.setBackground(bottomFade);
        bottomBar.setPadding(pad, dp(activity, 30), pad, pad);

        LinearLayout controlsRow = new LinearLayout(activity);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER);

        ImageButton seekBackBtn = new ImageButton(activity);
        seekBackBtn.setImageResource(android.R.drawable.ic_media_rew);
        GradientDrawable seekBackBg = new GradientDrawable();
        seekBackBg.setShape(GradientDrawable.OVAL);
        seekBackBg.setColor(0x33FFFFFF);
        seekBackBtn.setBackground(seekBackBg);
        seekBackBtn.setOnClickListener(v -> { doSeekBy(-10); scheduleAutoHide(); });

        playPauseBtn = new ImageButton(activity);
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        GradientDrawable circle = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFFC98A, 0xFFFF8A3D});
        circle.setShape(GradientDrawable.OVAL);
        playPauseBtn.setBackground(circle);
        playPauseBtn.setElevation(dp(activity, 6));
        playPauseBtn.setOnClickListener(v -> { togglePlayPause(); scheduleAutoHide(); });

        ImageButton seekFwdBtn = new ImageButton(activity);
        seekFwdBtn.setImageResource(android.R.drawable.ic_media_ff);
        GradientDrawable seekFwdBg = new GradientDrawable();
        seekFwdBg.setShape(GradientDrawable.OVAL);
        seekFwdBg.setColor(0x33FFFFFF);
        seekFwdBtn.setBackground(seekFwdBg);
        seekFwdBtn.setOnClickListener(v -> { doSeekBy(10); scheduleAutoHide(); });

        LinearLayout.LayoutParams sideBtnLp = new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44));
        sideBtnLp.setMargins(dp(activity, 16), 0, dp(activity, 16), 0);
        LinearLayout.LayoutParams mainBtnLp = new LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 58));

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
                if (duration > 0 && player != null) player.seekTo(newPos);
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

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.gravity = Gravity.BOTTOM;
        parent.addView(bottomBar, bottomLp);
    }

    private long getCurrentDuration() {
        if (player == null) return 0;
        long d = player.getDuration();
        return d == C.TIME_UNSET ? 0 : d;
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
            if (videoLayout != null) videoLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); // show the whole image, never cropping
            isFullscreen = true;
        } else {
            exitFullscreen(activity);
        }
    }

    private void exitFullscreen(Activity activity) {
        if (savedLp != null) container.setLayoutParams(savedLp);
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        showSystemBars(activity);
        if (fullscreenBtn != null) fullscreenBtn.setText("⤢");
        if (videoLayout != null) videoLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); // back to the small preview box's normal fit
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
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
    }

    private void doSeekBy(int deltaSeconds) {
        if (player == null) return;
        long duration = getCurrentDuration();
        long newTime = player.getCurrentPosition() + (deltaSeconds * 1000L);
        if (newTime < 0) newTime = 0;
        if (duration > 0 && newTime > duration) newTime = duration;
        player.seekTo(newTime);
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
        if (titleView != null) titleView.setText(titles.get(currentIndex));

        if (player != null) {
            MediaItem item = new MediaItem.Builder()
                    .setUri(Uri.parse(urls.get(currentIndex)))
                    .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(10000)
                            .setMinPlaybackSpeed(0.99f)
                            .setMaxPlaybackSpeed(1.01f)
                            .build())
                    .build();
            player.setMediaItem(item);
            player.prepare();
            player.play();
        }

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
                if (!userSeeking) {
                    long duration = getCurrentDuration();
                    long position;
                    boolean playing;
                    if (player != null) {
                        position = player.getCurrentPosition();
                        playing = player.isPlaying();
                    } else {
                        position = 0; playing = false;
                    }
                    boolean isLive = duration <= 0;
                    if (progressRow != null) progressRow.setVisibility(isLive ? View.GONE : View.VISIBLE);
                    if (liveBadgeView != null) liveBadgeView.setVisibility(isLive ? View.VISIBLE : View.GONE);
                    if (!isLive && timeCurView != null) {
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
        if (controlsEnabled) showControls();
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
