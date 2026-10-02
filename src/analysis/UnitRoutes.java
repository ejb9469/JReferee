package analysis;

import analysis.openings.Classifier;
import analysis.openings.Opening;
import domain.Nation;
import domain.Province;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import game.record.AdjustmentPhaseRecord;
import game.record.GameRecord;
import game.record.MovementPhaseRecord;
import game.record.ResolvedPhaseRecord;
import game.record.RetreatPhaseRecord;
import phase.Order;
import phase.UnitId;
import phase.adjustments.DisbandOrder;
import phase.movement.MovementResult;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatResult;

import java.util.*;


/**
 * Counts observed unit-route continuations using complete route prefixes.
 * Each instance is a training corpus, optionally restricted to an opening.
 */
public class UnitRoutes {


    private static final GameMoment START =
            new GameMoment(1901, GamePhase.SPRING_MOVEMENT);

    private final Opening requiredOpening;

    private final Map<UUID, GameRecord> games = new LinkedHashMap<>();
    private final Map<UUID, Map<UnitId, List<String>>> routes = new LinkedHashMap<>();

    private final Map<List<String>, NavigableMap<String, Long>> tree = new HashMap<>();
    private final Map<List<String>, Long> unfinished = new HashMap<>();


    public UnitRoutes() {
        this.requiredOpening = null;
    }

    public UnitRoutes(Opening requiredOpening) {
        this.requiredOpening = Objects.requireNonNull(requiredOpening, "requiredOpening");
    }


    // Training \\

    public synchronized boolean add(GameRecord game) {

        Objects.requireNonNull(game, "game");

        if (games.containsKey(game.id()))
            throw new IllegalArgumentException(
                    "Game is already indexed; rebuild the corpus to replace it: " + game.id());

        requireStandardStart(game);

        if (!matchesOpening(game))
            return false;

        // Extract and validate before modifying the corpus.
        Map<UnitId, List<String>> extracted = extract(game);

        for (List<String> route : extracted.values()) {

            List<String> prefix = List.of();

            for (String event : route) {

                tree.computeIfAbsent(prefix, ignored -> new TreeMap<>())
                        .merge(event, 1L, Math::addExact);

                List<String> extended = new ArrayList<>(prefix);
                extended.add(event);
                prefix = List.copyOf(extended);

            }

            if (!isDeath(route.getLast()))
                unfinished.merge(prefix, 1L, Math::addExact);

        }

        games.put(game.id(), game);
        routes.put(game.id(), extracted);

        return true;

    }

    private static void requireStandardStart(GameRecord game) {

        if (!START.equals(game.initialMoment()))
            throw new IllegalArgumentException(
                    "Unit routes require an archive beginning at Spring 1901 movement");

        // Reuse the existing standard-position validation.
        new Classifier(game.initialMoment(), game.initialBoard(), List.of());

    }

    private boolean matchesOpening(GameRecord game) {

        if (requiredOpening == null)
            return true;

        if (game.resolvedPhases().isEmpty())
            return false;

        if (!(game.resolvedPhases().getFirst() instanceof MovementPhaseRecord movement))
            throw new IllegalArgumentException("The first archived phase must be movement");

        Classifier classifier = new Classifier(
                movement.gameMoment(),
                movement.boardBefore(),
                movement.submittedOrders());

        return classifier.matches(
                requiredOpening.board(),
                requiredOpening.orders(),
                true);

    }


    // Individual histories \\

