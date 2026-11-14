package agents.generation;

import agents.encoding.MovementEncoding;
import agents.policy.MovementPolicy;
import game.GameState_Movement;
import phase.Order;
import phase.UnitId;

import java.util.*;

/**
 * Policy-guided bounded completion with own-order and convoy constraints.
 * Original full-catalog probabilities remain the scoring reference.
 * Construction does not have the raw policy's sampling distribution.
 */
public final class CoherentPolicyActionGenerator implements ActionGenerator {

    public static final int ATTEMPTS_PER_CANDIDATE = 8;
    public static final int CHOICES_PER_UNIT = 32;
    private final MovementPolicy proposal, anchor;

    public CoherentPolicyActionGenerator(MovementPolicy proposal, MovementPolicy anchor) {
        this.proposal = Objects.requireNonNull(proposal);
        this.anchor = Objects.requireNonNull(anchor);
    }

    public record Result(List<ActionCandidate> candidates, int target, int attempts,
                         int deadEnds, int duplicates, long choices) {
        public Result {
            candidates = List.copyOf(candidates);
            if (target < 1 || target > PolicyActionGenerator.MAX_CANDIDATES || candidates.size() > target
                    || attempts < 0 || attempts > target * ATTEMPTS_PER_CANDIDATE || deadEnds < 0 || duplicates < 0
                    || attempts != candidates.size() + deadEnds + duplicates || choices < 0)
                throw new IllegalArgumentException("Invalid coherent construction counts");
        }
    }

    @Override public List<ActionCandidate> generate(ActionGenerationRequest request, LegalOrderCatalog catalog) {
        return propose(request, catalog).candidates();
    }

    public Result propose(ActionGenerationRequest request, LegalOrderCatalog catalog) {
        catalog.requireMatches(request.state(), request.power());
        if (!(request.state() instanceof GameState_Movement root))
            throw new IllegalArgumentException("Coherent policy generation requires movement");
        return propose(request, catalog, new MovementEncoding(root, request.power()));
    }

    public Result propose(ActionGenerationRequest request, MovementEncoding encoding) {
        encoding.catalog().requireMatches(request.state(), request.power());
        return propose(request, encoding.catalog(), encoding);
    }

    private Result propose(ActionGenerationRequest request, LegalOrderCatalog catalog, MovementEncoding encoding) {
        GameState_Movement root = (GameState_Movement) request.state();
        var feasibility = new MovementFeasibility(root, catalog);
        var input = encoding.input();
        var prepared = Objects.requireNonNull(proposal.prepare(input));
        var anchored = proposal == anchor ? prepared : Objects.requireNonNull(anchor.prepare(input));
        List<List<Integer>> allowed = new ArrayList<>();
        int target = 1, limit = Math.min(request.candidateLimit(), PolicyActionGenerator.MAX_CANDIDATES);
        for (var slot : encoding.options()) {
            Set<?> retained = new HashSet<>(catalog.optionsFor(((Order) slot.getFirst()).unit()));
            List<Integer> indices = new ArrayList<>();
            for (int index = 0; index < slot.size(); index++) if (retained.remove(slot.get(index))) indices.add(index);
            if (!retained.isEmpty()) throw new IllegalArgumentException("Catalog exceeds the full movement masks");
            allowed.add(List.copyOf(indices));
            target = (int) Math.min(limit, (long) target * indices.size());
        }
        List<ActionCandidate> result = new ArrayList<>();
        Set<List<Integer>> seen = new HashSet<>();
        Random random = new Random(request.seed());
        int attempts = 0, deadEnds = 0, duplicates = 0;
        long choices = 0;
        int budget = CHOICES_PER_UNIT * Math.max(1, allowed.size());
        while (result.size() < target && attempts < target * ATTEMPTS_PER_CANDIDATE) {
            attempts++;
            int[] remaining = {budget};
            List<Integer> action = new ArrayList<>();
            boolean complete = complete(encoding, allowed, prepared, feasibility, random,
                    action, new LinkedHashMap<>(), remaining);
            choices += budget - remaining[0];
            if (!complete) { deadEnds++; continue; }
            if (!seen.add(List.copyOf(action))) { duplicates++; continue; }
            var candidate = new ActionCandidate(request.power(), encoding.decode(action),
                    anchored.logProbability(action), ActionCandidate.Source.POLICY);
            if (feasibility.inspect(candidate).stream().anyMatch(f -> f.kind() == MovementFeasibility.Kind.BLOCKER))
                throw new IllegalStateException("Coherent completion left an inconsistent action");
            result.add(candidate);
        }
        return new Result(result, target, attempts, deadEnds, duplicates, choices);
    }

    private record Choice(int index, double rank) { }

    private static boolean complete(MovementEncoding encoding, List<List<Integer>> allowed,
                                    MovementPolicy.Prepared policy, MovementFeasibility feasibility, Random random,
                                    List<Integer> prefix, Map<UnitId, Order> assigned, int[] remaining) {
        if (prefix.size() == allowed.size()) return true;
        if (remaining[0] == 0) return false;
        int slot = prefix.size();
        double[] logp = policy.logProbabilities(List.copyOf(prefix));
        MovementPolicy.requireDistribution(logp, encoding.options().get(slot).size());
        List<Choice> choices = new ArrayList<>();
        for (int index : allowed.get(slot))
            if (logp[index] != Double.NEGATIVE_INFINITY)
                choices.add(new Choice(index, logp[index] - Math.log(-Math.log(1 - random.nextDouble()))));
        choices.sort(Comparator.comparingDouble(Choice::rank).reversed().thenComparingInt(Choice::index));
        for (Choice choice : choices) {
            if (remaining[0] == 0) break;
            remaining[0]--;
            Order order = (Order) encoding.options().get(slot).get(choice.index());
            assigned.put(order.unit(), order);
            prefix.add(choice.index());
            if (feasibility.compatible(assigned) && complete(encoding, allowed, policy, feasibility,
                    random, prefix, assigned, remaining))
                return true;
            prefix.removeLast();
            assigned.remove(order.unit());
        }
        return false;
    }
}