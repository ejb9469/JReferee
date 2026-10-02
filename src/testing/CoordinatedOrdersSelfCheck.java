package testing;

import analysis.*;
import analysis.openings.Classifier;
import domain.*;
import game.BoardState;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;

import java.util.*;

public final class CoordinatedOrdersSelfCheck {

    private CoordinatedOrdersSelfCheck() { }

    public static void main(String[] args) {
        convoyPairs();
        convoyChains();
        supports();
        coasts();
        boundedSearch();
        foreignCooperation();
        stability();
        System.out.println("CoordinatedOrders checks passed.");
    }

    private static void convoyPairs() {
        UnitId nth = unit(UnitType.FLEET, Province.NTH);
        UnitId yor = unit(UnitType.ARMY, Province.Yor);
        BoardState board = board(nth, yor);
        Order convoy = Order.convoy(nth, Province.Yor, Province.Bel);
        var mismatch = generate(board, predictions(convoy, Order.move(yor, Province.Nwy)));
        require(mismatch.plans().isEmpty(), "NTH C Yor-Bel + Yor-Nwy survived");
        require(mismatch.diagnostics().rejectedCandidates() > 0
                        && mismatch.exhaustedRetainedDomains(),
                "Contradiction diagnostics missing");
        require(generate(board, predictions(convoy, Order.move(yor, Province.Bel)))
                .plans().size() == 1, "Matching NTH convoy rejected");
        var valid = generate(board, predictions(convoy, Order.move(yor, Province.Bel)));
        var result = new JointOrders().evaluate(board, valid.plans().getFirst(),
                List.of(), processor());
        require(result.finalLocations().get(yor) == Province.Bel, "Feasible convoy did not adjudicate");
        require(generate(board(nth), predictions(convoy)).plans().isEmpty(),
                "Missing army accepted");
        UnitId fleetYor = unit(UnitType.FLEET, Province.Yor);
        require(generate(board(nth, fleetYor),
                predictions(convoy, Order.hold(fleetYor))).plans().isEmpty(),
                "Convoy to a fleet accepted");
        require(generate(board, predictions(Order.convoy(nth, Province.Yor, Province.Nwy),
                Order.move(yor, Province.Bel))).plans().isEmpty(),
                "Wrong convoy destination accepted");
        require(generate(board(yor), predictions(Order.move(yor, Province.Lon)))
                .plans().size() == 1, "Adjacent land move requires a convoy");
        require(new JointOrders(4, 32).generate(board, Nation.ENGLAND,
                predictions(convoy, Order.move(yor, Province.Nwy))).size() == 1,
                "Legacy raw generation changed");
        require(new CoordinatedOrders(4, 32).generate(board, Nation.ENGLAND,
                predictions(convoy, Order.move(yor, Province.Nwy)), CoordinationMode.RAW)
                .plans().equals(new JointOrders(4, 32).generate(board, Nation.ENGLAND,
                        predictions(convoy, Order.move(yor, Province.Nwy)))),
                "RAW adapter differs from historical search");
    }

    private static void convoyChains() {
        UnitId lon = unit(UnitType.ARMY, Province.Lon);
        UnitId eng = unit(UnitType.FLEET, Province.ENG);
        UnitId mao = unit(UnitType.FLEET, Province.MAO);
        Order move = Order.move(lon, Province.Por);
        Order first = Order.convoy(eng, Province.Lon, Province.Por);
        Order last = Order.convoy(mao, Province.Lon, Province.Por);
        BoardState board = board(lon, eng, mao);
        var coherent = generate(board, predictions(move, first, last));
        require(coherent.plans().size() == 1, "Connected multi-fleet chain rejected");
        require(new JointOrders().evaluate(board, coherent.plans().getFirst(),
                List.of(), processor()).finalLocations().get(lon) == Province.Por,
                "Multi-fleet route did not adjudicate");
        UnitId wes = unit(UnitType.FLEET, Province.WES);
        require(generate(board(lon, eng, wes), predictions(move, first,
                Order.convoy(wes, Province.Lon, Province.Por))).plans().isEmpty(),
                "Disconnected matching fleets supplied a convoy");
        require(generate(board, predictions(move, first, Order.hold(mao)))
                .plans().isEmpty(), "Assigned holding fleet supplied a route");
        require(generate(board, predictions(move, first,
                Order.convoy(mao, Province.Lon, Province.Spa))).plans().isEmpty(),
                "Incompatible convoy supplied a route");
        // ENG is assigned before Lon, and MAO after it. Neither ordering is a contradiction.
        require(coherent.diagnostics().rejectedCandidates() == 0,
                "Later-assigned matching fleet was prematurely pruned");
    }

