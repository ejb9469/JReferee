package testing.selfcheck;

import analysis.tactics.*;
import analysis.tactics.detective.*;
import domain.*;
import game.*;
import phase.Order;
import phase.UnitId;

import java.nio.charset.StandardCharsets;
import java.util.*;


/**
 * Exact-set structural tests plus independent bounded reference enumeration.
 * The oracle examines submitted fields and builds boolean relations directly;
 * it calls neither detective primitives nor production graph/group helpers.
 */
public final class StructuralSupportMovementSelfCheck {


    // Constants \\

    private static final GameMoment MOMENT =
            new GameMoment(1903, GamePhase.SPRING_MOVEMENT);

    private static final List<Detective> DETECTIVES = List.of(
            new MutualHoldSupportDetective(),
            new SupportNetworkDetective(),
            new SupportingASupporterDetective(),
            new SupportingAConvoyFleetDetective(),
            new CrossPowerSupportDetective(),
            new MultinationalSupportedAttackDetective(),
            new CrossPowerContestDetective(),
            new MultiwayContestDetective(),
            new FollowTheLeaderDetective(),
            new ChainAdvanceDetective(),
            new VacateAndReplaceDetective(),
            new AttackFromSupportedDestinationDetective());


    // Fixture state \\

    private int checks;
    private int generated;


    // Construction \\

    private StructuralSupportMovementSelfCheck() {  }


    // Entry points \\

    public static void main(String[] args) {

        StructuralSupportMovementSelfCheck suite =
                new StructuralSupportMovementSelfCheck();

        suite.goldenSets();
        suite.adversarial();
        suite.boundedSupportDomain();
        suite.boundedMovementDomain();

        System.out.printf(
                "[CASE CLOSED] %d structural support/movement checks passed; "
                        + "%d generated contexts, 12 kinds.%n",
                suite.checks, suite.generated);

    }


    // Adversarial exact sets \\

