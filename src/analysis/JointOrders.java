package analysis;

import analysis.openings.Classifier;
import domain.Nation;
import game.BoardState;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementInput;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;

import java.util.*;


public class JointOrders {

    private final int choicesPerUnit;
    private final int beamWidth;


    public JointOrders() {
        this(4, 32);
    }

    public JointOrders(int choicesPerUnit, int beamWidth) {

        if (choicesPerUnit < 1 || beamWidth < 1)
            throw new IllegalArgumentException("Search limits must be positive");

        this.choicesPerUnit = choicesPerUnit;
        this.beamWidth = beamWidth;

    }


    // Candidate generation \\

    public List<OrderPlan> generate(
            BoardState board, Nation nation,
            Map<UnitId, RoutePrediction> predictions) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(nation, "nation");
        Objects.requireNonNull(predictions, "predictions");

        List<UnitId> units = new ArrayList<>();

        for (UnitId unit : board.locations().keySet())
            if (unit.owner() == nation)
                units.add(unit);

        if (units.isEmpty())
            return List.of();

        // Stable across map insertion order and different unit UUIDs.
        units.sort(
                Comparator.comparing((UnitId unit) -> board.locationOf(unit).name())
                        .thenComparing(unit -> unit.unitType().name()));

        Map<UnitId, List<Map.Entry<Order, Long>>> choices = new LinkedHashMap<>();

        for (UnitId unit : units) {

            RoutePrediction prediction = predictions.get(unit);

            if (prediction == null || prediction.counts().isEmpty())
                return List.of();

            List<Map.Entry<Order, Long>> ranked =
                    new ArrayList<>(prediction.counts().entrySet());

            for (Map.Entry<Order, Long> entry : ranked) {

                if (!entry.getKey().unit().equals(unit))
                    throw new IllegalArgumentException(
                            "Prediction order is not bound to its query unit");

                // Structural validation, not a geographical legality test.
                Classifier.signature(board, List.of(entry.getKey()));

            }

            ranked.sort(orderRanking(board));

            choices.put(unit, List.copyOf(
                    ranked.subList(0, Math.min(choicesPerUnit, ranked.size()))));

        }

        List<OrderPlan> beam = List.of(new OrderPlan(nation, List.of(), 0));

        Comparator<OrderPlan> ranking = Comparator
                .comparingDouble(OrderPlan::logPreference).reversed()
                .thenComparing(plan -> Classifier.signature(board, plan.orders()));

        for (UnitId unit : units) {

            RoutePrediction prediction = predictions.get(unit);
            long total = prediction.observations();

            List<OrderPlan> expanded = new ArrayList<>();

            for (OrderPlan partial : beam) {

                for (Map.Entry<Order, Long> choice : choices.get(unit)) {

                    List<Order> orders = new ArrayList<>(partial.orders());
                    orders.add(choice.getKey());

                    // Use all observations as the denominator, not just retained choices.
                    double contribution = Math.log((double) choice.getValue() / total);

                    expanded.add(new OrderPlan(
                            nation,
                            orders,
                            partial.logPreference() + contribution));

                }

            }

            expanded.sort(ranking);

            beam = List.copyOf(
                    expanded.subList(0, Math.min(beamWidth, expanded.size())));

        }

        return beam;

    }

    /**
     * Full production ranking, before the choices-per-unit cutoff.
     */
    public static List<Order> rankedOrders(
            BoardState board, RoutePrediction prediction) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(prediction, "prediction");

        List<Map.Entry<Order, Long>> ranked =
                new ArrayList<>(prediction.counts().entrySet());

        ranked.sort(orderRanking(board));

        return ranked.stream().map(Map.Entry::getKey).toList();

    }

    private static Comparator<Map.Entry<Order, Long>> orderRanking(
            BoardState board) {

        return Map.Entry.<Order, Long>comparingByValue().reversed()
                .thenComparing(entry ->
                        Classifier.signature(board, List.of(entry.getKey())));

    }


    // Evaluate an explicit simultaneous-order scenario. \\

    public MovementResult evaluate(
            BoardState board, OrderPlan plan,
            Collection<? extends Order> opponentOrders,
            MovementProcessor processor) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(opponentOrders, "opponentOrders");
        Objects.requireNonNull(processor, "processor");

        Set<UnitId> nationalUnits = new HashSet<>();

        for (UnitId unit : board.locations().keySet())
            if (unit.owner() == plan.nation())
                nationalUnits.add(unit);

        Set<UnitId> plannedUnits = new HashSet<>();

        for (Order order : plan.orders())
            plannedUnits.add(order.unit());

        if (!plannedUnits.equals(nationalUnits))
            throw new IllegalArgumentException(
                    "Plan must cover exactly the nation's active units");

        List<Order> allOrders = new ArrayList<>(plan.orders());

        for (Order order : opponentOrders) {

            Objects.requireNonNull(order, "opponent order");

            if (order.owner() == plan.nation())
                throw new IllegalArgumentException(
                        "Opponent scenario contains an order for the planned nation");

            allOrders.add(order);

        }

        // Reject duplicate/unknown issuers and malformed order structures.
        Classifier.signature(board, allOrders);

        Set<UnitId> allIssuers = new HashSet<>();

        for (Order order : allOrders)
            allIssuers.add(order.unit());

        if (!allIssuers.equals(board.locations().keySet()))
            throw new IllegalArgumentException(
                    "Opponent scenario must explicitly cover every remaining active unit");

        // MovementProcessor creates private work orders; the input board is unchanged.
        return processor.process(new MovementInput(board.locations(), allOrders));

    }

}