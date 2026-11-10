package com.example.timbre;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.timbre.audio.PcmRecorder;
import com.example.timbre.audio.WavIO;
import com.example.timbre.engine.OfflineVoiceEngine;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The microphone is held only while this activity is visibly recording. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(12, 18, 27);
    private static final int CARD = Color.rgb(21, 31, 45);
    private static final int TEXT = Color.rgb(237, 244, 248);
    private static final int MUTED = Color.rgb(151, 169, 187);
    private static final int ACCENT = Color.rgb(113, 226, 189);
    private static final int RECORD_PERMISSION = 40;
    private static final int MAX_SECONDS = 30;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final PcmRecorder recorder = new PcmRecorder();
    private OfflineVoiceEngine engine;
    private Future<?> job;
    private volatile boolean disposed;
    private boolean recording;
    private boolean busy = true;
    private boolean modelReady;
    private boolean resultReady;
    private ResultIdentity resultIdentity;
    private boolean awaitingOverlay;
    private long startedAt;
    private int recordingGeneration;
    private int sampleCount;
    private short[] sourceSamples;
    private File rawFile, sourceFile;
    private Button recordButton, convertButton, sourceButton, effectButton, floatButton, targetButton;
    private TextView statusText, clockText, detailText, referenceText;
    private ProgressBar progress;
    private MeterView meter;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (disposed) { return; }
            if (recording) {
                long elapsed = SystemClock.elapsedRealtime() - startedAt;
                clockText.setText(time(elapsed / 1000.0));
                if (elapsed >= MAX_SECONDS * 1000L) { finishRecording(); }
            }
            sourceButton.setText("source".equals(PlaybackService.playingClip) ? "■  停止原声" : "▷  原声");
            effectButton.setText("converted".equals(PlaybackService.playingClip) ? "■  停止效果" : "▶  变声效果");
            targetButton.setText("reference".equals(PlaybackService.playingClip) ? "■  停止参考" : "▷  听参考音色");
            floatButton.setText(PlaybackService.overlayVisible ? "关闭悬浮播放" : "开启悬浮播放");
            ui.postDelayed(this, 180);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        rawFile = new File(getFilesDir(), "recording.pcm");
        sourceFile = new File(getFilesDir(), "source.wav");
        buildInterface();
        setStatus("正在准备本地音色…");
        updateButtons();
        worker.execute(() -> {
            try {
                ModelMetadata metadata = readModelMetadata(this);
                ResultIdentity identity = new ResultIdentity(getFilesDir(), metadata.modelSha256);
                boolean restoredResult = identity.restore();
                copyAsset("reference.wav", new File(getFilesDir(), "reference.wav"));
                new File(getFilesDir(), "reference.m4a").delete();
                long count = Math.max(0, sourceFile.length() - 44) / 2;
                boolean recovered = sourceFile.isFile() && rawFile.length() >= count * 2 && count > 0
                        && count <= (long) recorder.getSampleRate() * MAX_SECONDS;
                ui.post(() -> {
                    if (disposed) { return; }
                    resultIdentity = identity;
                    modelReady = true;
                    resultReady = restoredResult;
                    referenceText.setText(String.format(Locale.CHINA,
                            "你提供的参考人声 · %.1f 秒", metadata.referenceSeconds));
                    sampleCount = recovered ? (int) count : 0;
                    busy = false;
                    setStatus(recovered ? (resultReady ? "已恢复上次变声，点击试听" : "已恢复上次录音，可开始转换")
                            : "准备好了，录下你想说的话");
                    updateDetails();
                    updateButtons();
                });
            } catch (Exception failure) {
                ui.post(() -> showFailure("本地参考音色未能加载", failure));
            }
        });
        ui.post(ticker);
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = column();
        page.setPadding(dp(22), dp(20), dp(22), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        // Android 15 draws edge to edge; keep controls outside status/navigation bars.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        String version = "";
        try { version = " · " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException ignored) { }
        TextView eyebrow = text("OFFLINE VOICE" + version, 11, ACCENT);
        eyebrow.setLetterSpacing(0.18f);
        page.addView(eyebrow);
        TextView title = text("声色", 36, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(page, title, 6);
        add(page, text("录下你的话，换成固定音色。", 15, MUTED), 7);

        LinearLayout target = card();
        add(page, target, 24);
        target.addView(text("固定目标音色", 16, TEXT));
        referenceText = text("正在读取参考人声…", 13, MUTED);
        add(target, referenceText, 7);
        targetButton = button("▷  听参考音色", false);
        targetButton.setOnClickListener(v -> play("reference"));
        add(target, targetButton, 13);

        LinearLayout recordingCard = card();
        add(page, recordingCard, 14);
        TextView recordLabel = text("01  录制内容", 14, MUTED);
        recordingCard.addView(recordLabel);
        clockText = text("00:00", 42, TEXT);
        clockText.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        clockText.setGravity(Gravity.CENTER);
        add(recordingCard, clockText, 12);
        meter = new MeterView();
        LinearLayout.LayoutParams meterParams = new LinearLayout.LayoutParams(-1, dp(40));
        meterParams.topMargin = dp(6);
        recordingCard.addView(meter, meterParams);
        detailText = text("每段最长 30 秒，录音结束后转换", 12, MUTED);
        detailText.setGravity(Gravity.CENTER);
        add(recordingCard, detailText, 7);
        recordButton = button("●  开始录音", true);
        recordButton.setOnClickListener(v -> {
            if (recording) { finishRecording(); }
            else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_PERMISSION);
            } else { startRecording(); }
        });
        add(recordingCard, recordButton, 18);

        LinearLayout effectCard = card();
        add(page, effectCard, 14);
        effectCard.addView(text("02  转换与试听", 14, MUTED));
        statusText = text("准备中…", 14, TEXT);
        statusText.setLineSpacing(dp(3), 1);
        add(effectCard, statusText, 12);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        add(effectCard, progress, 10);
        convertButton = button("生成固定音色", true);
        convertButton.setOnClickListener(v -> convert());
        add(effectCard, convertButton, 13);
        LinearLayout ab = new LinearLayout(this);
        ab.setOrientation(LinearLayout.HORIZONTAL);
        sourceButton = button("▷  原声", false);
        effectButton = button("▶  变声效果", false);
        sourceButton.setOnClickListener(v -> play("source"));
        effectButton.setOnClickListener(v -> play("converted"));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(50), 1);
        half.rightMargin = dp(8);
        ab.addView(sourceButton, half);
        ab.addView(effectButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        add(effectCard, ab, 10);

        LinearLayout floatingCard = card();
        add(page, floatingCard, 14);
        floatingCard.addView(text("03  切到其他 App 播放", 14, MUTED));
        add(floatingCard, text("开启悬浮按钮，再到其他 App 录语音。按悬浮 ▶，用扬声器播放变声。", 13, TEXT), 10);
        floatButton = button("开启悬浮播放", false);
        floatButton.setOnClickListener(v -> toggleOverlay());
        add(floatingCard, floatButton, 12);
        add(floatingCard, text("首次开启需允许显示在其他应用上层。对方 App 的降噪、回声消除可能影响收音。", 11, MUTED), 10);
        TextView footnote = text("音频仅在手机本地处理\n音色相似度、转换速度以实际试听为准", 11, MUTED);
        footnote.setGravity(Gravity.CENTER);
        footnote.setLineSpacing(dp(4), 1);
        add(page, footnote, 22);
    }

    private void startRecording() {
        if (!modelReady || busy || recording || disposed) { return; }
        stopPlayback();
        final int generation = ++recordingGeneration;
        try {
            resultIdentity.invalidate();
            resultReady = false;
            recorder.start(rawFile, new PcmRecorder.Listener() {
                @Override public void onLevel(float rms) {
                    ui.post(() -> { if (recording && generation == recordingGeneration) { meter.setLevel(rms); } });
                }
                @Override public void onLimitReached() { ui.post(() -> { if (generation == recordingGeneration) { finishRecording(); } }); }
                @Override public void onError(String message) {
                    ui.post(() -> {
                        if (disposed || generation != recordingGeneration || !recording) { return; }
                        recording = false;
                        recorder.cancel();
                        meter.setLevel(0);
                        setStatus(message);
                        updateButtons();
                    });
                }
            });
            recording = true;
            startedAt = SystemClock.elapsedRealtime();
            sourceSamples = null;
            sampleCount = 0;
            sourceFile.delete();
            clockText.setText("00:00");
            setStatus("正在录音…录完后点击结束");
            updateDetails();
            updateButtons();
        } catch (Exception failure) {
            showFailure("录音未能开始", failure);
        }
    }

    private void finishRecording() {
        if (!recording || disposed) { return; }
        recording = false;
        busy = true;
        meter.setLevel(0);
        setStatus("正在保存录音…");
        updateButtons();
        job = worker.submit(() -> {
            try {
                short[] recorded = recorder.stop();
                if (recorded.length < recorder.getSampleRate() / 2) {
                    throw new IOException("录音太短，请至少说半秒钟");
                }
                int max = MAX_SECONDS * recorder.getSampleRate();
                if (recorded.length > max) { recorded = java.util.Arrays.copyOf(recorded, max); }
                try (FileOutputStream output = new FileOutputStream(sourceFile)) {
                    WavIO.write(output, recorded, recorder.getSampleRate());
                }
                final short[] complete = recorded;
                ui.post(() -> {
                    if (disposed) { return; }
                    sourceSamples = complete;
                    sampleCount = complete.length;
                    busy = false;
                    clockText.setText(time((double) sampleCount / recorder.getSampleRate()));
                    setStatus("录音完成，可以生成固定音色");
                    updateDetails();
                    updateButtons();
                });
            } catch (Exception failure) {
                sourceFile.delete();
                rawFile.delete();
                ui.post(() -> showFailure("未能保存录音", failure));
            }
        });
    }

    private void convert() {
        if (!modelReady || busy || recording || sampleCount == 0 || disposed) { return; }
        stopPlayback();
        try {
            resultIdentity.invalidate();
        } catch (IOException failure) {
            resultReady = false;
            showFailure("未能清理上次变声结果", failure);
            return;
        }
        busy = true;
        resultReady = false;
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        setStatus("正在加载本地模型…");
        updateButtons();
        job = worker.submit(() -> {
            File pending = new File(getFilesDir(), "converted.pending.wav");
            try {
                if (engine == null) { engine = new OfflineVoiceEngine(getApplicationContext()); }
                short[] input = sourceSamples != null ? sourceSamples : readRaw();
                short[] converted = engine.convert(input, recorder.getSampleRate(), (percent, stage) -> ui.post(() -> {
                    if (!disposed) { progress.setProgress(percent); setStatus(stage + "  " + percent + "%"); }
                }));
                if (Thread.currentThread().isInterrupted()) { throw new java.io.InterruptedIOException("转换已取消"); }
                try (FileOutputStream output = new FileOutputStream(pending)) {
                    WavIO.write(output, converted, recorder.getSampleRate());
                }
                resultIdentity.commit(pending);
                ui.post(() -> {
                    if (disposed) { return; }
                    resultReady = resultIdentity.isCurrent();
                    if (!resultReady) {
                        showFailure("音色转换未完成", new IOException("变声结果已失效，请重新生成"));
                        return;
                    }
                    busy = false;
                    progress.setProgress(100);
                    progress.setVisibility(View.GONE);
                    setStatus("转换完成，试听后可开启悬浮播放");
                    updateButtons();
                });
            } catch (Exception failure) {
                try { resultIdentity.invalidate(); }
                catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                ui.post(() -> showFailure("音色转换未完成", failure));
            } finally {
                if (disposed && engine != null) { engine.close(); engine = null; }
            }
        });
    }

    private short[] readRaw() throws IOException {
        long rawBytes = rawFile.length();
        long bytes = (long) sampleCount * 2;
        if (bytes <= 0 || rawBytes < bytes || (rawBytes & 1) != 0
                || bytes > (long) MAX_SECONDS * recorder.getSampleRate() * 2) {
            throw new IOException("原始录音已失效，请重新录音");
        }
        byte[] data = new byte[(int) bytes];
        try (FileInputStream input = new FileInputStream(rawFile)) {
            int done = 0;
            while (done < data.length) {
                int n = input.read(data, done, data.length - done);
                if (n < 0) { throw new IOException("原始录音不完整"); }
                done += n;
            }
        }
        short[] samples = new short[data.length / 2];
        for (int i = 0; i < samples.length; i++) { samples[i] = (short) ((data[i * 2] & 255) | (data[i * 2 + 1] << 8)); }
        return samples;
    }

    private void play(String clip) {
        if (!modelReady || busy || recording || disposed || ("converted".equals(clip) && !resultReady)) { return; }
        if (clip.equals(PlaybackService.playingClip)) { stopPlayback(); return; }
        Intent intent = new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_PLAY);
        intent.putExtra(PlaybackService.EXTRA_CLIP, clip);
        startForegroundService(intent);
    }

    private void stopPlayback() {
        PlaybackService.stopPlaybackNow();
    }

    private void toggleOverlay() {
        if (PlaybackService.overlayVisible) {
            startService(new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_HIDE_OVERLAY));
        } else if (!Settings.canDrawOverlays(this)) {
            awaitingOverlay = true;
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException unavailable) {
                awaitingOverlay = false;
                Toast.makeText(this, "请到系统应用设置中允许悬浮窗权限", Toast.LENGTH_LONG).show();
            }
        } else { showOverlay(); }
    }

    private void showOverlay() {
        if (!modelReady || busy || recording || !resultReady) { return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        }
        startForegroundService(new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_SHOW_OVERLAY));
        setStatus("悬浮播放已开启，可切到其他 App");
    }

    @Override protected void onResume() {
        super.onResume();
        if (awaitingOverlay) {
            awaitingOverlay = false;
            if (Settings.canDrawOverlays(this)) { showOverlay(); }
        }
    }

    @Override protected void onStop() {
        if (recording) { finishRecording(); }
        super.onStop();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == RECORD_PERMISSION) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) { startRecording(); }
            else { setStatus("需要麦克风权限才能录音，可在系统设置中允许"); }
        }
    }

    @Override protected void onDestroy() {
        disposed = true;
        recorder.cancel();
        ui.removeCallbacksAndMessages(null);
        if (job != null) { job.cancel(true); }
        worker.execute(() -> { if (engine != null) { engine.close(); engine = null; } });
        worker.shutdown();
        super.onDestroy();
    }

    private void updateButtons() {
        recordButton.setEnabled(modelReady && !busy);
        recordButton.setText(recording ? "■  结束录音" : (sampleCount > 0 ? "●  重新录音" : "●  开始录音"));
        convertButton.setEnabled(modelReady && !busy && !recording && sampleCount > 0);
        convertButton.setText(resultReady ? "重新生成固定音色" : "生成固定音色");
        sourceButton.setEnabled(modelReady && !busy && !recording && sourceFile.isFile());
        effectButton.setEnabled(modelReady && !busy && !recording && resultReady);
        targetButton.setEnabled(modelReady && !busy && !recording);
        floatButton.setEnabled(modelReady && !busy && !recording && resultReady);
        for (Button b : new Button[]{recordButton, convertButton, sourceButton, effectButton, targetButton, floatButton}) {
            b.setAlpha(b.isEnabled() ? 1f : 0.38f);
        }
    }

    private void updateDetails() {
        detailText.setText(sampleCount > 0 ? String.format(Locale.CHINA, "已录制 %.1f 秒 · 目标音色固定", (double) sampleCount / recorder.getSampleRate())
                : "每段最长 30 秒，录音结束后转换");
    }

    private void showFailure(String message, Exception failure) {
        if (disposed) { return; }
        busy = false;
        progress.setVisibility(View.GONE);
        String detail = failure.getMessage();
        setStatus(message + (detail == null ? "，请重试" : "：" + detail));
        updateButtons();
    }

    private void setStatus(String value) { statusText.setText(value); }

    private void copyAsset(String name, File destination) throws IOException {
        File pending = new File(destination.getPath() + ".pending");
        try (InputStream input = getAssets().open(name); FileOutputStream output = new FileOutputStream(pending)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); }
            output.getFD().sync();
        }
        Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** The model identifier is read from the tiny packaged manifest, never by hashing the ONNX. */
    static ModelMetadata readModelMetadata(Context context) throws IOException {
        try (InputStream input = context.getAssets().open("model-config.json");
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 65536) { throw new IOException("内置音色配置过大"); }
                bytes.write(buffer, 0, count);
            }
            JSONObject config = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            String sha = config.getJSONObject("assets").getJSONObject("converter.onnx").getString("sha256");
            double seconds = config.getDouble("reference_seconds");
            if (!sha.matches("[0-9a-fA-F]{64}") || !Double.isFinite(seconds) || seconds <= 0) {
                throw new IOException("内置音色配置无效");
            }
            return new ModelMetadata(sha.toLowerCase(Locale.ROOT), seconds);
        } catch (JSONException invalid) {
            throw new IOException("内置音色配置无法读取", invalid);
        }
    }

    static final class ModelMetadata {
        final String modelSha256;
        final double referenceSeconds;
        ModelMetadata(String modelSha256, double referenceSeconds) {
            this.modelSha256 = modelSha256;
            this.referenceSeconds = referenceSeconds;
        }
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout card() {
        LinearLayout layout = column();
        layout.setPadding(dp(17), dp(17), dp(17), dp(17));
        layout.setBackground(shape(CARD, 19, Color.rgb(35, 49, 66)));
        return layout;
    }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }
    private Button button(String value, boolean primary) {
        Button button = new Button(this);
        button.setSoundEffectsEnabled(false);
        button.setText(value); button.setAllCaps(false); button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? BG : TEXT);
        button.setBackground(shape(primary ? ACCENT : Color.rgb(32, 46, 63), 12, 0));
        button.setMinimumHeight(dp(48)); button.setMinHeight(dp(48));
        button.setPadding(dp(10), dp(9), dp(10), dp(9));
        return button;
    }
    private GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (border != 0) { drawable.setStroke(dp(1), border); }
        return drawable;
    }
    private void add(LinearLayout parent, View child, int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(marginTop); parent.addView(child, params);
    }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private static String time(double seconds) { return String.format(Locale.ROOT, "%02d:%02d", (int) seconds / 60, (int) seconds % 60); }

    private final class MeterView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float level;
        MeterView() { super(MainActivity.this); }
        void setLevel(float next) { level = Math.min(1f, next * 4f); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(ACCENT); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(dp(4));
            float middle = getHeight() / 2f;
            for (int i = 0; i < 31; i++) {
                double profile = 0.2 + 0.8 * Math.abs(Math.sin(i * 1.3));
                float height = dp(3) + (float) (level * getHeight() * .65 * profile);
                float x = getWidth() * (i + 1) / 32f;
                canvas.drawLine(x, middle - height / 2, x, middle + height / 2, paint);
            }
        }
    }
}
