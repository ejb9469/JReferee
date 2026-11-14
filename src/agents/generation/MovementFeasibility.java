package agents.generation;

import contracts.OrderForms;
import domain.*;
import game.GameState_Movement;
import phase.Order;
import phase.UnitId;

import java.util.*;

/**
 * Own-order correspondence and possible convoy routes at a movement root.
 * Foreign orders and fleet survival remain unknown. Collisions are warnings.
 */
public final class MovementFeasibility {

    public enum Kind { BLOCKER, DEPENDENCY, CAUTION }
    public enum Code {
        OWN_SUPPORT_MISMATCH, OWN_CONVOY_MISMATCH, NO_CONVOY_ROUTE, CONVOY_OFF_ROUTE,
        FOREIGN_SUPPORT, FOREIGN_CONVOY, SHARED_DESTINATION, OWN_STATIONARY_DESTINATION, DIRECT_SWAP
    }
    public record Finding(int orderIndex, Kind kind, Code code, String message) {
        public Finding {
            if (orderIndex < 0) throw new IllegalArgumentException("Negative order index");
            Objects.requireNonNull(kind); Objects.requireNonNull(code); Objects.requireNonNull(message);
        }
    }

    private final GameState_Movement root;
    private final LegalOrderCatalog catalog;
    private final Map<Province, UnitId> occupants = new EnumMap<>(Province.class);
    private final Map<UnitId, List<Order>> options = new LinkedHashMap<>();
    private final List<UnitId> seaFleets;
    private final Map<Route, Boolean> routes = new HashMap<>();
    private record Route(Province origin, Province destination, int mask, int through) { }

    public MovementFeasibility(GameState_Movement root, LegalOrderCatalog catalog) {
        this.root = Objects.requireNonNull(root);
        this.catalog = Objects.requireNonNull(catalog);
        catalog.requireMatches(root, catalog.power());
        root.board().locations().forEach((unit, at) -> occupants.put(Province.canonical(at), unit));
        catalog.optionsByUnit().forEach((unit, choices) ->
                options.put(unit, choices.stream().map(form -> (Order) form).toList()));
        seaFleets = root.board().locations().keySet().stream()
                .filter(unit -> unit.unitType() == UnitType.FLEET && location(unit).geography == Geography.WATER)
                .sorted(Comparator.comparing(unit -> location(unit).name())).toList();
        if (seaFleets.size() > 30) throw new IllegalArgumentException("Expected the standard map");
    }

    /**
     * Looks ahead through the remaining catalog choices. A true result does
     * not promise that all future requirements can be satisfied together.
     */
    boolean compatible(Map<UnitId, Order> assigned) {
        Map<UnitId, List<Order>> remaining = new LinkedHashMap<>();
        options.forEach((unit, choices) -> remaining.put(unit,
                assigned.containsKey(unit) ? List.of(assigned.get(unit)) : choices));
        for (Order order : assigned.values()) {
            if (order.orderType() != OrderType.SUPPORT && order.orderType() != OrderType.CONVOY) continue;
            UnitId recipient = occupant(order.target());
            if (recipient == null) return false;
            if (recipient.owner() != catalog.power()) continue;
            List<Order> choices = remaining.get(recipient).stream().filter(candidate -> matches(order, candidate)).toList();
            if (choices.isEmpty()) return false;
            remaining.put(recipient, choices);
        }
        for (Order order : assigned.values()) {
            Order move = transport(order);
            if (move != null && !route(move, remaining, order.orderType() == OrderType.CONVOY ? order.unit() : null, true))
                return false;
        }
        return true;
    }

