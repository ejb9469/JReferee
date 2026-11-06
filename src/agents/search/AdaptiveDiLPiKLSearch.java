package agents.search;

import agents.generation.*;
import domain.Nation;
import game.*;

import java.util.*;

/**
 * Alternates fixed-pool DiL-piKL solves and bounded response discovery.
 *
 * <p>All nations discover against frozen population policies. Additions are
 * appended simultaneously. Each expanded game is solved from fresh Q values
 * before its policies can be returned. Incumbents and their priors are retained.</p>
 *
 * <p>Use the same evaluator and processor configuration for the solver and
 * response search. The caller supplies these collaborators. Expansion currently
 * applies to movement; other phases receive one ordinary fixed-pool solve.</p>
 *
 * <p>No stop reason proves equilibrium. Proposal coverage, continuation quality,
 * sampling error, and the configured budgets all limit the result.</p>
 */
public class AdaptiveDiLPiKLSearch {

    private final DiLPiKLSearch solver;
    private final ResponseSearch responses;
    private final int poolLimit;
    private final int expansionRounds;
    private final long seed;

    public AdaptiveDiLPiKLSearch(DiLPiKLSearch solver, ResponseSearch responses,
                                 int poolLimit, int expansionRounds, long seed) {
        this.solver = Objects.requireNonNull(solver, "solver");
        this.responses = Objects.requireNonNull(responses, "responses");
        if (poolLimit < 1 || expansionRounds < 0)
            throw new IllegalArgumentException("Invalid adaptive-search budgets");
        this.poolLimit = poolLimit;
        this.expansionRounds = expansionRounds;
        this.seed = seed;
    }

    public enum StopReason { ROUND_LIMIT, POOL_LIMIT, NO_RESPONSE_FOUND, NON_MOVEMENT }

    /**
     * Includes the initial solve and every completed re-solve.
     * Each entry retains its own immutable candidate pools and policies.
     */
    public record Result(Nation power, List<JointSearchResult> solves, StopReason reason,
                         List<Expansion> expansions) {
        public Result(Nation power, List<JointSearchResult> solves, StopReason reason) {
            this(power, solves, reason, List.of());
        }

        public Result {
            Objects.requireNonNull(power, "power");
            Objects.requireNonNull(reason, "reason");
            solves = List.copyOf(solves);
            expansions = List.copyOf(expansions);
            if (solves.isEmpty() || solves.stream().anyMatch(solve -> !solve.results().containsKey(power)))
                throw new IllegalArgumentException("Every solve must include the requested nation");
            for (Expansion expansion : expansions)
                if (expansion.solveIndex() >= solves.size())
                    throw new IllegalArgumentException("Expansion names a missing solve");
        }

        public JointSearchResult joint() {
            return solves.getLast();
        }

        public SearchResult result() {
            return joint().results().get(power);
        }
    }

    /**
     * Discovery against one completed solve, including pool-cap rejections.
     * A final unsuccessful discovery has no following solve.
     */
    public record Expansion(int solveIndex, ResponseSearch.Report report,
                            Map<Nation, List<ActionCandidate>> blocked) {
        public Expansion {
            if (solveIndex < 0)
                throw new IllegalArgumentException("Negative solve index");
            Objects.requireNonNull(report, "report");
            Map<Nation, List<ActionCandidate>> copy = new EnumMap<>(Nation.class);
            blocked.forEach((nation, candidates) -> copy.put(nation, List.copyOf(candidates)));
            blocked = Collections.unmodifiableMap(copy);
        }
    }

    /**
     * Uses plausible initial proposals and their existing convoy-free anchor.
     * Custom anchors and initial pools can use the explicit overload.
     */
    public Result search(Game game, Nation power, int initialLimit) {
        if (initialLimit < 1 || initialLimit > poolLimit)
            throw new IllegalArgumentException("Initial limit must fit the pool limit");
        GameState root = SearchSupport.root(game, power);
        Map<Nation, List<ActionCandidate>> pools = SearchSupport.generatePools(
                root, new PlausibleActionGenerator(), initialLimit, new Random(seed));
        if (!(root instanceof GameState_Movement))
            return new Result(power, List.of(solver.solve(game, pools)), StopReason.NON_MOVEMENT);
        return search(game, power, pools, ActionPrior.convoyFreeUniform(root));
    }

