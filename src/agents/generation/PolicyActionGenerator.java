package agents.generation;

import agents.encoding.MovementEncoding;
import agents.encoding.MovementInput;
import agents.policy.MovementPolicy;
import contracts.OrderForm;
import game.GameState_Movement;

import java.util.*;

/**
 * Samples distinct complete movement actions from a proposal policy.
 * The independently supplied anchor always scores the full root catalog.
 * Retreats and adjustments use an explicit fallback generator.
 *
 * <p>No all-hold or greedy action is inserted. Samples retain their first
 * occurrence order. Duplicates can leave a pool short after the bounded draw
 * budget; no uniform replacement or reweighting is performed.</p>
 *
 * <p>A restricted catalog supplies the proposal's input masks. Anchor inputs
 * remain unrestricted, including convoy choices and exact Fleet coasts.
 * Zero anchor support is retained as negative infinity. Search requires at
 * least one positive-anchor action in each pool.</p>
 */
public final class PolicyActionGenerator implements ActionGenerator {

    public static final int MAX_CANDIDATES = 256;
    public static final int SAMPLES_PER_CANDIDATE = 32;

    private final MovementPolicy proposal, anchor;
    private final ActionGenerator fallback;
    private final int samplesPerCandidate;

    public PolicyActionGenerator(MovementPolicy proposal, MovementPolicy anchor) {
        this(proposal, anchor, new UniformActionGenerator(), SAMPLES_PER_CANDIDATE);
    }

    public PolicyActionGenerator(MovementPolicy proposal, MovementPolicy anchor,
                                 ActionGenerator fallback, int samplesPerCandidate) {
        this.proposal = Objects.requireNonNull(proposal, "proposal");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        if (samplesPerCandidate < 1)
            throw new IllegalArgumentException("Samples per candidate must be positive");
        this.samplesPerCandidate = samplesPerCandidate;
    }

    /**
     * logProposal is the action probability under the proposal's input masks,
     * before deduplication. It is not the probability of inclusion in the pool.
     * candidate.logPrior is the independent full-root anchor probability.
     */
    public record Proposal(ActionCandidate candidate, double logProposal) {
        public Proposal {
            Objects.requireNonNull(candidate, "candidate");
            if (!Double.isFinite(logProposal) || logProposal > 0)
                throw new IllegalArgumentException("A sampled proposal needs a finite nonpositive log probability");
        }
    }

    public record Result(List<Proposal> proposals, int target, long samples, long duplicates) {
        public Result {
            proposals = List.copyOf(proposals);
            if (target < 1 || target > MAX_CANDIDATES || proposals.isEmpty() || proposals.size() > target
                    || duplicates < 0 || samples != proposals.size() + duplicates)
                throw new IllegalArgumentException("Invalid proposal counts");
        }
        public List<ActionCandidate> candidates() {
            return proposals.stream().map(Proposal::candidate).toList();
        }
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
            throw new IllegalArgumentException("Policy proposals require a movement state");
        return propose(request, catalog, new MovementEncoding(movement, request.power()));
    }

    /**
     * Reuses an existing full-root encoding without rebuilding its legal catalog.
     */
    public Result propose(ActionGenerationRequest request, MovementEncoding encoding) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(encoding, "encoding").catalog().requireMatches(request.state(), request.power());
        return propose(request, encoding.catalog(), encoding);
    }

    private Result propose(ActionGenerationRequest request, LegalOrderCatalog catalog, MovementEncoding encoding) {
        MovementInput full = encoding.input();
        Masks masks = restrict(full, encoding, catalog);
        MovementPolicy.Prepared proposed = Objects.requireNonNull(proposal.prepare(masks.input()), "prepared proposal");
        MovementPolicy.Prepared anchored = proposal == anchor && masks.input() == full
                ? proposed : Objects.requireNonNull(anchor.prepare(full), "prepared anchor");
        int target = 1;
        int limit = Math.min(request.candidateLimit(), MAX_CANDIDATES);
        for (var slot : masks.input().slots())
            target = (int) Math.min(limit, (long) target * slot.options().size());
        List<Proposal> result = new ArrayList<>();
        Set<List<Integer>> seen = new HashSet<>();
        CatalogActionValidator validator = new CatalogActionValidator();
        Random random = new Random(request.seed());
        long samples = 0, duplicates = 0, budget = (long) target * samplesPerCandidate;
        while (samples < budget && result.size() < target) {
            List<Integer> action = List.copyOf(proposed.sample(random));
            masks.input().requireAction(action);
            samples++;
            if (!seen.add(action)) {
                duplicates++;
                continue;
            }
            List<Integer> fullAction = new ArrayList<>();
            for (int slot = 0; slot < action.size(); slot++)
                fullAction.add(masks.indices().get(slot).get(action.get(slot)));
            double logProposal = proposed.logProbability(action);
            double logPrior = anchored == proposed ? logProposal : anchored.logProbability(fullAction);
            ActionCandidate candidate = new ActionCandidate(request.power(), encoding.decode(fullAction),
                    logPrior, ActionCandidate.Source.POLICY_SAMPLE);
            List<ActionValidationError> errors = validator.validate(request.state(), candidate, catalog);
            if (!errors.isEmpty())
                throw new IllegalStateException("Policy produced an invalid complete action: " + errors);
            result.add(new Proposal(candidate, logProposal));
        }
        return new Result(result, target, samples, duplicates);
    }

    private record Masks(MovementInput input, List<List<Integer>> indices) { }

    private static Masks restrict(MovementInput full, MovementEncoding encoding, LegalOrderCatalog catalog) {
        List<MovementInput.Slot> slots = new ArrayList<>();
        List<List<Integer>> indices = new ArrayList<>();
        boolean changed = false;
        for (int slot = 0; slot < full.slots().size(); slot++) {
            var original = full.slots().get(slot);
            List<MovementInput.Option> retained = new ArrayList<>();
            List<Integer> mapping = new ArrayList<>();
            List<OrderForm> orders = encoding.options().get(slot);
            Set<OrderForm> allowed = new HashSet<>(catalog.optionsFor(
                    ((phase.Order) orders.getFirst()).unit()));
            for (int index = 0; index < orders.size(); index++)
                if (allowed.remove(orders.get(index))) {
                    mapping.add(index);
                    retained.add(original.options().get(index));
                }
            if (!allowed.isEmpty())
                throw new IllegalArgumentException("Supplied catalog contains options outside the full movement encoding");
            changed |= retained.size() != orders.size();
            slots.add(new MovementInput.Slot(original.location(), retained));
            indices.add(List.copyOf(mapping));
        }
        return new Masks(changed ? new MovementInput(full.moment(), full.power(), full.units(), full.centers(), slots) : full,
                List.copyOf(indices));
    }
}