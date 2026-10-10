package analysis.tactics.assessment;

import analysis.tactics.*;
import analysis.tactics.detective.SupportToHoldDetective;
import analysis.tactics.detective.SupportToMoveDetective;
import analysis.tactics.outcome.ResolvedMovementContext;

import java.util.*;


/**
 * Baseline support assessments associated with structural-report indexes.
 *
 * <p>Indexes identify entries in the supplied report only; they are not
 * persistent tactical occurrence identifiers.</p>
 */
public record SupportAssessmentBatch(
        Status status,
        String message,
        List<Entry> entries
) {


    // Status \\

    public enum Status {
        EVALUATED,
        PARTIAL_ERROR,
        ERROR,
        DISABLED,
        INCOMPLETE_SCENARIO,
        NO_SUPPORTED_FINDINGS,
        NOT_APPLICABLE,
        INVALID_INPUT
    }


    // Construction \\

    public SupportAssessmentBatch {

        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));

        Set<Integer> indexes = new HashSet<>();
        int evaluated = 0;

        for (Entry entry : entries) {

            if (!indexes.add(entry.findingIndex()))
                throw new IllegalArgumentException("Duplicate finding index");

            if (entry.status() == Status.EVALUATED)
                evaluated++;
            else if (entry.status() != status
                    && !(status == Status.PARTIAL_ERROR
                    && entry.status() == Status.ERROR))
                throw new IllegalArgumentException(
                        "Entry status differs from batch status");

        }

        switch (status) {

            case EVALUATED -> {
                if (entries.isEmpty() || evaluated != entries.size())
                    throw new IllegalArgumentException(
                            "Evaluated batch requires successful entries");
            }

            case PARTIAL_ERROR -> {
                if (evaluated == 0 || evaluated == entries.size())
                    throw new IllegalArgumentException(
                            "Partial error requires successes and errors");
            }

            case NO_SUPPORTED_FINDINGS -> {
                if (!entries.isEmpty())
                    throw new IllegalArgumentException(
                            "No-supported-findings batch must be empty");
            }

            default -> {
                if (evaluated != 0)
                    throw new IllegalArgumentException(
                            "Unavailable batch contains assessments");
            }

        }

    }


    // Counts \\

    public int eligibleCount() {
        return entries.size();
    }

    public int evaluatedCount() {
        return (int) entries.stream()
                .filter(entry -> entry.status() == Status.EVALUATED)
                .count();
    }

    public int failedCount() {
        return (int) entries.stream()
                .filter(entry -> entry.status() == Status.ERROR)
                .count();
    }


    // Batch assessment \\

    public static SupportAssessmentBatch assess(
            InvestigationReport structural,
            ResolvedMovementContext resolved
    ) {

        Objects.requireNonNull(structural, "structural");
        Objects.requireNonNull(resolved, "resolved");

        TacticalContext original = structural.context();
        TacticalScenario scenario = resolved.scenario();
        TacticalContext local = scenario.context();

        if (!original.board().equals(local.board())
                || !original.moment().equals(local.moment())
                || !original.orders().equals(local.orders()))
            throw new IllegalArgumentException(
                    "Resolved scenario differs from structural input evidence");

        List<Integer> indexes = supportIndexes(structural);

        if (indexes.isEmpty())
            return unavailable(
                    structural,
                    Status.NO_SUPPORTED_FINDINGS,
                    "No support findings are available for the enabled assessors.");

        /*
         * Preserve the existing support-assessment explicit-input policy.
         * Validate once per batch, not once per finding.
         */
        MovementResolutionChecks.requireCorrespondence(
                scenario, resolved.result());

        Set<TacticMatch> recognized = new HashSet<>();
        recognized.addAll(new SupportToMoveDetective().investigate(local));
        recognized.addAll(new SupportToHoldDetective().investigate(local));

        SupportAssessor moveAssessor = new SupportToMoveAssessor();
        SupportAssessor holdAssessor = new SupportToHoldAssessor();

        List<Entry> entries = new ArrayList<>();
        int failures = 0;

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

                if (!recognized.contains(candidate))
                    throw new IllegalArgumentException(
                            "Support finding was not reproduced in resolved context");

                SupportAssessor assessor =
                        candidate.kind() == TacticKind.SUPPORT_TO_MOVE
                                ? moveAssessor : holdAssessor;

                entries.add(new Entry(
                        index,
                        Status.EVALUATED,
                        null,
                        assessor.assessRecognized(
                                candidate, scenario, resolved.result())));

            } catch (RuntimeException exception) {

                failures++;

                /*
                 * Explicit failure, not a FALSE tactical claim.
                 * Detailed exception handling/logging can be added at an
                 * application boundary without coupling this API to a logger.
                 */
                entries.add(new Entry(
                        index,
                        Status.ERROR,
                        "Support assessment failed ("
                                + exception.getClass().getSimpleName() + ").",
                        null));

            }

        }

        Status status = failures == 0
                ? Status.EVALUATED
                : failures == entries.size()
                ? Status.ERROR : Status.PARTIAL_ERROR;

        return new SupportAssessmentBatch(
                status,
                "Local baseline claims, not DBN annotations or causal conclusions.",
                entries);

    }


    // Unavailable batch \\

    public static SupportAssessmentBatch unavailable(
            InvestigationReport structural,
            Status status,
            String message
    ) {

        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");

        if (status == Status.EVALUATED || status == Status.PARTIAL_ERROR)
            throw new IllegalArgumentException(
                    "Unavailable batch cannot have an evaluated status");

        List<Entry> entries = new ArrayList<>();

        for (int index : supportIndexes(structural))
            entries.add(new Entry(index, status, message, null));

        return new SupportAssessmentBatch(status, message, entries);

    }

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


    // Per-finding result \\

    public record Entry(
            int findingIndex,
            Status status,
            String message,
            TacticalAssessment assessment
    ) {

        public Entry {

            Objects.requireNonNull(status, "status");

            if (findingIndex < 0)
                throw new IllegalArgumentException("Negative finding index");

            if ((status == Status.EVALUATED) != (assessment != null))
                throw new IllegalArgumentException(
                        "Only evaluated entries may contain an assessment");

            if (assessment == null && (message == null || message.isBlank()))
                throw new IllegalArgumentException(
                        "Unavailable entry requires an explanation");

            if (status == Status.PARTIAL_ERROR)
                throw new IllegalArgumentException(
                        "Partial error is a batch status, not an entry status");

        }

    }


}