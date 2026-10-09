package analysis.tactics;

import analysis.tactics.detective.BeleagueredGarrisonDetective;
import analysis.tactics.detective.BogusMovesDetective;
import analysis.tactics.detective.IncompleteConvoyChainDetective;
import analysis.tactics.detective.SelfBounceDetective;
import analysis.tactics.detective.SupportMismatchDetective;
import analysis.tactics.detective.SupportToHoldDetective;
import analysis.tactics.detective.SupportToMoveDetective;
import analysis.tactics.detective.UnmatchedConvoyDetective;

import java.util.*;


/**
 * Coordinates tactical patterns and diagnostics over known submissions.
 *
 * <p>Findings may overlap across categories. The support-mismatch detective
 * explicitly excludes bogus support; the agency does not implement that
 * semantic rule by deleting findings after investigation.</p>
 *
 * <p>This agency uses the standard Province map and static validation
 * policy. It does not supply adjudication, history, intent, or agreements.</p>
 */
public final class DetectiveAgency {


    // Core state \\

    private final Map<TacticKind, Assignment> assignments;


    // Constructors \\

    public DetectiveAgency() {
        this(List.of(
                new SupportToMoveDetective(),
                new SupportToHoldDetective(),
                new SelfBounceDetective(),
                new BeleagueredGarrisonDetective(),
                new SupportMismatchDetective(),
                new UnmatchedConvoyDetective(),
                new IncompleteConvoyChainDetective(),
                new BogusMovesDetective()));
    }

    public DetectiveAgency(
            Collection<? extends TacticDetector> detectives
    ) {

        Objects.requireNonNull(detectives, "detectives");

        Map<TacticKind, Assignment> registered =
                new EnumMap<>(TacticKind.class);

        for (TacticDetector detective : detectives) {

            Objects.requireNonNull(detective, "detective");

            TacticKind kind = Objects.requireNonNull(
                    detective.kind(), "detective kind");

            String version = Objects.requireNonNull(
                    detective.version(), "detective version");

            if (version.isBlank())
                throw new IllegalArgumentException(
                        "Detective version must not be blank");

            TacticDefinitionRegistry.require(kind);

            Assignment assignment = new Assignment(detective, kind, version);

            if (registered.putIfAbsent(kind, assignment) != null)
                throw new IllegalArgumentException(
                        "Multiple detectives registered for " + kind);

        }

        this.assignments = Collections.unmodifiableMap(registered);

    }


    // Registry queries \\

    public List<TacticKind> kinds() {
        return List.copyOf(assignments.keySet());
    }


    // Investigation \\

    public List<TacticMatch> investigate(TacticalContext context) {
        return investigateReport(context).findings();
    }

    /**
     * Records whether each kind was evaluated, skipped, or unimplemented.
     *
     * <p>Incomplete local findings are still findings. Missing submissions
     * do not imply that the whole investigation lacked its required input
     * capabilities.</p>
     */
    public InvestigationReport investigateReport(TacticalContext context) {

        Objects.requireNonNull(context, "context");

        List<TacticMatch> findings = new ArrayList<>();
        Set<TacticMatch> observed = new HashSet<>();

        List<InvestigationCoverage.KindCoverage> coverage =
                new ArrayList<>();

        Set<EvidenceCapability> available = availableEvidence(context);

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

            TacticDefinition definition =
                    TacticDefinitionRegistry.require(kind);

            Set<EvidenceCapability> missing =
                    EnumSet.noneOf(EvidenceCapability.class);

            missing.addAll(definition.requiredEvidence());
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

            List<TacticMatch> discovered =
                    assignment.detective().detect(context);

            verifyIdentity(assignment);

            if (discovered == null)
                throw new IllegalStateException(
                        "Detective returned a null result: " + kind);

            int countBefore = findings.size();

            for (TacticMatch match : discovered) {

                verifyMatch(assignment, context, match);

                if (!observed.add(match))
                    throw new IllegalStateException(
                            "Detective returned a duplicate occurrence: " + kind);

                findings.add(match);

            }

            int findingCount = findings.size() - countBefore;

            coverage.add(new InvestigationCoverage.KindCoverage(
                    kind,
                    true,
                    true,
                    findingCount == 0
                            ? InvestigationCoverage.Status.EVALUATED_WITH_NO_FINDINGS
                            : InvestigationCoverage.Status.EVALUATED_WITH_FINDINGS,
                    findingCount,
                    Set.of()));

        }

        findings.sort(DetectiveAgency::compareMatches);

        return new InvestigationReport(
                context,
                findings,
                new InvestigationCoverage(
                        InvestigationCoverage.Scope.COMPLETE_INVESTIGATION,
                        coverage));

    }


    // Available evidence \\

    private static Set<EvidenceCapability> availableEvidence(
            TacticalContext context
    ) {

        Objects.requireNonNull(context, "context");

        return EnumSet.of(
                EvidenceCapability.MOVEMENT_POSITION,
                EvidenceCapability.KNOWN_MOVEMENT_ORDERS,
                EvidenceCapability.MAP_ADJACENCY);

    }


    // Contract validation \\

    private static void verifyIdentity(Assignment assignment) {

        TacticDetector detective = assignment.detective();

        if (detective.kind() != assignment.kind()
                || !assignment.version().equals(detective.version()))
            throw new IllegalStateException(
                    "Detective identity changed after registration: "
                            + assignment.kind());

    }

    private static void verifyMatch(
            Assignment assignment,
            TacticalContext context,
            TacticMatch match
    ) {

        if (match == null)
            throw new IllegalStateException(
                    "Detective returned a null match: " + assignment.kind());

        if (match.kind() != assignment.kind())
            throw new IllegalStateException(
                    "Match belongs to a different category: " + assignment.kind());

        if (!match.detectorVersion().equals(assignment.version()))
            throw new IllegalStateException(
                    "Match uses a different detective version: "
                            + assignment.kind());

        if (match.context() != context)
            throw new IllegalStateException(
                    "Match does not retain the supplied context: "
                            + assignment.kind());

    }


    // Stable ordering \\

    private static int compareMatches(TacticMatch first, TacticMatch second) {

        int comparison = first.kind().compareTo(second.kind());

        if (comparison != 0)
            return comparison;

        comparison = first.focus().name().compareTo(second.focus().name());

        if (comparison != 0)
            return comparison;

        List<TacticMatch.Participant> firstParticipants = first.participants();
        List<TacticMatch.Participant> secondParticipants = second.participants();

        int sharedSize = Math.min(
                firstParticipants.size(), secondParticipants.size());

        for (int index = 0; index < sharedSize; index++) {

            TacticMatch.Participant firstParticipant =
                    firstParticipants.get(index);

            TacticMatch.Participant secondParticipant =
                    secondParticipants.get(index);

            comparison = firstParticipant.role().compareTo(
                    secondParticipant.role());

            if (comparison != 0)
                return comparison;

            String firstLocation = first.context().board()
                    .locationOf(firstParticipant.unit()).name();

            String secondLocation = second.context().board()
                    .locationOf(secondParticipant.unit()).name();

            comparison = firstLocation.compareTo(secondLocation);

            if (comparison != 0)
                return comparison;

        }

        return Integer.compare(
                firstParticipants.size(), secondParticipants.size());

    }


    // Registration identity \\

    private record Assignment(
            TacticDetector detective,
            TacticKind kind,
            String version
    ) { }


}