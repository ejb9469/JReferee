package testing.selfcheck;

import analysis.tactics.*;
import analysis.tactics.detective.*;
import domain.Geography;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import phase.Order;
import phase.UnitId;

import java.nio.charset.StandardCharsets;
import java.util.*;


/**
 * Exact-set checks of convoy correspondence and candidate routes, without
 * adjudication. The independent bounded oracle tries permutations of all
 * subsets of selected sea provinces, not the detectives' graph traversal.
 */
public final class ConvoyConstructionSelfCheck {

    // Constants \\

    private static final GameMoment MOMENT = new GameMoment(1902, GamePhase.SPRING_MOVEMENT);
    private static final List<Detective> DETECTIVES = List.of(
            new ConvoyedMoveDetective(), new MultiFleetConvoyDetective(),
            new MultiRouteConvoyDetective(), new ForeignConvoyDetective(),
            new MultinationalConvoyDetective(), new SupportedConvoyLandingDetective(),
            new ConvoySwapDetective(), new AdjacentProvinceConvoyDetective());


    // Check state \\

    private static int checks;
    private static int oracleCases;


    // Construction \\

    private ConvoyConstructionSelfCheck() {  }


    // Application entry point \\

    public static void main(String[] args) {

        checks = 0;
        oracleCases = 0;
        correspondence();
        unknownAndContradictory();
        routesAndOverlap();
        splitCoastsAndActualLocations();
        reciprocalExchange();
        boundedOracle();
        fullSeaGraph();
        System.out.printf("Convoy construction self-check passed: %d checks; %d exhaustive oracle cases.%n",
                checks, oracleCases);

    }


    // Matching-only contracts \\

