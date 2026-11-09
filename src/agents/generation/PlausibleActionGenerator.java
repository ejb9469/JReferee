package agents.generation;

import contracts.OrderForm;
import contracts.OrderForms;
import domain.*;
import game.GameState_Movement;
import phase.Order;
import phase.UnitId;

import java.util.*;

/**
 * Constructs complete movement actions from compatible tactical bundles.
 *
 * <p>Proposes supported attacks, supported defenses, possible support cuts,
 * vacancy chains, rotations, and self-bounces. Different tactical kinds and
 * target provinces receive separate places in the proposal sequence.
 * A small beam completes each bundle across the rest of the nation.</p>
 *
 * <p>Own support-to-move orders require a matching submitted move.
 * Own support-hold orders require a stationary unit, which may itself support.
 * Foreign support remains available during completion, but depends on orders
 * that this generator cannot choose. A structural plan may still bounce,
 * lose support, or be defeated by an opponent.</p>
 *
 * <p>Movement uses the same convoy-free catalog and factorized uniform anchor
 * as UniformActionGenerator. Construction scores only rank proposals.
 * They do not change logPrior or describe the probability of constructing an
 * action. The anchor is not renormalized after coherence checks or truncation.</p>
 *
 * <p>The first candidate is all-hold. When the target pool has at least 4
 * places, roughly a quarter are reserved for randomized completion.
 * Retreats and adjustments delegate unchanged to UniformActionGenerator.</p>
 *
 * <p>Movement returns at most 256 candidates. Beams retain 8 partial actions;
 * each kind retains at most 8 bundles per objective. Chain construction visits
 * at most 32 extensions per starting move and reaches at most 4 units.
 * At most 4 completion attempts per target candidate are made. Duplicate
 * proposals or these budgets may leave the returned pool below its limit.</p>
 *
 * <p>Small pools cannot cover every useful plan. Proposal ranking uses local
 * geometry and supply-center ownership, without adjudication or opponent
 * probabilities. Response-driven expansion, friendly convoy bundles, and a
 * learned anchor remain future work. BestResponseSearch and evaluation decide which of
 * these candidates are worth playing.</p>
 */
public class PlausibleActionGenerator implements ActionGenerator {

    // constants \\
    public static final int MAX_CANDIDATES = 256;
    public static final int BEAM_WIDTH = 8;
    public static final int BUNDLES_PER_OBJECTIVE = 8;
    public static final int CHAIN_VISITS = 32;
    public static final int CHAIN_LENGTH = 4;

    /**
     * Generates distinct actions for the supplied state and nation.
     *
     * <p>All movement orders come from the restricted catalog. Units follow
     * current-province order; catalog and board iteration order do not affect
     * seeded output. All working state belongs to this invocation.</p>
     *
     * @param request state, nation, candidate limit, and restarted random seed
     * @param catalog legal options for that state and nation
     * @return immutable candidates with fixed full-action anchor priors
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if the catalog does not match the request
     * @throws IllegalStateException if construction produces an invalid action
     */
    @Override
    public List<ActionCandidate> generate(
            ActionGenerationRequest request,
            LegalOrderCatalog catalog
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(catalog, "catalog");
        catalog.requireMatches(request.state(), request.power());
        if (!(request.state() instanceof GameState_Movement movement))
            return new UniformActionGenerator().generate(request, catalog);
        return new Construction(request,
                UniformActionGenerator.withoutConvoys(movement, catalog)).generate();
    }

    // Tactical seeds and partial actions \\

    private enum Kind { ATTACK, DEFENSE, CUT, CHAIN, COMBINED, MOVE, BOUNCE }

    private record Plan(Map<UnitId, Order> orders, double rank) { }

    /**
     * Per-call construction state. No game or catalog is mutated.
     */
    private static final class Construction {

        private final ActionGenerationRequest request;
        private final LegalOrderCatalog catalog;
        private final List<UnitId> units;
        private final Map<UnitId, List<Order>> options = new LinkedHashMap<>();
        private final Map<Province, UnitId> occupants = new EnumMap<>(Province.class);
        private final Map<Order, Double> ranks = new HashMap<>();
        private final Map<Kind, Map<Province, List<List<Order>>>> bundles = new EnumMap<>(Kind.class);
        private final Map<List<OrderForm>, ActionCandidate> candidates = new LinkedHashMap<>();
        private final Random random;
        private final double logPrior;
        private final int target;

