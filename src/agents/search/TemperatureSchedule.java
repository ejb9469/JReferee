package agents.search;

/**
 * Entropy temperature for the DiL-piKL policy.
 *
 * <p>Section 2.2 of arXiv:2210.05492 uses kappa_t = 3 S_t / (10 sqrt(t)).
 * ADAPTIVE uses the population standard deviation of each lambda type's
 * policy-averaged raw utility against the sampled opponents. Each completed
 * search iteration contributes one observation, including cache hits.</p>
 *
 * <p>The policy-averaged observation and uniform startup follow the public
 * Cicero implementation, in `bqre1p_agent.py` and `cfrstats.cc`. The paper does
 * not specify those details. Here observations have equal weights, matching
 * Algorithm 1's Q update. No variance floor or prior smoothing is added.</p>
 */
public enum TemperatureSchedule {

    ZERO,
    ADAPTIVE;

    /**
     * Returns the temperature after the given number of observations.
     * At t=0, ADAPTIVE uses the infinite-temperature limit: uniform over
     * positive-prior actions. One observation has zero population variance.
     */
    public double kappa(int observations, double standardDeviation) {
        if (observations < 0 || !Double.isFinite(standardDeviation) || standardDeviation < 0)
            throw new IllegalArgumentException("Invalid temperature statistics");
        if (this == ZERO)
            return 0.0;
        if (observations == 0)
            return Double.POSITIVE_INFINITY;
        return (3.0 / 10.0) * (standardDeviation / StrictMath.sqrt(observations));
    }

}