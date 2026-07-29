package com.netgo.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Fullscreen native video player using ExoPlayer (Media3) — the same
 * player engine family TiviMate and most major Android IPTV apps use,
 * chosen for its broad HLS/format compatibility and robust reconnect
 * handling versus the previous libVLC-based player.
 *
 * Two ways to launch it:
 *  - play(queue, startIndex): a flat queue (movies, series episodes, or a
 *    single live channel's sibling list). Channel Up/Down / D-pad Up/Down
 *    surf through it.
 *  - playLive(categories, catIndex, startIndex): live TV with EVERY
 *    category's channels included, so pressing the remote's Right button
 *    opens a channel list (browse/pick any channel in the current
 *    category), and pressing Right again opens a category list next to it
 *    (switch category without leaving fullscreen).
 */
@OptIn(markerClass = UnstableApi.class)
public class VlcPlayerActivity extends Activity {

    @Override
    public void finish() {
        super.finish();
        // No transition animation at all when closing — this removes any
        // chance of a flash/flicker during the switch back to the main
        // screen, regardless of what's causing it on a given device.
        overridePendingTransition(0, 0);
    }

    private ExoPlayer player;
    private PlayerView videoLayout;
    private DefaultTrackSelector trackSelector;
    private TextView titleView;
    private ProgressBar spinner;
    private TextView loadingEpisodeText;

    // ---- Flat-queue mode (movies / series / single channel list) ----
    private final List<String> urls = new ArrayList<>();
    private final List<String> tsUrls = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final List<String> nums = new ArrayList<>();
    private final List<String> imgUrls = new ArrayList<>();
    private final List<String> epgUrls = new ArrayList<>();
    private int currentIndex = 0;
    private long pendingResumePositionMs = 0;
    private String continueItemJson = "";
    // If a channel's HLS (.m3u8) stream keeps failing, automatically try
    // its raw MPEG-TS (.ts) URL instead — some Xtream panels' HLS output
    // is unreliable (plays briefly, then stalls) even though the exact
    // same channel works fine as a direct stream.
    private boolean usingFallbackFormat = false;

    // ---- Live TV mode (categories + browsing) ----
    private boolean isLive = false;
    // Whether this is a phone/tablet (touch-driven) rather than an
    // Android TV / Google TV / TV box (remote-driven) — controls which
    // interaction layer gets built: swipe/tap for one, D-pad for the
    // other. Leanback (TV) devices report this system feature; regular
    // phones don't.
    private boolean isPhoneDevice;
    private GestureDetector gestureDetector;
    private LinearLayout touchControlsBar;
    private ImageButton touchPlayPauseBtn;
    private TextView touchFullscreenBtn;
    private boolean touchControlsVisible = false;
    private Runnable hideTouchControlsRunnable;
    private final List<String> catTitles = new ArrayList<>();
    private final List<List<String>> catUrls = new ArrayList<>();
    private final List<List<String>> catTsUrls = new ArrayList<>();
    private final List<List<String>> catTitlesPerItem = new ArrayList<>();
    private final List<List<String>> catNums = new ArrayList<>();
    private final List<List<String>> catImgUrls = new ArrayList<>();
    private final List<List<String>> catEpgUrls = new ArrayList<>();
    private int currentCatIndex = 0;

    private static final int BROWSE_NONE = 0;
    private static final int BROWSE_CHANNELS = 1;
    private static final int BROWSE_CATEGORIES = 2;
    private int browseState = BROWSE_NONE;
    private int browseChannelIndex = 0;
    private int browseCatIndex = 0;

    private FrameLayout channelPanel;
    private LinearLayout channelListCol;
    private ScrollView channelScroll;
    private FrameLayout categoryPanel;
    private LinearLayout categoryListCol;
    private ScrollView categoryScroll;
    private FrameLayout episodePanel;
    private LinearLayout episodeListCol;
    private ScrollView episodeScroll;
    private boolean episodeBrowseOpen = false;
    private int browseEpisodeIndex = 0;

    // ---- Channel change banner ----
    private FrameLayout bannerRoot;
    private TextView bannerNum;
    private TextView bannerName;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ---- Remote lock (paused/blocked by the admin) — checked directly by
    // this screen, since it stays open independently of the main app's
    // WebView (which gets backgrounded and stops checking while a video
    // plays fullscreen). ----
    private String deviceCode = "";
    private static final String FIREBASE_BASE = "https://netgo-pairing-default-rtdb.firebaseio.com";
    private static final long STATUS_CHECK_INTERVAL_MS = 20000;
    private Runnable statusCheckRunnable;
    private FrameLayout lockoutView;
    private TextView lockoutTitleView;
    private TextView lockoutMsgView;
    private Runnable hideBannerRunnable;

    // ---- Progress bar (movies/series only, not live TV) ----
    private LinearLayout progressBarContainer;
    private SeekBar seekBar;
    private TextView timeElapsedView;
    private TextView timeTotalView;
    private TextView ccToggle;
    private Runnable progressTickRunnable;
    private boolean userSeeking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isPhoneDevice = !getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
                && !getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_vlc_player);

        videoLayout = findViewById(R.id.video_layout);
        titleView = findViewById(R.id.player_title);
        ImageButton closeBtn = findViewById(R.id.player_close);
        spinner = findViewById(R.id.player_spinner);
        loadingEpisodeText = findViewById(R.id.player_loading_text);
        progressBarContainer = findViewById(R.id.player_progress_bar);
        seekBar = findViewById(R.id.player_seekbar);
        seekBar.setFocusable(false);
        seekBar.setFocusableInTouchMode(false);
        timeElapsedView = findViewById(R.id.player_time_elapsed);
        timeTotalView = findViewById(R.id.player_time_total);
        ccToggle = findViewById(R.id.player_cc_toggle);
        ccToggle.setOnClickListener(v -> openAudioSubtitleMenu());
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) timeElapsedView.setText(formatTime(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (player != null) player.seekTo(sb.getProgress());
            }
        });
        closeBtn.setOnClickListener(v -> finish());

        // On TV, this ImageButton was the only focusable view on screen, so
        // Android auto-focused it on load — pressing OK on the remote then
        // triggered its click (closing the video) instead of doing nothing,
        // which is what should happen during normal playback.
        closeBtn.setFocusable(false);
        closeBtn.setFocusableInTouchMode(false);
        View rootView = findViewById(android.R.id.content);
        rootView.setFocusableInTouchMode(true);
        rootView.requestFocus();

        buildChannelBanner();
        buildBrowsePanels();
        buildLockoutView();

        if (!parseIntentData()) {
            finish();
            return;
        }
        deviceCode = getIntent().getStringExtra("deviceCode");

        if (isPhoneDevice) {
            buildTouchControls();
            setupGestureDetector();
            // Movies/series/anime/Kids/seguir viendo: rotate to landscape
            // automatically — only live TV stays portrait by default with
            // a manual fullscreen button instead.
            if (!isLive) {
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        }

        // Tuned for live IPTV/HLS: big enough to absorb real network
        // fluctuations without stalling, without adding so much delay
        // that a channel feels slow to respond.
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                // The first two numbers (min/max buffer) were already
                // generous — the real fix for micro-stutters is the third
                // and fourth ones: how much buffer is required before
                // starting/resuming playback. 1500/3000ms left very little
                // cushion, so any tiny network hiccup during otherwise
                // smooth playback immediately caused a visible micro-cut.
                // minBufferMs MUST be >= bufferForPlaybackAfterRebufferMs (and
                // >= bufferForPlaybackMs) or ExoPlayer throws an
                // IllegalArgumentException the moment it's built — which is
                // exactly what was crashing the app right after the
                // catalog/pairing screen, since that's when the first
                // player gets constructed (the TV home screen preview).
                .setBufferDurationsMs(8000, 40000, 3000, 5000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(10000)
                .setAllowCrossProtocolRedirects(true)
                // Some IPTV panels rate-limit or reject clients they don't
                // recognize — identifying as a well-known player name
                // improves compatibility with those servers.
                .setUserAgent("NetGo/1.0 (Linux;Android) ExoPlayerLib/1.4.1");

        // Tuned for IPTV specifically: slower to bump UP in quality (avoids
        // flip-flopping right after a brief bandwidth spike), quicker to
        // drop down when the network really can't keep up (better a
        // seamless step down than a stall), and uses a bit more of the
        // measured bandwidth than the library default since live TV
        // benefits more from steady quality than from a large safety
        // margin.
        // Tuned so a channel always STARTS at its best available quality
        // instead of creeping up to it — only steps down if the network
        // genuinely can't keep up (to avoid a freeze), and once it does,
        // waits a long time before trying to step back up, so the person
        // never sees a visible back-and-forth in quality.
        AdaptiveTrackSelection.Factory adaptiveFactory = new AdaptiveTrackSelection.Factory(
                60_000,  // minDurationForQualityIncreaseMs — long, so a recovered connection doesn't visibly bounce back up
                10_000,  // maxDurationForQualityDecreaseMs — reacts promptly to real trouble, before it becomes a stall
                25_000,  // minDurationToRetainAfterDiscardMs
                0.75f);  // bandwidthFraction
        trackSelector = new DefaultTrackSelector(this, adaptiveFactory);
        // No cap on resolution/bitrate — always eligible to play at
        // whatever quality the channel's own stream actually offers. For
        // channels with multiple quality variants (adaptive HLS), this is
        // what lets ExoPlayer pick the best one bandwidth allows instead
        // of being artificially held back.
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
                .setMaxVideoBitrate(Integer.MAX_VALUE)
                .setForceHighestSupportedBitrate(false)); // still adaptive — steps down only if the network truly can't keep up

        // A confidently high starting estimate — a channel begins at its
        // best quality immediately instead of ramping up to it over the
        // first several seconds, which is what "buscando la mejor
        // calidad" looked like from the outside. Only actually drops if
        // real playback shows the connection can't sustain it.
        androidx.media3.exoplayer.upstream.DefaultBandwidthMeter bandwidthMeter =
                new androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(this)
                        .setInitialBitrateEstimate(20_000_000)
                        .build();

        player = new ExoPlayer.Builder(this)
                .setRenderersFactory(new DefaultRenderersFactory(this)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(httpFactory)
                        .setLoadErrorHandlingPolicy(new IptvLoadErrorPolicy()))
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .setBandwidthMeter(bandwidthMeter)
                .build();
        videoLayout.setPlayer(player);
        videoLayout.setUseController(false);
        videoLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); // show the whole image, never cropping — no forced zoom by default

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    runOnUiThread(() -> {
                        spinner.setVisibility(View.GONE);
                        if (loadingEpisodeText != null) loadingEpisodeText.setVisibility(View.GONE);
                        selectSpanishAudioTrack();
                        liveRetryCount = 0;
                        cancelStallTimer();
                        if (isPhoneDevice) showTouchControls();
                        if (!isLive) {
                            startProgressTicker();
                            showCcButtonTemporarily();
                            if (pendingResumePositionMs > 0) {
                                player.seekTo(pendingResumePositionMs);
                                pendingResumePositionMs = 0;
                            }
                        }
                    });
                } else if (state == Player.STATE_BUFFERING) {
                    if (isLive) runOnUiThread(VlcPlayerActivity.this::scheduleStallTimer);
                } else if (state == Player.STATE_ENDED) {
                    runOnUiThread(() -> {
                        // A live channel's connection dropping briefly can
                        // also surface as ENDED (not just a player error) —
                        // reconnect to the SAME channel instead of
                        // advancing. Only movies/series actually finishing
                        // should move on to the next item.
                        if (isLive) loadCurrent();
                        else advanceOrFinish();
                    });
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                // Silent, automatic recovery — no dialog, no channel
                // change. Reconnects to the exact same channel, backing
                // off a little more each time so a truly dead stream
                // doesn't hammer the server in a tight loop.
                runOnUiThread(() -> {
                    spinner.setVisibility(View.GONE);
                    if (isLive) scheduleLiveRetry();
                });
            }
        });

        loadCurrent();
        showChannelBanner();
        scheduleStatusCheck();
    }

    private boolean parseIntentData() {
        try {
            isLive = getIntent().getBooleanExtra("isLive", false);
            if (isLive) {
                JSONArray cats = VlcPlayerPlugin.pendingCategories;
                VlcPlayerPlugin.pendingCategories = null;
                if (cats == null) return false;
                for (int c = 0; c < cats.length(); c++) {
                    JSONObject cat = cats.getJSONObject(c);
                    catTitles.add(cat.optString("title", "Categoría"));
                    JSONArray items = cat.getJSONArray("items");
                    List<String> u = new ArrayList<>(), ts = new ArrayList<>(), t = new ArrayList<>(), n = new ArrayList<>();
                    List<String> im = new ArrayList<>(), eg = new ArrayList<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.getJSONObject(i);
                        u.add(it.getString("url"));
                        ts.add(it.optString("tsUrl", ""));
                        t.add(it.optString("title", "Canal"));
                        n.add(it.optString("num", ""));
                        im.add(it.optString("img", ""));
                        eg.add(it.optString("epgUrl", ""));
                    }
                    catUrls.add(u);
                    catTsUrls.add(ts);
                    catTitlesPerItem.add(t);
                    catNums.add(n);
                    catImgUrls.add(im);
                    catEpgUrls.add(eg);
                }
                currentCatIndex = getIntent().getIntExtra("catIndex", 0);
                if (currentCatIndex < 0 || currentCatIndex >= catTitles.size()) currentCatIndex = 0;
                currentIndex = getIntent().getIntExtra("startIndex", 0);
                loadActiveCategoryIntoFlatLists();
                if (currentIndex < 0 || currentIndex >= urls.size()) currentIndex = 0;
                return !urls.isEmpty();
            } else {
                JSONArray arr = VlcPlayerPlugin.pendingQueue;
                VlcPlayerPlugin.pendingQueue = null;
                if (arr == null) return false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    urls.add(obj.getString("url"));
                    titles.add(obj.optString("title", "Reproduciendo"));
                    nums.add(obj.optString("num", ""));
                    imgUrls.add("");
                    epgUrls.add("");
                }
                pendingResumePositionMs = getIntent().getLongExtra("startPositionMs", 0);
                continueItemJson = getIntent().getStringExtra("contPlayingItem");
                currentIndex = getIntent().getIntExtra("startIndex", 0);
                if (currentIndex < 0 || currentIndex >= urls.size()) currentIndex = 0;
                return !urls.isEmpty();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Copies the current category's channels into the flat urls/titles/nums lists used for playback. */
    private void loadActiveCategoryIntoFlatLists() {
        urls.clear(); tsUrls.clear(); titles.clear(); nums.clear(); imgUrls.clear(); epgUrls.clear();
        urls.addAll(catUrls.get(currentCatIndex));
        tsUrls.addAll(catTsUrls.get(currentCatIndex));
        titles.addAll(catTitlesPerItem.get(currentCatIndex));
        nums.addAll(catNums.get(currentCatIndex));
        imgUrls.addAll(catImgUrls.get(currentCatIndex));
        epgUrls.addAll(catEpgUrls.get(currentCatIndex));
        prefetchChannelLogos(imgUrls); // so the banner shows every logo in this category instantly, not just the current one
    }

    // ---------- Remote control ----------
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // The admin message takes priority over everything else while it's
        // showing — any OK/Back press just dismisses it (this player
        // doesn't use Android's native view-focus system for its
        // controls, they're all handled directly here by keycode).
        if (adminMessageView != null && adminMessageView.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_BACK) {
                adminMessageView.animate().alpha(0f).setDuration(150)
                        .withEndAction(() -> adminMessageView.setVisibility(View.GONE)).start();
            }
            return true;
        }
        if (audioSubMenuOverlay != null && audioSubMenuOverlay.getParent() != null) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { moveAudioMenuSelection(-1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { moveAudioMenuSelection(1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                confirmAudioMenuSelection();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeAudioSubtitleMenu();
                return true;
            }
            return true; // swallow everything else while the menu is open
        }
        if (episodeBrowseOpen) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { moveEpisodeBrowseSelection(-1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { moveEpisodeBrowseSelection(1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                confirmEpisodeBrowseSelection();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                closeEpisodeBrowse();
                return true;
            }
            return true; // swallow everything else while the picker is open
        }
        if (isLive && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (browseState == BROWSE_NONE) { openChannelBrowse(); return true; }
            if (browseState == BROWSE_CHANNELS) { openCategoryBrowse(); return true; }
            return true;
        }
        if (isLive && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (browseState == BROWSE_CATEGORIES) { closeCategoryBrowse(); return true; }
            if (browseState == BROWSE_CHANNELS) { closeAllBrowse(); return true; }
        }
        if (isLive && browseState != BROWSE_NONE) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { moveBrowseSelection(-1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { moveBrowseSelection(1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                confirmBrowseSelection();
                return true;
            }
            return true; // swallow other keys while browsing so they don't hit the player
        }

        // Movies/series: left/right seeks 10s, OK toggles play/pause, Up
        // opens the audio/subtitles menu (CC). Down opens the full
        // episode list, but only when there's actually more than one item
        // to list (a lone movie has nothing to pick between).
        if (!isLive) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { seekBy(-10000); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { seekBy(10000); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && urls.size() > 1) { openEpisodeBrowse(); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                openAudioSubtitleMenu();
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            togglePlayPause();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CAPTIONS) {
            openAudioSubtitleMenu();
            return true;
        }

        // Live TV: Up/Down (and the dedicated channel keys) still surf
        // channels directly, same as always — only the non-live/episode
        // behavior above changed.
        if (isLive && urls.size() > 1) {
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                goToChannel(currentIndex - 1 < 0 ? urls.size() - 1 : currentIndex - 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                goToChannel(currentIndex + 1 >= urls.size() ? 0 : currentIndex + 1);
                return true;
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    private void togglePlayPause() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
    }

    // Manual zoom, since automatic "fill the screen" detection wasn't
    // reliable on every TV — this gives direct control instead: tap to
    // cycle through Original → Zoom, whichever looks right.
    private int zoomIndex = 0;
    private void cycleZoom() {
        if (videoLayout == null) return;
        zoomIndex = (zoomIndex + 1) % 2;
        videoLayout.setResizeMode(zoomIndex == 0
                ? AspectRatioFrameLayout.RESIZE_MODE_FIT
                : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        String label = zoomIndex == 0 ? "Tamaño original" : "Ajustado a pantalla";
        android.widget.Toast.makeText(this, label, android.widget.Toast.LENGTH_SHORT).show();
    }

    private boolean subtitlesEnabled = false;
    private FrameLayout audioSubMenuOverlay;

    /** The CC button's menu — lets you pick the real audio language (not
     *  just Spanish auto-detection) and turn subtitles on/off, all
     *  D-pad navigable like the channel/category browse panels. */
    private final List<TextView> audioMenuRows = new ArrayList<>();
    private final List<Runnable> audioMenuActions = new ArrayList<>();
    private int audioMenuSelectedIndex = 0;

    private void openAudioSubtitleMenu() {
        if (player == null || trackSelector == null) return;
        if (audioSubMenuOverlay != null && audioSubMenuOverlay.getParent() != null) {
            ((ViewGroup) audioSubMenuOverlay.getParent()).removeView(audioSubMenuOverlay);
        }
        audioMenuRows.clear();
        audioMenuActions.clear();
        audioMenuSelectedIndex = 0;

        FrameLayout root = findViewById(android.R.id.content);
        audioSubMenuOverlay = new FrameLayout(this);
        audioSubMenuOverlay.setBackgroundColor(0x99000000);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF142838);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(bg);

        TextView audioLabel = new TextView(this);
        audioLabel.setText("Idioma de audio");
        audioLabel.setTextColor(0xFF9FB6C4);
        audioLabel.setTextSize(12);
        audioLabel.setPadding(0, 0, 0, dp(6));
        card.addView(audioLabel);

        Tracks tracks = player.getCurrentTracks();
        int audioIdx = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < group.length; i++) {
                androidx.media3.common.Format fmt = group.getTrackFormat(i);
                String label = fmt.label != null ? fmt.label : (fmt.language != null ? fmt.language : ("Audio " + (audioIdx + 1)));
                boolean selected = group.isTrackSelected(i);
                final Tracks.Group fGroup = group;
                final int fIndex = i;
                TextView row = buildMenuRow("🔊 " + label, selected);
                card.addView(row);
                audioMenuRows.add(row);
                final int rowIndexForClick = audioMenuRows.size() - 1;
                audioMenuActions.add(() -> {
                    trackSelector.setParameters(trackSelector.buildUponParameters()
                            .setOverrideForType(new TrackSelectionOverride(fGroup.getMediaTrackGroup(), fIndex)));
                    closeAudioSubtitleMenu();
                });
                row.setOnClickListener(v -> {
                    audioMenuSelectedIndex = rowIndexForClick;
                    confirmAudioMenuSelection();
                });
                audioIdx++;
            }
        }
        if (audioIdx == 0) {
            TextView none = buildMenuRow("Solo hay un idioma disponible", false);
            none.setEnabled(false);
            card.addView(none);
        }

        TextView subLabel = new TextView(this);
        subLabel.setText("Subtítulos");
        subLabel.setTextColor(0xFF9FB6C4);
        subLabel.setTextSize(12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(14);
        subLp.bottomMargin = dp(6);
        card.addView(subLabel, subLp);

        TextView subRow = buildMenuRow(subtitlesEnabled ? "✓ Activados" : "Desactivados", false);
        card.addView(subRow);
        audioMenuRows.add(subRow);
        final int subRowIndex = audioMenuRows.size() - 1;
        audioMenuActions.add(() -> {
            toggleSubtitlesOnly();
            closeAudioSubtitleMenu();
        });
        subRow.setOnClickListener(v -> {
            audioMenuSelectedIndex = subRowIndex;
            confirmAudioMenuSelection();
        });

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        audioSubMenuOverlay.addView(card, cardLp);
        audioSubMenuOverlay.setOnClickListener(v -> closeAudioSubtitleMenu());

        root.addView(audioSubMenuOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        highlightAudioMenuRow();
    }

    /** This player never relies on Android's own view-focus system for
     *  D-pad movement — every panel (channels, categories, episodes) is
     *  navigated by hand, tracking a selected index and re-drawing the
     *  highlighted row. This menu needs the exact same treatment, which
     *  is what was missing (Up/Down did nothing inside it before). */
    private void highlightAudioMenuRow() {
        for (int i = 0; i < audioMenuRows.size(); i++) {
            TextView row = audioMenuRows.get(i);
            boolean isCursor = (i == audioMenuSelectedIndex);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(isCursor ? 0xFFFF8A3D : 0x00000000);
            bg.setCornerRadius(dp(8));
            row.setBackground(bg);
            row.setTextColor(isCursor ? 0xFF1A0E00 : Color.WHITE);
        }
    }

    private void moveAudioMenuSelection(int delta) {
        if (audioMenuRows.isEmpty()) return;
        int max = audioMenuRows.size();
        audioMenuSelectedIndex = ((audioMenuSelectedIndex + delta) % max + max) % max;
        highlightAudioMenuRow();
    }

    private void confirmAudioMenuSelection() {
        if (audioMenuSelectedIndex < 0 || audioMenuSelectedIndex >= audioMenuActions.size()) return;
        audioMenuActions.get(audioMenuSelectedIndex).run();
    }

    private TextView buildMenuRow(String text, boolean selected) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(14);
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setMaxLines(1);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFFFF8A3D : 0x00000000);
        bg.setCornerRadius(dp(8));
        row.setBackground(bg);
        row.setTextColor(selected ? 0xFF1A0E00 : Color.WHITE);
        row.setTypeface(row.getTypeface(), selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return row;
    }

    private void closeAudioSubtitleMenu() {
        if (audioSubMenuOverlay != null && audioSubMenuOverlay.getParent() != null) {
            ((ViewGroup) audioSubMenuOverlay.getParent()).removeView(audioSubMenuOverlay);
        }
    }

    /** Turns subtitles on/off — the actual toggle logic, called from the
     *  CC menu's subtitle row. */
    private void toggleSubtitlesOnly() {
        if (player == null || trackSelector == null) return;
        if (subtitlesEnabled) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setIgnoredTextSelectionFlags(~0));
            subtitlesEnabled = false;
        } else {
            boolean found = false;
            Tracks tracks = player.getCurrentTracks();
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() == C.TRACK_TYPE_TEXT && group.length > 0) {
                    trackSelector.setParameters(trackSelector.buildUponParameters()
                            .setIgnoredTextSelectionFlags(0)
                            .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), 0)));
                    found = true;
                    break;
                }
            }
            if (!found) {
                android.widget.Toast.makeText(this, "Esta señal no tiene subtítulos disponibles", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            subtitlesEnabled = true;
        }
    }

    /** Seeks by deltaMs (negative = backward) and briefly shows the progress bar. */
    private void seekBy(int deltaMs) {
        if (player == null) return;
        long length = player.getDuration();
        long newTime = player.getCurrentPosition() + deltaMs;
        if (newTime < 0) newTime = 0;
        if (length > 0 && length != C.TIME_UNSET && newTime > length) newTime = length;
        player.seekTo(newTime);
        if (seekBar != null) {
            if (length > 0 && length != C.TIME_UNSET) seekBar.setMax((int) length);
            seekBar.setProgress((int) newTime);
        }
        if (timeElapsedView != null) timeElapsedView.setText(formatTime((int) newTime));
        if (timeTotalView != null && length > 0 && length != C.TIME_UNSET) timeTotalView.setText(formatTime((int) length));
        showProgressBarTemporarily();
        showCcButtonTemporarily();
    }

    /** Shows the progress bar for a few seconds (used when seeking), then
     *  auto-hides it again — it no longer stays on screen all the time. */
    private Runnable hideProgressBarRunnable;
    private void showProgressBarTemporarily() {
        if (progressBarContainer == null) return;
        progressBarContainer.setVisibility(View.VISIBLE);
        if (hideProgressBarRunnable != null) handler.removeCallbacks(hideProgressBarRunnable);
        hideProgressBarRunnable = () -> progressBarContainer.setVisibility(View.GONE);
        handler.postDelayed(hideProgressBarRunnable, 2500);
    }

    /** CC only makes sense for movies/series (real audio-language and
     *  subtitle tracks) — never shown for live TV, and hidden the rest of
     *  the time too so it's not sitting on screen permanently. Briefly
     *  reappears whenever the person touches the remote, then fades away
     *  again on its own. */
    private Runnable hideCcButtonRunnable;
    private void showCcButtonTemporarily() {
        if (ccToggle == null || isLive) return;
        ccToggle.setVisibility(View.VISIBLE);
        if (hideCcButtonRunnable != null) handler.removeCallbacks(hideCcButtonRunnable);
        hideCcButtonRunnable = () -> ccToggle.setVisibility(View.GONE);
        handler.postDelayed(hideCcButtonRunnable, 3500);
    }

    private void goToChannel(int index) {
        currentIndex = index;
        liveRetryCount = 0; // fresh channel picked by the user — start the backoff over
        usingFallbackFormat = false; // new channel always starts on the primary format
        loadCurrent();
        showChannelBanner();
    }

    private void advanceOrFinish() {
        if (currentIndex + 1 < urls.size()) {
            currentIndex++;
            loadCurrent();
            showChannelBanner();
        } else {
            finish();
        }
    }

    // If the stream has more than one audio track, switch to a Spanish
    // Latin American one automatically — checked every time playback
    // starts (movies/series AND live channels), since each item/channel
    // can have a different track layout. Prefers a track explicitly
    // labeled "Latino"/"es-419" over a generic Spanish one (Spain dub),
    // falling back to any Spanish-labeled track if that's all there is.
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
        if (audioGroupCount <= 1 && latinoGroup == null && spanishGroup == null) return; // nothing to switch between

        Tracks.Group chosenGroup = latinoGroup != null ? latinoGroup : spanishGroup;
        int chosenIdx = latinoGroup != null ? latinoTrackIdx : spanishTrackIdx;
        if (chosenGroup != null) {
            trackSelector.setParameters(trackSelector.buildUponParameters()
                    .setOverrideForType(new TrackSelectionOverride(chosenGroup.getMediaTrackGroup(), chosenIdx)));
        }
    }

    // ---------- Silent live-channel recovery (no dialog, no channel change) ----------
    private int liveRetryCount = 0;
    private Runnable stallRunnable;
    private static final long STALL_TIMEOUT_MS = 11000;

    /** If a live channel starts buffering and never reaches Playing within
     *  this window, treat it as frozen and reconnect automatically. Only
     *  arms once per buffering episode — the player can report buffering
     *  repeatedly while genuinely stuck at the same spot, and resetting
     *  the timer on every one of them meant it could never actually
     *  elapse. */
    private void scheduleStallTimer() {
        if (stallRunnable != null) return; // already armed for this stall
        stallRunnable = () -> {
            stallRunnable = null;
            if (isLive) scheduleLiveRetry();
        };
        handler.postDelayed(stallRunnable, STALL_TIMEOUT_MS);
    }

    private void cancelStallTimer() {
        if (stallRunnable != null) handler.removeCallbacks(stallRunnable);
        stallRunnable = null;
    }

    /** Reconnects to the exact same channel after a short, increasing
     *  delay — never a different channel, never a popup. Caps out at 15s
     *  between attempts so a genuinely dead stream retries forever
     *  without hammering the server. After a couple of failed attempts on
     *  the primary HLS (.m3u8) format, automatically switches to the raw
     *  MPEG-TS (.ts) format instead — some servers' HLS output is
     *  unreliable even though the same channel works fine as a direct
     *  stream. */
    private Runnable liveRetryRunnable;

    private void scheduleLiveRetry() {
        cancelStallTimer();
        cancelLiveRetry();
        liveRetryCount++;
        if (!usingFallbackFormat && liveRetryCount >= 2
                && currentIndex < tsUrls.size() && !tsUrls.get(currentIndex).isEmpty()) {
            usingFallbackFormat = true;
        }
        long delay = Math.min(3000L + (liveRetryCount * 2000L), 15000L);
        liveRetryRunnable = this::loadCurrent;
        handler.postDelayed(liveRetryRunnable, delay);
    }

    private void cancelLiveRetry() {
        if (liveRetryRunnable != null) handler.removeCallbacks(liveRetryRunnable);
        liveRetryRunnable = null;
    }

    private boolean showEpisodeLoadingTextNext = false;

    private void loadCurrent() {
        cancelStallTimer();
        cancelLiveRetry();
        if (showEpisodeLoadingTextNext && loadingEpisodeText != null) {
            loadingEpisodeText.setVisibility(View.VISIBLE);
            spinner.setVisibility(View.GONE);
        } else {
            spinner.setVisibility(View.VISIBLE);
            if (loadingEpisodeText != null) loadingEpisodeText.setVisibility(View.GONE);
        }
        showEpisodeLoadingTextNext = false;
        titleView.setText(titles.get(currentIndex));
        stopProgressTicker();
        if (progressBarContainer != null) {
            progressBarContainer.setVisibility(View.GONE);
            seekBar.setProgress(0);
        }
        if (ccToggle != null) {
            ccToggle.setVisibility(View.GONE);
            if (hideCcButtonRunnable != null) handler.removeCallbacks(hideCcButtonRunnable);
        }
        subtitlesEnabled = false;
        closeAudioSubtitleMenu();
        zoomIndex = 0; // matches the auto-applied RESIZE_MODE_FIT below
        if (videoLayout != null) videoLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT); // show the whole image, never cropping — no forced zoom by default
        String playUrl = (usingFallbackFormat && currentIndex < tsUrls.size() && !tsUrls.get(currentIndex).isEmpty())
                ? tsUrls.get(currentIndex) : urls.get(currentIndex);
        MediaItem.Builder itemBuilder = new MediaItem.Builder().setUri(Uri.parse(playUrl));
        if (isLive) {
            // A narrower speed range than before (was 0.96–1.04, now
            // 0.99–1.01) — nudging playback speed too aggressively to stay
            // near the buffer target can itself introduce tiny audible
            // artifacts, which was likely showing up as its own kind of
            // micro-cut. This is barely perceptible but still gives some
            // cushion against network jitter.
            itemBuilder.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(10000)
                    .setMinPlaybackSpeed(0.99f)
                    .setMaxPlaybackSpeed(1.01f)
                    .build());
        }
        MediaItem item = itemBuilder.build();
        player.setMediaItem(item);
        player.prepare();
        player.play();
    }

    // ================= Browse panels (channel list + category list) =================
    private void buildBrowsePanels() {
        FrameLayout root = findViewById(android.R.id.content);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int channelWidth = (int) (dm.widthPixels * 0.30f);
        int categoryWidth = (int) (dm.widthPixels * 0.26f);

        channelPanel = buildListPanel();
        channelScroll = (ScrollView) channelPanel.getChildAt(0);
        channelListCol = (LinearLayout) channelScroll.getChildAt(0);
        FrameLayout.LayoutParams chLp = new FrameLayout.LayoutParams(channelWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        chLp.gravity = Gravity.END | Gravity.TOP;
        root.addView(channelPanel, chLp);

        categoryPanel = buildListPanel();
        categoryScroll = (ScrollView) categoryPanel.getChildAt(0);
        categoryListCol = (LinearLayout) categoryScroll.getChildAt(0);
        FrameLayout.LayoutParams catLp = new FrameLayout.LayoutParams(categoryWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        catLp.gravity = Gravity.END | Gravity.TOP;
        catLp.rightMargin = channelWidth;
        root.addView(categoryPanel, catLp);

        // Episode picker (movies/series only) — small numbered boxes in a
        // single vertical column, opened with Down.
        episodePanel = buildListPanel();
        episodeScroll = (ScrollView) episodePanel.getChildAt(0);
        episodeListCol = (LinearLayout) episodeScroll.getChildAt(0);
        int episodeWidth = (int) (dm.widthPixels * 0.20f);
        FrameLayout.LayoutParams epLp = new FrameLayout.LayoutParams(episodeWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        epLp.gravity = Gravity.END | Gravity.TOP;
        root.addView(episodePanel, epLp);
    }

    private FrameLayout buildListPanel() {
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(0xE60B1B26);
        panel.setVisibility(View.GONE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        col.setPadding(pad, dp(24), pad, pad);
        scroll.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return panel;
    }

    private void openChannelBrowse() {
        browseState = BROWSE_CHANNELS;
        browseChannelIndex = currentIndex;
        populateChannelList();
        channelPanel.setVisibility(View.VISIBLE);
    }

    private void openCategoryBrowse() {
        browseState = BROWSE_CATEGORIES;
        browseCatIndex = currentCatIndex;
        populateCategoryList();
        categoryPanel.setVisibility(View.VISIBLE);
    }

    private void closeCategoryBrowse() {
        browseState = BROWSE_CHANNELS;
        categoryPanel.setVisibility(View.GONE);
    }

    private void closeAllBrowse() {
        browseState = BROWSE_NONE;
        channelPanel.setVisibility(View.GONE);
        categoryPanel.setVisibility(View.GONE);
    }

    // ---------- Episode picker (movies/series, opened with Down) ----------
    private void openEpisodeBrowse() {
        episodeBrowseOpen = true;
        browseEpisodeIndex = currentIndex;
        populateEpisodeList();
        episodePanel.setVisibility(View.VISIBLE);
    }

    private void closeEpisodeBrowse() {
        episodeBrowseOpen = false;
        episodePanel.setVisibility(View.GONE);
    }

    private void populateEpisodeList() {
        episodeListCol.removeAllViews();
        for (int i = 0; i < urls.size(); i++) {
            // Small numbered box, starting at 1 — not the episode's own
            // title/number metadata, just plain sequential order as asked.
            episodeListCol.addView(buildListRow(String.valueOf(i + 1), i == browseEpisodeIndex));
        }
        scrollToSelected(episodeScroll, episodeListCol, browseEpisodeIndex);
    }

    private void moveEpisodeBrowseSelection(int delta) {
        int max = urls.size();
        browseEpisodeIndex = ((browseEpisodeIndex + delta) % max + max) % max;
        populateEpisodeList();
    }

    private void confirmEpisodeBrowseSelection() {
        closeEpisodeBrowse();
        if (browseEpisodeIndex != currentIndex) {
            showEpisodeLoadingTextNext = true;
            goToChannel(browseEpisodeIndex);
        }
    }

    private void populateChannelList() {
        channelListCol.removeAllViews();
        List<String> names = catTitlesPerItem.get(currentCatIndex);
        List<String> imgs = catImgUrls.get(currentCatIndex);
        for (int i = 0; i < names.size(); i++) {
            String img = i < imgs.size() ? imgs.get(i) : "";
            LinearLayout row = buildChannelListRow(names.get(i), img, i == browseChannelIndex);
            final int idx = i;
            row.setOnClickListener(v -> { browseChannelIndex = idx; confirmBrowseSelection(); });
            channelListCol.addView(row);
        }
        scrollToSelected(channelScroll, channelListCol, browseChannelIndex);
    }

    private void populateCategoryList() {
        categoryListCol.removeAllViews();
        for (int i = 0; i < catTitles.size(); i++) {
            TextView row = buildListRow(catTitles.get(i), i == browseCatIndex);
            final int idx = i;
            row.setOnClickListener(v -> { browseCatIndex = idx; confirmBrowseSelection(); });
            categoryListCol.addView(row);
        }
        scrollToSelected(categoryScroll, categoryListCol, browseCatIndex);
    }

    /** A channel row with its logo — same visual language as the banner's
     *  logo box, just smaller, so the channel list and the OSD banner look
     *  consistent. */
    private LinearLayout buildChannelListRow(String text, String imgUrl, boolean selected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(10), dp(8));

        FrameLayout logoBox = new FrameLayout(this);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setColor(0xFF0B1B26);
        logoBg.setCornerRadius(dp(7));
        logoBox.setBackground(logoBg);
        TextView fallback = new TextView(this);
        fallback.setTextColor(Color.WHITE);
        fallback.setTextSize(13);
        fallback.setGravity(Gravity.CENTER);
        fallback.setText(text != null && !text.isEmpty() ? text.substring(0, 1).toUpperCase() : "?");
        logoBox.addView(fallback, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        logoLp.rightMargin = dp(10);
        row.addView(logoBox, logoLp);
        loadImageIntoBox(logoBox, fallback, imgUrl);

        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(15);
        label.setMaxLines(1);
        if (selected) {
            label.setTextColor(0xFF1A0E00);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFFF8A3D);
            bg.setCornerRadius(dp(8));
            row.setBackground(bg);
            label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        } else {
            label.setTextColor(0xFFEAF2F5);
        }
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** Shared logo-loading helper — used by both the channel browse list
     *  and the OSD banner, so a logo only needs to be fetched/decoded
     *  once per implementation instead of two separate copies. */
    // Shared, in-memory cache of already-decoded channel logos — keyed by
    // URL. Lets the banner show a logo instantly on channel change instead
    // of waiting on a fresh network fetch every single time. Capped at 80
    // entries (small icons, this stays well under a few MB).
    private static final android.util.LruCache<String, android.graphics.Bitmap> logoCache = new android.util.LruCache<>(80);

    /** Downloads and decodes a small icon at roughly the size it'll
     *  actually be shown at, instead of decoding the source image at full
     *  resolution and only THEN scaling it down on screen. Some provider
     *  logos come in surprisingly large (500px+), and decoding those at
     *  full size on a generic/uncertified TV box with 1-2GB of RAM adds
     *  up fast across dozens of channels — this keeps the memory cost of
     *  each one down to what a ~48-64dp icon actually needs. */
    private android.graphics.Bitmap downloadAndDecodeBitmap(String urlStr, int targetSizePx) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.connect();
        if (conn.getResponseCode() != 200) return null;

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        }
        byte[] bytes = bos.toByteArray();

        android.graphics.BitmapFactory.Options boundsOpts = new android.graphics.BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, boundsOpts);

        int sample = 1;
        while ((boundsOpts.outWidth / (sample * 2)) >= targetSizePx && (boundsOpts.outHeight / (sample * 2)) >= targetSizePx) {
            sample *= 2;
        }
        android.graphics.BitmapFactory.Options decodeOpts = new android.graphics.BitmapFactory.Options();
        decodeOpts.inSampleSize = sample;
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decodeOpts);
    }

    /** Fetches and decodes every channel logo for the given URLs into the
     *  shared cache, in the background, without touching any UI — called
     *  once a category's channel list is known, so by the time the person
     *  actually lands on a given channel its logo is already sitting in
     *  memory and the banner can show it immediately. */
    private void prefetchChannelLogos(List<String> imgUrls) {
        for (String url : imgUrls) {
            if (url == null || url.isEmpty() || logoCache.get(url) != null) continue;
            new Thread(() -> {
                try {
                    android.graphics.Bitmap bmp = downloadAndDecodeBitmap(url, 96);
                    if (bmp != null) logoCache.put(url, bmp);
                } catch (Exception ignored) { /* that one logo just won't be pre-warmed — falls back to a live fetch */ }
            }).start();
        }
    }

    private void loadImageIntoBox(FrameLayout box, TextView fallback, String url) {
        fallback.setVisibility(View.VISIBLE);
        if (url == null || url.isEmpty()) return;

        android.graphics.Bitmap cached = logoCache.get(url);
        if (cached != null) {
            // Already have it — show it right away, no network round trip.
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(cached);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            int pad = dp(4);
            iv.setPadding(pad, pad, pad, pad);
            box.addView(iv, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fallback.setVisibility(View.GONE);
            return;
        }

        new Thread(() -> {
            try {
                android.graphics.Bitmap bmp = downloadAndDecodeBitmap(url, 96);
                if (bmp == null) return;
                logoCache.put(url, bmp);
                final android.graphics.Bitmap finalBmp = bmp;
                runOnUiThread(() -> {
                    android.widget.ImageView iv = new android.widget.ImageView(this);
                    iv.setImageBitmap(finalBmp);
                    iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    int pad = dp(4);
                    iv.setPadding(pad, pad, pad, pad);
                    box.addView(iv, 0, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    fallback.setVisibility(View.GONE);
                });
            } catch (Exception ignored) {
                // No logo available — the fallback letter stays visible.
            }
        }).start();
    }

    private TextView buildListRow(String text, boolean selected) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(15);
        row.setPadding(dp(10), dp(11), dp(10), dp(11));
        row.setMaxLines(1);
        if (selected) {
            row.setTextColor(0xFF1A0E00);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFFF8A3D);
            bg.setCornerRadius(dp(8));
            row.setBackground(bg);
            row.setTypeface(row.getTypeface(), android.graphics.Typeface.BOLD);
        } else {
            row.setTextColor(0xFFEAF2F5);
        }
        return row;
    }

    private void moveBrowseSelection(int delta) {
        if (browseState == BROWSE_CHANNELS) {
            int max = catTitlesPerItem.get(currentCatIndex).size();
            browseChannelIndex = ((browseChannelIndex + delta) % max + max) % max;
            populateChannelList();
        } else if (browseState == BROWSE_CATEGORIES) {
            int max = catTitles.size();
            browseCatIndex = ((browseCatIndex + delta) % max + max) % max;
            populateCategoryList();
        }
    }

    private void confirmBrowseSelection() {
        if (browseState == BROWSE_CHANNELS) {
            closeAllBrowse();
            goToChannel(browseChannelIndex);
        } else if (browseState == BROWSE_CATEGORIES) {
            currentCatIndex = browseCatIndex;
            loadActiveCategoryIntoFlatLists();
            browseChannelIndex = 0;
            closeAllBrowse();
            goToChannel(0);
        }
    }

    private void scrollToSelected(ScrollView scroll, LinearLayout col, int index) {
        if (index < 0 || index >= col.getChildCount()) return;
        View target = col.getChildAt(index);
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, target.getTop() - scroll.getHeight() / 2)));
    }

    // ---------- Channel change banner (native, attractive, auto-hides) ----------
    // ---------- OSD channel banner (logo, number+clock, program + progress, next) ----------
    private FrameLayout bannerLogoBox;
    private TextView bannerLogoFallback;
    private TextView bannerProgramTitle;
    private View bannerProgressFill;
    private View bannerProgressTrack;
    private TextView bannerProgramTimes;
    private TextView bannerNextLine;
    private TextView bannerClock;
    private long epgFetchToken = 0; // lets a slow/late EPG response be ignored if the channel changed again meanwhile

    // ---------- Touch interaction layer (phones only — TV keeps using the D-pad) ----------
    private void buildTouchControls() {
        FrameLayout root = findViewById(android.R.id.content);

        // Live TV doesn't get the movie-style play/pause/seek bar at all —
        // it's channel surfing (swipe/tap/double-tap), not something you
        // pause and scrub through. Just the fullscreen button.
        if (isLive) {
            touchFullscreenBtn = new TextView(this);
            touchFullscreenBtn.setText("⤢");
            touchFullscreenBtn.setTextColor(Color.WHITE);
            touchFullscreenBtn.setTextSize(20);
            touchFullscreenBtn.setGravity(Gravity.CENTER);
            GradientDrawable fsBg = new GradientDrawable();
            fsBg.setShape(GradientDrawable.OVAL);
            fsBg.setColor(0x66000000);
            touchFullscreenBtn.setBackground(fsBg);
            touchFullscreenBtn.setOnClickListener(v -> toggleTouchFullscreen());
            FrameLayout.LayoutParams fsLp = new FrameLayout.LayoutParams(dp(44), dp(44));
            fsLp.gravity = Gravity.TOP | Gravity.END;
            fsLp.topMargin = dp(24);
            fsLp.rightMargin = dp(24);
            root.addView(touchFullscreenBtn, fsLp);
            return;
        }

        touchControlsBar = new LinearLayout(this);
        touchControlsBar.setOrientation(LinearLayout.HORIZONTAL);
        touchControlsBar.setGravity(Gravity.CENTER);
        touchControlsBar.setVisibility(View.GONE);
        GradientDrawable barBg = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xC0000000, 0x00000000});
        touchControlsBar.setBackground(barBg);
        int barPad = dp(20);
        touchControlsBar.setPadding(barPad, dp(40), barPad, barPad);

        TextView seekBackBtn = touchIconButton("⏪ 10");
        seekBackBtn.setOnClickListener(v -> { seekBy(-10000); showTouchControls(); });
        touchControlsBar.addView(seekBackBtn, touchSideBtnParams());

        touchPlayPauseBtn = new ImageButton(this);
        touchPlayPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        GradientDrawable circle = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFFC98A, 0xFFFF8A3D});
        circle.setShape(GradientDrawable.OVAL);
        touchPlayPauseBtn.setBackground(circle);
        touchPlayPauseBtn.setOnClickListener(v -> { togglePlayPause(); showTouchControls(); });
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(dp(58), dp(58));
        mainLp.setMargins(dp(16), 0, dp(16), 0);
        touchControlsBar.addView(touchPlayPauseBtn, mainLp);

        TextView seekFwdBtn = touchIconButton("10 ⏩");
        seekFwdBtn.setOnClickListener(v -> { seekBy(10000); showTouchControls(); });
        touchControlsBar.addView(seekFwdBtn, touchSideBtnParams());

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        root.addView(touchControlsBar, barLp);
    }

    private TextView touchIconButton(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x33FFFFFF);
        btn.setBackground(bg);
        return btn;
    }

    private LinearLayout.LayoutParams touchSideBtnParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        return lp;
    }

    private boolean touchFullscreenOn = false;
    /** Rotates between portrait (the normal, default state for live TV on
     *  a phone) and landscape (true fullscreen) — this Activity handles
     *  the rotation itself (declared with configChanges in the manifest),
     *  so this never restarts playback either. */
    private void toggleTouchFullscreen() {
        touchFullscreenOn = !touchFullscreenOn;
        setRequestedOrientation(touchFullscreenOn
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (touchFullscreenBtn != null) touchFullscreenBtn.setText(touchFullscreenOn ? "⤡" : "⤢");
    }

    private void showTouchControls() {
        if (touchControlsBar == null) return;
        touchControlsVisible = true;
        touchControlsBar.setVisibility(View.VISIBLE);
        if (touchPlayPauseBtn != null && player != null) {
            touchPlayPauseBtn.setImageResource(player.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        }
        if (hideTouchControlsRunnable != null) handler.removeCallbacks(hideTouchControlsRunnable);
        hideTouchControlsRunnable = this::hideTouchControls;
        handler.postDelayed(hideTouchControlsRunnable, 3500);
    }

    private void hideTouchControls() {
        touchControlsVisible = false;
        if (touchControlsBar != null) touchControlsBar.setVisibility(View.GONE);
    }

    /** Swipe left/right changes channels on live TV — passing a finger
     *  over the screen, nothing more, exactly as asked. A single tap just
     *  toggles the touch controls (play/pause, seek, fullscreen) instead. */
    private void setupGestureDetector() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (isLive) {
                    if (browseState == BROWSE_NONE) openChannelBrowse();
                    // If a panel is already open, a tap on one of its own
                    // rows selects it (handled by that row's own click
                    // listener) — this outer handler deliberately does
                    // nothing else here, so the two don't fight over the
                    // same tap.
                } else {
                    if (touchControlsVisible) hideTouchControls(); else showTouchControls();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (isLive) openCategoryBrowse();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || !isLive || urls.size() <= 1 || browseState != BROWSE_NONE) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < dp(60) || Math.abs(dx) < Math.abs(dy)) return false;
                if (dx < 0) {
                    goToChannel(currentIndex + 1 >= urls.size() ? 0 : currentIndex + 1);
                } else {
                    goToChannel(currentIndex - 1 < 0 ? urls.size() - 1 : currentIndex - 1);
                }
                return true;
            }
        });
        View rootView = findViewById(android.R.id.content);
        rootView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    private void buildChannelBanner() {
        FrameLayout root = findViewById(android.R.id.content);

        bannerRoot = new FrameLayout(this);
        bannerRoot.setAlpha(0f);
        bannerRoot.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int padV = dp(16), padH = dp(18);
        card.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF20B1B26, 0xF2142838});
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(bg);

        // ---- Left: channel logo box ----
        bannerLogoBox = new FrameLayout(this);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setColor(0xFF0B1B26);
        logoBg.setCornerRadius(dp(10));
        logoBg.setStroke(dp(1), 0x40FFFFFF);
        bannerLogoBox.setBackground(logoBg);
        bannerLogoFallback = new TextView(this);
        bannerLogoFallback.setTextColor(Color.WHITE);
        bannerLogoFallback.setTextSize(20);
        bannerLogoFallback.setTypeface(bannerLogoFallback.getTypeface(), android.graphics.Typeface.BOLD);
        bannerLogoFallback.setGravity(Gravity.CENTER);
        bannerLogoBox.addView(bannerLogoFallback, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(bannerLogoBox, new LinearLayout.LayoutParams(dp(64), dp(64)));

        // ---- Center: channel/program name, progress bar, next programme ----
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMargins(dp(16), 0, dp(16), 0);

        bannerName = new TextView(this);
        bannerName.setTextColor(Color.WHITE);
        bannerName.setTextSize(17);
        bannerName.setTypeface(bannerName.getTypeface(), android.graphics.Typeface.BOLD);
        bannerName.setMaxLines(1);
        textCol.addView(bannerName);

        bannerProgramTitle = new TextView(this);
        bannerProgramTitle.setTextColor(0xFFEAF2F5);
        bannerProgramTitle.setTextSize(13);
        bannerProgramTitle.setMaxLines(1);
        LinearLayout.LayoutParams programLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        programLp.topMargin = dp(2);
        textCol.addView(bannerProgramTitle, programLp);

        // Progress bar: a thin track with an amber fill sized to the
        // programme's elapsed fraction.
        bannerProgressTrack = new View(this);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x33FFFFFF);
        trackBg.setCornerRadius(dp(3));
        bannerProgressTrack.setBackground(trackBg);
        FrameLayout progressWrap = new FrameLayout(this);
        progressWrap.addView(bannerProgressTrack, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));
        bannerProgressFill = new View(this);
        GradientDrawable fillBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFFFC98A, 0xFFFF8A3D});
        fillBg.setCornerRadius(dp(3));
        bannerProgressFill.setBackground(fillBg);
        FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(0, dp(5));
        fillLp.gravity = Gravity.START;
        progressWrap.addView(bannerProgressFill, fillLp);
        LinearLayout.LayoutParams progressWrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressWrapLp.topMargin = dp(8);
        textCol.addView(progressWrap, progressWrapLp);

        bannerProgramTimes = new TextView(this);
        bannerProgramTimes.setTextColor(0xFF9FB6C4);
        bannerProgramTimes.setTextSize(10.5f);
        LinearLayout.LayoutParams timesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timesLp.topMargin = dp(4);
        textCol.addView(bannerProgramTimes, timesLp);

        bannerNextLine = new TextView(this);
        bannerNextLine.setTextColor(0xFF7C93A1);
        bannerNextLine.setTextSize(11.5f);
        bannerNextLine.setMaxLines(1);
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nextLp.topMargin = dp(6);
        textCol.addView(bannerNextLine, nextLp);

        card.addView(textCol, textLp);

        // ---- Right: channel number (big) + current system clock ----
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.END);

        bannerNum = new TextView(this);
        bannerNum.setTextColor(0xFFFF8A3D);
        bannerNum.setTextSize(30);
        bannerNum.setTypeface(bannerNum.getTypeface(), android.graphics.Typeface.BOLD);
        bannerNum.setGravity(Gravity.END);
        rightCol.addView(bannerNum);

        bannerClock = new TextView(this);
        bannerClock.setTextColor(0xFF9FB6C4);
        bannerClock.setTextSize(13);
        bannerClock.setGravity(Gravity.END);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clockLp.topMargin = dp(2);
        rightCol.addView(bannerClock, clockLp);

        card.addView(rightCol);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.BOTTOM;
        cardLp.setMargins(dp(40), 0, dp(40), dp(40));
        bannerRoot.addView(card, cardLp);

        root.addView(bannerRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showChannelBanner() {
        String num = nums.get(currentIndex);
        String name = titles.get(currentIndex);
        bannerNum.setText(num == null || num.isEmpty() ? "•" : num);
        bannerName.setText(name);
        bannerLogoFallback.setText(name != null && !name.isEmpty() ? name.substring(0, 1).toUpperCase() : "?");
        bannerClock.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date()));

        // Reset programme info to a loading state while the EPG request is
        // in flight — it usually resolves well within the banner's
        // on-screen window.
        bannerProgramTitle.setText("Cargando programación…");
        bannerProgressFill.getLayoutParams().width = 0;
        bannerProgressFill.requestLayout();
        bannerProgramTimes.setText("");
        bannerNextLine.setText("");
        loadBannerLogo(currentIndex < imgUrls.size() ? imgUrls.get(currentIndex) : "");
        fetchEpgForBanner(currentIndex < epgUrls.size() ? epgUrls.get(currentIndex) : "");

        if (hideBannerRunnable != null) handler.removeCallbacks(hideBannerRunnable);

        bannerRoot.setVisibility(View.VISIBLE);
        bannerRoot.setTranslationY(dp(24));
        bannerRoot.animate().alpha(1f).translationY(0).setDuration(180).start();

        // On screen for ~4.5s (within the 3–5s window asked for), then a
        // smooth fade-out.
        hideBannerRunnable = () -> bannerRoot.animate().alpha(0f).setDuration(280)
                .withEndAction(() -> bannerRoot.setVisibility(View.GONE)).start();
        handler.postDelayed(hideBannerRunnable, 4500);
    }

    /** Loads the channel logo into the banner's logo box — checks the
     *  shared cache first (instant, since channels are pre-warmed as soon
     *  as the list is known), only falling back to a fresh network fetch
     *  if it genuinely isn't cached yet. Falls back to the channel's
     *  first letter if there's no logo URL or it fails to load. */
    private void loadBannerLogo(String url) {
        bannerLogoBox.removeViews(0, Math.max(0, bannerLogoBox.getChildCount() - 1));
        bannerLogoFallback.setVisibility(View.VISIBLE);
        if (url == null || url.isEmpty()) return;

        android.graphics.Bitmap cached = logoCache.get(url);
        if (cached != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(cached);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            int pad = dp(6);
            iv.setPadding(pad, pad, pad, pad);
            bannerLogoBox.addView(iv, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            bannerLogoFallback.setVisibility(View.GONE);
            return;
        }

        final long token = ++epgFetchToken; // reuse the same "is this still current" pattern
        new Thread(() -> {
            try {
                android.graphics.Bitmap bmp = downloadAndDecodeBitmap(url, 128); // bigger box here, so a bit more headroom
                if (bmp == null) return;
                logoCache.put(url, bmp);
                runOnUiThread(() -> {
                    if (token != epgFetchToken) return; // channel changed again before this arrived
                    android.widget.ImageView iv = new android.widget.ImageView(this);
                    iv.setImageBitmap(bmp);
                    iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    int pad = dp(6);
                    iv.setPadding(pad, pad, pad, pad);
                    bannerLogoBox.addView(iv, 0, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    bannerLogoFallback.setVisibility(View.GONE);
                });
            } catch (Exception ignored) {
                // No logo available — the fallback letter stays visible.
            }
        }).start();
    }

    /** Fetches the current + next programme for the channel showing in the
     *  banner right now, and fills in the title, progress bar and
     *  "Siguiente" line once it arrives. */
    private void fetchEpgForBanner(String epgUrl) {
        if (epgUrl == null || epgUrl.isEmpty()) {
            bannerProgramTitle.setText("Sin información de programación");
            return;
        }
        final long token = ++epgFetchToken;
        new Thread(() -> {
            try {
                URL u = new URL(epgUrl);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.connect();
                if (conn.getResponseCode() != 200) return;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                }
                JSONObject root = new JSONObject(bos.toString("UTF-8"));
                JSONArray listings = root.optJSONArray("epg_listings");
                if (listings == null || listings.length() == 0) {
                    runOnUiThread(() -> {
                        if (token == epgFetchToken) bannerProgramTitle.setText("Sin información de programación");
                    });
                    return;
                }

                JSONObject current = listings.getJSONObject(0);
                JSONObject next = listings.length() > 1 ? listings.getJSONObject(1) : null;

                String currentTitle = decodeEpgText(current.optString("title", ""));
                long startTs = current.optLong("start_timestamp", 0) * 1000L;
                long stopTs = current.optLong("stop_timestamp", 0) * 1000L;
                String nextTitle = next != null ? decodeEpgText(next.optString("title", "")) : "";
                long nextStartTs = next != null ? next.optLong("start_timestamp", 0) * 1000L : 0;
                long nextStopTs = next != null ? next.optLong("stop_timestamp", 0) * 1000L : 0;

                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
                String startStr = startTs > 0 ? fmt.format(new java.util.Date(startTs)) : "";
                String stopStr = stopTs > 0 ? fmt.format(new java.util.Date(stopTs)) : "";
                int progressPct = 0;
                if (startTs > 0 && stopTs > startTs) {
                    long now = System.currentTimeMillis();
                    progressPct = (int) Math.max(0, Math.min(100, (now - startTs) * 100 / (stopTs - startTs)));
                }
                final int finalProgressPct = progressPct;
                final String finalNextLine = (next != null && !nextTitle.isEmpty())
                        ? "Siguiente: " + nextTitle + " | " + fmt.format(new java.util.Date(nextStartTs)) + " hrs. / " + fmt.format(new java.util.Date(nextStopTs)) + " hrs."
                        : "";

                runOnUiThread(() -> {
                    if (token != epgFetchToken) return; // a newer channel change already superseded this
                    bannerProgramTitle.setText(currentTitle.isEmpty() ? titles.get(currentIndex) : currentTitle);
                    bannerProgramTimes.setText(startStr.isEmpty() ? "" : startStr + " hrs. – " + stopStr + " hrs.");
                    bannerNextLine.setText(finalNextLine);
                    int trackWidth = bannerProgressTrack.getWidth();
                    if (trackWidth > 0) {
                        bannerProgressFill.getLayoutParams().width = trackWidth * finalProgressPct / 100;
                    } else {
                        // Track hasn't been laid out yet — set it once it has.
                        bannerProgressTrack.post(() -> {
                            bannerProgressFill.getLayoutParams().width =
                                    bannerProgressTrack.getWidth() * finalProgressPct / 100;
                            bannerProgressFill.requestLayout();
                        });
                    }
                    bannerProgressFill.requestLayout();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (token == epgFetchToken) bannerProgramTitle.setText("Sin información de programación");
                });
            }
        }).start();
    }

    private String decodeEpgText(String base64){
        if (base64 == null || base64.isEmpty()) return "";
        try {
            return new String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- Progress bar (movies/series only — live TV has no fixed duration) ----------
    private void startProgressTicker() {
        stopProgressTicker();
        progressTickRunnable = () -> {
            if (player != null && !userSeeking) {
                long length = player.getDuration();
                long time = player.getCurrentPosition();
                if (length > 0 && length != C.TIME_UNSET) {
                    seekBar.setMax((int) length);
                    seekBar.setProgress((int) time);
                    timeTotalView.setText(formatTime((int) length));
                    timeElapsedView.setText(formatTime((int) time));
                    // Report back so the web app can save "seguir viendo" —
                    // Activities can't call the WebView directly, so JS
                    // reads this via VlcPlayer.getLastProgress() once it's
                    // visible again.
                    VlcPlayerPlugin.lastProgressUrl = urls.get(currentIndex);
                    VlcPlayerPlugin.lastProgressItemJson = continueItemJson != null ? continueItemJson : "";
                    VlcPlayerPlugin.lastProgressPositionMs = time;
                    VlcPlayerPlugin.lastProgressDurationMs = length;
                }
            }
            handler.postDelayed(progressTickRunnable, 500);
        };
        handler.post(progressTickRunnable);
    }

    private void stopProgressTicker() {
        if (progressTickRunnable != null) handler.removeCallbacks(progressTickRunnable);
    }

    private String formatTime(int ms) {
        int totalSeconds = ms / 1000;
        int h = totalSeconds / 3600;
        int m = (totalSeconds % 3600) / 60;
        int s = totalSeconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
    }

    // ---------- Remote lock: checked directly by this screen ----------
    private void scheduleStatusCheck() {
        if (deviceCode == null || deviceCode.isEmpty()) return;
        statusCheckRunnable = () -> {
            checkRemoteStatusOnce();
            handler.postDelayed(statusCheckRunnable, STATUS_CHECK_INTERVAL_MS);
        };
        handler.postDelayed(statusCheckRunnable, STATUS_CHECK_INTERVAL_MS);
    }

    private void cancelStatusCheck() {
        if (statusCheckRunnable != null) handler.removeCallbacks(statusCheckRunnable);
    }

    private void checkRemoteStatusOnce() {
        new Thread(() -> {
            try {
                URL u = new URL(FIREBASE_BASE + "/status/" + deviceCode + ".json");
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.connect();
                if (conn.getResponseCode() != 200) return;

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                }
                String text = bos.toString("UTF-8").trim();
                if (text.isEmpty() || "null".equals(text)) return;

                JSONObject obj = new JSONObject(text);
                boolean blocked = obj.optBoolean("blocked", false);
                boolean paused = obj.optBoolean("paused", false);
                if (blocked || paused) {
                    runOnUiThread(() -> showLockout(blocked));
                    return;
                }

                JSONObject message = obj.optJSONObject("message");
                if (message != null) {
                    String msgText = message.optString("text", "");
                    String msgName = message.optString("name", "");
                    long msgTs = message.optLong("ts", 0);
                    android.content.SharedPreferences prefs = getSharedPreferences("netgo_prefs", MODE_PRIVATE);
                    long lastShownTs = prefs.getLong("last_message_ts", 0);
                    if (!msgText.isEmpty() && msgTs > lastShownTs) {
                        prefs.edit().putLong("last_message_ts", msgTs).apply();
                        runOnUiThread(() -> showAdminMessage(msgText, msgName));
                    }
                }
            } catch (Exception ignored) {
                // Offline or Firebase unreachable — don't lock the player
                // out just because of a network hiccup.
            }
        }).start();
    }

    private FrameLayout adminMessageView;
    private TextView adminMessageTextView;
    private TextView adminMessageTitleView;

    /** Shows a message from the admin right over the video — playback
     *  keeps going, unlike the lockout screen; the person can dismiss it
     *  and keep watching without ever leaving the fullscreen player. */
    private void showAdminMessage(String text, String name) {
        if (adminMessageView == null) buildAdminMessageView();
        adminMessageTitleView.setText(name != null && !name.isEmpty() ? "¡Hola " + name + "!" : "Mensaje del administrador");
        adminMessageTextView.setText(text);
        adminMessageView.setAlpha(0f);
        adminMessageView.setVisibility(View.VISIBLE);
        adminMessageView.animate().alpha(1f).setDuration(200).start();
    }

    private void buildAdminMessageView() {
        FrameLayout root = findViewById(android.R.id.content);
        adminMessageView = new FrameLayout(this);
        adminMessageView.setBackgroundColor(0xDD050C11);
        adminMessageView.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        int pad = dp(28);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF142838);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(bg);

        TextView icon = new TextView(this);
        icon.setText("💬");
        icon.setTextSize(32);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iconLp.bottomMargin = dp(10);
        card.addView(icon, iconLp);

        TextView title = new TextView(this);
        adminMessageTitleView = title;
        title.setText("Mensaje del administrador");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(10);
        card.addView(title, titleLp);

        adminMessageTextView = new TextView(this);
        adminMessageTextView.setTextColor(0xFF9FB6C4);
        adminMessageTextView.setTextSize(14);
        adminMessageTextView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                dp(320), ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.bottomMargin = dp(18);
        card.addView(adminMessageTextView, textLp);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("Cerrar");
        closeBtn.setTextColor(0xFF1A0E00);
        closeBtn.setTypeface(closeBtn.getTypeface(), android.graphics.Typeface.BOLD);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(28), dp(12), dp(28), dp(12));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFFFF8A3D);
        btnBg.setCornerRadius(dp(10));
        closeBtn.setBackground(btnBg);
        closeBtn.setOnClickListener(v -> adminMessageView.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> adminMessageView.setVisibility(View.GONE)).start());
        card.addView(closeBtn);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        adminMessageView.addView(card, cardLp);

        root.addView(adminMessageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void buildLockoutView() {
        FrameLayout root = findViewById(android.R.id.content);
        lockoutView = new FrameLayout(this);
        lockoutView.setBackgroundColor(0xFF0B1B26);
        lockoutView.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        TextView icon = new TextView(this);
        icon.setText("🔒");
        icon.setTextSize(40);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iconLp.bottomMargin = dp(16);
        col.addView(icon, iconLp);

        lockoutTitleView = new TextView(this);
        lockoutTitleView.setTextColor(Color.WHITE);
        lockoutTitleView.setTextSize(22);
        lockoutTitleView.setTypeface(lockoutTitleView.getTypeface(), android.graphics.Typeface.BOLD);
        lockoutTitleView.setGravity(Gravity.CENTER);
        col.addView(lockoutTitleView);

        lockoutMsgView = new TextView(this);
        lockoutMsgView.setTextColor(0xFF9FB6C4);
        lockoutMsgView.setTextSize(14);
        lockoutMsgView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgLp.topMargin = dp(8);
        col.addView(lockoutMsgView, msgLp);

        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.gravity = Gravity.CENTER;
        lockoutView.addView(col, colLp);

        root.addView(lockoutView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showLockout(boolean blocked) {
        cancelStatusCheck();
        if (hideBannerRunnable != null) handler.removeCallbacks(hideBannerRunnable);
        if (player != null) player.stop();

        lockoutTitleView.setText(blocked ? "Cuenta bloqueada" : "Cuenta pausada");
        lockoutMsgView.setText(blocked
                ? "El administrador bloqueó tu acceso. Contáctalo para más información."
                : "El administrador pausó tu acceso. Contáctalo para reactivarlo.");
        bannerRoot.setVisibility(View.GONE);
        channelPanel.setVisibility(View.GONE);
        categoryPanel.setVisibility(View.GONE);
        spinner.setVisibility(View.GONE);
        lockoutView.setVisibility(View.VISIBLE);
    }

    private int dp(int value) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return (int) (value * dm.density);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Live channels must keep playing through a brief loss of
        // foreground (a system notification, an overlay flashing, etc.) —
        // there was no onStart/onResume to bring playback back afterwards,
        // so stopping here meant a channel could halt permanently over a
        // completely transient interruption. Movies/series still stop,
        // since pausing those when backgrounded is expected behavior.
        if (!isLive && player != null) {
            player.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hideBannerRunnable != null) handler.removeCallbacks(hideBannerRunnable);
        if (hideCcButtonRunnable != null) handler.removeCallbacks(hideCcButtonRunnable);
        if (hideTouchControlsRunnable != null) handler.removeCallbacks(hideTouchControlsRunnable);
        cancelStallTimer();
        cancelLiveRetry();
        cancelStatusCheck();
        stopProgressTicker();
        if (videoLayout != null) videoLayout.setPlayer(null);
        if (player != null) player.release();
    }

    @Override
    public void onBackPressed() {
        if (isLive && browseState != BROWSE_NONE) {
            closeAllBrowse();
            return;
        }
        finish();
    }
}
