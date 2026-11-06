package testing.selfcheck;

import agents.generation.*;
import contracts.OrderForm;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.*;
import phase.MovementInput;
import phase.Order;
import phase.RetreatInput;
import phase.UnitId;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;
import phase.retreats.RetreatProcessor;
import phase.retreats.RetreatResult;
import testing.TestCase;
import testing.TestEvaluation;

import java.math.BigInteger;
import java.util.*;

public final class MovementActionGeneratorTestCase implements TestCase {

    private TestEvaluation evaluation;

    @Override
    public void eval(boolean print) {
        TestEvaluation.Builder checks = new TestEvaluation.Builder();
        checkCandidates(checks);
        checkReproducibility(checks);
        checkSmallSpace(checks);
        checkEmptyAction(checks);
        checkLargeSpace(checks);
        checkInputs(checks);
        evaluation = checks.build();

        if (print)
            printEval();
    }

    private static void checkCandidates(TestEvaluation.Builder checks) {
        GameState_Movement state = exampleState();
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        Map<UnitId, List<OrderForm>> originalOptions = new LinkedHashMap<>(catalog.optionsByUnit());
        ActionGenerator generator = new UniformActionGenerator();
        List<ActionCandidate> candidates = generator.generate(request(state, 12, 17L), catalog);
        checks.expect("Requested candidate count is reached for this seed", 12, candidates.size());
        checks.expect("All-hold is first", true, candidates.getFirst().orders().stream()
                .allMatch(order -> order.orderType() == OrderType.HOLD));
        checks.expect("All-hold records its construction method",
                ActionCandidate.Source.HEURISTIC, candidates.getFirst().source());
        List<Order> expectedHolds = state.board().locations().entrySet().stream()
                .filter(entry -> entry.getKey().owner() == Nation.FRANCE)
                .sorted(Map.Entry.comparingByValue())
                .map(entry -> Order.hold(entry.getKey()))
                .toList();
        checks.expect("Orders follow current-province ordering",
                expectedHolds, candidates.getFirst().orders());

        double expectedPrior = 0.0;
        for (List<OrderForm> options : catalog.optionsByUnit().values())
            expectedPrior -= StrictMath.log(options.size());
        Set<List<OrderForm>> seen = new HashSet<>();
        CatalogActionValidator validator = new CatalogActionValidator();
        for (int index = 0; index < candidates.size(); index++) {
            ActionCandidate candidate = candidates.get(index);
            checks.expect("Complete valid candidate " + index,
                    List.of(), validator.validate(state, candidate, catalog));
            checks.expect("Unique submitted orders " + index, true, seen.add(candidate.orders()));
            checks.expect("Full-action uniform prior " + index, true,
                    Math.abs(candidate.logPrior() - expectedPrior) < 1.0e-12);
            if (index > 0)
                checks.expect("Sample source " + index,
                        ActionCandidate.Source.POLICY_SAMPLE, candidate.source());
        }
        for (int limit : List.of(1, 2, 7, 19)) {
            List<ActionCandidate> limited = generator.generate(request(state, limit, 17L), catalog);
            checks.expect("Candidate limit " + limit, limit, limited.size());
            checks.expect("Prior is independent of the candidate limit " + limit,
                    candidates.getFirst().logPrior(), limited.getFirst().logPrior());
        }
        checks.expect("Catalog order lists remain unchanged", originalOptions, catalog.optionsByUnit());
        expectThrows(checks, "Returned candidates are immutable", UnsupportedOperationException.class,
                () -> candidates.add(candidates.getFirst()));
        expectThrows(checks, "Candidate orders are immutable", UnsupportedOperationException.class,
                () -> candidates.getFirst().orders().clear());
        LegalOrderCatalog englishCatalog = new LegalOrderGenerator().generate(state, Nation.ENGLAND);
        List<ActionCandidate> english = generator.generate(
                new ActionGenerationRequest(state, Nation.ENGLAND, 3, 17L), englishCatalog);
        checks.expect("Requested nation is preserved", true, english.stream()
                .allMatch(candidate -> candidate.power() == Nation.ENGLAND
                        && validator.validate(state, candidate, englishCatalog).isEmpty()));
    }

