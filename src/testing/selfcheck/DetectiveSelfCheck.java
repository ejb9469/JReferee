package testing.selfcheck;

import analysis.tactics.*;
import analysis.tactics.detective.*;
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
 * Standalone structural checks for the tactical detectives.<br><br>
 *
 * Exercises positive matches, contradictory orders, incomplete information,
 * grouping, coast handling, stable ordering, and immutable results.
 *
 * <p>These checks do not adjudicate orders. A structural match does not
 * establish geographic legality, tactical realization, or strategic value.</p>
 *
 * <p>Run main(...) directly. Java assertions do not need to be enabled.</p>
 */
public final class DetectiveSelfCheck {


    // Constants \\

    private static final String RULESET = "detective-self-check-v1";

    private static final GameMoment MOMENT =
            new GameMoment(1902, GamePhase.SPRING_MOVEMENT);


    // Check state \\

    private static int checks;


    // Construction \\

    private DetectiveSelfCheck() {  }


    // Application entry point \\

    public static void main(String[] args) {

        checks = 0;

        supportToMove();
        supportToHold();
        selfBounce();
        beleagueredGarrison();

        splitCoasts();
        orderingAndIdentity();
        validation();
        immutability();

        System.out.printf(
                "Detective self-check passed: %d checks.%n",
                checks);

    }


    // Support to move \\

    private static void supportToMove() {

        Detective detective = new SupportToMoveDetective();

        UnitId par = army(Nation.FRANCE, Province.Par);
        UnitId mar = army(Nation.FRANCE, Province.Mar);
        UnitId mun = army(Nation.GERMANY, Province.Mun);

        BoardState board = board(par, mar, mun);

        Order support = Order.supportMove(
                mar, Province.Par, Province.Bur);

        TacticalContext matching = context(
                board,
                Order.move(par, Province.Bur),
                support);

        TacticMatch match = only(detective, matching);

        require(match.focus() == Province.Bur,
                "Support-to-move focus must be the destination");

        require(match.units(TacticMatch.Role.SUPPORTER).equals(List.of(mar)),
                "Wrong support-to-move supporter");

        require(match.units(TacticMatch.Role.SUPPORTED_UNIT).equals(List.of(par)),
                "Wrong supported mover");

        require(match.completePattern(),
                "Matching known orders must complete the local pattern");

        require(!matching.complete(),
                "The unrelated German order must remain unknown");

        require(match.missingOrders().isEmpty(),
                "Unknown unrelated orders are not local prerequisites");

        require(investigate(detective, context(
                        board,
                        Order.move(par, Province.Pic),
                        support)).isEmpty(),
                "Support to a different destination must not match");

        require(investigate(detective, context(
                        board,
                        Order.hold(par),
                        support)).isEmpty(),
                "Support-to-move must not match a known HOLD");

        require(investigate(detective, context(
                        board,
                        Order.move(par, Province.Bur),
                        Order.supportHold(mar, Province.Par))).isEmpty(),
                "Support-to-hold must not be classified as support-to-move");

        TacticalContext partial = context(board, support);
        TacticMatch incomplete = only(detective, partial);

        require(!incomplete.completePattern(),
                "Unknown recipient order must leave an incomplete match");

        require(incomplete.missingOrders().equals(Set.of(par)),
                "The recipient must be the only missing local prerequisite");

        require(partial.orderOf(par).isEmpty(),
                "Investigation must not invent the recipient's order");

        require(investigate(detective, context(
                        board(mar), support)).isEmpty(),
                "An absent recipient must not produce an incomplete match");

        require(investigate(detective, context(
                        board(par),
                        Order.supportMove(par, Province.Par, Province.Bur))).isEmpty(),
                "Self-referencing support-to-move must not match");

        UnitId italianMar = army(Nation.ITALY, Province.Mar);

        require(only(detective, context(
                        board(par, italianMar),
                        Order.move(par, Province.Bur),
                        Order.supportMove(
                                italianMar, Province.Par, Province.Bur)))
                        .completePattern(),
                "Foreign support must be recognized");

        UnitId lvp = army(Nation.FRANCE, Province.Lvp);

        require(only(detective, context(
                        board(par, lvp),
                        Order.move(par, Province.Bur),
                        Order.supportMove(lvp, Province.Par, Province.Bur)))
                        .completePattern(),
                "Structural recognition must not silently adjudicate geography");

    }


