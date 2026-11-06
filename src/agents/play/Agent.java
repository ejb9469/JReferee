package agents.play;

import agents.generation.ActionCandidate;
import domain.Nation;
import game.GameState;

/**
 * Chooses one complete action from an immutable phase-start position.
 *
 * <p>Other nations' chosen orders are unavailable. Speculative work must use
 * a separate game with the match's processor settings. The supplied seed is
 * specific to this decision; implementations should keep no hidden RNG state.</p>
 */
@FunctionalInterface
public interface Agent {

    ActionCandidate choose(GameState state, Nation power, long seed);

}
