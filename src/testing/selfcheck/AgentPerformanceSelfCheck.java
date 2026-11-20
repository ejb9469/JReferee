package testing.selfcheck;

import _app.AgentPerformanceApp;
import agents.evaluation.NeuralValueModel;
import agents.execution.*;
import agents.search.PolicyOutput;
import domain.*;
import game.*;
import io.json.JsonAccess;
import io.json.JsonReader;
import io.training.*;
import misc.scoring.ScoringSystem;
import performance.*;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;

import java.nio.file.*;
import java.util.*;

/**
 * Small on/off decision, recorder and profiler checks; not a speed benchmark.
 */
public final class AgentPerformanceSelfCheck {


    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));

    private AgentPerformanceSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 0 && args.length != 1 && args.length != 3)
            throw new IllegalArgumentException(
                    "Usage: AgentPerformanceSelfCheck [value-model.bin [anchor.bin gru-model.bin]]");
        GameState opening = StandardGameFactory.create1901().snapshot();
        GameState fall = new GameState_Movement(new GameMoment(1901, GamePhase.FALL_MOVEMENT), opening.board());
        Game winterGame = options(AgentSettings.paper(2, 2), 1903).createGame(fall);
        winterGame.resolveMovement(List.of());
        GameState winter = winterGame.snapshot();
        GameState retreat = retreat();
        for (AgentSettings.Method method : AgentSettings.Method.values()) {
            AgentSettings settings = new AgentSettings(method, 2, 2, 1, 2, PolicyOutput.AVERAGE);
            for (GameState state : List.of(opening, winter, retreat))
                decisions(state, settings);
        }
        archives(opening);
        if (args.length > 0) {
            NeuralValueModel value = NeuralValueModel.load(Path.of(args[0]));
            AgentSettings settings = AgentSettings.paper(2, 2).withValueModel(value);
            AgentPerformance performance = decisions(fall, settings);
            require(calls(performance, "neural value inference") > 0, "Neural leaf was not timed");
            if (args.length == 3) {
                AgentModels models = AgentModels.load(Path.of(args[1]), Optional.of(Path.of(args[2])));
                decisions(fall, new AgentSettings(AgentSettings.Method.DIL_PIKL, 2, 2, 1, 2,
                        PolicyOutput.AVERAGE, AgentSettings.Generator.COHERENT_GRU_HYBRID,
                        AgentSettings.Generator.UNIFORM, Optional.of(models), Optional.of(value)));
            }
        }
        System.out.println("AgentPerformanceSelfCheck passed.");

    }

    private static AgentPerformance decisions(GameState state, AgentSettings settings) {

        GameRunner.Options options = options(settings, 1903);
        Nation power = state.aliveNations().iterator().next();
        AgentPerformance disabled = AgentPerformance.disabled();
        Game first = options.createGame(state);
        AgentDecision reference = settings.decide(first, power, options, 7, 8, 9, disabled);
        AgentPerformance enabled = new AgentPerformance(true);
        Game second = options.createGame(state, enabled.movement());
        AgentDecision measured = settings.decide(second, power, options, 7, 8, 9, enabled);
        AgentPerformance.requireSameDecision(reference, measured);
        require(first.snapshot().equals(state) && second.snapshot().equals(state)
                        && first.getSnapshot() == null && second.getSnapshot() == null
                        && first.processorHistory().isEmpty() && second.processorHistory().isEmpty(),
                "Profiling changed root restoration");
        require(disabled.timings().snapshot().isEmpty()
                        && disabled.counters().values().stream().allMatch(value -> value == 0),
                "Disabled collector accumulated diagnostics");
        require(calls(enabled, "agent decision") == 1 && calls(enabled, "candidate generation") == 1
                && calls(enabled, "action sampling") == 1, "Decision timing coverage differs");
        long requests = enabled.counters().get("joint_requests");
        require(requests == calls(enabled, "joint evaluation")
                        && requests == enabled.counters().get("root_simulations")
                        && requests == enabled.counters().get("continuation_evaluations")
                        && enabled.counters().get("joint_cache_hits") == 0,
                "Search work accounting or cache policy changed");
        require((requests > 0) == (settings.method() != AgentSettings.Method.PRIOR),
                "Search ran for PRIOR or was not measured");
        require(enabled.counters().get("movements") == calls(enabled, "movement"),
                "Root/continuation movement diagnostics were not shared");
        return enabled;

    }

    private static void archives(GameState initial) throws Exception {

        Path temporary = Files.createTempDirectory("jreferee-agent-performance-");
        try {
            AgentSettings settings = new AgentSettings(AgentSettings.Method.PRIOR,
                    1, 1, 1, 1, PolicyOutput.AVERAGE);
            GameRunner.Options options = options(settings, 1901);
            UUID id = UUID.fromString("6dca5abe-80b5-48ed-900f-3e7777241d67");
            List<AgentDecision> reference = new ArrayList<>();
            List<AgentDecision> measured = new ArrayList<>();
            Path first = temporary.resolve("unprofiled"), second = temporary.resolve("profiled");
            var original = SelfPlayRecorder.run(first, id, initial, options, reference::add);
            AgentPerformance performance = new AgentPerformance(true);
            var result = SelfPlayRecorder.run(second, id, initial, options, measured::add, performance);
            // Independent winters retain different RetreatResult instances.
            require(original.state().moment().equals(result.state().moment()),
                    "Profiling changed the final phase");
            require(original.state().board().equals(result.state().board()),
                    "Profiling changed the final board or unit identities");
            require(original.reason() == result.reason() && original.scores().equals(result.scores()),
                    "Profiling changed the ending or scores");
            require(reference.size() == measured.size(), "Profiling changed the decision count");
            for (int index = 0; index < reference.size(); index++)
                AgentPerformance.requireSameDecision(reference.get(index), measured.get(index));
            for (String name : List.of("run.json", "outcome.json"))
                require(Files.readString(first.resolve(name)).equals(Files.readString(second.resolve(name))),
                        "Profiling changed archive settings or ending");
            var decoded = SelfPlayDataset.open(second);
            require(decoded.phases().size() == result.record().resolvedPhases().size()
                            && calls(performance, "archive phase write") == decoded.phases().size()
                            && calls(performance, "real phase resolution") == decoded.phases().size()
                            && calls(performance, "archive open") == 1
                            && calls(performance, "archive finish") == 1,
                    "Archive replay or timing coverage differs");
            String[] arguments = {second.toString(), "0", "ENGLAND", "baseline", "none", "none",
                    "1", "2", temporary.resolve("report").toString()};
            AgentPerformanceApp.main(arguments);
            var report = JSON.object(JsonReader.read(Files.readString(
                    temporary.resolve("report/performance.json"))), "$");
            var context = JSON.object(report.get("context"), "context");
            require(Boolean.TRUE.equals(context.get("recorded_decision_reproduced"))
                            && Boolean.TRUE.equals(context.get("profiled_decisions_equal_reference")),
                    "Profiler did not reproduce its fixed decision");
            var sections = JSON.array(report.get("sections"), "sections");
            require(sectionCalls(sections.get(1), "agent decision") == 1
                            && sectionCalls(sections.get(2), "agent decision") == 2,
                    "Warm-up leaked into measured work");
            arguments[4] = "none";
            arguments[5] = "missing-gru.bin";
            arguments[8] = temporary.resolve("rejected").toString();
            try {
                AgentPerformanceApp.main(arguments);
                throw new AssertionError("Mismatched artifacts were accepted");
            } catch (IllegalArgumentException expected) { }
            require(!Files.exists(temporary.resolve("rejected")), "Invalid run published a report");

            IllegalStateException failure = new IllegalStateException("observer failed");
            AgentPerformance failed = new AgentPerformance(true);
            Path interrupted = temporary.resolve("interrupted");
            try {
                SelfPlayRecorder.run(interrupted, id, initial, options, decision -> {
                    throw failure;
                }, failed);
                throw new AssertionError("Observer failure was swallowed");
            } catch (IllegalStateException actual) {
                require(actual == failure, "Original observer failure was replaced");
            }
            require(calls(failed, "decision observer") == 1
                            && calls(failed, "agent decision") == 1
                            && !Files.exists(interrupted.resolve("manifest.json")),
                    "Failed attempt was not timed or was published as complete");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }

    }

    private static GameState retreat() {

        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId german = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        UnitId support = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        GameState state = new GameState_Movement(new GameMoment(1901, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur, german, Province.Mun, support, Province.Ruh),
                        Map.of(Province.Par, Nation.FRANCE, Province.Mar, Nation.FRANCE,
                                Province.Mun, Nation.GERMANY)));
        Game game = options(AgentSettings.paper(2, 2), 1903).createGame(state);
        game.resolveMovement(List.of(Order.hold(french), Order.move(german, Province.Bur),
                Order.supportMove(support, Province.Mun, Province.Bur)));
        require(game.snapshot() instanceof GameState_Retreats, "Retreat fixture was not reached");
        return game.snapshot();

    }

    private static GameRunner.Options options(AgentSettings settings, int year) {

        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            seats.put(nation, settings);
        return new GameRunner.Options(year, 7, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, seats);

    }

    private static long calls(AgentPerformance performance, String name) {
        return performance.timings().snapshot().stream().filter(value -> value.name().equals(name))
                .mapToLong(PerformanceStats.Measurement::calls).sum();
    }

    private static long sectionCalls(Object raw, String name) {

        var section = JSON.object(raw, "section");
        for (Object value : JSON.array(section.get("timings"), "timings")) {
            var timing = JSON.object(value, "timing");
            if (name.equals(timing.get("operation")))
                return ((Number) timing.get("calls")).longValue();
        }
        return 0;

    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}