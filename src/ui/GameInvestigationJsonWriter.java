package ui;

import analysis.tactics.*;
import contracts.OrderForm;
import game.GameMoment;
import parsing.diplobn.DiploBNPhase;
import phase.Order;
import phase.UnitId;

import java.util.*;

import static io.json.JsonEscaper.appendString;


/**
 * Serializes a snapshot's structural tactical investigation for the browser.
 *
 * <p>Only movement snapshots are investigated. Historical outcome annotations
 * are not supplied to detectives as adjudication evidence.</p>
 */
public final class GameInvestigationJsonWriter {


    // Constants \\

    /*
     * Identifies this source-inspection context, not DBN's adjudication policy.
     */
    private static final String CONTEXT_ID =
            "diplobn-standard-browser-structural-v1";


    // Construction \\

    private GameInvestigationJsonWriter() {  }


    // Investigation \\

    public static String toJson(DiploBNPhase phase) {

        Objects.requireNonNull(phase, "phase");

        if (!phase.gamePhase().isMovement())
            return message(
                    "NOT_APPLICABLE",
                    "These detectives inspect movement snapshots only. "
                            + "Retreat and adjustment positions were not investigated.");

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
                            "Multiple submissions for "
                                    + OrderForm.unitText(
                                    order.unit(),
                                    phase.board().locationOf(order.unit())));

            }

            context = new TacticalContext(
                    CONTEXT_ID,
                    new GameMoment(
                            phase.sourcePhase() / 10,
                            phase.gamePhase()),
                    phase.board(),
                    orders);

        } catch (IllegalArgumentException exception) {

            return message(
                    "INVALID_INPUT",
                    "Snapshot was not investigated: " + exception.getMessage());

        }

        try {

            InvestigationReport report =
                    new DetectiveAgency().investigateReport(context);

            return reportJson(report);

        } catch (RuntimeException exception) {

            exception.printStackTrace(System.err);

            return message(
                    "ERROR",
                    "Investigation failed. No complete report is available; "
                            + "see the server log.");

        }

    }


    // Report serialization \\

    private static String reportJson(InvestigationReport report) {

        TacticalContext context = report.context();
        StringBuilder out = new StringBuilder("{");

        field(out, "status", "EVALUATED");
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

        out.append("],\"coverageScope\":");
        appendString(out, report.coverage().scope().name());
        out.append(",\"coverage\":[");

        List<InvestigationCoverage.KindCoverage> coverage =
                report.coverage().byKind();

        for (int index = 0; index < coverage.size(); index++) {

            if (index > 0)
                out.append(',');

            InvestigationCoverage.KindCoverage entry = coverage.get(index);

            out.append('{');
            field(out, "kind", entry.kind().name());
            out.append(',');
            field(out, "status", entry.status().name());
            out.append(",\"implemented\":").append(entry.implemented());
            out.append(",\"applicable\":").append(entry.applicable());
            out.append(",\"findingCount\":").append(entry.findingCount());
            out.append(",\"missingEvidence\":[");

            boolean first = true;

            for (EvidenceCapability capability : entry.missingEvidence()) {

                if (!first)
                    out.append(',');

                appendString(out, capability.name());
                first = false;

            }

            out.append("]}");

        }

        return out.append("]}").toString();

    }

    private static void appendFinding(
            StringBuilder out,
            TacticMatch match,
            int index
    ) {

        TacticalContext context = match.context();
        TacticDefinition definition =
                TacticDefinitionRegistry.require(match.kind());

        out.append("{\"index\":").append(index);
        out.append(',');
        field(out, "kind", match.kind().name());
        out.append(',');
        field(out, "focus", match.focus().name());
        out.append(',');
        field(out, "detectiveVersion", match.detectorVersion());
        out.append(',');
        field(out, "definitionVersion", definition.semanticVersion());
        out.append(',');
        field(out, "interpretation", definition.interpretation().name());
        out.append(',');
        field(out, "semantics", definition.semantics());

        out.append(",\"complete\":").append(match.completePattern());
        out.append(",\"participants\":[");

        for (int participantIndex = 0;
             participantIndex < match.participants().size();
             participantIndex++) {

            if (participantIndex > 0)
                out.append(',');

            TacticMatch.Participant participant =
                    match.participants().get(participantIndex);

            UnitId unit = participant.unit();
            TacticalContext.KnownOrder evidence = context.orders().get(unit);

            out.append('{');
            field(out, "role", participant.role().name());
            out.append(',');
            field(out, "nation", unit.owner().name());
            out.append(',');
            field(out, "province", context.board().locationOf(unit).name());
            out.append(',');
            field(out, "unit", unitText(context, unit));
            out.append(',');
            field(out, "order", evidence == null
                    ? null
                    : OrderForm.format(
                    evidence.order(),
                    context.board().locationOf(unit)));
            out.append(',');
            field(out, "provenance",
                    evidence == null ? null : evidence.provenance().name());
            out.append('}');

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


    // Formatting helpers \\

    private static String unitText(TacticalContext context, UnitId unit) {
        return OrderForm.unitText(unit, context.board().locationOf(unit));
    }

    private static String message(String status, String message) {

        StringBuilder out = new StringBuilder("{");

        field(out, "status", status);
        out.append(',');
        field(out, "message", message);

        return out.append('}').toString();

    }

    private static void field(StringBuilder out, String key, String value) {

        appendString(out, key);
        out.append(':');

        if (value == null)
            out.append("null");
        else
            appendString(out, value);

    }


}