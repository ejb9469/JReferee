package agents.generation;

import java.util.List;

/**
 * Generates complete action candidates from legal order options.
 */
public interface ActionGenerator {

    // Usage:
    //  LegalOrderCatalog catalog = new LegalOrderGenerator().generate(state, power);
    //  List<ActionCandidate> candidates = new UniformActionGenerator().generate(new ActionGenerationRequest(state, power, limit, seed), catalog);

    /**
     * Generates an immutable list of at most candidateLimit distinct actions
     * using a catalog for the same state and nation. Implementations may return
     * fewer candidates when the action space or sampling budget is exhausted.<br><br>
     *
     * <b>Implementations should call
     * {@link LegalOrderCatalog#requireMatches(game.GameState, domain.Nation)}
     * <i>before</i> using the catalog.</b>
     */
    List<ActionCandidate> generate(ActionGenerationRequest request,
                                   LegalOrderCatalog catalog);

}