package _app;

import contracts.OrderForm;
import game.GameMoment;
import game.GamePhase;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNGameClient;
import parsing.diplobn.DiploBNPhase;
import phase.Order;
import ui.BoardSnapshotJsonWriter;
import ui.BoardSnapshotMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.json.JsonEscaper.appendString;


/**
 * Exports DiploBN source movement snapshots for the strategy browser.
 *
 * This does not create training records, replay the game, resolve imported
 * orders, or infer omitted submissions.
 */
public final class DBNGameImporter {

    private DBNGameImporter() { }


    public static void main(String[] args) throws Exception {

        if (args.length != 1 && args.length != 2 && args.length != 4)
            throw usage();

        String source = args[0].trim();

        Path destination = (args.length >= 2
                ? Path.of(args[1])
                : Path.of("data", "strategy-game.json"))
                .toAbsolutePath()
                .normalize();

        Integer selectedYear = null;
        GamePhase selectedPhase = null;

        if (args.length == 4) {

            selectedYear = Integer.parseInt(args[2]);

            selectedPhase = GamePhase.valueOf(
                    args[3].trim().toUpperCase(Locale.ROOT));

            if (!selectedPhase.isMovement())
                throw new IllegalArgumentException(
                        "Select SPRING_MOVEMENT or FALL_MOVEMENT.");

        }

        if (Files.exists(destination))
            throw new IllegalArgumentException(
                    "Output already exists. Choose a new filename: " + destination);

        DiploBNGameClient client = new DiploBNGameClient();

        System.out.println("Downloading DiploBN game...");

        DiploBNGame game;

        try {

            game = source.matches("[1-9][0-9]*")
                    ? client.loadGame(Long.parseLong(source))
                    : client.loadGamePage(source);

        } catch (InterruptedException exception) {

            Thread.currentThread().interrupt();
            throw exception;

        }

        List<Integer> selectedIndexes = new ArrayList<>();

        System.out.println();
        System.out.println("Available movement snapshots:");

        for (int index = 0; index < game.phases().size(); index++) {

            DiploBNPhase phase = game.phases().get(index);

            if (!phase.gamePhase().isMovement())
                continue;

            int year = phase.sourcePhase() / 10;

            System.out.printf(
                    "[snapshot %d] %d %s; status=%s; units=%d; submitted orders=%d%n",
                    index + 1,
                    year,
                    phase.gamePhase(),
                    phase.sourceStatus(),
                    phase.board().locations().size(),
                    phase.movementOrders().size());

            if (selectedYear == null
                    || (year == selectedYear && phase.gamePhase() == selectedPhase))
                selectedIndexes.add(index);

        }

        if (selectedIndexes.isEmpty())
            throw new IllegalArgumentException(
                    "No movement snapshots match the requested phase.");

        String json = exportJson(source, game, selectedIndexes);

        Path parent = destination.getParent();

        if (parent != null)
            Files.createDirectories(parent);

        Files.writeString(
                destination,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);

        System.out.println();
        System.out.println("Exported " + selectedIndexes.size() + " movement snapshot(s).");
        System.out.println("File: " + destination);
        System.out.println(
                "In StrategyBrowserApp: choose this file, select a phase, then import it.");
        System.out.println(
                "Source submissions are comparison data only, not strategy inputs.");

    }


    private static String exportJson(
            String requestedSource,
            DiploBNGame game,
            List<Integer> selectedIndexes) {

        StringBuilder out = new StringBuilder();

        out.append("{\"format\":\"jreferee-strategy-game\",\"version\":1");
        out.append(",\"requestedSource\":").append(quote(requestedSource));
        out.append(",\"sourceUrl\":").append(quote(game.sourceUrl()));
        out.append(",\"competition\":").append(quote(game.competition()));
        out.append(",\"gameLabel\":").append(quote(game.gameLabel()));
        out.append(",\"provenance\":").append(quote(
                "Parsed DiploBN source snapshots. Not locally replay-verified. "
                        + "Movement submissions are historical comparison data only."));
        out.append(",\"phases\":[");

        boolean firstPhase = true;

        for (int index : selectedIndexes) {

            if (!firstPhase)
                out.append(',');

            firstPhase = false;

            DiploBNPhase phase = game.phases().get(index);

            GameMoment moment = new GameMoment(
                    phase.sourcePhase() / 10,
                    phase.gamePhase());

            List<Order> orders = new ArrayList<>(phase.movementOrders());

            orders.sort(Comparator.comparing(
                    order -> phase.board().locationOf(order.unit()).name()));

            Set<phase.UnitId> issuers = new HashSet<>();

            for (Order order : orders) {

                if (phase.board().locationOf(order.unit()) == null)
                    throw new IllegalArgumentException(
                            "Submitted order issuer absent at snapshot " + (index + 1));

                if (!issuers.add(order.unit()))
                    throw new IllegalArgumentException(
                            "Duplicate submitted order issuer at snapshot " + (index + 1));

            }

            out.append("{\"snapshot\":").append(index + 1);
            out.append(",\"sourcePhase\":").append(phase.sourcePhase());
            out.append(",\"sourceStatus\":").append(quote(phase.sourceStatus()));
            out.append(",\"board\":").append(
                    BoardSnapshotJsonWriter.toJson(
                            BoardSnapshotMapper.from(moment, phase.board())));

            out.append(",\"missingSubmissionCount\":")
                    .append(phase.board().locations().size() - issuers.size());

            out.append(",\"orders\":[");

            boolean firstOrder = true;

            for (Order order : orders) {

                if (!firstOrder)
                    out.append(',');

                firstOrder = false;

                out.append("{\"nation\":").append(quote(order.owner().name()));
                out.append(",\"text\":").append(quote(
                        OrderForm.format(order, phase.board().locationOf(order.unit()))));
                out.append(",\"origin\":").append(
                        quote(phase.board().locationOf(order.unit()).name()));
                out.append(",\"type\":").append(quote(order.orderType().name()));
                out.append(",\"target\":").append(quote(
                        order.target() == null ? null : order.target().name()));
                out.append(",\"auxiliaryTarget\":").append(quote(
                        order.auxiliaryTarget() == null
                                ? null
                                : order.auxiliaryTarget().name()));

                out.append('}');

            }

            out.append("]}");

        }

        return out.append("]}").toString();

    }

    private static String quote(String value) {

        if (value == null)
            return "null";

        StringBuilder out = new StringBuilder();
        appendString(out, value);
        return out.toString();

    }

    private static IllegalArgumentException usage() {

        return new IllegalArgumentException(
                "Usage: DBNGameImporter <GameID-or-HTTPS-game-URL> "
                        + "[output.json] [year SPRING_MOVEMENT|FALL_MOVEMENT]");

    }

}