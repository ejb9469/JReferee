package agents.execution;

import agents.evaluation.ScoringEvaluator;
import agents.generation.ActionCandidate;
import agents.simulation.Simulator;
import domain.Constants;
import domain.Nation;
import game.*;
import game.record.GameRecord;
import game.record.GameRecordBuilder;
import game.record.ResolvedPhaseRecord;
import misc.scoring.ScoringSystem;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * Runs a bounded, sequential, non-press game and records every resolved phase.
 *
 * <p>Each living nation searches an independent copy of the same starting
 * position. Submitted actions are collected before the real game advances.
 * The supplied initial state is never modified.</p>
 *
 * <p>A solo takes precedence over the year limit. The limit ends the game
 * after the final year's complete Fall turn, before winter builds. This is a
 * scored cutoff; no draw vote is inferred. Agreed draws are not implemented.</p>
 *
 * <p>Search, continuation, and action-selection seeds are derived separately
 * from the game seed, phase, and nation. Recorded timestamps are deterministic
 * phase markers, not wall-clock times. The caller supplies the game ID.</p>
 */
public final class GameRunner {

    public enum EndReason { SOLO, YEAR_LIMIT }

    /**
     * Complete settings for the built-in agents and all adjudication games.
     * Every nation has settings, including nations eliminated in the initial state.
     */
    public record Options(int lastYear, long seed, ScoringSystem scoring,
                          MovementProcessor.Policy movementPolicy, int adjudicationTrials,
                          long adjudicationSeed, Map<Nation, AgentSettings> agents) {
        public Options {
            Objects.requireNonNull(scoring, "scoring");
            Objects.requireNonNull(movementPolicy, "movementPolicy");
            if (lastYear < Constants.STARTING_YEAR || lastYear == Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid final year");
            if (adjudicationTrials < 1)
                throw new IllegalArgumentException("Adjudication trials must be at least 1");
            Map<Nation, AgentSettings> checked = Map.copyOf(Objects.requireNonNull(agents, "agents"));
            if (!checked.keySet().equals(EnumSet.allOf(Nation.class)))
                throw new IllegalArgumentException("Settings are required for all seven nations");
            agents = Collections.unmodifiableMap(new EnumMap<>(checked));
        }

        /**
         * Supplies fresh processors with the same rules for real and speculative play.
         */
        public Game createGame(GameState state) {
            return Game.fromState(state,
                    new MovementProcessor(movementPolicy, adjudicationTrials, adjudicationSeed),
                    new RetreatProcessor(), new AdjustmentProcessor());
        }

        public String rulesetId() {
            return "jreferee-agent-v1:" + movementPolicy + ":" + adjudicationTrials + ":" + adjudicationSeed;
        }
    }

    /**
     * Immutable game history, configuration, final position, and stopping reason.
     * Scores use the configured tournament system's fixed-scale rewards.
     */
    public record Result(Options options, GameState initial, GameState state,
                         GameRecord record, EndReason reason) {
        public Result {
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(initial, "initial");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(reason, "reason");
            if (!record.initialMoment().equals(initial.moment())
                    || !record.initialBoard().equals(initial.board())
                    || !record.latestBoard().equals(state.board())
                    || !record.rulesetId().equals(options.rulesetId()))
                throw new IllegalArgumentException("Record and game positions disagree");
            if (reason == EndReason.SOLO ? solo(state) == null
                    : solo(state) != null || !(state instanceof GameState_Adjustments)
                    || state.moment().year() != options.lastYear())
                throw new IllegalArgumentException("Stopping reason does not match the final position");
        }

        public Map<Nation, Double> scores() {
            return new ScoringEvaluator(options.scoring()).evaluate(state);
        }

        public Optional<Nation> winner() {
            return Optional.ofNullable(solo(state));
        }
    }

    public Result run(UUID id, GameState initial, Options options) {
        return run(id, initial, options, phase -> { });
    }

    /**
     * Calls the observer once after each successfully recorded phase.
     * Failures propagate; no partial game is reported as a completed result.
     */
    public Result run(UUID id, GameState initial, Options options,
                      Consumer<ResolvedPhaseRecord> observer) {
        return run(id, initial, options, observer, decision -> { });
    }

    /**
     * Reports each sampled decision before the real phase resolves.
     * Decisions retain immutable root diagnostics and are not added to the
     * game record. Observing them performs no additional search or sampling.
     * Exceptions from either observer propagate to the caller.
     */
    public Result run(UUID id, GameState initial, Options options,
                      Consumer<ResolvedPhaseRecord> observer, Consumer<AgentDecision> decisionObserver) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(decisionObserver, "decisionObserver");
        if (initial.moment().year() > options.lastYear())
            throw new IllegalArgumentException("Initial position is beyond the final year");
        new ScoringEvaluator(options.scoring()).evaluate(initial);

        Game game = options.createGame(initial);
        GameRecordBuilder archive = new GameRecordBuilder(id, options.rulesetId(), game);
        Simulator simulator = new Simulator();
        GameState root = initial;
        while (true) {
            EndReason reason = solo(root) != null ? EndReason.SOLO
                    : root instanceof GameState_Adjustments && root.moment().year() == options.lastYear()
                    ? EndReason.YEAR_LIMIT : null;
            if (reason != null)
                return new Result(options, initial, root, archive.build(), reason);

            Map<Nation, ActionCandidate> actions = new EnumMap<>(Nation.class);
            for (Nation nation : root.aliveNations()) {
                Game simulation = options.createGame(root);
                AgentDecision decision = options.agents().get(nation).decide(
                        simulation, nation, options, seed(options.seed(), root.moment(), nation, 0),
                        seed(options.seed(), root.moment(), nation, 1),
                        seed(options.seed(), root.moment(), nation, 2));
                if (!simulation.snapshot().equals(root) || simulation.getSnapshot() != null
                        || !simulation.processorHistory().isEmpty())
                    throw new IllegalStateException("Agent changed its simulation root for " + nation);
                if (decision.power() != nation)
                    throw new IllegalStateException("Agent returned another nation's policy");
                actions.put(nation, decision.selected());
                decisionObserver.accept(decision);
            }

            long tick = ((long) root.moment().year() - Constants.STARTING_YEAR)
                    * GamePhase.values().length + root.moment().gamePhase().ordinal();
            Moment resolvedAt = new Moment(root.moment().year(), root.moment().gamePhase(),
                    Instant.EPOCH.plusSeconds(tick));
            GameState next = simulator.advance(archive, actions, resolvedAt);
            if (next.moment().compareTo(root.moment()) <= 0 || next.moment().year() > options.lastYear())
                throw new IllegalStateException("Recorded game advanced outside its phase or year limits");
            observer.accept(archive.resolvedPhases().getLast());
            root = next;
        }
    }

    private static Nation solo(GameState state) {
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);
        state.board().owners().values().forEach(nation -> counts.merge(nation, 1, Integer::sum));
        for (Nation nation : Nation.values())
            if (counts.getOrDefault(nation, 0) >= 18)
                return nation;
        return null;
    }

    private static long seed(long seed, GameMoment moment, Nation nation, int stream) {
        long phase = (long) moment.year() * GamePhase.values().length + moment.gamePhase().ordinal();
        long key = (phase * Nation.values().length + nation.ordinal()) * 3 + stream;
        long value = seed + 0x9E3779B97F4A7C15L * (key + 1);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

}