package testing.selfcheck;

import agents.execution.*;
        import agents.generation.ActionCandidate;
import agents.search.PolicyOutput;
import agents.simulation.Simulator;
import domain.*;
        import game.*;
        import io.json.JsonAccess;
import io.json.JsonReader;
import io.training.*;
        import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;

import java.nio.file.*;
        import java.util.*;
        import java.util.function.Consumer;

/**
 * Replays real games and rejects semantic corruption even with fresh hashes.
 */
public final class SelfPlayDatasetSelfCheck {

    private static final UUID ID = new UUID(0, 81);
    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));

    private SelfPlayDatasetSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 0)
            throw new IllegalArgumentException("Usage: SelfPlayDatasetSelfCheck");
        Path temporary = Files.createTempDirectory("jreferee-readback-");
        try {
            Path source = temporary.resolve("source");
            GameRunner.Result original = SelfPlayRecorder.run(source, ID,
                    StandardGameFactory.create1901().snapshot(), options(1902, false), decision -> { });
            SelfPlayDataset.GameExample decoded = SelfPlayDataset.open(source);
            require(SelfPlayRecorder.state(original.state()).equals(SelfPlayRecorder.state(decoded.finalState())),
                    "Typed replay changed the final state");
            require(decoded.phases().size() == original.record().resolvedPhases().size()
                    && decoded.valueAssessment().rejection().orElseThrow()
                    == ValueRewardContract.Rejection.ARTIFICIAL_CUTOFF, "Cutoff eligibility changed");
            for (var phase : decoded.phases())
                for (var decision : phase.decisions()) {
                    require(decision.policy().candidates().equals(decision.pools().get(decision.power())),
                            "Policy indices lost their pool");
                    rejectMutation(() -> decision.pools().clear());
                }
            expect(() -> SelfPlayDataset.openAll(List.of(source, source)));
            Bundle renamed = bundle(source);
            String newId = new UUID(0, 82).toString();
            renamed.run().put("game_id", newId);
            renamed.outcome().put("game_id", newId);
            Path alias = temporary.resolve("alias");
            write(alias, renamed);
            expect(() -> SelfPlayDataset.openAll(List.of(source, alias)));

            corrupt(temporary, source, "policy-shape",
                    b -> firstDecision(b).put("policy", List.of(1.0, 0.0, 0.0)));
            corrupt(temporary, source, "selection",
                    b -> firstDecision(b).put("selected_index", 99));
            corrupt(temporary, source, "seed",
                    b -> firstDecision(b).put("selection_seed", "0"));
            corrupt(temporary, source, "illegal-order", b -> {
                Map<String, Object> decision = firstDecision(b);
                String power = (String) decision.get("power");
                Map<String, Object> candidate = map(array(map(decision.get("pools")).get(power)).getFirst());
                Map<String, Object> order = map(array(candidate.get("orders")).getFirst());
                order.put("type", "MOVE");
                order.put("target", "Syr");
                order.put("auxiliary_target", null);
            });
            corrupt(temporary, source, "type", b -> {
                Map<String, Object> typed = array(b.phases().getFirst().get("decisions")).stream()
                        .map(SelfPlayDatasetSelfCheck::map).filter(d -> d.get("type") != null)
                        .findFirst().orElseThrow();
                map(typed.get("type")).put("playing_lambda", 0.5);
            });
            corrupt(temporary, source, "chronology",
                    b -> map(map(b.phases().get(1).get("state")).get("moment")).put("year", 1910));
            corrupt(temporary, source, "adjudication",
                    b -> map(map(b.phases().getFirst().get("board_after")).get("centers")).put("Par", "GERMANY"));
            corrupt(temporary, source, "model-identity",
                    b -> map(map(b.run().get("seats")).get("FRANCE"))
                            .put("movement_models", Map.of("anchor", "not-a-hash")));
            corrupt(temporary, source, "scores",
                    b -> map(b.outcome().get("cutoff_scores")).put("FRANCE", 0.999));
            corrupt(temporary, source, "false-solo", b -> {
                b.outcome().put("termination", "SOLO");
                b.outcome().put("runner_reason", "SOLO");
                b.outcome().put("winner", "FRANCE");
                b.outcome().put("value_target_eligible", true);
                b.outcome().put("eventual_sos_target", b.outcome().get("cutoff_scores"));
                b.outcome().put("cutoff_scores", null);
            });
            Bundle zeroPrior = bundle(source);
            Map<String, Object> best = array(zeroPrior.phases().getFirst().get("decisions")).stream()
                    .map(SelfPlayDatasetSelfCheck::map)
                    .filter(d -> "BEST_RESPONSE".equals(d.get("method"))).findFirst().orElseThrow();
            int selected = JSON.integer(best.get("selected_index"), "selected_index");
            map(array(map(best.get("pools")).get((String) best.get("power"))).get(selected))
                    .put("log_prior", "-Infinity");
            Path bestZero = temporary.resolve("best-zero-prior");
            write(bestZero, zeroPrior);
            SelfPlayDataset.open(bestZero);
            retreats(temporary);
            solo(temporary);
            System.out.println("SelfPlayDatasetSelfCheck passed.");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }

    }

    private static GameRunner.Options options(int year, boolean priorOnly) {
        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        for (Nation power : Nation.values())
            seats.put(power, new AgentSettings(priorOnly ? AgentSettings.Method.PRIOR
                    : AgentSettings.Method.values()[power.ordinal() % 3],
                    priorOnly ? 1 : 2, 1, 1, 1, PolicyOutput.AVERAGE));
        return new GameRunner.Options(year, 7, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, seats);
    }

    private static void retreats(Path temporary) throws Exception {

        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId german = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        UnitId support = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        GameState initial = new GameState_Movement(new GameMoment(1901, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur, german, Province.Mun, support, Province.Ruh),
                        Map.of(Province.Par, Nation.FRANCE, Province.Mun, Nation.GERMANY)));
        var options = options(1901, true);
        Path base = temporary.resolve("battle-base"), tail = temporary.resolve("battle-tail");
        SelfPlayRecorder.run(base, ID, initial, options, decision -> { });
        Map<Nation, ActionCandidate> actions = Map.of(
                Nation.FRANCE, new ActionCandidate(Nation.FRANCE, List.of(Order.hold(french)),
                        0, ActionCandidate.Source.HEURISTIC),
                Nation.GERMANY, new ActionCandidate(Nation.GERMANY,
                        List.of(Order.move(german, Province.Bur),
                                Order.supportMove(support, Province.Mun, Province.Bur)),
                        0, ActionCandidate.Source.HEURISTIC));
        GameState retreat = new Simulator().advance(options.createGame(initial), actions);
        require(retreat instanceof GameState_Retreats, "Fixture did not dislodge a unit");
        SelfPlayRecorder.run(tail, ID, retreat, options, decision -> { });
        Bundle first = bundle(base), rest = bundle(tail);
        Map<String, Object> movement = first.phases().getFirst();
        for (Object raw : array(movement.get("decisions"))) {
            Map<String, Object> decision = map(raw);
            Nation power = Nation.valueOf((String) decision.get("power"));
            Map<String, Object> candidate = map(array(map(decision.get("pools")).get(power.name())).getFirst());
            candidate.put("orders", actions.get(power).orders().stream()
                    .map(order -> SelfPlayRecorder.order(initial, order)).toList());
        }
        movement.put("board_after", SelfPlayRecorder.state(retreat).get("board"));
        List<Map<String, Object>> phases = new ArrayList<>(List.of(movement));
        phases.addAll(rest.phases());
        Bundle combined = new Bundle(first.run(), phases, rest.outcome());
        Path complete = temporary.resolve("battle");
        write(complete, combined);
        var game = SelfPlayDataset.open(complete);
        require(game.phases().size() == 2 && game.phases().get(1).state() instanceof GameState_Retreats,
                "Retreat phase was not reconstructed");
        corrupt(temporary, complete, "lost-dislodgement",
                b -> map(b.phases().get(1).get("state")).put("dislodged", List.of()));
        expect(() -> SelfPlayDataset.open(tail));

    }

    private static void solo(Path temporary) throws Exception {
        Map<Province, Nation> owners = new EnumMap<>(Province.class);
        for (Province province : Province.values())
            if (province.supplyCenter && Province.canonical(province) == province && owners.size() < 18)
                owners.put(province, Nation.FRANCE);
        GameState initial = new GameState_Movement(new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(), owners));
        Path path = temporary.resolve("solo");
        SelfPlayRecorder.run(path, ID, initial, options(1901, true), decision -> { });
        var game = SelfPlayDataset.open(path);
        require(game.phases().isEmpty() && game.valueAssessment().rewards().orElseThrow().get(Nation.FRANCE) == 1,
                "Exact solo target was not retained");
    }

    private record Bundle(
            Map<String, Object> run, List<Map<String, Object>> phases, Map<String, Object> outcome
    ) { }

    private static Bundle bundle(Path directory) throws Exception {
        var snapshot = SelfPlayFiles.snapshot(directory);
        return new Bundle(map(JsonReader.read(snapshot.run())),
                snapshot.phases().lines().map(JsonReader::read).map(SelfPlayDatasetSelfCheck::map).toList(),
                map(JsonReader.read(snapshot.outcome())));
    }

    private static void write(Path directory, Bundle bundle) throws Exception {
        int decisions = 0;
        try (SelfPlayFiles files = new SelfPlayFiles(directory, bundle.run())) {
            for (int index = 0; index < bundle.phases().size(); index++) {
                Map<String, Object> phase = bundle.phases().get(index);
                phase.put("index", index);
                decisions += array(phase.get("decisions")).size();
                files.append(phase);
            }
            bundle.outcome().put("phase_count", bundle.phases().size());
            bundle.outcome().put("decision_count", decisions);
            files.finish(bundle.outcome());
        }
    }

    private static void corrupt(Path temporary, Path source, String name, Consumer<Bundle> change) throws Exception {
        Bundle bundle = bundle(source);
        change.accept(bundle);
        Path bad = temporary.resolve(name);
        write(bad, bundle);
        SelfPlayFiles.verify(bad);
        expect(() -> SelfPlayDataset.open(bad));
    }

    private static Map<String, Object> firstDecision(Bundle bundle) {
        return map(array(bundle.phases().getFirst().get("decisions")).getFirst());
    }

    private static Map<String, Object> map(Object value) { return JSON.object(value, "object"); }
    private static List<Object> array(Object value) { return JSON.array(value, "array"); }
    private interface Checked { void run() throws Exception; }

    private static void expect(Checked action) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected rejection");
    }

    private static void rejectMutation(Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Mutable typed record");
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}
