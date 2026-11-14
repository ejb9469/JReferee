package io.training;

import java.util.*;

/**
 * Provider-specific decoding with shared position-admission checks.
 */
@FunctionalInterface
public interface ValueSourceAdapter {

    String POSITION_VALIDATION = "jreferee-value-standing-units-v1";
    int MAX_STANDARD_UNITS = 34;

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

        public Result validatePositions() {

            if (this.game.isEmpty())
                return this;

            List<String> notes = new ArrayList<>(this.diagnostics);
            List<ValueDataset.Position> positions = this.game.orElseThrow().positions();
            boolean invalid = false;

            for (int index = 0; index < positions.size(); index++) {

                ValueDataset.Position position = positions.get(index);
                int units = position.board().locations().size();

                if (units > MAX_STANDARD_UNITS) {

                    invalid = true;
                    notes.add("Position " + index + ": " + position.moment().year()
                            + " " + position.moment().gamePhase().name()
                            + " has " + units + " standing units; maximum "
                            + MAX_STANDARD_UNITS + ".");

                }

            }

            if (!invalid)
                return this;

            notes.add("Entire game excluded; no positions or outcomes were repaired.");
            return new Result(Optional.empty(),
                    Optional.of("TOO_MANY_STANDING_UNITS"), notes);

        }

    }

}