package agents.generation;

import org.jetbrains.annotations.Nullable;
import phase.UnitId;

import java.util.Objects;

/**
 * One submission error found while checking a complete action.
 *
 * <p>An order index is zero-based, or -1 (sentinel) for a missing order or adjustment.
 * Unit is null for errors without an existing issuing unit.</p>
 */
public record ActionValidationError(
        Code code,
        @Nullable UnitId unit,
        int orderIndex
) {

    public ActionValidationError {
        Objects.requireNonNull(code, "code");

        if (code == Code.UNKNOWN_UNIT || code == Code.DUPLICATE_UNIT || code == Code.MISSING_ORDER)
            Objects.requireNonNull(unit, "unit");

        boolean missing = code == Code.MISSING_ORDER || code == Code.MISSING_ADJUSTMENTS;
        if (missing ? orderIndex != -1 : orderIndex < 0)
            throw new IllegalArgumentException(
                    "Missing orders require index -1; submitted orders require a nonnegative index");
    }

    public enum Code {
        WRONG_ORDER_PHASE,
        UNKNOWN_UNIT,
        DUPLICATE_UNIT,
        ORDER_NOT_IN_CATALOG,
        MISSING_ORDER,
        DUPLICATE_BUILD_SITE,
        TOO_MANY_ADJUSTMENTS,
        MISSING_ADJUSTMENTS
    }

}