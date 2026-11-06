package testing.selfcheck;

import agents.evaluation.Evaluator;
import agents.evaluation.SCEvaluator;
import agents.evaluation.ScoringEvaluator;
import agents.generation.ActionCandidate;
import misc.scoring.ScoringSystem;
import agents.simulation.Simulator;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.*;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.util.*;

/**
 * Standalone final-score and scoring-evaluator checks. Run without -ea.
 */
public final class ScoringSystemSelfCheck {

    private static final List<Nation> FIXTURE_ORDER = List.of(
            Nation.AUSTRIA, Nation.ENGLAND, Nation.FRANCE, Nation.GERMANY,
            Nation.ITALY, Nation.RUSSIA, Nation.TURKEY);

    private static int checks;

    private ScoringSystemSelfCheck() { }

    public static void main(String[] args) {
        drawSizeAndSquares();
        ranksAndGap();
        solos();
        invalidInputs();
        properties();
        evaluation();
        System.out.println("ScoringSystemSelfCheck passed: " + checks + " checks");
    }

    private static void drawSizeAndSquares() {
        Map<Nation, Integer> counts = counts(0, 5, 3, 13, 3, 4, 6);
        checkScores(ScoringSystem.DRAW_SIZE, counts, 0, 100.0 / 6, 100.0 / 6,
                100.0 / 6, 100.0 / 6, 100.0 / 6, 100.0 / 6);
        checkScores(ScoringSystem.SUM_OF_SQUARES, counts, 0, 2500.0 / 264,
                900.0 / 264, 16900.0 / 264, 900.0 / 264, 1600.0 / 264, 3600.0 / 264);
        Map<Nation, Integer> neutrals = counts(3, 3, 3, 3, 3, 4, 3);
        Map<Nation, Double> rewards = ScoringSystem.SUM_OF_SQUARES.rewards(neutrals);
        close(rewards.get(Nation.RUSSIA), 16.0 / 70, "Starting SoS must use squared center counts");
        close(rewards.get(Nation.FRANCE), 9.0 / 70, "Neutral centers must not enter the denominator");
        checkScores(ScoringSystem.DRAW_SIZE, counts(0, 0, 1, 0, 0, 0, 0), 0, 0, 100, 0, 0, 0, 0);
    }

    private static void ranksAndGap() {
        Map<Nation, Integer> counts = counts(0, 5, 3, 13, 3, 4, 6);
        checkScores(ScoringSystem.C_DIPLO_100, counts, 1, 13, 4, 52, 4, 5, 21);
        checkScores(ScoringSystem.OPEN_MIND_THE_GAP, counts, 0, 11, 6.75, 67.5, 6.75, 8, 14);
        checkScores(ScoringSystem.DUCT_TAPE_V2, counts, 3, 20, 7.5, 53, 7.5, 13, 30);

        Map<Nation, Integer> tied = counts(0, 5, 4, 8, 4, 5, 8);
        checkScores(ScoringSystem.OPEN_MIND_THE_GAP, tied, 0, 17.25, 15, 24.75, 15, 17.25, 24.75);
        checkScores(ScoringSystem.C_DIPLO_100, counts(5, 4, 5, 5, 4, 5, 4),
                20.75, 5, 20.75, 20.75, 5, 20.75, 5);
        checkScores(ScoringSystem.DUCT_TAPE_V2, counts(0, 17, 0, 0, 16, 1, 0),
                5.25, 57, 5.25, 5.25, 40, 16, 5.25);
        checkScores(ScoringSystem.DUCT_TAPE_V2, counts(1, 10, 1, 1, 10, 10, 1),
                6.25, 10 + 79.0 / 3, 6.25, 6.25, 10 + 79.0 / 3, 10 + 79.0 / 3, 6.25);
        checkScores(ScoringSystem.C_DIPLO_100, counts(17, 17, 0, 0, 0, 0, 0),
                44, 44, 2.4, 2.4, 2.4, 2.4, 2.4);
        checkScores(ScoringSystem.OPEN_MIND_THE_GAP, counts(17, 17, 0, 0, 0, 0, 0),
                38.25, 38.25, 0.3, 0.3, 0.3, 0.3, 0.3);

        Map<Nation, Double> duct = ScoringSystem.DUCT_TAPE_V2.rewards(counts);
        close(duct.values().stream().mapToDouble(Double::doubleValue).sum(), 134.0 / 75,
                "Fixed-scale Duct Tape rewards must retain their variable total");
        close(ScoringSystem.C_DIPLO_100.score(counts(5, 4, 5, 5, 4, 5, 4))
                        .values().stream().mapToDouble(Double::doubleValue).sum(), 98,
                "Unowned centers must not be invented to fill C-Diplo's point pool");
    }

