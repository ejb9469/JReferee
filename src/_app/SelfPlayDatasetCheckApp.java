package _app;

import game.GameState_Retreats;
import io.training.SelfPlayDataset;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Reads a batch together so duplicate-game checks span every input.
 */
public final class SelfPlayDatasetCheckApp {

    private SelfPlayDatasetCheckApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length == 0)
            throw new IllegalArgumentException(
                    "Usage: SelfPlayDatasetCheckApp <archive-directory> [...]");

        var games = SelfPlayDataset.openAll(
                Arrays.stream(args).map(Path::of).toList());

        for (var game : games) {
            int decisions = game.phases().stream()
                    .mapToInt(phase -> phase.decisions().size()).sum();

            long movementPolicies = game.phases().stream()
                    .filter(phase -> phase.state().moment().gamePhase().isMovement())
                    .flatMap(phase -> phase.decisions().stream())
                    .filter(decision -> decision.search().isPresent())
                    .count();

            long valuePositions = game.valueAssessment().rewards().isPresent()
                    ? game.phases().stream()
                    .filter(phase -> !(phase.state() instanceof GameState_Retreats))
                    .count()
                    : 0;

            System.out.println("Replay verified: " + game.id());
            System.out.println("Archive fingerprint: " + game.fingerprint());
            System.out.println("Logical game key: " + game.logicalKey());
            System.out.println(
                    "Phases=" + game.phases().size()
                            + " decisions=" + decisions
                            + " movement_search_policies=" + movementPolicies
                            + " eligible_value_positions=" + valuePositions);
            System.out.println(
                    "Ending=" + game.outcome().termination()
                            + " value_rejection="
                            + game.valueAssessment().rejection());
        }

        System.out.println(
                "Verified " + games.size()
                        + " distinct games. No training or TEST access.");

    }

}