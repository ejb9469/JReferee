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
 * Positive tactical bias sorts by selected offending MOVE count before historical
 * preference. Potential stationary collision targets are assigned first within
 * related-unit groups, expanded together before the requested beam cutoff;
 * intermediate groups retain at most max(256, beamWidth) partials. This bounded
 * lookahead is not exhaustive and cannot recover omitted domain choices.
 * Optional geography replenishes historical top-K with at most K observed
 * positional choices and ranks partials with an optimistic destination hint.
 * These hints never replace actual adjudicated scoring or historical counts.
 * RAW always uses historical generation, even with an explicit positive bias.
 */
public final class CoordinatedOrders {

    public static final int FOREIGN_ASSIGNMENT_LIMIT = 4096;
    public static final int GEOGRAPHIC_CHOICE_LIMIT = 64;

    private final int choicesPerUnit;
    private final int beamWidth;
    private final double tacticalBiasWeight;
    private final ScoringConfiguration scoringConfiguration;

    public CoordinatedOrders(int choicesPerUnit, int beamWidth) {
        this(choicesPerUnit, beamWidth, 0);
    }

    public CoordinatedOrders(int choicesPerUnit, int beamWidth, double tacticalBiasWeight) {
        this(choicesPerUnit, beamWidth, tacticalBiasWeight, ScoringConfiguration.defaults());
    }

    public CoordinatedOrders(int choicesPerUnit, int beamWidth, double tacticalBiasWeight,
                             ScoringConfiguration scoringConfiguration) {
        if (choicesPerUnit < 1 || beamWidth < 1)
            throw new IllegalArgumentException("Search limits must be positive");
        this.choicesPerUnit = choicesPerUnit;
        this.beamWidth = beamWidth;
        TacticalBias.validateWeight(tacticalBiasWeight);
        this.tacticalBiasWeight = tacticalBiasWeight;
        this.scoringConfiguration = Objects.requireNonNull(scoringConfiguration);
    }

    /** Exact complete-plan check; does not manufacture route evidence. */
    public static Optional<String> coordinationProblem(BoardState board, OrderPlan plan) {
        Map<UnitId, List<Order>> domains = new LinkedHashMap<>();
        for (Order order : plan.orders())
            domains.put(order.unit(), List.of(order));
        Set<UnitId> expected = new HashSet<>();
        board.locations().keySet().stream().filter(unit -> unit.owner() == plan.nation())
                .forEach(expected::add);
        if (!domains.keySet().equals(expected))
            return Optional.of("Plan must name every active friendly unit exactly once.");
        return Optional.ofNullable(check(board, plan.nation(), plan.orders(), domains,
                CoordinationMode.CONDITIONAL).reason());
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
        long replenished = 0;
        long guidanceUnexamined = 0;
        boolean guided = scoringConfiguration.hasGeography(nation);
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
            Set<Order> choices = new LinkedHashSet<>(ranked.subList(0, retained));
            if (guided) {
                Map<Order, Double> hints = new HashMap<>();
                Map<Order, Integer> historicalRanks = new HashMap<>();
                int examined = Math.min(GEOGRAPHIC_CHOICE_LIMIT, ranked.size());
                guidanceUnexamined += ranked.size() - examined;
                for (int index = 0; index < examined; index++) {
                    Order order = ranked.get(index);
                    historicalRanks.put(order, index);
                    hints.put(order, PositionShaping.optimisticDelta(board,
                            new OrderPlan(nation, List.of(order), 0), scoringConfiguration));
                }
                List<Order> positional = new ArrayList<>(ranked.subList(0, examined));
                positional.sort(Comparator.comparingDouble((Order order) ->
                        hints.get(order)).reversed().thenComparingInt(historicalRanks::get));
                choices.addAll(positional.subList(0, Math.min(retained, examined)));
            }
            replenished += choices.size() - retained;
            omitted += ranked.size() - choices.size();
            domains.put(unit, List.copyOf(choices));
        }
        if (units.isEmpty())
            return new Result(List.of(), new Diagnostics(mode, 0, 0, omitted,
                    false, List.of("The requested nation has no active units."), Map.of()), false);

