package testing.selfcheck;

import agents.generation.*;
import agents.play.Agent;
import agents.play.GameRunner;
import contracts.OrderForm;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.*;
import game.record.*;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.adjustments.BuildOrder;
import phase.retreats.RetreatOrder;
import ui.NativeGameReplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Standalone recorded-agent-game checks. Run without -ea.
 *
 * <p>An optional output directory receives the forced-retreat/build/disband
 * fixture as a browser bundle.</p>
 */
public final class AgentGameSelfCheck {

    private static int checks;
    private static final long SEED = 7389L;

    private AgentGameSelfCheck() { }

    public static void main(String[] args) throws Exception {
        GameRunner.Result fixture = recordedPhases();
        soloAfterRetreats();
        invalidActions();
        invalidArguments();
        bundle(fixture);
        if (args.length == 1)
            NativeGameReplay.writeBundle(Path.of(args[0]), Path.of("src/resources/ui"),
                    fixture, ScoringSystem.SUM_OF_SQUARES, Map.of("searchPower", "FRANCE", "seed", Long.toString(SEED)));
        System.out.println("AgentGameSelfCheck passed: " + checks + " checks");
    }

    private static GameRunner.Result recordedPhases() {
        UnitId french = unit(Nation.FRANCE, Province.Par);
        UnitId english = unit(Nation.ENGLAND, Province.Lon);
        UnitId german = unit(Nation.GERMANY, Province.Ber);
        BoardState board = new BoardState(Map.of(french, Province.Bur, english, Province.Ruh, german, Province.Mun),
                Map.of(Province.Par, Nation.FRANCE, Province.Lon, Nation.ENGLAND, Province.Mun, Nation.GERMANY));
        Game game = new Game(1901, board);
        Map<Nation, Agent> agents = agents((state, nation, seed) -> {
            require(game.snapshot().equals(state), "Every decision must see the unadvanced real game");
            if (state.moment().equals(new GameMoment(1901, GamePhase.SPRING_MOVEMENT)))
                return switch (nation) {
                    case FRANCE -> action(nation, Order.move(french, Province.Mun));
                    case ENGLAND -> action(nation, Order.supportMove(english, Province.Bur, Province.Mun));
                    case GERMANY -> action(nation, Order.hold(german));
                    default -> throw new AssertionError("Eliminated nations must not be asked to act");
                };
            if (state instanceof GameState_Retreats)
                return nation == Nation.GERMANY ? action(nation, new RetreatOrder(german, Province.Boh)) : action(nation);
            if (state instanceof GameState_Adjustments && nation == Nation.FRANCE)
                return action(nation, new BuildOrder(nation, UnitType.ARMY, Province.Par));
            return baseline(state, nation, seed);
        });
        List<GameState> completed = new ArrayList<>();
        GameRunner.Result result = GameRunner.play(game, agents, UUID.randomUUID(), "fixture", 1902, SEED, completed::add);
        require(result.endReason() == GameRunner.EndReason.CUTOFF, "The year limit is a cutoff");
        require(result.finalState().moment().equals(new GameMoment(1902, GamePhase.WINTER_ADJUSTMENT)),
                "Stop at winter before final-year builds");
        List<ResolvedPhaseRecord> phases = result.record().resolvedPhases();
        require(phases.size() == 6 && completed.size() == 6, "Resolve four movements, one retreat, and one winter");
        require(phases.get(1) instanceof RetreatPhaseRecord, "Expose actual dislodgements");
        require(phases.get(3) instanceof AdjustmentPhaseRecord, "Record intermediate-year winter");
        require(result.finalState().board().ownerOf(Province.Mun) == Nation.FRANCE, "Apply completed-Fall ownership");
        require(!result.finalState().board().locations().containsKey(german), "Process the required winter disband");
        require(result.finalState().board().locations().values().contains(Province.Par), "Process the winter build");
        require(result.decisionSeeds().get(1).containsKey(Nation.GERMANY), "A dislodged nation still chooses");
        require(!result.decisionSeeds().get(4).containsKey(Nation.GERMANY), "An eliminated nation no longer chooses");
        Random expected = new Random(SEED);
        for (int index = 0; index < phases.size(); index++) {
            ResolvedPhaseRecord phase = phases.get(index);
            require(phase.boardAfter().equals(completed.get(index).board()), "Archive the real resolution exactly once");
            require(index == 0 || phase.boardBefore().equals(phases.get(index - 1).boardAfter()), "Record continuity");
            for (Nation nation : Nation.values())
                if (result.decisionSeeds().get(index).containsKey(nation))
                    require(result.decisionSeeds().get(index).get(nation) == expected.nextLong(), "Persist exact child seeds");
        }
        require(game.getSnapshot() == null, "Recorded play must not leave a speculative checkpoint");
        require(result.record().latestGameMoment().gamePhase() == GamePhase.FALL_MOVEMENT,
                "Resolved-record time differs from the final current time");
        expect(UnsupportedOperationException.class, () -> result.decisionSeeds().getFirst().clear());
        String json = NativeGameReplay.toJson(result, ScoringSystem.SUM_OF_SQUARES,
                Map.of("seed", Long.toString(SEED), "escaped", "quotes \" and newline\n"));
        require(json.contains("\"type\":\"RETREAT\",\"unitType\":\"ARMY\",\"origin\":\"Mun\""),
                "Retreat origin must be actual dislodged position, not creation province Ber");
        require(json.contains("\"type\":\"DESTROY\",\"unitType\":\"ARMY\",\"origin\":\"Boh\""),
                "Disband origin must be winter position, not creation province Ber");
        require(json.contains("\"type\":\"BUILD\",\"unitType\":\"ARMY\",\"origin\":\"Par\""), "Preserve builds");
        require(json.contains("\"dislodgedUnits\":[{\"nation\":\"GERMANY\",\"type\":\"ARMY\",\"province\":\"Mun\"}]"),
                "Dislodged units remain separate from the occupying board");
        require(json.contains("\"status\":\"FINAL\",\"board\":{\"year\":1902,\"phase\":\"WINTER_ADJUSTMENT\""),
                "Persist the actual final phase");
        require(json.contains("quotes \\\" and newline\\n"), "Escape replay strings");
        require(!json.contains("DIPLOBN"), "Native records must not pretend to be imported");
        return result;
    }

