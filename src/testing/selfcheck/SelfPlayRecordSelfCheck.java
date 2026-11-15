package testing.selfcheck;

import agents.execution.*;
import agents.generation.*;
import agents.search.PolicyOutput;
import domain.*;
import game.*;
import io.json.JsonAccess;
import io.json.JsonReader;
import io.training.SelfPlayFiles;
import io.training.SelfPlayRecorder;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.adjustments.*;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatOrder;

import java.nio.file.*;
import java.util.*;

/**
 * Small real games plus positional encoding and archive failure checks.
 * No model files, training data or TEST access are required.
 */
public final class SelfPlayRecordSelfCheck {

    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));
    private static final UUID ID = new UUID(0, 71);

    private SelfPlayRecordSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 0)
            throw new IllegalArgumentException("Usage: SelfPlayRecordSelfCheck");
        Path temporary = Files.createTempDirectory("jreferee-self-play-check-");
        try {
            recordedGame(temporary);
            coordinates();
            endingsAndFailures(temporary);
            System.out.println("SelfPlayRecordSelfCheck passed.");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }

    }

    private static GameRunner.Options options(int year) {

        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        for (Nation power : Nation.values())
            seats.put(power, new AgentSettings(
                    AgentSettings.Method.values()[power.ordinal() % 3],
                    2, 1, 1, 1, PolicyOutput.AVERAGE));
        return new GameRunner.Options(year, 7, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, seats);

    }

    private static void recordedGame(Path temporary) throws Exception {

        GameState initial = StandardGameFactory.create1901().snapshot();
        GameRunner.Options options = options(1902);
        List<AgentDecision> observed = new ArrayList<>();
        Path directory = temporary.resolve("recorded");
        GameRunner.Result recorded = SelfPlayRecorder.run(directory, ID, initial, options, observed::add);
        GameRunner.Result plain = new GameRunner().run(ID, initial, options);
        require(recorded.state().moment().equals(plain.state().moment())
                        && recorded.state().board().equals(plain.state().board())
                        && recorded.reason() == plain.reason() && recorded.scores().equals(plain.scores()),
                "Recording changed the game");
        SelfPlayFiles.Summary summary = SelfPlayFiles.verify(directory);
        require(summary.phases() == recorded.record().resolvedPhases().size()
                && summary.decisions() == observed.size()
                && summary.termination().equals("ARTIFICIAL_CUTOFF"), "Archive summary differs");
        Map<String, Object> outcome = document(directory.resolve("outcome.json"));
        require(Boolean.FALSE.equals(outcome.get("value_target_eligible"))
                && outcome.get("eventual_sos_target") == null
                && outcome.get("cutoff_scores") instanceof Map<?, ?>, "Cutoff became an eventual target");
        int offset = 0;
        boolean opening = false, standard = false;
        for (String line : Files.readAllLines(directory.resolve("phases.jsonl"))) {
            Map<String, Object> phase = JSON.object(JsonReader.read(line), "phase");
            for (Object entry : JSON.array(phase.get("decisions"), "decisions")) {
                AgentDecision decision = observed.get(offset++);
                require(SelfPlayFiles.json(entry).equals(SelfPlayFiles.json(SelfPlayRecorder.decision(decision))),
                        "Persisted candidate order, policy, type or seeds changed");
                require(decision.selected().equals(
                                decision.distribution().sample(new Random(decision.selectionSeed()))),
                        "Selection seed does not reproduce the selected action");
                Map<String, Object> encoded = JSON.object(entry, "decision");
                if (decision.settings().method() == AgentSettings.Method.DIL_PIKL) {
                    Map<String, Object> type = JSON.object(encoded.get("type"), "type");
                    opening |= "OPENING".equals(type.get("profile"));
                    standard |= "STANDARD".equals(type.get("profile"));
                } else {
                    require(encoded.get("type") == null, "Non-DiL method invented a lambda type");
                }
            }
        }
        require(offset == observed.size() && opening && standard, "Missing decisions or phase profiles");
        expect(FileAlreadyExistsException.class,
                () -> SelfPlayRecorder.run(directory, ID, initial, options, decision -> { }));
        SelfPlayFiles.verify(directory);
        byte[] original = Files.readAllBytes(directory.resolve("phases.jsonl"));
        byte[] corrupted = original.clone();
        corrupted[0] ^= 1;
        Files.write(directory.resolve("phases.jsonl"), corrupted);
        expect(IllegalArgumentException.class, () -> SelfPlayFiles.verify(directory));
        Files.write(directory.resolve("phases.jsonl"), original);
        SelfPlayFiles.verify(directory);

    }

    private static void coordinates() {

        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId german = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        UnitId support = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur, german, Province.Mun, support, Province.Ruh),
                        Map.of(Province.Par, Nation.FRANCE, Province.Mar, Nation.FRANCE,
                                Province.Mun, Nation.GERMANY, Province.Stp, Nation.RUSSIA)));
        require(SelfPlayRecorder.order(root, Order.hold(french)).get("origin").equals("Bur"),
                "Movement used a unit's birth province");
        Game game = options(1908).createGame(root);
        game.resolveMovement(List.of(Order.hold(french), Order.move(german, Province.Bur),
                Order.supportMove(support, Province.Mun, Province.Bur)));
        GameState retreat = game.snapshot();
        require(retreat instanceof GameState_Retreats, "Fixture did not dislodge a unit");
        require(SelfPlayRecorder.order(retreat, new RetreatOrder(french, Province.Gas))
                .get("origin").equals("Bur"), "Retreat lost the displaced location");
        require(JSON.array(SelfPlayRecorder.state(retreat).get("dislodged"), "dislodged").size() == 1,
                "Retreat position lost its dislodged unit");
        game.resolveRetreats(List.of());
        GameState winter = game.snapshot();
        require(SelfPlayRecorder.order(winter, new DisbandOrder(Nation.GERMANY, support))
                .get("origin").equals("Ruh"), "Disband used a birth province");
        require(SelfPlayRecorder.order(winter, new BuildOrder(Nation.RUSSIA, UnitType.FLEET, Province.StpSC))
                .get("origin").equals("StpSC"), "Build lost its exact coast");
        Map<String, Object> waive = SelfPlayRecorder.order(winter, new WaiveOrder(Nation.RUSSIA));
        require(waive.get("origin") == null && waive.get("unit_type") == null, "Waive invented a unit");

        ActionCandidate hold = new ActionCandidate(Nation.FRANCE, List.of(Order.hold(french)),
                0, ActionCandidate.Source.HEURISTIC);
        ActionCandidate excluded = new ActionCandidate(Nation.FRANCE, List.of(Order.move(french, Province.Gas)),
                Double.NEGATIVE_INFINITY, ActionCandidate.Source.EXPLORATION);
        List<ActionCandidate> pool = List.of(hold, excluded);
        AgentDecision decision = new AgentDecision(root,
                new AgentSettings(AgentSettings.Method.PRIOR, 2, 1, 1, 1, PolicyOutput.AVERAGE),
                Long.MIN_VALUE, Long.MAX_VALUE, 7, 0, Map.of(Nation.FRANCE, pool),
                ActionDistribution.fromPriors(pool), Optional.empty(), 0);
        String text = SelfPlayFiles.json(SelfPlayRecorder.decision(decision));
        JsonReader.read(text);
        require(text.contains("\"-Infinity\"") && text.contains("\"" + Long.MIN_VALUE + "\""),
                "Zero prior or 64-bit seed was not preserved");

    }

    private static void endingsAndFailures(Path temporary) throws Exception {

        Map<Province, Nation> owners = new EnumMap<>(Province.class);
        for (Province province : Province.values())
            if (province.supplyCenter && Province.canonical(province) == province && owners.size() < 18)
                owners.put(province, Nation.FRANCE);
        GameState solo = new GameState_Movement(new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(), owners));
        Path completed = temporary.resolve("solo");
        SelfPlayRecorder.run(completed, ID, solo, options(1901), decision -> {
            throw new AssertionError("Terminal solo generated a decision");
        });
        SelfPlayFiles.Summary summary = SelfPlayFiles.verify(completed);
        require(summary.phases() == 0 && summary.termination().equals("SOLO"), "Initial solo was not retained");
        Map<String, Object> outcome = document(completed.resolve("outcome.json"));
        Map<String, Object> target = JSON.object(outcome.get("eventual_sos_target"), "target");
        require(((Number) target.get("FRANCE")).doubleValue() == 1
                && outcome.get("cutoff_scores") == null, "Solo target was not exact");

        Path failed = temporary.resolve("failed");
        RuntimeException sentinel = new IllegalStateException("Observer failed");
        GameState initial = StandardGameFactory.create1901().snapshot();
        try {
            SelfPlayRecorder.run(failed, ID, initial, options(1902), decision -> {
                if (!decision.state().moment().equals(initial.moment()))
                    throw sentinel;
            });
            throw new AssertionError("Observer failure was swallowed");
        } catch (IllegalStateException exception) {
            require(exception == sentinel, "Observer exception changed");
        }
        require(Files.exists(failed.resolve("run.json"))
                && !Files.exists(failed.resolve("manifest.json")), "Failure created a completion marker");
        require(Files.readAllLines(failed.resolve("phases.jsonl")).size() == 1,
                "Failure lost the previously flushed phase");
        expect(NoSuchFileException.class, () -> SelfPlayFiles.verify(failed));
        expect(IllegalArgumentException.class, () -> SelfPlayFiles.json(Double.NaN));

    }

    private static Map<String, Object> document(Path path) throws Exception {
        return JSON.object(JsonReader.read(Files.readString(path)), path.toString());
    }

    private interface Checked { void run() throws Exception; }

    private static void expect(Class<? extends Exception> type, Checked action) throws Exception {

        try {
            action.run();
        } catch (Exception exception) {
            if (type.isInstance(exception))
                return;
            throw exception;
        }
        throw new AssertionError("Expected " + type.getSimpleName());

    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}