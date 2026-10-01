package contracts;

import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;


/**
 * Read-only properties shared by order and unit representations.
 *
 * <p>`origin()` is representation-native: for persistent units it is their creation
 * province, not their current position. <br>Positional consumers must use the board.</p>
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
    //Province        location();  // change to `origin()` where this func appears,
                                        // ... but allow enums to have this func
                                            //   temporarily (simply duplicate into `auxTarget()`)


    // Formatting is deliberately separate from board lookup. \\

    static String unitText(OrderForm order, @Nullable Province issuingProvince) {

        Objects.requireNonNull(order, "order");

        return order.owner().getPrefix() + " "
                + (order.unitType() == null ? "?" : order.unitType().name().charAt(0))
                + " " + provinceText(issuingProvince);

    }

    static String format(OrderForm order, @Nullable Province issuingProvince) {

        Objects.requireNonNull(order, "order");

        OrderType type = order.orderType();

        // A waive has neither a unit nor a location.
        if (type == OrderType.WAIVE)
            return order.owner().getPrefix() + " WAIVE";

        String unit = unitText(order, issuingProvince);

        if (type == null)
            return unit;

        return switch (type) {
            case MOVE -> unit + " - " + provinceText(order.target());
            case HOLD -> unit + " H";
            case SUPPORT -> unit + " S " + provinceText(order.target())
                    + (order.auxiliaryTarget() == null
                    ? " H" : " - " + provinceText(order.auxiliaryTarget()));
            case CONVOY -> unit + " C " + provinceText(order.target())
                    + " - " + provinceText(order.auxiliaryTarget());
            case RETREAT -> unit + (order.target() == null
                    ? " PIFF" : " R " + provinceText(order.target()));
            case BUILD -> unit + " BUILD";
            case DESTROY -> unit + " DESTROY";
            case WAIVE -> throw new IllegalStateException("WAIVE was not handled");
        };

    }

    private static String provinceText(@Nullable Province province) {
        return province == null ? "?" : province.toString();
    }


}