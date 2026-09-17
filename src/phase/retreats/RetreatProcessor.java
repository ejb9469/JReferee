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

/**
 * Retreat-phase processor.
 *
 * <p>Its implementation adjudicates submitted retreat orders against the
 * immutable movement result. It must not inspect the legacy Judge, Justice,
 * SzykmanJustice, or mutable adjudication.Order objects retained internally by
 * movement processing.</p>
 */
public final class RetreatProcessor
        implements Processor<RetreatInput, RetreatResult> {


    // This is an alright use-case for a Singleton structure;
        //  1. aids in architecture
        //  2. retreats & adjustments are not very time-intensive
    public RetreatProcessor() {
    }


    @Override
    public RetreatResult process(RetreatInput input) {

        Objects.requireNonNull(input, "input");

        MovementResult movementResult = input.movementResult();

        Map<UnitId, MovementResult.Dislodgement> dislodgements =
                movementResult.dislodgements();

        /*
         * MovementResult excludes already-dislodged units.
         * Successful retreats will be added to this surviving-unit board during the second pass.
         */
        Map<UnitId, Province> finalLocations = new LinkedHashMap<>(
                movementResult.finalLocations());

        Map<UnitId, RetreatResult.Outcome> outcomes =
                new LinkedHashMap<>();

        Map<UnitId, RetreatOrder> ordersByUnit =
                new LinkedHashMap<>();

        Set<UnitId> duplicateOrderUnits =
                new LinkedHashSet<>();

        /*
         * SUBMISSION PASS:
         *
         * A retreat order can only be issued by a unit dislodged during the
         * immediately preceding movement phase.
         * An order for another unit is considered malformed input,
         * and besides cannot be represented in a result keyed only by
         * dislodged units.
         */
        for (RetreatOrder order : input.submittedOrders()) {
            UnitId unit = order.unit();
            if (!dislodgements.containsKey(unit))
                throw new IllegalArgumentException(
                        "Retreat order names a unit not dislodged in movement: "
                                + unit);
            if (ordersByUnit.putIfAbsent(unit, order) != null)
                duplicateOrderUnits.add(unit);
        }

        /*
         * Duplicate submissions invalidate that unit's retreat entirely.
         * [The 1st order is intentionally not allowed to win merely because it
         * happened to be encountered first.]
         */
        for (UnitId duplicateUnit : duplicateOrderUnits) {
            ordersByUnit.remove(duplicateUnit);
            outcomes.put(
                    duplicateUnit,
                    RetreatResult.Outcome.destroyed(
                            RetreatResult.Outcome.Status
                                    .DESTROYED_INVALID_SUBMISSION)
            );
        }

        /*
         * 1st ADJUDICATION PASS:
         *
         * Validate each retreat independently.
         * An illegal retreat must not cause another legal retreat
         * to the same territory to collide.
         */
        Map<UnitId, RetreatOrder> legalOrders = new LinkedHashMap<>();

        for (Map.Entry<UnitId, RetreatOrder> entry :
                ordersByUnit.entrySet()) {

            UnitId unit = entry.getKey();
            RetreatOrder order = entry.getValue();

            MovementResult.Dislodgement dislodgement =
                    dislodgements.get(unit);

            Province origin = unit.origin();
            Province destination = order.destination();



            boolean legal;

             /*
             * A unit cannot retreat to the territory from which it was
             * dislodged, including another representation of a split coast.
             * (uses `.equalsIgnoreCoast(...)`)
             */
            legal = !Province.equalsIgnoreCoast(origin, destination);

            /*
             * A unit may not retreat to the origin of the successful attack
             * that dislodged it.
             */
            if (legal && Province.equalsIgnoreCoast(
                    destination,
                    dislodgement.attackerOrigin()))
                legal = false;

            /*
             * Retreats occur after all movement results have been applied.
             * Any territory occupied after movement is then closed real estate.
             */
            if (legal) {
                for (Province occupiedLocation : finalLocations.values()) {
                    if (Province.equalsIgnoreCoast(
                            occupiedLocation,
                            destination)) {
                        legal = false;
                        break;
                    }
                }
            }

            /*
             * A genuine movement standoff prevents retreat into that territory,
             * even though it is empty after movement.
             */
            if (legal) {
                for (Province standoff :
                        movementResult.standoffProvinces()) {
                    if (Province.equalsIgnoreCoast(
                            standoff,
                            destination)) {
                        legal = false;
                        break;
                    }
                }
            }

            /*
             * Retreats cannot be convoyed. (sorry!)
             *
             * Armies require ordinary land adjacency and cannot enter water or
             * a specifically selected split-coast location.
             */
            if (legal && unit.unitType() == UnitType.ARMY)
                if (destination.geography == Geography.WATER
                        || destination.coastType == domain.CoastType.SPLIT
                        || !origin.isAdjacentTo(destination))
                    legal = false;

            /*
             * Fleets must move by sea and cannot enter inland territory.
             * adjacentBySea(...) retains the existing coast-specific rules.
             */
            if (legal && unit.unitType() == UnitType.FLEET)
                if (destination.geography == Geography.INLAND
                        || !Province.adjacentBySea(origin, destination))
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

        /*
         * 2nd ADJUDICATION PASS:
         *
         * Otherwise-legal retreats to the same territory all fail.
         * Differently-named coasts of one split-coast territory are one
         * destination for collision purposes.
         */
        Map<Province, List<UnitId>> unitsByDestination =
                new LinkedHashMap<>();

        for (Map.Entry<UnitId, RetreatOrder> entry :
                legalOrders.entrySet()) {

            Province destination = entry.getValue().destination();

            Province territory = destination.parent == null
                    ? destination
                    : destination.parent;

            unitsByDestination.computeIfAbsent(
                    territory,
                    ignored -> new ArrayList<>()
            ).add(entry.getKey());

        }

        for (Map.Entry<Province, List<UnitId>> entry :
                unitsByDestination.entrySet()) {

            List<UnitId> retreatingUnits = entry.getValue();

            if (retreatingUnits.size() > 1) {
                for (UnitId unit : retreatingUnits) {
                    outcomes.put(
                            unit,
                            RetreatResult.Outcome.destroyed(
                                    RetreatResult.Outcome.Status
                                            .DESTROYED_RETREAT_COLLISION)
                    );
                }
                continue;
            }

            UnitId unit = retreatingUnits.getFirst();
            Province destination = legalOrders.get(unit).destination();

            finalLocations.put(unit, destination);

            outcomes.put(
                    unit,
                    RetreatResult.Outcome.retreated(destination));

        }

        /*
         * A dislodged unit without an accepted, invalid, or colliding retreat
         * --> omitted a retreat order and is destroyed.
         */
        for (UnitId dislodgedUnit : dislodgements.keySet()) {
            if (!outcomes.containsKey(dislodgedUnit))
                outcomes.put(
                        dislodgedUnit,
                        RetreatResult.Outcome.destroyed(
                                RetreatResult.Outcome.Status
                                        .DESTROYED_NO_ORDER)
                );
        }

        Set<UnitId> destroyedUnits = new LinkedHashSet<>();

        for (Map.Entry<UnitId, RetreatResult.Outcome> entry :
                outcomes.entrySet()) {
            if (!entry.getValue().succeeded())
                destroyedUnits.add(entry.getKey());
        }

        return new RetreatResult(
                movementResult,
                finalLocations,
                outcomes,
                destroyedUnits
        );

    }

}