    /**
     * Expands explicit movement pools under an authoritative fixed root prior.
     * Existing candidate priors must already match that anchor.
     */
    public Result search(Game game, Nation power,
                         Map<Nation, List<ActionCandidate>> initial, ActionPrior prior) {
        return run(game, power, initial, prior, null);
    }

    /**
     * Shares evaluation and diagnostics across every solve and discovery.
     * Solver and response search must use the helper's evaluator instance.
     */
    public Result search(Game game, Nation power, Map<Nation, List<ActionCandidate>> initial,
                         ActionPrior prior, JointActionEvaluator trials) {
        return run(game, power, initial, prior, Objects.requireNonNull(trials, "trials"));
    }

    private Result run(Game game, Nation power, Map<Nation, List<ActionCandidate>> initial,
                       ActionPrior prior, JointActionEvaluator trials) {
        GameState root = SearchSupport.root(game, power);
        Map<Nation, List<ActionCandidate>> pools = SearchSupport.pools(root, initial);
        SearchSupport.requireAnchor(pools, prior);
        if (pools.values().stream().anyMatch(pool -> pool.size() > poolLimit))
            throw new IllegalArgumentException("Initial pool exceeds the pool limit");
        List<JointSearchResult> history = new ArrayList<>();
        List<Expansion> expansions = new ArrayList<>();
        JointSearchResult solved = solve(game, pools, trials);
        history.add(solved);
        if (!(root instanceof GameState_Movement))
            return new Result(power, history, StopReason.NON_MOVEMENT);
        Random random = new Random(seed);
        for (int round = 0; round < expansionRounds; round++) {
            if (pools.values().stream().allMatch(pool -> pool.size() == poolLimit))
                return new Result(power, history, StopReason.POOL_LIMIT, expansions);
            ResponseSearch.Report report = responses.discover(game, solved, prior, random.nextLong(),
                    trials == null ? responses.trials(game) : trials);
            Map<Nation, List<ResponseSearch.Response>> found = report.responses();
            Map<Nation, List<ActionCandidate>> expanded = new EnumMap<>(Nation.class);
            Map<Nation, List<ActionCandidate>> rejected = new EnumMap<>(Nation.class);
            boolean changed = false;
            boolean blocked = false;
            for (Nation nation : pools.keySet()) {
                List<ActionCandidate> candidates = new ArrayList<>(pools.get(nation));
                List<ActionCandidate> blockedCandidates = new ArrayList<>();
                for (ResponseSearch.Response response : found.get(nation)) {
                    if (candidates.size() == poolLimit) {
                        blocked = true;
                        blockedCandidates.add(response.candidate());
                        continue;
                    }
                    candidates.add(response.candidate());
                    changed = true;
                }
                expanded.put(nation, List.copyOf(candidates));
                rejected.put(nation, List.copyOf(blockedCandidates));
            }
            expansions.add(new Expansion(history.size() - 1, report, rejected));
            if (!changed)
                return new Result(power, history,
                        blocked ? StopReason.POOL_LIMIT : StopReason.NO_RESPONSE_FOUND, expansions);

            // No policy is exposed for the expanded pools until this solve completes.
            pools = Collections.unmodifiableMap(expanded);
            solved = solve(game, pools, trials);
            history.add(solved);
        }
        return new Result(power, history, StopReason.ROUND_LIMIT, expansions);
    }

    private JointSearchResult solve(Game game, Map<Nation, List<ActionCandidate>> pools,
                                    JointActionEvaluator trials) {
        return trials == null ? solver.solve(game, pools) : solver.solve(game, pools, trials);
    }
}