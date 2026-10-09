package testing.selfcheck;

import analysis.tactics.*;
import analysis.tactics.detective.*;
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
import java.util.function.Function;


/**
 * Standalone checks for DetectiveAgency.<br><br>
 *
 * Exercises standard investigations, overlapping categories, partial
 * information, registry isolation, ordering, and detective contract failures.
 *
 * <p>Uses explicit checks rather than Java assertions.
 * No adjudication or external corpus is required.</p>
 */
public final class DetectiveAgencySelfCheck {


    // Constants \\

    private static final String RULESET = "agency-self-check-v1";
    private static final String VERSION = "structure-v1";

    private static final GameMoment MOMENT =
            new GameMoment(1902, GamePhase.SPRING_MOVEMENT);


    // Check state \\

    private static int checks;


    // Construction \\

    private DetectiveAgencySelfCheck() {  }


    // Application entry point \\

    public static void main(String[] args) {

        checks = 0;

        standardInvestigation();
        incompleteInformation();
        registration();
        ordering();
        contractFailures();
        failurePropagation();

        System.out.printf(
                "Detective agency self-check passed: %d checks.%n",
                checks);

    }


    // Standard integration \\

    private static void standardInvestigation() {

        DetectiveAgency agency = new DetectiveAgency(standardDetectives());
        TacticalContext context = standardContext();

        List<TacticMatch> findings = agency.investigate(context);

        List<TacticKind> expectedKinds = List.of(
                TacticKind.SUPPORT_TO_MOVE,
                TacticKind.SUPPORT_TO_HOLD,
                TacticKind.SELF_BOUNCE,
                TacticKind.BELEAGUERED_GARRISON);

        require(agency.kinds().equals(expectedKinds),
                "Explicit registry must include the four supplied categories");

        require(kinds(findings).equals(List.of(
                        TacticKind.SUPPORT_TO_MOVE,
                        TacticKind.SUPPORT_TO_MOVE,
                        TacticKind.SUPPORT_TO_HOLD,
                        TacticKind.SELF_BOUNCE,
                        TacticKind.BELEAGUERED_GARRISON)),
                "Expected five occurrences in category order");

        TacticMatch selfBounce = findings.get(3);
        TacticMatch garrison = findings.get(4);

        require(selfBounce.focus() == Province.Bur
                        && garrison.focus() == Province.Bur,
                "Overlapping findings must retain the shared focus");

        require(selfBounce.units(TacticMatch.Role.ATTACKER).equals(
                        garrison.units(TacticMatch.Role.ATTACKER)),
                "Overlapping findings must preserve their common attackers");

        require(garrison.units(TacticMatch.Role.DEFENDER).size() == 1,
                "Garrison finding must retain its distinct defender role");

        require(garrison.units(TacticMatch.Role.SUPPORTER).equals(List.of(
                        army(Nation.FRANCE, Province.Gas),
                        army(Nation.FRANCE, Province.Mun))),
                "Garrison must include support evidence for both attacks");

        for (TacticMatch match : findings) {

            require(match.context() == context,
                    "Agency must preserve each finding's context");

            require(match.completePattern(),
                    "Standard fixture must have complete local patterns");

        }

        List<TacticMatch> individual = new ArrayList<>();

        for (TacticDetector detective : standardDetectives())
            individual.addAll(detective.detect(context));

        require(findings.equals(individual),
                "Agency must preserve the standard detectives' findings");

        rejects(UnsupportedOperationException.class,
                () -> findings.add(findings.getFirst()),
                "Mutable investigation result");

        rejects(UnsupportedOperationException.class,
                () -> agency.kinds().add(TacticKind.SELF_BOUNCE),
                "Mutable category listing");

        TacticalContext unknown = new TacticalContext(
                RULESET, MOMENT, context.board(), Map.of());

        require(agency.investigate(unknown).isEmpty(),
                "Agency must not retain findings from an earlier context");

        require(agency.investigate(context).equals(findings),
                "Repeated investigation must remain stable");

    }


    // Incomplete information \\

