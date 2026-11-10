package com.example.timbre.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes a standard PCM16 mono WAV without closing the caller's stream. */
public final class WavIO {
    private WavIO() { }

    public static void write(OutputStream output, short[] samples, int sampleRate)
            throws IOException {
        if (output == null || samples == null) {
            throw new IllegalArgumentException("output and samples are required");
        }
        if (sampleRate < 1 || sampleRate > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Invalid sample rate");
        }
        long dataBytes = (long) samples.length * 2;
        if (dataBytes + 36 > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("PCM exceeds the RIFF WAV size limit");
        }
        output.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        little32(output, dataBytes + 36);
        output.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        little32(output, 16);
        little16(output, 1); // PCM
        little16(output, 1); // Mono
        little32(output, sampleRate);
        little32(output, (long) sampleRate * 2);
        little16(output, 2); // Block alignment
        little16(output, 16);
        output.write("data".getBytes(StandardCharsets.US_ASCII));
        little32(output, dataBytes);
        byte[] buffer = new byte[8192];
        int cursor = 0;
        for (short sample : samples) {
            buffer[cursor++] = (byte) sample;
            buffer[cursor++] = (byte) (sample >>> 8);
            if (cursor == buffer.length) {
                output.write(buffer);
                cursor = 0;
            }
        }
        if (cursor > 0) { output.write(buffer, 0, cursor); }
    }

    private static void little16(OutputStream output, int value) throws IOException {
        output.write(value & 255);
        output.write((value >>> 8) & 255);
    }

    private static void little32(OutputStream output, long value) throws IOException {
        output.write((int) (value & 255));
        output.write((int) ((value >>> 8) & 255));
        output.write((int) ((value >>> 16) & 255));
        output.write((int) ((value >>> 24) & 255));
    }
}
