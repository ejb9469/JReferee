package analysis.tactics.outcome;

import analysis.tactics.TacticAssessor;
import analysis.tactics.TacticalContext;
import analysis.tactics.TacticalScenario;
import domain.OrderType;
import domain.Province;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.*;


/**
 * Complete movement scenario and its corresponding local resolution.
 *
 * <p>Construction resolves the exact supplied scenario through an identified
 * resolver. No caller-owned Game is advanced by this class.</p>
 *
 * <p>The resolver must honor its interface contract. Validation checks
 * observable correspondence, not independent adjudication correctness or
 * authenticity of the resolver's advertised configuration.</p>
 *
 * <p>Planned and assumed instructions retain their provenance in the scenario.
 * Outcomes are facts about that scenario, not necessarily historical events.</p>
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

        validateResult(scenario, result);

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

    /**
     * Uses the initial board, so dislodged units remain addressable.
     */
    public Province initialLocationOf(UnitId unit) {

        Objects.requireNonNull(unit, "unit");

        Province location = scenario.context().board().locationOf(unit);

        if (location == null)
            throw new IllegalArgumentException(
                    "Unit is absent from the initial scenario");

        return location;

    }


    // Result correspondence \\

    private static void validateResult(
            TacticalScenario scenario,
            MovementResult result
    ) {

        TacticalContext context = scenario.context();
        Map<UnitId, Province> initial = context.board().locations();

        require(
                result.outcomes().keySet().equals(initial.keySet()),
                "Outcome units differ from the scenario");

        Set<UnitId> represented = new HashSet<>(
                result.finalLocations().keySet());

        require(
                Collections.disjoint(
                        represented, result.dislodgements().keySet()),
                "A unit is both surviving and dislodged");

        represented.addAll(result.dislodgements().keySet());

        require(
                represented.equals(initial.keySet()),
                "Final and dislodged units do not partition the initial units");

        Map<UnitId, Province> successfulMoves = new LinkedHashMap<>();
        Set<Province> occupied = EnumSet.noneOf(Province.class);

        for (UnitId unit : initial.keySet()) {

            TacticalContext.KnownOrder known = context.orders().get(unit);
            MovementResult.Outcome outcome = result.outcomes().get(unit);

            require(outcome != null, "Missing outcome");
            require(unit.equals(outcome.unit()), "Outcome issuer differs");
            require(
                    known.order().equals(outcome.submittedOrder()),
                    "Resolution changes a scenario instruction");

            /*
             * A processor-defaulted HOLD is acceptable only if the scenario
             * explicitly identified it as a confirmed default.
             *
             * Conversely, an explicit instruction passed to a processor can
             * have submitted=true even if its scenario provenance is ASSUMED,
             * PLANNED, or DEFAULTED. Do not overwrite that provenance.
             */
            require(
                    outcome.submitted()
                            || (known.provenance()
                            == TacticalContext.Provenance.DEFAULTED
                            && known.order().orderType() == OrderType.HOLD),
                    "Resolution silently defaulted a known instruction");

            if (outcome.successfulMove())
                successfulMoves.put(unit, known.order().target());

            MovementResult.Dislodgement dislodgement =
                    result.dislodgementOf(unit);

            if (dislodgement != null) {

                require(
                        unit.equals(dislodgement.unit()),
                        "Dislodgement identity differs");

                require(
                        !outcome.successfulMove(),
                        "Successful mover is also dislodged");

                require(
                        initial.get(unit) == dislodgement.displacedFrom(),
                        "Displaced position differs from initial position");

                UnitId attacker = dislodgement.attacker();

                require(
                        initial.containsKey(attacker),
                        "Dislodger is absent from the scenario");

                require(
                        attacker.owner() != unit.owner(),
                        "Resolution reports self-dislodgement");

                require(
                        initial.get(attacker) == dislodgement.attackerOrigin(),
                        "Dislodger origin differs from initial position");

                MovementResult.Outcome attack = result.outcomes().get(attacker);

                require(
                        attack != null && attack.successfulMove(),
                        "Dislodger has no successful move");

                require(
                        Province.equalsIgnoreCoast(
                                attack.submittedOrder().target(),
                                dislodgement.displacedFrom()),
                        "Dislodger did not enter the displaced territory");

            } else {

                Province expected = outcome.successfulMove()
                        ? known.order().target()
                        : initial.get(unit);

                Province actual = result.finalLocations().get(unit);

                require(actual == expected, "Unexpected final location");

                require(
                        occupied.add(Province.canonical(actual)),
                        "Multiple surviving units occupy one territory");

            }

        }

        require(
                successfulMoves.equals(result.successfulMoveDestinations()),
                "Successful-move index differs from per-unit outcomes");

        for (Province standoff : result.standoffProvinces()) {

            require(standoff != null, "Null standoff");

            require(
                    !occupied.contains(Province.canonical(standoff)),
                    "Standoff is occupied after movement");

        }

    }

    private static void require(boolean condition, String message) {

        if (!condition)
            throw new IllegalStateException(
                    "Movement result contract violation: " + message);

    }


}