    private static void solos() {
        for (ScoringSystem system : ScoringSystem.values()) {
            double solo = system == ScoringSystem.DUCT_TAPE_V2 ? 75 : 100;
            close(system.soloScore(), solo, "The configured solo award must match the scoring rules");
            for (Nation winner : Nation.values()) {
                Map<Nation, Integer> counts = counts(0, 0, 0, 0, 0, 0, 0);
                counts.put(winner, 18);
                counts.put(Nation.values()[(winner.ordinal() + 1) % Nation.values().length], 16);
                Map<Nation, Double> scores = system.score(counts);
                Map<Nation, Double> rewards = system.rewards(counts);
                for (Nation nation : Nation.values()) {
                    close(scores.get(nation), nation == winner ? solo : 0,
                            "A solo must override center, survival, and rank points");
                    close(rewards.get(nation), nation == winner ? 1 : 0,
                            "A solo reward must be exactly 1/0");
                }
            }
        }
        for (ScoringSystem system : ScoringSystem.values())
            close(system.rewards(counts(0, 0, 34, 0, 0, 0, 0)).get(Nation.FRANCE), 1,
                    "The solo override must also handle more than 18 centers");
    }

    private static void invalidInputs() {
        for (ScoringSystem system : ScoringSystem.values()) {
            expect(NullPointerException.class, () -> system.score(null));
            expect(IllegalArgumentException.class, () -> system.score(Map.of()));
            expect(IllegalArgumentException.class, () -> system.score(counts(0, 0, 0, 0, 0, 0, 0)));
            expect(IllegalArgumentException.class, () -> system.score(counts(-1, 5, 5, 5, 5, 5, 5)));
            expect(IllegalArgumentException.class, () -> system.score(counts(Integer.MAX_VALUE, 0, 0, 0, 0, 0, 0)));
            expect(IllegalArgumentException.class, () -> system.score(counts(18, 18, 0, 0, 0, 0, 0)));
            Map<Nation, Integer> missing = counts(3, 3, 3, 3, 3, 4, 3);
            missing.remove(Nation.TURKEY);
            expect(IllegalArgumentException.class, () -> system.score(missing));
            Map<Nation, Integer> nullCount = counts(3, 3, 3, 3, 3, 4, 3);
            nullCount.put(Nation.FRANCE, null);
            expect(NullPointerException.class, () -> system.score(nullCount));
            Map<Nation, Integer> nullKey = new HashMap<>(counts(3, 3, 3, 3, 3, 4, 3));
            nullKey.put(null, 0);
            expect(NullPointerException.class, () -> system.score(nullKey));
        }
    }

    private static void properties() {
        Random random = new Random(54L);
        for (int sample = 0; sample < 100; sample++) {
            Map<Nation, Integer> counts = counts(0, 0, 0, 0, 0, 0, 0);
            int total = 1 + random.nextInt(34);
            for (int center = 0; center < total; center++)
                counts.merge(Nation.values()[random.nextInt(7)], 1, Integer::sum);
            for (ScoringSystem system : ScoringSystem.values()) {
                Map<Nation, Integer> before = Map.copyOf(counts);
                Map<Nation, Double> scores = system.score(counts);
                Map<Nation, Double> rewards = system.rewards(counts);
                require(scores.keySet().equals(EnumSet.allOf(Nation.class)), "Scores must include all nations");
                require(rewards.keySet().equals(scores.keySet()), "Rewards must include all nations");
                require(counts.equals(before), "Scoring must leave its input unchanged");
                Map<Nation, Integer> rotated = new EnumMap<>(Nation.class);
                for (Nation nation : Nation.values())
                    rotated.put(Nation.values()[(nation.ordinal() + 1) % 7], counts.get(nation));
                Map<Nation, Double> rotatedScores = system.score(rotated);
                for (Nation nation : Nation.values()) {
                    require(Double.isFinite(rewards.get(nation)) && rewards.get(nation) >= 0
                                    && rewards.get(nation) <= 1,
                            "Selected systems must produce finite rewards in 0..1");
                    close(rewards.get(nation), scores.get(nation) / system.soloScore(),
                            "Reward scaling must use the fixed solo award");
                    close(rotatedScores.get(Nation.values()[(nation.ordinal() + 1) % 7]), scores.get(nation),
                            "Renaming nations must preserve their scores");
                }
                if (system == ScoringSystem.SUM_OF_SQUARES || system == ScoringSystem.DRAW_SIZE)
                    close(rewards.values().stream().mapToDouble(Double::doubleValue).sum(), 1,
                            "Paper reward vectors must sum to one");
                long survivors = counts.values().stream().filter(count -> count > 0).count();
                double pot = switch (system) {
                    case DRAW_SIZE, SUM_OF_SQUARES -> 100;
                    case C_DIPLO_100 -> total + 66;
                    case OPEN_MIND_THE_GAP -> 1.5 * total + 9 * survivors + 9;
                    case DUCT_TAPE_V2 -> total + 100;
                };
                if (counts.values().stream().anyMatch(count -> count >= 18))
                    pot = system.soloScore();
                close(scores.values().stream().mapToDouble(Double::doubleValue).sum(), pot,
                        "Total points must preserve the selected system's payout");
            }
        }
    }

