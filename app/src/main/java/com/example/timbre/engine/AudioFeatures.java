package com.example.timbre.engine;

/** OpenVoice V2 linear-magnitude STFT and band-limited PCM resampling. */
public final class AudioFeatures {
    public static final int MODEL_SAMPLE_RATE = 22050;
    public static final int FFT_SIZE = 1024;
    public static final int HOP_SIZE = 256;
    public static final int REFLECT_PADDING = 384;
    public static final int BINS = FFT_SIZE / 2 + 1;
    private static final int SINC_RADIUS = 24;
    private static final int PHASES = 1024;
    private static final double[] HANN = makeHann();

    private AudioFeatures() { }

    public static final class Spectrogram {
        /** Contiguous frequency-major data for a float32 tensor [1, 513, frames]. */
        public final float[] values;
        public final int frames;

        private Spectrogram(float[] values, int frames) {
            this.values = values;
            this.frames = frames;
        }
    }

    public static Spectrogram magnitude(float[] audio) throws InterruptedException {
        if (audio == null || audio.length == 0) {
            throw new IllegalArgumentException("Audio is empty");
        }
        int frames = Math.max(1, (audio.length + 2 * REFLECT_PADDING - FFT_SIZE) / HOP_SIZE + 1);
        float[] magnitudes = new float[Math.multiplyExact(BINS, frames)];
        double[] real = new double[FFT_SIZE];
        double[] imaginary = new double[FFT_SIZE];
        for (int frame = 0; frame < frames; frame++) {
            if ((frame & 31) == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Audio analysis cancelled");
            }
            int start = frame * HOP_SIZE - REFLECT_PADDING;
            for (int i = 0; i < FFT_SIZE; i++) {
                real[i] = audio[reflect(start + i, audio.length)] * HANN[i];
                imaginary[i] = 0;
            }
            fft(real, imaginary);
            for (int bin = 0; bin < BINS; bin++) {
                double power = real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
                magnitudes[bin * frames + frame] = (float) Math.sqrt(power + 1e-6);
            }
        }
        return new Spectrogram(magnitudes, frames);
    }

    /** Pads the final model frame; the converter output can then be trimmed without stretching. */
    public static float[] padToHop(float[] audio) {
        if (audio == null || audio.length == 0) { throw new IllegalArgumentException("Audio is empty"); }
        int length = Math.max(FFT_SIZE, ((audio.length + HOP_SIZE - 1) / HOP_SIZE) * HOP_SIZE);
        if (length == audio.length) { return audio; }
        float[] result = new float[length];
        System.arraycopy(audio, 0, result, 0, audio.length);
        // Reflect the end rather than introducing an abrupt zero discontinuity.
        for (int i = audio.length; i < result.length; i++) {
            result[i] = audio[reflect(i, audio.length)];
        }
        return result;
    }

    public static float[] toFloat(short[] audio) {
        float[] result = new float[audio.length];
        for (int i = 0; i < result.length; i++) { result[i] = audio[i] / 32768f; }
        return result;
    }

    /** Preserves duration; a windowed-sinc lowpass prevents aliasing on downsampling. */
    public static float[] resample(float[] source, int sourceRate, int targetRate)
            throws InterruptedException {
        if (source == null || sourceRate <= 0 || targetRate <= 0) {
            throw new IllegalArgumentException("Invalid audio or sample rate");
        }
        if (sourceRate == targetRate) { return source.clone(); }
        if (source.length == 0) { return new float[0]; }
        long outputLength = Math.round((double) source.length * targetRate / sourceRate);
        if (outputLength > Integer.MAX_VALUE) { throw new IllegalArgumentException("Audio too long"); }
        float[] output = new float[(int) outputLength];
        double cutoff = Math.min(1.0, (double) targetRate / sourceRate) * 0.94;
        float[][] filters = sincFilters(cutoff);
        double step = (double) sourceRate / targetRate;
        for (int i = 0; i < output.length; i++) {
            if ((i & 8191) == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Resampling cancelled");
            }
            double position = i * step;
            int integer = (int) position;
            int phase = (int) Math.round((position - integer) * PHASES);
            if (phase == PHASES) { integer++; phase = 0; }
            float[] filter = filters[phase];
            double sum = 0;
            for (int tap = 0; tap < filter.length; tap++) {
                int index = reflect(integer + tap - SINC_RADIUS + 1, source.length);
                sum += source[index] * filter[tap];
            }
            output[i] = (float) sum;
        }
        return output;
    }

