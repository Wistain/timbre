package com.example.timbre;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

/** Plays completed files without opening a microphone or requesting audio focus. */
public final class PlaybackService extends Service {
    public static final String ACTION_PLAY = "com.example.timbre.PLAY";
    public static final String ACTION_STOP = "com.example.timbre.STOP";
    public static final String ACTION_SHOW_OVERLAY = "com.example.timbre.SHOW_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.example.timbre.HIDE_OVERLAY";
    public static final String ACTION_CLOSE = "com.example.timbre.CLOSE";
    public static final String EXTRA_CLIP = "clip";
    public static volatile String playingClip;
    public static volatile boolean overlayVisible;
    public static volatile boolean running;
    private static PlaybackService instance;
    private static final String CHANNEL = "voice-playback";
    private static final int NOTIFICATION_ID = 71;
    private MediaPlayer player;
    private WindowManager windowManager;
    private View overlay;
    private TextView overlayPlay;
    private boolean foreground;
    private ResultIdentity resultIdentity;

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        instance = this;
        try {
            resultIdentity = new ResultIdentity(getFilesDir(), MainActivity.readModelMetadata(this).modelSha256);
        } catch (IOException invalidConfig) {
            resultIdentity = null;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "变声播放", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("播放变声和控制悬浮按钮");
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { finishIfIdle(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action) || ACTION_SHOW_OVERLAY.equals(action)) {
            // Must precede asynchronous preparation for startForegroundService callers.
            startForeground(NOTIFICATION_ID, notification());
            foreground = true;
        }
        if (ACTION_PLAY.equals(action)) {
            String clip = intent.getStringExtra(EXTRA_CLIP);
            if (clip == null) { clip = "converted"; }
            if (clip.equals(playingClip)) { releasePlayer(); finishIfIdle(); }
            else { playFile(clip); }
        } else if (ACTION_SHOW_OVERLAY.equals(action)) {
            showOverlay();
            finishIfIdle();
        } else if (ACTION_HIDE_OVERLAY.equals(action)) {
            hideOverlay();
            finishIfIdle();
        } else if (ACTION_STOP.equals(action)) {
            releasePlayer();
            finishIfIdle();
        } else if (ACTION_CLOSE.equals(action)) {
            releasePlayer();
            hideOverlay();
            finishIfIdle();
        }
        return START_NOT_STICKY;
    }

    private void playFile(String clip) {
        releasePlayer();
        String fileName;
        if ("source".equals(clip)) { fileName = "source.wav"; }
        else if ("reference".equals(clip)) { fileName = "reference.wav"; }
        else if ("converted".equals(clip)) {
            if (!hasCurrentResult()) { finishIfIdle(); return; }
            fileName = "converted.wav";
        }
        else { finishIfIdle(); return; }
        File file = new File(getFilesDir(), fileName);
        if (!file.isFile() || file.length() == 0) {
            Toast.makeText(this, "请先完成录音和音色转换", Toast.LENGTH_SHORT).show();
            finishIfIdle();
            return;
        }
        MediaPlayer next = new MediaPlayer();
        player = next;
        playingClip = clip;
        next.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        next.setOnPreparedListener(prepared -> {
            if (player != prepared) { return; }
            try {
                prepared.start();
                updateControls();
            } catch (RuntimeException unavailable) {
                failPlayback();
            }
        });
        next.setOnCompletionListener(completed -> {
            if (player != completed) { return; }
            releasePlayer();
            finishIfIdle();
        });
        next.setOnErrorListener((failed, what, extra) -> {
            if (player == failed) { failPlayback(); }
            return true;
        });
        try {
            next.setDataSource(file.getAbsolutePath());
            next.prepareAsync();
            updateControls();
        } catch (IOException | RuntimeException failure) {
            failPlayback();
        }
    }

    private void failPlayback() {
        releasePlayer();
        Toast.makeText(this, "播放失败，请返回声色重新试听", Toast.LENGTH_SHORT).show();
        finishIfIdle();
    }

    private void releasePlayer() {
        MediaPlayer old = player;
        player = null;
        playingClip = null;
        if (old != null) {
            old.setOnPreparedListener(null);
            old.setOnCompletionListener(null);
            old.setOnErrorListener(null);
            old.release();
        }
        updateControls();
    }

