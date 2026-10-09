package analysis.tactics.detective;

import analysis.tactics.TacticDetector;
import analysis.tactics.TacticKind;
import analysis.tactics.TacticMatch;
import analysis.tactics.TacticalContext;

import java.util.*;


/**
 * Base implementation of a structural tactical detective.<br><br>
 *
 * Concrete detectives examine known order relationships.
 * They do not adjudicate orders, infer player intent, or assign
 * strategic value.
 *
 * <p>This class owns the common investigation boundary:
 * context validation, stable result ordering, and immutable output.</p>
 *
 * <p>Each instance retains an immutable algorithm version. A detective
 * whose matching semantics change must explicitly select a new version.</p>
 */
public abstract class Detective implements TacticDetector {


    // abstract class is justified here given our polymorphic architecture


    // Constants \\

    private static final String LEGACY_VERSION = "structure-v1";


    // Core state \\

    private final String version;


    // Constructors \\

    /**
     * Preserves the original version for unchanged detective subclasses.
     */
    protected Detective() {
        this(LEGACY_VERSION);
    }

    /**
     * Assigns the version of this detective's matching algorithm.<br><br>
     *
     * The version is independent of the catalogue definition's semantic
     * version. It must not change during the lifetime of the detective.
     */
    protected Detective(String version) {

        Objects.requireNonNull(version, "version");

        if (version.isBlank())
            throw new IllegalArgumentException(
                    "Detective version must not be blank");

        this.version = version;

    }


    // Detective identity \\

    @Override
    public abstract TacticKind kind();

    @Override
    public final String version() {
        return version;
    }


    // Investigation \\

    /**
     * Examines the supplied context and returns all matching occurrences.<br><br>
     *
     * Results are ordered by canonical focus, then participant roles and
     * actual board locations. UUIDs do not determine presentation order.
     */
    public final List<TacticMatch> investigate(TacticalContext context) {

        Objects.requireNonNull(context, "context");

        List<TacticMatch> findings = new ArrayList<>(examine(context));

        findings.sort(Detective::compareMatches);

        return List.copyOf(findings);

    }

    /**
     * Compatibility entry point for callers using TacticDetector.
     */
    @Override
    public final List<TacticMatch> detect(TacticalContext context) {
        return investigate(context);
    }

    /**
     * Performs the category-specific examination.<br><br>
     *
     * The context is non-null. Implementations must return a non-null,
     * duplicate-free collection of matches belonging to this detective
     * and the supplied context.
     *
     * <p>Every match must use this detective's kind() and version().</p>
     *
     * <p>The returned list may be mutable and unsorted; investigate(...)
     * copies and orders it before exposing the results.</p>
     */
    protected abstract List<TacticMatch> examine(TacticalContext context);


    // Stable ordering \\

    private static int compareMatches(TacticMatch first, TacticMatch second) {

        int comparison = first.focus().name().compareTo(second.focus().name());

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


}