    private static void supports() {
        UnitId bel = unit(UnitType.ARMY, Province.Bel);
        UnitId pic = unit(UnitType.ARMY, Province.Pic);
        BoardState board = board(bel, pic);
        Order support = Order.supportMove(bel, Province.Pic, Province.Bur);
        require(generate(board, predictions(support, Order.move(pic, Province.Bur)))
                .plans().size() == 1, "Friendly matching support rejected");
        require(generate(board, predictions(support, Order.move(pic, Province.Par)))
                .plans().isEmpty(), "Mismatching support accepted");
        Order holdSupport = Order.supportHold(bel, Province.Pic);
        require(generate(board, predictions(holdSupport, Order.move(pic, Province.Bur)))
                .plans().isEmpty(), "Hold support paired with MOVE");
        require(generate(board, predictions(holdSupport, Order.hold(pic)))
                .plans().size() == 1, "Hold support paired with HOLD rejected");
        require(generate(board, predictions(holdSupport, Order.supportHold(pic, Province.Bel)))
                .plans().size() == 1, "Hold support must allow a stationary supporter");
        UnitId lon = unit(UnitType.FLEET, Province.Lon);
        UnitId nth = unit(UnitType.FLEET, Province.NTH);
        UnitId yor = unit(UnitType.ARMY, Province.Yor);
        require(generate(board(lon, nth, yor), predictions(
                Order.supportHold(lon, Province.NTH),
                Order.convoy(nth, Province.Yor, Province.Bel),
                Order.move(yor, Province.Bel))).plans().size() == 1,
                "Hold support must allow a stationary convoyer");
        require(generate(board(bel), predictions(support)).plans().isEmpty(),
                "Support referencing missing unit accepted");
        // Tactical self-bounces are not coordination contradictions.
        require(generate(board, predictions(Order.move(bel, Province.Bur),
                Order.move(pic, Province.Bur))).plans().size() == 1,
                "Ordinary self-bounce blanket rejected");
    }

    private static void coasts() {
        UnitId mao = unit(UnitType.FLEET, Province.MAO);
        UnitId gas = unit(UnitType.FLEET, Province.Gas);
        BoardState board = board(mao, gas);
        require(generate(board, predictions(Order.move(mao, Province.SpaNC),
                Order.supportMove(gas, Province.MAO, Province.SpaNC))).plans().size() == 1,
                "Support to explicit fleet coast rejected");
        require(generate(board, predictions(Order.move(mao, Province.SpaNC),
                Order.supportMove(gas, Province.MAO, Province.SpaSC))).plans().isEmpty(),
                "Different explicit destination coasts matched");
        require(generate(board, predictions(Order.move(mao, Province.SpaNC),
                Order.supportMove(gas, Province.MAO, Province.Spa))).plans().isEmpty(),
                "Canonical destination silently changed Judge's exact support match");
        UnitId spa = unit(UnitType.FLEET, Province.SpaNC);
        require(generate(board(spa, gas), predictions(Order.hold(spa),
                Order.supportHold(gas, Province.SpaNC))).plans().size() == 1,
                "Explicit supported position rejected");
        require(generate(board(spa, gas), predictions(Order.hold(spa),
                Order.supportHold(gas, Province.Spa))).plans().isEmpty(),
                "Canonical reference confused with exact fleet coast");
    }

