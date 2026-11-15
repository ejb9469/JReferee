package _app;

import adjudication.Justice;
import agents.evaluation.NeuralValueModel;
import agents.execution.*;
import agents.search.PolicyOutput;
import domain.Nation;
import game.StandardGameFactory;
import misc.scoring.ScoringSystem;
import phase.movement.MovementProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Explicit neural/baseline recorded-game smoke run, without a browser server.
 */
public final class NeuralValueGameApp {

    private NeuralValueGameApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 6)
            throw new IllegalArgumentException(
                    "Usage: NeuralValueGameApp <value-model.bin|baseline> <anchor.bin> "
                            + "<gru-model.bin> <last-year> <candidates> <iterations>");

        int lastYear = Integer.parseInt(args[3]);
        int candidates = Integer.parseInt(args[4]);
        int iterations = Integer.parseInt(args[5]);
        if (lastYear < 1902)
            throw new IllegalArgumentException("Use at least 1902 to exercise a nonterminal neural leaf");

        AgentModels movement = AgentModels.load(Path.of(args[1]), Optional.of(Path.of(args[2])));
        Optional<NeuralValueModel> value = args[0].equals("baseline")
                ? Optional.empty() : Optional.of(NeuralValueModel.load(Path.of(args[0])));
        AgentSettings settings = new AgentSettings(
                AgentSettings.Method.DIL_PIKL, candidates, iterations,
                1, candidates, PolicyOutput.AVERAGE,
                AgentSettings.Generator.COHERENT_GRU_HYBRID,
                AgentSettings.Generator.UNIFORM, Optional.of(movement), value);

        Map<Nation, AgentSettings> seats = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            seats.put(nation, settings);
        GameRunner.Options options = new GameRunner.Options(
                lastYear, 7, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, Justice.SHUFFLE_SEED_DEFAULT, seats);
        settings.createEvaluator(options, 0);
        UUID id = UUID.nameUUIDFromBytes(("NeuralValueGameApp:" + options)
                .getBytes(StandardCharsets.UTF_8));

        System.out.println("BEGIN VALUE GAME SMOKE RUN");
        System.out.println("Game: " + id);
        System.out.println("Configuration: " + options);
        System.out.println("Movement models: " + movement);
        if (value.isPresent()) {
            NeuralValueModel model = value.orElseThrow();
            System.out.println("Value model SHA-256=" + model.sha256());
            System.out.println("Value checkpoint SHA-256=" + model.checkpointSha256());
            System.out.println("Value dataset fingerprint=" + model.datasetFingerprint());
            System.out.println("Neural nonterminal winter leaves; exact solo/cutoff SoS.");
        } else {
            System.out.println("Baseline: existing year-end stop-now SoS evaluation.");
        }
        System.out.println("All seven seats share immutable weights; no training or TEST access.");
        System.out.flush();

        long started = System.nanoTime();
        GameRunner.Result result = new GameRunner().run(id,
                StandardGameFactory.create1901().snapshot(), options,
                phase -> { }, decision -> {
                    System.out.printf(Locale.ROOT, "%s seat=%s selected=%d seconds=%.3f%n",
                            decision.state().moment(), decision.power(), decision.selectedIndex(),
                            decision.elapsedNanos() / 1e9);
                    System.out.flush();
                });

        System.out.println("End: " + result.reason() + " at " + result.state().moment());
        System.out.println("Actual final rewards: " + result.scores());
        System.out.println("Recorded phases: " + result.record().resolvedPhases().size());
        System.out.printf(Locale.ROOT, "Elapsed seconds: %.3f%n", (System.nanoTime() - started) / 1e9);
        System.out.println("END VALUE GAME SMOKE RUN");

    }

}