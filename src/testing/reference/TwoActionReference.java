package testing.reference;

import agents.search.SearchProfile;
import agents.search.TemperatureSchedule;
import agents.search.PolicyOutput;

import java.util.*;

/**
 * Independent Algorithm 1 calculation for two players with two actions each.
 * Payoffs must be in [0, 1]. No adjudication or production policy/update helpers
 * are used. Q uses payoff sums; utility variance uses ordinary Welford updates.
 */
public final class TwoActionReference {

    private TwoActionReference() { }

    public record Player(List<Double> values, Map<Double, List<Double>> policies,
                         Map<Double, Double> temperatures, List<Double> population,
                         Map<Double, List<Double>> averagePolicies, List<Double> averagePopulation) {
        public Player {
            values = List.copyOf(values);
            policies = copy(policies);
            averagePolicies = copy(averagePolicies);
            temperatures = Collections.unmodifiableMap(new LinkedHashMap<>(temperatures));
            population = List.copyOf(population);
            averagePopulation = List.copyOf(averagePopulation);
        }

        private static Map<Double, List<Double>> copy(Map<Double, List<Double>> policies) {
            Map<Double, List<Double>> copy = new LinkedHashMap<>();
            policies.forEach((lambda, policy) -> copy.put(lambda, List.copyOf(policy)));
            return Collections.unmodifiableMap(copy);
        }

        public Map<Double, List<Double>> policies(PolicyOutput output) {
            return switch (output) {
                case FINAL -> policies;
                case AVERAGE -> averagePolicies;
            };
        }

        public List<Double> population(PolicyOutput output) {
            return switch (output) {
                case FINAL -> population;
                case AVERAGE -> averagePopulation;
            };
        }
    }

    public record Snapshot(int iterations, List<Player> players) {
        public Snapshot {
            players = List.copyOf(players);
        }
    }

    /**
     * payoffs[player][first player's action][second player's action].
     * Priors use that same player/action order and remain in log form.
     */
    public static List<Snapshot> run(double[][][] payoffs, double[][] priors,
                                     SearchProfile profile, int iterations, long seed,
                                     List<Integer> checkpoints) {
        Objects.requireNonNull(profile, "profile");
        if (payoffs.length != 2 || priors.length != 2 || iterations < 1)
            throw new IllegalArgumentException("Reference requires two players and a positive budget");
        for (int player = 0; player < 2; player++) {
            if (payoffs[player].length != 2 || priors[player].length != 2)
                throw new IllegalArgumentException("Reference requires two actions per player");
            boolean supported = false;
            for (double prior : priors[player]) {
                if (Double.isNaN(prior) || prior > 0)
                    throw new IllegalArgumentException("Invalid log prior");
                supported |= Double.isFinite(prior);
            }
            if (!supported)
                throw new IllegalArgumentException("Each player needs positive prior support");
            for (double[] row : payoffs[player]) {
                if (row.length != 2)
                    throw new IllegalArgumentException("Reference requires a two-by-two payoff matrix");
                for (double value : row)
                    if (!Double.isFinite(value) || value < 0 || value > 1)
                        throw new IllegalArgumentException("Reference payoffs must be in [0, 1]");
            }
        }
        checkpoints = List.copyOf(checkpoints);
        int previous = 0;
        for (int checkpoint : checkpoints) {
            if (checkpoint <= previous || checkpoint > iterations)
                throw new IllegalArgumentException("Invalid reference checkpoint order");
            previous = checkpoint;
        }
        if (previous != iterations)
            throw new IllegalArgumentException("Reference checkpoints must end at the budget");
        double[] populationTypes = profile.population().values().stream().mapToDouble(Double::doubleValue).toArray();
        double[] weights = profile.population().probabilities().stream().mapToDouble(Double::doubleValue).toArray();
        Set<Double> tracked = new LinkedHashSet<>(profile.population().values());
        tracked.add(profile.agentLambda());
        List<Double> types = List.copyOf(tracked);
        for (double lambda : types)
            if (!Double.isFinite(lambda) || lambda <= 0)
                throw new IllegalArgumentException("Reference lambdas must be finite and positive");
        boolean adaptive = Objects.requireNonNull(profile.temperature()) == TemperatureSchedule.ADAPTIVE;
        double[][] sums = new double[2][2];
        double[][] utilityMeans = new double[2][types.size()];
        double[][] m2 = new double[2][types.size()];
        double[][][] policySums = new double[2][types.size()][2];
        List<Snapshot> history = new ArrayList<>();
        Random random = new Random(seed);

        for (int iteration = 0; iteration < iterations; iteration++) {
            double[][][] policies = new double[2][types.size()][];
            int[] played = new int[2];
            for (int player = 0; player < 2; player++) {
                double[] values = {sums[player][0] / Math.max(1, iteration),
                        sums[player][1] / Math.max(1, iteration)};
                for (int type = 0; type < types.size(); type++) {
                    policies[player][type] = policy(values, priors[player], types.get(type),
                            temperature(adaptive, iteration, m2[player][type]));
                    for (int action = 0; action < 2; action++)
                        policySums[player][type][action] += policies[player][type][action];
                }
                double lambda = populationTypes[draw(weights, random)];
                played[player] = draw(policies[player][types.indexOf(lambda)], random);
            }
            for (int player = 0; player < 2; player++) {
                double[] observed = new double[2];
                for (int action = 0; action < 2; action++) {
                    observed[action] = player == 0 ? payoffs[0][action][played[1]]
                            : payoffs[1][played[0]][action];
                    sums[player][action] += observed[action];
                }
                if (adaptive)
                    for (int type = 0; type < types.size(); type++) {
                        double utility = policies[player][type][0] * observed[0]
                                + policies[player][type][1] * observed[1];
                        double delta = utility - utilityMeans[player][type];
                        utilityMeans[player][type] += delta / (iteration + 1.0);
                        m2[player][type] += delta * (utility - utilityMeans[player][type]);
                    }
            }
            if (iteration + 1 == checkpoints.get(history.size())) {
                int completed = iteration + 1;
                List<Player> players = new ArrayList<>();
                for (int player = 0; player < 2; player++) {
                    double[] values = {sums[player][0] / completed, sums[player][1] / completed};
                    Map<Double, List<Double>> finalPolicies = new LinkedHashMap<>();
                    Map<Double, List<Double>> averagePolicies = new LinkedHashMap<>();
                    Map<Double, Double> temperatures = new LinkedHashMap<>();
                    for (int type = 0; type < types.size(); type++) {
                        double lambda = types.get(type);
                        double kappa = temperature(adaptive, completed, m2[player][type]);
                        double[] probabilities = policy(values, priors[player], lambda, kappa);
                        finalPolicies.put(lambda, List.of(probabilities[0], probabilities[1]));
                        averagePolicies.put(lambda, List.of(policySums[player][type][0] / completed,
                                policySums[player][type][1] / completed));
                        temperatures.put(lambda, kappa);
                    }
                    players.add(new Player(List.of(values[0], values[1]), finalPolicies, temperatures,
                            mixture(finalPolicies, populationTypes, weights), averagePolicies,
                            mixture(averagePolicies, populationTypes, weights)));
                }
                history.add(new Snapshot(completed, players));
            }
        }
        return List.copyOf(history);
    }

