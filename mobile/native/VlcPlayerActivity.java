package com.netgo.mobile;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
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
 *  - Movies / live channels: queue of length 1 — when it ends, the activity
 *    closes and the app is left showing whatever screen was underneath
 *    (e.g. the Películas grid), which is effectively "return automatically".
 *  - Series: queue of every episode in order — when one episode ends, the
 *    next one loads and plays automatically; when the last one ends, the
 *    activity closes the same way.
 */
public class VlcPlayerActivity extends Activity {

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private TextView titleView;
    private ProgressBar spinner;

    private final List<String> urls = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private int currentIndex = 0;

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
                // Fires when the current item finishes playing.
                runOnUiThread(this::advanceOrFinish);
            }
        });

        loadCurrent();
    }

    private boolean parseQueueFromIntent() {
        try {
            String queueJson = getIntent().getStringExtra("queueJson");
            JSONArray arr = new JSONArray(queueJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                urls.add(obj.getString("url"));
                titles.add(obj.optString("title", "Reproduciendo"));
            }
            currentIndex = getIntent().getIntExtra("startIndex", 0);
            if (currentIndex < 0 || currentIndex >= urls.size()) currentIndex = 0;
            return !urls.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Called when one item in the queue finishes: play the next one, or close. */
    private void advanceOrFinish() {
        if (currentIndex + 1 < urls.size()) {
            currentIndex++;
            loadCurrent();
        } else {
            // Last (or only) item — movie ended, live channel stopped, or the
            // final episode of a series just finished. Close and return to
            // the app, which is already on the right screen underneath.
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
        if (mediaPlayer != null) mediaPlayer.release();
        if (libVLC != null) libVLC.release();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
