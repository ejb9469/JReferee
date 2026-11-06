package testing.selfcheck;

import agents.evaluation.Evaluator;
import agents.evaluation.SCEvaluator;
import agents.generation.ActionCandidate;
import agents.simulation.Simulator;
import contracts.OrderForm;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.*;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.adjustments.BuildOrder;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;

import java.util.*;

/**
 * Standalone supply-center evaluation checks. Run without -ea.
 */
public final class SCEvaluatorSelfCheck {

    private static final Evaluator EVALUATOR = new SCEvaluator();
    private static final Simulator SIMULATOR = new Simulator();

    private static int checks;

    private SCEvaluatorSelfCheck() { }

    public static void main(String[] args) {
        startingPosition();
        ownership();
        scoreRange();
        phaseTiming();
        retreats();
        expect(NullPointerException.class, () -> EVALUATOR.evaluate(null));
        System.out.println("SCEvaluatorSelfCheck passed: " + checks + " checks");
    }

    private static void startingPosition() {
        Game game = StandardGameFactory.create1901();
        GameState state = game.snapshot();
        game.takeSnapshot();
        GameState saved = game.getSnapshot();
        Map<Nation, Double> scores = evaluate(state);
        for (Nation nation : Nation.values())
            require(scores.get(nation) == (nation == Nation.RUSSIA ? 4.0 : 3.0),
                    "Starting scores must equal starting center counts");
        require(scores.values().stream().mapToDouble(Double::doubleValue).sum() == 22.0,
                "Neutral centers must not be awarded to any nation");
        require(new ArrayList<>(scores.keySet()).equals(Arrays.asList(Nation.values())),
                "Scores must follow nation order");
        expect(UnsupportedOperationException.class, () -> scores.put(Nation.FRANCE, 99.0));
        expect(UnsupportedOperationException.class, () -> scores.entrySet().iterator().next().setValue(99.0));
        require(game.snapshot().equals(state) && game.getSnapshot() == saved,
                "Evaluation must leave both the game and its saved checkpoint unchanged");
        game.restoreFromSnapshot();
    }

    private static void ownership() {
        UnitId french = army(Nation.FRANCE, Province.Par);
        UnitId german = army(Nation.GERMANY, Province.Mun);
        Map<Province, Nation> owners = new LinkedHashMap<>();
        owners.put(Province.StpNC, Nation.RUSSIA);
        owners.put(Province.StpSC, Nation.RUSSIA);
        owners.put(Province.Stp, Nation.RUSSIA);
        owners.put(Province.SpaNC, Nation.FRANCE);
        owners.put(Province.SpaSC, Nation.FRANCE);
        owners.put(Province.BulEC, Nation.TURKEY);
        owners.put(Province.BulSC, Nation.TURKEY);
        owners.put(Province.Par, Nation.FRANCE);
        GameState state = movement(GamePhase.SPRING_MOVEMENT,
                new BoardState(Map.of(french, Province.Bur, german, Province.Par), owners));
        Map<Nation, Double> scores = evaluate(state);
        require(scores.get(Nation.FRANCE) == 2.0, "Spain's coasts must count once alongside Paris");
        require(scores.get(Nation.RUSSIA) == 1.0, "St. Petersburg must count once");
        require(scores.get(Nation.TURKEY) == 1.0, "Bulgaria must count once");
        require(scores.get(Nation.GERMANY) == 0.0 && state.aliveNations().contains(Nation.GERMANY),
                "A centerless occupying unit must not receive the recorded owner's point");
        require(scores.get(Nation.ENGLAND) == 0.0 && !state.aliveNations().contains(Nation.ENGLAND),
                "Eliminated nations must retain zero-valued entries");

        Map<Province, Nation> reversed = new LinkedHashMap<>();
        for (Province province : new ArrayList<>(owners.keySet()).reversed())
            reversed.put(province, owners.get(province));
        GameState differentUnits = movement(GamePhase.FALL_MOVEMENT,
                new BoardState(Map.of(army(Nation.ITALY, Province.Rom), Province.Bel), reversed));
        require(evaluate(differentUnits).equals(scores),
                "Scores must depend only on ownership, independent of unit identities, positions, or map order");
        GameState unowned = movement(GamePhase.SPRING_MOVEMENT,
                new BoardState(Map.of(french, Province.Par), Map.of()));
        require(evaluate(unowned).values().stream().allMatch(score -> score == 0.0),
                "Occupying an unowned center must not preempt the Fall ownership update");
        require(evaluate(movement(GamePhase.SPRING_MOVEMENT, new BoardState(Map.of(), Map.of())))
                        .values().stream().allMatch(score -> score == 0.0),
                "An empty position must return all zeros");
    }

