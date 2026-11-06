package testing;

import agents.generation.*;
import contracts.OrderForm;
import contracts.OrderForms;
import domain.*;
import game.*;
import phase.AdjustmentInput;
import phase.MovementInput;
import phase.Order;
import phase.RetreatInput;
import phase.UnitId;
import phase.adjustments.*;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;
import phase.retreats.RetreatResult;

import java.util.*;

import static agents.generation.ActionValidationError.Code.*;

public final class AdjustmentActionTestCase implements TestCase {

    private TestEvaluation evaluation;

    @Override
    public void eval(boolean print) {
        TestEvaluation.Builder checks = new TestEvaluation.Builder();
        checkBuildSites(checks);
        checkQuotas(checks);
        checkDisbands(checks);
        checkGeneration(checks);
        checkLargeDisbandSpace(checks);
        evaluation = checks.build();
        if (print)
            printEval();
    }

    private static void checkBuildSites(TestEvaluation.Builder checks) {
        GameState_Adjustments state = winter(Map.of(), Map.of(
                Province.Par, Nation.FRANCE, Province.Bre, Nation.ENGLAND,
                Province.Bel, Nation.FRANCE, Province.Mun, Nation.FRANCE));
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        checks.expect("Only an owned, available home center is offered",
                List.of(build(Nation.FRANCE, UnitType.ARMY, Province.Par)), catalog.buildOptions());
        for (Province forbidden : List.of(Province.Bre, Province.Mar, Province.Bel, Province.Mun))
            expectCodes(checks, "Unavailable build site: " + forbidden, state,
                    candidate(Nation.FRANCE, build(Nation.FRANCE, UnitType.ARMY, forbidden)),
                    ORDER_NOT_IN_CATALOG);
        expectCodes(checks, "Fleet cannot be built inland", state,
                candidate(Nation.FRANCE, build(Nation.FRANCE, UnitType.FLEET, Province.Par)),
                ORDER_NOT_IN_CATALOG);

        UnitId fleet = unit(Nation.FRANCE, UnitType.FLEET, Province.Bre);
        GameState_Adjustments occupied = winter(Map.of(fleet, Province.Bre),
                Map.of(Province.Par, Nation.FRANCE, Province.Bre, Nation.FRANCE));
        expectCodes(checks, "Occupied home center cannot receive a build", occupied,
                candidate(Nation.FRANCE, build(Nation.FRANCE, UnitType.FLEET, Province.Bre)),
                ORDER_NOT_IN_CATALOG);

        GameState_Adjustments russia = winter(Map.of(),
                Map.of(Province.Stp, Nation.RUSSIA, Province.Mos, Nation.RUSSIA));
        LegalOrderCatalog russianCatalog = new LegalOrderGenerator().generate(russia, Nation.RUSSIA);
        for (Province coast : List.of(Province.StpNC, Province.StpSC))
            checks.expect("Explicit fleet coast is offered: " + coast, true,
                    russianCatalog.contains(build(Nation.RUSSIA, UnitType.FLEET, coast)));
        checks.expect("Canonical Army build is offered", true,
                russianCatalog.contains(build(Nation.RUSSIA, UnitType.ARMY, Province.Stp)));
        expectCodes(checks, "Fleet requires an explicit St Petersburg coast", russia,
                candidate(Nation.RUSSIA, build(Nation.RUSSIA, UnitType.FLEET, Province.Stp)),
                ORDER_NOT_IN_CATALOG);
        expectCodes(checks, "Army cannot be built on a split-coast identifier", russia,
                candidate(Nation.RUSSIA, build(Nation.RUSSIA, UnitType.ARMY, Province.StpNC)),
                ORDER_NOT_IN_CATALOG);
        expectCodes(checks, "Two coasts still refer to one build site", russia,
                candidate(Nation.RUSSIA, build(Nation.RUSSIA, UnitType.FLEET, Province.StpNC),
                        build(Nation.RUSSIA, UnitType.FLEET, Province.StpSC)),
                DUPLICATE_BUILD_SITE);
        expectCodes(checks, "Army and Fleet cannot share a build site", russia,
                candidate(Nation.RUSSIA, build(Nation.RUSSIA, UnitType.ARMY, Province.Stp),
                        build(Nation.RUSSIA, UnitType.FLEET, Province.StpNC)), DUPLICATE_BUILD_SITE);
        UnitId stp = unit(Nation.RUSSIA, UnitType.FLEET, Province.StpSC);
        GameState_Adjustments occupiedCoast = winter(Map.of(stp, Province.StpSC), russia.board().owners());
        expectCodes(checks, "Occupancy on one coast prevents a build on the other", occupiedCoast,
                candidate(Nation.RUSSIA, build(Nation.RUSSIA, UnitType.FLEET, Province.StpNC)),
                ORDER_NOT_IN_CATALOG);
        expectThrows(checks, "Build options are immutable", UnsupportedOperationException.class,
                () -> russianCatalog.buildOptions().clear());
    }