    private static void incompleteInformation() {

        UnitId par = army(Nation.FRANCE, Province.Par);
        UnitId gas = army(Nation.FRANCE, Province.Gas);

        BoardState board = board(par, gas);

        TacticalContext partial = context(
                board,
                Order.supportMove(gas, Province.Par, Province.Bur));

        DetectiveAgency agency = new DetectiveAgency();

        List<TacticMatch> findings = agency.investigate(partial);

        require(findings.size() == 1,
                "Partial fixture must retain one support relationship");

        TacticMatch match = findings.getFirst();

        require(match.kind() == TacticKind.SUPPORT_TO_MOVE,
                "Wrong category for incomplete support");

        require(!match.completePattern()
                        && match.missingOrders().equals(Set.of(par)),
                "Agency must preserve missing local prerequisites");

        require(partial.orderOf(par).isEmpty(),
                "Agency must not invent a recipient order");

        TacticalContext completed = context(
                board,
                Order.supportMove(gas, Province.Par, Province.Bur),
                Order.move(par, Province.Bur));

        List<TacticMatch> completedFindings = agency.investigate(completed);

        require(completedFindings.size() == 1
                        && completedFindings.getFirst().completePattern(),
                "Re-investigation must recognize the completed relationship");

        require(!match.completePattern(),
                "Earlier findings must remain unchanged");

    }


    // Registration \\

    private static void registration() {

        TacticalContext context = standardContext();

        List<TacticDetector> supplied = new ArrayList<>();
        supplied.add(new SelfBounceDetective());

        DetectiveAgency subset = new DetectiveAgency(supplied);

        supplied.clear();
        supplied.add(new SupportToMoveDetective());

        require(subset.kinds().equals(List.of(TacticKind.SELF_BOUNCE)),
                "Agency must copy the registry collection");

        require(kinds(subset.investigate(context))
                        .equals(List.of(TacticKind.SELF_BOUNCE)),
                "Subset investigation must run only registered categories");

        DetectiveAgency empty = new DetectiveAgency(List.of());

        require(empty.kinds().isEmpty()
                        && empty.investigate(context).isEmpty(),
                "Empty registry must produce an empty investigation");

        rejects(NullPointerException.class,
                () -> new DetectiveAgency(null),
                "Null registry");

        List<TacticDetector> nullEntry = new ArrayList<>();
        nullEntry.add(null);

        rejects(NullPointerException.class,
                () -> new DetectiveAgency(nullEntry),
                "Null detective");

        rejects(IllegalArgumentException.class,
                () -> new DetectiveAgency(List.of(
                        new SelfBounceDetective(),
                        new SelfBounceDetective())),
                "Duplicate tactical category");

        TacticDetector repeated = new SelfBounceDetective();

        rejects(IllegalArgumentException.class,
                () -> new DetectiveAgency(List.of(repeated, repeated)),
                "Repeated detective instance");

        rejects(NullPointerException.class,
                () -> new DetectiveAgency(List.of(
                        deputy(null, VERSION, ignored -> List.of()))),
                "Null detective kind");

        rejects(NullPointerException.class,
                () -> new DetectiveAgency(List.of(
                        deputy(TacticKind.SELF_BOUNCE, null,
                                ignored -> List.of()))),
                "Null detective version");

        rejects(IllegalArgumentException.class,
                () -> new DetectiveAgency(List.of(
                        deputy(TacticKind.SELF_BOUNCE, " ",
                                ignored -> List.of()))),
                "Blank detective version");

        rejects(NullPointerException.class,
                () -> empty.investigate(null),
                "Null context with an empty registry");

    }


    // Ordering \\

