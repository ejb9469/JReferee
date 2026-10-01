package contracts;


import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;

import org.jetbrains.annotations.Nullable;

/**
 * Marker interface for a JReferee or imported external order/unit representation.
 *
 * <p>Implementations may be immutable game-facing orders, mutable
 * adjudication work orders, or typed source orders. Structurally distinct
 * order families remain distinct classes; this marker exists only to provide
 * a shared translation contract.</p>
 */
public interface OrderForm {


    Nation          owner();                // En(glish)

    @Nullable   // i.e. in `WaiveOrder`
    UnitType        unitType();             // F(leet)

    @Nullable   // i.e. in `WaiveOrder`
    Province        origin();               // B(arents Sea)

    @Nullable   // i.e. in `UnitId`
    OrderType       orderType();            // S(upports)

    @Nullable
    Province        target();               // Nwy

    @Nullable
    Province        auxiliaryTarget();      // Stp
    //Province        location();  // change to `origin()`,
                                    // .. but allow enums to have this func (simply duplicate into `auxTarget()`)

}