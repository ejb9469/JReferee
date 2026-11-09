package agents.search;

/**
 * Chooses the returned policy without changing search sampling or updates.
 */
public enum PolicyOutput {

    /** Policy formed after the last completed value update. */
    FINAL,

    /** Equal-weight average of the policies used during completed iterations. */
    AVERAGE

}