    private static void checkQuotas(TestEvaluation.Builder checks) {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState_Adjustments state = winter(Map.of(army, Province.Bur),
                Map.of(Province.Par, Nation.FRANCE, Province.Bre, Nation.FRANCE));
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        checks.expect("Balance uses controlled centers and surviving units",
                1, catalog.adjustmentBalance().availableBuilds());
        expectCodes(checks, "Unused builds may be omitted", state, candidate(Nation.FRANCE));
        expectCodes(checks, "An explicit waive is accepted", state,
                candidate(Nation.FRANCE, new WaiveOrder(Nation.FRANCE)));
        checks.expect("Excess build identifies its order index without a unit",
                List.of(new ActionValidationError(TOO_MANY_ADJUSTMENTS, null, 1)),
                new CatalogActionValidator().validate(state,
                        candidate(Nation.FRANCE, build(Nation.FRANCE, UnitType.ARMY, Province.Par),
                                build(Nation.FRANCE, UnitType.FLEET, Province.Bre))));
        expectCodes(checks, "Waives also consume build capacity", state,
                candidate(Nation.FRANCE, new WaiveOrder(Nation.FRANCE),
                        build(Nation.FRANCE, UnitType.FLEET, Province.Bre)), TOO_MANY_ADJUSTMENTS);
        expectCodes(checks, "Disband is unavailable in a build year", state,
                candidate(Nation.FRANCE, new DisbandOrder(Nation.FRANCE, army)), ORDER_NOT_IN_CATALOG);
        expectCodes(checks, "Movement orders are rejected in winter", state,
                candidate(Nation.FRANCE, Order.hold(army)), WRONG_ORDER_PHASE);

        GameState_Adjustments twoBuilds = winter(Map.of(),
                Map.of(Province.Par, Nation.FRANCE, Province.Bre, Nation.FRANCE));
        ActionCandidate waives = candidate(Nation.FRANCE,
                new WaiveOrder(Nation.FRANCE), new WaiveOrder(Nation.FRANCE));
        expectCodes(checks, "Repeated waives represent distinct unused build slots", twoBuilds, waives);
        checks.expect("Processor accepts both waives", true,
                process(twoBuilds, waives).outcomes().stream().allMatch(AdjustmentResult.Outcome::accepted));
        GameState_Adjustments balanced = winter(Map.of(army, Province.Bur),
                Map.of(Province.Par, Nation.FRANCE));
        expectCodes(checks, "Balanced nation submits an empty action", balanced, candidate(Nation.FRANCE));
        expectCodes(checks, "Balanced nation cannot waive a nonexistent build", balanced,
                candidate(Nation.FRANCE, new WaiveOrder(Nation.FRANCE)), ORDER_NOT_IN_CATALOG);
        expectCodes(checks, "Balanced nation cannot build", balanced,
                candidate(Nation.FRANCE, build(Nation.FRANCE, UnitType.ARMY, Province.Par)),
                ORDER_NOT_IN_CATALOG);
    }