    private void goldenSets() {

        Fixture f = fixture("golden",
                new Province[]{Province.Par, Province.Bur, Province.SpaSC,
                        Province.Mar, Province.Mun, Province.Ruh},
                new Nation[]{Nation.FRANCE, Nation.GERMANY, Nation.ENGLAND,
                        Nation.ITALY, Nation.FRANCE, Nation.GERMANY});

        TacticalContext support = context(f, new Order[]{
                Order.supportHold(f.units[0], Province.Bur),
                Order.supportHold(f.units[1], Province.Par),
                Order.convoy(f.units[2], Province.Bel, Province.Hol),
                Order.supportHold(f.units[3], Province.SpaNC),
                Order.supportHold(f.units[4], Province.Par),
                Order.supportHold(f.units[5], Province.Mun)}, false);

        exact(support, TacticKind.MUTUAL_HOLD_SUPPORT,
                "Bur:SUPPORTED_UNIT@Bur,SUPPORTED_UNIT@Par,SUPPORTER@Bur,SUPPORTER@Par");
        exact(support, TacticKind.SUPPORT_NETWORK,
                "Bur:SUPPORTED_UNIT@Bur,SUPPORTED_UNIT@Mun,SUPPORTED_UNIT@Par,"
                        + "SUPPORTER@Bur,SUPPORTER@Mun,SUPPORTER@Par,SUPPORTER@Ruh");
        exact(support, TacticKind.SUPPORTING_A_SUPPORTER,
                "Bur:SUPPORTED_UNIT@Bur,SUPPORTER@Par",
                "Par:SUPPORTED_UNIT@Par,SUPPORTER@Bur",
                "Par:SUPPORTED_UNIT@Par,SUPPORTER@Mun",
                "Mun:SUPPORTED_UNIT@Mun,SUPPORTER@Ruh");
        exact(support, TacticKind.SUPPORTING_A_CONVOY_FLEET,
                "Spa:SUPPORTED_UNIT@SpaSC,SUPPORTER@Mar");
        exact(support, TacticKind.CROSS_POWER_SUPPORT,
                "Bur:SUPPORTED_UNIT@Bur,SUPPORTER@Par",
                "Par:SUPPORTED_UNIT@Par,SUPPORTER@Bur",
                "Spa:SUPPORTED_UNIT@SpaSC,SUPPORTER@Mar",
                "Mun:SUPPORTED_UNIT@Mun,SUPPORTER@Ruh");

        TacticalContext attack = context(f, new Order[]{
                Order.move(f.units[0], Province.SpaNC),
                Order.supportMove(f.units[1], Province.Par, Province.SpaSC),
                Order.move(f.units[2], Province.Bur),
                Order.supportMove(f.units[3], Province.Par, Province.Spa),
                Order.supportMove(f.units[4], Province.Par, Province.SpaSC),
                Order.move(f.units[5], Province.Spa)}, false);

        exact(attack, TacticKind.MULTINATIONAL_SUPPORTED_ATTACK,
                "Spa:SUPPORTED_UNIT@Par,SUPPORTER@Bur,SUPPORTER@Mar,SUPPORTER@Mun");
        exact(attack, TacticKind.CROSS_POWER_CONTEST,
                "Spa:ATTACKER@Par,ATTACKER@Ruh");
        exact(attack, TacticKind.MULTIWAY_CONTEST);
        exact(attack, TacticKind.FOLLOW_THE_LEADER,
                "Spa:ISSUER@Par,REFERENCED_UNIT@SpaSC",
                "Spa:ISSUER@Ruh,REFERENCED_UNIT@SpaSC");
        exact(attack, TacticKind.VACATE_AND_REPLACE,
                "Spa:ISSUER@Par,REFERENCED_UNIT@SpaSC",
                "Spa:ISSUER@Ruh,REFERENCED_UNIT@SpaSC");
        exact(attack, TacticKind.CHAIN_ADVANCE,
                "Par:ISSUER@Par,ISSUER@SpaSC",
                "Ruh:ISSUER@Ruh,ISSUER@SpaSC");
        exact(attack, TacticKind.ATTACK_FROM_SUPPORTED_DESTINATION,
                "Spa:ATTACKER@SpaSC,SUPPORTED_UNIT@Par,"
                        + "SUPPORTER@Bur,SUPPORTER@Mar,SUPPORTER@Mun");

        TacticalContext contest = context(f, new Order[]{
                Order.move(f.units[0], Province.Gas),
                Order.move(f.units[1], Province.Gas),
                Order.move(f.units[2], Province.Gas),
                Order.move(f.units[3], Province.Gas),
                Order.move(f.units[4], Province.Gas),
                Order.move(f.units[5], Province.Gas)}, false);

        exact(contest, TacticKind.CROSS_POWER_CONTEST,
                "Gas:ATTACKER@Bur,ATTACKER@Mar,ATTACKER@Mun,"
                        + "ATTACKER@Par,ATTACKER@Ruh,ATTACKER@SpaSC");
        exact(contest, TacticKind.MULTIWAY_CONTEST,
                "Gas:ATTACKER@Bur,ATTACKER@Mar,ATTACKER@Mun,"
                        + "ATTACKER@Par,ATTACKER@Ruh,ATTACKER@SpaSC");

        TacticalContext split = context(f, new Order[]{
                Order.move(f.units[0], Province.Gas),
                Order.move(f.units[1], Province.Gas),
                Order.move(f.units[2], Province.Gas),
                Order.move(f.units[3], Province.Bel),
                Order.move(f.units[4], Province.Bel),
                Order.move(f.units[5], Province.Bel)}, false);

        exact(split, TacticKind.CROSS_POWER_CONTEST,
                "Gas:ATTACKER@Bur,ATTACKER@Par,ATTACKER@SpaSC",
                "Bel:ATTACKER@Mar,ATTACKER@Mun,ATTACKER@Ruh");
        exact(split, TacticKind.MULTIWAY_CONTEST,
                "Gas:ATTACKER@Bur,ATTACKER@Par,ATTACKER@SpaSC",
                "Bel:ATTACKER@Mar,ATTACKER@Mun,ATTACKER@Ruh");

        TacticalContext branches = context(f, new Order[]{
                Order.move(f.units[0], Province.Bur),
                Order.move(f.units[1], Province.Mar),
                Order.move(f.units[2], Province.Bur),
                Order.move(f.units[3], Province.Gas),
                Order.move(f.units[4], Province.SpaNC),
                Order.move(f.units[5], Province.Gas)}, false);

        exact(branches, TacticKind.CHAIN_ADVANCE,
                "Bur:ISSUER@Bur,ISSUER@Mar,ISSUER@Par",
                "Bur:ISSUER@Bur,ISSUER@Mar,ISSUER@Mun,ISSUER@SpaSC");

    }

