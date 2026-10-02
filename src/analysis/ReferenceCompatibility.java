package analysis;

import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.BoardState;
import phase.Order;

import java.util.List;
import java.util.Objects;


public final class ReferenceCompatibility {

    private ReferenceCompatibility() {  }


    public static Issue issue(BoardState board, Order order) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(order, "order");

        boolean support = order.orderType() == OrderType.SUPPORT;
        boolean convoy = order.orderType() == OrderType.CONVOY;

        if (!support && !convoy)
            return Issue.NONE;

        Province target = order.target();
        boolean occupied = false;
        boolean armyPresent = false;

        if (target != null) {
            Province territory = Province.canonical(target);

            for (var entry : board.locations().entrySet()) {
                if (Province.canonical(entry.getValue()) != territory)
                    continue;

                occupied = true;

                if (entry.getKey().unitType() == UnitType.ARMY)
                    armyPresent = true;
            }
        }

        if (!occupied)
            return Issue.MISSING_UNIT;

        if (convoy && !armyPresent)
            return Issue.CONVOY_WITHOUT_ARMY;

        return Issue.NONE;

    }


    public static List<Order> compatibleOrders(
            BoardState board,
            List<Order> ranked) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(ranked, "ranked");

        return ranked.stream()
                .filter(order -> issue(board, order) == Issue.NONE)
                .toList();

    }


    public enum Issue {
        NONE,
        MISSING_UNIT,
        CONVOY_WITHOUT_ARMY
    }

}
