package io.training;

import agents.policy.LinearMovementPolicy;
import agents.encoding.EncodingPurpose;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Bounded-buffer shuffling, sparse SGD, and validation-based model selection.
 * The test split is never opened here.
 */
public class PolicyTraining {

    private PolicyTraining() { }

    public record Settings(int epochs, int dimensions, double learningRate, int shuffleBuffer, long shuffleSeed) {
        public Settings {
            if (epochs < 1 || shuffleBuffer < 1 || !Double.isFinite(learningRate) || learningRate <= 0)
                throw new IllegalArgumentException("Epochs, shuffle buffer, and finite learning rate must be positive");
            if (dimensions < 1024 || dimensions > 4_194_304 || Integer.bitCount(dimensions) != 1)
                throw new IllegalArgumentException("Dimensions must be a power of two from 1024 to 4194304");
        }
        public Settings() { this(3, LinearMovementPolicy.DEFAULT_DIMENSIONS, 0.05, 256, 7); }
        public String toJson() {
            return "{\"epochs\":" + epochs + ",\"dimensions\":" + dimensions + ",\"learning_rate\":" + learningRate
                    + ",\"shuffle_buffer\":" + shuffleBuffer + ",\"shuffle_seed\":" + shuffleSeed + "}";
        }
    }

    public record Epoch(int number, double learningRate, double onlineOrderNll,
                        MovementDataset.Statistics reading, long elapsedNanos, PolicyEvaluation.Result validation) {
        public String toJson() {
            return "{\"epoch\":" + number + ",\"learning_rate\":" + learningRate + ",\"online_order_nll\":" + onlineOrderNll
                    + ",\"training_performance\":" + PolicyEvaluation.performanceJson(reading, elapsedNanos)
                    + ",\"validation\":" + validation.toJson() + "}";
        }
    }

    public record Result(LinearMovementPolicy model, int bestEpoch, PolicyEvaluation.Result initialValidation,
                         List<Epoch> epochs) {
        public Result { epochs = List.copyOf(epochs); }
        public PolicyEvaluation.Result bestValidation() { return epochs.get(bestEpoch - 1).validation(); }
        public String toJson() {
            return "{\"best_epoch\":" + bestEpoch + ",\"initial_validation\":" + initialValidation.toJson()
                    + ",\"epochs\":[" + String.join(",", epochs.stream().map(Epoch::toJson).toList()) + "]}";
        }
    }

    public static Result fit(MovementDataset dataset, Settings settings, Consumer<Epoch> observer) throws IOException {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(observer, "observer");
        if (dataset.examples(EncodingPurpose.TRAIN) == 0 || dataset.examples(EncodingPurpose.VALIDATION) == 0)
            throw new IllegalArgumentException("Training and validation splits must both contain examples");
        var learner = new LinearMovementPolicy.Learner(settings.dimensions());
        var initial = PolicyEvaluation.evaluate(dataset, EncodingPurpose.VALIDATION, learner.snapshot());
        LinearMovementPolicy best = null;
        double bestLoss = Double.POSITIVE_INFINITY;
        int bestEpoch = 0;
        List<Epoch> epochs = new ArrayList<>();
        for (int number = 1; number <= settings.epochs(); number++) {
            long start = System.nanoTime(), examples = 0, orders = 0;
            double loss = 0, rate = settings.learningRate() / Math.sqrt(number);
            Random random = new Random(settings.shuffleSeed() + number - 1);
            MovementDataset.Statistics reading;
            try (var cursor = dataset.open(EncodingPurpose.TRAIN)) {
                List<MovementDataset.Example> buffer = new ArrayList<>();
                MovementDataset.Example next;
                while (buffer.size() < settings.shuffleBuffer() && (next = cursor.next()) != null)
                    buffer.add(next);
                while (!buffer.isEmpty()) {
                    int index = random.nextInt(buffer.size());
                    var example = buffer.get(index);
                    next = cursor.next();
                    if (next != null) buffer.set(index, next);
                    else {
                        var last = buffer.removeLast();
                        if (index < buffer.size()) buffer.set(index, last);
                    }
                    loss += learner.learn(example.input(), example.action(), rate);
                    examples++;
                    orders += example.action().size();
                }
                reading = cursor.statistics();
            }
            if (examples != dataset.examples(EncodingPurpose.TRAIN) || !Double.isFinite(loss))
                throw new IOException("Training count or loss is invalid");
            long elapsed = System.nanoTime() - start;
            LinearMovementPolicy model = learner.snapshot();
            var validation = PolicyEvaluation.evaluate(dataset, EncodingPurpose.VALIDATION, model);
            if (validation.overall().orderNll() < bestLoss) {
                best = model;
                bestLoss = validation.overall().orderNll();
                bestEpoch = number;
            }
            Epoch epoch = new Epoch(number, rate, loss / orders, reading, elapsed, validation);
            epochs.add(epoch);
            observer.accept(epoch);
        }
        return new Result(Objects.requireNonNull(best, "best model"), bestEpoch, initial, epochs);
    }
}