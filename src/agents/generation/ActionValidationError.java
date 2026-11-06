package agents.generation;

import org.jetbrains.annotations.Nullable;
import phase.UnitId;

import java.util.Objects;

/**
 * One submission error found while checking a complete action.
 *
 * <p>An order index is zero-based, or -1 for a missing order.
 * Unit is null when the submitted form has no supported unit identity.</p>
 */
public record ActionValidationError(
        Code code,
        @Nullable UnitId unit,
        int orderIndex
) {

    public ActionValidationError {
        Objects.requireNonNull(code, "code");

        if (code != Code.WRONG_ORDER_PHASE)
            Objects.requireNonNull(unit, "unit");

        if (code == Code.MISSING_ORDER ? orderIndex != -1 : orderIndex < 0)
            throw new IllegalArgumentException(
                    "Missing orders require index -1; submitted orders require a nonnegative index");
    }

    public enum Code {
        WRONG_ORDER_PHASE,
        UNKNOWN_UNIT,
        DUPLICATE_UNIT,
        ORDER_NOT_IN_CATALOG,
        MISSING_ORDER
    }

}