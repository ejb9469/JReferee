package agents.search;

import agents.generation.ActionCandidate;
import agents.generation.ActionDistribution;
import domain.Nation;

import java.util.*;

/**
 * One nation's selected policy and fresh payoff checks at one lambda.
 *
 * <p>Checks cover the retained pool. Fresh values use the opponents' population
 * mixtures in the same output mode. Temperature is the final recorded kappa;
 * an averaged policy need not be a softmax at that temperature.
 * The evaluator must belong to the same unchanged root
 * and use the same payoff function as the solve.</p>
 */
public record TypeReceipt(
        Nation power, double lambda, double populationWeight, boolean playing,
        double temperature, int opponentSamples,
        List<Double> probabilities, List<Double> freshValues, List<Double> standardErrors,
        double responseResidual, double regularizedGain, double temperatureResidual,
        PolicyOutput output
) {

    public TypeReceipt {
        Objects.requireNonNull(power, "power");
        Objects.requireNonNull(output, "output");
        LambdaDistribution.requirePositive(lambda);
        probabilities = List.copyOf(probabilities);
        freshValues = List.copyOf(freshValues);
        standardErrors = List.copyOf(standardErrors);
        if (!Double.isFinite(populationWeight) || populationWeight < 0 || populationWeight > 1
                || opponentSamples < 0 || opponentSamples == 1
                || probabilities.isEmpty() || probabilities.size() != freshValues.size()
                || probabilities.size() != standardErrors.size())
            throw new IllegalArgumentException("Invalid receipt dimensions, weight, or sample count");
        for (double value : new double[]{temperature, responseResidual, regularizedGain, temperatureResidual})
            if (!Double.isFinite(value) || value < 0)
                throw new IllegalArgumentException("Receipt measures must be finite and nonnegative");
        if (responseResidual > 1 || temperatureResidual > 1)
            throw new IllegalArgumentException("Probability differences cannot exceed one");
        double mass = 0;
        for (int action = 0; action < probabilities.size(); action++) {
            double probability = probabilities.get(action);
            double error = standardErrors.get(action);
            if (!Double.isFinite(probability) || probability < 0 || probability > 1
                    || !Double.isFinite(freshValues.get(action))
                    || !Double.isFinite(error) || error < 0 || opponentSamples == 0 && error != 0)
                throw new IllegalArgumentException("Invalid action probability, value, or standard error");
            mass += probability;
        }
        if (Math.abs(mass - 1) > 1e-10)
            throw new IllegalArgumentException("Receipt probabilities must sum to one");
    }

    public TypeReceipt(Nation power, double lambda, double populationWeight, boolean playing,
                       double temperature, int opponentSamples, List<Double> probabilities,
                       List<Double> freshValues, List<Double> standardErrors,
                       double responseResidual, double regularizedGain, double temperatureResidual) {
        this(power, lambda, populationWeight, playing, temperature, opponentSamples,
                probabilities, freshValues, standardErrors, responseResidual, regularizedGain,
                temperatureResidual, PolicyOutput.FINAL);
    }

    public double expectedScore() {
        double score = 0;
        for (int action = 0; action < probabilities.size(); action++)
            score += probabilities.get(action) * freshValues.get(action);
        if (!Double.isFinite(score))
            throw new IllegalStateException("Non-finite expected score");
        return score;
    }

    /**
     * Numeric order: lambda, population weight, playing flag (0/1), temperature,
     * opponent sample count (0 for exact), expected score, response residual,
     * regularized gain, temperature residual; then probability, fresh value,
     * and standard error for each action in pool order.
     * Nation and output mode remain separate metadata.
     */
    public List<Double> values() {
        List<Double> values = new ArrayList<>(List.of(lambda, populationWeight, playing ? 1.0 : 0.0,
                temperature, (double) opponentSamples, expectedScore(),
                responseResidual, regularizedGain, temperatureResidual));
        for (int action = 0; action < probabilities.size(); action++) {
            values.add(probabilities.get(action));
            values.add(freshValues.get(action));
            values.add(standardErrors.get(action));
        }
        return List.copyOf(values);
    }

    /**
     * Enumerates all retained joint actions once. Requires deterministic payoffs;
     * use sampled() when the opposing action space is too large to enumerate.
     */
    public static List<TypeReceipt> exact(JointSearchResult solved, JointActionEvaluator trials) {
        return evaluate(solved, trials, 0, 0);
    }

    /**
     * Uses common opponent draws for every alternative, with a separate RNG.
     * Standard errors concern action values, not the derived response measures.
     */
    public static List<TypeReceipt> sampled(JointSearchResult solved, JointActionEvaluator trials,
                                            int samples, long seed) {
        if (samples < 2)
            throw new IllegalArgumentException("Sampled receipts require at least two observations");
        return evaluate(solved, trials, samples, seed);
    }

    public static List<TypeReceipt> exact(SearchProfile profile, JointSearchResult solved,
                                          JointActionEvaluator trials) {
        Objects.requireNonNull(solved, "solved").requireProfile(profile);
        return exact(solved, trials);
    }

    public static List<TypeReceipt> sampled(SearchProfile profile, JointSearchResult solved,
                                            JointActionEvaluator trials, int samples, long seed) {
        Objects.requireNonNull(solved, "solved").requireProfile(profile);
        return sampled(solved, trials, samples, seed);
    }

    private static List<TypeReceipt> evaluate(JointSearchResult solved, JointActionEvaluator trials,
                                              int samples, long seed) {
        Objects.requireNonNull(solved, "solved");
        Objects.requireNonNull(trials, "trials");
        JointSearchResult.Settings profile = solved.settings();
        LambdaDistribution population = profile.population();
        Set<Double> lambdas = new LinkedHashSet<>(population.values());
        lambdas.add(profile.agentLambda());
        List<Nation> nations = new ArrayList<>(solved.results().keySet());
        Map<Nation, Map<Double, ActionDistribution>> policies = new EnumMap<>(Nation.class);

        for (Nation nation : nations) {
            Map<Double, ActionDistribution> types = new LinkedHashMap<>();
            for (double lambda : lambdas)
                types.put(lambda, solved.policy(nation, lambda));
            policies.put(nation, types);
        }

        double[][] means = new double[nations.size()][];
        double[][] errors = new double[nations.size()][];
        for (int nation = 0; nation < nations.size(); nation++) {
            int size = solved.results().get(nations.get(nation)).meanValues().size();
            means[nation] = new double[size];
            errors[nation] = new double[size];
        }
        if (samples == 0)
            enumerate(nations, solved.populationPolicies(), trials, new EnumMap<>(Nation.class),
                    new int[nations.size()], 0, means);
        else
            sample(nations, solved.populationPolicies(), trials, samples, new Random(seed), means, errors);

        List<TypeReceipt> receipts = new ArrayList<>();
        for (int index = 0; index < nations.size(); index++) {
            Nation nation = nations.get(index);
            List<Double> fresh = Arrays.stream(means[index]).boxed().toList();
            List<Double> standardErrors = Arrays.stream(errors[index]).boxed().toList();
            for (double lambda : lambdas) {
                ActionDistribution policy = policies.get(nation).get(lambda);
                List<Double> response = DiLPiKLSearch.policy(policy.candidates(), fresh, lambda).probabilities();
                double kappa = solved.temperature(nation, lambda);
                List<Double> tempered = DiLPiKLSearch.policy(policy.candidates(), fresh, lambda, kappa).probabilities();
                int type = population.values().indexOf(lambda);
                receipts.add(new TypeReceipt(nation, lambda,
                        type < 0 ? 0 : population.probabilities().get(type),
                        lambda == profile.agentLambda(), kappa, samples, policy.probabilities(), fresh, standardErrors,
                        difference(policy.probabilities(), response), gain(policy, response, fresh, lambda),
                        difference(policy.probabilities(), tempered), solved.output()));
            }
        }
        return List.copyOf(receipts);
    }

    private static void enumerate(List<Nation> nations, Map<Nation, ActionDistribution> populations,
                                  JointActionEvaluator trials, Map<Nation, ActionCandidate> joint,
                                  int[] chosen, int depth, double[][] means) {
        if (depth < nations.size()) {
            Nation nation = nations.get(depth);
            List<ActionCandidate> candidates = populations.get(nation).candidates();
            for (int action = 0; action < candidates.size(); action++) {
                chosen[depth] = action;
                joint.put(nation, candidates.get(action));
                enumerate(nations, populations, trials, joint, chosen, depth + 1, means);
            }
            return;
        }
        Map<Nation, Double> scores = trials.evaluate(joint);
        for (int nation = 0; nation < nations.size(); nation++) {
            double weight = 1;
            for (int opponent = 0; opponent < nations.size(); opponent++)
                if (opponent != nation)
                    weight *= populations.get(nations.get(opponent)).probabilities().get(chosen[opponent]);
            means[nation][chosen[nation]] += weight * scores.get(nations.get(nation));
        }
    }

    private static void sample(List<Nation> nations, Map<Nation, ActionDistribution> populations,
                               JointActionEvaluator trials, int samples, Random random,
                               double[][] means, double[][] errors) {
        for (int observation = 0; observation < samples; observation++) {
            Map<Nation, ActionCandidate> played = new EnumMap<>(Nation.class);
            for (Nation nation : nations)
                played.put(nation, populations.get(nation).sample(random));
            Map<Nation, Double> observed = trials.evaluate(played);
            for (int nation = 0; nation < nations.size(); nation++) {
                Nation power = nations.get(nation);
                Map<Nation, ActionCandidate> joint = new EnumMap<>(played);
                List<ActionCandidate> candidates = populations.get(power).candidates();
                for (int action = 0; action < candidates.size(); action++) {
                    ActionCandidate candidate = candidates.get(action);
                    joint.put(power, candidate);
                    double score = candidate == played.get(power)
                            ? observed.get(power) : trials.evaluate(joint).get(power);
                    double delta = score - means[nation][action];
                    means[nation][action] += delta / (observation + 1.0);
                    errors[nation][action] += delta * (score - means[nation][action]);
                }
            }
        }
        for (int nation = 0; nation < nations.size(); nation++)
            for (int action = 0; action < errors[nation].length; action++)
                errors[nation][action] = StrictMath.sqrt(errors[nation][action] / (samples - 1) / samples);
    }

    private static double gain(ActionDistribution policy, List<Double> response,
                               List<Double> fresh, double lambda) {
        double maximumPrior = policy.candidates().stream().mapToDouble(ActionCandidate::logPrior).max().orElseThrow();
        double gain = 0;
        for (int action = 0; action < fresh.size(); action++) {
            double prior = policy.candidates().get(action).logPrior();
            if (prior == Double.NEGATIVE_INFINITY)
                continue;
            double p = policy.probabilities().get(action);
            double b = response.get(action);
            // Compare objectives directly; log(b) could be infinite after probability underflow.
            gain += (b - p) * fresh.get(action)
                    + lambda * (entropyTerm(p) - entropyTerm(b) + (b - p) * (prior - maximumPrior));
        }
        if (!Double.isFinite(gain) || gain < -1e-10)
            throw new IllegalStateException("Invalid regularized improvement: " + gain);
        return Math.max(0, gain);
    }

    private static double entropyTerm(double probability) {
        return probability == 0 ? 0 : probability * StrictMath.log(probability);
    }

    private static double difference(List<Double> left, List<Double> right) {
        double difference = 0;
        for (int action = 0; action < left.size(); action++)
            difference = Math.max(difference, Math.abs(left.get(action) - right.get(action)));
        return difference;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        try (Formatter out = new Formatter(text, Locale.ROOT)) {
            out.format("==============================================================%n");
            out.format("                     DiL-piKL TYPE RECEIPT%n");
            out.format("--------------------------------------------------------------%n");
            out.format("Nation: %-12s  Playing type: %s%n", power, playing ? "yes" : "no");
            out.format("Policy: %s; opponents: %s population%n", output, output);
            out.format("Lambda: %.10g          Population weight: %.6f%n", lambda, populationWeight);
            out.format("Final temperature: %.10g%n", temperature);
            out.format("Values: %s%n", opponentSamples == 0 ? "exact opponent enumeration"
                    : "estimated from " + opponentSamples + " opponent samples");
            out.format("--------------------------------------------------------------%n");
            out.format("Action     Probability       Fresh score        Std. error%n");
            for (int action = 0; action < probabilities.size(); action++)
                out.format("%6d %15.8f %17.8f %17.8f%n", action, probabilities.get(action),
                        freshValues.get(action), standardErrors.get(action));
            out.format("--------------------------------------------------------------%n");
            out.format("Expected score                  %16.10f%n", expectedScore());
            out.format("Response difference             %16.10f%n", responseResidual);
            out.format("Regularized improvement         %16.10f%n", regularizedGain);
            out.format("Difference with final temperature %14.10f%n", temperatureResidual);
            out.format("==============================================================%n");
            out.format("Retained actions only; these checks do not prove convergence.%n");
            if (opponentSamples > 0)
                out.format("Standard errors apply to action values, not response differences.%n");
        }
        return text.toString();
    }

}