    private static void ordering() {

        TacticalContext context = standardContext();

        List<TacticMatch> expected =
                new DetectiveAgency().investigate(context);

        List<TacticDetector> detectives =
                new ArrayList<>(standardDetectives());

        Random random = new Random(9469L);

        for (int trial = 0; trial < 20; trial++) {

            Collections.shuffle(detectives, random);

            DetectiveAgency shuffledAgency =
                    new DetectiveAgency(detectives);

            require(shuffledAgency.investigate(context).equals(expected),
                    "Registration order changed the findings");

        }

        /*
         * Supply deliberately unsorted findings from an interface-level
         * implementation to exercise the agency's own ordering.
         */
        UnitId par = army(Nation.FRANCE, Province.Par);
        UnitId bre = army(Nation.FRANCE, Province.Bre);
        UnitId gas = army(Nation.FRANCE, Province.Gas);
        UnitId mun = army(Nation.FRANCE, Province.Mun);
        UnitId ruh = army(Nation.FRANCE, Province.Ruh);

        TacticalContext multiple = context(
                board(par, bre, gas, mun, ruh),
                Order.move(par, Province.Bur),
                Order.move(bre, Province.Bel),
                Order.supportMove(gas, Province.Par, Province.Bur),
                Order.supportMove(mun, Province.Par, Province.Bur),
                Order.supportMove(ruh, Province.Bre, Province.Bel));

        List<TacticMatch> ordered =
                new SupportToMoveDetective().investigate(multiple);

        require(ordered.size() == 3,
                "Ordering fixture requires three support relationships");

        require(ordered.get(0).focus() == Province.Bel
                        && ordered.get(1).focus() == Province.Bur
                        && ordered.get(2).focus() == Province.Bur,
                "Expected distinct-focus and equal-focus ordering cases");

        require(ordered.get(1).units(TacticMatch.Role.SUPPORTER)
                        .equals(List.of(gas))
                        && ordered.get(2).units(TacticMatch.Role.SUPPORTER)
                        .equals(List.of(mun)),
                "Participant tie-break fixture is incorrect");

        List<TacticMatch> reversed = new ArrayList<>(ordered);
        Collections.reverse(reversed);

        DetectiveAgency unsortedAgency = new DetectiveAgency(List.of(
                deputy(TacticKind.SUPPORT_TO_MOVE, VERSION,
                        ignored -> reversed)));

        List<TacticMatch> result = unsortedAgency.investigate(multiple);

        require(result.equals(ordered),
                "Agency must sort detective findings independently");

        reversed.clear();

        require(result.equals(ordered),
                "Returned results must not share the detective's mutable list");

    }


    // Detective contract failures \\

    private static void contractFailures() {

        TacticalContext context = standardContext();

        TacticMatch valid =
                new SupportToMoveDetective().investigate(context).getFirst();

        requireContractFailure(context, ignored -> null,
                "Null result collection");

        List<TacticMatch> nullMatch = new ArrayList<>();
        nullMatch.add(null);

        requireContractFailure(context, ignored -> nullMatch,
                "Null result occurrence");

        requireContractFailure(context, ignored -> List.of(valid, valid),
                "Duplicate occurrence");

        TacticMatch wrongKind = new TacticMatch(
                TacticKind.SUPPORT_TO_HOLD,
                valid.detectorVersion(),
                context,
                valid.focus(),
                valid.participants(),
                valid.missingOrders());

        requireContractFailure(context, ignored -> List.of(wrongKind),
                "Wrong match kind");

        TacticMatch wrongVersion = new TacticMatch(
                valid.kind(),
                "different-version",
                context,
                valid.focus(),
                valid.participants(),
                valid.missingOrders());

        requireContractFailure(context, ignored -> List.of(wrongVersion),
                "Wrong match version");

        TacticalContext equivalent = new TacticalContext(
                context.rulesetId(),
                context.moment(),
                context.board(),
                context.orders());

        require(equivalent.equals(context) && equivalent != context,
                "Context fixture must be equal but not identical");

        TacticMatch wrongContext = new TacticMatch(
                valid.kind(),
                valid.detectorVersion(),
                equivalent,
                valid.focus(),
                valid.participants(),
                valid.missingOrders());

        requireContractFailure(context, ignored -> List.of(wrongContext),
                "Reconstructed context instead of supplied context");

        String[] version = { VERSION };

        TacticDetector changingIdentity = new TacticDetector() {

            @Override
            public TacticKind kind() {
                return TacticKind.SUPPORT_TO_MOVE;
            }

            @Override
            public String version() {
                return version[0];
            }

            @Override
            public List<TacticMatch> detect(TacticalContext supplied) {
                throw new AssertionError(
                        "Identity change must be rejected before detection");
            }

        };

        DetectiveAgency agency =
                new DetectiveAgency(List.of(changingIdentity));

        version[0] = "changed-after-registration";

        rejects(IllegalStateException.class,
                () -> agency.investigate(context),
                "Changed registered identity");

    }

