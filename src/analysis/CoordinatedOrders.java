package analysis;

import adjudication.util.Orders;
import analysis.openings.Classifier;
import domain.*;
import game.BoardState;
import phase.Order;
import phase.UnitId;

import java.util.*;

/**
 * Own-country beam search. Retained domains contain only observed orders;
 * forward checks are optimistic until all participating units are assigned.
 */
public final class CoordinatedOrders {

    private final int choicesPerUnit;
    private final int beamWidth;

    public CoordinatedOrders(int choicesPerUnit, int beamWidth) {
        if (choicesPerUnit < 1 || beamWidth < 1)
            throw new IllegalArgumentException("Search limits must be positive");
        this.choicesPerUnit = choicesPerUnit;
        this.beamWidth = beamWidth;
    }

    public Result generate(BoardState board, Nation nation,
                           Map<UnitId, RoutePrediction> predictions,
                           CoordinationMode mode) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(nation, "nation");
        Objects.requireNonNull(predictions, "predictions");
        Objects.requireNonNull(mode, "mode");
        if (mode == CoordinationMode.RAW)
            return new Result(new JointOrders(choicesPerUnit, beamWidth)
                    .generate(board, nation, predictions), Diagnostics.raw(), false);

        List<UnitId> units = board.locations().keySet().stream()
                .filter(unit -> unit.owner() == nation)
                .sorted(unitOrder(board)).toList();
        Map<UnitId, List<Order>> domains = new LinkedHashMap<>();
        long omitted = 0;
        for (UnitId unit : units) {
            RoutePrediction prediction = predictions.get(unit);
            if (prediction == null || prediction.counts().isEmpty())
                return new Result(List.of(), new Diagnostics(mode, 0, 0, omitted,
                        false, List.of("Missing own route evidence."), Map.of()), false);
            for (Order order : prediction.counts().keySet()) {
                if (!order.unit().equals(unit))
                    throw new IllegalArgumentException("Prediction order is not bound to its query unit");
                Classifier.signature(board, List.of(order));
            }
            List<Order> ranked = JointOrders.rankedOrders(board, prediction);
            int retained = Math.min(choicesPerUnit, ranked.size());
            omitted += ranked.size() - retained;
            domains.put(unit, List.copyOf(ranked.subList(0, retained)));
        }
        if (units.isEmpty())
            return new Result(List.of(), new Diagnostics(mode, 0, 0, omitted,
                    false, List.of("The requested nation has no active units."), Map.of()), false);

