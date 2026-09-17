package phase.movement;

import phase.Order;
import phase.PhaseInput;
import phase.UnitId;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable input for one Spring or Fall movement phase.
 */
public record MovementInput(
        List<UnitId> activeUnits,
        List<Order> submittedOrders
) implements PhaseInput {

    public MovementInput {
        activeUnits = List.copyOf(
                Objects.requireNonNull(activeUnits, "activeUnits"));
        submittedOrders = List.copyOf(
                Objects.requireNonNull(
                        submittedOrders,
                        "submittedOrders"));
        if (new LinkedHashSet<>(activeUnits).size() != activeUnits.size())
            throw new IllegalArgumentException(
                    "activeUnits contains duplicate UnitId values");
    }

    public MovementInput(
            Collection<UnitId> activeUnits,
            Collection<Order> submittedOrders)
    {
        this(
                List.copyOf(Objects.requireNonNull(
                                activeUnits,
                                "activeUnits")),
                List.copyOf(Objects.requireNonNull(
                                submittedOrders,
                                "submittedOrders"))
        );
    }

}