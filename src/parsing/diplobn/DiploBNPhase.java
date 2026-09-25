package parsing.diplobn;

import game.BoardState;
import game.GamePhase;
import phase.Order;
import phase.adjustments.AdjustmentOrder;
import phase.retreats.RetreatOrder;

import java.util.List;
import java.util.Objects;


/**
 * One DiploBN game snapshot converted into JReferee's board and phase-order
 * vocabulary.
 *
 * <p>The board is the source snapshot exactly as supplied by DiploBN. The
 * source status determines whether a Spring/Fall snapshot is a movement phase
 * or its corresponding retreat phase.</p>
 */
public final class DiploBNPhase {


    private final int sourcePhase;
    private final String sourceStatus;
    private final GamePhase gamePhase;
    private final BoardState board;

    private final List<DiploBNOrder> sourceMovementOrders;
    private final List<Order> movementOrders;
    private final List<RetreatOrder> retreatOrders;
    private final List<AdjustmentOrder> adjustmentOrders;

    private final List<DiploBNOrderResolution> resolutions;
    private final List<DiploBNOrderResolution> retreatResolutions;


    DiploBNPhase(
            int sourcePhase,
            String sourceStatus,
            GamePhase gamePhase,
            BoardState board,
            List<DiploBNOrder> sourceMovementOrders,
            List<Order> movementOrders,
            List<RetreatOrder> retreatOrders,
            List<AdjustmentOrder> adjustmentOrders,
            List<DiploBNOrderResolution> resolutions,
            List<DiploBNOrderResolution> retreatResolutions
    ) {

        this.sourcePhase = sourcePhase;
        this.sourceStatus = Objects.requireNonNull(
                sourceStatus,
                "sourceStatus");

        this.gamePhase = Objects.requireNonNull(gamePhase, "gamePhase");
        this.board = Objects.requireNonNull(board, "board");

        this.sourceMovementOrders = List.copyOf(
                Objects.requireNonNull(
                        sourceMovementOrders,
                        "sourceMovementOrders"));

        this.movementOrders = List.copyOf(
                Objects.requireNonNull(
                        movementOrders,
                        "movementOrders"));

        if (this.sourceMovementOrders.size()
                != this.movementOrders.size())
            throw new IllegalArgumentException(
                    "Source and translated movement order counts differ");

        this.retreatOrders = List.copyOf(
                Objects.requireNonNull(
                        retreatOrders,
                        "retreatOrders"));

        this.adjustmentOrders = List.copyOf(
                Objects.requireNonNull(
                        adjustmentOrders,
                        "adjustmentOrders"));

        this.resolutions = List.copyOf(
                Objects.requireNonNull(
                        resolutions,
                        "resolutions"));

        this.retreatResolutions = List.copyOf(
                Objects.requireNonNull(
                        retreatResolutions,
                        "retreatResolutions"));

    }


    /**
     * The original five-digit DiploBN phase code.
     */
    public int sourcePhase() {
        return sourcePhase;
    }

    /**
     * The original DiploBN phase status.
     */
    public String sourceStatus() {
        return sourceStatus;
    }

    /**
     * The phase inferred for JReferee processing.
     */
    public GamePhase gamePhase() {
        return gamePhase;
    }

    /**
     * Board snapshot represented by this source phase.
     */
    public BoardState board() {
        return board;
    }

    /**
     * Decoded source movement submissions. Empty for retreat and winter
     * snapshots.
     */
    public List<DiploBNOrder> sourceMovementOrders() {
        return sourceMovementOrders;
    }

    /**
     * Submitted movement orders translated into JReferee's immutable phase
     * representation. Empty for retreat and winter snapshots.
     */
    public List<Order> movementOrders() {
        return movementOrders;
    }

    /**
     * Submitted retreat orders. Empty when DiploBN supplied none.
     */
    public List<RetreatOrder> retreatOrders() {
        return retreatOrders;
    }

    /**
     * Submitted winter adjustment orders. Empty outside winter.
     */
    public List<AdjustmentOrder> adjustmentOrders() {
        return adjustmentOrders;
    }

    /**
     * Historical results attached to Orders.
     */
    public List<DiploBNOrderResolution> resolutions() {
        return resolutions;
    }

    /**
     * Historical results attached to RetreatOrders.
     */
    public List<DiploBNOrderResolution> retreatResolutions() {
        return retreatResolutions;
    }

}