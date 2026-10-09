package analysis.tactics;

import analysis.tactics.detective.*;

import java.util.*;


/**
 * Coordinates structural tactical patterns and diagnostics.
 *
 * <p>Registration and evidence availability are separate from findings.
 * An unimplemented or skipped category is not an evaluated negative result.</p>
 *
 * <p>This agency uses the standard Province map. It does not supply
 * adjudication, history, agreements, strategic objectives, or intent.</p>
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
                new BogusMovesDetective(),
                new ForeignCooperationDetective(),
                new FriendlyOccupantCollisionDetective(),
                new HeadToHeadDetective(),
                new CircularMovementDetective(),
                new AttackOnSupporterDetective(),
                new AttackOnConvoyFleetDetective(),
                new MultipleSupportToMoveDetective(),
                new MultipleSupportToHoldDetective(),
                new MutualHoldSupportDetective(),
                new SupportNetworkDetective(),
                new SupportingASupporterDetective(),
                new SupportingAConvoyFleetDetective(),
                new CrossPowerSupportDetective(),
                new MultinationalSupportedAttackDetective(),
                new CrossPowerContestDetective(),
                new MultiwayContestDetective(),
                new FollowTheLeaderDetective(),
                new ChainAdvanceDetective(),
                new VacateAndReplaceDetective(),
                new AttackFromSupportedDestinationDetective(),
                new ConvoyedMoveDetective(),
                new MultiFleetConvoyDetective(),
                new MultiRouteConvoyDetective(),
                new ForeignConvoyDetective(),
                new MultinationalConvoyDetective(),
                new SupportedConvoyLandingDetective(),
                new ConvoySwapDetective(),
                new AdjacentProvinceConvoyDetective()));
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

            if (registered.putIfAbsent(
                    kind, new Assignment(detective, kind, version)) != null)
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

    public InvestigationReport investigateReport(TacticalContext context) {

        Objects.requireNonNull(context, "context");

        List<TacticMatch> findings = new ArrayList<>();
        Set<TacticMatch> observed = new HashSet<>();

        List<InvestigationCoverage.KindCoverage> coverage =
                new ArrayList<>();

        Set<EvidenceCapability> available = EnumSet.of(
                EvidenceCapability.MOVEMENT_POSITION,
                EvidenceCapability.KNOWN_MOVEMENT_ORDERS,
                EvidenceCapability.MAP_ADJACENCY);

        for (TacticKind kind : TacticKind.values()) {

            Assignment assignment = assignments.get(kind);

            if (assignment == null) {

                coverage.add(new InvestigationCoverage.KindCoverage(
                        kind, false, false,
                        InvestigationCoverage.Status.UNSUPPORTED_UNIMPLEMENTED,
                        0, Set.of()));

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
                        kind, true, false,
                        InvestigationCoverage.Status.SKIPPED_MISSING_EVIDENCE,
                        0, missing));

                continue;

            }

            List<TacticMatch> discovered =
                    assignment.detective().detect(context);

            verifyIdentity(assignment);

            if (discovered == null)
                throw new IllegalStateException(
                        "Detective returned a null result: " + kind);

            int before = findings.size();

            for (TacticMatch match : discovered) {

                verifyMatch(assignment, context, match);

                if (!observed.add(match))
                    throw new IllegalStateException(
                            "Duplicate tactical occurrence: " + kind);

                findings.add(match);

            }

            int count = findings.size() - before;

            coverage.add(new InvestigationCoverage.KindCoverage(
                    kind, true, true,
                    count == 0
                            ? InvestigationCoverage.Status.EVALUATED_WITH_NO_FINDINGS
                            : InvestigationCoverage.Status.EVALUATED_WITH_FINDINGS,
                    count, Set.of()));

        }

        findings.sort(DetectiveAgency::compareMatches);

        return new InvestigationReport(
                context,
                findings,
                new InvestigationCoverage(
                        InvestigationCoverage.Scope.COMPLETE_INVESTIGATION,
                        coverage));

    }


    // Contract validation \\

    private static void verifyIdentity(Assignment assignment) {

        if (assignment.detective().kind() != assignment.kind()
                || !assignment.version().equals(
                assignment.detective().version()))
            throw new IllegalStateException(
                    "Detective identity changed: " + assignment.kind());

    }

    private static void verifyMatch(
            Assignment assignment,
            TacticalContext context,
            TacticMatch match
    ) {

        if (match == null)
            throw new IllegalStateException(
                    "Null finding from " + assignment.kind());

        if (match.kind() != assignment.kind()
                || !match.detectorVersion().equals(assignment.version())
                || match.context() != context)
            throw new IllegalStateException(
                    "Finding violates detective contract: " + assignment.kind());

    }


    // Stable ordering \\

    private static int compareMatches(TacticMatch first, TacticMatch second) {

        int comparison = first.kind().compareTo(second.kind());

        if (comparison != 0)
            return comparison;

        comparison = first.focus().name().compareTo(second.focus().name());

        if (comparison != 0)
            return comparison;

        int shared = Math.min(
                first.participants().size(), second.participants().size());

        for (int index = 0; index < shared; index++) {

            TacticMatch.Participant a = first.participants().get(index);
            TacticMatch.Participant b = second.participants().get(index);

            comparison = a.role().compareTo(b.role());

            if (comparison != 0)
                return comparison;

            comparison = first.context().board().locationOf(a.unit()).name()
                    .compareTo(second.context().board().locationOf(b.unit()).name());

            if (comparison != 0)
                return comparison;

        }

        return Integer.compare(
                first.participants().size(), second.participants().size());

    }


    // Registration identity \\

    private record Assignment(
            TacticDetector detective,
            TacticKind kind,
            String version
    ) { }


}