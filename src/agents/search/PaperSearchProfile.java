package agents.search;

import game.GamePhase;
import game.GameState;

import java.util.Objects;

/**
 * Diplodocus test-time settings from Section 4.2 of arXiv:2210.05492.
 * Iteration budgets and candidate generation are supplied by the caller.
 */
public enum PaperSearchProfile implements SearchProfile {

    OPENING(LambdaDistribution.uniform(1e-2, StrictMath.pow(10, -1.5),
            1e-1, StrictMath.pow(10, -0.5)), 1e-2),
    STANDARD(LambdaDistribution.uniform(1e-4, 1e-3, 1e-2, 1e-1), 1e-4);

    private final LambdaDistribution population;
    private final double agentLambda;

    PaperSearchProfile(LambdaDistribution population, double agentLambda) {
        this.population = population;
        this.agentLambda = agentLambda;
    }

    public static PaperSearchProfile forState(GameState state) {
        Objects.requireNonNull(state, "state");
        return state.moment().year() == 1901
                && state.moment().gamePhase() == GamePhase.SPRING_MOVEMENT ? OPENING : STANDARD;
    }

    @Override
    public LambdaDistribution population() {
        return population;
    }

    @Override
    public double agentLambda() {
        return agentLambda;
    }

    @Override
    public TemperatureSchedule temperature() {
        return TemperatureSchedule.ADAPTIVE;
    }

}