package agents.generation;

import java.util.List;

/**
 * Generates complete action candidates from legal order options.
 */
public interface ActionGenerator {

    /**
     * Generates candidates for the request using a catalog for the same state
     * and nation.<br><br>
     *
     * <b>Implementations should call
     * {@link LegalOrderCatalog#requireMatches(game.GameState, domain.Nation)}
     * <i>before</i> using the catalog.</b>
     */
    List<ActionCandidate> generate(ActionGenerationRequest request,
                                   LegalOrderCatalog catalog);

}