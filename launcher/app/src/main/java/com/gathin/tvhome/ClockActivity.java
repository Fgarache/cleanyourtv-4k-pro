package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The screensaver shown by Home itself after home_idle_clock_minutes without input (or Dormir / Back on Home).
 */
public class ClockActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    private ClockRefresh clockRefresh;
    private SeamlessVideoController controller;
    private AudioSpectrumVisualizerView visualizerView;
    private View mediaRow;
    private SpinningVinylView mediaDisc;
    private TextView mediaTitle;
    private TextView mediaArtist;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable mediaPoll = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            updateScreensaverMedia();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Prevent Android TV's system DreamService timer from launching a second screensaver on top after 5 minutes
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);

        String fmt = Config.load(this).clockFormat;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        controller = new SeamlessVideoController(this, root, WallpaperPickerActivity.MODE_SCREENSAVER);
        boolean playingNow = MediaBridgeService.isPlaying(this);
        controller.setMusicOverride(playingNow);
        if (!playingNow) {
            controller.configureFromPrefs();
        }

        // Audio spectrum visualizer anchored at BottomCenter: 100% width, 40% of total screen height
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int vizHeight = Math.round(screenH * 0.40f);
        visualizerView = new AudioSpectrumVisualizerView(this);
        visualizerView.setHeightBoost(1.30f);
        FrameLayout.LayoutParams vizLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                vizHeight,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(visualizerView, vizLp);

        attachClockOverlay(root, fmt);
        setContentView(root);
    }

    private void attachClockOverlay(FrameLayout root, String fmt) {
        View overlay = getLayoutInflater().inflate(R.layout.overlay_home_clock, root, false);
        root.addView(overlay);
        ClockFormat.apply(overlay, fmt);
        mediaRow = overlay.findViewById(R.id.screensaverMediaRow);
        mediaDisc = overlay.findViewById(R.id.screensaverMediaDisc);
        mediaTitle = overlay.findViewById(R.id.screensaverMediaTitle);
        mediaArtist = overlay.findViewById(R.id.screensaverMediaArtist);
        clockRefresh = new ClockRefresh(overlay);
        clockRefresh.start();
    }

    private void updateScreensaverMedia() {
        boolean playing = MediaBridgeService.isPlaying(this);
        String title = MediaBridgeService.getTitle(this);
        String artist = MediaBridgeService.getArtist(this);
        String pkg = MediaBridgeService.getActivePackage(this);

        if (controller != null) {
            controller.setMusicOverride(playing);
        }
        if (visualizerView != null) {
            visualizerView.setActive(playing);
        }

        if (mediaRow != null && mediaDisc != null && mediaTitle != null) {
            if (playing) {
                mediaTitle.setText(title != null && !title.isEmpty() ? title : "Reproduciendo música");
                if (mediaArtist != null) {
                    if (artist != null && !artist.isEmpty()) {
                        mediaArtist.setText(artist);
                        mediaArtist.setVisibility(View.VISIBLE);
                    } else {
                        mediaArtist.setText("");
                        mediaArtist.setVisibility(View.GONE);
                    }
                }
                Bitmap art = MediaBridgeService.getArtwork(this, pkg);
                Drawable fallbackIcon = null;
                if (art == null && pkg != null && !pkg.isEmpty()) {
                    try {
                        fallbackIcon = getPackageManager().getApplicationIcon(pkg);
                    } catch (Throwable ignored) {
                    }
                }
                mediaDisc.setArtworkOrIcon(art, fallbackIcon);
                mediaDisc.setSpinning(true);
                mediaRow.setVisibility(View.VISIBLE);
            } else {
                mediaDisc.setSpinning(false);
                mediaRow.setVisibility(View.GONE);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateScreensaverMedia();
        if (controller != null) {
            controller.start();
        }
        if (clockRefresh != null) {
            clockRefresh.start();
        }
        handler.removeCallbacks(mediaPoll);
        handler.postDelayed(mediaPoll, 1500);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(mediaPoll);
        if (mediaDisc != null) {
            mediaDisc.setSpinning(false);
        }
        if (visualizerView != null) {
            visualizerView.releaseVisualizer();
        }
        if (controller != null) {
            controller.stop();
        }
        if (clockRefresh != null) {
            clockRefresh.stop();
        }
        super.onPause();
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(mediaPoll);
        if (mediaDisc != null) {
            mediaDisc.setSpinning(false);
        }
        if (visualizerView != null) {
            visualizerView.releaseVisualizer();
        }
        if (controller != null) {
            controller.close();
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        finish();
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
