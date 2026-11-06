package testing.selfcheck;

import agents.generation.*;
import agents.simulation.JointActionSimulator;
import contracts.OrderForm;
import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.*;
import phase.*;
import phase.adjustments.*;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;
import phase.retreats.RetreatOrder;
import phase.retreats.RetreatProcessor;
import phase.retreats.RetreatResult;

import java.util.*;

/**
 * Standalone joint-action simulation checks. Run without -ea.
 */
public final class JointActionSimulatorSelfCheck {

    private static final JointActionSimulator SIMULATOR =
            new JointActionSimulator(MovementProcessor.Policy.SZYKMAN_JUSTICE, 4, 0L);

    private static int checks;

    private JointActionSimulatorSelfCheck() { }

    public static void main(String[] args) {
        movementAndOrdering();
        convoy();
        retreats();
        adjustments();
        invalidInputs();
        generatedActions();
        System.out.println("JointActionSimulatorSelfCheck passed: " + checks + " checks");
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
        Map<Nation, ActionCandidate> actions = joint(
                action(Nation.FRANCE, Order.hold(otherFrench), Order.move(french, Province.Mun)),
                action(Nation.ENGLAND, Order.supportMove(english, Province.Bur, Province.Mun)),
                action(Nation.GERMANY, Order.hold(german)));
        Map<Nation, ActionCandidate> originalActions = Map.copyOf(actions);

        GameState_Retreats next = requireRetreats(SIMULATOR.advance(state, actions));
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
                        .equals(List.of(french, otherFrench, german, english)),
                "Movement must use current province-name order, independent of unit origins");

        Map<UnitId, Province> reversedLocations = new LinkedHashMap<>();
        for (UnitId unit : new ArrayList<>(locations.keySet()).reversed())
            reversedLocations.put(unit, locations.get(unit));
        Map<Nation, ActionCandidate> reversedActions = new LinkedHashMap<>();
        for (Nation nation : Arrays.asList(Nation.values()).reversed()) {
            ActionCandidate candidate = actions.get(nation);
            reversedActions.put(nation, new ActionCandidate(nation, candidate.orders().reversed(),
                    candidate.logPrior(), candidate.source()));
        }
        GameState reordered = new GameState_Movement(state.moment(),
                new BoardState(reversedLocations, board.owners()));
        GameState_Retreats repeated = requireRetreats(SIMULATOR.advance(reordered, reversedActions));
        requireSameRetreat(next, repeated);
        require(new ArrayList<>(repeated.movementResult().outcomes().keySet())
                        .equals(new ArrayList<>(next.movementResult().outcomes().keySet())),
                "Reordered inputs must retain canonical adjudication order");

        Map<Nation, ActionCandidate> changedMetadata = new EnumMap<>(Nation.class);
        actions.forEach((nation, candidate) -> changedMetadata.put(nation,
                new ActionCandidate(nation, candidate.orders(), Double.NEGATIVE_INFINITY,
                        ActionCandidate.Source.EXPLORATION)));
        requireSameRetreat(next, requireRetreats(SIMULATOR.advance(state, changedMetadata)));
        expect(UnsupportedOperationException.class, () -> next.board().locations().clear());

        Map<Nation, ActionCandidate> unsupported = new EnumMap<>(actions);
        unsupported.put(Nation.ENGLAND, action(Nation.ENGLAND, Order.hold(english)));
        GameState bounced = SIMULATOR.advance(state, unsupported);
        require(bounced.moment().gamePhase() == GamePhase.FALL_MOVEMENT,
                "Movement without dislodgements must skip retreats");
        require(bounced.board().equals(board), "An unsupported attack must bounce");
        requireSameRetreat(next, requireRetreats(new JointActionSimulator(
                MovementProcessor.Policy.JUSTICE, 1, 17L).advance(state, actions)));

