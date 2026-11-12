package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.view.KeyEvent;

import java.util.List;

/**
 * Bridges Android's MediaSessionManager (for YouTube, YouTube Music, SmartTube, Spotify, etc.)
 * to the Home screen's under-clock media controls and the 2-slot active app card in the dock.
 */
public final class MediaBridgeService extends NotificationListenerService {

    public interface Listener {
        void onMediaChanged();
    }

    private static Listener listener;

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void notifyChanged() {
        if (listener != null) listener.onMediaChanged();
    }

    public static ComponentName component(Context ctx) {
        return new ComponentName(ctx, GuardRevival.class);
    }

    public static MediaController findController(Context ctx) {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            List<MediaController> list = msm.getActiveSessions(component(ctx));
            if (list == null || list.isEmpty()) return null;
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    return mc;
                }
            }
            String activePkg = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                    .getString(AppAdapter.PREF_ACTIVE_MEDIA_PKG, "");
            if (activePkg != null && !activePkg.isEmpty()) {
                for (MediaController mc : list) {
                    if (activePkg.equals(mc.getPackageName())) return mc;
                }
            }
            return list.get(0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String getActivePackage(Context ctx) {
        MediaController mc = findController(ctx);
        if (mc != null && mc.getPackageName() != null && !mc.getPackageName().isEmpty()) {
            return mc.getPackageName();
        }
        String activePkg = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(AppAdapter.PREF_ACTIVE_MEDIA_PKG, "");
        return activePkg != null && !activePkg.isEmpty() ? activePkg : null;
    }

    public static String getTitle(Context ctx) {
        MediaController mc = findController(ctx);
        if (mc == null) return null;
        MediaMetadata meta = mc.getMetadata();
        if (meta == null) return null;
        CharSequence t = meta.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null || t.length() == 0) t = meta.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        return t != null && t.length() > 0 ? t.toString() : null;
    }

    public static String getArtist(Context ctx) {
        MediaController mc = findController(ctx);
        if (mc == null) return null;
        MediaMetadata meta = mc.getMetadata();
        if (meta == null) return null;
        CharSequence a = meta.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null || a.length() == 0) a = meta.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (a == null || a.length() == 0) a = meta.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (a == null || a.length() == 0) a = meta.getText(MediaMetadata.METADATA_KEY_AUTHOR);
        return a != null && a.length() > 0 ? a.toString() : null;
    }

    public static Bitmap getArtwork(Context ctx, String forPkg) {
        try {
            MediaController primary = findController(ctx);
            if (primary != null && (forPkg == null || forPkg.isEmpty() || forPkg.equals(primary.getPackageName()))) {
                Bitmap b = extractBitmap(primary.getMetadata());
                if (b != null) return b;
            }
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            List<MediaController> list = msm.getActiveSessions(component(ctx));
            if (list == null) return null;
            for (MediaController mc : list) {
                if (forPkg != null && !forPkg.isEmpty() && !forPkg.equals(mc.getPackageName())) continue;
                Bitmap b = extractBitmap(mc.getMetadata());
                if (b != null) return b;
            }
            for (MediaController mc : list) {
                Bitmap b = extractBitmap(mc.getMetadata());
                if (b != null) return b;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Bitmap extractBitmap(MediaMetadata meta) {
        if (meta == null) return null;
        Bitmap bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return (bmp != null && !bmp.isRecycled()) ? bmp : null;
    }

    public static boolean isPlaying(Context ctx) {
        MediaController mc = findController(ctx);
        if (mc != null) {
            PlaybackState st = mc.getPlaybackState();
            if (st != null) return st.getState() == PlaybackState.STATE_PLAYING;
        }
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void previous(Context ctx) {
        MediaController mc = findController(ctx);
        if (mc != null) {
            try {
                mc.getTransportControls().skipToPrevious();
                return;
            } catch (Throwable ignored) {
            }
        }
        sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    public static void playPause(Context ctx) {
        MediaController mc = findController(ctx);
        if (mc != null) {
            try {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    mc.getTransportControls().pause();
                } else {
                    mc.getTransportControls().play();
                }
                return;
            } catch (Throwable ignored) {
            }
        }
        sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    public static void next(Context ctx) {
        MediaController mc = findController(ctx);
        if (mc != null) {
            try {
                mc.getTransportControls().skipToNext();
                return;
            } catch (Throwable ignored) {
            }
        }
        sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public static void stop(Context ctx) {
        MediaController mc = findController(ctx);
        if (mc != null) {
            try {
                mc.getTransportControls().pause();
                mc.getTransportControls().stop();
            } catch (Throwable ignored) {
            }
        }
        sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_STOP);
    }

    public static void closeApp(Context ctx, String pkg) {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm != null) {
                List<MediaController> list = msm.getActiveSessions(component(ctx));
                if (list != null) {
                    for (MediaController mc : list) {
                        if (pkg == null || pkg.equals(mc.getPackageName())) {
                            try {
                                mc.getTransportControls().pause();
                                mc.getTransportControls().stop();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_STOP);
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(AppAdapter.PREF_ACTIVE_MEDIA_PKG)
                .apply();
        if (pkg != null && !pkg.isEmpty()) {
            try {
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) am.killBackgroundProcesses(pkg);
            } catch (Throwable ignored) {
            }
        }
        notifyChanged();
    }

    private static void sendMediaKey(Context ctx, int keyCode) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Throwable ignored) {
        }
    }
}
