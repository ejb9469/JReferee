package testing.selfcheck;

import agents.evaluation.Evaluator;
import agents.generation.*;
import agents.search.*;
import contracts.OrderForm;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.*;
import performance.PerformanceStats;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Shared policy calls, payoff meanings, and adaptive result extraction.
 */
public final class SearchContractSelfCheck {

    private static final UnitId FRENCH = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
    private static final GameState ROOT = new GameState_Movement(
            new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
            new BoardState(Map.of(FRENCH, Province.Pic), Map.of(Province.Par, Nation.FRANCE)));
    private static final int SAMPLES = 4;
    private static final Evaluator VALUE = state -> {
        Map<Nation, Double> values = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            values.put(nation, nation == Nation.FRANCE
                    && state.board().locationOf(FRENCH) == Province.Bel ? 1.0 : 0.0);
        return values;
    };
    private static int checks;

    private SearchContractSelfCheck() { }

    public static void main(String[] args) {
        commonCalls();
        algorithmDifferences();
        invalidPools();
        adaptiveResults();
        solveSettings();
        explicitGeneration();
        failures();
        System.out.println("SearchContractSelfCheck passed: " + checks + " checks");
    }

    private static void commonCalls() {
        for (PolicySearch search : searches(VALUE)) {
            Game game = game(ROOT);
            SearchResult result = search.search(game, Nation.FRANCE);
            require(result.distribution().power() == Nation.FRANCE, "Returned another nation's policy");
            require(result.samplesPerCandidate() == SAMPLES, "Observation count changed");
            require(result.meanValues().size() == result.distribution().candidates().size(),
                    "Values and candidates are not aligned");
            LegalOrderCatalog catalog = new LegalOrderGenerator().generate(ROOT, Nation.FRANCE);
            for (ActionCandidate candidate : result.distribution().candidates())
                require(new CatalogActionValidator().validate(ROOT, candidate, catalog).isEmpty(),
                        "Returned an incomplete or illegal action");
            require(search.search(game, Nation.FRANCE).equals(result), "Seeded policy call did not repeat");
            requireRestored(game, ROOT);
            expect(UnsupportedOperationException.class, () -> result.meanValues().clear());
            expect(UnsupportedOperationException.class, () -> result.distribution().candidates().clear());
            expect(UnsupportedOperationException.class, () -> result.distribution().probabilities().clear());
            expect(NullPointerException.class, () -> search.search(null, Nation.FRANCE));
            expect(NullPointerException.class, () -> search.search(game, null));
            expect(IllegalArgumentException.class, () -> search.search(game, Nation.GERMANY));
            game.takeSnapshot();
            GameState checkpoint = game.getSnapshot();
            expect(IllegalStateException.class, () -> search.search(game, Nation.FRANCE));
            require(game.getSnapshot() == checkpoint, "Search consumed the caller's checkpoint");
            game.restoreFromSnapshot();
            requireRestored(game, ROOT);
        }
    }

    private static void algorithmDifferences() {
        ActionGenerator unsupportedMove = (request, catalog) -> List.of(
                action(Order.hold(FRENCH), 0), action(Order.move(FRENCH, Province.Bel), Double.NEGATIVE_INFINITY));
        PolicySearch baseline = new BestResponseSearch(VALUE, unsupportedMove, 2, SAMPLES, 7);
        SearchResult greedy = baseline.search(game(ROOT), Nation.FRANCE);
        require(greedy.distribution().probabilities().equals(List.of(0.0, 1.0)),
                "Unregularized search must still allow a zero-prior response");
        require(greedy.meanValues().equals(List.of(0.0, 1.0)), "Raw payoffs changed");
        for (PolicyOutput output : PolicyOutput.values()) {
            PolicySearch regularized = solver(VALUE, unsupportedMove, output);
            SearchResult result = regularized.search(game(ROOT), Nation.FRANCE);
            require(result.distribution().probabilities().equals(List.of(1.0, 0.0)),
                    "Regularized search assigned mass outside anchor support");
            require(result.meanValues().equals(greedy.meanValues()),
                    "Regularization or output selection changed raw payoffs");
            require(result.samplesPerCandidate() == greedy.samplesPerCandidate(),
                    "Algorithms disagree on the observation count");
        }
    }

