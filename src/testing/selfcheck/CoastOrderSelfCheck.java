package testing.selfcheck;

import adjudication.*;
import adjudication.util.Orders;
import agents.encoding.MovementEncoding;
import contracts.OrderForms;
import domain.*;
import game.*;
import io.catalog.SourceFingerprints;
import io.training.DiploBNTrainingExporter;
import parsing.diplobn.*;
import phase.Order;
import phase.UnitId;

import java.nio.file.*;
import java.util.*;

/**
 * Coast handling across adjudication, action identity, encoding, and import.
 * Optional arguments are exported DiploBN files for games 14031, 16192, or 10667.
 */
public final class CoastOrderSelfCheck {

    private static int checks;

    private CoastOrderSelfCheck() { }

    public static void main(String[] args) throws Exception {
        catalogAndIdentity();
        adjudication();
        inference();
        export();
        for (String arg : args)
            sourceGame(Path.of(arg));
        System.out.println("CoastOrderSelfCheck passed: " + checks + " checks");
    }

    private static void catalogAndIdentity() {
        for (Province[] pair : List.of(
                new Province[]{Province.Ser, Province.Gre, Province.Bul, Province.BulEC, Province.BulSC},
                new Province[]{Province.Por, Province.MAO, Province.Spa, Province.SpaNC, Province.SpaSC},
                new Province[]{Province.Mos, Province.BAR, Province.Stp, Province.StpNC, Province.StpSC})) {
            UnitId army = unit(UnitType.ARMY), fleet = unit(UnitType.FLEET);
            MovementEncoding encoding = encoding(Map.of(army, pair[0], fleet, pair[1]));
            Order generic = Order.supportMove(army, pair[1], pair[2]);
            List<Integer> expected = encoding.encode(List.of(generic, Order.hold(fleet)));
            for (int coast = 2; coast < pair.length; coast++) {
                Order alias = Order.supportMove(army, pair[1], pair[coast]);
                require(generic.equals(alias) && alias.equals(generic), "Support identity");
                require(generic.hashCode() == alias.hashCode(), "Support hash");
                require(alias.auxiliaryTarget() == pair[coast], "Submitted fields retained");
                require(encoding.catalog().contains(alias), "Catalog accepts support alias");
                require(encoding.encode(List.of(alias, Order.hold(fleet))).equals(expected), "One action index");
                require(encoding.token(alias).equals(encoding.token(generic)), "One support label");
            }
            require(encoding.catalog().optionsFor(army).stream().filter(generic::equals).count() == 1,
                    "One catalog option per support");
            for (var choices : encoding.options())
                for (var option : choices)
                    if (option.orderType() == OrderType.SUPPORT)
                        require(option.target() == Province.canonical(option.target())
                                        && option.auxiliaryTarget() == Province.canonical(option.auxiliaryTarget()),
                                "Canonical support references");
            require(!Order.move(fleet, pair[3]).equals(Order.move(fleet, pair[4])), "Moves retain coasts");
            require(new HashSet<>(List.of(generic,
                    Order.supportMove(army, pair[1], pair[3]))).size() == 1, "Candidate deduplication");
        }

        UnitId supporter = UnitId.newlyBuilt(1901, Nation.FRANCE, UnitType.FLEET, Province.Mar);
        UnitId fleet = unit(UnitType.FLEET);
        MovementEncoding encoding = encoding(Map.of(supporter, Province.Gas, fleet, Province.SpaSC));
        var expected = encoding.encode(List.of(Order.supportHold(supporter, Province.Spa), Order.hold(fleet)));
        for (Province reference : List.of(Province.Spa, Province.SpaNC, Province.SpaSC)) {
            var orders = List.of(Order.supportHold(supporter, reference), Order.hold(fleet));
            require(encoding.encode(orders).equals(expected), "Hold support ignores referenced coast");
            require(encoding.decode(expected).containsAll(orders), "Canonical decode");
            require(encoding.catalog().contains(Order.supportMove(supporter, reference, Province.MAO)),
                    "Support from a split-coast province");
        }
        require(!OrderForms.canSupportTo(UnitType.FLEET, Province.SpaNC, Province.LYO),
                "Supporting Fleet's own coast stays exact");
        require(!encoding.catalog().contains(Order.supportMove(supporter, Province.Spa, Province.Mos)),
                "Illegal support remains absent");
    }

