package testing.selfcheck;

import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.*;
import game.press.PressMessage;
import phase.*;
import phase.adjustments.*;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;
import phase.retreats.RetreatResult;
import testing.TestCase;
import testing.TestEvaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GameRestorationTestCase implements TestCase {

    private static final UnitId FRENCH = unit(Nation.FRANCE, Province.Par);
    private static final UnitId ATTACKER = unit(Nation.GERMANY, Province.Mun);
    private static final UnitId SUPPORTER = unit(Nation.GERMANY, Province.Ruh);

    private TestEvaluation evaluation;

    @Override
    public void eval(boolean print) {
        TestEvaluation.Builder checks = new TestEvaluation.Builder();
        checkMovement(checks);
        checkRetreats(checks);
        checkWinter(checks);
        checkHistories(checks);
        checkProcessors(checks);
        checkInputs(checks);
        evaluation = checks.build();
        if (print)
            printEval();
    }

    private static void checkMovement(TestEvaluation.Builder checks) {
        for (GamePhase phase : List.of(GamePhase.SPRING_MOVEMENT, GamePhase.FALL_MOVEMENT)) {
            GameState state = new GameState_Movement(new GameMoment(1907, phase),
                    new BoardState(Map.of(FRENCH, Province.Pic),
                            Map.of(Province.Par, Nation.FRANCE, Province.Bel, Nation.ENGLAND)));
            Game first = Game.fromState(state);
            Game second = Game.fromState(state);
            checks.expect("Movement position round trip: " + phase, state, first.snapshot());
            checks.expect("Restoration has no completed processors", List.of(), first.processorHistory());

            first.resolveMovement(List.of(Order.move(FRENCH, Province.Bel)));
            checks.expect("Restored movement uses the current province", Province.Bel,
                    first.board().locationOf(FRENCH));
            checks.expect("Movement advances to the next required phase",
                    phase == GamePhase.SPRING_MOVEMENT
                            ? GamePhase.FALL_MOVEMENT : GamePhase.WINTER_ADJUSTMENT, first.phase());
            checks.expect("Movement preserves the year", 1907, first.year());
            checks.expect("Only Fall occupation changes center ownership",
                    phase == GamePhase.SPRING_MOVEMENT ? Nation.ENGLAND : Nation.FRANCE,
                    first.board().ownerOf(Province.Bel));
            checks.expect("The other branch has not advanced", state, second.snapshot());
            checks.expect("The input board remains unchanged", Province.Pic, state.board().locationOf(FRENCH));

            second.resolveMovement(List.of(Order.move(FRENCH, Province.Par)));
            checks.expect("The second branch can choose another move", Province.Par,
                    second.board().locationOf(FRENCH));
            checks.expect("Advancing the second branch leaves the first unchanged", Province.Bel,
                    first.board().locationOf(FRENCH));
            checks.expect("Default branches have separate movement processors", false,
                    first.processorHistory().getFirst() == second.processorHistory().getFirst());
        }
    }

    private static void checkRetreats(TestEvaluation.Builder checks) {
        for (GamePhase phase : List.of(GamePhase.SPRING_RETREAT, GamePhase.FALL_RETREAT)) {
            GameState_Retreats state = retreatState(phase);
            Game first = Game.fromState(state);
            Game second = Game.fromState(state);
            checks.expect("Fixture has a dislodged French unit", true,
                    state.movementResult().dislodgements().containsKey(FRENCH));
            checks.expect("Retreat position round trip: " + phase, state, first.snapshot());
            checks.expect("Restoration does not rerun movement", List.of(), first.processorHistory());

            RetreatResult result = first.resolveRetreats(List.of(new RetreatOrder(FRENCH, Province.Bel)));
            checks.expect("Restored retreat uses the pending dislodgement", Province.Bel,
                    result.locationOf(FRENCH));
            checks.expect("Retreat advances to the next required phase",
                    phase == GamePhase.SPRING_RETREAT
                            ? GamePhase.FALL_MOVEMENT : GamePhase.WINTER_ADJUSTMENT, first.phase());
            checks.expect("Retreat preserves the year", 1907, first.year());
            checks.expect("Only Fall retreats update center ownership",
                    phase == GamePhase.SPRING_RETREAT ? Nation.ENGLAND : Nation.FRANCE,
                    first.board().ownerOf(Province.Bel));
            checks.expect("The other retreat branch has not advanced", state, second.snapshot());

            RetreatResult omitted = second.resolveRetreats(List.of());
            checks.expect("Omission destroys the unit only in its own branch", true, omitted.wasDestroyed(FRENCH));
            checks.expect("Successful retreat remains in the first branch", Province.Bel,
                    first.board().locationOf(FRENCH));
            checks.expect("The source result still records the dislodgement", true,
                    state.movementResult().dislodgements().containsKey(FRENCH));
            RetreatResult forbidden = Game.fromState(state).resolveRetreats(
                    List.of(new RetreatOrder(FRENCH, Province.Mun)));
            checks.expect("Restoration preserves the attacker's origin restriction", true,
                    forbidden.wasDestroyed(FRENCH));
        }
    }

    private static void checkWinter(TestEvaluation.Builder checks) {
        GameState_Retreats fall = retreatState(GamePhase.FALL_RETREAT);
        RetreatResult retreatResult = new RetreatProcessor().process(new RetreatInput(
                fall.movementResult(), List.of(new RetreatOrder(FRENCH, Province.Bel))));
        GameState_Adjustments state = new GameState_Adjustments(
                new GameMoment(1907, GamePhase.WINTER_ADJUSTMENT),
                fall.board().afterFallTurn(retreatResult), retreatResult);
        Game first = Game.fromState(state);
        Game second = Game.fromState(state);
        checks.expect("Winter position round trip", state, first.snapshot());
        checks.expect("Winter restoration does not process adjustments", List.of(), first.processorHistory());

        AdjustmentResult result = first.resolveAdjustments(
                List.of(new BuildOrder(Nation.FRANCE, UnitType.ARMY, Province.Par)));
        checks.expect("Restored winter accepts the available build", true,
                result.outcomes().getFirst().accepted());
        checks.expect("Restored winter creates one unit", 1, result.builtUnits().size());
        checks.expect("The built unit has the requested owner, type, and location", true,
                result.builtUnits().stream().allMatch(unit -> unit.owner() == Nation.FRANCE
                        && unit.unitType() == UnitType.ARMY && result.locationOf(unit) == Province.Par));
        checks.expect("Existing identity survives restoration and adjustment", Province.Bel,
                first.board().locationOf(FRENCH));
        checks.expect("Winter advances to Spring movement", GamePhase.SPRING_MOVEMENT, first.phase());
        checks.expect("Winter advances the year once", 1908, first.year());
        checks.expect("Winter preserves center ownership", state.board().owners(), first.board().owners());
        checks.expect("The other winter branch has not advanced", state, second.snapshot());

        second.resolveAdjustments(List.of(new WaiveOrder(Nation.FRANCE)));
        checks.expect("A waived build leaves the other board unchanged", state.board(), second.board());
        checks.expect("Waiving in another branch does not undo the build", 4, first.board().locations().size());
        checks.expect("The input winter board still has three units", 3, state.board().locations().size());
        checks.expect("The following Spring can also be restored", first.snapshot(),
                Game.fromState(first.snapshot()).snapshot());
    }
    private static void checkHistories(TestEvaluation.Builder checks) {
        Game original = StandardGameFactory.create1901();
        PressMessage originalMessage = message(original);
        original.recordPress(originalMessage);
        original.resolveMovement(List.of());
        GameState state = original.snapshot();
        Game restored = Game.fromState(state);
        checks.expect("Source game has processor history", 1, original.processorHistory().size());
        checks.expect("Restored processor history is empty", List.of(), restored.processorHistory());
        checks.expect("Restored press history is empty", List.of(), restored.pressHistory());

        PressMessage branchMessage = message(restored);
        restored.recordPress(branchMessage);
        restored.resolveMovement(List.of());
        checks.expect("Branch press remains local", List.of(branchMessage), restored.pressHistory());
        checks.expect("Original press is preserved", List.of(originalMessage), original.pressHistory());
        checks.expect("Original processor history is unchanged", 1, original.processorHistory().size());
        checks.expect("Branch records movement and the skipped Fall retreat", 2, restored.processorHistory().size());
        checks.expect("The live game is unchanged by the branch", state, original.snapshot());
        checks.expect("The snapshot can be restored again", state, Game.fromState(state).snapshot());
    }

    private static void checkProcessors(TestEvaluation.Builder checks) {
        int[] calls = new int[3];
        Processor<MovementInput, MovementResult> movement = input -> {
            calls[0]++;
            return new MovementProcessor(MovementProcessor.Policy.JUSTICE, 1, 17L).process(input);
        };
        Processor<RetreatInput, RetreatResult> retreats = input -> {
            calls[1]++;
            return new RetreatProcessor().process(input);
        };
        Processor<AdjustmentInput, AdjustmentResult> adjustments = input -> {
            calls[2]++;
            return new AdjustmentProcessor().process(input);
        };
        GameState state = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(), Map.of()));
        Game restored = Game.fromState(state, movement, retreats, adjustments);
        checks.expect("Restoration invokes no processors", List.of(0, 0, 0),
                List.of(calls[0], calls[1], calls[2]));
        restored.resolveMovement(List.of());
        checks.expect("Fall uses the supplied movement and retreat processors", List.of(1, 1, 0),
                List.of(calls[0], calls[1], calls[2]));
        checks.expect("History records the supplied processors", List.of(movement, retreats),
                restored.processorHistory());

        Game winter = Game.fromState(restored.snapshot(), movement, retreats, adjustments);
        winter.resolveAdjustments(List.of());
        checks.expect("Restored winter uses the supplied adjustment processor", List.of(1, 1, 1),
                List.of(calls[0], calls[1], calls[2]));
        checks.expect("Restored winter has its own processor history", List.of(adjustments),
                winter.processorHistory());
        checks.expect("Source winter position remains available", GamePhase.WINTER_ADJUSTMENT, restored.phase());

        Game retreat = Game.fromState(retreatState(GamePhase.SPRING_RETREAT), movement, retreats, adjustments);
        retreat.resolveRetreats(List.of());
        checks.expect("Restored retreats use the supplied retreat processor", List.of(1, 2, 1),
                List.of(calls[0], calls[1], calls[2]));
    }

    private static void checkInputs(TestEvaluation.Builder checks) {
        GameState state = StandardGameFactory.create1901().snapshot();
        expectThrows(checks, "Null state is rejected", NullPointerException.class,
                () -> Game.fromState(null));
        expectThrows(checks, "Null state with supplied processors is rejected", NullPointerException.class,
                () -> Game.fromState(null, new MovementProcessor(), new RetreatProcessor(), new AdjustmentProcessor()));
        expectThrows(checks, "Null movement processor is rejected", NullPointerException.class,
                () -> Game.fromState(state, null, new RetreatProcessor(), new AdjustmentProcessor()));
        expectThrows(checks, "Null retreat processor is rejected", NullPointerException.class,
                () -> Game.fromState(state, new MovementProcessor(), null, new AdjustmentProcessor()));
        expectThrows(checks, "Null adjustment processor is rejected", NullPointerException.class,
                () -> Game.fromState(state, new MovementProcessor(), new RetreatProcessor(), null));
        expectThrows(checks, "Restoration preserves the minimum-year restriction", IllegalArgumentException.class,
                () -> Game.fromState(new GameState_Movement(
                        new GameMoment(1900, GamePhase.SPRING_MOVEMENT), state.board())));
    }

    private static GameState_Retreats retreatState(GamePhase phase) {
        BoardState board = new BoardState(
                Map.of(FRENCH, Province.Bur, ATTACKER, Province.Mun, SUPPORTER, Province.Ruh),
                Map.of(Province.Par, Nation.FRANCE, Province.Mun, Nation.GERMANY,
                        Province.Ber, Nation.GERMANY, Province.Bel, Nation.ENGLAND));
        MovementResult result = new MovementProcessor().process(new MovementInput(
                board.locations(), List.of(Order.hold(FRENCH), Order.move(ATTACKER, Province.Bur),
                Order.supportMove(SUPPORTER, Province.Mun, Province.Bur))));
        return new GameState_Retreats(new GameMoment(1907, phase), board.after(result), result);
    }

    private static UnitId unit(Nation owner, Province origin) {
        return new UnitId(UUID.randomUUID(), owner, UnitType.ARMY, origin);
    }

    private static PressMessage message(Game game) {
        return new PressMessage(UUID.randomUUID(), new Moment(game.year(), game.phase(), Instant.EPOCH),
                Nation.FRANCE, Set.of(Nation.GERMANY), "Orders", "Ready.");
    }

    private static void expectThrows(TestEvaluation.Builder checks, String label,
                                     Class<? extends RuntimeException> expected, Runnable action) {
        try {
            action.run();
            checks.expect(label, expected, null);
        } catch (RuntimeException actual) {
            checks.expect(label, expected, actual.getClass());
        }
    }

    @Override
    public String getName() {
        return "Game restoration";
    }

    @Override
    public String getEval() {
        return evaluation == null ? null : evaluation.format(getName());
    }

    @Override
    public int getScore() {
        return evaluation == null ? 0 : evaluation.score();
    }

    @Override
    public int getSize() {
        return evaluation == null ? 0 : evaluation.size();
    }

    @Override
    public void printEval() {
        System.out.println(evaluation == null ? "UNEVALUATED " + getName() : getEval());
    }

    @Override
    public void printNameAndScore() {
        System.out.printf("[%02d/%02d]\t%s%n", getScore(), getSize(), getName());
    }

    public static void main(String[] args) {
        GameRestorationTestCase test = new GameRestorationTestCase();
        test.eval(true);
        if (!test.passed())
            throw new AssertionError("Game restoration tests failed");
    }

}