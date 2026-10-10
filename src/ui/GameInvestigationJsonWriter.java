package ui;

import analysis.tactics.*;
import game.GameMoment;
import parsing.diplobn.DiploBNPhase;
import phase.Order;
import phase.UnitId;

import java.util.*;

import static io.json.JsonEscaper.appendString;
import static ui.TacticalJson.*;


/**
 * Browser serialization of three separate analysis products:
 *
 * <ul>
 *     <li>root structural findings and coverage;</li>
 *     <li>resolvedEvents: outcome-agency findings and coverage;</li>
 *     <li>localSupportAssessments: scenario-specific support claims.</li>
 * </ul>
 *
 * <p>DBN historical annotations remain outside all three products.</p>
 */
public final class GameInvestigationJsonWriter {


    // Constants \\

    private static final String CONTEXT_ID =
            "diplobn-standard-browser-structural-v1";


    // Construction \\

    private GameInvestigationJsonWriter() {  }


    // Investigation \\

    public static String toJson(DiploBNPhase phase) {

        Objects.requireNonNull(phase, "phase");

        if (!phase.gamePhase().isMovement())
            return unavailable(
                    "NOT_APPLICABLE",
                    "Movement investigators do not inspect retreat "
                            + "or adjustment snapshots.");

        TacticalContext context;

        try {

            Map<UnitId, TacticalContext.KnownOrder> orders =
                    new LinkedHashMap<>();

            for (Order order : phase.movementOrders()) {

                TacticalContext.KnownOrder evidence =
                        new TacticalContext.KnownOrder(
                                order,
                                TacticalContext.Provenance.SUBMITTED);

                if (orders.putIfAbsent(order.unit(), evidence) != null)
                    throw new IllegalArgumentException(
                            "Multiple submissions for one active unit");

            }

            context = new TacticalContext(
                    CONTEXT_ID,
                    new GameMoment(
                            phase.sourcePhase() / 10,
                            phase.gamePhase()),
                    phase.board(),
                    orders);

        } catch (IllegalArgumentException exception) {

            return unavailable(
                    "INVALID_INPUT",
                    "Snapshot was not investigated: " + exception.getMessage());

        }

        InvestigationReport structural;

        try {

            structural = new DetectiveAgency().investigateReport(context);

        } catch (RuntimeException exception) {

            exception.printStackTrace(System.err);

            return unavailable(
                    "ERROR",
                    "Structural investigation failed; see the server log.");

        }

        BrowserResolvedAnalysis.Sections resolved =
                BrowserResolvedAnalysis.analyze(structural);

        return reportJson(structural, resolved);

    }


    // Successful structural report \\

    private static String reportJson(
            InvestigationReport report,
            BrowserResolvedAnalysis.Sections resolved
    ) {

        TacticalContext context = report.context();
        StringBuilder out = new StringBuilder("{");

        field(out, "status", "EVALUATED");
        out.append(',');
        field(out, "agency", "STRUCTURAL");
        out.append(',');
        field(out, "contextId", context.rulesetId());

        out.append(",\"knownOrders\":").append(context.orders().size());
        out.append(",\"activeUnits\":").append(context.board().locations().size());
        out.append(",\"unknownUnits\":[");

        List<UnitId> unknown = context.unknownUnits();

        for (int index = 0; index < unknown.size(); index++) {

            if (index > 0)
                out.append(',');

            appendString(out, unitText(context, unknown.get(index)));

        }

        out.append("],\"findings\":[");

        for (int index = 0; index < report.findings().size(); index++) {

            if (index > 0)
                out.append(',');

            appendFinding(out, report.findings().get(index), index);

        }

        out.append("],");
        coverage(out, report.coverage());

        out.append(",\"resolvedEvents\":").append(resolved.resolvedEvents());
        out.append(",\"localSupportAssessments\":")
                .append(resolved.localSupportAssessments());

        return out.append('}').toString();

    }

    private static void appendFinding(
            StringBuilder out,
            TacticMatch match,
            int index
    ) {

        TacticalContext context = match.context();

        out.append("{\"index\":").append(index);
        out.append(',');

        definition(
                out,
                match.kind(),
                match.detectorVersion(),
                "detectiveVersion");

        out.append(',');
        field(out, "focus", match.focus().name());

        out.append(",\"complete\":").append(match.completePattern());
        out.append(",\"participants\":[");

        for (int participantIndex = 0;
             participantIndex < match.participants().size();
             participantIndex++) {

            if (participantIndex > 0)
                out.append(',');

            TacticMatch.Participant participant =
                    match.participants().get(participantIndex);

            TacticalJson.participant(
                    out,
                    participant.role().name(),
                    participant.unit(),
                    context);

        }

        out.append("],\"missingOrders\":[");

        boolean first = true;

        for (UnitId unit : match.missingOrders()) {

            if (!first)
                out.append(',');

            appendString(out, unitText(context, unit));
            first = false;

        }

        out.append("]}");

    }


    // No completed structural investigation \\

    private static String unavailable(String status, String message) {

        StringBuilder out = new StringBuilder("{");

        field(out, "status", status);
        out.append(',');
        field(out, "agency", "STRUCTURAL");
        out.append(',');
        field(out, "message", message);
        out.append(',');

        coverage(out, InvestigationCoverage.notRecorded());

        out.append(",\"findings\":[]");
        out.append(",\"resolvedEvents\":")
                .append(OutcomeReportJsonWriter.unavailable(
                        status,
                        "No resolved-event investigation was performed."));

        out.append(",\"localSupportAssessments\":{");
        field(out, "status", status);
        out.append(',');
        field(out, "message", "No local support assessment was performed.");

        out.append(",\"eligibleCount\":0,\"evaluatedCount\":0,\"failedCount\":0");
        out.append(",\"configuration\":null,\"entries\":[]}");

        return out.append('}').toString();

    }


}