    private static void adjudication() {
        for (int mode = 0; mode < 3; mode++) {
            for (Province coast : List.of(Province.Spa, Province.SpaNC, Province.SpaSC)) {
                var move = work(Nation.FRANCE, UnitType.FLEET, Province.MAO, OrderType.MOVE, Province.SpaSC, null);
                var support = work(Nation.FRANCE, UnitType.ARMY, Province.Por, OrderType.SUPPORT, Province.MAO, coast);
                var hold = work(Nation.ENGLAND, UnitType.ARMY, Province.Spa, OrderType.HOLD, null, null);
                require(Orders.locateCorresponding(support, List.of(move, support, hold)) == move,
                        "Forward support lookup");
                require(Orders.locateCorresponding(move, true, List.of(move, support, hold)).contains(support),
                        "Reverse support lookup");
                var result = judge(mode, List.of(move, support, hold));
                require(verdict(result, Province.MAO) && verdict(result, Province.Por)
                        && !verdict(result, Province.Spa), "Supported Fleet attack");

                var cut = judge(mode, List.of(
                        work(Nation.FRANCE, UnitType.FLEET, Province.LYO, OrderType.MOVE, Province.SpaSC, null),
                        work(Nation.FRANCE, UnitType.FLEET, Province.WES, OrderType.SUPPORT, Province.LYO, coast),
                        work(Nation.ENGLAND, UnitType.ARMY, Province.Spa, OrderType.HOLD, null, null),
                        work(Nation.ENGLAND, UnitType.FLEET, Province.Naf, OrderType.MOVE, Province.WES, null)));
                require(!verdict(cut, Province.LYO) && !verdict(cut, Province.WES)
                        && verdict(cut, Province.Spa), "Cut support still fails");

                var defended = judge(mode, List.of(
                        work(Nation.FRANCE, UnitType.FLEET, Province.SpaSC, OrderType.HOLD, null, null),
                        work(Nation.FRANCE, UnitType.ARMY, Province.Gas, OrderType.SUPPORT, coast, null),
                        work(Nation.ENGLAND, UnitType.FLEET, Province.MAO, OrderType.MOVE, Province.SpaNC, null)));
                require(verdict(defended, Province.SpaSC) && verdict(defended, Province.Gas)
                        && !verdict(defended, Province.MAO), "Hold support across coast references");
            }
        }
        var support = work(Nation.FRANCE, UnitType.ARMY, Province.Gas, OrderType.SUPPORT, Province.Spa, null);
        var other = work(Nation.FRANCE, UnitType.FLEET, Province.SpaSC, OrderType.SUPPORT, Province.MAO, null);
        require(Orders.locateCorresponding(support, List.of(support, other)) == other,
                "Hold support can support a supporting unit");
        other.orderType = OrderType.MOVE;
        other.pos1 = Province.MAO;
        require(Orders.locateCorresponding(support, List.of(support, other)) == null,
                "Hold support cannot support a moving unit");
    }

    private static void inference() {
        UnitId fleet = unit(UnitType.FLEET);
        for (Province[] example : List.of(
                new Province[]{Province.Nwy, Province.Stp, Province.StpNC},
                new Province[]{Province.BOT, Province.Stp, Province.StpSC},
                new Province[]{Province.LYO, Province.Spa, Province.SpaSC},
                new Province[]{Province.Gre, Province.Bul, Province.BulSC},
                new Province[]{Province.MAO, Province.Spa, Province.Spa},
                new Province[]{Province.Con, Province.Bul, Province.Bul},
                new Province[]{Province.ENG, Province.Stp, Province.Stp},
                new Province[]{Province.Nwy, Province.StpSC, Province.StpSC})) {
            BoardState board = new BoardState(Map.of(fleet, example[0]), Map.of());
            DiploBNOrder source = DiploBNOrder.from(Order.move(fleet, example[1]));
            require(new DiploBNOrderTranslator().translate(source, board).target() == example[2],
                    "Immutable import inference");
            require(new DiploBNAdjudicationOrderTranslator(true).translate(source, board).target() == example[2],
                    "Adjudication import inference");
            require(new DiploBNOrderTranslator(false).translate(source, board).target() == example[1],
                    "Strict immutable import");
            require(new DiploBNAdjudicationOrderTranslator(false).translate(source, board).target() == example[1],
                    "Strict adjudication import");
            require(source.target() == example[1], "Source order unchanged");
        }
    }

