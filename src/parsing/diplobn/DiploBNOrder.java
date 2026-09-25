package parsing.diplobn;

import contracts.OrderForm;
import domain.OrderType;
import domain.Province;
import phase.UnitId;

import java.util.Objects;


/**
 * One decoded DiploBN movement submission.
 *
 * <p>This source-level order is independent of JReferee's immutable phase
 * order and mutable adjudication work-order representations. Translators can
 * convert it to either representation through {@link contracts.OrderTranslator}.</p>
 */
public class DiploBNOrder extends phase.Order {

    public DiploBNOrder(UnitId unit, OrderType type, Province target, Province auxiliaryTarget) {

        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(type, "type");

        if (type != OrderType.HOLD
                && type != OrderType.MOVE
                && type != OrderType.SUPPORT
                && type != OrderType.CONVOY)
            throw new IllegalArgumentException("DiploBN movement order cannot use " + type);

        super(unit, type, target, auxiliaryTarget);

    }


    /**
     * Creates one source-level DiploBN movement form from a JReferee immutable
     * phase order.
     */
    public static DiploBNOrder from(
            phase.Order order
    ) {

        Objects.requireNonNull(order, "order");

        return new DiploBNOrder(
                order.unit(),
                order.type(),
                order.target(),
                order.auxiliaryTarget());

    }

}