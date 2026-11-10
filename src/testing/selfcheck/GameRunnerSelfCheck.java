package testing.selfcheck;

import agents.execution.AgentSettings;
import agents.execution.GameRunner;
import agents.generation.*;
import agents.search.PolicyOutput;
import agents.simulation.Simulator;
import domain.*;
import game.*;
import game.record.*;
import misc.scoring.ScoringSystem;
import parsing.datc.DATCProcessorFileParser;
import phase.Order;
import phase.UnitId;
import phase.adjustments.BuildOrder;
import phase.adjustments.DisbandOrder;
import phase.movement.MovementProcessor;
import ui.BoardViewerServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Repeatable builds, recorded phase execution, bounded games, and viewer history.
 */
public final class GameRunnerSelfCheck {

    private static final UUID ID = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static int checks;

    private GameRunnerSelfCheck() { }

    public static void main(String[] args) throws Exception {
        buildIdentities();
        recordedPhases();
        endings();
        repeatedGame();
        invalidSettings();
        adjustmentFixtures();
        System.out.println("GameRunnerSelfCheck passed: " + checks + " checks");
    }

    private static void buildIdentities() {
        UnitId unit = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState fall = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(unit, Province.Pic),
                        Map.of(Province.Par, Nation.FRANCE, Province.Mar, Nation.FRANCE)));
        Game setup = options(1908, 7, AgentSettings.Method.PRIOR).createGame(fall);
        setup.resolveMovement(List.of());
        GameState winter = setup.snapshot();
        Game first = Game.fromState(winter), second = Game.fromState(winter);
        BuildOrder build = new BuildOrder(Nation.FRANCE, UnitType.ARMY, Province.Par);
        first.resolveAdjustments(List.of(build));
        second.resolveAdjustments(List.of(build));
        require(first.snapshot().equals(second.snapshot()), "Repeated winter builds must preserve exact identities");
        UnitId built = first.board().locations().keySet().stream().filter(value -> !value.equals(unit))
                .findFirst().orElseThrow();
        require(!built.equals(unit), "Built identity must differ from the original unit");
        require(!built.equals(UnitId.newlyBuilt(1908, Nation.FRANCE, UnitType.ARMY, Province.Par)),
                "A later rebuild must have its own identity");
        require(!built.equals(UnitId.newlyBuilt(1907, Nation.FRANCE, UnitType.FLEET, Province.Bre)),
                "Build type and site must contribute to identity");
        expect(IllegalArgumentException.class,
                () -> UnitId.newlyBuilt(1900, Nation.FRANCE, UnitType.ARMY, Province.Par));
        expect(NullPointerException.class, () -> UnitId.newlyBuilt(1907, null, UnitType.ARMY, Province.Par));

        Game trial = Game.fromState(winter);
        ActionCandidate action = new ActionCandidate(Nation.FRANCE, List.of(build), 0,
                ActionCandidate.Source.HEURISTIC);
        Simulator simulator = new Simulator();
        GameState a = simulator.peek(trial, Map.of(Nation.FRANCE, action));
        GameState b = simulator.peek(trial, Map.of(Nation.FRANCE, action));
        require(a.equals(b) && a.equals(first.snapshot()), "Peeks and real builds must agree on identities");
        require(trial.snapshot().equals(winter) && trial.getSnapshot() == null,
                "Build trials must restore the winter root");
    }

    private static void recordedPhases() {
        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId german = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        UnitId support = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur, german, Province.Mun, support, Province.Ruh),
                        Map.of(Province.Par, Nation.FRANCE, Province.Mar, Nation.FRANCE,
                                Province.Mun, Nation.GERMANY)));
        Game game = options(1908, 7, AgentSettings.Method.PRIOR).createGame(root);
        GameRecordBuilder archive = new GameRecordBuilder(ID, "recorded-test", game);
        Simulator simulator = new Simulator();
        Map<Nation, ActionCandidate> moves = Map.of(
                Nation.FRANCE, action(Nation.FRANCE, Order.hold(french)),
                Nation.GERMANY, action(Nation.GERMANY, Order.move(german, Province.Bur),
                        Order.supportMove(support, Province.Mun, Province.Bur)));
        expect(IllegalArgumentException.class, () -> simulator.advance(archive,
                Map.of(Nation.FRANCE, moves.get(Nation.FRANCE)), moment(root)));
        expect(IllegalArgumentException.class, () -> simulator.advance(archive, moves,
                new Moment(1906, GamePhase.FALL_MOVEMENT, Instant.EPOCH)));
        require(archive.resolvedPhases().isEmpty() && game.snapshot().equals(root),
                "Invalid submissions must not advance or record a phase");
        GameState retreat = simulator.advance(archive, moves, moment(root));
        require(retreat instanceof GameState_Retreats, "Fixture must expose Fall retreats");
        GameState winter = simulator.advance(archive,
                Map.of(Nation.FRANCE, action(Nation.FRANCE), Nation.GERMANY, action(Nation.GERMANY)),
                moment(retreat));
        require(winter instanceof GameState_Adjustments, "Retreats must finish before winter");
        GameState spring = simulator.advance(archive, Map.of(
                        Nation.FRANCE, action(Nation.FRANCE, new BuildOrder(Nation.FRANCE, UnitType.ARMY, Province.Par)),
                        Nation.GERMANY, action(Nation.GERMANY, new DisbandOrder(Nation.GERMANY, support))),
                moment(winter));
        GameRecord record = archive.build();
        require(record.resolvedPhases().size() == 3, "Each actual phase must be archived exactly once");
        require(record.resolvedPhases().get(0) instanceof MovementPhaseRecord
                && record.resolvedPhases().get(1) instanceof RetreatPhaseRecord
                && record.resolvedPhases().get(2) instanceof AdjustmentPhaseRecord, "Typed records are missing");
        require(spring.moment().equals(new GameMoment(1908, GamePhase.SPRING_MOVEMENT)),
                "Recorded execution advanced twice");
        require(record.latestBoard().equals(spring.board()), "Archive and live board differ");
        require(root.board().locations().get(french) == Province.Bur, "The original root changed");

        List<GameMoment> observed = new ArrayList<>();
        GameRunner.Result fromRetreat = new GameRunner().run(ID, retreat,
                options(1907, 7, AgentSettings.Method.PRIOR), phase -> observed.add(phase.gameMoment()));
        require(observed.equals(List.of(retreat.moment())), "Runner must resolve the pending retreat once");
        require(fromRetreat.state() instanceof GameState_Adjustments, "Cutoff must wait for Fall retreats");
    }

    private static void endings() {
        Map<Province, Nation> owners = new EnumMap<>(Province.class);
        for (Province province : Province.values())
            if (province.supplyCenter && province == Province.canonical(province) && province != Province.Bel
                    && owners.size() < 17)
                owners.put(province, Nation.FRANCE);
        owners.put(Province.Bel, Nation.ENGLAND);
        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState initial = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bel), owners));
        GameRunner.Result solo = new GameRunner().run(ID, initial, options(1907, 7, AgentSettings.Method.PRIOR));
        require(solo.reason() == GameRunner.EndReason.SOLO && solo.winner().orElseThrow() == Nation.FRANCE,
                "A solo must take precedence over the year limit");
        require(solo.record().resolvedPhases().size() == 1, "Solo must stop before winter adjustments");
        require(solo.scores().get(Nation.FRANCE) == 1.0 && solo.scores().values().stream()
                .mapToDouble(Double::doubleValue).sum() == 1.0, "Solo reward scale changed");
        GameRunner.Result alreadyEnded = new GameRunner().run(ID, solo.state(), options(1907, 7, AgentSettings.Method.PRIOR));
        require(alreadyEnded.record().resolvedPhases().isEmpty(), "Terminal input must not play another phase");

        GameState opening = StandardGameFactory.create1901().snapshot();
        GameRunner.Result cutoff = new GameRunner().run(ID, opening, options(1901, 7, AgentSettings.Method.PRIOR));
        require(cutoff.reason() == GameRunner.EndReason.YEAR_LIMIT && cutoff.winner().isEmpty(),
                "A bounded game is not a solo or an agreed draw");
        require(cutoff.state().moment().equals(new GameMoment(1901, GamePhase.WINTER_ADJUSTMENT)),
                "Year limit must stop after Fall ownership updates");
        require(cutoff.record().resolvedPhases().size() == 2, "Empty retreats must remain skipped");
    }

    private static void repeatedGame() throws Exception {
        Map<Nation, AgentSettings> agents = new EnumMap<>(Nation.class);
        AgentSettings.Method[] methods = AgentSettings.Method.values();
        for (Nation nation : Nation.values())
            agents.put(nation, new AgentSettings(methods[nation.ordinal() % methods.length],
                    3, 2, 1, 3, PolicyOutput.AVERAGE));
        GameRunner.Options settings = new GameRunner.Options(1902, 17, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, agents);
        agents.clear();
        GameState initial = StandardGameFactory.create1901().snapshot();
        List<ResolvedPhaseRecord> observed = new ArrayList<>();
        GameRunner runner = new GameRunner();
        GameRunner.Result first = runner.run(ID, initial, settings, observed::add);
        GameRunner.Result second = runner.run(ID, initial, settings);
        require(observed.equals(first.record().resolvedPhases()), "Observer must receive exactly the archived phases");
        require(first.state().board().equals(second.state().board()), "Seeded games must reproduce unit identities and ownership");
        require(first.scores().equals(second.scores()), "Seeded final scores differ");
        require(initial.equals(StandardGameFactory.create1901().snapshot()), "Run changed its initial state");
        require(first.record().resolvedPhases().stream().anyMatch(phase -> phase instanceof AdjustmentPhaseRecord),
                "Repeated-game check must cross a winter");
        compareRecords(first.record(), second.record());
        expect(UnsupportedOperationException.class, () -> settings.agents().clear());
        expect(UnsupportedOperationException.class, () -> first.record().resolvedPhases().clear());
        expect(UnsupportedOperationException.class, () -> first.scores().clear());

        try (BoardViewerServer a = new BoardViewerServer(settings.createGame(first.state()), first.record(), 0);
             BoardViewerServer b = new BoardViewerServer(settings.createGame(second.state()), second.record(), 0);
             HttpClient client = HttpClient.newHttpClient()) {
            a.start();
            b.start();
            String history = get(client, a, "/api/history");
            require(history.equals(get(client, b, "/api/history")), "Seeded games must reproduce the complete viewer history");
            require(history.startsWith("{\"snapshots\":[")
                            && history.contains("After WINTER ADJUSTMENT 1901")
                            && history.split("\\{\"label\":", -1).length - 1
                            == first.record().resolvedPhases().size() + 1,
                    "History must include the initial board and every resolved phase");
            require(get(client, a, "/api/board").equals(get(client, b, "/api/board")),
                    "Final board payloads differ");
            require(get(client, a, "/").contains("previous-turn") && get(client, a, "/ui/viewer.js").contains("ArrowRight"),
                    "Replay controls must be served");
            require(get(client, a, "/ui/maps/standard.svg").contains("<svg"), "Map asset is missing");
        }
    }

    private static void compareRecords(GameRecord first, GameRecord second) {
        require(first.id().equals(second.id()) && first.rulesetId().equals(second.rulesetId()),
                "Record identity or adjudication metadata changed");
        require(first.resolvedPhases().size() == second.resolvedPhases().size(), "Phase counts differ");
        for (int index = 0; index < first.resolvedPhases().size(); index++) {
            ResolvedPhaseRecord a = first.resolvedPhases().get(index), b = second.resolvedPhases().get(index);
            require(a.resolvedAt().equals(b.resolvedAt()), "Logical phase timestamps differ");
            require(a.boardBefore().equals(b.boardBefore()) && a.boardAfter().equals(b.boardAfter()),
                    "Recorded boards differ");
            switch (a) {
                case MovementPhaseRecord movement -> require(movement.submittedOrders().equals(
                        ((MovementPhaseRecord) b).submittedOrders()), "Movement submissions differ");
                case RetreatPhaseRecord retreat -> require(retreat.submittedOrders().equals(
                        ((RetreatPhaseRecord) b).submittedOrders()), "Retreat submissions differ");
                case AdjustmentPhaseRecord adjustment -> require(adjustment.submittedOrders().equals(
                        ((AdjustmentPhaseRecord) b).submittedOrders()), "Adjustment submissions differ");
            }
        }
    }

    private static void invalidSettings() {
        expect(IllegalArgumentException.class, () -> AgentSettings.paper(0, 1));
        expect(IllegalArgumentException.class, () -> AgentSettings.paper(1, 0));
        expect(NullPointerException.class, () -> new AgentSettings(null, 1, 1, 1, 1, PolicyOutput.FINAL));
        expect(IllegalArgumentException.class, () -> new GameRunner.Options(1901, 7, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, Map.of()));
        GameRunner.Options settings = options(1901, 7, AgentSettings.Method.PRIOR);
        GameState later = new GameState_Movement(new GameMoment(1902, GamePhase.SPRING_MOVEMENT),
                StandardGameFactory.create1901().board());
        expect(IllegalArgumentException.class, () -> new GameRunner().run(ID, later, settings));
        expect(NullPointerException.class, () -> new GameRunner().run(null, later, settings));
        IllegalStateException failure = new IllegalStateException("Observer failure");
        require(expect(IllegalStateException.class, () -> new GameRunner().run(ID,
                        StandardGameFactory.create1901().snapshot(), settings, phase -> { throw failure; })) == failure,
                "Observer failures must propagate without a success result");
    }

    private static void adjustmentFixtures() {
        AdjustmentActionTestCase actions = new AdjustmentActionTestCase();
        actions.eval(false);
        require(actions.getScore() == actions.getSize(), actions.getEval());
        for (var test : new DATCProcessorFileParser().parseManyFiles()) {
            test.eval(false);
            require(test.getScore() == test.getSize(), test.getEval());
        }
    }

    private static GameRunner.Options options(int lastYear, long seed, AgentSettings.Method method) {
        Map<Nation, AgentSettings> agents = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            agents.put(nation, new AgentSettings(method, 1, 1, 1, 1, PolicyOutput.AVERAGE));
        return new GameRunner.Options(lastYear, seed, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, agents);
    }

    private static Moment moment(GameState state) {
        return new Moment(state.moment().year(), state.moment().gamePhase(), Instant.EPOCH);
    }

    private static ActionCandidate action(Nation nation, contracts.OrderForm... orders) {
        return new ActionCandidate(nation, List.of(orders), 0, ActionCandidate.Source.HEURISTIC);
    }

    private static String get(HttpClient client, BoardViewerServer server, String path) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.address().getPort() + path))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 200, "Viewer request failed: " + path);
        return response.body();
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }

    private static RuntimeException expect(Class<? extends RuntimeException> type, Runnable operation) {
        checks++;
        try {
            operation.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception))
                return exception;
            throw new AssertionError("Unexpected exception", exception);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

}