    private void adversarial() {

        Fixture f = fixture("adversarial",
                new Province[]{Province.Par, Province.Bur, Province.SpaSC,
                        Province.Mar, Province.Mun, Province.Ruh},
                new Nation[]{Nation.FRANCE, Nation.GERMANY, Nation.ENGLAND,
                        Nation.ITALY, Nation.FRANCE, Nation.GERMANY});

        assertAll(f, new Order[]{
                Order.supportHold(f.units[0], Province.Bur),
                Order.supportHold(f.units[1], Province.Par),
                Order.convoy(f.units[2], Province.Bel, Province.Hol),
                Order.supportHold(f.units[3], Province.SpaNC),
                Order.supportHold(f.units[4], Province.Par),
                Order.supportHold(f.units[5], Province.Mun)});

        assertAll(f, new Order[]{
                Order.move(f.units[0], Province.SpaNC),
                Order.supportMove(f.units[1], Province.Par, Province.SpaSC),
                Order.move(f.units[2], Province.Bur),
                Order.supportMove(f.units[3], Province.Par, Province.Spa),
                Order.supportMove(f.units[4], Province.Par, Province.SpaSC),
                Order.move(f.units[5], Province.Spa)});

        assertAll(f, new Order[]{
                Order.move(f.units[0], Province.Bur),
                Order.move(f.units[1], Province.Mar),
                Order.move(f.units[2], Province.Bur),
                Order.move(f.units[3], Province.Gas),
                Order.move(f.units[4], Province.SpaSC),
                Order.move(f.units[5], Province.Gas)});

        // A three-cycle and its feeder must not become an open chain.
        assertAll(f, new Order[]{
                Order.move(f.units[0], Province.Bur),
                Order.move(f.units[1], Province.Mar),
                Order.move(f.units[2], Province.Bur),
                Order.move(f.units[3], Province.Par),
                Order.supportMove(f.units[4], Province.Par, Province.Bur),
                Order.supportMove(f.units[5], Province.Par, Province.Bur)});

        // Known contradictions, unknown recipients, absent references, and self edges.
        assertAll(f, new Order[]{
                Order.hold(f.units[0]),
                Order.supportMove(f.units[1], Province.Par, Province.Spa),
                null,
                Order.supportHold(f.units[3], Province.SpaNC),
                Order.supportHold(f.units[4], Province.Mun),
                Order.supportMove(f.units[5], Province.Bel, Province.Gas)});

        assertAll(f, new Order[]{
                Order.move(f.units[0], Province.Par),
                Order.move(f.units[1], Province.SpaNC),
                Order.move(f.units[2], Province.SpaSC),
                Order.supportMove(f.units[3], Province.Par, Province.Par),
                Order.supportMove(f.units[4], Province.Par, Province.Par),
                Order.supportHold(f.units[5], Province.Par)});

        Fixture same = fixture("same-power", f.locations,
                new Nation[]{Nation.FRANCE, Nation.FRANCE, Nation.FRANCE,
                        Nation.FRANCE, Nation.FRANCE, Nation.FRANCE});

        assertAll(same, new Order[]{
                Order.move(same.units[0], Province.Gas),
                Order.move(same.units[1], Province.Gas),
                Order.move(same.units[2], Province.Gas),
                Order.supportMove(same.units[3], Province.Par, Province.Gas),
                Order.supportMove(same.units[4], Province.Par, Province.Gas),
                Order.supportHold(same.units[5], Province.Mar)});

        // The mover's power must not count as a second supporting power.
        assertAll(f, new Order[]{
                Order.move(f.units[0], Province.Gas),
                Order.supportMove(f.units[1], Province.Par, Province.Gas),
                null, null, null,
                Order.supportMove(f.units[5], Province.Par, Province.Gas)});

        // A CONVOY submission by an army is not a convoy fleet.
        assertAll(f, new Order[]{
                new Order(f.units[0], OrderType.CONVOY, Province.Bel, Province.Hol),
                Order.supportHold(f.units[1], Province.Par),
                Order.convoy(f.units[2], Province.Bel, Province.Hol),
                Order.supportHold(f.units[3], Province.SpaNC),
                Order.supportHold(f.units[4], Province.Par),
                null});

        // Disconnected support groups must remain separate maximal components.
        assertAll(f, new Order[]{
                Order.hold(f.units[0]),
                Order.supportHold(f.units[1], Province.Par),
                Order.supportHold(f.units[2], Province.Par),
                Order.hold(f.units[3]),
                Order.supportHold(f.units[4], Province.Mar),
                Order.supportHold(f.units[5], Province.Mar)});

        mutationAndImmutability(f);

    }


