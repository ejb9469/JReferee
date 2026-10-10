package analysis.tactics.assessment;

import analysis.tactics.*;
import analysis.tactics.detective.SupportToHoldDetective;
import analysis.tactics.detective.SupportToMoveDetective;
import domain.OrderType;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.*;


/**
 * Baseline assessment of a single support relationship.
 *
 * <p>SUPPORT_EFFECTIVE is the adjudicator's order-level support verdict.
 * It does not establish necessity, causation, or contribution to every
 * strength calculation. A false verdict does not establish a support cut.</p>
 */
public abstract class SupportAssessor implements TacticAssessor {


    // Core state \\

    private final TacticKind kind;
    private final String assessmentVersion;
    private final Detective detective;


    // Construction \\

    protected SupportAssessor(boolean moving) {

        kind = moving
                ? TacticKind.SUPPORT_TO_MOVE
                : TacticKind.SUPPORT_TO_HOLD;

        assessmentVersion = moving
                ? "support-to-move-baseline-v1"
                : "support-to-hold-baseline-v1";

        detective = moving
                ? new SupportToMoveDetective()
                : new SupportToHoldDetective();

    }


    // Public single-match assessment \\

    @Override
    public final TacticalAssessment assess(
            TacticMatch match,
            TacticalScenario scenario,
            MovementResolver resolver
    ) {

        Objects.requireNonNull(resolver, "resolver");
        validateInput(match, scenario);

        if (!scenario.context().rulesetId().equals(resolver.rulesetId()))
            throw new IllegalArgumentException(
                    "Resolver ruleset differs from the scenario");

        if (!detective.investigate(match.context()).contains(match))
            throw new IllegalArgumentException(
                    "Finding is not recognized by the current support detective");

        MovementResult result = resolver.resolve(scenario);

        if (!scenario.context().rulesetId().equals(resolver.rulesetId()))
            throw new IllegalStateException(
                    "Resolver identity changed during assessment");

        MovementResolutionChecks.requireCorrespondence(scenario, result);

        return evaluate(match, scenario, result);

    }


    // Validated batch entry point \\

    /**
     * Called only after the batch validates the shared result and verifies
     * membership in the current detective's recognition set.
     */
    final TacticalAssessment assessRecognized(
            TacticMatch match,
            TacticalScenario scenario,
            MovementResult validatedResult
    ) {

        validateInput(match, scenario);
        Objects.requireNonNull(validatedResult, "validatedResult");

        return evaluate(match, scenario, validatedResult);

    }

    private void validateInput(
            TacticMatch match,
            TacticalScenario scenario
    ) {

        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(scenario, "scenario");

        if (match.kind() != kind)
            throw new IllegalArgumentException(
                    "This assessor requires " + kind);

        if (!match.completePattern())
            throw new IllegalArgumentException(
                    "Complete the scenario and re-detect the support relationship");

        /*
         * Batch matches already retain this exact complete context.
         * Public callers may provide a genuine extension of an earlier context.
         */
        if (match.context() != scenario.context())
            scenario.requireExtensionOf(match.context());

        if (match.participants().size() != 2
                || match.units(TacticMatch.Role.SUPPORTER).size() != 1
                || match.units(TacticMatch.Role.SUPPORTED_UNIT).size() != 1)
            throw new IllegalArgumentException(
                    "Expected exactly one supporter and one recipient");

    }


    // Claim evaluation \\

    private TacticalAssessment evaluate(
            TacticMatch match,
            TacticalScenario scenario,
            MovementResult result
    ) {

        UnitId supporter =
                match.units(TacticMatch.Role.SUPPORTER).getFirst();

        UnitId recipient =
                match.units(TacticMatch.Role.SUPPORTED_UNIT).getFirst();

        MovementResult.Outcome support = result.outcomes().get(supporter);
        MovementResult.Outcome subject = result.outcomes().get(recipient);

        List<TacticalAssessment.Finding> findings = new ArrayList<>();
        findings.add(supportFinding(scenario, support));

        if (kind == TacticKind.SUPPORT_TO_MOVE) {

            findings.add(finding(
                    TacticalAssessment.Claim.SUPPORTED_MOVE_SUCCEEDED,
                    truth(subject.successfulMove()),
                    scenario,
                    "BASELINE_SUPPORTED_MOVE",
                    "The configured resolver reports the recipient's submitted "
                            + "move as "
                            + (subject.successfulMove()
                            ? "successful." : "unsuccessful.")
                            + " This does not establish that this support was necessary."));

        } else {

            boolean survived = result.finalLocations().containsKey(recipient);

            findings.add(finding(
                    TacticalAssessment.Claim.SUBJECT_SURVIVED,
                    truth(survived),
                    scenario,
                    "BASELINE_SUBJECT_SURVIVAL",
                    survived
                            ? "The recipient remains on the board after movement."
                            : "The recipient was dislodged during movement. "
                            + "This does not mean it has been destroyed; "
                            + "a retreat may still be available."));

        }

        return new TacticalAssessment(
                match,
                scenario,
                assessmentVersion,
                findings);

    }

    private static TacticalAssessment.Finding supportFinding(
            TacticalScenario scenario,
            MovementResult.Outcome support
    ) {

        if (support.effectiveType() != OrderType.SUPPORT
                || support.rewrittenBySzykman()) {

            return finding(
                    TacticalAssessment.Claim.SUPPORT_EFFECTIVE,
                    TacticalAssessment.Truth.UNKNOWN,
                    scenario,
                    "SUPPORT_REWRITE_NOT_INTERPRETED",
                    "The submitted support was represented by a different effective "
                            + "instruction or a policy rewrite. This assessor does "
                            + "not infer effectiveness from that replacement.");

        }

        return finding(
                TacticalAssessment.Claim.SUPPORT_EFFECTIVE,
                truth(support.succeeded()),
                scenario,
                "BASELINE_SUPPORT_VERDICT",
                support.succeeded()
                        ? "The resolver accepted the submitted support. This is an "
                        + "order-level verdict, not proof of necessity or "
                        + "contribution to every strength calculation."
                        : "The resolver rejected the submitted support. The available "
                        + "outcome does not distinguish invalidity, mismatch, "
                        + "cutting, dislodgement, or other failure causes.");

    }


    // Evidence \\

    private static TacticalAssessment.Truth truth(boolean value) {
        return value
                ? TacticalAssessment.Truth.TRUE
                : TacticalAssessment.Truth.FALSE;
    }

    private static TacticalAssessment.Finding finding(
            TacticalAssessment.Claim claim,
            TacticalAssessment.Truth truth,
            TacticalScenario scenario,
            String code,
            String explanation
    ) {

        String detail = explanation
                + " Scenario=" + scenario.id()
                + "; ruleset=" + scenario.context().rulesetId()
                + ". Input provenance remains attached to the scenario.";

        return new TacticalAssessment.Finding(
                claim,
                truth,
                List.of(new TacticalAssessment.Evidence(
                        code, scenario, detail)));

    }


}