    // Support to hold \\

    private static void supportToHold() {

        Detective detective = new SupportToHoldDetective();

        UnitId par = army(Nation.FRANCE, Province.Par);
        UnitId bur = army(Nation.FRANCE, Province.Bur);

        BoardState board = board(par, bur);

        Order support = Order.supportHold(bur, Province.Par);

        TacticMatch holding = only(detective, context(
                board,
                Order.hold(par),
                support));

        require(holding.focus() == Province.Par,
                "Support-to-hold focus must be the recipient's territory");

        require(holding.units(TacticMatch.Role.SUPPORTED_UNIT)
                        .equals(List.of(par)),
                "Wrong supported non-mover");

        TacticalContext reciprocal = context(
                board,
                Order.supportHold(par, Province.Bur),
                support);

        List<TacticMatch> reciprocalMatches =
                investigate(detective, reciprocal);

        require(reciprocalMatches.size() == 2,
                "Reciprocal support must produce two relationships");

        require(reciprocalMatches.get(0).focus() == Province.Bur
                        && reciprocalMatches.get(1).focus() == Province.Par,
                "Reciprocal matches must be ordered by focus");

        UnitId nth = fleet(Nation.ENGLAND, Province.NTH);
        UnitId eng = fleet(Nation.ENGLAND, Province.ENG);

        require(only(detective, context(
                        board(nth, eng),
                        Order.convoy(nth, Province.Lon, Province.Bel),
                        Order.supportHold(eng, Province.NTH)))
                        .completePattern(),
                "A known convoying recipient must qualify structurally");

        require(investigate(detective, context(
                        board,
                        Order.move(par, Province.Pic),
                        support)).isEmpty(),
                "A known moving recipient must not qualify");

        require(investigate(detective, context(
                        board,
                        Order.move(par, Province.Pic),
                        Order.supportMove(bur, Province.Par, Province.Pic))).isEmpty(),
                "Support-to-move must not be classified as support-to-hold");

        TacticMatch incomplete = only(detective, context(board, support));

        require(incomplete.missingOrders().equals(Set.of(par)),
                "Unknown recipient order must be recorded");

        require(!incomplete.completePattern(),
                "Unknown must not become an implicit HOLD");

        Map<UnitId, TacticalContext.KnownOrder> defaulted =
                new LinkedHashMap<>();

        defaulted.put(par, new TacticalContext.KnownOrder(
                Order.hold(par),
                TacticalContext.Provenance.DEFAULTED));

        defaulted.put(bur, submitted(support));

        TacticalContext confirmedOmission =
                new TacticalContext(RULESET, MOMENT, board, defaulted);

        require(only(detective, confirmedOmission).completePattern(),
                "A confirmed defaulted HOLD must complete the relationship");

        require(confirmedOmission.orderOf(par).orElseThrow().provenance()
                        == TacticalContext.Provenance.DEFAULTED,
                "Defaulted provenance must be retained");

        require(investigate(detective, context(
                        board(bur), support)).isEmpty(),
                "Support into an empty province must not match");

        require(investigate(detective, context(
                        board(par),
                        Order.supportHold(par, Province.Par))).isEmpty(),
                "Self-referencing hold support must not match");

        UnitId italianBur = army(Nation.ITALY, Province.Bur);

        require(only(detective, context(
                        board(par, italianBur),
                        Order.hold(par),
                        Order.supportHold(italianBur, Province.Par)))
                        .completePattern(),
                "Foreign hold support must be recognized");

    }


    // Self-bounce \\