        List<OrderPlan> beam = List.of(new OrderPlan(nation, List.of(), 0));
        Comparator<OrderPlan> ranking = Comparator
                .comparingDouble(OrderPlan::logPreference).reversed()
                .thenComparing(plan -> Classifier.signature(board, plan.orders()));
        Map<OrderPlan, Double> planHints = new HashMap<>();
        if (guided) {
            int ownCount = units.size();
            ranking = Comparator.comparingDouble((OrderPlan plan) ->
                    planHints.computeIfAbsent(plan, candidate ->
                            PositionShaping.optimisticDelta(board, candidate, scoringConfiguration)
                                    + (1 + scoringConfiguration.humanWeight() / ownCount)
                                    * candidate.logPreference())).reversed().thenComparing(ranking);
        }
        Set<UnitId> groupEnds = new HashSet<>();
        if (tacticalBiasWeight > 0) {
            ranking = Comparator.comparingInt((OrderPlan plan) ->
                    TacticalBias.offendingMoveCount(board, plan)).thenComparing(ranking);
            units = dependencyOrder(board, units, domains, groupEnds);
        }
        long expandedCount = 0;
        long rejected = 0;
        boolean truncated = false;
        boolean dependencyTruncated = false;
        long dependencyAssignments = 0;
        Set<String> reasons = new TreeSet<>();
        for (UnitId unit : units) {
            List<OrderPlan> expanded = new ArrayList<>();
            for (OrderPlan partial : beam) {
                for (Order choice : domains.get(unit)) {
                    expandedCount++;
                    List<Order> orders = new ArrayList<>(partial.orders());
                    orders.add(choice);
                    Check check = check(board, nation, orders, domains, mode);
                    dependencyTruncated |= check.limited();
                    dependencyAssignments += check.examined();
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
            planHints.clear();
            int retainedWidth = tacticalBiasWeight > 0 && !groupEnds.contains(unit)
                    ? Math.max(256, beamWidth) : beamWidth;
            truncated |= expanded.size() > retainedWidth;
            beam = List.copyOf(expanded.subList(0, Math.min(retainedWidth, expanded.size())));
            if (beam.isEmpty())
                break;
        }

        Map<String, List<String>> conditional = new LinkedHashMap<>();
        Map<String, ForeignDependencies> structured = new LinkedHashMap<>();
        List<OrderPlan> complete = new ArrayList<>();
        for (OrderPlan plan : beam) {
            if (plan.orders().size() != units.size())
                continue;
            Check check = check(board, nation, plan.orders(), domains, mode);
            dependencyTruncated |= check.limited();
            dependencyAssignments += check.examined();
            if (check.reason() != null) {
                rejected++;
                reasons.add(check.reason());
            } else {
                complete.add(plan);
                if (!check.dependencies().isEmpty())
                    conditional.put(Classifier.signature(board, plan.orders()), check.dependencies());
                structured.put(Classifier.signature(board, plan.orders()), check.structured());
            }
        }
        Diagnostics diagnostics = new Diagnostics(mode, expandedCount, rejected, omitted,
                truncated, List.copyOf(reasons), conditional,
                dependencyAssignments, dependencyTruncated, structured, guided, replenished,
                guidanceUnexamined);
        return new Result(complete, diagnostics,
                complete.isEmpty() && omitted == 0 && !truncated && !dependencyTruncated);
    }

    private static List<UnitId> dependencyOrder(BoardState board, List<UnitId> units,
                                                Map<UnitId, List<Order>> domains,
                                                Set<UnitId> groupEnds) {
        Map<UnitId, Set<UnitId>> edges = new LinkedHashMap<>();
        Set<UnitId> stationaryTargets = new HashSet<>();
        for (UnitId unit : units)
            edges.put(unit, new HashSet<>());
        for (UnitId unit : units) {
            for (Order order : domains.get(unit)) {
                if (order.target() == null)
                    continue;
                for (UnitId other : units) {
                    if (unit.equals(other) || Province.canonical(order.target())
                            != Province.canonical(board.locationOf(other)))
                        continue;
                    boolean reference = order.orderType() == OrderType.SUPPORT
                            || order.orderType() == OrderType.CONVOY;
                    boolean tactical = order.orderType() == OrderType.MOVE
                            && domains.get(other).stream().anyMatch(candidate ->
                            candidate.orderType() == OrderType.SUPPORT
                                    || candidate.orderType() == OrderType.CONVOY);
                    if (tactical)
                        stationaryTargets.add(other);
                    if (reference || tactical) {
                        edges.get(unit).add(other);
                        edges.get(other).add(unit);
                    }
                }
            }
        }
        List<UnitId> ordered = new ArrayList<>();
        Set<UnitId> visited = new HashSet<>();
        for (UnitId start : units) {
            if (!visited.add(start))
                continue;
            Set<UnitId> component = new HashSet<>();
            Deque<UnitId> pending = new ArrayDeque<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                UnitId next = pending.removeFirst();
                component.add(next);
                for (UnitId neighbor : edges.get(next))
                    if (visited.add(neighbor))
                        pending.addLast(neighbor);
            }
            List<UnitId> group = units.stream().filter(component::contains)
                    .sorted(Comparator.comparing((UnitId unit) -> !stationaryTargets.contains(unit))
                            .thenComparing(unitOrder(board))).toList();
            ordered.addAll(group);
            groupEnds.add(group.getLast());
        }
        return List.copyOf(ordered);
    }
    private static Check check(BoardState board, Nation nation, List<Order> partial,
                               Map<UnitId, List<Order>> domains, CoordinationMode mode) {
        Map<UnitId, Order> assigned = new HashMap<>();
        for (Order order : partial)
            assigned.put(order.unit(), order);
        Set<String> dependencies = new TreeSet<>();
        List<ForeignDependencies.Requirement> requirements = new ArrayList<>();
        Map<UnitId, Order> foreignMoves = new LinkedHashMap<>();
        Set<UnitId> foreignStationary = new HashSet<>();
        Map<UnitId, Order> routes = new LinkedHashMap<>();
        Map<UnitId, List<Order>> friendlyObligations = new HashMap<>();
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
                    requirements.add(new ForeignDependencies.Requirement(target,
                            board.locationOf(target), order.auxiliaryTarget() == null
                            ? ForeignDependencies.Constraint.STATIONARY
                            : ForeignDependencies.Constraint.EXACT_ORDER,
                            order.auxiliaryTarget() == null ? null : OrderType.MOVE,
                            order.auxiliaryTarget(), null, List.of(order), "MOVEMENT"));
                    if (order.auxiliaryTarget() == null) {
                        foreignStationary.add(target);
                    } else {
                        Order requiredMove = Order.move(target, order.auxiliaryTarget());
                        Order previous = foreignMoves.putIfAbsent(target, requiredMove);
                        if (previous != null && !previous.equals(requiredMove))
                            return Check.reject("Conflicting foreign MOVE requirements for "
                                    + label(board, target) + ".");
                    }
                } else {
                    List<Order> obligations = friendlyObligations.computeIfAbsent(
                            target, ignored -> new ArrayList<>());
                    obligations.add(order);
                    List<Order> options = options(target, assigned, domains);
                    if (options.stream().noneMatch(candidate -> valid(board, candidate)
                            && obligations.stream().allMatch(reference -> agrees(reference, candidate))))
                        return Check.reject(label(board, order.unit()) + ": "
                                + order.orderType() + " disagrees with "
                                + label(board, target) + " move/destination or stationary order.");
                }
                if (order.orderType() == OrderType.CONVOY) {
                    Order move = Order.move(target, order.auxiliaryTarget());
                    routes.put(target, move);
                } else if (order.auxiliaryTarget() != null && target.unitType() == UnitType.ARMY
                        && !board.locationOf(target).isAdjacentTo(order.auxiliaryTarget())) {
                    routes.put(target, Order.move(target, order.auxiliaryTarget()));
                }
            }
            if (order.orderType() == OrderType.MOVE && order.unitType() == UnitType.ARMY
                    && !board.locationOf(order.unit()).isAdjacentTo(order.target())) {
                routes.put(order.unit(), order);
            }
        }
        for (Order move : foreignMoves.values()) {
            if (foreignStationary.contains(move.unit()))
                return Check.reject("Conflicting foreign stationary/MOVE requirements for "
                        + label(board, move.unit()) + ".");
            if (!valid(board, move))
                return Check.reject("Invalid required foreign MOVE for " + label(board, move.unit()) + ".");
            if (move.unitType() == UnitType.ARMY
                    && !board.locationOf(move.unit()).isAdjacentTo(move.target()))
                routes.put(move.unit(), move);
        }
        if (mode == CoordinationMode.CONDITIONAL && partial.size() == domains.size()
                && !routes.isEmpty()) {
            ForeignSearch search = new ForeignSearch(board, nation, assigned, domains,
                    List.copyOf(routes.values()), foreignMoves.keySet());
            if (!search.find(0, new HashMap<>())) {
                return new Check(search.limited
                        ? "Foreign cooperation assignment search exhausted its "
                        + FOREIGN_ASSIGNMENT_LIMIT + " node limit."
                        : "No consistent simultaneous foreign convoy orders can supply the required routes.",
                        List.of(), search.examined, search.limited);
            }
            dependencies.addAll(search.dependencies);
            ForeignSearch alternatives = new ForeignSearch(board, nation, assigned, domains,
                    List.copyOf(routes.values()), foreignMoves.keySet());
            alternatives.collect = true;
            alternatives.examined = search.examined;
            alternatives.alternatives.addAll(search.alternatives);
            alternatives.find(0, new HashMap<>());
            List<List<ForeignDependencies.Requirement>> conjunctions = new ArrayList<>();
            for (List<ForeignDependencies.Requirement> routeRequirements : alternatives.alternatives) {
                List<ForeignDependencies.Requirement> conjunction = new ArrayList<>(requirements);
                conjunction.addAll(routeRequirements);
                conjunctions.add(conjunction);
            }
            return new Check(null, List.copyOf(dependencies),
                    alternatives.examined, search.limited || alternatives.limited,
                    new ForeignDependencies(conjunctions, alternatives.limited));
        }
        for (Order move : routes.values()) {
            Check route = route(board, nation, move, assigned, domains, mode, foreignMoves.keySet());
            if (route.reason() != null)
                return route;
            dependencies.addAll(route.dependencies());
        }
        return new Check(null, List.copyOf(dependencies), 0, false,
                new ForeignDependencies(List.of(requirements), false));
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
                               CoordinationMode mode, Set<UnitId> movingForeign) {
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
                if (!movingForeign.contains(fleet))
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
                    + ": no connected matching convoy chain in retained domains.");
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
        if (!Orders.orderIsValid(new adjudication.Order(order, board.locationOf(order.unit()))))
            return false;
        return order.orderType() != OrderType.MOVE || order.unitType() != UnitType.FLEET
                || board.locationOf(order.unit()).geography != Geography.COASTAL
                || order.target().geography != Geography.COASTAL
                || Province.adjacentBySea(board.locationOf(order.unit()), order.target());
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

    /**
     * Foreign orders are assumptions, not evidence or generated submissions.
     * Explore a bounded assignment, rather than promising mutually exclusive
     * convoy orders from the same foreign fleet.
     */
    private static final class ForeignSearch {
        private final BoardState board;
        private final Nation nation;
        private final Map<UnitId, Order> own;
        private final Map<UnitId, List<Order>> domains;
        private final List<Order> routes;
        private final List<UnitId> fleets;
        private long examined;
        private boolean limited;
        private List<String> dependencies = List.of();
        private boolean collect;
        private final List<List<ForeignDependencies.Requirement>> alternatives = new ArrayList<>();

        ForeignSearch(BoardState board, Nation nation, Map<UnitId, Order> own,
                      Map<UnitId, List<Order>> domains, List<Order> routes,
                      Set<UnitId> movingForeign) {
            this.board = board;
            this.nation = nation;
            this.own = own;
            this.domains = domains;
            this.routes = routes;
            this.fleets = board.locations().keySet().stream()
                    .filter(unit -> unit.owner() != nation
                            && unit.unitType() == UnitType.FLEET
                            && board.locationOf(unit).geography == Geography.WATER
                            && !movingForeign.contains(unit))
                    .sorted(unitOrder(board)).toList();
        }

        boolean find(int depth, Map<UnitId, Order> assumptions) {
            if (examined == FOREIGN_ASSIGNMENT_LIMIT) {
                limited = true;
                return false;
            }
            examined++;
            Set<String> needed = new TreeSet<>();
            Map<UnitId, ForeignDependencies.Requirement> structured = new LinkedHashMap<>();
            boolean supplied = true;
            for (Order move : routes) {
                List<UnitId> available = new ArrayList<>();
                for (UnitId unit : board.locations().keySet().stream()
                        .sorted(unitOrder(board)).toList()) {
                    if (unit.unitType() != UnitType.FLEET
                            || board.locationOf(unit).geography != Geography.WATER)
                        continue;
                    if (unit.owner() == nation) {
                        if (options(unit, own, domains).stream()
                                .anyMatch(order -> matches(order, move)))
                            available.add(unit);
                    } else if (assumptions.containsKey(unit)
                            && matches(assumptions.get(unit), move)) {
                        available.add(unit);
                    }
                }
                List<UnitId> selected = path(board, move, available);
                if (selected.isEmpty()) {
                    supplied = false;
                    for (UnitId fleet : fleets)
                        if (!assumptions.containsKey(fleet))
                            available.add(fleet);
                    if (path(board, move, available).isEmpty())
                        return false;
                } else {
                    for (UnitId fleet : selected)
                        if (fleet.owner() != nation) {
                            needed.add(label(board, fleet) + " CONVOY "
                                    + board.locationOf(move.unit()) + " -> " + move.target());
                            Order required = assumptions.get(fleet);
                            List<Order> affected = own.values().stream()
                                    .filter(order -> order.equals(move)
                                            || order.target() == board.locationOf(move.unit())
                                            && order.auxiliaryTarget() == move.target())
                                    .sorted(Comparator.comparing(order ->
                                            Classifier.signature(board, List.of(order)))).toList();
                            ForeignDependencies.Requirement previous = structured.get(fleet);
                            if (previous != null) {
                                List<Order> combined = new ArrayList<>(previous.affectedFriendlyOrders());
                                combined.addAll(affected);
                                affected = combined.stream().distinct()
                                        .sorted(Comparator.comparing(order ->
                                                Classifier.signature(board, List.of(order)))).toList();
                            }
                            structured.put(fleet, new ForeignDependencies.Requirement(fleet,
                                    board.locationOf(fleet), ForeignDependencies.Constraint.EXACT_ORDER,
                                    OrderType.CONVOY, required.target(), required.auxiliaryTarget(),
                                    affected, "MOVEMENT"));
                        }
                }
            }
            if (supplied) {
                dependencies = List.copyOf(needed);
                alternatives.add(List.copyOf(structured.values()));
                return !collect;
            }
            if (depth == fleets.size())
                return false;
            UnitId fleet = fleets.get(depth);
            assumptions.put(fleet, null); // No convoy assumption for this fleet.
            if (find(depth + 1, assumptions))
                return true;
            for (Order move : routes) {
                if (limited)
                    break;
                assumptions.put(fleet, Order.convoy(fleet,
                        board.locationOf(move.unit()), move.target()));
                if (find(depth + 1, assumptions))
                    return true;
            }
            assumptions.remove(fleet);
            return false;
        }

        private boolean matches(Order order, Order move) {
            return order != null && order.orderType() == OrderType.CONVOY
                    && order.target() == board.locationOf(move.unit())
                    && order.auxiliaryTarget() == move.target() && valid(board, order);
        }
    }

    private record Check(String reason, List<String> dependencies, long examined, boolean limited,
                         ForeignDependencies structured) {
        Check(String reason, List<String> dependencies) {
            this(reason, dependencies, 0, false, new ForeignDependencies(List.of(List.of()), false));
        }
        Check(String reason, List<String> dependencies, long examined, boolean limited) {
            this(reason, dependencies, examined, limited, new ForeignDependencies(List.of(), limited));
        }

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
                              Map<String, List<String>> conditionalPlans,
                              long dependencyAssignmentsExamined,
                              boolean dependencySearchTruncated,
                              Map<String, ForeignDependencies> foreignDependencies,
                              boolean geographyGuided, long replenishedChoices,
                              long guidanceChoicesUnexamined) {
        public Diagnostics(CoordinationMode mode, long expandedCandidates,
                           long rejectedCandidates, long omittedChoices,
                           boolean beamTruncated, List<String> reasons,
                           Map<String, List<String>> conditionalPlans,
                           long dependencyAssignmentsExamined, boolean dependencySearchTruncated,
                           Map<String, ForeignDependencies> foreignDependencies,
                           boolean geographyGuided, long replenishedChoices) {
            this(mode, expandedCandidates, rejectedCandidates, omittedChoices, beamTruncated,
                    reasons, conditionalPlans, dependencyAssignmentsExamined,
                    dependencySearchTruncated, foreignDependencies, geographyGuided,
                    replenishedChoices, 0);
        }
        public Diagnostics(CoordinationMode mode, long expandedCandidates,
                           long rejectedCandidates, long omittedChoices,
                           boolean beamTruncated, List<String> reasons,
                           Map<String, List<String>> conditionalPlans,
                           long dependencyAssignmentsExamined, boolean dependencySearchTruncated,
                           Map<String, ForeignDependencies> foreignDependencies) {
            this(mode, expandedCandidates, rejectedCandidates, omittedChoices, beamTruncated,
                    reasons, conditionalPlans, dependencyAssignmentsExamined,
                    dependencySearchTruncated, foreignDependencies, false, 0);
        }
        public Diagnostics(CoordinationMode mode, long expandedCandidates,
                           long rejectedCandidates, long omittedChoices,
                           boolean beamTruncated, List<String> reasons,
                           Map<String, List<String>> conditionalPlans,
                           long dependencyAssignmentsExamined, boolean dependencySearchTruncated) {
            this(mode, expandedCandidates, rejectedCandidates, omittedChoices, beamTruncated,
                    reasons, conditionalPlans, dependencyAssignmentsExamined,
                    dependencySearchTruncated, Map.of());
        }
        public Diagnostics(CoordinationMode mode, long expandedCandidates,
                           long rejectedCandidates, long omittedChoices,
                           boolean beamTruncated, List<String> reasons,
                           Map<String, List<String>> conditionalPlans) {
            this(mode, expandedCandidates, rejectedCandidates, omittedChoices,
                    beamTruncated, reasons, conditionalPlans, 0, false);
        }
        public Diagnostics {
            Objects.requireNonNull(mode, "mode");
            reasons = List.copyOf(reasons);
            Map<String, List<String>> copy = new LinkedHashMap<>();
            conditionalPlans.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            conditionalPlans = Collections.unmodifiableMap(copy);
            foreignDependencies = Collections.unmodifiableMap(new LinkedHashMap<>(foreignDependencies));
        }

        public boolean dependenciesChecked() { return mode != CoordinationMode.RAW; }

        public static Diagnostics raw() {
            return new Diagnostics(CoordinationMode.RAW, 0, 0, 0, false, List.of(), Map.of());
        }
    }
}
