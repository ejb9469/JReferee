package analysis.tactics;

import java.util.*;


/**
 * Coordinates structural investigations across tactical detectives.<br><br>
 *
 * Each registered detective examines the same context. Findings from
 * different categories are retained even when their participants overlap.
 *
 * <p>The agency validates the returned findings, orders them consistently,
 * and exposes an immutable result. It does not adjudicate orders or select
 * a preferred tactical interpretation.</p>
 */
public final class DetectiveAgency {


    // Core state \\

    private final Map<TacticKind, Assignment> assignments;


    // Constructors \\

    /**
     * Registers the four standard structural detectives.
     */
    public DetectiveAgency() {
        this(List.of(
                new SupportToMoveDetective(),
                new SupportToHoldDetective(),
                new SelfBounceDetective(),
                new BeleagueredGarrisonDetective()));
    }

    /**
     * Registers one detective per tactical category.<br><br>
     *
     * Subsets and an empty collection are allowed. Duplicate categories
     * are rejected, including repeated registration of the same instance.
     *
     * <p>The collection is copied. Detective instances themselves are
     * retained and must obey the pure investigation contract.</p>
     */
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

            Assignment assignment = new Assignment(
                    detective, kind, version);

            if (registered.putIfAbsent(kind, assignment) != null)
                throw new IllegalArgumentException(
                        "Multiple detectives registered for " + kind);

        }

        this.assignments = Collections.unmodifiableMap(registered);

    }


    // Registry queries \\

    /**
     * Returns registered categories in TacticKind declaration order.
     */
    public List<TacticKind> kinds() {
        return List.copyOf(assignments.keySet());
    }


    // Investigation \\

    /**
     * Runs every registered detective against the supplied context.<br><br>
     *
     * Results are ordered by category, canonical focus, and participant
     * roles and actual board locations. Registration order does not
     * determine output order.
     *
     * <p>A detective failure or contract violation aborts the investigation.
     * No partial result is returned.</p>
     */
    public List<TacticMatch> investigate(TacticalContext context) {

        Objects.requireNonNull(context, "context");

        List<TacticMatch> findings = new ArrayList<>();
        Set<TacticMatch> observed = new HashSet<>();

        for (Assignment assignment : assignments.values()) {

            TacticDetector detective = assignment.detective();

            // Registration captures identity; changing it later is invalid.
            if (detective.kind() != assignment.kind()
                    || !assignment.version().equals(detective.version()))
                throw new IllegalStateException(
                        "Detective identity changed after registration: "
                                + assignment.kind());

            List<TacticMatch> discovered = detective.detect(context);

            if (discovered == null)
                throw new IllegalStateException(
                        "Detective returned a null result: "
                                + assignment.kind());

            for (TacticMatch match : discovered) {

                verifyMatch(assignment, context, match);

                /*
                 * Different categories may describe the same orders.
                 * Only duplicate occurrences, not overlapping patterns,
                 * are rejected.
                 */
                if (!observed.add(match))
                    throw new IllegalStateException(
                            "Detective returned a duplicate occurrence: "
                                    + assignment.kind());

                findings.add(match);

            }

        }

        findings.sort(DetectiveAgency::compareMatches);

        return List.copyOf(findings);

    }


    // Finding validation \\

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
                    "Match belongs to a different tactical category: "
                            + assignment.kind());

        if (!match.detectorVersion().equals(assignment.version()))
            throw new IllegalStateException(
                    "Match uses a different detective version: "
                            + assignment.kind());

        // Require the supplied context, not an equivalent reconstructed one.
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
                firstParticipants.size(),
                secondParticipants.size());

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
                firstParticipants.size(),
                secondParticipants.size());

    }


    // Registration identity \\

    private record Assignment(
            TacticDetector detective,
            TacticKind kind,
            String version
    ) { }


}