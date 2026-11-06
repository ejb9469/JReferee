package agents.search;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * A finite population of positive KL-regularization strengths.
 *
 * <p>Probabilities describe beliefs about player types. They are separate from
 * action probabilities. A single value gives the fixed-lambda piKL case.</p>
 */
public record LambdaDistribution(List<Double> values, List<Double> probabilities) {

    public LambdaDistribution {
        values = List.copyOf(Objects.requireNonNull(values, "values"));
        probabilities = List.copyOf(Objects.requireNonNull(probabilities, "probabilities"));
        if (values.isEmpty() || values.size() != probabilities.size())
            throw new IllegalArgumentException("Lambda values and probabilities must have equal, nonzero sizes");
        if (new HashSet<>(values).size() != values.size())
            throw new IllegalArgumentException("Lambda values must be distinct");
        double total = 0.0;
        for (int index = 0; index < values.size(); index++) {
            requirePositive(values.get(index));
            double probability = probabilities.get(index);
            if (!Double.isFinite(probability) || probability < 0.0 || probability > 1.0)
                throw new IllegalArgumentException("Invalid type probability: " + probability);
            total += probability;
        }
        if (Math.abs(total - 1.0) > 1e-10)
            throw new IllegalArgumentException("Type probabilities must sum to one");
    }

    public static LambdaDistribution fixed(double lambda) {
        return new LambdaDistribution(List.of(lambda), List.of(1.0));
    }

    public static LambdaDistribution uniform(double... lambdas) {
        Objects.requireNonNull(lambdas, "lambdas");
        if (lambdas.length == 0)
            throw new IllegalArgumentException("At least one lambda is required");
        return new LambdaDistribution(Arrays.stream(lambdas).boxed().toList(),
                Collections.nCopies(lambdas.length, 1.0 / lambdas.length));
    }

    public double sample(Random random) {
        Objects.requireNonNull(random, "random");
        double total = 0.0;
        int lastPositive = 0;
        for (int index = 0; index < probabilities.size(); index++) {
            total += probabilities.get(index);
            if (probabilities.get(index) > 0.0)
                lastPositive = index;
        }
        double ticket = random.nextDouble() * total;
        for (int index = 0; index < lastPositive; index++) {
            ticket -= probabilities.get(index);
            if (ticket < 0.0)
                return values.get(index);
        }
        return values.get(lastPositive);
    }

    static void requirePositive(double lambda) {
        if (!Double.isFinite(lambda) || lambda <= 0.0)
            throw new IllegalArgumentException("Lambda must be finite and positive: " + lambda);
    }

}