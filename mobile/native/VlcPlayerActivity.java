package com.netgo.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Fullscreen native video player using libVLC.
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
public class VlcPlayerActivity extends Activity {

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private TextView titleView;
    private ProgressBar spinner;

    // ---- Flat-queue mode (movies / series / single channel list) ----
    private final List<String> urls = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final List<String> nums = new ArrayList<>();
    private int currentIndex = 0;

    // ---- Live TV mode (categories + browsing) ----
    private boolean isLive = false;
    private final List<String> catTitles = new ArrayList<>();
    private final List<List<String>> catUrls = new ArrayList<>();
    private final List<List<String>> catTitlesPerItem = new ArrayList<>();
    private final List<List<String>> catNums = new ArrayList<>();
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

    // ---- Channel change banner ----
    private FrameLayout bannerRoot;
    private TextView bannerNum;
    private TextView bannerName;
    private TextView bannerCount;
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

    // ---- "Canal en mantenimiento" (stuck-loading) screen ----
    private FrameLayout maintenanceView;
    private Runnable maintenanceRunnable;
    private static final long MAINTENANCE_TIMEOUT_MS = 10000;

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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_vlc_player);

        VLCVideoLayout videoLayout = findViewById(R.id.video_layout);
        titleView = findViewById(R.id.player_title);
        ImageButton closeBtn = findViewById(R.id.player_close);
        spinner = findViewById(R.id.player_spinner);
        progressBarContainer = findViewById(R.id.player_progress_bar);
        seekBar = findViewById(R.id.player_seekbar);
        seekBar.setFocusable(false);
        seekBar.setFocusableInTouchMode(false);
        timeElapsedView = findViewById(R.id.player_time_elapsed);
        timeTotalView = findViewById(R.id.player_time_total);
        ccToggle = findViewById(R.id.player_cc_toggle);
        ccToggle.setOnClickListener(v -> toggleSubtitles());
        TextView expandToggle = findViewById(R.id.player_expand_toggle);
        expandToggle.setOnClickListener(v -> cycleZoom());
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) timeElapsedView.setText(formatTime(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (mediaPlayer != null) mediaPlayer.setTime(sb.getProgress());
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
        buildMaintenanceView();
        buildBrowsePanels();
        buildLockoutView();

        if (!parseIntentData()) {
            finish();
            return;
        }
        deviceCode = getIntent().getStringExtra("deviceCode");

        ArrayList<String> options = new ArrayList<>();
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--rtsp-tcp");
        options.add("--network-caching=1200");
        options.add("--live-caching=1200");
        options.add("--file-caching=600");
        options.add("--http-reconnect");

        libVLC = new LibVLC(this, options);
        mediaPlayer = new MediaPlayer(libVLC);
        mediaPlayer.attachViews(videoLayout, null, false, true);

        mediaPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.Playing) {
                runOnUiThread(() -> {
                    spinner.setVisibility(View.GONE);
                    cancelMaintenanceTimer();
                    hideMaintenanceView();
                    if (!isLive) startProgressTicker();
                });
            } else if (event.type == MediaPlayer.Event.EncounteredError) {
                runOnUiThread(() -> {
                    spinner.setVisibility(View.GONE);
                    cancelMaintenanceTimer();
                    if (isLive) {
                        showMaintenanceThenAdvance();
                    } else {
                        showMaintenanceNow();
                    }
                });
            } else if (event.type == MediaPlayer.Event.EndReached) {
                runOnUiThread(this::advanceOrFinish);
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
                    List<String> u = new ArrayList<>(), t = new ArrayList<>(), n = new ArrayList<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.getJSONObject(i);
                        u.add(it.getString("url"));
                        t.add(it.optString("title", "Canal"));
                        n.add(it.optString("num", ""));
                    }
                    catUrls.add(u);
                    catTitlesPerItem.add(t);
                    catNums.add(n);
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
                }
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
        urls.clear(); titles.clear(); nums.clear();
        urls.addAll(catUrls.get(currentCatIndex));
        titles.addAll(catTitlesPerItem.get(currentCatIndex));
        nums.addAll(catNums.get(currentCatIndex));
    }

    // ---------- Remote control ----------
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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

        // Movies/series: left/right seeks 10s, OK toggles play/pause.
        if (!isLive) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { seekBy(-10000); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { seekBy(10000); return true; }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            togglePlayPause();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CAPTIONS) {
            toggleSubtitles();
            return true;
        }

        if (urls.size() > 1) {
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
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        } else {
            mediaPlayer.play();
        }
    }

    // Manual zoom, since automatic "fill the screen" detection wasn't
    // reliable on every TV — this gives direct control instead: tap to
    // cycle through Original → Zoom 1 → Zoom 2, whichever looks right.
    private final float[] zoomLevels = {0f, 1.33f, 1.78f};
    private int zoomIndex = 0;
    private void cycleZoom() {
        if (mediaPlayer == null) return;
        zoomIndex = (zoomIndex + 1) % zoomLevels.length;
        mediaPlayer.setScale(zoomLevels[zoomIndex]);
        String label = zoomIndex == 0 ? "Tamaño original" : "Estirado " + (int) (zoomLevels[zoomIndex] * 100) + "%";
        android.widget.Toast.makeText(this, label, android.widget.Toast.LENGTH_SHORT).show();
    }

    private boolean subtitlesEnabled = true;
    /** Turns subtitles on/off — bound to the remote's CC/Subtitles key. */
    private void toggleSubtitles() {
        if (mediaPlayer == null) return;
        if (subtitlesEnabled) {
            mediaPlayer.setSpuTrack(-1);
            subtitlesEnabled = false;
            android.widget.Toast.makeText(this, "Subtítulos desactivados", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            try {
                org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks = mediaPlayer.getSpuTracks();
                if (tracks != null && tracks.length > 0) {
                    int trackId = tracks.length > 1 ? tracks[1].id : tracks[0].id;
                    mediaPlayer.setSpuTrack(trackId);
                    android.widget.Toast.makeText(this, "Subtítulos activados", android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    android.widget.Toast.makeText(this, "Esta señal no tiene subtítulos disponibles", android.widget.Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ignored) { }
            subtitlesEnabled = true;
        }
    }

    /** Seeks by deltaMs (negative = backward) and briefly shows the progress bar. */
    private void seekBy(int deltaMs) {
        if (mediaPlayer == null) return;
        long length = mediaPlayer.getLength();
        long newTime = mediaPlayer.getTime() + deltaMs;
        if (newTime < 0) newTime = 0;
        if (length > 0 && newTime > length) newTime = length;
        mediaPlayer.setTime(newTime);
        if (seekBar != null) {
            if (length > 0) seekBar.setMax((int) length);
            seekBar.setProgress((int) newTime);
        }
        if (timeElapsedView != null) timeElapsedView.setText(formatTime((int) newTime));
        if (timeTotalView != null && length > 0) timeTotalView.setText(formatTime((int) length));
        showProgressBarTemporarily();
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

    private void goToChannel(int index) {
        currentIndex = index;
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

    private void loadCurrent() {
        spinner.setVisibility(View.VISIBLE);
        titleView.setText(titles.get(currentIndex));
        hideMaintenanceView();
        scheduleMaintenanceTimer();
        if (autoAdvanceRunnable != null) { handler.removeCallbacks(autoAdvanceRunnable); autoAdvanceRunnable = null; }
        stopProgressTicker();
        if (progressBarContainer != null) {
            progressBarContainer.setVisibility(View.GONE);
            seekBar.setProgress(0);
        }
        Media media = new Media(libVLC, Uri.parse(urls.get(currentIndex)));
        media.setHWDecoderEnabled(true, false);
        mediaPlayer.setMedia(media);
        media.release();
        zoomIndex = 0;
        mediaPlayer.setScale(0);
        mediaPlayer.play();
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

    private void populateChannelList() {
        channelListCol.removeAllViews();
        List<String> names = catTitlesPerItem.get(currentCatIndex);
        for (int i = 0; i < names.size(); i++) {
            channelListCol.addView(buildListRow(names.get(i), i == browseChannelIndex));
        }
        scrollToSelected(channelScroll, channelListCol, browseChannelIndex);
    }

    private void populateCategoryList() {
        categoryListCol.removeAllViews();
        for (int i = 0; i < catTitles.size(); i++) {
            categoryListCol.addView(buildListRow(catTitles.get(i), i == browseCatIndex));
        }
        scrollToSelected(categoryScroll, categoryListCol, browseCatIndex);
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
    private void buildChannelBanner() {
        FrameLayout root = findViewById(android.R.id.content);

        bannerRoot = new FrameLayout(this);
        bannerRoot.setAlpha(0f);
        bannerRoot.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int padV = dp(14), padH = dp(18);
        card.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF0142838, 0xE0142838});
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(bg);

        FrameLayout badge = new FrameLayout(this);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(0xFFFF8A3D);
        badge.setBackground(badgeBg);
        bannerNum = new TextView(this);
        bannerNum.setTextColor(0xFF1A0E00);
        bannerNum.setTextSize(20);
        bannerNum.setTypeface(bannerNum.getTypeface(), android.graphics.Typeface.BOLD);
        bannerNum.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams numLp = new FrameLayout.LayoutParams(dp(52), dp(52));
        badge.addView(bannerNum, numLp);
        card.addView(badge, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.setMargins(dp(16), 0, dp(16), 0);

        TextView liveLabel = new TextView(this);
        liveLabel.setText("● EN VIVO");
        liveLabel.setTextColor(0xFFFF6B6B);
        liveLabel.setTextSize(11);

        bannerName = new TextView(this);
        bannerName.setTextColor(Color.WHITE);
        bannerName.setTextSize(19);
        bannerName.setTypeface(bannerName.getTypeface(), android.graphics.Typeface.BOLD);
        bannerName.setMaxLines(1);

        textCol.addView(liveLabel);
        textCol.addView(bannerName);
        card.addView(textCol, textLp);

        bannerCount = new TextView(this);
        bannerCount.setTextColor(0xFF9FB6C4);
        bannerCount.setTextSize(13);
        card.addView(bannerCount);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.BOTTOM | Gravity.START;
        cardLp.setMargins(dp(40), 0, 0, dp(48));
        bannerRoot.addView(card, cardLp);

        root.addView(bannerRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showChannelBanner() {
        String num = nums.get(currentIndex);
        bannerNum.setText(num == null || num.isEmpty() ? "•" : num);
        bannerName.setText(titles.get(currentIndex));
        if (urls.size() > 1) {
            bannerCount.setText((currentIndex + 1) + " / " + urls.size());
            bannerCount.setVisibility(View.VISIBLE);
        } else {
            bannerCount.setVisibility(View.GONE);
        }

        if (hideBannerRunnable != null) handler.removeCallbacks(hideBannerRunnable);

        bannerRoot.setVisibility(View.VISIBLE);
        bannerRoot.setTranslationY(dp(24));
        bannerRoot.animate().alpha(1f).translationY(0).setDuration(180).start();

        hideBannerRunnable = () -> bannerRoot.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> bannerRoot.setVisibility(View.GONE)).start();
        handler.postDelayed(hideBannerRunnable, 3800);
    }

    // ---------- Progress bar (movies/series only — live TV has no fixed duration) ----------
    private void startProgressTicker() {
        stopProgressTicker();
        progressTickRunnable = () -> {
            if (mediaPlayer != null && !userSeeking) {
                long length = mediaPlayer.getLength();
                long time = mediaPlayer.getTime();
                if (length > 0) {
                    seekBar.setMax((int) length);
                    seekBar.setProgress((int) time);
                    timeTotalView.setText(formatTime((int) length));
                    timeElapsedView.setText(formatTime((int) time));
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

    // ---------- "Canal en mantenimiento" screen ----------
    private void buildMaintenanceView() {
        FrameLayout root = findViewById(android.R.id.content);
        maintenanceView = new FrameLayout(this);
        maintenanceView.setBackgroundColor(0xFF0B1B26);
        maintenanceView.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        ImageView logo = new ImageView(this);
        int logoSize = dp(84);
        try {
            logo.setImageResource(getResources().getIdentifier("ic_launcher_foreground", "mipmap", getPackageName()));
        } catch (Exception ignored) { }
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoLp.bottomMargin = dp(20);
        col.addView(logo, logoLp);

        TextView msg = new TextView(this);
        msg.setText("Canal en mantenimiento");
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(20);
        msg.setTypeface(msg.getTypeface(), android.graphics.Typeface.BOLD);
        msg.setGravity(Gravity.CENTER);
        col.addView(msg);

        TextView sub = new TextView(this);
        sub.setText("No pudimos cargar esta señal. Prueba otro canal.");
        sub.setTextColor(0xFF9FB6C4);
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(8);
        col.addView(sub, subLp);

        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.gravity = Gravity.CENTER;
        maintenanceView.addView(col, colLp);

        root.addView(maintenanceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void scheduleMaintenanceTimer() {
        cancelMaintenanceTimer();
        maintenanceRunnable = () -> {
            spinner.setVisibility(View.GONE);
            maintenanceView.setAlpha(0f);
            maintenanceView.setVisibility(View.VISIBLE);
            maintenanceView.animate().alpha(1f).setDuration(200).start();
        };
        handler.postDelayed(maintenanceRunnable, MAINTENANCE_TIMEOUT_MS);
    }

    private void cancelMaintenanceTimer() {
        if (maintenanceRunnable != null) handler.removeCallbacks(maintenanceRunnable);
    }

    private void hideMaintenanceView() {
        if (maintenanceView != null) maintenanceView.setVisibility(View.GONE);
    }

    /** Shows "Canal en mantenimiento" right away (used on a real playback error). */
    private void showMaintenanceNow() {
        spinner.setVisibility(View.GONE);
        maintenanceView.setAlpha(0f);
        maintenanceView.setVisibility(View.VISIBLE);
        maintenanceView.animate().alpha(1f).setDuration(200).start();
    }

    /** For live TV: shows the maintenance message, then automatically moves
     *  to the next channel after a few seconds instead of getting stuck —
     *  the person never has to back out to the home screen. */
    private Runnable autoAdvanceRunnable;
    private void showMaintenanceThenAdvance() {
        showMaintenanceNow();
        if (autoAdvanceRunnable != null) handler.removeCallbacks(autoAdvanceRunnable);
        if (urls.size() <= 1) return; // nothing to advance to
        autoAdvanceRunnable = () -> goToChannel(currentIndex + 1 >= urls.size() ? 0 : currentIndex + 1);
        handler.postDelayed(autoAdvanceRunnable, 3000);
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
                }
            } catch (Exception ignored) {
                // Offline or Firebase unreachable — don't lock the player
                // out just because of a network hiccup.
            }
        }).start();
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
        cancelMaintenanceTimer();
        if (hideBannerRunnable != null) handler.removeCallbacks(hideBannerRunnable);
        if (mediaPlayer != null) mediaPlayer.stop();

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
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.detachViews();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hideBannerRunnable != null) handler.removeCallbacks(hideBannerRunnable);
        if (autoAdvanceRunnable != null) handler.removeCallbacks(autoAdvanceRunnable);
        cancelMaintenanceTimer();
        cancelStatusCheck();
        stopProgressTicker();
        if (mediaPlayer != null) mediaPlayer.release();
        if (libVLC != null) libVLC.release();
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
