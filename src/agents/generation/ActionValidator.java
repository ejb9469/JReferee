package agents.generation;

import game.GameState;

import java.util.List;

/**
 * Checks a complete action against the state in which it will be submitted.
 */
public sealed interface ActionValidator
        permits CatalogActionValidator {

    // this class is necessary to prune edge-case / invalid moves after generation, and before later scoring

    // for batch validation, reuse the catalog:
        // List<ActionValidationError> result = new MovementActionValidator().validate(state, candidate, catalog)

    // we could bundle `List<ActionValidationError>` into its own class, ...
    // ... but it is better to keep the validator stateless (i.e. safely reusable)

    /**
     * Returns submission errors for the candidate's nation.
     * An unsupported state is a caller error and throws `IllegalArgumentException`.
     */
    List<ActionValidationError> validate(GameState state,
                                         ActionCandidate candidate);

}