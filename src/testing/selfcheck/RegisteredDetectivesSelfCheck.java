package testing.selfcheck;

import analysis.tactics.*;
import analysis.tactics.detective.*;
import domain.*;
import game.*;
import phase.Order;
import phase.UnitId;

import java.util.*;

/**
 * Bounded reference checks for the twelve original diagnostic/relationship
 * detectives beyond the four covered by DetectiveSelfCheck.
 *
 * <p>Reference predicates use submitted fields, board locations, and the public
 * standard map, never another detective or the adjudicator as an oracle.
 * They test structural contracts, not dynamic success or diplomatic intent.</p>
 */
public final class RegisteredDetectivesSelfCheck {
    private static int checks;
    private static final Map<String, Integer> cases = new LinkedHashMap<>();
    private static final GameMoment MOMENT = new GameMoment(1902, GamePhase.SPRING_MOVEMENT);

    private RegisteredDetectivesSelfCheck() { }

    public static void main(String[] args) {
        checks = 0;
        cases.clear();
        movementGraphs();
        supportReferencesAndGroups();
        staticValidity();
        convoyReferences();
        convoyRoutes();
        specialFixtures();
        require(new HashSet<>(new DetectiveAgency().kinds()).containsAll(
                        cases.keySet().stream().map(TacticKind::valueOf).toList()),
                "Regression detective unexpectedly missing from default constructor");
        System.out.println("Registered detectives self-check passed: " + checks
                + " checks; reference cases " + cases + ".");
    }

    private record Expected(Province focus, Set<TacticMatch.Participant> participants,
                            Set<UnitId> missing) { }

    private static Expected expected(Province focus, Set<UnitId> missing, Object... roles) {
        Set<TacticMatch.Participant> participants = new HashSet<>();
        for (int index = 0; index < roles.length; index += 2)
            participants.add(new TacticMatch.Participant(
                    (TacticMatch.Role) roles[index], (UnitId) roles[index + 1]));
        return new Expected(Province.canonical(focus), Set.copyOf(participants), missing);
    }

    private static void verify(Detective detective, TacticalContext context, Set<Expected> expected) {
        cases.merge(detective.kind().name(), 1, Integer::sum);
        Map<UnitId, TacticalContext.KnownOrder> before = new LinkedHashMap<>(context.orders());
        Map<UnitId, Province> locations = new LinkedHashMap<>(context.board().locations());
        List<TacticMatch> findings = detective.investigate(context);
        Set<Expected> actual = new HashSet<>();
        for (TacticMatch match : findings) {
            require(match.kind() == detective.kind()
                            && match.detectorVersion().equals(detective.version())
                            && match.context() == context, "Identity/context contract");
            require(match.completePattern() == match.missingOrders().isEmpty(), "Local completeness");
            require(actual.add(new Expected(match.focus(), Set.copyOf(match.participants()),
                    match.missingOrders())), "Duplicate occurrence");
        }
        require(actual.equals(expected), detective.kind() + ": expected " + expected + ", got " + actual);
        require(context.orders().equals(before) && context.board().locations().equals(locations),
                "Investigation mutated source evidence");
        require(detective.investigate(context).equals(findings), "Unstable reuse");
        TacticalContext empty = context(context.board());
        require(detective.investigate(empty).isEmpty(), "Retained findings across contexts");
        require(detective.investigate(context).equals(findings), "Reuse after empty investigation");
        rejectsMutation(() -> findings.clear());
        if (!findings.isEmpty()) {
            TacticMatch match = findings.getFirst();
            rejectsMutation(() -> match.participants().clear());
            rejectsMutation(() -> match.missingOrders().clear());
        }
    }

    private static UnitId unit(int identity, Nation owner, UnitType type) {
        // All creation origins deliberately differ from actual board locations.
        return new UnitId(new UUID(17, 100 - identity), owner, type, Province.Vie);
    }

    private static BoardState board(UnitId[] units, Province... locations) {
        Map<UnitId, Province> position = new LinkedHashMap<>();
        for (int index = units.length - 1; index >= 0; index--)
            position.put(units[index], locations[index]);
        return new BoardState(position, Map.of());
    }

