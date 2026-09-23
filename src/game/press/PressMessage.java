package game.press;

import static adjudication.util.Constants.STARTING_YEAR;
import domain.Nation;
import game.GamePhase;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable message sent during a game.
 *
 * <p>A press message is game-domain data, i.e. NOT suitable for network delivery.<br>
 * A {@link PressProcedure} decides how, if at all, to deliver it.</p>
 */
public record PressMessage(
        UUID id,
        int year,
        GamePhase phase,
        Nation sender,
        Set<Nation> recipients,
        String subject,
        String body,
        Instant sentAt
) {

    public PressMessage {

        Objects.requireNonNull(id, "id");

        if (year < STARTING_YEAR)  // `STARTING_YEAR` from the adjudication pkg!
            throw new IllegalArgumentException("year must be at least " + STARTING_YEAR);

        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(sender, "sender");

        recipients = Set.copyOf(Objects.requireNonNull(
                recipients, "recipients"));

        if (recipients.isEmpty())
            throw new IllegalArgumentException("Press requires at least one recipient");

        Objects.requireNonNull(subject, "subject");

        if (subject.isBlank())
            throw new IllegalArgumentException("Press subject must not be blank");

        Objects.requireNonNull(body, "body");

        if (body.isBlank())
            throw new IllegalArgumentException("Press body must not be blank");

        Objects.requireNonNull(sentAt, "sentAt");

    }

    /**
     * Returns whether a nation may view this message through the official game
     * press history.
     *
     * <p>The sender always retains visibility, even when they are not one of
     * the recipients.</p>
     */
    public boolean isVisibleTo(Nation nation) {
        Objects.requireNonNull(nation, "nation");
        return( sender == nation || recipients.contains(nation) );
    }

}