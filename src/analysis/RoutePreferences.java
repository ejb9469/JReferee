package analysis;

import analysis.openings.Opening;
import domain.OrderType;
import domain.Province;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import game.record.GameRecord;
import game.record.MovementPhaseRecord;
import game.record.ResolvedPhaseRecord;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.*;


public class RoutePreferences {


    private final UnitRoutes routes;
    private final int maximumSuffix;
    private final boolean yearPooledFallback;

    private final Map<List<String>, Map<List<String>, Long>> decisions = new HashMap<>();


    public RoutePreferences() {
        this(null, 4, false);
    }

    public RoutePreferences(Opening opening, int maximumSuffix) {
        this(opening, maximumSuffix, false);
    }

    public RoutePreferences(
            Opening opening,
            int maximumSuffix,
            boolean yearPooledFallback) {

        if (maximumSuffix < 0)
            throw new IllegalArgumentException(
                    "Maximum suffix length must not be negative");

        this.routes = opening == null
                ? new UnitRoutes()
                : new UnitRoutes(opening);

        this.maximumSuffix = maximumSuffix;
        this.yearPooledFallback = yearPooledFallback;

    }


    // Training \\

    public synchronized boolean add(GameRecord game) {

        Objects.requireNonNull(game, "game");

        if (!routes.add(game))
            return false;

        for (ResolvedPhaseRecord phase : game.resolvedPhases()) {

            if (!(phase instanceof MovementPhaseRecord movement))
                continue;

            for (MovementResult.Outcome outcome : movement.result().outcomes().values()) {

                // An omitted order is not evidence of a deliberate hold.
                if (!outcome.submitted())
                    continue;

                UnitId unit = outcome.unit();
                Order order = outcome.submittedOrder();

                List<String> history = historyBefore(
                        routes.route(game.id(), unit),
                        movement.gameMoment());

                List<String> context = context(
                        game.rulesetId(),
                        movement.gameMoment(),
                        movement.boardBefore(),
                        unit);

                List<String> action = actionKey(order);

                record(key(context, "EXACT", history), action);

                List<String> recent = recentEvents(history);

                for (int length = 1; length <= Math.min(maximumSuffix, recent.size()); length++)
                    record(key(context, "SUFFIX", tail(recent, length)), action);

                record(key(context, "POSITION", List.of()), action);

                if (yearPooledFallback)
                    record(
                            key(withoutYear(context), "POSITION_YEAR_POOLED", List.of()),
                            action);

            }

        }

        return true;

    }

    private void record(List<String> key, List<String> action) {
        decisions.computeIfAbsent(key, ignored -> new HashMap<>())
                .merge(action, 1L, Math::addExact);
    }


    // Prediction \\

    public synchronized RoutePrediction predict(
            String ruleset, GameMoment moment, BoardState board,
            UnitId unit, List<String> history, long minimumObservations) {

        if (minimumObservations < 1)
            throw new IllegalArgumentException("Minimum observations must be positive");

        List<String> suppliedHistory = List.copyOf(
                Objects.requireNonNull(history, "history"));

        requirePastHistory(ruleset, moment, suppliedHistory);

        List<String> context = context(ruleset, moment, board, unit);

        if (!suppliedHistory.isEmpty()) {

            Map<List<String>, Long> exact =
                    decisions.get(key(context, "EXACT", suppliedHistory));

            if (enough(exact, minimumObservations))
                return prediction("EXACT", -1, exact, unit);

            List<String> recent = recentEvents(suppliedHistory);

            for (int length = Math.min(maximumSuffix, recent.size()); length >= 1; length--) {

                Map<List<String>, Long> suffix =
                        decisions.get(key(context, "SUFFIX", tail(recent, length)));

                if (enough(suffix, minimumObservations))
                    return prediction("SUFFIX", length, suffix, unit);

            }

        }

        Map<List<String>, Long> position =
                decisions.get(key(context, "POSITION", List.of()));

        if (enough(position, minimumObservations))
            return prediction("POSITION", 0, position, unit);

        if (yearPooledFallback) {

            Map<List<String>, Long> pooled = decisions.get(
                    key(withoutYear(context), "POSITION_YEAR_POOLED", List.of()));

            if (enough(pooled, minimumObservations))
                return prediction("POSITION_YEAR_POOLED", 0, pooled, unit);

        }

        return new RoutePrediction("NONE", 0, Map.of());

    }