    private static Map<UnitId, List<String>> extract(GameRecord game) {

        Map<UnitId, List<String>> histories = new LinkedHashMap<>();
        Map<UnitId, Province> living = new LinkedHashMap<>(game.initialBoard().locations());
        Map<UnitId, Province> dislodged = new LinkedHashMap<>();

        Set<UnitId> dead = new HashSet<>();

        for (Map.Entry<UnitId, Province> entry : living.entrySet())
            born(histories, game.rulesetId(), entry.getKey(), entry.getValue(), START, false);

        GameMoment expected = START;

        for (ResolvedPhaseRecord phase : game.resolvedPhases()) {

            if (!phase.gameMoment().equals(expected))
                throw new IllegalArgumentException(
                        "Missing or unexpected phase: expected " + expected
                                + ", found " + phase.gameMoment());

            verifyBoard(living, dislodged, phase.boardBefore());

            if (phase instanceof MovementPhaseRecord movement) {
                movement(histories, living, dislodged, movement);
            } else if (phase instanceof RetreatPhaseRecord retreat) {
                retreat(histories, living, dislodged, dead, retreat);
            } else if (phase instanceof AdjustmentPhaseRecord adjustment) {
                adjustment(histories, living, dead, adjustment, game.rulesetId());
            } else {
                throw new IllegalArgumentException("Unsupported phase record");
            }

            verifyBoard(living, dislodged, phase.boardAfter());
            expected = nextMoment(phase.gameMoment(), !dislodged.isEmpty());

        }

        Map<UnitId, List<String>> result = new LinkedHashMap<>();

        for (Map.Entry<UnitId, List<String>> entry : histories.entrySet()) {

            boolean ended = isDeath(entry.getValue().getLast());

            if (ended != dead.contains(entry.getKey()))
                throw new IllegalStateException("Inconsistent route termination");

            result.put(entry.getKey(), List.copyOf(entry.getValue()));

        }

        return Collections.unmodifiableMap(result);

    }

    private static void born(Map<UnitId, List<String>> histories, String ruleset,
                             UnitId unit, Province location, GameMoment moment,
                             boolean built) {

        Objects.requireNonNull(location, "birth location");

        if (histories.containsKey(unit))
            throw new IllegalArgumentException("Unit identity was reused after inception: " + unit);

        List<String> route = new ArrayList<>();

        // UUIDs distinguish individual histories, but never aggregate-tree keys.
        route.add("RULESET|" + ruleset);
        route.add(stamp(moment) + "|BIRTH|"
                + (built ? "BUILD" : "INITIAL") + "|"
                + unit.owner().name() + "|"
                + unit.unitType().name() + "|"
                + location.name());

        histories.put(unit, route);

    }

    private static void movement(
            Map<UnitId, List<String>> histories,
            Map<UnitId, Province> living,
            Map<UnitId, Province> dislodged,
            MovementPhaseRecord phase) {

        if (!dislodged.isEmpty())
            throw new IllegalArgumentException("Movement began with unresolved retreats");

        MovementResult result = phase.result();

        if (!result.outcomes().keySet().equals(living.keySet()))
            throw new IllegalArgumentException("Movement outcomes do not cover the living units");

        if (!living.keySet().containsAll(result.dislodgements().keySet()))
            throw new IllegalArgumentException("Unknown unit in dislodgements");

        String time = stamp(phase.gameMoment());

        for (Map.Entry<UnitId, Province> entry : living.entrySet()) {

            UnitId unit = entry.getKey();
            Province origin = entry.getValue();
            MovementResult.Outcome outcome = result.outcomes().get(unit);
            Order order = outcome.submittedOrder();

            if (!unit.equals(order.unit()))
                throw new IllegalArgumentException("Movement outcome names a different issuer");

            List<String> route = histories.get(unit);

            route.add(time + "|ORDER|"
                    + (outcome.submitted() ? "SUBMITTED" : "SYNTHESIZED") + "|"
                    + orderKey(order.orderType().name(), origin,
                    order.target(), order.auxiliaryTarget()));

            MovementResult.Dislodgement displacement = result.dislodgements().get(unit);
            Province destination = phase.boardAfter().locationOf(unit);

            String position;

            if (displacement != null) {

                if (destination != null || displacement.displacedFrom() != origin)
                    throw new IllegalArgumentException("Inconsistent dislodgement position");

                // The unit is alive but temporarily absent from the active board.
                dislodged.put(unit, origin);
                position = "DISLODGED|" + origin.name();

            } else {

                if (destination == null)
                    throw new IllegalArgumentException("Unit vanished without a dislodgement");

                entry.setValue(destination);
                position = "AT|" + destination.name();

            }

            route.add(time + "|RESULT|" + position
                    + "|SUCCEEDED=" + outcome.succeeded()
                    + "|EFFECTIVE=" + outcome.effectiveType().name()
                    + "|REWRITTEN=" + outcome.rewrittenBySzykman());

        }

    }

