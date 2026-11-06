package _app;

import agents.evaluation.RolloutEvaluator;
import agents.evaluation.ScoringEvaluator;
import agents.generation.*;
import agents.play.Agent;
import agents.play.GameRunner;
import agents.search.DiLPiKLSearch;
import agents.search.LambdaDistribution;
import com.sun.net.httpserver.SimpleFileServer;
import domain.Nation;
import game.Game;
import game.GameState;
import game.StandardGameFactory;
import misc.scoring.ScoringSystem;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;
import ui.NativeGameReplay;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Small local DiL-piKL-versus-uniform demo, with a persistent browser replay.
 */
public final class AgentGameApp {

    private static final String RULESET = "SZYKMAN_JUSTICE;trials=4;seed=0";

    private AgentGameApp() { }

    public static void main(String[] args) throws Exception {
        Path output = Path.of("data", "agent-demo");
        Path assets = Path.of("src", "resources", "ui");
        Path replay = null;
        int lastYear = 1903;
        int candidates = 4;
        int iterations = 16;
        int rollouts = 2;
        int port = 0;
        long seed = 42L;
        Nation power = Nation.FRANCE;
        boolean serve = false;
        boolean creationOptions = false;
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if (option.equals("--help")) {
                System.out.println(usage());
                return;
            }
            if (option.equals("--serve")) {
                serve = true;
                continue;
            }
            if (++index >= args.length)
                throw new IllegalArgumentException(usage());
            String value = args[index];
            if (value.startsWith("--"))
                throw new IllegalArgumentException("Missing value for " + option + ". " + usage());
            switch (option) {
                case "--replay" -> replay = Path.of(value);
                case "--port" -> port = Integer.parseInt(value);
                case "--output" -> { output = Path.of(value); creationOptions = true; }
                case "--assets" -> { assets = Path.of(value); creationOptions = true; }
                case "--last-year" -> { lastYear = Integer.parseInt(value); creationOptions = true; }
                case "--candidates" -> { candidates = Integer.parseInt(value); creationOptions = true; }
                case "--iterations" -> { iterations = Integer.parseInt(value); creationOptions = true; }
                case "--rollouts" -> { rollouts = Integer.parseInt(value); creationOptions = true; }
                case "--seed" -> { seed = Long.parseLong(value); creationOptions = true; }
                case "--power" -> { power = Nation.valueOf(value.toUpperCase(Locale.ROOT)); creationOptions = true; }
                default -> throw new IllegalArgumentException(usage());
            }
        }
        if (port < 0 || port > 65535 || lastYear < 1901 || candidates < 1 || iterations < 1 || rollouts < 1)
            throw new IllegalArgumentException("Invalid year, budget, or port. " + usage());
        if (replay != null && creationOptions)
            throw new IllegalArgumentException("--replay cannot be combined with game-creation options");
        if (port != 0 && !serve && replay == null)
            throw new IllegalArgumentException("--port requires --serve or --replay");
        if (replay != null) {
            output = replay;
            serve = true;
        } else {
            if (Files.exists(output))
                throw new IllegalArgumentException("Output already exists; choose a new directory or use --replay: " + output);
            if (!Files.isRegularFile(assets.resolve("games.html")))
                throw new IllegalArgumentException("Browser assets not found: " + assets);
            Map<Nation, Agent> agents = new EnumMap<>(Nation.class);
            Agent uniform = uniform(candidates);
            for (Nation nation : Nation.values()) agents.put(nation, uniform);
            agents.put(power, search(candidates, iterations, rollouts));
            Map<String, String> settings = new LinkedHashMap<>();
            settings.put("seed", Long.toString(seed));
            settings.put("lastYear", Integer.toString(lastYear));
            settings.put("searchPower", power.name());
            settings.put("agents", power + ": DiL-piKL; other nations: uniform candidate pools");
            settings.put("candidates", Integer.toString(candidates));
            settings.put("iterations", Integer.toString(iterations));
            settings.put("rollouts", Integer.toString(rollouts));
            settings.put("rolloutCandidates", Integer.toString(candidates));
            settings.put("populationLambdas", "0.01,0.1 (equal weights)");
            settings.put("agentLambda", "0.01");
            settings.put("kappa", "0");
            settings.put("opponents", "Uniform selection within generated candidate pools");
            settings.put("engineRevision", System.getProperty("jreferee.engineRevision", "UNSPECIFIED"));
            Game game = simulation(StandardGameFactory.create1901().snapshot());
            System.out.println("Running " + power + " versus six uniform-pool agents through Fall " + lastYear);
            GameRunner.Result result = GameRunner.play(game, agents, UUID.randomUUID(), RULESET,
                    lastYear, seed, state -> System.out.println("Reached " + state.moment()));
            NativeGameReplay.writeBundle(output, assets, result, ScoringSystem.SUM_OF_SQUARES, settings);
            System.out.println("Result: " + result.endReason() + "; " + result.record().resolvedPhases().size() + " recorded phases");
            System.out.println("Replay saved: " + output.toAbsolutePath());
        }
        if (serve) {
            Path directory = output.toRealPath();
            if (!Files.isRegularFile(directory.resolve("index.html")) || !Files.isRegularFile(directory.resolve("replay.json")))
                throw new IllegalArgumentException("Not an exported replay directory: " + directory);
            var server = SimpleFileServer.createFileServer(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                    directory, SimpleFileServer.OutputLevel.NONE);
            Thread shutdown = new Thread(() -> server.stop(0), "agent-replay-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                server.start();
                System.out.println("Replay: http://127.0.0.1:" + server.getAddress().getPort() + "/");
                System.out.println("Read-only replay. Press Ctrl+C to stop.");
                new CountDownLatch(1).await();
            } finally {
                server.stop(0);
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already running the hook.
                }
            }
        }
    }

    private static Agent uniform(int candidates) {
        return (state, power, seed) -> {
            Random random = new Random(seed);
            var catalog = new LegalOrderGenerator().generate(state, power);
            var pool = new UniformActionGenerator().generate(
                    new ActionGenerationRequest(state, power, candidates, random.nextLong()), catalog);
            return ActionDistribution.fromPriors(pool).sample(random);
        };
    }

    private static Agent search(int candidates, int iterations, int rollouts) {
        return (state, power, seed) -> {
            Random random = new Random(seed);
            var evaluator = new RolloutEvaluator(new ScoringEvaluator(ScoringSystem.SUM_OF_SQUARES),
                    new UniformActionGenerator(), AgentGameApp::simulation, rollouts, candidates, random.nextLong());
            var search = new DiLPiKLSearch(evaluator, candidates, iterations,
                    LambdaDistribution.uniform(0.01, 0.1), 0.01, random.nextLong());
            return search.search(simulation(state), power).distribution().sample(random);
        };
    }

    private static Game simulation(GameState state) {
        return Game.fromState(state, new MovementProcessor(MovementProcessor.Policy.SZYKMAN_JUSTICE, 4, 0L),
                new RetreatProcessor(), new AdjustmentProcessor());
    }

    private static String usage() {
        return "AgentGameApp [--output directory] [--assets directory] [--last-year 1903] "
                + "[--power FRANCE] [--seed 42] [--candidates 4] [--iterations 16] [--rollouts 2] "
                + "[--serve] [--port 0]\n"
                + "AgentGameApp --replay directory [--port 0]";
    }

}
