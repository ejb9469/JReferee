package agents.search;

import agents.generation.ActionDistribution;

import java.util.List;
import java.util.Objects;

/**
 * A search policy and its per-candidate value estimates.
 *
 * <p>Mean values follow distribution.candidates() order and use the evaluator's
 * payoff scale. Probabilities need not be derived directly from those values;
 * later regularized search can return a mixed policy in the same form.</p>
 */
public record SearchResult(
        ActionDistribution distribution,
        List<Double> meanValues,
        int samplesPerCandidate
) {

    public SearchResult {
        Objects.requireNonNull(distribution, "distribution");
        meanValues = List.copyOf(Objects.requireNonNull(meanValues, "meanValues"));
        if (meanValues.size() != distribution.candidates().size())
            throw new IllegalArgumentException("Every candidate must have a mean value");
        if (samplesPerCandidate < 1)
            throw new IllegalArgumentException("samplesPerCandidate must be at least 1");
        for (double value : meanValues)
            if (!Double.isFinite(value))
                throw new IllegalArgumentException("Search values must be finite");
    }

}