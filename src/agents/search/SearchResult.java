package agents.search;

import agents.generation.ActionDistribution;

import java.util.List;
import java.util.Objects;

/**
 * One nation's selected policy and its per-candidate raw payoff estimates.
 *
 * <p>Mean values follow distribution.candidates() order and use the evaluator's
 * payoff scale, without a search regularization penalty. BestResponseSearch measures fixed
 * anchor opponents; DiLPiKLSearch measures its sampled opponent history.
 * These values are not fresh estimates against the returned policies.</p>
 *
 * <p>The distribution is the policy to sample. It may maximize the means,
 * apply regularization, or average policies from successive iterations.
 * Mean values alone do not determine its probabilities.</p>
 *
 * <p>samplesPerCandidate counts root payoff observations included in each
 * mean. Cache hits still count; evaluator-internal rollouts do not add to it.
 * An adaptive result reports only its last fixed-pool solve, since each
 * expansion restarts the values. Total work belongs to the search diagnostics.</p>
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
                throw new IllegalArgumentException("BestResponseSearch values must be finite");
    }

}