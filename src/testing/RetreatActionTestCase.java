package testing;

import agents.generation.*;
import contracts.OrderForm;
import domain.*;
import game.*;
import phase.MovementInput;
import phase.Order;
import phase.RetreatInput;
import phase.UnitId;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;
import phase.retreats.RetreatResult;

import java.util.*;

import static agents.generation.ActionValidationError.Code.*;

public final class RetreatActionTestCase implements TestCase {

    private TestEvaluation evaluation;

    @Override
    public void eval(boolean print) {
        TestEvaluation.Builder checks = new TestEvaluation.Builder();
        checkCatalogAndValidation(checks);
        checkCoasts(checks);
        checkBlockedRetreats(checks);
        checkConvoyArrival(checks);
        checkCollisions(checks);
        evaluation = checks.build();
        if (print)
            printEval();
    }

    private static void checkCatalogAndValidation(TestEvaluation.Builder checks) {
        UnitId defender = unit(Nation.FRANCE, UnitType.ARMY, Province.Bre);
        UnitId attacker = unit(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        UnitId supporter = unit(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        UnitId standby = unit(Nation.FRANCE, UnitType.ARMY, Province.Mar);
        GameState_Retreats state = landRetreat(defender, attacker, supporter, standby, Province.Bur);
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        RetreatOrder toPic = new RetreatOrder(defender, Province.Pic);
        checks.expect("Only dislodged units receive retreat options",
                Set.of(defender), catalog.optionsByUnit().keySet());
        checks.expect("Catalog uses displaced location rather than creation origin",
                Map.of(defender, Province.Par), catalog.unitLocations());
        checks.expect("Catalog lists legal destinations",
                Set.of(toPic, new RetreatOrder(defender, Province.Bre)),
                new HashSet<>(catalog.optionsFor(defender)));
        checks.expect("Omission is available in retreats", true, catalog.allowsOmission());
        CatalogActionValidator validator = new CatalogActionValidator();
        checks.expect("Omission is valid", List.of(), validator.validate(state, candidate(), catalog));
        checks.expect("Legal retreat is valid", List.of(),
                validator.validate(state, candidate(toPic), catalog));
        checks.expect("Duplicate issuer is rejected",
                List.of(new ActionValidationError(DUPLICATE_UNIT, defender, 1)),
                validator.validate(state, candidate(toPic, toPic), catalog));
        checks.expect("Nondislodged own unit is rejected",
                List.of(new ActionValidationError(UNKNOWN_UNIT, standby, 0)),
                validator.validate(state, candidate(new RetreatOrder(standby, Province.Pie)), catalog));
        checks.expect("Movement order is rejected in retreats",
                List.of(new ActionValidationError(WRONG_ORDER_PHASE, defender, 0)),
                validator.validate(state, candidate(Order.hold(defender)), catalog));
        checks.expect("Attacker's land origin is rejected",
                List.of(new ActionValidationError(ORDER_NOT_IN_CATALOG, defender, 0)),
                validator.validate(state, candidate(new RetreatOrder(defender, Province.Bur)), catalog));

        GameState_Retreats differentAttack =
                landRetreat(defender, attacker, supporter, standby, Province.Pic);
        checks.expect("Different attacks can leave the same board",
                state.board(), differentAttack.board());
        checks.expect("Different attacks can have the same moment",
                state.moment(), differentAttack.moment());
        LegalOrderCatalog otherCatalog =
                new LegalOrderGenerator().generate(differentAttack, Nation.FRANCE);
        checks.expect("Retreat legality differs despite equal board and moment", false,
                otherCatalog.contains(toPic));
        expectThrows(checks, "Catalog rejects a different movement result",
                IllegalArgumentException.class,
                () -> catalog.requireMatches(differentAttack, Nation.FRANCE));
        GameState_Retreats equivalent = new GameState_Retreats(
                state.moment(), new BoardState(state.board().locations(), state.board().owners()),
                state.movementResult());
        checks.expect("Another snapshot of the same retreat phase matches", List.of(),
                validator.validate(equivalent, candidate(toPic), catalog));

        List<ActionCandidate> actions = new UniformActionGenerator().generate(
                new ActionGenerationRequest(state, Nation.FRANCE, 100, 3L), catalog);
        checks.expect("Both retreats and omission are generated", 3, actions.size());
        checks.expect("All-omitted action comes first", List.of(), actions.getFirst().orders());
        for (ActionCandidate action : actions) {
            checks.expect("Generated retreat action validates", List.of(),
                    validator.validate(state, action, catalog));
            checks.expect("Prior includes the omission choice", true,
                    Math.abs(action.logPrior() + StrictMath.log(3)) < 1.0e-12);
        }
        checks.expect("Retreat generation is repeatable", actions,
                new UniformActionGenerator().generate(
                        new ActionGenerationRequest(equivalent, Nation.FRANCE, 100, 3L), catalog));
        checks.expect("Omitted unit is destroyed by the processor",
                RetreatResult.Outcome.Status.DESTROYED_NO_ORDER,
                new RetreatProcessor().process(new RetreatInput(state.movementResult(), List.of()))
                        .outcomeOf(defender).status());
        LegalOrderCatalog noUnits = new LegalOrderGenerator().generate(state, Nation.ITALY);
        checks.expect("Power with no dislodged units has one empty action", 1,
                new UniformActionGenerator().generate(
                        new ActionGenerationRequest(state, Nation.ITALY, 100, 3L), noUnits).size());
        expectThrows(checks, "Retreat option lists are immutable", UnsupportedOperationException.class,
                () -> catalog.optionsFor(defender).clear());
    }

    private static GameState_Retreats landRetreat(
            UnitId defender, UnitId attacker, UnitId supporter, UnitId standby,
            Province attackOrigin
    ) {
        return state(new MovementProcessor().process(new MovementInput(
                Map.of(defender, Province.Par, attacker, attackOrigin,
                        supporter, Province.Gas, standby, Province.Mar),
                List.of(Order.hold(defender), Order.move(attacker, Province.Par),
                        Order.supportMove(supporter, attackOrigin, Province.Par),
                        Order.hold(standby)))));
    }

    private static void checkCoasts(TestEvaluation.Builder checks) {
        UnitId fleet = unit(Nation.FRANCE, UnitType.FLEET, Province.Smy);
        UnitId attacker = unit(Nation.ITALY, UnitType.FLEET, Province.WES);
        UnitId supporter = unit(Nation.ITALY, UnitType.FLEET, Province.TYS);
        GameState_Retreats state = state(new MovementProcessor().process(new MovementInput(
                Map.of(fleet, Province.LYO, attacker, Province.WES, supporter, Province.TYS),
                List.of(Order.hold(fleet), Order.move(attacker, Province.LYO),
                        Order.supportMove(supporter, Province.WES, Province.LYO)))));
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        checks.expect("Reachable Spain coast is offered",
                true, catalog.contains(new RetreatOrder(fleet, Province.SpaSC)));
        checks.expect("Unreachable Spain coast is excluded",
                false, catalog.contains(new RetreatOrder(fleet, Province.SpaNC)));
        checks.expect("Occupied destination is excluded",
                false, catalog.contains(new RetreatOrder(fleet, Province.TYS)));
        checks.expect("Fleet attacker origin is excluded",
                false, catalog.contains(new RetreatOrder(fleet, Province.WES)));
    }

    private static void checkBlockedRetreats(TestEvaluation.Builder checks) {
        UnitId defender = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId attacker = unit(Nation.GERMANY, UnitType.ARMY, Province.Bur);
        UnitId supporter = unit(Nation.GERMANY, UnitType.ARMY, Province.Gas);
        UnitId bre = unit(Nation.GERMANY, UnitType.ARMY, Province.Bre);
        UnitId bel = unit(Nation.ENGLAND, UnitType.ARMY, Province.Bel);
        GameState_Retreats state = state(new MovementProcessor().process(new MovementInput(
                Map.of(defender, Province.Par, attacker, Province.Bur, supporter, Province.Gas,
                        bre, Province.Bre, bel, Province.Bel),
                List.of(Order.hold(defender), Order.move(attacker, Province.Par),
                        Order.supportMove(supporter, Province.Bur, Province.Par),
                        Order.move(bre, Province.Pic), Order.move(bel, Province.Pic)))));
        checks.expect("Fixture creates a standoff", true, state.movementResult().wasStandoff(Province.Pic));
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        checks.expect("Blocked unit has no legal destinations", List.of(), catalog.optionsFor(defender));
        List<ActionCandidate> actions = new UniformActionGenerator().generate(
                new ActionGenerationRequest(state, Nation.FRANCE, Integer.MAX_VALUE, 1L), catalog);
        checks.expect("Blocked unit has only the omission action", 1, actions.size());
        checks.expect("Forced omission has probability one", 0.0, actions.getFirst().logPrior());
        checks.expect("Forced omission contains no order", List.of(), actions.getFirst().orders());
    }

    private static void checkConvoyArrival(TestEvaluation.Builder checks) {
        UnitId defender = unit(Nation.FRANCE, UnitType.ARMY, Province.Hol);
        UnitId attacker = unit(Nation.ENGLAND, UnitType.ARMY, Province.Bel);
        UnitId fleet = unit(Nation.ENGLAND, UnitType.FLEET, Province.NTH);
        UnitId supporter = unit(Nation.ENGLAND, UnitType.ARMY, Province.Ruh);
        for (boolean convoy : List.of(false, true)) {
            GameState_Retreats state = state(new MovementProcessor().process(new MovementInput(
                    Map.of(defender, Province.Hol, attacker, Province.Bel,
                            fleet, Province.NTH, supporter, Province.Ruh),
                    List.of(Order.hold(defender), Order.move(attacker, Province.Hol),
                            Order.supportMove(supporter, Province.Bel, Province.Hol),
                            convoy ? Order.convoy(fleet, Province.Bel, Province.Hol) : Order.hold(fleet)))));
            checks.expect("Convoy arrival fact: " + convoy, convoy,
                    state.movementResult().dislodgementOf(defender).attackerArrivedByConvoy());
            checks.expect("Retreat to attacker origin follows convoy exception: " + convoy,
                    convoy, new LegalOrderGenerator().generate(state, Nation.FRANCE)
                            .contains(new RetreatOrder(defender, Province.Bel)));
        }
    }

    private static void checkCollisions(TestEvaluation.Builder checks) {
        UnitId par = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId bel = unit(Nation.FRANCE, UnitType.ARMY, Province.Bel);
        UnitId bur = unit(Nation.GERMANY, UnitType.ARMY, Province.Bur);
        UnitId gas = unit(Nation.GERMANY, UnitType.ARMY, Province.Gas);
        UnitId hol = unit(Nation.ENGLAND, UnitType.ARMY, Province.Hol);
        UnitId ruh = unit(Nation.ENGLAND, UnitType.ARMY, Province.Ruh);
        GameState_Retreats state = state(new MovementProcessor().process(new MovementInput(
                Map.of(par, Province.Par, bel, Province.Bel, bur, Province.Bur,
                        gas, Province.Gas, hol, Province.Hol, ruh, Province.Ruh),
                List.of(Order.hold(par), Order.hold(bel), Order.move(bur, Province.Par),
                        Order.supportMove(gas, Province.Bur, Province.Par),
                        Order.move(hol, Province.Bel), Order.supportMove(ruh, Province.Hol, Province.Bel)))));
        List<RetreatOrder> orders = List.of(
                new RetreatOrder(par, Province.Pic), new RetreatOrder(bel, Province.Pic));
        ActionCandidate action = new ActionCandidate(
                Nation.FRANCE, orders, -1.0, ActionCandidate.Source.POLICY);
        checks.expect("Individually legal colliding retreats pass validation",
                List.of(), new CatalogActionValidator().validate(state, action));
        RetreatResult result = new RetreatProcessor().process(
                new RetreatInput(state.movementResult(), orders));
        for (UnitId unit : List.of(par, bel))
            checks.expect("Processor resolves the collision for " + unit,
                    RetreatResult.Outcome.Status.DESTROYED_RETREAT_COLLISION,
                    result.outcomeOf(unit).status());

        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, Nation.FRANCE);
        List<ActionCandidate> actions = new UniformActionGenerator().generate(
                new ActionGenerationRequest(state, Nation.FRANCE, 100, 3L), catalog);
        checks.expect("Two units each have two retreats plus omission", 9, actions.size());
        Set<List<OrderForm>> unique = new HashSet<>();
        for (ActionCandidate generated : actions) {
            checks.expect("Joint retreat action is unique", true, unique.add(generated.orders()));
            checks.expect("Joint retreat action is valid", List.of(),
                    new CatalogActionValidator().validate(state, generated, catalog));
        }
        checks.expect("Joint probabilities include both omission choices", true,
                Math.abs(actions.stream().mapToDouble(
                        generated -> StrictMath.exp(generated.logPrior())).sum() - 1.0) < 1.0e-12);
    }

    private static UnitId unit(Nation owner, UnitType type, Province origin) {
        return new UnitId(UUID.randomUUID(), owner, type, origin);
    }

    private static GameState_Retreats state(MovementResult movement) {
        return new GameState_Retreats(new GameMoment(1901, GamePhase.SPRING_RETREAT),
                new BoardState(movement.finalLocations(), Map.of()), movement);
    }

    private static ActionCandidate candidate(OrderForm... orders) {
        return new ActionCandidate(Nation.FRANCE, List.of(orders), -1.0, ActionCandidate.Source.POLICY);
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
        return "Retreat action generation and validation";
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
        RetreatActionTestCase test = new RetreatActionTestCase();
        test.eval(true);
        if (!test.passed())
            throw new AssertionError("Retreat action tests failed");
    }

}