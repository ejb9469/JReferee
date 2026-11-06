package agents.search;

import agents.evaluation.Evaluator;
import agents.generation.*;
import domain.Nation;
import game.Game;
import game.GameState;

import java.util.*;

/**
 * Root preparation and payoff checks shared by the search implementations.
 */
final class SearchSupport {

    private SearchSupport() { }

    static GameState root(Game game, Nation power) {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(power, "power");
        if (game.getSnapshot() != null)
            throw new IllegalStateException("Search requires a game without a saved checkpoint");
        GameState root = game.snapshot();
        if (!root.aliveNations().contains(power))
            throw new IllegalArgumentException("Cannot search for an eliminated nation: " + power);
        return root;
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