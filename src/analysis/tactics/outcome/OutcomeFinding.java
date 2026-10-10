package analysis.tactics.outcome;

import analysis.tactics.TacticKind;
import domain.Province;
import phase.UnitId;

import java.util.*;


/**
 * An occurrence recognized within an identified movement resolution.
 *
 * <p>Common validation belongs here. Category-specific conditions belong
 * to the producing OutcomeDetective.</p>
 *
 * <p>Participants refer to initial units, including those later dislodged.
 * Exact positions and submissions remain in the attached context.</p>
 */
public record OutcomeFinding(
        TacticKind kind,
        String investigatorVersion,
        ResolvedMovementContext context,
        Province focus,
        List<Participant> participants
) {


    // Construction \\

    public OutcomeFinding {

        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(investigatorVersion, "investigatorVersion");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(focus, "focus");

        if (investigatorVersion.isBlank())
            throw new IllegalArgumentException(
                    "investigatorVersion must not be blank");

        participants = List.copyOf(
                Objects.requireNonNull(participants, "participants"));

        if (participants.isEmpty())
            throw new IllegalArgumentException(
                    "An outcome finding requires participants");

        if (new HashSet<>(participants).size() != participants.size())
            throw new IllegalArgumentException(
                    "Duplicate participant role");

        for (Participant participant : participants)
            context.initialLocationOf(participant.unit());

        focus = Province.canonical(focus);

        List<Participant> sorted = new ArrayList<>(participants);
        ResolvedMovementContext evidence = context;

        sorted.sort(
                Comparator.comparing(Participant::role)
                        .thenComparing(participant ->
                                evidence.initialLocationOf(
                                        participant.unit()).name()));

        participants = List.copyOf(sorted);

    }


    // Participant queries \\

    public List<UnitId> units(Role role) {

        Objects.requireNonNull(role, "role");

        List<UnitId> units = new ArrayList<>();

        for (Participant participant : participants)
            if (participant.role() == role)
                units.add(participant.unit());

        return List.copyOf(units);

    }


    // Roles \\

    public enum Role {
        SUPPORTER,
        DISLODGED_UNIT,
        DISLODGER
    }

    public record Participant(Role role, UnitId unit) {

        public Participant {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(unit, "unit");
        }

    }


}