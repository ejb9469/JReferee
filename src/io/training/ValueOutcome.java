package io.training;

import domain.Constants;
import domain.Nation;
import game.GameMoment;

import java.util.*;

/**
 * Observed ending information, separate from model inputs.<br><br>
 *
 * A completed outcome's moment identifies its terminal source snapshot.
 * Training observations must precede that boundary.
 *
 * Source scoring and limit labels are bookkeeping, not reward selectors.
 * SOURCE_COMPLETED records completion without inventing its cause.
 */
public record ValueOutcome(
        Termination termination,
        Evidence evidence,
        GameMoment moment,
        Map<Nation, Integer> centerCounts,
        Optional<String> sourceScoring,
        Optional<String> sourceLimit,
        String provenance
) {


    // Ending classification \\

    public enum Termination {

        SOLO,
        AGREED_DRAW,
        RULE_LIMIT,
        SOURCE_COMPLETED,
        ARTIFICIAL_CUTOFF,
        INCOMPLETE,
        UNKNOWN

    }

    public enum Evidence {

        UNVERIFIED,
        SOURCE_RECORDED,
        REPLAY_VERIFIED

    }


    // Construction \\

    public ValueOutcome {

        Objects.requireNonNull(termination, "termination");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(sourceScoring, "sourceScoring");
        Objects.requireNonNull(sourceLimit, "sourceLimit");
        Objects.requireNonNull(provenance, "provenance");

        if (moment.year() < Constants.STARTING_YEAR)
            throw new IllegalArgumentException(
                    "Outcome predates the standard game");

        if (provenance.isBlank())
            throw new IllegalArgumentException(
                    "Outcome provenance is required");

        if (sourceScoring.filter(String::isBlank).isPresent()
                || sourceLimit.filter(String::isBlank).isPresent())
            throw new IllegalArgumentException(
                    "Use Optional.empty() for missing source labels");

        Map<Nation, Integer> supplied = Map.copyOf(
                Objects.requireNonNull(centerCounts, "centerCounts"));

        if (!supplied.keySet().equals(EnumSet.allOf(Nation.class)))
            throw new IllegalArgumentException(
                    "Outcome counts must include all seven nations");

        int total = 0;
        int soloPowers = 0;

        for (Nation nation : Nation.values()) {

            int count = supplied.get(nation);

            if (count < 0 || count > 34)
                throw new IllegalArgumentException(
                        "Invalid center count for " + nation + ": " + count);

            total += count;

            if (count >= 18)
                soloPowers++;

        }

        if (total > 34)
            throw new IllegalArgumentException(
                    "Owned supply centers exceed the standard map");

        boolean completed = switch (termination) {
            case SOLO, AGREED_DRAW, RULE_LIMIT, SOURCE_COMPLETED -> true;
            case ARTIFICIAL_CUTOFF, INCOMPLETE, UNKNOWN -> false;
        };

        if (completed && total == 0)
            throw new IllegalArgumentException(
                    "A completed outcome needs owned supply centers");

        if (termination == Termination.SOLO && soloPowers != 1)
            throw new IllegalArgumentException(
                    "A solo requires one power with at least 18 centers");

        if (completed && termination != Termination.SOLO && soloPowers != 0)
            throw new IllegalArgumentException(
                    "A solo takes precedence over another completed ending");

        centerCounts = Collections.unmodifiableMap(new EnumMap<>(supplied));

    }

}