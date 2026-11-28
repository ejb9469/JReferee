package _app;

import game.*;
import io.catalog.SourceFingerprints;
import io.json.JsonAccess;
import io.json.JsonReader;
import parsing.ParseException;
import parsing.diplobn.DiploBNParser;
import parsing.webdiplomacy.WebDiplomacyAdapter;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static io.json.JsonEscaper.appendString;


/**
 * Fingerprints caller-verified TRAIN projections and diagnoses webDiplomacy
 * mechanics. It has no catalog, dataset-export or model access.
 *
 * @deprecated Temporary diagnostic.
 *              A cohesive training-import workflow should replace & supersede this application;
 *              keep import and admission behavior out of this class.
 */
@Deprecated(forRemoval = true)
public final class TrainingSourcePilotApp {


    private static final JsonAccess JSON = new JsonAccess(ParseException::new);
    private static final int MAX_LINE = 16 * 1024 * 1024;


    private TrainingSourcePilotApp() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 2)
            throw new IllegalArgumentException("Usage: TrainingSourcePilotApp <requests.jsonl> <new-results.jsonl>");
        Path output = Path.of(args[1]);
        require(!Files.exists(output) && !Files.isSymbolicLink(output), "Results already exist");
        Set<String> identities = new HashSet<>();
        try (var input = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8);
             var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            String line;
            while ((line = input.readLine()) != null) {
                require(line.length() <= MAX_LINE && !line.isBlank(), "Invalid request line size");
                Map<String, Object> request = JSON.object(JsonReader.read(line), "$" );
                String source = text(request, "source"), key = text(request, "game_key");
                require(identities.add(source + "\n" + key) && identities.size() <= 2000,
                        "Duplicate identity or request limit exceeded");
                writer.write(inspect(request));
                writer.newLine();
                if (identities.size() % 100 == 0)
                    System.out.println("[SEMANTICS] Processed " + identities.size() + " TRAIN projections");
            }
        }
        require(!identities.isEmpty(), "No TRAIN projections supplied");
        System.out.println("[SEMANTICS] Completed " + identities.size() + " TRAIN projections");

    }

    private static String inspect(Map<String, Object> request) {

        String source = text(request, "source"), key = text(request, "game_key");
        require(Set.of("DIPLOBN", "WEBDIPLOMACY").contains(source) && key.matches("[1-9][0-9]*"),
                "Unexpected source identity");
        String splitHash = hash("7\n" + source + "\n" + key);
        require(Long.remainderUnsigned(Long.parseUnsignedLong(splitHash.substring(0, 16), 16), 100) < 80,
                "Held-out projections are prohibited");
        String sourceHash = text(request, "source_payload_sha256");
        String contentHash = text(request, "content_sha256");
        String content = text(request, "content");
        require(sourceHash.matches("[0-9a-f]{64}") && hash(content).equals(contentHash),
                "Projection receipt differs");
        StringBuilder out = new StringBuilder("{\"source\":");
        appendString(out, source);
        out.append(",\"game_key\":");
        appendString(out, key);
        out.append(",\"source_payload_sha256\":");
        appendString(out, sourceHash);
        out.append(",\"content_sha256\":");
        appendString(out, contentHash);

        List<GameState_Movement> states;
        WebDiplomacyAdapter.SourceGame web = null;
        Fingerprint fingerprint;
        try {
            if (source.equals("WEBDIPLOMACY")) {
                web = WebDiplomacyAdapter.readFixture(content);
                require(web.key().equals(key) && web.sourceHash().equals(sourceHash),
                        "Projected game identity differs");
                states = web.frames().stream().filter(frame -> frame.name().equals("Diplomacy"))
                        .map(frame -> new GameState_Movement(frame.moment(), frame.board())).toList();
            } else {
                var game = new DiploBNParser().parse(content);
                states = game.phases().stream().filter(phase -> phase.gamePhase().isMovement())
                        .map(phase -> new GameState_Movement(
                                new GameMoment(phase.sourcePhase() / 10, phase.gamePhase()), phase.board())).toList();
            }
            fingerprint = fingerprint(states);
        } catch (IllegalArgumentException exception) {
            out.append(",\"status\":\"INVALID_SOURCE\",\"detail\":");
            appendString(out, exception.getMessage());
            return out.append('}').toString();
        }
        out.append(",\"status\":\"FINGERPRINTED\",\"movement_frames\":").append(fingerprint.frames())
                .append(",\"trajectory_sha256\":");
        appendString(out, fingerprint.trajectory());
        out.append(",\"prefix_sha256\":");
        if (fingerprint.prefix() == null)
            out.append("null");
        else
            appendString(out, fingerprint.prefix());
        if (web != null) {
            out.append(",\"replay\":");
            WebDiplomacyReplayApp.replay(web).appendJson(out);
        }
        return out.append('}').toString();

    }

    /**
     * Uses ordered start-of-movement boards, including exact fleet coasts and
     * positive center owners. UUIDs, orders, source IDs and result flags are
     * excluded. Shared four-frame prefixes are conservative collision gates.
     */
    public static Fingerprint fingerprint(List<GameState_Movement> states) {

        require(!states.isEmpty(), "No movement snapshots");
        require(boardKey(states.getFirst().board()).equals(boardKey(StandardGameFactory.create1901().board())),
                "Fingerprint history does not start from standard Classic");
        List<String> hashes = new ArrayList<>();
        for (int turn = 0; turn < states.size(); turn++) {
            GameState_Movement state = states.get(turn);
            GamePhase phase = turn % 2 == 0 ? GamePhase.SPRING_MOVEMENT : GamePhase.FALL_MOVEMENT;
            require(state.moment().equals(new GameMoment(1901 + turn / 2, phase)),
                    "Fingerprint history has a missing or repeated movement snapshot");
            hashes.add(hash("jreferee-movement-board-v1\n" + state.moment().year()
                    + "\n" + phase.name() + "\n" + boardKey(state.board())));
        }
        String trajectory = hash("jreferee-movement-trajectory-v1\n" + String.join("\n", hashes));
        String prefix = hashes.size() < 4 ? null
                : hash("jreferee-movement-prefix-4-v1\n" + String.join("\n", hashes.subList(0, 4)));
        return new Fingerprint(hashes.size(), trajectory, prefix);

    }

    private static String boardKey(BoardState board) {

        List<String> units = board.locations().entrySet().stream().map(entry ->
                "U\t" + entry.getKey().owner().name() + "\t" + entry.getKey().unitType().name()
                        + "\t" + entry.getValue().name()).sorted().toList();
        List<String> centers = board.owners().entrySet().stream().map(entry ->
                "C\t" + entry.getKey().name() + "\t" + entry.getValue().name()).sorted().toList();
        return String.join("\n", units) + "\n" + String.join("\n", centers);

    }

    private static String text(Map<String, Object> value, String key) {
        return JSON.string(JSON.required(value, key, "$"), "$." + key);
    }

    private static String hash(String value) {
        return SourceFingerprints.sha256Hex(value);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalArgumentException(message);
    }


    public record Fingerprint(int frames, String trajectory, String prefix) { }

}