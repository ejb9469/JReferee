package testing;

import agents.generation.LegalOrderCatalog;
import agents.generation.LegalOrderGenerator;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GamePhase;
import game.GameMoment;
import game.GameState_Movement;
import phase.Order;
import phase.UnitId;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LegalOrderGeneratorTestCase implements TestCase {

    private TestEvaluation evaluation;

    @Override
    public void eval(boolean print) {
        UnitId coastalArmy = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Mar);
        UnitId inlandArmy = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId splitCoastFleet = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.LYO);
        UnitId convoyFleet = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.ENG);
        UnitId convoyArmy = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Bre);
        UnitId otherPowerArmy = new UnitId(
                UUID.randomUUID(), Nation.RUSSIA, UnitType.ARMY, Province.War);

        BoardState board = new BoardState(
                Map.of(
                        coastalArmy, Province.Mar,
                        inlandArmy, Province.Par,
                        splitCoastFleet, Province.LYO,
                        convoyFleet, Province.ENG,
                        convoyArmy, Province.Bre,
                        otherPowerArmy, Province.War),
                Map.of());

        GameState_Movement state = new GameState_Movement(
                new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                board);

        LegalOrderCatalog catalog = new LegalOrderGenerator()
                .generate(state, Nation.FRANCE);

        TestEvaluation.Builder checks = new TestEvaluation.Builder();
        checks.expect("Catalog covers exactly the requested power's units",
                Set.of(coastalArmy, inlandArmy, splitCoastFleet, convoyFleet, convoyArmy),
                catalog.optionsByUnit().keySet());

        checks.expect("Each unit has a hold option",
                true,
                catalog.optionsByUnit().values().stream()
                        .allMatch(options -> options.stream()
                                .anyMatch(order -> order.orderType() == OrderType.HOLD)));

        checks.expect("Coast-to-coast Army move is included as a convoy possibility",
                true,
                catalog.contains(Order.move(coastalArmy, Province.Nwy)));

        checks.expect("Nonadjacent inland Army move is omitted",
                false,
                catalog.contains(Order.move(inlandArmy, Province.Mos)));

        checks.expect("Fleet can move to the adjacent Spain coast",
                true,
                catalog.contains(Order.move(splitCoastFleet, Province.SpaSC)));

        checks.expect("Fleet cannot move to the other Spain coast",
                false,
                catalog.contains(Order.move(splitCoastFleet, Province.SpaNC)));

        checks.expect("Sea fleet receives a coastal Army convoy option",
                true,
                catalog.contains(Order.convoy(
                        convoyFleet, Province.Bre, Province.Nwy)));

        evaluation = checks.build();

        if (print)
            printEval();
    }

    @Override
    public String getName() {
        return "Legal order generation";
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
        LegalOrderGeneratorTestCase test = new LegalOrderGeneratorTestCase();
        test.eval(true);

        if (!test.passed())
            throw new AssertionError("Legal-order generation tests failed");
    }
}