package _app;

import agents.execution.ProposalInspector;
import game.*;
import io.training.ValidationPositions;
import ui.BoardViewerServer;
import ui.ProposalViewerServer;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class ProposalViewerApp {

    private ProposalViewerApp() { }

    public static void main(String[] args) throws Exception {
        Set<String> names = Set.of(
                "--linear-model", "--anchor-model", "--gru-model",
                "--positions", "--assets", "--port");

        Map<String, String> flags = new HashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (index + 1 >= args.length || !names.contains(args[index])
                    || flags.putIfAbsent(args[index], args[index + 1]) != null)
                throw new IllegalArgumentException("Expected distinct named value pairs: " + names);
        }

        if (!flags.containsKey("--linear-model") || !flags.containsKey("--gru-model"))
            throw new IllegalArgumentException(
                    "Required: --linear-model policy.bin --gru-model model.bin");

        Path linear = Path.of(flags.get("--linear-model"));
        var inspector = ProposalInspector.load(
                linear,
                Path.of(flags.getOrDefault("--anchor-model", linear.toString())),
                Path.of(flags.get("--gru-model")));

        List<ProposalViewerServer.Position> positions = new ArrayList<>();
        positions.add(new ProposalViewerServer.Position(
                "opening", "Standard opening", null,
                (GameState_Movement) StandardGameFactory.create1901().snapshot()));

        if (flags.containsKey("--positions")) {
            for (var position : ValidationPositions.load(Path.of(flags.get("--positions"))).positions()) {
                positions.add(new ProposalViewerServer.Position(
                        position.rank(),
                        position.source() + " " + position.gameKey() + " / " + position.state().moment(),
                        position.communication(),
                        position.state()));
            }
        }

        try (var server = new ProposalViewerServer(
                inspector,
                positions,
                Integer.parseInt(flags.getOrDefault("--port", "0")),
                Path.of(flags.getOrDefault("--assets", BoardViewerServer.DEFAULT_STATIC_ROOT.toString())))) {

            server.start();
            Runtime.getRuntime().addShutdownHook(
                    new Thread(server::close, "jreferee-proposal-viewer-shutdown"));

            System.out.println("Proposal viewer: http://127.0.0.1:" + server.address().getPort() + "/");
            System.out.println("Rankings cover generated pools only. Models remain frozen. Press Ctrl+C to stop.");
            new CountDownLatch(1).await();

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}