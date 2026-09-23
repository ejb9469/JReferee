package game.press;

import domain.Nation;
import game.Moment;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable persisted Diplomacy press.
 *
 * <p>This is game-record data only. It does not know how to save or deliver
 * itself, and it does not communicate with UI, network, inbox, or simulation
 * infrastructure.</p>
 *
 * <p>Callers supply the stable ID and {@link Moment}. This keeps replay,
 * importing, persistence, and testing deterministic.</p>
 */
public record PressMessage(
        UUID id,
        Moment moment,
        Nation sender,
        Set<Nation> recipients,
        String subject,
        String body
) {

    public PressMessage {

        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(sender, "sender");

        recipients = Set.copyOf(Objects.requireNonNull(recipients, "recipients"));
        if (recipients.isEmpty())
            throw new IllegalArgumentException("Press requires at least one recipient");

        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank())
            throw new IllegalArgumentException("Press subject must not be blank");

        Objects.requireNonNull(body, "body");
        if (body.isBlank())
            throw new IllegalArgumentException("Press body must not be blank");

    }

    /**
     * The sender can always view their sent message. Each named recipient can
     * also view it; all other powers cannot.
     */
    public boolean isVisibleTo(Nation nation) {
        Objects.requireNonNull(nation, "nation");

        return sender == nation || recipients.contains(nation);
    }

}