        GameState fall = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT), board);
        GameState_Retreats fallRetreats = requireRetreats(SIMULATOR.advance(fall, actions));
        require(fallRetreats.moment().gamePhase() == GamePhase.FALL_RETREAT, "Fall must expose required retreats");
        require(fallRetreats.board().ownerOf(Province.Mun) == Nation.GERMANY,
                "Fall ownership must wait until retreats finish");
        GameState winter = SIMULATOR.advance(fallRetreats, joint());
        require(winter instanceof GameState_Adjustments, "Completed Fall retreats must reach winter");
        require(winter.board().ownerOf(Province.Mun) == Nation.FRANCE, "Completed Fall must transfer ownership");
        require(!winter.board().locations().containsKey(german), "An explicitly omitted retreat destroys the unit");
    }

    private static void convoy() {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId fleet = unit(Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        GameState state = new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(Map.of(army, Province.Lon, fleet, Province.NTH), Map.of()));
        Map<Nation, ActionCandidate> actions = joint(
                action(Nation.FRANCE, Order.move(army, Province.Bel)),
                action(Nation.ENGLAND, Order.convoy(fleet, Province.Lon, Province.Bel)));
        require(SIMULATOR.advance(state, actions).board().locationOf(army) == Province.Bel,
                "A foreign convoy must resolve together with the Army move");
        actions.put(Nation.ENGLAND, action(Nation.ENGLAND, Order.hold(fleet)));
        require(SIMULATOR.advance(state, actions).board().locationOf(army) == Province.Lon,
                "Missing convoy cooperation is an adjudication outcome");
    }

    private static void retreats() {
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
        GameState_Retreats state = requireRetreats(SIMULATOR.advance(movement, joint(
                action(Nation.FRANCE, Order.hold(french)),
                action(Nation.ENGLAND, Order.hold(english)),
                action(Nation.GERMANY, Order.move(german, Province.Bur),
                        Order.supportMove(germanSupport, Province.Mun, Province.Bur)),
                action(Nation.ITALY, Order.move(italian, Province.Pic),
                        Order.supportMove(italianSupport, Province.Par, Province.Pic)))));
        require(state.movementResult().dislodgements().keySet().equals(Set.of(french, english)),
                "Fixture must dislodge both retreating units");
        Map<Nation, ActionCandidate> actions = joint(
                action(Nation.FRANCE, new RetreatOrder(french, Province.Bel)),
                action(Nation.ENGLAND, new RetreatOrder(english, Province.Bel)));
        GameState collided = SIMULATOR.advance(state, actions);
        require(collided instanceof GameState_Adjustments, "Fall retreats must advance to winter");
        require(!collided.board().locations().containsKey(french)
                        && !collided.board().locations().containsKey(english),
                "Retreats from different powers must collide");
        require(collided.board().ownerOf(Province.Bel) == Nation.RUSSIA,
                "A vacant center retains its prior owner after a collision");
        require(state.movementResult().dislodgements().size() == 2, "The source retreat facts must remain intact");

        actions.put(Nation.FRANCE, action(Nation.FRANCE));
        GameState successful = SIMULATOR.advance(state, actions);
        require(successful.board().locationOf(english) == Province.Bel, "A lone legal retreat must succeed");
        require(successful.board().ownerOf(Province.Bel) == Nation.ENGLAND, "Fall retreat occupation transfers control");
        GameState spring = new GameState_Retreats(new GameMoment(1907, GamePhase.SPRING_RETREAT),
                state.board(), state.movementResult());
        GameState afterSpring = SIMULATOR.advance(spring, actions);
        require(afterSpring.moment().equals(new GameMoment(1907, GamePhase.FALL_MOVEMENT)),
                "Spring retreats must advance to Fall in the same year");
        require(afterSpring.board().ownerOf(Province.Bel) == Nation.RUSSIA, "Spring retreats retain ownership");

        actions.put(Nation.FRANCE, action(Nation.FRANCE, new RetreatOrder(french, Province.Mun)));
        RuntimeException invalid = expect(IllegalArgumentException.class, () -> SIMULATOR.advance(state, actions));
        require(invalid.getMessage().contains("FRANCE") && invalid.getMessage().contains("ORDER_NOT_IN_CATALOG"),
                "An invalid retreat must report its power and validation error");
    }
    private static void adjustments() {
        UnitId french = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId german = unit(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        UnitId removed = unit(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        BoardState board = new BoardState(Map.of(french, Province.Bur, german, Province.Mun, removed, Province.Ber),
                Map.of(Province.Par, Nation.FRANCE, Province.Bre, Nation.FRANCE, Province.Mun, Nation.GERMANY,
                        Province.Stp, Nation.RUSSIA, Province.Mos, Nation.RUSSIA));
        GameState_Adjustments state = winter(board);
        Map<Nation, ActionCandidate> actions = joint(
                action(Nation.FRANCE, new BuildOrder(Nation.FRANCE, UnitType.FLEET, Province.Bre)),
                action(Nation.GERMANY, new DisbandOrder(Nation.GERMANY, removed)),
                action(Nation.RUSSIA, new BuildOrder(Nation.RUSSIA, UnitType.FLEET, Province.StpSC),
                        new WaiveOrder(Nation.RUSSIA)));
        GameState next = SIMULATOR.advance(state, actions);
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
        require(position(next).equals(position(SIMULATOR.advance(state, actions))),
                "Repeated winter simulations agree on positions and types despite new UUIDs");

        Map<Nation, ActionCandidate> missing = new EnumMap<>(actions);
        missing.put(Nation.GERMANY, action(Nation.GERMANY));
        RuntimeException invalid = expect(IllegalArgumentException.class, () -> SIMULATOR.advance(state, missing));
        require(invalid.getMessage().contains("MISSING_ADJUSTMENTS"), "Mandatory disbands must not be defaulted");
        Map<Nation, ActionCandidate> duplicate = new EnumMap<>(actions);
        duplicate.put(Nation.RUSSIA, action(Nation.RUSSIA,
                new BuildOrder(Nation.RUSSIA, UnitType.FLEET, Province.StpNC),
                new BuildOrder(Nation.RUSSIA, UnitType.FLEET, Province.StpSC)));
        expect(IllegalArgumentException.class, () -> SIMULATOR.advance(state, duplicate));
        Map<Nation, ActionCandidate> excess = new EnumMap<>(actions);
        excess.put(Nation.FRANCE, action(Nation.FRANCE,
                new BuildOrder(Nation.FRANCE, UnitType.FLEET, Province.Bre), new WaiveOrder(Nation.FRANCE)));
        expect(IllegalArgumentException.class, () -> SIMULATOR.advance(state, excess));
        Map<Nation, ActionCandidate> wrongPhase = new EnumMap<>(actions);
        wrongPhase.put(Nation.FRANCE, action(Nation.FRANCE, Order.hold(french)));
        expect(IllegalArgumentException.class, () -> SIMULATOR.advance(state, wrongPhase));
        require(state.board().equals(board), "Rejected submissions must leave the input unchanged");
    }

    private static void invalidInputs() {
        GameState state = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(), Map.of()));
        Map<Nation, ActionCandidate> actions = joint();
        expect(NullPointerException.class, () -> SIMULATOR.advance(null, actions));
        expect(NullPointerException.class, () -> SIMULATOR.advance(state, null));
        Map<Nation, ActionCandidate> missing = new EnumMap<>(actions);
        missing.remove(Nation.TURKEY);
        expect(IllegalArgumentException.class, () -> SIMULATOR.advance(state, missing));
        Map<Nation, ActionCandidate> mismatch = new EnumMap<>(actions);
        mismatch.put(Nation.FRANCE, action(Nation.GERMANY));
        expect(IllegalArgumentException.class, () -> SIMULATOR.advance(state, mismatch));
        Map<Nation, ActionCandidate> nullCandidate = new EnumMap<>(actions);
        nullCandidate.put(Nation.FRANCE, null);
        expect(NullPointerException.class, () -> SIMULATOR.advance(state, nullCandidate));
        Map<Nation, ActionCandidate> nullKey = new HashMap<>(actions);
        nullKey.put(null, action(Nation.FRANCE));
        expect(NullPointerException.class, () -> SIMULATOR.advance(state, nullKey));
        expect(NullPointerException.class, () -> new JointActionSimulator(null, 4, 0L));
        expect(IllegalArgumentException.class, () -> new JointActionSimulator(MovementProcessor.Policy.JUSTICE, 0, 0L));

        GameState winter = new JointActionSimulator().advance(state, actions);
        require(winter instanceof GameState_Adjustments, "Empty Fall movement must reach winter through Game");
        GameState spring = SIMULATOR.advance(winter, actions);
        require(spring.moment().equals(new GameMoment(1908, GamePhase.SPRING_MOVEMENT)),
                "A balanced winter still advances to the following Spring");
        UnitId french = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState occupied = new GameState_Movement(state.moment(),
                new BoardState(Map.of(french, Province.Par), Map.of()));
        expect(IllegalArgumentException.class, () -> SIMULATOR.advance(occupied, actions));
        actions.put(Nation.FRANCE, action(Nation.FRANCE, Order.move(french, Province.NTH)));
        expect(IllegalArgumentException.class, () -> SIMULATOR.advance(occupied, actions));
    }

    private static void generatedActions() {
        Game live = StandardGameFactory.create1901();
        GameState initial = live.snapshot();
        GameState state = initial;
        for (int step = 0; step < 8; step++) {
            Map<Nation, ActionCandidate> actions = new EnumMap<>(Nation.class);
            for (Nation nation : Nation.values()) {
                LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, nation);
                List<ActionCandidate> candidates = new UniformActionGenerator().generate(
                        new ActionGenerationRequest(state, nation, 6, 71L + step), catalog);
                actions.put(nation, candidates.getLast());
            }
            GameMoment before = state.moment();
            state = SIMULATOR.advance(state, actions);
            require(state.moment().compareTo(before) > 0, "Generated joint actions must advance the game");
        }
        require(state.moment().year() >= 1902, "Generated actions must cross a winter boundary");
        require(live.snapshot().equals(initial), "A simulated sequence must leave the live game unchanged");
    }

    private static GameState_Adjustments winter(BoardState board) {
        MovementResult movement = new MovementProcessor(MovementProcessor.Policy.SZYKMAN_JUSTICE, 4, 0L)
                .process(new MovementInput(board.locations(), List.of()));
        RetreatResult retreats = new RetreatProcessor().process(new RetreatInput(movement, List.of()));
        return new GameState_Adjustments(new GameMoment(1907, GamePhase.WINTER_ADJUSTMENT), board, retreats);
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