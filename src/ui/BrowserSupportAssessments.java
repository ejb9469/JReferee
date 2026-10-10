package ui;

import analysis.tactics.*;
import analysis.tactics.assessment.SupportToHoldAssessor;
import analysis.tactics.assessment.SupportToMoveAssessor;
import analysis.tactics.detective.SupportToHoldDetective;
import analysis.tactics.detective.SupportToMoveDetective;
import analysis.tactics.outcome.ResolvedMovementContext;
import phase.movement.MovementResult;

import java.util.*;

import static ui.TacticalJson.field;


/**
 * Serializes support assessments using an already-resolved baseline.
 *
 * <p>Outcome investigators and support assessors share the same resolution.
 * No processor is constructed or run here.</p>
 */
public final class BrowserSupportAssessments {


    // Construction \\

    private BrowserSupportAssessments() {  }


    // Assessment \\

    public static String toJson(
            InvestigationReport structural,
            ResolvedMovementContext resolved
    ) {

        Objects.requireNonNull(structural, "structural");
        Objects.requireNonNull(resolved, "resolved");

        TacticalContext original = structural.context();
        TacticalScenario scenario = resolved.scenario();
        TacticalContext local = scenario.context();

        /*
         * The ruleset IDs intentionally differ: the original context labels
         * source inspection, while the local context identifies resolution.
         * All actual input evidence must otherwise agree exactly.
         */
        if (!original.board().equals(local.board())
                || !original.moment().equals(local.moment())
                || !original.orders().equals(local.orders()))
            throw new IllegalArgumentException(
                    "Resolved scenario differs from structural input evidence");

        List<Integer> indexes = supportIndexes(structural);

        if (indexes.isEmpty())
            return unavailable(
                    structural,
                    "NO_SUPPORTED_FINDINGS",
                    "No support findings are available for the enabled assessors.");

        Set<TacticMatch> recognized = new HashSet<>();
        recognized.addAll(new SupportToMoveDetective().investigate(local));
        recognized.addAll(new SupportToHoldDetective().investigate(local));

        TacticAssessor.MovementResolver baseline = baseline(resolved);
        List<Entry> entries = new ArrayList<>();
        int failed = 0;

        for (int index : indexes) {

            try {

                TacticMatch source = structural.findings().get(index);

                TacticMatch candidate = new TacticMatch(
                        source.kind(),
                        source.detectorVersion(),
                        local,
                        source.focus(),
                        source.participants(),
                        source.missingOrders());

                if (!candidate.completePattern()
                        || !recognized.contains(candidate))
                    throw new IllegalArgumentException(
                            "Support finding was not reproduced in resolved context");

                TacticAssessor assessor =
                        candidate.kind() == TacticKind.SUPPORT_TO_MOVE
                                ? new SupportToMoveAssessor()
                                : new SupportToHoldAssessor();

                entries.add(new Entry(
                        index,
                        "EVALUATED",
                        null,
                        assessor.assess(candidate, scenario, baseline)));

            } catch (RuntimeException exception) {

                exception.printStackTrace(System.err);
                failed++;

                entries.add(new Entry(
                        index,
                        "ERROR",
                        "Support assessment failed; see the server log.",
                        null));

            }

        }

        String status = failed == 0
                ? "EVALUATED"
                : failed == entries.size() ? "ERROR" : "PARTIAL_ERROR";

        StringBuilder out = header(
                status,
                "Local baseline claims, not DBN annotations or causal conclusions.",
                entries.size(),
                entries.size() - failed,
                failed);

        /*
         * Policy/trials/seed and engine revision are encoded in rulesetId by
         * ProcessorMovementResolver. Do not parse opaque identities here.
         */
        out.append(",\"configuration\":{");
        field(out, "rulesetId", resolved.rulesetId());
        out.append(',');
        field(out, "scenarioId", scenario.id());
        out.append('}');

        appendEntries(out, entries);

        return out.append('}').toString();

    }


    // Unavailable assessments \\

