package agents.generation;

import contracts.OrderForm;
import domain.Nation;
import game.GameState;
import game.GameState_Movement;

import java.util.*;

/**
 * Scores complete actions under one fixed root-state anchor.
 * Implementations must be deterministic and independent of search results.
 */
@FunctionalInterface
public interface ActionPrior {

    /**
     * Returns a natural-log probability, or negative infinity for zero support.
     * The same submitted action must keep this value as candidate pools expand.
     */
    double logProbability(Nation power, List<OrderForm> orders);

    /**
     * Binds the existing convoy-free, factorized uniform movement anchor.
     * Invalid or excluded orders are reported rather than silently assigned mass.
     */
    static ActionPrior convoyFreeUniform(GameState root) {
        Objects.requireNonNull(root, "root");
        if (!(root instanceof GameState_Movement movement))
            throw new IllegalArgumentException("This anchor requires a movement root");
        Map<Nation, LegalOrderCatalog> catalogs = new EnumMap<>(Nation.class);
        Map<Nation, Double> priors = new EnumMap<>(Nation.class);
        for (Nation nation : root.aliveNations()) {
            LegalOrderCatalog catalog = UniformActionGenerator.withoutConvoys(
                    movement, new LegalOrderGenerator().generate(root, nation));
            catalogs.put(nation, catalog);
            priors.put(nation, UniformActionGenerator.logPrior(catalog));
        }
        return (power, orders) -> {
            LegalOrderCatalog catalog = catalogs.get(Objects.requireNonNull(power, "power"));
            if (catalog == null)
                throw new IllegalArgumentException("Anchor has no living nation " + power);
            ActionCandidate candidate = new ActionCandidate(power, orders, 0.0, ActionCandidate.Source.HEURISTIC);
            List<ActionValidationError> errors = new CatalogActionValidator().validate(root, candidate, catalog);
            if (!errors.isEmpty())
                throw new IllegalArgumentException("Action is outside the anchor catalog: " + errors);
            return priors.get(power);
        };
    }
}
