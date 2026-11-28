package parsing.webdiplomacy;

import contracts.OrderForm;
import game.BoardState;
import game.GameMoment;
import game.record.GameHistory;
import io.catalog.SourceFingerprints;
import io.json.JsonAccess;
import io.json.JsonReader;
import parsing.ParseException;
import parsing.webdiplomacy.WebDiplomacyAdapter.Frame;

import java.util.*;

/**
 * Reads acquired raw bundles for historical display. Mechanics decoding is
 * shared with the adapter; TRAIN admission and replay gates remain separate.
 */
public final class WebDiplomacyParser {

    private static final JsonAccess JSON = new JsonAccess(ParseException::new);

    public GameHistory parse(String source) {
        Map<String, Object> bundle = JSON.object(JsonReader.read(source), "$");
        require(text(bundle, "schema").equals("jreferee-webdiplomacy-raw-v1"), "$.schema",
                "Unsupported webDiplomacy bundle schema");
        int gameId = integer(bundle, "gameID");
        require(gameId > 0, "$.gameID", "Expected a positive game ID");
        Map<String, Object> game = document(bundle, "game");
        Map<String, Object> history = document(bundle, "history");
        Map<String, Object> variant = document(bundle, "variant");
        for (var entry : Map.of("game", game, "history", history).entrySet())
            require(integer(entry.getValue(), "gameID") == gameId
                            && text(entry.getValue(), "file").equals(entry.getKey()),
                    entry.getKey(), "Game document identity mismatch");
        Map<String, Object> gameVariant = JSON.object(game.get("variant"), "game.variant");
        require(integer(gameVariant, "id") == 1 && text(gameVariant, "name").equals("Classic"),
                "game.variant", "Game does not use Classic");
        require(text(game, "phase").equals("Finished"), "game.phase", "Expected a completed game");
        historyPhases(history, integer(game, "turn"));
        List<Frame> frames = WebDiplomacyAdapter.decodeHistory(variant, history);
        WebDiplomacyAdapter.verifyOwnership(frames);
        WebDiplomacyAdapter.verifyFinishedOwnership(frames);
        return history(frames);
    }

    private static GameHistory history(List<Frame> frames) {
        List<GameHistory.Position> positions = new ArrayList<>();
        GameMoment previousMoment = null;
        for (Frame frame : frames) {
            boolean terminal = frame.name().equals("Finished");
            GameMoment moment = terminal ? previousMoment : frame.moment();
            BoardState board;
            String boardError = null;
            try {
                board = frame.board();
            } catch (IllegalArgumentException exception) {
                if (!terminal)
                    throw exception;
                board = null;
                boardError = "Source terminal board is unavailable: " + exception.getMessage();
            }
            List<? extends OrderForm> forms = frame.name().equals("Builds")
                    ? WebDiplomacyAdapter.adjustments(frame, board) : frame.orders();
            List<GameHistory.Submission> orders = new ArrayList<>();
            List<GameHistory.Annotation> annotations = new ArrayList<>();
            for (int index = 0; index < forms.size(); index++) {
                OrderForm order = forms.get(index);
                var submission = frame.orders().get(index);
                orders.add(new GameHistory.Submission(order.owner(), order.unitType(), submission.origin(),
                        order.orderType(), order.target(), order.auxiliaryTarget(), submission.viaConvoy()));
                annotations.add(new GameHistory.Annotation(submission.owner(), submission.origin(),
                        frame.name().equals("Retreats"), submission.success(),
                        submission.type() + (submission.dislodged() ? "; DISLODGED" : "")));
            }
            positions.add(new GameHistory.Position(frame.turn(), frame.name(), moment, board,
                    orders, annotations, frame.dislodged(), true, true, terminal,
                    frame.reportedCenters(), boardError));
            previousMoment = moment;
        }
        return new GameHistory(null, positions);
    }

    static List<Object> historyPhases(Map<String, Object> history, int gameTurn) {
        require(gameTurn >= 0, "game.turn", "Invalid game turn");
        List<Object> phases = JSON.array(history.get("phases"), "history.phases");
        require(phases.size() >= 2, "history.phases", "Expected initial and final positions");
        long previous = -1;
        int previousTurn = -1;
        for (int index = 0; index < phases.size(); index++) {
            String path = "history.phases[" + index + "]";
            Map<String, Object> phase = JSON.object(phases.get(index), path);
            int turn = integer(phase, "turn");
            String name = text(phase, "phase");
            int rank = switch (name) {
                case "Diplomacy" -> 0;
                case "Retreats" -> 1;
                case "Builds" -> 2;
                case "Finished" -> 3;
                default -> throw new ParseException(path, "Unknown phase: " + name);
            };
            require(turn >= 0 && turn <= gameTurn && 4L * turn + rank > previous,
                    path, "Invalid or unordered turn");
            require(index != 0 || turn == 0 && rank == 0, path, "Expected Spring 1901 first");
            require(rank != 2 || turn % 2 == 1, path, "Builds must follow Fall");
            require(name.equals("Finished") == (index == phases.size() - 1),
                    path, "Expected exactly one final Finished position");
            JSON.array(phase.get("units"), path + ".units");
            JSON.array(phase.get("centers"), path + ".centers");
            List<Object> orders = JSON.array(phase.get("orders"), path + ".orders");
            if (name.equals("Finished"))
                require(turn == previousTurn && orders.isEmpty(), path,
                        "Finished must retain the preceding turn and contain no orders");
            previous = 4L * turn + rank;
            previousTurn = turn;
        }
        require(gameTurn == previousTurn || gameTurn == (long) previousTurn + 1,
                "game.turn", "Game header differs from the retained history boundary");
        return phases;
    }

    private static Map<String, Object> document(Map<String, Object> bundle, String name) {
        Map<String, Object> envelope = JSON.object(bundle.get(name), name);
        String json = text(envelope, "json");
        require(SourceFingerprints.sha256Hex(json).equals(text(envelope, "sha256")),
                name, "Document fingerprint mismatch");
        Map<String, Object> document = JSON.object(JsonReader.read(json), name);
        require(integer(document, "schema") == 1, name, "Unsupported document schema");
        return document;
    }

    private static String text(Map<String, Object> value, String key) {
        return JSON.string(value.get(key), key);
    }

    private static int integer(Map<String, Object> value, String key) {
        return JSON.integer(value.get(key), key);
    }

    private static void require(boolean condition, String path, String message) {
        if (!condition)
            throw new ParseException(path, message);
    }
}
