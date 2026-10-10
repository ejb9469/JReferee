package analysis.tactics.assessment;

import analysis.tactics.MovementResultValidation;
import analysis.tactics.TacticalScenario;
import phase.movement.MovementResult;


/**
 * Compatibility facade for explicit-input assessment validation.
 *
 * <p>Shared scenario/result consistency is implemented in
 * MovementResultValidation. This API preserves the original requirement
 * that every instruction was explicitly passed to the processor.</p>
 */
public final class MovementResolutionChecks {


    // Construction \\

    private MovementResolutionChecks() {  }


    // Validation \\

    public static void requireCorrespondence(
            TacticalScenario scenario,
            MovementResult result
    ) {

        MovementResultValidation.requireCorrespondence(
                scenario,
                result,
                MovementResultValidation.SubmissionPolicy.EXPLICIT_ONLY);

    }


}