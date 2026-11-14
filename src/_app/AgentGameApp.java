package _app;

import adjudication.Justice;
import agents.execution.AgentModels;
import agents.execution.AgentSettings;
import agents.execution.GameRunner;
import agents.search.PolicyOutput;
import domain.Nation;
import game.StandardGameFactory;
import misc.scoring.ScoringSystem;
import phase.movement.MovementProcessor;
import ui.GameBrowserServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Runs a recorded seven-agent game, then serves its read-only browser.<br><br>
 *
 * Models are loaded once before play. Legacy model-free defaults remain
 * unchanged; learned development budgets are supplied explicitly.
 */
public final class AgentGameApp {


    // Usage \\

    private static final String USAGE = "Usage: AgentGameApp "
            + "[--seed <long>] [--last-year <year>] "
            + "[--port <0-65535>] [--assets <ui-directory>] "
            + "[--movement-policy <policy>] "
            + "[--candidates <count>] [--iterations <count>] "
            + "[--root-generator <uniform|plausible|coherent_gru_hybrid>] "
            + "[--anchor-model <linear-policy.bin>] [--gru-model <model.bin>] "
            + "[--rollout-generator <uniform|plausible>] "
            + "[--rollouts <count>] [--rollout-candidates <count>] "
            + "[--output <final|average>]";


    // Construction \\

    private AgentGameApp() { }


    // Application entry point \\

