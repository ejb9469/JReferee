package jobs;

import java.nio.file.Path;

public record PolicyTrainingResult(
        Path directory, Path verificationLog, Path trainingLog,
        FileVerificationResult checkpoint, FileVerificationResult trainingReport,
        int selectedEpoch, double validationCrossEntropy
) implements JobResult { }