    private static boolean enough(Map<List<String>, Long> counts, long minimum) {

        if (counts == null)
            return false;

        long total = 0;

        for (long count : counts.values())
            total = Math.addExact(total, count);

        return total >= minimum;

    }

    private static RoutePrediction prediction(
            String basis, int suffixLength,
            Map<List<String>, Long> counts, UnitId unit) {

        List<Map.Entry<List<String>, Long>> ranked = new ArrayList<>(counts.entrySet());

        ranked.sort((first, second) -> {

            int comparison = Long.compare(second.getValue(), first.getValue());

            if (comparison != 0)
                return comparison;

            return String.join("|", first.getKey()).compareTo(String.join("|", second.getKey()));

        });

        Map<Order, Long> result = new LinkedHashMap<>();

        for (Map.Entry<List<String>, Long> entry : ranked) {

            List<String> fields = entry.getKey();

            // Rebind the observed instruction to the caller's current unit.
            Order order = new Order(
                    unit,
                    OrderType.valueOf(fields.get(0)),
                    province(fields.get(1)),
                    province(fields.get(2)));

            result.put(order, entry.getValue());

        }

        return new RoutePrediction(basis, suffixLength, result);

    }


    // Route prefixes \\

    public synchronized List<String> historyBefore(
            UUID gameId, UnitId unit, GameMoment moment) {
        return historyBefore(routes.route(gameId, unit), moment);
    }

    public static List<String> historyBefore(List<String> route, GameMoment moment) {

        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(moment, "moment");

        if (route.size() < 2 || !route.getFirst().startsWith("RULESET|"))
            throw new IllegalArgumentException("Expected a UnitRoutes history");

        List<String> prefix = new ArrayList<>();
        prefix.add(route.getFirst());

        for (int index = 1; index < route.size(); index++) {

            String event = route.get(index);
            String[] fields = event.split("\\|", -1);
            GameMoment eventMoment = eventMoment(fields);

            int comparison = eventMoment.compareTo(moment);

            // Initial units exist before the first Spring orders.
            boolean initialBirth = index == 1
                    && comparison == 0
                    && fields.length > 3
                    && fields[2].equals("BIRTH")
                    && fields[3].equals("INITIAL");

            if (comparison > 0 || (comparison == 0 && !initialBirth))
                break;

            if (fields[2].equals("DEATH"))
                throw new IllegalArgumentException("Unit was already dead before the requested phase");

            prefix.add(event);

        }

        if (prefix.size() < 2 || !prefix.get(1).contains("|BIRTH|"))
            throw new IllegalArgumentException("Unit had not been born before the requested phase");

        return List.copyOf(prefix);

    }

    private static void requirePastHistory(
            String ruleset, GameMoment moment, List<String> history) {

        Objects.requireNonNull(ruleset, "ruleset");
        Objects.requireNonNull(moment, "moment");

        // Empty history explicitly requests position-based prediction.
        if (history.isEmpty())
            return;

        if (!history.getFirst().equals("RULESET|" + ruleset))
            throw new IllegalArgumentException("History belongs to a different ruleset");

        if (!historyBefore(history, moment).equals(history))
            throw new IllegalArgumentException(
                    "History includes the current decision or future observations");

    }

    private static GameMoment eventMoment(String[] fields) {

        if (fields.length < 3)
            throw new IllegalArgumentException("Malformed route event");

        try {
            return new GameMoment(
                    Integer.parseInt(fields[0]),
                    GamePhase.valueOf(fields[1]));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Malformed route event timestamp", exception);
        }

    }

