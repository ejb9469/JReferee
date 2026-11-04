package agents.generation;

import domain.Nation;
import game.GameState;

import java.util.Objects;

/**
 * Input for generating complete actions for one nation.
 */
public record ActionGenerationRequest(
        GameState state,
        Nation power,
        int candidateLimit,
        long seed
) {

    public ActionGenerationRequest {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(power, "power");

        if (candidateLimit < 1)
            throw new IllegalArgumentException("candidateLimit must be at least 1");
    }

}