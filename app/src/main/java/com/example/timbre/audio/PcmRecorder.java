package com.example.timbre.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Records mono PCM16 locally. Listener callbacks run on the recording thread. */
public final class PcmRecorder {
    public static final int MAX_DURATION_SECONDS = 120;
    private static final int SAMPLE_RATE = 44100;
    private static final int MAX_SAMPLES = SAMPLE_RATE * MAX_DURATION_SECONDS;
    private Session active;

    public interface Listener {
        /** Normalized RMS level in [0, 1], at most about 12 times per second. */
        void onLevel(float rms);
        /** Recording has finished and stop() can retrieve the samples. */
        void onLimitReached();
        void onError(String message);
    }

    public int getSampleRate() {
        return SAMPLE_RATE;
    }

    /** Initializes the microphone and starts a worker; requires RECORD_AUDIO permission. */
    public synchronized void start(File rawFile, Listener listener) throws IOException {
        // Cancellation may arrive between worker cleanup and its final callback.
        if (active != null && active.cancelled && !active.worker.isAlive()) {
            active.file.delete();
            active = null;
        }
        if (active != null) {
            throw new IOException("请先结束当前录音");
        }
        if (rawFile == null || listener == null) {
            throw new IllegalArgumentException("rawFile and listener are required");
        }
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            throw new IOException("此设备暂不支持 44.1 kHz 单声道录音");
        }
        int bufferBytes = Math.max(minimum * 2, 8192);
        AudioRecord recorder = createRecorder(bufferBytes);
        BufferedOutputStream output = null;
        try {
            output = new BufferedOutputStream(new FileOutputStream(rawFile), 32768);
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("麦克风无法开始录音，请检查权限或其他应用占用");
            }
            Session session = new Session(recorder, rawFile, output, listener);
            session.worker = new Thread(() -> record(session), "timbre-microphone");
            active = session;
            session.worker.start();
        } catch (IOException | RuntimeException failure) {
            active = null;
            if (output != null) {
                try { output.close(); } catch (IOException ignored) { }
            }
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
            recorder.release();
            if (rawFile.exists()) {
                rawFile.delete();
            }
            throw failure;
        }
    }

    private static AudioRecord createRecorder(int bufferBytes) throws IOException {
        // UNPROCESSED avoids automatic voice enhancement when supported by the device.
        int[] sources = {MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION};
        RuntimeException lastFailure = null;
        for (int source : sources) {
            AudioRecord candidate = null;
            try {
                candidate = new AudioRecord(source, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        bufferBytes);
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    return candidate;
                }
            } catch (SecurityException denied) {
                if (candidate != null) { candidate.release(); }
                throw denied;
            } catch (RuntimeException unavailable) {
                lastFailure = unavailable;
            }
            if (candidate != null) { candidate.release(); }
        }
        throw new IOException("无法打开麦克风，请关闭其他正在录音的应用", lastFailure);
    }

    /** Stops the worker and reads its result. Call from a background thread. */
    public short[] stop() throws IOException {
        Session session;
        synchronized (this) {
            session = active;
        }
        if (session == null) {
            throw new IOException("没有可读取的录音");
        }
        if (Thread.currentThread() == session.worker) {
            throw new IllegalStateException("Call stop() from outside a recording callback");
        }
        requestStop(session);
        try {
            session.worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            session.cancelled = true;
            throw new IOException("读取录音已取消", interrupted);
        } finally {
            synchronized (this) {
                if (active == session && !session.worker.isAlive()) {
                    active = null;
                }
            }
        }
        if (session.cancelled) {
            throw new IOException("录音已取消");
        }
        if (session.failure != null) {
            throw session.failure;
        }
        return readPcm(session.file);
    }

    /** Requests cancellation without waiting on the UI thread. Partial data is removed. */
    public void cancel() {
        Session session;
        synchronized (this) {
            session = active;
            if (session == null) { return; }
            session.cancelled = true;
        }
        requestStop(session);
        // A completed recording has no worker left to perform cancellation cleanup.
        if (!session.worker.isAlive()) {
            session.file.delete();
            synchronized (this) {
                if (active == session) { active = null; }
            }
        }
    }

    private static void requestStop(Session session) {
        session.running = false;
        // stop() unblocks AudioRecord.read() if the microphone stops producing data.
        try { session.recorder.stop(); } catch (IllegalStateException ignored) { }
    }

    private void record(Session session) {
        short[] samples = new short[2048];
        byte[] bytes = new byte[samples.length * 2];
        int total = 0;
        long nextLevel = 0L;
        boolean reachedLimit = false;
        try {
            while (session.running && total < MAX_SAMPLES) {
                int requested = Math.min(samples.length, MAX_SAMPLES - total);
                int count = session.recorder.read(samples, 0, requested,
                        AudioRecord.READ_BLOCKING);
                if (count < 0) {
                    if (!session.running) { break; }
                    throw new IOException("麦克风读取失败（" + count + "）");
                }
                if (count == 0) {
                    if (session.running) {
                        throw new IOException("麦克风没有返回音频，请重新录音");
                    }
                    break;
                }
                double squareSum = 0;
                for (int i = 0; i < count; i++) {
                    short sample = samples[i];
                    bytes[i * 2] = (byte) sample;
                    bytes[i * 2 + 1] = (byte) (sample >>> 8);
                    squareSum += (double) sample * sample;
                }
                session.output.write(bytes, 0, count * 2);
                total += count;
                long now = System.nanoTime();
                if (now >= nextLevel && !session.cancelled) {
                    nextLevel = now + 80_000_000L;
                    float rms = (float) (Math.sqrt(squareSum / count) / 32768.0);
                    try { session.listener.onLevel(rms); } catch (RuntimeException ignored) { }
                }
            }
            reachedLimit = total >= MAX_SAMPLES && session.running;
        } catch (IOException failure) {
            session.failure = failure;
        } catch (RuntimeException failure) {
            if (session.running && !session.cancelled) {
                session.failure = new IOException("录音中断，请重新录制", failure);
            }
        } finally {
            session.running = false;
            try {
                session.output.close();
            } catch (IOException failure) {
                if (session.failure == null) { session.failure = failure; }
            }
            try { session.recorder.stop(); } catch (IllegalStateException ignored) { }
            session.recorder.release();
            if (session.cancelled) {
                session.file.delete();
                synchronized (this) {
                    if (active == session) { active = null; }
                }
            }
        }
        if (!session.cancelled) {
            try {
                if (session.failure != null) {
                    session.listener.onError(session.failure.getMessage());
                } else if (reachedLimit) {
                    session.listener.onLimitReached();
                }
            } catch (RuntimeException ignored) {
                // UI lifecycle changes must not prevent microphone cleanup.
            }
        }
    }

    private static short[] readPcm(File file) throws IOException {
        long byteCount = file.length();
        if ((byteCount & 1) != 0 || byteCount > (long) MAX_SAMPLES * 2) {
            throw new IOException("录音数据格式异常");
        }
        short[] samples = new short[(int) byteCount / 2];
        byte[] buffer = new byte[8192];
        int position = 0;
        int carriedByte = -1;
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                int offset = 0;
                if (carriedByte >= 0 && count > 0) {
                    samples[position++] = (short) (carriedByte | ((buffer[0] & 255) << 8));
                    carriedByte = -1;
                    offset = 1;
                }
                while (offset + 1 < count) {
                    samples[position++] = (short) ((buffer[offset] & 255)
                            | ((buffer[offset + 1] & 255) << 8));
                    offset += 2;
                }
                if (offset < count) { carriedByte = buffer[offset] & 255; }
            }
        }
        if (carriedByte >= 0 || position != samples.length) {
            throw new IOException("录音文件未完整保存");
        }
        return samples;
    }

    private static final class Session {
        final AudioRecord recorder;
        final File file;
        final BufferedOutputStream output;
        final Listener listener;
        volatile boolean running = true;
        volatile boolean cancelled;
        volatile IOException failure;
        Thread worker;

        Session(AudioRecord recorder, File file, BufferedOutputStream output, Listener listener) {
            this.recorder = recorder;
            this.file = file;
            this.output = output;
            this.listener = listener;
        }
    }
}
