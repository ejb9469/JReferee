package phase.retreats;

import phase.PhaseInput;
import phase.movement.MovementResult;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable input for one retreat phase.
 */
public record RetreatInput(
        MovementResult movementResult,
        List<RetreatOrder> submittedOrders
) implements PhaseInput {

    public RetreatInput {
        Objects.requireNonNull(movementResult, "movementResult");
        submittedOrders = List.copyOf(
                Objects.requireNonNull(
                        submittedOrders,
                        "submittedOrders"));
    }

    public RetreatInput(
            MovementResult movementResult,
            Collection<RetreatOrder> submittedOrders
    ) {
        this(
                movementResult,
                List.copyOf(
                        Objects.requireNonNull(
                                submittedOrders,
                                "submittedOrders"))
        );
    }

}