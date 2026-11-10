package _app;

import adjudication.Justice;
import agents.execution.AgentSettings;
import agents.execution.GameRunner;
import agents.search.PolicyOutput;
import domain.Nation;
import game.StandardGameFactory;
import misc.scoring.ScoringSystem;
import phase.movement.MovementProcessor;
import ui.GameBrowserServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/**
 * Plays a short seven-agent game, then opens the read-only game browser.
 *
 * <p>Run from the repository root, or supply --assets. The browser uses an
 * available loopback port unless --port is supplied. No database is required.
 * The completed game remains in memory until the application exits.</p>
 *
 * <p>All seats use fixed-pool DiL-piKL and SoS rewards. Defaults retain
 * averaged policies, uniform convoy-free candidates, and one year-end rollout.
 * Root and rollout generators, rollout budgets, and output are selectable.
 * The demonstration explicitly uses JUDGE for turnaround. SZYKMAN_JUSTICE
 * selects the reference adjudicator with its normal trial budget.</p>
 */
public final class AgentGameApp {

    // Construction \\

    private AgentGameApp() {  }


    // Application entry point \\

    public static void main(String[] args) throws Exception {

        Path assets = Path.of("src", "resources", "ui");
        long seed = 7;
        int lastYear = 1903;
        int port = 0;
        MovementProcessor.Policy policy = MovementProcessor.Policy.JUDGE;
        int candidates = 4;
        int iterations = 8;
        int rollouts = 1;
        Integer rolloutCandidates = null;
        PolicyOutput output = PolicyOutput.AVERAGE;
        AgentSettings.Generator rootGenerator = AgentSettings.Generator.UNIFORM;
        AgentSettings.Generator rolloutGenerator = AgentSettings.Generator.UNIFORM;

        for (int index = 0; index < args.length; index++) {

            if (index + 1 >= args.length)
                throw usage();

            switch (args[index]) {
                case "--seed" -> seed = Long.parseLong(args[++index]);
                case "--last-year" -> lastYear = Integer.parseInt(args[++index]);
                case "--port" -> port = Integer.parseInt(args[++index]);
                case "--assets" -> assets = Path.of(args[++index]);
                case "--movement-policy" -> policy = MovementProcessor.Policy.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));
                case "--candidates" -> candidates = Integer.parseInt(args[++index]);
                case "--iterations" -> iterations = Integer.parseInt(args[++index]);
                case "--rollouts" -> rollouts = Integer.parseInt(args[++index]);
                case "--rollout-candidates" -> rolloutCandidates = Integer.parseInt(args[++index]);
                case "--output" -> output = PolicyOutput.valueOf(args[++index].toUpperCase(Locale.ROOT));
                case "--root-generator" -> rootGenerator = AgentSettings.Generator.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));
                case "--rollout-generator" -> rolloutGenerator = AgentSettings.Generator.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));
                default -> throw usage();
            }

        }

        if (port < 0 || port > 65535)
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        if (!Files.isDirectory(assets))
            throw new IllegalArgumentException("UI directory not found: " + assets);

        Map<Nation, AgentSettings> agents = new EnumMap<>(Nation.class);
        AgentSettings settings = new AgentSettings(AgentSettings.Method.DIL_PIKL, candidates, iterations,
                rollouts, rolloutCandidates == null ? candidates : rolloutCandidates,
                output, rootGenerator, rolloutGenerator);
        for (Nation nation : Nation.values())
            agents.put(nation, settings);

        GameRunner.Options options = new GameRunner.Options(lastYear, seed, ScoringSystem.SUM_OF_SQUARES,
                policy, policy == MovementProcessor.Policy.JUDGE ? 1 : Justice.NUM_TRIALS_DEFAULT,
                Justice.SHUFFLE_SEED_DEFAULT, agents);
        UUID id = UUID.nameUUIDFromBytes(("AgentGameApp:" + options).getBytes(StandardCharsets.UTF_8));

        System.out.println("BEGIN RECORDED AGENT GAME");
        System.out.println("Configuration: " + options);
        System.out.println("JUDGE and SZYKMAN_JUSTICE may disagree on difficult positions.");

        long started = System.nanoTime();
        GameRunner.Result result = new GameRunner().run(id, StandardGameFactory.create1901().snapshot(),
                options, phase -> System.out.println("Resolved " + phase.gameMoment()));

        System.out.println("End: " + result.reason() + " at " + result.state().moment());
        result.winner().ifPresent(nation -> System.out.println("Winner: " + nation));
        System.out.println("Scores: " + result.scores());
        System.out.printf(Locale.ROOT, "Recorded phases: %d; elapsed: %.3f seconds%n",
                result.record().resolvedPhases().size(), (System.nanoTime() - started) / 1e9);
        System.out.println("END RECORDED AGENT GAME");

        try (GameBrowserServer server = new GameBrowserServer(result, port, assets)) {

            Thread shutdown = new Thread(server::close, "agent-game-browser-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);

            try {

                server.start();

                System.out.println();
                System.out.println("Game browser: http://127.0.0.1:" + server.address().getPort());
                System.out.println("Recorded game: read-only, in memory; no database access.");
                System.out.println("Snapshots show each phase before resolution, followed by the final board.");
                System.out.println("Use the snapshot selector, arrow keys, Home, and End to navigate.");
                System.out.println("Restart the server after changing browser assets.");
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

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "Usage: AgentGameApp "
                        + "[--seed <long>] [--last-year <year>] "
                        + "[--port <0-65535>] [--assets <ui-directory>] "
                        + "[--movement-policy <policy>] "
                        + "[--candidates <count>] [--iterations <count>] "
                        + "[--root-generator <uniform|plausible>] [--rollout-generator <uniform|plausible>] "
                        + "[--rollouts <count>] [--rollout-candidates <count>] [--output <final|average>]");
    }

}