    private static void selfBounce() {

        Detective detective = new SelfBounceDetective();

        UnitId par = army(Nation.FRANCE, Province.Par);
        UnitId mar = army(Nation.FRANCE, Province.Mar);
        UnitId mun = army(Nation.FRANCE, Province.Mun);
        UnitId gas = army(Nation.FRANCE, Province.Gas);

        BoardState board = board(par, mar, mun, gas);

        Order first = Order.move(par, Province.Bur);
        Order second = Order.move(mar, Province.Bur);

        TacticMatch pair = only(detective, context(board, first, second));

        require(pair.focus() == Province.Bur,
                "Self-bounce focus must be the shared destination");

        require(pair.units(TacticMatch.Role.ATTACKER)
                        .equals(List.of(mar, par)),
                "Self-bounce participants must be ordered by current location");

        require(pair.completePattern(),
                "Two known competing moves complete the local pattern");

        require(investigate(detective, context(board, first)).isEmpty(),
                "One known move must not fabricate a second mover");

        require(investigate(detective, context(
                        board,
                        first,
                        Order.move(mar, Province.Spa))).isEmpty(),
                "Different destinations must not form a self-bounce");

        require(investigate(detective, context(
                        board,
                        first,
                        Order.hold(mar))).isEmpty(),
                "A stationary friendly unit is not a competing mover");

        UnitId italianMar = army(Nation.ITALY, Province.Mar);

        require(investigate(detective, context(
                        board(par, italianMar),
                        first,
                        Order.move(italianMar, Province.Bur))).isEmpty(),
                "Different nations must not form one self-bounce group");

        TacticMatch triple = only(detective, context(
                board,
                first,
                second,
                Order.move(mun, Province.Bur)));

        require(triple.units(TacticMatch.Role.ATTACKER)
                        .equals(List.of(mar, mun, par)),
                "Three incoming moves must produce one complete group");

        require(only(detective, context(
                        board,
                        first,
                        second,
                        Order.supportMove(gas, Province.Par, Province.Bur)))
                        .units(TacticMatch.Role.ATTACKER).size() == 2,
                "Unequal proposed support must not suppress a structural group");

        UnitId defender = army(Nation.GERMANY, Province.Bur);

        require(only(detective, context(
                        board(par, mar, defender),
                        first,
                        second,
                        Order.hold(defender))).completePattern(),
                "Destination occupancy belongs to assessment, not recognition");

        UnitId lvp = army(Nation.FRANCE, Province.Lvp);

        require(only(detective, context(
                        board(par, lvp),
                        first,
                        Order.move(lvp, Province.Bur))).completePattern(),
                "An impossible route must not be silently adjudicated here");

    }


    // Beleaguered garrison \\

    private static void beleagueredGarrison() {
        checks += BeleagueredGarrisonSelfCheck.runChecks();
    }


    // Split coasts \\

    private static void splitCoasts() {

        UnitId por = fleet(Nation.FRANCE, Province.Por);
        UnitId mao = fleet(Nation.FRANCE, Province.MAO);
        UnitId spa = fleet(Nation.GERMANY, Province.SpaSC);
        UnitId gas = army(Nation.FRANCE, Province.Gas);
        UnitId wes = fleet(Nation.ITALY, Province.WES);

        TacticalContext competition = context(
                board(por, mao, spa, gas, wes),
                Order.move(por, Province.SpaNC),
                Order.move(mao, Province.SpaSC),
                Order.supportMove(gas, Province.Por, Province.SpaSC),
                Order.supportMove(wes, Province.MAO, Province.SpaNC));

        require(only(new SelfBounceDetective(), competition).focus()
                        == Province.Spa,
                "Different destination coasts must share one self-bounce focus");

        TacticMatch garrison =
                only(new BeleagueredGarrisonDetective(), competition);

        require(garrison.units(TacticMatch.Role.DEFENDER).equals(List.of(spa)),
                "Garrison lookup must find the occupant on either coast");

        require(garrison.units(TacticMatch.Role.SUPPORTER)
                        .equals(List.of(gas, wes)),
                "Canonical support matching must retain both supporters");

        require(competition.orderOf(por).orElseThrow().order().target()
                        == Province.SpaNC
                        && competition.orderOf(mao).orElseThrow().order().target()
                        == Province.SpaSC,
                "Canonical grouping must not rewrite exact move destinations");

        TacticalContext supportMove = context(
                board(por, gas),
                Order.move(por, Province.SpaSC),
                Order.supportMove(gas, Province.Por, Province.SpaNC));

        require(only(new SupportToMoveDetective(), supportMove).focus()
                        == Province.Spa,
                "Structural support reference matching uses canonical territory");

        require(supportMove.orderOf(gas).orElseThrow().order().auxiliaryTarget()
                        == Province.SpaNC,
                "Exact support coast must remain available for adjudication");

        TacticalContext supportHold = context(
                board(mao, spa),
                Order.supportHold(mao, Province.Spa),
                Order.hold(spa));

        require(only(new SupportToHoldDetective(), supportHold)
                        .units(TacticMatch.Role.SUPPORTED_UNIT).equals(List.of(spa)),
                "Canonical hold reference must locate a split-coast recipient");

    }


