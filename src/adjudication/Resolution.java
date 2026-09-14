package adjudication;


/**
 * 'Resolution' assigned to submitted Orders.<br>
 *
 * A resolution may be 'complete', assigning SUCCESS or FAILURE to every order,
 * or incomplete, leaving some orders UNKNOWN.
 */
public interface Resolution {


    /**
     * Returns the state assigned to a given Order:
     * {SUCCESS, FAILURE, UNKNOWN}
     */
    ResolutionState stateOf(Order order);


    /**
     * Returns whether this resolution establishes an outcome for every Order.
     */
    boolean isComplete();


}
