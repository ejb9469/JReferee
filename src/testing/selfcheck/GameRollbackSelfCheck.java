package testing.selfcheck;

import contracts.Snapshot;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.*;
import game.press.PressLedger;
import game.press.PressMessage;
import phase.*;
import phase.adjustments.*;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;
import phase.retreats.RetreatResult;

import java.time.Instant;
import java.util.*;

/**
 * Standalone checks for game checkpoints and in-place rollback. <b>Run without -ea.</b>
 */
@SuppressWarnings("deprecation")
public final class GameRollbackSelfCheck {

    private static int checks;

    private GameRollbackSelfCheck() { }

    public static void main(String[] args) {
        checkpointLifecycle();
        movement();
        retreats();
        adjustments();
        histories();
        failedResolution();
        ledgerRollback();
        orderCompatibility();
        System.out.println("GameRollbackSelfCheck passed: " + checks + " checks");
    }

    private static void checkpointLifecycle() {
        Game game = new Game(1907, new BoardState(Map.of(), Map.of()));
        Snapshot<GameState> snapshots = game;
        GameState initial = game.snapshot();
        require(snapshots.getSnapshot() == null, "A new game has no checkpoint");
        expect(IllegalStateException.class, snapshots::restoreFromSnapshot);
        require(game.snapshot().equals(initial), "Missing-checkpoint failure leaves the position unchanged");

        snapshots.takeSnapshot();
        GameState saved = snapshots.getSnapshot();
        require(saved.equals(initial), "The generic contract returns the saved GameState");
        expect(UnsupportedOperationException.class, () -> saved.board().locations().clear());
        game.resolveMovement(List.of());
        require(snapshots.getSnapshot() == saved, "Advancement retains the original checkpoint");
        GameState fall = game.snapshot();
        require(snapshots.getSnapshot() == saved, "Reading the current position does not replace the checkpoint");
        snapshots.takeSnapshot();
        require(snapshots.getSnapshot().equals(fall), "Taking another checkpoint replaces the first");
        Game branch = Game.fromState(snapshots.getSnapshot());
        require(branch.getSnapshot() == null, "A restored branch has its own checkpoint slot");
        game.resolveMovement(List.of());
        snapshots.restoreFromSnapshot();
        require(game.snapshot().equals(fall), "Restore uses the most recent checkpoint");
        require(snapshots.getSnapshot() == null, "Restore consumes the checkpoint");
        expect(IllegalStateException.class, snapshots::restoreFromSnapshot);
        require(branch.snapshot().equals(fall), "Rollback does not affect a separate branch");
        require(saved.equals(initial), "Previously returned snapshots remain usable");
        snapshots.takeSnapshot();
        snapshots.restoreFromSnapshot();
        require(game.snapshot().equals(fall), "Restoring without advancing is valid");
    }

    private static void movement() {
        UnitId french = army(Nation.FRANCE, Province.Par);
        for (GamePhase phase : List.of(GamePhase.SPRING_MOVEMENT, GamePhase.FALL_MOVEMENT)) {
            GameState state = new GameState_Movement(new GameMoment(1907, phase),
                    new BoardState(Map.of(french, Province.Pic), Map.of(Province.Bel, Nation.ENGLAND)));
            Game game = Game.fromState(state);
            game.takeSnapshot();
            game.resolveMovement(List.of(Order.move(french, Province.Bel)));
            require(game.board().locationOf(french) == Province.Bel, "The trial move succeeds");
            require(game.phase() == (phase == GamePhase.SPRING_MOVEMENT
                            ? GamePhase.FALL_MOVEMENT : GamePhase.WINTER_ADJUSTMENT),
                    "The trial skips unnecessary retreats");
            require(game.board().ownerOf(Province.Bel) ==
                            (phase == GamePhase.FALL_MOVEMENT ? Nation.FRANCE : Nation.ENGLAND),
                    "Only Fall changes center ownership");
            game.restoreFromSnapshot();
            require(game.snapshot().equals(state), "Movement rollback restores the complete position");
            require(game.processorHistory().isEmpty(), "Movement rollback removes completed trial processors");
            game.resolveMovement(List.of(Order.hold(french)));
            require(game.board().locationOf(french) == Province.Pic, "An alternate action starts at the saved position");
            require(game.board().ownerOf(Province.Bel) == Nation.ENGLAND, "The discarded capture does not leak");
        }
    }