    public List<Finding> inspect(ActionCandidate candidate) {
        var errors = new CatalogActionValidator().validate(root, candidate, catalog);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid action: " + errors);
        Map<UnitId, List<Order>> submitted = new LinkedHashMap<>();
        List<Order> orders = candidate.orders().stream().map(form -> (Order) form).toList();
        orders.forEach(order -> submitted.put(order.unit(), List.of(order)));
        List<Finding> findings = new ArrayList<>();
        for (int index = 0; index < orders.size(); index++) {
            Order order = orders.get(index);
            if (order.orderType() == OrderType.SUPPORT || order.orderType() == OrderType.CONVOY) {
                UnitId recipient = occupant(order.target());
                if (recipient.owner() == candidate.power()) {
                    Order actual = submitted.get(recipient).getFirst();
                    if (!matches(order, actual))
                        add(findings, index, Kind.BLOCKER,
                                order.orderType() == OrderType.SUPPORT ? Code.OWN_SUPPORT_MISMATCH : Code.OWN_CONVOY_MISMATCH,
                                "Referenced own unit orders " + describe(actual));
                } else {
                    add(findings, index, Kind.DEPENDENCY,
                            order.orderType() == OrderType.SUPPORT ? Code.FOREIGN_SUPPORT : Code.FOREIGN_CONVOY,
                            "Requires a matching order from " + recipient.owner());
                }
            }
            Order move = transport(order);
            if (move != null) {
                UnitId through = order.orderType() == OrderType.CONVOY ? order.unit() : null;
                if (!route(move, submitted, through, true)) {
                    add(findings, index, Kind.BLOCKER,
                            through == null ? Code.NO_CONVOY_ROUTE : Code.CONVOY_OFF_ROUTE,
                            through == null ? "No convoy route with these own orders, even allowing foreign cooperation"
                                    : "This fleet is on no compatible convoy route");
                } else if (!route(move, submitted, through, false)) {
                    add(findings, index, Kind.DEPENDENCY, Code.FOREIGN_CONVOY,
                            "A possible route requires foreign convoy orders");
                }
            }
            if (order.orderType() != OrderType.MOVE) continue;
            for (int other = 0; other < orders.size(); other++) {
                Order second = orders.get(other);
                if (other != index && second.orderType() == OrderType.MOVE
                        && Province.equalsIgnoreCoast(order.target(), second.target()))
                    add(findings, index, Kind.CAUTION, Code.SHARED_DESTINATION,
                            "Also targeted by " + location(second.unit()) + "; may be an intended self-bounce");
            }
            UnitId occupant = occupant(order.target());
            if (occupant == null || occupant.owner() != candidate.power()) continue;
            Order other = submitted.get(occupant).getFirst();
            if (other.orderType() != OrderType.MOVE)
                add(findings, index, Kind.CAUTION, Code.OWN_STATIONARY_DESTINATION,
                        "Own unit at " + order.target() + " is not ordered to leave");
            else if (Province.equalsIgnoreCoast(other.target(), location(order.unit()))
                    && location(order.unit()).isAdjacentTo(order.target())
                    && location(other.unit()).isAdjacentTo(other.target())) {
                if (!routeForAdjacentArmy(order, submitted, true) && !routeForAdjacentArmy(other, submitted, true))
                    add(findings, index, Kind.CAUTION, Code.DIRECT_SWAP,
                            "Opposing direct moves between own units cannot exchange places");
                else if (!routeForAdjacentArmy(order, submitted, false) && !routeForAdjacentArmy(other, submitted, false))
                    add(findings, index, Kind.DEPENDENCY, Code.FOREIGN_CONVOY,
                            "Exchanging these own units requires foreign convoy orders");
            }
        }
        return List.copyOf(findings);
    }

    private boolean routeForAdjacentArmy(Order order, Map<UnitId, List<Order>> available, boolean foreign) {
        return order.unitType() == UnitType.ARMY && route(order, available, null, foreign);
    }

    private static void add(List<Finding> findings, int index, Kind kind, Code code, String message) {
        findings.add(new Finding(index, kind, code, message));
    }

    private boolean matches(Order reference, Order actual) {
        if (reference.orderType() == OrderType.SUPPORT)
            return OrderForms.supportMatches(reference, actual, location(actual.unit()));
        return actual.unitType() == UnitType.ARMY && actual.orderType() == OrderType.MOVE
                && Province.equalsIgnoreCoast(reference.auxiliaryTarget(), actual.target());
    }

