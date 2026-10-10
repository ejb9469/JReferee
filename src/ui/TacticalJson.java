package ui;

import analysis.tactics.*;
import contracts.OrderForm;
import phase.UnitId;

import static io.json.JsonEscaper.appendString;


/**
 * Shared JSON fragments for tactical browser reports.
 *
 * <p>Package-private: this is a serialization utility, not a domain model.</p>
 */
final class TacticalJson {


    // Construction \\

    private TacticalJson() {  }


    // Fields \\

    static void field(StringBuilder out, String key, String value) {

        appendString(out, key);
        out.append(':');

        if (value == null)
            out.append("null");
        else
            appendString(out, value);

    }


    // Coverage \\

    /**
     * Appends coverageScope and coverage fields without a leading comma.
     */
    static void coverage(
            StringBuilder out,
            InvestigationCoverage coverage
    ) {

        field(out, "coverageScope", coverage.scope().name());
        out.append(",\"coverage\":[");

        boolean first = true;

        for (InvestigationCoverage.KindCoverage entry : coverage.byKind()) {

            if (!first)
                out.append(',');

            out.append('{');
            field(out, "kind", entry.kind().name());
            out.append(',');
            field(out, "status", entry.status().name());

            out.append(",\"implemented\":").append(entry.implemented());
            out.append(",\"applicable\":").append(entry.applicable());
            out.append(",\"findingCount\":").append(entry.findingCount());
            out.append(",\"missingEvidence\":[");

            boolean firstCapability = true;

            for (EvidenceCapability capability : entry.missingEvidence()) {

                if (!firstCapability)
                    out.append(',');

                appendString(out, capability.name());
                firstCapability = false;

            }

            out.append("]}");
            first = false;

        }

        out.append(']');

    }


    // Finding metadata \\

    static void definition(
            StringBuilder out,
            TacticKind kind,
            String version,
            String versionField
    ) {

        TacticDefinition definition = TacticDefinitionRegistry.require(kind);

        field(out, "kind", kind.name());
        out.append(',');
        field(out, versionField, version);
        out.append(',');
        field(out, "definitionVersion", definition.semanticVersion());
        out.append(',');
        field(out, "interpretation", definition.interpretation().name());
        out.append(',');
        field(out, "semantics", definition.semantics());

    }


    // Participants \\

    static void participant(
            StringBuilder out,
            String role,
            UnitId unit,
            TacticalContext context
    ) {

        TacticalContext.KnownOrder evidence = context.orders().get(unit);

        out.append('{');
        field(out, "role", role);
        out.append(',');
        field(out, "nation", unit.owner().name());
        out.append(',');
        field(out, "province", context.board().locationOf(unit).name());
        out.append(',');
        field(out, "unit", unitText(context, unit));
        out.append(',');
        field(out, "order",
                evidence == null
                        ? null
                        : OrderForm.format(
                        evidence.order(),
                        context.board().locationOf(unit)));
        out.append(',');
        field(out, "provenance",
                evidence == null ? null : evidence.provenance().name());
        out.append('}');

    }

    static String unitText(TacticalContext context, UnitId unit) {
        return OrderForm.unitText(unit, context.board().locationOf(unit));
    }


}