package game.press;

/**
 * A delivery adapter for game press.
 * THIS IS "TRUSTED INFRASTRUCTURE"; unsafe for actual games (everything public)
 *
 * <p>Procedures receive already-validated, immutable press messages from
 * {@link PressExchange}.<br>
 * They do not decide message visibility, mutate game state, or adjudicate orders.</p>
 *
 * <p>Examples include an in-memory simulation pipe, a file writer, an HTTP
 * client, or an SMTP sender.<br>
 * Inbound polling/import is intentionally outside this first outbound-delivery contract.</p>
 */
@FunctionalInterface
public interface PressProcedure {

    /**
     * Delivers one message.
     *
     * <p>Implementations may throw a runtime exception for an external
     * delivery failure. The exchange records the message in game history
     * before invoking procedures, so an external transport failure cannot
     * erase the message from the game's authoritative record.</p>
     */
    void deliver(PressMessage message);

    // deliberately leaving out inbox-related methods from here,
    // subclasses should handle this (until future change is needed)

}