    private static void scoreRange() {
        Set<Province> centers = EnumSet.noneOf(Province.class);
        for (Province province : Province.values())
            if (province.supplyCenter)
                centers.add(Province.canonical(province));
        require(centers.size() == 34, "The standard board must contain 34 distinct supply centers");
        Map<Province, Nation> owners = new EnumMap<>(Province.class);
        int count = 0;
        for (Province center : centers)
            owners.put(center, count++ < 18 ? Nation.FRANCE : Nation.GERMANY);
        Map<Nation, Double> scores = evaluate(movement(GamePhase.SPRING_MOVEMENT,
                new BoardState(Map.of(), owners)));
        require(scores.get(Nation.FRANCE) == 18.0 && scores.get(Nation.GERMANY) == 16.0,
                "The baseline must retain raw counts rather than assign terminal win/loss payoffs");
        owners.replaceAll((province, owner) -> Nation.FRANCE);
        Map<Nation, Double> all = evaluate(movement(GamePhase.SPRING_MOVEMENT,
                new BoardState(Map.of(), owners)));
        require(all.get(Nation.FRANCE) == 34.0 && all.get(Nation.GERMANY) == 0.0,
                "Owning the full map must score exactly 34");
        require(scores.get(Nation.FRANCE) == 18.0, "Earlier evaluation results must remain unchanged");
    }

    private static void phaseTiming() {
        UnitId french = army(Nation.FRANCE, Province.Par);
        Game game = game(movement(GamePhase.SPRING_MOVEMENT,
                new BoardState(Map.of(french, Province.Pic),
                        Map.of(Province.Par, Nation.FRANCE, Province.Bel, Nation.GERMANY))));
        Map<Nation, Double> before = evaluate(game.snapshot());
        GameState fall = peek(game, Map.of(
                Nation.FRANCE, action(Nation.FRANCE, Order.move(french, Province.Bel)),
                Nation.GERMANY, action(Nation.GERMANY)));
        require(fall.board().locationOf(french) == Province.Bel, "The Spring move must reach Belgium");
        require(evaluate(fall).equals(before), "Spring movement must retain recorded center scores");

        GameState winter = peek(game(fall), Map.of(
                Nation.FRANCE, action(Nation.FRANCE, Order.hold(french)),
                Nation.GERMANY, action(Nation.GERMANY)));
        require(winter instanceof GameState_Adjustments, "Fall without dislodgements must reach winter");
        Map<Nation, Double> captured = evaluate(winter);
        require(captured.get(Nation.FRANCE) == 2.0 && captured.get(Nation.GERMANY) == 0.0,
                "Completing Fall must credit the captor and remove the prior owner's point");
        require(before.get(Nation.FRANCE) == 1.0 && before.get(Nation.GERMANY) == 1.0,
                "An earlier score map must survive later evaluations");

        GameState spring = peek(game(winter), Map.of(Nation.FRANCE,
                action(Nation.FRANCE, new BuildOrder(Nation.FRANCE, UnitType.ARMY, Province.Par))));
        require(spring.board().locations().size() == 2, "The winter build must create another unit");
        require(evaluate(spring).equals(captured), "Winter builds must leave ownership scores unchanged");
    }

