package com.dongting.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.session.MediaSession.Token;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;

public class PlaybackService extends Service {
    static final String ACTION_TOGGLE = "com.dongting.player.TOGGLE";
    static final String ACTION_PREVIOUS = "com.dongting.player.PREVIOUS";
    static final String ACTION_NEXT = "com.dongting.player.NEXT";
    static final String ACTION_STOP = "com.dongting.player.STOP";

    private static final String CHANNEL_ID = "dongting_playback";
    private static final int NOTIFICATION_ID = 20260502;

    private final LocalBinder binder = new LocalBinder();
    private ExoPlayer player;
    private MediaSession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build();
        player = new ExoPlayer.Builder(this).setAudioAttributes(attrs, true).build();
        player.setHandleAudioBecomingNoisy(true);
        mediaSession = new MediaSession.Builder(this, player).build();
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updateForegroundState();
            }
            @Override public void onMediaItemTransition(@Nullable androidx.media3.common.MediaItem mediaItem, int reason) {
                updateForegroundState();
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            if (player.isPlaying()) player.pause(); else player.play();
        } else if (ACTION_PREVIOUS.equals(action)) {
            if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem();
        } else if (ACTION_NEXT.equals(action)) {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem();
        } else if (ACTION_STOP.equals(action)) {
            player.pause();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        updateForegroundState();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) mediaSession.release();
        if (player != null) player.release();
        super.onDestroy();
    }

    ExoPlayer getPlayer() {
        return player;
    }

    MediaSession getMediaSession() {
        return mediaSession;
    }

    void refreshNotification() {
        updateForegroundState();
    }

    private void updateForegroundState() {
        if (player == null) return;
        Notification notification = buildNotification();
        if (player.isPlaying()) {
            startForeground(NOTIFICATION_ID, notification);
        } else {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH);
            }
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = "洞听播放器";
        String subtitle = player.isPlaying() ? "正在播放" : "已暂停";
        if (player.getCurrentMediaItem() != null && player.getCurrentMediaItem().mediaMetadata.title != null) {
            title = String.valueOf(player.getCurrentMediaItem().mediaMetadata.title);
            if (player.getCurrentMediaItem().mediaMetadata.artist != null) {
                subtitle = String.valueOf(player.getCurrentMediaItem().mediaMetadata.artist);
            }
        }

        Notification.Builder builder = new Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setSmallIcon(R.drawable.ic_stat_dongting)
                .setContentIntent(contentIntent)
                .setDeleteIntent(action(ACTION_STOP, 5))
                .setOngoing(player.isPlaying())
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_previous, "上一首", action(ACTION_PREVIOUS, 1))
                .addAction(player.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        player.isPlaying() ? "暂停" : "播放", action(ACTION_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, "下一首", action(ACTION_NEXT, 3))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ACTION_STOP, 4));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Token token = mediaSession.getPlatformToken();
            builder.setStyle(new Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2));
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        return builder.build();
    }

    private PendingIntent action(String action, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "洞听播放",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("洞听播放器后台播放控制");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    public class LocalBinder extends Binder {
        PlaybackService getService() {
            return PlaybackService.this;
        }
    }
}
