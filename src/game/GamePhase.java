package game;

/**
 * The five adjudication phases in one Diplomacy game year.
 *
 * <p>Spring and Fall movement may be followed by a retreat phase when one or
 * more units are dislodged. Winter adjustments always advance the game to
 * Spring movement of the following year.</p>
 */
public enum GamePhase {

    SPRING_MOVEMENT,
    SPRING_RETREAT,
    FALL_MOVEMENT,
    FALL_RETREAT,
    WINTER_ADJUSTMENT;

    /**
     * Returns whether this phase accepts movement orders and is resolved by
     * {@code MovementProcessor}.
     */
    public boolean isMovement() {
        return this == SPRING_MOVEMENT
                || this == FALL_MOVEMENT;
    }

    /**
     * Returns whether this phase accepts retreat orders and is resolved by
     * {@code RetreatProcessor}.
     */
    public boolean isRetreat() {
        return this == SPRING_RETREAT
                || this == FALL_RETREAT;
    }

    /**
     * Returns whether this phase accepts build, disband, and waive orders and
     * is resolved by {@code AdjustmentProcessor}.
     */
    public boolean isAdjustment() {
        return this == WINTER_ADJUSTMENT;
    }


}