    private static void retreats() {
        UnitId french = army(Nation.FRANCE, Province.Bur);
        UnitId german = army(Nation.GERMANY, Province.Mun);
        UnitId support = army(Nation.GERMANY, Province.Ruh);
        Game game = game(movement(GamePhase.FALL_MOVEMENT,
                new BoardState(Map.of(french, Province.Bur, german, Province.Mun, support, Province.Ruh),
                        Map.of(Province.Par, Nation.FRANCE, Province.Bel, Nation.GERMANY,
                                Province.Mun, Nation.GERMANY))));
        Map<Nation, Double> before = evaluate(game.snapshot());
        GameState result = peek(game, Map.of(
                Nation.FRANCE, action(Nation.FRANCE, Order.hold(french)),
                Nation.GERMANY, action(Nation.GERMANY, Order.move(german, Province.Bur),
                        Order.supportMove(support, Province.Mun, Province.Bur))));
        if (!(result instanceof GameState_Retreats retreats))
            throw new AssertionError("Fixture must expose Fall retreats");
        require(!retreats.board().locations().containsKey(french), "The French unit must be awaiting retreat");
        require(evaluate(retreats).equals(before), "Pending Fall retreats must retain earlier ownership scores");
        Map<Nation, ActionCandidate> actions = Map.of(
                Nation.FRANCE, action(Nation.FRANCE, new RetreatOrder(french, Province.Bel)),
                Nation.GERMANY, action(Nation.GERMANY));
        GameState winter = peek(game(retreats), actions);
        Map<Nation, Double> captured = evaluate(winter);
        require(captured.get(Nation.FRANCE) == 2.0 && captured.get(Nation.GERMANY) == 1.0,
                "A completed Fall retreat into Belgium must transfer its point");

        GameState springRetreats = new GameState_Retreats(new GameMoment(1907, GamePhase.SPRING_RETREAT),
                retreats.board(), retreats.movementResult());
        require(evaluate(springRetreats).equals(before), "Spring retreat scores must use recorded ownership");
        require(evaluate(peek(game(springRetreats), actions)).equals(before),
                "A Spring retreat into Belgium must not transfer its point");
    }

    private static Map<Nation, Double> evaluate(GameState state) {
        Map<Nation, Double> scores = EVALUATOR.evaluate(state);
        require(scores.keySet().equals(EnumSet.allOf(Nation.class)), "Every nation must have a score");
        for (double score : scores.values())
            require(Double.isFinite(score) && score >= 0.0 && score <= 34.0 && score == Math.rint(score),
                    "Supply-center scores must be finite counts within the standard board's range");
        require(EVALUATOR.evaluate(state).equals(scores), "Repeated evaluations must agree");
        return scores;
    }

    private static GameState peek(Game game, Map<Nation, ActionCandidate> actions) {
        GameState before = game.snapshot();
        GameState next = SIMULATOR.peek(game, actions);
        evaluate(next);
        require(game.snapshot().equals(before) && game.getSnapshot() == null,
                "Evaluating a peek must leave the simulation game restored");
        return next;
    }

    private static GameState movement(GamePhase phase, BoardState board) {
        return new GameState_Movement(new GameMoment(1907, phase), board);
    }

    private static Game game(GameState state) {
        return Game.fromState(state,
                new MovementProcessor(MovementProcessor.Policy.SZYKMAN_JUSTICE, 4, 0L),
                new RetreatProcessor(), new AdjustmentProcessor());
    }

    private static UnitId army(Nation nation, Province origin) {
        return new UnitId(UUID.randomUUID(), nation, UnitType.ARMY, origin);
    }

    private static ActionCandidate action(Nation nation, OrderForm... orders) {
        return new ActionCandidate(nation, List.of(orders), 0.0, ActionCandidate.Source.HEURISTIC);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable operation) {
        checks++;
        try {
            operation.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception))
                return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + exception, exception);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

}