package agents.simulation;

import adjudication.Justice;
import agents.generation.ActionCandidate;
import agents.generation.ActionValidationError;
import agents.generation.ActionValidator;
import agents.generation.CatalogActionValidator;
import contracts.OrderForm;
import domain.Nation;
import domain.Province;
import game.*;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;

import java.util.*;

/**
 * Resolves one joint action in a separate game.
 *
 * <p>Every nation supplies a candidate, including an empty action where
 * appropriate. All candidates are checked against the same starting state.
 * Candidate priors and sources do not affect resolution.</p>
 *
 * <p>Game handles phase advancement and skipped retreats. Each call creates
 * fresh processors. Movement inputs are ordered by current province name.
 * Winter builds retain the processor's randomly generated unit identities.</p>
 */
public final class Simulator {

    private final MovementProcessor.Policy policy;
    private final int trialCount;
    private final long shuffleSeed;

    public Simulator() {
        this(MovementProcessor.Policy.SZYKMAN_JUSTICE,
                Justice.NUM_TRIALS_DEFAULT, Justice.SHUFFLE_SEED_DEFAULT);
    }

    /**
     * Uses the supplied movement settings and standard retreat and adjustment
     * processors. GameState does not contain processor configuration.
     */
    public Simulator(MovementProcessor.Policy policy, int trialCount, long shuffleSeed) {
        this.policy = Objects.requireNonNull(policy, "policy");
        if (trialCount < 1)
            throw new IllegalArgumentException("trialCount must be at least 1");
        this.trialCount = trialCount;
        this.shuffleSeed = shuffleSeed;
    }

    /**
     * Returns the next position without modifying the supplied state or actions.
     * Invalid joint submissions throw IllegalArgumentException before resolution.
     * Null arguments, keys, or candidates throw NullPointerException.
     */
    public GameState advance(GameState state, Map<Nation, ActionCandidate> actions) {

        Objects.requireNonNull(state, "state");
        Map<Nation, ActionCandidate> submitted = Map.copyOf(
                Objects.requireNonNull(actions, "actions"));
        if (!submitted.keySet().equals(EnumSet.allOf(Nation.class)))
            throw new IllegalArgumentException("A joint action requires one candidate for every nation");

        ActionValidator         validator = new CatalogActionValidator();
        List<Order>             movementOrders = new ArrayList<>();
        List<RetreatOrder>      retreatOrders = new ArrayList<>();
        List<AdjustmentOrder>   adjustmentOrders = new ArrayList<>();

        for (Nation nation : Nation.values()) {
            ActionCandidate candidate = submitted.get(nation);
            if (candidate.power() != nation)
                throw new IllegalArgumentException("Candidate power does not match map key: " + nation);

            List<ActionValidationError> errors = validator.validate(state, candidate);
            if (!errors.isEmpty())
                throw new IllegalArgumentException("Invalid action for " + nation + ": " + errors);

            for (OrderForm order : candidate.orders()) {
                switch (order) {
                    case Order              movement    -> movementOrders.add(movement);
                    case RetreatOrder       retreat     -> retreatOrders.add(retreat);
                    case AdjustmentOrder    adjustment  -> adjustmentOrders.add(adjustment);
                    case null, default                  -> throw new IllegalStateException("Unsupported validated order: " + order);
                }
            }
        }

        Game game = Game.fromState(
                orderedMovementState(state),
                new MovementProcessor(policy, trialCount, shuffleSeed),
                new RetreatProcessor(),
                new AdjustmentProcessor());

        switch (state.moment().gamePhase()) {
            case SPRING_MOVEMENT, FALL_MOVEMENT     -> game.resolveMovement(movementOrders);
            case SPRING_RETREAT, FALL_RETREAT       -> game.resolveRetreats(retreatOrders);
            case WINTER_ADJUSTMENT                  -> game.resolveAdjustments(adjustmentOrders);
        }

        return game.snapshot();

    }

    private static GameState orderedMovementState(GameState state) {
        if (!(state instanceof GameState_Movement movement))
            return state;

        // MovementProcessor normalizes orders in board iteration order.
        Map<UnitId, Province> locations = movement.board().locations();
        List<UnitId> units = new ArrayList<>(locations.keySet());
        units.sort(Comparator.comparing(unit -> locations.get(unit).name()));
        Map<UnitId, Province> ordered = new LinkedHashMap<>();
        for (UnitId unit : units)
            ordered.put(unit, locations.get(unit));

        return new GameState_Movement(movement.moment(),
                new BoardState(ordered, movement.board().owners()));
    }

}