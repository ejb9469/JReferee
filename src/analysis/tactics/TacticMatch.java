package analysis.tactics;

import domain.Province;
import phase.UnitId;

import java.util.*;


/**
 * Immutable occurrence of a structural tactical pattern.<br><br>
 *
 * A detector is responsible for its category-specific matching rules.
 * This record validates the shared participant and prerequisite structure.
 *
 * <p>Equality identifies a concrete occurrence and its context.
 * It is not a UUID-independent historical pattern identity.</p>
 */
public record TacticMatch(
        TacticKind kind,
        String detectorVersion,
        TacticalContext context,
        Province focus,
        List<Participant> participants,
        Set<UnitId> missingOrders
) {


    // Construction and validation \\

    public TacticMatch {

        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detectorVersion, "detectorVersion");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(focus, "focus");

        if (detectorVersion.isBlank())
            throw new IllegalArgumentException(
                    "detectorVersion must not be blank");

        participants = List.copyOf(
                Objects.requireNonNull(participants, "participants"));

        missingOrders = Set.copyOf(
                Objects.requireNonNull(missingOrders, "missingOrders"));

        if (participants.isEmpty())
            throw new IllegalArgumentException(
                    "A match requires participants");

        if (new HashSet<>(participants).size() != participants.size())
            throw new IllegalArgumentException(
                    "Duplicate participant role");

        Set<UnitId> involved = new HashSet<>();

        for (Participant participant : participants) {

            if (!context.board().locations().containsKey(participant.unit()))
                throw new IllegalArgumentException(
                        "Inactive participant: " + participant.unit());

            involved.add(participant.unit());

        }

        for (UnitId unit : missingOrders) {

            if (!involved.contains(unit))
                throw new IllegalArgumentException(
                        "Missing prerequisite must name a participant");

            if (context.orders().containsKey(unit))
                throw new IllegalArgumentException(
                        "Order is already known: " + unit);

        }

        // The tactical focus is a territory; exact coasts remain in context.
        focus = Province.canonical(focus);

        TacticalContext position = context;

        List<Participant> sortedParticipants =
                new ArrayList<>(participants);

        sortedParticipants.sort(
                Comparator.comparing(Participant::role)
                        .thenComparing(participant ->
                                position.board()
                                        .locationOf(participant.unit())
                                        .name()));

        participants = List.copyOf(sortedParticipants);

        List<UnitId> sortedMissing = new ArrayList<>(missingOrders);

        sortedMissing.sort(Comparator.comparing(
                unit -> position.board().locationOf(unit).name()));

        missingOrders = Collections.unmodifiableSet(
                new LinkedHashSet<>(sortedMissing));

    }


    // Pattern queries \\

    /**
     * Returns whether the local pattern prerequisites are complete.<br><br>
     *
     * This does not imply that every order in the surrounding scenario
     * is known, or that the pattern will succeed.
     */
    public boolean completePattern() {
        return missingOrders.isEmpty();
    }

    public List<UnitId> units(Role role) {

        Objects.requireNonNull(role, "role");

        List<UnitId> selected = new ArrayList<>();

        for (Participant participant : participants)
            if (participant.role() == role)
                selected.add(participant.unit());

        return List.copyOf(selected);

    }


    // Participant roles \\

    public enum Role {

        SUPPORTER,
        SUPPORTED_UNIT,
        ATTACKER,
        DEFENDER

    }

    public record Participant(
            Role role,
            UnitId unit
    ) {

        public Participant {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(unit, "unit");
        }

    }


}