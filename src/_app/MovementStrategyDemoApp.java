package _app;

import analysis.MovementStrategy;
import analysis.OutcomeEvaluator;
import analysis.PredictionPolicy;
import analysis.RoutePreferences;
import domain.Nation;
import domain.Province;
import game.Game;
import game.GameMoment;
import game.GamePhase;
import game.Moment;
import game.StandardGameFactory;
import game.record.GameRecord;
import game.record.GameRecordBuilder;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.time.Instant;
import java.util.*;


public final class MovementStrategyDemoApp {

    private static final String RULESET = "synthetic-movement-strategy-demo";

    private MovementStrategyDemoApp() {  }


    public static void main(String[] args) {

        RoutePreferences preferences = new RoutePreferences();

        for (int index = 0; index < 3; index++)
            preferences.add(trainingGame());

        Game query = StandardGameFactory.create1901();

        MovementStrategy.Recommendation result = MovementStrategy.recommend(
                preferences,
                RULESET,
                new GameMoment(1901, GamePhase.SPRING_MOVEMENT),
                query.board(),
                Nation.ENGLAND,
                Map.of(),
                Map.of(Province.NTH, 1.0),
                new MovementStrategy.Configuration(
                        PredictionPolicy.ORIGINAL, 3, 4, 8, 32),
                new OutcomeEvaluator(1.0, 3.0, 0.5),
                new MovementProcessor(
                        MovementProcessor.Policy.JUSTICE, 1, 0xD1A10C4CL));

        System.out.println("Synthetic, corpus-free movement strategy demonstration");
        System.out.println("Status: " + result.status());
        System.out.println("Policy: " + result.policy());
        System.out.println("Candidate plans: " + result.candidatePlanCount());
        System.out.println("Opponent scenarios: " + result.opponentScenarioCount());
        System.out.println("Diagnostic: " + result.diagnostic());

        if (result.hasRecommendation())
            result.rankedPlans().forEach(System.out::println);

    }


    private static GameRecord trainingGame() {

        Game game = new Game(
                1901,
                StandardGameFactory.create1901().board(),
                new MovementProcessor(MovementProcessor.Policy.JUSTICE, 1, 1L),
                new RetreatProcessor(),
                new AdjustmentProcessor());

        GameRecordBuilder archive = new GameRecordBuilder(
                UUID.randomUUID(), RULESET, game);

        List<Order> holds = game.board().locations().keySet().stream()
                .map(Order::hold)
                .toList();

        archive.resolveMovement(
                holds,
                new Moment(1901, GamePhase.SPRING_MOVEMENT, Instant.EPOCH));

        return archive.build();

    }

}