    private static List<String> recentEvents(List<String> history) {

        List<String> events = new ArrayList<>();

        // Ruleset and birth are retained by exact matching, not suffix matching.
        for (int index = 2; index < history.size(); index++) {

            String event = history.get(index);
            int separator = event.indexOf('|');

            // Drop historical year, but retain season, action, and result.
            events.add(event.substring(separator + 1));

        }

        return List.copyOf(events);

    }

    private static List<String> tail(List<String> values, int length) {
        return List.copyOf(values.subList(values.size() - length, values.size()));
    }


    // Context uses only information available before simultaneous orders. \\

    private static List<String> context(
            String ruleset, GameMoment moment, BoardState board, UnitId unit) {

        Objects.requireNonNull(ruleset, "ruleset");
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(unit, "unit");

        if (!moment.gamePhase().isMovement())
            throw new IllegalArgumentException("Preferences currently support movement phases only");

        Province location = board.locationOf(unit);

        if (location == null)
            throw new IllegalArgumentException("Prediction unit is absent from the active board");

        List<String> context = new ArrayList<>();

        context.add(ruleset);
        context.add(Integer.toString(moment.year()));
        context.add(moment.gamePhase().name());
        context.add(unit.owner().name());
        context.add(unit.unitType().name());
        context.add(location.name());

        Set<Province> neighborhood = EnumSet.noneOf(Province.class);
        Province territory = Province.canonical(location);

        for (Province province : Province.values()) {

            if (province != Province.canonical(province))
                continue;

            if (province == territory
                    || territory.isAdjacentToIgnoreSplitCoast(province)
                    || province.isAdjacentToIgnoreSplitCoast(territory))
                neighborhood.add(province);

        }

        Map<String, String> occupants = new TreeMap<>();

        for (Map.Entry<UnitId, Province> entry : board.locations().entrySet()) {

            if (!neighborhood.contains(Province.canonical(entry.getValue())))
                continue;

            UnitId occupant = entry.getKey();

            occupants.put(entry.getValue().name(),
                    occupant.owner().name() + "|" + occupant.unitType().name());

        }

        for (Map.Entry<String, String> occupant : occupants.entrySet())
            context.add("UNIT|" + occupant.getKey() + "|" + occupant.getValue());

        for (Province province : neighborhood) {

            if (!province.supplyCenter)
                continue;

            var owner = board.ownerOf(province);

            context.add("CENTER|" + province.name() + "|"
                    + (owner == null ? "NEUTRAL" : owner.name()));

        }

        return List.copyOf(context);

    }

    /**
     * The current context layout begins with ruleset, year, and phase.
     * Replace only the year; retain every other context component.
     *
     * This is used solely for position fallback, never exact route history.
     */
    private static List<String> withoutYear(List<String> context) {

        if (context.size() < 6)
            throw new IllegalArgumentException("Incomplete prediction context");

        List<String> pooled = new ArrayList<>(context);
        pooled.set(1, "*");

        return List.copyOf(pooled);

    }

    private static List<String> key(
            List<String> context, String basis, List<String> history) {

        List<String> result = new ArrayList<>(context.size() + history.size() + 2);

        result.addAll(context);
        result.add("HISTORY");
        result.add(basis);
        result.addAll(history);

        return List.copyOf(result);

    }

    private static List<String> actionKey(Order order) {
        return List.of(
                order.orderType().name(),
                order.target() == null ? "-" : order.target().name(),
                order.auxiliaryTarget() == null ? "-" : order.auxiliaryTarget().name());
    }

    private static Province province(String value) {
        return value.equals("-") ? null : Province.valueOf(value);
    }


    // Read-only access to the underlying lifetime histories. \\

    public synchronized int gameCount() {
        return routes.gameCount();
    }

    public synchronized List<String> route(UUID gameId, UnitId unit) {
        return routes.route(gameId, unit);
    }

    public synchronized long unfinishedAt(List<String> prefix) {
        return routes.unfinishedAt(prefix);
    }


}