    private static void checkReproducibility(TestEvaluation.Builder checks) {
        GameState_Movement state = exampleState();
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        UniformActionGenerator generator = new UniformActionGenerator();
        List<ActionCandidate> first = generator.generate(request(state, 12, 17L), catalog);
        generator.generate(request(state, 19, 999L), catalog);
        checks.expect("Earlier calls do not change seeded output",
                first, generator.generate(request(state, 12, 17L), catalog));
        checks.expect("Different seed changes samples for this fixture", false,
                first.equals(generator.generate(request(state, 12, 18L), catalog)));

        List<Map.Entry<UnitId, Province>> entries =
                new ArrayList<>(state.board().locations().entrySet());
        Collections.reverse(entries);
        Map<UnitId, Province> reversedLocations = new LinkedHashMap<>();
        for (Map.Entry<UnitId, Province> entry : entries)
            reversedLocations.put(entry.getKey(), entry.getValue());
        GameState_Movement reversedState = state(reversedLocations);
        LegalOrderCatalog reversedCatalog =
                new LegalOrderGenerator().generate(reversedState, Nation.FRANCE);
        checks.expect("Reordered state has equal values", state, reversedState);
        boolean differentOptionOrder = false;
        for (UnitId unit : catalog.optionsByUnit().keySet())
            if (!catalog.optionsFor(unit).equals(reversedCatalog.optionsFor(unit)))
                differentOptionOrder = true;
        checks.expect("Fixture also changes support-option iteration order", true, differentOptionOrder);
        checks.expect("Map and option iteration order do not change seeded output",
                first, generator.generate(request(reversedState, 12, 17L), reversedCatalog));
        for (long seed : new long[] {0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE})
            checks.expect("Repeatable seed " + seed,
                    generator.generate(request(state, 5, seed), catalog),
                    new UniformActionGenerator().generate(request(state, 5, seed), catalog));
    }

    private static void checkSmallSpace(TestEvaluation.Builder checks) {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState_Movement state = state(Map.of(army, Province.Par));
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        UniformActionGenerator generator = new UniformActionGenerator();
        List<ActionCandidate> candidates = generator.generate(
                request(state, Integer.MAX_VALUE, 3L), catalog);
        Set<OrderForm> actual = new HashSet<>();
        double totalProbability = 0.0;
        int holds = 0;
        for (ActionCandidate candidate : candidates) {
            actual.addAll(candidate.orders());
            totalProbability += StrictMath.exp(candidate.logPrior());
            if (candidate.orders().equals(List.of(Order.hold(army))))
                holds++;
        }
        checks.expect("Finite action space caps a very large request",
                catalog.optionsFor(army).size(), candidates.size());
        checks.expect("Every single-unit option is reached for this seed",
                new HashSet<>(catalog.optionsFor(army)), actual);
        checks.expect("All-hold is not duplicated with a different source", 1, holds);
        checks.expect("Full-space probabilities sum to one",
                true, Math.abs(totalProbability - 1.0) < 1.0e-12);
        List<ActionCandidate> one = generator.generate(request(state, 1, 3L), catalog);
        checks.expect("A one-candidate output still uses the full-space prior", true,
                Math.abs(one.getFirst().logPrior()
                        + StrictMath.log(catalog.optionsFor(army).size())) < 1.0e-12);
        checks.expect("All-hold prior is not forced to probability one",
                true, one.getFirst().logPrior() < 0.0);
    }