    // Ordering, identity, and reuse \\

    private static void orderingAndIdentity() {

        UnitId par = army(Nation.FRANCE, Province.Par);
        UnitId mar = army(Nation.FRANCE, Province.Mar);
        UnitId gas = army(Nation.FRANCE, Province.Gas);
        UnitId mun = army(Nation.FRANCE, Province.Mun);
        UnitId bre = army(Nation.FRANCE, Province.Bre);
        UnitId pic = army(Nation.FRANCE, Province.Pic);
        UnitId ruh = army(Nation.FRANCE, Province.Ruh);

        UnitId bur = army(Nation.GERMANY, Province.Bur);
        UnitId bel = army(Nation.GERMANY, Province.Bel);

        BoardState board = board(
                par, mar, gas, mun, bre, pic, ruh, bur, bel);

        TacticalContext moves = context(
                board,
                Order.move(par, Province.Bur),
                Order.move(mar, Province.Bur),
                Order.supportMove(gas, Province.Par, Province.Bur),
                Order.supportMove(mun, Province.Par, Province.Bur),
                Order.move(bre, Province.Bel),
                Order.move(pic, Province.Bel),
                Order.supportMove(ruh, Province.Bre, Province.Bel),
                Order.hold(bur),
                Order.hold(bel));

        TacticalContext holds = context(
                board,
                Order.hold(par),
                Order.supportHold(gas, Province.Par),
                Order.supportHold(mun, Province.Par),
                Order.hold(bre),
                Order.supportHold(ruh, Province.Bre));

        List<TacticMatch> supportedMoves =
                investigate(new SupportToMoveDetective(), moves);

        require(focuses(supportedMoves).equals(
                        List.of(Province.Bel, Province.Bur, Province.Bur)),
                "Support-to-move results must be ordered by focus");

        require(supportedMoves.get(1).units(TacticMatch.Role.SUPPORTER)
                        .equals(List.of(gas))
                        && supportedMoves.get(2).units(TacticMatch.Role.SUPPORTER)
                        .equals(List.of(mun)),
                "Equal-focus matches must be ordered by supporter location");

        List<TacticMatch> supportedHolds =
                investigate(new SupportToHoldDetective(), holds);

        require(focuses(supportedHolds).equals(
                        List.of(Province.Bre, Province.Par, Province.Par)),
                "Support-to-hold results must be ordered by focus");

        require(supportedHolds.get(1).units(TacticMatch.Role.SUPPORTER)
                        .equals(List.of(gas))
                        && supportedHolds.get(2).units(TacticMatch.Role.SUPPORTER)
                        .equals(List.of(mun)),
                "Hold-support ties must use participant locations");

        require(focuses(investigate(new SelfBounceDetective(), moves))
                        .equals(List.of(Province.Bel, Province.Bur)),
                "Self-bounce groups must be ordered by focus");

        /*
         * Keep the earlier fixture unchanged: other assertions depend on
         * its support counts. Garrison ordering requires two independently
         * supported moves into each occupied destination.
         */
        UnitId hol = army(Nation.FRANCE, Province.Hol);

        TacticalContext garrisonMoves = context(
                board(par, mar, gas, mun, bre, pic, ruh, hol, bur, bel),
                Order.move(par, Province.Bur),
                Order.move(mar, Province.Bur),
                Order.supportMove(gas, Province.Par, Province.Bur),
                Order.supportMove(mun, Province.Mar, Province.Bur),
                Order.move(bre, Province.Bel),
                Order.move(pic, Province.Bel),
                Order.supportMove(ruh, Province.Bre, Province.Bel),
                Order.supportMove(hol, Province.Pic, Province.Bel),
                Order.hold(bur),
                Order.hold(bel));

        require(focuses(investigate(
                        new BeleagueredGarrisonDetective(), garrisonMoves))
                        .equals(List.of(Province.Bel, Province.Bur)),
                "Garrison groups must be ordered by focus");

        checkOrdering(new SupportToMoveDetective(), moves);
        checkOrdering(new SupportToHoldDetective(), holds);
        checkOrdering(new SelfBounceDetective(), moves);
        checkOrdering(new BeleagueredGarrisonDetective(), garrisonMoves);

        // Include incomplete matches in ordering and identity checks.
        checkOrdering(new SupportToMoveDetective(), context(
                board,
                Order.supportMove(gas, Province.Par, Province.Bur),
                Order.supportMove(mun, Province.Par, Province.Bur),
                Order.supportMove(ruh, Province.Bre, Province.Bel)));

        checkOrdering(new SupportToHoldDetective(), context(
                board,
                Order.supportHold(gas, Province.Par),
                Order.supportHold(mun, Province.Par),
                Order.supportHold(ruh, Province.Bre)));

    }

