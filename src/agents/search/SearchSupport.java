package agents.search;

import agents.evaluation.Evaluator;
import agents.generation.*;
import contracts.OrderForm;
import domain.Nation;
import game.Game;
import game.GameState;

import java.util.*;

/**
 * Root preparation and payoff checks shared by the search implementations.
 * Static-only, package-private utility class.
 */
final class SearchSupport {

    private SearchSupport() { }

    static GameState root(Game game) {
        Objects.requireNonNull(game, "game");
        if (game.getSnapshot() != null)
            throw new IllegalStateException("Search requires a game without a saved checkpoint");
        return game.snapshot();
    }

    static GameState root(Game game, Nation power) {
        Objects.requireNonNull(power, "power");
        GameState root = root(game);
        if (!root.aliveNations().contains(power))
            throw new IllegalArgumentException("Cannot search for an eliminated nation: " + power);
        return root;
    }

    /**
     * Copies explicit pools, preserving candidate order and original priors.
     * Submission equality ignores list order and construction metadata.
     */
    static Map<Nation, List<ActionCandidate>> pools(
            GameState root, Map<Nation, List<ActionCandidate>> supplied
    ) {
        Objects.requireNonNull(supplied, "pools");
        if (supplied.isEmpty() || !supplied.keySet().equals(root.aliveNations()))
            throw new IllegalArgumentException("Pools must cover exactly the living nations");
        Map<Nation, List<ActionCandidate>> result = new EnumMap<>(Nation.class);
        CatalogActionValidator validator = new CatalogActionValidator();
        LegalOrderGenerator legal = new LegalOrderGenerator();
        for (Nation nation : root.aliveNations()) {
            List<ActionCandidate> candidates = List.copyOf(supplied.get(nation));
            if (candidates.isEmpty())
                throw new IllegalArgumentException("Empty pool for " + nation);
            LegalOrderCatalog catalog = legal.generate(root, nation);
            Set<Map<OrderForm, Integer>> seen = new HashSet<>();
            for (ActionCandidate candidate : candidates) {
                if (candidate.power() != nation)
                    throw new IllegalArgumentException("Candidate belongs to another nation");
                List<ActionValidationError> errors = validator.validate(root, candidate, catalog);
                if (!errors.isEmpty())
                    throw new IllegalArgumentException("Invalid candidate: " + errors);
                if (!seen.add(key(candidate)))
                    throw new IllegalArgumentException("Duplicate action for " + nation);
            }
            if (candidates.stream().noneMatch(candidate -> Double.isFinite(candidate.logPrior())))
                throw new IllegalStateException("No candidate has positive prior for " + nation);
            result.put(nation, candidates);
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<OrderForm, Integer> key(ActionCandidate candidate) {
        Map<OrderForm, Integer> counts = new HashMap<>();
        candidate.orders().forEach(order -> counts.merge(order, 1, Integer::sum));
        return Map.copyOf(counts);
    }

    static ActionCandidate anchored(ActionCandidate candidate, ActionPrior prior) {
        return new ActionCandidate(candidate.power(), candidate.orders(),
                prior.logProbability(candidate.power(), candidate.orders()), candidate.source());
    }

    static void requireAnchor(Map<Nation, List<ActionCandidate>> pools, ActionPrior prior) {
        Objects.requireNonNull(prior, "prior");
        for (List<ActionCandidate> candidates : pools.values())
            for (ActionCandidate candidate : candidates)
                if (anchored(candidate, prior).logPrior() != candidate.logPrior())
                    throw new IllegalArgumentException("Candidate prior differs from the fixed root anchor");
    }

    static Map<Nation, List<ActionCandidate>> generatePools(
            GameState state, ActionGenerator generator, int candidateLimit, Random random
    ) {
        LegalOrderGenerator legalOrders = new LegalOrderGenerator();
        CatalogActionValidator validator = new CatalogActionValidator();
        Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
        for (Nation nation : state.aliveNations()) {
            LegalOrderCatalog catalog = legalOrders.generate(state, nation);
            List<ActionCandidate> candidates = List.copyOf(generator.generate(
                    new ActionGenerationRequest(state, nation, candidateLimit, random.nextLong()), catalog));
            if (candidates.isEmpty() || candidates.size() > candidateLimit)
                throw new IllegalStateException("Invalid candidate count for " + nation + ": " + candidates.size());
            for (ActionCandidate candidate : candidates) {
                if (candidate.power() != nation)
                    throw new IllegalStateException("Generator returned an action for another nation");
                List<ActionValidationError> errors = validator.validate(state, candidate, catalog);
                if (!errors.isEmpty())
                    throw new IllegalStateException("Generator returned an invalid action for " + nation + ": " + errors);
            }
            pools.put(nation, candidates);
        }
        return Collections.unmodifiableMap(pools);
    }

    static Map<Nation, Double> values(Evaluator evaluator, GameState state) {
        Map<Nation, Double> values = Map.copyOf(
                Objects.requireNonNull(evaluator.evaluate(state), "evaluator result"));
        if (!values.keySet().equals(EnumSet.allOf(Nation.class)))
            throw new IllegalStateException("Evaluator must score every nation");
        for (double value : values.values())
            if (!Double.isFinite(value))
                throw new IllegalStateException("Evaluator returned a non-finite score");
        return values;
    }

}