    private static List<Double> mixture(Map<Double, List<Double>> policies, double[] types, double[] weights) {
        double[] mixture = new double[2];
        for (int type = 0; type < types.length; type++)
            for (int action = 0; action < 2; action++)
                mixture[action] += weights[type] * policies.get(types[type]).get(action);
        double mass = mixture[0] + mixture[1];
        return List.of(mixture[0] / mass, mixture[1] / mass);
    }

    private static double temperature(boolean adaptive, int count, double m2) {
        if (!adaptive)
            return 0;
        return count == 0 ? Double.POSITIVE_INFINITY
                : 3 * StrictMath.sqrt(m2 / count) / (10 * StrictMath.sqrt(count));
    }

    private static double[] policy(double[] values, double[] priors, double lambda, double kappa) {
        if (Double.isInfinite(kappa)) {
            double count = (Double.isFinite(priors[0]) ? 1 : 0) + (Double.isFinite(priors[1]) ? 1 : 0);
            return new double[]{Double.isFinite(priors[0]) ? 1 / count : 0,
                    Double.isFinite(priors[1]) ? 1 / count : 0};
        }
        double best = Double.NEGATIVE_INFINITY;
        for (int action = 0; action < 2; action++)
            if (Double.isFinite(priors[action]))
                best = Math.max(best, values[action]);
        double[] logits = new double[2];
        for (int action = 0; action < 2; action++)
            logits[action] = priors[action] == Double.NEGATIVE_INFINITY ? Double.NEGATIVE_INFINITY
                    : (values[action] - best) / (lambda + kappa) + lambda / (lambda + kappa) * priors[action];
        double maximum = Math.max(logits[0], logits[1]);
        double first = StrictMath.exp(logits[0] - maximum);
        double second = StrictMath.exp(logits[1] - maximum);
        return new double[]{first / (first + second), second / (first + second)};
    }

    private static int draw(double[] probabilities, Random random) {
        double total = 0;
        int last = 0;
        for (int index = 0; index < probabilities.length; index++) {
            total += probabilities[index];
            if (probabilities[index] > 0)
                last = index;
        }
        double ticket = random.nextDouble() * total;
        for (int index = 0; index < last; index++) {
            ticket -= probabilities[index];
            if (ticket < 0)
                return index;
        }
        return last;
    }

}