    // Independent bounded domains \\

    private void boundedSupportDomain() {

        Fixture f = fixture("bounded-support",
                new Province[]{Province.Par, Province.Bur, Province.SpaSC},
                new Nation[]{Nation.FRANCE, Nation.GERMANY, Nation.ENGLAND});

        List<List<Order>> options = new ArrayList<>();

        for (UnitId unit : f.units) {

            List<Order> choices = new ArrayList<>();
            choices.add(null);
            choices.add(Order.hold(unit));

            for (Province destination : new Province[]{
                    Province.Par, Province.Bur, Province.SpaNC, Province.Gas})
                choices.add(Order.move(unit, destination));

            for (Province recipient : new Province[]{
                    Province.Par, Province.Bur, Province.Spa, Province.Bel})
                choices.add(Order.supportHold(unit, recipient));

            for (Province recipient : new Province[]{
                    Province.Par, Province.Bur, Province.Spa})
                for (Province destination : new Province[]{
                        Province.Par, Province.Bur, Province.SpaSC, Province.Gas})
                    choices.add(Order.supportMove(unit, recipient, destination));

            choices.add(new Order(unit, OrderType.CONVOY, Province.Bel, Province.Hol));
            options.add(choices);

        }

        for (Order first : options.get(0))
            for (Order second : options.get(1))
                for (Order third : options.get(2)) {
                    assertAll(f, new Order[]{first, second, third});
                    generated++;
                }

    }

    private void boundedMovementDomain() {

        Fixture f = fixture("bounded-movement",
                new Province[]{Province.Par, Province.Bur, Province.SpaSC, Province.Mar},
                new Nation[]{Nation.FRANCE, Nation.FRANCE, Nation.GERMANY, Nation.ITALY});

        List<List<Order>> options = new ArrayList<>();

        for (UnitId unit : f.units) {
            List<Order> choices = new ArrayList<>();
            choices.add(null);
            choices.add(Order.hold(unit));
            for (Province destination : new Province[]{
                    Province.Par, Province.Bur, Province.SpaNC, Province.Mar, Province.Gas})
                choices.add(Order.move(unit, destination));
            options.add(choices);
        }

        for (Order first : options.get(0))
            for (Order second : options.get(1))
                for (Order third : options.get(2))
                    for (Order fourth : options.get(3)) {
                        assertAll(f, new Order[]{first, second, third, fourth});
                        generated++;
                    }

    }


    // Independent reference model \\

