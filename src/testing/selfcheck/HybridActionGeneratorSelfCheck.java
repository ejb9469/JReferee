package testing.selfcheck;

import agents.encoding.*;
import agents.generation.*;
import agents.policy.*;
import contracts.OrderForm;
import domain.*;
import game.*;
import phase.Order;
import phase.UnitId;

import java.util.*;

/**
 * Hybrid reservations, duplicate handling, fixed anchors and own-order matching.
 */
public final class HybridActionGeneratorSelfCheck {

    private static int checks;

    private HybridActionGeneratorSelfCheck() { }

    public static void main(String[] args) {
        reservations();
        generation();
        coordination();
        System.out.println("HybridActionGeneratorSelfCheck passed: " + checks + " checks");
    }

    private static void reservations() {
        var root = (GameState_Movement) StandardGameFactory.create1901().snapshot();
        var encoding = new MovementEncoding(root, Nation.FRANCE);
        var request = new ActionGenerationRequest(root, Nation.FRANCE, 4, 7);
        var anchor = new LinearMovementPolicy(1024);
        List<ActionCandidate> actions = new ArrayList<>();
        for (int option = 0; option < 6; option++) {
            List<Integer> action = new ArrayList<>(Collections.nCopies(encoding.options().size(), 0));
            action.set(0, option);
            actions.add(new ActionCandidate(Nation.FRANCE, encoding.decode(action), 0, ActionCandidate.Source.POLICY_SAMPLE));
        }
        int[] calls = {0, 0};
        ActionGenerator first = (r, c) -> {
            calls[0]++;
            require(r.candidateLimit() == 4 && r.seed() == 7, "Child receives the full bounded budget and unchanged seed");
            return actions.subList(0, 4);
        };
        List<ActionCandidate> plans = List.of(reverse(actions.get(1)), reverse(actions.get(0)),
                reverse(actions.get(4)), reverse(actions.get(5)));
        ActionGenerator second = (r, c) -> { calls[1]++; return plans; };
        var generator = new HybridActionGenerator(first, second, anchor, new UniformActionGenerator());
        var result = generator.propose(request, encoding);
        require(Arrays.equals(calls, new int[]{1, 1}), "Each child is invoked once");
        require(result.overlap() == 2 && result.learnedSelected() == 2 && result.heuristicSelected() == 2,
                "Order-independent overlaps do not consume heuristic reservations");
        require(result.learnedAvailable() == 4 && result.heuristicAvailable() == 4
                && result.learnedQuota() == 2 && result.heuristicQuota() == 2, "Construction counts and quotas");
        require(keys(result.candidates()).equals(keys(List.of(actions.get(0), actions.get(1), plans.get(2), plans.get(3)))),
                "Reserved places follow child order");
        require(result.candidates().getFirst().source() == ActionCandidate.Source.POLICY_SAMPLE
                && result.candidates().getLast().source() == ActionCandidate.Source.HEURISTIC, "Retained source labels");
        for (ActionCandidate candidate : result.candidates())
            require(candidate.logPrior() == anchor.prepare(encoding.input()).logProbability(encoding.encode(candidate.orders())),
                    "Child priors are replaced by the full anchor");
        expect(UnsupportedOperationException.class, () -> result.candidates().clear());

        var shortLearned = new HybridActionGenerator(fixed(actions.subList(0, 1)), fixed(plans),
                anchor, new UniformActionGenerator()).propose(request, encoding);
        require(shortLearned.candidates().size() == 4 && shortLearned.learnedSelected() == 1
                && shortLearned.heuristicSelected() == 3, "Unused learned reservation passes to heuristic plans");
        var shortHeuristic = new HybridActionGenerator(fixed(actions.subList(0, 4)), fixed(plans.subList(0, 2)),
                anchor, new UniformActionGenerator()).propose(request, encoding);
        require(shortHeuristic.candidates().size() == 4 && shortHeuristic.learnedSelected() == 4
                && shortHeuristic.heuristicSelected() == 0, "Learned proposals fill exhausted heuristic places");
        var shortBoth = new HybridActionGenerator(fixed(actions.subList(0, 1)), fixed(List.of(plans.get(1))),
                anchor, new UniformActionGenerator()).propose(request, encoding);
        require(shortBoth.candidates().size() == 1 && shortBoth.overlap() == 1, "Short union is not padded");
        var emptyFirst = new HybridActionGenerator(fixed(List.of()), fixed(plans), anchor, new UniformActionGenerator());
        require(emptyFirst.propose(new ActionGenerationRequest(root, Nation.FRANCE, 1, 7), encoding)
                .heuristicSelected() == 1, "A one-place empty learned pool can use a heuristic candidate");
        var odd = new HybridActionGenerator(fixed(actions), fixed(plans), anchor, new UniformActionGenerator())
                .propose(new ActionGenerationRequest(root, Nation.FRANCE, 5, 7), encoding);
        require(odd.learnedQuota() == 3 && odd.heuristicQuota() == 2 && odd.learnedSelected() == 3
                && odd.heuristicSelected() == 2, "Odd extra reservation goes to learned proposals");

        for (ActionGenerator invalid : List.<ActionGenerator>of(
                (r, c) -> actions,
                (r, c) -> List.of(actions.getFirst(), actions.getFirst()),
                (r, c) -> List.of(new ActionCandidate(Nation.FRANCE, List.of(), 0, ActionCandidate.Source.HEURISTIC)),
                (r, c) -> List.of(new ActionCandidate(Nation.ENGLAND, List.of(), 0, ActionCandidate.Source.HEURISTIC))))
            expect(IllegalStateException.class, () -> new HybridActionGenerator(invalid, fixed(plans),
                    anchor, new UniformActionGenerator()).propose(request, encoding));
        expect(IllegalArgumentException.class, () -> generator.propose(request, new MovementEncoding(root, Nation.ENGLAND)));
    }

