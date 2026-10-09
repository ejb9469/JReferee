package testing.selfcheck;

import analysis.tactics.*;
import analysis.tactics.detective.BeleagueredGarrisonDetective;
import domain.Nation;
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
 * Structural checks for supported-attack garrison recognition.<br><br>
 *
 * At least two distinct incoming moves must each have a matching known
 * support-to-move order. Unsupported attackers never qualify.
 *
 * <p>No adjudication is performed. Matching submitted support is not
 * proof of legal or effective support.</p>
 */
public final class BeleagueredGarrisonSelfCheck {


    // Constants \\

    private static final GameMoment MOMENT =
            new GameMoment(1902, GamePhase.SPRING_MOVEMENT);


    // Fixture state \\

    private final UnitId bur = army(Nation.GERMANY, Province.Bur);
    private final UnitId par = army(Nation.FRANCE, Province.Par);
    private final UnitId mun = army(Nation.ITALY, Province.Mun);

    private final UnitId gas = army(Nation.FRANCE, Province.Gas);
    private final UnitId ruh = army(Nation.ITALY, Province.Ruh);
    private final UnitId bel = army(Nation.ENGLAND, Province.Bel);

    private final UnitId mar = army(Nation.FRANCE, Province.Mar);
    private final UnitId pic = army(Nation.FRANCE, Province.Pic);

    private final BoardState board =
            board(bur, par, mun, gas, ruh, bel, mar, pic);

    private final Order first = Order.move(par, Province.Bur);
    private final Order second = Order.move(mun, Province.Bur);

    private final Order supportFirst =
            Order.supportMove(gas, Province.Par, Province.Bur);

    private final Order supportSecond =
            Order.supportMove(ruh, Province.Mun, Province.Bur);

    private final BeleagueredGarrisonDetective detective =
            new BeleagueredGarrisonDetective();

    private int checks;


    // Construction \\

    private BeleagueredGarrisonSelfCheck() {  }


    // Entry points \\

    public static void main(String[] args) {

        System.out.println("[OPEN FILE] The supported siege");

        int checks = runChecks();

        System.out.printf(
                "[CASE CLOSED] %d garrison checks passed.%n",
                checks);

    }

    /**
     * Also callable from the broader DetectiveSelfCheck runner.
     */
    public static int runChecks() {

        BeleagueredGarrisonSelfCheck suite =
                new BeleagueredGarrisonSelfCheck();

        suite.threshold();
        suite.supportReferences();
        suite.participants();
        suite.occupancyAndInformation();

        return suite.checks;

    }


    // Supported-attack threshold \\

    private void threshold() {

        none(context(board, first, second),
                "Two unsupported moves must not qualify");

        none(context(board, first, second, supportFirst),
                "One supported and one unsupported move must not qualify");

        none(context(
                        board,
                        first,
                        supportFirst,
                        Order.supportMove(ruh, Province.Par, Province.Bur)),
                "Several supports for one mover are still one attack");

        none(context(
                        board,
                        first,
                        second,
                        supportFirst,
                        Order.supportMove(ruh, Province.Par, Province.Bur)),
                "Supporting one of two movers twice must not qualify both");

        TacticMatch match = only(context(
                board, first, second, supportFirst, supportSecond));

        require(match.focus() == Province.Bur,
                "Wrong garrison focus");

        require(match.units(TacticMatch.Role.ATTACKER)
                        .equals(List.of(mun, par)),
                "Expected two supported attackers in location order");

        require(match.units(TacticMatch.Role.SUPPORTER)
                        .equals(List.of(gas, ruh)),
                "Both supporting orders must appear as evidence");

        require(match.units(TacticMatch.Role.DEFENDER).equals(List.of(bur)),
                "Wrong original occupant");

    }


    // Support-reference matching \\

    private void supportReferences() {

        List<Order> nonMatching = List.of(
                Order.supportHold(ruh, Province.Mun),
                Order.supportMove(ruh, Province.Mar, Province.Bur),
                Order.supportMove(ruh, Province.Mun, Province.Bel),
                Order.hold(ruh));

        for (Order order : nonMatching)
            none(context(board, first, second, supportFirst, order),
                    "Nonmatching support must not qualify the second attack: "
                            + order);

        none(context(board, first, supportFirst, supportSecond),
                "Support for an unknown move does not establish an attack");

        none(context(
                        board,
                        first,
                        Order.hold(mun),
                        supportFirst,
                        supportSecond),
                "Support for a known HOLD must not qualify as supported movement");

        TacticMatch foreign = only(context(
                board,
                first,
                second,
                supportFirst,
                Order.supportMove(bel, Province.Mun, Province.Bur)));

        require(foreign.units(TacticMatch.Role.SUPPORTER)
                        .equals(List.of(bel, gas)),
                "Support may come from a third power");

        UnitId lvp = army(Nation.ENGLAND, Province.Lvp);

        TacticMatch impossibleSupport = only(context(
                board(bur, par, mun, gas, lvp),
                first,
                second,
                supportFirst,
                Order.supportMove(lvp, Province.Mun, Province.Bur)));

        require(impossibleSupport.completePattern(),
                "Structural recognition must not adjudicate support geography");

        TacticMatch attackedSupport = only(context(
                board,
                first,
                second,
                supportFirst,
                supportSecond,
                Order.move(mar, Province.Gas)));

        require(attackedSupport.units(TacticMatch.Role.SUPPORTER).contains(gas),
                "An attack on a supporter must not trigger implicit adjudication");

    }


