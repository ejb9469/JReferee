package _app;

import adjudication.Justice;
import agents.evaluation.NeuralValueModel;
import agents.execution.*;
import agents.search.PolicyOutput;
import domain.Nation;
import game.StandardGameFactory;
import io.training.SelfPlayFiles;
import io.training.SelfPlayRecorder;
import misc.scoring.ScoringSystem;
import phase.movement.MovementProcessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Records one bounded game, or verifies an existing completed archive.
 */
public final class SelfPlayGameApp {

    private SelfPlayGameApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length == 2 && args[0].equals("--verify")) {
            System.out.println("Verified: " + SelfPlayFiles.verify(Path.of(args[1])));
            return;
        }
        if (args.length != 8)
            throw new IllegalArgumentException(
                    "Usage: SelfPlayGameApp <value-model.bin|baseline> <anchor.bin> <gru-model.bin> "
                            + "<last-year> <candidates> <iterations> <seed> <new-output-directory>"
                            + "\n   or: SelfPlayGameApp --verify <directory>");
        int lastYear = Integer.parseInt(args[3]);
        int candidates = Integer.parseInt(args[4]);
        int iterations = Integer.parseInt(args[5]);
        long seed = Long.parseLong(args[6]);
        Path directory = Path.of(args[7]);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(directory.toString());
        GameRunner.Options options = options(args[0], Path.of(args[1]), Path.of(args[2]),
                lastYear, candidates, iterations, seed);
        UUID id = gameId(options);

        System.out.println("BEGIN PERSISTENT SELF-PLAY");
        System.out.println("Game: " + id);
        System.out.println("Configuration: " + options);
        System.out.println("Output: " + directory.toAbsolutePath());
        System.out.println("No training or TEST access. A year limit is an artificial cutoff.");
        long started = System.nanoTime();
        GameRunner.Result result = SelfPlayRecorder.run(directory, id,
                StandardGameFactory.create1901().snapshot(), options, decision -> {
                    System.out.printf(Locale.ROOT, "%s seat=%s selected=%d seconds=%.3f%n",
                            decision.state().moment(), decision.power(), decision.selectedIndex(),
                            decision.elapsedNanos() / 1e9);
                    System.out.flush();
                });
        System.out.println("End: " + result.reason() + " at " + result.state().moment());
        System.out.println("Actual final scores: " + result.scores());
        System.out.println("Verified: " + SelfPlayFiles.verify(directory));
        System.out.printf(Locale.ROOT, "Elapsed seconds: %.3f%n", (System.nanoTime() - started) / 1e9);
        System.out.println("END PERSISTENT SELF-PLAY");

    }

    static GameRunner.Options options(
            String valuePath, Path anchorPath, Path gruPath,
            int lastYear, int candidates, int iterations, long seed
    ) throws IOException {

        if (lastYear < 1902)
            throw new IllegalArgumentException("Use at least 1902 to exercise nonterminal values");
        AgentModels movement = AgentModels.load(anchorPath, Optional.of(gruPath));
        Optional<NeuralValueModel> value = valuePath.equals("baseline")
                ? Optional.empty() : Optional.of(NeuralValueModel.load(Path.of(valuePath)));
        AgentSettings settings = new AgentSettings(
                AgentSettings.Method.DIL_PIKL, candidates, iterations, 1, candidates,
                PolicyOutput.AVERAGE, AgentSettings.Generator.COHERENT_GRU_HYBRID,
                AgentSettings.Generator.UNIFORM, Optional.of(movement), value);
        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        for (Nation power : Nation.values())
            seats.put(power, settings);
        GameRunner.Options options = new GameRunner.Options(
                lastYear, seed, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, Justice.SHUFFLE_SEED_DEFAULT, seats);
        settings.createEvaluator(options, 0);
        return options;

    }

    static UUID gameId(GameRunner.Options options) {
        return UUID.nameUUIDFromBytes(("SelfPlayGameApp:v1:" + options)
                .getBytes(StandardCharsets.UTF_8));
    }

}