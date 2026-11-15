package agents.evaluation;

import agents.generation.ActionGenerator;
import domain.Constants;
import domain.Nation;
import game.*;
import misc.scoring.ScoringSystem;

import java.util.*;
import java.util.function.Function;

/**
 * Existing year-end continuations with a neural non-terminal winter leaf.
 * Solos and the runner's final-year cutoff retain exact SoS rewards.
 */
public final class NeuralRolloutEvaluator implements Evaluator {

    private final NeuralValueModel model;
    private final int lastYear;
    private final RolloutEvaluator rollout;
    private final ScoringEvaluator scoring =
            new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES);

    public NeuralRolloutEvaluator(
            NeuralValueModel model,
            int lastYear,
            ActionGenerator generator,
            Function<GameState, Game> gameFactory,
            int rolloutCount,
            int candidateLimit,
            long seed
    ) {

        this.model = Objects.requireNonNull(model, "model");
        if (lastYear < Constants.STARTING_YEAR || lastYear == Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid final year");
        this.lastYear = lastYear;
        this.rollout = new RolloutEvaluator(this::leaf, generator, gameFactory,
                rolloutCount, candidateLimit, seed);

    }

    @Override
    public Map<Nation, Double> evaluate(GameState state) {

        Objects.requireNonNull(state, "state");
        if (state.moment().year() > this.lastYear)
            throw new IllegalArgumentException("Value position is beyond the game cutoff");
        if (!(state instanceof GameState_Retreats) && solo(state))
            return this.scoring.evaluate(state);
        return this.rollout.evaluate(state);

    }

    private Map<Nation, Double> leaf(GameState state) {

        if (!(state instanceof GameState_Adjustments))
            throw new IllegalStateException("Neural rollout leaf must be a completed Fall turn");
        if (state.moment().year() == this.lastYear || solo(state))
            return this.scoring.evaluate(state);
        return this.model.predict(state.moment(), state.board());

    }

    private static boolean solo(GameState state) {

        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);
        state.board().owners().values().forEach(nation -> counts.merge(nation, 1, Integer::sum));
        return counts.values().stream().anyMatch(count -> count >= 18);

    }

}