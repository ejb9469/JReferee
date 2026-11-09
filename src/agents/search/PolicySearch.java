package agents.search;

import domain.Nation;
import game.Game;

/**
 * Selects a policy for one living nation at the current phase.
 *
 * <p>Implementations compare complete actions using finite evaluator payoffs.
 * Root pools contain distinct actions and positive prior support for every
 * living nation. The algorithm determines how payoffs and priors affect the
 * returned probabilities.</p>
 */
@FunctionalInterface
public interface PolicySearch {

    /**
     * Returns an immutable policy and aligned raw payoff estimates.
     * The caller samples the policy and submits the chosen orders.
     *
     * <p>The supplied game must be isolated and have no saved checkpoint.
     * Search preserves its position and histories, including after a failed
     * trial. Generation, adjudication, and evaluation failures propagate.
     * Collaborators must preserve the supplied state; their external state
     * is outside Game's rollback.</p>
     *
     * @param game simulation game at the phase to search
     * @param power living nation whose policy is requested
     * @return policy over complete actions for power
     * @throws NullPointerException if game or power is null
     * @throws IllegalArgumentException if power is eliminated
     * @throws IllegalStateException if the game has a saved checkpoint
     */
    SearchResult search(Game game, Nation power);

}