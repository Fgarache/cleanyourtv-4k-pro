package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * System DreamService screensaver: unified with ClockActivity so whether triggered by the Dormir button,
 * idle timer on Home, or Android TV's system DreamManager, it shows the exact same video, spinning vinyl,
 * and RGB spectrum visualizer.
 */
public class ClockDream extends DreamService {

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
            updateScreensaverMedia();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);

        String fmt = Config.load(this).clockFormat;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        controller = new SeamlessVideoController(this, root, WallpaperPickerActivity.MODE_SCREENSAVER);
        boolean playingNow = MediaBridgeService.isPlaying(this);
        controller.setMusicOverride(playingNow);
        if (!playingNow) {
            controller.configureFromPrefs();
        }

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
        View overlay = LayoutInflater.from(this).inflate(R.layout.overlay_home_clock, root, false);
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
    public void onDreamingStarted() {
        super.onDreamingStarted();
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
    public void onDreamingStopped() {
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
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
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
        if (clockRefresh != null) {
            clockRefresh.stop();
        }
        super.onDetachedFromWindow();
    }
}
