package analysis.tactics;

import domain.OrderType;
import domain.Province;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.*;


/**
 * Observable correspondence between a complete scenario and its result.
 *
 * <p>This validates identities and result consistency, not independent
 * adjudication correctness or the authenticity of resolver provenance.</p>
 */
public final class MovementResultValidation {


    // Input policy \\

    public enum SubmissionPolicy {

        /** Every instruction must have been passed explicitly to the processor. */
        EXPLICIT_ONLY,

        /** A processor default must correspond to an explicitly recorded default. */
        ALLOW_CONFIRMED_DEFAULTS

    }


    // Construction \\

    private MovementResultValidation() {  }


    // Validation \\

    public static void requireCorrespondence(
            TacticalScenario scenario,
            MovementResult result,
            SubmissionPolicy policy
    ) {

        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(policy, "policy");

        TacticalContext context = scenario.context();

        if (!context.complete())
            throw new IllegalArgumentException(
                    "Result validation requires a complete scenario");

        Map<UnitId, Province> initial = context.board().locations();

        require(
                result.outcomes().keySet().equals(initial.keySet()),
                "Outcome units differ from the scenario");

        Set<UnitId> represented =
                new HashSet<>(result.finalLocations().keySet());

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

            boolean confirmedDefault =
                    policy == SubmissionPolicy.ALLOW_CONFIRMED_DEFAULTS
                            && known.provenance()
                            == TacticalContext.Provenance.DEFAULTED
                            && known.order().orderType() == OrderType.HOLD;

            require(
                    outcome.submitted() || confirmedDefault,
                    "Resolution unexpectedly defaulted an instruction");

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
                        "Displaced position differs from the initial position");

                UnitId attacker = dislodgement.attacker();

                require(
                        initial.containsKey(attacker),
                        "Dislodger is absent from the scenario");

                require(
                        attacker.owner() != unit.owner(),
                        "Resolution reports self-dislodgement");

                require(
                        initial.get(attacker) == dislodgement.attackerOrigin(),
                        "Dislodger origin differs from the initial position");

                MovementResult.Outcome attack =
                        result.outcomes().get(attacker);

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