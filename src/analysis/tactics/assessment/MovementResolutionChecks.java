package analysis.tactics.assessment;

import analysis.tactics.TacticalContext;
import analysis.tactics.TacticalScenario;
import domain.Province;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.*;


/**
 * Validates the observable correspondence between a complete scenario
 * and a game-facing movement result.
 *
 * <p>This validates result structure and submitted identities. It does not
 * independently re-adjudicate the position or authenticate a resolver.</p>
 */
public final class MovementResolutionChecks {


    // Construction \\

    private MovementResolutionChecks() {  }


    // Validation \\

    public static void requireCorrespondence(
            TacticalScenario scenario,
            MovementResult result
    ) {

        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(result, "result");

        TacticalContext context = scenario.context();

        if (!context.complete())
            throw new IllegalArgumentException(
                    "Movement resolution requires a complete scenario");

        Map<UnitId, Province> initial = context.board().locations();

        require(
                result.outcomes().keySet().equals(initial.keySet()),
                "Outcome unit set differs from the scenario");

        Set<UnitId> represented = new HashSet<>(
                result.finalLocations().keySet());

        require(
                Collections.disjoint(
                        represented,
                        result.dislodgements().keySet()),
                "A unit cannot remain on the board and be dislodged");

        represented.addAll(result.dislodgements().keySet());

        require(
                represented.equals(initial.keySet()),
                "Final and dislodged unit sets do not partition the scenario");

        Map<UnitId, Province> expectedSuccessfulMoves =
                new LinkedHashMap<>();

        Set<Province> occupied = EnumSet.noneOf(Province.class);

        for (UnitId unit : initial.keySet()) {

            MovementResult.Outcome outcome = result.outcomes().get(unit);
            Order expected = context.orders().get(unit).order();

            require(outcome != null, "Missing outcome");
            require(unit.equals(outcome.unit()), "Outcome issuer differs");
            require(
                    expected.equals(outcome.submittedOrder()),
                    "Result changes a scenario submission");

            /*
             * Every scenario instruction is passed explicitly to the processor.
             * This flag concerns processor input, not historical provenance.
             * SUBMITTED/PLANNED/ASSUMED/DEFAULTED remains in TacticalContext.
             */
            require(
                    outcome.submitted(),
                    "Processor unexpectedly defaulted a scenario instruction");

            if (outcome.successfulMove())
                expectedSuccessfulMoves.put(unit, expected.target());

            if (result.dislodgements().containsKey(unit)) {

                MovementResult.Dislodgement dislodgement =
                        result.dislodgements().get(unit);

                require(
                        !outcome.successfulMove(),
                        "A successful mover cannot also be dislodged");

                require(
                        unit.equals(dislodgement.unit()),
                        "Dislodgement issuer differs");

                require(
                        initial.get(unit) == dislodgement.displacedFrom(),
                        "Dislodgement origin differs from the initial board");

                UnitId attacker = dislodgement.attacker();

                require(
                        initial.containsKey(attacker),
                        "Dislodger is absent from the scenario");

                require(
                        attacker.owner() != unit.owner(),
                        "Result reports self-dislodgement");

                require(
                        initial.get(attacker) == dislodgement.attackerOrigin(),
                        "Dislodger origin differs from the initial board");

                MovementResult.Outcome attack = result.outcomes().get(attacker);

                require(
                        attack != null && attack.successfulMove(),
                        "Dislodger has no successful move");

                require(
                        Province.canonical(attack.submittedOrder().target())
                                == Province.canonical(initial.get(unit)),
                        "Dislodger did not enter the displaced territory");

            } else {

                Province expectedLocation = outcome.successfulMove()
                        ? expected.target()
                        : initial.get(unit);

                Province actualLocation = result.finalLocations().get(unit);

                require(
                        actualLocation == expectedLocation,
                        "Final location does not match the submitted move outcome");

                require(
                        occupied.add(Province.canonical(actualLocation)),
                        "Multiple surviving units occupy one territory");

            }

        }

        require(
                expectedSuccessfulMoves.equals(
                        result.successfulMoveDestinations()),
                "Successful-move index differs from per-unit outcomes");

        for (Province standoff : result.standoffProvinces()) {

            require(standoff != null, "Null standoff territory");

            require(
                    !occupied.contains(Province.canonical(standoff)),
                    "Standoff territory is occupied after movement");

        }

    }

    private static void require(boolean condition, String message) {

        if (!condition)
            throw new IllegalStateException(
                    "Movement resolver contract violation: " + message);

    }


}