    private static void invalidPools() {
        ActionCandidate hold = action(Order.hold(FRENCH), 0);
        List<List<ActionCandidate>> invalid = List.of(
                List.of(hold, new ActionCandidate(Nation.FRANCE, hold.orders(), -1,
                        ActionCandidate.Source.EXPLORATION)),
                List.of(action(Order.hold(FRENCH), Double.NEGATIVE_INFINITY),
                        action(Order.move(FRENCH, Province.Bel), Double.NEGATIVE_INFINITY)));
        for (int index = 0; index < invalid.size(); index++) {
            List<ActionCandidate> pool = invalid.get(index);
            ActionGenerator generator = (request, catalog) -> pool;
            AtomicInteger evaluations = new AtomicInteger();
            Evaluator evaluator = state -> {
                evaluations.incrementAndGet();
                return VALUE.evaluate(state);
            };
            for (PolicySearch search : List.of(
                    new BestResponseSearch(evaluator, generator, 2, SAMPLES, 7),
                    solver(evaluator, generator, PolicyOutput.FINAL),
                    solver(evaluator, generator, PolicyOutput.AVERAGE))) {
                Game game = game(ROOT);
                expect(index == 0 ? IllegalArgumentException.class : IllegalStateException.class,
                        () -> search.search(game, Nation.FRANCE));
                requireRestored(game, ROOT);
            }
            require(evaluations.get() == 0, "Invalid pools reached evaluation");
        }
    }

