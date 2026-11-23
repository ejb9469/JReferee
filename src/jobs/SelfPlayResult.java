package jobs;

import java.nio.file.Path;

public record SelfPlayResult(
        Path directory, Path log, String datasetFingerprint,
        FileVerificationResult batchManifest, FileVerificationResult cacheManifest
) implements JobResult { }