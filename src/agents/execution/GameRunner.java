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
import performance.AgentPerformance;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * Runs and records a bounded, sequential, non-press game.<br><br>
 *
 * Every living nation decides from an independent copy of the same starting
 * position. The real game advances only after all actions are collected.
 * A solo takes precedence over the final-year cutoff.
 */
public final class GameRunner {


    // Configuration and results \\

    public enum EndReason {
        SOLO,
        YEAR_LIMIT
    }

    /**
     * Settings for every seat and every real or speculative game.
     */
    public record Options(
            int lastYear,
            long seed,
            ScoringSystem scoring,
            MovementProcessor.Policy movementPolicy,
            int adjudicationTrials,
            long adjudicationSeed,
            Map<Nation, AgentSettings> agents
    ) {

        public Options {

            Objects.requireNonNull(scoring, "scoring");
            Objects.requireNonNull(movementPolicy, "movementPolicy");

            if (lastYear < Constants.STARTING_YEAR
                    || lastYear == Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid final year");

            if (adjudicationTrials < 1)
                throw new IllegalArgumentException(
                        "Adjudication trials must be at least 1");

            Map<Nation, AgentSettings> checked = Map.copyOf(
                    Objects.requireNonNull(agents, "agents"));

            if (!checked.keySet().equals(EnumSet.allOf(Nation.class)))
                throw new IllegalArgumentException(
                        "Settings are required for all seven nations");

            agents = Collections.unmodifiableMap(new EnumMap<>(checked));

        }

        /**
         * Creates fresh processors with identical real/speculative rules.
         */
        public Game createGame(GameState state) {
            return createGame(state, null);
        }

        public Game createGame(GameState state, MovementProcessor.Diagnostics diagnostics) {
            return Game.fromState(
                    state,
                    new MovementProcessor(
                            this.movementPolicy,
                            this.adjudicationTrials,
                            this.adjudicationSeed,
                            diagnostics
                    ),
                    new RetreatProcessor(),
                    new AdjustmentProcessor()
            );
        }

        public String rulesetId() {
            return "jreferee-agent-v1:"
                    + this.movementPolicy + ":"
                    + this.adjudicationTrials + ":"
                    + this.adjudicationSeed;
        }

    }

    /**
     * Immutable configuration, history, final position and stopping reason.
     * A year limit is a scored cutoff, not an agreed draw.
     */
    public record Result(
            Options options,
            GameState initial,
            GameState state,
            GameRecord record,
            EndReason reason
    ) {

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
                throw new IllegalArgumentException(
                        "Record and game positions disagree");

            boolean validEnding;

            if (reason == EndReason.SOLO) {
                validEnding = solo(state) != null;
            } else {
                validEnding = solo(state) == null
                        && state instanceof GameState_Adjustments
                        && state.moment().year() == options.lastYear();
            }

            if (!validEnding)
                throw new IllegalArgumentException(
                        "Stopping reason does not match the final position");

        }

        public Map<Nation, Double> scores() {
            return new ScoringEvaluator(this.options.scoring()).evaluate(
                    this.state);
        }

        public Optional<Nation> winner() {
            return Optional.ofNullable(solo(this.state));
        }

    }


    // Game execution \\

    public Result run(UUID id, GameState initial, Options options) {
        return this.run(id, initial, options, phase -> { });
    }

    /**
     * Reports each phase after it has been successfully recorded.
     * Observer failures propagate rather than producing a completed result.
     */
    public Result run(
            UUID id,
            GameState initial,
            Options options,
            Consumer<ResolvedPhaseRecord> observer
    ) {
        return this.run(id, initial, options, observer, decision -> { });
    }

    /**
     * Decision diagnostics are reported before real resolution.
     * Observation performs no additional generation, search or sampling.
     */
    public Result run(
            UUID id,
            GameState initial,
            Options options,
            Consumer<ResolvedPhaseRecord> observer,
            Consumer<AgentDecision> decisionObserver
    ) {

        return this.run(id, initial, options, observer, decisionObserver, AgentPerformance.disabled());

    }

