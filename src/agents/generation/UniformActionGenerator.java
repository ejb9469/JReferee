package agents.generation;

import contracts.OrderForm;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;
import game.GameState_Adjustments;
import game.GameState_Movement;
import phase.Order;
import phase.UnitId;
import phase.adjustments.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generates candidates using a uniform baseline policy without convoys.
 *
 * <p>Movement proposals omit CONVOY orders, non-adjacent Army moves, and
 * supports for those moves. Fleet movement and support for ordinary adjacent
 * moves remain available. The legal catalog and adjudication rules are
 * unchanged. FriendlyConvoyUniformActionGenerator enables same-nation convoys.</p>
 *
 * <p>The first action is all-hold in movement, or all-omitted in retreats.
 * Omission destroys a dislodged unit. Further actions are sampled without keeping
 * duplicates. Each candidate's logPrior is its probability under the full
 * phase policy, expressed as a natural logarithm. Movement and retreat
 * choices are independent and uniform per unit. Movement priors use the
 * remaining options after convoy-dependent orders have been removed.</p>
 *
 * <p>Units are ordered by current province; options by type and targets.
 * Equivalent states and the same seed therefore produce the same candidates
 * regardless of map or catalog iteration order.</p>
 *
 * <p>The target count is the smaller of candidateLimit and the action-space
 * size. At most 32 samples per target candidate are drawn. The returned list
 * may be shorter if duplicates exhaust that budget.</p>
 *
 * <p>Winter builds are uniform over legal build combinations, with unused
 * builds represented by omission. Disbands are uniform over subsets of the
 * required size. The first winter action is no builds, or the first required
 * units in province order.</p>
 */
public class UniformActionGenerator implements ActionGenerator {

    public static final int SAMPLES_PER_CANDIDATE = 32;

