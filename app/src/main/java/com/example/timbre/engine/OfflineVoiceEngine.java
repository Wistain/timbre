package com.example.timbre.engine;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Local OpenVoice V2 voice conversion to the reference timbre embedded in converter.onnx. */
public final class OfflineVoiceEngine implements AutoCloseable {
    private static final int MODEL_RATE = AudioFeatures.MODEL_SAMPLE_RATE;
    private static final int CHUNK_SAMPLES = 3 * MODEL_RATE;
    private static final int OVERLAP_SAMPLES = 4096;
    private final OrtEnvironment environment;
    private OrtSession encoder;
    private OrtSession converter;
    private boolean closed;

    public interface ProgressListener {
        void onProgress(int progress, String stage);
    }

    /** Model loading is substantial; construct this on a background thread. */
    public OfflineVoiceEngine(Context context) throws Exception {
        checkInterrupted();
        environment = OrtEnvironment.getEnvironment();
        Context application = context.getApplicationContext();
        // Isolate each installed APK version so a replacement reference voice never uses stale assets.
        long installed = application.getPackageManager()
                .getPackageInfo(application.getPackageName(), 0).lastUpdateTime;
        File directory = new File(application.getFilesDir(), "offline-voice-" + installed);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建本地模型目录");
        }
        File encoderFile = copyModel(application, directory, "ref_encoder.onnx");
        File converterFile = copyModel(application, directory, "converter.onnx");
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1,
                    Math.min(4, Runtime.getRuntime().availableProcessors())));
            options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            // Dynamic audio lengths should not permanently reserve the largest inference arena.
            options.setMemoryPatternOptimization(false);
            options.setCPUArenaAllocator(false);
            encoder = environment.createSession(encoderFile.getAbsolutePath(), options);
            checkInterrupted();
            converter = environment.createSession(converterFile.getAbsolutePath(), options);
            checkInterrupted();
            if (!encoder.getInputNames().contains("spec")
                    || !converter.getInputNames().contains("spec")
                    || !converter.getInputNames().contains("src_se")) {
                throw new IOException("内置语音模型接口不匹配");
            }
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    /** Returns PCM at the input rate and exact input sample count. Does not access the network. */
    public synchronized short[] convert(short[] source, int sampleRate, ProgressListener listener)
            throws Exception {
        if (closed) { throw new IOException("语音引擎已关闭"); }
        if (source == null || sampleRate < 8000 || sampleRate > 192000
                || source.length < sampleRate / 4) {
            throw new IOException("请先录制至少 0.25 秒的声音");
        }
        checkInterrupted();
        report(listener, 2, "准备声音");
        try {
            float[] audio = AudioFeatures.resample(AudioFeatures.toFloat(source), sampleRate, MODEL_RATE);
            double energy = 0;
            for (float sample : audio) { energy += (double) sample * sample; }
            if (Math.sqrt(energy / audio.length) < 0.00003) {
                report(listener, 100, "录音音量太低");
                return source.clone();
            }
            report(listener, 8, "分析原声音色");
            AudioFeatures.Spectrogram fullSpec = AudioFeatures.magnitude(AudioFeatures.padToHop(audio));
            float[] sourceEmbedding;
            try (OnnxTensor spec = tensor(fullSpec.values, 1, AudioFeatures.BINS, fullSpec.frames);
                 OrtSession.Result result = encoder.run(singleInput("spec", spec))) {
                sourceEmbedding = floats(result.get(0));
            }
            // Release the Java reference before converter inference; encoder inputs can be much longer.
            fullSpec = null;
            if (sourceEmbedding.length != 256) {
                throw new IOException("原声音色特征维度不匹配");
            }
            checkInterrupted();
            report(listener, 18, "转换为目标音色");
            float[] converted = new float[audio.length];
            int chunkCount = audio.length <= CHUNK_SAMPLES ? 1
                    : 1 + (audio.length - CHUNK_SAMPLES + CHUNK_SAMPLES - OVERLAP_SAMPLES - 1)
                    / (CHUNK_SAMPLES - OVERLAP_SAMPLES);
            int start = 0;
            int completed = 0;
            int previousEnd = 0;
            try (OnnxTensor embedding = tensor(sourceEmbedding, 1, 256, 1)) {
                while (start < audio.length) {
                    checkInterrupted();
                    int end = Math.min(start + CHUNK_SAMPLES, audio.length);
                    float[] segment = Arrays.copyOfRange(audio, start, end);
                    AudioFeatures.Spectrogram spec = AudioFeatures.magnitude(AudioFeatures.padToHop(segment));
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("src_se", embedding);
                    float[] output;
                    try (OnnxTensor spectrum = tensor(spec.values, 1, AudioFeatures.BINS, spec.frames)) {
                        inputs.put("spec", spectrum);
                        try (OrtSession.Result result = converter.run(inputs)) {
                            output = floats(result.get(0));
                        }
                    }
                    checkInterrupted();
                    if (output.length < segment.length) {
                        throw new IOException("语音模型返回的声音长度不足");
                    }
                    int overlap = Math.max(0, previousEnd - start);
                    overlap = Math.min(overlap, segment.length);
                    for (int i = 0; i < segment.length; i++) {
                        float sample = output[i];
                        if (!Float.isFinite(sample)) {
                            throw new IOException("语音模型返回无效声音，请重试");
                        }
                        if (i < overlap) {
                            // Complementary raised-cosine weights keep DC/gain continuous at joins.
                            float weight = (float) (0.5 - 0.5 * Math.cos(Math.PI * (i + 1) / (overlap + 1)));
                            converted[start + i] = converted[start + i] * (1 - weight) + sample * weight;
                        } else {
                            converted[start + i] = sample;
                        }
                    }
                    previousEnd = end;
                    completed++;
                    report(listener, 18 + Math.min(74, 74 * completed / chunkCount),
                            "转换音色 " + completed + "/" + chunkCount);
                    if (end == audio.length) { break; }
                    start = end - OVERLAP_SAMPLES;
                }
            }
            checkInterrupted();
            report(listener, 95, "整理播放声音");
            float[] restored = AudioFeatures.resample(converted, MODEL_RATE, sampleRate);
            short[] pcm = AudioFeatures.toPcm(restored, source.length);
            report(listener, 100, "声音已准备好");
            return pcm;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("音色转换已取消");
        }
    }

    private OnnxTensor tensor(float[] data, long... shape) throws OrtException {
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape);
    }

    private static Map<String, OnnxTensor> singleInput(String name, OnnxTensor value) {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put(name, value);
        return inputs;
    }

    private static float[] floats(OnnxValue value) throws IOException {
        if (!(value instanceof OnnxTensor)) { throw new IOException("模型输出不是音频张量"); }
        FloatBuffer buffer = ((OnnxTensor) value).getFloatBuffer();
        if (buffer == null) { throw new IOException("模型输出不是浮点音频"); }
        float[] data = new float[buffer.remaining()];
        buffer.get(data);
        return data;
    }

    private static File copyModel(Context context, File directory, String name) throws IOException {
        File destination = new File(directory, name);
        long expected = -1;
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(name)) {
            expected = descriptor.getLength();
        } catch (IOException compressedAsset) {
            // Compressed assets are still supported, with a fresh copy on engine initialization.
        }
        if (destination.isFile() && expected > 0 && destination.length() == expected) {
            return destination;
        }
        File temporary = new File(directory, name + ".new");
        try {
            try (InputStream input = context.getAssets().open(name);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkInterrupted();
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (temporary.length() == 0 || (expected >= 0 && temporary.length() != expected)) {
                throw new IOException("内置语音模型不完整：" + name);
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } finally {
            if (temporary.exists()) { temporary.delete(); }
        }
    }

    private static void report(ProgressListener listener, int progress, String stage) {
        if (listener != null) { listener.onProgress(progress, stage); }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) { throw new InterruptedIOException("音色转换已取消"); }
    }

    @Override public synchronized void close() {
        if (closed) { return; }
        closed = true;
        if (converter != null) {
            try { converter.close(); } catch (OrtException ignored) { }
            converter = null;
        }
        if (encoder != null) {
            try { encoder.close(); } catch (OrtException ignored) { }
            encoder = null;
        }
    }
}