        private Construction(ActionGenerationRequest request, LegalOrderCatalog catalog) {
            this.request = request;
            this.catalog = catalog;
            this.random = new Random(request.seed());
            this.logPrior = UniformActionGenerator.logPrior(catalog);
            this.units = new ArrayList<>(catalog.unitLocations().keySet());
            units.sort(Comparator.comparing(catalog.unitLocations()::get));
            request.state().board().locations().forEach((unit, location) ->
                    occupants.put(Province.canonical(location), unit));

            int count = 1;
            for (UnitId unit : units) {
                List<Order> orders = catalog.optionsFor(unit).stream()
                        .map(form -> (Order) form)
                        .sorted(UniformActionGenerator.ORDER_COMPARATOR).toList();
                options.put(unit, orders);
                count = (int) Math.min(Math.min(request.candidateLimit(), MAX_CANDIDATES),
                        (long) count * orders.size());
            }
            this.target = count;
            options.values().forEach(orders -> orders.forEach(order -> ranks.put(order, rank(order))));
            for (Kind kind : Kind.values())
                bundles.put(kind, new EnumMap<>(Province.class));
        }

        private List<ActionCandidate> generate() {
            Map<UnitId, Order> holds = new LinkedHashMap<>();
            units.forEach(unit -> holds.put(unit, Order.hold(unit)));
            add(holds, ActionCandidate.Source.HEURISTIC);
            if (target == 1)
                return List.copyOf(candidates.values());

            prepareBundles();
            List<List<Order>> seeds = seedSequence();
            int directedTarget = target - (target >= 4 ? Math.max(1, target / 4) : 0);
            int attempts = 0;
            int nextSeed = 0;

            // Give different plans a turn before taking another result from one beam.
            while (candidates.size() < directedTarget && nextSeed < seeds.size()
                    && attempts < 2 * target) {
                addFirst(complete(seeds.get(nextSeed++), false), ActionCandidate.Source.HEURISTIC);
                attempts++;
            }

            // Randomized construction can reach plans outside the tactical seed list.
            while (candidates.size() < target && attempts < 4 * target) {
                addFirst(complete(List.of(), true), ActionCandidate.Source.EXPLORATION);
                attempts++;
            }
            return List.copyOf(candidates.values());
        }

        // Bundle construction \\

        private void prepareBundles() {
            List<Order> moves = options.values().stream().flatMap(Collection::stream)
                    .filter(order -> order.orderType() == OrderType.MOVE).toList();
            List<Order> supports = options.values().stream().flatMap(Collection::stream)
                    .filter(order -> order.orderType() == OrderType.SUPPORT).toList();

            for (Order move : moves) {
                offer(Kind.MOVE, move.target(), List.of(move));
                List<Order> matching = supports.stream()
                        .filter(support -> move.unit().equals(supportedUnit(support))
                                && matches(support, move)).toList();

                // Include concentrated attacks before the bounded single-support variants.
                Map<UnitId, Order> attack = new LinkedHashMap<>();
                attack.put(move.unit(), move);
                for (Order support : matching)
                    attack.putIfAbsent(support.unit(), support);
                if (attack.size() > 1)
                    offer(Kind.ATTACK, move.target(), new ArrayList<>(attack.values()));
                for (Order support : matching)
                    offer(Kind.ATTACK, move.target(), List.of(move, support));

                chains(new ArrayList<>(List.of(move)), move, new int[] { CHAIN_VISITS });
                for (Order other : moves) {
                    if (other.unit().equals(move.unit()))
                        continue;
                    if (Province.equalsIgnoreCoast(move.target(), other.target()))
                        offer(Kind.BOUNCE, move.target(), List.of(move, other));
                    UnitId defender = occupant(move.target());
                    UnitId supporter = occupant(other.target());
                    if (defender != null && supporter != null
                            && defender.owner() != request.power()
                            && supporter.owner() != request.power()
                            && !defender.equals(supporter)
                            && !Province.equalsIgnoreCoast(location(other.unit()), move.target())
                            && OrderForms.canSupportTo(supporter.unitType(),
                            location(supporter), move.target())) {
                        Map<UnitId, Order> cutAttack = new LinkedHashMap<>(attack);
                        cutAttack.put(other.unit(), other);
                        offer(Kind.CUT, move.target(), new ArrayList<>(cutAttack.values()));
                        offer(Kind.CUT, move.target(), List.of(move, other));
                    }
                }
            }
            for (Order support : supports)
                if (support.auxiliaryTarget() == null
                        && supportedUnit(support) != null
                        && supportedUnit(support).owner() == request.power())
                    offer(Kind.DEFENSE, support.target(), List.of(support));

            // Preserve plans that coordinate attacks in two different areas.
            var attacks = new ArrayList<>(bundles.get(Kind.ATTACK).entrySet());
            for (int first = 0; first < attacks.size(); first++)
                for (int second = first + 1; second < attacks.size(); second++) {
                    List<Order> combined = new ArrayList<>(attacks.get(first).getValue().getFirst());
                    combined.addAll(attacks.get(second).getValue().getFirst());
                    offer(Kind.COMBINED, attacks.get(first).getKey(), combined);
                }
        }

