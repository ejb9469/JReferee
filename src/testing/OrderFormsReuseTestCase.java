package testing;

import agents.generation.LegalOrderCatalog;
import agents.generation.LegalOrderGenerator;
import contracts.OrderForms;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import game.GameState_Movement;
import phase.Order;
import phase.UnitId;

import java.util.Map;
import java.util.UUID;

public final class OrderFormsReuseTestCase implements TestCase {

    private TestEvaluation evaluation;

    @Override
    public void eval(boolean print) {
        TestEvaluation.Builder checks = new TestEvaluation.Builder();

        UnitId fleet = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.Tus);
        UnitId convoyFleet = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.ENG);

        checks.expect("Tuscany and Venice share board adjacency",
                true, Province.Tus.isAdjacentTo(Province.Ven));
        checks.expect("Tuscany and Venice are not sea-adjacent",
                false, Province.adjacentBySea(Province.Tus, Province.Ven));
        checks.expect("Shared Fleet move predicate rejects a land-only edge",
                false,
                OrderForms.isPotentialMove(
                        UnitType.FLEET,
                        Province.Tus,
                        Province.Ven));
        checks.expect("Movement validator rejects a land-only Fleet edge",
                false,
                OrderForms.isValidMovementOrder(
                        Order.move(fleet, Province.Ven),
                        Province.Tus));
        checks.expect("Movement validator rejects an invalid Fleet location",
                false,
                OrderForms.isValidMovementOrder(
                        Order.move(fleet, Province.Ven),
                        Province.Par));
        checks.expect("Fleet support uses the shared sea-adjacency rule",
                false,
                OrderForms.isValidMovementOrder(
                        Order.supportMove(fleet, Province.Tri, Province.Ven),
                        Province.Tus));
        checks.expect("Convoy order rejects an inland Army origin",
                false,
                OrderForms.isValidMovementOrder(
                        Order.convoy(convoyFleet, Province.Par, Province.Nwy),
                        Province.ENG));
        checks.expect("Convoy order rejects an inland Army destination",
                false,
                OrderForms.isValidMovementOrder(
                        Order.convoy(convoyFleet, Province.Bre, Province.Mun),
                        Province.ENG));
        checks.expect("Coastal Army endpoints form a possible convoy move",
                true,
                OrderForms.isValidMovementOrder(
                        Order.convoy(convoyFleet, Province.Bre, Province.Nwy),
                        Province.ENG));

        GameState_Movement state = new GameState_Movement(
                new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(fleet, Province.Tus), Map.of()));
        LegalOrderCatalog catalog = new LegalOrderGenerator()
                .generate(state, Nation.FRANCE);

        checks.expect("Generator rejects the same land-only Fleet edge",
                false,
                catalog.contains(Order.move(fleet, Province.Ven)));
        checks.expect("Catalog validates generated choices with shared rules",
                true,
                catalog.optionsFor(fleet).stream().allMatch(
                        order -> OrderForms.isValidMovementOrder(
                                order,
                                Province.Tus)));

        evaluation = checks.build();

        if (print)
            printEval();
    }

    @Override
    public String getName() {
        return "Shared order-rule reuse";
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
        System.out.println(evaluation == null
                ? "UNEVALUATED " + getName()
                : getEval());
    }

    @Override
    public void printNameAndScore() {
        System.out.printf("[%02d/%02d]\t%s%n",
                getScore(), getSize(), getName());
    }

    public static void main(String[] args) {
        OrderFormsReuseTestCase test = new OrderFormsReuseTestCase();
        test.eval(true);

        if (!test.passed())
            throw new AssertionError("Shared order-rule tests failed");
    }
}