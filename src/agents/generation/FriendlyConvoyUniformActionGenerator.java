package agents.generation;

import contracts.OrderForm;
import contracts.OrderForms;
import domain.Geography;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.GameState;
import game.GameState_Movement;
import phase.Order;
import phase.UnitId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Uniform candidate proposals restricted to same-nation convoys.
 *
 * <p>A convoy-dependent Army move must have a connected route among its
 * nation's submitted convoy orders. Convoy orders must match an own Army's
 * move. Foreign support remains available, but a supported foreign Army's
 * convoy move requires a possible route using that Army's own fleets.</p>
 *
 * <p>Fleet locations come from the board. Fleets in coastal provinces cannot
 * convoy. Ordinary adjacent Army moves require no route; matching convoys
 * between adjacent provinces remain available.</p>
 *
 * <p>Up to 32 times the requested candidate count is proposed by the uniform
 * generator. Filtering may return fewer candidates; all-hold remains first.
 * Priors retain the uniform per-unit anchor on the restricted catalog, before
 * complete-action filtering. They are not the rejection sampler's probabilities.</p>
 *
 * <p>Retreats and adjustments are unchanged. This is a generation policy;
 * the broad legal catalog and adjudicator still accept foreign convoys.
 * Matching orders and a route do not guarantee survival or success.</p>
 */
public final class FriendlyConvoyUniformActionGenerator implements ActionGenerator {

    private static final int PROPOSALS_PER_CANDIDATE = 32;

    @Override
    public List<ActionCandidate> generate(
            ActionGenerationRequest request,
            LegalOrderCatalog catalog
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(catalog, "catalog");
        catalog.requireMatches(request.state(), request.power());

        UniformActionGenerator uniform = new UniformActionGenerator();
        if (!(request.state() instanceof GameState_Movement))
            return uniform.generate(request, catalog);

        GameState state = request.state();
        Map<Province, UnitId> occupants = new LinkedHashMap<>();
        state.board().locations().forEach((unit, location) ->
                occupants.put(Province.canonical(location), unit));

        Map<UnitId, List<OrderForm>> options = new LinkedHashMap<>();
        Map<Order, Boolean> possibleRoutes = new LinkedHashMap<>();
        for (var entry : catalog.optionsByUnit().entrySet()) {
            List<OrderForm> retained = new ArrayList<>();
            for (OrderForm form : entry.getValue()) {
                Order order = (Order) form;
                UnitId army = switch (order.orderType()) {
                    case MOVE -> order.unit();
                    case SUPPORT, CONVOY -> occupants.get(Province.canonical(order.target()));
                    default -> null;
                };
                Province destination = order.orderType() == OrderType.MOVE
                        ? order.target() : order.auxiliaryTarget();
                if (order.orderType() == OrderType.CONVOY
                        && (army == null || army.unitType() != UnitType.ARMY
                        || army.owner() != order.owner()))
                    continue;
                if (army != null && army.unitType() == UnitType.ARMY && destination != null
                        && (order.orderType() == OrderType.CONVOY
                        || !state.board().locationOf(army).isAdjacentTo(destination))) {
                    Order move = Order.move(army, destination);
                    if (!possibleRoutes.computeIfAbsent(move, ignored ->
                            hasRoute(state, move, possibleConvoys(state, move))))
                        continue;
                }
                retained.add(order);
            }
            options.put(entry.getKey(), retained);
        }

        LegalOrderCatalog restricted = new LegalOrderCatalog(state, request.power(), options);
        if (request.candidateLimit() == 1 || restricted.unitLocations().isEmpty())
            return uniform.generateFromCatalog(request, restricted);
        int proposalLimit = (int) Math.min(
                Integer.MAX_VALUE, (long) PROPOSALS_PER_CANDIDATE * request.candidateLimit());
        List<ActionCandidate> proposals = uniform.generateFromCatalog(new ActionGenerationRequest(
                state, request.power(), proposalLimit, request.seed()), restricted);
        List<ActionCandidate> result = new ArrayList<>();
        for (ActionCandidate candidate : proposals) {
            if (!matchesOwnConvoys(state, candidate, occupants))
                continue;
            result.add(candidate);
            if (result.size() == request.candidateLimit())
                break;
        }
        if (result.isEmpty())
            throw new IllegalStateException("Friendly-convoy generation lost the all-hold action");
        return List.copyOf(result);
    }

    private static List<Order> possibleConvoys(GameState state, Order move) {
        List<Order> convoys = new ArrayList<>();
        for (var entry : state.board().locations().entrySet())
            if (entry.getKey().owner() == move.owner()
                    && entry.getKey().unitType() == UnitType.FLEET
                    && entry.getValue().geography == Geography.WATER)
                convoys.add(Order.convoy(entry.getKey(),
                        state.board().locationOf(move.unit()), move.target()));
        return convoys;
    }

    private static boolean hasRoute(GameState state, Order move, List<Order> convoys) {
        return !OrderForms.drawConvoyPath(move, state.board().locationOf(move.unit()),
                convoys, order -> state.board().locationOf(order.unit())).isEmpty();
    }

    private static boolean matchesOwnConvoys(
            GameState state, ActionCandidate candidate, Map<Province, UnitId> occupants
    ) {
        Map<UnitId, Order> submitted = new LinkedHashMap<>();
        List<Order> convoys = new ArrayList<>();
        for (OrderForm form : candidate.orders()) {
            Order order = (Order) form;
            submitted.put(order.unit(), order);
            if (order.orderType() == OrderType.CONVOY)
                convoys.add(order);
        }
        for (Order order : submitted.values()) {
            if (order.orderType() == OrderType.MOVE && order.unitType() == UnitType.ARMY
                    && !state.board().locationOf(order.unit()).isAdjacentTo(order.target())
                    && !hasRoute(state, order, convoys))
                return false;
            if (order.orderType() == OrderType.CONVOY) {
                Order move = submitted.get(occupants.get(Province.canonical(order.target())));
                if (move == null || move.unitType() != UnitType.ARMY
                        || move.orderType() != OrderType.MOVE
                        || move.target() != order.auxiliaryTarget()
                        || !hasRoute(state, move, convoys))
                    return false;
            }
            if (order.orderType() == OrderType.SUPPORT && order.auxiliaryTarget() != null) {
                UnitId army = occupants.get(Province.canonical(order.target()));
                if (army != null && army.owner() == candidate.power() && army.unitType() == UnitType.ARMY
                        && !state.board().locationOf(army).isAdjacentTo(order.auxiliaryTarget())) {
                    Order move = submitted.get(army);
                    if (move.orderType() != OrderType.MOVE || move.target() != order.auxiliaryTarget()
                            || !hasRoute(state, move, convoys))
                        return false;
                }
            }
        }
        return true;
    }

}