    private static void checkDisbands(TestEvaluation.Builder checks) {
        UnitId par = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId bre = unit(Nation.FRANCE, UnitType.FLEET, Province.Bre);
        UnitId mar = unit(Nation.FRANCE, UnitType.ARMY, Province.Mar);
        UnitId bur = unit(Nation.FRANCE, UnitType.ARMY, Province.Bur);
        GameState_Adjustments state = winter(
                Map.of(par, Province.Par, bre, Province.Bre, mar, Province.Mar, bur, Province.Bur),
                Map.of(Province.Par, Nation.FRANCE, Province.Mar, Nation.FRANCE));
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        checks.expect("Removal year offers every own unit", Set.of(par, bre, mar, bur),
                catalog.optionsByUnit().keySet());
        checks.expect("Removal year offers no builds", List.of(), catalog.buildOptions());
        expectCodes(checks, "Missing mandatory disbands are reported", state, candidate(Nation.FRANCE),
                MISSING_ADJUSTMENTS);
        expectCodes(checks, "Too few explicit disbands are rejected", state,
                candidate(Nation.FRANCE, new DisbandOrder(Nation.FRANCE, par)), MISSING_ADJUSTMENTS);
        expectCodes(checks, "Duplicate unit cannot satisfy the required count", state,
                candidate(Nation.FRANCE, new DisbandOrder(Nation.FRANCE, par),
                        new DisbandOrder(Nation.FRANCE, par)), DUPLICATE_UNIT, MISSING_ADJUSTMENTS);
        expectCodes(checks, "Too many disbands are rejected", state,
                candidate(Nation.FRANCE, new DisbandOrder(Nation.FRANCE, par),
                        new DisbandOrder(Nation.FRANCE, bre), new DisbandOrder(Nation.FRANCE, mar)),
                TOO_MANY_ADJUSTMENTS);
        UnitId absent = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        expectCodes(checks, "An absent identity cannot be disbanded", state,
                candidate(Nation.FRANCE, new DisbandOrder(Nation.FRANCE, absent),
                        new DisbandOrder(Nation.FRANCE, bre)), UNKNOWN_UNIT, MISSING_ADJUSTMENTS);
        UnitId foreign = unit(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        expectThrows(checks, "A power cannot disband another power's unit", IllegalArgumentException.class,
                () -> new DisbandOrder(Nation.FRANCE, foreign));
        checks.expect("Processor civil-disorder fallback is preserved", 2,
                process(state, candidate(Nation.FRANCE)).disbandedUnits().size());
        checkGenerated(checks, state, Nation.FRANCE, 6);

        GameState_Adjustments allRemoved = winter(state.board().locations(), Map.of());
        List<ActionCandidate> only = checkGenerated(checks, allRemoved, Nation.FRANCE, 1);
        checks.expect("All units are explicitly removed when no centers remain", 4,
                only.getFirst().orders().size());
        checks.expect("Forced removal action has probability one", 0.0, only.getFirst().logPrior());
    }

    private static void checkGeneration(TestEvaluation.Builder checks) {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Mar);
        GameState_Adjustments oneBuild = winter(Map.of(army, Province.Bur),
                Map.of(Province.Par, Nation.FRANCE, Province.Bre, Nation.FRANCE));
        List<ActionCandidate> builds = checkGenerated(checks, oneBuild, Nation.FRANCE, 4);
        checks.expect("No-build action comes first", List.of(), builds.getFirst().orders());
        checks.expect("Generated waives are represented by omission", true,
                builds.stream().flatMap(action -> action.orders().stream())
                        .noneMatch(order -> order instanceof WaiveOrder));
        GameState_Adjustments twoBuilds = winter(Map.of(), oneBuild.board().owners());
        checkGenerated(checks, twoBuilds, Nation.FRANCE, 6);
        GameState_Adjustments russia = winter(Map.of(), Map.of(
                Province.Mos, Nation.RUSSIA, Province.War, Nation.RUSSIA,
                Province.Sev, Nation.RUSSIA, Province.Stp, Nation.RUSSIA));
        checkGenerated(checks, russia, Nation.RUSSIA, 48);
        checkGenerated(checks, winter(Map.of(), Map.of()), Nation.FRANCE, 1);
        GameState_Adjustments noHomeSite = winter(Map.of(), Map.of(Province.Bel, Nation.FRANCE));
        checks.expect("Capacity without a home site produces only omission",
                List.of(), checkGenerated(checks, noHomeSite, Nation.FRANCE, 1).getFirst().orders());
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(oneBuild, Nation.FRANCE);
        UniformActionGenerator generator = new UniformActionGenerator();
        List<ActionCandidate> limited = generator.generate(
                new ActionGenerationRequest(oneBuild, Nation.FRANCE, 2, 9L), catalog);
        checks.expect("Winter candidate limit is respected", 2, limited.size());
        checks.expect("Limited output retains full-space prior",
                builds.getFirst().logPrior(), limited.getFirst().logPrior());
        GameState_Adjustments same = new GameState_Adjustments(
                oneBuild.moment(), new BoardState(oneBuild.board().locations(), oneBuild.board().owners()),
                oneBuild.fallRetreatResult());
        checks.expect("Equivalent snapshot gives repeatable winter candidates", limited,
                generator.generate(new ActionGenerationRequest(same, Nation.FRANCE, 2, 9L), catalog));
        expectThrows(checks, "Changed ownership invalidates the winter catalog", IllegalArgumentException.class,
                () -> catalog.requireMatches(new GameState_Adjustments(oneBuild.moment(),
                        new BoardState(oneBuild.board().locations(), Map.of(Province.Par, Nation.FRANCE)),
                        oneBuild.fallRetreatResult()), Nation.FRANCE));
        expectThrows(checks, "Returned winter candidates are immutable", UnsupportedOperationException.class,
                () -> limited.clear());
    }