    private void showOverlay() {
        if (!hasCurrentResult()) { return; }
        if (overlay != null) { updateControls(); return; }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先允许声色显示悬浮窗", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(5), dp(3), dp(6), dp(3));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(21, 40, 53));
        background.setCornerRadius(dp(28));
        background.setStroke(dp(1), Color.rgb(113, 226, 189));
        pill.setBackground(background);
        pill.setElevation(dp(8));
        TextView handle = label("⋮", 22);
        handle.setContentDescription("拖动悬浮按钮");
        pill.addView(handle, new LinearLayout.LayoutParams(dp(25), dp(46)));
        overlayPlay = label("▶", 23);
        overlayPlay.setContentDescription("播放或停止变声");
        pill.addView(overlayPlay, new LinearLayout.LayoutParams(dp(49), dp(46)));
        TextView close = label("×", 25);
        close.setContentDescription("关闭悬浮播放");
        pill.addView(close, new LinearLayout.LayoutParams(dp(35), dp(46)));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(12);
        params.y = dp(190);
        params.alpha = .8f;
        handle.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            int windowX, windowY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX(); startY = event.getRawY(); windowX = params.x; windowY = params.y;
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    int maxX = Math.max(0, getResources().getDisplayMetrics().widthPixels - pill.getWidth());
                    int maxY = Math.max(0, getResources().getDisplayMetrics().heightPixels - pill.getHeight());
                    params.x = Math.max(0, Math.min(maxX, windowX - (int) (event.getRawX() - startX)));
                    params.y = Math.max(0, Math.min(maxY, windowY + (int) (event.getRawY() - startY)));
                    if (overlay == pill) {
                        try { windowManager.updateViewLayout(pill, params); } catch (RuntimeException ignored) { }
                    }
                    return true;
                }
                return true;
            }
        });
        overlayPlay.setOnClickListener(view -> {
            if ("converted".equals(playingClip)) { releasePlayer(); }
            else { playFile("converted"); }
        });
        close.setOnClickListener(view -> { releasePlayer(); hideOverlay(); finishIfIdle(); });
        try {
            windowManager.addView(pill, params);
            overlay = pill;
            overlayVisible = true;
            updateControls();
        } catch (RuntimeException denied) {
            overlayPlay = null;
            Toast.makeText(this, "悬浮窗未能开启，请检查系统权限", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasCurrentResult() {
        if (resultIdentity != null && resultIdentity.isCurrent()) { return true; }
        Toast.makeText(this, "目标音色已更新，请返回声色重新生成变声", Toast.LENGTH_LONG).show();
        return false;
    }

    private TextView label(String value, int size) {
        TextView view = new TextView(this);
        view.setSoundEffectsEnabled(false);
        view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(113, 226, 189));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private void hideOverlay() {
        View old = overlay;
        overlay = null;
        overlayPlay = null;
        overlayVisible = false;
        if (old != null) {
            try { windowManager.removeView(old); } catch (RuntimeException ignored) { }
        }
    }

    private void updateControls() {
        if (overlayPlay != null) { overlayPlay.setText("converted".equals(playingClip) ? "■" : "▶"); }
        if (foreground) { getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification()); }
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent play = PendingIntent.getService(this, 2,
                new Intent(this, PlaybackService.class).setAction(ACTION_PLAY).putExtra(EXTRA_CLIP, "converted"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, PlaybackService.class).setAction(ACTION_CLOSE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(com.example.timbre.R.drawable.ic_voice)
                .setContentTitle("声色 · 离线变声")
                .setContentText(playingClip != null ? "正在播放，点击返回声色" : "悬浮播放已开启")
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "播放/停止变声", play).build())
                .addAction(new Notification.Action.Builder(null, "关闭", stop).build()).build();
    }

    private void finishIfIdle() {
        updateControls();
        if (player == null && overlay == null) {
            foreground = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    /** Activity and service share the main looper: release playback before opening the microphone. */
    public static void stopPlaybackNow() {
        PlaybackService current = instance;
        if (current != null) {
            current.releasePlayer();
            current.finishIfIdle();
        }
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        foreground = false;
        releasePlayer();
        hideOverlay();
        running = false;
        if (instance == this) { instance = null; }
        super.onDestroy();
    }
}
