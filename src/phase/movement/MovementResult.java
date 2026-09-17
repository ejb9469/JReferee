package phase.movement;

import adjudication.Judge;
import adjudication.ParadoxCycle;
import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.PhaseResult;
import phase.UnitId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable game-facing movement adjudication outcome.
 *
 * <p>The producer Judge is retained only for package-private diagnostics. The
 * retreat phase must depend exclusively on this immutable result.</p>
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
        this.outcomes = Collections.unmodifiableMap(new LinkedHashMap<>(outcomes));
        this.finalLocations = Collections.unmodifiableMap(new LinkedHashMap<>(finalLocations));
        this.successfulMoveDestinations = Collections.unmodifiableMap(new LinkedHashMap<>(successfulMoveDestinations));
        this.dislodgements = Collections.unmodifiableMap(new LinkedHashMap<>(dislodgements));
        this.standoffProvinces = Collections.unmodifiableSet(new LinkedHashSet<>(standoffProvinces));
        this.paradoxCycles = List.copyOf(paradoxCycles);
    }

    static MovementResult from(
            Map<UnitId, Province> startingLocations,
            Collection<NormalizedOrder> normalizedOrders,
            Judge producer
    ) {

        Map<UnitId, NormalizedOrder> normalizedByUnit =
                indexNormalizedOrders(normalizedOrders);

        Map<UnitId, adjudication.Order> adjudicatedByUnit =
                indexAdjudicatedOrders(
                        startingLocations,
                        producer.getOrders());

        if (!adjudicatedByUnit.keySet().equals(
                normalizedByUnit.keySet()))
            throw new IllegalStateException(
                    "Producer result does not match the starting unit set");

        Set<UnitId> convoyedMoveUnits = convoyedMovers(
                adjudicatedByUnit);

        Map<UnitId, Outcome> outcomes = new LinkedHashMap<>();
        Map<UnitId, Province> successfulMoves = new LinkedHashMap<>();

        for (UnitId unit : startingLocations.keySet()) {

            NormalizedOrder normalized = normalizedByUnit.get(unit);
            adjudication.Order adjudicated = adjudicatedByUnit.get(unit);

            if (normalized == null || adjudicated == null)
                throw new IllegalStateException(
                        "Missing normalized or adjudicated order for: " + unit);

            boolean rewrittenBySzykman =
                    adjudicated.getSnapshot() != null;

            Outcome outcome = new Outcome(
                    unit,
                    normalized.order(),
                    normalized.submitted(),
                    adjudicated.verdict,
                    adjudicated.orderType,
                    rewrittenBySzykman);

            outcomes.put(unit, outcome);

            if (outcome.successfulMove())
                successfulMoves.put(unit, adjudicated.pos1);

        }

        Map<UnitId, Province> finalLocations = new LinkedHashMap<>();

        for (Map.Entry<UnitId, Province> entry :
                startingLocations.entrySet()) {
            finalLocations.put(
                    entry.getKey(),
                    successfulMoves.getOrDefault(
                            entry.getKey(),
                            entry.getValue())
            );
        }

        Map<UnitId, Dislodgement> dislodgements = dislodgements(
                startingLocations,
                successfulMoves,
                convoyedMoveUnits);

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

    private static Map<UnitId, NormalizedOrder> indexNormalizedOrders(
            Collection<NormalizedOrder> normalizedOrders
    ) {

        Map<UnitId, NormalizedOrder> result = new LinkedHashMap<>();

        for (NormalizedOrder normalized : normalizedOrders) {
            NormalizedOrder prior = result.putIfAbsent(
                    normalized.unit(),
                    normalized);
            if (prior != null)
                throw new IllegalStateException(
                        "Normalizer produced multiple orders for unit: " + normalized.unit());
        }

        return result;

    }

    /**
     * The adjudicator does not carry UnitId. Reassociate each final
     * mutable order with the stable unit at its starting position.
     */
    private static Map<UnitId, adjudication.Order> indexAdjudicatedOrders(
            Map<UnitId, Province> startingLocations,
            Collection<adjudication.Order> adjudicatedOrders
    ) {

        Map<UnitId, adjudication.Order> result = new LinkedHashMap<>();

        for (adjudication.Order order : adjudicatedOrders) {
            UnitId unit = unitAt(
                    startingLocations,
                    order.owner,
                    order.unitType,
                    order.pos0);
            if (result.putIfAbsent(unit, order) != null)
                throw new IllegalStateException(
                        "Multiple adjudicated orders for unit: " + unit);
        }

        return result;

    }

    private static UnitId unitAt(
            Map<UnitId, Province> locations,
            domain.Nation owner,
            domain.UnitType unitType,
            Province location
    ) {

        UnitId result = null;

        for (Map.Entry<UnitId, Province> entry : locations.entrySet()) {

            UnitId candidate = entry.getKey();

            if (candidate.owner() != owner
                    || candidate.unitType() != unitType
                    || !Province.equalsIgnoreCoast(
                    entry.getValue(),
                    location))
                continue;

            if (result != null)
                throw new IllegalStateException(
                        "Multiple matching starting units at " + location);

            result = candidate;

        }

        if (result == null)
            throw new IllegalStateException(
                    "No starting unit matches adjudicated order at " + location);

        return result;

    }

    /**
     * Determines which successful army moves arrived by convoy.
     *
     * <p>A matching submitted convoy is insufficient: an adjacent army move
     * can still succeed over land after its convoy is disrupted. A move counts
     * as convoyed only when the resolved orders contain a complete chain of
     * successful matching convoy fleets from the army's source to destination.
     * This also avoids treating convoy-swap-related attacks as land attacks
     * merely because their endpoints are adjacent.</p>
     */
    private static Set<UnitId> convoyedMovers(Map<UnitId, adjudication.Order> adjudicatedByUnit) {

        Set<UnitId> result = new LinkedHashSet<>();

        for (Map.Entry<UnitId, adjudication.Order> entry :
                adjudicatedByUnit.entrySet()) {

            adjudication.Order move = entry.getValue();

            if (move.orderType != OrderType.MOVE
                    || !move.verdict
                    || move.unitType != domain.UnitType.ARMY)
                continue;

            if (hasSuccessfulConvoyPath(
                    move,
                    adjudicatedByUnit.values()))
                result.add(entry.getKey());

        }

        return result;

    }

    private static boolean hasSuccessfulConvoyPath(
            adjudication.Order move,
            Collection<adjudication.Order> adjudicatedOrders
    ) {

        List<adjudication.Order> matchingConvoys =
                new ArrayList<>();

        for (adjudication.Order order : adjudicatedOrders) {
            if (order.orderType == OrderType.CONVOY
                    && order.verdict
                    && order.pos1 == move.pos0
                    && order.pos2 == move.pos1)
                matchingConvoys.add(order);
        }

        if (matchingConvoys.isEmpty())
            return false;

        Set<adjudication.Order> reachableConvoys =
                Collections.newSetFromMap(new IdentityHashMap<>());

        Deque<adjudication.Order> frontier =
                new ArrayDeque<>();

        for (adjudication.Order convoy : matchingConvoys) {
            if (convoy.pos0.isAdjacentTo(move.pos0)) {
                reachableConvoys.add(convoy);
                frontier.addLast(convoy);
            }
        }

        while (!frontier.isEmpty()) {

            adjudication.Order convoy = frontier.removeFirst();

            if (convoy.pos0.isAdjacentTo(move.pos1))
                return true;

            for (adjudication.Order next : matchingConvoys) {
                if (reachableConvoys.contains(next)
                        || !convoy.pos0.isAdjacentTo(next.pos0))
                    continue;
                reachableConvoys.add(next);
                frontier.addLast(next);
            }

        }

        return false;

    }

    private static Map<UnitId, Dislodgement> dislodgements(
            Map<UnitId, Province> startingLocations,
            Map<UnitId, Province> successfulMoves,
            Set<UnitId> convoyedMoveUnits
    ) {

        Map<UnitId, Dislodgement> result = new LinkedHashMap<>();

        for (Map.Entry<UnitId, Province> attack : successfulMoves.entrySet()) {

            UnitId attacker = attack.getKey();
            Province destination = attack.getValue();

            for (Map.Entry<UnitId, Province> defenderEntry : startingLocations.entrySet()) {

                UnitId defender = defenderEntry.getKey();
                Province displacedFrom = defenderEntry.getValue();

                if (!Province.equalsIgnoreCoast(
                        displacedFrom,
                        destination))
                    continue;

                /*
                 * A successful mover vacated its original territory and is
                 * never dislodged from it.
                 */
                if (successfulMoves.containsKey(defender))
                    continue;

                if (defender.owner() == attacker.owner())
                    throw new IllegalStateException(
                            "Successful self-dislodgement: " + attacker + " -> " + defender);

                Dislodgement prior = result.putIfAbsent(
                        defender,
                        new Dislodgement(
                                defender,
                                displacedFrom,
                                attacker,
                                startingLocations.get(attacker),
                                convoyedMoveUnits.contains(attacker)
                        ));

                if (prior != null)
                    throw new IllegalStateException(
                            "Multiple successful attacks dislodged unit: " + defender);

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

            if (!producer.movePathIsOpen(order))
                continue;

            failedMovesByDestination.merge(
                    canonicalProvince(order.pos1),
                    1,
                    Integer::sum);

        }

        Set<Province> standoffs = new LinkedHashSet<>();

        for (Map.Entry<Province, Integer> entry : failedMovesByDestination.entrySet()) {

            boolean occupiedAfterMovement = false;

            for (Province location : finalLocations.values()) {
                if (Province.equalsIgnoreCoast(
                        location,
                        entry.getKey())
                ) {
                    occupiedAfterMovement = true;
                    break;
                }
            }

            if (entry.getValue() >= 2 && !occupiedAfterMovement)
                standoffs.add(entry.getKey());

        }

        return standoffs;

    }

    private static Province canonicalProvince(Province province) {
        return province.parent == null
                ? province
                : province.parent;
    }

    /**
     * Diagnostics only. No later phase may depend on the mutable Judge.
     */
    Judge producer() {
        return producer;
    }

    public Map<UnitId, Outcome> outcomes() {
        return outcomes;
    }

    @Override
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

    public Dislodgement dislodgementOf(UnitId unit) {
        Objects.requireNonNull(unit, "unit");
        return dislodgements.get(unit);
    }

    public boolean wasStandoff(Province province) {

        Objects.requireNonNull(province, "province");

        Province territory = canonicalProvince(province);

        for (Province standoff : standoffProvinces)
            if (Province.equalsIgnoreCoast(standoff, territory))
                return true;

        return false;

    }

    public record Outcome(
            UnitId unit,
            Order submittedOrder,
            boolean submitted,
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
     * Complete retreat-relevant facts for a movement-displaced unit.
     *
     * @param displacedFrom actual board location from which the unit was
     *                      dislodged, not UnitId.origin()
     * @param attackerArrivedByConvoy whether the successful dislodging army
     *                                 attack was convoyed
     */
    public record Dislodgement(
            UnitId unit,
            Province displacedFrom,
            UnitId attacker,
            Province attackerOrigin,
            boolean attackerArrivedByConvoy
    ) {
        public Dislodgement {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(displacedFrom, "displacedFrom");
            Objects.requireNonNull(attacker, "attacker");
            Objects.requireNonNull(attackerOrigin, "attackerOrigin");
        }
    }

}