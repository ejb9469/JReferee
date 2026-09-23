package game.record;

import game.BoardState;
import game.Game;
import game.GameMoment;
import game.GamePhase;
import game.Moment;
import phase.Order;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.AdjustmentResult;
import phase.movement.MovementResult;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;


/**
 * Builds an immutable GameRecord while resolving phases through one Game.
 *
 * <p>This builder must be attached before the supplied Game resolves its first
 * recorded phase. It delegates adjudication and phase advancement to Game,
 * then archives each completed result with the board before and after it.</p>
 *
 * <p>Callers provide each resolution Moment. The builder does not read a clock
 * or generate identifiers, so archived records remain deterministic under
 * replay, import, and testing.</p>
 */
public final class GameRecordBuilder {


    private final UUID id;
    private final String rulesetId;

    private final Game game;
    private final GameMoment initialMoment;
    private final BoardState initialBoard;

    private final List<ResolvedPhaseRecord> resolvedPhases;


    public GameRecordBuilder(
            UUID id,
            String rulesetId,
            Game game
    ) {

        this.id = Objects.requireNonNull(id, "id");
        this.rulesetId = Objects.requireNonNull(rulesetId, "rulesetId");

        if (rulesetId.isBlank())
            throw new IllegalArgumentException("rulesetId must not be blank");

        this.game = Objects.requireNonNull(game, "game");

        this.initialMoment = currentGameMoment();
        this.initialBoard = game.board();

        this.resolvedPhases = new ArrayList<>();

    }


    /**
     * Resolves the current movement phase and archives its typed result.
     */
    public MovementPhaseRecord resolveMovement(
            Collection<Order> submittedOrders,
            Moment resolvedAt
    ) {

        requireCurrentMoment(resolvedAt);

        BoardState boardBefore = game.board();
        List<Order> copiedOrders = List.copyOf(
                Objects.requireNonNull(
                        submittedOrders,
                        "submittedOrders"));

        MovementResult result = game.resolveMovement(copiedOrders);

        MovementPhaseRecord record = new MovementPhaseRecord(
                resolvedAt,
                boardBefore,
                copiedOrders,
                result,
                game.board()
        );

        append(record);

        return record;

    }

    /**
     * Resolves the current retreat phase and archives its typed result.
     */
    public RetreatPhaseRecord resolveRetreats(
            Collection<RetreatOrder> submittedOrders,
            Moment resolvedAt
    ) {

        requireCurrentMoment(resolvedAt);

        BoardState boardBefore = game.board();
        List<RetreatOrder> copiedOrders = List.copyOf(
                Objects.requireNonNull(
                        submittedOrders,
                        "submittedOrders"));

        RetreatResult result = game.resolveRetreats(copiedOrders);

        RetreatPhaseRecord record = new RetreatPhaseRecord(
                resolvedAt,
                boardBefore,
                copiedOrders,
                result,
                game.board()
        );

        append(record);

        return record;

    }

    /**
     * Resolves the current Winter adjustment phase and archives its typed
     * result.
     */
    public AdjustmentPhaseRecord resolveAdjustments(
            Collection<AdjustmentOrder> submittedOrders,
            Moment resolvedAt
    ) {

        requireCurrentMoment(resolvedAt);

        BoardState boardBefore = game.board();
        List<AdjustmentOrder> copiedOrders = List.copyOf(
                Objects.requireNonNull(
                        submittedOrders,
                        "submittedOrders"));

        AdjustmentResult result = game.resolveAdjustments(copiedOrders);

        AdjustmentPhaseRecord record = new AdjustmentPhaseRecord(
                resolvedAt,
                boardBefore,
                copiedOrders,
                result,
                game.board()
        );

        append(record);

        return record;

    }

    /**
     * Returns an immutable snapshot of all phases archived so far.
     */
    public List<ResolvedPhaseRecord> resolvedPhases() {
        return List.copyOf(resolvedPhases);
    }

    /**
     * Produces an immutable replayable record from all archived phases and all
     * press currently recorded by the Game.
     */
    public GameRecord build() {
        return new GameRecord(
                id,
                rulesetId,
                initialMoment,
                initialBoard,
                resolvedPhases,
                game.pressHistory()
        );
    }


    private GameMoment currentGameMoment() {
        return new GameMoment(
                game.year(),
                game.phase()
        );
    }

    private void requireCurrentMoment(Moment moment) {

        Objects.requireNonNull(moment, "resolvedAt");

        if (moment.year() != game.year()
                || moment.gamePhase() != game.phase())
            throw new IllegalArgumentException(
                    "Resolution moment does not match the current game position"
            );

    }

    private void append(ResolvedPhaseRecord record) {

        Objects.requireNonNull(record, "record");

        if (!resolvedPhases.isEmpty()) {

            GameMoment previousMoment =
                    resolvedPhases.getLast().gameMoment();

            if (record.gameMoment().compareTo(previousMoment) <= 0)
                throw new IllegalStateException(
                        "Resolved phase records must be appended "
                                + "in strictly increasing game order"
                );

        }

        resolvedPhases.add(record);

    }


}