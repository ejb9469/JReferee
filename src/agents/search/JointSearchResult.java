package agents.search;

import agents.generation.ActionDistribution;
import domain.Nation;

import java.util.*;

/**
 * Results for every nation in one fixed-pool solve.
 *
 * <p>Playing policies and population mixtures use the selected output.
 * Q and temperatures always describe the completed search updates.
 * Average policies are stored separately for every tracked lambda.
 * Final policies can be reconstructed from Q and temperature.</p>
 */
public record JointSearchResult(
        Map<Nation, SearchResult> results,
        Map<Nation, ActionDistribution> populationPolicies,
        Map<Nation, Map<Double, Double>> temperatures,
        Map<Nation, Map<Double, ActionDistribution>> averagePolicies,
        PolicyOutput output
) {

    public JointSearchResult {
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(populationPolicies, "populationPolicies");
        Objects.requireNonNull(temperatures, "temperatures");
        Objects.requireNonNull(averagePolicies, "averagePolicies");
        Objects.requireNonNull(output, "output");
        if (results.isEmpty() || !results.keySet().equals(populationPolicies.keySet()))
            throw new IllegalArgumentException("Result and population nations must match");
        if (!temperatures.isEmpty() && !temperatures.keySet().equals(results.keySet()))
            throw new IllegalArgumentException("Temperature nations must match the results");
        if ((!averagePolicies.isEmpty() || output == PolicyOutput.AVERAGE)
                && !averagePolicies.keySet().equals(results.keySet()))
            throw new IllegalArgumentException("Average-policy nations must match the results");
        Map<Nation, SearchResult> copiedResults = new EnumMap<>(Nation.class);
        Map<Nation, ActionDistribution> copiedPolicies = new EnumMap<>(Nation.class);
        Map<Nation, Map<Double, Double>> copiedTemperatures = new EnumMap<>(Nation.class);
        Map<Nation, Map<Double, ActionDistribution>> copiedAverages = new EnumMap<>(Nation.class);
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
            if (!averagePolicies.isEmpty()) {
                Map<Double, ActionDistribution> types = new LinkedHashMap<>(
                        Objects.requireNonNull(averagePolicies.get(nation), "averages for " + nation));
                if (!copiedTemperatures.containsKey(nation)
                        || !types.keySet().equals(copiedTemperatures.get(nation).keySet()))
                    throw new IllegalArgumentException("Average and temperature types must match");
                for (ActionDistribution policy : types.values()) {
                    Objects.requireNonNull(policy, "average policy");
                    if (!policy.candidates().equals(result.distribution().candidates()))
                        throw new IllegalArgumentException("Average-policy candidates must align");
                    for (int action = 0; action < policy.candidates().size(); action++)
                        if (policy.candidates().get(action).logPrior() == Double.NEGATIVE_INFINITY
                                && policy.probabilities().get(action) != 0)
                            throw new IllegalArgumentException("Zero-prior actions cannot have average mass");
                }
                copiedAverages.put(nation, Collections.unmodifiableMap(types));
            }
        }
        results = Collections.unmodifiableMap(copiedResults);
        populationPolicies = Collections.unmodifiableMap(copiedPolicies);
        temperatures = Collections.unmodifiableMap(copiedTemperatures);
        averagePolicies = Collections.unmodifiableMap(copiedAverages);
    }

    /**
     * Retains construction of final policies without recorded averages.
     */
    public JointSearchResult(Map<Nation, SearchResult> results,
                             Map<Nation, ActionDistribution> populationPolicies,
                             Map<Nation, Map<Double, Double>> temperatures) {
        this(results, populationPolicies, temperatures, Map.of(), PolicyOutput.FINAL);
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

    /**
     * Returns one type's policy in the selected output mode.
     */
    public ActionDistribution policy(Nation nation, double lambda) {
        return policy(nation, lambda, output);
    }

    private ActionDistribution policy(Nation nation, double lambda, PolicyOutput choice) {
        double kappa = temperature(nation, lambda);
        if (choice == PolicyOutput.AVERAGE) {
            Map<Double, ActionDistribution> types = averagePolicies.get(nation);
            if (types == null || !types.containsKey(lambda))
                throw new IllegalArgumentException("No recorded average for " + nation + " at lambda " + lambda);
            return types.get(lambda);
        }
        SearchResult result = results.get(nation);
        return DiLPiKLSearch.policy(result.distribution().candidates(), result.meanValues(), lambda, kappa);
    }

    /**
     * Selects another output from this same solve, without sampling or evaluation.
     * The supplied profile must match the recorded playing and population policies.
     */
    public JointSearchResult withOutput(PolicyOutput choice, SearchProfile profile) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(profile.temperature(), "temperature");
        Set<Double> lambdas = new LinkedHashSet<>(profile.population().values());
        lambdas.add(profile.agentLambda());
        for (Nation nation : results.keySet())
            for (double lambda : lambdas)
                if (temperature(nation, lambda) != 0 && profile.temperature() == TemperatureSchedule.ZERO)
                    throw new IllegalArgumentException("Profile temperature differs from the solve");
        return withOutput(choice, profile.population(), profile.agentLambda());
    }

    JointSearchResult withOutput(PolicyOutput choice, LambdaDistribution population, double agentLambda) {
        Objects.requireNonNull(choice, "output");
        Objects.requireNonNull(population, "population");
        LambdaDistribution.requirePositive(agentLambda);
        Map<Nation, SearchResult> selected = new EnumMap<>(Nation.class);
        Map<Nation, ActionDistribution> mixtures = new EnumMap<>(Nation.class);
        for (Nation nation : results.keySet()) {
            SearchResult result = results.get(nation);
            requireSame(policy(nation, agentLambda, output), result.distribution());
            requireSame(mixture(nation, population, output), populationPolicies.get(nation));
            selected.put(nation, new SearchResult(policy(nation, agentLambda, choice),
                    result.meanValues(), result.samplesPerCandidate()));
            mixtures.put(nation, mixture(nation, population, choice));
        }
        return choice == output ? this
                : new JointSearchResult(selected, mixtures, temperatures, averagePolicies, choice);
    }

    private ActionDistribution mixture(Nation nation, LambdaDistribution population, PolicyOutput choice) {
        Map<Double, ActionDistribution> types = new LinkedHashMap<>();
        for (double lambda : population.values())
            types.put(lambda, policy(nation, lambda, choice));
        return mixture(types, population);
    }

    static ActionDistribution mixture(Map<Double, ActionDistribution> types, LambdaDistribution population) {
        var candidates = types.get(population.values().getFirst()).candidates();
        double[] mixture = new double[candidates.size()];
        for (int type = 0; type < population.values().size(); type++) {
            double weight = population.probabilities().get(type);
            if (weight == 0)
                continue;
            List<Double> probabilities = types.get(population.values().get(type)).probabilities();
            for (int action = 0; action < mixture.length; action++)
                mixture[action] += weight * probabilities.get(action);
        }
        double total = Arrays.stream(mixture).sum();
        return new ActionDistribution(candidates, Arrays.stream(mixture).map(p -> p / total).boxed().toList());
    }

    private static void requireSame(ActionDistribution actual, ActionDistribution expected) {
        for (int action = 0; action < actual.probabilities().size(); action++)
            if (Math.abs(actual.probabilities().get(action) - expected.probabilities().get(action)) > 1e-10)
                throw new IllegalArgumentException("Profile does not match the solved policies");
    }

}