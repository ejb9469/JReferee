package testing.selfcheck;

import adjudication.Judge;
import agents.encoding.MovementEncoding;
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
import phase.UnitId;
import phase.movement.MovementProcessor;

import java.util.*;

/**
 * Circular moves with an unavailable convoy must leave friendly occupants in place.
 */
public final class SelfDislodgementSelfCheck {

    private static int checks;

    private SelfDislodgementSelfCheck() { }

    public static void main(String[] args) {
        unavailableConvoy();
        ordinaryCycle();
        convoySwap();
        foreignAttack();
        System.out.println("SelfDislodgementSelfCheck passed: " + checks + " checks");
    }

    private static void unavailableConvoy() {
        UnitId apu = unit(Nation.ITALY, UnitType.FLEET, Province.Apu);
        UnitId ven = unit(Nation.ITALY, UnitType.ARMY, Province.Ven);
        UnitId nap = unit(Nation.ITALY, UnitType.FLEET, Province.Nap);
        UnitId adr = unit(Nation.ITALY, UnitType.FLEET, Province.ADR);
        UnitId ion = unit(Nation.ITALY, UnitType.FLEET, Province.ION);
        for (int route = 0; route < 4; route++) {
            List<Order> orders = List.of(
                    Order.move(apu, Province.Ven), Order.move(ven, Province.Nap), Order.move(nap, Province.Apu),
                    (route & 1) == 0 ? Order.hold(adr) : Order.convoy(adr, Province.Ven, Province.Nap),
                    (route & 2) == 0 ? Order.hold(ion) : Order.convoy(ion, Province.Ven, Province.Nap));
            check(orders, route == 3 ? Set.of(apu, ven, nap) : Set.of(), Set.of());
        }
    }

    private static void ordinaryCycle() {
        UnitId ven = unit(Nation.ITALY, UnitType.ARMY, Province.Ven);
        UnitId tyr = unit(Nation.AUSTRIA, UnitType.ARMY, Province.Tyr);
        UnitId tri = unit(Nation.ITALY, UnitType.ARMY, Province.Tri);
        check(List.of(Order.move(ven, Province.Tyr), Order.move(tyr, Province.Tri), Order.move(tri, Province.Ven)),
                Set.of(ven, tyr, tri), Set.of());
    }

    private static void convoySwap() {
        UnitId apu = unit(Nation.ITALY, UnitType.FLEET, Province.Apu);
        UnitId ven = unit(Nation.ITALY, UnitType.ARMY, Province.Ven);
        UnitId adr = unit(Nation.ITALY, UnitType.FLEET, Province.ADR);
        check(List.of(Order.move(apu, Province.Ven), Order.move(ven, Province.Apu), Order.hold(adr)),
                Set.of(), Set.of());
        check(List.of(Order.move(apu, Province.Ven), Order.move(ven, Province.Apu),
                Order.convoy(adr, Province.Ven, Province.Apu)), Set.of(apu, ven), Set.of());
    }

    private static void foreignAttack() {
        UnitId apu = unit(Nation.ITALY, UnitType.FLEET, Province.Apu);
        UnitId ven = unit(Nation.ITALY, UnitType.ARMY, Province.Ven);
        UnitId nap = unit(Nation.TURKEY, UnitType.FLEET, Province.Nap);
        UnitId adr = unit(Nation.ITALY, UnitType.FLEET, Province.ADR);
        UnitId ion = unit(Nation.TURKEY, UnitType.FLEET, Province.ION);
        check(List.of(Order.move(apu, Province.Ven), Order.move(ven, Province.Nap), Order.move(nap, Province.Apu),
                Order.hold(adr), Order.supportMove(ion, Province.Nap, Province.Apu)), Set.of(nap), Set.of(apu));
    }

    private static void check(List<Order> orders, Set<UnitId> movers, Set<UnitId> dislodged) {
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        for (Order order : orders) locations.put(order.unit(), order.origin());
        var root = new GameState_Movement(new GameMoment(1905, GamePhase.FALL_MOVEMENT), new BoardState(locations, Map.of()));
        for (Nation power : root.aliveNations()) {
            var encoding = new MovementEncoding(root, power);
            encoding.encode(orders.stream().filter(order -> order.owner() == power).toList());
            checks++;
        }
        for (MovementProcessor.Policy policy : MovementProcessor.Policy.values()) {
            var result = new MovementProcessor(policy, 3, 7).process(new MovementInput(locations, orders));
            require(result.successfulMoveDestinations().keySet().equals(movers), policy + ": successful movers");
            require(result.dislodgements().keySet().equals(dislodged), policy + ": dislodgements");
            require(result.finalLocations().size() == locations.size() - dislodged.size(), policy + ": remaining units");
            Set<Province> occupied = new HashSet<>();
            for (var entry : result.finalLocations().entrySet()) {
                Order order = orders.stream().filter(value -> value.unit().equals(entry.getKey())).findFirst().orElseThrow();
                require(entry.getValue() == (movers.contains(order.unit()) ? order.target() : order.origin()), policy + ": final location");
                require(occupied.add(Province.canonical(entry.getValue())), policy + ": no overlapping units");
            }
        }
        permutations(new ArrayList<>(orders), 0, movers);
    }

    private static void permutations(List<Order> orders, int start, Set<UnitId> movers) {
        if (start == orders.size()) {
            List<adjudication.Order> work = orders.stream().map(order -> new adjudication.Order(order, order.origin())).toList();
            new Judge(work).judge();
            for (int index = 0; index < work.size(); index++) {
                var result = work.get(index);
                require(result.resolved, "Definitive verdict");
                if (result.orderType == OrderType.MOVE)
                    require(result.verdict == movers.contains(orders.get(index).unit()), "Move verdict independent of traversal order");
            }
            return;
        }
        for (int next = start; next < orders.size(); next++) {
            Collections.swap(orders, start, next);
            permutations(orders, start + 1, movers);
            Collections.swap(orders, start, next);
        }
    }

    private static UnitId unit(Nation owner, UnitType type, Province location) {
        return new UnitId(owner, type, location);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}