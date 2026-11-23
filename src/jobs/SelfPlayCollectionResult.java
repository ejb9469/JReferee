package jobs;

import java.nio.file.Path;
import java.util.*;

/**
 * Verified worker results in planned batch order (not completion order).
 */
public record SelfPlayCollectionResult(
        Path directory, List<SelfPlayResult> batches, FileVerificationResult manifest
) {

    public SelfPlayCollectionResult {
        Objects.requireNonNull(directory, "directory");
        batches = List.copyOf(batches);
        Objects.requireNonNull(manifest, "manifest");
    }

}