package analysis.tactics.outcome;

import analysis.tactics.*;
import analysis.tactics.outcome.detective.*;

import java.util.*;


/**
 * Coordinates investigators of one resolved movement scenario.
 *
 * <p>Coverage is outcome-agency-specific. A kind implemented by the
 * structural agency can still be unsupported by this agency.</p>
 *
 * <p>Investigator failures abort this report; no partial successful report
 * is returned. Browser code can isolate that failure from structural output.</p>
 */
public final class OutcomeAgency {


    // Core state \\

    private final Map<TacticKind, Assignment> assignments;


    // Construction \\

    public OutcomeAgency() {
        this(List.of(new SupporterDislodgementDetective()));
    }

    public OutcomeAgency(
            Collection<? extends TacticInvestigator<
                    ResolvedMovementContext, OutcomeFinding>> investigators
    ) {

        Objects.requireNonNull(investigators, "investigators");

        Map<TacticKind, Assignment> registered =
                new EnumMap<>(TacticKind.class);

        for (TacticInvestigator<ResolvedMovementContext, OutcomeFinding>
                investigator : investigators) {

            Objects.requireNonNull(investigator, "investigator");

            TacticKind kind = Objects.requireNonNull(
                    investigator.kind(), "investigator kind");

            String version = Objects.requireNonNull(
                    investigator.version(), "investigator version");

            if (version.isBlank())
                throw new IllegalArgumentException(
                        "Investigator version must not be blank");

            TacticDefinitionRegistry.require(kind);

            Assignment assignment = new Assignment(
                    investigator, kind, version);

            if (registered.putIfAbsent(kind, assignment) != null)
                throw new IllegalArgumentException(
                        "Multiple outcome investigators for " + kind);

        }

        assignments = Collections.unmodifiableMap(registered);

    }


    // Registry queries \\

    public List<TacticKind> kinds() {
        return List.copyOf(assignments.keySet());
    }


    // Investigation \\

    public OutcomeReport investigateReport(ResolvedMovementContext context) {

        Objects.requireNonNull(context, "context");

        Set<EvidenceCapability> available = EnumSet.of(
                EvidenceCapability.MOVEMENT_POSITION,
                EvidenceCapability.KNOWN_MOVEMENT_ORDERS,
                EvidenceCapability.MAP_ADJACENCY,
                EvidenceCapability.ADJUDICATION_OUTCOME);

        List<OutcomeFinding> findings = new ArrayList<>();
        Set<OutcomeFinding> observed = new HashSet<>();

        List<InvestigationCoverage.KindCoverage> coverage =
                new ArrayList<>();

        for (TacticKind kind : TacticKind.values()) {

            Assignment assignment = assignments.get(kind);

            if (assignment == null) {

                coverage.add(new InvestigationCoverage.KindCoverage(
                        kind,
                        false,
                        false,
                        InvestigationCoverage.Status.UNSUPPORTED_UNIMPLEMENTED,
                        0,
                        Set.of()));

                continue;

            }

            verifyIdentity(assignment);

            Set<EvidenceCapability> missing =
                    EnumSet.noneOf(EvidenceCapability.class);

            missing.addAll(
                    TacticDefinitionRegistry.require(kind).requiredEvidence());

            missing.removeAll(available);

            if (!missing.isEmpty()) {

                coverage.add(new InvestigationCoverage.KindCoverage(
                        kind,
                        true,
                        false,
                        InvestigationCoverage.Status.SKIPPED_MISSING_EVIDENCE,
                        0,
                        missing));

                continue;

            }

            List<OutcomeFinding> discovered =
                    assignment.investigator().investigate(context);

            verifyIdentity(assignment);

            if (discovered == null)
                throw new IllegalStateException(
                        "Null outcome investigation result");

            int before = findings.size();

            for (OutcomeFinding finding : discovered) {

                if (finding == null
                        || finding.context() != context
                        || finding.kind() != kind
                        || !finding.investigatorVersion().equals(
                        assignment.version()))
                    throw new IllegalStateException(
                            "Outcome investigator returned an invalid finding");

                if (!observed.add(finding))
                    throw new IllegalStateException(
                            "Duplicate outcome occurrence");

                findings.add(finding);

            }

            int count = findings.size() - before;

            coverage.add(new InvestigationCoverage.KindCoverage(
                    kind,
                    true,
                    true,
                    count == 0
                            ? InvestigationCoverage.Status.EVALUATED_WITH_NO_FINDINGS
                            : InvestigationCoverage.Status.EVALUATED_WITH_FINDINGS,
                    count,
                    Set.of()));

        }

        return new OutcomeReport(
                context,
                findings,
                new InvestigationCoverage(
                        InvestigationCoverage.Scope.COMPLETE_INVESTIGATION,
                        coverage));

    }


    // Identity validation \\

    private static void verifyIdentity(Assignment assignment) {

        if (assignment.investigator().kind() != assignment.kind()
                || !assignment.version().equals(
                assignment.investigator().version()))
            throw new IllegalStateException(
                    "Outcome investigator changed identity");

    }


    // Registration \\

    private record Assignment(
            TacticInvestigator<ResolvedMovementContext, OutcomeFinding> investigator,
            TacticKind kind,
            String version
    ) { }


}