    /** Applies only attenuation when needed, then quantizes to PCM16 at an exact requested length. */
    public static short[] toPcm(float[] audio, int targetLength) {
        if (targetLength < 0) { throw new IllegalArgumentException("Negative PCM length"); }
        float peak = 0;
        for (float sample : audio) {
            if (!Float.isFinite(sample)) { throw new IllegalArgumentException("Model produced invalid audio"); }
            peak = Math.max(peak, Math.abs(sample));
        }
        float scale = peak > 0.98f ? 0.98f / peak : 1f;
        short[] pcm = new short[targetLength];
        int copied = Math.min(audio.length, targetLength);
        for (int i = 0; i < copied; i++) {
            int value = Math.round(audio[i] * scale * 32767f);
            pcm[i] = (short) Math.max(-32768, Math.min(32767, value));
        }
        // Any unavoidable fractional resampling remainder is silent rather than time-stretched.
        return pcm;
    }

    private static float[][] sincFilters(double cutoff) {
        float[][] filters = new float[PHASES][SINC_RADIUS * 2];
        for (int phase = 0; phase < PHASES; phase++) {
            double fraction = (double) phase / PHASES;
            double norm = 0;
            for (int tap = 0; tap < filters[phase].length; tap++) {
                double distance = tap - SINC_RADIUS + 1 - fraction;
                double x = Math.PI * distance * cutoff;
                double sinc = Math.abs(x) < 1e-12 ? 1 : Math.sin(x) / x;
                double windowPosition = distance / SINC_RADIUS;
                double window = Math.abs(windowPosition) > 1 ? 0
                        : 0.42 + 0.5 * Math.cos(Math.PI * windowPosition)
                        + 0.08 * Math.cos(2 * Math.PI * windowPosition);
                double value = cutoff * sinc * window;
                filters[phase][tap] = (float) value;
                norm += value;
            }
            for (int tap = 0; tap < filters[phase].length; tap++) {
                filters[phase][tap] /= norm;
            }
        }
        return filters;
    }

    private static int reflect(int index, int length) {
        if (length <= 1) { return 0; }
        int period = 2 * (length - 1);
        int remainder = index % period;
        if (remainder < 0) { remainder += period; }
        return remainder < length ? remainder : period - remainder;
    }

    private static double[] makeHann() {
        double[] window = new double[FFT_SIZE];
        for (int i = 0; i < window.length; i++) {
            window[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / FFT_SIZE));
        }
        return window;
    }

    private static void fft(double[] real, double[] imaginary) {
        int size = real.length;
        for (int i = 1, j = 0; i < size; i++) {
            int bit = size >>> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>>= 1; }
            j ^= bit;
            if (i < j) {
                double value = real[i]; real[i] = real[j]; real[j] = value;
                value = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = value;
            }
        }
        for (int width = 2; width <= size; width <<= 1) {
            double angle = -2 * Math.PI / width;
            double stepReal = Math.cos(angle);
            double stepImaginary = Math.sin(angle);
            int half = width >>> 1;
            for (int start = 0; start < size; start += width) {
                double phaseReal = 1;
                double phaseImaginary = 0;
                for (int i = 0; i < half; i++) {
                    int left = start + i;
                    int right = left + half;
                    double rightReal = phaseReal * real[right] - phaseImaginary * imaginary[right];
                    double rightImaginary = phaseReal * imaginary[right] + phaseImaginary * real[right];
                    real[right] = real[left] - rightReal;
                    imaginary[right] = imaginary[left] - rightImaginary;
                    real[left] += rightReal;
                    imaginary[left] += rightImaginary;
                    double nextReal = phaseReal * stepReal - phaseImaginary * stepImaginary;
                    phaseImaginary = phaseReal * stepImaginary + phaseImaginary * stepReal;
                    phaseReal = nextReal;
                }
            }
        }
    }
}
