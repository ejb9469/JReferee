package ui;

import analysis.tactics.*;
import analysis.tactics.assessment.SupportAssessmentBatch;
import analysis.tactics.outcome.ResolvedMovementContext;

import java.util.Objects;

import static ui.TacticalJson.field;


/**
 * Browser serialization of typed support-assessment batches.
 */
public final class BrowserSupportAssessments {


    // Construction \\

    private BrowserSupportAssessments() {  }


    // Compatibility entry points \\

    public static String toJson(
            InvestigationReport structural,
            ResolvedMovementContext resolved
    ) {

        SupportAssessmentBatch batch =
                SupportAssessmentBatch.assess(structural, resolved);

        ResolutionConfiguration configuration =
                batch.status() == SupportAssessmentBatch.Status.NO_SUPPORTED_FINDINGS
                        ? null : ResolutionConfiguration.identity(resolved);

        return toJson(batch, configuration);

    }

    public static String unavailable(
            InvestigationReport structural,
            String status,
            String message
    ) {

        return toJson(
                SupportAssessmentBatch.unavailable(
                        structural,
                        SupportAssessmentBatch.Status.valueOf(status),
                        message),
                null);

    }


    // Serialization \\

    public static String toJson(
            SupportAssessmentBatch batch,
            ResolutionConfiguration configuration
    ) {

        Objects.requireNonNull(batch, "batch");

        validateConfiguration(batch, configuration);

        StringBuilder out = new StringBuilder("{");

        field(out, "status", batch.status().name());
        out.append(',');
        field(out, "message", batch.message());

        out.append(",\"eligibleCount\":").append(batch.eligibleCount());
        out.append(",\"evaluatedCount\":").append(batch.evaluatedCount());
        out.append(",\"failedCount\":").append(batch.failedCount());

        out.append(",\"configuration\":");
        appendConfiguration(out, configuration);

        out.append(",\"entries\":[");

        for (int index = 0; index < batch.entries().size(); index++) {

            if (index > 0)
                out.append(',');

            appendEntry(out, batch.entries().get(index));

        }

        return out.append("]}").toString();

    }

    private static void validateConfiguration(
            SupportAssessmentBatch batch,
            ResolutionConfiguration configuration
    ) {

        for (SupportAssessmentBatch.Entry entry : batch.entries()) {

            if (entry.assessment() == null)
                continue;

            TacticalScenario scenario = entry.assessment().scenario();

            if (configuration == null
                    || !configuration.rulesetId().equals(
                    scenario.context().rulesetId())
                    || !configuration.scenarioId().equals(scenario.id()))
                throw new IllegalArgumentException(
                        "Browser configuration differs from assessment evidence");

        }

    }

    private static void appendConfiguration(
            StringBuilder out,
            ResolutionConfiguration configuration
    ) {

        if (configuration == null) {
            out.append("null");
            return;
        }

        out.append('{');
        field(out, "rulesetId", configuration.rulesetId());

        if (configuration.hasProcessorDetails()) {

            out.append(',');
            field(out, "engineRevision", configuration.engineRevision());
            out.append(',');
            field(out, "policy", configuration.policy().name());

            out.append(",\"trialCount\":").append(configuration.trialCount());
            out.append(",\"shuffleSeed\":").append(configuration.shuffleSeed());

        }

        out.append(',');
        field(out, "scenarioId", configuration.scenarioId());
        out.append('}');

    }

    private static void appendEntry(
            StringBuilder out,
            SupportAssessmentBatch.Entry entry
    ) {

        out.append("{\"findingIndex\":").append(entry.findingIndex());
        out.append(',');
        field(out, "status", entry.status().name());
        out.append(',');
        field(out, "message", entry.message());

        TacticalAssessment assessment = entry.assessment();

        if (assessment == null) {

            out.append(",\"assessmentVersion\":null,\"claims\":[]}");
            return;

        }

        out.append(',');
        field(out, "assessmentVersion", assessment.assessmentVersion());
        out.append(",\"claims\":[");

        for (int index = 0; index < assessment.findings().size(); index++) {

            if (index > 0)
                out.append(',');

            TacticalAssessment.Finding finding =
                    assessment.findings().get(index);

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


}