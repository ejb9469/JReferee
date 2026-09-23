package game.press;

import domain.Nation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * Synchronous in-memory press delivery for simulations, local clients, and
 * standalone testing.
 *
 * <p>Each delivered message is copied to the inbox of every listed recipient.
 * The sender's authoritative sent-message history remains in
 * {@link PressExchange}; a sender is placed in an inbox only when explicitly
 * included in the message recipient set.</p>
 */
public final class MemoryPipePressProcedure
        implements PressProcedure {


    private final Map<Nation, List<PressMessage>> inboxes =
            new EnumMap<>(Nation.class);


    /**
     * Places the message in each recipient's inbox.
     */
    @Override
    public void deliver(PressMessage message) {
        Objects.requireNonNull(message, "message");

        for (Nation recipient : message.recipients()) {
            inboxes.computeIfAbsent(
                    recipient,
                    ignored -> new ArrayList<>()
            ).add(message);
        }
    }


    /**
     * Returns an immutable snapshot of a nation's current inbox.
     */
    public List<PressMessage> inboxOf(Nation nation) {
        Objects.requireNonNull(nation, "nation");

        return List.copyOf(
                inboxes.getOrDefault(nation, List.of()));
    }


    /**
     * Returns the current inbox contents and clears that inbox.
     *
     * <p>This is useful for turn-by-turn simulation agents that consume all
     * newly delivered press before deciding what to send or order next.</p>
     */
    public List<PressMessage> popInboxOf(Nation nation) {
        Objects.requireNonNull(nation, "nation");

        List<PressMessage> inbox = inboxes.get(nation);

        if (inbox == null || inbox.isEmpty()) {
            return List.of();
        }

        List<PressMessage> delivered = List.copyOf(inbox);
        inbox.clear();

        return delivered;
    }


}