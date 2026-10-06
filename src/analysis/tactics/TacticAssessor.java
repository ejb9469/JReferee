package analysis.tactics;

import phase.movement.MovementResult;


/**
 * Interface of scenario-based tactical assessors.<br><br>
 *
 * An assessor evaluates an already-recognized, complete structural match.
 * Adjudication is delegated to a MovementResolver; tactical interpretation
 * remains separate from the adjudicator's rules.
 *
 * <p>Implementations must:</p>
 * <ul>
 *     <li>reject null arguments;</li>
 *     <li>require match.completePattern();</li>
 *     <li>validate scenario.requireExtensionOf(match.context());</li>
 *     <li>reject a resolver with a different ruleset;</li>
 *     <li>use the resolver for baseline and counterfactual adjudication;</li>
 *     <li>retain complete counterfactual scenarios as evidence;</li>
 *     <li>report unavailable or budget-limited conclusions as UNKNOWN;</li>
 *     <li>return every claim required by TacticalAssessment;</li>
 *     <li>leave caller-owned contexts and live Games unmodified.</li>
 * </ul>
 *
 * <p>The returned assessmentVersion identifies the assessment semantics,
 * including any counterfactual intervention protocol.</p>
 */
public interface TacticAssessor {


    // Assessment \\

    TacticalAssessment assess(
            TacticMatch match,
            TacticalScenario scenario,
            MovementResolver resolver
    );


    // Adjudication boundary \\

    /**
     * Adapter to game-facing movement adjudication.<br><br>
     *
     * One resolver instance uses a fixed adjudication configuration:
     * policy, trial count, seed, and any other outcome-affecting settings.
     */
    interface MovementResolver {

        /**
         * Identifies the configured ruleset.<br><br>
         *
         * The identifier must describe the configuration sufficiently
         * for the caller's reproducibility requirements.
         */
        String rulesetId();

        /**
         * Resolves exactly the supplied scenario.<br><br>
         *
         * Implementations must reject a ruleset mismatch and return a
         * result corresponding to the supplied board and every explicit
         * scenario order.
         *
         * <p>Resolution must not advance a caller-owned Game.</p>
         */
        MovementResult resolve(TacticalScenario scenario);

    }


}