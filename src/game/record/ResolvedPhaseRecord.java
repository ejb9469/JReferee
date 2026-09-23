package game.record;

import game.BoardState;
import game.GameMoment;
import game.Moment;
import phase.PhaseResult;

/**
 * Immutable archived result of one resolved Diplomacy phase.
 *
 * <p>The resolvedAt `Moment` records when adjudication completed.
 * Its `GameMoment` identifies the board position and phase in replay.</p>
 */
// this is implemented this way because of the non-inheritance structure of the `phase` package
// (i.e. all records & interfaces)
public sealed interface ResolvedPhaseRecord
        permits MovementPhaseRecord,
                RetreatPhaseRecord,
                AdjustmentPhaseRecord {

    /**
     * The timestamped event at which this phase was resolved.
     */
    Moment resolvedAt();

    /**
     * Timestamp-free identity of the phase for replay and indexing.
     */
    default GameMoment gameMoment() {
        return resolvedAt().gameMoment();
    }

    /**
     * Immutable board presented to the phase processor.
     */
    BoardState boardBefore();

    /**
     * Immutable board produced by the resolved phase.
     */
    BoardState boardAfter();

    /**
     * Typed implementations return their more specific result type.
     */
    PhaseResult result();

}