    private static TacticalContext context(BoardState board, Order... orders) {
        Map<UnitId, TacticalContext.KnownOrder> evidence = new LinkedHashMap<>();
        for (int index = orders.length - 1; index >= 0; index--)
            if (orders[index] != null)
                evidence.put(orders[index].unit(), new TacticalContext.KnownOrder(
                        orders[index], TacticalContext.Provenance.SUBMITTED));
        return new TacticalContext("bounded-reference-v1", MOMENT, board, evidence);
    }

    private static Province location(TacticalContext context, UnitId unit) {
        return Province.canonical(context.board().locationOf(unit));
    }

    private static Order order(TacticalContext context, UnitId unit) {
        var evidence = context.orders().get(unit);
        return evidence == null ? null : evidence.order();
    }

    private static Order convoy(UnitId issuer, Province origin, Province destination) {
        // The public factory rejects armies; raw submissions must retain invalid issuers.
        return new Order(issuer, OrderType.CONVOY, origin, destination);
    }

    private static boolean stationary(Order order) {
        return order != null && (order.orderType() == OrderType.HOLD
                || order.orderType() == OrderType.SUPPORT || order.orderType() == OrderType.CONVOY);
    }

    private static UnitId resident(TacticalContext context, Province target) {
        return context.board().locations().entrySet().stream()
                .filter(entry -> Province.canonical(entry.getValue()) == Province.canonical(target))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /**
     * 2 owner assignments x 2 recipient types x 8^3 submitted-order choices.
     * Choices include UNKNOWN, HOLD, SUPPORT, CONVOY, all three occupied
     * destinations (including self), and an empty destination.
     */
    private static void movementGraphs() {
        Detective head = new HeadToHeadDetective();
        Detective circle = new CircularMovementDetective();
        Detective collision = new FriendlyOccupantCollisionDetective();
        Detective supportAttack = new AttackOnSupporterDetective();
        Detective convoyAttack = new AttackOnConvoyFleetDetective();
        for (boolean foreign : List.of(false, true)) {
            for (UnitType type : UnitType.values()) {
                UnitId[] units = {unit(0, Nation.FRANCE, UnitType.ARMY),
                        unit(1, foreign ? Nation.GERMANY : Nation.FRANCE, type),
                        unit(2, Nation.ITALY, UnitType.ARMY)};
                Province[] positions = {Province.Par, Province.Bur, Province.Mar};
                BoardState board = board(units, positions);
                for (int code = 0; code < 512; code++) {
                    int remaining = code;
                    Order[] orders = new Order[3];
                    for (int index = 0; index < 3; index++) {
                        int choice = remaining % 8;
                        remaining /= 8;
                        orders[index] = switch (choice) {
                            case 0 -> null;
                            case 1 -> Order.hold(units[index]);
                            case 2 -> Order.supportHold(units[index], Province.Mun);
                            case 3 -> convoy(units[index], Province.Lon, Province.Bel);
                            case 4, 5, 6 -> Order.move(units[index], positions[choice - 4]);
                            default -> Order.move(units[index], Province.Pic);
                        };
                    }
                    TacticalContext context = context(board, orders);
                    Set<Expected> heads = new HashSet<>();
                    Set<Expected> collisions = new HashSet<>();
                    Set<Expected> supportAttacks = new HashSet<>();
                    Set<Expected> convoyAttacks = new HashSet<>();
                    Set<Expected> vacancies = new HashSet<>();
                    for (UnitId mover : units) {
                        Order move = order(context, mover);
                        if (move == null || move.orderType() != OrderType.MOVE) continue;
                        UnitId target = resident(context, move.target());
                        if (target == null || target.equals(mover)) continue;
                        Order targetOrder = order(context, target);
                        if (stationary(targetOrder) && mover.owner() == target.owner())
                            collisions.add(expected(move.target(), Set.of(),
                                    TacticMatch.Role.ATTACKER, mover, TacticMatch.Role.REFERENCED_UNIT, target));
                        if (targetOrder != null && targetOrder.orderType() == OrderType.SUPPORT)
                            supportAttacks.add(expected(move.target(), Set.of(),
                                    TacticMatch.Role.ATTACKER, mover, TacticMatch.Role.SUPPORTER, target));
                        if (targetOrder != null && targetOrder.orderType() == OrderType.CONVOY
                                && target.unitType() == UnitType.FLEET)
                            convoyAttacks.add(expected(move.target(), Set.of(),
                                    TacticMatch.Role.ATTACKER, mover, TacticMatch.Role.CONVOYING_UNIT, target));
                        if (targetOrder != null && targetOrder.orderType() == OrderType.MOVE) {
                            if (Province.canonical(targetOrder.target()) == location(context, mover)) {
                                Province focus = List.of(location(context, mover), location(context, target))
                                        .stream().min(Comparator.comparing(Enum::name)).orElseThrow();
                                heads.add(expected(focus, Set.of(),
                                        TacticMatch.Role.ATTACKER, mover, TacticMatch.Role.ATTACKER, target));
                            }
                            if (mover.owner() != target.owner()
                                    && Province.canonical(targetOrder.target()) != location(context, target))
                                vacancies.add(expected(location(context, target), Set.of(),
                                        TacticMatch.Role.ISSUER, mover, TacticMatch.Role.REFERENCED_UNIT, target));
                        }
                    }
                    // Independent permutation predicate: both possible directed three-cycles.
                    Set<Expected> circles = new HashSet<>();
                    for (int[] permutation : List.of(new int[]{0, 1, 2}, new int[]{0, 2, 1})) {
                        boolean closed = true;
                        for (int index = 0; index < 3; index++) {
                            Order submitted = orders[permutation[index]];
                            closed &= submitted != null && submitted.orderType() == OrderType.MOVE
                                    && submitted.target() == positions[permutation[(index + 1) % 3]];
                        }
                        if (closed)
                            circles.add(expected(Province.Bur, Set.of(),
                                    TacticMatch.Role.ISSUER, units[0], TacticMatch.Role.ISSUER, units[1],
                                    TacticMatch.Role.ISSUER, units[2]));
                    }
                    verify(head, context, heads);
                    verify(circle, context, circles);
                    verify(collision, context, collisions);
                    verify(supportAttack, context, supportAttacks);
                    verify(convoyAttack, context, convoyAttacks);
                    // No referenced support recipients or coastal convoy armies in this domain.
                    verify(new ForeignCooperationDetective(), context, vacancies);
                }
            }
        }
    }

    /**
     * 2 recipient owners x 6 recipient states x 5^3 supporter submissions.
     * Supports at Gas/Ruh/Mar may be unknown, hold, move to Bur/Pic, or name
     * an absent recipient. Grouping is by recipient and canonical focus.
     */
    private static void supportReferencesAndGroups() {
        for (Nation owner : List.of(Nation.FRANCE, Nation.GERMANY)) {
            UnitId recipient = unit(0, owner, UnitType.ARMY);
            UnitId[] supporters = {unit(1, Nation.FRANCE, UnitType.ARMY),
                    unit(2, Nation.GERMANY, UnitType.ARMY), unit(3, Nation.ITALY, UnitType.ARMY)};
            UnitId[] units = {recipient, supporters[0], supporters[1], supporters[2]};
            BoardState board = board(units, Province.Par, Province.Gas, Province.Ruh, Province.Mar);
            for (int state = 0; state < 6; state++) {
                Order recipientOrder = switch (state) {
                    case 0 -> null;
                    case 1 -> Order.hold(recipient);
                    case 2 -> Order.move(recipient, Province.Bur);
                    case 3 -> Order.move(recipient, Province.Pic);
                    case 4 -> Order.supportHold(recipient, Province.Mun);
                    default -> convoy(recipient, Province.Lon, Province.Bel);
                };
                for (int code = 0; code < 125; code++) {
                    int remaining = code;
                    Order[] orders = {recipientOrder, null, null, null};
                    for (int index = 0; index < 3; index++) {
                        int choice = remaining % 5;
                        remaining /= 5;
                        orders[index + 1] = switch (choice) {
                            case 0 -> null;
                            case 1 -> Order.supportHold(supporters[index], Province.Par);
                            case 2 -> Order.supportMove(supporters[index], Province.Par, Province.Bur);
                            case 3 -> Order.supportMove(supporters[index], Province.Par, Province.Pic);
                            default -> Order.supportMove(supporters[index], Province.Lon, Province.Bur);
                        };
                    }
                    TacticalContext context = context(board, orders);
                    Set<Expected> mismatches = new HashSet<>();
                    Set<Expected> foreign = new HashSet<>();
                    Map<Province, Set<UnitId>> moves = new EnumMap<>(Province.class);
                    Set<UnitId> holds = new HashSet<>();
                    for (Order support : orders) {
                        if (support == null || support.orderType() != OrderType.SUPPORT) continue;
                        UnitId referenced = resident(context, support.target());
                        Order submitted = referenced == null ? null : order(context, referenced);
                        boolean compatible = submitted == null || (support.auxiliaryTarget() == null
                                ? stationary(submitted)
                                : submitted.orderType() == OrderType.MOVE
                                && Province.canonical(submitted.target())
                                == Province.canonical(support.auxiliaryTarget()));
                        Province focus = support.auxiliaryTarget() == null
                                ? support.target() : support.auxiliaryTarget();
                        if (validStatic(context, support)
                                && (referenced == null || referenced.equals(support.unit()) || !compatible))
                            mismatches.add(referenced == null
                                    ? expected(focus, Set.of(), TacticMatch.Role.SUPPORTER, support.unit())
                                    : expected(focus, Set.of(), TacticMatch.Role.SUPPORTER, support.unit(),
                                    TacticMatch.Role.REFERENCED_UNIT, referenced));
                        if (referenced == null || referenced.equals(support.unit()) || !compatible) continue;
                        Set<UnitId> missing = submitted == null ? Set.of(referenced) : Set.of();
                        if (support.unit().owner() != referenced.owner())
                            foreign.add(expected(focus, missing, TacticMatch.Role.SUPPORTER, support.unit(),
                                    TacticMatch.Role.SUPPORTED_UNIT, referenced));
                        if (support.auxiliaryTarget() == null) holds.add(support.unit());
                        else moves.computeIfAbsent(Province.canonical(focus), ignored -> new HashSet<>())
                                .add(support.unit());
                    }
                    Set<UnitId> missing = recipientOrder == null ? Set.of(recipient) : Set.of();
                    Set<Expected> moveGroups = new HashSet<>();
                    for (var entry : moves.entrySet())
                        if (entry.getValue().size() >= 2)
                            moveGroups.add(group(entry.getKey(), recipient, entry.getValue(), missing));
                    Set<Expected> holdGroups = holds.size() < 2 ? Set.of()
                            : Set.of(group(Province.Par, recipient, holds, missing));
                    verify(new SupportMismatchDetective(), context, mismatches);
                    verify(new MultipleSupportToMoveDetective(), context, moveGroups);
                    verify(new MultipleSupportToHoldDetective(), context, holdGroups);
                    verify(new ForeignCooperationDetective(), context, foreign);
                }
            }
        }
    }

    private static Expected group(Province focus, UnitId recipient, Set<UnitId> supporters,
                                  Set<UnitId> missing) {
        Set<TacticMatch.Participant> participants = new HashSet<>();
        participants.add(new TacticMatch.Participant(TacticMatch.Role.SUPPORTED_UNIT, recipient));
        for (UnitId supporter : supporters)
            participants.add(new TacticMatch.Participant(TacticMatch.Role.SUPPORTER, supporter));
        return new Expected(Province.canonical(focus), participants, missing);
    }

    /**
     * Independent static-validity reference, intentionally preserving the
     * existing validator's policy: distant army land moves are not rejected.
     * It does not call Orders.orderIsValid or BogusMovesDetective.isBogus.
     */
    private static boolean validStatic(TacticalContext context, Order order) {
        Province from = context.board().locationOf(order.unit());
        Province target = order.target();
        Province auxiliary = order.auxiliaryTarget();
        boolean army = order.unitType() == UnitType.ARMY;
        if (order.orderType() == OrderType.HOLD) return true;
        if (order.orderType() == OrderType.MOVE) {
            if (Province.canonical(from) == Province.canonical(target)) return false;
            return army ? target.geography != Geography.WATER && target.coastType != CoastType.SPLIT
                    : target.geography != Geography.INLAND && from.isAdjacentTo(target);
        }
        if (order.orderType() == OrderType.CONVOY)
            return !army && from.geography == Geography.WATER
                    && Province.canonical(target) != Province.canonical(auxiliary);
        Province destination = auxiliary == null ? target : auxiliary;
        return !(army && destination.geography == Geography.WATER)
                && !(!army && destination.geography == Geography.INLAND && !destination.hasCoast())
                && !(auxiliary != null && Province.canonical(target) == Province.canonical(auxiliary))
                && from.isAdjacentToIgnoreSplitCoast(destination)
                && (army || Province.adjacentBySea(from, destination));
    }

    /** All map targets from three actual origins, both unit types, five order forms. */
    private static void staticValidity() {
        for (UnitType type : UnitType.values()) {
            for (Province origin : type == UnitType.ARMY
                    ? List.of(Province.Par, Province.Bur, Province.Spa)
                    : List.of(Province.ENG, Province.Mar, Province.SpaSC)) {
                UnitId issuer = unit(0, Nation.FRANCE, type);
                BoardState board = board(new UnitId[]{issuer}, origin);
                for (Province target : Province.values()) {
                    for (Order submitted : List.of(Order.hold(issuer), Order.move(issuer, target),
                            Order.supportHold(issuer, target),
                            Order.supportMove(issuer, Province.Lon, target),
                            convoy(issuer, Province.Lon, target))) {
                        TacticalContext context = context(board, submitted);
                        verify(new BogusMovesDetective(), context, validStatic(context, submitted)
                                ? Set.of() : Set.of(expected(origin, Set.of(), TacticMatch.Role.ISSUER, issuer)));
                    }
                }
            }
        }
    }

    /** Absent/army/fleet recipients, known and unknown orders, legal/illegal issuers. */
    private static void convoyReferences() {
        for (UnitType issuerType : UnitType.values()) {
            for (Province fleetLocation : List.of(Province.ENG, Province.Bre)) {
                for (int recipientType = 0; recipientType < 3; recipientType++) {
                    for (Nation owner : List.of(Nation.ENGLAND, Nation.FRANCE)) {
                        for (int state = 0; state < 4; state++) {
                            UnitId issuer = unit(0, Nation.ENGLAND, issuerType);
                            UnitId recipient = unit(1, owner, recipientType == 2
                                    ? UnitType.FLEET : UnitType.ARMY);
                            BoardState board = recipientType == 0
                                    ? board(new UnitId[]{issuer}, fleetLocation)
                                    : board(new UnitId[]{issuer, recipient}, fleetLocation, Province.Lon);
                            Order recipientOrder = recipientType == 0 ? null : switch (state) {
                                case 0 -> null;
                                case 1 -> Order.hold(recipient);
                                case 2 -> Order.move(recipient, Province.Bel);
                                default -> Order.move(recipient, Province.Pic);
                            };
                            Order convoy = convoy(issuer, Province.Lon, Province.Bel);
                            TacticalContext context = context(board, convoy, recipientOrder);
                            boolean army = recipientType == 1;
                            boolean matched = army && state == 2;
                            Set<UnitId> missing = army && state == 0 ? Set.of(recipient) : Set.of();
                            Set<Expected> unmatched = matched ? Set.of() : Set.of(recipientType == 0
                                    ? expected(Province.Bel, Set.of(), TacticMatch.Role.CONVOYING_UNIT, issuer)
                                    : expected(Province.Bel, missing, TacticMatch.Role.CONVOYING_UNIT, issuer,
                                    TacticMatch.Role.REFERENCED_UNIT, recipient));
                            verify(new UnmatchedConvoyDetective(), context, unmatched);
                            boolean foreign = issuerType == UnitType.FLEET
                                    && fleetLocation.geography == Geography.WATER && army
                                    && owner != Nation.ENGLAND && (state == 0 || matched);
                            verify(new ForeignCooperationDetective(), context, foreign
                                    ? Set.of(expected(Province.Bel, missing,
                                    TacticMatch.Role.CONVOYING_UNIT, issuer,
                                    TacticMatch.Role.CONVOYED_ARMY, recipient)) : Set.of());
                        }
                    }
                }
            }
        }
    }

    /**
     * 8 endpoint pairs x 2 army-order states x 2^6 matching sea-fleet subsets.
     * Reachability is a Floyd-Warshall closure, independent of detective BFS.
     * Nonmatching and unknown fleets never form bridge nodes.
     */
    private static void convoyRoutes() {
        Province[] seas = {Province.ENG, Province.NTH, Province.IRI,
                Province.MAO, Province.NAO, Province.NWG};
        List<Province[]> endpoints = List.of(
                new Province[]{Province.Lon, Province.Bel},
                new Province[]{Province.Lon, Province.Por},
                new Province[]{Province.Lon, Province.Stp},
                new Province[]{Province.Lon, Province.SpaNC},
                new Province[]{Province.Bre, Province.SpaSC},
                new Province[]{Province.Par, Province.Bel},
                new Province[]{Province.Lon, Province.Lon},
                new Province[]{Province.Lon, Province.Bur});
        for (Province[] pair : endpoints) {
            for (boolean knownMove : List.of(false, true)) {
                for (int mask = 0; mask < 64; mask++) {
                    UnitId army = unit(0, Nation.ENGLAND, UnitType.ARMY);
                    UnitId[] units = new UnitId[7];
                    Province[] positions = new Province[7];
                    units[0] = army;
                    positions[0] = Province.canonical(pair[0]);
                    Order[] orders = new Order[7];
                    orders[0] = knownMove ? Order.move(army, pair[1]) : null;
                    Set<UnitId> matching = new HashSet<>();
                    Set<Province> nodes = EnumSet.noneOf(Province.class);
                    for (int index = 0; index < 6; index++) {
                        units[index + 1] = unit(index + 1, Nation.FRANCE, UnitType.FLEET);
                        positions[index + 1] = seas[index];
                        boolean selected = (mask & (1 << index)) != 0;
                        orders[index + 1] = selected
                                ? Order.convoy(units[index + 1], pair[0], pair[1])
                                : index % 2 == 0 ? null
                                : Order.convoy(units[index + 1], Province.Edi, Province.Bre);
                        if (selected) {
                            matching.add(units[index + 1]);
                            nodes.add(seas[index]);
                        }
                    }
                    TacticalContext context = context(board(units, positions), orders);
                    Province origin = Province.canonical(pair[0]);
                    Province destination = Province.canonical(pair[1]);
                    boolean incomplete = knownMove && origin != destination && origin.hasCoast()
                            && destination.hasCoast() && !nodes.isEmpty() && !route(origin, destination, nodes);
                    Set<TacticMatch.Participant> participants = new HashSet<>();
                    participants.add(new TacticMatch.Participant(TacticMatch.Role.CONVOYED_ARMY, army));
                    for (UnitId fleet : matching)
                        participants.add(new TacticMatch.Participant(TacticMatch.Role.CONVOYING_UNIT, fleet));
                    verify(new IncompleteConvoyChainDetective(), context, incomplete
                            ? Set.of(new Expected(destination, participants, Set.of())) : Set.of());
                }
            }
        }
    }

    private static boolean touches(Province sea, Province land) {
        return Arrays.stream(Province.values()).anyMatch(coast ->
                Province.canonical(coast) == land && sea.isAdjacentTo(coast));
    }

    private static boolean route(Province origin, Province destination, Set<Province> nodes) {
        List<Province> list = List.copyOf(nodes);
        boolean[][] reachable = new boolean[list.size()][list.size()];
        for (int a = 0; a < list.size(); a++)
            for (int b = 0; b < list.size(); b++)
                reachable[a][b] = a == b || list.get(a).isAdjacentTo(list.get(b));
        for (int bridge = 0; bridge < list.size(); bridge++)
            for (int a = 0; a < list.size(); a++)
                for (int b = 0; b < list.size(); b++)
                    reachable[a][b] |= reachable[a][bridge] && reachable[bridge][b];
        for (int a = 0; a < list.size(); a++)
            for (int b = 0; b < list.size(); b++)
                if (touches(list.get(a), origin) && touches(list.get(b), destination) && reachable[a][b])
                    return true;
        return false;
    }

    private static void specialFixtures() {
        UnitId a = unit(0, Nation.FRANCE, UnitType.FLEET);
        UnitId b = unit(1, Nation.FRANCE, UnitType.FLEET);
        UnitId c = unit(2, Nation.GERMANY, UnitType.FLEET);
        UnitId feeder = unit(3, Nation.ITALY, UnitType.FLEET);
        BoardState board = board(new UnitId[]{a, b, c, feeder},
                Province.SpaNC, Province.Por, Province.MAO, Province.WES);
        TacticalContext cycle = context(board, Order.move(a, Province.Por),
                Order.move(b, Province.MAO), Order.move(c, Province.SpaSC),
                Order.move(feeder, Province.MAO));
        verify(new CircularMovementDetective(), cycle, Set.of(expected(Province.MAO, Set.of(),
                TacticMatch.Role.ISSUER, a, TacticMatch.Role.ISSUER, b, TacticMatch.Role.ISSUER, c)));
        verify(new CircularMovementDetective(), context(board,
                Order.move(a, Province.Por), Order.move(b, Province.MAO),
                Order.move(c, Province.WES), Order.move(feeder, Province.SpaSC)),
                Set.of(expected(Province.MAO, Set.of(), TacticMatch.Role.ISSUER, a,
                        TacticMatch.Role.ISSUER, b, TacticMatch.Role.ISSUER, c,
                        TacticMatch.Role.ISSUER, feeder)));
        require(cycle.orders().get(c).order().target() == Province.SpaSC
                        && cycle.board().locationOf(a) == Province.SpaNC, "Canonicalization changed exact coasts");
        verify(new HeadToHeadDetective(), context(board, Order.move(a, Province.Por),
                Order.move(b, Province.SpaSC)), Set.of(expected(Province.Por, Set.of(),
                TacticMatch.Role.ATTACKER, a, TacticMatch.Role.ATTACKER, b)));
        verify(new FriendlyOccupantCollisionDetective(), context(board,
                Order.move(b, Province.SpaSC), Order.hold(a)), Set.of(expected(Province.Spa, Set.of(),
                TacticMatch.Role.ATTACKER, b, TacticMatch.Role.REFERENCED_UNIT, a)));
        verify(new AttackOnSupporterDetective(), context(board,
                Order.move(c, Province.SpaSC), Order.supportHold(a, Province.Por)),
                Set.of(expected(Province.Spa, Set.of(), TacticMatch.Role.ATTACKER, c,
                        TacticMatch.Role.SUPPORTER, a)));
        verify(new UnmatchedConvoyDetective(), context(board,
                Order.convoy(c, Province.SpaSC, Province.Por), Order.hold(a)),
                Set.of(expected(Province.Por, Set.of(), TacticMatch.Role.CONVOYING_UNIT, c,
                        TacticMatch.Role.REFERENCED_UNIT, a)));
        // Known self-reference is diagnosed only when the submitted support passes static checks.
        verify(new SupportMismatchDetective(), context(board,
                Order.supportMove(a, Province.SpaSC, Province.Por)),
                Set.of(expected(Province.Por, Set.of(), TacticMatch.Role.SUPPORTER, a,
                        TacticMatch.Role.REFERENCED_UNIT, a)));
        UnitId army = unit(4, Nation.ENGLAND, UnitType.ARMY);
        BoardState extra = board(new UnitId[]{a, b, c, feeder, army},
                Province.SpaNC, Province.Por, Province.MAO, Province.WES, Province.Lon);
        verify(new IncompleteConvoyChainDetective(), context(extra,
                Order.move(army, Province.Bel), Order.convoy(a, Province.Lon, Province.Bel)),
                Set.of()); // Coastal fleets cannot be route nodes.
        UnitId distant = unit(5, Nation.ITALY, UnitType.ARMY);
        BoardState invalidIssuer = board(new UnitId[]{army, distant}, Province.Lon, Province.Par);
        verify(new IncompleteConvoyChainDetective(), context(invalidIssuer,
                Order.move(army, Province.Bel), convoy(distant, Province.Lon, Province.Bel)), Set.of());
        verify(new UnmatchedConvoyDetective(), context(invalidIssuer,
                Order.move(army, Province.Bel), convoy(distant, Province.Lon, Province.Bel)), Set.of());
        verify(new BogusMovesDetective(), context(invalidIssuer,
                Order.move(army, Province.Bel), convoy(distant, Province.Lon, Province.Bel)),
                Set.of(expected(Province.Par, Set.of(), TacticMatch.Role.ISSUER, distant)));
        // Three supporters produce one occurrence, not three pairwise findings; exact coasts merge.
        verify(new MultipleSupportToMoveDetective(), context(extra, Order.move(a, Province.Por),
                Order.supportMove(b, Province.SpaSC, Province.Por),
                Order.supportMove(c, Province.SpaNC, Province.Por),
                Order.supportMove(feeder, Province.Spa, Province.Por)),
                Set.of(group(Province.Por, a, Set.of(b, c, feeder), Set.of())));
        verify(new MultipleSupportToHoldDetective(), context(extra,
                Order.supportHold(b, Province.SpaSC), Order.supportHold(c, Province.SpaNC),
                Order.supportHold(feeder, Province.Spa)),
                Set.of(group(Province.Spa, a, Set.of(b, c, feeder), Set.of(a))));
        UnitId supporter = unit(6, Nation.GERMANY, UnitType.ARMY);
        BoardState unrelated = board(new UnitId[]{army, distant, supporter},
                Province.Lon, Province.Par, Province.Gas);
        // Bogus support never doubles as a mismatch, even with an absent reference.
        verify(new SupportMismatchDetective(), context(unrelated,
                Order.supportMove(distant, Province.Vie, Province.NTH)), Set.of());
        // Local absence is a confirmed mismatch, not an unknown recipient prerequisite.
        verify(new SupportMismatchDetective(), context(unrelated,
                Order.supportMove(supporter, Province.Vie, Province.Bur)),
                Set.of(expected(Province.Bur, Set.of(), TacticMatch.Role.SUPPORTER, supporter)));
        for (Province destination : List.of(Province.Lon, Province.Bur, Province.SpaSC)) {
            TacticalContext candidate = context(extra, Order.convoy(c, Province.Lon, destination));
            boolean coastalDeparture = destination == Province.SpaSC;
            verify(new ForeignCooperationDetective(), candidate, coastalDeparture
                    ? Set.of(expected(Province.Spa, Set.of(army),
                    TacticMatch.Role.CONVOYING_UNIT, c, TacticMatch.Role.CONVOYED_ARMY, army))
                    : Set.of());
        }
        // A convoy submitted from an inland army endpoint is not foreign cooperation.
        verify(new ForeignCooperationDetective(), context(
                board(new UnitId[]{c, distant}, Province.MAO, Province.Par),
                Order.convoy(c, Province.Par, Province.Bel)), Set.of());
        UnitId[] separate = new UnitId[8];
        for (int index = 0; index < separate.length; index++)
            separate[index] = unit(20 + index, Nation.FRANCE, UnitType.ARMY);
        BoardState separated = board(separate, Province.Par, Province.Bur, Province.Mar,
                Province.Lon, Province.Bel, Province.Pic, Province.Mun, Province.Gas);
        verify(new CircularMovementDetective(), context(separated,
                Order.move(separate[0], Province.Bur), Order.move(separate[1], Province.Mar),
                Order.move(separate[2], Province.Par), Order.move(separate[3], Province.Bel),
                Order.move(separate[4], Province.Pic), Order.move(separate[5], Province.Lon),
                Order.move(separate[6], Province.Bur), Order.hold(separate[7])),
                Set.of(expected(Province.Bur, Set.of(), TacticMatch.Role.ISSUER, separate[0],
                                TacticMatch.Role.ISSUER, separate[1], TacticMatch.Role.ISSUER, separate[2]),
                        expected(Province.Bel, Set.of(), TacticMatch.Role.ISSUER, separate[3],
                                TacticMatch.Role.ISSUER, separate[4], TacticMatch.Role.ISSUER, separate[5])));
        TacticalContext overlap = context(separated, Order.move(separate[0], Province.Bur),
                Order.supportHold(separate[1], Province.Mar));
        InvestigationReport report = new DetectiveAgency().investigateReport(overlap);
        require(report.byKind(TacticKind.FRIENDLY_OCCUPANT_COLLISION).size() == 1
                        && report.byKind(TacticKind.ATTACK_ON_SUPPORTER).size() == 1,
                "Agency suppressed overlapping collision/supporter-attack relationships");
        require(overlap.unknownUnits().contains(separate[2]),
                "Overlapping findings fabricated unrelated recipient evidence");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejectsMutation(Runnable mutation) {
        checks++;
        try {
            mutation.run();
            throw new AssertionError("Mutable detective output");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable API result.
        }
    }
}