    private static void checkOrdering(
            Detective detective,
            TacticalContext original
    ) {

        List<TacticMatch> expected = investigate(detective, original);

        require(!expected.isEmpty(),
                "Ordering fixture must contain matches");

        Random random = new Random(9469L);

        for (int trial = 0; trial < 20; trial++) {

            List<UnitId> units =
                    new ArrayList<>(original.board().locations().keySet());

            List<UnitId> issuers =
                    new ArrayList<>(original.orders().keySet());

            Collections.shuffle(units, random);
            Collections.shuffle(issuers, random);

            Map<UnitId, Province> locations = new LinkedHashMap<>();
            Map<UnitId, TacticalContext.KnownOrder> orders =
                    new LinkedHashMap<>();

            for (UnitId unit : units)
                locations.put(unit, original.board().locationOf(unit));

            for (UnitId unit : issuers)
                orders.put(unit, original.orders().get(unit));

            TacticalContext shuffled = new TacticalContext(
                    original.rulesetId(),
                    original.moment(),
                    new BoardState(locations, original.board().owners()),
                    orders);

            require(investigate(detective, shuffled).equals(expected),
                    "Insertion order changed " + detective.kind());

        }

        TacticalContext reidentified = reidentify(original);

        require(describe(investigate(detective, reidentified))
                        .equals(describe(expected)),
                "UUID or creation origin changed " + detective.kind());

        TacticalContext unknown = context(original.board());

        require(investigate(detective, unknown).isEmpty(),
                "Reused detective retained findings from an earlier context");

        require(investigate(detective, original).equals(expected),
                "Detective reuse changed the original findings");

    }


    // Context and input validation \\

