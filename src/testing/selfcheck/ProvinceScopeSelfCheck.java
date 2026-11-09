package testing.selfcheck;

import _app.CounterSearchApp;
import agents.evaluation.*;
import agents.generation.*;
import agents.search.TemperatureSchedule;
import contracts.OrderForm;
import domain.*;
import game.*;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.util.*;

/**
 * Movement boundaries, unchanged anchors, and scoped continuations.
 */
public final class ProvinceScopeSelfCheck {

    private static int checks;

    private ProvinceScopeSelfCheck() { }

    public static void main(String[] args) {
        catalogBoundaries();
        coastsAndCurrentLocations();
        generatedActions();
        bypasses();
        otherPhases();
        continuationAnchors();
        adaptiveDemonstration();
        System.out.println("ProvinceScopeSelfCheck passed: " + checks + " checks");
    }

    private static void catalogBoundaries() {
        CounterSearchApp.Fixture fixture = CounterSearchApp.fixture();
        GameState root = fixture.root();
        ProvinceScope scope = CounterSearchApp.SCOPE;
        UnitId tyr = unitAt(root, Province.Tyr);
        UnitId ruh = unitAt(root, Province.Ruh);
        LegalOrderCatalog french = catalog(root, Nation.FRANCE);
        LegalOrderCatalog german = catalog(root, Nation.GERMANY);
        require(french.contains(Order.move(tyr, Province.Tri)), "Tyrolia-Trieste must remain legal on the full board");
        require(german.contains(Order.move(ruh, Province.Hol)), "Ruhr-Holland must remain legal on the full board");
        require(!scope.restrict(root, french).contains(Order.move(tyr, Province.Tri)),
                "The tactical scope must exclude Tyrolia-Trieste");
        require(!scope.restrict(root, german).contains(Order.move(ruh, Province.Hol)),
                "The tactical scope must exclude Ruhr-Holland");
        Order outsideDestination = Order.supportMove(ruh, Province.Bel, Province.Hol);
        require(german.contains(outsideDestination), "Support boundary case must be otherwise legal");
        require(!scope.restrict(root, german).contains(outsideDestination),
                "A support destination outside the scope must be excluded");
        for (Nation nation : root.aliveNations()) {
            LegalOrderCatalog restricted = scope.restrict(root, catalog(root, nation));
            require(restricted.unitLocations().equals(catalog(root, nation).unitLocations()),
                    "Scoping must not drop units");
            restricted.requireMatches(root, nation);
            for (UnitId unit : restricted.unitLocations().keySet()) {
                require(restricted.contains(Order.hold(unit)), "Every hold must survive");
                for (OrderForm order : restricted.optionsFor(unit))
                    inside(scope, root, order);
            }
        }

        UnitId bur = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId bel = new UnitId(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        GameState foreign = movement(Map.of(bur, Province.Bur, bel, Province.Bel),
                Map.of(Province.Par, Nation.FRANCE, Province.Lon, Nation.ENGLAND));
        ProvinceScope local = new ProvinceScope(List.of(Province.Bur, Province.Pic));
        Order outsideOrigin = Order.supportMove(bur, Province.Bel, Province.Pic);
        require(catalog(foreign, Nation.FRANCE).contains(outsideOrigin), "Support origin case must be legal");
        require(!local.restrict(foreign, catalog(foreign, Nation.FRANCE)).contains(outsideOrigin),
                "The supported origin must be inside the scope too");

        UnitId army = new UnitId(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        UnitId fleet = new UnitId(Nation.ENGLAND, UnitType.FLEET, Province.Edi);
        GameState convoy = movement(Map.of(army, Province.Lon, fleet, Province.ENG),
                Map.of(Province.Lon, Nation.ENGLAND, Province.Edi, Nation.ENGLAND));
        Order route = Order.convoy(fleet, Province.Lon, Province.Bel);
        require(new ProvinceScope(List.of(Province.Lon, Province.ENG, Province.Bel))
                        .restrict(convoy, catalog(convoy, Nation.ENGLAND)).contains(route),
                "Geographic scoping must preserve an in-scope convoy");
        require(!new ProvinceScope(List.of(Province.Lon, Province.ENG))
                        .restrict(convoy, catalog(convoy, Nation.ENGLAND)).contains(route),
                "Convoy destinations must respect the boundary");
    }

    private static void coastsAndCurrentLocations() {
        List<Province> supplied = new ArrayList<>(List.of(Province.SpaNC, Province.MAO));
        ProvinceScope scope = new ProvinceScope(supplied);
        supplied.clear();
        require(scope.provinces().equals(Set.of(Province.Spa, Province.MAO)), "Scope must copy canonical provinces");
        require(scope.contains(Province.SpaSC), "Membership must include both coasts of the canonical province");
        expect(UnsupportedOperationException.class, () -> scope.provinces().clear());
        expect(IllegalArgumentException.class, () -> new ProvinceScope(List.of()));
        expect(NullPointerException.class, () -> new ProvinceScope(null));

        UnitId fleet = new UnitId(Nation.FRANCE, UnitType.FLEET, Province.Bre);
        GameState root = movement(Map.of(fleet, Province.MAO), Map.of(Province.Bre, Nation.FRANCE));
        LegalOrderCatalog restricted = scope.restrict(root, catalog(root, Nation.FRANCE));
        require(restricted.contains(Order.move(fleet, Province.SpaNC))
                        && restricted.contains(Order.move(fleet, Province.SpaSC)),
                "Retained orders must preserve coast-specific destinations");
        scope.requireWithin(root, new ActionCandidate(Nation.FRANCE, List.of(Order.move(fleet, Province.SpaSC)),
                0, ActionCandidate.Source.HEURISTIC));
        checks++;
        GameState moved = movement(Map.of(fleet, Province.SpaSC), root.board().owners());
        scope.requireWithin(moved, new ActionCandidate(Nation.FRANCE, List.of(Order.hold(fleet)),
                0, ActionCandidate.Source.HEURISTIC));
        checks++;
        expect(IllegalArgumentException.class, () -> scope.restrict(moved, catalog(root, Nation.FRANCE)));
        GameState outside = movement(Map.of(fleet, Province.Por), root.board().owners());
        expect(IllegalArgumentException.class, () -> scope.requireWithin(outside,
                new ActionCandidate(Nation.FRANCE, List.of(Order.hold(fleet)), 0, ActionCandidate.Source.HEURISTIC)));
    }

    private static void generatedActions() {
        CounterSearchApp.Fixture fixture = CounterSearchApp.fixture();
        GameState root = fixture.root();
        ProvinceScope scope = CounterSearchApp.SCOPE;
        for (ActionGenerator delegate : List.of(new UniformActionGenerator(), new PlausibleActionGenerator()))
            for (long seed : new long[]{7, 17, 31})
                for (Nation nation : root.aliveNations()) {
                    LegalOrderCatalog full = catalog(root, nation);
                    ActionGenerationRequest request = new ActionGenerationRequest(root, nation, 32, seed);
                    ScopedActionGenerator generator = new ScopedActionGenerator((input, filtered) -> {
                        for (var entry : filtered.optionsByUnit().values())
                            for (OrderForm order : entry)
                                inside(scope, root, order);
                        return delegate.generate(input, filtered);
                    }, scope);
                    List<ActionCandidate> candidates = generator.generate(request, full);
                    require(!candidates.isEmpty() && candidates.size() <= 32, "Invalid scoped candidate count");
                    require(candidates.getFirst().orders().stream().allMatch(order -> order.orderType() == OrderType.HOLD),
                            "All-hold must remain the first candidate");
                    Set<Set<OrderForm>> seen = new HashSet<>();
                    for (ActionCandidate candidate : candidates) {
                        require(seen.add(Set.copyOf(candidate.orders())), "Scoped candidates must remain distinct");
                        require(new CatalogActionValidator().validate(root, candidate, full).isEmpty(),
                                "Scoped action must remain a complete legal full-board action");
                        require(Double.compare(candidate.logPrior(),
                                        fixture.prior().logProbability(nation, candidate.orders())) == 0,
                                "Filtering must not replace the full-root anchor");
                        for (OrderForm order : candidate.orders())
                            inside(scope, root, order);
                    }
                    double filteredPrior = delegate.generate(request, scope.restrict(root, full))
                            .getFirst().logPrior();
                    require(filteredPrior != candidates.getFirst().logPrior(),
                            "This fixture must exercise actual prior restoration after filtering");
                    expect(UnsupportedOperationException.class, () -> candidates.clear());
                }
        require(root.board().owners().containsKey(Province.Par) && root.board().owners().containsKey(Province.Ber),
                "Outside-scope centers must remain on the full board");
    }

    private static void bypasses() {
        CounterSearchApp.Fixture fixture = CounterSearchApp.fixture();
        GameState root = fixture.root();
        List<OrderForm> orders = new ArrayList<>(fixture.probes().get(Nation.FRANCE).getFirst().orders());
        orders.removeIf(order -> order instanceof Order movement && movement.unit().equals(unitAt(root, Province.Tyr)));
        orders.add(Order.move(unitAt(root, Province.Tyr), Province.Tri));
        ActionCandidate outside = new ActionCandidate(Nation.FRANCE, orders,
                fixture.prior().logProbability(Nation.FRANCE, orders), ActionCandidate.Source.HEURISTIC);
        require(new CatalogActionValidator().validate(root, outside).isEmpty(), "Rejected probe must be otherwise legal");
        expect(IllegalArgumentException.class, () -> CounterSearchApp.SCOPE.requireWithin(root, outside));
        expect(IllegalArgumentException.class, () -> new CounterSearchApp.Fixture(root, fixture.prior(),
                Map.of(Nation.FRANCE, List.of(outside), Nation.GERMANY, fixture.probes().get(Nation.GERMANY))));
        ActionGenerationRequest request = new ActionGenerationRequest(root, Nation.FRANCE, 32, 7);
        expect(IllegalStateException.class, () -> new ScopedActionGenerator(
                (input, catalog) -> List.of(outside), CounterSearchApp.SCOPE)
                .generate(request, catalog(root, Nation.FRANCE)));
        ActionCandidate valid = fixture.probes().get(Nation.FRANCE).getFirst();
        expect(IllegalStateException.class, () -> new ScopedActionGenerator(
                (input, catalog) -> List.of(valid, valid), CounterSearchApp.SCOPE)
                .generate(request, catalog(root, Nation.FRANCE)));
        expect(IllegalArgumentException.class, () -> CounterSearchApp.SCOPE.requireWithin(root,
                new ActionCandidate(Nation.FRANCE, List.of(Order.hold(unitAt(root, Province.Pic))),
                        0, ActionCandidate.Source.HEURISTIC)));
        ProvinceScope incomplete = new ProvinceScope(List.of(Province.Pic, Province.Bur));
        expect(IllegalArgumentException.class, () -> incomplete.restrict(root, catalog(root, Nation.FRANCE)));
    }

    private static void otherPhases() {
        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId ruh = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        UnitId hol = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Kie);
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bel, ruh, Province.Ruh, hol, Province.Hol),
                        Map.of(Province.Par, Nation.FRANCE, Province.Ber, Nation.GERMANY, Province.Kie, Nation.GERMANY)));
        Game game = game(root);
        game.resolveMovement(List.of(Order.hold(french), Order.move(ruh, Province.Bel),
                Order.supportMove(hol, Province.Ruh, Province.Bel)));
        GameState retreats = game.snapshot();
        require(retreats instanceof GameState_Retreats state
                && state.movementResult().dislodgements().containsKey(french), "Retreat test must include a dislodgement");
        UniformActionGenerator baseline = new UniformActionGenerator();
        ProvinceScope scope = new ProvinceScope(List.of(Province.Bel));
        ScopedActionGenerator generator = new ScopedActionGenerator(baseline, scope, state -> {
            throw new AssertionError("Movement anchors must not be bound for retreats or adjustments");
        });
        compareUnchanged(retreats, Nation.FRANCE, baseline, generator);
        expect(IllegalArgumentException.class, () -> scope.restrict(retreats, catalog(retreats, Nation.FRANCE)));
        game.resolveRetreats(List.of());
        require(game.snapshot() instanceof GameState_Adjustments, "Fall retreats must reach winter");
        compareUnchanged(game.snapshot(), Nation.FRANCE, baseline, generator);
    }

    private static void continuationAnchors() {
        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId german = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        GameState spring = movement(Map.of(french, Province.Pic, german, Province.Mun),
                Map.of(Province.Par, Nation.FRANCE, Province.Mun, Nation.GERMANY));
        List<GameState> bound = new ArrayList<>();
        ScopedActionGenerator generator = new ScopedActionGenerator(new UniformActionGenerator(),
                CounterSearchApp.SCOPE, state -> {
            bound.add(state);
            return ActionPrior.convoyFreeUniform(state);
        });
        generator.generate(new ActionGenerationRequest(spring, Nation.FRANCE, 1, 7), catalog(spring, Nation.FRANCE));
        Game game = game(spring);
        game.resolveMovement(List.of(Order.move(french, Province.Bel), Order.hold(german)));
        GameState successor = game.snapshot();
        List<GameState> leaves = new ArrayList<>();
        RolloutEvaluator evaluator = new RolloutEvaluator(state -> {
            leaves.add(state);
            return new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES).evaluate(state);
        }, generator, ProvinceScopeSelfCheck::game, 1, 1, 7);
        Map<Nation, Double> values = evaluator.evaluate(successor);
        require(bound.size() == 3 && bound.getFirst().equals(spring),
                "The anchor factory must run for each movement generation state");
        for (GameState state : bound.subList(1, bound.size())) {
            require(state.moment().gamePhase() == GamePhase.FALL_MOVEMENT, "Continuation anchor must use the current phase");
            require(state.board().locationOf(french) == Province.Bel, "Continuation anchor must use moved unit locations");
        }
        require(leaves.size() == 1 && leaves.getFirst() instanceof GameState_Adjustments,
                "Scoped rollouts must still stop before winter adjustments");
        close(values.get(Nation.FRANCE), 0.8, "Scoring must retain Paris outside the scope and newly owned Belgium");
        require(game.snapshot().equals(successor), "Rollout must not alter the supplied source game");
        GameState fall = bound.get(1);
        ActionCandidate continuation = generator.generate(new ActionGenerationRequest(fall, Nation.FRANCE, 1, 7),
                catalog(fall, Nation.FRANCE)).getFirst();
        close(continuation.logPrior(), ActionPrior.convoyFreeUniform(fall)
                .logProbability(Nation.FRANCE, continuation.orders()), "Continuation prior must be rebound");
    }

    private static void adaptiveDemonstration() {
        for (long seed : new long[]{7, 17, 31}) {
            CounterSearchApp.Report report = CounterSearchApp.run(seed, true,
                    new CounterSearchApp.Options(MovementProcessor.Policy.JUDGE, 128, 3, 512,
                            TemperatureSchedule.ADAPTIVE));
            require(report.matrix().equals(List.of(List.of(0.36, 0.64), List.of(0.64, 0.36))),
                    "Scoping must not change the named-plan payoff matrix");
            for (var solve : report.search().solves())
                for (var policy : solve.populationPolicies().values())
                    for (ActionCandidate candidate : policy.candidates())
                        CounterSearchApp.SCOPE.requireWithin(report.root(), candidate);
            for (var expansion : report.search().expansions())
                for (var proposals : expansion.report().proposals().values())
                    for (var proposal : proposals)
                        CounterSearchApp.SCOPE.requireWithin(report.root(), proposal.candidate());
            checks++;
        }
    }

    private static void compareUnchanged(GameState state, Nation nation, ActionGenerator baseline,
                                         ActionGenerator scoped) {
        ActionGenerationRequest request = new ActionGenerationRequest(state, nation, 8, 7);
        LegalOrderCatalog catalog = catalog(state, nation);
        require(scoped.generate(request, catalog).equals(baseline.generate(request, catalog)),
                "Nonmovement generation must pass through unchanged");
    }

    private static void inside(ProvinceScope scope, GameState state, OrderForm form) {
        if (!(form instanceof Order order))
            throw new AssertionError("Expected a movement order");
        require(scope.contains(state.board().locationOf(order.unit())), "Issuer is outside the scope");
        require(order.target() == null || scope.contains(order.target()), "Target is outside the scope");
        require(order.auxiliaryTarget() == null || scope.contains(order.auxiliaryTarget()),
                "Auxiliary target is outside the scope");
    }

    private static LegalOrderCatalog catalog(GameState state, Nation nation) {
        return new LegalOrderGenerator().generate(state, nation);
    }

    private static GameState movement(Map<UnitId, Province> locations, Map<Province, Nation> owners) {
        return new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(locations, owners));
    }

    private static UnitId unitAt(GameState state, Province province) {
        return state.board().locations().entrySet().stream().filter(entry -> entry.getValue() == province)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static Game game(GameState state) {
        return Game.fromState(state, new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0),
                new RetreatProcessor(), new AdjustmentProcessor());
    }

    private static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-10, message);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable operation) {
        checks++;
        try {
            operation.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception))
                return;
            throw new AssertionError("Expected " + type.getSimpleName(), exception);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

}