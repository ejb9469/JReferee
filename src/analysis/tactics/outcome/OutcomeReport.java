package analysis.tactics.outcome;

import analysis.tactics.InvestigationCoverage;
import analysis.tactics.TacticKind;

import java.util.*;
import java.util.function.Predicate;


/**
 * Immutable outcome findings and outcome-agency coverage.
 *
 * <p>Coverage is relative to the outcome agency, not the structural agency
 * or the union of every investigator in the application.</p>
 */
public record OutcomeReport(
        ResolvedMovementContext context,
        List<OutcomeFinding> findings,
        InvestigationCoverage coverage
) {


    // Construction \\

    public OutcomeReport {

        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(coverage, "coverage");

        findings = List.copyOf(
                Objects.requireNonNull(findings, "findings"));

        if (coverage.scope() == InvestigationCoverage.Scope.NOT_RECORDED)
            throw new IllegalArgumentException(
                    "An outcome report requires recorded agency coverage");

        Set<OutcomeFinding> observed = new HashSet<>();
        Map<TacticKind, Integer> counts = new EnumMap<>(TacticKind.class);

        for (OutcomeFinding finding : findings) {

            if (finding.context() != context)
                throw new IllegalArgumentException(
                        "Finding does not retain the report context");

            if (!observed.add(finding))
                throw new IllegalArgumentException(
                        "Duplicate outcome finding");

            counts.merge(finding.kind(), 1, Integer::sum);

        }

        for (InvestigationCoverage.KindCoverage entry : coverage.byKind()) {

            int displayed = counts.getOrDefault(entry.kind(), 0);

            if (displayed > 0
                    && entry.status()
                    != InvestigationCoverage.Status.EVALUATED_WITH_FINDINGS)
                throw new IllegalArgumentException(
                        "Finding belongs to a non-evaluated category");

            if (coverage.scope()
                    == InvestigationCoverage.Scope.COMPLETE_INVESTIGATION) {

                if (displayed != entry.findingCount())
                    throw new IllegalArgumentException(
                            "Outcome coverage count differs for " + entry.kind());

            } else if (displayed > entry.findingCount()) {

                throw new IllegalArgumentException(
                        "Filtered count exceeds whole-investigation count");

            }

        }

        List<OutcomeFinding> sorted = new ArrayList<>(findings);
        sorted.sort(OutcomeReport::compareFindings);
        findings = List.copyOf(sorted);

    }


    // Filtered views \\

    public OutcomeReport byKind(TacticKind kind) {

        Objects.requireNonNull(kind, "kind");
        return filtered(finding -> finding.kind() == kind);

    }

    public OutcomeReport filtered(Predicate<OutcomeFinding> predicate) {

        Objects.requireNonNull(predicate, "predicate");

        List<OutcomeFinding> selected = new ArrayList<>();

        for (OutcomeFinding finding : findings)
            if (predicate.test(finding))
                selected.add(finding);

        return new OutcomeReport(
                context,
                selected,
                coverage.asFilteredView());

    }


    // Shared stable ordering \\

    public static int compareFindings(
            OutcomeFinding first,
            OutcomeFinding second
    ) {

        int comparison = first.kind().compareTo(second.kind());

        if (comparison != 0)
            return comparison;

        comparison = first.focus().name().compareTo(second.focus().name());

        if (comparison != 0)
            return comparison;

        int shared = Math.min(
                first.participants().size(),
                second.participants().size());

        for (int index = 0; index < shared; index++) {

            OutcomeFinding.Participant a = first.participants().get(index);
            OutcomeFinding.Participant b = second.participants().get(index);

            comparison = a.role().compareTo(b.role());

            if (comparison != 0)
                return comparison;

            comparison = first.context().initialLocationOf(a.unit()).name()
                    .compareTo(
                            second.context().initialLocationOf(b.unit()).name());

            if (comparison != 0)
                return comparison;

        }

        return Integer.compare(
                first.participants().size(),
                second.participants().size());

    }


}