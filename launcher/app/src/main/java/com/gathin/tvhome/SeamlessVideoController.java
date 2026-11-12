package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Single-decoder hardware-safe TextureView video controller with snapshot fusion crossfade:
 * - Uses strictly ONE MediaPlayer instance at a time so MediaTek hardware decoders never lock up.
 * - Captures the live TextureView frame into a top ImageView before switching videos and fades it out
 *   over 1400ms once the new video starts rendering, fusing both videos without any black flash or freeze.
 * - Supports "loop" (plays forward half [0..D/2] with seek cooldown) and "bounce" (full [0..D] ping-pong).
 */
public final class SeamlessVideoController implements TextureView.SurfaceTextureListener {

    public static final String MODE_SINGLE = "single";
    public static final String MODE_RANDOM = "random";
    public static final String MODE_NONE = "none";

    public static final String STYLE_LOOP = "loop";
    public static final String STYLE_BOUNCE = "bounce";

    private static final long CROSSFADE_MS = 1400L;
    private static final long LOOP_CHECK_MS = 180L;

    private final Context context;
    private final FrameLayout root;
    private final String targetMode;
    private final FrameLayout stage;
    private final TextureView textureView;
    private final ImageView crossfadeView;

    private Surface surface;
    private MediaPlayer player;
    private boolean prepared;
    private long lastSeekMs;
    private Bitmap snapshotBitmap;

    private String playbackMode = MODE_SINGLE;
    private String playStyle = STYLE_BOUNCE;
    private String category = "ALL";
    private long intervalMs = 5 * 60_000L;
    private final List<File> playlist = new ArrayList<>();
    private File currentFile;
    private final Random random = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean started;
    private boolean closed;
    private boolean fadingSnapshot;
    private boolean musicOverride;
    private int musicTransitionStep = 0;

