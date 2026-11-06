package agents.play;

import agents.generation.ActionCandidate;
import agents.generation.CatalogActionValidator;
import contracts.OrderForm;
import domain.Nation;
import game.*;
import game.record.GameRecord;
import game.record.GameRecordBuilder;
import phase.Order;
import phase.adjustments.AdjustmentOrder;
import phase.retreats.RetreatOrder;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * Runs and records a local non-press game with the standard 18-center solo rule.
 *
 * <p>Every living nation chooses from the same phase-start snapshot. All
 * submissions are validated before the record builder resolves the real phase
 * once. Speculative peeks and rollouts never use this game's record builder.</p>
 *
 * <p>Seeds are assigned in living-nation enum order. A processor or progress
 * callback failure aborts the run; the supplied game may already have advanced.
 * There is no automatic draw negotiation or recovery checkpoint.</p>
 */
public final class GameRunner {

    private GameRunner() { }

    public static Result play(Game game, Map<Nation, Agent> agents, UUID id, String rulesetId,
                              int lastYear, long seed, Consumer<GameState> progress) {
        Objects.requireNonNull(game, "game");
        agents = Map.copyOf(Objects.requireNonNull(agents, "agents"));
        Objects.requireNonNull(progress, "progress");
        if (!agents.keySet().equals(EnumSet.allOf(Nation.class)))
            throw new IllegalArgumentException("An agent must be assigned to every nation");
        if (game.getSnapshot() != null)
            throw new IllegalStateException("A recorded game must not have a saved checkpoint");
        if (lastYear < game.year())
            throw new IllegalArgumentException("lastYear must not precede the starting year");

        GameRecordBuilder recorder = new GameRecordBuilder(id, rulesetId, game);
        Random random = new Random(seed);
        List<Map<Nation, Long>> decisionSeeds = new ArrayList<>();
        CatalogActionValidator validator = new CatalogActionValidator();

        while (true) {
            GameState root = game.snapshot();
            if (winner(root.board()).isPresent())
                return new Result(recorder.build(), root, EndReason.SOLO, decisionSeeds);
            if (root instanceof GameState_Adjustments && game.year() == lastYear)
                return new Result(recorder.build(), root, EndReason.CUTOFF, decisionSeeds);

            Map<Nation, Long> phaseSeeds = new EnumMap<>(Nation.class);
            List<Order> movement = new ArrayList<>();
            List<RetreatOrder> retreats = new ArrayList<>();
            List<AdjustmentOrder> adjustments = new ArrayList<>();
            for (Nation nation : root.aliveNations()) {
                long decisionSeed = random.nextLong();
                phaseSeeds.put(nation, decisionSeed);
                ActionCandidate candidate = Objects.requireNonNull(
                        agents.get(nation).choose(root, nation, decisionSeed), "agent action");
                if (candidate.power() != nation)
                    throw new IllegalStateException("Agent submitted another nation's action: " + nation);
                var errors = validator.validate(root, candidate);
                if (!errors.isEmpty())
                    throw new IllegalStateException("Invalid action from " + nation + ": " + errors);
                for (OrderForm order : candidate.orders()) {
                    switch (order) {
                        case Order submitted -> movement.add(submitted);
                        case RetreatOrder submitted -> retreats.add(submitted);
                        case AdjustmentOrder submitted -> adjustments.add(submitted);
                        default -> throw new IllegalStateException("Unsupported submitted order: " + order);
                    }
                }
            }

            Moment resolvedAt = new Moment(game.year(), game.phase(), Instant.now());
            switch (game.phase()) {
                case SPRING_MOVEMENT, FALL_MOVEMENT -> recorder.resolveMovement(movement, resolvedAt);
                case SPRING_RETREAT, FALL_RETREAT -> recorder.resolveRetreats(retreats, resolvedAt);
                case WINTER_ADJUSTMENT -> recorder.resolveAdjustments(adjustments, resolvedAt);
            }
            decisionSeeds.add(Map.copyOf(phaseSeeds));
            GameState next = game.snapshot();
            if (next.moment().compareTo(root.moment()) <= 0 || next.moment().year() > lastYear)
                throw new IllegalStateException("Recorded play advanced beyond its year-end boundary");
            progress.accept(next);
        }
    }

    public static Optional<Nation> winner(BoardState board) {
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);
        for (Nation owner : board.owners().values())
            counts.merge(owner, 1, Integer::sum);
        return counts.entrySet().stream().filter(entry -> entry.getValue() >= 18)
                .map(Map.Entry::getKey).findFirst();
    }

    public enum EndReason { SOLO, CUTOFF }

    public record Result(GameRecord record, GameState finalState, EndReason endReason,
                         List<Map<Nation, Long>> decisionSeeds) {
        public Result {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(finalState, "finalState");
            Objects.requireNonNull(endReason, "endReason");
            decisionSeeds = decisionSeeds.stream().map(Map::copyOf).toList();
            if (decisionSeeds.size() != record.resolvedPhases().size()
                    || !record.latestBoard().equals(finalState.board()))
                throw new IllegalArgumentException("Recorded phases, seeds, and final board must agree");
        }
    }

}
