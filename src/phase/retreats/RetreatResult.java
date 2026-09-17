package phase.retreats;

import domain.Province;
import phase.PhaseResult;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable result of a retreat phase.
 *
 * <p>The result is the only retreat-phase input needed by a future winter
 * adjustment processor. It contains no mutable adjudication orders and no
 * reference to a Judge/Justice implementation.</p>
 */
public final class RetreatResult implements PhaseResult {


    private final MovementResult movementResult;
    private final Map<UnitId, Province> finalLocations;
    private final Map<UnitId, Outcome> outcomes;
    private final Set<UnitId> destroyedUnits;


    /*
     * Package-private intentionally: `RetreatProcessor` will construct results
     * after it validates orders, applies retreat collisions, and destroys
     * units with absent or failed retreats.
     */
    RetreatResult(
            MovementResult movementResult,
            Map<UnitId, Province> finalLocations,
            Map<UnitId, Outcome> outcomes,
            Set<UnitId> destroyedUnits
    ) {

        this.movementResult = Objects.requireNonNull(
                movementResult,
                "movementResult");

        this.finalLocations = Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        Objects.requireNonNull(
                                finalLocations,
                                "finalLocations")));

        this.outcomes = Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        Objects.requireNonNull(
                                outcomes,
                                "outcomes")));

        this.destroyedUnits = Collections.unmodifiableSet(
                new LinkedHashSet<>(
                        Objects.requireNonNull(
                                destroyedUnits,
                                "destroyedUnits")));

        verifyOutcomeCoverage();
        verifyDestroyedUnitsAbsentFromBoard();

    }


    /**
     * The movement result that created this retreat phase.
     *
     * <p>Use this for historical reporting only. Winter adjustments should use
     * {@link #finalLocations()} rather than reinterpreting movement facts.</p>
     */
    public MovementResult movementResult() {
        return movementResult;
    }

    /**
     * Complete board after retreats.
     *
     * <p>This includes movement survivors and units whose retreats succeeded.
     * It excludes all destroyed dislodged units.</p>
     */
    public Map<UnitId, Province> finalLocations() {
        return finalLocations;
    }

    /**
     * Return a Map of all units -> outcomes that were dislodged during movement.
     */
    public Map<UnitId, Outcome> outcomes() {
        return outcomes;
    }

    /**
     * Units removed because they had no retreat order, submitted an illegal
     * retreat, or collided with another valid retreat attempt.
     */
    public Set<UnitId> destroyedUnits() {
        return destroyedUnits;
    }

    public Province locationOf(UnitId unit) {
        Objects.requireNonNull(unit, "unit");
        return finalLocations.get(unit);
    }

    public Outcome outcomeOf(UnitId unit) {
        Objects.requireNonNull(unit, "unit");
        return outcomes.get(unit);
    }

    public boolean wasDestroyed(UnitId unit) {
        Objects.requireNonNull(unit, "unit");
        return destroyedUnits.contains(unit);
    }

    private void verifyOutcomeCoverage() {
        Set<UnitId> dislodgedUnits =
                movementResult.dislodgements().keySet();
        if (!outcomes.keySet().equals(dislodgedUnits)) {
            throw new IllegalArgumentException(
                    "Retreat outcomes must cover exactly the units dislodged during movement");
        }
    }

    private void verifyDestroyedUnitsAbsentFromBoard() {
        for (UnitId destroyedUnit : destroyedUnits)
            if (finalLocations.containsKey(destroyedUnit))
                throw new IllegalArgumentException(
                        "Destroyed unit remains on post-retreat board: " + destroyedUnit);
    }

    /**
     * Outcome of retreat processing for one movement-dislodged unit.
     *
     * <p>A failed or omitted retreat destroys the unit under standard Diplomacy
     * rules. The status tells callers why the unit was removed without forcing
     * them to reinterpret the submitted order list.</p>
     */
    public record Outcome(
            Status status,
            Province destination
    ) {

        public Outcome {
            Objects.requireNonNull(status, "status");
            if (status == Status.RETREATED
                    && destination == null)
                throw new IllegalArgumentException(
                        "A successful retreat requires a destination");
            if (status != Status.RETREATED
                    && destination != null)
                throw new IllegalArgumentException(
                        "A DESTROY outcome must not retain a final destination");
        }

        public static Outcome retreated(Province destination) {
            return new Outcome(
                    Status.RETREATED,
                    Objects.requireNonNull(destination, "destination"));
        }

        public static Outcome destroyed(Status status) {
            if (status == Status.RETREATED) {
                throw new IllegalArgumentException(
                        "Use retreated(...) for successful retreats");
            }

            return new Outcome(status, null);
        }

        public boolean succeeded() {
            return status == Status.RETREATED;
        }

        public enum Status {
            /**
             * The unit entered into its retreat destination.
             */
            RETREATED,

            /**
             * No retreat order was submitted for the dislodged unit.
             */
            DESTROYED_NO_ORDER,

            /**
             * The submitted order did not name a unit dislodged in the
             * movement result, or multiple orders named the same unit.
             */
            DESTROYED_INVALID_SUBMISSION,

            /**
             * The destination was occupied after movement, was a standoff
             * province, was the attacker's origin, or otherwise was not a
             * legal retreat destination.
             */
            DESTROYED_ILLEGAL_DESTINATION,

            /**
             * Two or more independently legal retreat orders selected the
             * same destination, so all such retreating units were destroyed.
             */
            DESTROYED_RETREAT_COLLISION
        }

    }

}