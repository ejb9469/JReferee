package phase.movement;

import adjudication.Judge;
import adjudication.ParadoxCycle;
import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.PhaseResult;
import phase.UnitId;

import java.util.*;


/**
 * Immutable game-facing movement adjudication outcome.
 *
 * <p>The `producer` Judge should not be treated as part of the state contract
 * of the following retreat phase.</p>
 */
public final class MovementResult implements PhaseResult {


    private final Judge producer;

    private final Map<UnitId, Outcome> outcomes;
    private final Map<UnitId, Province> finalLocations;
    private final Map<UnitId, Province> successfulMoveDestinations;
    private final Map<UnitId, Dislodgement> dislodgements;
    private final Set<Province> standoffProvinces;
    private final List<ParadoxCycle> paradoxCycles;


    private MovementResult(
            Judge producer,
            Map<UnitId, Outcome> outcomes,
            Map<UnitId, Province> finalLocations,
            Map<UnitId, Province> successfulMoveDestinations,
            Map<UnitId, Dislodgement> dislodgements,
            Set<Province> standoffProvinces,
            List<ParadoxCycle> paradoxCycles
    ) {
        this.producer = Objects.requireNonNull(producer, "producer");
        // use `...unmodifiableMap/Set` to preserve insertion order
        this.outcomes = Collections.unmodifiableMap(
                new LinkedHashMap<>(outcomes));
        this.finalLocations = Collections.unmodifiableMap(
                new LinkedHashMap<>(finalLocations));
        this.successfulMoveDestinations = Collections.unmodifiableMap(
                new LinkedHashMap<>(successfulMoveDestinations));
        this.dislodgements = Collections.unmodifiableMap(
                new LinkedHashMap<>(dislodgements));
        this.standoffProvinces = Collections.unmodifiableSet(
                new LinkedHashSet<>(standoffProvinces));
        this.paradoxCycles = List.copyOf(paradoxCycles);
    }


    static MovementResult from(
            Collection<UnitId> activeUnits,
            Collection<NormalizedOrder> normalizedOrders,
            Judge producer
    ) {

        Map<UnitId, NormalizedOrder> normalizedByUnit = new LinkedHashMap<>();

        for (NormalizedOrder normalized : normalizedOrders) {
            NormalizedOrder prior = normalizedByUnit.putIfAbsent(
                    normalized.unit(),
                    normalized);
            if (prior != null)
                throw new IllegalStateException(
                        "Normalizer produced multiple orders for unit: " + normalized.unit());
        }

        Map<UnitId, adjudication.Order> adjudicatedByUnit =
                indexByUnit(producer.getOrders());

        if (!adjudicatedByUnit.keySet().equals(normalizedByUnit.keySet())) {
            throw new IllegalStateException(
                    "Producer result does not match normalized unit set");
        }

        Map<UnitId, Outcome> outcomes = new LinkedHashMap<>();
        Map<UnitId, Province> successfulMoves = new LinkedHashMap<>();

        for (UnitId unit : activeUnits) {

            NormalizedOrder normalized = normalizedByUnit.get(unit);

            if (normalized == null)
                throw new IllegalStateException(
                        "Normalizer omitted active unit: " + unit);

            Order submitted = normalized.order();
            adjudication.Order adjudicated = adjudicatedByUnit.get(unit);

            /*
             * A snapshot-backed HOLD is the engine's effective form of the
             * original submitted CONVOY. Its unit identity remains unchanged,
             * so unit-keyed lookup maps it to the right immutable order.
             */
            boolean rewrittenBySzykman =
                    adjudicated.getSnapshot() != null;

            Outcome outcome = new Outcome(
                    unit,
                    submitted,
                    normalized.submitted(),
                    adjudicated.verdict,
                    adjudicated.orderType,
                    rewrittenBySzykman);

            outcomes.put(unit, outcome);

            if (outcome.successfulMove())
                successfulMoves.put(unit, adjudicated.pos1);

        }

        Map<UnitId, Province> finalLocations = new LinkedHashMap<>();

        for (UnitId unit : activeUnits) {
            finalLocations.put(
                    unit,
                    successfulMoves.getOrDefault(unit, unit.origin()));
        }

        Map<UnitId, Dislodgement> dislodgements = dislodgements(
                activeUnits,
                successfulMoves);

        for (UnitId dislodgedUnit : dislodgements.keySet())
            finalLocations.remove(dislodgedUnit);

        Set<Province> standoffs = standoffs(
                producer,
                adjudicatedByUnit,
                finalLocations);

        return new MovementResult(
                producer,
                outcomes,
                finalLocations,
                successfulMoves,
                dislodgements,
                standoffs,
                producer.getParadoxCycles());

    }


    private static Map<UnitId, adjudication.Order> indexByUnit(Collection<adjudication.Order> adjudicatedOrders) {

        Map<UnitId, adjudication.Order> result = new LinkedHashMap<>();

        for (adjudication.Order order : adjudicatedOrders) {
            UnitId unit = new UnitId(
                    order.owner,
                    order.unitType,
                    order.pos0);
            if (result.putIfAbsent(unit, order) != null) {
                throw new IllegalStateException(
                        "Multiple adjudicated orders for unit: " + unit);
            }
        }

        return result;

    }

