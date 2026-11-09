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
 * Final policies can be reconstructed from Q and temperature.
 * The original solve settings are retained with the result.</p>
 */
public record JointSearchResult(
        Map<Nation, SearchResult> results,
        Map<Nation, ActionDistribution> populationPolicies,
        Map<Nation, Map<Double, Double>> temperatures,
        Map<Nation, Map<Double, ActionDistribution>> averagePolicies,
        Settings settings,
        PolicyOutput output
) {

    /**
     * Immutable lambda population, playing type, and temperature schedule.
     */
    public record Settings(LambdaDistribution population, double agentLambda,
                           TemperatureSchedule temperature) implements SearchProfile {
        public Settings {
            Objects.requireNonNull(population, "population");
            Objects.requireNonNull(temperature, "temperature");
            LambdaDistribution.requirePositive(agentLambda);
        }

        public Settings(SearchProfile profile) {
            this(Objects.requireNonNull(profile, "profile").population(),
                    profile.agentLambda(), profile.temperature());
        }
    }

    public JointSearchResult {
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(populationPolicies, "populationPolicies");
        Objects.requireNonNull(temperatures, "temperatures");
        Objects.requireNonNull(averagePolicies, "averagePolicies");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(output, "output");
        if (results.isEmpty() || !results.keySet().equals(populationPolicies.keySet()))
            throw new IllegalArgumentException("Result and population nations must match");
        if (!temperatures.keySet().equals(results.keySet()))
            throw new IllegalArgumentException("Temperature nations must match the results");
        if ((!averagePolicies.isEmpty() || output == PolicyOutput.AVERAGE)
                && !averagePolicies.keySet().equals(results.keySet()))
            throw new IllegalArgumentException("Average-policy nations must match the results");
        Map<Nation, SearchResult> copiedResults = new EnumMap<>(Nation.class);
        Map<Nation, ActionDistribution> copiedPolicies = new EnumMap<>(Nation.class);
        Map<Nation, Map<Double, Double>> copiedTemperatures = new EnumMap<>(Nation.class);
        Map<Nation, Map<Double, ActionDistribution>> copiedAverages = new EnumMap<>(Nation.class);
        Set<Double> lambdas = new LinkedHashSet<>(settings.population().values());
        lambdas.add(settings.agentLambda());
        for (Nation nation : results.keySet()) {
            Objects.requireNonNull(nation, "nation");
            SearchResult result = Objects.requireNonNull(results.get(nation), "result");
            ActionDistribution population = Objects.requireNonNull(populationPolicies.get(nation), "population");
            if (result.distribution().power() != nation
                    || !result.distribution().candidates().equals(population.candidates()))
                throw new IllegalArgumentException("Result and population candidates must align");
            copiedResults.put(nation, result);
            copiedPolicies.put(nation, population);
            Map<Double, Double> recorded = new LinkedHashMap<>(
                    Objects.requireNonNull(temperatures.get(nation), "temperatures for " + nation));
            if (!recorded.keySet().equals(lambdas))
                throw new IllegalArgumentException("Temperature types must match the solve settings");
            for (double kappa : recorded.values()) {
                if (!Double.isFinite(kappa) || kappa < 0)
                    throw new IllegalArgumentException("Final temperatures must be finite and nonnegative");
                if (settings.temperature() == TemperatureSchedule.ZERO && kappa != 0)
                    throw new IllegalArgumentException("Zero-temperature settings require zero temperatures");
            }
            copiedTemperatures.put(nation, Collections.unmodifiableMap(recorded));
            if (!averagePolicies.isEmpty()) {
                Map<Double, ActionDistribution> types = new LinkedHashMap<>(
                        Objects.requireNonNull(averagePolicies.get(nation), "averages for " + nation));
                if (!types.keySet().equals(lambdas))
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
        for (Nation nation : results.keySet()) {
            Map<Double, ActionDistribution> types = policies(results.get(nation), temperatures.get(nation),
                    averagePolicies.getOrDefault(nation, Map.of()), output);
            requireSame(types.get(settings.agentLambda()), results.get(nation).distribution());
            requireSame(mixture(types, settings.population()), populationPolicies.get(nation));
        }
    }

    /**
     * Constructs final policies without recorded averages.
     * The solve settings and all tracked temperatures are still required.
     */
    public JointSearchResult(Map<Nation, SearchResult> results,
                             Map<Nation, ActionDistribution> populationPolicies,
                             Map<Nation, Map<Double, Double>> temperatures, Settings settings) {
        this(results, populationPolicies, temperatures, Map.of(), settings, PolicyOutput.FINAL);
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
        double kappa = temperature(nation, lambda);
        if (output == PolicyOutput.AVERAGE) {
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
     */
    public JointSearchResult withOutput(PolicyOutput choice) {
        Objects.requireNonNull(choice, "output");
        if (choice == output)
            return this;
        Map<Nation, SearchResult> selected = new EnumMap<>(Nation.class);
        Map<Nation, ActionDistribution> mixtures = new EnumMap<>(Nation.class);
        for (Nation nation : results.keySet()) {
            SearchResult result = results.get(nation);
            Map<Double, ActionDistribution> types = policies(result, temperatures.get(nation),
                    averagePolicies.getOrDefault(nation, Map.of()), choice);
            selected.put(nation, new SearchResult(types.get(settings.agentLambda()),
                    result.meanValues(), result.samplesPerCandidate()));
            mixtures.put(nation, mixture(types, settings.population()));
        }
        return new JointSearchResult(selected, mixtures, temperatures, averagePolicies, settings, choice);
    }

    /**
     * Checks an explicitly supplied profile before selecting the output.
     */
    public JointSearchResult withOutput(PolicyOutput choice, SearchProfile profile) {
        requireProfile(profile);
        return withOutput(choice);
    }

    void requireProfile(SearchProfile profile) {
        if (!settings.equals(new Settings(profile)))
            throw new IllegalArgumentException("Profile differs from the recorded search settings");
    }

    private static Map<Double, ActionDistribution> policies(SearchResult result, Map<Double, Double> temperatures,
                                                            Map<Double, ActionDistribution> averages,
                                                            PolicyOutput choice) {
        if (choice == PolicyOutput.AVERAGE) {
            if (averages.isEmpty())
                throw new IllegalArgumentException("No recorded average policies");
            return averages;
        }
        Map<Double, ActionDistribution> types = new LinkedHashMap<>();
        temperatures.forEach((lambda, kappa) -> types.put(lambda,
                DiLPiKLSearch.policy(result.distribution().candidates(), result.meanValues(), lambda, kappa)));
        return types;
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
                throw new IllegalArgumentException("Result policies do not match the recorded settings");
    }

}