    private static void correspondence() {

        UnitId army = unit(Nation.ENGLAND, UnitType.ARMY, Province.Par);
        UnitId nth = unit(Nation.ENGLAND, UnitType.FLEET, Province.NTH);
        UnitId eng = unit(Nation.FRANCE, UnitType.FLEET, Province.ENG);
        UnitId bla = unit(Nation.GERMANY, UnitType.FLEET, Province.BLA);
        UnitId pic = unit(Nation.FRANCE, UnitType.ARMY, Province.Pic);
        Map<UnitId, Province> locations = locations(
                army, Province.Lon, nth, Province.NTH, eng, Province.ENG,
                bla, Province.BLA, pic, Province.Pic);
        TacticalContext context = context(locations, Order.move(army, Province.Bel),
                Order.convoy(nth, Province.Lon, Province.Bel),
                Order.convoy(eng, Province.Lon, Province.Bel),
                Order.convoy(bla, Province.Lon, Province.Bel),
                Order.supportMove(pic, Province.Lon, Province.Bel));
        Map<TacticKind, List<Expected>> expected = new EnumMap<>(TacticKind.class);
        expected.put(TacticKind.CONVOYED_MOVE, List.of(move(Province.Bel, army, nth, eng, bla)));
        expected.put(TacticKind.MULTI_FLEET_CONVOY, List.of(move(Province.Bel, army, nth, eng, bla)));
        expected.put(TacticKind.MULTINATIONAL_CONVOY, List.of(move(Province.Bel, army, nth, eng, bla)));
        expected.put(TacticKind.MULTI_ROUTE_CONVOY, List.of(move(Province.Bel, army, nth, eng)));
        expected.put(TacticKind.FOREIGN_CONVOY,
                List.of(move(Province.Bel, army, eng), move(Province.Bel, army, bla)));
        expected.put(TacticKind.SUPPORTED_CONVOY_LANDING,
                List.of(landing(Province.Bel, army, pic, nth, eng, bla)));
        expect(context, expected);

        // Geographic illegality is not silently added to correspondence definitions.
        UnitId inland = unit(Nation.ENGLAND, UnitType.ARMY, Province.Mun);
        UnitId coastal = unit(Nation.FRANCE, UnitType.FLEET, Province.Bre);
        TacticalContext impossible = context(locations(
                        inland, Province.Mun, coastal, Province.Bre, pic, Province.Pic),
                Order.move(inland, Province.Par),
                Order.convoy(coastal, Province.Mun, Province.Par),
                Order.supportMove(pic, Province.Mun, Province.Par));
        expect(impossible, Map.of(
                TacticKind.CONVOYED_MOVE, List.of(move(Province.Par, inland, coastal)),
                TacticKind.FOREIGN_CONVOY, List.of(move(Province.Par, inland, coastal)),
                TacticKind.SUPPORTED_CONVOY_LANDING, List.of(landing(Province.Par, inland, pic, coastal))));

        TacticalContext wrongSupport = context(locations, Order.move(army, Province.Bel),
                Order.convoy(nth, Province.Lon, Province.Bel),
                Order.supportMove(pic, Province.Lon, Province.Hol));
        expect(wrongSupport, Map.of(TacticKind.CONVOYED_MOVE,
                List.of(move(Province.Bel, army, nth))));
        TacticalContext holdSupport = context(locations, Order.move(army, Province.Bel),
                Order.convoy(nth, Province.Lon, Province.Bel),
                Order.supportHold(pic, Province.Lon));
        expect(holdSupport, Map.of(TacticKind.CONVOYED_MOVE,
                List.of(move(Province.Bel, army, nth))));
        TacticalContext mismatchedRecipient = context(locations, Order.move(army, Province.Bel),
                Order.convoy(nth, Province.Lon, Province.Bel),
                Order.supportMove(pic, Province.Par, Province.Bel));
        expect(mismatchedRecipient, Map.of(TacticKind.CONVOYED_MOVE,
                List.of(move(Province.Bel, army, nth))));
        UnitId hol = unit(Nation.GERMANY, UnitType.ARMY, Province.Hol);
        TacticalContext twoSupporters = context(locations(
                        army, Province.Lon, nth, Province.NTH, pic, Province.Pic, hol, Province.Hol),
                Order.move(army, Province.Bel), Order.convoy(nth, Province.Lon, Province.Bel),
                Order.supportMove(pic, Province.Lon, Province.Bel),
                Order.supportMove(hol, Province.Lon, Province.Bel));
        expect(twoSupporters, Map.of(
                TacticKind.CONVOYED_MOVE, List.of(move(Province.Bel, army, nth)),
                TacticKind.SUPPORTED_CONVOY_LANDING, List.of(
                        landing(Province.Bel, army, pic, nth),
                        landing(Province.Bel, army, hol, nth))));
        TacticalContext unsupportedUnknown = context(locations(
                        army, Province.Lon, nth, Province.NTH, pic, Province.Pic),
                Order.convoy(nth, Province.Lon, Province.Bel),
                Order.supportMove(pic, Province.Lon, Province.Bel));
        expect(unsupportedUnknown, Map.of());

        // Same foreign power is foreign cooperation, not multinational fleet cooperation.
        UnitId engGerman = unit(Nation.GERMANY, UnitType.FLEET, Province.ENG);
        TacticalContext oneFleetPower = context(locations(
                        army, Province.Lon, engGerman, Province.ENG, bla, Province.BLA),
                Order.move(army, Province.Bel),
                Order.convoy(engGerman, Province.Lon, Province.Bel),
                Order.convoy(bla, Province.Lon, Province.Bel));
        expect(oneFleetPower, Map.of(
                TacticKind.CONVOYED_MOVE, List.of(move(Province.Bel, army, engGerman, bla)),
                TacticKind.MULTI_FLEET_CONVOY, List.of(move(Province.Bel, army, engGerman, bla)),
                TacticKind.FOREIGN_CONVOY,
                List.of(move(Province.Bel, army, engGerman), move(Province.Bel, army, bla))));
        immutable(context);

    }


    // Unknown and contradictory evidence \\

