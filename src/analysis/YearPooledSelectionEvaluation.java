package analysis;

import game.record.GameRecord;

import java.io.PrintStream;
import java.util.Collection;
import java.util.Objects;


public final class YearPooledSelectionEvaluation {

    private YearPooledSelectionEvaluation() {  }


    public static void evaluate(
            Collection<GameRecord> trainingGames,
            Collection<GameRecord> validationGames,
            PrintStream output) {
        evaluateFourPolicies(trainingGames, validationGames, output);
    }


    public static void evaluate(
            Collection<GameRecord> trainingGames,
            Collection<GameRecord> validationGames,
            PrintStream output,
            Experiment experiment) {
        Objects.requireNonNull(experiment, "experiment");
        evaluateFourPolicies(trainingGames, validationGames, output);
    }


    public static FourPolicyEvaluation.Report evaluateFourPolicies(
            Collection<GameRecord> trainingGames,
            Collection<GameRecord> validationGames,
            PrintStream output) {
        return FourPolicyEvaluation.evaluate(
                trainingGames, validationGames, output);
    }


    public enum Experiment {
        YEAR_POOLED_SELECTION,
        REFERENCE_FILTERING
    }

}