    private static void generation() {
        var root = (GameState_Movement) StandardGameFactory.create1901().snapshot();
        var uniform = new LinearMovementPolicy(1024);
        var learner = new LinearMovementPolicy.Learner(1024);
        var france = new MovementEncoding(root, Nation.FRANCE);
        List<Integer> holds = france.encode(france.catalog().unitLocations().keySet().stream().map(Order::hold).toList());
        for (int step = 0; step < 10; step++) learner.learn(france.input(), holds, 0.05);
        var anchor = learner.snapshot();
        var generator = new HybridActionGenerator(uniform, anchor);
        for (Nation power : Nation.values()) {
            var encoding = new MovementEncoding(root, power);
            for (int limit : new int[]{1, 8, 9}) {
                var request = new ActionGenerationRequest(root, power, limit, 7);
                var result = generator.propose(request, encoding);
                require(result.equals(generator.propose(request, encoding.catalog())), "Encoding reuse preserves output");
                require(result.candidates().size() == limit, "Opening pool fills its requested budget");
                require(new HashSet<>(keys(result.candidates())).size() == limit, "Complete actions are distinct");
                require(keys(result.candidates()).equals(keys(new HybridActionGenerator(uniform, uniform)
                        .generate(request, encoding.catalog()))), "Anchor changes do not affect selection");
                var prepared = anchor.prepare(encoding.input());
                for (ActionCandidate candidate : result.candidates()) {
                    require(new CatalogActionValidator().validate(root, candidate, encoding.catalog()).isEmpty(), "Catalog-valid action");
                    require(candidate.logPrior() == prepared.logProbability(encoding.encode(candidate.orders())), "Exact learned anchor");
                }
            }
        }
        var large = generator.propose(new ActionGenerationRequest(root, Nation.FRANCE, 300, 7), france);
        require(large.target() == 256 && large.candidates().size() <= 256, "Movement cap");
        UnitId fleet = new UnitId(Nation.FRANCE, UnitType.FLEET, Province.Bre);
        var coast = movement(Map.of(fleet, Province.MAO), Map.of(Province.Bre, Nation.FRANCE));
        var encoding = new MovementEncoding(coast, Nation.FRANCE);
        var request = new ActionGenerationRequest(coast, Nation.FRANCE, 8, 17);
        var scope = new ProvinceScope(List.of(Province.MAO, Province.Spa));
        var restricted = generator.propose(request, scope.restrict(coast, encoding.catalog()));
        require(restricted.target() == 3 && restricted.candidates().size() == 3, "Scoped finite action count");
        require(restricted.candidates().stream().anyMatch(c -> c.orders().contains(Order.move(fleet, Province.SpaNC)))
                        && restricted.candidates().stream().anyMatch(c -> c.orders().contains(Order.move(fleet, Province.SpaSC))),
                "Exact fleet coasts survive deduplication");
        for (ActionCandidate candidate : restricted.candidates())
            require(candidate.logPrior() == anchor.prepare(encoding.input()).logProbability(encoding.encode(candidate.orders())),
                    "Scope does not normalize the anchor");
        require(new ScopedActionGenerator(generator, scope, anchor::bind).generate(request, encoding.catalog())
                .equals(restricted.candidates()), "Explicit scoped anchor integrates");
        MovementPolicy point = input -> new MovementPolicy.Prepared() {
            @Override public MovementInput input() { return input; }
            @Override public double[] logProbabilities(List<Integer> prefix) {
                input.requirePrefix(prefix);
                double[] values = new double[input.slots().get(prefix.size()).options().size()];
                Arrays.fill(values, Double.NEGATIVE_INFINITY);
                values[0] = 0;
                return values;
            }
        };
        require(new HybridActionGenerator(uniform, point).propose(request, encoding).candidates().stream()
                .anyMatch(c -> c.logPrior() == Double.NEGATIVE_INFINITY), "Zero anchor support is preserved");
        var empty = movement(Map.of(), Map.of(Province.Bre, Nation.FRANCE));
        var noUnits = generator.propose(new ActionGenerationRequest(empty, Nation.FRANCE, 8, 7),
                new MovementEncoding(empty, Nation.FRANCE));
        require(noUnits.target() == 1 && noUnits.candidates().getFirst().orders().isEmpty()
                && noUnits.candidates().getFirst().logPrior() == 0, "Empty complete movement action");
        require(root.equals(StandardGameFactory.create1901().snapshot()), "Root is unchanged");
    }

