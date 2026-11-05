package testing;

import adjudication.util.Convoys;
import adjudication.util.Orders;
import agents.generation.ActionCandidate;
import agents.generation.LegalOrderCatalog;
import agents.generation.LegalOrderGenerator;
import contracts.OrderForm;
import contracts.OrderForms;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import game.GameState_Movement;
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

import java.util.*;

public final class OrderFormsTestCase implements TestCase {

    private TestEvaluation evaluation;

    @Override
    public void eval(boolean print) {
        TestEvaluation.Builder checks = new TestEvaluation.Builder();

        checkCopies(checks);
        checkFilters(checks);
        checkMovementValidity(checks);
        checkRetreatValidity(checks);
        checkSupportValidity(checks);
        checkOrdersAdapters(checks);
        checkConvoyHelpers(checks);
        checkPhaseOrderLocations(checks);
        checkCandidateIsolation(checks);
        checkSupportResolution(checks);
        checkRetreatResolution(checks);

        evaluation = checks.build();

        if (print)
            printEval();
    }

    private static void checkCopies(TestEvaluation.Builder checks) {
        UnitId army = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        Order hold = Order.hold(army);
        List<OrderForm> source = new ArrayList<>(List.of(hold));

        List<OrderForm> shallow = OrderForms.shallowCopy(source);
        checks.expect("Shallow copy creates a new list", true, shallow != source);
        checks.expect("Shallow copy retains the same form", true, shallow.get(0) == hold);

        source.clear();
        checks.expect("Shallow copy is independent of its source list",
                List.of(hold), shallow);

        adjudication.Order workOrder = new adjudication.Order(
                Nation.FRANCE,
                UnitType.ARMY,
                Province.Bre,
                OrderType.HOLD);

        List<adjudication.Order> deep = OrderForms.deepCopy(
                List.of(workOrder),
                adjudication.Order::new);

        checks.expect("Deep copy uses the supplied copier",
                true, deep.get(0) != workOrder);
        checks.expect("Deep copy initially preserves order values",
                true, deep.get(0).equals(workOrder));

        deep.get(0).pos1 = Province.Nwy;
        checks.expect("Deep copy isolates mutable order fields",
                null, workOrder.pos1);

        List<adjudication.Order> adapterCopy = Orders.deepCopy(List.of(workOrder));
        checks.expect("Orders deep-copy adapter creates a new order",
                true, adapterCopy.get(0) != workOrder);

        Collection<OrderForm> candidateInput = new ArrayList<>(List.of(hold));
        ActionCandidate candidate = new ActionCandidate(
                Nation.FRANCE,
                candidateInput,
                -0.25,
                ActionCandidate.Source.POLICY);

        candidateInput.clear();
        checks.expect("Candidate copies the input collection",
                List.of(hold), candidate.orders());
        checks.expect("Candidate shallow-copies order forms",
                true, candidate.orders().get(0) == hold);

        boolean unmodifiable = false;
        try {
            candidate.orders().add(hold);
        } catch (UnsupportedOperationException expected) {
            unmodifiable = true;
        }
        checks.expect("Candidate order list is immutable", true, unmodifiable);
    }

    private static void checkFilters(TestEvaluation.Builder checks) {
        UnitId army = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        Order move = Order.move(army, Province.Nwy);
        List<OrderForm> mixed = List.of(move, army, Order.hold(army));

        checks.expect("Order-type filter retains matching forms",
                List.of(move),
                new ArrayList<>(Orders.pruneForOrderType(OrderType.MOVE, mixed)));

        checks.expect("Null order type selects unit-only forms",
                List.of(army),
                new ArrayList<>(Orders.pruneForOrderType(null, mixed)));

        adjudication.Order splitCoastMove = new adjudication.Order(
                Nation.FRANCE,
                UnitType.FLEET,
                Province.LYO,
                OrderType.MOVE,
                Province.SpaSC);

        checks.expect("Move lookup ignores coast distinctions",
                List.of(splitCoastMove),
                new ArrayList<>(Orders.locateUnitsMovingToPosition(
                        Province.Spa,
                        List.of(splitCoastMove))));
    }

    private static void checkMovementValidity(TestEvaluation.Builder checks) {
        UnitId army = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId fleet = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.Smy);

