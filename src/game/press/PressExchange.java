package game.press;

import domain.Nation;
import game.GamePhase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Authoritative per-game press ledger and delivery coordinator.
 *
 * <p>Every accepted message is retained in chronological game history before
 * registered delivery procedures are invoked. This makes game press available
 * for simulation and audit even if a future external transport fails.</p>
 */
public final class PressExchange {

    private final List<PressMessage> history = new ArrayList<>();

    /*
     * LinkedHashSet preserves registration/delivery order while avoiding
     * duplicate registration of the same procedure instance.
     */
    private final Set<PressProcedure> procedures =
            new LinkedHashSet<>();

    /**
     * Registers a procedure that receives every future accepted message.
     *
     * <p>Existing history is not replayed automatically. A replay/import
     * feature can be added later with explicit delivery semantics.</p>
     */
    public void registerProcedure(PressProcedure procedure) {
        procedures.add(Objects.requireNonNull(
                procedure,
                "procedure"));
    }

    /**
     * Stops delivering future messages through one procedure.
     *
     * @return whether the procedure had been registered
     */
    public boolean unregisterProcedure(PressProcedure procedure) {
        return procedures.remove(Objects.requireNonNull(
                procedure,
                "procedure"));
    }

    /**
     * Creates, records, and dispatches one press message.
     *
     * <p>The message enters {@linkplain #history() game history} before
     * delivery begins. If one or more procedures fail, all remaining
     * procedures are still attempted and an exception is thrown afterward.</p>
     *
     * @param year current game year
     * @param phase current game phase
     * @param sender nation sending the message
     * @param recipients intended recipient nations
     * @param body message text
     * @return the immutable recorded message
     */
    public PressMessage send(
            int year,
            GamePhase phase,
            Nation sender,
            Collection<Nation> recipients,
            String subject,
            String body
    ) {
        PressMessage message = new PressMessage(
                UUID.randomUUID(),
                year,
                Objects.requireNonNull(phase, "phase"),
                Objects.requireNonNull(sender, "sender"),
                Set.copyOf(Objects.requireNonNull(
                        recipients,
                        "recipients")),
                subject, body,
                Instant.now());

        history.add(message);
        deliver(message);

        return message;
    }

    /**
     * Returns every accepted message in chronological send order.
     */
    public List<PressMessage> history() {
        return List.copyOf(history);
    }

    /**
     * Returns messages visible to a nation in chronological send order.
     *
     * <p>A nation sees messages it sent and messages addressed to it.</p>
     */
    public List<PressMessage> visibleTo(Nation nation) {
        Objects.requireNonNull(nation, "nation");

        List<PressMessage> visible = new ArrayList<>();

        for (PressMessage message : history) {
            if (message.isVisibleTo(nation)) {
                visible.add(message);
            }
        }

        return List.copyOf(visible);
    }

    /**
     * Returns a snapshot of currently registered delivery procedures.
     */
    public List<PressProcedure> procedures() {
        return List.copyOf(procedures);
    }

    private void deliver(PressMessage message) {
        RuntimeException failure = null;

        for (PressProcedure procedure : procedures) {
            try {
                procedure.deliver(message);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }

        if (failure != null) {
            throw new IllegalStateException(
                    "Press was recorded, but one or more procedures failed to deliver it",
                    failure);
        }
    }

}