    private Order transport(Order order) {
        UnitId army = order.orderType() == OrderType.MOVE ? order.unit()
                : order.orderType() == OrderType.SUPPORT || order.orderType() == OrderType.CONVOY ? occupant(order.target()) : null;
        Province destination = order.orderType() == OrderType.MOVE ? order.target() : order.auxiliaryTarget();
        if (army == null || army.unitType() != UnitType.ARMY || destination == null) return null;
        return order.orderType() == OrderType.CONVOY || !location(army).isAdjacentTo(destination)
                ? Order.move(army, destination) : null;
    }

    private boolean route(Order move, Map<UnitId, List<Order>> available, UnitId through, boolean foreign) {
        int mask = 0;
        for (int index = 0; index < seaFleets.size(); index++) {
            UnitId fleet = seaFleets.get(index);
            boolean permitted = fleet.owner() != catalog.power() ? foreign
                    : available.get(fleet).stream().anyMatch(order -> order.orderType() == OrderType.CONVOY
                    && Province.equalsIgnoreCoast(order.target(), location(move.unit()))
                    && Province.equalsIgnoreCoast(order.auxiliaryTarget(), move.target()));
            if (permitted) mask |= 1 << index;
        }
        int required = through == null ? -1 : seaFleets.indexOf(through);
        if (through != null && (required < 0 || (mask & (1 << required)) == 0)) return false;
        Route key = new Route(location(move.unit()), move.target(), mask, required);
        Boolean saved = routes.get(key);
        if (saved != null) return saved;
        List<Order> convoys = new ArrayList<>();
        for (int index = 0; index < seaFleets.size(); index++)
            if ((mask & (1 << index)) != 0)
                convoys.add(Order.convoy(seaFleets.get(index), key.origin(), key.destination()));
        boolean result = required < 0
                ? !OrderForms.drawConvoyPath(move, key.origin(), convoys, order -> location(order.unit())).isEmpty()
                : throughRoute(key, convoys);
        if (routes.size() >= 8192) routes.clear();
        routes.put(key, result);
        return result;
    }

    // A route through a fleet needs two vertex-disjoint arms to the endpoints.
    private boolean throughRoute(Route route, List<Order> convoys) {
        int count = convoys.size(), origin = 2 * count, destination = origin + 1, sink = origin + 2;
        int[][] capacity = new int[sink + 1][sink + 1];
        int source = -1;
        for (int i = 0; i < count; i++) {
            Province at = location(convoys.get(i).unit());
            capacity[2 * i][2 * i + 1] = 1;
            if (convoys.get(i).unit().equals(seaFleets.get(route.through()))) source = 2 * i + 1;
            if (at.isAdjacentTo(route.origin())) capacity[2 * i + 1][origin] = 1;
            if (at.isAdjacentTo(route.destination())) capacity[2 * i + 1][destination] = 1;
            for (int j = 0; j < count; j++)
                if (i != j && at.isAdjacentTo(location(convoys.get(j).unit())))
                    capacity[2 * i + 1][2 * j] = 1;
        }
        capacity[origin][sink] = capacity[destination][sink] = 1;
        for (int flow = 0; flow < 2; flow++) {
            int[] previous = new int[capacity.length];
            Arrays.fill(previous, -1);
            previous[source] = source;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty() && previous[sink] < 0) {
                int node = queue.remove();
                for (int next = 0; next < capacity.length; next++)
                    if (capacity[node][next] > 0 && previous[next] < 0) {
                        previous[next] = node;
                        queue.add(next);
                    }
            }
            if (previous[sink] < 0) return false;
            for (int node = sink; node != source; node = previous[node]) {
                capacity[previous[node]][node]--;
                capacity[node][previous[node]]++;
            }
        }
        return true;
    }

    private UnitId occupant(Province at) { return occupants.get(Province.canonical(at)); }
    private Province location(UnitId unit) { return root.board().locationOf(unit); }
    private String describe(Order order) {
        return order.unitType() + " " + location(order.unit()) + " " + order.orderType()
                + (order.target() == null ? "" : " " + order.target())
                + (order.auxiliaryTarget() == null ? "" : " " + order.auxiliaryTarget());
    }
}