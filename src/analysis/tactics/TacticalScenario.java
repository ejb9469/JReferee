package analysis.tactics;

import phase.UnitId;

import java.util.Map;
import java.util.Objects;


/**
 * Complete movement scenario for tactical assessment.<br><br>
 *
 * The caller supplies an ID which must distinguish this scenario within
 * its assessment or report. The ID is not a content hash.
 */
public record TacticalScenario(
        String id,
        TacticalContext context
) {


    // Construction and validation \\

    public TacticalScenario {

        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(context, "context");

        if (id.isBlank())
            throw new IllegalArgumentException(
                    "Scenario ID must not be blank");

        if (!context.complete())
            throw new IllegalArgumentException(
                    "Complete scenario required; unknown units: "
                            + context.unknownUnits());

    }


    // Baseline compatibility \\

    /**
     * Requires this scenario to extend the supplied context without
     * replacing any known order evidence.<br><br>
     *
     * The starting board, moment, and ruleset must remain unchanged.
     * Existing orders must retain both their values and their provenance.
     *
     * <p>Counterfactual scenarios which deliberately replace orders are
     * not baseline extensions and must not be validated with this method.</p>
     */
    public void requireExtensionOf(TacticalContext original) {

        Objects.requireNonNull(original, "original");

        if (!context.rulesetId().equals(original.rulesetId())
                || !context.moment().equals(original.moment())
                || !context.board().equals(original.board()))
            throw new IllegalArgumentException(
                    "Scenario changes the ruleset, moment, or starting board");

        for (Map.Entry<UnitId, TacticalContext.KnownOrder> entry
                : original.orders().entrySet()) {

            TacticalContext.KnownOrder current =
                    context.orders().get(entry.getKey());

            if (!entry.getValue().equals(current))
                throw new IllegalArgumentException(
                        "Scenario changes known order evidence for "
                                + entry.getKey());

        }

    }


}