package parsing.diplobn;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;


/**
 * Downloads a DiploBN game through the same JSON request used by the DiploBN
 * game viewer, then parses it into JReferee game and phase architecture.
 *
 * <p>DiploBN game pages use a browser-facing URL such as
 * {@code https://diplobn.com/game/?GameID=12990}. The page itself is HTML;
 * this client extracts the GameID, posts a {@code GetGameJSON} request to
 * DiploBN's analytics hub endpoint, extracts the returned game JSON, and
 * delegates it to {@link DiploBNParser}.</p>
 */
public final class DiploBNGameClient {


    private static final URI HUB_URI = URI.create(
            "https://diplobn.com/wp-json/DBNAnalytics/v1/hubget/13");

    private static final Duration DEFAULT_CONNECT_TIMEOUT =
            Duration.ofSeconds(10);

    private static final Duration DEFAULT_REQUEST_TIMEOUT =
            Duration.ofSeconds(30);


    private final HttpClient httpClient;
    private final DiploBNParser parser;
    private final Duration requestTimeout;


    /**
     * Creates a client with standard JDK HTTP behavior.
     */
    public DiploBNGameClient() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .followRedirects(
                                HttpClient.Redirect.NORMAL)
                        .build(),
                new DiploBNParser(),
                DEFAULT_REQUEST_TIMEOUT
        );
    }

    /**
     * Creates a client with supplied HTTP and parsing dependencies.
     *
     * <p>This overload allows tests to use a local HTTP server or a controlled
     * client configuration.</p>
     */
    public DiploBNGameClient(
            HttpClient httpClient,
            DiploBNParser parser,
            Duration requestTimeout
    ) {

        this.httpClient = Objects.requireNonNull(
                httpClient,
                "httpClient");

        this.parser = Objects.requireNonNull(parser, "parser");

        this.requestTimeout = Objects.requireNonNull(
                requestTimeout,
                "requestTimeout");

        if (requestTimeout.isNegative()
                || requestTimeout.isZero())
            throw new IllegalArgumentException(
                    "requestTimeout must be positive");

    }


    /**
     * Downloads and parses a game identified by its numeric DiploBN GameID.
     *
     * @throws IllegalArgumentException when {@code gameId} is not positive
     * @throws IOException when DiploBN cannot be reached, returns a failing
     *                     HTTP response, or returns unusable JSON
     * @throws InterruptedException when the calling thread is interrupted
     */
    public DiploBNGame loadGame(long gameId)
            throws IOException, InterruptedException {

        if (gameId <= 0)
            throw new IllegalArgumentException(
                    "gameId must be positive");

        String boundary = "----JReferee"
                + UUID.randomUUID()
                .toString()
                .replace("-", "");

        String requests = "[{\"Key\":\"GetGameJSON\",\"Parameters\":{"
                + "\"GameID\":"
                + gameId
                + ",\"RootKey\":null}}]";

        String requestBody = multipartBody(
                boundary,
                "requests",
                requests);

        HttpRequest request = HttpRequest.newBuilder(HUB_URI)
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                requestBody,
                                StandardCharsets.UTF_8))
                .timeout(requestTimeout)
                .header(
                        "Accept",
                        "application/json, text/plain, */*")
                .header(
                        "Content-Type",
                        "multipart/form-data; boundary="
                                + boundary)
                .header(
                        "Origin",
                        "https://diplobn.com")
                .header(
                        "Referer",
                        gamePageUri(gameId).toString())
                .header(
                        "User-Agent",
                        "JReferee-DiploBN-Importer/1.0")
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(
                        StandardCharsets.UTF_8));

        if (response.statusCode() < 200
                || response.statusCode() >= 300)
            throw new IOException(
                    "DiploBN GetGameJSON request failed with HTTP "
                            + response.statusCode()
                            + " for GameID "
                            + gameId);

        return parseHubResponse(
                response.body(),
                gameId);

    }

    /**
     * Extracts {@code GameID} from a DiploBN game-page URL, downloads the
     * corresponding game JSON, and parses it.
     *
     * @throws IllegalArgumentException when the URI is not a DiploBN game URL
     *                                  containing a positive GameID parameter
     * @throws IOException when DiploBN cannot be reached, returns a failing
     *                     HTTP response, or returns unusable JSON
     * @throws InterruptedException when the calling thread is interrupted
     */
    public DiploBNGame loadGamePage(String pageUrl)
            throws IOException, InterruptedException {

        Objects.requireNonNull(pageUrl, "pageUrl");

        URI pageUri;

        try {
            pageUri = new URI(pageUrl);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(
                    "Invalid DiploBN game page URL: "
                            + pageUrl,
                    exception);
        }

        return loadGamePage(pageUri);

    }

    /**
     * Extracts {@code GameID} from a DiploBN game-page URI, downloads the
     * corresponding game JSON, and parses it.
     */
    public DiploBNGame loadGamePage(URI pageUri)
            throws IOException, InterruptedException {

        Objects.requireNonNull(pageUri, "pageUri");

        validateGamePageUri(pageUri);

        return loadGame(gameIdFrom(pageUri));

    }


    private DiploBNGame parseHubResponse(
            String responseBody,
            long gameId
    ) throws IOException {

        if (responseBody == null || responseBody.isBlank())
            throw new IOException(
                    "DiploBN returned an empty response for GameID "
                            + gameId);

        Object parsedResponse;

        try {
            parsedResponse = JsonReader.read(responseBody);
        } catch (DiploBNParseException exception) {
            throw new IOException(
                    "DiploBN returned malformed JSON for GameID "
                            + gameId,
                    exception);
        }

        Object gameObject = findGameObject(parsedResponse);

        if (gameObject == null)
            throw new IOException(
                    "DiploBN response did not contain a GamePhases object"
                            + " for GameID "
                            + gameId);

        return parser.parse(toJson(gameObject));

    }

    private static Object findGameObject(Object value) {

        if (value instanceof Map<?, ?> object) {

            if (object.containsKey("GamePhases"))
                return object;

            for (Object member : object.values()) {
                Object found = findGameObject(member);

                if (found != null)
                    return found;
            }

            return null;
        }

        if (value instanceof List<?> array) {
            for (Object element : array) {
                Object found = findGameObject(element);

                if (found != null)
                    return found;
            }

            return null;
        }

        if (value instanceof String text) {

            String stripped = text.strip();

            if (!stripped.startsWith("{")
                    && !stripped.startsWith("["))
                return null;

            try {
                return findGameObject(JsonReader.read(stripped));
            } catch (DiploBNParseException exception) {
                return null;
            }

        }

        return null;

    }

    private static String multipartBody(
            String boundary,
            String fieldName,
            String value
    ) {

        return "--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\""
                + fieldName
                + "\"\r\n"
                + "\r\n"
                + value
                + "\r\n"
                + "--"
                + boundary
                + "--\r\n";

    }

    private static URI gamePageUri(long gameId) {
        return URI.create(
                "https://diplobn.com/game/?GameID="
                        + gameId);
    }

    private static void validateGamePageUri(URI pageUri) {

        if (!pageUri.isAbsolute())
            throw new IllegalArgumentException(
                    "DiploBN game page URL must be absolute: "
                            + pageUri);

        if (!"https".equalsIgnoreCase(pageUri.getScheme()))
            throw new IllegalArgumentException(
                    "DiploBN game page URL must use HTTPS: "
                            + pageUri);

        String host = pageUri.getHost();

        if (host == null || !isDiploBNHost(host))
            throw new IllegalArgumentException(
                    "Game page URL must use diplobn.com: "
                            + pageUri);

        String path = pageUri.getPath();

        if (path == null || !path.equals("/game/"))
            throw new IllegalArgumentException(
                    "Expected DiploBN game page path '/game/': "
                            + pageUri);

    }

    private static long gameIdFrom(URI pageUri) {

        for (String parameter : queryParameters(pageUri)) {

            int separator = parameter.indexOf('=');

            String key = separator < 0
                    ? parameter
                    : parameter.substring(0, separator);

            if (!key.equalsIgnoreCase("GameID"))
                continue;

            String rawValue = separator < 0
                    ? ""
                    : parameter.substring(separator + 1);

            String value = URLDecoder.decode(
                    rawValue,
                    StandardCharsets.UTF_8);

            try {
                long gameId = Long.parseLong(value);

                if (gameId <= 0)
                    break;

                return gameId;
            } catch (NumberFormatException exception) {
                break;
            }

        }

        throw new IllegalArgumentException(
                "DiploBN game page URL requires a positive GameID parameter: "
                        + pageUri);

    }

    private static List<String> queryParameters(URI uri) {

        String query = uri.getRawQuery();

        if (query == null || query.isBlank())
            return List.of();

        List<String> parameters = new ArrayList<>();

        for (String parameter : query.split("&")) {
            if (!parameter.isBlank())
                parameters.add(parameter);
        }

        return parameters;

    }

    private static boolean isDiploBNHost(String host) {

        String normalizedHost = host.toLowerCase(Locale.ROOT);

        return normalizedHost.equals("diplobn.com")
                || normalizedHost.endsWith(".diplobn.com");

    }

    private static String toJson(Object value) {

        StringBuilder output = new StringBuilder();

        appendJson(output, value);

        return output.toString();

    }

    private static void appendJson(
            StringBuilder output,
            Object value
    ) {

        if (value == null) {
            output.append("null");
            return;
        }

        if (value instanceof String text) {
            appendJsonString(output, text);
            return;
        }

        if (value instanceof Boolean bool) {
            output.append(bool);
            return;
        }

        if (value instanceof Number number) {
            output.append(number);
            return;
        }

        if (value instanceof Map<?, ?> object) {
            appendJsonObject(output, object);
            return;
        }

        if (value instanceof List<?> array) {
            appendJsonArray(output, array);
            return;
        }

        throw new IllegalArgumentException(
                "Unsupported JSON value type: "
                        + value.getClass().getName());

    }

    private static void appendJsonObject(
            StringBuilder output,
            Map<?, ?> object
    ) {

        output.append('{');

        boolean first = true;

        for (Map.Entry<?, ?> entry : object.entrySet()) {

            if (!(entry.getKey() instanceof String key))
                throw new IllegalArgumentException(
                        "JSON object key is not a string");

            if (!first)
                output.append(',');

            appendJsonString(output, key);
            output.append(':');
            appendJson(output, entry.getValue());

            first = false;

        }

        output.append('}');

    }

    private static void appendJsonArray(
            StringBuilder output,
            List<?> array
    ) {

        output.append('[');

        for (int index = 0; index < array.size(); index++) {

            if (index > 0)
                output.append(',');

            appendJson(output, array.get(index));

        }

        output.append(']');

    }

    private static void appendJsonString(
            StringBuilder output,
            String value
    ) {

        output.append('"');

        for (int index = 0; index < value.length(); index++) {

            char character = value.charAt(index);

            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append("\\u");

                        String hexadecimal = Integer.toHexString(
                                character);

                        for (int pad = hexadecimal.length();
                             pad < 4;
                             pad++)
                            output.append('0');

                        output.append(hexadecimal);
                    } else {
                        output.append(character);
                    }
                }
            }

        }

        output.append('"');

    }

}