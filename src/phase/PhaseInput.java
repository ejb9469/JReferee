package phase;

/**
 * Immutable input accepted by a game-phase Processor.
 *
 * <p>This is intentionally a marker interface. Each phase has distinct input:
 * movement needs a starting board and movement orders; retreats need a
 * MovementResult and retreat orders; adjustments need a RetreatResult,
 * supply-center ownership, and adjustment orders.</p>
 */
public interface PhaseInput {   }