    private static void retreat(
            Map<UnitId, List<String>> histories,
            Map<UnitId, Province> living,
            Map<UnitId, Province> dislodged,
            Set<UnitId> dead,
            RetreatPhaseRecord phase) {

        RetreatResult result = phase.result();

        if (!result.outcomes().keySet().equals(dislodged.keySet()))
            throw new IllegalArgumentException("Retreat outcomes do not match pending dislodgements");

        String time = stamp(phase.gameMoment());
        Set<UnitId> destroyed = new HashSet<>();

        for (Map.Entry<UnitId, Province> entry : dislodged.entrySet()) {

            UnitId unit = entry.getKey();
            Province origin = entry.getValue();
            List<String> route = histories.get(unit);

            List<String> submissions = new ArrayList<>();

            for (RetreatOrder order : phase.submittedOrders())
                if (order.unit().equals(unit))
                    submissions.add(orderKey(
                            order.orderType().name(), origin,
                            order.target(), order.auxiliaryTarget()));

            Collections.sort(submissions);

            if (submissions.isEmpty())
                route.add(time + "|ORDER|MISSING");
            else if (submissions.size() == 1)
                route.add(time + "|ORDER|SUBMITTED|" + submissions.getFirst());
            else
                route.add(time + "|ORDER|INVALID_MULTIPLE|" + String.join(";", submissions));

            RetreatResult.Outcome outcome = result.outcomeOf(unit);

            if (outcome.succeeded()) {

                Province destination = phase.boardAfter().locationOf(unit);

                if (destination == null || destination != outcome.destination())
                    throw new IllegalArgumentException("Retreat destination does not match the board");

                living.put(unit, destination);
                route.add(time + "|RESULT|AT|" + destination.name() + "|RETREATED");

            } else {

                if (phase.boardAfter().locationOf(unit) != null)
                    throw new IllegalArgumentException("Destroyed unit remains on the board");

                living.remove(unit);
                dead.add(unit);
                destroyed.add(unit);

                route.add(time + "|DEATH|" + outcome.status().name());

            }

        }

        if (!destroyed.equals(result.destroyedUnits()))
            throw new IllegalArgumentException("Retreat destruction records disagree");

        dislodged.clear();

    }

    private static void adjustment(
            Map<UnitId, List<String>> histories,
            Map<UnitId, Province> living,
            Set<UnitId> dead,
            AdjustmentPhaseRecord phase,
            String ruleset) {

        String time = stamp(phase.gameMoment());

        Set<UnitId> submittedDisbands = new HashSet<>();

        for (var outcome : phase.result().outcomes())
            if (outcome.accepted() && outcome.order() instanceof DisbandOrder order)
                submittedDisbands.add(order.unit());

        if (!phase.result().disbandedUnits().containsAll(submittedDisbands))
            throw new IllegalArgumentException("Accepted disband is missing from the result");

        for (UnitId unit : phase.result().disbandedUnits()) {

            Province origin = living.remove(unit);

            if (origin == null)
                throw new IllegalArgumentException("Disband names a unit that is not alive");

            boolean submitted = submittedDisbands.contains(unit);
            List<String> route = histories.get(unit);

            route.add(time + "|ORDER|" + (submitted ? "SUBMITTED" : "AUTOMATIC")
                    + "|" + orderKey("DESTROY", origin, null, null));
            route.add(time + "|DEATH|" + (submitted ? "DISBANDED" : "AUTO_DISBANDED"));

            dead.add(unit);

        }

        // Surviving winter is part of the route, but not a submitted hold.
        for (Map.Entry<UnitId, Province> entry : living.entrySet())
            histories.get(entry.getKey()).add(
                    time + "|RESULT|AT|" + entry.getValue().name() + "|SURVIVED_WINTER");

        for (UnitId unit : phase.result().builtUnits()) {

            Province location = phase.boardAfter().locationOf(unit);

            born(histories, ruleset, unit, location, phase.gameMoment(), true);
            living.put(unit, location);

        }

    }


