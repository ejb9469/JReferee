package adjudication;

import contracts.HomogeneousState;
import domain.Order;
import domain.OrderType;
import domain.Province;
import adjudication.util.Constants;
import adjudication.util.Orders;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;

public class RetreatsJudge extends Judge implements HomogeneousState {

    public static final boolean DEBUG_PRINT = true;


    protected final Collection<Province>    occupiedAreas;
    protected final Collection<Province>    embattledAreas;
    protected       Collection<Order>       movementPhaseOrdersCopy = null;


    public RetreatsJudge() {
        super();
        this.occupiedAreas = new HashSet<>();
        this.embattledAreas = new HashSet<>();
    }

    public RetreatsJudge(Collection<Order> orders) {
        super(orders);
        this.occupiedAreas = new HashSet<>();
        this.embattledAreas = new HashSet<>();
    }

    public RetreatsJudge(Collection<Order> orders, Collection<Province> occupiedAreas, Collection<Province> embattledAreas) {
        super(orders);
        this.occupiedAreas = occupiedAreas;
        this.embattledAreas = embattledAreas;
    }

    public RetreatsJudge(Collection<Order> orders, Collection<Province> occupiedAreas, Collection<Province> embattledAreas, Collection<Order> movementPhaseOrdersCopy) {
        super(orders);
        this.occupiedAreas = occupiedAreas;
        this.embattledAreas = embattledAreas;
        this.movementPhaseOrdersCopy = movementPhaseOrdersCopy;
    }

    public RetreatsJudge(Judge movementJudge) {
        super();
        this.movementPhaseOrdersCopy = List.copyOf(movementJudge.orders);
        // Direct assignment is technically unnecessary for below 2 func calls, ...
        // ... but allows for `occAreas` and `embAreas` to remain `final`
        this.occupiedAreas = this.generateOccupiedAreas(movementPhaseOrdersCopy);
        this.embattledAreas = this.generateEmbattledAreas(movementPhaseOrdersCopy);
    }


    @Override
    public void judge() {

        // Call `enforceStasis()`
        enforceStasis();

        if ((movementPhaseOrdersCopy != null && !movementPhaseOrdersCopy.isEmpty()) &&
            (this.embattledAreas.isEmpty() || this.occupiedAreas.isEmpty())) {
            this.generateOccupiedAreas(movementPhaseOrdersCopy);
            this.generateEmbattledAreas(movementPhaseOrdersCopy);
        }

        for (Order order : orders) {
            order.verdict = adjudicate(order, true);
            order.resolved = true;
        }

    }

    @Override
    protected boolean adjudicate(Order order, boolean optimistic) {

        // Handle RETREAT orders (retreats phase)
        if (order.orderType == OrderType.RETREAT) {

            boolean retreatIsValid = Orders.orderIsValid(order);
            if (!retreatIsValid)
                return false;

            // The unit was 'piffed', but INVOLUNTARILY
            // technically a failed Order; SPECIAL CASE, will be handled at the unit removal level
            if (suffocated(order, this.occupiedAreas, this.embattledAreas,
                    locateDislodgingMove(order, movementPhaseOrdersCopy),
                    this.movementPhaseOrdersCopy)) {
                piff(order);
                return false;
            }

            // The retreat fails 'if & only if' there is another (dislodged) unit(s) retreating there, ...
            // ... AND at least one of these 'opponent' Retreat Orders is valid (i.e. simply ignore junk retreats)
            Collection<Order> bouncers = Orders.locateUnitsMovingToPosition(order.pos1, orders);
            for (Order bouncingRetreat : bouncers) {
                if (!bouncingRetreat.equals(order) &&
                    Orders.orderIsValid(bouncingRetreat))
                    return false;
            }

            return true;

        }

        else if (order.orderType == OrderType.DESTROY) {

            // All destroys are automatically successful
            // The implications of the destroy will be handled at the unit removal level
            pull(order);
            return true;

        } // else: below

        throw new IllegalStateException("Impossible state: @ end of `RetreatsJudge::adjudicate(...)` function: `RetreatsJudge` implements `HomogenousState::enforceStasis()`!");

    }


    protected boolean suffocated(Order retreatOrder,
                                 Collection<Province> occupiedAreas, Collection<Province> embattledAreas,
                                 Order dislodgingMove, Collection<Order> movementPhaseOrders) {

        return (generateRetreatZones(retreatOrder, occupiedAreas, embattledAreas, dislodgingMove, movementPhaseOrders)
                .isEmpty());

    }

