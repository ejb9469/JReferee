package testing.selfcheck;

import agents.generation.*;
import contracts.OrderForm;
import domain.*;
import game.*;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.util.*;

/**
 * Proposal coverage, unchanged anchors, and bounded, repeatable construction.
 */
public final class PlausibleActionGeneratorSelfCheck {

    private static int checks;

    private PlausibleActionGeneratorSelfCheck() { }

    public static void main(String[] args) {
        openingCoverage();
        generationContracts();
        supportedAttack();
        coastsAndSmallCatalogs();
        otherPhases();
        System.out.println("PlausibleActionGeneratorSelfCheck passed: " + checks + " checks");
    }

    private static void openingCoverage() {
        GameState root = StandardGameFactory.create1901().snapshot();
        UnitId london = unitAt(root, Province.Lon);
        UnitId brest = unitAt(root, Province.Bre);
        UnitId marseilles = unitAt(root, Province.Mar);
        for (long seed : new long[] { 0, 7, 17, 31 }) {
            List<ActionCandidate> england = generate(root, Nation.ENGLAND, 4, seed);
            List<ActionCandidate> france = generate(root, Nation.FRANCE, 4, seed);
            require(england.size() == 4 && france.size() == 4, "Opening pools must fill four places");
            require(directed(england, Order.move(london, Province.ENG)),
                    "The directed English pool must include London-Channel");
            require(directed(england, Order.move(london, Province.NTH)),
                    "The directed English pool must also include London-North Sea");
            require(directed(france, Order.move(brest, Province.ENG), Order.move(marseilles, Province.Spa)),
                    "The directed French pool must combine Channel entry and Spain expansion");
            require(directed(france, Order.move(brest, Province.MAO), Order.move(marseilles, Province.Spa)),
                    "The directed French pool must retain an Atlantic alternative with Spain expansion");
            for (List<ActionCandidate> pool : List.of(england, france)) {
                require(pool.getLast().source() == ActionCandidate.Source.EXPLORATION,
                        "Four-candidate pools must retain an exploration place");
                require(pool.getFirst().orders().stream().allMatch(order -> order.orderType() == OrderType.HOLD),
                        "All-hold must remain available first");
            }
            require(directed(generate(root, Nation.TURKEY, 4, seed),
                            Order.move(unitAt(root, Province.Con), Province.Bul)),
                    "Direct neutral-center expansion must reach the Turkish pool");
        }
    }

    private static void generationContracts() {
        GameState root = StandardGameFactory.create1901().snapshot();
        BoardState before = root.board();
        PlausibleActionGenerator generator = new PlausibleActionGenerator();
        for (Nation nation : Nation.values())
            for (long seed : new long[] { 7, 17, 31 })
                for (int limit : new int[] { 1, 4, 30 }) {
                    LegalOrderCatalog catalog = catalog(root, nation);
                    ActionGenerationRequest request = new ActionGenerationRequest(root, nation, limit, seed);
                    List<ActionCandidate> pool = generator.generate(request, catalog);
                    require(!pool.isEmpty() && pool.size() <= limit, "Candidate limit was not respected");
                    require(pool.equals(generator.generate(request, catalog)), "Seeded proposals changed");
                    checkPool(root, nation, pool);
                    expect(UnsupportedOperationException.class, () -> pool.clear());
                    expect(UnsupportedOperationException.class, () -> pool.getFirst().orders().clear());
                }
        require(root.board().equals(before), "Generation changed the board");

        GameState reordered = new GameState_Movement(root.moment(),
                new BoardState(reversed(root.board().locations()), reversed(root.board().owners())));
        for (Nation nation : Nation.values())
            require(generate(root, nation, 8, 7).equals(generate(reordered, nation, 8, 7)),
                    "Board insertion order changed proposals");

        List<ActionCandidate> capped = generate(root, Nation.FRANCE,
                PlausibleActionGenerator.MAX_CANDIDATES + 1, 7);
        require(capped.size() <= PlausibleActionGenerator.MAX_CANDIDATES, "Movement cap was exceeded");
        checkPool(root, Nation.FRANCE, capped);
        expect(IllegalArgumentException.class, () -> generator.generate(
                new ActionGenerationRequest(root, Nation.FRANCE, 4, 7), catalog(root, Nation.ENGLAND)));
    }