    private static void requireContractFailure(
            TacticalContext context,
            Function<TacticalContext, List<TacticMatch>> behavior,
            String message
    ) {

        DetectiveAgency agency = new DetectiveAgency(List.of(
                deputy(TacticKind.SUPPORT_TO_MOVE, VERSION, behavior)));

        rejects(IllegalStateException.class,
                () -> agency.investigate(context),
                message);

    }


    // Failure propagation \\

    private static void failurePropagation() {

        TacticalContext context = standardContext();

        RuntimeException failure =
                new IllegalArgumentException("Deliberate deputy failure");

        int[] laterCalls = { 0 };

        TacticDetector failing = deputy(
                TacticKind.SUPPORT_TO_MOVE,
                VERSION,
                ignored -> {
                    throw failure;
                });

        TacticDetector later = deputy(
                TacticKind.SELF_BOUNCE,
                VERSION,
                ignored -> {
                    laterCalls[0]++;
                    return List.of();
                });

        DetectiveAgency agency =
                new DetectiveAgency(List.of(later, failing));

        checks++;

        try {

            agency.investigate(context);

        } catch (RuntimeException exception) {

            require(exception == failure,
                    "Agency must preserve the original detective failure");

            require(laterCalls[0] == 0,
                    "Investigation must stop after a detective fails");

            return;

        }

        throw new AssertionError(
                "Agency returned instead of propagating detective failure");

    }


    // Test deputies \\

    /**
     * Interface-level test double.<br><br>
     *
     * Some checks deliberately return invalid findings to exercise
     * agency validation without weakening the concrete detectives.
     */
    private static TacticDetector deputy(
            TacticKind kind,
            String version,
            Function<TacticalContext, List<TacticMatch>> behavior
    ) {

        return new TacticDetector() {

            @Override
            public TacticKind kind() {
                return kind;
            }

            @Override
            public String version() {
                return version;
            }

            @Override
            public List<TacticMatch> detect(TacticalContext context) {
                return behavior.apply(context);
            }

        };

    }


    // Fixtures \\

    private static List<TacticDetector> standardDetectives() {
        return List.of(
                new SupportToMoveDetective(),
                new SupportToHoldDetective(),
                new SelfBounceDetective(),
                new BeleagueredGarrisonDetective());
    }

    /**
     * Produces two support-to-move occurrences and one of each other
     * implemented category.<br><br>
     *
     * The two supported French moves are both a self-bounce candidate
     * and part of the German occupant's garrison candidate.
     */
    private static TacticalContext standardContext() {

        UnitId par = army(Nation.FRANCE, Province.Par);
        UnitId mar = army(Nation.FRANCE, Province.Mar);
        UnitId gas = army(Nation.FRANCE, Province.Gas);
        UnitId mun = army(Nation.FRANCE, Province.Mun);

        UnitId bur = army(Nation.GERMANY, Province.Bur);
        UnitId ruh = army(Nation.GERMANY, Province.Ruh);

        return context(
                board(par, mar, gas, mun, bur, ruh),
                Order.move(par, Province.Bur),
                Order.move(mar, Province.Bur),
                Order.supportMove(gas, Province.Par, Province.Bur),
                Order.supportMove(mun, Province.Mar, Province.Bur),
                Order.hold(bur),
                Order.supportHold(ruh, Province.Bur));

    }

    private static UnitId army(Nation nation, Province location) {

        String key = "agency-fixture|"
                + nation.name() + "|" + location.name();

        UUID id = UUID.nameUUIDFromBytes(
                key.getBytes(StandardCharsets.UTF_8));

        return new UnitId(id, nation, UnitType.ARMY, location);

    }

    /**
     * Initial fixture placement only. Detectives use actual board locations.
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

        for (Order order : submissions) {

            TacticalContext.KnownOrder known =
                    new TacticalContext.KnownOrder(
                            order,
                            TacticalContext.Provenance.SUBMITTED);

            if (orders.putIfAbsent(order.unit(), known) != null)
                throw new IllegalArgumentException(
                        "Duplicate fixture order: " + order.unit());

        }

        return new TacticalContext(RULESET, MOMENT, board, orders);

    }

    private static List<TacticKind> kinds(List<TacticMatch> findings) {

        List<TacticKind> result = new ArrayList<>();

        for (TacticMatch match : findings)
            result.add(match.kind());

        return List.copyOf(result);

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