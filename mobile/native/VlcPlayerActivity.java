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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Fullscreen native video player using libVLC (bundles its own decoders —
 * H.264, H.265/HEVC, MPEG-2, AC-3, E-AC-3, DTS, AAC, MKV, TS — regardless of
 * what the Android WebView/Chromium supports).
 *
 * Plays a queue of one or more items:
 *  - Movies: queue of length 1 — when it ends, the activity closes.
 *  - Live channels: queue of every channel in that category — the remote's
 *    Channel Up/Down (or D-pad Up/Down as a fallback) moves between them,
 *    showing an on-screen "now playing" banner each time.
 *  - Series: queue of every episode in order, auto-advancing.
 */
public class VlcPlayerActivity extends Activity {

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private TextView titleView;
    private ProgressBar spinner;

    private final List<String> urls = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final List<String> nums = new ArrayList<>();
    private int currentIndex = 0;

    // ---- Channel change banner ----
    private FrameLayout bannerRoot;
    private TextView bannerNum;
    private TextView bannerName;
    private TextView bannerCount;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideBannerRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_vlc_player);

        VLCVideoLayout videoLayout = findViewById(R.id.video_layout);
        titleView = findViewById(R.id.player_title);
        ImageButton closeBtn = findViewById(R.id.player_close);
        spinner = findViewById(R.id.player_spinner);
        closeBtn.setOnClickListener(v -> finish());

        buildChannelBanner();

        if (!parseQueueFromIntent()) {
            finish();
            return;
        }

        ArrayList<String> options = new ArrayList<>();
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--rtsp-tcp");
        options.add("--network-caching=2500");
        options.add("--live-caching=2500");
        options.add("--file-caching=1000");
        options.add("--http-reconnect");

        libVLC = new LibVLC(this, options);
        mediaPlayer = new MediaPlayer(libVLC);
        mediaPlayer.attachViews(videoLayout, null, false, false);

        mediaPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.Playing) {
                runOnUiThread(() -> spinner.setVisibility(View.GONE));
            } else if (event.type == MediaPlayer.Event.EncounteredError) {
                runOnUiThread(() -> {
                    spinner.setVisibility(View.GONE);
                    titleView.setText("No se pudo reproducir esta señal");
                });
            } else if (event.type == MediaPlayer.Event.EndReached) {
                runOnUiThread(this::advanceOrFinish);
            }
        });

        loadCurrent();
        showChannelBanner();
    }

    private boolean parseQueueFromIntent() {
        try {
            String queueJson = getIntent().getStringExtra("queueJson");
            JSONArray arr = new JSONArray(queueJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                urls.add(obj.getString("url"));
                titles.add(obj.optString("title", "Reproduciendo"));
                nums.add(obj.optString("num", ""));
            }
            currentIndex = getIntent().getIntExtra("startIndex", 0);
            if (currentIndex < 0 || currentIndex >= urls.size()) currentIndex = 0;
            return !urls.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Remote control: Channel Up/Down (and D-pad Up/Down as a
    // fallback, since many Android TV remotes don't have dedicated channel
    // keys) surf through the current queue of channels. ----------
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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

    private void goToChannel(int index) {
        currentIndex = index;
        loadCurrent();
        showChannelBanner();
    }

    /** Called when one item in the queue finishes: play the next one, or close. */
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
        Media media = new Media(libVLC, Uri.parse(urls.get(currentIndex)));
        media.setHWDecoderEnabled(true, false);
        mediaPlayer.setMedia(media);
        media.release();
        mediaPlayer.play();
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

        // Amber circular channel-number badge
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
        if (mediaPlayer != null) mediaPlayer.release();
        if (libVLC != null) libVLC.release();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
