package agents.evaluation;

import domain.Nation;
import game.GameState;

import java.util.Map;

/**
 * Assigns a value to each nation's position.<br>
 *      `Evaluator` is used for internal model rewards weighing.<br><br>
 *          This is subtly distinct from the scoring of a game's position at its end-point:
 *          ... that is the prerogative of `ScoringSystem` et al.
 *
 * <p>Higher values favor the nation being scored. Implementations document
 * their scale and what they measure. Scores need not sum to one; i.e. not "zero-sum".</p>
 *
 * <p>Positional heuristics, rollouts, and learned value models can share this
 * interface. A search must use a consistent payoff scale, including its
 * terminal results and any policy-regularization weights.</p>
 */
public interface Evaluator {

    /**
     * Returns an immutable map containing one finite score for every nation,
     * including eliminated nations.<br>
     * The supplied state remains unchanged.
     *
     * <p>Each implementation defines how much of the phase state it uses.
     * Any internal rollout must use a separate game and preserve the
     * supplied position.</p>
     *
     * @param   state position to evaluate
     * @return  scores keyed by all nations
     * @throws  NullPointerException if state is null
     */
    Map<Nation, Double> evaluate(GameState state);

}