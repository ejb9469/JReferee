package phase;

/**
 * Immutable input accepted by a game-phase Processor.
 *
 * <p>This is intentionally a marker interface. Each phase has different
 * required input: movement needs active units and movement orders, retreats
 * need a MovementResult and retreat orders, and adjustments need a
 * RetreatResult, supply-center ownership, and adjustment orders.</p>
 */
public interface PhaseInput {   }