    private static void retreats() {
        UnitId french = army(Nation.FRANCE, Province.Par);
        UnitId attacker = army(Nation.GERMANY, Province.Mun);
        UnitId supporter = army(Nation.GERMANY, Province.Ruh);
        for (GamePhase phase : List.of(GamePhase.SPRING_MOVEMENT, GamePhase.FALL_MOVEMENT)) {
            GameState initial = new GameState_Movement(new GameMoment(1907, phase),
                    new BoardState(Map.of(french, Province.Bur, attacker, Province.Mun, supporter, Province.Ruh),
                            Map.of(Province.Bel, Nation.ENGLAND)));
            Game game = Game.fromState(initial);
            game.takeSnapshot();
            List<Order> attack = List.of(Order.hold(french), Order.move(attacker, Province.Bur),
                    Order.supportMove(supporter, Province.Mun, Province.Bur));
            game.resolveMovement(attack);
            require(game.phase().isRetreat(), "The attack creates a retreat phase");
            game.restoreFromSnapshot();
            require(game.snapshot().equals(initial), "Rollback out of retreats restores movement");
            game.resolveMovement(List.of());
            require(!game.phase().isRetreat(), "The discarded dislodgement does not affect a later resolution");

            game = Game.fromState(initial);
            game.resolveMovement(attack);
            GameState saved = game.snapshot();
            List<Processor<? extends PhaseInput, ? extends PhaseResult>> history = game.processorHistory();
            game.takeSnapshot();
            game.resolveRetreats(List.of(new RetreatOrder(french, Province.Bel)));
            require(game.board().locationOf(french) == Province.Bel, "The trial retreat succeeds");
            game.restoreFromSnapshot();
            require(game.snapshot().equals(saved), "Retreat rollback restores the pending movement result");
            require(game.processorHistory().equals(history), "Retreat rollback preserves earlier processor history");
            require(game.board().ownerOf(Province.Bel) == Nation.ENGLAND, "Retreat rollback restores ownership");
            game.takeSnapshot();
            RetreatResult illegal = game.resolveRetreats(List.of(new RetreatOrder(french, Province.Mun)));
            require(illegal.wasDestroyed(french), "Restored retreat facts retain the attacker's origin restriction");
            game.restoreFromSnapshot();
            require(game.snapshot().equals(saved), "A failed retreat can also be rolled back");
            require(game.resolveRetreats(List.of()).wasDestroyed(french), "Omission can be chosen after rollback");
        }
    }

    private static void adjustments() {
        UnitId french = army(Nation.FRANCE, Province.Par);
        UnitId german = army(Nation.GERMANY, Province.Mun);
        UnitId removed = army(Nation.GERMANY, Province.Ber);
        BoardState board = new BoardState(Map.of(french, Province.Bur, german, Province.Mun, removed, Province.Ber),
                Map.of(Province.Par, Nation.FRANCE, Province.Bre, Nation.FRANCE, Province.Mun, Nation.GERMANY));
        MovementResult movement = movementProcessor().process(new MovementInput(board.locations(), List.of()));
        RetreatResult retreats = new RetreatProcessor().process(new RetreatInput(movement, List.of()));
        GameState state = new GameState_Adjustments(new GameMoment(1907, GamePhase.WINTER_ADJUSTMENT), board, retreats);
        Game game = Game.fromState(state);
        game.takeSnapshot();
        AdjustmentResult result = game.resolveAdjustments(List.of(
                new BuildOrder(Nation.FRANCE, UnitType.FLEET, Province.Bre),
                new DisbandOrder(Nation.GERMANY, removed)));
        UnitId built = result.builtUnits().iterator().next();
        require(game.board().locationOf(built) == Province.Bre, "The trial creates a new unit");
        require(!game.board().locations().containsKey(removed), "The trial removes the selected unit");
        require(game.year() == 1908, "Winter advances the year");
        game.resolveMovement(List.of());
        game.restoreFromSnapshot();
        require(game.snapshot().equals(state), "Rollback across multiple phases restores the original winter");
        require(game.board().locationOf(removed) == Province.Ber, "Rollback restores the disbanded identity");
        require(!game.board().locations().containsKey(built), "Rollback removes trial-built units");
        require(result.builtUnits().contains(built), "Previously returned results remain unchanged");
        require(game.processorHistory().isEmpty(), "All processors after the winter checkpoint are discarded");
        game.resolveAdjustments(List.of(new WaiveOrder(Nation.FRANCE), new DisbandOrder(Nation.GERMANY, german)));
        require(game.board().locationOf(removed) == Province.Ber, "An alternate disband keeps the other unit");
        require(!game.board().locations().containsKey(german), "An alternate disband removes the chosen unit");
        require(game.board().locations().size() == 2, "Waiving after rollback does not retain the old build");
    }
    private static void histories() {
        PressLedger ledger = new PressLedger();
        Game game = new Game(1907, new BoardState(Map.of(), Map.of()),
                movementProcessor(), new RetreatProcessor(), new AdjustmentProcessor(), ledger);
        game.recordPress(message(game));
        game.resolveMovement(List.of());
        game.recordPress(message(game));
        GameState saved = game.snapshot();
        List<PressMessage> oldPress = game.pressHistory();
        List<Processor<? extends PhaseInput, ? extends PhaseResult>> oldProcessors = game.processorHistory();
        game.takeSnapshot();
        game.resolveMovement(List.of());
        PressMessage winterMessage = message(game);
        game.recordPress(winterMessage);
        game.resolveAdjustments(List.of());
        PressMessage springMessage = message(game);
        game.recordPress(springMessage);
        List<PressMessage> trialPress = game.pressHistory();
        require(game.processorHistory().size() == 4, "Trial history includes the skipped Fall retreat");
        game.restoreFromSnapshot();
        require(game.snapshot().equals(saved), "Rollback restores the saved Fall position");
        require(game.processorHistory().equals(oldProcessors), "Rollback preserves the earlier processor prefix");
        require(game.pressHistory().equals(oldPress), "Rollback preserves the earlier press prefix");
        require(ledger.messages().equals(oldPress), "Rollback updates the supplied ledger rather than replacing it");
        require(!ledger.containsId(winterMessage.id()) && !ledger.containsId(springMessage.id()),
                "Rollback releases IDs of discarded messages");
        require(trialPress.size() == 4, "Previously returned press lists remain unchanged");
        game.resolveMovement(List.of());
        game.recordPress(winterMessage);
        require(ledger.containsId(winterMessage.id()), "A discarded message ID can be used again");

        game.takeSnapshot();
        GameState beforeFailure = game.snapshot();
        ledger.truncate(0);
        expect(IllegalStateException.class, game::restoreFromSnapshot);
        require(game.snapshot().equals(beforeFailure), "A shortened-ledger error does not alter the position");
        require(game.getSnapshot() != null, "A failed restore does not consume the checkpoint");
    }

