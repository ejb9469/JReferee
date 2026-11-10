package phase;

import contracts.OrderForm;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * Stable identity for a unit across phases and turns.
 *
 * <p>Origin records where the unit was created. It is not its current board
 * location; current locations live in `PhaseResult.finalLocations()`.</p>
 */
public record UnitId(
        UUID value,
        Nation owner,
        UnitType unitType,
        Province origin
) implements OrderForm {

    /**
     * "Real" (non-deprecated) constructor. Includes UUID.
     */
    public UnitId {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(unitType, "unitType");
        Objects.requireNonNull(origin, "origin");
    }


    @Override
    public Nation owner() {
        return this.owner;
    }

    @Override
    public Province origin() {
        return this.origin;
    }

    @Override
    public UnitType unitType() {
        return this.unitType;
    }

    @Override
    public @Nullable OrderType orderType() {
        return null;
    }

    @Override
    public @Nullable Province target() {
        return null;
    }

    @Override
    public @Nullable Province auxiliaryTarget() {
        return null;
    }


    /**
     * Compatibility constructor, DEPRECATED.
     *
     * <p>The generated identity is deterministic so existing tests that
     * construct the same original unit descriptor continue to compare equal.</p>
     */
    @Deprecated
    public UnitId(
            Nation owner,
            UnitType unitType,
            Province origin
    ) {
        this(
                stableInitialValue(owner, unitType, origin),
                owner,
                unitType,
                origin);
    }

    /**
     * Creates a repeatable identity for a unit built in the given winter `year`.
     * Rebuilding at the same site in another year creates a different identity.
     */
    public static UnitId newlyBuilt(
            int year,  // origin year
            Nation owner,
            UnitType unitType,
            Province buildLocation
    ) {
        if (year < domain.Constants.STARTING_YEAR)  // validity check on `year` - may later be removed
            throw new IllegalArgumentException("Build year must be at least " + domain.Constants.STARTING_YEAR);
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(unitType, "unitType");
        Objects.requireNonNull(buildLocation, "buildLocation");
        String key = "WINTER\u001F" + year + "\u001F" + owner.name()  // unique "hash"/token used to generate UUID
                + "\u001F" + unitType.name() + "\u001F" + buildLocation.name();
        return new UnitId(
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),  // previously UUID.randomUUID() ; now it is deterministic
                owner,
                unitType,
                buildLocation);
    }

    /**
     * Constructs a UUID where one is not provided.
     */
    private static UUID stableInitialValue(
            Nation owner,
            UnitType unitType,
            Province origin
    ) {

        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(unitType, "unitType");
        Objects.requireNonNull(origin, "origin");

        String key = owner.name()
                + "\u001F"
                + unitType.name()
                + "\u001F"
                + origin.name();

        return UUID.nameUUIDFromBytes(
                key.getBytes(StandardCharsets.UTF_8));

    }

}