    private static void validation() {

        UnitId par = army(Nation.FRANCE, Province.Par);
        UnitId mar = army(Nation.FRANCE, Province.Mar);

        BoardState board = board(par, mar);

        for (Detective detective : detectives()) {

            rejects(NullPointerException.class,
                    () -> detective.investigate(null),
                    "Null investigation context");

            rejects(NullPointerException.class,
                    () -> detective.detect(null),
                    "Null compatibility context");

            require(investigate(detective, context(board())).isEmpty(),
                    "Empty board must have no structural findings");

        }

        for (GamePhase phase : List.of(
                GamePhase.SPRING_RETREAT,
                GamePhase.FALL_RETREAT,
                GamePhase.WINTER_ADJUSTMENT)) {

            rejects(IllegalArgumentException.class,
                    () -> new TacticalContext(
                            RULESET,
                            new GameMoment(1902, phase),
                            board,
                            Map.of()),
                    "Non-movement phase");

        }

        TacticalContext fall = new TacticalContext(
                RULESET,
                new GameMoment(1902, GamePhase.FALL_MOVEMENT),
                board,
                context(board,
                        Order.move(par, Province.Bur),
                        Order.move(mar, Province.Bur)).orders());

        require(only(new SelfBounceDetective(), fall).completePattern(),
                "Fall movement must be accepted");

        rejects(IllegalArgumentException.class,
                () -> new TacticalContext(
                        " ", MOMENT, board, Map.of()),
                "Blank ruleset");

        rejects(IllegalArgumentException.class,
                () -> context(board(par), Order.hold(mar)),
                "Absent order issuer");

        rejects(IllegalArgumentException.class,
                () -> new TacticalContext(
                        RULESET,
                        MOMENT,
                        board,
                        Map.of(par, submitted(Order.hold(mar)))),
                "Order key differs from issuer");

        rejects(IllegalArgumentException.class,
                () -> submitted(new Order(
                        par, OrderType.MOVE, null, null)),
                "MOVE without destination");

        rejects(IllegalArgumentException.class,
                () -> submitted(new Order(
                        par, OrderType.HOLD, Province.Bur, null)),
                "HOLD with destination");

        rejects(IllegalArgumentException.class,
                () -> submitted(new Order(
                        par, OrderType.SUPPORT, null, null)),
                "SUPPORT without recipient");

        rejects(IllegalArgumentException.class,
                () -> submitted(new Order(
                        par, OrderType.CONVOY, Province.Mar, null)),
                "CONVOY without destination");

        rejects(IllegalArgumentException.class,
                () -> new TacticalContext.KnownOrder(
                        Order.move(par, Province.Bur),
                        TacticalContext.Provenance.DEFAULTED),
                "Defaulted non-HOLD");

        for (TacticalContext.Provenance provenance :
                TacticalContext.Provenance.values()) {

            Map<UnitId, TacticalContext.KnownOrder> orders =
                    new LinkedHashMap<>();

            orders.put(par, new TacticalContext.KnownOrder(
                    Order.hold(par), provenance));

            orders.put(mar, submitted(
                    Order.supportHold(mar, Province.Par)));

            TacticalContext context =
                    new TacticalContext(RULESET, MOMENT, board, orders);

            require(only(new SupportToHoldDetective(), context)
                            .completePattern(),
                    "Known HOLD provenance must not change recognition");

        }

    }


    // Immutability \\

