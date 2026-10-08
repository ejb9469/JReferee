package testing.selfcheck;

import analysis.tactics.*;
import analysis.tactics.detective.SupportToMoveDetective;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Standalone checks for investigation-report filters and reference queries.
 */
public final class InvestigationReportSelfCheck {


    // Constants \\

    private static final GameMoment MOMENT =
            new GameMoment(1902, GamePhase.SPRING_MOVEMENT);


    // Check state \\

    private static int checks;


    // Construction \\

    private InvestigationReportSelfCheck() {  }


    // Application entry point \\

    public static void main(String[] args) {

        checks = 0;

        reportFilters();
        incompletePatterns();
        validation();

        System.out.printf(
                "Investigation report self-check passed: %d checks.%n",
                checks);

    }


    // Filters and reference semantics \\

    private static void reportFilters() {

        TacticalContext context = completeSupportContext();
        InvestigationReport report =
                InvestigationReport.investigate(new DetectiveAgency(), context);

        require(report.byKind(TacticKind.SUPPORT_TO_MOVE).size() == 1,
                "Kind filter did not select support-to-move");

        UnitId supporter = unit(Nation.FRANCE, UnitType.FLEET, Province.Mar);
        UnitId supported = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);

        require(report.byParticipant(supporter).size() == 1,
                "Participant filter did not select supporter");
        require(report.byParticipant(supported).size() == 1,
                "Participant filter did not select supported unit");
        require(report.byParticipant(
                        unit(Nation.GERMANY, UnitType.ARMY, Province.Mun)).isEmpty(),
                "Unknown participant unexpectedly selected a finding");
        require(!context.orders().containsKey(
                        unit(Nation.GERMANY, UnitType.ARMY, Province.Mun))
                        && context.unknownUnits().contains(
                        unit(Nation.GERMANY, UnitType.ARMY, Province.Mun)),
                "An absent order was not preserved as unknown");

        require(report.focusedOn(Province.Bur).size() == 1,
                "Focus filter did not select destination");
        require(report.focusedOn(Province.Par).isEmpty(),
                "Focus filter included a participant's territory");
        require(report.atTerritory(Province.Par).size() == 1,
                "Reference query omitted participant location");
        require(report.atTerritory(Province.Bur).size() == 1,
                "Reference query omitted focus or order destination");
        require(report.atTerritory(Province.Pic).isEmpty(),
                "Reference query expanded to an unrelated territory");

        InvestigationReport chained = report
                .byKind(TacticKind.SUPPORT_TO_MOVE)
                .byParticipant(supported)
                .focusedOn(Province.Bur)
                .completePatterns();

        require(chained.findings().equals(report.findings()),
                "Chained filters changed the selected occurrence");
        require(chained.context() == context,
                "Filtering did not retain the original context");
        require(chained.coverage().scope()
                        == InvestigationCoverage.Scope.FILTERED_VIEW,
                "Filtered report did not label its coverage scope");

    }


    // Local pattern completeness \\

    private static void incompletePatterns() {

        UnitId par = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId mar = unit(Nation.FRANCE, UnitType.FLEET, Province.Mar);
        BoardState board = new BoardState(
                Map.of(par, Province.Par, mar, Province.Mar),
                Map.of());
        TacticalContext context = new TacticalContext(
                "report-self-check-v1",
                MOMENT,
                board,
                Map.of(mar, known(Order.supportMove(
                        mar, Province.Par, Province.Bur))));

        InvestigationReport report =
                new DetectiveAgency(List.of(new SupportToMoveDetective()))
                        .investigateReport(context);

        require(report.incompletePatterns().size() == 1,
                "Unknown recipient order was not retained as incomplete");
        require(report.completePatterns().isEmpty(),
                "Incomplete local pattern was treated as complete");
        require(report.findings().getFirst().missingOrders().equals(Set.of(par)),
                "Missing order evidence was not preserved");
        require(!context.orders().containsKey(par),
                "Report filtering fabricated an unknown order");

    }


    // Validation \\

    private static void validation() {

        TacticalContext context = completeSupportContext();
        TacticMatch match = InvestigationReport
                .investigate(new DetectiveAgency(), context)
                .findings()
                .getFirst();

        rejects(NullPointerException.class,
                () -> report(context, null),
                "Null findings");
        rejects(NullPointerException.class,
                () -> report(null, List.of()),
                "Null context");
        rejects(NullPointerException.class,
                () -> InvestigationReport.investigate(null, context),
                "Null agency");
        rejects(NullPointerException.class,
                () -> InvestigationReport.investigate(new DetectiveAgency(), null),
                "Null investigation context");
        rejects(IllegalArgumentException.class,
                () -> report(context, List.of(match, match)),
                "Duplicate match");
        rejects(NullPointerException.class,
                () -> report(context, Arrays.asList((TacticMatch) null)),
                "Null match");
        rejects(UnsupportedOperationException.class,
                () -> report(context, List.of(match)).findings().clear(),
                "Mutable report findings");
        rejects(NullPointerException.class,
                () -> report(context, List.of()).byKind(null),
                "Null kind filter");
        rejects(NullPointerException.class,
                () -> report(context, List.of()).byParticipant(null),
                "Null participant filter");
        rejects(NullPointerException.class,
                () -> report(context, List.of()).focusedOn(null),
                "Null focus filter");
        rejects(NullPointerException.class,
                () -> report(context, List.of()).atTerritory(null),
                "Null territory filter");

    }


    // Fixtures \\

    private static TacticalContext completeSupportContext() {

        UnitId par = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId mar = unit(Nation.FRANCE, UnitType.FLEET, Province.Mar);
        UnitId mun = unit(Nation.GERMANY, UnitType.ARMY, Province.Mun);

        BoardState board = new BoardState(
                Map.of(
                        par, Province.Par,
                        mar, Province.Mar,
                        mun, Province.Mun),
                Map.of());

        return new TacticalContext(
                "report-self-check-v1",
                MOMENT,
                board,
                Map.of(
                        par, known(Order.move(par, Province.Bur)),
                        mar, known(Order.supportMove(
                                mar, Province.Par, Province.Bur))));

    }

    private static UnitId unit(
            Nation nation,
            UnitType type,
            Province origin
    ) {
        return new UnitId(nation, type, origin);
    }

    private static TacticalContext.KnownOrder known(Order order) {
        return new TacticalContext.KnownOrder(
                order, TacticalContext.Provenance.SUBMITTED);
    }

    private static InvestigationReport report(
            TacticalContext context,
            List<TacticMatch> findings
    ) {
        return new InvestigationReport(context, findings);
    }


    // Assertions \\

    private static void require(boolean condition, String message) {

        checks++;

        if (!condition)
            throw new AssertionError(message);

    }

    private static void rejects(
            Class<? extends Throwable> expected,
            Runnable action,
            String description
    ) {

        checks++;

        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure))
                return;

            throw new AssertionError(
                    description + " threw " + failure.getClass().getName(),
                    failure);
        }

        throw new AssertionError(description + " was accepted");

    }


}
