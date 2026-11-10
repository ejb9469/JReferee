package testing.selfcheck;

import agents.generation.*;
import agents.simulation.Simulator;
import contracts.OrderForm;
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

import java.time.Instant;
import java.util.*;

/**
 * Standalone joint-action simulation checks. Run without -ea.
 */
public final class SimulatorSelfCheck {

    private static final Simulator SIMULATOR = new Simulator();

    private static int checks;

    private SimulatorSelfCheck() { }

    public static void main(String[] args) {
        aliveNations();
        movementAndOrdering();
        convoy();
        retreats();
        adjustments();
        invalidInputs();
        checkpointOwnership();
        failedResolution();
        generatedActions();
        System.out.println("SimulatorSelfCheck passed: " + checks + " checks");
    }

    private static void aliveNations() {
        require(StandardGameFactory.create1901().snapshot().aliveNations().equals(EnumSet.allOf(Nation.class)),
                "All seven nations must be alive at the standard start");
        Game empty = game(new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(), Map.of())));
        require(empty.snapshot().aliveNations().isEmpty(), "An empty board with no owners has no living nations");
        expect(UnsupportedOperationException.class, () -> empty.snapshot().aliveNations().add(Nation.FRANCE));
        require(advance(empty, Map.of()).moment().gamePhase() == GamePhase.FALL_MOVEMENT,
                "An empty joint action must be allowed when nobody is alive");

        UnitId french = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        Game game = game(new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur), Map.of(Province.Mun, Nation.GERMANY))));
        GameState before = game.snapshot();
        Set<Nation> alive = before.aliveNations();
        require(alive.equals(EnumSet.of(Nation.FRANCE, Nation.GERMANY)),
                "A centerless unit and a unitless center owner must both keep their nations alive");
        require(new ArrayList<>(alive).equals(new ArrayList<>(EnumSet.of(Nation.FRANCE, Nation.GERMANY))),
                "Living nations must follow enum order");
        expect(UnsupportedOperationException.class, () -> alive.remove(Nation.FRANCE));
        Map<Nation, ActionCandidate> actions = Map.of(
                Nation.FRANCE, action(Nation.FRANCE, Order.move(french, Province.Mun)),
                Nation.GERMANY, action(Nation.GERMANY));
        GameState fall = advance(game, actions);
        require(fall.board().locationOf(french) == Province.Mun, "A sparse joint action must resolve movement");
        require(fall.aliveNations().equals(alive), "Spring occupation must not eliminate the center's owner");
        require(fall.board().equals(advance(game, joint(actions.values().toArray(ActionCandidate[]::new))).board()),
                "Explicit empty candidates for eliminated nations must retain the same result");

        RuntimeException missingUnit = expect(IllegalArgumentException.class, () -> SIMULATOR.peek(game,
                Map.of(Nation.GERMANY, action(Nation.GERMANY))));
        require(missingUnit.getMessage().contains("FRANCE"), "A missing unit owner must be named");
        RuntimeException missingOwner = expect(IllegalArgumentException.class, () -> SIMULATOR.peek(game,
                Map.of(Nation.FRANCE, actions.get(Nation.FRANCE))));
        require(missingOwner.getMessage().contains("GERMANY"), "A missing center owner must be named");

        Map<Nation, ActionCandidate> extra = new EnumMap<>(actions);
        UnitId english = unit(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        extra.put(Nation.ENGLAND, action(Nation.ENGLAND, Order.hold(english)));
        expect(IllegalArgumentException.class, () -> SIMULATOR.peek(game, extra));
        extra.put(Nation.ENGLAND, action(Nation.GERMANY));
        expect(IllegalArgumentException.class, () -> SIMULATOR.peek(game, extra));
        extra.put(Nation.ENGLAND, null);
        expect(NullPointerException.class, () -> SIMULATOR.peek(game, extra));
        require(game.snapshot().equals(before) && game.getSnapshot() == null,
                "Rejected sparse submissions must leave the game and checkpoint unchanged");
        require(game.processorHistory().isEmpty(), "Rejected submissions must not run a processor");

        Game fallGame = game(fall);
        Map<Nation, ActionCandidate> holds = Map.of(
                Nation.FRANCE, action(Nation.FRANCE, Order.hold(french)),
                Nation.GERMANY, action(Nation.GERMANY));
        GameState winter = advance(fallGame, holds);
        require(winter.aliveNations().equals(Set.of(Nation.FRANCE)),
                "Losing the last center at the end of Fall must eliminate a unitless nation");
        require(alive.equals(EnumSet.of(Nation.FRANCE, Nation.GERMANY)),
                "An earlier living-nation set must remain unchanged");
        require(advance(game(winter), Map.of(Nation.FRANCE, action(Nation.FRANCE))).moment().year() == 1908,
                "The eliminated nation may be omitted in winter");
    }

    private static void movementAndOrdering() {
        UnitId french = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId otherFrench = unit(Nation.FRANCE, UnitType.ARMY, Province.Bre);
        UnitId english = unit(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        UnitId german = unit(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        locations.put(german, Province.Mun);
        locations.put(english, Province.Ruh);
        locations.put(otherFrench, Province.Gas);
        locations.put(french, Province.Bur);
        BoardState board = new BoardState(locations, Map.of(Province.Mun, Nation.GERMANY));
        Game live = new Game(1907, board);
        GameState state = live.snapshot();
        Game game = game(state);
        Map<Nation, ActionCandidate> actions = joint(
                action(Nation.FRANCE, Order.hold(otherFrench), Order.move(french, Province.Mun)),
                action(Nation.ENGLAND, Order.supportMove(english, Province.Bur, Province.Mun)),
                action(Nation.GERMANY, Order.hold(german)));
        Map<Nation, ActionCandidate> originalActions = Map.copyOf(actions);

        GameState_Retreats next = requireRetreats(advance(game, actions));
        require(next.moment().equals(new GameMoment(1907, GamePhase.SPRING_RETREAT)),
                "A dislodgement must expose Spring retreats");
        require(next.board().locationOf(french) == Province.Mun, "Foreign support must affect the joint result");
        require(next.movementResult().dislodgements().containsKey(german), "The German unit must be dislodged");
        require(next.board().ownerOf(Province.Mun) == Nation.GERMANY, "Spring must retain center ownership");
        require(live.snapshot().equals(state), "Simulation must not advance the live game");
        require(live.processorHistory().isEmpty(), "Simulation must not add to live processor history");
        require(actions.equals(originalActions), "Simulation must not change candidate data");
        require(state.board().locations().equals(locations), "Simulation must not change the input board");
        require(new ArrayList<>(next.movementResult().outcomes().keySet())
                        .equals(new ArrayList<>(locations.keySet())),
                "Movement must follow the supplied game's board order");

        Map<Nation, ActionCandidate> reversedActions = new LinkedHashMap<>();
        for (Nation nation : Arrays.asList(Nation.values()).reversed()) {
            ActionCandidate candidate = actions.get(nation);
            reversedActions.put(nation, new ActionCandidate(nation, candidate.orders().reversed(),
                    candidate.logPrior(), candidate.source()));
        }
        GameState_Retreats repeated = requireRetreats(advance(game, reversedActions));
        requireSameRetreat(next, repeated);
        require(new ArrayList<>(repeated.movementResult().outcomes().keySet())
                        .equals(new ArrayList<>(next.movementResult().outcomes().keySet())),
                "Reordered submissions must retain the game's adjudication order");

        Map<UnitId, Province> reversedLocations = new LinkedHashMap<>();
        for (UnitId unit : new ArrayList<>(locations.keySet()).reversed())
            reversedLocations.put(unit, locations.get(unit));
        Game reordered = game(new GameState_Movement(state.moment(),
                new BoardState(reversedLocations, board.owners())));
        GameState_Retreats reorderedNext = requireRetreats(advance(reordered, actions));
        requireSameRetreat(next, reorderedNext);
        require(new ArrayList<>(reorderedNext.movementResult().outcomes().keySet())
                        .equals(new ArrayList<>(reversedLocations.keySet())),
                "A different game retains its own board order");

        Map<Nation, ActionCandidate> changedMetadata = new EnumMap<>(Nation.class);
        actions.forEach((nation, candidate) -> changedMetadata.put(nation,
                new ActionCandidate(nation, candidate.orders(), Double.NEGATIVE_INFINITY,
                        ActionCandidate.Source.EXPLORATION)));
        requireSameRetreat(next, requireRetreats(advance(game, changedMetadata)));
        expect(UnsupportedOperationException.class, () -> next.board().locations().clear());

        Map<Nation, ActionCandidate> unsupported = new EnumMap<>(actions);
        unsupported.put(Nation.ENGLAND, action(Nation.ENGLAND, Order.hold(english)));
        GameState bounced = advance(game, unsupported);
        require(bounced.moment().gamePhase() == GamePhase.FALL_MOVEMENT,
                "Movement without dislodgements must skip retreats");
        require(bounced.board().equals(board), "An unsupported attack must bounce");
        Game configured = Game.fromState(state,
                new MovementProcessor(MovementProcessor.Policy.JUSTICE, 1, 17L),
                new RetreatProcessor(), new AdjustmentProcessor());
        requireSameRetreat(next, requireRetreats(advance(configured, actions)));

        GameState fall = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT), board);
        GameState_Retreats fallRetreats = requireRetreats(advance(game(fall), actions));
        require(fallRetreats.moment().gamePhase() == GamePhase.FALL_RETREAT, "Fall must expose required retreats");
        require(fallRetreats.board().ownerOf(Province.Mun) == Nation.GERMANY,
                "Fall ownership must wait until retreats finish");
        GameState winter = advance(game(fallRetreats), joint());
        require(winter instanceof GameState_Adjustments, "Completed Fall retreats must reach winter");
        require(winter.board().ownerOf(Province.Mun) == Nation.FRANCE, "Completed Fall must transfer ownership");
        require(!winter.board().locations().containsKey(german), "An explicitly omitted retreat destroys the unit");
    }

    private static void convoy() {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId fleet = unit(Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        GameState state = new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(army, Province.Lon, fleet, Province.NTH), Map.of()));
        Game game = game(state);
        Map<Nation, ActionCandidate> actions = joint(
                action(Nation.FRANCE, Order.move(army, Province.Bel)),
                action(Nation.ENGLAND, Order.convoy(fleet, Province.Lon, Province.Bel)));
        require(advance(game, actions).board().locationOf(army) == Province.Bel,
                "A foreign convoy must resolve together with the Army move");
        actions.put(Nation.ENGLAND, action(Nation.ENGLAND, Order.hold(fleet)));
        require(advance(game, actions).board().locationOf(army) == Province.Lon,
                "Missing convoy cooperation is an adjudication outcome");
    } private static void retreats() {
        UnitId french = unit(Nation.FRANCE, UnitType.ARMY, Province.Bur);
        UnitId english = unit(Nation.ENGLAND, UnitType.ARMY, Province.Pic);
        UnitId german = unit(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        UnitId germanSupport = unit(Nation.GERMANY, UnitType.ARMY, Province.Ruh);
        UnitId italian = unit(Nation.ITALY, UnitType.ARMY, Province.Par);
        UnitId italianSupport = unit(Nation.ITALY, UnitType.ARMY, Province.Bre);
        GameState movement = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur, english, Province.Pic,
                        german, Province.Mun, germanSupport, Province.Ruh,
                        italian, Province.Par, italianSupport, Province.Bre),
                        Map.of(Province.Bel, Nation.RUSSIA)));
        GameState_Retreats state = requireRetreats(advance(game(movement), joint(
                action(Nation.FRANCE, Order.hold(french)),
                action(Nation.ENGLAND, Order.hold(english)),
                action(Nation.GERMANY, Order.move(german, Province.Bur),
                        Order.supportMove(germanSupport, Province.Mun, Province.Bur)),
                action(Nation.ITALY, Order.move(italian, Province.Pic),
                        Order.supportMove(italianSupport, Province.Par, Province.Pic)))));
        Game game = game(state);
        require(state.movementResult().dislodgements().keySet().equals(Set.of(french, english)),
                "Fixture must dislodge both retreating units");
        require(state.aliveNations().equals(EnumSet.of(
                        Nation.FRANCE, Nation.ENGLAND, Nation.GERMANY, Nation.ITALY, Nation.RUSSIA)),
                "Dislodged centerless nations must remain alive while their retreats are pending");
        Map<Nation, ActionCandidate> actions = joint(
                action(Nation.FRANCE, new RetreatOrder(french, Province.Bel)),
                action(Nation.ENGLAND, new RetreatOrder(english, Province.Bel)));
        actions.remove(Nation.AUSTRIA);
        actions.remove(Nation.TURKEY);
        Map<Nation, ActionCandidate> missing = new EnumMap<>(actions);
        missing.remove(Nation.FRANCE);
        RuntimeException missingRetreat = expect(IllegalArgumentException.class,
                () -> SIMULATOR.peek(game, missing));
        require(missingRetreat.getMessage().contains("FRANCE"), "A missing dislodged nation must be named");
        require(game.snapshot().equals(state) && game.getSnapshot() == null,
                "A missing retreat candidate must not checkpoint or advance the game");
        GameState collided = advance(game, actions);
        require(collided instanceof GameState_Adjustments, "Fall retreats must advance to winter");
        require(!collided.board().locations().containsKey(french)
                        && !collided.board().locations().containsKey(english),
                "Retreats from different powers must collide");
        require(collided.board().ownerOf(Province.Bel) == Nation.RUSSIA,
                "A vacant center retains its prior owner after a collision");
        require(state.movementResult().dislodgements().size() == 2, "The source retreat facts must remain intact");
        require(collided.aliveNations().equals(EnumSet.of(Nation.GERMANY, Nation.ITALY, Nation.RUSSIA)),
                "Destroyed retreaters must not remain alive through the saved Fall result");

        actions.put(Nation.FRANCE, action(Nation.FRANCE));
        GameState successful = advance(game, actions);
        require(successful.board().locationOf(english) == Province.Bel, "A lone legal retreat must succeed");
        require(successful.board().ownerOf(Province.Bel) == Nation.ENGLAND, "Fall retreat occupation transfers control");
        require(!successful.aliveNations().contains(Nation.FRANCE)
                        && successful.aliveNations().contains(Nation.ENGLAND),
                "Explicit omission must destroy the last French unit while a successful English retreat survives");
        GameState spring = new GameState_Retreats(new GameMoment(1907, GamePhase.SPRING_RETREAT),
                state.board(), state.movementResult());
        GameState afterSpring = advance(game(spring), actions);
        require(afterSpring.moment().equals(new GameMoment(1907, GamePhase.FALL_MOVEMENT)),
                "Spring retreats must advance to Fall in the same year");
        require(afterSpring.board().ownerOf(Province.Bel) == Nation.RUSSIA, "Spring retreats retain ownership");

        actions.put(Nation.FRANCE, action(Nation.FRANCE, new RetreatOrder(french, Province.Mun)));
        RuntimeException invalid = expect(IllegalArgumentException.class, () -> SIMULATOR.peek(game, actions));
        require(invalid.getMessage().contains("FRANCE") && invalid.getMessage().contains("ORDER_NOT_IN_CATALOG"),
                "An invalid retreat must report its power and validation error");
        require(game.snapshot().equals(state) && game.getSnapshot() == null,
                "A rejected retreat must leave the game and checkpoint unchanged");
    }

    private static void adjustments() {
        UnitId french = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId german = unit(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        UnitId removed = unit(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        BoardState board = new BoardState(Map.of(french, Province.Bur, german, Province.Mun, removed, Province.Ber),
                Map.of(Province.Par, Nation.FRANCE, Province.Bre, Nation.FRANCE, Province.Mun, Nation.GERMANY,
                        Province.Stp, Nation.RUSSIA, Province.Mos, Nation.RUSSIA));
        GameState_Adjustments state = winter(board);
        Game game = game(state);
        Map<Nation, ActionCandidate> actions = joint(
                action(Nation.FRANCE, new BuildOrder(Nation.FRANCE, UnitType.FLEET, Province.Bre)),
                action(Nation.GERMANY, new DisbandOrder(Nation.GERMANY, removed)),
                action(Nation.RUSSIA, new BuildOrder(Nation.RUSSIA, UnitType.FLEET, Province.StpSC),
                        new WaiveOrder(Nation.RUSSIA)));
        actions.remove(Nation.AUSTRIA);
        actions.remove(Nation.ENGLAND);
        actions.remove(Nation.ITALY);
        actions.remove(Nation.TURKEY);
        require(state.aliveNations().equals(EnumSet.of(Nation.FRANCE, Nation.GERMANY, Nation.RUSSIA)),
                "A unitless nation owning build centers must remain alive in winter");
        Map<Nation, ActionCandidate> missingBuilder = new EnumMap<>(actions);
        missingBuilder.remove(Nation.RUSSIA);
        RuntimeException missingNation = expect(IllegalArgumentException.class,
                () -> SIMULATOR.peek(game, missingBuilder));
        require(missingNation.getMessage().contains("RUSSIA"), "A missing unitless builder must be named");
        GameState next = advance(game, actions);
        require(next.moment().equals(new GameMoment(1908, GamePhase.SPRING_MOVEMENT)),
                "Winter must advance the year exactly once");
        require(position(next).equals(Map.of(Province.Bur, "FRANCE:ARMY", Province.Mun, "GERMANY:ARMY",
                        Province.Bre, "FRANCE:FLEET", Province.StpSC, "RUSSIA:FLEET")),
                "All powers' builds, disbands, and waives must be applied");
        require(next.board().locationOf(french) == Province.Bur && next.board().locationOf(german) == Province.Mun,
                "Surviving units retain their identities");
        require(!next.board().locations().containsKey(removed), "The selected unit must be removed");
        require(next.board().owners().equals(board.owners()), "Winter must preserve center ownership");
        require(state.board().equals(board), "The source winter board must remain unchanged");
        require(next.equals(advance(game, actions)),
                "Repeated winter simulations must agree on positions, types, and identities");

        Map<Nation, ActionCandidate> missing = new EnumMap<>(actions);
        missing.put(Nation.GERMANY, action(Nation.GERMANY));
        RuntimeException invalid = expect(IllegalArgumentException.class, () -> SIMULATOR.peek(game, missing));
        require(invalid.getMessage().contains("MISSING_ADJUSTMENTS"), "Mandatory disbands must not be defaulted");
        Map<Nation, ActionCandidate> duplicate = new EnumMap<>(actions);
        duplicate.put(Nation.RUSSIA, action(Nation.RUSSIA,
                new BuildOrder(Nation.RUSSIA, UnitType.FLEET, Province.StpNC),
                new BuildOrder(Nation.RUSSIA, UnitType.FLEET, Province.StpSC)));
        expect(IllegalArgumentException.class, () -> SIMULATOR.peek(game, duplicate));
        Map<Nation, ActionCandidate> excess = new EnumMap<>(actions);
        excess.put(Nation.FRANCE, action(Nation.FRANCE,
                new BuildOrder(Nation.FRANCE, UnitType.FLEET, Province.Bre), new WaiveOrder(Nation.FRANCE)));
        expect(IllegalArgumentException.class, () -> SIMULATOR.peek(game, excess));
        Map<Nation, ActionCandidate> wrongPhase = new EnumMap<>(actions);
        wrongPhase.put(Nation.FRANCE, action(Nation.FRANCE, Order.hold(french)));
        expect(IllegalArgumentException.class, () -> SIMULATOR.peek(game, wrongPhase));
        require(state.board().equals(board), "Rejected submissions must leave the input unchanged");
        require(game.snapshot().equals(state) && game.getSnapshot() == null,
                "Rejected adjustments must leave the game and checkpoint unchanged");

        UnitId last = unit(Nation.ITALY, UnitType.ARMY, Province.Rom);
        GameState_Adjustments centerless = winter(new BoardState(Map.of(last, Province.Tus), Map.of()));
        require(centerless.aliveNations().equals(Set.of(Nation.ITALY)),
                "A nation with a required disband must remain alive until it is resolved");
        Game disbandGame = game(centerless);
        expect(IllegalArgumentException.class, () -> SIMULATOR.peek(disbandGame, Map.of()));
        GameState eliminated = advance(disbandGame, Map.of(
                Nation.ITALY, action(Nation.ITALY, new DisbandOrder(Nation.ITALY, last))));
        require(eliminated.aliveNations().isEmpty(), "Disbanding the last centerless unit must eliminate its nation");
        require(advance(game(eliminated), Map.of()).moment().gamePhase() == GamePhase.FALL_MOVEMENT,
                "A fully eliminated nation needs no candidate in the following phase");
    }

    private static void invalidInputs() {
        GameState state = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(), Map.of()));
        Game game = game(state);
        Map<Nation, ActionCandidate> actions = joint();
        expect(NullPointerException.class, () -> SIMULATOR.peek(null, actions));
        expect(NullPointerException.class, () -> SIMULATOR.peek(game, null));
        Map<Nation, ActionCandidate> missing = new EnumMap<>(actions);
        missing.remove(Nation.TURKEY);
        require(advance(game, missing) instanceof GameState_Adjustments,
                "Omitting an eliminated nation must be accepted");
        Map<Nation, ActionCandidate> mismatch = new EnumMap<>(actions);
        mismatch.put(Nation.FRANCE, action(Nation.GERMANY));
        expect(IllegalArgumentException.class, () -> SIMULATOR.peek(game, mismatch));
        Map<Nation, ActionCandidate> nullCandidate = new EnumMap<>(actions);
        nullCandidate.put(Nation.FRANCE, null);
        expect(NullPointerException.class, () -> SIMULATOR.peek(game, nullCandidate));
        Map<Nation, ActionCandidate> nullKey = new HashMap<>(actions);
        nullKey.put(null, action(Nation.FRANCE));
        expect(NullPointerException.class, () -> SIMULATOR.peek(game, nullKey));
        require(game.snapshot().equals(state) && game.getSnapshot() == null,
                "Invalid submissions must leave the game and checkpoint unchanged");

        GameState winter = advance(game, actions);
        require(winter instanceof GameState_Adjustments, "Empty Fall movement must reach winter through Game");
        GameState spring = advance(game(winter), actions);
        require(spring.moment().equals(new GameMoment(1908, GamePhase.SPRING_MOVEMENT)),
                "A balanced winter still advances to the following Spring");
        UnitId french = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState occupied = new GameState_Movement(state.moment(),
                new BoardState(Map.of(french, Province.Par), Map.of()));
        int[] calls = new int[1];
        Game occupiedGame = Game.fromState(occupied, input -> {
            calls[0]++;
            return movementProcessor().process(input);
        }, new RetreatProcessor(), new AdjustmentProcessor());
        expect(IllegalArgumentException.class, () -> SIMULATOR.peek(occupiedGame, actions));
        actions.put(Nation.FRANCE, action(Nation.FRANCE, Order.move(french, Province.NTH)));
        expect(IllegalArgumentException.class, () -> SIMULATOR.peek(occupiedGame, actions));
        require(calls[0] == 0, "Invalid candidates must not invoke a processor");
        require(occupiedGame.snapshot().equals(occupied) && occupiedGame.getSnapshot() == null,
                "Invalid candidates must not advance or checkpoint the game");
    } private static void checkpointOwnership() {
        Game game = game(new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(), Map.of())));
        game.recordPress(message(game));
        game.takeSnapshot();
        GameState saved = game.getSnapshot();
        game.resolveMovement(List.of());
        game.recordPress(message(game));
        GameState current = game.snapshot();
        var processors = game.processorHistory();
        var press = game.pressHistory();

        expect(IllegalStateException.class, () -> SIMULATOR.peek(game, joint()));
        require(game.getSnapshot() == saved, "A caller's checkpoint must remain intact");
        require(game.snapshot().equals(current), "Rejecting a saved checkpoint must retain the current position");
        require(game.processorHistory().equals(processors) && game.pressHistory().equals(press),
                "Rejecting a saved checkpoint must leave both histories unchanged");
        game.restoreFromSnapshot();
        require(game.snapshot().equals(saved), "The caller must still be able to restore its checkpoint");

        game.resolveMovement(List.of());
        game.recordPress(message(game));
        require(game.processorHistory().size() == 1 && game.pressHistory().size() == 2,
                "The next trial must start with nonempty histories");
        GameState winter = advance(game, joint());
        require(winter instanceof GameState_Adjustments, "A Fall trial must include the skipped retreat");
        require(game.phase() == GamePhase.FALL_MOVEMENT, "The Fall trial must restore its starting phase");
    }

    private static void failedResolution() {
        UnitId french = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState state = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Pic), Map.of()));
        IllegalStateException failure = new IllegalStateException("Retreat processor failure");
        int[] calls = new int[1];
        Game game = Game.fromState(state, movementProcessor(), input -> {
            calls[0]++;
            if (calls[0] == 1)
                throw failure;
            return new RetreatProcessor().process(input);
        }, new AdjustmentProcessor());
        game.recordPress(message(game));
        var press = game.pressHistory();
        Map<Nation, ActionCandidate> actions = joint(
                action(Nation.FRANCE, Order.move(french, Province.Bel)));

        RuntimeException caught = expect(IllegalStateException.class, () -> SIMULATOR.peek(game, actions));
        require(caught == failure, "Resolution must propagate the original exception");
        require(game.snapshot().equals(state), "A partially completed Fall transition must be rolled back");
        require(game.processorHistory().isEmpty(), "Failed trials must discard partial processor history");
        require(game.pressHistory().equals(press), "Failed trials must preserve earlier press");
        require(game.getSnapshot() == null, "A failed trial must consume only its own checkpoint");
        require(calls[0] == 1, "Rollback must not rerun or rewind a custom processor");

        GameState next = advance(game, actions);
        require(calls[0] == 2, "The supplied processor must be reused after failure");
        require(next instanceof GameState_Adjustments && next.board().locationOf(french) == Province.Bel,
                "The same game must support another trial after failure");
        require(next.board().ownerOf(Province.Bel) == Nation.FRANCE, "The successful retry must finish Fall");
    }

    private static void generatedActions() {
        Game live = StandardGameFactory.create1901();
        GameState initial = live.snapshot();
        GameState state = initial;
        for (int step = 0; step < 8; step++) {
            Map<Nation, ActionCandidate> actions = new EnumMap<>(Nation.class);
            for (Nation nation : state.aliveNations()) {
                LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, nation);
                List<ActionCandidate> candidates = new UniformActionGenerator().generate(
                        new ActionGenerationRequest(state, nation, 6, 71L + step), catalog);
                actions.put(nation, candidates.getLast());
            }
            GameMoment before = state.moment();
            Game game = game(state);
            GameState next = advance(game, actions);
            GameState repeated = advance(game, actions);
            require(next.moment().equals(repeated.moment())
                            && position(next).equals(position(repeated))
                            && next.board().owners().equals(repeated.board().owners()),
                    "Repeated generated actions must retain their resulting position and ownership");
            state = next;
            require(state.moment().compareTo(before) > 0, "Generated joint actions must advance the game");
        }
        require(state.moment().year() >= 1902, "Generated actions must cross a winter boundary");
        require(live.snapshot().equals(initial), "A simulated sequence must leave the live game unchanged");
    }

    private static GameState_Adjustments winter(BoardState board) {
        MovementResult movement = movementProcessor()
                .process(new MovementInput(board.locations(), List.of()));
        RetreatResult retreats = new RetreatProcessor().process(new RetreatInput(movement, List.of()));
        return new GameState_Adjustments(new GameMoment(1907, GamePhase.WINTER_ADJUSTMENT), board, retreats);
    }

    private static Game game(GameState state) {
        return Game.fromState(state, movementProcessor(), new RetreatProcessor(), new AdjustmentProcessor());
    }

    private static MovementProcessor movementProcessor() {
        return new MovementProcessor(MovementProcessor.Policy.SZYKMAN_JUSTICE, 4, 0L);
    }

    private static GameState advance(Game game, Map<Nation, ActionCandidate> actions) {
        GameState before = game.snapshot();
        var order = new ArrayList<>(game.board().locations().keySet());
        var processors = game.processorHistory();
        var press = game.pressHistory();
        var submitted = Map.copyOf(actions);
        Set<Nation> alive = before.aliveNations();
        GameState next = SIMULATOR.peek(game, actions);
        require(game.snapshot().equals(before), "Each trial must restore the starting position and phase facts");
        require(new ArrayList<>(game.board().locations().keySet()).equals(order),
                "Each trial must restore the board iteration order");
        require(game.processorHistory().equals(processors), "Each trial must restore processor history");
        require(game.pressHistory().equals(press), "Each trial must restore press history");
        require(game.getSnapshot() == null, "Each trial must consume its checkpoint");
        require(actions.equals(submitted), "Each trial must leave candidate data unchanged");
        require(game.snapshot().aliveNations().equals(alive), "Each trial must restore the living nations");
        return next;
    }

    private static PressMessage message(Game game) {
        return new PressMessage(UUID.randomUUID(), new Moment(game.year(), game.phase(), Instant.EPOCH),
                Nation.FRANCE, Set.of(Nation.GERMANY), "Orders", "Ready.");
    }

    private static GameState_Retreats requireRetreats(GameState state) {
        if (state instanceof GameState_Retreats retreats)
            return retreats;
        throw new AssertionError("Expected retreats, got " + state.moment());
    }

    private static void requireSameRetreat(GameState_Retreats first, GameState_Retreats second) {
        require(first.moment().equals(second.moment()), "Equivalent resolutions must reach the same moment");
        require(first.board().equals(second.board()), "Equivalent movement inputs must produce the same board");
        require(first.movementResult().outcomes().equals(second.movementResult().outcomes()), "Movement outcomes must match");
        require(first.movementResult().dislodgements().equals(second.movementResult().dislodgements()), "Dislodgements must match");
        require(first.movementResult().standoffProvinces().equals(second.movementResult().standoffProvinces()), "Standoffs must match");
    }

    private static Map<Province, String> position(GameState state) {
        Map<Province, String> position = new EnumMap<>(Province.class);
        state.board().locations().forEach((unit, location) ->
                position.put(location, unit.owner() + ":" + unit.unitType()));
        return position;
    }

    private static UnitId unit(Nation nation, UnitType type, Province origin) {
        return new UnitId(UUID.randomUUID(), nation, type, origin);
    }

    private static ActionCandidate action(Nation nation, OrderForm... orders) {
        return new ActionCandidate(nation, List.of(orders), -1.0, ActionCandidate.Source.POLICY);
    }

    private static Map<Nation, ActionCandidate> joint(ActionCandidate... candidates) {
        Map<Nation, ActionCandidate> actions = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            actions.put(nation, action(nation));
        for (ActionCandidate candidate : candidates)
            actions.put(candidate.power(), candidate);
        return actions;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }

    private static RuntimeException expect(Class<? extends RuntimeException> type, Runnable operation) {
        checks++;
        try {
            operation.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception))
                return exception;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + exception, exception);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

}