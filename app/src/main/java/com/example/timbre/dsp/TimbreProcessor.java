package com.example.timbre.dsp;

import java.util.concurrent.CancellationException;

/**
 * Offline, mono PCM16 timbre processing. The spectral envelope moves while the
 * original FFT frequencies and phases stay in place. This is deliberately not
 * a pitch shifter or a model that imitates a particular speaker.
 */
public final class TimbreProcessor {
    private static final double PCM_SCALE = 32768.0;
    private static final double MAX_LOG_GAIN = Math.log(4.0);

    private TimbreProcessor() { }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    /**
     * @param formantShiftSemitones envelope shift, clamped to [-4, 4]
     * @param brightnessDb high-frequency shelf, clamped to [-8, 8] dB
     * @param strength effect amount, clamped to [0, 1]
     * @return a new PCM16 array with exactly the input length
     */
    public static short[] process(short[] input, int sampleRate,
                                  float formantShiftSemitones, float brightnessDb,
                                  float strength, ProgressListener listener) {
        if (input == null) {
            throw new IllegalArgumentException("Input audio is required");
        }
        if (sampleRate < 8000 || sampleRate > 192000) {
            throw new IllegalArgumentException("Sample rate must be 8000 to 192000 Hz");
        }
        requireFinite(formantShiftSemitones);
        requireFinite(brightnessDb);
        requireFinite(strength);
        double shift = clamp(formantShiftSemitones, -4.0, 4.0);
        double brightness = clamp(brightnessDb, -8.0, 8.0);
        double amount = clamp(strength, 0.0, 1.0);
        report(listener, 0);
        if (input.length == 0 || amount == 0.0 || (shift == 0.0 && brightness == 0.0)) {
            short[] copy = input.clone();
            report(listener, 100);
            return copy;
        }

        int size = 512;
        while (size < sampleRate * 0.040 && size < 8192) {
            size <<= 1;
        }
        int hop = size / 4;
        int half = size / 2;
        double[] window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / size);
        }

        // Centered zero padding gives both endpoints full overlap coverage.
        double[] sum = new double[input.length];
        double[] weights = new double[input.length];
        double[] real = new double[size];
        double[] imaginary = new double[size];
        double[] envelopeReal = new double[size];
        double[] envelopeImaginary = new double[size];
        double[] previousLogGain = new double[half + 1];
        double formantRatio = Math.pow(2.0, shift / 12.0);
        double brightnessLog = brightness * Math.log(10.0) / 20.0;
        int lifterLength = Math.max(4, (int) Math.round(sampleRate * 0.0028));
        lifterLength = Math.min(lifterLength, half - 1);
        int lastPercent = 0;
        long totalFrames = ((long) input.length + half + hop - 1L) / hop;
        long frameNumber = 0;

        for (long frameStart = -half; frameStart < input.length; frameStart += hop) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Audio processing cancelled");
            }
            double energy = 0.0;
            int validSamples = 0;
            for (int i = 0; i < size; i++) {
                long position = frameStart + i;
                double sample = position >= 0 && position < input.length
                        ? input[(int) position] / PCM_SCALE : 0.0;
                if (position >= 0 && position < input.length) {
                    energy += sample * sample;
                    validSamples++;
                }
                real[i] = sample * window[i];
                imaginary[i] = 0.0;
            }
            transform(real, imaginary, false);
            double largestMagnitude = 0.0;
            for (int k = 0; k <= half; k++) {
                largestMagnitude = Math.max(largestMagnitude,
                        Math.hypot(real[k], imaginary[k]));
            }
            double magnitudeFloor = Math.max(1.0e-10, largestMagnitude * 1.0e-5);
            for (int k = 0; k < size; k++) {
                envelopeReal[k] = Math.log(Math.max(magnitudeFloor,
                        Math.hypot(real[k], imaginary[k])));
                envelopeImaginary[k] = 0.0;
            }

            // A short, smoothly tapered real cepstrum removes harmonic ripples
            // from the log spectrum, leaving the broad vocal-tract envelope.
            transform(envelopeReal, envelopeImaginary, true);
            for (int q = 0; q < size; q++) {
                int distance = Math.min(q, size - q);
                double lifter = distance < lifterLength
                        ? 0.5 + 0.5 * Math.cos(Math.PI * distance / lifterLength) : 0.0;
                envelopeReal[q] *= lifter;
                envelopeImaginary[q] *= lifter;
            }
            transform(envelopeReal, envelopeImaginary, false);

            double rms = Math.sqrt(energy / Math.max(1, validSamples));
            double silenceProtection = smoothStep(0.0001, 0.0015, rms);
            double nyquist = sampleRate * 0.5;
            for (int k = 0; k <= half; k++) {
                double frequency = (double) k * sampleRate / size;
                double sourceBin = clamp(k / formantRatio, 0.0, half);
                int lower = (int) sourceBin;
                int upper = Math.min(half, lower + 1);
                double fraction = sourceBin - lower;
                double warpedEnvelope = envelopeReal[lower] * (1.0 - fraction)
                        + envelopeReal[upper] * fraction;
                double formantGain = clamp(warpedEnvelope - envelopeReal[k],
                        -Math.log(3.0), Math.log(3.0));
                double lowProtection = smoothStep(80.0, 280.0, frequency);
                double upperProtection = 1.0 - smoothStep(
                        Math.min(6000.0, nyquist * 0.70),
                        Math.min(9000.0, nyquist * 0.95), frequency);
                double shelf = Math.pow(frequency / (frequency + 1800.0), 1.5);
                double targetLogGain = clamp((formantGain * upperProtection
                        + brightnessLog * shelf) * amount * lowProtection
                        * silenceProtection, -MAX_LOG_GAIN, MAX_LOG_GAIN);
                // Smooth successive frames to avoid audible parameter flutter.
                double logGain = previousLogGain[k] * 0.35 + targetLogGain * 0.65;
                previousLogGain[k] = logGain;
                double gain = Math.exp(logGain);
                real[k] *= gain;
                imaginary[k] *= gain;
                if (k > 0 && k < half) {
                    real[size - k] *= gain;
                    imaginary[size - k] *= gain;
                }
            }
            transform(real, imaginary, true);
            for (int i = 0; i < size; i++) {
                long position = frameStart + i;
                if (position >= 0 && position < input.length) {
                    int index = (int) position;
                    sum[index] += real[i] * window[i];
                    weights[index] += window[i] * window[i];
                }
            }
            frameNumber++;
            int percent = (int) Math.min(98L, frameNumber * 98L / totalFrames);
            if (percent > lastPercent) {
                report(listener, percent);
                lastPercent = percent;
            }
        }

        double peak = 0.0;
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= Math.max(1.0e-12, weights[i]);
            peak = Math.max(peak, Math.abs(sum[i]));
        }
        // Uniform attenuation prevents clipping without introducing nonlinear
        // distortion or changing relative dynamics between frames.
        double safePeak = 32760.0 / PCM_SCALE;
        double gain = peak > safePeak ? safePeak / peak : 1.0;
        short[] output = new short[input.length];
        for (int i = 0; i < output.length; i++) {
            output[i] = (short) Math.round(clamp(sum[i] * gain * PCM_SCALE,
                    -32760.0, 32760.0));
        }
        report(listener, 100);
        return output;
    }

    private static void requireFinite(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException("Effect parameters must be finite");
        }
    }

    private static void report(ProgressListener listener, int percent) {
        if (listener != null) {
            listener.onProgress(percent);
        }
    }

    private static double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    private static double smoothStep(double lower, double upper, double value) {
        double t = clamp((value - lower) / (upper - lower), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /** In-place radix-2 complex FFT; the inverse includes the 1/N scale. */
    private static void transform(double[] real, double[] imaginary, boolean inverse) {
        int count = real.length;
        for (int i = 1, j = 0; i < count; i++) {
            int bit = count >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double swap = real[i];
                real[i] = real[j];
                real[j] = swap;
                swap = imaginary[i];
                imaginary[i] = imaginary[j];
                imaginary[j] = swap;
            }
        }
        for (int width = 2; width <= count; width <<= 1) {
            double angle = (inverse ? 2.0 : -2.0) * Math.PI / width;
            double stepReal = Math.cos(angle);
            double stepImaginary = Math.sin(angle);
            int halfWidth = width / 2;
            for (int start = 0; start < count; start += width) {
                double twiddleReal = 1.0;
                double twiddleImaginary = 0.0;
                for (int offset = 0; offset < halfWidth; offset++) {
                    int even = start + offset;
                    int odd = even + halfWidth;
                    double oddReal = real[odd] * twiddleReal
                            - imaginary[odd] * twiddleImaginary;
                    double oddImaginary = real[odd] * twiddleImaginary
                            + imaginary[odd] * twiddleReal;
                    real[odd] = real[even] - oddReal;
                    imaginary[odd] = imaginary[even] - oddImaginary;
                    real[even] += oddReal;
                    imaginary[even] += oddImaginary;
                    double nextReal = twiddleReal * stepReal
                            - twiddleImaginary * stepImaginary;
                    twiddleImaginary = twiddleReal * stepImaginary
                            + twiddleImaginary * stepReal;
                    twiddleReal = nextReal;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < count; i++) {
                real[i] /= count;
                imaginary[i] /= count;
            }
        }
    }
}
