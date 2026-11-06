package testing.selfcheck;

import agents.evaluation.*;
import agents.generation.*;
import agents.simulation.Simulator;
import contracts.OrderForm;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.*;
import game.press.PressMessage;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;

import java.time.Instant;
import java.util.*;

/**
 * Standalone year-end rollout and shared-resolution checks. Run without -ea.
 */
public final class RolloutEvaluatorSelfCheck {

    private static final Simulator SIMULATOR = new Simulator();
    private static final Evaluator SOS = new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES);
    private static int checks;

    private RolloutEvaluatorSelfCheck() { }

    public static void main(String[] args) {
        Fixture battle = battle();
        List<GameState> phases = phases(battle);
        boundaries(battle, phases);
        skippedRetreats(battle, phases);
        resolutionAndHistory();
        sampling();
        failures(battle, phases);
        arguments(phases.getLast());
        System.out.println("RolloutEvaluatorSelfCheck passed: " + checks + " checks");
    }

    private static void boundaries(Fixture fixture, List<GameState> phases) {
        for (int index = 0; index < phases.size(); index++) {
            GameState start = phases.get(index);
            int remaining = 4 - index;
            Game[] worker = new Game[1];
            int[] factories = { 0 };
            int[] leaves = { 0 };
            Evaluator leaf = state -> {
                leaves[0]++;
                require(state.moment().equals(new GameMoment(1907, GamePhase.WINTER_ADJUSTMENT)),
                        "Every leaf must be current-year winter");
                if (remaining > 0) {
                    require(worker[0].getSnapshot().equals(start), "One root checkpoint must cover the trajectory");
                    require(worker[0].processorHistory().size() == remaining, "Every required phase must resolve");
                }
                return SOS.evaluate(state);
            };
            RolloutEvaluator evaluator = new RolloutEvaluator(leaf, fixture.generator(), state -> {
                factories[0]++;
                worker[0] = game(state);
                return worker[0];
            }, 3, 4, 81L);
            Map<Nation, Double> scores = evaluator.evaluate(start);
            close(scores.get(Nation.FRANCE), 0.5, "Fall retreat into Holland must count");
            close(scores.get(Nation.GERMANY), 0.5, "Germany must retain Belgium and Munich");
            require(scores.keySet().equals(EnumSet.allOf(Nation.class)), "Eliminated nations must remain in the result");
            expect(UnsupportedOperationException.class, () -> scores.put(Nation.FRANCE, 9.0));
            require(leaves[0] == (remaining == 0 ? 1 : 3), "Winter inputs need one direct leaf evaluation");
            require(factories[0] == (remaining == 0 ? 0 : 1), "One game must be reused for all samples");
            if (remaining > 0)
                restored(worker[0], start);
        }
        Evaluator duct = new ScoringEvaluator(ScoringSystem.DUCT_TAPE_V2);
        Map<Nation, Double> values = new RolloutEvaluator(duct, fixture.generator(),
                RolloutEvaluatorSelfCheck::game, 3, 4, 0L).evaluate(fixture.state());
        close(values.values().stream().mapToDouble(Double::doubleValue).sum(), 104.0 / 75,
                "Averaging must preserve variable-total rewards");
    }

    private static void skippedRetreats(Fixture fixture, List<GameState> phases) {
        ActionGenerator holds = new UniformActionGenerator();
        GameState noRetreat = SIMULATOR.peek(game(fixture.state()), joint(fixture.state(), holds));
        require(noRetreat.moment().gamePhase() == GamePhase.FALL_MOVEMENT, "Holds must skip Spring retreats");
        List<GameMoment> horizons = new ArrayList<>();
        Evaluator leaf = state -> {
            horizons.add(state.moment());
            return SOS.evaluate(state);
        };
        RolloutEvaluator evaluator = new RolloutEvaluator(leaf, holds,
                RolloutEvaluatorSelfCheck::game, 2, 1, 4L);
        evaluator.evaluate(noRetreat);
        evaluator.evaluate(phases.get(1));
        require(horizons.size() == 4 && horizons.stream().allMatch(
                        moment -> moment.equals(new GameMoment(1907, GamePhase.WINTER_ADJUSTMENT))),
                "Compared successors must share a horizon despite different retreat paths");

        Game live = StandardGameFactory.create1901();
        GameState original = live.snapshot();
        RolloutEvaluator standard = new RolloutEvaluator(SOS, 3, 4, 19L);
        Map<Nation, Double> first = standard.evaluate(original);
        require(first.equals(standard.evaluate(original)), "Seeded standard rollouts must repeat");
        require(live.snapshot().equals(original) && live.getSnapshot() == null, "The supplied position's game must stay untouched");
        close(first.values().stream().mapToDouble(Double::doubleValue).sum(), 1, "Mean SoS rewards must sum to one");
    }

    private static void resolutionAndHistory() {
        Fixture fixture = capture(GamePhase.SPRING_MOVEMENT, 0, Double.NEGATIVE_INFINITY);
        Game game = game(fixture.state());
        Map<Nation, ActionCandidate> actions = joint(fixture.state(), fixture.generator());
        GameState peeked = SIMULATOR.peek(game, actions);
        require(SIMULATOR.advance(game, actions).equals(peeked), "advance and peek must adjudicate identically");
        require(game.snapshot().equals(peeked) && game.getSnapshot() == null, "advance must keep the new position without creating a checkpoint");
        game.recordPress(new PressMessage(UUID.randomUUID(), new Moment(1907, game.phase(), Instant.EPOCH),
                Nation.FRANCE, Set.of(Nation.GERMANY), "Orders", "Ready."));
        var processors = game.processorHistory();
        var press = game.pressHistory();
        game.takeSnapshot();
        GameState saved = game.getSnapshot();
        expect(IllegalArgumentException.class, () -> SIMULATOR.advance(game, Map.of()));
        require(game.snapshot().equals(peeked) && game.getSnapshot() == saved, "Invalid actions must not touch the saved checkpoint");
        SIMULATOR.advance(game, joint(peeked, new UniformActionGenerator()));
        require(game.phase() == GamePhase.WINTER_ADJUSTMENT && game.getSnapshot() == saved,
                "Resolution under a caller checkpoint must keep both the advance and checkpoint");
        game.restoreFromSnapshot();
        require(game.snapshot().equals(peeked), "The outer checkpoint must restore the entire advance");

        new RolloutEvaluator(SOS, new UniformActionGenerator(), ignored -> game, 3, 1, 0L).evaluate(peeked);
        require(game.snapshot().equals(peeked) && game.getSnapshot() == null, "Rollouts must restore a reused worker");
        require(game.processorHistory().equals(processors) && game.pressHistory().equals(press),
                "Earlier press and processor histories must survive every trajectory");
    }

    private static void sampling() {
        Fixture fixture = capture(GamePhase.FALL_MOVEMENT, -1000 + StrictMath.log(0.75),
                -1000 + StrictMath.log(0.25));
        int[] captures = { 0 };
        Evaluator leaf = state -> {
            if (state.board().ownerOf(Province.Bel) == Nation.FRANCE)
                captures[0]++;
            return SOS.evaluate(state);
        };
        RolloutEvaluator evaluator = new RolloutEvaluator(leaf, fixture.generator(),
                RolloutEvaluatorSelfCheck::game, 256, 2, 54L);
        Map<Nation, Double> values = evaluator.evaluate(fixture.state());
        close(values.get(Nation.FRANCE), 0.5 + 0.5 * captures[0] / 256, "Scores must be the arithmetic mean of leaf values");
        require(Math.abs(captures[0] / 256.0 - 0.75) < 0.08, "Sampling must respect relative priors, including very small probabilities");

        Fixture zero = capture(GamePhase.FALL_MOVEMENT, -1000, Double.NEGATIVE_INFINITY);
        close(new RolloutEvaluator(SOS, zero.generator(), RolloutEvaluatorSelfCheck::game,
                8, 2, 9L).evaluate(zero.state()).get(Nation.FRANCE), 1, "Zero-prior actions must never be selected");
        Fixture impossible = capture(GamePhase.FALL_MOVEMENT, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        failsRestored(impossible.state(), impossible.generator(), SOS, IllegalStateException.class);
    }

    private static void failures(Fixture fixture, List<GameState> phases) {
        IllegalStateException failure = new IllegalStateException("Injected failure");
        ActionGenerator broken = (request, catalog) -> {
            if (request.state().moment().gamePhase() == GamePhase.FALL_MOVEMENT)
                throw failure;
            return fixture.generator().generate(request, catalog);
        };
        require(failsRestored(fixture.state(), broken, SOS, IllegalStateException.class) == failure,
                "A late generation failure must propagate unchanged");
        require(failsRestored(fixture.state(), fixture.generator(), state -> { throw failure; },
                IllegalStateException.class) == failure, "Leaf failures must propagate unchanged");
        failsRestored(fixture.state(), (request, catalog) -> List.of(), SOS, IllegalStateException.class);
        failsRestored(fixture.state(), (request, catalog) -> Collections.nCopies(5, action(request.power(), 0)),
                SOS, IllegalStateException.class);
        failsRestored(fixture.state(), (request, catalog) -> null, SOS, NullPointerException.class);
        failsRestored(fixture.state(), (request, catalog) -> List.of(action(Nation.TURKEY, 0)),
                SOS, IllegalStateException.class);
        failsRestored(fixture.state(), (request, catalog) -> List.of(action(request.power(), 0)),
                SOS, IllegalArgumentException.class);
        failsRestored(fixture.state(), fixture.generator(), state -> Map.of(Nation.FRANCE, 1.0),
                IllegalStateException.class);
        failsRestored(fixture.state(), fixture.generator(), state -> null, NullPointerException.class);
        failsRestored(fixture.state(), fixture.generator(), state -> {
            Map<Nation, Double> scores = new EnumMap<>(SOS.evaluate(state));
            scores.put(Nation.FRANCE, Double.NaN);
            return scores;
        }, IllegalStateException.class);

        Fixture capture = capture(GamePhase.FALL_MOVEMENT, 0, Double.NEGATIVE_INFINITY);
        Game partial = Game.fromState(capture.state(), movement(), input -> { throw failure; }, new AdjustmentProcessor());
        RolloutEvaluator evaluator = new RolloutEvaluator(SOS, capture.generator(), ignored -> partial, 2, 2, 0L);
        require(expect(IllegalStateException.class, () -> evaluator.evaluate(capture.state())) == failure,
                "A partially completed Fall transition must propagate its processor failure");
        restored(partial, capture.state());

        Game saved = game(fixture.state());
        saved.takeSnapshot();
        GameState checkpoint = saved.getSnapshot();
        expect(IllegalStateException.class, () -> new RolloutEvaluator(SOS, fixture.generator(),
                ignored -> saved, 1, 1, 0L).evaluate(fixture.state()));
        require(saved.getSnapshot() == checkpoint && saved.snapshot().equals(fixture.state()),
                "A factory's existing checkpoint must remain untouched");
        saved.restoreFromSnapshot();
        expect(IllegalStateException.class, () -> new RolloutEvaluator(SOS, fixture.generator(),
                ignored -> game(phases.getLast()), 1, 1, 0L).evaluate(fixture.state()));
        expect(NullPointerException.class, () -> new RolloutEvaluator(SOS, fixture.generator(),
                ignored -> null, 1, 1, 0L).evaluate(fixture.state()));
    }

    private static void arguments(GameState winter) {
        expect(NullPointerException.class, () -> new RolloutEvaluator(null, 1, 1, 0L));
        expect(NullPointerException.class, () -> new RolloutEvaluator(SOS, null, Game::fromState, 1, 1, 0L));
        expect(NullPointerException.class, () -> new RolloutEvaluator(SOS, new UniformActionGenerator(), null, 1, 1, 0L));
        expect(IllegalArgumentException.class, () -> new RolloutEvaluator(SOS, 0, 1, 0L));
        expect(IllegalArgumentException.class, () -> new RolloutEvaluator(SOS, 1, 0, 0L));
        expect(NullPointerException.class, () -> new RolloutEvaluator(SOS, 1, 1, 0L).evaluate(null));
        Map<Nation, Double> mutable = new EnumMap<>(SOS.evaluate(winter));
        Map<Nation, Double> scores = new RolloutEvaluator(state -> mutable, 1, 1, 0L).evaluate(winter);
        mutable.put(Nation.FRANCE, 99.0);
        close(scores.get(Nation.FRANCE), 0.5, "Even a direct winter result must be an independent immutable map");
        expect(UnsupportedOperationException.class, () -> scores.entrySet().iterator().next().setValue(0.0));
        GameState empty = new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(), Map.of()));
        require(new RolloutEvaluator(new SCEvaluator(), 2, 1, 0L).evaluate(empty)
                        .values().stream().allMatch(value -> value == 0.0),
                "An empty custom position must still advance when the leaf evaluator accepts it");
    }

    private static List<GameState> phases(Fixture fixture) {
        List<GameState> states = new ArrayList<>();
        Game game = game(fixture.state());
        states.add(game.snapshot());
        while (!(states.getLast() instanceof GameState_Adjustments))
            states.add(SIMULATOR.advance(game, joint(states.getLast(), fixture.generator())));
        require(states.size() == 5, "The fixture must expose all four movement/retreat phases");
        for (int index = 0; index < states.size(); index++)
            require(states.get(index).moment().gamePhase() == GamePhase.values()[index], "Fixture phases must follow the year");
        return List.copyOf(states);
    }

    private static Fixture battle() {
        UnitId french = army(Nation.FRANCE, Province.Bur);
        UnitId german = army(Nation.GERMANY, Province.Mun);
        UnitId support = army(Nation.GERMANY, Province.Ruh);
        GameState start = new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur, german, Province.Mun, support, Province.Ruh),
                        Map.of(Province.Par, Nation.FRANCE, Province.Bel, Nation.GERMANY, Province.Mun, Nation.GERMANY)));
        ActionGenerator generator = (request, catalog) -> {
            catalog.requireMatches(request.state(), request.power());
            return List.of(switch (request.state().moment().gamePhase()) {
                case SPRING_MOVEMENT -> request.power() == Nation.FRANCE
                        ? action(Nation.FRANCE, 0, Order.hold(french))
                        : action(Nation.GERMANY, 0, Order.move(german, Province.Bur),
                        Order.supportMove(support, Province.Mun, Province.Bur));
                case FALL_MOVEMENT -> request.power() == Nation.FRANCE
                        ? action(Nation.FRANCE, 0, Order.hold(french))
                        : action(Nation.GERMANY, 0, Order.move(german, Province.Bel),
                        Order.supportMove(support, Province.Bur, Province.Bel));
                case SPRING_RETREAT -> request.power() == Nation.FRANCE
                        ? action(Nation.FRANCE, 0, new RetreatOrder(french, Province.Bel))
                        : action(Nation.GERMANY, 0);
                case FALL_RETREAT -> request.power() == Nation.FRANCE
                        ? action(Nation.FRANCE, 0, new RetreatOrder(french, Province.Hol))
                        : action(Nation.GERMANY, 0);
                case WINTER_ADJUSTMENT -> throw new AssertionError("No winter actions may be generated");
            });
        };
        return new Fixture(start, generator);
    }

    private static Fixture capture(GamePhase phase, double movePrior, double holdPrior) {
        UnitId french = army(Nation.FRANCE, Province.Par);
        GameState state = new GameState_Movement(new GameMoment(1907, phase),
                new BoardState(Map.of(french, Province.Pic), Map.of(Province.Par, Nation.FRANCE, Province.Bel, Nation.GERMANY)));
        ActionGenerator generator = (request, catalog) -> request.power() == Nation.FRANCE
                ? List.of(action(Nation.FRANCE, movePrior, Order.move(french, Province.Bel)),
                action(Nation.FRANCE, holdPrior, Order.hold(french)))
                : List.of(action(request.power(), 0));
        return new Fixture(state, generator);
    }

    private static Map<Nation, ActionCandidate> joint(GameState state, ActionGenerator generator) {
        Map<Nation, ActionCandidate> actions = new EnumMap<>(Nation.class);
        for (Nation nation : state.aliveNations())
            actions.put(nation, generator.generate(new ActionGenerationRequest(state, nation, 4, 0L),
                    new LegalOrderGenerator().generate(state, nation)).getFirst());
        return actions;
    }

    private static RuntimeException failsRestored(GameState state, ActionGenerator generator,
                                                  Evaluator leaf, Class<? extends RuntimeException> type) {
        Game worker = game(state);
        RuntimeException failure = expect(type, () -> new RolloutEvaluator(leaf, generator,
                ignored -> worker, 2, 4, 0L).evaluate(state));
        restored(worker, state);
        return failure;
    }

    private static void restored(Game game, GameState state) {
        require(game.snapshot().equals(state) && game.getSnapshot() == null, "Rollout must restore its input and consume its checkpoint");
        require(game.processorHistory().isEmpty() && game.pressHistory().isEmpty(), "Rollout must trim speculative histories");
    }

    private static Game game(GameState state) {
        return Game.fromState(state, movement(), new RetreatProcessor(), input -> {
            throw new AssertionError("No winter adjustments may be resolved");
        });
    }

    private static MovementProcessor movement() {
        return new MovementProcessor(MovementProcessor.Policy.SZYKMAN_JUSTICE, 4, 0L);
    }

    private static UnitId army(Nation nation, Province origin) {
        return new UnitId(UUID.randomUUID(), nation, UnitType.ARMY, origin);
    }

    private static ActionCandidate action(Nation nation, double prior, OrderForm... orders) {
        return new ActionCandidate(nation, List.of(orders), prior, ActionCandidate.Source.HEURISTIC);
    }

    private static void close(double actual, double expected, String message) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-10, message + ": got " + actual);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }

    private static RuntimeException expect(Class<? extends RuntimeException> type, Runnable operation) {
        checks++;
        try {
            operation.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception))
                return exception;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + exception, exception);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private record Fixture(GameState state, ActionGenerator generator) { }

}