        checks.expect("Canonical hold is valid",
                true,
                OrderForms.isValidMovementOrder(Order.hold(army), Province.Par));

        checks.expect("Malformed hold is invalid",
                false,
                OrderForms.isValidMovementOrder(
                        new Order(army, OrderType.HOLD, Province.Bur, null),
                        Province.Par));

        checks.expect("Army move to water is invalid",
                false,
                OrderForms.isValidMovementOrder(
                        Order.move(army, Province.NTH),
                        Province.Par));

        checks.expect("Army move to a split coast is invalid",
                false,
                OrderForms.isValidMovementOrder(
                        Order.move(army, Province.SpaNC),
                        Province.Gas));

        checks.expect("Coast-to-coast Army move is structurally valid",
                true,
                OrderForms.isValidMovementOrder(
                        Order.move(army, Province.Nwy),
                        Province.Bre));

        checks.expect("Adjacent inland Army move is valid",
                true,
                OrderForms.isValidMovementOrder(
                        Order.move(army, Province.Bur),
                        Province.Par));

        checks.expect("Nonadjacent inland Army move is invalid",
                false,
                OrderForms.isValidMovementOrder(
                        Order.move(army, Province.Mos),
                        Province.Par));

        checks.expect("Coastal Army cannot convoy to inland province",
                false,
                OrderForms.isValidMovementOrder(
                        Order.move(army, Province.Mun),
                        Province.Bre));

        Order fleetToSouthCoast = Order.move(fleet, Province.SpaSC);
        checks.expect("Fleet move uses current location, not creation origin",
                true,
                OrderForms.isValidMovementOrder(fleetToSouthCoast, Province.LYO));
        checks.expect("Fleet creation origin remains distinct",
                Province.Smy, fleetToSouthCoast.origin());

        checks.expect("Fleet cannot enter the wrong Spain coast",
                false,
                OrderForms.isValidMovementOrder(
                        Order.move(fleet, Province.SpaNC),
                        Province.LYO));

        checks.expect("Fleet cannot move to an inland province",
                false,
                OrderForms.isValidMovementOrder(
                        Order.move(fleet, Province.Mun),
                        Province.LYO));

        checks.expect("Fleet cannot move to its current territory",
                false,
                OrderForms.isValidMovementOrder(
                        Order.move(fleet, Province.LYO),
                        Province.LYO));

        checks.expect("Unit-only form is not a movement order",
                false,
                OrderForms.isValidMovementOrder(army, Province.Par));

