package io.training;

import domain.Nation;
import misc.scoring.ScoringSystem;

import java.util.*;

/**
 * Undiscounted normalized SoS targets for completed games.<br><br>
 *
 * Historical scoring and limit labels do not select or modify rewards.
 * Targets come from final center ownership, with the standard solo override.
 * There is no intermediate reward shaping or cutoff bootstrapping here.
 */
public final class ValueRewardContract {


    // Version and rejection reasons \\

    public static final String VERSION = "jreferee-eventual-sos-v3";

    public enum Rejection {

        UNVERIFIED_OUTCOME,
        ARTIFICIAL_CUTOFF,
        INCOMPLETE_GAME,
        UNKNOWN_ENDING

    }


    // Construction \\

    public ValueRewardContract() { }

    public static ValueRewardContract defaults() {
        return new ValueRewardContract();
    }


    // Assessment result \\

    /**
     * Exactly one of rewards or rejection is present.
     * Rejection must never be converted into an all-zero target.
     */
    public record Assessment(
            Optional<Map<Nation, Double>> rewards,
            Optional<Rejection> rejection
    ) {

        public Assessment {

            Objects.requireNonNull(rewards, "rewards");
            Objects.requireNonNull(rejection, "rejection");

            if (rewards.isPresent() == rejection.isPresent())
                throw new IllegalArgumentException(
                        "Supply either rewards or a rejection reason");

            if (rewards.isPresent()) {

                Map<Nation, Double> supplied = Map.copyOf(
                        rewards.orElseThrow());

                if (!supplied.keySet().equals(EnumSet.allOf(Nation.class)))
                    throw new IllegalArgumentException(
                            "Targets must include all seven nations");

                double total = 0;

                for (Nation nation : Nation.values()) {

                    double reward = supplied.get(nation);

                    if (!Double.isFinite(reward) || reward < 0 || reward > 1)
                        throw new IllegalArgumentException(
                                "Invalid target for " + nation + ": " + reward);

                    total += reward;

                }

                if (Math.abs(total - 1) > 1e-9)
                    throw new IllegalArgumentException(
                            "SoS targets must sum to one");

                rewards = Optional.of(Collections.unmodifiableMap(
                        new EnumMap<>(supplied)));

            }

        }

        public static Assessment rejected(Rejection reason) {
            return new Assessment(
                    Optional.empty(),
                    Optional.of(Objects.requireNonNull(reason, "reason"))
            );
        }

        public static Assessment accepted(Map<Nation, Double> rewards) {
            return new Assessment(
                    Optional.of(Objects.requireNonNull(rewards, "rewards")),
                    Optional.empty()
            );
        }

    }


    // Reward assessment \\

    public Assessment assess(ValueOutcome outcome) {

        Objects.requireNonNull(outcome, "outcome");

        switch (outcome.termination()) {

            case ARTIFICIAL_CUTOFF:
                return Assessment.rejected(Rejection.ARTIFICIAL_CUTOFF);

            case INCOMPLETE:
                return Assessment.rejected(Rejection.INCOMPLETE_GAME);

            case UNKNOWN:
                return Assessment.rejected(Rejection.UNKNOWN_ENDING);

            case SOLO, AGREED_DRAW, RULE_LIMIT, SOURCE_COMPLETED:
                break;

        }

        if (outcome.evidence() == ValueOutcome.Evidence.UNVERIFIED)
            return Assessment.rejected(Rejection.UNVERIFIED_OUTCOME);

        return Assessment.accepted(
                ScoringSystem.SUM_OF_SQUARES.rewards(outcome.centerCounts()));

    }

    public String id() {
        return VERSION;
    }

}