    // Participants and grouping \\

    private void participants() {

        TacticMatch mixed = only(context(
                board,
                first,
                second,
                supportFirst,
                supportSecond,
                Order.move(mar, Province.Bur)));

        require(mixed.units(TacticMatch.Role.ATTACKER)
                        .equals(List.of(mun, par)),
                "Unsupported third attacker must be excluded");

        require(mixed.participants().size() == 5,
                "Expected one defender, two attackers, and two supporters");

        for (TacticMatch.Participant participant : mixed.participants())
            require(!participant.unit().equals(mar),
                    "Unsupported mover leaked into garrison evidence");

        TacticMatch extraSupport = only(context(
                board,
                first,
                second,
                supportFirst,
                supportSecond,
                Order.supportMove(bel, Province.Par, Province.Bur)));

        require(extraSupport.units(TacticMatch.Role.ATTACKER).size() == 2,
                "Additional support must not create another attacker");

        require(extraSupport.units(TacticMatch.Role.SUPPORTER)
                        .equals(List.of(bel, gas, ruh)),
                "All matching supporters must be retained");

        TacticMatch triple = only(context(
                board,
                first,
                second,
                supportFirst,
                supportSecond,
                Order.move(mar, Province.Bur),
                Order.supportMove(bel, Province.Mar, Province.Bur)));

        require(triple.units(TacticMatch.Role.ATTACKER)
                        .equals(List.of(mar, mun, par)),
                "Three supported attacks must form one grouped occurrence");

        require(triple.units(TacticMatch.Role.SUPPORTER)
                        .equals(List.of(bel, gas, ruh)),
                "Three supported attacks must retain all support evidence");

        UnitId friendlyMun = army(Nation.GERMANY, Province.Mun);

        TacticMatch friendly = only(context(
                board(bur, par, friendlyMun, gas, ruh),
                first,
                Order.move(friendlyMun, Province.Bur),
                supportFirst,
                supportSecond));

        require(friendly.units(TacticMatch.Role.ATTACKER).contains(friendlyMun),
                "Ownership does not replace structural support matching");

    }


    // Occupancy and incomplete information \\

    private void occupancyAndInformation() {

        TacticalContext partial = context(
                board, first, second, supportFirst, supportSecond);

        TacticMatch match = only(partial);

        require(match.completePattern() && match.missingOrders().isEmpty(),
                "Known moves and supports complete the local pattern");

        require(!partial.complete() && partial.orderOf(bur).isEmpty(),
                "Unknown defender orders must remain unknown");

        none(context(
                        board(par, mun, gas, ruh),
                        first,
                        second,
                        supportFirst,
                        supportSecond),
                "Supported attacks into empty territory are not a garrison");

        none(context(
                        board,
                        first,
                        Order.move(mun, Province.Bel),
                        supportFirst,
                        Order.supportMove(ruh, Province.Mun, Province.Bel)),
                "Supported moves to different destinations must not be combined");

        require(only(context(
                        board,
                        first,
                        second,
                        supportFirst,
                        supportSecond,
                        Order.move(bur, Province.Bel))).completePattern(),
                "A departing occupant remains a structural candidate");

        none(context(
                        board,
                        first,
                        supportFirst,
                        Order.move(bur, Province.Bur),
                        Order.supportMove(ruh, Province.Bur, Province.Bur)),
                "A supported self-move by the occupant is not another attacker");

        none(context(board),
                "No known orders must produce no garrison finding");

        require(only(partial).equals(match),
                "Detective reuse changed a previous investigation");

    }


    // Investigation helpers \\

    private void none(TacticalContext context, String message) {
        require(detective.investigate(context).isEmpty(), message);
    }

    private TacticMatch only(TacticalContext context) {

        List<TacticMatch> findings = detective.investigate(context);

        require(findings.size() == 1,
                "Expected one garrison finding, found " + findings.size());

        TacticMatch match = findings.getFirst();

        require(match.context() == context
                        && match.kind() == TacticKind.BELEAGUERED_GARRISON,
                "Finding lost its context or category");

        return match;

    }


    // Fixtures \\

    private static UnitId army(Nation nation, Province location) {

        String key = "supported-garrison|"
                + nation.name() + "|" + location.name();

        return new UnitId(
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                nation,
                UnitType.ARMY,
                location);

    }

    private static BoardState board(UnitId... units) {

        Map<UnitId, Province> locations = new LinkedHashMap<>();

        for (UnitId unit : units)
            if (locations.putIfAbsent(unit, unit.origin()) != null)
                throw new IllegalArgumentException("Duplicate fixture unit");

        return new BoardState(locations, Map.of());

    }

    private static TacticalContext context(
            BoardState board,
            Order... orders
    ) {

        Map<UnitId, TacticalContext.KnownOrder> known = new LinkedHashMap<>();

        for (Order order : orders) {

            TacticalContext.KnownOrder evidence =
                    new TacticalContext.KnownOrder(
                            order, TacticalContext.Provenance.SUBMITTED);

            if (known.putIfAbsent(order.unit(), evidence) != null)
                throw new IllegalArgumentException("Duplicate fixture order");

        }

        return new TacticalContext(
                "supported-garrison-self-check-v1",
                MOMENT,
                board,
                known);

    }


    // Assertions \\

    private void require(boolean condition, String message) {

        checks++;

        if (!condition)
            throw new AssertionError(message);

    }


}