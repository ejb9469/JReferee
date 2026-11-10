package agents.policy;

import agents.encoding.MovementEncoding;
import agents.encoding.MovementInput;
import domain.Nation;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Factorized masked softmax over a small set of hashed board/order features.
 * Learners are mutable; snapshots and prepared policies are immutable.
 */
public final class LinearMovementPolicy implements MovementPolicy {

    public static final String SCHEMA = "jreferee-linear-policy-v1";
    public static final String FEATURES = "jreferee-linear-features-v1";
    public static final int DEFAULT_DIMENSIONS = 65_536;
    private static final int FEATURE_COUNT = 12;
    private final double[] weights;

    public LinearMovementPolicy(int dimensions) {
        requireDimensions(dimensions);
        weights = new double[dimensions];
    }

    private LinearMovementPolicy(double[] weights) {
        this.weights = weights.clone();
    }

    public int dimensions() { return weights.length; }

    @Override
    public Prepared prepare(MovementInput input) {
        Objects.requireNonNull(input, "input");
        double[][] probabilities = new double[input.slots().size()][];
        int[] features = new int[FEATURE_COUNT];
        for (int slot = 0; slot < probabilities.length; slot++) {
            var options = input.slots().get(slot).options();
            double[] scores = new double[options.size()];
            for (int option = 0; option < scores.length; option++) {
                features(input, options.get(option), weights.length, features, 0);
                for (int feature : features) scores[option] += weights[feature];
            }
            normalize(scores, scores.length);
            probabilities[slot] = scores;
        }
        return new Prepared() {
            @Override
            public MovementInput input() { return input; }
            @Override
            public double[] logProbabilities(List<Integer> prefix) {
                input.requirePrefix(prefix);
                if (prefix.size() == probabilities.length)
                    throw new IllegalArgumentException("The action is already complete");
                return probabilities[prefix.size()].clone();
            }
            @Override
            public double logProbability(List<Integer> action) {
                input.requireAction(action);
                double result = 0;
                for (int slot = 0; slot < action.size(); slot++) result += probabilities[slot][action.get(slot)];
                return result;
            }
        };
    }

