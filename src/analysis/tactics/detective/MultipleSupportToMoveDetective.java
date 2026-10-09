package analysis.tactics.detective;


/**
 * Recognizes at least two supports naming the same recipient move.
 *
 * <p>An unknown recipient order produces an incomplete group. Different
 * destinations form different groups and are never counted together.</p>
 */
public final class MultipleSupportToMoveDetective
        extends MultipleSupportDetective {


    // Construction \\

    public MultipleSupportToMoveDetective() {
        super(true);
    }


}