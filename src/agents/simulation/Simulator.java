package agents.simulation;

import agents.generation.ActionCandidate;
import agents.generation.ActionValidationError;
import agents.generation.ActionValidator;
import agents.generation.CatalogActionValidator;
import contracts.OrderForm;
import domain.Nation;
import game.Game;
import game.GameState;
import phase.Order;
import phase.adjustments.AdjustmentOrder;
import phase.retreats.RetreatOrder;

import java.util.*;

/**
 * Resolves joint actions, with an optional "peek" that restores the game.
 *
 * <p>Each peek temporarily advances a supplied game, captures the resulting
 * position, and restores the game. Every living nation supplies one candidate,
 * including an empty action where appropriate. advance() keeps the resulting
 * position and leaves checkpoint ownership to its caller. Eliminated nations may be
 * omitted or supply empty candidates. Candidate priors and sources do not
 * affect resolution.</p>
 *
 * <p>Processor settings and board iteration order come from the supplied game.
 * Use a separate simulation game; concurrent and nested peeks on the same
 * game are unsupported. Custom processor state and external game records
 * are outside Game's rollback.</p>
 *
 * @author Evan B.
 */
public class Simulator {

    /**
     * Takes a "peek" at the next position without keeping the game's advance.
     *
     * <p>All candidates are validated before a checkpoint is taken. The game
     * then resolves the current phase, including any skipped retreats. Its
     * starting position and histories are restored before returning, or before
     * propagating a resolution failure. The returned immutable position remains
     * available after restoration.</p>
     *
     * <p>An existing checkpoint is left untouched and prevents the peek.</p>
     *
     * @param   game simulation game with no saved checkpoint
     * @param   actions one candidate for every nation in {@link GameState#aliveNations()}
     * @return  the resulting immutable position
     * @throws  NullPointerException if an argument, map key, or candidate is null
     * @throws  IllegalArgumentException if a living nation is missing, a candidate's
     *         power differs from its map key, or a candidate is invalid
     * @throws  IllegalStateException if the game already has a saved checkpoint
     *         or its state cannot be resolved or restored
     */
    public GameState peek(Game game, Map<Nation, ActionCandidate> actions) {

        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(actions, "actions");

        Map<Nation, ActionCandidate> submitted = Map.copyOf(actions);

        if (game.getSnapshot() != null)
            throw new IllegalStateException("Simulation requires a game without a saved checkpoint");
        validate(game.snapshot(), submitted);

        game.takeSnapshot();
        try {
            return resolveValidated(game, submitted);
        } finally {
            game.restoreFromSnapshot();
        }

    }

    /**
     * Advances one adjudication phase and keeps the resulting position.
     *
     * <p>Validation finishes before any orders are applied. No checkpoint is
     * taken or restored; an existing checkpoint remains available to the caller.
     * A rollout can resolve several phases under one checkpoint and restore
     * the whole trajectory in finally.</p>
     *
     * <p>A processor failure may leave a partially advanced game. The caller
     * owns recovery. Use peek() for a single self-restoring trial.</p>
     *
     * @param   game isolated simulation game
     * @param   actions one candidate for every living nation
     * @return  the resulting immutable position
     * @throws  NullPointerException if an argument, map key, or candidate is null
     * @throws  IllegalArgumentException if a joint action is incomplete or invalid
     */
    public GameState advance(Game game, Map<Nation, ActionCandidate> actions) {
        Objects.requireNonNull(game, "game");
        Map<Nation, ActionCandidate> submitted = Map.copyOf(Objects.requireNonNull(actions, "actions"));
        validate(game.snapshot(), submitted);
        return resolveValidated(game, submitted);
    }

    private static void validate(GameState state, Map<Nation, ActionCandidate> submitted) {
        Set<Nation> missing = EnumSet.noneOf(Nation.class);
        missing.addAll(state.aliveNations());
        missing.removeAll(submitted.keySet());
        if (!missing.isEmpty())
            throw new IllegalArgumentException("Missing candidates for living nations: " + missing);

        ActionValidator validator = new CatalogActionValidator();
        for (Nation nation : Nation.values()) {
            ActionCandidate candidate = submitted.get(nation);
            if (candidate == null)
                continue;
            if (candidate.power() != nation)
                throw new IllegalArgumentException("Candidate power does not match map key: " + nation);

            List<ActionValidationError> errors = validator.validate(state, candidate);
            if (!errors.isEmpty())
                throw new IllegalArgumentException("Invalid action for " + nation + ": " + errors);
        }
    }

    private static GameState resolveValidated(Game game, Map<Nation, ActionCandidate> submitted) {
        List<Order>             movementOrders = new ArrayList<>();
        List<RetreatOrder>      retreatOrders = new ArrayList<>();
        List<AdjustmentOrder>   adjustmentOrders = new ArrayList<>();

        for (Nation nation : Nation.values()) {
            ActionCandidate candidate = submitted.get(nation);
            if (candidate == null)
                continue;
            for (OrderForm order : candidate.orders()) {
                switch (order) {
                    case Order              movement    -> movementOrders.add(movement);
                    case RetreatOrder       retreat     -> retreatOrders.add(retreat);
                    case AdjustmentOrder    adjustment  -> adjustmentOrders.add(adjustment);
                    case null, default                  -> throw new IllegalStateException("Unsupported validated order: " + order);
                }
            }
        }

        switch (game.phase()) {
            case SPRING_MOVEMENT, FALL_MOVEMENT -> game.resolveMovement(movementOrders);
            case SPRING_RETREAT, FALL_RETREAT   -> game.resolveRetreats(retreatOrders);
            case WINTER_ADJUSTMENT              -> game.resolveAdjustments(adjustmentOrders);
        }
        return game.snapshot();

    }

}