    private Map<TacticKind, Set<String>> reference(Fixture f, Order[] orders) {

        int count = f.units.length;
        boolean[][] hold = new boolean[count][count];
        boolean[][] moveSupport = new boolean[count][count];
        boolean[] moving = new boolean[count];
        Province[] destinations = new Province[count];
        Map<TacticKind, Set<String>> expected = new EnumMap<>(TacticKind.class);

        for (Detective detective : DETECTIVES)
            expected.put(detective.kind(), new TreeSet<>());

        for (int i = 0; i < count; i++)
            if (orders[i] != null && orders[i].orderType() == OrderType.MOVE) {
                destinations[i] = Province.canonical(orders[i].target());
                moving[i] = destinations[i] != Province.canonical(f.locations[i]);
            }

        for (int i = 0; i < count; i++) {

            Order submission = orders[i];

            if (submission == null || submission.orderType() != OrderType.SUPPORT)
                continue;

            for (int j = 0; j < count; j++) {

                Order recipient = orders[j];

                if (i == j || recipient == null
                        || Province.canonical(submission.target())
                        != Province.canonical(f.locations[j]))
                    continue;

                if (submission.auxiliaryTarget() == null)
                    hold[i][j] = recipient.orderType() == OrderType.HOLD
                            || recipient.orderType() == OrderType.SUPPORT
                            || recipient.orderType() == OrderType.CONVOY;
                else
                    moveSupport[i][j] = recipient.orderType() == OrderType.MOVE
                            && Province.canonical(submission.auxiliaryTarget())
                            == destinations[j];

                if (!hold[i][j] && !moveSupport[i][j])
                    continue;

                Province focus = hold[i][j]
                        ? Province.canonical(f.locations[j]) : destinations[j];
                List<String> roles = List.of(role(f, "SUPPORTER", i),
                        role(f, "SUPPORTED_UNIT", j));

                if (f.units[i].owner() != f.units[j].owner())
                    add(expected, TacticKind.CROSS_POWER_SUPPORT, focus, roles);

                if (hold[i][j] && recipient.orderType() == OrderType.SUPPORT)
                    add(expected, TacticKind.SUPPORTING_A_SUPPORTER, focus, roles);

                if (hold[i][j] && recipient.orderType() == OrderType.CONVOY
                        && f.units[j].unitType() == UnitType.FLEET)
                    add(expected, TacticKind.SUPPORTING_A_CONVOY_FLEET, focus, roles);

            }

        }

        for (int i = 0; i < count; i++)
            for (int j = i + 1; j < count; j++)
                if (hold[i][j] && hold[j][i])
                    add(expected, TacticKind.MUTUAL_HOLD_SUPPORT,
                            firstLocation(f, List.of(i, j)),
                            List.of(role(f, "SUPPORTER", i), role(f, "SUPPORTER", j),
                                    role(f, "SUPPORTED_UNIT", i), role(f, "SUPPORTED_UNIT", j)));

        // Boolean transitive closure, deliberately unlike the production graph traversal.
        boolean[][] connected = new boolean[count][count];

        for (int i = 0; i < count; i++)
            for (int j = 0; j < count; j++)
                connected[i][j] = i == j || hold[i][j] || hold[j][i]
                        || moveSupport[i][j] || moveSupport[j][i];

        for (int k = 0; k < count; k++)
            for (int i = 0; i < count; i++)
                for (int j = 0; j < count; j++)
                    connected[i][j] |= connected[i][k] && connected[k][j];

        for (int root = 0; root < count; root++) {

            boolean first = true;
            for (int j = 0; j < root; j++)
                first &= !connected[root][j];
            if (!first)
                continue;

            List<Integer> component = new ArrayList<>();
            Set<String> roles = new TreeSet<>();
            int edges = 0;

            for (int i = 0; i < count; i++)
                if (connected[root][i]) {
                    component.add(i);
                    for (int j = 0; j < count; j++)
                        if (hold[i][j] || moveSupport[i][j]) {
                            edges++;
                            roles.add(role(f, "SUPPORTER", i));
                            roles.add(role(f, "SUPPORTED_UNIT", j));
                        }
                }

            if (edges >= 2)
                add(expected, TacticKind.SUPPORT_NETWORK,
                        firstLocation(f, component), roles);

        }

        for (int recipient = 0; recipient < count; recipient++) {

            if (!moving[recipient])
                continue;

            Set<Nation> powers = EnumSet.noneOf(Nation.class);
            List<String> supportRoles = new ArrayList<>();
            supportRoles.add(role(f, "SUPPORTED_UNIT", recipient));
            int supportCount = 0;

            for (int supporter = 0; supporter < count; supporter++)
                if (moveSupport[supporter][recipient]) {
                    supportCount++;
                    powers.add(f.units[supporter].owner());
                    supportRoles.add(role(f, "SUPPORTER", supporter));
                }

            if (powers.size() >= 2)
                add(expected, TacticKind.MULTINATIONAL_SUPPORTED_ATTACK,
                        destinations[recipient], supportRoles);

            for (int outgoing = 0; outgoing < count; outgoing++)
                if (supportCount > 0 && outgoing != recipient && moving[outgoing]
                        && Province.canonical(f.locations[outgoing]) == destinations[recipient]) {
                    List<String> roles = new ArrayList<>(supportRoles);
                    roles.add(role(f, "ATTACKER", outgoing));
                    add(expected, TacticKind.ATTACK_FROM_SUPPORTED_DESTINATION,
                            destinations[recipient], roles);
                }

        }

        Set<Province> examined = EnumSet.noneOf(Province.class);

        for (int i = 0; i < count; i++)
            if (moving[i] && examined.add(destinations[i])) {

                Set<Nation> powers = EnumSet.noneOf(Nation.class);
                List<String> roles = new ArrayList<>();

                for (int j = 0; j < count; j++)
                    if (moving[j] && destinations[j] == destinations[i]) {
                        powers.add(f.units[j].owner());
                        roles.add(role(f, "ATTACKER", j));
                    }

                if (powers.size() >= 2)
                    add(expected, TacticKind.CROSS_POWER_CONTEST, destinations[i], roles);
                if (powers.size() >= 3)
                    add(expected, TacticKind.MULTIWAY_CONTEST, destinations[i], roles);

            }

        boolean[][] linked = new boolean[count][count];

        for (int i = 0; i < count; i++)
            for (int j = 0; j < count; j++)
                if (i != j && moving[i] && moving[j]
                        && destinations[i] == Province.canonical(f.locations[j])) {
                    linked[i][j] = true;
                    List<String> roles = List.of(role(f, "ISSUER", i),
                            role(f, "REFERENCED_UNIT", j));
                    add(expected, TacticKind.FOLLOW_THE_LEADER, destinations[i], roles);
                    add(expected, TacticKind.VACATE_AND_REPLACE, destinations[i], roles);
                }

        // Enumerate every simple permutation, then reject extendible endpoints.
        // This is independent of the production start-node traversal algorithm.
        enumeratePaths(f, moving, linked, new ArrayList<>(), expected);
        return expected;

    }

