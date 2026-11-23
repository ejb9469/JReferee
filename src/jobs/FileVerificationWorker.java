package jobs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Streams a file and verifies its expected SHA-256 without changing it.
 */
public final class FileVerificationWorker
        implements Worker<FileVerificationRequest, FileVerificationResult> {

    @Override
    public FileVerificationResult execute(FileVerificationRequest request, Workplace workplace)
            throws IOException, InterruptedException {

        Objects.requireNonNull(request,     "request");
        Objects.requireNonNull(workplace,   "workplace");

        workplace.checkCancelled();

        if (!Files.readAttributes(request.file(), BasicFileAttributes.class).isRegularFile())
            throw new IOException("Not a regular file: " + request.file());

        workplace.reportProgress("Verifying " + request.file());

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }

        byte[] buffer = new byte[64 * 1024];
        long bytes = 0, lastProgress = 0;
        try (InputStream input = Files.newInputStream(request.file())) {
            while (true) {
                workplace.checkCancelled();
                int read = input.read(buffer);
                if (read < 0)
                    break;
                digest.update(buffer, 0, read);
                bytes = Math.addExact(bytes, read);
                if (bytes - lastProgress >= 4 * 1024 * 1024) {
                    workplace.reportProgress("Read " + bytes + " bytes");
                    lastProgress = bytes;
                }
            }
        }

        workplace.checkCancelled();

        String hash = HexFormat.of().formatHex(digest.digest());

        if (!hash.equals(request.expectedSha256()))
            throw new IOException("SHA-256 mismatch for " + request.file()
                    + ": expected " + request.expectedSha256() + ", got " + hash);

        workplace.reportProgress("Verified " + bytes + " bytes");

        return new FileVerificationResult(request.file(), bytes, hash);

    }

}