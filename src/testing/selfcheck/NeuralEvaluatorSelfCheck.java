package testing.selfcheck;

import agents.evaluation.*;
import agents.execution.*;
import agents.generation.*;
import agents.search.PolicyOutput;
import agents.simulation.Simulator;
import domain.*;
import game.*;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;

import java.nio.file.Path;
import java.util.*;

/**
 * Neural leaves, exact boundaries, retreat continuation and legacy defaults.
 */
public final class NeuralEvaluatorSelfCheck {

    private NeuralEvaluatorSelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 1)
            throw new IllegalArgumentException("Usage: NeuralEvaluatorSelfCheck <value-model.bin>");
        NeuralValueModel model = NeuralValueModel.load(Path.of(args[0]));
        AgentSettings baseline = AgentSettings.paper(2, 2);
        AgentSettings neural = baseline.withValueModel(model);
        GameRunner.Options options = options(neural, 1904, ScoringSystem.SUM_OF_SQUARES);
        Game live = StandardGameFactory.create1901();
        GameState opening = live.snapshot();
        Simulator simulator = new Simulator();

        GameState winter = opening;
        Game worker = options.createGame(opening);
        while (!(winter instanceof GameState_Adjustments))
            winter = simulator.advance(worker, holds(winter));
        GameState leaf = winter;

        require(neural.createEvaluator(options, 7).evaluate(leaf)
                        .equals(model.predict(leaf.moment(), leaf.board())),
                "Nonterminal winter did not use the model");
        require(neural.createEvaluator(options(neural, 1901, ScoringSystem.SUM_OF_SQUARES), 7)
                        .evaluate(leaf).equals(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES).evaluate(leaf)),
                "Final-year cutoff was not scored exactly");

        Evaluator old = new RolloutEvaluator(new ScoringEvaluator(options.scoring()),
                baseline.rolloutGenerator().create(), options::createGame, 1, 2, 7);
        require(baseline.createEvaluator(options, 7).evaluate(opening).equals(old.evaluate(opening)),
                "Default evaluator behavior changed");
        require(baseline.valueModel().isEmpty() && !baseline.toString().contains("valueModel=")
                        && neural.toString().contains(model.sha256()),
                "Configuration identity did not distinguish neural mode");
        expect(() -> neural.createEvaluator(options(neural, 1904, ScoringSystem.DRAW_SIZE), 7));
        expect(() -> new AgentSettings(AgentSettings.Method.PRIOR, 2, 2, 1, 2,
                PolicyOutput.AVERAGE).withValueModel(model));

        Map<Province, Nation> centers = new EnumMap<>(Province.class);
        for (Province province : Province.values())
            if (province.parent == null && province.supplyCenter && centers.size() < 18)
                centers.put(province, Nation.FRANCE);
        GameState solo = new GameState_Movement(new GameMoment(1903, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(), centers));
        NeuralRolloutEvaluator exact = new NeuralRolloutEvaluator(model, 1904,
                (request, catalog) -> { throw new AssertionError("Solo generated orders"); },
                state -> { throw new AssertionError("Solo created a rollout game"); }, 1, 1, 7);
        require(exact.evaluate(solo).equals(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES).evaluate(solo)),
                "Solo was not scored exactly");

        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Bur);
        UnitId german = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        UnitId support = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Ruh);
        GameState battle = new GameState_Movement(new GameMoment(1903, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur, german, Province.Mun, support, Province.Ruh),
                        Map.of(Province.Par, Nation.FRANCE, Province.Mun, Nation.GERMANY)));
        Game battleGame = options.createGame(battle);
        GameState retreat = simulator.advance(battleGame, Map.of(
                Nation.FRANCE, action(Nation.FRANCE, List.of(Order.hold(french))),
                Nation.GERMANY, action(Nation.GERMANY, List.of(
                        Order.move(german, Province.Bur),
                        Order.supportMove(support, Province.Mun, Province.Bur)))));
        require(retreat instanceof GameState_Retreats, "Fixture did not create pending retreats");
        Game expectedGame = options.createGame(retreat);
        Map<Nation, ActionCandidate> disbands = new EnumMap<>(Nation.class);
        for (Nation nation : retreat.aliveNations())
            disbands.put(nation, action(nation, List.of()));
        GameState afterRetreat = simulator.advance(expectedGame, disbands);
        require(afterRetreat instanceof GameState_Adjustments, "Retreat did not finish the Fall turn");

        Game[] captured = new Game[1];
        NeuralRolloutEvaluator resolving = new NeuralRolloutEvaluator(model, 1904,
                (request, catalog) -> List.of(action(request.power(), List.of())),
                state -> captured[0] = options.createGame(state), 1, 1, 7);
        require(resolving.evaluate(retreat).equals(
                        model.predict(afterRetreat.moment(), afterRetreat.board())),
                "Pending retreat was not resolved before inference");
        require(captured[0].snapshot().equals(retreat) && captured[0].getSnapshot() == null
                        && captured[0].processorHistory().isEmpty(),
                "Continuation game was not restored");
        require(live.snapshot().equals(opening) && live.getSnapshot() == null,
                "Original game was modified");

        AgentSettings small = AgentSettings.paper(1, 1).withValueModel(model);
        int[] decisions = {0};
        GameRunner.Result run = new GameRunner().run(new UUID(0, 7), opening,
                options(small, 1902, ScoringSystem.SUM_OF_SQUARES), phase -> { }, decision -> {
                    decisions[0]++;
                    require(decision.settings().valueModel().orElseThrow() == model
                                    && decision.searchResult().isPresent(),
                            "Search did not retain the shared value model");
                });
        require(decisions[0] > 0 && run.reason() == GameRunner.EndReason.YEAR_LIMIT
                && run.scores().equals(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES)
                .evaluate(run.state())), "Recorded runner integration or terminal scores differ");
        System.out.println("NeuralEvaluatorSelfCheck passed; model=" + model.sha256());

    }

    private static Map<Nation, ActionCandidate> holds(GameState state) {
        Map<Nation, ActionCandidate> result = new EnumMap<>(Nation.class);
        UniformActionGenerator generator = new UniformActionGenerator();
        for (Nation nation : state.aliveNations())
            result.put(nation, generator.generate(new ActionGenerationRequest(state, nation, 1, 7),
                    new LegalOrderGenerator().generate(state, nation)).getFirst());
        return result;
    }

    private static ActionCandidate action(Nation nation, List<? extends contracts.OrderForm> orders) {
        return new ActionCandidate(nation, new ArrayList<>(orders), 0, ActionCandidate.Source.HEURISTIC);
    }

    private static GameRunner.Options options(AgentSettings settings, int lastYear, ScoringSystem scoring) {
        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            seats.put(nation, settings);
        return new GameRunner.Options(lastYear, 7, scoring,
                MovementProcessor.Policy.JUDGE, 1, 0, seats);
    }

    private static void expect(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}