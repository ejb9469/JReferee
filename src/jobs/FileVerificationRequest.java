package jobs;

import java.nio.file.Path;
import java.util.*;

public record FileVerificationRequest(Path file, String expectedSha256)
        implements JobRequest<FileVerificationResult> {

    public FileVerificationRequest {
        file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256")
                .toLowerCase(Locale.ROOT);
        if (!expectedSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Expected a 64-digit SHA-256");
    }

}