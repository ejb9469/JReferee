package misc;

import domain.*;
import adjudication.util.Orders;

import java.util.*;

/**
 * The `PiecePusher` class holds a combination of:
 *          "movement phase" (i.e. Spring or Fall) Orders,
 *          and retreat phase Orders<br><br>
 *
 * Each time `PiecePusher::push()` is called, PiecePusher progresses in 3 states: <i>movement</i>, <i>retreats</i>, and <i>"complete"</i>
 *      (builds are handled separately)<br><br>
 * When `PiecePusher::push()` is 'pushing' in the <u>movement</u> phase, the movements are forced / reflected on the board, and retreats are generated.<br>
 * When `PiecePusher::push()` is 'pushing' in the <u>retreats</u> phase, the retreats are forced / reflected on the board. The movement phase Orders remain unaltered.
 */
public class PiecePusher {


    protected final Collection<Order>       movementPhaseOrders;  // MUST BE EXPLICITLY ASSIGNED VIA CONSTRUCTOR
    protected final Collection<Order>       retreatPhaseOrders;

    protected       Map<Province, UnitType>  unitTypeMap  = null;
    protected       Map<Province, Nation>    unitOwnerMap = null;


    public PiecePusher(Collection<Order> movementPhaseOrders) {
        this.movementPhaseOrders = movementPhaseOrders;
        this.retreatPhaseOrders = new HashSet<>();
        initialize(false);
    }

    public PiecePusher(Collection<Order> movementPhaseOrders, Collection<Order> retreatPhaseOrders) {
        this.movementPhaseOrders = movementPhaseOrders;
        this.retreatPhaseOrders = retreatPhaseOrders;
        initialize(true);
    }

    // constructor helper method
    private void initialize(boolean useRetreatsPos) {
        this.unitTypeMap = new HashMap<>();
        this.unitOwnerMap = new HashMap<>();
        Collection<Order> orders;
        if (useRetreatsPos)
            orders = retreatPhaseOrders;
        else
            orders = movementPhaseOrders;
        for (Order order : orders) {
            this.unitTypeMap.put(order.pos0, order.unitType);
            this.unitOwnerMap.put(order.pos0, order.owner);
        }
    }


    @SuppressWarnings("PointlessBooleanExpression")
    protected void push() {

        Map<Province,UnitType> unitMap1 = new HashMap<>();
        Map<Province,Nation>   unitMap2 = new HashMap<>();

        if (!retreatPhaseOrders.isEmpty()) {

            for (Order retreatOrder : Orders.pruneForOrderType(OrderType.RETREAT, retreatPhaseOrders)) {
                if (retreatOrder.verdict == true) {
                    unitMap1.put(retreatOrder.pos1, retreatOrder.unitType);
                    unitMap2.put(retreatOrder.pos1, retreatOrder.owner);
                }
            }

            this.unitTypeMap.putAll(unitMap1);
            this.unitOwnerMap.putAll(unitMap2);
            return;

        }  // ELSE: below

        Collection<Order> retreats = new ArrayList<>();
        for (Order order : movementPhaseOrders) {

            if (order.orderType == OrderType.MOVE) {
                if (order.verdict == true) {
                    unitMap1.put(order.pos1, order.unitType);
                    unitMap2.put(order.pos1, order.owner);
                } else {
                    // TODO: Check for assailants (?)
                    unitMap1.put(order.pos0, order.unitType);
                    unitMap2.put(order.pos0, order.owner);
                }
            }

            else if (order.orderType == OrderType.HOLD || order.orderType == OrderType.SUPPORT || order.orderType == OrderType.CONVOY) {
                if (order.verdict == true) {
                    unitMap1.put(order.pos0, order.unitType);
                    unitMap2.put(order.pos0, order.owner);
                } else {
                    Collection<Order> assailants = Orders.locateUnitsMovingToPosition(order.pos0, movementPhaseOrders);
                    boolean anySuccessfulAssailant = false;
                    for (Order moveOrder : assailants) {
                        if (moveOrder.verdict == true) {
                            anySuccessfulAssailant = true;
                            Order retreatOrder = new Order(
                                    moveOrder.owner, moveOrder.unitType, moveOrder.pos1,
                                    OrderType.RETREAT, null, null);
                            retreatOrder.dislodged = true;
                            retreats.add(retreatOrder);
                            break;  // 2+ units cannot succeed to the same area
                        }
                    }
                    if (!anySuccessfulAssailant) {
                        unitMap1.put(order.pos0, order.unitType);
                        unitMap2.put(order.pos0, order.owner);
                    }
                }
            }

        }

        retreatPhaseOrders.addAll(retreats);  // no need to `.clear()`: is already empty
        this.unitTypeMap.putAll(unitMap1);
        this.unitOwnerMap.putAll(unitMap2);

    }

}