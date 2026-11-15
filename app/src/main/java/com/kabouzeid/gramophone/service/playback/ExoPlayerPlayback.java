package com.kabouzeid.gramophone.service.playback;

import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;

import com.kabouzeid.gramophone.R;
import com.kabouzeid.gramophone.util.PreferenceUtil;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * {@link Playback} backed by Media3 ExoPlayer instead of the platform MediaPlayer.
 *
 * Gives true gapless playback (ExoPlayer reads the encoder delay/padding from
 * MP3 LAME / iTunes / FLAC metadata), broader codec support and more robust error handling.
 *
 * ExoPlayer must only be touched from the thread whose looper it was built on, but
 * MusicService calls into Playback from several threads (main, PlaybackHandler, ...).
 * So the player lives on its own HandlerThread and every call is marshalled onto it.
 * The player's playlist always holds at most two items: [current, next].
 */
public class ExoPlayerPlayback implements Playback {
    private static final String TAG = ExoPlayerPlayback.class.getSimpleName();

    private final Context context;
    private final HandlerThread thread;
    private final Handler handler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExoPlayer player;
    private final int audioSessionId;

    @Nullable
    private PlaybackCallbacks callbacks;
    private volatile boolean initialized = false;
    private volatile boolean released = false;

    public ExoPlayerPlayback(@NonNull Context context) {
        this.context = context.getApplicationContext();

        thread = new HandlerThread("ExoPlayerPlayback", Process.THREAD_PRIORITY_AUDIO);
        thread.start();
        handler = new Handler(thread.getLooper());

        audioSessionId = Util.generateAudioSessionIdV21(this.context);

        player = runOnPlayerThread(() -> {
            ExoPlayer p = new ExoPlayer.Builder(this.context)
                    .setLooper(thread.getLooper())
                    // MusicService already manages audio focus and the "becoming noisy" broadcast.
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(), false)
                    .setHandleAudioBecomingNoisy(false)
                    .setWakeMode(C.WAKE_MODE_LOCAL)
                    .build();
            p.setAudioSessionId(audioSessionId);
            p.addListener(new PlayerListener());
            return p;
        });

        openAudioEffectSession();
    }

    // ---------------------------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------------------------

    @Override
    public boolean setDataSource(String path) {
        initialized = false;
        if (path == null) return false;
        return runOnPlayerThread(() -> {
            player.setPlayWhenReady(false);
            player.setMediaItem(toMediaItem(path));
            player.prepare();
            initialized = true;
            return true;
        }, false);
    }

    @Override
    public void setNextDataSource(@Nullable String path) {
        runOnPlayerThread(() -> {
            int current = player.getCurrentMediaItemIndex();
            int count = player.getMediaItemCount();
            if (count > current + 1) {
                player.removeMediaItems(current + 1, count);
            }
            if (path != null && isPlayable(path) && PreferenceUtil.getInstance(context).gaplessPlayback()) {
                player.addMediaItem(toMediaItem(path));
            }
            return null;
        }, null);
    }

    @Override
    public void setCallbacks(PlaybackCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public boolean start() {
        return runOnPlayerThread(() -> {
            if (player.getPlaybackState() == Player.STATE_IDLE) {
                player.prepare();
            }
            player.setPlayWhenReady(true);
            return true;
        }, false);
    }

    @Override
    public void stop() {
        initialized = false;
        runOnPlayerThread(() -> {
            player.stop();
            player.clearMediaItems();
            return null;
        }, null);
    }

    @Override
    public void release() {
        if (released) return;
        stop();
        closeAudioEffectSession();
        runOnPlayerThread(() -> {
            player.release();
            return null;
        });
        released = true;
        thread.quitSafely();
    }

    @Override
    public boolean pause() {
        return runOnPlayerThread(() -> {
            player.setPlayWhenReady(false);
            return true;
        }, false);
    }

    @Override
    public boolean isPlaying() {
        return runOnPlayerThread(() -> initialized
                && player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED
                && player.getPlaybackState() != Player.STATE_IDLE, false);
    }

    @Override
    public int duration() {
        if (!initialized) return -1;
        return runOnPlayerThread(() -> {
            long d = player.getDuration();
            return d == C.TIME_UNSET ? -1 : (int) d;
        }, -1);
    }

    @Override
    public int position() {
        if (!initialized) return -1;
        return runOnPlayerThread(() -> (int) player.getCurrentPosition(), -1);
    }

    @Override
    public int seek(int whereto) {
        return runOnPlayerThread(() -> {
            player.seekTo(whereto);
            return whereto;
        }, -1);
    }

    @Override
    public boolean setVolume(float vol) {
        return runOnPlayerThread(() -> {
            player.setVolume(vol);
            return true;
        }, false);
    }

    @Override
    public boolean setAudioSessionId(int sessionId) {
        return runOnPlayerThread(() -> {
            player.setAudioSessionId(sessionId);
            return true;
        }, false);
    }

    @Override
    public int getAudioSessionId() {
        return audioSessionId;
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private final class PlayerListener implements Player.Listener {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return;
            // Drop the finished track so the playlist is [current] again; MusicService
            // will queue the following one via setNextDataSource().
            int current = player.getCurrentMediaItemIndex();
            if (current > 0) player.removeMediaItems(0, current);
            if (callbacks != null) callbacks.onTrackWentToNext();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED) {
                // Like MediaPlayer after completion: stop here so that a later seek(0)
                // doesn't silently resume playback.
                player.setPlayWhenReady(false);
                if (callbacks != null) callbacks.onTrackEnded();
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            Log.e(TAG, "Playback error", error);
            initialized = false;
            mainHandler.post(() -> Toast.makeText(context, R.string.unplayable_file, Toast.LENGTH_SHORT).show());
            // Same as the old MediaPlayer path: an unplayable track ends, and the service moves on.
            player.setPlayWhenReady(false);
            if (callbacks != null) callbacks.onTrackEnded();
        }
    }

    private static MediaItem toMediaItem(@NonNull String path) {
        Uri uri = path.startsWith("content://") || path.startsWith("file://")
                ? Uri.parse(path)
                : Uri.fromFile(new java.io.File(path));
        return MediaItem.fromUri(uri);
    }

    /** MusicService passes the empty song (id -1) when there is no next track. */
    private static boolean isPlayable(@NonNull String path) {
        return !path.endsWith("/-1");
    }

    private void openAudioEffectSession() {
        final Intent intent = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
        intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
        context.sendBroadcast(intent);
    }

    private void closeAudioEffectSession() {
        final Intent intent = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
        context.sendBroadcast(intent);
    }

    /** Like {@link #runOnPlayerThread(Callable)}, but returns {@code fallback} once released. */
    private <T> T runOnPlayerThread(@NonNull Callable<T> task, T fallback) {
        if (released) return fallback;
        try {
            return runOnPlayerThread(task);
        } catch (RuntimeException e) {
            if (released) return fallback;
            throw e;
        }
    }

    /** Runs {@code task} on the player thread and waits for the result. */
    private <T> T runOnPlayerThread(@NonNull Callable<T> task) {
        if (Looper.myLooper() == thread.getLooper()) {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        FutureTask<T> future = new FutureTask<>(task);
        if (!handler.post(future)) {
            throw new IllegalStateException("Player thread is gone");
        }
        try {
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
