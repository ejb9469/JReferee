package agents.generation;

import contracts.OrderForm;
import game.GameState;
import game.GameState_Movement;

import java.util.*;
import java.util.function.Function;

/**
 * Applies a fixed movement scope before delegating candidate construction.
 *
 * <p>Generated actions retain their probabilities under the full-state anchor.
 * Filtering a catalog must not redefine that anchor as pools expand.</p>
 *
 * <p>Retreats and adjustments pass through unchanged. An outside-scope unit
 * in a later movement state is reported rather than removed from the board.</p>
 */
public class ScopedActionGenerator implements ActionGenerator {

    private final ActionGenerator generator;
    private final ProvinceScope scope;
    private final Function<GameState, ActionPrior> priors;

    /**
     * Uses the full-state convoy-free uniform anchor.
     */
    public ScopedActionGenerator(ActionGenerator generator, ProvinceScope scope) {
        this(generator, scope, ActionPrior::convoyFreeUniform);
    }

    /**
     * Binds an anchor for each movement state, including rollout continuations.
     * Supply a factory for a different anchor or a convoy-capable generator.
     */
    public ScopedActionGenerator(ActionGenerator generator, ProvinceScope scope,
                                 Function<GameState, ActionPrior> priors) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.priors = Objects.requireNonNull(priors, "priors");
    }

    @Override
    public List<ActionCandidate> generate(ActionGenerationRequest request, LegalOrderCatalog catalog) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(catalog, "catalog").requireMatches(request.state(), request.power());
        if (!(request.state() instanceof GameState_Movement))
            return List.copyOf(generator.generate(request, catalog));

        LegalOrderCatalog restricted = scope.restrict(request.state(), catalog);
        List<ActionCandidate> candidates = List.copyOf(generator.generate(request, restricted));
        if (candidates.size() > request.candidateLimit())
            throw new IllegalStateException("Scoped generator exceeded the candidate limit");
        ActionPrior prior = Objects.requireNonNull(priors.apply(request.state()), "state anchor");
        CatalogActionValidator validator = new CatalogActionValidator();
        Set<Set<OrderForm>> seen = new HashSet<>();
        List<ActionCandidate> result = new ArrayList<>();
        for (ActionCandidate candidate : candidates) {
            if (candidate.power() != request.power())
                throw new IllegalStateException("Scoped generator returned another nation's action");
            List<ActionValidationError> errors = validator.validate(request.state(), candidate, restricted);
            if (!errors.isEmpty())
                throw new IllegalStateException("Scoped generator returned an invalid action: " + errors);
            if (!seen.add(Set.copyOf(candidate.orders())))
                throw new IllegalStateException("Scoped generator returned a duplicate action");
            result.add(new ActionCandidate(candidate.power(), candidate.orders(),
                    prior.logProbability(candidate.power(), candidate.orders()), candidate.source()));
        }
        return List.copyOf(result);
    }

}