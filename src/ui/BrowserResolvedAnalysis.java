package ui;

import analysis.tactics.*;
import analysis.tactics.assessment.ProcessorMovementResolver;
import analysis.tactics.outcome.*;
import phase.movement.MovementProcessor;

import java.util.Objects;

import static ui.TacticalJson.field;


/**
 * Performs optional local resolution once per browser snapshot.
 *
 * <p>Structural detection has already completed. Resolution or outcome
 * failures do not invalidate that structural report.</p>
 */
public final class BrowserResolvedAnalysis {


    // Configuration \\

    public static final String ENGINE_REVISION_PROPERTY =
            "jreferee.assessment.engineRevision";


    // Construction \\

    private BrowserResolvedAnalysis() {  }


    // Browser orchestration \\

    public static Sections analyze(InvestigationReport structural) {

        Objects.requireNonNull(structural, "structural");

        ProcessorMovementResolver resolver;
        ResolvedMovementContext resolved;

        try {

            String revision = System.getProperty(ENGINE_REVISION_PROPERTY);

            if (revision == null || revision.isBlank())
                return unavailable(
                        structural,
                        "DISABLED",
                        "Local resolution is disabled. Set "
                                + ENGINE_REVISION_PROPERTY
                                + " to the actual adjudicator build identifier.");

            if (!structural.context().complete())
                return unavailable(
                        structural,
                        "INCOMPLETE_SCENARIO",
                        "Local resolution was not run because active units "
                                + "have unknown orders. No implicit HOLDs were added.");

            resolver = new ProcessorMovementResolver(
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

        } catch (RuntimeException exception) {

            exception.printStackTrace(System.err);

            return unavailable(
                    structural,
                    "ERROR",
                    "Local resolution failed. Structural findings remain "
                            + "available; see the server log.");

        }

        String outcomeJson;

        try {

            OutcomeReport outcomes =
                    new OutcomeAgency().investigateReport(resolved);

            outcomeJson = OutcomeReportJsonWriter.toJson(outcomes);

        } catch (RuntimeException exception) {

            exception.printStackTrace(System.err);

            outcomeJson = OutcomeReportJsonWriter.unavailable(
                    "ERROR",
                    "Outcome investigation failed after local resolution. "
                            + "Outcome coverage was not recorded.");

        }

        String supportJson;

        try {

            supportJson = BrowserSupportAssessments.toJson(structural, resolved);

        } catch (RuntimeException exception) {

            exception.printStackTrace(System.err);

            supportJson = BrowserSupportAssessments.unavailable(
                    structural,
                    "ERROR",
                    "Support assessment failed. Outcome and structural "
                            + "reports are independent.");

        }

        /*
         * Preserve explicit configuration fields expected by the earlier
         * support-assessment renderer. Replace only the known serializer
         * fragment, without parsing or modifying user-provided strings.
         */
        String basicConfiguration = configurationIdentity(resolved);
        String fullConfiguration = configuration(resolver, resolved);

        supportJson = supportJson.replace(
                basicConfiguration,
                fullConfiguration);

        return new Sections(outcomeJson, supportJson);

    }


    // Unavailable sections \\

    private static Sections unavailable(
            InvestigationReport structural,
            String status,
            String message
    ) {

        return new Sections(
                OutcomeReportJsonWriter.unavailable(status, message),
                BrowserSupportAssessments.unavailable(
                        structural, status, message));

    }


    // Configuration serialization \\

    private static String configurationIdentity(
            ResolvedMovementContext resolved
    ) {

        StringBuilder out = new StringBuilder("\"configuration\":{");

        field(out, "rulesetId", resolved.rulesetId());
        out.append(',');
        field(out, "scenarioId", resolved.scenario().id());

        return out.append('}').toString();

    }

    private static String configuration(
            ProcessorMovementResolver resolver,
            ResolvedMovementContext resolved
    ) {

        StringBuilder out = new StringBuilder("\"configuration\":{");

        field(out, "rulesetId", resolver.rulesetId());
        out.append(',');
        field(out, "engineRevision", resolver.engineRevision());
        out.append(',');
        field(out, "policy", resolver.policy().name());

        out.append(",\"trialCount\":").append(resolver.trialCount());
        out.append(",\"shuffleSeed\":").append(resolver.shuffleSeed());
        out.append(',');

        field(out, "scenarioId", resolved.scenario().id());

        return out.append('}').toString();

    }


    // Independent JSON sections \\

    public record Sections(
            String resolvedEvents,
            String localSupportAssessments
    ) {

        public Sections {
            Objects.requireNonNull(resolvedEvents, "resolvedEvents");
            Objects.requireNonNull(localSupportAssessments, "localSupportAssessments");
        }

    }


}