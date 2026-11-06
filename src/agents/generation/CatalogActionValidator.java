package agents.generation;

import contracts.OrderForm;
import game.GameState;
import phase.UnitId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static agents.generation.ActionValidationError.Code.*;

/**
 * Checks movement and retreat actions against their per-unit catalog.
 *
 * <p>Support matching, convoy availability, and tactical conflicts are left to
 * coordination analysis and adjudication. Foreign orders are not required in
 * the candidate.</p>
 */
public final class CatalogActionValidator implements ActionValidator {

    /**
     * Generates the catalog before checking the candidate.
     */
    @Override
    public List<ActionValidationError> validate(
            GameState state,
            ActionCandidate candidate
    ) {
        Objects.requireNonNull(candidate, "candidate");

        return validate(
                state,
                candidate,
                new LegalOrderGenerator().generate(state, candidate.power()));
    }

    /**
     * Reuses a catalog when checking several candidates for the same state.
     * A state or power mismatch throws IllegalArgumentException.
     */
    public List<ActionValidationError> validate(
            GameState state,
            ActionCandidate candidate,
            LegalOrderCatalog catalog
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(catalog, "catalog");
        catalog.requireMatches(state, candidate.power());

        List<ActionValidationError> errors = new ArrayList<>();
        Set<UnitId> submittedUnits = new HashSet<>();

        for (int index = 0; index < candidate.orders().size(); index++) {
            OrderForm form = candidate.orders().get(index);
            UnitId unit = LegalOrderCatalog.unitOf(form);
            if (!catalog.acceptsPhase(form)) {
                errors.add(new ActionValidationError(
                        WRONG_ORDER_PHASE, unit, index));
                continue;
            }

            if (!catalog.optionsByUnit().containsKey(unit)) {
                errors.add(new ActionValidationError(
                        UNKNOWN_UNIT, unit, index));
                continue;
            }

            if (!submittedUnits.add(unit))
                errors.add(new ActionValidationError(
                        DUPLICATE_UNIT, unit, index));

            if (!catalog.contains(form))
                errors.add(new ActionValidationError(
                        ORDER_NOT_IN_CATALOG, unit, index));
        }

        if (!catalog.allowsOmission())
            for (UnitId unit : catalog.optionsByUnit().keySet())
                if (!submittedUnits.contains(unit))
                    errors.add(new ActionValidationError(
                            MISSING_ORDER, unit, -1));

        return List.copyOf(errors);
    }

}