    private static void soloAfterRetreats() {
        Map<Province, Nation> owners = new EnumMap<>(Province.class);
        for (Province province : Province.values()) {
            if (province.supplyCenter && province == Province.canonical(province) && province != Province.Mun
                    && owners.size() < 17)
                owners.put(province, Nation.FRANCE);
        }
        require(owners.size() == 17, "Solo fixture has seventeen distinct French centers");
        owners.put(Province.Mun, Nation.GERMANY);
        UnitId french = unit(Nation.FRANCE, Province.Par);
        UnitId support = unit(Nation.FRANCE, Province.Mar);
        UnitId german = unit(Nation.GERMANY, Province.Ber);
        GameState root = new GameState_Movement(new GameMoment(1901, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur, support, Province.Ruh, german, Province.Mun), owners));
        Game game = Game.fromState(root);
        List<GameState> reached = new ArrayList<>();
        GameRunner.Result result = GameRunner.play(game, agents((state, nation, seed) -> {
            if (state instanceof GameState_Movement && nation == Nation.FRANCE)
                return action(nation, Order.move(french, Province.Mun), Order.supportMove(support, Province.Bur, Province.Mun));
            return baseline(state, nation, seed);
        }), UUID.randomUUID(), "fixture", 1901, SEED, reached::add);
        require(reached.size() == 2 && reached.getFirst() instanceof GameState_Retreats,
                "Play Fall retreats before deciding a solo");
        require(GameRunner.winner(reached.getFirst().board()).isEmpty(), "Pending Fall capture is not yet a solo");
        require(result.endReason() == GameRunner.EndReason.SOLO, "Solo takes priority over cutoff");
        require(GameRunner.winner(result.finalState().board()).orElseThrow() == Nation.FRANCE, "Identify winner");
        String json = NativeGameReplay.toJson(result, ScoringSystem.SUM_OF_SQUARES, Map.of());
        require(json.contains("\"endReason\":\"SOLO\",\"winner\":\"FRANCE\""), "Persist explicit solo metadata");
        require(json.contains("\"FRANCE\":100.0"), "Solo receives the native scoring award");
        GameRunner.Result stopped = GameRunner.play(game, agents((state, nation, seed) -> {
            throw new AssertionError("A finished position must not request another action");
        }), UUID.randomUUID(), "fixture", 1901, SEED, state -> { });
        require(stopped.record().resolvedPhases().isEmpty(), "Already-terminal position stays unchanged");
    }

    private static void invalidActions() {
        Game game = StandardGameFactory.create1901();
        GameState before = game.snapshot();
        Map<Nation, Agent> choices = agents(AgentGameSelfCheck::baseline);
        Nation last = Nation.values()[Nation.values().length - 1];
        choices.put(last, (state, nation, seed) -> action(nation));
        expect(IllegalStateException.class, () -> play(game, choices));
        require(before.equals(game.snapshot()) && game.processorHistory().isEmpty(),
                "Even the last nation's invalid submission must not partially resolve the phase");
        choices.put(last, (state, nation, seed) -> action(Nation.FRANCE));
        expect(IllegalStateException.class, () -> play(game, choices));
        choices.put(last, (state, nation, seed) -> null);
        expect(NullPointerException.class, () -> play(game, choices));
        choices.put(last, (state, nation, seed) -> { throw new IllegalArgumentException("agent failure"); });
        expect(IllegalArgumentException.class, () -> play(game, choices));
        choices.remove(last);
        expect(IllegalArgumentException.class, () -> play(game, choices));
        require(before.equals(game.snapshot()) && game.processorHistory().isEmpty(), "Failed decisions leave no real phase");
        game.takeSnapshot();
        expect(IllegalStateException.class, () -> play(game, agents(AgentGameSelfCheck::baseline)));
        game.restoreFromSnapshot();
        expect(IllegalArgumentException.class, () -> GameRunner.play(game, agents(AgentGameSelfCheck::baseline),
                UUID.randomUUID(), "fixture", 1900, SEED, state -> { }));
    }

    private static void invalidArguments() throws Exception {
        for (String[] arguments : List.of(new String[] { "--output" },
                new String[] { "--output", "--serve" }, new String[] { "--iterations", "0" },
                new String[] { "--replay", "unused", "--seed", "42" }, new String[] { "--port", "9855" })) {
            boolean refused = false;
            try {
                _app.AgentGameApp.main(arguments);
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            require(refused, "Reject incomplete, invalid, or incompatible command-line options before play");
        }
    }

    private static void bundle(GameRunner.Result result) throws Exception {
        Path parent = Files.createTempDirectory("jreferee-agent-replay-test-");
        Path output = parent.resolve("replay");
        try {
            NativeGameReplay.writeBundle(output, Path.of("src/resources/ui"), result, ScoringSystem.SUM_OF_SQUARES, Map.of());
            require(Files.readString(output.resolve("index.html")).contains("data-source=\"native\""), "Export native mode");
            require(Files.isRegularFile(output.resolve("ui/maps/standard.svg")), "Export the map asset");
            require(Files.readString(output.resolve("replay.json")).contains("\"source\":\"NATIVE\""), "Persist native replay");
            boolean refused = false;
            try {
                NativeGameReplay.writeBundle(output, Path.of("src/resources/ui"), result, ScoringSystem.SUM_OF_SQUARES, Map.of());
            } catch (java.nio.file.FileAlreadyExistsException expected) {
                refused = true;
            }
            require(refused, "Do not overwrite an existing bundle");
        } finally {
            try (var files = Files.walk(parent)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void play(Game game, Map<Nation, Agent> agents) {
        GameRunner.play(game, agents, UUID.randomUUID(), "fixture", 1901, SEED, state -> { });
    }

    private static ActionCandidate baseline(GameState state, Nation nation, long seed) {
        var catalog = new LegalOrderGenerator().generate(state, nation);
        return new UniformActionGenerator().generate(new ActionGenerationRequest(state, nation, 1, seed), catalog).getFirst();
    }

    private static Map<Nation, Agent> agents(Agent agent) {
        Map<Nation, Agent> result = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values()) result.put(nation, agent);
        return result;
    }

    private static UnitId unit(Nation nation, Province origin) {
        return new UnitId(UUID.randomUUID(), nation, UnitType.ARMY, origin);
    }

    private static ActionCandidate action(Nation nation, OrderForm... orders) {
        return new ActionCandidate(nation, List.of(orders), 0.0, ActionCandidate.Source.EXPLORATION);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable action) {
        checks++;
        try {
            action.run();
        } catch (RuntimeException failure) {
            if (type.isInstance(failure)) return;
            throw failure;
        }
        throw new AssertionError("Expected " + type.getName());
    }

}
