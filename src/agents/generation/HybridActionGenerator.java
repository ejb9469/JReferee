package agents.generation;

import agents.encoding.MovementEncoding;
import agents.policy.MovementPolicy;
import contracts.OrderForm;
import game.GameState_Movement;

import java.util.*;

/**
 * Combines learned samples and heuristic plans under a fixed movement anchor.
 *
 * <p>Each child constructs at most the target number of candidates once.
 * Half the retained places are reserved for each child, with an odd extra
 * place assigned to learned proposals. Overlapping actions are retained once.
 * Unfilled places use remaining learned proposals, then heuristic proposals.</p>
 *
 * <p>Child order and source labels are preserved. All retained actions are
 * rescored under the full-root anchor. No construction probability is assigned
 * to the combined pool, and no submitted order is changed.</p>
 */
public final class HybridActionGenerator implements ActionGenerator {

    private final ActionGenerator learned, heuristic, fallback;
    private final MovementPolicy anchor;

    public HybridActionGenerator(MovementPolicy proposal, MovementPolicy anchor) {
        this(new PolicyActionGenerator(proposal, anchor), new PlausibleActionGenerator(),
                anchor, new UniformActionGenerator());
    }

    public HybridActionGenerator(ActionGenerator learned, ActionGenerator heuristic,
                                 MovementPolicy anchor, ActionGenerator fallback) {
        this.learned = Objects.requireNonNull(learned, "learned");
        this.heuristic = Objects.requireNonNull(heuristic, "heuristic");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    public record Result(List<ActionCandidate> candidates, int target,
                         int learnedAvailable, int heuristicAvailable,
                         int learnedSelected, int heuristicSelected, int overlap,
                         Long learnedSamples, Long learnedDuplicates) {
        public Result(List<ActionCandidate> candidates, int target, int learnedAvailable, int heuristicAvailable,
                      int learnedSelected, int heuristicSelected, int overlap) {
            this(candidates, target, learnedAvailable, heuristicAvailable, learnedSelected, heuristicSelected,
                    overlap, null, null);
        }
        public Result {
            candidates = List.copyOf(candidates);
            if (target < 1 || target > PolicyActionGenerator.MAX_CANDIDATES
                    || learnedAvailable < 0 || learnedAvailable > target
                    || heuristicAvailable < 0 || heuristicAvailable > target
                    || learnedSelected < 0 || learnedSelected > learnedAvailable
                    || heuristicSelected < 0 || heuristicSelected > heuristicAvailable
                    || candidates.size() != learnedSelected + heuristicSelected
                    || candidates.size() > target || overlap < 0
                    || overlap > Math.min(learnedAvailable, heuristicAvailable)
                    || candidates.size() != Math.min(target, learnedAvailable + heuristicAvailable - overlap)
                    || (learnedSamples == null) != (learnedDuplicates == null)
                    || learnedSamples != null && (learnedDuplicates < 0 || learnedSamples != learnedAvailable + learnedDuplicates))
                throw new IllegalArgumentException("Invalid hybrid counts");
        }
        public int learnedQuota() { return (target + 1) / 2; }
        public int heuristicQuota() { return target / 2; }
    }

    @Override
    public List<ActionCandidate> generate(ActionGenerationRequest request, LegalOrderCatalog catalog) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(catalog, "catalog").requireMatches(request.state(), request.power());
        if (!(request.state() instanceof GameState_Movement))
            return List.copyOf(fallback.generate(request, catalog));
        return propose(request, catalog).candidates();
    }

