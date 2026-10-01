package adjudication.util;

import contracts.OrderForm;
import domain.Province;

import java.util.Comparator;
import java.util.Objects;
import java.util.function.Function;


public class OrderComparator implements Comparator<OrderForm> {

    private final Function<? super OrderForm, Province> origin;


    public OrderComparator() {
        this(OrderForm::origin);
    }

    public OrderComparator(Function<? super OrderForm, Province> origin) {
        this.origin = Objects.requireNonNull(origin, "origin");
    }


    @Override
    public int compare(OrderForm first, OrderForm second) {

        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");

        int comparison = compareNullable(first.owner(), second.owner());

        if (comparison != 0)
            return comparison;

        comparison = compareNullable(first.orderType(), second.orderType());

        if (comparison != 0)
            return comparison;

        comparison = compareNullable(first.unitType(), second.unitType());

        if (comparison != 0)
            return comparison;

        comparison = compareProvince(origin.apply(first), origin.apply(second));

        if (comparison != 0)
            return comparison;

        comparison = compareProvince(first.target(), second.target());

        if (comparison != 0)
            return comparison;

        // No hash-code fallback: compare the actual order properties.
        return compareProvince(first.auxiliaryTarget(), second.auxiliaryTarget());

    }

    private static int compareProvince(Province first, Province second) {
        return compareNullable(
                first == null ? null : first.name(),
                second == null ? null : second.name());
    }

    private static <T extends Comparable<? super T>> int compareNullable(T first, T second) {

        if (first == second)
            return 0;

        if (first == null)
            return -1;

        if (second == null)
            return 1;

        return first.compareTo(second);

    }

}