    private static void immutability() {

        UnitId par = army(Nation.FRANCE, Province.Par);
        UnitId mar = army(Nation.FRANCE, Province.Mar);

        Map<UnitId, Province> locations = new LinkedHashMap<>();
        locations.put(par, Province.Par);
        locations.put(mar, Province.Mar);

        BoardState board = new BoardState(locations, Map.of());

        Map<UnitId, TacticalContext.KnownOrder> orders =
                new LinkedHashMap<>();

        Order originalSupport =
                Order.supportMove(mar, Province.Par, Province.Bur);

        TacticalContext.KnownOrder evidence = submitted(originalSupport);

        require(evidence.order() != originalSupport
                        && evidence.order().equals(originalSupport),
                "KnownOrder must snapshot the order values");

        orders.put(mar, evidence);

        TacticalContext context =
                new TacticalContext(RULESET, MOMENT, board, orders);

        locations.clear();
        orders.clear();

        require(context.board().locations().size() == 2
                        && context.orders().size() == 1,
                "Source collection mutation must not change the context");

        List<TacticMatch> findings =
                investigate(new SupportToMoveDetective(), context);

        TacticMatch match = findings.getFirst();

        rejects(UnsupportedOperationException.class,
                () -> findings.add(match),
                "Mutable investigation result");

        rejects(UnsupportedOperationException.class,
                () -> match.participants().clear(),
                "Mutable participants");

        rejects(UnsupportedOperationException.class,
                () -> match.missingOrders().clear(),
                "Mutable missing prerequisites");

        rejects(UnsupportedOperationException.class,
                () -> match.units(TacticMatch.Role.SUPPORTER).add(par),
                "Mutable role query result");

        rejects(UnsupportedOperationException.class,
                () -> context.orders().clear(),
                "Mutable context orders");

        rejects(UnsupportedOperationException.class,
                () -> context.unknownUnits().add(mar),
                "Mutable unknown-unit query");

        List<TacticMatch.Participant> participants =
                new ArrayList<>(match.participants());

        Set<UnitId> missing = new LinkedHashSet<>(match.missingOrders());

        TacticMatch copied = new TacticMatch(
                match.kind(),
                match.detectorVersion(),
                context,
                match.focus(),
                participants,
                missing);

        participants.clear();
        missing.clear();

        require(copied.equals(match),
                "TacticMatch must copy caller-supplied collections");

        require(context.orderOf(par).isEmpty(),
                "Investigating an incomplete pattern must not change its context");

    }


    // Shared detective contracts \\

    private static List<Detective> detectives() {
        return List.of(
                new SupportToMoveDetective(),
                new SupportToHoldDetective(),
                new SelfBounceDetective(),
                new BeleagueredGarrisonDetective());
    }

    private static List<TacticMatch> investigate(
            Detective detective,
            TacticalContext context
    ) {

        Map<UnitId, TacticalContext.KnownOrder> before =
                new LinkedHashMap<>(context.orders());

        List<TacticMatch> findings = detective.investigate(context);

        require(findings.equals(detective.detect(context)),
                "detect(...) must delegate consistently to investigate(...)");

        require(new HashSet<>(findings).size() == findings.size(),
                "Duplicate structural occurrences");

        require(context.orders().equals(before),
                "Investigation changed known order evidence");

        for (TacticMatch match : findings) {

            require(match.kind() == detective.kind(),
                    "Match kind differs from detective kind");

            require(match.detectorVersion().equals(detective.version()),
                    "Match version differs from detective version");

            require(match.context() == context,
                    "Match must retain the supplied context");

        }

        return findings;

    }

    private static TacticMatch only(
            Detective detective,
            TacticalContext context
    ) {

        List<TacticMatch> findings = investigate(detective, context);

        require(findings.size() == 1,
                "Expected one " + detective.kind()
                        + " match, found " + findings.size());

        return findings.getFirst();

    }

    private static List<Province> focuses(List<TacticMatch> findings) {

        List<Province> result = new ArrayList<>();

        for (TacticMatch match : findings)
            result.add(match.focus());

        return List.copyOf(result);

    }


    // UUID-independent comparison \\

    /**
     * Describes classified structure using current locations.<br><br>
     *
     * Record equality includes concrete unit identities. This projection
     * checks that UUIDs and creation origins do not change recognition,
     * roles, prerequisites, or ordering.
     */
    private static List<String> describe(List<TacticMatch> findings) {

        List<String> descriptions = new ArrayList<>();

        for (TacticMatch match : findings) {

            StringJoiner description = new StringJoiner("|");

            description.add(match.kind().name());
            description.add(match.detectorVersion());
            description.add(match.focus().name());
            description.add(Boolean.toString(match.completePattern()));

            for (TacticMatch.Participant participant : match.participants()) {

                UnitId unit = participant.unit();

                description.add(
                        participant.role().name() + ":"
                                + unit.owner().name() + ":"
                                + unit.unitType().name() + ":"
                                + match.context().board().locationOf(unit).name());

            }

            List<String> missingLocations = new ArrayList<>();

            for (UnitId unit : match.missingOrders())
                missingLocations.add(
                        match.context().board().locationOf(unit).name());

            Collections.sort(missingLocations);
            description.add("missing=" + missingLocations);

            descriptions.add(description.toString());

        }

        return List.copyOf(descriptions);

    }