        checks.expect("Retreats remain outside movement-order validation",
                false,
                OrderForms.isValidMovementOrder(
                        new RetreatOrder(army, Province.Bur),
                        Province.Bre));
    }

    private static void checkRetreatValidity(TestEvaluation.Builder checks) {
        UnitId army = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId fleet = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.Smy);

        checks.expect("Adjacent Army retreat is valid",
                true,
                OrderForms.isValidRetreatOrder(
                        new RetreatOrder(army, Province.Gas),
                        Province.Bre));

        checks.expect("Army retreat cannot use a convoy route",
                false,
                OrderForms.isValidRetreatOrder(
                        new RetreatOrder(army, Province.Nwy),
                        Province.Bre));

        checks.expect("Army retreat cannot enter a sea province",
                false,
                OrderForms.isValidRetreatOrder(
                        new RetreatOrder(army, Province.NTH),
                        Province.Bre));

        checks.expect("Fleet retreat follows sea adjacency",
                true,
                OrderForms.isValidRetreatOrder(
                        new RetreatOrder(fleet, Province.SpaSC),
                        Province.LYO));

        checks.expect("Fleet retreat rejects the unreachable Spain coast",
                false,
                OrderForms.isValidRetreatOrder(
                        new RetreatOrder(fleet, Province.SpaNC),
                        Province.LYO));

        for (Province origin : Province.values()) {
            if (!OrderForms.isValidUnitLocation(UnitType.FLEET, origin))
                continue;
            for (Province destination : Province.values())
                checks.expect("Fleet move and retreat geometry: "
                                + origin + " - " + destination,
                        OrderForms.isPotentialMove(UnitType.FLEET, origin, destination),
                        OrderForms.isValidRetreatOrder(
                                new RetreatOrder(fleet, destination), origin));
        }
    }

    private static void checkSupportValidity(TestEvaluation.Builder checks) {
        UnitId army = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId fleet = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.Smy);

        checks.expect("Army cannot support into a sea province",
                false,
                OrderForms.isValidMovementOrder(
                        Order.supportMove(army, Province.Lon, Province.NTH),
                        Province.Par));

        checks.expect("Army can support a Fleet move to a split coast",
                true,
                OrderForms.isValidMovementOrder(
                        Order.supportMove(army, Province.MAO, Province.SpaNC),
                        Province.Gas));

        checks.expect("Army can support a Fleet holding on the other coast",
                true,
                OrderForms.isValidMovementOrder(
                        Order.supportHold(army, Province.StpNC),
                        Province.Fin));

        checks.expect("Fleet can support a coastal move across a sea area",
                true,
                OrderForms.isValidMovementOrder(
                        Order.supportMove(fleet, Province.Mar, Province.Spa),
                        Province.LYO));

        checks.expect("Fleet support does not follow a land-only coast edge",
                false,
                OrderForms.isValidMovementOrder(
                        Order.supportMove(fleet, Province.Tri, Province.Ven),
                        Province.Tus));

        checks.expect("Support move cannot target its starting territory",
                false,
                OrderForms.isValidMovementOrder(
                        Order.supportMove(fleet, Province.Mar, Province.Mar),
                        Province.LYO));
    }

    private static void checkOrdersAdapters(TestEvaluation.Builder checks) {
        UnitId army = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId fleet = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.Smy);
        Order phaseMove = Order.move(fleet, Province.SpaSC);
        adjudication.Order validWorkOrder = new adjudication.Order(
                phaseMove, Province.LYO);
        adjudication.Order invalidWorkOrder = new adjudication.Order(
                phaseMove, Province.Smy);

        checks.expect("Orders validity adapter uses work-order location",
                true, Orders.orderIsValid(validWorkOrder));
        checks.expect("Orders validity adapter rejects nonadjacent coast",
                false, Orders.orderIsValid(invalidWorkOrder));

        adjudication.Order validSupport = new adjudication.Order(
                Order.supportMove(fleet, Province.Mar, Province.Spa),
                Province.LYO);
        adjudication.Order invalidSupport = new adjudication.Order(
                Order.supportMove(fleet, Province.Tri, Province.Ven),
                Province.Tus);

        checks.expect("Orders adapter accepts valid fleet support",
                true, Orders.orderIsValid(validSupport));
        checks.expect("Orders adapter rejects land-only fleet support",
                false, Orders.orderIsValid(invalidSupport));

        adjudication.Order validRetreat = new adjudication.Order(
                new RetreatOrder(army, Province.Gas),
                Province.Bre);
        adjudication.Order invalidRetreat = new adjudication.Order(
                new RetreatOrder(army, Province.Nwy),
                Province.Bre);

        checks.expect("Orders adapter accepts a valid Army retreat",
                true, Orders.orderIsValid(validRetreat));
        checks.expect("Orders adapter rejects a nonadjacent Army retreat",
                false, Orders.orderIsValid(invalidRetreat));

        adjudication.Order validConvoy = new adjudication.Order(
                Nation.FRANCE,
                UnitType.FLEET,
                Province.NTH,
                OrderType.CONVOY,
                Province.Bre,
                Province.Nwy);

        checks.expect("Orders adapter accepts a well-formed convoy order",
                true, Orders.orderIsValid(validConvoy));
    }

    private static void checkConvoyHelpers(TestEvaluation.Builder checks) {
        adjudication.Order armyMove = new adjudication.Order(
                Nation.FRANCE,
                UnitType.ARMY,
                Province.Bre,
                OrderType.MOVE,
                Province.Nwy);
        adjudication.Order engConvoy = convoy(Province.ENG);
        adjudication.Order nthConvoy = convoy(Province.NTH);
        adjudication.Order mismatchedConvoy = new adjudication.Order(
                Nation.FRANCE,
                UnitType.FLEET,
                Province.NTH,
                OrderType.CONVOY,
                Province.Bre,
                Province.Lon);

        List<adjudication.Order> route = List.of(engConvoy, nthConvoy);

        checks.expect("Shared helper validates a two-fleet route",
                true,
                OrderForms.convoyPathIsValid(
                        armyMove,
                        Province.Bre,
                        route,
                        adjudication.Order::origin));

        checks.expect("Convoys adapter validates a two-fleet route",
                true, Convoys.convoyPathIsValid(armyMove, route));

        checks.expect("Convoys adapter draws the route in order",
                route, Convoys.drawConvoyPath(armyMove, route));

        checks.expect("Convoys adapter finds the adjacent fleet",
                List.of(nthConvoy),
                Convoys.findAdjacentConvoys(
                        engConvoy,
                        List.of(),
                        route));

        checks.expect("Excluded convoy is not returned",
                List.of(),
                Convoys.findAdjacentConvoys(
                        engConvoy,
                        List.of(nthConvoy),
                        route));

        checks.expect("Incomplete convoy path is rejected",
                false, Convoys.convoyPathIsValid(armyMove, List.of(engConvoy)));

        checks.expect("Repeated fleet in a convoy path is rejected",
                false,
                Convoys.convoyPathIsValid(
                        armyMove,
                        List.of(engConvoy, nthConvoy, engConvoy)));

        checks.expect("Mismatched convoy destination is rejected",
                false,
                Convoys.convoyPathIsValid(
                        armyMove,
                        List.of(engConvoy, mismatchedConvoy)));

        checks.expect("No route is returned for an incomplete path",
                List.of(),
                Convoys.drawConvoyPath(armyMove, List.of(engConvoy)));
    }

    private static void checkPhaseOrderLocations(TestEvaluation.Builder checks) {
        UnitId army = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId engFleet = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.Smy);
        UnitId nthFleet = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.Tus);

        Order armyMove = Order.move(army, Province.Nwy);
        Order engConvoy = Order.convoy(engFleet, Province.Bre, Province.Nwy);
        Order nthConvoy = Order.convoy(nthFleet, Province.Bre, Province.Nwy);

        Map<UnitId, Province> currentLocations = Map.of(
                army, Province.Bre,
                engFleet, Province.ENG,
                nthFleet, Province.NTH);

        List<Order> route = List.of(engConvoy, nthConvoy);

        checks.expect("Phase order keeps its creation origin",
                Province.Par, armyMove.origin());

        checks.expect("Resolver validates route from current fleet locations",
                true,
                OrderForms.convoyPathIsValid(
                        armyMove,
                        currentLocations.get(army),
                        route,
                        order -> currentLocations.get(order.unit())));

        checks.expect("Creation origins do not stand in for fleet locations",
                false,
                OrderForms.convoyPathIsValid(
                        armyMove,
                        currentLocations.get(army),
                        route,
                        Order::origin));
    }

    private static void checkCandidateIsolation(TestEvaluation.Builder checks) {
        UnitId unit = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        Order hold = Order.hold(unit);
        List<OrderForm> source = new ArrayList<>(List.of(hold));
        ActionCandidate candidate = new ActionCandidate(
                Nation.FRANCE, source, -0.5, ActionCandidate.Source.HEURISTIC);
        Set<ActionCandidate> candidates = new HashSet<>(Set.of(candidate));
        source.clear();
        checks.expect("Candidate remains stable after source list changes",
                true, candidates.contains(candidate));
        checks.expect("Candidate retains immutable submitted orders",
                List.of(hold), candidate.orders());

        List<OrderForm> rejected = List.of(
                new adjudication.Order(hold, Province.Par),
                new Order(unit, OrderType.HOLD, null, null) { },
                unit);
        for (OrderForm order : rejected) {
            boolean rejectedOrder = false;
            try {
                new ActionCandidate(Nation.FRANCE, List.of(order),
                        -0.5, ActionCandidate.Source.HEURISTIC);
            } catch (IllegalArgumentException expected) {
                rejectedOrder = true;
            }
            checks.expect("Candidate rejects " + order.getClass().getName(),
                    true, rejectedOrder);
        }

        List<OrderForm> accepted = List.of(
                hold,
                new RetreatOrder(unit, Province.Bur),
                new BuildOrder(Nation.FRANCE, UnitType.FLEET, Province.Bre),
                new DisbandOrder(Nation.FRANCE, unit),
                new WaiveOrder(Nation.FRANCE));
        for (OrderForm order : accepted) {
            ActionCandidate singleOrder = new ActionCandidate(
                    Nation.FRANCE, List.of(order), -0.5,
                    ActionCandidate.Source.HEURISTIC);
            checks.expect("Candidate accepts " + order.getClass().getSimpleName(),
                    List.of(order), singleOrder.orders());
        }
    }

    private static void checkSupportResolution(TestEvaluation.Builder checks) {
        UnitId army = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Gas);
        UnitId fleet = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.MAO);
        UnitId defender = new UnitId(
                UUID.randomUUID(), Nation.ENGLAND, UnitType.FLEET, Province.SpaNC);
        Map<UnitId, Province> locations = Map.of(
                army, Province.Gas, fleet, Province.MAO, defender, Province.SpaNC);
        Order support = Order.supportMove(army, Province.MAO, Province.SpaNC);
        LegalOrderCatalog catalog = new LegalOrderGenerator().generate(
                new GameState_Movement(
                        new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                        new BoardState(locations, Map.of())),
                Nation.FRANCE);
        checks.expect("Catalog includes Army support for a split-coast Fleet move",
                true, catalog.contains(support));
        checks.expect("Catalog includes Army hold support for a split-coast Fleet",
                true, catalog.contains(Order.supportHold(army, Province.SpaNC)));
        checks.expect("Orders adapter accepts Army support to a split coast",
                true, Orders.orderIsValid(new adjudication.Order(support, Province.Gas)));

        MovementResult result = new MovementProcessor().process(new MovementInput(
                locations, List.of(support, Order.move(fleet, Province.SpaNC),
                Order.hold(defender))));
        checks.expect("Supported Fleet arrives at Spain's north coast",
                Province.SpaNC, result.finalLocations().get(fleet));
        checks.expect("Army support dislodges the defending Fleet",
                true, result.dislodgements().containsKey(defender));
    }

    private static void checkRetreatResolution(TestEvaluation.Builder checks) {
        UnitId fleet = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.LYO);
        UnitId attacker = new UnitId(
                UUID.randomUUID(), Nation.ITALY, UnitType.FLEET, Province.WES);
        UnitId supporter = new UnitId(
                UUID.randomUUID(), Nation.ITALY, UnitType.FLEET, Province.TYS);
        MovementResult movement = new MovementProcessor().process(new MovementInput(
                Map.of(fleet, Province.LYO, attacker, Province.WES,
                        supporter, Province.TYS),
                List.of(Order.hold(fleet), Order.move(attacker, Province.LYO),
                        Order.supportMove(supporter, Province.WES, Province.LYO))));
        checks.expect("Retreat fixture dislodges the Fleet",
                true, movement.dislodgements().containsKey(fleet));
        RetreatResult wrongCoast = new RetreatProcessor().process(new RetreatInput(
                movement, List.of(new RetreatOrder(fleet, Province.SpaNC))));
        checks.expect("Retreat processor rejects an unreachable coast",
                RetreatResult.Outcome.Status.DESTROYED_ILLEGAL_DESTINATION,
                wrongCoast.outcomeOf(fleet).status());
        checks.expect("Invalid retreat does not place a Fleet on the board",
                null, wrongCoast.locationOf(fleet));
        RetreatResult reachableCoast = new RetreatProcessor().process(new RetreatInput(
                movement, List.of(new RetreatOrder(fleet, Province.SpaSC))));
        checks.expect("Retreat processor still accepts the reachable coast",
                Province.SpaSC, reachableCoast.locationOf(fleet));
    }

    private static adjudication.Order convoy(Province sea) {
        return new adjudication.Order(
                Nation.FRANCE,
                UnitType.FLEET,
                sea,
                OrderType.CONVOY,
                Province.Bre,
                Province.Nwy);
    }

    @Override
    public String getName() {
        return "OrderForm shared utilities";
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
        System.out.printf("[%02d/%02d]\t%s%n", getScore(), getSize(), getName());
    }

    public static void main(String[] args) {
        OrderFormsTestCase test = new OrderFormsTestCase();
        test.eval(true);

        if (!test.passed())
            throw new AssertionError("OrderForm shared utility tests failed");
    }

}