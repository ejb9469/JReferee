package phase.movement;

import domain.Nation;
import domain.Province;
import domain.UnitType;
import phase.Order;
import phase.UnitId;

import java.util.UUID;


public class NormalizedOrderSelfCheck {


    private NormalizedOrderSelfCheck() {  }


    public static void main(String[] args) {

        UnitId first = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId second = new UnitId(
                UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Mar);

        Order order = Order.hold(first);
        NormalizedOrder normalized = new NormalizedOrder(first, order, false);

        if (normalized.owner() != order.owner()
                || normalized.orderType() != order.orderType()
                || normalized.submitted())
            throw new AssertionError("Normalized order did not preserve its properties");

        try {
            new NormalizedOrder(second, order, true);
        } catch (IllegalArgumentException expected) {
            System.out.println("NormalizedOrder checks passed.");
            return;
        }

        throw new AssertionError("Mismatched normalized issuer was accepted");

    }


}