    private static void failedResolution() {
        UnitId french = army(Nation.FRANCE, Province.Par);
        int[] calls = new int[1];
        Processor<RetreatInput, RetreatResult> failingRetreats = input -> {
            calls[0]++;
            throw new IllegalStateException("Retreat processor failure");
        };
        GameState state = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Pic), Map.of()));
        Game game = Game.fromState(state, movementProcessor(), failingRetreats, new AdjustmentProcessor());
        game.takeSnapshot();
        expect(IllegalStateException.class, () -> game.resolveMovement(List.of(Order.move(french, Province.Bel))));
        require(game.board().locationOf(french) == Province.Bel, "Fixture fails after movement updates the board");
        require(game.processorHistory().size() == 1, "Fixture has a partially recorded transition");
        game.restoreFromSnapshot();
        require(game.snapshot().equals(state), "Rollback recovers from a partially completed phase transition");
        require(game.processorHistory().isEmpty(), "Rollback removes the partial transition's history");
        require(calls[0] == 1, "Rollback does not rerun or rewind custom processor internals");
    }

    private static void ledgerRollback() {
        Game game = new Game(1907, new BoardState(Map.of(), Map.of()));
        PressMessage first = message(game);
        PressMessage second = message(game);
        PressLedger ledger = new PressLedger(List.of(first, second));
        expect(IllegalArgumentException.class, () -> ledger.truncate(-1));
        expect(IllegalArgumentException.class, () -> ledger.truncate(3));
        require(ledger.messages().equals(List.of(first, second)), "Invalid truncation leaves the ledger unchanged");
        ledger.truncate(2);
        ledger.truncate(1);
        require(ledger.messages().equals(List.of(first)), "Truncation preserves the requested prefix");
        expect(IllegalArgumentException.class, () -> ledger.append(first));
        ledger.append(second);
        require(ledger.messages().equals(List.of(first, second)), "Discarded IDs can be reused without duplicates");
        ledger.truncate(0);
        require(ledger.size() == 0 && !ledger.containsId(first.id()) && !ledger.containsId(second.id()),
                "Truncating to zero clears messages and IDs");
    }

    private static void orderCompatibility() {
        adjudication.Order order = new adjudication.Order(
                Nation.FRANCE, UnitType.ARMY, Province.Par, OrderType.MOVE, Province.Bur);
        Snapshot<adjudication.Order> snapshots = order;
        snapshots.takeSnapshot();
        order.orderType = OrderType.HOLD;
        order.pos1 = null;
        snapshots.restoreFromSnapshot();
        require(order.orderType == OrderType.MOVE && order.pos1 == Province.Bur,
                "Generic Snapshot preserves the existing order rollback");
        require(snapshots.getSnapshot() == null, "Order rollback still consumes its snapshot");
    }

    private static MovementProcessor movementProcessor() {
        return new MovementProcessor(MovementProcessor.Policy.SZYKMAN_JUSTICE, 4, 0L);
    }

    private static UnitId army(Nation nation, Province origin) {
        return new UnitId(UUID.randomUUID(), nation, UnitType.ARMY, origin);
    }

    private static PressMessage message(Game game) {
        return new PressMessage(UUID.randomUUID(), new Moment(game.year(), game.phase(), Instant.EPOCH),
                Nation.FRANCE, Set.of(Nation.GERMANY), "Orders", "Ready.");
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable operation) {
        checks++;
        try {
            operation.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception))
                return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + exception, exception);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

}