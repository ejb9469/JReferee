package phase.movement;

import adjudication.Judge;
import adjudication.ParadoxCycle;
import domain.OrderType;
import domain.Province;
import phase.Order;
import phase.PhaseResult;
import phase.UnitId;
import phase.UnitLookup;

import java.util.*;


/**
 * Immutable game-facing movement adjudication outcome.
 *
 * <p>The producer Judge is retained only for package-private diagnostics.
 * Retreat processing depends exclusively on this immutable result.</p>
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
        this.successfulMoveDestinations =
                Collections.unmodifiableMap(new LinkedHashMap<>(successfulMoveDestinations));
        this.dislodgements = Collections.unmodifiableMap(new LinkedHashMap<>(dislodgements));
        this.standoffProvinces = Collections.unmodifiableSet(new LinkedHashSet<>(standoffProvinces));
        this.paradoxCycles = List.copyOf(paradoxCycles);

    }


    static MovementResult from(
            Map<UnitId, Province> startingLocations,
            Collection<NormalizedOrder> normalizedOrders,
            Judge producer
    ) {

        Map<UnitId, NormalizedOrder> normalizedByUnit = indexNormalizedOrders(normalizedOrders);

        Map<UnitId, adjudication.Order> adjudicatedByUnit =
                indexAdjudicatedOrders(startingLocations, producer.getOrders());

        if (!adjudicatedByUnit.keySet().equals(normalizedByUnit.keySet()))
            throw new IllegalStateException("Producer result does not match the starting unit set");

        Set<UnitId> convoyedMoveUnits = convoyedMovers(adjudicatedByUnit);
        Map<UnitId, Outcome> outcomes = new LinkedHashMap<>();
        Map<UnitId, Province> successfulMoves = new LinkedHashMap<>();

        for (UnitId unit : startingLocations.keySet()) {

            NormalizedOrder normalized = normalizedByUnit.get(unit);
            adjudication.Order adjudicated = adjudicatedByUnit.get(unit);

            if (normalized == null || adjudicated == null)
                throw new IllegalStateException("Missing normalized or adjudicated order for: " + unit);

            Outcome outcome = new Outcome(
                    unit,
                    normalized.order(),
                    normalized.submitted(),
                    adjudicated.verdict,
                    adjudicated.orderType,
                    adjudicated.getSnapshot() != null);

            outcomes.put(unit, outcome);

            if (outcome.successfulMove())
                successfulMoves.put(unit, adjudicated.pos1);

        }

        Map<UnitId, Province> finalLocations = new LinkedHashMap<>();

        for (Map.Entry<UnitId, Province> entry : startingLocations.entrySet())
            finalLocations.put(
                    entry.getKey(), successfulMoves.getOrDefault(entry.getKey(), entry.getValue()));

        Map<UnitId, Dislodgement> dislodgements =
                dislodgements(startingLocations, successfulMoves, convoyedMoveUnits);

        for (UnitId dislodgedUnit : dislodgements.keySet())
            finalLocations.remove(dislodgedUnit);

        Set<Province> standoffs = standoffs(producer, adjudicatedByUnit, finalLocations);

        return new MovementResult(
                producer,
                outcomes,
                finalLocations,
                successfulMoves,
                dislodgements,
                standoffs,
                producer.getParadoxCycles());

    }


    // Order identity \\

    private static Map<UnitId, NormalizedOrder> indexNormalizedOrders(
            Collection<NormalizedOrder> normalizedOrders
    ) {

        Map<UnitId, NormalizedOrder> result = new LinkedHashMap<>();

        for (NormalizedOrder normalized : normalizedOrders) {

            NormalizedOrder prior = result.putIfAbsent(normalized.unit(), normalized);

            if (prior != null)
                throw new IllegalStateException(
                        "Normalizer produced multiple orders for unit: " + normalized.unit());

        }

        return result;

    }

    private static Map<UnitId, adjudication.Order> indexAdjudicatedOrders(
            Map<UnitId, Province> startingLocations,
            Collection<adjudication.Order> adjudicatedOrders
    ) {

        Map<UnitId, adjudication.Order> result = new LinkedHashMap<>();

        for (adjudication.Order order : adjudicatedOrders) {

            UnitId unit = unitAt(startingLocations, order.owner, order.unitType, order.pos0);

            if (result.putIfAbsent(unit, order) != null)
                throw new IllegalStateException("Multiple adjudicated orders for unit: " + unit);

        }

        return result;

    }

    private static UnitId unitAt(
            Map<UnitId, Province> locations,
            domain.Nation owner,
            domain.UnitType unitType,
            Province location
    ) {

        List<UnitId> matches = UnitLookup.matchingAt(
                locations, location,
                unit -> unit.owner() == owner && unit.unitType() == unitType);

        if (matches.size() > 1)
            throw new IllegalStateException("Multiple matching starting units at " + location);

        if (matches.isEmpty())
            throw new IllegalStateException(
                    "No starting unit matches adjudicated order at " + location);

        return matches.getFirst();

    }


    // Convoy arrival facts \\

    private static Set<UnitId> convoyedMovers(
            Map<UnitId, adjudication.Order> adjudicatedByUnit
    ) {

        Set<UnitId> result = new LinkedHashSet<>();

        for (Map.Entry<UnitId, adjudication.Order> entry : adjudicatedByUnit.entrySet()) {

            adjudication.Order move = entry.getValue();

            if (move.orderType != OrderType.MOVE
                    || !move.verdict
                    || move.unitType != domain.UnitType.ARMY)
                continue;

            if (hasSuccessfulConvoyPath(move, adjudicatedByUnit.values()))
                result.add(entry.getKey());

        }

        return result;

    }

    private static boolean hasSuccessfulConvoyPath(
            adjudication.Order move,
            Collection<adjudication.Order> adjudicatedOrders
    ) {

        List<adjudication.Order> matchingConvoys = new ArrayList<>();

        for (adjudication.Order order : adjudicatedOrders)
            if (order.orderType == OrderType.CONVOY
                    && order.verdict
                    && order.pos1 == move.pos0
                    && order.pos2 == move.pos1)
                matchingConvoys.add(order);

        if (matchingConvoys.isEmpty())
            return false;

        Set<adjudication.Order> reachableConvoys =
                Collections.newSetFromMap(new IdentityHashMap<>());

        Deque<adjudication.Order> frontier = new ArrayDeque<>();

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

                if (reachableConvoys.contains(next) || !convoy.pos0.isAdjacentTo(next.pos0))
                    continue;

                reachableConvoys.add(next);
                frontier.addLast(next);

            }

        }

        return false;

    }


    // Dislodgement and standoff facts \\

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

                if (!Province.equalsIgnoreCoast(displacedFrom, destination))
                    continue;

                // A successful mover vacated its original territory.
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
                                convoyedMoveUnits.contains(attacker)));

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

        Map<Province, Integer> failedMovesByDestination = new LinkedHashMap<>();

        for (adjudication.Order order : adjudicatedByUnit.values()) {

            if (order.orderType != OrderType.MOVE || order.verdict)
                continue;

            if (!producer.movePathIsOpen(order))
                continue;

            failedMovesByDestination.merge(Province.canonical(order.pos1), 1, Integer::sum);

        }

        Set<Province> standoffs = new LinkedHashSet<>();

        for (Map.Entry<Province, Integer> entry : failedMovesByDestination.entrySet()) {

            boolean occupiedAfterMovement = false;

            for (Province location : finalLocations.values()) {
                if (Province.equalsIgnoreCoast(location, entry.getKey())) {
                    occupiedAfterMovement = true;
                    break;
                }
            }

            if (entry.getValue() >= 2 && !occupiedAfterMovement)
                standoffs.add(entry.getKey());

        }

        return standoffs;

    }


    // Accessors \\

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
        Province territory = Province.canonical(province);

        for (Province standoff : standoffProvinces)
            if (Province.equalsIgnoreCoast(standoff, territory))
                return true;

        return false;

    }


    // Result types \\

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
     * Retreat-relevant facts. Locations are actual board positions, not origins
     * embedded in persistent UnitId values.
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