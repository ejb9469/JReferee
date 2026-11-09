package agents.search;

import agents.evaluation.Evaluator;
import agents.generation.ActionGenerator;

/**
 * Lambda types, playing lambda, and temperature for a fixed-pool search.
 */
public interface SearchProfile {

    LambdaDistribution          population();

    double                      agentLambda();

    TemperatureSchedule         temperature();

    default DiLPiKLSearch solver(Evaluator evaluator, ActionGenerator generator,
                                 int candidateLimit, int iterations, long seed) {
        return solver(evaluator, generator, candidateLimit, iterations, seed, PolicyOutput.FINAL);
    }

    /**
     * Selects final or averaged output. The older factory retains FINAL.
     */
    default DiLPiKLSearch solver(Evaluator evaluator, ActionGenerator generator,
                                 int candidateLimit, int iterations, long seed, PolicyOutput output) {
        return new DiLPiKLSearch(evaluator, generator, candidateLimit, iterations,
                population(), agentLambda(), seed, temperature(), output);
    }

}