    private final Runnable loopCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (closed || !started) return;
            if (STYLE_LOOP.equals(playStyle) && player != null && prepared) {
                try {
                    if (player.isPlaying()) {
                        int dur = player.getDuration();
                        int pos = player.getCurrentPosition();
                        long now = SystemClock.elapsedRealtime();
                        if (dur > 1500 && pos >= (dur / 2) - 120 && (now - lastSeekMs) > 1500L) {
                            lastSeekMs = now;
                            player.seekTo(0);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            handler.postDelayed(this, LOOP_CHECK_MS);
        }
    };

    private final Runnable rotateRunnable = new Runnable() {
        @Override
        public void run() {
            if (closed || !started || !MODE_RANDOM.equals(playbackMode)) return;
            crossfadeToNextRandom();
            handler.postDelayed(this, intervalMs);
        }
    };

    public SeamlessVideoController(Context context, FrameLayout root, String targetMode) {
        this.context = context;
        this.root = root;
        this.targetMode = targetMode;

        stage = new FrameLayout(context);
        stage.setFocusable(false);
        stage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        textureView = new TextureView(context);
        textureView.setOpaque(false);
        textureView.setFocusable(false);
        textureView.setSurfaceTextureListener(this);

        crossfadeView = new ImageView(context);
        crossfadeView.setScaleType(ImageView.ScaleType.FIT_XY);
        crossfadeView.setFocusable(false);
        crossfadeView.setAlpha(0f);
        crossfadeView.setVisibility(View.GONE);

        stage.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        stage.addView(crossfadeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    public boolean configureFromPrefs() {
        SharedPreferences prefs = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        boolean isScreensaver = WallpaperPickerActivity.MODE_SCREENSAVER.equals(targetMode);

        String modeKey = isScreensaver
                ? WallpaperPickerActivity.PREF_SCREENSAVER_MODE
                : WallpaperPickerActivity.PREF_WALLPAPER_MODE;
        String pathKey = isScreensaver
                ? WallpaperPickerActivity.PREF_CUSTOM_SCREENSAVER
                : WallpaperPickerActivity.PREF_CUSTOM_WALLPAPER;
        String catKey = isScreensaver
                ? WallpaperPickerActivity.PREF_SCREENSAVER_CATEGORY
                : WallpaperPickerActivity.PREF_WALLPAPER_CATEGORY;
        String intKey = isScreensaver
                ? WallpaperPickerActivity.PREF_SCREENSAVER_INTERVAL
                : WallpaperPickerActivity.PREF_WALLPAPER_INTERVAL;
        String styleKey = isScreensaver
                ? WallpaperPickerActivity.PREF_SCREENSAVER_STYLE
                : WallpaperPickerActivity.PREF_WALLPAPER_STYLE;

        playbackMode = prefs.getString(modeKey, MODE_SINGLE);
        playStyle = prefs.getString(styleKey, STYLE_BOUNCE);
        category = prefs.getString(catKey, "ALL");
        int mins = prefs.getInt(intKey, 5);
        if (mins != 1 && mins != 5 && mins != 10) mins = 5;
        intervalMs = mins * 60_000L;

        if (isScreensaver && musicOverride) {
            String musicMode = prefs.getString(WallpaperPickerActivity.PREF_MUSIC_MODE, null);
            if (musicMode != null) {
                playbackMode = musicMode;
                if (MODE_SINGLE.equals(musicMode)) {
                    pathKey = WallpaperPickerActivity.PREF_CUSTOM_MUSIC;
                    styleKey = WallpaperPickerActivity.PREF_MUSIC_STYLE;
                    playStyle = prefs.getString(styleKey, STYLE_BOUNCE);
                } else {
                    category = prefs.getString(WallpaperPickerActivity.PREF_MUSIC_CATEGORY, "Music");
                    styleKey = WallpaperPickerActivity.PREF_MUSIC_STYLE;
                    playStyle = prefs.getString(styleKey, STYLE_BOUNCE);
                    intervalMs = 15_000L;
                }
            } else {
                playbackMode = MODE_RANDOM;
                category = "Music";
                intervalMs = 15_000L;
            }
        }

        if (!isScreensaver && MODE_NONE.equals(playbackMode)) {
            releasePlayer();
            if (stage.getParent() != null) {
                ((ViewGroup) stage.getParent()).removeView(stage);
            }
            root.setBackgroundColor(0xFF0D0D11);
            return true;
        }

        playlist.clear();
        if (MODE_RANDOM.equals(playbackMode)) {
            playlist.addAll(WallpaperPickerActivity.collectFilesForCategory(context, category));
            if (playlist.isEmpty()) {
                playlist.addAll(WallpaperPickerActivity.collectFilesForCategory(context, "ALL"));
            }
            if (playlist.isEmpty()) return false;
            currentFile = playlist.get(random.nextInt(playlist.size()));
        } else {
            String customPath = prefs.getString(pathKey, "");
            File f = customPath != null && !customPath.isEmpty() ? new File(customPath) : null;
            if (f == null || !f.canRead()) {
                List<File> all = WallpaperPickerActivity.collectFilesForCategory(context, "ALL");
                if (!all.isEmpty()) {
                    f = all.get(0);
                    prefs.edit().putString(pathKey, f.getAbsolutePath()).apply();
                } else {
                    return false;
                }
            }
            currentFile = f;
            playlist.add(f);
        }

        if (stage.getParent() == null) {
            root.addView(stage, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.setBackgroundColor(Color.BLACK);
        if (surface != null && surface.isValid() && started) {
            openPlayer(false);
        }
        return true;
    }

    public void setMusicOverride(boolean active) {
        if (this.musicOverride == active) return;
        this.musicOverride = active;
        reloadInPlace();
    }

    public void reloadInPlace() {
        handler.removeCallbacks(loopCheckRunnable);
        handler.removeCallbacks(rotateRunnable);
        captureSnapshotIfPossible();
        configureFromPrefs();
        if (started && !MODE_NONE.equals(playbackMode)) {
            if (surface != null && surface.isValid()) {
                openPlayer(snapshotBitmap != null);
            }
            handler.postDelayed(loopCheckRunnable, LOOP_CHECK_MS);
            if (MODE_RANDOM.equals(playbackMode) && playlist.size() > 1) {
                handler.postDelayed(rotateRunnable, intervalMs);
            }
        }
    }

    public void start() {
        if (closed) return;
        started = true;
        if (MODE_NONE.equals(playbackMode)) return;
        if (player != null && prepared) {
            try {
                if (!player.isPlaying()) player.start();
            } catch (Exception ignored) {
            }
        } else if (surface != null && surface.isValid() && currentFile != null) {
            openPlayer(false);
        }
        handler.removeCallbacks(loopCheckRunnable);
        handler.postDelayed(loopCheckRunnable, LOOP_CHECK_MS);
        handler.removeCallbacks(rotateRunnable);
        if (MODE_RANDOM.equals(playbackMode) && playlist.size() > 1) {
            handler.postDelayed(rotateRunnable, intervalMs);
        }
    }

    public void stop() {
        started = false;
        handler.removeCallbacks(loopCheckRunnable);
        handler.removeCallbacks(rotateRunnable);
        // Release hardware decoder when paused so another Activity (like WallpaperPickerActivity)
        // never contends for the TV's single hardware H.264 codec.
        releasePlayer();
    }

    public void close() {
        closed = true;
        started = false;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        clearSnapshot();
    }

    private void captureSnapshotIfPossible() {
        if (!textureView.isAvailable() || textureView.getWidth() <= 0 || textureView.getHeight() <= 0) return;
        try {
            Bitmap bmp = textureView.getBitmap(textureView.getWidth() / 2, textureView.getHeight() / 2);
            if (bmp != null) {
                if (snapshotBitmap != null && snapshotBitmap != bmp) {
                    snapshotBitmap.recycle();
                }
                snapshotBitmap = bmp;
                crossfadeView.animate().cancel();
                crossfadeView.setImageBitmap(snapshotBitmap);
                crossfadeView.setAlpha(1f);
                crossfadeView.setScaleX(1f);
                crossfadeView.setScaleY(1f);
                crossfadeView.setTranslationX(0f);
                crossfadeView.setTranslationY(0f);
                crossfadeView.setVisibility(View.VISIBLE);
                fadingSnapshot = true;
            }
        } catch (Throwable ignored) {
        }
    }

    private void fadeOutSnapshot() {
        if (!fadingSnapshot) return;
        fadingSnapshot = false;
        crossfadeView.animate().cancel();
        textureView.animate().cancel();

        boolean useMusicTransition = musicOverride && WallpaperPickerActivity.MODE_SCREENSAVER.equals(targetMode);
        if (useMusicTransition) {
            int style = (musicTransitionStep++) % 3;
            int w = Math.max(1, stage.getWidth());
            DecelerateInterpolator interp = new DecelerateInterpolator(1.5f);
            if (style == 0) {
                // Cinematic Zoom-Through transition
                textureView.setScaleX(1.10f);
                textureView.setScaleY(1.10f);
                textureView.setTranslationX(0f);
                textureView.animate().scaleX(1f).scaleY(1f).setDuration(CROSSFADE_MS).setInterpolator(interp).start();
                crossfadeView.animate()
                        .alpha(0f)
                        .scaleX(1.22f)
                        .scaleY(1.22f)
                        .setDuration(CROSSFADE_MS)
                        .setInterpolator(interp)
                        .withEndAction(this::clearSnapshot)
                        .start();
            } else if (style == 1) {
                // Smooth Whip-Glide pan transition
                textureView.setScaleX(1.06f);
                textureView.setScaleY(1.06f);
                textureView.setTranslationX(w * 0.08f);
                textureView.animate().scaleX(1f).scaleY(1f).translationX(0f).setDuration(CROSSFADE_MS).setInterpolator(interp).start();
                crossfadeView.animate()
                        .alpha(0f)
                        .scaleX(1.08f)
                        .scaleY(1.08f)
                        .translationX(-w * 0.16f)
                        .setDuration(CROSSFADE_MS)
                        .setInterpolator(interp)
                        .withEndAction(this::clearSnapshot)
                        .start();
            } else {
                // Soft Pull-Back & Zoom-Out morph transition
                textureView.setScaleX(1.14f);
                textureView.setScaleY(1.14f);
                textureView.setTranslationX(0f);
                textureView.animate().scaleX(1f).scaleY(1f).setDuration(CROSSFADE_MS).setInterpolator(interp).start();
                crossfadeView.animate()
                        .alpha(0f)
                        .scaleX(0.86f)
                        .scaleY(0.86f)
                        .setDuration(CROSSFADE_MS)
                        .setInterpolator(interp)
                        .withEndAction(this::clearSnapshot)
                        .start();
            }
        } else {
            textureView.setScaleX(1f);
            textureView.setScaleY(1f);
            textureView.setTranslationX(0f);
            crossfadeView.animate()
                    .alpha(0f)
                    .setDuration(CROSSFADE_MS)
                    .setInterpolator(new DecelerateInterpolator(1.2f))
                    .withEndAction(this::clearSnapshot)
                    .start();
        }
    }

    private void clearSnapshot() {
        fadingSnapshot = false;
        crossfadeView.setVisibility(View.GONE);
        crossfadeView.setScaleX(1f);
        crossfadeView.setScaleY(1f);
        crossfadeView.setTranslationX(0f);
        crossfadeView.setTranslationY(0f);
        crossfadeView.setImageDrawable(null);
        if (snapshotBitmap != null) {
            snapshotBitmap.recycle();
            snapshotBitmap = null;
        }
    }

    private void crossfadeToNextRandom() {
        if (closed || !started || playlist.isEmpty()) return;
        File next = currentFile;
        if (playlist.size() > 1) {
            for (int tries = 0; tries < 6; tries++) {
                File candidate = playlist.get(random.nextInt(playlist.size()));
                if (currentFile == null || !candidate.getAbsolutePath().equals(currentFile.getAbsolutePath())) {
                    next = candidate;
                    break;
                }
            }
        }
        if (next == null) return;
        currentFile = next;
        captureSnapshotIfPossible();
        openPlayer( true);
    }

    private void openPlayer(boolean crossfadeWhenReady) {
        releasePlayer();
        if (currentFile == null || surface == null || !surface.isValid() || closed || MODE_NONE.equals(playbackMode)) {
            return;
        }
        try {
            player = new MediaPlayer();
            player.setSurface(surface);
            player.setDataSource(currentFile.getAbsolutePath());
            player.setVolume(0f, 0f);
            player.setLooping(true);
            player.setOnVideoSizeChangedListener((mp, vW, vH) -> applyCenterCrop(vW, vH));
            player.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    if (crossfadeWhenReady || fadingSnapshot) {
                        fadeOutSnapshot();
                    }
                }
                return false;
            });
            player.setOnPreparedListener(mp -> {
                if (closed || !started) {
                    releasePlayer();
                    return;
                }
                prepared = true;
                lastSeekMs = SystemClock.elapsedRealtime();
                mp.setVolume(0f, 0f);
                mp.setLooping(true);
                applyCenterCrop(mp.getVideoWidth(), mp.getVideoHeight());
                mp.start();
                if (crossfadeWhenReady || fadingSnapshot) {
                    handler.postDelayed(this::fadeOutSnapshot, 220);
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                clearSnapshot();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            clearSnapshot();
        }
    }

    private void applyCenterCrop(int videoWidth, int videoHeight) {
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return;
        float scaleX = 1.0f;
        float scaleY = 1.0f;
        float videoRatio = (float) videoWidth / videoHeight;
        float viewRatio = (float) viewWidth / viewHeight;
        if (videoRatio > viewRatio) {
            scaleX = videoRatio / viewRatio;
        } else {
            scaleY = viewRatio / videoRatio;
        }
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        textureView.setTransform(matrix);
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try {
                player.setOnPreparedListener(null);
                player.setOnInfoListener(null);
                player.setOnErrorListener(null);
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surface = new Surface(st);
        if (started && currentFile != null && !MODE_NONE.equals(playbackMode)) {
            openPlayer(false);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        if (player != null && prepared) {
            try {
                applyCenterCrop(player.getVideoWidth(), player.getVideoHeight());
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }
}