    private static void unknownAndContradictory() {

        UnitId army = unit(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        UnitId fleet = unit(Nation.FRANCE, UnitType.FLEET, Province.ENG);
        UnitId fake = unit(Nation.GERMANY, UnitType.ARMY, Province.NTH);
        Map<UnitId, Province> locations = locations(
                army, Province.Lon, fleet, Province.ENG, fake, Province.NTH);
        for (Order armyOrder : Arrays.asList(null, Order.hold(army),
                Order.move(army, Province.Hol),
                Order.supportMove(army, Province.ENG, Province.Bel))) {
            List<Order> orders = new ArrayList<>();
            orders.add(Order.convoy(fleet, Province.Lon, Province.Bel));
            // Malformed issuer type is possible through Order's structural constructor.
            orders.add(new Order(fake, OrderType.CONVOY, Province.Lon, Province.Bel));
            if (armyOrder != null)
                orders.add(armyOrder);
            expect(context(locations, orders.toArray(Order[]::new)),
                    Map.of(TacticKind.FOREIGN_CONVOY, List.of(move(Province.Bel, army, fleet))));
        }
        expect(context(locations, Order.move(army, Province.Bel)), Map.of());
        expect(context(locations, Order.move(army, Province.Bel),
                Order.convoy(fleet, Province.Par, Province.Bel)), Map.of());
        expect(context(locations, Order.convoy(fleet, Province.NTH, Province.Bel)), Map.of(
                TacticKind.FOREIGN_CONVOY, List.of(move(Province.Bel, fake, fleet)),
                TacticKind.ADJACENT_PROVINCE_CONVOY, List.of(move(Province.Bel, fake, fleet))));
        UnitId recipientFleet = unit(Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        expect(context(locations(recipientFleet, Province.Lon, fleet, Province.ENG),
                Order.move(recipientFleet, Province.Bel),
                Order.convoy(fleet, Province.Lon, Province.Bel)), Map.of());
        // Exact coincidence of origin and destination is still correspondence, not a route.
        expect(context(locations, Order.move(army, Province.Lon),
                Order.convoy(fleet, Province.Lon, Province.Lon)), Map.of(
                TacticKind.CONVOYED_MOVE, List.of(move(Province.Lon, army, fleet)),
                TacticKind.FOREIGN_CONVOY, List.of(move(Province.Lon, army, fleet))));

        for (Order armyOrder : Arrays.asList(null, Order.hold(army),
                Order.move(army, Province.Bel))) {

            List<Order> orders = new ArrayList<>(List.of(
                    Order.convoy(fleet, Province.Lon, Province.Wal)));

            if (armyOrder != null)
                orders.add(armyOrder);

            expect(context(locations, orders.toArray(Order[]::new)), Map.of(
                    TacticKind.FOREIGN_CONVOY, List.of(move(Province.Wal, army, fleet)),
                    TacticKind.ADJACENT_PROVINCE_CONVOY, List.of(move(Province.Wal, army, fleet))));

        }

    }


    // Candidate routes and overlap \\

    private static void routesAndOverlap() {

        UnitId army = unit(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        UnitId eng = unit(Nation.ENGLAND, UnitType.FLEET, Province.ENG);
        UnitId mao = unit(Nation.ENGLAND, UnitType.FLEET, Province.MAO);
        UnitId wes = unit(Nation.ENGLAND, UnitType.FLEET, Province.WES);
        UnitId nth = unit(Nation.ENGLAND, UnitType.FLEET, Province.NTH);
        Map<UnitId, Province> locations = locations(
                army, Province.Lon, eng, Province.ENG, mao, Province.MAO,
                wes, Province.WES, nth, Province.NTH);
        for (Order bridge : Arrays.asList(null, Order.hold(mao),
                Order.convoy(mao, Province.Lon, Province.Por))) {
            List<Order> orders = new ArrayList<>(List.of(
                    Order.move(army, Province.Tun),
                    Order.convoy(eng, Province.Lon, Province.Tun),
                    Order.convoy(wes, Province.Lon, Province.Tun)));
            if (bridge != null)
                orders.add(bridge);
            TacticalContext context = context(locations, orders.toArray(Order[]::new));
            expect(context, Map.of(
                    TacticKind.CONVOYED_MOVE, List.of(move(Province.Tun, army, eng, wes)),
                    TacticKind.MULTI_FLEET_CONVOY, List.of(move(Province.Tun, army, eng, wes))));
            expectSingle(new IncompleteConvoyChainDetective(), context,
                    List.of(move(Province.Tun, army, eng, wes)));

        }
        TacticalContext singleRoute = context(locations, Order.move(army, Province.Tun),
                Order.convoy(eng, Province.Lon, Province.Tun),
                Order.convoy(mao, Province.Lon, Province.Tun),
                Order.convoy(wes, Province.Lon, Province.Tun));
        expect(singleRoute, Map.of(
                TacticKind.CONVOYED_MOVE, List.of(move(Province.Tun, army, eng, mao, wes)),
                TacticKind.MULTI_FLEET_CONVOY, List.of(move(Province.Tun, army, eng, mao, wes))));
        expectSingle(new IncompleteConvoyChainDetective(), singleRoute, List.of());
        TacticalContext overlapping = context(locations, Order.move(army, Province.Tun),
                Order.convoy(eng, Province.Lon, Province.Tun),
                Order.convoy(mao, Province.Lon, Province.Tun),
                Order.convoy(wes, Province.Lon, Province.Tun),
                Order.convoy(nth, Province.Lon, Province.Tun));
        expect(overlapping, Map.of(
                TacticKind.CONVOYED_MOVE, List.of(move(Province.Tun, army, eng, mao, wes, nth)),
                TacticKind.MULTI_FLEET_CONVOY, List.of(move(Province.Tun, army, eng, mao, wes, nth)),
                TacticKind.MULTI_ROUTE_CONVOY, List.of(move(Province.Tun, army, eng, mao, wes, nth))));
        TacticalContext adjacent = context(locations, Order.move(army, Province.Wal),
                Order.convoy(eng, Province.Lon, Province.Wal));
        expect(adjacent, Map.of(
                TacticKind.CONVOYED_MOVE, List.of(move(Province.Wal, army, eng)),
                TacticKind.ADJACENT_PROVINCE_CONVOY, List.of(move(Province.Wal, army, eng))));
        TacticalContext adjacentReferences = context(locations, Order.move(army, Province.Wal),
                Order.convoy(eng, Province.Lon, Province.Wal),
                Order.convoy(wes, Province.Lon, Province.Wal));
        expect(adjacentReferences, Map.of(
                TacticKind.CONVOYED_MOVE, List.of(move(Province.Wal, army, eng, wes)),
                TacticKind.MULTI_FLEET_CONVOY, List.of(move(Province.Wal, army, eng, wes)),
                TacticKind.ADJACENT_PROVINCE_CONVOY,
                List.of(move(Province.Wal, army, eng), move(Province.Wal, army, wes))));
        TacticalContext adjacentDisconnected = context(locations, Order.move(army, Province.Wal),
                Order.convoy(wes, Province.Lon, Province.Wal));
        expect(adjacentDisconnected, Map.of(
                TacticKind.CONVOYED_MOVE, List.of(move(Province.Wal, army, wes)),
                TacticKind.ADJACENT_PROVINCE_CONVOY, List.of(move(Province.Wal, army, wes))));
        // A coastal fleet is not a bridge between seas.
        UnitId bre = unit(Nation.ENGLAND, UnitType.FLEET, Province.Bre);
        TacticalContext coastalBridge = context(locations(
                        army, Province.Lon, eng, Province.ENG, bre, Province.Bre, wes, Province.WES),
                Order.move(army, Province.Tun),
                Order.convoy(eng, Province.Lon, Province.Tun),
                Order.convoy(bre, Province.Lon, Province.Tun),
                Order.convoy(wes, Province.Lon, Province.Tun));
        expect(coastalBridge, Map.of(
                TacticKind.CONVOYED_MOVE, List.of(move(Province.Tun, army, eng, bre, wes)),
                TacticKind.MULTI_FLEET_CONVOY, List.of(move(Province.Tun, army, eng, bre, wes))));
        expectSingle(new IncompleteConvoyChainDetective(), coastalBridge,
                List.of(move(Province.Tun, army, eng, wes)));
    }


    // Exact coasts and persistent identities \\

    private static void splitCoastsAndActualLocations() {

        UnitId army = unit(Nation.TURKEY, UnitType.ARMY, Province.Par);
        UnitId fleet = unit(Nation.TURKEY, UnitType.FLEET, Province.NTH);
        UnitId support = unit(Nation.TURKEY, UnitType.ARMY, Province.Con);
        TacticalContext coast = context(locations(
                        army, Province.Ank, fleet, Province.BLA, support, Province.Con),
                Order.move(army, Province.BulSC),
                Order.convoy(fleet, Province.Ank, Province.BulEC),
                Order.supportMove(support, Province.Ank, Province.Bul));
        expect(coast, Map.of(
                TacticKind.CONVOYED_MOVE, List.of(move(Province.Bul, army, fleet)),
                TacticKind.SUPPORTED_CONVOY_LANDING, List.of(landing(Province.Bul, army, support, fleet))));
        expectSingle(new IncompleteConvoyChainDetective(), coast, List.of());
        require(coast.board().locationOf(fleet) == Province.BLA, "Actual fleet location changed");
        require(coast.orderOf(army).orElseThrow().order().target() == Province.BulSC,
                "Exact army destination coast lost");
        require(coast.orderOf(fleet).orElseThrow().order().auxiliaryTarget() == Province.BulEC,
                "Exact convoy destination coast lost");

        TacticalContext originCoast = context(locations(army, Province.SpaSC, fleet, Province.MAO),
                Order.move(army, Province.Por),
                Order.convoy(fleet, Province.SpaNC, Province.Por));
        expect(originCoast, Map.of(
                TacticKind.CONVOYED_MOVE, List.of(move(Province.Por, army, fleet)),
                TacticKind.ADJACENT_PROVINCE_CONVOY, List.of(move(Province.Por, army, fleet))));
        require(originCoast.board().locationOf(army) == Province.SpaSC, "Exact board coast lost");
        require(originCoast.orderOf(fleet).orElseThrow().order().target() == Province.SpaNC,
                "Exact convoy source coast lost");
        expect(context(locations(army, Province.SpaSC, fleet, Province.MAO),
                Order.move(army, Province.Por),
                Order.convoy(fleet, Province.Par, Province.Por)), Map.of());

        // A fleet identity created at sea but now on a coast cannot be a route node.
        TacticalContext relocated = context(locations(army, Province.Lon, fleet, Province.Bre),
                Order.move(army, Province.Wal), Order.convoy(fleet, Province.Lon, Province.Wal));
        expect(relocated, Map.of(TacticKind.CONVOYED_MOVE,
                List.of(move(Province.Wal, army, fleet)),
                TacticKind.ADJACENT_PROVINCE_CONVOY, List.of(move(Province.Wal, army, fleet))));

        // Adjacency describes the convoy reference even with invalid convoy geography.
        TacticalContext inland = context(locations(army, Province.Mun, fleet, Province.Bre),
                Order.convoy(fleet, Province.Mun, Province.Bur));
        expect(inland, Map.of(TacticKind.ADJACENT_PROVINCE_CONVOY,
                List.of(move(Province.Bur, army, fleet))));

    }


    // Reciprocal exchange \\

    private static void reciprocalExchange() {

        UnitId a = unit(Nation.ENGLAND, UnitType.ARMY, Province.Par);
        UnitId b = unit(Nation.FRANCE, UnitType.ARMY, Province.Mun);
        UnitId nth = unit(Nation.ENGLAND, UnitType.FLEET, Province.NTH);
        UnitId eng = unit(Nation.FRANCE, UnitType.FLEET, Province.ENG);
        UnitId bla = unit(Nation.FRANCE, UnitType.FLEET, Province.BLA);
        Map<UnitId, Province> locations = locations(
                a, Province.Lon, b, Province.Bel, nth, Province.NTH, eng, Province.ENG, bla, Province.BLA);
        TacticalContext swap = context(locations, Order.move(a, Province.Bel), Order.move(b, Province.Lon),
                Order.convoy(nth, Province.Lon, Province.Bel),
                Order.convoy(eng, Province.Bel, Province.Lon));
        Set<TacticMatch.Participant> pair = new HashSet<>(move(Province.Bel, a, nth).participants());
        pair.addAll(move(Province.Lon, b, eng).participants());
        expect(swap, Map.of(
                TacticKind.CONVOYED_MOVE,
                List.of(move(Province.Bel, a, nth), move(Province.Lon, b, eng)),
                TacticKind.CONVOY_SWAP, List.of(new Expected(Province.Bel, Set.copyOf(pair)))));
        TacticalContext broken = context(locations, Order.move(a, Province.Bel), Order.move(b, Province.Lon),
                Order.convoy(nth, Province.Lon, Province.Bel),
                Order.convoy(bla, Province.Bel, Province.Lon));
        expect(broken, Map.of(TacticKind.CONVOYED_MOVE,
                List.of(move(Province.Bel, a, nth), move(Province.Lon, b, bla))));
        TacticalContext unknown = context(locations, Order.move(a, Province.Bel),
                Order.convoy(nth, Province.Lon, Province.Bel),
                Order.convoy(eng, Province.Bel, Province.Lon));
        expect(unknown, Map.of(TacticKind.CONVOYED_MOVE, List.of(move(Province.Bel, a, nth))));
        TacticalContext nonreciprocal = context(locations, Order.move(a, Province.Bel), Order.move(b, Province.Hol),
                Order.convoy(nth, Province.Lon, Province.Bel),
                Order.convoy(eng, Province.Bel, Province.Lon));
        expect(nonreciprocal, Map.of(TacticKind.CONVOYED_MOVE, List.of(move(Province.Bel, a, nth))));

    }


    // Exhaustive bounded reference domains \\

    private static void boundedOracle() {

        Province[][] domains = {
                {Province.ENG, Province.NTH, Province.MAO, Province.IRI, Province.NAO, Province.NWG},
                {Province.BLA, Province.AEG, Province.ION, Province.EAS, Province.ADR, Province.TYS},
                {Province.BAR, Province.NWG, Province.NTH, Province.BAL, Province.BOT, Province.SKA}
        };
        Province[][] endpoints = {
                {Province.Lon, Province.Bel, Province.Por, Province.Wal},
                {Province.Ank, Province.Bul, Province.Tun, Province.Con},
                {Province.Stp, Province.Nwy, Province.Swe, Province.Den}
        };
        for (int domain = 0; domain < domains.length; domain++) {
            Province[] seas = domains[domain];
            for (Province origin : endpoints[domain])
                for (Province destination : endpoints[domain]) {
                    if (origin == destination)
                        continue;
                    for (int subset = 0; subset < 1 << seas.length; subset++) {
                        List<Province> selected = new ArrayList<>();
                        for (int bit = 0; bit < seas.length; bit++)
                            if ((subset & 1 << bit) != 0)
                                selected.add(seas[bit]);
                        verifyOracle(origin, destination, selected);
                        oracleCases++;
                    }
                }
        }

    }

    private static void verifyOracle(
            Province origin,
            Province destination,
            List<Province> seas
    ) {

        UnitId army = unit(Nation.ENGLAND, UnitType.ARMY, origin);
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        locations.put(army, origin);
        List<Order> orders = new ArrayList<>(List.of(Order.move(army, destination)));
        Map<Province, UnitId> fleetAt = new EnumMap<>(Province.class);
        for (Province sea : seas) {
            UnitId fleet = unit(Nation.ENGLAND, UnitType.FLEET, sea);
            fleetAt.put(sea, fleet);
            locations.put(fleet, sea);
            orders.add(Order.convoy(fleet, origin, destination));
        }
        TacticalContext context = context(locations, orders.toArray(Order[]::new));
        Oracle oracle = reference(origin, destination, seas);
        List<UnitId> all = new ArrayList<>(fleetAt.values());
        List<UnitId> routeFleets = oracle.used().stream().map(fleetAt::get).toList();
        Map<TacticKind, List<Expected>> expected = new EnumMap<>(TacticKind.class);
        if (!all.isEmpty())
            expected.put(TacticKind.CONVOYED_MOVE, List.of(move(destination, army, all.toArray(UnitId[]::new))));
        if (all.size() >= 2)
            expected.put(TacticKind.MULTI_FLEET_CONVOY, List.of(move(destination, army, all.toArray(UnitId[]::new))));
        if (oracle.countBound() >= 2)
            expected.put(TacticKind.MULTI_ROUTE_CONVOY,
                    List.of(move(destination, army, routeFleets.toArray(UnitId[]::new))));
        if (!all.isEmpty() && origin.isAdjacentTo(destination))
            expected.put(TacticKind.ADJACENT_PROVINCE_CONVOY,
                    all.stream().map(fleet -> move(destination, army, fleet)).toList());
        expect(context, expected);
        expectSingle(new IncompleteConvoyChainDetective(), context,
                !all.isEmpty() && oracle.countBound() == 0
                        ? List.of(move(destination, army, all.toArray(UnitId[]::new))) : List.of());

    }


    // Independent subset-permutation oracle \\

    private static Oracle reference(
            Province origin,
            Province destination,
            List<Province> available
    ) {

        int[] count = {0};
        Set<Province> used = EnumSet.noneOf(Province.class);
        for (int subset = 1; subset < 1 << available.size(); subset++) {
            List<Province> permutation = new ArrayList<>();
            for (int bit = 0; bit < available.size(); bit++)
                if ((subset & 1 << bit) != 0)
                    permutation.add(available.get(bit));
            permutations(permutation, 0, origin, destination, count, used);
        }
        return new Oracle(count[0], used);

    }

    private static void permutations(
            List<Province> path,
            int index,
            Province origin,
            Province destination,
            int[] count,
            Set<Province> used
    ) {

        if (index == path.size()) {
            if (!referenceTouches(path.getFirst(), origin)
                    || !referenceTouches(path.getLast(), destination))
                return;
            for (int position = 1; position < path.size(); position++)
                if (!path.get(position - 1).isAdjacentTo(path.get(position)))
                    return;
            count[0] = Math.min(2, count[0] + 1);
            used.addAll(path);
            return;
        }
        for (int next = index; next < path.size(); next++) {
            Collections.swap(path, index, next);
            permutations(path, index + 1, origin, destination, count, used);
            Collections.swap(path, index, next);
        }

    }

    private static boolean referenceTouches(Province sea, Province territory) {

        for (Province endpoint : Province.values())
            if (Province.canonical(endpoint) == Province.canonical(territory)
                    && sea.isAdjacentTo(endpoint))
                return true;
        return false;

    }


    // Complete standard sea graph \\

    private static void fullSeaGraph() {

        UnitId army = unit(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        locations.put(army, Province.Lon);
        List<Order> orders = new ArrayList<>(List.of(Order.move(army, Province.Tun)));
        List<UnitId> fleets = new ArrayList<>();
        for (Province province : Province.values()) {
            if (province.geography != Geography.WATER)
                continue;
            UnitId fleet = unit(Nation.ENGLAND, UnitType.FLEET, province);
            fleets.add(fleet);
            locations.put(fleet, province);
            orders.add(Order.convoy(fleet, Province.Lon, Province.Tun));
        }
        TacticalContext context = context(locations, orders.toArray(Order[]::new));
        List<TacticMatch> routes = new MultiRouteConvoyDetective().investigate(context);
        require(routes.size() == 1, "Full standard-map graph did not terminate with a route match");
        // Isolated basins, dead ends, and a cycle attached only at ION are
        // not part of any simple London-to-Tunis sea path.
        Set<UnitId> expected = new HashSet<>();
        for (Province sea : List.of(Province.ENG, Province.IRI, Province.MAO,
                Province.NAO, Province.NTH, Province.NWG, Province.WES,
                Province.TYS, Province.LYO, Province.ION))
            expected.add(unit(Nation.ENGLAND, UnitType.FLEET, sea));
        require(new HashSet<>(routes.getFirst().units(TacticMatch.Role.CONVOYING_UNIT)).equals(expected),
                "Full-map path union contains dead ends or omits viable fleets");
        System.out.printf("Full-map candidate graph: %d sea nodes; %d participating route nodes.%n",
                fleets.size(), expected.size());

    }


    // Immutability and detective reuse \\

    private static void immutable(TacticalContext context) {

        for (Detective detective : DETECTIVES) {
            List<TacticMatch> findings = detective.investigate(context);
            require(findings.equals(detective.investigate(context)), "Reused detective is stateful");
            require(detective.investigate(context(Map.of())).isEmpty(),
                    "Reused detective leaks a previous context");
            require(findings.equals(detective.investigate(context)),
                    "Reused detective changes after examining an empty context");
            expectUnsupported(() -> findings.add(null));
            for (TacticMatch match : findings) {
                expectUnsupported(() -> match.participants().clear());
                expectUnsupported(() -> match.missingOrders().add(match.participants().getFirst().unit()));
                expectUnsupported(() -> match.units(TacticMatch.Role.CONVOYING_UNIT).clear());
            }
        }
        List<Map.Entry<UnitId, TacticalContext.KnownOrder>> reversed =
                new ArrayList<>(context.orders().entrySet());
        Collections.reverse(reversed);
        Map<UnitId, TacticalContext.KnownOrder> orders = new LinkedHashMap<>();
        for (Map.Entry<UnitId, TacticalContext.KnownOrder> entry : reversed)
            orders.put(entry.getKey(), entry.getValue());
        TacticalContext reordered = new TacticalContext(
                context.rulesetId(), MOMENT, context.board(), orders);
        orders.clear();
        for (Detective detective : DETECTIVES)
            require(detective.investigate(context).equals(detective.investigate(reordered)),
                    "Input map ordering or later mutation affects results");

    }


    // Exact occurrence assertions \\

    private static void expect(
            TacticalContext context,
            Map<TacticKind, List<Expected>> expected
    ) {

        for (Detective detective : DETECTIVES)
            expectSingle(detective, context, expected.getOrDefault(detective.kind(), List.of()));

    }

    private static void expectSingle(
            Detective detective,
            TacticalContext context,
            List<Expected> expected
    ) {

        List<TacticMatch> findings = detective.investigate(context);
        require(findings.size() == expected.size(),
                detective.kind() + " count: expected " + expected + ", got " + findings);
        Set<Expected> actual = new HashSet<>();
        for (TacticMatch match : findings) {
            require(match.kind() == detective.kind() && match.detectorVersion().equals(detective.version())
                            && match.context().equals(context) && match.completePattern()
                            && match.missingOrders().isEmpty(),
                    "Wrong identity/version/context/completeness");
            require(actual.add(new Expected(match.focus(), Set.copyOf(match.participants()))),
                    "Duplicate occurrence");
        }
        require(actual.equals(Set.copyOf(expected)),
                detective.kind() + " exact set differs: expected " + expected + ", got " + actual);

    }

    private static Expected move(Province focus, UnitId army, UnitId... fleets) {

        Set<TacticMatch.Participant> participants = new HashSet<>();
        participants.add(new TacticMatch.Participant(TacticMatch.Role.CONVOYED_ARMY, army));
        for (UnitId fleet : fleets)
            participants.add(new TacticMatch.Participant(TacticMatch.Role.CONVOYING_UNIT, fleet));
        return new Expected(Province.canonical(focus), Set.copyOf(participants));

    }

    private static Expected landing(
            Province focus,
            UnitId army,
            UnitId supporter,
            UnitId... fleets
    ) {

        Set<TacticMatch.Participant> participants = new HashSet<>(move(focus, army, fleets).participants());
        participants.add(new TacticMatch.Participant(TacticMatch.Role.SUPPORTED_UNIT, army));
        participants.add(new TacticMatch.Participant(TacticMatch.Role.SUPPORTER, supporter));
        return new Expected(Province.canonical(focus), Set.copyOf(participants));

    }


    // Context and unit fixtures \\

    private static TacticalContext context(
            Map<UnitId, Province> locations,
            Order... supplied
    ) {

        Map<UnitId, TacticalContext.KnownOrder> orders = new LinkedHashMap<>();
        for (Order order : supplied)
            orders.put(order.unit(), new TacticalContext.KnownOrder(
                    order, TacticalContext.Provenance.SUBMITTED));
        return new TacticalContext("convoy-selfcheck-v1", MOMENT,
                new BoardState(locations, Map.of()), orders);

    }

    private static Map<UnitId, Province> locations(Object... pairs) {

        Map<UnitId, Province> locations = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2)
            locations.put((UnitId) pairs[index], (Province) pairs[index + 1]);
        return locations;

    }

    private static UnitId unit(Nation owner, UnitType type, Province origin) {

        return new UnitId(UUID.nameUUIDFromBytes(
                (owner + ":" + type + ":" + origin).getBytes(StandardCharsets.UTF_8)),
                owner, type, origin);

    }


    // Check helpers \\

    private static void expectUnsupported(Runnable action) {

        try {
            action.run();
            throw new IllegalStateException("Mutable result");
        } catch (UnsupportedOperationException expected) {
            checks++;
        }

    }

    private static void require(boolean condition, String message) {

        checks++;
        if (!condition)
            throw new IllegalStateException(message);

    }


    // Expected evidence and oracle summaries \\

    private record Expected(Province focus, Set<TacticMatch.Participant> participants) { }
    private record Oracle(int countBound, Set<Province> used) { }
}
