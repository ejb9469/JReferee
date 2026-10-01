package phase.movement;

import contracts.OrderForm;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import phase.Order;
import phase.UnitId;

import java.util.Objects;


record NormalizedOrder(
        UnitId unit,
        Order order,
        boolean submitted
) implements OrderForm {

    NormalizedOrder {

        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(order, "order");

        if (!unit.equals(order.unit()))
            throw new IllegalArgumentException("Normalized unit does not match the order issuer");

    }


    @Override
    public Nation owner() {
        return order.owner();
    }

    @Override
    public UnitType unitType() {
        return order.unitType();
    }

    @Override
    public Province origin() {
        return order.origin();
    }

    @Override
    public OrderType orderType() {
        return order.orderType();
    }

    @Override
    public Province target() {
        return order.target();
    }

    @Override
    public Province auxiliaryTarget() {
        return order.auxiliaryTarget();
    }

}