        /**
         * Follows moves out of friendly occupied provinces, including cycles.
         * Two-unit swaps remain possible self-bounces; longer cycles may rotate.
         */
        private void chains(List<Order> path, Order last, int[] remaining) {
            if (path.size() > 1)
                offer(Kind.CHAIN, path.getFirst().target(), path);
            UnitId next = occupant(last.target());
            if (path.size() == CHAIN_LENGTH || next == null || next.owner() != request.power()
                    || path.stream().anyMatch(order -> order.unit().equals(next)))
                return;
            for (Order move : options.get(next)) {
                if (move.orderType() != OrderType.MOVE)
                    continue;
                if (remaining[0]-- <= 0)
                    return;
                path.add(move);
                chains(path, move, remaining);
                path.removeLast();
            }
        }

        /**
         * Keeps a bounded set per tactical kind and target province.
         * Equivalent submitted orders share one entry within each bucket.
         */
        private void offer(Kind kind, Province objective, List<Order> orders) {
            Map<UnitId, Order> assignments = new LinkedHashMap<>();
            for (Order order : orders) {
                Order previous = assignments.putIfAbsent(order.unit(), order);
                if (previous != null && !previous.equals(order))
                    return;
            }
            if (!consistent(assignments))
                return;
            List<Order> key = ordered(assignments);
            List<List<Order>> bucket = bundles.get(kind)
                    .computeIfAbsent(Province.canonical(objective), ignored -> new ArrayList<>());
            if (bucket.size() < BUNDLES_PER_OBJECTIVE && !bucket.contains(key))
                bucket.add(key);
        }

        /**
         * Interleaves kinds and objectives. Ranking never supplies logPrior.
         */
        private List<List<Order>> seedSequence() {
            List<List<List<Order>>> byKind = new ArrayList<>();
            for (Kind kind : Kind.values()) {
                List<List<List<Order>>> objectives = new ArrayList<>(bundles.get(kind).values());
                Collections.shuffle(objectives, random);
                objectives.sort(Comparator.comparingDouble(
                        (List<List<Order>> bucket) -> seedRank(bucket.getFirst())).reversed());
                List<List<Order>> sequence = new ArrayList<>();
                for (int depth = 0; depth < BUNDLES_PER_OBJECTIVE; depth++)
                    for (List<List<Order>> bucket : objectives)
                        if (depth < bucket.size())
                            sequence.add(bucket.get(depth));
                byKind.add(sequence);
            }
            List<List<Order>> result = new ArrayList<>();
            int longest = byKind.stream().mapToInt(List::size).max().orElse(0);
            for (int index = 0; index < longest; index++)
                for (List<List<Order>> sequence : byKind)
                    if (index < sequence.size())
                        result.add(sequence.get(index));
            return result;
        }

        // Complete-action construction \\

        /**
         * Extends a seed one unassigned unit at a time.
         * Own support requirements constrain both assigned and future orders.
         */
        private List<Plan> complete(List<Order> seed, boolean explore) {
            Map<UnitId, Order> initial = new LinkedHashMap<>();
            seed.forEach(order -> initial.put(order.unit(), order));
            List<Plan> beam = List.of(new Plan(initial, seedRank(seed)));
            for (UnitId unit : units) {
                if (initial.containsKey(unit))
                    continue;
                List<Plan> next = new ArrayList<>();
                for (Plan plan : beam)
                    for (Order order : options.get(unit)) {
                        Map<UnitId, Order> assignments = new LinkedHashMap<>(plan.orders());
                        assignments.put(unit, order);
                        if (consistent(assignments))
                            next.add(new Plan(assignments, explore
                                    ? plan.rank() + random.nextDouble() : score(assignments)));
                    }
                if (next.isEmpty())
                    throw new IllegalStateException("A coherent partial action could not be completed");
                Collections.shuffle(next, random);
                next.sort(Comparator.comparingDouble(Plan::rank).reversed());
                beam = List.copyOf(next.subList(0, Math.min(BEAM_WIDTH, next.size())));
            }
            return beam;
        }

