package agents.policy;

import agents.encoding.MovementEncoding;
import agents.encoding.MovementInput;
import agents.generation.ActionPrior;
import domain.Nation;
import game.GameState;
import game.GameState_Movement;

import java.util.*;
import java.util.random.RandomGenerator;

/**
 * An immutable movement policy.<br><br>
 *
 * Preparation may cache position-level work.<br>
 * Conditional order probabilities allow both factorized and autoregressive models.
 */
public interface MovementPolicy {

    Prepared prepare(MovementInput input);

    interface Prepared {
        MovementInput input();

        /**
         * Natural-log probabilities for the next slot, conditional on earlier
         * option indices. The returned array belongs to the caller.
         */
        double[] logProbabilities(List<Integer> prefix);

        default double logProbability(List<Integer> action) {
            input().requireAction(action);
            double result = 0;
            for (int slot = 0; slot < action.size(); slot++) {
                double[] probabilities = logProbabilities(action.subList(0, slot));
                requireDistribution(probabilities, input().slots().get(slot).options().size());
                result += probabilities[action.get(slot)];
            }
            return result;
        }

        default List<Integer> greedy() {
            List<Integer> action = new ArrayList<>();
            for (var slot : input().slots()) {
                double[] probabilities = logProbabilities(List.copyOf(action));
                requireDistribution(probabilities, slot.options().size());
                int best = 0;
                for (int index = 1; index < probabilities.length; index++)
                    if (probabilities[index] > probabilities[best]) best = index;
                action.add(best);
            }
            return List.copyOf(action);
        }

        default List<Integer> sample(RandomGenerator random) {
            Objects.requireNonNull(random, "random");
            List<Integer> action = new ArrayList<>();
            for (var slot : input().slots()) {
                double[] probabilities = logProbabilities(List.copyOf(action));
                requireDistribution(probabilities, slot.options().size());
                double draw = random.nextDouble(), cumulative = 0;
                int selected = -1, lastPositive = -1;
                for (int index = 0; index < probabilities.length; index++) {
                    double mass = Math.exp(probabilities[index]);
                    if (mass == 0) continue;
                    lastPositive = index;
                    cumulative += mass;
                    if (selected < 0 && draw < cumulative) selected = index;
                }
                action.add(selected >= 0 ? selected : lastPositive);
            }
            return List.copyOf(action);
        }
    }

    /**
     * Binds full-catalog probabilities to the existing fixed-root anchor API.
     * This does not change any generator or search default.
     */
    default ActionPrior bind(GameState root) {
        if (!(Objects.requireNonNull(root, "root") instanceof GameState_Movement movement))
            throw new IllegalArgumentException("A learned movement prior requires a movement root");
        record Bound(MovementEncoding encoding, Prepared policy) { }
        Map<Nation, Bound> bound = new EnumMap<>(Nation.class);
        for (Nation nation : root.aliveNations()) {
            MovementEncoding encoding = new MovementEncoding(movement, nation);
            bound.put(nation, new Bound(encoding, prepare(encoding.input())));
        }
        return (power, orders) -> {
            Bound value = bound.get(Objects.requireNonNull(power, "power"));
            if (value == null) throw new IllegalArgumentException("No root policy for " + power);
            return value.policy().logProbability(value.encoding().encode(orders));
        };
    }

    static void requireDistribution(double[] probabilities, int options) {
        if (probabilities.length != options || options == 0)
            throw new IllegalArgumentException("Policy output does not match the legal mask");
        double total = 0;
        for (double probability : probabilities) {
            if (Double.isNaN(probability) || probability > 0)
                throw new IllegalArgumentException("Expected nonpositive log probabilities");
            total += Math.exp(probability);
        }
        if (Math.abs(total - 1) > 1e-6)
            throw new IllegalArgumentException("Policy probabilities do not sum to one: " + total);
    }
}