package ui;

import analysis.tactics.*;
import analysis.tactics.assessment.ProcessorMovementResolver;
import analysis.tactics.assessment.SupportAssessmentBatch;
import analysis.tactics.outcome.*;
import phase.movement.MovementProcessor;

import java.util.Objects;


/**
 * Optional local resolution and independent analysis products for one snapshot.
 *
 * <p>Resolution runs once. Outcome investigators and support assessors consume
 * the same result. Failures do not invalidate the structural report.</p>
 */
public final class BrowserResolvedAnalysis {


    // Configuration \\

    public static final String ENGINE_REVISION_PROPERTY =
            "jreferee.assessment.engineRevision";


    // Construction \\

    private BrowserResolvedAnalysis() {  }


    // Orchestration \\

    public static Sections analyze(InvestigationReport structural) {

        Objects.requireNonNull(structural, "structural");

        ResolvedMovementContext resolved;
        ResolutionConfiguration configuration;

        try {

            String revision = System.getProperty(ENGINE_REVISION_PROPERTY);

            if (revision == null || revision.isBlank())
                return unavailable(
                        structural,
                        SupportAssessmentBatch.Status.DISABLED,
                        "Local resolution is disabled. Set "
                                + ENGINE_REVISION_PROPERTY
                                + " to the actual adjudicator build identifier.");

            if (!structural.context().complete())
                return unavailable(
                        structural,
                        SupportAssessmentBatch.Status.INCOMPLETE_SCENARIO,
                        "Local resolution was not run because active units "
                                + "have unknown orders. No implicit HOLDs were added.");

            ProcessorMovementResolver resolver = new ProcessorMovementResolver(
                    MovementProcessor.Policy.SZYKMAN_JUSTICE,
                    32,
                    0L,
                    revision);

            TacticalContext original = structural.context();

            TacticalContext local = new TacticalContext(
                    resolver.rulesetId(),
                    original.moment(),
                    original.board(),
                    original.orders());

            TacticalScenario scenario = new TacticalScenario(
                    "browser-baseline-"
                            + local.moment().year()
                            + "-"
                            + local.moment().gamePhase().name(),
                    local);

            resolved = ResolvedMovementContext.resolve(scenario, resolver);
            configuration = ResolutionConfiguration.from(resolver, resolved);

        } catch (RuntimeException exception) {

            exception.printStackTrace(System.err);

            return unavailable(
                    structural,
                    SupportAssessmentBatch.Status.ERROR,
                    "Local resolution failed. Structural findings remain "
                            + "available; see the server log.");

        }

        String outcomes;

        try {

            outcomes = OutcomeReportJsonWriter.toJson(
                    new OutcomeAgency().investigateReport(resolved));

        } catch (RuntimeException exception) {

            exception.printStackTrace(System.err);

            outcomes = OutcomeReportJsonWriter.unavailable(
                    "ERROR",
                    "Outcome investigation failed after local resolution. "
                            + "Outcome coverage was not recorded.");

        }

        String supports;

        try {

            SupportAssessmentBatch batch =
                    SupportAssessmentBatch.assess(structural, resolved);

            supports = BrowserSupportAssessments.toJson(
                    batch,
                    batch.status()
                            == SupportAssessmentBatch.Status.NO_SUPPORTED_FINDINGS
                            ? null : configuration);

        } catch (RuntimeException exception) {

            exception.printStackTrace(System.err);

            supports = BrowserSupportAssessments.unavailable(
                    structural,
                    "ERROR",
                    "Support assessment failed. Outcome and structural "
                            + "reports are independent.");

        }

        return new Sections(outcomes, supports);

    }


    // Unavailable analysis \\

    private static Sections unavailable(
            InvestigationReport structural,
            SupportAssessmentBatch.Status status,
            String message
    ) {

        return new Sections(
                OutcomeReportJsonWriter.unavailable(status.name(), message),
                BrowserSupportAssessments.unavailable(
                        structural, status.name(), message));

    }


    // Independently serialized products \\

    public record Sections(
            String resolvedEvents,
            String localSupportAssessments
    ) {

        public Sections {

            Objects.requireNonNull(resolvedEvents, "resolvedEvents");
            Objects.requireNonNull(
                    localSupportAssessments, "localSupportAssessments");

        }

    }


}