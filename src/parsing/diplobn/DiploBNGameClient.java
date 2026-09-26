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
import java.util.*;

import static io.json.JsonEscaper.appendString;


/**
 * Downloads canonical DiploBN game JSON through the analytics hub and
 * delegates game decoding to DiploBNParser.
 */
public class DiploBNGameClient {


    // Constants \\

    private static final URI HUB_URI =
            URI.create("https://diplobn.com/wp-json/DBNAnalytics/v1/hubget/13");

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);


    // Core state \\

    private final HttpClient httpClient;
    private final DiploBNParser parser;
    private final Duration requestTimeout;


    // Constructors \\

    public DiploBNGameClient() {

        this(
                HttpClient.newBuilder()
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                new DiploBNParser(),
                DEFAULT_REQUEST_TIMEOUT);

    }

    public DiploBNGameClient(
            HttpClient httpClient,
            DiploBNParser parser,
            Duration requestTimeout
    ) {

        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");

        if (requestTimeout.isNegative() || requestTimeout.isZero())
            throw new IllegalArgumentException("requestTimeout must be positive");

    }


    // Download entry points \\

    public DiploBNDownloadedGame downloadGame(long gameId)
            throws IOException, InterruptedException {

        if (gameId <= 0)
            throw new IllegalArgumentException("gameId must be positive");

        String boundary = "----JReferee" + UUID.randomUUID().toString().replace("-", "");

        String requests = "[{\"Key\":\"GetGameJSON\",\"Parameters\":{"
                + "\"GameID\":" + gameId + ",\"RootKey\":null}}]";

        String requestBody = multipartBody(boundary, "requests", requests);

        HttpRequest request = HttpRequest.newBuilder(HUB_URI)
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody, StandardCharsets.UTF_8))
                .timeout(requestTimeout)
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Origin", "https://diplobn.com")
                .header("Referer", gamePageUri(gameId).toString())
                .header("User-Agent", "JReferee-DiploBN-Importer/1.0")
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException(
                    "DiploBN GetGameJSON request failed with HTTP "
                            + response.statusCode() + " for GameID " + gameId);

        return parseHubResponse(response.body(), gameId);

    }

    public DiploBNGame loadGame(long gameId) throws IOException, InterruptedException {
        return downloadGame(gameId).game();
    }

    public DiploBNDownloadedGame downloadGamePage(String pageUrl)
            throws IOException, InterruptedException {

        Objects.requireNonNull(pageUrl, "pageUrl");

        URI pageUri;

        try {
            pageUri = new URI(pageUrl);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(
                    "Invalid DiploBN game page URL: " + pageUrl, exception);
        }

        return downloadGamePage(pageUri);

    }

    public DiploBNDownloadedGame downloadGamePage(URI pageUri)
            throws IOException, InterruptedException {

        Objects.requireNonNull(pageUri, "pageUri");
        validateGamePageUri(pageUri);

        return downloadGame(gameIdFrom(pageUri));

    }

    public DiploBNGame loadGamePage(String pageUrl)
            throws IOException, InterruptedException {
        return downloadGamePage(pageUrl).game();
    }

    public DiploBNGame loadGamePage(URI pageUri)
            throws IOException, InterruptedException {
        return downloadGamePage(pageUri).game();
    }


    // Response decoding \\

    private DiploBNDownloadedGame parseHubResponse(String responseBody, long gameId)
            throws IOException {

        if (responseBody == null || responseBody.isBlank())
            throw new IOException("DiploBN returned an empty response for GameID " + gameId);

        Object parsedResponse;

        try {
            parsedResponse = JsonReader.read(responseBody);
        } catch (ParseException exception) {
            throw new IOException(
                    "DiploBN returned malformed JSON for GameID " + gameId, exception);
        }

        Object gameObject = findGameObject(parsedResponse);

        if (gameObject == null)
            throw new IOException(
                    "DiploBN response did not contain a GamePhases object for GameID " + gameId);

        String sourceJson = toJson(gameObject);

        return new DiploBNDownloadedGame(
                gameId,
                gamePageUri(gameId).toString(),
                sourceJson,
                parser.parse(sourceJson));

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

            if (!stripped.startsWith("{") && !stripped.startsWith("["))
                return null;

            try {
                return findGameObject(JsonReader.read(stripped));
            } catch (ParseException exception) {
                return null;
            }

        }

        return null;

    }


    // Request helpers \\

    private static String multipartBody(String boundary, String fieldName, String value) {

        return "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n"
                + "\r\n" + value + "\r\n"
                + "--" + boundary + "--\r\n";

    }

    private static URI gamePageUri(long gameId) {
        return URI.create("https://diplobn.com/game/?GameID=" + gameId);
    }

    private static void validateGamePageUri(URI pageUri) {

        if (!pageUri.isAbsolute())
            throw new IllegalArgumentException(
                    "DiploBN game page URL must be absolute: " + pageUri);

        if (!"https".equalsIgnoreCase(pageUri.getScheme()))
            throw new IllegalArgumentException(
                    "DiploBN game page URL must use HTTPS: " + pageUri);

        String host = pageUri.getHost();

        if (host == null || !isDiploBNHost(host))
            throw new IllegalArgumentException(
                    "Game page URL must use diplobn.com: " + pageUri);

        String path = pageUri.getPath();

        if (path == null || !path.equals("/game/"))
            throw new IllegalArgumentException(
                    "Expected DiploBN game page path '/game/': " + pageUri);

    }

    private static long gameIdFrom(URI pageUri) {

        for (String parameter : queryParameters(pageUri)) {

            int separator = parameter.indexOf('=');
            String key = separator < 0 ? parameter : parameter.substring(0, separator);

            if (!key.equalsIgnoreCase("GameID"))
                continue;

            String rawValue = separator < 0 ? "" : parameter.substring(separator + 1);
            String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);

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
                "DiploBN game page URL requires a positive GameID parameter: " + pageUri);

    }

    private static List<String> queryParameters(URI uri) {

        String query = uri.getRawQuery();

        if (query == null || query.isBlank())
            return List.of();

        List<String> parameters = new ArrayList<>();

        for (String parameter : query.split("&"))
            if (!parameter.isBlank())
                parameters.add(parameter);

        return parameters;

    }

    private static boolean isDiploBNHost(String host) {

        String normalizedHost = host.toLowerCase(Locale.ROOT);

        return normalizedHost.equals("diplobn.com")
                || normalizedHost.endsWith(".diplobn.com");

    }


    // Canonical source serialization \\

    private static String toJson(Object value) {

        StringBuilder output = new StringBuilder();
        appendJson(output, value);

        return output.toString();

    }

    private static void appendJson(StringBuilder output, Object value) {

        if (value == null) {
            output.append("null");
            return;
        }

        if (value instanceof String text) {
            appendString(output, text);
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
                "Unsupported JSON value type: " + value.getClass().getName());

    }

    private static void appendJsonObject(StringBuilder output, Map<?, ?> object) {

        output.append('{');
        boolean first = true;

        for (Map.Entry<?, ?> entry : object.entrySet()) {

            if (!(entry.getKey() instanceof String key))
                throw new IllegalArgumentException("JSON object key is not a string");

            if (!first)
                output.append(',');

            appendString(output, key);
            output.append(':');
            appendJson(output, entry.getValue());

            first = false;

        }

        output.append('}');

    }

    private static void appendJsonArray(StringBuilder output, List<?> array) {

        output.append('[');

        for (int index = 0; index < array.size(); index++) {

            if (index > 0)
                output.append(',');

            appendJson(output, array.get(index));

        }

        output.append(']');

    }


}