    public void save(Path path) throws IOException {
        MessageDigest checksum = digest();
        try (DigestOutputStream checked = new DigestOutputStream(new BufferedOutputStream(
                Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)), checksum);
             DataOutputStream out = new DataOutputStream(checked)) {
            out.writeUTF(SCHEMA);
            out.writeUTF(MovementEncoding.SCHEMA);
            out.writeUTF(FEATURES);
            out.writeInt(weights.length);
            for (double weight : weights) out.writeDouble(weight);
            out.flush();
            checked.on(false);
            out.write(checksum.digest());
        }
    }

    public static LinearMovementPolicy load(Path path) throws IOException {
        MessageDigest checksum = digest();
        try (DigestInputStream checked = new DigestInputStream(new BufferedInputStream(Files.newInputStream(path)), checksum);
             DataInputStream in = new DataInputStream(checked)) {
            if (!SCHEMA.equals(in.readUTF()) || !MovementEncoding.SCHEMA.equals(in.readUTF())
                    || !FEATURES.equals(in.readUTF()))
                throw new IOException("Unsupported policy, encoding, or feature schema: " + path);
            int dimensions = in.readInt();
            try { requireDimensions(dimensions); }
            catch (IllegalArgumentException exception) { throw new IOException(exception.getMessage(), exception); }
            double[] weights = new double[dimensions];
            for (int index = 0; index < weights.length; index++) {
                weights[index] = in.readDouble();
                if (!Double.isFinite(weights[index])) throw new IOException("Nonfinite policy weight: " + path);
            }
            checked.on(false);
            byte[] expected = in.readNBytes(32);
            if (!MessageDigest.isEqual(checksum.digest(), expected) || in.read() != -1)
                throw new IOException("Policy checksum or file length is invalid: " + path);
            return new LinearMovementPolicy(weights);
        } catch (EOFException exception) {
            throw new IOException("Truncated policy file: " + path, exception);
        }
    }

    public static final class Learner {
        private final double[] weights;
        private int[] features = new int[0];
        private double[] scores = new double[0];

        public Learner(int dimensions) {
            requireDimensions(dimensions);
            weights = new double[dimensions];
        }

        public LinearMovementPolicy snapshot() { return new LinearMovementPolicy(weights); }

        /**
         * Applies one SGD update per observed order and returns the sum of
         * their pre-update losses. Feature and score buffers are reused.
         */
        public double learn(MovementInput input, List<Integer> action, double rate) {
            input.requireAction(action);
            if (!Double.isFinite(rate) || rate <= 0)
                throw new IllegalArgumentException("Learning rate must be finite and positive");
            double loss = 0;
            for (int slot = 0; slot < action.size(); slot++) {
                var options = input.slots().get(slot).options();
                int size = options.size();
                if (scores.length < size) {
                    scores = new double[size];
                    features = new int[Math.multiplyExact(size, FEATURE_COUNT)];
                }
                Arrays.fill(scores, 0, size, 0);
                for (int option = 0; option < size; option++) {
                    int offset = option * FEATURE_COUNT;
                    features(input, options.get(option), weights.length, features, offset);
                    for (int index = 0; index < FEATURE_COUNT; index++) scores[option] += weights[features[offset + index]];
                }
                normalize(scores, size);
                loss -= scores[action.get(slot)];
                for (int option = 0; option < size; option++) {
                    double update = rate * ((option == action.get(slot) ? 1 : 0) - Math.exp(scores[option]));
                    for (int index = 0; index < FEATURE_COUNT; index++) {
                        int feature = features[option * FEATURE_COUNT + index];
                        weights[feature] += update;
                        if (!Double.isFinite(weights[feature]))
                            throw new IllegalStateException("Nonfinite weight; reduce the learning rate");
                    }
                }
            }
            return loss;
        }
    }

    private static void features(MovementInput input, MovementInput.Option option,
                                 int dimensions, int[] output, int offset) {
        int label = option.label().hashCode(), kind = option.type().name().hashCode();
        int season = input.moment().gamePhase().name().hashCode();
        var destination = option.destination();
        var occupant = input.unitAt(destination);
        var referenced = input.unitAt(option.target());
        output[offset] = bucket(0, label, 0, dimensions);
        output[offset + 1] = bucket(1, input.power().name().hashCode(), label, dimensions);
        output[offset + 2] = bucket(2, season, label, dimensions);
        output[offset + 3] = bucket(3, kind, 0, dimensions);
        output[offset + 4] = bucket(4, option.unitType().name().hashCode(), kind, dimensions);
        output[offset + 5] = bucket(5, season, kind, dimensions);
        output[offset + 6] = bucket(6, kind, destination.supplyCenter ? 1 : 0, dimensions);
        output[offset + 7] = bucket(7, kind, relation(input.power(), input.centerOwner(destination)), dimensions);
        output[offset + 8] = bucket(8, kind, relation(input.power(), occupant == null ? null : occupant.owner()), dimensions);
        output[offset + 9] = bucket(9, kind, occupant == null ? 0 : occupant.type().name().hashCode(), dimensions);
        output[offset + 10] = bucket(10, kind, relation(input.power(), referenced == null ? null : referenced.owner()), dimensions);
        output[offset + 11] = bucket(11, kind, Math.min(8, Math.max(0, input.moment().year() - 1901)), dimensions);
    }

    private static int relation(Nation power, Nation other) {
        return other == null ? 0 : power == other ? 1 : 2;
    }

    private static int bucket(int family, int first, int second, int dimensions) {
        int hash = family * 0x9e3779b9 + first;
        hash = Integer.rotateLeft(hash, 13) ^ second;
        hash ^= hash >>> 16;
        hash *= 0x85ebca6b;
        hash ^= hash >>> 13;
        return hash & (dimensions - 1);
    }

    private static void normalize(double[] scores, int size) {
        double max = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < size; index++) {
            if (!Double.isFinite(scores[index])) throw new IllegalStateException("Nonfinite policy score");
            max = Math.max(max, scores[index]);
        }
        double sum = 0;
        for (int index = 0; index < size; index++) sum += Math.exp(scores[index] - max);
        double logSum = Math.log(sum);
        for (int index = 0; index < size; index++) {
            scores[index] = (scores[index] - max) - logSum;
            if (!Double.isFinite(scores[index])) throw new IllegalStateException("Nonfinite policy log probability");
        }
    }

    private static void requireDimensions(int dimensions) {
        if (dimensions < 1024 || dimensions > 4_194_304 || Integer.bitCount(dimensions) != 1)
            throw new IllegalArgumentException("Feature dimensions must be a power of two from 1024 to 4194304");
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}