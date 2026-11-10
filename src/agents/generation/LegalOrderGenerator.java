package agents.generation;

import contracts.OrderForms;
import domain.Geography;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.GameState;
import game.GameState_Adjustments;
import game.GameState_Movement;
import game.GameState_Retreats;
import phase.Order;
import phase.AdjustmentInput;
import phase.UnitId;
import phase.adjustments.*;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Creates movement, retreat, or adjustment order options from a game state.
 *
 * <p>Coast-to-coast Army moves are included as convoy possibilities.
 * Convoy availability depends on the combined orders of all nations and is
 * not required for catalog membership.</p>
 */
public class LegalOrderGenerator {

    public LegalOrderCatalog generate(GameState state, Nation power) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(power, "power");

        if (state instanceof GameState_Adjustments adjustments) {
            AdjustmentInput input = new AdjustmentInput(
                    adjustments.moment().year(), adjustments.fallRetreatResult(), adjustments.board().owners(), List.of());
            Balance balance = new BalanceCalculator().calculate(input).get(power);
            Map<UnitId, List<DisbandOrder>> disbands = new LinkedHashMap<>();
            List<BuildOrder> builds = new ArrayList<>();
            if (balance.requiredDisbands() > 0)
                for (UnitId unit : adjustments.board().locations().keySet())
                    if (unit.owner() == power)
                        disbands.put(unit, List.of(new DisbandOrder(power, unit)));
            if (balance.availableBuilds() > 0)
                for (Province location : Province.values())
                    for (UnitType type : UnitType.values()) {
                        BuildOrder build = new BuildOrder(power, type, location);
                        if (AdjustmentProcessor.validateBuildSite(
                                input, build, adjustments.board().locations()) == null)
                            builds.add(build);
                    }
            return new LegalOrderCatalog(state, power, disbands, builds);
        }

        if (state instanceof GameState_Retreats retreats) {
            Map<UnitId, List<RetreatOrder>> optionsByUnit = new LinkedHashMap<>();
            for (UnitId unit : retreats.movementResult().dislodgements().keySet()) {
                if (unit.owner() != power)
                    continue;

                List<RetreatOrder> orders = new ArrayList<>();
                for (Province destination : Province.values()) {
                    RetreatOrder order = new RetreatOrder(unit, destination);
                    if (RetreatProcessor.isLegalRetreat(order, retreats.movementResult()))
                        orders.add(order);
                }
                optionsByUnit.put(unit, orders);
            }
            return new LegalOrderCatalog(state, power, optionsByUnit);
        }

        if (!(state instanceof GameState_Movement movementState))
            throw new IllegalArgumentException(
                    "Unsupported game state: " + state.getClass().getName());

        Map<UnitId, Province> locations = movementState.board().locations();
        for (Map.Entry<UnitId, Province> entry : locations.entrySet())
            requireValidLocation(entry.getKey(), entry.getValue());

        Map<UnitId, List<Order>> optionsByUnit = new LinkedHashMap<>();

        for (Map.Entry<UnitId, Province> entry : locations.entrySet()) {
            UnitId unit = entry.getKey();
            if (unit.owner() == power)
                optionsByUnit.put(
                        unit,
                        generateOrders(unit, entry.getValue(), locations));
        }

        return new LegalOrderCatalog(movementState, power, optionsByUnit);
    }

    private static List<Order> generateOrders(
            UnitId issuer,
            Province currentLocation,
            Map<UnitId, Province> locations
    ) {
        List<Order> orders = new ArrayList<>();
        orders.add(Order.hold(issuer));

        for (Province destination : Province.values())
            if (OrderForms.isPotentialMove(
                    issuer.unitType(),
                    currentLocation,
                    destination))
                orders.add(Order.move(issuer, destination));

        addSupportOrders(orders, issuer, currentLocation, locations);

        if (issuer.unitType() == UnitType.FLEET
                && currentLocation.geography == Geography.WATER)
            addConvoyOrders(orders, issuer, locations);

        return List.copyOf(orders);
    }

    private static void addSupportOrders(
            List<Order> orders,
            UnitId issuer,
            Province supportingLocation,
            Map<UnitId, Province> locations
    ) {
        Set<Order> supports = new LinkedHashSet<>();
        for (Map.Entry<UnitId, Province> entry : locations.entrySet()) {
            UnitId supportedUnit = entry.getKey();
            Province supportedLocation = entry.getValue();

            if (issuer.equals(supportedUnit))
                continue;

            if (OrderForms.canSupportTo(
                    issuer.unitType(),
                    supportingLocation,
                    supportedLocation))
                supports.add(Order.supportHold(issuer, Province.canonical(supportedLocation)));

            for (Province destination : Province.values()) {
                if (!OrderForms.isPotentialMove(
                        supportedUnit.unitType(),
                        supportedLocation,
                        destination))
                    continue;

                if (OrderForms.canSupportTo(
                        issuer.unitType(),
                        supportingLocation,
                        destination))
                    supports.add(Order.supportMove(
                            issuer,
                            Province.canonical(supportedLocation),
                            Province.canonical(destination)));
            }
        }
        orders.addAll(supports);
    }

    private static void addConvoyOrders(
            List<Order> orders,
            UnitId convoyingFleet,
            Map<UnitId, Province> locations
    ) {
        for (Map.Entry<UnitId, Province> entry : locations.entrySet()) {
            UnitId army = entry.getKey();
            Province armyLocation = entry.getValue();

            if (army.unitType() != UnitType.ARMY)
                continue;

            for (Province destination : Province.values())
                if (OrderForms.isPotentialConvoyMove(
                        armyLocation,
                        destination))
                    orders.add(Order.convoy(
                            convoyingFleet,
                            armyLocation,
                            destination));
        }
    }

    private static void requireValidLocation(UnitId unit, Province location) {
        if (!OrderForms.isValidUnitLocation(unit.unitType(), location))
            throw new IllegalArgumentException(
                    "Unit is in a location that does not match its type: "
                            + unit + " at " + location);
    }

}