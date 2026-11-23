package jobs;

import java.nio.file.Path;
import java.util.*;

public record PolicyTrainingRequest(
        List<FileVerificationRequest> cacheManifests,
        FileVerificationRequest initialPolicy, FileVerificationRequest anchor,
        long splitSeed, PolicyTrainingSettings settings, Path directory
) implements JobRequest<PolicyTrainingResult> {

    public PolicyTrainingRequest {
        cacheManifests = List.copyOf(cacheManifests);
        Objects.requireNonNull(initialPolicy, "initialPolicy");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(settings, "settings");
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (cacheManifests.isEmpty() || cacheManifests.size() > 1024
                || cacheManifests.stream().anyMatch(file ->
                !file.file().getFileName().toString().equals("manifest.json")))
            throw new IllegalArgumentException("Supply 1..1024 explicitly approved cache/manifest.json receipts");
    }

}