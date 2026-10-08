package analysis.tactics;

import java.util.*;


/**
 * Per-kind account of implementation and evaluation for one investigation.
 *
 * <p>Coverage is separate from local match completeness. A filtered view
 * retains the original coverage and labels it as such; it is not a new
 * investigation.</p>
 */
public record InvestigationCoverage(
        Scope scope,
        List<KindCoverage> byKind
) {


    // Construction and validation \\

    public InvestigationCoverage {

        Objects.requireNonNull(scope, "scope");
        byKind = List.copyOf(Objects.requireNonNull(byKind, "byKind"));

        Set<TacticKind> observed = EnumSet.noneOf(TacticKind.class);

        for (KindCoverage coverage : byKind)
            if (!observed.add(coverage.kind()))
                throw new IllegalArgumentException(
                        "Duplicate coverage entry: " + coverage.kind());

        List<KindCoverage> ordered = new ArrayList<>(byKind);
        ordered.sort(Comparator.comparing(KindCoverage::kind));
        byKind = List.copyOf(ordered);

        if (scope == Scope.NOT_RECORDED && !byKind.isEmpty())
            throw new IllegalArgumentException(
                    "Unrecorded coverage must not contain entries");

        if (scope != Scope.NOT_RECORDED
                && !observed.equals(EnumSet.allOf(TacticKind.class)))
            throw new IllegalArgumentException(
                    "Investigation coverage must account for every tactic kind");

    }


    // Scope \\

    public enum Scope {

        COMPLETE_INVESTIGATION,
        FILTERED_VIEW,
        NOT_RECORDED

    }

    public enum Status {

        EVALUATED_WITH_FINDINGS,
        EVALUATED_WITH_NO_FINDINGS,
        SKIPPED_MISSING_EVIDENCE,
        UNSUPPORTED_UNIMPLEMENTED

    }


    // Filtered views \\

    public InvestigationCoverage asFilteredView() {

        if (scope != Scope.COMPLETE_INVESTIGATION)
            return this;

        return new InvestigationCoverage(Scope.FILTERED_VIEW, byKind);

    }


    // Per-kind coverage \\

    /**
     * Captures the whole-investigation result for one kind, not a filtered
     * report's selected finding count.
     */
    public record KindCoverage(
            TacticKind kind,
            boolean implemented,
            boolean applicable,
            Status status,
            int findingCount,
            Set<EvidenceCapability> missingEvidence
    ) {

        public KindCoverage {

            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(missingEvidence, "missingEvidence");

            EnumSet<EvidenceCapability> missing =
                    EnumSet.noneOf(EvidenceCapability.class);

            for (EvidenceCapability capability : missingEvidence)
                missing.add(Objects.requireNonNull(
                        capability, "missing evidence capability"));

            missingEvidence = Collections.unmodifiableSet(missing);

            if (findingCount < 0)
                throw new IllegalArgumentException(
                        "findingCount must not be negative");

            switch (status) {

                case EVALUATED_WITH_FINDINGS -> {
                    require(implemented && applicable
                                    && findingCount > 0 && missing.isEmpty(),
                            "Invalid evaluated-with-findings coverage");
                }

                case EVALUATED_WITH_NO_FINDINGS -> {
                    require(implemented && applicable
                                    && findingCount == 0 && missing.isEmpty(),
                            "Invalid evaluated-with-no-findings coverage");
                }

                case SKIPPED_MISSING_EVIDENCE -> {
                    require(implemented && !applicable
                                    && findingCount == 0 && !missing.isEmpty(),
                            "Invalid skipped coverage");
                }

                case UNSUPPORTED_UNIMPLEMENTED -> {
                    require(!implemented && !applicable
                                    && findingCount == 0 && missing.isEmpty(),
                            "Invalid unsupported coverage");
                }

            }

        }

        private static void require(boolean condition, String message) {
            if (!condition)
                throw new IllegalArgumentException(message);
        }

    }


    // Unreported coverage \\

    public static InvestigationCoverage notRecorded() {
        return new InvestigationCoverage(Scope.NOT_RECORDED, List.of());
    }


}
