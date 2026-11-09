package testing.selfcheck;

import agents.evaluation.*;
import agents.generation.*;
import agents.search.BestResponseSearch;
import agents.search.SearchResult;
import agents.simulation.Simulator;
import contracts.OrderForm;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.*;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;

import java.util.*;

/**
 * Standalone sampled-search and action-distribution checks. Run without -ea.
 */
public final class SearchSelfCheck {

    private static final Evaluator SOS = new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES);
    private static int checks;

    private SearchSelfCheck() { }

    public static void main(String[] args) {
        distributions();
        sharedOpponents();
        tiesAndEmptyActions();
        yearEndAndPhases();
        failures();
        System.out.println("SearchSelfCheck passed: " + checks + " checks");
    }

    private static void distributions() {
        UnitId unit = army(Nation.FRANCE, Province.Par);
        ActionCandidate first = action(Nation.FRANCE, -1000 + StrictMath.log(0.25), Order.hold(unit));
        ActionCandidate second = action(Nation.FRANCE, -1000 + StrictMath.log(0.75), Order.move(unit, Province.Bur));
        List<ActionCandidate> candidates = new ArrayList<>(List.of(first, second));
        ActionDistribution prior = ActionDistribution.fromPriors(candidates);
        close(prior.probabilities().get(0), 0.25, "Small anchor probabilities must normalize without underflow");
        close(prior.probabilities().get(1), 0.75, "Relative priors must be preserved");
        candidates.clear();
        require(prior.candidates().equals(List.of(first, second)), "Candidate lists must be copied");
        require(prior.power() == Nation.FRANCE, "The distribution must retain its nation");
        expect(UnsupportedOperationException.class, () -> prior.probabilities().set(0, 1.0));
        expect(UnsupportedOperationException.class, () -> prior.candidates().clear());
        Random random = new Random(31L);
        ActionDistribution forced = new ActionDistribution(List.of(first, second), List.of(0.0, 1.0));
        for (int sample = 0; sample < 32; sample++)
            require(forced.sample(random) == second, "A zero-probability action must never be selected");
        require(first.logPrior() < -1000 && second.logPrior() < -1000, "Selection must not replace anchor priors");

        for (List<Double> invalid : List.of(List.of(0.0, 0.0), List.of(-0.1, 1.1),
                List.of(Double.NaN, 1.0), List.of(Double.POSITIVE_INFINITY, 0.0), List.of(0.5)))
            expect(IllegalArgumentException.class, () -> new ActionDistribution(List.of(first, second), invalid));
        expect(IllegalArgumentException.class, () -> new ActionDistribution(List.of(), List.of()));
        expect(IllegalArgumentException.class, () -> new ActionDistribution(
                List.of(first, action(Nation.GERMANY, 0)), List.of(0.5, 0.5)));
        expect(IllegalArgumentException.class, () -> ActionDistribution.fromPriors(List.of()));
        expect(IllegalStateException.class, () -> ActionDistribution.fromPriors(
                List.of(action(Nation.FRANCE, Double.NEGATIVE_INFINITY))));
        expect(NullPointerException.class, () -> prior.sample(null));
        expect(NullPointerException.class, () -> ActionDistribution.fromPriors(null));

        List<Double> means = new ArrayList<>(List.of(-2.0, -1.0));
        SearchResult result = new SearchResult(forced, means, 4);
        means.set(0, 99.0);
        require(result.meanValues().equals(List.of(-2.0, -1.0)), "BestResponseSearch values must be copied");
        expect(UnsupportedOperationException.class, () -> result.meanValues().clear());
        expect(IllegalArgumentException.class, () -> new SearchResult(forced, List.of(1.0), 1));
        expect(IllegalArgumentException.class, () -> new SearchResult(forced, List.of(1.0, 2.0), 0));
        expect(IllegalArgumentException.class, () -> new SearchResult(forced, List.of(1.0, Double.NaN), 1));
    }

    private static void sharedOpponents() {
        Fixture fixture = fixture(GamePhase.SPRING_MOVEMENT);
        Game game = game(fixture.state());
        game.resolveMovement(List.of());
        GameState root = game.snapshot();
        var history = game.processorHistory();
        List<Boolean> profiles = new ArrayList<>();
        Map<Nation, Integer> generated = new EnumMap<>(Nation.class);
        ActionGenerator generator = (request, catalog) -> {
            require(request.state().equals(root), "Candidate pools must be generated at the root");
            generated.merge(request.power(), 1, Integer::sum);
            return fixture.generator().generate(request, catalog);
        };
        Evaluator evaluator = state -> {
            require(game.snapshot().equals(root) && game.getSnapshot() == null,
                    "peek must restore the root before calling the evaluator");
            require(game.processorHistory().equals(history), "Each comparison must preserve earlier history");
            boolean atSea = state.board().locationOf(fixture.english()) == Province.NTH;
            boolean captured = state.board().ownerOf(Province.Bel) == Nation.FRANCE;
            profiles.add(atSea);
            Map<Nation, Double> values = values(captured ? (atSea ? 2.0 : 0.0) : 1.0);
            values.put(Nation.ENGLAND, captured ? 0.0 : 100.0);
            return values;
        };
        BestResponseSearch search = new BestResponseSearch(evaluator, generator, 2, 128, 7L);
        SearchResult result = search.search(game, Nation.FRANCE);
        require(generated.equals(Map.of(Nation.FRANCE, 1, Nation.ENGLAND, 1)),
                "Root pools must be generated once for living nations only");
        require(profiles.size() == 256 && profiles.subList(0, 128).equals(profiles.subList(128, 256)),
                "Every candidate must face exactly the same opponent samples");
        long atSea = profiles.subList(0, 128).stream().filter(Boolean::booleanValue).count();
        require(Math.abs(atSea / 128.0 - 0.75) < 0.1, "Opponent sampling must follow its nonuniform prior");
        close(result.meanValues().get(0), 1, "The hold value must be averaged correctly");
        close(result.meanValues().get(1), 2.0 * atSea / 128, "The move value must average all opponent samples");
        require(result.distribution().probabilities().equals(List.of(0.0, 1.0)),
                "Selection must maximize the searching nation's payoff, not total payoff or its prior");
        require(result.distribution().candidates().get(1).logPrior() == Double.NEGATIVE_INFINITY,
                "A selected root action must retain its zero anchor prior");
        require(result.samplesPerCandidate() == 128, "The result must report the sample count");
        require(result.equals(search.search(game, Nation.FRANCE)), "Seeded searches must repeat");
        require(game.snapshot().equals(root) && game.processorHistory().equals(history), "BestResponseSearch must retain the root and history");
    }

    private static void tiesAndEmptyActions() {
        Fixture fixture = fixture(GamePhase.FALL_MOVEMENT);
        SearchResult tied = new BestResponseSearch(state -> values(-2), fixture.generator(), 2, 3, 0L)
                .search(game(fixture.state()), Nation.FRANCE);
        require(tied.distribution().probabilities().equals(List.of(0.5, 0.5)), "Exact ties must share probability");

        GameState alone = new GameState_Movement(fixture.state().moment(),
                new BoardState(Map.of(fixture.french(), Province.Pic), Map.of(Province.Par, Nation.FRANCE)));
        SearchResult singlePower = new BestResponseSearch(new SCEvaluator(), fixture.generator(), 2, 2, 0L)
                .search(game(alone), Nation.FRANCE);
        require(singlePower.distribution().probabilities().equals(List.of(0.0, 1.0)),
                "An empty opponent profile must still permit a search");

        GameState noUnits = new GameState_Movement(fixture.state().moment(),
                new BoardState(Map.of(fixture.english(), Province.Lon),
                        Map.of(Province.Par, Nation.FRANCE, Province.Lon, Nation.ENGLAND)));
        SearchResult empty = new BestResponseSearch(SOS, 2, 2, 0L).search(game(noUnits), Nation.FRANCE);
        require(empty.distribution().candidates().size() == 1
                        && empty.distribution().candidates().getFirst().orders().isEmpty()
                        && empty.distribution().probabilities().equals(List.of(1.0)),
                "A living nation with no orders must receive an explicit empty action");
    }

    private static void yearEndAndPhases() {
        Fixture fixture = fixture(GamePhase.SPRING_MOVEMENT);
        SearchResult immediate = new BestResponseSearch(SOS, fixture.generator(), 2, 2, 0L)
                .search(game(fixture.state()), Nation.FRANCE);
        require(immediate.distribution().probabilities().equals(List.of(0.5, 0.5)), "Spring ownership must remain unchanged");
        Evaluator rollout = new RolloutEvaluator(SOS, new UniformActionGenerator(),
                SearchSelfCheck::game, 2, 1, 0L);
        SearchResult yearEnd = new BestResponseSearch(rollout, fixture.generator(), 2, 2, 0L)
                .search(game(fixture.state()), Nation.FRANCE);
        require(yearEnd.distribution().probabilities().equals(List.of(0.0, 1.0)),
                "BestResponseSearch plus rollout must recognize a Spring capture retained through Fall");
        close(yearEnd.meanValues().get(0), 0.2, "Holding must retain the original center share");
        close(yearEnd.meanValues().get(1), 0.8, "Capturing must improve the year-end center share");

        UnitId french = army(Nation.FRANCE, Province.Bur);
        UnitId german = army(Nation.GERMANY, Province.Mun);
        UnitId support = army(Nation.GERMANY, Province.Ruh);
        Game retreats = game(new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur, german, Province.Mun, support, Province.Ruh),
                        Map.of(Province.Par, Nation.FRANCE, Province.Bel, Nation.GERMANY, Province.Mun, Nation.GERMANY))));
        Simulator simulator = new Simulator();
        GameState retreatRoot = simulator.advance(retreats, Map.of(
                Nation.FRANCE, action(Nation.FRANCE, 0, Order.hold(french)),
                Nation.GERMANY, action(Nation.GERMANY, 0, Order.move(german, Province.Bur),
                        Order.supportMove(support, Province.Mun, Province.Bur))));
        require(retreatRoot instanceof GameState_Retreats, "The fixture must expose retreats");
        SearchResult retreatResult = new BestResponseSearch(SOS, 4, 2, 0L).search(retreats, Nation.FRANCE);
        require(retreatResult.distribution().candidates().stream().allMatch(candidate ->
                        candidate.orders().stream().allMatch(order -> order instanceof RetreatOrder)),
                "Retreat search must generate retreat actions");
        require(retreats.snapshot().equals(retreatRoot), "Retreat facts must survive the search");

        GameState winter = simulator.peek(retreats, Map.of(
                Nation.FRANCE, retreatResult.distribution().sample(new Random(0L)),
                Nation.GERMANY, action(Nation.GERMANY, 0)));
        Game winterGame = game(winter);
        Evaluator nextYear = new RolloutEvaluator(state -> {
            require(state.moment().equals(new GameMoment(1908, GamePhase.WINTER_ADJUSTMENT)),
                    "Winter root actions must be evaluated through the following year's Fall");
            return SOS.evaluate(state);
        }, new UniformActionGenerator(), SearchSelfCheck::game, 1, 1, 0L);
        new BestResponseSearch(nextYear, 4, 1, 0L).search(winterGame, Nation.FRANCE);
        require(winterGame.snapshot().equals(winter), "Winter search must restore the pre-adjustment root");
    }

    private static void failures() {
        Fixture fixture = fixture(GamePhase.FALL_MOVEMENT);
        Game game = game(fixture.state());
        BestResponseSearch search = new BestResponseSearch(SOS, fixture.generator(), 2, 2, 0L);
        expect(IllegalArgumentException.class, () -> search.search(game, Nation.TURKEY));
        expect(NullPointerException.class, () -> search.search(null, Nation.FRANCE));
        expect(NullPointerException.class, () -> search.search(game, null));
        expect(NullPointerException.class, () -> new BestResponseSearch(null, 1, 1, 0L));
        expect(NullPointerException.class, () -> new BestResponseSearch(SOS, null, 1, 1, 0L));
        expect(IllegalArgumentException.class, () -> new BestResponseSearch(SOS, 0, 1, 0L));
        expect(IllegalArgumentException.class, () -> new BestResponseSearch(SOS, 1, 0, 0L));
        game.takeSnapshot();
        GameState checkpoint = game.getSnapshot();
        expect(IllegalStateException.class, () -> search.search(game, Nation.FRANCE));
        require(game.getSnapshot() == checkpoint, "BestResponseSearch must not consume a caller's checkpoint");
        game.restoreFromSnapshot();

        IllegalStateException failure = new IllegalStateException("Injected failure");
        require(failsRestored(fixture, fixture.generator(), state -> { throw failure; },
                IllegalStateException.class) == failure, "Evaluator failures must propagate unchanged");
        failsRestored(fixture, fixture.generator(), state -> null, NullPointerException.class);
        failsRestored(fixture, fixture.generator(), state -> Map.of(Nation.FRANCE, 1.0), IllegalStateException.class);
        failsRestored(fixture, fixture.generator(), state -> {
            Map<Nation, Double> values = values(1);
            values.put(Nation.TURKEY, Double.NaN);
            return values;
        }, IllegalStateException.class);
        failsRestored(fixture, (request, catalog) -> List.of(), SOS, IllegalStateException.class);
        failsRestored(fixture, (request, catalog) -> Collections.nCopies(3, action(request.power(), 0)),
                SOS, IllegalStateException.class);
        failsRestored(fixture, (request, catalog) -> List.of(action(Nation.TURKEY, 0)), SOS, IllegalStateException.class);
        failsRestored(fixture, (request, catalog) -> List.of(action(request.power(), 0)), SOS, IllegalStateException.class);
        failsRestored(fixture, (request, catalog) -> null, SOS, NullPointerException.class);

        Game partial = Game.fromState(fixture.state(), movement(), input -> { throw failure; }, new AdjustmentProcessor());
        require(expect(IllegalStateException.class, () -> search.search(partial, Nation.FRANCE)) == failure,
                "Adjudication failures must propagate unchanged");
        require(partial.snapshot().equals(fixture.state()) && partial.getSnapshot() == null
                && partial.processorHistory().isEmpty(), "Failed peeks must restore partial phase changes");
    }

    private static RuntimeException failsRestored(Fixture fixture, ActionGenerator generator,
                                                  Evaluator evaluator, Class<? extends RuntimeException> type) {
        Game game = game(fixture.state());
        RuntimeException failure = expect(type,
                () -> new BestResponseSearch(evaluator, generator, 2, 2, 0L).search(game, Nation.FRANCE));
        require(game.snapshot().equals(fixture.state()) && game.getSnapshot() == null
                && game.processorHistory().isEmpty(), "BestResponseSearch failures must retain the root");
        return failure;
    }

    private static Fixture fixture(GamePhase phase) {
        UnitId french = army(Nation.FRANCE, Province.Par);
        UnitId english = new UnitId(UUID.randomUUID(), Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        GameState state = new GameState_Movement(new GameMoment(1907, phase),
                new BoardState(Map.of(french, Province.Pic, english, Province.Lon),
                        Map.of(Province.Par, Nation.FRANCE, Province.Bel, Nation.ENGLAND, Province.Lon, Nation.ENGLAND)));
        List<ActionCandidate> france = List.of(action(Nation.FRANCE, 0, Order.hold(french)),
                action(Nation.FRANCE, Double.NEGATIVE_INFINITY, Order.move(french, Province.Bel)));
        List<ActionCandidate> england = List.of(
                action(Nation.ENGLAND, -1000 + StrictMath.log(0.75), Order.move(english, Province.NTH)),
                action(Nation.ENGLAND, -1000 + StrictMath.log(0.25), Order.hold(english)));
        ActionGenerator generator = (request, catalog) -> {
            catalog.requireMatches(request.state(), request.power());
            return request.power() == Nation.FRANCE ? france : england;
        };
        return new Fixture(state, french, english, generator);
    }

    private static Map<Nation, Double> values(double french) {
        Map<Nation, Double> values = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            values.put(nation, nation == Nation.FRANCE ? french : 0.0);
        return values;
    }

    private static Game game(GameState state) {
        return Game.fromState(state, movement(), new RetreatProcessor(), new AdjustmentProcessor());
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

    private record Fixture(GameState state, UnitId french, UnitId english, ActionGenerator generator) { }

}