        List<OrderPlan> beam = List.of(new OrderPlan(nation, List.of(), 0));
        Comparator<OrderPlan> ranking = Comparator
                .comparingDouble(OrderPlan::logPreference).reversed()
                .thenComparing(plan -> Classifier.signature(board, plan.orders()));
        long expandedCount = 0;
        long rejected = 0;
        boolean truncated = false;
        Set<String> reasons = new TreeSet<>();
        for (UnitId unit : units) {
            List<OrderPlan> expanded = new ArrayList<>();
            for (OrderPlan partial : beam) {
                for (Order choice : domains.get(unit)) {
                    expandedCount++;
                    List<Order> orders = new ArrayList<>(partial.orders());
                    orders.add(choice);
                    Check check = check(board, nation, orders, domains, mode);
                    if (check.reason() != null) {
                        rejected++;
                        reasons.add(check.reason());
                        continue;
                    }
                    double contribution = Math.log((double) predictions.get(unit)
                            .counts().get(choice) / predictions.get(unit).observations());
                    expanded.add(new OrderPlan(nation, orders,
                            partial.logPreference() + contribution));
                }
            }
            expanded.sort(ranking);
            truncated |= expanded.size() > beamWidth;
            beam = List.copyOf(expanded.subList(0, Math.min(beamWidth, expanded.size())));
            if (beam.isEmpty())
                break;
        }
        Map<String, List<String>> conditional = new LinkedHashMap<>();
        List<OrderPlan> complete = new ArrayList<>();
        for (OrderPlan plan : beam) {
            if (plan.orders().size() != units.size())
                continue;
            Check check = check(board, nation, plan.orders(), domains, mode);
            if (check.reason() != null) {
                rejected++;
                reasons.add(check.reason());
            } else {
                complete.add(plan);
                if (!check.dependencies().isEmpty())
                    conditional.put(Classifier.signature(board, plan.orders()), check.dependencies());
            }
        }
        Diagnostics diagnostics = new Diagnostics(mode, expandedCount, rejected, omitted,
                truncated, List.copyOf(reasons), conditional);
        return new Result(complete, diagnostics,
                complete.isEmpty() && omitted == 0 && !truncated);
    }

    private static Check check(BoardState board, Nation nation, List<Order> partial,
                               Map<UnitId, List<Order>> domains, CoordinationMode mode) {
        Map<UnitId, Order> assigned = new HashMap<>();
        for (Order order : partial)
            assigned.put(order.unit(), order);
        Set<String> dependencies = new TreeSet<>();
        for (Order order : partial) {
            if (!valid(board, order))
                return Check.reject(label(board, order.unit()) + ": invalid movement order.");
            if (order.orderType() == OrderType.SUPPORT || order.orderType() == OrderType.CONVOY) {
                UnitId target = unitAt(board, order.target());
                if (target == null)
                    return Check.reject(label(board, order.unit()) + ": referenced unit is missing.");
                if (order.orderType() == OrderType.CONVOY && target.unitType() != UnitType.ARMY)
                    return Check.reject(label(board, order.unit()) + ": convoy target is not an army.");
                // Judge matches exact encoded positions, even for split coasts.
                if (order.target() != board.locationOf(target))
                    return Check.reject(label(board, order.unit()) + ": referenced coast does not match unit position.");
                if (target.owner() != nation) {
                    String dependency = required(board, order, target);
                    if (mode == CoordinationMode.STRICT)
                        return Check.reject("Foreign cooperation required: " + dependency);
                    dependencies.add(dependency);
                } else {
                    List<Order> options = options(target, assigned, domains);
                    if (options.stream().noneMatch(candidate -> agrees(order, candidate)
                            && valid(board, candidate)))
                        return Check.reject(label(board, order.unit()) + ": "
                                + order.orderType() + " disagrees with "
                                + label(board, target) + " move/destination or stationary order.");
                }
                if (order.orderType() == OrderType.CONVOY) {
                    Order move = Order.move(target, order.auxiliaryTarget());
                    Check route = route(board, nation, move, assigned, domains, mode, true);
                    if (route.reason() != null)
                        return route;
                    dependencies.addAll(route.dependencies());
                }
            }
            if (order.orderType() == OrderType.MOVE && order.unitType() == UnitType.ARMY
                    && !board.locationOf(order.unit()).isAdjacentTo(order.target())) {
                Check route = route(board, nation, order, assigned, domains, mode, false);
                if (route.reason() != null)
                    return route;
                dependencies.addAll(route.dependencies());
            }
        }
        return new Check(null, List.copyOf(dependencies));
    }

    private static boolean agrees(Order reference, Order selected) {
        if (reference.auxiliaryTarget() == null)
            return selected.orderType() != OrderType.MOVE;
        return selected.orderType() == OrderType.MOVE
                && reference.auxiliaryTarget() == selected.target();
    }

    private static List<Order> options(UnitId unit, Map<UnitId, Order> assigned,
                                       Map<UnitId, List<Order>> domains) {
        return assigned.containsKey(unit) ? List.of(assigned.get(unit))
                : domains.getOrDefault(unit, List.of());
    }

    private static Check route(BoardState board, Nation nation, Order move,
                               Map<UnitId, Order> assigned, Map<UnitId, List<Order>> domains,
                               CoordinationMode mode, boolean explicitConvoy) {
        if (!valid(board, move))
            return Check.reject("Invalid convoyed army move.");
        List<UnitId> fleets = board.locations().keySet().stream()
                .filter(unit -> unit.unitType() == UnitType.FLEET
                        && board.locationOf(unit).geography == Geography.WATER)
                .sorted(unitOrder(board)).toList();
        List<UnitId> available = new ArrayList<>();
        List<UnitId> foreign = new ArrayList<>();
        for (UnitId fleet : fleets) {
            if (fleet.owner() != nation) {
                foreign.add(fleet);
                continue;
            }
            if (options(fleet, assigned, domains).stream().anyMatch(order ->
                    order.orderType() == OrderType.CONVOY
                            && order.target() == board.locationOf(move.unit())
                            && order.auxiliaryTarget() == move.target()
                            && valid(board, order)))
                available.add(fleet);
        }
        List<UnitId> path = path(board, move, available);
        if (!path.isEmpty())
            return new Check(null, List.of());
        available.addAll(foreign);
        path = path(board, move, available);
        if (path.isEmpty())
            return Check.reject(label(board, move.unit()) + " -> " + move.target()
                    + ": no connected matching convoy chain in retained domains"
                    + (explicitConvoy ? " for convoy order." : "."));
        List<String> dependencies = path.stream().filter(unit -> unit.owner() != nation)
                .map(unit -> label(board, unit) + " CONVOY "
                        + board.locationOf(move.unit()) + " -> " + move.target()).toList();
        if (mode == CoordinationMode.STRICT)
            return Check.reject("Foreign cooperation required: " + String.join("; ", dependencies));
        return new Check(null, dependencies);
    }

    // Polynomial reachability using the same exact adjacency and matching
    // endpoints as Convoys.drawConvoyPath, without enumerating convoy paths.
    private static List<UnitId> path(BoardState board, Order move, List<UnitId> fleets) {
        Map<UnitId, UnitId> previous = new LinkedHashMap<>();
        Deque<UnitId> queue = new ArrayDeque<>();
        for (UnitId fleet : fleets) {
            if (board.locationOf(fleet).isAdjacentTo(board.locationOf(move.unit()))) {
                previous.put(fleet, null);
                queue.add(fleet);
            }
        }
        while (!queue.isEmpty()) {
            UnitId current = queue.remove();
            if (board.locationOf(current).isAdjacentTo(move.target())) {
                List<UnitId> path = new ArrayList<>();
                for (UnitId step = current; step != null; step = previous.get(step))
                    path.add(step);
                Collections.reverse(path);
                return path;
            }
            for (UnitId next : fleets) {
                if (!previous.containsKey(next)
                        && board.locationOf(current).isAdjacentTo(board.locationOf(next))) {
                    previous.put(next, current);
                    queue.add(next);
                }
            }
        }
        return List.of();
    }

    private static boolean valid(BoardState board, Order order) {
        return Orders.orderIsValid(new adjudication.Order(order, board.locationOf(order.unit())));
    }

    private static UnitId unitAt(BoardState board, Province province) {
        for (var entry : board.locations().entrySet())
            if (Province.canonical(entry.getValue()) == Province.canonical(province))
                return entry.getKey();
        return null;
    }

    private static Comparator<UnitId> unitOrder(BoardState board) {
        return Comparator.comparing((UnitId unit) -> board.locationOf(unit).name())
                .thenComparing(unit -> unit.owner().name())
                .thenComparing(unit -> unit.unitType().name());
    }

    private static String label(BoardState board, UnitId unit) {
        return unit.owner() + " " + unit.unitType() + " " + board.locationOf(unit);
    }

    private static String required(BoardState board, Order order, UnitId target) {
        return label(board, target) + (order.auxiliaryTarget() == null
                ? " stationary non-MOVE order" : " MOVE -> " + order.auxiliaryTarget());
    }

    private record Check(String reason, List<String> dependencies) {
        static Check reject(String reason) {
            return new Check(reason, List.of());
        }
    }

    public record Result(List<OrderPlan> plans, Diagnostics diagnostics,
                         boolean exhaustedRetainedDomains) {
        public Result {
            plans = List.copyOf(plans);
            Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    public record Diagnostics(CoordinationMode mode, long expandedCandidates,
                              long rejectedCandidates, long omittedChoices,
                              boolean beamTruncated, List<String> reasons,
                              Map<String, List<String>> conditionalPlans) {
        public Diagnostics {
            Objects.requireNonNull(mode, "mode");
            reasons = List.copyOf(reasons);
            Map<String, List<String>> copy = new LinkedHashMap<>();
            conditionalPlans.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            conditionalPlans = Collections.unmodifiableMap(copy);
        }

        public static Diagnostics raw() {
            return new Diagnostics(CoordinationMode.RAW, 0, 0, 0, false, List.of(), Map.of());
        }
    }
}