    private static void boundedSearch() {
        UnitId nth = unit(UnitType.FLEET, Province.NTH);
        UnitId yor = unit(UnitType.ARMY, Province.Yor);
        BoardState board = board(nth, yor);
        Map<UnitId, RoutePrediction> domains = predictions(Order.move(yor, Province.Nwy));
        domains.put(nth, prediction(
                Order.convoy(nth, Province.Yor, Province.Bel), 10,
                Order.convoy(nth, Province.Yor, Province.Nwy), 1));
        var raw = new JointOrders(2, 1).generate(board, Nation.ENGLAND, domains);
        require(raw.getFirst().orders().contains(Order.convoy(nth, Province.Yor, Province.Bel)),
                "Narrow raw beam fixture changed");
        var coherent = new CoordinatedOrders(2, 1).generate(
                board, Nation.ENGLAND, domains, CoordinationMode.STRICT);
        require(coherent.plans().size() == 1
                        && coherent.plans().getFirst().orders().contains(
                        Order.convoy(nth, Province.Yor, Province.Nwy)),
                "Forward check failed to save alternative before beam truncation");
        var cutoff = new CoordinatedOrders(1, 1).generate(
                board, Nation.ENGLAND, domains, CoordinationMode.STRICT);
        require(cutoff.plans().isEmpty() && cutoff.diagnostics().omittedChoices() == 1
                        && !cutoff.exhaustedRetainedDomains(),
                "Below-cutoff cooperation falsely proved impossible or synthesized");
        require(coherent.plans().stream().flatMap(plan -> plan.orders().stream())
                .allMatch(order -> domains.get(order.unit()).counts().containsKey(order)),
                "An unevidenced order was invented");
        require(Math.abs(coherent.plans().getFirst().logPreference()
                        - Math.log(1.0 / 11)) < 1e-12,
                "Original observation denominator was changed");
        UnitId bel = unit(UnitType.ARMY, Province.Bel);
        UnitId pic = unit(UnitType.ARMY, Province.Pic);
        UnitId bur = unit(UnitType.ARMY, Province.Bur);
        var supportDomains = predictions(Order.move(pic, Province.Par));
        supportDomains.put(bur, prediction(Order.supportMove(bur, Province.Pic, Province.Bel),
                10, Order.supportMove(bur, Province.Pic, Province.Par), 1));
        require(new CoordinatedOrders(2, 1).generate(board(bur, pic), Nation.ENGLAND,
                supportDomains, CoordinationMode.STRICT).plans().size() == 1,
                "Later-assigned supported unit caused premature pruning");
        var independent = predictions(Order.hold(bel), Order.hold(pic));
        independent.put(bel, prediction(Order.hold(bel), 2, Order.move(bel, Province.Hol), 1));
        var bounded = new CoordinatedOrders(2, 1).generate(board(bel, pic), Nation.ENGLAND,
                independent, CoordinationMode.STRICT);
        require(bounded.diagnostics().beamTruncated(), "Beam pruning not observable");
        require(bounded.diagnostics().expandedCandidates() <= 4, "Unbounded enumeration");
        require(generate(board, Map.of()).plans().isEmpty(), "Missing evidence invented holds");
        require(new CoordinatedOrders(1, 1).generate(board, Nation.FRANCE, Map.of(),
                CoordinationMode.STRICT).plans().isEmpty(), "Absent nation invented orders");
    }

