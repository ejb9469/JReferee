package analysis.tactics.detective;


/**
 * Recognizes at least two hold supports naming the same non-moving unit.
 *
 * <p>A known HOLD, SUPPORT, or CONVOY recipient qualifies. An unknown
 * recipient order produces an incomplete group; a known MOVE does not qualify.</p>
 */
public final class MultipleSupportToHoldDetective
        extends MultipleSupportDetective {


    // Construction \\

    public MultipleSupportToHoldDetective() {
        super(false);
    }


}