    private static void checkLargeDisbandSpace(TestEvaluation.Builder checks) {
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        Map<Province, Nation> owners = new LinkedHashMap<>();
        for (Province province : Province.values()) {
            if (Province.canonical(province) != province)
                continue;
            UnitType type = OrderForms.isValidUnitLocation(UnitType.ARMY, province)
                    ? UnitType.ARMY : UnitType.FLEET;
            if (locations.size() < 70 && OrderForms.isValidUnitLocation(type, province))
                locations.put(unit(Nation.FRANCE, type, province), province);
            if (province.supplyCenter)
                owners.put(province, Nation.FRANCE);
        }
        GameState_Adjustments state = winter(locations, owners);
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        List<ActionCandidate> actions = new UniformActionGenerator().generate(
                new ActionGenerationRequest(state, Nation.FRANCE, 16, 9L), catalog);
        checks.expect("Large disband space is sampled without enumeration", 16, actions.size());
        checks.expect("Every sampled action explicitly removes thirty-six units", true,
                actions.stream().allMatch(action -> action.orders().size() == 36));
        checks.expect("Subset prior handles combination counts larger than Long.MAX_VALUE", true,
                Math.abs(actions.getFirst().logPrior() + StrictMath.log(1.0906999232175554e20)) < 1.0e-12);
        for (ActionCandidate action : actions)
            checkAction(checks, state, catalog, action);
        Map<UnitId, Province> reversed = new LinkedHashMap<>();
        for (UnitId unit : new ArrayList<>(locations.keySet()).reversed())
            reversed.put(unit, locations.get(unit));
        GameState_Adjustments reordered = winter(reversed, owners);
        checks.expect("Disband sampling ignores board insertion order", true,
                actions.equals(new UniformActionGenerator().generate(
                        new ActionGenerationRequest(reordered, Nation.FRANCE, 16, 9L),
                        new LegalOrderGenerator().generate(reordered, Nation.FRANCE))));
    }

