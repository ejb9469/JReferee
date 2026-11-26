package parsing.webdiplomacy;

import io.catalog.SourceFingerprints;
import io.json.JsonAccess;
import io.json.JsonReader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.json.JsonEscaper.appendString;


/**
 * Acquires public, completed Classic game snapshots from webDiplomacy.
 * One instance serves one serial request stream.
 */
public final class WebDiplomacyGameClient implements AutoCloseable {


    private static final URI SITE = URI.create("https://webdiplomacy.net/");
    private static final String CLASSIC = "variants/Classic/cache/variant.json";
    private static final long DELAY_MILLIS = 1000;
    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));

    private final HttpClient client;
    private boolean requested;
    private String variantVersion;
    private Document variant;


    public enum SkipReason {
        NOT_FOUND, NOT_FINISHED, NON_CLASSIC, NON_MEMBER_GAME, SANDBOX, INSUFFICIENT_HISTORY
    }

    public static final class SkippedGameException extends Exception {

        private final SkipReason reason;

        private SkippedGameException(SkipReason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public SkipReason reason() {
            return reason;
        }

    }

    public record DownloadedGame(
            long gameId, String title, String sourceJson, int phaseCount
    ) {
        public URI canonicalUri() {
            return SITE.resolve("board.php?gameID=" + gameId);
        }
    }

    private record Document(URI uri, String text, Map<String, Object> root) { }


    public WebDiplomacyGameClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /**
     * Takes ownership of the supplied client, including closing it.
     */
    public WebDiplomacyGameClient(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
        if (client.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("Automatic redirects must be disabled");
    }


    public DownloadedGame downloadGame(long gameId)
            throws IOException, InterruptedException, SkippedGameException {

        if (gameId <= 0 || gameId > Integer.MAX_VALUE)
            throw new IllegalArgumentException("GameID must be a positive Java int");

        String directory = "cache/games/" + (gameId / 100) + "/" + gameId + "/";
        Document game = read(SITE.resolve(directory + "game.json"),
                SITE.resolve("gamefile.php?gameID=" + gameId + "&file=game"), true);
        header(game, gameId, "game");
        Map<String, Object> metadata = game.root();
        require(metadata.get("isSandbox") instanceof Boolean, "Missing isSandbox flag");
        if (Boolean.TRUE.equals(metadata.get("isSandbox")))
            throw new SkippedGameException(SkipReason.SANDBOX);
        String phase = text(metadata, "phase");
        require(List.of("Pre-game", "Diplomacy", "Retreats", "Builds", "Finished").contains(phase),
                "Unknown game phase: " + phase);
        if (!phase.equals("Finished"))
            throw new SkippedGameException(SkipReason.NOT_FINISHED);

        Map<String, Object> map = JSON.object(metadata.get("variant"), "game.variant");
        if (integer(map, "id") != 1 || !text(map, "name").equals("Classic"))
            throw new SkippedGameException(SkipReason.NON_CLASSIC);
        if (!text(metadata, "playerTypes").equals("Members"))
            throw new SkippedGameException(SkipReason.NON_MEMBER_GAME);

        String gameOver = text(metadata, "gameOver");
        if (!List.of("Won", "Drawn").contains(gameOver)) {
            StringBuilder detail = new StringBuilder("Finished Members game GameID=")
                    .append(gameId).append(" has inconsistent gameOver=");
            appendString(detail, gameOver);
            throw new IOException(detail.toString());
        }
        require(text(map, "url").equals(CLASSIC), "Unexpected Classic variant URL");

        Document history = read(SITE.resolve(directory + "history.json"),
                SITE.resolve("gamefile.php?gameID=" + gameId + "&file=history"), false);
        header(history, gameId, "history");
        int phaseCount = checkHistory(history.root(), integer(metadata, "turn"));

        String version = text(map, "version");
        require(!version.isBlank(), "Missing Classic variant version");
        if (!version.equals(variantVersion)) {
            Document downloaded = read(SITE.resolve(CLASSIC + "?v="
                            + URLEncoder.encode(version, StandardCharsets.UTF_8)),
                    SITE.resolve("gamefile.php?variantID=1&file=variant"), false);
            Map<String, Object> root = downloaded.root();
            require(integer(root, "schema") == 1 && integer(root, "variantID") == 1
                            && integer(root, "mapID") == 1 && text(root, "name").equals("Classic"),
                    "Unexpected Classic variant document");
            require(JSON.array(root.get("countries"), "variant.countries").size() == 7
                            && !JSON.array(root.get("territories"), "variant.territories").isEmpty()
                            && integer(root, "supplyCenterCount") == 34
                            && integer(root, "supplyCenterTarget") == 18,
                    "Incomplete Classic variant document");
            variant = downloaded;
            variantVersion = version;
        }

        StringBuilder payload = new StringBuilder(
                "{\"schema\":\"jreferee-webdiplomacy-raw-v1\",\"gameID\":")
                .append(gameId);
        appendFile(payload, "game", game);
        appendFile(payload, "history", history);
        appendFile(payload, "variant", variant);
        return new DownloadedGame(gameId, text(metadata, "name"),
                payload.append('}').toString(), phaseCount);

    }


    private Document read(URI uri, URI fallback, boolean gameLookup)
            throws IOException, InterruptedException, SkippedGameException {

        HttpResponse<String> response = get(uri);
        if (response.statusCode() == 404) {
            response = get(fallback);
            if (gameLookup && response.statusCode() == 404
                    && response.body().strip().equals("Unknown game."))
                throw new SkippedGameException(SkipReason.NOT_FOUND);
            if (gameLookup && response.statusCode() == 403
                    && response.body().strip().equals(
                    "You can't view this game, it is a sandbox game which you didn't create."))
                throw new SkippedGameException(SkipReason.SANDBOX);
            if (response.statusCode() != 302)
                throw httpFailure(response);

            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("Missing redirect Location: " + fallback));
            URI target = fallback.resolve(location);
            require(sameSite(target) && Objects.equals(target.getPath(), uri.getPath())
                            && target.getFragment() == null,
                    "Unexpected public-file redirect: " + target);
            response = get(target);
        }
        if (response.statusCode() != 200)
            throw httpFailure(response);
        return new Document(response.uri(), response.body(),
                JSON.object(JsonReader.read(response.body()), response.uri().toString()));

    }

    private HttpResponse<String> get(URI uri) throws IOException, InterruptedException {

        require(sameSite(uri), "Unexpected download origin: " + uri);
        if (requested)
            Thread.sleep(DELAY_MILLIS);
        requested = true;
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "JReferee-WebDiplomacy-Importer/1.0")
                .GET()
                .build();
        return client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    }

    private static boolean sameSite(URI uri) {
        return "https".equals(uri.getScheme()) && "webdiplomacy.net".equals(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
    }

    private static IOException httpFailure(HttpResponse<String> response) {
        return new IOException("HTTP " + response.statusCode() + " at " + response.uri()
                + response.headers().firstValue("Retry-After")
                .map(value -> "; Retry-After=" + value).orElse("")
                + "; collection stopped without retry");
    }

    private static void header(Document document, long gameId, String file) throws IOException {
        Map<String, Object> root = document.root();
        require(integer(root, "schema") == 1 && integer(root, "gameID") == gameId
                        && text(root, "file").equals(file) && !text(root, "version").isBlank(),
                "Unexpected game-file header: " + document.uri());
    }

    private static int checkHistory(Map<String, Object> root, int gameTurn)
            throws IOException, SkippedGameException {

        String context = "GameID=" + integer(root, "gameID");
        require(gameTurn >= 0, context + " has invalid game turn=" + gameTurn);
        List<Object> phases = JSON.array(root.get("phases"), "history.phases");
        if (phases.size() < 2)
            throw new SkippedGameException(SkipReason.INSUFFICIENT_HISTORY);
        long previous = -1;
        int previousTurn = -1;
        for (int index = 0; index < phases.size(); index++) {
            Map<String, Object> phase = JSON.object(phases.get(index), "history.phases[" + index + "]");
            int turn = integer(phase, "turn");
            String name = text(phase, "phase");
            int rank = switch (name) {
                case "Diplomacy" -> 0;
                case "Retreats" -> 1;
                case "Builds" -> 2;
                case "Finished" -> 3;
                default -> throw new IOException("Unknown history phase: " + name);
            };
            long position = 4L * turn + rank;
            require(turn >= 0 && turn <= gameTurn,
                    context + " has invalid history turn=" + turn + "; game turn=" + gameTurn);
            require(position > previous, "History phases are repeated or out of order");
            require(index != 0 || turn == 0 && name.equals("Diplomacy"),
                    "History does not start in Spring 1901");
            require(name.equals("Finished") == (index == phases.size() - 1),
                    "History must end with exactly one Finished position");
            for (String field : List.of("units", "centers"))
                JSON.array(phase.get(field), "history.phases[" + index + "]." + field);
            List<Object> orders = JSON.array(phase.get("orders"), "history.phases[" + index + "].orders");
            if (index == phases.size() - 1) {
                // The source appends Finished with the preceding history phase's turn.
                require(turn == previousTurn,
                        context + " Finished turn=" + turn
                                + " differs from preceding history turn=" + previousTurn);
                require(orders.isEmpty(), context + " Finished position contains orders");
            }
            previous = position;
            previousTurn = turn;
        }
        return phases.size();

    }

    private static void appendFile(StringBuilder out, String name, Document document) {
        out.append(',');
        appendString(out, name);
        out.append(":{\"url\":");
        appendString(out, document.uri().toString());
        out.append(",\"sha256\":");
        appendString(out, SourceFingerprints.sha256Hex(document.text()));
        out.append(",\"json\":");
        appendString(out, document.text());
        out.append('}');
    }

    private static String text(Map<String, Object> object, String key) {
        return JSON.string(object.get(key), key);
    }

    private static int integer(Map<String, Object> object, String key) {
        return JSON.integer(object.get(key), key);
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition)
            throw new IOException(message);
    }

    @Override
    public void close() {
        client.close();
    }


}