        /**
         * Rejects contradictory support requirements without adjudicating them.
         * Duplicate move destinations and friendly vacancy dependencies remain.
         */
        private boolean consistent(Map<UnitId, Order> assignments) {
            Map<UnitId, Province> required = new HashMap<>();
            for (Order order : assignments.values()) {
                if (order.orderType() != OrderType.SUPPORT)
                    continue;
                UnitId recipient = supportedUnit(order);
                if (recipient == null)
                    return false;
                if (recipient.owner() != request.power())
                    continue;
                Province destination = order.auxiliaryTarget() == null
                        ? null : Province.canonical(order.auxiliaryTarget());
                if (required.containsKey(recipient)
                        && required.get(recipient) != destination)
                    return false;
                required.put(recipient, destination);
                Order submitted = assignments.get(recipient);
                if (submitted != null ? !matches(order, submitted)
                        : options.get(recipient).stream().noneMatch(option -> matches(order, option)))
                    return false;
            }
            return true;
        }

        private static boolean matches(Order support, Order recipient) {
            return support.auxiliaryTarget() == null
                    ? recipient.orderType() != OrderType.MOVE
                    : recipient.orderType() == OrderType.MOVE
                    && Province.equalsIgnoreCoast(support.auxiliaryTarget(), recipient.target());
        }

        private void addFirst(List<Plan> beam, ActionCandidate.Source source) {
            for (Plan plan : beam)
                if (add(plan.orders(), source))
                    return;
        }

        /**
         * Deduplicates complete submitted orders before recording their source.
         */
        private boolean add(Map<UnitId, Order> assignments, ActionCandidate.Source source) {
            List<OrderForm> orders = new ArrayList<>(ordered(assignments));
            if (candidates.containsKey(orders))
                return false;
            if (assignments.size() != units.size() || !consistent(assignments))
                throw new IllegalStateException("Generated an incomplete or inconsistent action");
            ActionCandidate candidate = new ActionCandidate(request.power(), orders, logPrior, source);
            List<ActionValidationError> errors = new CatalogActionValidator()
                    .validate(request.state(), candidate, catalog);
            if (!errors.isEmpty())
                throw new IllegalStateException("Generated an invalid action: " + errors);
            candidates.put(candidate.orders(), candidate);
            return true;
        }

        // Local proposal ranking \\

        /**
         * Prefers compatible activity while completing a fixed tactical seed.
         * Penalties affect ordering only; explicit bounce seeds remain available.
         */
        private double score(Map<UnitId, Order> assignments) {
            double result = 0.0;
            Set<Province> destinations = EnumSet.noneOf(Province.class);
            for (Order order : assignments.values()) {
                result += ranks.get(order);
                if (order.orderType() == OrderType.MOVE) {
                    if (!destinations.add(Province.canonical(order.target())))
                        result -= 6.0;
                    UnitId occupant = occupant(order.target());
                    Order other = assignments.get(occupant);
                    if (other != null && other.orderType() != OrderType.MOVE)
                        result -= 4.0;
                } else if (order.orderType() == OrderType.SUPPORT
                        && order.auxiliaryTarget() != null
                        && assignments.containsKey(supportedUnit(order))) {
                    result += 2.0;
                }
            }
            return result;
        }

        /**
         * Cheap construction preferences. No score claims that an order succeeds.
         * Defensive seeds and exploration keep lower-ranked alternatives available.
         */
        private double rank(Order order) {
            if (order.orderType() == OrderType.HOLD)
                return 0.0;
            if (order.orderType() == OrderType.MOVE) {
                Province destination = Province.canonical(order.target());
                double score = 1.0;
                if (destination.supplyCenter)
                    score += request.state().board().ownerOf(destination) == request.power() ? 0.5 : 3.0;
                UnitId defender = occupant(destination);
                return score + (defender != null && defender.owner() != request.power() ? 1.0 : 0.0);
            }
            UnitId recipient = supportedUnit(order);
            if (recipient == null || recipient.owner() != request.power())
                return 0.05;
            if (order.auxiliaryTarget() == null)
                for (var entry : request.state().board().locations().entrySet())
                    if (entry.getKey().owner() != request.power()
                            && OrderForms.canSupportTo(entry.getKey().unitType(),
                            entry.getValue(), location(recipient)))
                        return 1.75;
            return 0.75;
        }

        private double seedRank(List<Order> seed) {
            return seed.stream().mapToDouble(ranks::get).sum();
        }

        private List<Order> ordered(Map<UnitId, Order> assignments) {
            return units.stream().filter(assignments::containsKey).map(assignments::get).toList();
        }

        private UnitId supportedUnit(Order support) {
            return occupant(support.target());
        }

        private UnitId occupant(Province province) {
            return occupants.get(Province.canonical(province));
        }

        private Province location(UnitId unit) {
            return request.state().board().locationOf(unit);
        }
    }

}