    public static void main(String[] args) throws Exception {

        if (args.length == 1
                && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println(USAGE);
            return;
        }

        Path assets = Path.of("src", "resources", "ui");
        Path anchorModel = null;
        Path gruModel = null;

        long seed = 7;
        int lastYear = 1903;
        int port = 0;
        int candidates = 4;
        int iterations = 8;
        int rollouts = 1;
        Integer rolloutCandidates = null;

        MovementProcessor.Policy policy = MovementProcessor.Policy.JUDGE;
        PolicyOutput output = PolicyOutput.AVERAGE;

        AgentSettings.Generator rootGenerator = AgentSettings.Generator.UNIFORM;
        AgentSettings.Generator rolloutGenerator = AgentSettings.Generator.UNIFORM;

        for (int index = 0; index < args.length; index++) {

            if (index + 1 >= args.length)
                throw new IllegalArgumentException(USAGE);

            switch (args[index]) {

                case "--seed" -> seed = Long.parseLong(args[++index]);
                case "--last-year" -> lastYear = Integer.parseInt(args[++index]);
                case "--port" -> port = Integer.parseInt(args[++index]);
                case "--assets" -> assets = Path.of(args[++index]);
                case "--candidates" -> candidates = Integer.parseInt(args[++index]);
                case "--iterations" -> iterations = Integer.parseInt(args[++index]);
                case "--rollouts" -> rollouts = Integer.parseInt(args[++index]);

                case "--rollout-candidates" ->
                        rolloutCandidates = Integer.parseInt(args[++index]);

                case "--movement-policy" -> policy = MovementProcessor.Policy.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));

                case "--output" -> output = PolicyOutput.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));

                case "--root-generator" -> rootGenerator = AgentSettings.Generator.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));

                case "--rollout-generator" -> rolloutGenerator = AgentSettings.Generator.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));

                case "--anchor-model" -> anchorModel = Path.of(args[++index]);
                case "--gru-model" -> gruModel = Path.of(args[++index]);

                default -> throw new IllegalArgumentException(USAGE);

            }

        }

        if (port < 0 || port > 65535)
            throw new IllegalArgumentException(
                    "Port must be between 0 and 65535");

        if (!Files.isDirectory(assets))
            throw new IllegalArgumentException(
                    "UI directory not found: " + assets);

        Optional<AgentModels> models = loadModels(
                rootGenerator, rolloutGenerator, anchorModel, gruModel);

        AgentSettings settings = new AgentSettings(
                AgentSettings.Method.DIL_PIKL,
                candidates,
                iterations,
                rollouts,
                rolloutCandidates == null ? candidates : rolloutCandidates,
                output,
                rootGenerator,
                rolloutGenerator,
                models
        );

        Map<Nation, AgentSettings> agents = new EnumMap<>(Nation.class);

        for (Nation nation : Nation.values())
            agents.put(nation, settings);

        GameRunner.Options options = new GameRunner.Options(
                lastYear,
                seed,
                ScoringSystem.SUM_OF_SQUARES,
                policy,
                policy == MovementProcessor.Policy.JUDGE
                        ? 1 : Justice.NUM_TRIALS_DEFAULT,
                Justice.SHUFFLE_SEED_DEFAULT,
                agents
        );

        UUID id = UUID.nameUUIDFromBytes(
                ("AgentGameApp:" + options).getBytes(StandardCharsets.UTF_8));

        System.out.println("BEGIN RECORDED AGENT GAME");
        System.out.println("Game: " + id);
        System.out.println("Configuration: " + options);
        System.out.println("JUDGE and SZYKMAN_JUSTICE may disagree on difficult positions.");
        System.out.flush();

        long started = System.nanoTime();

        GameRunner.Result result = new GameRunner().run(
                id,
                StandardGameFactory.create1901().snapshot(),
                options,
                phase -> {
                    System.out.println("Resolved " + phase.gameMoment());
                    System.out.flush();
                }
        );

        System.out.println("End: " + result.reason() + " at " + result.state().moment());
        result.winner().ifPresent(nation -> System.out.println("Winner: " + nation));
        System.out.println("Scores: " + result.scores());

        System.out.printf(
                Locale.ROOT,
                "Recorded phases: %d; elapsed: %.3f seconds%n",
                result.record().resolvedPhases().size(),
                (System.nanoTime() - started) / 1e9
        );

        System.out.println("END RECORDED AGENT GAME");

        serve(result, port, assets);

    }


    // Model configuration \\

    private static Optional<AgentModels> loadModels(
            AgentSettings.Generator root,
            AgentSettings.Generator rollout,
            Path anchor,
            Path gru
    ) throws IOException {

        if (rollout == AgentSettings.Generator.COHERENT_GRU_HYBRID)
            throw new IllegalArgumentException(
                    "--rollout-generator must be uniform or plausible");

        if (root == AgentSettings.Generator.COHERENT_GRU_HYBRID
                && (anchor == null || gru == null))
            throw new IllegalArgumentException(
                    "coherent_gru_hybrid requires --anchor-model and --gru-model");

        if (gru != null && root != AgentSettings.Generator.COHERENT_GRU_HYBRID)
            throw new IllegalArgumentException(
                    "--gru-model requires --root-generator coherent_gru_hybrid");

        if (anchor == null)
            return Optional.empty();

        return Optional.of(AgentModels.load(anchor, Optional.ofNullable(gru)));

    }


    // Read-only browser \\

    private static void serve(
            GameRunner.Result result,
            int port,
            Path assets
    ) throws IOException {

        try (GameBrowserServer server = new GameBrowserServer(result, port, assets)) {

            Thread shutdown = new Thread(
                    server::close, "agent-game-browser-shutdown");

            Runtime.getRuntime().addShutdownHook(shutdown);

            try {

                server.start();

                System.out.println();
                System.out.println("Game browser: http://127.0.0.1:" + server.address().getPort());
                System.out.println("Recorded game: read-only, in memory; no database access.");
                System.out.println("Snapshots show each phase before resolution, followed by the final board.");
                System.out.println("Use the snapshot selector, arrow keys, Home, and End to navigate.");
                System.out.println("Press Ctrl+C to stop.");

                new CountDownLatch(1).await();

            } catch (InterruptedException exception) {

                Thread.currentThread().interrupt();

            } finally {

                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown has already begun.
                }

            }

        }

    }

}