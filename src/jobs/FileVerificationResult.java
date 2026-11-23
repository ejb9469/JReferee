package jobs;

import java.nio.file.Path;

public record FileVerificationResult(Path file, long bytes, String sha256) implements JobResult { }