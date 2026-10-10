package analysis.tactics.assessment;


/**
 * Assesses one complete support-to-move relationship.
 *
 * <p>Reports the support's baseline verdict and the recipient move's
 * success. No necessity or counterfactual claim is made.</p>
 */
public final class SupportToMoveAssessor extends SupportAssessor {


    // Construction \\

    public SupportToMoveAssessor() {
        super(true);
    }


}