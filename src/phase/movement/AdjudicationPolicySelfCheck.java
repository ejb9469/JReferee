package phase.movement;

import _app.CounterSearchApp;
import adjudication.Judge;
import adjudication.util.OrderComparator;
import adjudication.util.Orders;
import agents.generation.ActionCandidate;
import domain.*;
import game.*;
import parsing.datc.DATCFileParser;
import performance.PerformanceStats;
import phase.MovementInput;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.retreats.RetreatProcessor;
import testing.AdjudicatorTestCase;
import testing.DATCAdjTestCase;

import java.util.*;

/**
 * Reference fixtures, movement counters, and small-position permutation checks.
 * Run from the repository root so DATC fixture paths resolve normally.
 */
public final class AdjudicationPolicySelfCheck {
    private static int checks;

    private AdjudicationPolicySelfCheck() {
    }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--timings")) {
            counters();
            System.out.println("AdjudicationPolicySelfCheck timings passed: " + checks + " checks");
            return;
        }
        if (args.length != 0)
            throw new IllegalArgumentException("Usage: AdjudicationPolicySelfCheck [--timings]");
        referenceFixtures();
        counters();
        tacticalPositions();
        CounterSearchApp.Report report = CounterSearchApp.run(7, true);
        compare(report.root(), report.probes().get(Nation.FRANCE), report.probes().get(Nation.GERMANY));
        compare(report.root(),
                report.search().joint().populationPolicies().get(Nation.FRANCE).candidates(),
                report.search().joint().populationPolicies().get(Nation.GERMANY).candidates());
        System.out.println("AdjudicationPolicySelfCheck passed: " + checks + " checks");
    }

    private static void referenceFixtures() {
        Collection<AdjudicatorTestCase> fixtures = new DATCFileParser().parseManyFiles();
        require(!fixtures.isEmpty(), "No DATC fixtures were loaded");
        List<String> failed = new ArrayList<>();
        List<String> fastDifferences = new ArrayList<>();
        for (AdjudicatorTestCase fixture : fixtures) {
            DATCAdjTestCase reference = new DATCAdjTestCase(fixture);
            reference.eval(false);
            if (reference.getScore() != reference.getSize())
                failed.add(reference.getName() + ": " + reference.getScore() + "/" + reference.getSize());
            List<adjudication.Order> canonical = Orders.deepCopy(fixture.getOrders());
            canonical.sort(new OrderComparator());
            Judge plain = new Judge(canonical);
            plain.judge();
            List<adjudication.Order> aligned = new ArrayList<>(Orders.conformOrder(plain.getOrders(), fixture.getOrders()));
            for (int index = 0; index < aligned.size(); index++)
                if (aligned.get(index).verdict != reference.getActualFields().get(index)[0]) {
                    fastDifferences.add(fixture.getName());
                    break;
                }
            checks++;
        }
        require(failed.isEmpty(), "Reference failures: " + failed);
        System.out.println("Reference DATC fixtures: " + fixtures.size());
        System.out.println("Fast/reference DATC verdict differences: " + fastDifferences);
    }

    private static void tacticalPositions() {
        UnitId par = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId pic = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Pic);
        UnitId bur = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Bur);
        UnitId enemy = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Bur);
        UnitId bel = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Bel);
        tactic(List.of(Order.move(par, Province.Bur), Order.move(bur, Province.Pic),
                Order.move(pic, Province.Par)), 3, 0);
        tactic(List.of(Order.move(par, Province.Bur), Order.supportMove(pic, Province.Par, Province.Bur),
                Order.hold(enemy), Order.move(bel, Province.Pic)), 0, 0);
        tactic(List.of(Order.move(par, Province.Bur), Order.supportMove(pic, Province.Par, Province.Bur),
                Order.hold(enemy), Order.hold(bel)), 1, 1);
        tactic(List.of(Order.move(par, Province.Bur), Order.supportMove(pic, Province.Par, Province.Bur),
                Order.move(enemy, Province.Par)), 1, 1);
        tactic(List.of(Order.move(par, Province.Bur), Order.supportMove(pic, Province.Par, Province.Bur),
                Order.hold(bur)), 0, 0);
    }

    private static void tactic(List<Order> orders, int moves, int dislodgements) {
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        orders.forEach(order -> locations.put(order.unit(), order.unit().origin()));
        MovementInput input = new MovementInput(locations, orders);
        MovementResult reference = new MovementProcessor().process(input);
        MovementResult plain = new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0).process(input);
        require(signature(plain).equals(signature(reference)), "Tactical full-result comparison failed");
        require(reference.successfulMoveDestinations().size() == moves, "Unexpected tactical move count");
        require(reference.dislodgements().size() == dislodgements, "Unexpected tactical dislodgement count");
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(locations, Map.of()));
        List<GameState> winters = new ArrayList<>();
        for (MovementProcessor.Policy policy : List.of(
                MovementProcessor.Policy.JUDGE, MovementProcessor.Policy.SZYKMAN_JUSTICE)) {
            Game game = Game.fromState(root, new MovementProcessor(policy, 3_000, 0),
                    new RetreatProcessor(), new AdjustmentProcessor());
            game.resolveMovement(orders);
            if (game.phase().isRetreat())
                game.resolveRetreats(List.of());
            winters.add(game.snapshot());
        }
        require(winters.get(0).moment().equals(winters.get(1).moment())
                        && winters.get(0).board().equals(winters.get(1).board())
                        && winters.get(0).aliveNations().equals(winters.get(1).aliveNations()),
                "Retreat or Fall ownership consequences differ");
    }

    private static void counters() {
        UnitId unit = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        MovementInput input = new MovementInput(Map.of(unit, Province.Par),
                List.of(Order.move(unit, Province.Bur)));
        MovementInput duplicate = new MovementInput(input.startingLocations(),
                List.of(Order.move(unit, Province.Bur), Order.hold(unit)));
        for (MovementProcessor.Policy policy : MovementProcessor.Policy.values()) {
            MovementResult reference = new MovementProcessor(policy, 3_000, 0L).process(input);
            require(reference.finalLocations().get(unit) == Province.Bur, "Ordinary move failed");
            for (boolean enabled : new boolean[]{true, false}) {
                PerformanceStats timings = new PerformanceStats(enabled);
                MovementProcessor.Diagnostics diagnostics = new MovementProcessor.Diagnostics(timings);
                MovementProcessor processor = new MovementProcessor(policy, 3_000, 0L, diagnostics);
                require(signature(processor.process(input)).equals(signature(reference)),
                        "Timing changed movement results");
                timingCalls(timings, 1, 1);
                try {
                    processor.process(null);
                    throw new AssertionError("Null input was accepted");
                } catch (NullPointerException expected) {
                    require("input".equals(expected.getMessage()), "Null-input exception changed");
                }
                timingCalls(timings, 2, 1);
                try {
                    processor.process(duplicate);
                    throw new AssertionError("Duplicate orders were accepted");
                } catch (IllegalArgumentException expected) {
                    require(expected.getMessage().equals("Multiple orders submitted for unit: " + unit),
                            "Duplicate-order exception changed");
                }
                timingCalls(timings, 3, 1);
                require(signature(processor.process(input)).equals(signature(reference)),
                        "A failed attempt affected later movement");
                timingCalls(timings, 4, 2);
                require(diagnostics.snapshot().equals(new MovementProcessor.Counts(
                                4, 2, policy == MovementProcessor.Policy.JUDGE ? 2 : 0,
                                policy == MovementProcessor.Policy.JUDGE ? 0 : 2, 0, 0, 0)),
                        "Timing changed movement or adjudicator counters");
            }
        }
        require(new MovementProcessor().process(input).producer() instanceof adjudication.SzykmanJustice,
                "The default adjudicator changed");
    }

    private static void timingCalls(PerformanceStats timings, long attempts, long adjudications) {
        List<PerformanceStats.Measurement> snapshot = timings.snapshot();
        if (!timings.isEnabled()) {
            require(snapshot.isEmpty(), "Disabled timers retained measurements");
            return;
        }
        Map<String, Long> calls = new LinkedHashMap<>();
        long movementNanos = 0;
        long stageNanos = 0;
        for (var measurement : snapshot) {
            calls.put(measurement.name(), measurement.calls());
            if (measurement.name().equals("movement"))
                movementNanos = measurement.totalNanos();
            else
                stageNanos += measurement.totalNanos();
        }
        require(calls.equals(Map.of(
                        "movement", attempts,
                        "movement preparation", attempts,
                        "movement adjudication", adjudications,
                        "movement result conversion", adjudications)),
                "Movement timing stages or attempt counts differ: " + calls);
        require(stageNanos <= movementNanos, "Movement stages are not contained in the outer timing");
    }

    private static void compare(GameState root, List<ActionCandidate> france, List<ActionCandidate> germany) {
        for (ActionCandidate french : france)
            for (ActionCandidate german : germany) {
                List<Order> orders = new ArrayList<>();
                french.orders().forEach(order -> orders.add((Order) order));
                german.orders().forEach(order -> orders.add((Order) order));
                MovementInput input = new MovementInput(root.board().locations(), orders);
                MovementResult expected = new MovementProcessor().process(input);
                MovementResult fast = new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0L).process(input);
                require(signature(fast).equals(signature(expected)), "Fast/reference movement differs");
                List<NormalizedOrder> normalized = new ArrayList<>();
                List<adjudication.Order> work = new ArrayList<>();
                for (Order order : orders) {
                    NormalizedOrder entry = new NormalizedOrder(order.unit(), order, true);
                    normalized.add(entry);
                    work.add(new adjudication.Order(entry, input.startingLocations().get(order.unit())));
                }
                permute(work, 0, input, normalized, signature(expected));
            }
    }

    private static void permute(List<adjudication.Order> orders, int next, MovementInput input,
                                List<NormalizedOrder> normalized, List<Object> expected) {
        if (next == orders.size()) {
            Judge judge = new Judge(adjudication.util.Orders.deepCopy(orders));
            judge.judge();
            MovementResult result = MovementResult.from(input.startingLocations(), normalized, judge);
            require(signature(result).equals(expected), "An input permutation changed movement results");
            return;
        }
        for (int index = next; index < orders.size(); index++) {
            Collections.swap(orders, next, index);
            permute(orders, next + 1, input, normalized, expected);
            Collections.swap(orders, next, index);
        }
    }

    private static List<Object> signature(MovementResult result) {
        return List.of(result.outcomes(), result.finalLocations(), result.successfulMoveDestinations(),
                result.dislodgements(), result.standoffProvinces());
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }

}