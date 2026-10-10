package ui;

import analysis.tactics.InvestigationCoverage;
import analysis.tactics.outcome.*;

import static ui.TacticalJson.*;


/**
 * Serializes resolved-event findings separately from structural findings.
 */
public final class OutcomeReportJsonWriter {


    // Construction \\

    private OutcomeReportJsonWriter() {  }


    // Successful report \\

    public static String toJson(OutcomeReport report) {

        java.util.Objects.requireNonNull(report, "report");

        StringBuilder out = new StringBuilder("{");

        field(out, "status", "EVALUATED");
        out.append(',');
        field(out, "agency", "OUTCOME");
        out.append(',');
        field(out, "evidenceSource", "LOCAL_RESOLUTION");
        out.append(',');
        field(out, "message",
                "Events in the identified local scenario resolution; "
                        + "not DBN historical annotations.");

        out.append(',');
        field(out, "rulesetId", report.context().rulesetId());
        out.append(',');
        field(out, "scenarioId", report.context().scenario().id());

        out.append(",\"year\":")
                .append(report.context().scenario().context().moment().year());

        out.append(',');
        field(out, "phase",
                report.context().scenario().context().moment().gamePhase().name());

        out.append(',');
        coverage(out, report.coverage());

        out.append(",\"findings\":[");

        for (int index = 0; index < report.findings().size(); index++) {

            if (index > 0)
                out.append(',');

            OutcomeFinding finding = report.findings().get(index);

            out.append("{\"index\":").append(index);
            out.append(',');

            definition(
                    out,
                    finding.kind(),
                    finding.investigatorVersion(),
                    "investigatorVersion");

            out.append(',');
            field(out, "focus", finding.focus().name());

            /*
             * No structural completePattern flag: these are occurrences
             * in a resolved context, not incomplete submission candidates.
             */
            out.append(",\"participants\":[");

            for (int participantIndex = 0;
                 participantIndex < finding.participants().size();
                 participantIndex++) {

                if (participantIndex > 0)
                    out.append(',');

                OutcomeFinding.Participant participant =
                        finding.participants().get(participantIndex);

                TacticalJson.participant(
                        out,
                        participant.role().name(),
                        participant.unit(),
                        finding.context().scenario().context());

            }

            out.append("]}");

        }

        return out.append("]}").toString();

    }


    // No completed investigation \\

    public static String unavailable(String status, String message) {

        StringBuilder out = new StringBuilder("{");

        field(out, "status", status);
        out.append(',');
        field(out, "agency", "OUTCOME");
        out.append(',');
        field(out, "message", message);
        out.append(',');

        coverage(out, InvestigationCoverage.notRecorded());

        return out.append(",\"findings\":[]}").toString();

    }


}