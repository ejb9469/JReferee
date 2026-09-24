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
public record DiploBNOrder(
        UnitId unit,
        OrderType type,
        Province target,
        Province auxiliaryTarget
) implements OrderForm {

    public DiploBNOrder {

        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(type, "type");

        if (type != OrderType.HOLD
                && type != OrderType.MOVE
                && type != OrderType.SUPPORT
                && type != OrderType.CONVOY)
            throw new IllegalArgumentException(
                    "DiploBN movement order cannot use "
                            + type);

        if (type == OrderType.HOLD
                && (target != null || auxiliaryTarget != null))
            throw new IllegalArgumentException(
                    "Hold order must not have targets");

        if (type == OrderType.MOVE && target == null)
            throw new IllegalArgumentException(
                    "Move order requires a destination");

        if (type == OrderType.SUPPORT && target == null)
            throw new IllegalArgumentException(
                    "Support order requires a supported position");

        if (type == OrderType.CONVOY
                && (target == null || auxiliaryTarget == null))
            throw new IllegalArgumentException(
                    "Convoy order requires army origin and destination");

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