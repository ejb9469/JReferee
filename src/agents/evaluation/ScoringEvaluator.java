package agents.evaluation;

import misc.scoring.ScoringSystem;
import domain.Nation;
import game.GameState;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;

/**
 * Evaluates recorded center ownership as if the game stopped at that position.
 *
 * <p>Uses the selected system's fixed-scale rewards. At an unfinished position this is
 * only a stop-now heuristic.
 *
 * <p>Recorded ownership changes after Fall retreats. Spring tactics, pending
 * retreats, and winter unit choices can therefore tie under this evaluator.
 * Some systems award points to eliminated nations, so all seven are scored.</p>
 *
 * <p>A future learned model must be trained for the chosen scoring system.
 * Appendix F of the paper includes that system among the model inputs.
 * Training examples and value-cache keys must also retain it. OMG and Duct
 * Tape have variable total payouts, which a sum-to-one value head cannot
 * represent directly. C-Diplo's total also depends on unowned centers.</p>
 */
public final class ScoringEvaluator implements Evaluator {

    @NotNull
    private final ScoringSystem scoringSystem;

    public ScoringEvaluator(ScoringSystem scoringSystem) {
        this.scoringSystem = Objects.requireNonNull(scoringSystem, "scoringSystem");
    }

    public ScoringSystem scoringSystem() {
        return ( this.scoringSystem );  // safe because is an enum
    }

    /**
     * Scores the recorded ownership without declaring the game finished.
     * An entirely unowned board has no defined scoring result and throws
     * IllegalArgumentException. SCEvaluator permits that diagnostic position.
     */
    @Override
    public Map<Nation, Double> evaluate(GameState state) {
        Objects.requireNonNull(state, "state");
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            counts.put(nation, 0);
        for (Nation owner : state.board().owners().values())
            counts.merge(owner, 1, Integer::sum);
        return scoringSystem.rewards(counts);
    }

}