    /** Diagnostics remain outside the game configuration and record. */
    public Result run(
            UUID id,
            GameState initial,
            Options options,
            Consumer<ResolvedPhaseRecord> observer,
            Consumer<AgentDecision> decisionObserver,
            AgentPerformance performance
    ) {

        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(decisionObserver, "decisionObserver");
        Objects.requireNonNull(performance, "performance");

        if (initial.moment().year() > options.lastYear())
            throw new IllegalArgumentException(
                    "Initial position is beyond the final year");

        new ScoringEvaluator(options.scoring()).evaluate(initial);

        Game game = options.createGame(initial, performance.movement());
        GameRecordBuilder archive = new GameRecordBuilder(
                id, options.rulesetId(), game);

        Simulator simulator = new Simulator(performance.timings());
        GameState root = initial;

        while (true) {

            EndReason reason = null;

            if (solo(root) != null) {
                reason = EndReason.SOLO;
            } else if (root instanceof GameState_Adjustments
                    && root.moment().year() == options.lastYear()) {
                reason = EndReason.YEAR_LIMIT;
            }

            if (reason != null)
                return new Result(
                        options, initial, root, archive.build(), reason);

            Map<Nation, ActionCandidate> actions = new EnumMap<>(Nation.class);

            for (Nation nation : root.aliveNations()) {

                Game simulation = options.createGame(root, performance.movement());
                AgentSettings settings = options.agents().get(nation);

                AgentDecision decision = settings.decide(
                        simulation,
                        nation,
                        options,
                        seed(options.seed(), root.moment(), nation, 0),
                        seed(options.seed(), root.moment(), nation, 1),
                        seed(options.seed(), root.moment(), nation, 2),
                        performance
                );

                if (!simulation.snapshot().equals(root)
                        || simulation.getSnapshot() != null
                        || !simulation.processorHistory().isEmpty())
                    throw new IllegalStateException(
                            "Agent changed its simulation root for " + nation);

                if (decision.power() != nation)
                    throw new IllegalStateException(
                            "Agent returned another nation's policy");

                actions.put(nation, decision.selected());
                try (var observation = performance.timings().measure("decision observer")) {
                    decisionObserver.accept(decision);
                }

            }

            // Logical timestamps reproduce the archive independently of runtime.
            long tick = ((long) root.moment().year() - Constants.STARTING_YEAR)
                    * GamePhase.values().length
                    + root.moment().gamePhase().ordinal();

            Moment resolvedAt = new Moment(
                    root.moment().year(),
                    root.moment().gamePhase(),
                    Instant.EPOCH.plusSeconds(tick)
            );

            GameState next;
            try (var resolution = performance.timings().measure("real phase resolution")) {
                next = simulator.advance(archive, actions, resolvedAt);
            }

            if (next.moment().compareTo(root.moment()) <= 0
                    || next.moment().year() > options.lastYear())
                throw new IllegalStateException(
                        "Recorded game advanced outside its phase or year limits");

            try (var observation = performance.timings().measure("phase observer")) {
                observer.accept(archive.resolvedPhases().getLast());
            }
            root = next;

        }

    }


    // Ending and random streams \\

    private static Nation solo(GameState state) {

        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);

        state.board().owners().values().forEach(
                nation -> counts.merge(nation, 1, Integer::sum));

        for (Nation nation : Nation.values())
            if (counts.getOrDefault(nation, 0) >= 18)
                return nation;

        return null;

    }

    private static long seed(
            long seed,
            GameMoment moment,
            Nation nation,
            int stream
    ) {

        long phase = (long) moment.year() * GamePhase.values().length
                + moment.gamePhase().ordinal();

        long key = (phase * Nation.values().length + nation.ordinal())
                * 3 + stream;

        long value = seed + 0x9E3779B97F4A7C15L * (key + 1);

        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;

        return value ^ (value >>> 31);

    }

}