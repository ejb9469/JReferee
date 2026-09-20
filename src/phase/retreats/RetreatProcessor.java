package phase.retreats;

import domain.Geography;
import domain.Province;
import domain.UnitType;
import phase.Processor;
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

    public RetreatProcessor() {
    }

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

            MovementResult.Dislodgement dislodgement = dislodgements.get(unit);

            Province displacedFrom = dislodgement.displacedFrom();
            Province destination = order.destination();

            boolean legal = !Province.equalsIgnoreCoast(
                    displacedFrom,
                    destination);

            /*
             * A dislodged unit may not retreat to the attacker's origin unless
             * that successful dislodging attack arrived by convoy.
             */
            if (legal
                    && !dislodgement.attackerArrivedByConvoy()
                    && Province.equalsIgnoreCoast(
                    destination,
                    dislodgement.attackerOrigin()))
                legal = false;

            if (legal && isOccupied(destination, finalLocations))
                legal = false;

            if (legal && movementResult.wasStandoff(destination))
                legal = false;

            if (legal && unit.unitType() == UnitType.ARMY)
                if (destination.geography == Geography.WATER
                        || destination.coastType
                        == domain.CoastType.SPLIT
                        || !displacedFrom.isAdjacentTo(destination))
                    legal = false;

            if (legal && unit.unitType() == UnitType.FLEET)
                if (destination.geography == Geography.INLAND
                        || !Province.adjacentBySea(
                        displacedFrom,
                        destination))
                    legal = false;

            if (!legal) {
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

            UnitId unit = retreatingUnits.get(0);
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

}