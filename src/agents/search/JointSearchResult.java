package agents.search;

import agents.generation.ActionDistribution;
import domain.Nation;

import java.util.*;

/**
 * Final-Q results for every nation in one fixed-pool solve.
 *
 * <p>Results use agentLambda. Population policies mix the final-Q policies
 * of the population's lambda types. Neither map is a historical average.</p>
 */
public record JointSearchResult(
        Map<Nation, SearchResult> results,
        Map<Nation, ActionDistribution> populationPolicies
) {
    public JointSearchResult {
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(populationPolicies, "populationPolicies");
        if (results.isEmpty() || !results.keySet().equals(populationPolicies.keySet()))
            throw new IllegalArgumentException("Result and population nations must match");
        Map<Nation, SearchResult> copiedResults = new EnumMap<>(Nation.class);
        Map<Nation, ActionDistribution> copiedPolicies = new EnumMap<>(Nation.class);
        for (Nation nation : results.keySet()) {
            Objects.requireNonNull(nation, "nation");
            SearchResult result = Objects.requireNonNull(results.get(nation), "result");
            ActionDistribution population = Objects.requireNonNull(populationPolicies.get(nation), "population");
            if (result.distribution().power() != nation
                    || !result.distribution().candidates().equals(population.candidates()))
                throw new IllegalArgumentException("Result and population candidates must align");
            copiedResults.put(nation, result);
            copiedPolicies.put(nation, population);
        }
        results = Collections.unmodifiableMap(copiedResults);
        populationPolicies = Collections.unmodifiableMap(copiedPolicies);
    }
}