    protected Collection<Province> generateRetreatZones(Order retreatOrder,
                                                        Collection<Province> occupiedAreas, Collection<Province> embattledAreas,
                                                        Order dislodgingMove, Collection<Order> movementPhaseOrders) {

        if (retreatOrder.orderType != OrderType.RETREAT)
            throw new IllegalArgumentException(String.format("`static %s::suffocated(...)` called on non-Retreat Order: %s",
                    this.getClass().getSimpleName(), retreatOrder));

        Province[] neighbors = Province.getAdjacencyMapCopy().get(retreatOrder.pos0);
        Collection<Province> retreatZones = new HashSet<>(List.of(neighbors));

        for (Province neighbor : neighbors) {
            if (occupiedAreas.contains(neighbor) || embattledAreas.contains(neighbor))
                retreatZones.remove(neighbor);
        }

        if (dislodgingMove == null)
            return retreatZones;

        if (movementPhaseOrders == null) {
            if (dislodgingMove.pos0.isAdjacentTo(dislodgingMove.pos1))
                retreatZones.remove(dislodgingMove.pos0);
        } else {
            if (Orders.adjacentMatchingConvoyFleetExists(dislodgingMove, movementPhaseOrders) &&
                    dislodgingMove.pos0.isAdjacentTo(dislodgingMove.pos1) &&
                    dislodgingMove.suppressH2HAdjudication) {
                // We can infer the dislodging move 'swapped' from the above conditions
                // Do nothing (ATM)
            }
            // No convoy swap, but maybe a non-adjacent Convoy
            // So, only remove the source of `dislodgingMove` if it has only traveled 1 square
            // (There is no such thing as a convoyed retreat)
            else if (dislodgingMove.pos0.isAdjacentTo(dislodgingMove.pos1)) {
                retreatZones.remove(dislodgingMove.pos0);
            }
        }

        return retreatZones;

    }


    private void pull(Order order) {

        if (DEBUG_PRINT)
            System.out.println(Constants.ANSI_RED + String.format("Retreats: VOLUNTARY   `pull()` notice for Order: \t%s", order.toString()) + Constants.ANSI_RESET);

    }

    private void piff(Order order) {

        if (DEBUG_PRINT)
            System.out.println(Constants.ANSI_RED + String.format("Retreats: INVOLUNTARY `piff()` notice for Order: \t%s", order.toString()) + Constants.ANSI_RESET);

    }


    @SuppressWarnings("PointlessBooleanExpression")
    protected Order locateDislodgingMove(Order retreatOrder, Collection<Order> movementPhaseOrders) {

        for (Order moveOrder : Orders.pruneForOrderType(OrderType.MOVE, movementPhaseOrders)) {
            if (Province.equalsIgnoreCoast(moveOrder.pos1, retreatOrder.pos0) &&
                (moveOrder.resolved && moveOrder.verdict == true)) {
                return moveOrder;
            }
        }

        return null;

    }

    @SuppressWarnings("PointlessBooleanExpression")
    protected Collection<Province> generateOccupiedAreas(Collection<Order> movementPhaseOrders) {

        Collection<Province> occupiedAreas = new HashSet<>();

        for (Order order : movementPhaseOrders) {

            if (order.dislodged)  // Dislodged by a MOVE Order --> a successful MOVE Order must be at `pos0`
                occupiedAreas.add(order.pos0);
            else if (order.orderType == OrderType.MOVE) {
                if (order.resolved && order.verdict == true)
                    occupiedAreas.add(order.pos1);
            } else if (order.orderType == OrderType.HOLD) {
                if (order.resolved && order.verdict == true)
                    occupiedAreas.add(order.pos0);
            } else if (order.orderType == OrderType.SUPPORT ||
                       order.orderType == OrderType.CONVOY) {
                Collection<Order> assailants = Orders.locateUnitsMovingToPosition(order.pos0, movementPhaseOrders);
                boolean anySuccessfulAssailant = false;
                for (Order moveOrder : assailants) {
                    if (moveOrder.resolved && moveOrder.verdict == true) {
                        anySuccessfulAssailant = true;
                        break;
                    }
                }
                if (anySuccessfulAssailant)
                    occupiedAreas.add(order.pos0);
            }

        }

        this.occupiedAreas.clear();
        this.occupiedAreas.addAll(occupiedAreas);
        return occupiedAreas;

    }

    @SuppressWarnings("PointlessBooleanExpression")
    protected Collection<Province> generateEmbattledAreas(Collection<Order> movementPhaseOrders) {

        Collection<Province> embattledAreas = new HashSet<>();

        Collection<Order> moveOrders = Orders.pruneForOrderType(OrderType.MOVE, movementPhaseOrders);
        for (Order order : moveOrders) {
            if ((order.verdict == false && order.resolved) &&
                 super.pathSuccessful(order, true, movementPhaseOrders)) {
                // Check for / distinguish between "squashed" Head-to-Head attacks vs. bounces in provinces
                Order headToHead = Orders.locateHeadToHead(order, moveOrders);
                if (headToHead == null)
                    embattledAreas.add(order.pos1);
            }
        }

        this.embattledAreas.clear();
        this.embattledAreas.addAll(embattledAreas);
        return embattledAreas;

    }


    @Override
    public void enforceStasis() throws IllegalStateException {

        for (Order order : orders) {
            if (!order.dislodged || (order.orderType != OrderType.RETREAT && order.orderType != OrderType.DESTROY))
                throw new IllegalStateException(String.format(
                        "%s\n`%s:enforceStasis()`: OrderType is %s but only %s are allowed",
                        order.toString(),
                        this.getClass().getSimpleName(), order.orderType,
                        OrderType.RETREAT.name() + " and " + OrderType.DESTROY.name())
                );
        }

    }

}
