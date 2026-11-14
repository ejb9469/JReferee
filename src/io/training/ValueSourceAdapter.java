package io.training;

import java.util.*;

/**
 * Provider-specific payload decoding behind a provider-neutral result.
 * Invalid source data produces an explicit rejection, not a fabricated game.
 */
@FunctionalInterface
public interface ValueSourceAdapter {

    Result read(String source, String gameKey, String payload, String expectedHash);

    record Result(
            Optional<ValueDataset.GameExample> game,
            Optional<String> rejection,
            List<String> diagnostics
    ) {

        public Result {

            Objects.requireNonNull(game, "game");
            Objects.requireNonNull(rejection, "rejection");
            diagnostics = List.copyOf(diagnostics);

            if (game.isPresent() == rejection.isPresent())
                throw new IllegalArgumentException(
                        "Supply either a game or a rejection reason");

            if (rejection.filter(String::isBlank).isPresent())
                throw new IllegalArgumentException("Rejection reason is blank");

        }

        public static Result rejected(String reason) {
            return new Result(
                    Optional.empty(), Optional.of(reason), List.of());
        }

    }

}