    public Result propose(ActionGenerationRequest request, LegalOrderCatalog catalog) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(catalog, "catalog").requireMatches(request.state(), request.power());
        if (!(request.state() instanceof GameState_Movement movement))
            throw new IllegalArgumentException("Hybrid proposals require a movement state");
        return propose(request, catalog, new MovementEncoding(movement, request.power()));
    }

    public Result propose(ActionGenerationRequest request, MovementEncoding encoding) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(encoding, "encoding").catalog().requireMatches(request.state(), request.power());
        return propose(request, encoding.catalog(), encoding);
    }

    private Result propose(ActionGenerationRequest request, LegalOrderCatalog catalog, MovementEncoding encoding) {
        int target = 1, limit = Math.min(request.candidateLimit(), PolicyActionGenerator.MAX_CANDIDATES);
        for (var unit : catalog.unitLocations().keySet())
            target = (int) Math.min(limit, (long) target * catalog.optionsFor(unit).size());
        var childRequest = new ActionGenerationRequest(request.state(), request.power(), target, request.seed());
        var learnedPool = child(learned, childRequest, catalog, encoding);
        var first = learnedPool.candidates();
        var second = target == 1 && !first.isEmpty()
                ? List.<ActionCandidate>of() : child(heuristic, childRequest, catalog, encoding).candidates();
        Set<Set<OrderForm>> firstKeys = new HashSet<>();
        first.forEach(candidate -> firstKeys.add(Set.copyOf(candidate.orders())));
        int overlap = (int) second.stream().filter(candidate -> firstKeys.contains(Set.copyOf(candidate.orders()))).count();

        Set<Set<OrderForm>> seen = new HashSet<>();
        List<ActionCandidate> selected = new ArrayList<>();
        int learnedSelected = take(first, (target + 1) / 2, selected, seen);
        int heuristicSelected = take(second, target / 2, selected, seen);
        learnedSelected += take(first, target - selected.size(), selected, seen);
        heuristicSelected += take(second, target - selected.size(), selected, seen);

        var prepared = Objects.requireNonNull(anchor.prepare(encoding.input()), "prepared anchor");
        List<ActionCandidate> anchored = new ArrayList<>();
        for (ActionCandidate candidate : selected)
            anchored.add(new ActionCandidate(candidate.power(), candidate.orders(),
                    prepared.logProbability(encoding.encode(candidate.orders())), candidate.source()));
        return new Result(anchored, target, first.size(), second.size(), learnedSelected, heuristicSelected, overlap,
                learnedPool.samples(), learnedPool.duplicates());
    }

    private record Child(List<ActionCandidate> candidates, Long samples, Long duplicates) { }

    private static Child child(ActionGenerator generator, ActionGenerationRequest request,
                               LegalOrderCatalog catalog, MovementEncoding encoding) {
        PolicyActionGenerator.Result sampled = generator instanceof PolicyActionGenerator policy
                ? catalog == encoding.catalog() ? policy.propose(request, encoding) : policy.propose(request, catalog) : null;
        List<ActionCandidate> candidates = List.copyOf(sampled == null
                ? generator.generate(request, catalog) : sampled.candidates());
        if (candidates.size() > request.candidateLimit())
            throw new IllegalStateException("Hybrid child exceeded its candidate limit");
        CatalogActionValidator validator = new CatalogActionValidator();
        Set<Set<OrderForm>> seen = new HashSet<>();
        for (ActionCandidate candidate : candidates) {
            if (candidate.power() != request.power())
                throw new IllegalStateException("Hybrid child returned another nation's action");
            var errors = validator.validate(request.state(), candidate, catalog);
            if (!errors.isEmpty())
                throw new IllegalStateException("Hybrid child returned an invalid action: " + errors);
            if (!seen.add(Set.copyOf(candidate.orders())))
                throw new IllegalStateException("Hybrid child returned a duplicate action");
        }
        return new Child(candidates, sampled == null ? null : sampled.samples(), sampled == null ? null : sampled.duplicates());
    }

    private static int take(List<ActionCandidate> source, int count, List<ActionCandidate> selected,
                            Set<Set<OrderForm>> seen) {
        int added = 0;
        for (ActionCandidate candidate : source) {
            if (added == count) break;
            if (seen.add(Set.copyOf(candidate.orders()))) {
                selected.add(candidate);
                added++;
            }
        }
        return added;
    }
}