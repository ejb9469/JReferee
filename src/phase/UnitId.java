package phase;

import domain.Nation;
import domain.Province;
import domain.UnitType;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity for a unit across phases and turns.
 *
 * <p>Origin records where the unit was created. It is not its current board
 * location; current locations live in PhaseResult.finalLocations().</p>
 */
public record UnitId(
        UUID value,
        Nation owner,
        UnitType unitType,
        Province origin
) {

    /**
     * "Real" (non-deprecated) constructor. Includes UUID.
     */
    public UnitId {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(unitType, "unitType");
        Objects.requireNonNull(origin, "origin");
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
     * Creates a distinct identity for a winter-built unit.
     */
    public static UnitId newlyBuilt(
            Nation owner,
            UnitType unitType,
            Province buildLocation
    ) {
        return new UnitId(
                UUID.randomUUID(),
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