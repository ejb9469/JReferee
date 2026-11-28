package game.record;

import contracts.OrderForm;
import domain.*;
import game.*;
import phase.BoardRules;
import phase.UnitId;
import phase.adjustments.DisbandOrder;
import phase.movement.MovementResult;

import java.util.*;

/**
 * Read-only historical positions, preserving source submissions and outcomes.
 * Availability flags distinguish missing information from known empty lists.
 * This projection does not contain the processor state needed to resume play.
 */
public record GameHistory(String rulesetId, List<Position> positions) {

    public GameHistory {
        positions = List.copyOf(positions);
        if (positions.isEmpty())
            throw new IllegalArgumentException("A history must contain a position");
        for (int index = 0; index < positions.size() - 1; index++)
            if (positions.get(index).terminal())
                throw new IllegalArgumentException("Only the last position may be terminal");
    }

    public record Position(
            Integer sourcePhase, String status, GameMoment moment, BoardState board,
            List<Submission> orders, List<Annotation> annotations,
            Map<UnitId, Province> dislodged, boolean dislodgementsKnown,
            boolean ordersKnown, boolean terminal
    ) {
        public Position {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(moment, "moment");
            Objects.requireNonNull(board, "board");
            orders = List.copyOf(orders);
            annotations = List.copyOf(annotations);
            dislodged = BoardRules.unitLocations(dislodged, "dislodged");
            if (terminal && !orders.isEmpty())
                throw new IllegalArgumentException("A terminal position cannot contain submissions");
            if (!ordersKnown && !orders.isEmpty())
                throw new IllegalArgumentException("Unknown orders cannot contain submissions");
            if (!dislodgementsKnown && !dislodged.isEmpty())
                throw new IllegalArgumentException("Unknown dislodgements cannot contain units");
        }
    }

    /**
     * Positional copy of an OrderForm. Origin is the issuing location.
     * A null viaConvoy means the source does not supply that flag.
     */
    public record Submission(
            Nation owner, UnitType unitType, Province origin, OrderType orderType,
            Province target, Province auxiliaryTarget, Boolean viaConvoy
    ) implements OrderForm {
        public Submission {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(orderType, "orderType");
            if (orderType != OrderType.WAIVE && (origin == null || unitType == null))
                throw new IllegalArgumentException("A non-waive submission needs an origin and unit type");
        }

        public static Submission from(OrderForm order, Province issuingProvince) {
            return new Submission(order.owner(), order.unitType(), issuingProvince,
                    order.orderType(), order.target(), order.auxiliaryTarget(), null);
        }
    }

    public record Annotation(
            Nation nation, Province origin, boolean retreatOrder,
            Boolean successful, String reason
    ) {
        public Annotation {
            Objects.requireNonNull(nation, "nation");
        }
    }

    public static GameHistory from(Game game) {
        GameState state = Objects.requireNonNull(game, "game").snapshot();
        return new GameHistory(null, List.of(position(state, "CURRENT", false)));
    }

    public static GameHistory from(GameRecord record, GameState finalState, String finalStatus) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(finalState, "finalState");
        if (!record.latestBoard().equals(finalState.board()))
            throw new IllegalArgumentException("Final state does not match the recorded board");
        if (record.resolvedPhases().isEmpty()
                ? !finalState.moment().equals(record.initialMoment())
                : finalState.moment().compareTo(record.latestGameMoment()) <= 0)
            throw new IllegalArgumentException("Final state does not follow the recorded timeline");
        List<Position> positions = new ArrayList<>();
        for (ResolvedPhaseRecord phase : record.resolvedPhases())
            positions.add(from(phase));
        positions.add(position(finalState, finalStatus, true));
        return new GameHistory(record.rulesetId(), positions);
    }

    private static Position position(GameState state, String status, boolean terminal) {
        return new Position(null, status, state.moment(), state.board(), List.of(), List.of(),
                state instanceof GameState_Retreats retreats
                        ? displaced(retreats.movementResult()) : Map.of(),
                true, terminal, terminal);
    }

    private static Position from(ResolvedPhaseRecord phase) {
        List<Submission> orders = new ArrayList<>();
        List<Annotation> annotations = new ArrayList<>();
        Map<UnitId, Province> dislodged = Map.of();
        switch (phase) {
            case MovementPhaseRecord movement -> {
                movement.submittedOrders().forEach(order ->
                        orders.add(Submission.from(order, phase.boardBefore().locationOf(order.unit()))));
                movement.result().outcomes().forEach((unit, outcome) ->
                        annotations.add(new Annotation(unit.owner(), phase.boardBefore().locationOf(unit),
                                false, outcome.succeeded(), outcome.effectiveType().name()
                                + (outcome.submitted() ? "" : " (default order)")
                                + (outcome.rewrittenBySzykman() ? "; Szykman rewrite" : "")
                                + (movement.result().dislodgements().containsKey(unit) ? "; DISLODGED" : ""))));
            }
            case RetreatPhaseRecord retreat -> {
                dislodged = displaced(retreat.result().movementResult());
                for (var order : retreat.submittedOrders())
                    orders.add(Submission.from(order, dislodged.get(order.unit())));
                for (var entry : retreat.result().outcomes().entrySet())
                    annotations.add(new Annotation(entry.getKey().owner(), dislodged.get(entry.getKey()),
                            true, entry.getValue().succeeded(), entry.getValue().status().name()));
            }
            case AdjustmentPhaseRecord adjustment -> {
                Set<UnitId> submittedDisbands = new HashSet<>();
                for (var order : adjustment.submittedOrders()) {
                    Province origin = order instanceof DisbandOrder disband
                            ? phase.boardBefore().locationOf(disband.unit()) : order.origin();
                    orders.add(Submission.from(order, origin));
                    if (order instanceof DisbandOrder disband)
                        submittedDisbands.add(disband.unit());
                }
                for (var outcome : adjustment.result().outcomes()) {
                    var order = outcome.order();
                    Province origin = order instanceof DisbandOrder disband
                            ? phase.boardBefore().locationOf(disband.unit()) : order.origin();
                    annotations.add(new Annotation(order.owner(), origin, false, outcome.accepted(),
                            order.orderType() + ": " + outcome.status()));
                }
                for (UnitId unit : adjustment.result().disbandedUnits())
                    if (!submittedDisbands.contains(unit))
                        annotations.add(new Annotation(unit.owner(), phase.boardBefore().locationOf(unit),
                                false, true, "AUTOMATIC_DISBAND"));
            }
        }
        return new Position(null, "RESOLVED", phase.gameMoment(), phase.boardBefore(), orders,
                annotations, dislodged, true, true, false);
    }

    private static Map<UnitId, Province> displaced(MovementResult result) {
        Map<UnitId, Province> units = new LinkedHashMap<>();
        result.dislodgements().forEach((unit, detail) -> units.put(unit, detail.displacedFrom()));
        return units;
    }
}
