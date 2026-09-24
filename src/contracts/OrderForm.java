package contracts;


/**
 * Marker interface for a JReferee or imported external order representation.
 *
 * <p>Implementations may be immutable game-facing orders, mutable
 * adjudication work orders, or typed source orders. Structurally distinct
 * order families remain distinct classes; this marker exists only to provide
 * a shared translation contract.</p>
 */
public interface OrderForm {

}