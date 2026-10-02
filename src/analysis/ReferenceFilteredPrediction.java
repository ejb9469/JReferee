package analysis;

import game.BoardState;
import phase.Order;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


public final class ReferenceFilteredPrediction {

    private ReferenceFilteredPrediction() {  }


    public static RoutePrediction apply(
            BoardState board,
            RoutePrediction original) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(original, "original");

        Map<Order, Long> retained = new LinkedHashMap<>();

        for (var entry : original.counts().entrySet()) {

            if (CandidateSelectionAudit.referenceIssue(board, entry.getKey())
                    == CandidateSelectionAudit.ReferenceIssue.NONE)
                retained.put(entry.getKey(), entry.getValue());

        }

        if (retained.size() == original.counts().size())
            return original;

        /*
         * Preserve the evidence provenance, even if filtering leaves no
         * candidates. Candidate availability is determined by empty counts.
         */
        return new RoutePrediction(
                original.basis(), original.suffixLength(), retained);

    }

}