    private static void evaluation() {
        GameState initial = StandardGameFactory.create1901().snapshot();
        for (ScoringSystem system : ScoringSystem.values()) {
            ScoringEvaluator evaluator = new ScoringEvaluator(system);
            require(evaluator.scoringSystem() == system, "Evaluator configuration must retain the scoring identity");
            Map<Nation, Double> scores = evaluator.evaluate(initial);
            require(scores.equals(system.rewards(counts(3, 3, 3, 3, 3, 4, 3))),
                    "Evaluation must use the configured system's rewards");
            expect(UnsupportedOperationException.class, () -> scores.put(Nation.FRANCE, 5.0));
            expect(NullPointerException.class, () -> evaluator.evaluate(null));
            expect(IllegalArgumentException.class, () -> evaluator.evaluate(new GameState_Movement(
                    initial.moment(), new BoardState(Map.of(), Map.of()))));
        }
        expect(NullPointerException.class, () -> new ScoringEvaluator(null));
        require(new SCEvaluator().evaluate(initial).get(Nation.RUSSIA) == 4.0,
                "The existing raw-center evaluator must retain its scale");

        UnitId german = new UnitId(UUID.randomUUID(), Nation.GERMANY, UnitType.ARMY, Province.Mun);
        GameState coasts = new GameState_Movement(initial.moment(),
                new BoardState(Map.of(german, Province.Par),
                        Map.of(Province.Par, Nation.FRANCE, Province.SpaNC, Nation.FRANCE,
                                Province.SpaSC, Nation.FRANCE, Province.StpNC, Nation.RUSSIA,
                                Province.StpSC, Nation.RUSSIA)));
        Map<Nation, Double> coastScores = new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES).evaluate(coasts);
        close(coastScores.get(Nation.FRANCE), 4.0 / 5, "Coast aliases must be counted once");
        close(coastScores.get(Nation.GERMANY), 0, "An occupying unit must not take the recorded owner's score");
        close(new ScoringEvaluator(ScoringSystem.DRAW_SIZE).evaluate(coasts).get(Nation.FRANCE), 0.5,
                "Final-score survival must depend on centers, including when a centerless unit remains");

        UnitId french = new UnitId(UUID.randomUUID(), Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState before = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Pic),
                        Map.of(Province.Par, Nation.FRANCE, Province.Bel, Nation.GERMANY)));
        Game game = Game.fromState(before,
                new MovementProcessor(MovementProcessor.Policy.SZYKMAN_JUSTICE, 4, 0L),
                new RetreatProcessor(), new AdjustmentProcessor());
        GameState next = new Simulator().peek(game, Map.of(
                Nation.FRANCE, new ActionCandidate(Nation.FRANCE, List.of(Order.move(french, Province.Bel)),
                        0.0, ActionCandidate.Source.HEURISTIC),
                Nation.GERMANY, new ActionCandidate(Nation.GERMANY, List.of(),
                        0.0, ActionCandidate.Source.HEURISTIC)));
        Evaluator evaluator = new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES);
        close(evaluator.evaluate(before).get(Nation.FRANCE), 0.5, "The original position must remain evaluable");
        close(evaluator.evaluate(next).get(Nation.FRANCE), 1.0, "The completed Fall capture must change SoS");
        require(game.snapshot().equals(before) && game.getSnapshot() == null,
                "Scoring a peek must retain the restored game");
    }

    private static void checkScores(ScoringSystem system, Map<Nation, Integer> counts, double... expected) {
        Map<Nation, Double> scores = system.score(counts);
        require(new ArrayList<>(scores.keySet()).equals(Arrays.asList(Nation.values())),
                "Native score iteration must follow nation order");
        for (int index = 0; index < FIXTURE_ORDER.size(); index++) {
            Nation nation = FIXTURE_ORDER.get(index);
            close(scores.get(nation), expected[index], "Unexpected native score for " + system + "/" + nation);
        }
        expect(UnsupportedOperationException.class, () -> scores.entrySet().iterator().next().setValue(0.0));
    }

    private static Map<Nation, Integer> counts(int... values) {
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);
        for (int index = 0; index < FIXTURE_ORDER.size(); index++)
            counts.put(FIXTURE_ORDER.get(index), values[index]);
        return counts;
    }

    private static void close(double actual, double expected, String message) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) < 1e-10,
                message + ": expected " + expected + ", got " + actual);
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