    private static void coordination() {
        UnitId army = new UnitId(Nation.ENGLAND, UnitType.ARMY, Province.Lvp);
        UnitId north = new UnitId(Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        UnitId west = new UnitId(Nation.ENGLAND, UnitType.FLEET, Province.Edi);
        UnitId foreign = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        var root = movement(Map.of(army, Province.Yor, north, Province.NTH, west, Province.NWG, foreign, Province.Bel),
                Map.of(Province.Lon, Nation.ENGLAND, Province.Par, Nation.FRANCE));
        require(inspect(root, Order.hold(army), Order.convoy(north, Province.Yor, Province.Bel), Order.move(west, Province.Nwy))
                .equals(new MovementCoordination.Counts(0, 0, 1, 1)), "Convoy with a holding own army is mismatched");
        require(inspect(root, Order.move(army, Province.Nwy), Order.convoy(north, Province.Yor, Province.Hol), Order.hold(west))
                .mismatchedConvoys() == 1, "Different convoy destination is mismatched");
        require(inspect(root, Order.move(army, Province.Nwy), Order.convoy(north, Province.Yor, Province.Nwy),
                        Order.supportHold(west, Province.NTH)).equals(new MovementCoordination.Counts(1, 0, 1, 0)),
                "Matching convoy and support to a stationary convoying fleet");
        require(inspect(root, Order.move(army, Province.Hol), Order.supportMove(north, Province.Yor, Province.Hol), Order.hold(west))
                .equals(new MovementCoordination.Counts(1, 0, 0, 0)), "Matching support does not require proof of a convoy route");
        require(inspect(root, Order.move(army, Province.Bel), Order.supportMove(north, Province.Yor, Province.Hol), Order.hold(west))
                .mismatchedSupports() == 1, "Support to a different own destination is mismatched");
        require(inspect(root, Order.hold(army), Order.move(north, Province.Nwy), Order.supportHold(west, Province.NTH))
                .mismatchedSupports() == 1, "Hold support to a moving own unit is mismatched");
        require(!inspect(root, Order.hold(army), Order.supportMove(north, Province.Bel, Province.Hol), Order.hold(west)).mismatched(),
                "Foreign support cannot be assessed from own orders");
        require(inspect(root, Order.hold(army), Order.convoy(north, Province.Bel, Province.Nwy), Order.hold(west))
                .ownConvoys() == 0, "Foreign convoy is outside the own-order check");
        require(inspect(root, Order.move(army, Province.Hol), Order.convoy(north, Province.Yor, Province.Bel),
                        Order.supportMove(west, Province.NTH, Province.Nwy)).equals(new MovementCoordination.Counts(1, 1, 1, 1)),
                "Both mismatch types are counted in one action");
        expect(IllegalArgumentException.class, () -> inspect(root, Order.hold(army)));
        expect(IllegalArgumentException.class, () -> inspect(root, Order.hold(army), Order.hold(army), Order.hold(west)));

        UnitId fleet = new UnitId(Nation.FRANCE, UnitType.FLEET, Province.Bre);
        UnitId supporter = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        var coast = movement(Map.of(fleet, Province.MAO, supporter, Province.Gas), Map.of(Province.Bre, Nation.FRANCE));
        require(!inspect(coast, Order.move(fleet, Province.SpaSC), Order.supportMove(supporter, Province.MAO, Province.SpaNC))
                .mismatched(), "Support destinations match by canonical province");
        var occupiedCoast = movement(Map.of(fleet, Province.SpaSC, supporter, Province.Gas), coast.board().owners());
        require(!inspect(occupiedCoast, Order.hold(fleet), Order.supportHold(supporter, Province.SpaNC)).mismatched(),
                "Supported current location matches by canonical province");
    }

    private static MovementCoordination.Counts inspect(GameState_Movement root, Order... orders) {
        var candidate = new ActionCandidate(orders[0].owner(), List.of(orders), 0, ActionCandidate.Source.HEURISTIC);
        return MovementCoordination.inspect(root, candidate);
    }
    private static ActionGenerator fixed(List<ActionCandidate> candidates) {
        return (request, catalog) -> candidates.subList(0, Math.min(request.candidateLimit(), candidates.size()));
    }
    private static ActionCandidate reverse(ActionCandidate candidate) {
        List<OrderForm> orders = new ArrayList<>(candidate.orders());
        Collections.reverse(orders);
        return new ActionCandidate(candidate.power(), orders, 0, ActionCandidate.Source.HEURISTIC);
    }
    private static List<Set<OrderForm>> keys(List<ActionCandidate> candidates) {
        return candidates.stream().map(candidate -> Set.copyOf(candidate.orders())).toList();
    }
    private static GameState_Movement movement(Map<UnitId, Province> units, Map<Province, Nation> centers) {
        return new GameState_Movement(new GameMoment(1902, GamePhase.FALL_MOVEMENT), new BoardState(units, centers));
    }
    private static void expect(Class<? extends Exception> type, Runnable operation) {
        try { operation.run(); }
        catch (Exception exception) {
            if (!type.isInstance(exception)) throw new AssertionError("Unexpected exception", exception);
            checks++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}