    private static void supportedAttack() {
        UnitId bur = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId ruh = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Mar);
        UnitId mun = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        GameState root = movement(Map.of(bur, Province.Bur, ruh, Province.Ruh, mun, Province.Mun),
                Map.of(Province.Par, Nation.FRANCE, Province.Mar, Nation.FRANCE, Province.Mun, Nation.GERMANY));
        ActionGenerator generator = new ScopedActionGenerator(new PlausibleActionGenerator(),
                new ProvinceScope(List.of(Province.Bur, Province.Ruh, Province.Mun)));
        List<ActionCandidate> pool = generator.generate(
                new ActionGenerationRequest(root, Nation.FRANCE, 4, 7), catalog(root, Nation.FRANCE));
        require(directed(pool, Order.move(bur, Province.Mun),
                        Order.supportMove(ruh, Province.Bur, Province.Mun))
                        || directed(pool, Order.move(ruh, Province.Mun),
                        Order.supportMove(bur, Province.Ruh, Province.Mun)),
                "Proposal selection discarded support for an occupied enemy center");
        checkPool(root, Nation.FRANCE, pool);
    }

    private static void coastsAndSmallCatalogs() {
        UnitId fleet = new UnitId(Nation.FRANCE, UnitType.FLEET, Province.Bre);
        GameState coast = movement(Map.of(fleet, Province.MAO), Map.of(Province.Bre, Nation.FRANCE));
        List<ActionCandidate> pool = generate(coast, Nation.FRANCE, 30, 7);
        require(pool.stream().anyMatch(action -> action.orders().contains(Order.move(fleet, Province.SpaNC)))
                        && pool.stream().anyMatch(action -> action.orders().contains(Order.move(fleet, Province.SpaSC))),
                "Spain's two coasts were merged");
        checkPool(coast, Nation.FRANCE, pool);

        UnitId army = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        GameState single = movement(Map.of(army, Province.Pic), Map.of(Province.Par, Nation.FRANCE));
        LegalOrderCatalog holds = new ProvinceScope(List.of(Province.Pic))
                .restrict(single, catalog(single, Nation.FRANCE));
        List<ActionCandidate> only = new PlausibleActionGenerator().generate(
                new ActionGenerationRequest(single, Nation.FRANCE, 30, 7), holds);
        require(only.size() == 1 && only.getFirst().orders().equals(List.of(Order.hold(army))),
                "A hold-only catalog must produce exactly one action");

        GameState empty = movement(Map.of(), Map.of(Province.Par, Nation.FRANCE));
        List<ActionCandidate> noUnits = generate(empty, Nation.FRANCE, 30, 7);
        require(noUnits.size() == 1 && noUnits.getFirst().orders().isEmpty(),
                "A living nation without units must still supply an empty action");
    }

    private static void otherPhases() {
        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId ruh = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        UnitId hol = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Kie);
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bel, ruh, Province.Ruh, hol, Province.Hol),
                        Map.of(Province.Par, Nation.FRANCE, Province.Ber, Nation.GERMANY, Province.Kie, Nation.GERMANY)));
        Game game = Game.fromState(root, new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0),
                new RetreatProcessor(), new AdjustmentProcessor());
        game.resolveMovement(List.of(Order.hold(french), Order.move(ruh, Province.Bel),
                Order.supportMove(hol, Province.Ruh, Province.Bel)));
        require(game.snapshot() instanceof GameState_Retreats, "Fixture must reach retreats");
        compareUniform(game.snapshot(), Nation.FRANCE);
        game.resolveRetreats(List.of());
        require(game.snapshot() instanceof GameState_Adjustments, "Fixture must reach winter");
        compareUniform(game.snapshot(), Nation.FRANCE);
        compareUniform(game.snapshot(), Nation.GERMANY);
    }

    private static void compareUniform(GameState root, Nation nation) {
        ActionGenerationRequest request = new ActionGenerationRequest(root, nation, 8, 7);
        LegalOrderCatalog catalog = catalog(root, nation);
        require(new PlausibleActionGenerator().generate(request, catalog)
                        .equals(new UniformActionGenerator().generate(request, catalog)),
                "Nonmovement proposals changed");
    }

    private static void checkPool(GameState root, Nation nation, List<ActionCandidate> pool) {
        LegalOrderCatalog catalog = catalog(root, nation);
        double prior = new UniformActionGenerator().generate(
                new ActionGenerationRequest(root, nation, 1, 0), catalog).getFirst().logPrior();
        Set<List<OrderForm>> seen = new HashSet<>();
        for (ActionCandidate candidate : pool) {
            require(seen.add(candidate.orders()), "Duplicate complete action");
            require(new CatalogActionValidator().validate(root, candidate, catalog).isEmpty(),
                    "Generated an illegal or incomplete action");
            require(Double.compare(candidate.logPrior(), prior) == 0, "Proposal ranking changed the anchor");
            Map<UnitId, Order> orders = new HashMap<>();
            for (OrderForm form : candidate.orders()) {
                Order order = (Order) form;
                orders.put(order.unit(), order);
                require(order.orderType() != OrderType.CONVOY, "Unexpected convoy order");
                if (order.orderType() == OrderType.MOVE && order.unitType() == UnitType.ARMY)
                    require(root.board().locationOf(order.unit()).isAdjacentTo(order.target()),
                            "Unexpected convoy-dependent Army move");
            }
            for (Order order : orders.values()) {
                if (order.orderType() != OrderType.SUPPORT)
                    continue;
                UnitId recipient = root.board().locations().entrySet().stream()
                        .filter(entry -> Province.equalsIgnoreCoast(entry.getValue(), order.target()))
                        .map(Map.Entry::getKey).findFirst().orElseThrow();
                if (order.auxiliaryTarget() != null && recipient.unitType() == UnitType.ARMY)
                    require(root.board().locationOf(recipient).isAdjacentTo(order.auxiliaryTarget()),
                            "Unexpected support for a convoy-dependent move");
                if (recipient.owner() != nation)
                    continue;
                Order submitted = orders.get(recipient);
                require(order.auxiliaryTarget() == null
                                ? submitted.orderType() != OrderType.MOVE
                                : submitted.orderType() == OrderType.MOVE
                                && Province.equalsIgnoreCoast(order.auxiliaryTarget(), submitted.target()),
                        "A friendly support contradicts the recipient's order");
            }
        }
    }

    private static boolean directed(List<ActionCandidate> pool, Order... orders) {
        return pool.stream().anyMatch(candidate -> candidate.source() == ActionCandidate.Source.HEURISTIC
                && candidate.orders().containsAll(List.of(orders)));
    }

    private static List<ActionCandidate> generate(GameState root, Nation nation, int limit, long seed) {
        return new PlausibleActionGenerator().generate(
                new ActionGenerationRequest(root, nation, limit, seed), catalog(root, nation));
    }

    private static LegalOrderCatalog catalog(GameState root, Nation nation) {
        return new LegalOrderGenerator().generate(root, nation);
    }

    private static GameState movement(Map<UnitId, Province> locations, Map<Province, Nation> owners) {
        return new GameState_Movement(new GameMoment(1907, GamePhase.SPRING_MOVEMENT),
                new BoardState(locations, owners));
    }

    private static UnitId unitAt(GameState root, Province province) {
        return root.board().locations().entrySet().stream().filter(entry -> entry.getValue() == province)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static <K, V> Map<K, V> reversed(Map<K, V> source) {
        List<K> keys = new ArrayList<>(source.keySet());
        Collections.reverse(keys);
        Map<K, V> result = new LinkedHashMap<>();
        keys.forEach(key -> result.put(key, source.get(key)));
        return result;
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
            throw new AssertionError("Expected " + type.getSimpleName(), exception);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}