package analysis.tactics;

import java.util.*;


/**
 * Scenario-specific assessment of a complete tactical pattern.<br><br>
 *
 * Each category has separate outcome claims rather than one general
 * "success" flag. Findings do not assign strategic scores or infer intent.
 */
public record TacticalAssessment(
        TacticMatch match,
        TacticalScenario scenario,
        String assessmentVersion,
        List<Finding> findings
) {


    // Construction and validation \\

    public TacticalAssessment {

        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(assessmentVersion, "assessmentVersion");

        if (assessmentVersion.isBlank())
            throw new IllegalArgumentException(
                    "assessmentVersion must not be blank");

        if (!match.completePattern())
            throw new IllegalArgumentException(
                    "Complete the scenario and re-detect an incomplete match");

        scenario.requireExtensionOf(match.context());

        findings = List.copyOf(
                Objects.requireNonNull(findings, "findings"));

        Set<Claim> expected =
                TacticalAssessmentContracts.requiredClaims(match.kind());

        Set<Claim> actual = EnumSet.noneOf(Claim.class);

        for (Finding finding : findings)
            if (!actual.add(finding.claim()))
                throw new IllegalArgumentException(
                        "Duplicate claim: " + finding.claim());

        // Missing claims must be UNKNOWN findings, not silently omitted.
        if (!actual.equals(expected))
            throw new IllegalArgumentException(
                    "Expected claims " + expected + ", found " + actual);

        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator.comparing(Finding::claim));

        findings = List.copyOf(sorted);

    }


    // Claims and verdicts \\

    public enum Claim {

        SUPPORT_EFFECTIVE,
        SUPPORTED_MOVE_SUCCEEDED,
        SUBJECT_SURVIVED,
        SELF_BOUNCE_REALIZED,
        GARRISON_PRESERVED,
        COMPETITION_SAVED_GARRISON

    }

    public enum Truth {

        TRUE,
        FALSE,
        UNKNOWN

    }


    // Findings \\

    public record Finding(
            Claim claim,
            Truth truth,
            List<Evidence> evidence
    ) {

        public Finding {

            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(truth, "truth");

            evidence = List.copyOf(
                    Objects.requireNonNull(evidence, "evidence"));

            if (evidence.isEmpty())
                throw new IllegalArgumentException(
                        "Every finding requires evidence or an UNKNOWN reason");

        }

    }


    // Evidence and counterfactual provenance \\

    /**
     * One supporting observation or explanation of unavailable evidence.<br><br>
     *
     * Counterfactual evidence retains the complete intervened scenario,
     * not merely its name or a prose description.
     *
     * <p>Example codes:</p>
     * <ul>
     *     <li>SUPPORT_OUTCOME</li>
     *     <li>BASELINE_DEFENDER_RETAINED</li>
     *     <li>ISOLATED_ATTACK_DISLODGED_DEFENDER</li>
     *     <li>SIMULATION_BUDGET_EXHAUSTED</li>
     * </ul>
     */
    public record Evidence(
            String code,
            TacticalScenario scenario,
            String explanation
    ) {

        public Evidence {

            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(scenario, "scenario");
            Objects.requireNonNull(explanation, "explanation");

            if (code.isBlank() || explanation.isBlank())
                throw new IllegalArgumentException(
                        "Evidence code and explanation must not be blank");

        }

    }


}