    private static List<ActionCandidate> checkGenerated(
            TestEvaluation.Builder checks, GameState_Adjustments state, Nation power, int expected
    ) {
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, power);
        List<ActionCandidate> actions = new UniformActionGenerator().generate(
                new ActionGenerationRequest(state, power, 100, 9L), catalog);
        checks.expect("Complete winter action space: " + expected, expected, actions.size());
        Set<List<OrderForm>> unique = new HashSet<>();
        for (ActionCandidate action : actions) {
            checks.expect("Winter candidate is unique", true, unique.add(action.orders()));
            checkAction(checks, state, catalog, action);
        }
        checks.expect("Full winter action space has probability one", true,
                Math.abs(actions.stream().mapToDouble(action -> StrictMath.exp(action.logPrior())).sum()
                        - 1.0) < 1.0e-12);
        return actions;
    }

    private static void checkAction(TestEvaluation.Builder checks, GameState_Adjustments state,
                                    LegalOrderCatalog catalog, ActionCandidate action) {
        checks.expect("Winter candidate validates", List.of(),
                new CatalogActionValidator().validate(state, action, catalog));
        AdjustmentResult result = process(state, action);
        checks.expect("Processor accepts every generated order", true,
                result.outcomes().stream().allMatch(AdjustmentResult.Outcome::accepted));
        Set<UnitId> removals = new HashSet<>();
        Set<BuildOrder> builds = new HashSet<>();
        for (OrderForm order : action.orders()) {
            if (order instanceof DisbandOrder disband)
                removals.add(disband.unit());
            if (order instanceof BuildOrder build)
                builds.add(build);
        }
        checks.expect("Explicit disband count matches the quota",
                catalog.adjustmentBalance().requiredDisbands(), removals.size());
        checks.expect("Generated action requires no automatic disbands", removals, result.disbandedUnits());
        Set<BuildOrder> actualBuilds = new HashSet<>();
        for (UnitId unit : result.builtUnits())
            actualBuilds.add(new BuildOrder(unit.owner(), unit.unitType(), result.finalLocations().get(unit)));
        checks.expect("Processor creates the requested unit types and coasts", builds, actualBuilds);
    }

    private static AdjustmentResult process(GameState_Adjustments state, ActionCandidate candidate) {
        List<AdjustmentOrder> orders = new ArrayList<>();
        for (OrderForm form : candidate.orders()) {
            if (!(form instanceof AdjustmentOrder order))
                throw new AssertionError("Generated a non-adjustment order");
            orders.add(order);
        }
        return new AdjustmentProcessor().process(new AdjustmentInput(
                state.fallRetreatResult(), state.board().owners(), orders));
    }

    private static GameState_Adjustments winter(Map<UnitId, Province> locations, Map<Province, Nation> owners) {
        RetreatResult result = new RetreatProcessor().process(new RetreatInput(
                new MovementProcessor().process(new MovementInput(locations, List.of())), List.of()));
        return new GameState_Adjustments(new GameMoment(1901, GamePhase.WINTER_ADJUSTMENT),
                new BoardState(result.finalLocations(), owners), result);
    }

    private static UnitId unit(Nation owner, UnitType type, Province origin) {
        return new UnitId(UUID.randomUUID(), owner, type, origin);
    }

    private static BuildOrder build(Nation power, UnitType type, Province location) {
        return new BuildOrder(power, type, location);
    }

    private static ActionCandidate candidate(Nation power, OrderForm... orders) {
        return new ActionCandidate(power, List.of(orders), -1.0, ActionCandidate.Source.POLICY);
    }

    private static void expectCodes(TestEvaluation.Builder checks, String label, GameState state,
                                    ActionCandidate candidate, ActionValidationError.Code... codes) {
        checks.expect(label, List.of(codes), new CatalogActionValidator().validate(state, candidate)
                .stream().map(ActionValidationError::code).toList());
    }

    private static void expectThrows(TestEvaluation.Builder checks, String label,
                                     Class<? extends RuntimeException> expected, Runnable action) {
        try {
            action.run();
            checks.expect(label, expected, null);
        } catch (RuntimeException actual) {
            checks.expect(label, expected, actual.getClass());
        }
    }

    @Override
    public String getName() {
        return "Adjustment action generation and validation";
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
        AdjustmentActionTestCase test = new AdjustmentActionTestCase();
        test.eval(true);
        if (!test.passed())
            throw new AssertionError("Adjustment action tests failed");
    }

}