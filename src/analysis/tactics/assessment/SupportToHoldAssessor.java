package analysis.tactics.assessment;


/**
 * Assesses one complete support-to-hold relationship.
 *
 * <p>The recipient may be issuing HOLD, SUPPORT, or CONVOY.
 * Survival is taken from the final board, not the recipient order's verdict.</p>
 */
public final class SupportToHoldAssessor extends SupportAssessor {


    // Construction \\

    public SupportToHoldAssessor() {
        super(false);
    }


}