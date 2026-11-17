package agents.encoding;

import java.util.*;

/**
 * Explicit search-type inputs, independent of board and outcome features.
 */
public record NeuralSearchType(
        int profileId, int outputId, double playingLambda,
        List<Double> populationLambdas, List<Double> populationProbabilities
) {

    public static final List<String> FIELDS = List.of(
            "log10_playing_lambda",
            "log10_population_lambda_0", "log10_population_lambda_1",
            "log10_population_lambda_2", "log10_population_lambda_3",
            "population_weight_0", "population_weight_1",
            "population_weight_2", "population_weight_3",
            "output_FINAL", "output_AVERAGE");

    public NeuralSearchType {

        populationLambdas = List.copyOf(populationLambdas);
        populationProbabilities = List.copyOf(populationProbabilities);
        if (profileId < 1 || profileId > 2 || outputId < 1 || outputId > 2
                || populationLambdas.size() != 4 || populationProbabilities.size() != 4)
            throw new IllegalArgumentException("Invalid search-type IDs or dimensions");
        positive(playingLambda);
        populationLambdas.forEach(NeuralSearchType::positive);
        double mass = 0;
        for (double probability : populationProbabilities) {
            if (!Double.isFinite(probability) || probability < 0 || probability > 1)
                throw new IllegalArgumentException("Invalid population probability");
            mass += probability;
        }
        if (Math.abs(mass - 1) > 1e-10)
            throw new IllegalArgumentException("Population probabilities must sum to one");

    }

    public double[] features() {

        double[] result = new double[FIELDS.size()];
        result[0] = (float) StrictMath.log10(this.playingLambda);
        for (int index = 0; index < 4; index++) {
            result[index + 1] = (float) StrictMath.log10(this.populationLambdas.get(index));
            result[index + 5] = this.populationProbabilities.get(index).floatValue();
        }
        result[this.outputId == 1 ? 9 : 10] = 1;
        return result;

    }

    public static Map<String, Object> specification() {

        return Map.of(
                "schema", "jreferee-search-type-input-v1",
                "fields", FIELDS,
                "lambda_transform", "float32(log10(float64(lambda))); no clipping",
                "weight_transform", "float32(weight)",
                "output_encoding", "one_hot(FINAL,AVERAGE)",
                "profile_id", "validated metadata, not an additional model feature",
                "conditioning", "base_initial + tanh(type_projection(features))",
                "projection_bias", false,
                "initial_projection", "zeros for a supervised warm start");

    }

    private static void positive(double value) {

        if (!Double.isFinite(value) || value <= 0)
            throw new IllegalArgumentException("Type lambdas must be finite and positive");

    }

}