    private void enumeratePaths(
            Fixture f,
            boolean[] moving,
            boolean[][] linked,
            List<Integer> path,
            Map<TacticKind, Set<String>> expected
    ) {

        if (path.size() >= 2) {

            boolean maximal = true;
            int first = path.getFirst();
            int last = path.getLast();

            for (int i = 0; i < moving.length; i++)
                maximal &= !linked[i][first] && !linked[last][i];

            if (maximal) {
                List<String> roles = path.stream()
                        .map(i -> role(f, "ISSUER", i)).toList();
                add(expected, TacticKind.CHAIN_ADVANCE,
                        firstLocation(f, path), roles);
            }

        }

        for (int i = 0; i < moving.length; i++)
            if (moving[i] && !path.contains(i)
                    && (path.isEmpty() || linked[path.getLast()][i])) {
                path.add(i);
                enumeratePaths(f, moving, linked, path, expected);
                path.removeLast();
            }

    }


    // Exact-set comparison \\

    private void exact(TacticalContext context, TacticKind kind, String... expected) {

        Detective detective = DETECTIVES.stream()
                .filter(candidate -> candidate.kind() == kind).findFirst().orElseThrow();
        List<TacticMatch> actual = detective.investigate(context);
        Set<String> signatures = new TreeSet<>();

        for (TacticMatch match : actual)
            signatures.add(signature(match));

        require(actual.size() == signatures.size()
                        && signatures.equals(new TreeSet<>(List.of(expected))),
                "Golden complete set mismatch for " + kind + ": " + signatures);

    }

