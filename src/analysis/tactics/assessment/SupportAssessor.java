package analysis.tactics.assessment;

import analysis.tactics.*;
import analysis.tactics.Detective;
import analysis.tactics.detective.SupportToHoldDetective;
import analysis.tactics.detective.SupportToMoveDetective;
import domain.OrderType;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.*;


/**
 * Shared baseline assessment for a single support relationship.
 *
 * <p>SUPPORT_EFFECTIVE means accepted support under the resolver's verdict:
 * the effective type remains SUPPORT and its verdict is successful.
 * It does not mean necessary support, causal contribution, or contribution
 * to every attack/defend/prevent-strength calculation.</p>
 *
 * <p>A false verdict does not identify the failure reason. In particular,
 * it is not automatically evidence of a support cut.</p>
 */
public abstract class SupportAssessor implements TacticAssessor {


    // Core state \\

    private final TacticKind kind;
    private final String assessmentVersion;
    private final Detective detective;


    // Construction \\

    protected SupportAssessor(boolean moving) {

        this.kind = moving
                ? TacticKind.SUPPORT_TO_MOVE
                : TacticKind.SUPPORT_TO_HOLD;

        this.assessmentVersion = moving
                ? "support-to-move-baseline-v1"
                : "support-to-hold-baseline-v1";

        this.detective = moving
                ? new SupportToMoveDetective()
                : new SupportToHoldDetective();

    }


    // Assessment \\

    @Override
    public final TacticalAssessment assess(
            TacticMatch match,
            TacticalScenario scenario,
            MovementResolver resolver
    ) {

        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(resolver, "resolver");

        validateMatch(match, scenario, resolver);

        String expectedRuleset = scenario.context().rulesetId();

        MovementResult result = resolver.resolve(scenario);

        if (!expectedRuleset.equals(resolver.rulesetId()))
            throw new IllegalStateException(
                    "Resolver identity changed during assessment");

        MovementResolutionChecks.requireCorrespondence(scenario, result);

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
                            + (subject.successfulMove() ? "successful." : "unsuccessful.")
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


    // Input validation \\

    private void validateMatch(
            TacticMatch match,
            TacticalScenario scenario,
            MovementResolver resolver
    ) {

        if (match.kind() != kind)
            throw new IllegalArgumentException(
                    "This assessor requires " + kind);

        if (!match.completePattern())
            throw new IllegalArgumentException(
                    "Complete the scenario and re-detect the support relationship");

        scenario.requireExtensionOf(match.context());

        if (!scenario.context().rulesetId().equals(resolver.rulesetId()))
            throw new IllegalArgumentException(
                    "Resolver ruleset differs from the scenario");

        /*
         * TacticMatch validates common structure, not category semantics.
         * Re-recognize against the original context to reject fabricated
         * participants, incorrect focus, and unsupported detective versions.
         *
         * A future change to support-detection semantics must update this
         * assessor's compatibility and version deliberately.
         */
        if (!detective.investigate(match.context()).contains(match))
            throw new IllegalArgumentException(
                    "Finding is not a recognized support relationship "
                            + "under the current detective version");

        if (match.participants().size() != 2
                || match.units(TacticMatch.Role.SUPPORTER).size() != 1
                || match.units(TacticMatch.Role.SUPPORTED_UNIT).size() != 1)
            throw new IllegalArgumentException(
                    "Expected exactly one supporter and one recipient");

    }


    // Support verdict \\

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


    // Evidence construction \\

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
                        code,
                        scenario,
                        detail)));

    }


}