    private static void export() throws Exception {
        String payload = """
                {"GamePhases":[{"Phase":19011,"Status":"Completed",
                 "SupplyCenters":{},"Units":{"England":[["F","Nwy"]],
                 "France":[["A","Por"],["F","MAO"]]},
                 "Orders":{"England":[["Nwy",["m","Stp"],"f"]],
                 "France":[["Por",["sm","MAO","Spa"],"s"],
                 ["MAO",["m",["Spa","sc"]],"f"]]}}]}
                """;
        var phase = new DiploBNParser().parse(payload).phases().getFirst();
        require(phase.sourceMovementOrders().getFirst().target() == Province.Stp
                && phase.movementOrders().getFirst().target() == Province.StpNC, "Raw and translated orders");
        var exporter = new DiploBNTrainingExporter(7);
        StringBuilder examples = new StringBuilder(), rejected = new StringBuilder();
        var report = exporter.writeDetailed("DIPLOBN", "42", payload, SourceFingerprints.sha256Hex(payload),
                examples, rejected);
        require(report.counts().equals(new DiploBNTrainingExporter.Counts(2, 0)), "Legal failed orders exported");
        require(examples.toString().contains("FLEET Nwy MOVE StpNC -")
                && examples.toString().contains("ARMY Por SUPPORT MAO Spa"), "Canonical exported labels");
        require(examples.toString().contains(MovementEncoding.SCHEMA)
                && examples.toString().contains(DiploBNTrainingExporter.SCHEMA), "Versioned export");
        for (String invalid : List.of(
                payload.replace("[\"Spa\",\"sc\"]", "\"Spa\""),
                payload.replace("[\"m\",\"Stp\"]", "[\"m\",[\"Stp\",\"sc\"]]"))) {
            report = exporter.writeDetailed("DIPLOBN", "42", invalid, SourceFingerprints.sha256Hex(invalid),
                    new StringBuilder(), new StringBuilder());
            require(report.counts().examples() == 1
                            && report.rejectionReasons().equals(Map.of("INVALID_ACTION", 1L)),
                    "Ambiguous or explicit illegal moves remain rejected");
        }
    }

    private static void sourceGame(Path path) throws Exception {
        String key = path.getFileName().toString().replace("diplobn-", "").replace(".json", "");
        long[] expected = switch (key) {
            case "14031" -> new long[]{103, 9};
            case "16192" -> new long[]{94, 7};
            case "10667" -> new long[]{132, 15};
            default -> throw new IllegalArgumentException("Unknown regression game: " + path);
        };
        String payload = Files.readString(path);
        var report = new DiploBNTrainingExporter(7).writeDetailed("DIPLOBN", key, payload,
                SourceFingerprints.sha256Hex(payload), new StringBuilder(), new StringBuilder());
        require(report.counts().equals(new DiploBNTrainingExporter.Counts(expected[0], expected[1])),
                "Source export counts for " + key);
        Map<Integer, Set<Province>> affected = switch (key) {
            case "14031" -> Map.of(
                    19022, Set.of(Province.Ser), 19031, Set.of(Province.Ser, Province.Gre, Province.AEG, Province.EAS),
                    19052, Set.of(Province.Por, Province.Rum), 19062, Set.of(Province.Por),
                    19072, Set.of(Province.AEG, Province.Gre, Province.Ser), 19081, Set.of(Province.Gas, Province.WES));
            case "16192" -> Map.of(19021, Set.of(Province.Nwy), 19062, Set.of(Province.Nwy, Province.BAR),
                    19071, Set.of(Province.Nwy), 19072, Set.of(Province.Nwy));
            default -> Map.of();
        };
        for (DiploBNPhase phase : new DiploBNParser().parse(payload).phases()) {
            if (!affected.containsKey(phase.sourcePhase()))
                continue;
            var adjudicator = new DiploBNAdjudicator(phase);
            adjudicator.judge();
            for (Province origin : affected.get(phase.sourcePhase())) {
                var source = phase.resolutions().stream()
                        .filter(r -> Province.equalsIgnoreCoast(r.issuingProvince(), origin))
                        .findFirst().orElseThrow();
                require(verdict(adjudicator.getOrders(), origin) == source.successful(),
                        "Source resolution at " + phase.sourcePhase() + " " + origin);
            }
        }
        System.out.println("Game " + key + ": " + report.counts());
    }

    private static UnitId unit(UnitType type) {
        return UnitId.newlyBuilt(1901, Nation.FRANCE, type, type == UnitType.ARMY ? Province.Par : Province.Bre);
    }

    private static MovementEncoding encoding(Map<UnitId, Province> locations) {
        return new MovementEncoding(new GameState_Movement(new GameMoment(1902, GamePhase.SPRING_MOVEMENT),
                new BoardState(locations, Map.of())), Nation.FRANCE);
    }

    private static adjudication.Order work(Nation nation, UnitType type, Province origin,
                                           OrderType order, Province target, Province auxiliary) {
        return new adjudication.Order(nation, type, origin, order, target, auxiliary);
    }

    private static Collection<adjudication.Order> judge(int mode, List<adjudication.Order> orders) {
        Adjudicator judge = switch (mode) {
            case 0 -> new Judge(orders);
            case 1 -> new Justice(orders);
            default -> new SzykmanJustice(orders);
        };
        judge.judge();
        return judge.getOrders();
    }

    private static boolean verdict(Collection<adjudication.Order> orders, Province origin) {
        return orders.stream().filter(order -> order.pos0 == origin).findFirst().orElseThrow().verdict;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }
}