    private static Map<UnitId, Dislodgement> dislodgements(
            Collection<UnitId> activeUnits,
            Map<UnitId, Province> successfulMoves
    ) {

        Map<UnitId, Dislodgement> result = new LinkedHashMap<>();

        for (Map.Entry<UnitId, Province> attack :
                successfulMoves.entrySet()) {

            UnitId attacker = attack.getKey();
            Province destination = attack.getValue();

            for (UnitId defender : activeUnits) {

                if (!Province.equalsIgnoreCoast(
                        defender.origin(),
                        destination)
                )
                    continue;

                /*
                 * A unit that successfully moved away is not dislodged.
                 */
                if (successfulMoves.containsKey(defender))
                    continue;

                /*
                 * A correctly adjudicated position cannot self-dislodge.
                 * Treat it as a consistency failure rather than silently
                 * encoding an illegal retreat candidate.
                 */
                if (defender.owner() == attacker.owner()) {
                    throw new IllegalStateException(
                            "Successful self-dislodgement: "
                                    + attacker
                                    + " -> "
                                    + defender);
                }

                Dislodgement prior = result.putIfAbsent(
                        defender,
                        new Dislodgement(
                                defender,
                                attacker,
                                attacker.origin())
                );

                if (prior != null)
                    throw new IllegalStateException(
                            "Multiple successful attacks dislodged unit: " + defender);

                /*
                 * finalLocations is intentionally not updated here:
                 * callers remove all dislodged units after this pass.
                 */
                break;

            }
        }

        return result;
    }

    private static Set<Province> standoffs(
            Judge producer,
            Map<UnitId, adjudication.Order> adjudicatedByUnit,
            Map<UnitId, Province> finalLocations
    ) {

        Map<Province, Integer> failedMovesByDestination =
                new LinkedHashMap<>();

        for (adjudication.Order order : adjudicatedByUnit.values()) {

            if (order.orderType != OrderType.MOVE || order.verdict)
                continue;

            /*
             * A standoff requires viable opposing attacks, not simply a
             * failed order. If this helper is not added to Judge yet, use
             * Orders.orderIsValid(order) as a temporary weaker predicate.
             */
            if (!producer.movePathIsOpen(order))
                continue;

            Province territory = canonicalProvince(order.pos1);

            failedMovesByDestination.merge(
                    territory,
                    1,
                    Integer::sum);

        }

        Set<Province> standoffs = new LinkedHashSet<>();

        for (Map.Entry<Province, Integer> entry :
                failedMovesByDestination.entrySet()) {

            boolean occupiedAfterMovement = false;

            for (Province location : finalLocations.values()) {
                if (Province.equalsIgnoreCoast(location, entry.getKey())) {
                    occupiedAfterMovement = true;
                    break;
                }
            }

            if (entry.getValue() >= 2 && !occupiedAfterMovement) {
                standoffs.add(entry.getKey());
            }

        }

        return standoffs;

    }

    private static Province canonicalProvince(Province province) {
        return province.parent == null ? province : province.parent;
    }


    /**
     * Intended for diagnostics only. The retreat phase must not depend on it.
     * (package-private)
     */
    Judge producer() {
        return producer;
    }


    public Map<UnitId, Outcome> outcomes() {
        return outcomes;
    }

    /**
     * Includes only units still on the board after movement.
     * Dislodged units are represented separately by {@link #dislodgements()}.
     */
    public Map<UnitId, Province> finalLocations() {
        return finalLocations;
    }

    public Map<UnitId, Province> successfulMoveDestinations() {
        return successfulMoveDestinations;
    }

    public Map<UnitId, Dislodgement> dislodgements() {
        return dislodgements;
    }

    public Set<Province> standoffProvinces() {
        return standoffProvinces;
    }

    public List<ParadoxCycle> paradoxCycles() {
        return paradoxCycles;
    }


    /**
     * Helper function to avoid handling raw maps.
     * Returns the surviving unit location after movement,
     * or `null` when the unit was dislodged / inactive in this phase.
     */
    public Province locationOf(UnitId unit) {
        Objects.requireNonNull(unit, "unit");
        return this.finalLocations.get(unit);
    }

    /**
     * Helper function to avoid handling raw maps.
     * Returns the dislodgement status, or `null` if not dislodged.
     */
    public Dislodgement dislodgementOf(UnitId unit) {
        Objects.requireNonNull(unit, "unit");
        return this.dislodgements.get(unit);
    }

    /**
     * Helper function to avoid handling raw maps.
     * Returns whether a territory was left empty after
     * a genuine movement standoff, thus unavailable as a retreat destination.
     */
    public boolean wasStandoff(Province province) {
        Objects.requireNonNull(province, "province");
        Province territory = canonicalProvince(province);
        for (Province standoff : this.standoffProvinces)
            if (Province.equalsIgnoreCoast(standoff, territory))
                return true;
        return false;
    }


    public record Outcome(
            UnitId unit,
            Order submittedOrder,
            boolean submitted,  // synthesized vs. natural hold
            boolean succeeded,
            OrderType effectiveType,
            boolean rewrittenBySzykman
    ) {
        public Outcome {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(submittedOrder, "submittedOrder");
            Objects.requireNonNull(effectiveType, "effectiveType");
        }
        public boolean successfulMove() {
            return submittedOrder.type() == OrderType.MOVE
                    && effectiveType == OrderType.MOVE
                    && succeeded;
        }
    }


    /**
     * The minimal retreat-relevant facts:
     * the displaced unit and the successful attacker's origin.
     */
    public record Dislodgement(
            UnitId unit,
            UnitId attacker,
            Province attackerOrigin
    ) {
        public Dislodgement {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(attacker, "attacker");
            Objects.requireNonNull(attackerOrigin, "attackerOrigin");
        }
    }


}