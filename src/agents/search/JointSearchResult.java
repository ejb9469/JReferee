package agents.search;

import agents.generation.ActionDistribution;
import domain.Nation;

import java.util.*;

/**
 * Final-Q results for every nation in one fixed-pool solve.
 *
 * <p>Results use agentLambda. Population policies mix the final-Q policies
 * of the population's lambda types. Neither map is a historical average.
 * Temperatures record the completed kappa for each nation and lambda,
 * including agentLambda when it is absent from the population.</p>
 */
public record JointSearchResult(
        Map<Nation, SearchResult> results,
        Map<Nation, ActionDistribution> populationPolicies,
        Map<Nation, Map<Double, Double>> temperatures
) {

    public JointSearchResult {
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(populationPolicies, "populationPolicies");
        Objects.requireNonNull(temperatures, "temperatures");
        if (results.isEmpty() || !results.keySet().equals(populationPolicies.keySet()))
            throw new IllegalArgumentException("Result and population nations must match");
        if (!temperatures.isEmpty() && !temperatures.keySet().equals(results.keySet()))
            throw new IllegalArgumentException("Temperature nations must match the results");
        Map<Nation, SearchResult> copiedResults = new EnumMap<>(Nation.class);
        Map<Nation, ActionDistribution> copiedPolicies = new EnumMap<>(Nation.class);
        Map<Nation, Map<Double, Double>> copiedTemperatures = new EnumMap<>(Nation.class);
        for (Nation nation : results.keySet()) {
            Objects.requireNonNull(nation, "nation");
            SearchResult result = Objects.requireNonNull(results.get(nation), "result");
            ActionDistribution population = Objects.requireNonNull(populationPolicies.get(nation), "population");
            if (result.distribution().power() != nation
                    || !result.distribution().candidates().equals(population.candidates()))
                throw new IllegalArgumentException("Result and population candidates must align");
            copiedResults.put(nation, result);
            copiedPolicies.put(nation, population);
            if (!temperatures.isEmpty()) {
                Map<Double, Double> types = new LinkedHashMap<>(
                        Objects.requireNonNull(temperatures.get(nation), "temperatures for " + nation));
                if (types.isEmpty())
                    throw new IllegalArgumentException("Missing lambda temperatures for " + nation);
                types.forEach((lambda, kappa) -> {
                    LambdaDistribution.requirePositive(Objects.requireNonNull(lambda, "lambda"));
                    if (!Double.isFinite(Objects.requireNonNull(kappa, "kappa")) || kappa < 0)
                        throw new IllegalArgumentException("Final temperatures must be finite and nonnegative");
                });
                copiedTemperatures.put(nation, Collections.unmodifiableMap(types));
            }
        }
        results = Collections.unmodifiableMap(copiedResults);
        populationPolicies = Collections.unmodifiableMap(copiedPolicies);
        temperatures = Collections.unmodifiableMap(copiedTemperatures);
    }

    /**
     * Retains construction of results without temperature diagnostics.
     */
    public JointSearchResult(Map<Nation, SearchResult> results,
                             Map<Nation, ActionDistribution> populationPolicies) {
        this(results, populationPolicies, Map.of());
    }

    public double temperature(Nation nation, double lambda) {
        Objects.requireNonNull(nation, "nation");
        LambdaDistribution.requirePositive(lambda);
        Map<Double, Double> types = temperatures.get(nation);
        if (types == null || !types.containsKey(lambda))
            throw new IllegalArgumentException("No recorded temperature for " + nation + " at lambda " + lambda);
        return types.get(lambda);
    }

}