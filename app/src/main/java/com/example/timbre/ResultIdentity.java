package com.example.timbre;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Owns the saved conversion result for one embedded converter model. */
public final class ResultIdentity {
    private static final Object FILE_LOCK = new Object();
    private final File directory;
    private final String modelSha256;

    public ResultIdentity(File directory, String modelSha256) {
        if (directory == null || modelSha256 == null
                || !modelSha256.toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A converter SHA-256 is required");
        }
        this.directory = directory;
        this.modelSha256 = modelSha256.toLowerCase(Locale.ROOT);
    }

    /** Restores a result left by a previous app process. */
    public boolean restore() throws IOException {
        synchronized (FILE_LOCK) {
            if (!isCurrent()) {
                invalidate();
                return false;
            }
            delete("converted.pending.wav");
            delete("converted.model-sha256.pending");
            return true;
        }
    }

    /** Read-only check, also used by notification and floating playback entry points. */
    public boolean isCurrent() {
        synchronized (FILE_LOCK) {
            File audio = new File(directory, "converted.wav");
            File identity = new File(directory, "converted.model-sha256");
            if (!audio.isFile() || audio.length() <= 44 || ((audio.length() - 44) & 1) != 0
                    || !identity.isFile() || identity.length() > 128) {
                return false;
            }
            try {
                String expected = modelSha256 + "\n" + audio.length() + "\n";
                String actual = new String(Files.readAllBytes(identity.toPath()), StandardCharsets.US_ASCII);
                return expected.equals(actual);
            } catch (IOException unavailable) {
                return false;
            }
        }
    }

    /** Remove the marker first: a crash can never expose a partially replaced result as valid. */
    public void invalidate() throws IOException {
        synchronized (FILE_LOCK) {
            delete("converted.model-sha256");
            delete("converted.model-sha256.pending");
            delete("converted.pending.wav");
            delete("converted.wav");
        }
    }

    /** Publishes audio first and the identity last. Failure leaves no playable derived result. */
    public void commit(File pending) throws IOException {
        synchronized (FILE_LOCK) {
            File expected = new File(directory, "converted.pending.wav");
            if (pending == null || !expected.getCanonicalFile().equals(pending.getCanonicalFile())) {
                throw new IOException("变声结果临时文件位置异常");
            }
            try {
                checkInterrupted();
                delete("converted.model-sha256");
                delete("converted.model-sha256.pending");
                if (!pending.isFile() || pending.length() <= 44 || ((pending.length() - 44) & 1) != 0) {
                    throw new IOException("变声结果没有完整保存");
                }
                try (RandomAccessFile audio = new RandomAccessFile(pending, "rw")) {
                    audio.getFD().sync();
                }
                File audio = new File(directory, "converted.wav");
                Files.move(pending.toPath(), audio.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                checkInterrupted();
                File marker = new File(directory, "converted.model-sha256.pending");
                String identity = modelSha256 + "\n" + audio.length() + "\n";
                try (FileOutputStream output = new FileOutputStream(marker)) {
                    output.write(identity.getBytes(StandardCharsets.US_ASCII));
                    output.getFD().sync();
                }
                checkInterrupted();
                Files.move(marker.toPath(), new File(directory, "converted.model-sha256").toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                if (!isCurrent()) { throw new IOException("无法确认变声结果对应的目标音色"); }
            } catch (IOException failure) {
                try { invalidate(); } catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) { throw new InterruptedIOException("转换已取消"); }
    }

    private void delete(String name) throws IOException {
        File file = new File(directory, name);
        if (file.exists() && !file.delete()) { throw new IOException("无法清理上次变声结果：" + name); }
    }
}
