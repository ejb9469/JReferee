package agents.evaluation;

import domain.Nation;
import game.GameState;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Scores a position by recorded supply-center ownership.
 *
 * <p>Each owned center is worth one point. Scores are raw counts expressed
 * as doubles; unowned centers contribute no points. On the standard board,
 * Russia starts at 4.0 and the other nations at 3.0.</p>
 *
 * <p>Game updates ownership after the complete Fall turn, including retreats.
 * Spring movement, Spring retreats, and winter adjustments therefore leave
 * these scores unchanged. Fall positions awaiting retreats still use the
 * earlier ownership. Short searches may give every candidate the same score.</p>
 *
 * <p>Unit strength, mobility, threatened centers, and retreat prospects have
 * no weight here. Future positional or learned evaluators can estimate those
 * effects; a rollout through Fall can expose actual ownership changes.</p>
 *
 * <p>This is a material baseline for the evaluation pipeline. It does not
 * forecast winning chances or recognize solos and agreed draws. Terminal
 * scoring needs the chosen game's outcome rules. A future learned evaluator
 * should target that utility, with search regularization calibrated to its
 * scale.</p>
 */
public final class SCEvaluator implements Evaluator {

    /**
     * Counts the supplied state's center owners in nation order.
     * A position returned by Simulator.peek() remains usable after rollback.
     */
    @Override
    public Map<Nation, Double> evaluate(GameState state) {

        Objects.requireNonNull(state, "state");

        Map<Nation, Double> scores = new EnumMap<>(Nation.class);

        // Zero also describes a centerless nation that still has units.
        for (Nation nation : Nation.values())
            scores.put(nation, 0.0);

        // BoardState canonicalizes split coasts to one ownership entry.
        for (Nation owner : state.board().owners().values())
            scores.merge(owner, 1.0, Double::sum);

        return Collections.unmodifiableMap(scores);

    }

}