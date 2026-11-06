package phase.retreats;

import contracts.OrderForms;
import domain.Province;
import phase.Processor;
import phase.RetreatInput;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static phase.BoardRules.isOccupied;

/**
 * Adjudicates retreat orders using immutable movement-phase facts.
 */
public final class RetreatProcessor
        implements Processor<RetreatInput, RetreatResult> {

    public RetreatProcessor() {     }

    @Override
    public RetreatResult process(RetreatInput input) {

        Objects.requireNonNull(input, "input");

        MovementResult movementResult = input.movementResult();

        Map<UnitId, MovementResult.Dislodgement> dislodgements =
                movementResult.dislodgements();

        Map<UnitId, Province> finalLocations = new LinkedHashMap<>(
                movementResult.finalLocations());

        Map<UnitId, RetreatResult.Outcome> outcomes = new LinkedHashMap<>();

        Map<UnitId, RetreatOrder> ordersByUnit = new LinkedHashMap<>();

        Set<UnitId> duplicateOrderUnits = new LinkedHashSet<>();

        for (RetreatOrder order : input.submittedOrders()) {
            UnitId unit = order.unit();
            if (!dislodgements.containsKey(unit))
                throw new IllegalArgumentException(
                        "Retreat order names a unit not dislodged in movement: " + unit);
            if (ordersByUnit.putIfAbsent(unit, order) != null)
                duplicateOrderUnits.add(unit);
        }

        for (UnitId duplicateUnit : duplicateOrderUnits) {
            ordersByUnit.remove(duplicateUnit);
            outcomes.put(
                    duplicateUnit,
                    RetreatResult.Outcome.destroyed(
                            RetreatResult.Outcome.Status
                                    .DESTROYED_INVALID_SUBMISSION)
            );
        }

        Map<UnitId, RetreatOrder> legalOrders = new LinkedHashMap<>();

        for (Map.Entry<UnitId, RetreatOrder> entry : ordersByUnit.entrySet()) {

            UnitId unit = entry.getKey();
            RetreatOrder order = entry.getValue();

            if (!isLegalRetreat(order, movementResult)) {
                outcomes.put(
                        unit,
                        RetreatResult.Outcome.destroyed(
                                RetreatResult.Outcome.Status
                                        .DESTROYED_ILLEGAL_DESTINATION)
                );
                continue;
            }

            legalOrders.put(unit, order);

        }

        Map<Province, List<UnitId>> unitsByDestination = new LinkedHashMap<>();

        for (Map.Entry<UnitId, RetreatOrder> entry : legalOrders.entrySet()) {

            Province territory = Province.canonical(
                    entry.getValue().destination());

            unitsByDestination.computeIfAbsent(
                    territory,
                    ignored -> new ArrayList<>()
            ).add(entry.getKey());

        }

        for (List<UnitId> retreatingUnits : unitsByDestination.values()) {

            if (retreatingUnits.size() > 1) {
                for (UnitId unit : retreatingUnits)
                    outcomes.put(
                            unit,
                            RetreatResult.Outcome.destroyed(
                                    RetreatResult.Outcome.Status
                                            .DESTROYED_RETREAT_COLLISION)
                    );
                continue;
            }

            UnitId unit = retreatingUnits.getFirst();
            Province destination = legalOrders.get(unit).destination();

            finalLocations.put(unit, destination);

            outcomes.put(
                    unit,
                    RetreatResult.Outcome.retreated(destination)
            );

        }

        for (UnitId dislodgedUnit : dislodgements.keySet())
            if (!outcomes.containsKey(dislodgedUnit))
                outcomes.put(
                        dislodgedUnit,
                        RetreatResult.Outcome.destroyed(
                                RetreatResult.Outcome.Status
                                        .DESTROYED_NO_ORDER)
                );

        Set<UnitId> destroyedUnits = new LinkedHashSet<>();

        for (Map.Entry<UnitId, RetreatResult.Outcome> entry : outcomes.entrySet())
            if (!entry.getValue().succeeded())
                destroyedUnits.add(entry.getKey());

        return new RetreatResult(
                movementResult,
                finalLocations,
                outcomes,
                destroyedUnits);

    }

    /**
     * Checks an individual retreat against the preceding movement result.
     * Collisions between legal retreats are resolved separately.
     */
    public static boolean isLegalRetreat(RetreatOrder order, MovementResult movementResult) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(movementResult, "movementResult");
        MovementResult.Dislodgement dislodgement =
                movementResult.dislodgements().get(order.unit());

        if (dislodgement == null
                || !OrderForms.isValidRetreatOrder(order, dislodgement.displacedFrom()))
            return false;

        Province destination = order.destination();
        return (dislodgement.attackerArrivedByConvoy()
                || !Province.equalsIgnoreCoast(destination, dislodgement.attackerOrigin()))
                && !isOccupied(destination, movementResult.finalLocations())
                && !movementResult.wasStandoff(destination);
    }

}