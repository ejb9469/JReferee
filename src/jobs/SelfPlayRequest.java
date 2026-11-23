package jobs;

import java.nio.file.Path;
import java.util.*;

public record SelfPlayRequest(
        Optional<FileVerificationRequest> value,
        FileVerificationRequest anchor, FileVerificationRequest policy,
        int lastYear, int candidates, int iterations,
        long firstSeed, int games, long splitSeed, Path directory
) implements JobRequest<SelfPlayResult> {

    public SelfPlayRequest {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(policy, "policy");
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (lastYear < 1902 || candidates < 1 || iterations < 1 || games < 1 || games > 128)
            throw new IllegalArgumentException("Invalid year, search budget or batch size");
        Math.addExact(firstSeed, games - 1L);
    }

}