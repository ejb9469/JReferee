package game.record;

import domain.Nation;
import game.BoardState;
import game.GameMoment;
import game.press.PressMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, replayable record of one Diplomacy game.
 *
 * <p>The record preserves three independent histories:</p>
 * <ul>
 *     <li>the initial board;</li>
 *     <li>resolved phase records, in game-phase order;</li>
 *     <li>press messages, in authoritative append order.</li>
 * </ul>
 *
 * <p>Ruleset ID is persisted because adjudication behavior is part of a
 * game's "meaning". This is particularly important for JReferee-specific
 * choices such as convoy-kidnapping policy and convoy-paradox handling.
 * i.e. 2 games may be adjudicated differently, but only 1 is authoritative.</p>
 */
public record GameRecord(
        UUID id,
        String rulesetId,
        GameMoment initialMoment,
        BoardState initialBoard,
        List<ResolvedPhaseRecord> resolvedPhases,
        List<PressMessage> pressMessages
) {

    public GameRecord {

        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rulesetId, "rulesetId");

        if (rulesetId.isBlank())
            throw new IllegalArgumentException("rulesetId must not be blank");

        Objects.requireNonNull(initialMoment, "initialMoment");
        Objects.requireNonNull(initialBoard, "initialBoard");

        resolvedPhases = List.copyOf(Objects.requireNonNull(resolvedPhases, "resolvedPhases"));
        pressMessages = List.copyOf(Objects.requireNonNull(pressMessages, "pressMessages"));

        verifyPhaseTimeline(
                initialMoment,
                initialBoard,
                resolvedPhases
        );

        verifyPressTimeline(
                initialMoment,
                pressMessages
        );

    }

    /**
     * Returns the final archived board, or the initial board when no phase has
     * yet been resolved.
     */
    public BoardState latestBoard() {
        if (resolvedPhases.isEmpty())
            return initialBoard;
        return resolvedPhases.getLast().boardAfter();
    }

    /**
     * Returns the most recently resolved game phase, or the initial phase when
     * no phase record has been added.
     */
    public GameMoment latestGameMoment() {
        if (resolvedPhases.isEmpty())
            return initialMoment;
        return resolvedPhases.getLast().gameMoment();
    }

    /**
     * Finds a resolved phase by its GameMoment (timestamp-free)
     */
    public Optional<ResolvedPhaseRecord> resolvedPhaseAt(GameMoment gameMoment) {

        Objects.requireNonNull(gameMoment, "gameMoment");

        for (ResolvedPhaseRecord phase : resolvedPhases)
            if (phase.gameMoment().equals(gameMoment))
                return Optional.of(phase);

        return Optional.empty();

    }

    /**
     * Returns press recorded during one logical game phase, preserving
     * append order. i.e. NOT BY CLOCK
     */
    public List<PressMessage> pressAt(GameMoment gameMoment) {

        Objects.requireNonNull(gameMoment, "gameMoment");

        List<PressMessage> matching = new ArrayList<>();

        for (PressMessage message : pressMessages)
            if (message.moment().gameMoment().equals(gameMoment))
                matching.add(message);

        return List.copyOf(matching);

    }

    /**
     * Returns press visible to one nation in append order.
     */
    public List<PressMessage> pressVisibleTo(Nation nation) {

        Objects.requireNonNull(nation, "nation");

        List<PressMessage> visible = new ArrayList<>();

        for (PressMessage message : pressMessages)
            if (message.isVisibleTo(nation))
                visible.add(message);

        return List.copyOf(visible);

    }

    private static void verifyPhaseTimeline(GameMoment initialMoment, BoardState initialBoard,
                                            List<ResolvedPhaseRecord> resolvedPhases) {

        BoardState expectedBoardBefore = initialBoard;
        GameMoment previousMoment = null;

        for (int i = 0; i < resolvedPhases.size(); i++) {

            ResolvedPhaseRecord phase = resolvedPhases.get(i);

            if (i == 0 && !phase.gameMoment().equals(initialMoment))
                throw new IllegalArgumentException("First resolved phase must match initialMoment");

            if ( previousMoment != null && (phase.gameMoment().compareTo(previousMoment) <= 0) )
                throw new IllegalArgumentException("Resolved phases must be in strictly increasing game order");

            if (!expectedBoardBefore.equals(phase.boardBefore()))
                throw new IllegalArgumentException(
                        "Phase boardBefore does not match the preceding "
                                + "boardAfter: "
                                + phase.gameMoment());

            previousMoment = phase.gameMoment();
            expectedBoardBefore = phase.boardAfter();

        }

    }

    private static void verifyPressTimeline(GameMoment initialMoment,
                                            List<PressMessage> pressMessages) {

        Set<UUID> messageIds = new HashSet<>();

        for (PressMessage message : pressMessages) {
            if (!messageIds.add(message.id()))
                throw new IllegalArgumentException("Duplicate press message ID: " + message.id());
            if ( (message.moment().gameMoment()).compareTo(initialMoment) < 0 )
                throw new IllegalArgumentException("Press cannot precede initialMoment");
        }

    }

}