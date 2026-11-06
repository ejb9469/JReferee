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
import phase.adjustments.BuildOrder;
import phase.adjustments.DisbandOrder;
import phase.adjustments.WaiveOrder;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;
import phase.retreats.RetreatResult;
import testing.TestCase;
import testing.TestEvaluation;

import java.util.*;

import static agents.generation.ActionValidationError.Code.*;

public final class MovementActionValidatorTestCase implements TestCase {

    private TestEvaluation evaluation;

    @Override
    public void eval(boolean print) {
        TestEvaluation.Builder checks = new TestEvaluation.Builder();
        checkCompleteness(checks);
        checkMembership(checks);
        checkPhases(checks);
        checkCatalogMatching(checks);
        checkCoordination(checks);
        checkResults(checks);
        evaluation = checks.build();

        if (print)
            printEval();
    }

    private static void checkCompleteness(TestEvaluation.Builder checks) {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId fleet = unit(Nation.FRANCE, UnitType.FLEET, Province.Bre);
        UnitId foreign = unit(Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        GameState_Movement state = state(Map.of(
                army, Province.Par, fleet, Province.Bre, foreign, Province.Lon));
        CatalogActionValidator validator = new CatalogActionValidator();
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        ActionCandidate holds = candidate(Order.hold(army), Order.hold(fleet));
        List<ActionValidationError> valid = validator.validate(state, holds, catalog);
        checks.expect("One order per own unit is valid", true, valid.isEmpty());
        checks.expect("Valid result has no errors", List.of(), valid);
        ActionValidator shared = validator;
        checks.expect("Sealed interface generates an equivalent catalog",
                valid, shared.validate(state, holds));
        checks.expect("Submission order does not affect validity",
                true, validator.validate(state,
                        candidate(Order.hold(fleet), Order.hold(army)), catalog).isEmpty());
        checks.expect("Missing unit is identified without an order index",
                List.of(error(MISSING_ORDER, fleet, -1)),
                validator.validate(state, candidate(Order.hold(army)), catalog));
        checks.expect("Duplicate hold identifies its second occurrence",
                List.of(error(DUPLICATE_UNIT, army, 1)),
                validator.validate(state, candidate(
                        Order.hold(army), Order.hold(army), Order.hold(fleet)), catalog));
        checks.expect("Conflicting orders for the same unit are also duplicates",
                List.of(error(DUPLICATE_UNIT, army, 1)),
                validator.validate(state, candidate(
                        Order.hold(army), Order.move(army, Province.Bur),
                        Order.hold(fleet)), catalog));
        checks.expect("Duplicate count cannot hide a missing unit",
                List.of(error(DUPLICATE_UNIT, army, 1), error(MISSING_ORDER, fleet, -1)),
                validator.validate(state,
                        candidate(Order.hold(army), Order.hold(army)), catalog));

        UnitId replacement = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        checks.expect("A new identity at the same origin cannot replace an existing unit",
                List.of(error(UNKNOWN_UNIT, replacement, 0), error(MISSING_ORDER, army, -1)),
                validator.validate(state,
                        candidate(Order.hold(replacement), Order.hold(fleet)), catalog));
        UnitId changedOrigin = new UnitId(
                army.value(), army.owner(), army.unitType(), Province.Mar);
        checks.expect("Identity matching uses the entire UnitId",
                List.of(error(UNKNOWN_UNIT, changedOrigin, 0), error(MISSING_ORDER, army, -1)),
                validator.validate(state,
                        candidate(Order.hold(changedOrigin), Order.hold(fleet)), catalog));
        UnitId equalId = new UnitId(
                army.value(), army.owner(), army.unitType(), army.origin());
        checks.expect("Equal UnitIds need not be the same object",
                true, validator.validate(state,
                        candidate(Order.hold(equalId), Order.hold(fleet)), catalog).isEmpty());
        expectThrows(checks, "Candidate boundary rejects foreign orders",
                IllegalArgumentException.class, () -> candidate(Order.hold(foreign)));
        checks.expect("Power without units has one empty action",
                true, validator.validate(state(Map.of(foreign, Province.Lon)), candidate()).isEmpty());
        checks.expect("Empty action is incomplete for a power with units",
                false, validator.validate(state, candidate(), catalog).isEmpty());
    }

    private static void checkMembership(TestEvaluation.Builder checks) {
        UnitId fleet = unit(Nation.FRANCE, UnitType.FLEET, Province.Smy);
        GameState_Movement state = state(Map.of(fleet, Province.LYO));
        CatalogActionValidator validator = new CatalogActionValidator();
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        checks.expect("Movement uses the current location rather than creation origin",
                true, validator.validate(state,
                        candidate(Order.move(fleet, Province.SpaSC)), catalog).isEmpty());
        checks.expect("Unreachable split coast is rejected",
                List.of(error(ORDER_NOT_IN_CATALOG, fleet, 0)),
                validator.validate(state,
                        candidate(Order.move(fleet, Province.SpaNC)), catalog));
        checks.expect("A move legal only at the creation origin is rejected",
                List.of(error(ORDER_NOT_IN_CATALOG, fleet, 0)),
                validator.validate(state,
                        candidate(Order.move(fleet, Province.AEG)), catalog));

        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState_Movement inland = state(Map.of(army, Province.Par));
        List<Order> invalidOrders = List.of(
                new Order(army, OrderType.HOLD, Province.Bur, null),
                new Order(army, OrderType.MOVE, null, null),
                Order.move(army, Province.NTH),
                Order.move(army, Province.Mos),
                Order.supportHold(army, Province.Bur));
        for (Order order : invalidOrders)
            checks.expect("Order outside the catalog is rejected: " + order,
                    List.of(error(ORDER_NOT_IN_CATALOG, army, 0)),
                    validator.validate(inland, candidate(order)));
        checks.expect("Duplicate and membership errors are both reported",
                List.of(error(DUPLICATE_UNIT, army, 1),
                        error(ORDER_NOT_IN_CATALOG, army, 1)),
                validator.validate(inland,
                        candidate(Order.hold(army), Order.move(army, Province.NTH))));
    }

    private static void checkPhases(TestEvaluation.Builder checks) {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState_Movement state = state(Map.of(army, Province.Par));
        CatalogActionValidator validator = new CatalogActionValidator();
        List<OrderForm> otherPhases = List.of(
                new RetreatOrder(army, Province.Bur),
                new BuildOrder(Nation.FRANCE, UnitType.FLEET, Province.Bre),
                new DisbandOrder(Nation.FRANCE, army),
                new WaiveOrder(Nation.FRANCE));
        for (OrderForm order : otherPhases)
            checks.expect("Wrong submitted representation: " + order.getClass().getSimpleName(),
                    List.of(error(WRONG_ORDER_PHASE,
                                    order instanceof RetreatOrder retreat ? retreat.unit()
                                            : order instanceof DisbandOrder disband ? disband.unit() : null, 0),
                            error(MISSING_ORDER, army, -1)),
                    validator.validate(state, candidate(order)));
        for (OrderType type : List.of(
                OrderType.RETREAT, OrderType.BUILD, OrderType.DESTROY, OrderType.WAIVE))
            checks.expect("Movement DTO cannot disguise another phase: " + type,
                    List.of(error(WRONG_ORDER_PHASE, army, 0), error(MISSING_ORDER, army, -1)),
                    validator.validate(state,
                            candidate(new Order(army, type, null, null))));
        checks.expect("A wrong-phase extra order is rejected even when all units are covered",
                List.of(error(WRONG_ORDER_PHASE, null, 1)),
                validator.validate(state,
                        candidate(Order.hold(army), new WaiveOrder(Nation.FRANCE))));

        UnitId attacker = unit(Nation.GERMANY, UnitType.ARMY, Province.Bur);
        UnitId supporter = unit(Nation.GERMANY, UnitType.ARMY, Province.Pic);
        MovementResult movement = new MovementProcessor().process(new MovementInput(
                Map.of(army, Province.Par, attacker, Province.Bur, supporter, Province.Pic),
                List.of(Order.hold(army), Order.move(attacker, Province.Par),
                        Order.supportMove(supporter, Province.Bur, Province.Par))));
        checks.expect("Fixture dislodges the French Army",
                true, movement.dislodgements().containsKey(army));
        GameState_Retreats retreats = new GameState_Retreats(
                new GameMoment(1901, GamePhase.FALL_RETREAT),
                new BoardState(movement.finalLocations(), Map.of()), movement);
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        checks.expect("Shared validator accepts retreat omissions",
                List.of(), validator.validate(retreats, candidate()));
        expectThrows(checks, "Catalog overload also rejects retreat state",
                IllegalArgumentException.class,
                () -> validator.validate(retreats, candidate(), catalog));
        RetreatResult retreatResult = new RetreatProcessor().process(
                new RetreatInput(movement, List.of()));
        GameState_Adjustments adjustments = new GameState_Adjustments(
                new GameMoment(1901, GamePhase.WINTER_ADJUSTMENT),
                new BoardState(retreatResult.finalLocations(), Map.of()), retreatResult);        checks.expect("Shared validator accepts balanced winter omissions",
                List.of(), validator.validate(adjustments, candidate()));
        checks.expect("Removed unit cannot receive a later movement order",
                List.of(error(UNKNOWN_UNIT, army, 0)),
                validator.validate(state(retreatResult.finalLocations()),
                        candidate(Order.hold(army))));
    }

    private static void checkCatalogMatching(TestEvaluation.Builder checks) {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId foreign = unit(Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        GameState_Movement state = state(Map.of(army, Province.Par, foreign, Province.Lon));
        LegalOrderGenerator generator = new LegalOrderGenerator();
        LegalOrderCatalog catalog = generator.generate(state, Nation.FRANCE);
        CatalogActionValidator validator = new CatalogActionValidator();
        ActionCandidate action = candidate(Order.hold(army));
        checks.expect("Value-equivalent state matches the catalog",
                true, validator.validate(
                        state(new LinkedHashMap<>(state.board().locations())), action, catalog).isEmpty());
        List<GameState_Movement> mismatches = List.of(
                new GameState_Movement(
                        new GameMoment(1902, GamePhase.SPRING_MOVEMENT), state.board()),
                new GameState_Movement(
                        new GameMoment(1901, GamePhase.FALL_MOVEMENT), state.board()),
                state(Map.of(army, Province.Bur, foreign, Province.Lon)),
                state(Map.of(army, Province.Par, foreign, Province.ENG)),
                new GameState_Movement(state.moment(),
                        new BoardState(state.board().locations(), Map.of(Province.Par, Nation.FRANCE))));
        for (int index = 0; index < mismatches.size(); index++) {
            GameState_Movement mismatch = mismatches.get(index);
            expectThrows(checks, "Stale catalog is rejected: " + index,
                    IllegalArgumentException.class,
                    () -> validator.validate(mismatch, action, catalog));
        }
        expectThrows(checks, "Catalog for another nation is rejected",
                IllegalArgumentException.class,
                () -> validator.validate(state, action, generator.generate(state, Nation.ENGLAND)));
        expectThrows(checks, "Null state is rejected", NullPointerException.class,
                () -> validator.validate(null, action));
        expectThrows(checks, "Null state with catalog is rejected", NullPointerException.class,
                () -> validator.validate(null, action, catalog));
        expectThrows(checks, "Null candidate is rejected", NullPointerException.class,
                () -> validator.validate(state, null));
        expectThrows(checks, "Null candidate with catalog is rejected", NullPointerException.class,
                () -> validator.validate(state, null, catalog));
        expectThrows(checks, "Null catalog is rejected", NullPointerException.class,
                () -> validator.validate(state, action, null));
        checks.expect("Candidate orders remain unchanged",
                List.of(Order.hold(army)), action.orders());
    }

    private static void checkCoordination(TestEvaluation.Builder checks) {
        CatalogActionValidator validator = new CatalogActionValidator();
        UnitId gas = unit(Nation.FRANCE, UnitType.ARMY, Province.Gas);
        UnitId foreignFleet = unit(Nation.ENGLAND, UnitType.FLEET, Province.MAO);
        checks.expect("Army support for a foreign Fleet's split-coast move is valid",
                true, validator.validate(state(Map.of(gas, Province.Gas, foreignFleet, Province.MAO)),
                        candidate(Order.supportMove(gas, Province.MAO, Province.SpaNC))).isEmpty());
        UnitId par = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId bur = unit(Nation.FRANCE, UnitType.ARMY, Province.Bur);
        GameState_Movement land = state(Map.of(gas, Province.Gas, par, Province.Par, bur, Province.Bur));
        checks.expect("Hold support can be given to a supporting unit",
                true, validator.validate(land, candidate(Order.supportHold(gas, Province.Par),
                        Order.supportHold(par, Province.Bur), Order.hold(bur))).isEmpty());
        checks.expect("Unmatched own support is a coordination problem",
                true, validator.validate(land, candidate(Order.supportMove(gas, Province.Par, Province.Bur),
                        Order.hold(par), Order.hold(bur))).isEmpty());
        checks.expect("Competing own moves are not submission errors",
                true, validator.validate(land, candidate(Order.move(gas, Province.Bur),
                        Order.move(par, Province.Bur), Order.hold(bur))).isEmpty());

        UnitId bre = unit(Nation.FRANCE, UnitType.ARMY, Province.Bre);
        UnitId eng = unit(Nation.ENGLAND, UnitType.FLEET, Province.ENG);
        UnitId nth = unit(Nation.ENGLAND, UnitType.FLEET, Province.NTH);
        checks.expect("Army move may depend entirely on foreign convoy orders",
                true, validator.validate(state(Map.of(bre, Province.Bre, eng, Province.ENG, nth, Province.NTH)),
                        candidate(Order.move(bre, Province.Nwy))).isEmpty());
        checks.expect("Catalog membership does not promise an available convoy route",
                true, validator.validate(state(Map.of(bre, Province.Bre)),
                        candidate(Order.move(bre, Province.Nwy))).isEmpty());
        UnitId ownEng = unit(Nation.FRANCE, UnitType.FLEET, Province.ENG);
        UnitId ownNth = unit(Nation.FRANCE, UnitType.FLEET, Province.NTH);
        UnitId foreignArmy = unit(Nation.ENGLAND, UnitType.ARMY, Province.Bre);
        checks.expect("Hold support can be given to a fleet convoying a foreign Army",
                true, validator.validate(state(Map.of(
                                ownEng, Province.ENG, ownNth, Province.NTH, foreignArmy, Province.Bre)),
                        candidate(Order.convoy(ownEng, Province.Bre, Province.Nwy),
                                Order.supportHold(ownNth, Province.ENG))).isEmpty());
    }

    private static void checkResults(TestEvaluation.Builder checks) {
        UnitId unit = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState_Movement state = state(Map.of(unit, Province.Par));
        CatalogActionValidator validator = new CatalogActionValidator();
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        List<ActionValidationError> errors = validator.validate(state, candidate(), catalog);
        List<ActionValidationError> valid = validator.validate(
                state, candidate(Order.hold(unit)), catalog);
        checks.expect("An error makes the action invalid", false, errors.isEmpty());
        checks.expect("A later valid action has no leftover errors", true, valid.isEmpty());
        checks.expect("Later validation does not change an earlier error list",
                List.of(error(MISSING_ORDER, unit, -1)), errors);
        checks.expect("Repeated validation produces the same errors",
                errors, validator.validate(state, candidate(), catalog));
        expectThrows(checks, "Returned errors are immutable", UnsupportedOperationException.class,
                () -> errors.clear());
        expectThrows(checks, "Empty returned list is also immutable", UnsupportedOperationException.class,
                () -> valid.add(error(MISSING_ORDER, unit, -1)));
        expectThrows(checks, "Null error code is rejected", NullPointerException.class,
                () -> error(null, unit, 0));
        expectThrows(checks, "Missing order requires a unit", NullPointerException.class,
                () -> error(MISSING_ORDER, null, -1));
        expectThrows(checks, "Missing order requires the missing index", IllegalArgumentException.class,
                () -> error(MISSING_ORDER, unit, 0));
        expectThrows(checks, "Submitted error requires an order index", IllegalArgumentException.class,
                () -> error(ORDER_NOT_IN_CATALOG, unit, -1));
    }

    private static UnitId unit(Nation owner, UnitType type, Province origin) {
        return new UnitId(UUID.randomUUID(), owner, type, origin);
    }

    private static GameState_Movement state(Map<UnitId, Province> locations) {
        return new GameState_Movement(
                new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                new BoardState(locations, Map.of()));
    }

    private static ActionCandidate candidate(OrderForm... orders) {
        return new ActionCandidate(Nation.FRANCE, List.of(orders),
                -1.0, ActionCandidate.Source.POLICY);
    }

    private static ActionValidationError error(
            ActionValidationError.Code code, UnitId unit, int index
    ) {
        return new ActionValidationError(code, unit, index);
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
        return "Movement action validation";
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
        MovementActionValidatorTestCase test = new MovementActionValidatorTestCase();
        test.eval(true);

        if (!test.passed())
            throw new AssertionError("Movement action validation tests failed");
    }

}