    // Validation and canonical events \\

    private static void verifyBoard(Map<UnitId, Province> living,
                                    Map<UnitId, Province> dislodged, BoardState board) {

        Map<UnitId, Province> active = new HashMap<>(living);
        active.keySet().removeAll(dislodged.keySet());

        if (!active.equals(board.locations()))
            throw new IllegalArgumentException("Board does not match the tracked unit lifetimes");

    }

    private static GameMoment nextMoment(GameMoment moment, boolean retreatsPending) {

        int year = moment.year();

        return switch (moment.gamePhase()) {
            case SPRING_MOVEMENT -> new GameMoment(year,
                    retreatsPending ? GamePhase.SPRING_RETREAT : GamePhase.FALL_MOVEMENT);
            case SPRING_RETREAT -> new GameMoment(year, GamePhase.FALL_MOVEMENT);
            case FALL_MOVEMENT -> new GameMoment(year,
                    retreatsPending ? GamePhase.FALL_RETREAT : GamePhase.WINTER_ADJUSTMENT);
            case FALL_RETREAT -> new GameMoment(year, GamePhase.WINTER_ADJUSTMENT);
            case WINTER_ADJUSTMENT -> new GameMoment(year + 1, GamePhase.SPRING_MOVEMENT);
        };

    }

    private static String stamp(GameMoment moment) {
        return moment.year() + "|" + moment.gamePhase().name();
    }

    private static String orderKey(String type, Province origin,
                                   Province target, Province auxiliaryTarget) {
        return type + "|" + provinceKey(origin)
                + "|" + provinceKey(target)
                + "|" + provinceKey(auxiliaryTarget);
    }

    private static String provinceKey(Province province) {
        return province == null ? "-" : province.name();
    }

    private static boolean isDeath(String event) {
        return event.contains("|DEATH|");
    }


    // Queries \\

    public synchronized int gameCount() {
        return games.size();
    }

    public synchronized Optional<GameRecord> game(UUID gameId) {
        return Optional.ofNullable(games.get(Objects.requireNonNull(gameId, "gameId")));
    }

    public synchronized Map<UnitId, List<String>> routes(UUID gameId) {

        Objects.requireNonNull(gameId, "gameId");

        Map<UnitId, List<String>> result = routes.get(gameId);

        if (result == null)
            throw new IllegalArgumentException("Game is not indexed: " + gameId);

        return result;

    }

    public synchronized List<String> route(UUID gameId, UnitId unit) {

        List<String> result = routes(gameId).get(Objects.requireNonNull(unit, "unit"));

        if (result == null)
            throw new IllegalArgumentException("Unit is not recorded in this game: " + unit);

        return result;

    }

    public synchronized List<String> birthPrefix(UUID gameId, UnitId unit) {
        return List.copyOf(route(gameId, unit).subList(0, 2));
    }

    public synchronized Map<String, Long> next(List<String> prefix) {
        return next(prefix, 1);
    }

    public synchronized Map<String, Long> next(List<String> prefix, long minimumAppearances) {

        if (minimumAppearances < 1)
            throw new IllegalArgumentException("Minimum appearances must be positive");

        List<String> key = List.copyOf(Objects.requireNonNull(prefix, "prefix"));
        NavigableMap<String, Long> children = tree.get(key);

        if (children == null)
            return Map.of();

        List<Map.Entry<String, Long>> ranked = new ArrayList<>(children.entrySet());

        ranked.removeIf(entry -> entry.getValue() < minimumAppearances);
        ranked.sort(
                Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()));

        Map<String, Long> result = new LinkedHashMap<>();

        for (Map.Entry<String, Long> entry : ranked)
            result.put(entry.getKey(), entry.getValue());

        return Collections.unmodifiableMap(result);

    }

    public synchronized long unfinishedAt(List<String> prefix) {
        return unfinished.getOrDefault(
                List.copyOf(Objects.requireNonNull(prefix, "prefix")), 0L);
    }


}