    private void assertAll(Fixture f, Order[] orders) {

        TacticalContext context = context(f, orders, false);
        Map<TacticKind, Set<String>> expected = reference(f, orders);

        for (Detective detective : DETECTIVES) {

            List<TacticMatch> matches = detective.investigate(context);
            Set<String> actual = new TreeSet<>();

            for (TacticMatch match : matches) {
                require(match.context() == context && match.kind() == detective.kind()
                                && match.detectorVersion().equals(detective.version())
                                && match.completePattern(),
                        "Evidence/version/completeness mismatch");
                require(actual.add(signature(match)), "Duplicate finding");
            }

            require(actual.equals(expected.get(detective.kind())),
                    detective.kind() + " expected " + expected.get(detective.kind())
                            + ", found " + actual + " for " + Arrays.toString(orders));

        }

    }

    private static String signature(TacticMatch match) {

        List<String> roles = match.participants().stream()
                .map(participant -> participant.role().name() + "@"
                        + match.context().board().locationOf(participant.unit()).name())
                .sorted().toList();

        return match.focus().name() + ":" + String.join(",", roles);

    }

    private static String role(Fixture f, String role, int unit) {
        return role + "@" + f.locations[unit].name();
    }

    private static void add(
            Map<TacticKind, Set<String>> expected,
            TacticKind kind,
            Province focus,
            Collection<String> roles
    ) {

        List<String> sorted = roles.stream().sorted().toList();
        expected.get(kind).add(focus.name() + ":" + String.join(",", sorted));

    }

    private static Province firstLocation(Fixture f, Collection<Integer> indices) {

        return indices.stream().map(i -> Province.canonical(f.locations[i]))
                .min(Comparator.comparing(Enum::name)).orElseThrow();

    }


    // Mutation, UUID, and evidence boundaries \\

    private void mutationAndImmutability(Fixture f) {

        Order[] orders = {
                Order.move(f.units[0], Province.SpaNC),
                Order.supportMove(f.units[1], Province.Par, Province.SpaSC),
                Order.move(f.units[2], Province.Gas),
                Order.supportMove(f.units[3], Province.Par, Province.Spa),
                Order.supportMove(f.units[4], Province.Par, Province.SpaNC),
                Order.move(f.units[5], Province.SpaSC)};

        TacticalContext normal = context(f, orders, false);
        checkVariant(f, orders);
        checkVariant(f, new Order[]{
                Order.supportHold(f.units[0], Province.Bur),
                Order.supportHold(f.units[1], Province.Par),
                Order.convoy(f.units[2], Province.Bel, Province.Hol),
                Order.supportHold(f.units[3], Province.SpaNC),
                Order.supportHold(f.units[4], Province.Par),
                Order.supportHold(f.units[5], Province.Mun)});
        checkVariant(f, Arrays.stream(f.units)
                .map(unit -> Order.move(unit, Province.Gas)).toArray(Order[]::new));

        require(normal.board().locationOf(f.units[2]) == Province.SpaSC
                        && normal.orders().get(f.units[0]).order().target() == Province.SpaNC
                        && normal.orders().get(f.units[1]).order().auxiliaryTarget() == Province.SpaSC,
                "Canonical matching altered exact submitted coasts");

        MutableSubmission submitted = new MutableSubmission(f.units[0], Province.SpaNC);
        Order[] mutableOrders = orders.clone();
        mutableOrders[0] = submitted;
        Map<UnitId, Province> locations = new LinkedHashMap<>(f.board.locations());
        Map<UnitId, TacticalContext.KnownOrder> known = new LinkedHashMap<>();

        for (Order order : mutableOrders)
            known.put(order.unit(), new TacticalContext.KnownOrder(
                    order, TacticalContext.Provenance.PLANNED));

        TacticalContext snapshot = new TacticalContext("structural-self-check", MOMENT,
                new BoardState(locations, Map.of()), known);
        submitted.destination = Province.Bel;
        locations.clear();
        known.clear();

        for (Detective detective : DETECTIVES)
            require(detective.investigate(snapshot).stream()
                            .map(StructuralSupportMovementSelfCheck::signature).toList()
                            .equals(detective.investigate(normal).stream()
                                    .map(StructuralSupportMovementSelfCheck::signature).toList()),
                    "Caller mutation altered evidence");

        immutable(() -> snapshot.orders().clear());
        immutable(() -> snapshot.board().locations().clear());

    }

