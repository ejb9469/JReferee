package analysis.tactics.outcome;

import analysis.tactics.MovementResultValidation;
import analysis.tactics.TacticAssessor;
import analysis.tactics.TacticalScenario;
import domain.Province;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.Objects;


/**
 * A complete movement scenario and its corresponding identified resolution.
 *
 * <p>Planned, assumed, and defaulted evidence retains its provenance.
 * Outcomes describe the supplied scenario, not necessarily historical events.</p>
 */
public final class ResolvedMovementContext {


    // Core state \\

    private final TacticalScenario scenario;
    private final String rulesetId;
    private final MovementResult result;


    // Construction \\

    private ResolvedMovementContext(
            TacticalScenario scenario,
            String rulesetId,
            MovementResult result
    ) {

        MovementResultValidation.requireCorrespondence(
                scenario,
                result,
                MovementResultValidation.SubmissionPolicy.ALLOW_CONFIRMED_DEFAULTS);

        this.scenario = scenario;
        this.rulesetId = rulesetId;
        this.result = result;

    }

    public static ResolvedMovementContext resolve(
            TacticalScenario scenario,
            TacticAssessor.MovementResolver resolver
    ) {

        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(resolver, "resolver");

        String rulesetId = Objects.requireNonNull(
                resolver.rulesetId(), "resolver ruleset");

        if (rulesetId.isBlank()
                || !rulesetId.equals(scenario.context().rulesetId()))
            throw new IllegalArgumentException(
                    "Resolver configuration differs from the scenario");

        if (!scenario.context().complete())
            throw new IllegalArgumentException(
                    "Resolved investigation requires a complete scenario");

        MovementResult result = Objects.requireNonNull(
                resolver.resolve(scenario), "resolver result");

        if (!rulesetId.equals(resolver.rulesetId()))
            throw new IllegalStateException(
                    "Resolver identity changed during resolution");

        return new ResolvedMovementContext(scenario, rulesetId, result);

    }


    // Evidence access \\

    public TacticalScenario scenario() {
        return scenario;
    }

    public String rulesetId() {
        return rulesetId;
    }

    public MovementResult result() {
        return result;
    }

    public Province initialLocationOf(UnitId unit) {

        Objects.requireNonNull(unit, "unit");

        Province location =
                scenario.context().board().locations().get(unit);

        if (location == null)
            throw new IllegalArgumentException(
                    "Unit is absent from the initial scenario");

        return location;

    }


}