    private static void foreignCooperation() {
        UnitId yor = unit(UnitType.ARMY, Province.Yor);
        UnitId nth = new UnitId(UUID.randomUUID(), Nation.FRANCE, UnitType.FLEET, Province.NTH);
        BoardState board = board(yor, nth);
        Map<UnitId, RoutePrediction> domains = predictions(Order.move(yor, Province.Bel));
        require(generate(board, domains).plans().isEmpty(), "Strict foreign route accepted");
        var conditional = new CoordinatedOrders(4, 32).generate(
                board, Nation.ENGLAND, domains, CoordinationMode.CONDITIONAL);
        require(conditional.plans().size() == 1
                        && conditional.diagnostics().conditionalPlans().values().stream()
                        .flatMap(Collection::stream).anyMatch(text -> text.contains("CONVOY")),
                "Foreign fleet assumption not exposed");
        OrderPlan plan = conditional.plans().getFirst();
        require(new JointOrders().evaluate(board, plan, List.of(Order.hold(nth)),
                processor()).finalLocations().get(yor) == Province.Yor,
                "Conditional route was silently guaranteed");
        require(new JointOrders().evaluate(board, plan,
                List.of(Order.convoy(nth, Province.Yor, Province.Bel)),
                processor()).finalLocations().get(yor) == Province.Bel,
                "Explicit cooperation scenario failed");
        UnitId bel = unit(UnitType.ARMY, Province.Bel);
        UnitId pic = new UnitId(UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Pic);
        board = board(bel, pic);
        domains = predictions(Order.supportMove(bel, Province.Pic, Province.Bur));
        require(generate(board, domains).plans().isEmpty(), "Strict foreign support accepted");
        conditional = new CoordinatedOrders(4, 32).generate(
                board, Nation.ENGLAND, domains, CoordinationMode.CONDITIONAL);
        require(conditional.plans().size() == 1
                        && !conditional.diagnostics().conditionalPlans().isEmpty(),
                "Foreign army move assumption not exposed");
    }

    private static void stability() {
        UnitId nth = unit(UnitType.FLEET, Province.NTH);
        UnitId yor = unit(UnitType.ARMY, Province.Yor);
        var domains = predictions(Order.convoy(nth, Province.Yor, Province.Bel),
                Order.move(yor, Province.Bel));
        BoardState board = board(nth, yor);
        var before = new LinkedHashMap<>(board.locations());
        var first = generate(board, domains);
        BoardState reordered = board(yor, nth);
        var reversed = new LinkedHashMap<UnitId, RoutePrediction>();
        reversed.put(yor, domains.get(yor));
        reversed.put(nth, domains.get(nth));
        require(first.equals(generate(reordered, reversed)), "Map insertion changed search");
        UnitId newNth = unit(UnitType.FLEET, Province.NTH);
        UnitId newYor = unit(UnitType.ARMY, Province.Yor);
        BoardState newBoard = board(newYor, newNth);
        var second = generate(newBoard, predictions(Order.move(newYor, Province.Bel),
                Order.convoy(newNth, Province.Yor, Province.Bel)));
        require(Classifier.signature(board, first.plans().getFirst().orders()).equals(
                Classifier.signature(newBoard, second.plans().getFirst().orders())),
                "Random UUIDs changed stable plan signature");
        require(first.diagnostics().equals(second.diagnostics()), "UUID-dependent diagnostics");
        require(board.locations().equals(before) && domains.size() == 2
                        && domains.get(nth).observations() == 1,
                "Search mutated input");
    }

    private static CoordinatedOrders.Result generate(BoardState board,
                                                     Map<UnitId, RoutePrediction> domains) {
        return new CoordinatedOrders(4, 32).generate(
                board, Nation.ENGLAND, domains, CoordinationMode.STRICT);
    }

    private static UnitId unit(UnitType type, Province province) {
        return new UnitId(UUID.randomUUID(), Nation.ENGLAND, type, province);
    }

    private static BoardState board(UnitId... units) {
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        for (UnitId unit : units)
            locations.put(unit, unit.origin());
        return new BoardState(locations, Map.of());
    }

    private static Map<UnitId, RoutePrediction> predictions(Order... orders) {
        Map<UnitId, RoutePrediction> predictions = new LinkedHashMap<>();
        for (Order order : orders)
            predictions.put(order.unit(), new RoutePrediction("POSITION", 0, Map.of(order, 1L)));
        return predictions;
    }

    private static RoutePrediction prediction(Order first, long firstCount,
                                              Order second, long secondCount) {
        return new RoutePrediction("POSITION", 0, Map.of(first, firstCount, second, secondCount));
    }

    private static MovementProcessor processor() {
        return new MovementProcessor(MovementProcessor.Policy.JUSTICE, 1, 1L);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