    private void checkVariant(Fixture f, Order[] orders) {

        TacticalContext normal = context(f, orders, false);
        TacticalContext reversed = context(f, orders, true);
        Fixture renamed = fixture("uuid-renamed", f.locations,
                Arrays.stream(f.units).map(UnitId::owner).toArray(Nation[]::new));
        Order[] renamedOrders = new Order[orders.length];

        for (int i = 0; i < orders.length; i++)
            renamedOrders[i] = new Order(renamed.units[i], orders[i].orderType(),
                    orders[i].target(), orders[i].auxiliaryTarget());

        TacticalContext renamedContext = context(renamed, renamedOrders, true);

        for (Detective detective : DETECTIVES) {

            List<TacticMatch> before = detective.investigate(normal);
            require(before.equals(detective.investigate(reversed)),
                    "Input order changed output order or contents");
            require(before.stream().map(StructuralSupportMovementSelfCheck::signature).toList()
                            .equals(detective.investigate(renamedContext).stream()
                                    .map(StructuralSupportMovementSelfCheck::signature).toList()),
                    "UUID changed structure or presentation");

            immutable(() -> before.add(null));

            for (TacticMatch match : before) {
                immutable(() -> match.participants().clear());
                immutable(() -> match.missingOrders().add(f.units[0]));
                immutable(() -> match.units(TacticMatch.Role.SUPPORTER).add(f.units[0]));
            }

            detective.investigate(context(f, new Order[f.units.length], false));
            require(before.equals(detective.investigate(normal)),
                    "Detective reuse mutated prior results");

        }

    }

    private static final class MutableSubmission extends Order {

        private Province destination;

        private MutableSubmission(UnitId unit, Province destination) {
            super(unit, OrderType.MOVE, destination, null);
            this.destination = destination;
        }

        @Override
        public Province target() {
            return destination;
        }

    }


    // Fixtures \\

    private static Fixture fixture(String key, Province[] locations, Nation[] nations) {

        UnitId[] units = new UnitId[locations.length];
        Map<UnitId, Province> position = new LinkedHashMap<>();

        for (int i = 0; i < units.length; i++) {
            units[i] = new UnitId(
                    UUID.nameUUIDFromBytes((key + "|" + i).getBytes(StandardCharsets.UTF_8)),
                    nations[i], i == 2 ? UnitType.FLEET : UnitType.ARMY, Province.Mos);
            position.put(units[i], locations[i]);
        }

        return new Fixture(units, locations.clone(), new BoardState(position, Map.of()));

    }

    private static TacticalContext context(Fixture f, Order[] orders, boolean reverse) {

        Map<UnitId, TacticalContext.KnownOrder> known = new LinkedHashMap<>();
        Map<UnitId, Province> locations = new LinkedHashMap<>();

        for (int offset = 0; offset < orders.length; offset++) {
            int i = reverse ? orders.length - 1 - offset : offset;
            locations.put(f.units[i], f.locations[i]);
            if (orders[i] != null)
                known.put(f.units[i], new TacticalContext.KnownOrder(
                        orders[i], TacticalContext.Provenance.SUBMITTED));
        }

        return new TacticalContext("structural-self-check", MOMENT,
                new BoardState(locations, Map.of()), known);

    }

    private record Fixture(UnitId[] units, Province[] locations, BoardState board) { }


    // Assertions \\

    private void require(boolean condition, String message) {

        checks++;
        if (!condition)
            throw new AssertionError(message);

    }

    private void immutable(Runnable mutation) {

        try {
            mutation.run();
        } catch (UnsupportedOperationException expected) {
            checks++;
            return;
        }

        throw new AssertionError("Mutable result or evidence");

    }


}