    static final Comparator<OrderForm> ORDER_COMPARATOR =
            Comparator.comparing(OrderForm::orderType)
                    .thenComparing(OrderForm::target,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(OrderForm::auxiliaryTarget,
                            Comparator.nullsFirst(Comparator.naturalOrder()));

    @Override
    public List<ActionCandidate> generate(
            ActionGenerationRequest request,
            LegalOrderCatalog catalog
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(catalog, "catalog");
        catalog.requireMatches(request.state(), request.power());
        if (request.state() instanceof GameState_Movement movement)
            catalog = withoutConvoys(movement, catalog);
        return generateFromCatalog(request, catalog);
    }

    /**
     * Shared sampler for generators that supply their own restricted catalog.
     * This method applies no additional convoy policy.
     */
    List<ActionCandidate> generateFromCatalog(
            ActionGenerationRequest request,
            LegalOrderCatalog catalog
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(catalog, "catalog");
        catalog.requireMatches(request.state(), request.power());

        if (request.state() instanceof GameState_Adjustments)
            return catalog.adjustmentBalance().requiredDisbands() > 0
                    ? generateDisbands(request, catalog)
                    : generateBuilds(request, catalog);

        List<UnitId> units = new ArrayList<>(catalog.optionsByUnit().keySet());
        units.sort(Comparator.comparing(catalog.unitLocations()::get));

        List<List<OrderForm>> optionsByUnit = new ArrayList<>();
        List<OrderForm> defaults = new ArrayList<>();
        int omissionChoices = catalog.allowsOmission() ? 1 : 0;
        double logPrior = logPrior(catalog);
        int targetSize = 1;

        for (UnitId unit : units) {
            List<OrderForm> options = new ArrayList<>(catalog.optionsFor(unit));
            options.sort(ORDER_COMPARATOR);
            optionsByUnit.add(options);
            if (!catalog.allowsOmission())
                defaults.add(Order.hold(unit));
            int choiceCount = options.size() + omissionChoices;
            targetSize = (int) Math.min(
                    request.candidateLimit(), (long) targetSize * choiceCount);
        }

        List<ActionCandidate> candidates = new ArrayList<>();
        candidates.add(checkedCandidate(
                request, catalog, defaults, logPrior, ActionCandidate.Source.HEURISTIC));

        Set<List<OrderForm>> seen = new HashSet<>();
        seen.add(List.copyOf(defaults));

        Random random = new Random(request.seed());
        long sampleLimit = (long) SAMPLES_PER_CANDIDATE * targetSize;

        for (long sample = 0;
             sample < sampleLimit && candidates.size() < targetSize;
             sample++) {
            List<OrderForm> orders = new ArrayList<>();
            for (List<OrderForm> options : optionsByUnit) {
                int choice = random.nextInt(options.size() + omissionChoices);
                if (choice < options.size())
                    orders.add(options.get(choice));
            }

            List<OrderForm> action = List.copyOf(orders);
            if (seen.add(action))
                candidates.add(checkedCandidate(
                        request, catalog, action, logPrior,
                        ActionCandidate.Source.POLICY_SAMPLE));
        }

        return List.copyOf(candidates);
    }

    /**
     * Shared movement restriction for the uniform and plausible generators.
     */
    static LegalOrderCatalog withoutConvoys(
            GameState_Movement state, LegalOrderCatalog catalog
    ) {
        Map<Province, UnitId> occupants = new LinkedHashMap<>();
        state.board().locations().forEach((unit, location) ->
                occupants.put(Province.canonical(location), unit));
        Map<UnitId, List<OrderForm>> options = new LinkedHashMap<>();
        for (var entry : catalog.optionsByUnit().entrySet()) {
            List<OrderForm> retained = new ArrayList<>();
            for (OrderForm form : entry.getValue()) {
                Order order = (Order) form;
                if (order.orderType() == OrderType.CONVOY)
                    continue;
                UnitId mover = order.orderType() == OrderType.MOVE ? order.unit()
                        : order.orderType() == OrderType.SUPPORT && order.auxiliaryTarget() != null
                        ? occupants.get(Province.canonical(order.target())) : null;
                Province destination = order.orderType() == OrderType.MOVE
                        ? order.target() : order.auxiliaryTarget();
                if (mover != null && mover.unitType() == UnitType.ARMY
                        && !state.board().locationOf(mover).isAdjacentTo(destination))
                    continue;
                retained.add(order);
            }
            options.put(entry.getKey(), retained);
        }
        return new LegalOrderCatalog(state, catalog.power(), options);
    }

    /**
     * Full-action prior under independent, uniform per-unit choices.
     * The supplied movement or retreat catalog already defines the policy.
     */
    static double logPrior(LegalOrderCatalog catalog) {
        List<UnitId> units = new ArrayList<>(catalog.unitLocations().keySet());
        units.sort(Comparator.comparing(catalog.unitLocations()::get));
        double result = 0.0;
        for (UnitId unit : units)
            result -= StrictMath.log(catalog.optionsFor(unit).size()
                    + (catalog.allowsOmission() ? 1 : 0));
        return result;
    }

    private static List<ActionCandidate> generateBuilds(
            ActionGenerationRequest request, LegalOrderCatalog catalog
    ) {
        Map<Province, List<BuildOrder>> bySite = new TreeMap<>();
        for (BuildOrder build : catalog.buildOptions())
            bySite.computeIfAbsent(Province.canonical(build.location()),
                    ignored -> new ArrayList<>()).add(build);
        for (List<BuildOrder> options : bySite.values())
            options.sort(Comparator.comparing(BuildOrder::unitType).thenComparing(BuildOrder::location));

        // The standard board has at most four home centers per nation.
        List<List<OrderForm>> actions = new ArrayList<>();
        collectBuildActions(new ArrayList<>(bySite.values()), 0,
                catalog.adjustmentBalance().availableBuilds(), new ArrayList<>(), actions);
        double logPrior = 0.0 - StrictMath.log(actions.size());
        List<ActionCandidate> candidates = new ArrayList<>();
        candidates.add(checkedCandidate(request, catalog, actions.removeFirst(),
                logPrior, ActionCandidate.Source.HEURISTIC));
        Collections.shuffle(actions, new Random(request.seed()));
        for (List<OrderForm> action : actions) {
            if (candidates.size() == request.candidateLimit())
                break;
            candidates.add(checkedCandidate(request, catalog, action,
                    logPrior, ActionCandidate.Source.POLICY_SAMPLE));
        }
        return List.copyOf(candidates);
    }

    private static void collectBuildActions(
            List<List<BuildOrder>> sites, int index, int remaining,
            List<OrderForm> current, List<List<OrderForm>> actions
    ) {
        if (index == sites.size() || remaining == 0) {
            actions.add(List.copyOf(current));
            return;
        }
        collectBuildActions(sites, index + 1, remaining, current, actions);
        for (BuildOrder build : sites.get(index)) {
            current.add(build);
            collectBuildActions(sites, index + 1, remaining - 1, current, actions);
            current.removeLast();
        }
    }

    private static List<ActionCandidate> generateDisbands(
            ActionGenerationRequest request, LegalOrderCatalog catalog
    ) {
        List<UnitId> units = new ArrayList<>(catalog.unitLocations().keySet());
        Comparator<UnitId> byLocation = Comparator.comparing(catalog.unitLocations()::get);
        units.sort(byLocation);
        int required = catalog.adjustmentBalance().requiredDisbands();
        BigInteger combinations = numberOfSubsets(units.size(), required);
        int target = combinations.min(BigInteger.valueOf(request.candidateLimit())).intValueExact();
        double logPrior = 0.0 - StrictMath.log(combinations.doubleValue());
        List<OrderForm> first = disbandOrders(units.subList(0, required), request.power());
        List<ActionCandidate> candidates = new ArrayList<>();
        candidates.add(checkedCandidate(request, catalog, first, logPrior, ActionCandidate.Source.HEURISTIC));
        Set<List<OrderForm>> seen = new HashSet<>();
        seen.add(first);
        Random random = new Random(request.seed());

        for (long sample = 0;
             sample < (long) SAMPLES_PER_CANDIDATE * target && candidates.size() < target;
             sample++) {
            List<UnitId> shuffled = new ArrayList<>(units);
            Collections.shuffle(shuffled, random);
            List<UnitId> selected = new ArrayList<>(shuffled.subList(0, required));
            selected.sort(byLocation);
            List<OrderForm> action = disbandOrders(selected, request.power());
            if (seen.add(action))
                candidates.add(checkedCandidate(request, catalog, action,
                        logPrior, ActionCandidate.Source.POLICY_SAMPLE));
        }
        return List.copyOf(candidates);
    }

    private static List<OrderForm> disbandOrders(List<UnitId> units, Nation power) {
        List<OrderForm> orders = new ArrayList<>();
        for (UnitId unit : units)
            orders.add(new DisbandOrder(power, unit));
        return List.copyOf(orders);
    }

    private static BigInteger numberOfSubsets(int size, int count) {
        BigInteger result = BigInteger.ONE;
        for (int index = 1; index <= Math.min(count, size - count); index++)
            result = result.multiply(BigInteger.valueOf(size - index + 1))
                    .divide(BigInteger.valueOf(index));
        return result;
    }

    private static ActionCandidate checkedCandidate(
            ActionGenerationRequest request,
            LegalOrderCatalog catalog,
            List<OrderForm> orders,
            double logPrior,
            ActionCandidate.Source source
    ) {
        ActionCandidate candidate = new ActionCandidate(
                request.power(), orders, logPrior, source);
        List<ActionValidationError> errors = new CatalogActionValidator()
                .validate(request.state(), candidate, catalog);

        if (!errors.isEmpty())
            throw new IllegalStateException(
                    "Generated an invalid action: " + errors);

        return candidate;
    }

}