    private static void adaptiveResults() {
        AdaptiveDiLPiKLSearch adaptive = adaptive(VALUE);
        ActionGenerator generator = new UniformActionGenerator();
        PolicySearch search = adaptive.asSearch(1, generator, ActionPrior::convoyFreeUniform);
        expect(IllegalArgumentException.class, () -> adaptive.asSearch(0, generator, ActionPrior::convoyFreeUniform));
        expect(IllegalArgumentException.class, () -> adaptive.asSearch(3, generator, ActionPrior::convoyFreeUniform));
        Game game = game(ROOT);
        AdaptiveDiLPiKLSearch.Result report = adaptive.search(game, Nation.FRANCE, 1, generator,
                ActionPrior::convoyFreeUniform);
        require(report.solves().size() == 2, "Fixture must complete an expansion and re-solve");
        require(report.reason() == AdaptiveDiLPiKLSearch.StopReason.ROUND_LIMIT,
                "Unexpected adaptive stop reason");
        require(report.joint().output() == PolicyOutput.AVERAGE, "Adapter setup lost the solver output mode");
        require(report.result().distribution().candidates().size() == 2, "Final pool omitted the addition");
        require(report.result().samplesPerCandidate() == SAMPLES,
                "Adaptive result combined observations from restarted solves");
        require(report.result().meanValues().equals(List.of(0.0, 1.0)),
                "Expanded solve did not measure both candidates afresh");
        require(search.search(game, Nation.FRANCE).equals(report.result()),
                "Policy adapter did not return the final solve's selected policy");
        requireRestored(game, ROOT);

        Game winter = game(new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT), ROOT.board()));
        winter.resolveMovement(List.of());
        GameState root = winter.snapshot();
        var history = winter.processorHistory();
        require(root instanceof GameState_Adjustments, "Fixture must reach adjustments");
        AdaptiveDiLPiKLSearch.Result winterReport = adaptive.search(winter, Nation.FRANCE, 1, generator,
                ActionPrior::convoyFreeUniform);
        require(winterReport.reason() == AdaptiveDiLPiKLSearch.StopReason.NON_MOVEMENT
                && winterReport.solves().size() == 1, "Nonmovement phase attempted expansion");
        require(search.search(winter, Nation.FRANCE).equals(winterReport.result()),
                "Adapter retained the earlier root or movement pools");
        require(winter.snapshot().equals(root) && winter.getSnapshot() == null
                && winter.processorHistory().equals(history), "Adapter changed the winter root");
    }

    private static void failures() {
        IllegalStateException failure = new IllegalStateException("Injected evaluation failure");
        for (PolicySearch search : searches(state -> { throw failure; })) {
            Game game = game(ROOT);
            require(expect(IllegalStateException.class, () -> search.search(game, Nation.FRANCE)) == failure,
                    "Search replaced the evaluator failure");
            requireRestored(game, ROOT);
        }
    }

    private static void solveSettings() {
        List<ActionCandidate> pool = List.of(action(Order.hold(FRENCH), StrictMath.log(0.75)),
                action(Order.move(FRENCH, Province.Bel), StrictMath.log(0.25)));
        ActionGenerator generator = (request, catalog) -> pool;
        Evaluator constant = state -> {
            Map<Nation, Double> values = new EnumMap<>(Nation.class);
            for (Nation nation : Nation.values())
                values.put(nation, 0.0);
            return values;
        };
        var settings = new JointSearchResult.Settings(new LambdaDistribution(
                List.of(0.1, 0.2), List.of(0.25, 0.75)), 0.1, TemperatureSchedule.ZERO);
        SearchProfile[] source = {settings};
        SearchProfile mutable = new SearchProfile() {
            public LambdaDistribution population() { return source[0].population(); }
            public double agentLambda() { return source[0].agentLambda(); }
            public TemperatureSchedule temperature() { return source[0].temperature(); }
        };
        DiLPiKLSearch solver = mutable.solver(constant, generator, 2, SAMPLES, 7);
        source[0] = PaperSearchProfile.STANDARD;
        Game game = game(ROOT);
        JointSearchResult solved = solver.solve(game, Map.of(Nation.FRANCE, pool));
        require(solved.settings().equals(settings), "Solve settings retained a mutable profile");
        require(solved.withOutput(PolicyOutput.FINAL) == solved, "Same output allocated a replacement");
        require(solved.withOutput(PolicyOutput.FINAL, settings) == solved, "Checked same output allocated a replacement");
        JointSearchResult average = solved.withOutput(PolicyOutput.AVERAGE);
        require(average.settings().equals(settings), "Conversion changed the solve settings");
        require(average.withOutput(PolicyOutput.FINAL).equals(solved), "Conversion must remain reversible");
        expect(UnsupportedOperationException.class, () -> solved.settings().population().probabilities().clear());
        JointActionEvaluator trials = JointActionEvaluator.deterministic(game, constant, 2, new PerformanceStats(false));
        List<SearchProfile> mismatches = List.of(
                new JointSearchResult.Settings(new LambdaDistribution(
                        List.of(0.1, 0.2), List.of(0.75, 0.25)), 0.1, TemperatureSchedule.ZERO),
                new JointSearchResult.Settings(settings.population(), 0.2, TemperatureSchedule.ZERO),
                new JointSearchResult.Settings(settings.population(), 0.1, TemperatureSchedule.ADAPTIVE));
        for (SearchProfile mismatch : mismatches) {
            expect(IllegalArgumentException.class, () -> solved.withOutput(PolicyOutput.FINAL, mismatch));
            expect(IllegalArgumentException.class, () -> solved.withOutput(PolicyOutput.AVERAGE, mismatch));
            expect(IllegalArgumentException.class, () -> TypeReceipt.exact(mismatch, solved, trials));
        }
        require(trials.counts().requests() == 0, "Settings checks evaluated actions");
        require(TypeReceipt.exact(solved, trials).equals(TypeReceipt.exact(settings, solved, trials)),
                "Recorded and explicit profiles produced different receipts");
        require(TypeReceipt.sampled(solved, trials, 2, 7).equals(
                TypeReceipt.sampled(settings, solved, trials, 2, 7)), "Sampled receipt overloads disagree");
        SearchResult inconsistent = new SearchResult(
                new ActionDistribution(pool, List.of(1.0, 0.0)), List.of(0.0, 0.0), SAMPLES);
        expect(IllegalArgumentException.class, () -> new JointSearchResult(
                Map.of(Nation.FRANCE, inconsistent), solved.populationPolicies(), solved.temperatures(), settings));
        expect(NullPointerException.class, () -> new JointSearchResult(
                solved.results(), solved.populationPolicies(), solved.temperatures(), null));
        requireRestored(game, ROOT);
    }

    private static void explicitGeneration() {
        List<GameState> generated = new ArrayList<>(), bound = new ArrayList<>();
        ActionGenerator generator = (request, catalog) -> {
            catalog.requireMatches(request.state(), request.power());
            generated.add(request.state());
            List<OrderForm> orders = List.of(Order.move(FRENCH, Province.Bel));
            return List.of(new ActionCandidate(request.power(), orders,
                    ActionPrior.convoyFreeUniform(request.state()).logProbability(request.power(), orders),
                    ActionCandidate.Source.HEURISTIC));
        };
        Function<GameState, ActionPrior> priors = root -> {
            bound.add(root);
            return ActionPrior.convoyFreeUniform(root);
        };
        ResponseDiscovery responses = new ResponseDiscovery(VALUE, generator, 1, 1, 1, 0);
        AdaptiveDiLPiKLSearch adaptive = new AdaptiveDiLPiKLSearch(
                solver(VALUE, new PlausibleActionGenerator(), PolicyOutput.AVERAGE), responses, 2, 0, 7);
        PolicySearch search = adaptive.asSearch(1, generator, priors);
        GameState later = new GameState_Movement(new GameMoment(1908, GamePhase.SPRING_MOVEMENT), ROOT.board());
        for (GameState root : List.of(ROOT, later)) {
            Game game = game(root);
            SearchResult result = search.search(game, Nation.FRANCE);
            require(result.distribution().candidates().getFirst().orders().equals(List.of(Order.move(FRENCH, Province.Bel))),
                    "Adaptive search ignored the supplied initial generator");
            requireRestored(game, root);
        }
        require(generated.equals(List.of(ROOT, later)) && bound.equals(generated),
                "Initial generation and prior binding must use each call's root");
        expect(NullPointerException.class, () -> adaptive.asSearch(1, null, priors));
        expect(NullPointerException.class, () -> adaptive.asSearch(1, generator, null));
        Game game = game(ROOT);
        expect(NullPointerException.class, () -> adaptive.search(game, Nation.FRANCE, 1, generator, root -> null));
        expect(IllegalArgumentException.class, () -> adaptive.search(game, Nation.FRANCE, 1, generator,
                root -> (power, orders) -> 0.0));
        requireRestored(game, ROOT);
    }

    private static List<PolicySearch> searches(Evaluator evaluator) {
        ActionGenerator generator = (request, catalog) -> List.of(
                action(Order.hold(FRENCH), StrictMath.log(0.75)),
                action(Order.move(FRENCH, Province.Bel), StrictMath.log(0.25)));
        return List.of(new BestResponseSearch(evaluator, generator, 2, SAMPLES, 7),
                solver(evaluator, generator, PolicyOutput.FINAL),
                solver(evaluator, generator, PolicyOutput.AVERAGE),
                adaptive(evaluator).asSearch(1, new UniformActionGenerator(), ActionPrior::convoyFreeUniform));
    }

    private static DiLPiKLSearch solver(Evaluator evaluator, ActionGenerator generator, PolicyOutput output) {
        return new DiLPiKLSearch(evaluator, generator, 2, SAMPLES, LambdaDistribution.fixed(0.1),
                0.1, 7, TemperatureSchedule.ADAPTIVE, output);
    }

    private static AdaptiveDiLPiKLSearch adaptive(Evaluator evaluator) {
        ActionGenerator proposals = (request, catalog) -> {
            catalog.requireMatches(request.state(), request.power());
            List<OrderForm> orders = List.of(Order.move(FRENCH, Province.Bel));
            double prior = ActionPrior.convoyFreeUniform(request.state())
                    .logProbability(request.power(), orders);
            return List.of(new ActionCandidate(request.power(), orders, prior, ActionCandidate.Source.HEURISTIC));
        };
        ResponseDiscovery responses = new ResponseDiscovery(evaluator, proposals, 1, SAMPLES, 1, 0);
        return new AdaptiveDiLPiKLSearch(solver(evaluator, new UniformActionGenerator(), PolicyOutput.AVERAGE),
                responses, 2, 1, 7);
    }

    private static ActionCandidate action(Order order, double prior) {
        return new ActionCandidate(Nation.FRANCE, List.of(order), prior, ActionCandidate.Source.HEURISTIC);
    }

    private static Game game(GameState state) {
        return Game.fromState(state, new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0),
                new RetreatProcessor(), new AdjustmentProcessor());
    }

    private static void requireRestored(Game game, GameState root) {
        require(game.snapshot().equals(root) && game.getSnapshot() == null
                && game.processorHistory().isEmpty(), "Search changed the root or histories");
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
            throw new AssertionError("Unexpected exception", exception);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

}