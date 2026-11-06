package agents.generation;

import domain.Nation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Selection probabilities for an <i>ordered</i> pool of one nation's candidates.
 *
 * <p>The pool must contain distinct actions, as required by ActionGenerator.
 * Selection probabilities are separate from each candidate's anchor logPrior.
 * Both lists are immutable and use the same indexing.</p>
 */
public record ActionDistribution(
        List<ActionCandidate> candidates,
        List<Double> probabilities
) {

    public ActionDistribution {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        probabilities = List.copyOf(Objects.requireNonNull(probabilities, "probabilities"));
        if (candidates.isEmpty() || candidates.size() != probabilities.size())
            throw new IllegalArgumentException("Candidates and probabilities must have equal, nonzero sizes");

        Nation power = candidates.getFirst().power();
        double total = 0.0;
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).power() != power)
                throw new IllegalArgumentException("A distribution must contain only one nation's actions");
            double probability = probabilities.get(index);
            if (!Double.isFinite(probability) || probability < 0.0 || probability > 1.0)
                throw new IllegalArgumentException("Invalid selection probability: " + probability);
            total += probability;
        }
        if (Math.abs(total - 1.0) > 1e-10)
            throw new IllegalArgumentException("Selection probabilities must sum to one");
    }

    public Nation power() {
        return candidates.getFirst().power();
    }

    /**
     * Conditions anchor probabilities on the supplied candidate pool.
     * Subtracting the largest logPrior avoids underflow for small priors.
     * An entirely zero-prior pool cannot define a policy and is rejected.
     */
    public static ActionDistribution fromPriors(List<ActionCandidate> candidates) {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (candidates.isEmpty())
            throw new IllegalArgumentException("A prior distribution requires candidates");
        double maximum = Double.NEGATIVE_INFINITY;
        for (ActionCandidate candidate : candidates)
            maximum = Math.max(maximum, candidate.logPrior());
        if (!Double.isFinite(maximum))
            throw new IllegalStateException("No candidate has positive prior");

        List<Double> probabilities = new ArrayList<>(candidates.size());
        double total = 0.0;
        for (ActionCandidate candidate : candidates) {
            double weight = StrictMath.exp(candidate.logPrior() - maximum);
            probabilities.add(weight);
            total += weight;
        }
        for (int index = 0; index < probabilities.size(); index++)
            probabilities.set(index, probabilities.get(index) / total);
        return new ActionDistribution(candidates, probabilities);
    }

    /**
     * Samples one action without modifying either its orders or its logPrior.
     */
    public ActionCandidate sample(Random random) {
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
                return candidates.get(index);
        }
        // The final positive weight owns the remaining interval, including rounding at its edge.
        return candidates.get(lastPositive);
    }

}