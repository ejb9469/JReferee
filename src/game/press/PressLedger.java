package game.press;

import domain.Nation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;


/**
 * Authoritative press timeline for one game.
 *
 * <p>This class records and queries press only. It does not generate IDs,
 * access a clock, send messages, retain recipient inboxes, or modify game
 * state.</p>
 *
 * <p>Message append order is authoritative. Do not sort messages by
 * {@code Moment.instant()}: imported records can contain equal or imperfect
 * timestamps, while append order preserves the stored game timeline.
 * Rollback may discard a suffix through {@link #truncate(int)}.</p>
 */
@Deprecated
public final class PressLedger {


    private final List<PressMessage> messages = new ArrayList<>();
    private final Set<UUID> messageIds = new HashSet<>();


    public PressLedger() {
    }

    /**
     * Recreates a ledger from a persisted press timeline.
     *
     * <p>The supplied collection's iteration order becomes the authoritative
     * append order for the recreated ledger.</p>
     */
    public PressLedger(Collection<PressMessage> recordedMessages) {
        Objects.requireNonNull(recordedMessages, "recordedMessages");
        for (PressMessage message : recordedMessages)
            append(message);
    }

    /**
     * Records an already-created press message,
     * and returns the same message. This allows inline-fu tricks.
     *
     * @return the same immutable message supplied by the caller
     */
    public PressMessage append(PressMessage message) {

        Objects.requireNonNull(message, "message");

        if (!messageIds.add(message.id()))
            throw new IllegalArgumentException("Press message ID already exists: " + message.id());

        messages.add(message);
        return message;

    }

    /**
     * Returns every message in authoritative append order.
     */
    public List<PressMessage> messages() {
        return List.copyOf(messages);
    }

    /**
     * Returns messages visible to one power, preserving append order.
     */
    public List<PressMessage> visibleTo(Nation nation) {

        Objects.requireNonNull(nation, "nation");

        List<PressMessage> visible = new ArrayList<>();

        for (PressMessage message : messages) {
            if (message.isVisibleTo(nation))
                visible.add(message);
        }

        return List.copyOf(visible);

    }

    public boolean containsId(UUID messageId) {
        return messageIds.contains(
                Objects.requireNonNull(messageId, "messageId"));
    }

    public int size() {
        return messages.size();
    }

    /**
     * Keeps the first retainedCount messages, releasing discarded message IDs
     * so they may be submitted again after rollback.
     */
    public void truncate(int retainedCount) {
        if (retainedCount < 0 || retainedCount > messages.size())
            throw new IllegalArgumentException("retainedCount must be between zero and the current size");

        while (messages.size() > retainedCount)
            messageIds.remove(messages.removeLast().id());
    }

}