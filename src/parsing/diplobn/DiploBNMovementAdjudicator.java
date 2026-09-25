package parsing.diplobn;

import adjudication.Adjudicator;
import adjudication.Judge;
import contracts.OrderTranslator;
import game.BoardState;
import phase.Order;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;


/**
 * Independently adjudicates movement submissions imported from one DiploBN
 * source phase.
 *
 * <p>This adjudicator does not advance a {@code game.Game}. It translates the
 * imported movement submissions into fresh mutable adjudication work orders,
 * resolves them against the source board when {@link #judge()} is called, and
 * exposes the resolved work-order collection through {@link #getOrders()}.</p>
 */
public final class DiploBNMovementAdjudicator
        implements Adjudicator {


    private final BoardState board;

    private final List<DiploBNOrder> sourceOrders;

    private final OrderTranslator<
            DiploBNOrder,
            adjudication.Order> orderTranslator;

    private List<adjudication.Order> orders;


    /**
     * Creates an adjudicator for all movement submissions in one imported
     * DiploBN source phase.
     */
    public DiploBNMovementAdjudicator(
            DiploBNPhase phase
    ) {
        this(
                Objects.requireNonNull(phase, "phase").board(),
                sourceOrdersOf(phase),
                new DiploBNAdjudicationOrderTranslator());
    }

    /**
     * Creates an adjudicator for one explicit source board and decoded
     * DiploBN movement-order collection.
     */
    public DiploBNMovementAdjudicator(
            BoardState board,
            Collection<DiploBNOrder> sourceOrders
    ) {
        this(
                board,
                sourceOrders,
                new DiploBNAdjudicationOrderTranslator());
    }

    /**
     * Creates an adjudicator with an explicit translation policy.
     *
     * <p>The supplied translator must create a new mutable
     * {@link adjudication.Order} for every source order. Reusing mutable work
     * orders across adjudicator instances is not supported.</p>
     */
    public DiploBNMovementAdjudicator(
            BoardState board,
            Collection<DiploBNOrder> sourceOrders,
            OrderTranslator<
                    DiploBNOrder,
                    adjudication.Order> orderTranslator
    ) {

        this.board = Objects.requireNonNull(board, "board");

        this.sourceOrders = List.copyOf(
                Objects.requireNonNull(
                        sourceOrders,
                        "sourceOrders"));

        this.orderTranslator = Objects.requireNonNull(
                orderTranslator,
                "orderTranslator");

        this.orders = List.of();

    }


    /**
     * Translates each submitted source order into a fresh mutable work order
     * and adjudicates the translated collection.
     *
     * <p>This method can be invoked more than once. Each invocation translates
     * fresh work orders, preventing mutable resolution metadata from one run
     * from affecting a later run.</p>
     */
    @Override
    public void judge() {

        List<adjudication.Order> workOrders =
                new ArrayList<>(sourceOrders.size());

        for (DiploBNOrder sourceOrder : sourceOrders) {
            workOrders.add(
                    orderTranslator.translate(
                            sourceOrder,
                            board));
        }

        Judge judge = new Judge(workOrders);

        judge.judge();

        this.orders = List.copyOf(judge.getOrders());

    }

    /**
     * Returns the most recently adjudicated DiploBN movement orders.
     *
     * <p>Before {@link #judge()} is first invoked, this method returns an
     * empty immutable list.</p>
     */
    @Override
    public Collection<adjudication.Order> getOrders() {
        return orders;
    }

    /**
     * Returns the immutable decoded source submissions supplied to this
     * adjudicator.
     */
    public List<DiploBNOrder> sourceOrders() {
        return sourceOrders;
    }

    /**
     * Returns the imported board used as the starting position for every
     * independent adjudication run.
     */
    public BoardState board() {
        return board;
    }


    private static List<DiploBNOrder> sourceOrdersOf(
            DiploBNPhase phase
    ) {

        List<DiploBNOrder> sourceOrders =
                new ArrayList<>(
                        phase.movementOrders().size());

        for (Order order : phase.movementOrders()) {
            sourceOrders.add(
                    DiploBNOrder.from(order));
        }

        return sourceOrders;

    }

}