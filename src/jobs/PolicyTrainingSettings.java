package jobs;

import java.util.Map;

public record PolicyTrainingSettings(
        int epochs, int batchSize, int candidateBatchSize, int maxOptions,
        double learningRate, double weightDecay, double gradientClip, long seed, int threads
) {

    public PolicyTrainingSettings {
        if (epochs < 1 || epochs > 10000 || batchSize < 1 || batchSize > 4096
                || candidateBatchSize < 1 || candidateBatchSize > 4096
                || maxOptions < 1 || maxOptions > 1_000_000
                || seed < 0 || seed > Long.MAX_VALUE - 10000 || threads < 1 || threads > 256
                || !Double.isFinite(learningRate) || learningRate <= 0
                || !Double.isFinite(weightDecay) || weightDecay < 0
                || !Double.isFinite(gradientClip) || gradientClip <= 0)
            throw new IllegalArgumentException("Invalid policy training settings");
    }

    public static PolicyTrainingSettings standard() {
        return new PolicyTrainingSettings(3, 8, 32, 16384, 0.0001, 0.0001, 1.0, 7, 4);
    }

    Map<String, Object> values() {
        return Map.of(
                "epochs", (long) epochs, "batch_size", (long) batchSize,
                "candidate_batch_size", (long) candidateBatchSize, "max_options", (long) maxOptions,
                "learning_rate", learningRate, "weight_decay", weightDecay,
                "gradient_clip", gradientClip, "seed", seed, "threads", (long) threads, "device", "cpu");
    }

}