    private static TacticalContext reidentify(TacticalContext original) {

        Map<UnitId, UnitId> replacements = new LinkedHashMap<>();
        Map<UnitId, Province> locations = new LinkedHashMap<>();

        for (Map.Entry<UnitId, Province> entry
                : original.board().locations().entrySet()) {

            UnitId old = entry.getKey();

            UnitId replacement = new UnitId(
                    uuid("replacement|" + old.value()),
                    old.owner(),
                    old.unitType(),
                    Province.Swi);

            replacements.put(old, replacement);
            locations.put(replacement, entry.getValue());

        }

        Map<UnitId, TacticalContext.KnownOrder> orders =
                new LinkedHashMap<>();

        for (Map.Entry<UnitId, TacticalContext.KnownOrder> entry
                : original.orders().entrySet()) {

            UnitId replacement = replacements.get(entry.getKey());
            TacticalContext.KnownOrder evidence = entry.getValue();
            Order order = evidence.order();

            orders.put(replacement, new TacticalContext.KnownOrder(
                    new Order(
                            replacement,
                            order.orderType(),
                            order.target(),
                            order.auxiliaryTarget()),
                    evidence.provenance()));

        }

        return new TacticalContext(
                original.rulesetId(),
                original.moment(),
                new BoardState(locations, original.board().owners()),
                orders);

    }


    // Fixtures \\

    private static UnitId army(Nation nation, Province location) {
        return unit(nation, UnitType.ARMY, location);
    }

    private static UnitId fleet(Nation nation, Province location) {
        return unit(nation, UnitType.FLEET, location);
    }

    private static UnitId unit(
            Nation nation,
            UnitType type,
            Province location
    ) {

        String key = "fixture|"
                + nation.name() + "|"
                + type.name() + "|"
                + location.name();

        return new UnitId(uuid(key), nation, type, location);

    }

    private static UUID uuid(String key) {
        return UUID.nameUUIDFromBytes(
                key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Convenience for initial-position fixtures only.
     *
     * Reidentified fixtures supply actual locations separately to test
     * that production code does not use creation origin as current location.
     */
    private static BoardState board(UnitId... units) {

        Map<UnitId, Province> locations = new LinkedHashMap<>();

        for (UnitId unit : units)
            if (locations.putIfAbsent(unit, unit.origin()) != null)
                throw new IllegalArgumentException(
                        "Duplicate fixture unit: " + unit);

        return new BoardState(locations, Map.of());

    }

    private static TacticalContext context(
            BoardState board,
            Order... submissions
    ) {

        Map<UnitId, TacticalContext.KnownOrder> orders =
                new LinkedHashMap<>();

        for (Order order : submissions)
            if (orders.putIfAbsent(order.unit(), submitted(order)) != null)
                throw new IllegalArgumentException(
                        "Duplicate fixture submission: " + order.unit());

        return new TacticalContext(RULESET, MOMENT, board, orders);

    }

    private static TacticalContext.KnownOrder submitted(Order order) {
        return new TacticalContext.KnownOrder(
                order,
                TacticalContext.Provenance.SUBMITTED);
    }


    // Check helpers \\

    private static void require(boolean condition, String message) {

        checks++;

        if (!condition)
            throw new AssertionError(message);

    }

    private static void rejects(
            Class<? extends RuntimeException> expected,
            Runnable action,
            String message
    ) {

        checks++;

        try {

            action.run();

        } catch (RuntimeException exception) {

            if (expected.isInstance(exception))
                return;

            throw new AssertionError(
                    message + ": expected " + expected.getSimpleName()
                            + ", found " + exception.getClass().getSimpleName(),
                    exception);

        }

        throw new AssertionError(
                message + ": expected " + expected.getSimpleName());

    }


}