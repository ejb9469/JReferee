package io.training;

import agents.encoding.EncodingPurpose;
import game.BoardState;
import game.GameMoment;
import misc.scoring.ScoringSystem;

import java.io.IOException;
import java.util.*;

/**
 * Reopenable game-grouped observations for supervised value learning.<br><br>
 *
 * This interface defines the contract with no particular file format.
 * Every source game belongs to exactly one split. Implementations must
 * preserve the existing movement dataset's game-level split assignment.
 *
 * Outcome metadata and game identities are never model inputs.
 */
public interface ValueDataset {


    // Public position input \\

    /**
     * One public board, independent of a deciding power.<br><br>
     *
     * This first board-only input supports movement and adjustment positions.
     * Retreat positions require additional displaced-unit and retreat-legality
     * information and are explicitly unsupported here.
     *
     * A future numeric encoder must ignore UnitId birth/build identity and
     * encode only current locations, types, owners and center ownership.
     */
    record Position(
            GameMoment moment,
            BoardState board,
            ScoringSystem scoring
    ) {

        public Position {

            Objects.requireNonNull(moment, "moment");
            Objects.requireNonNull(board, "board");
            Objects.requireNonNull(scoring, "scoring");

            if (moment.year() < 1901)
                throw new IllegalArgumentException(
                        "Value positions require a standard-game year");

            if (moment.gamePhase().isRetreat())
                throw new IllegalArgumentException(
                        "Board-only value input cannot represent pending retreats");

            if (scoring != ScoringSystem.SUM_OF_SQUARES)
                throw new IllegalArgumentException(
                        "This value input version supports SUM_OF_SQUARES only");

        }

    }


    // Game-grouped observations \\

    /**
     * Positions are unique, chronologically ordered, nonterminal observations.
     * The ending is stored once rather than copied into each model input.
     *
     * Communication type remains literal source metadata, including unknown.
     * Keeping it here does not implement press conditioning in a model.
     */
    record GameExample(
            String source,
            String gameKey,
            Optional<String> communicationType,
            String payloadSha256,
            List<Position> positions,
            ValueOutcome outcome
    ) {

        public GameExample {

            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(gameKey, "gameKey");
            Objects.requireNonNull(communicationType, "communicationType");
            Objects.requireNonNull(payloadSha256, "payloadSha256");
            Objects.requireNonNull(outcome, "outcome");

            if (source.isBlank() || gameKey.isBlank())
                throw new IllegalArgumentException(
                        "Source and game key are required");

            if (communicationType.isPresent()
                    && communicationType.orElseThrow().isBlank())
                throw new IllegalArgumentException(
                        "Use Optional.empty() for unknown communication type");

            if (!payloadSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException(
                        "Expected a lowercase SHA-256 payload identity");

            positions = List.copyOf(
                    Objects.requireNonNull(positions, "positions"));

            if (positions.isEmpty())
                throw new IllegalArgumentException(
                        "A value game needs at least one supported position");

            GameMoment previous = null;

            for (Position position : positions) {

                if (previous != null
                        && position.moment().compareTo(previous) <= 0)
                    throw new IllegalArgumentException(
                            "Positions must have distinct increasing moments");

                if (position.moment().compareTo(outcome.moment()) >= 0)
                    throw new IllegalArgumentException(
                            "Training observations must precede the ending "
                                    + "or last-observed boundary");

                previous = position.moment();

            }

        }

        /**
         * Derives labels from the selected contract, never stored source scores.
         * Callers must count/report rejections before excluding games.
         */
        public ValueRewardContract.Assessment assess(
                ValueRewardContract contract
        ) {
            return Objects.requireNonNull(contract, "contract").assess(
                    this.outcome);
        }

    }


    // Streaming access \\

    /**
     * A byte-backed reader supplies sha256 only after verified EOF.
     */
    record Statistics(
            long games,
            long positions,
            long readNanos,
            Optional<String> sha256
    ) {

        public Statistics {

            Objects.requireNonNull(sha256, "sha256");

            if (games < 0 || positions < 0 || readNanos < 0)
                throw new IllegalArgumentException(
                        "Dataset statistics cannot be negative");

            if (sha256.isPresent()
                    && !sha256.orElseThrow().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException(
                        "Expected a lowercase SHA-256 stream identity");

        }

    }

    interface Cursor extends AutoCloseable {

        /**
         * Returns null only at verified EOF. Invalid records throw.
         * Closing early does not verify unread input.
         */
        GameExample next() throws IOException;

        Statistics statistics();

        @Override
        void close() throws IOException;

    }

    Cursor open(EncodingPurpose split) throws IOException;

    long games(EncodingPurpose split);

    long positions(EncodingPurpose split);

    /**
     * Identifies the manifest, source-game index and split assignments.
     * A training receipt must additionally retain ValueRewardContract.id().
     */
    String fingerprint();

}