    public static String unavailable(
            InvestigationReport structural,
            String status,
            String message
    ) {

        List<Integer> indexes = supportIndexes(structural);
        List<Entry> entries = new ArrayList<>();

        for (int index : indexes)
            entries.add(new Entry(index, status, message, null));

        StringBuilder out = header(
                status,
                message,
                indexes.size(),
                0,
                status.equals("ERROR") ? indexes.size() : 0);

        out.append(",\"configuration\":null");
        appendEntries(out, entries);

        return out.append('}').toString();

    }


    // Baseline reuse \\

    private static TacticAssessor.MovementResolver baseline(
            ResolvedMovementContext resolved
    ) {

        return new TacticAssessor.MovementResolver() {

            @Override
            public String rulesetId() {
                return resolved.rulesetId();
            }

            @Override
            public MovementResult resolve(TacticalScenario requested) {

                if (requested != resolved.scenario())
                    throw new IllegalArgumentException(
                            "Baseline cache cannot resolve another scenario");

                return resolved.result();

            }

        };

    }


    // Selection \\

    private static List<Integer> supportIndexes(InvestigationReport report) {

        Objects.requireNonNull(report, "report");

        List<Integer> indexes = new ArrayList<>();

        for (int index = 0; index < report.findings().size(); index++) {

            TacticKind kind = report.findings().get(index).kind();

            if (kind == TacticKind.SUPPORT_TO_MOVE
                    || kind == TacticKind.SUPPORT_TO_HOLD)
                indexes.add(index);

        }

        return indexes;

    }


    // JSON \\

    private static StringBuilder header(
            String status,
            String message,
            int eligible,
            int evaluated,
            int failed
    ) {

        StringBuilder out = new StringBuilder("{");

        field(out, "status", status);
        out.append(',');
        field(out, "message", message);

        out.append(",\"eligibleCount\":").append(eligible);
        out.append(",\"evaluatedCount\":").append(evaluated);
        out.append(",\"failedCount\":").append(failed);

        return out;

    }

    private static void appendEntries(
            StringBuilder out,
            List<Entry> entries
    ) {

        out.append(",\"entries\":[");

        for (int index = 0; index < entries.size(); index++) {

            if (index > 0)
                out.append(',');

            Entry entry = entries.get(index);

            out.append("{\"findingIndex\":").append(entry.index());
            out.append(',');
            field(out, "status", entry.status());
            out.append(',');
            field(out, "message", entry.message());

            if (entry.assessment() == null) {

                out.append(",\"assessmentVersion\":null,\"claims\":[]}");
                continue;

            }

            TacticalAssessment assessment = entry.assessment();

            out.append(',');
            field(out, "assessmentVersion", assessment.assessmentVersion());
            out.append(",\"claims\":[");

            for (int claimIndex = 0;
                 claimIndex < assessment.findings().size();
                 claimIndex++) {

                if (claimIndex > 0)
                    out.append(',');

                TacticalAssessment.Finding finding =
                        assessment.findings().get(claimIndex);

                out.append('{');
                field(out, "claim", finding.claim().name());
                out.append(',');
                field(out, "truth", finding.truth().name());
                out.append(",\"evidence\":[");

                for (int evidenceIndex = 0;
                     evidenceIndex < finding.evidence().size();
                     evidenceIndex++) {

                    if (evidenceIndex > 0)
                        out.append(',');

                    TacticalAssessment.Evidence evidence =
                            finding.evidence().get(evidenceIndex);

                    if (evidence.scenario() != assessment.scenario())
                        throw new IllegalStateException(
                                "Baseline serializer cannot flatten counterfactuals");

                    out.append('{');
                    field(out, "code", evidence.code());
                    out.append(',');
                    field(out, "scenarioId", evidence.scenario().id());
                    out.append(',');
                    field(out, "explanation", evidence.explanation());
                    out.append('}');

                }

                out.append("]}");

            }

            out.append("]}");

        }

        out.append(']');

    }

    private record Entry(
            int index,
            String status,
            String message,
            TacticalAssessment assessment
    ) { }


}