    private static void checkEmptyAction(TestEvaluation.Builder checks) {
        UnitId foreign = unit(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        GameState_Movement state = state(Map.of(foreign, Province.Lon));
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        List<ActionCandidate> candidates = new UniformActionGenerator().generate(
                request(state, Integer.MAX_VALUE, Long.MIN_VALUE), catalog);
        checks.expect("Nation without units has exactly one candidate", 1, candidates.size());
        checks.expect("Empty action has no orders", List.of(), candidates.getFirst().orders());
        checks.expect("Empty action has probability one", 0.0, candidates.getFirst().logPrior());
        checks.expect("Empty action retains the requested nation",
                Nation.FRANCE, candidates.getFirst().power());
        checks.expect("Empty action passes validation", List.of(),
                new CatalogActionValidator().validate(state, candidates.getFirst(), catalog));
    }

    private static void checkLargeSpace(TestEvaluation.Builder checks) {
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        for (Province province : List.of(
                Province.Bre, Province.Pic, Province.Bel, Province.Hol,
                Province.Kie, Province.Den, Province.Ber, Province.Pru,
                Province.Lvn, Province.Fin, Province.Stp, Province.Nwy,
                Province.Con, Province.Ank, Province.Smy, Province.Syr))
            locations.put(unit(Nation.FRANCE, UnitType.ARMY, province), province);
        GameState_Movement state = state(locations);
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        BigInteger combinations = BigInteger.ONE;
        for (List<OrderForm> options : catalog.optionsByUnit().values())
            combinations = combinations.multiply(BigInteger.valueOf(options.size()));
        checks.expect("Fixture exceeds long-sized action counts",
                true, combinations.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
        List<ActionCandidate> candidates = new UniformActionGenerator()
                .generate(request(state, 16, 7L), catalog);
        checks.expect("Large action space is capped without overflow", 16, candidates.size());
        checks.expect("Large-space log priors remain finite", true,
                candidates.stream().allMatch(candidate -> Double.isFinite(candidate.logPrior())
                        && candidate.logPrior() < 0.0));
    }

    private static void checkInputs(TestEvaluation.Builder checks) {
        GameState_Movement state = exampleState();
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        UniformActionGenerator generator = new UniformActionGenerator();
        ActionGenerationRequest request = request(state, 1, 0L);
        expectThrows(checks, "Null request is rejected", NullPointerException.class,
                () -> generator.generate(null, catalog));
        expectThrows(checks, "Null catalog is rejected", NullPointerException.class,
                () -> generator.generate(request, null));
        expectThrows(checks, "Wrong catalog nation is rejected", IllegalArgumentException.class,
                () -> generator.generate(
                        new ActionGenerationRequest(state, Nation.TURKEY, 1, 0L), catalog));
        GameState_Movement later = new GameState_Movement(
                new GameMoment(1902, GamePhase.SPRING_MOVEMENT), state.board());
        expectThrows(checks, "Stale catalog is rejected before constructing all-hold",
                IllegalArgumentException.class, () -> generator.generate(request(later, 1, 0L), catalog));
        GameState_Movement empty = state(Map.of());
        expectThrows(checks, "Different board is rejected", IllegalArgumentException.class,
                () -> generator.generate(request(empty, 1, 0L), catalog));
        MovementResult movement = new MovementProcessor()
                .process(new MovementInput(Map.of(), List.of()));
        RetreatResult retreats = new RetreatProcessor()
                .process(new RetreatInput(movement, List.of()));
        GameState_Adjustments adjustments = new GameState_Adjustments(
                new GameMoment(1901, GamePhase.WINTER_ADJUSTMENT), empty.board(), retreats);
        expectThrows(checks, "Nonmovement state is rejected", IllegalArgumentException.class,
                () -> generator.generate(
                        new ActionGenerationRequest(adjustments, Nation.FRANCE, 1, 0L), catalog));
        expectThrows(checks, "Zero limit is rejected by the request", IllegalArgumentException.class,
                () -> request(state, 0, 0L));
        expectThrows(checks, "Negative limit is rejected by the request", IllegalArgumentException.class,
                () -> request(state, -1, 0L));
    }

    private static GameState_Movement exampleState() {
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        locations.put(unit(Nation.FRANCE, UnitType.ARMY, Province.Par), Province.Gas);
        locations.put(unit(Nation.ENGLAND, UnitType.FLEET, Province.Lon), Province.MAO);
        locations.put(unit(Nation.FRANCE, UnitType.FLEET, Province.Smy), Province.LYO);
        locations.put(unit(Nation.ENGLAND, UnitType.ARMY, Province.Lvp), Province.Bre);
        locations.put(unit(Nation.FRANCE, UnitType.ARMY, Province.Mar), Province.Bur);
        return state(locations);
    }

    private static UnitId unit(Nation owner, UnitType type, Province origin) {
        return new UnitId(UUID.randomUUID(), owner, type, origin);
    }

    private static GameState_Movement state(Map<UnitId, Province> locations) {
        return new GameState_Movement(
                new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                new BoardState(locations, Map.of()));
    }

    private static ActionGenerationRequest request(GameState state, int limit, long seed) {
        return new ActionGenerationRequest(state, Nation.FRANCE, limit, seed);
    }

    private static void expectThrows(
            TestEvaluation.Builder checks, String label,
            Class<? extends RuntimeException> expected, Runnable action
    ) {
        try {
            action.run();
            checks.expect(label, expected, null);
        } catch (RuntimeException actual) {
            checks.expect(label, expected, actual.getClass());
        }
    }

    @Override
    public String getName() {
        return "Movement action generation";
    }

    @Override
    public String getEval() {
        return evaluation == null ? null : evaluation.format(getName());
    }

    @Override
    public int getScore() {
        return evaluation == null ? 0 : evaluation.score();
    }

    @Override
    public int getSize() {
        return evaluation == null ? 0 : evaluation.size();
    }

    @Override
    public void printEval() {
        System.out.println(evaluation == null ? "UNEVALUATED " + getName() : getEval());
    }

    @Override
    public void printNameAndScore() {
        System.out.printf("[%02d/%02d]\t%s%n", getScore(), getSize(), getName());
    }

    public static void main(String[] args) {
        MovementActionGeneratorTestCase test = new MovementActionGeneratorTestCase();
        test.eval(true);

        if (!test.passed())
            throw new AssertionError("Movement action generation tests failed");
    }

}