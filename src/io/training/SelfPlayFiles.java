package io.training;

import io.json.JsonAccess;
import io.json.JsonEscaper;
import io.json.JsonReader;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * New-directory archive with a completion manifest written last.
 * Verification checks integrity and record counts, not Diplomacy legality.
 */
public final class SelfPlayFiles implements AutoCloseable {

    public static final String SCHEMA = "jreferee-self-play-v1";
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final List<String> PAYLOADS = List.of("run.json", "phases.jsonl", "outcome.json");
    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));

    private final Path directory;
    private final BufferedWriter phases;
    private final String gameId;
    private int phaseCount;
    private int decisionCount;
    private boolean closed;

    public record Summary(String gameId, int phases, int decisions, String termination) { }

    public record Snapshot(
            Summary summary, String run, String phases, String outcome, String fingerprint
    ) { }

    public SelfPlayFiles(Path directory, Map<String, Object> run) throws IOException {

        this.directory = Objects.requireNonNull(directory, "directory");
        this.gameId = text(run, "game_id");
        require(SCHEMA.equals(text(run, "schema")), "Unsupported run schema");
        String document = json(run);
        Files.createDirectory(directory);
        write("run.json", document);
        this.phases = Files.newBufferedWriter(directory.resolve("phases.jsonl"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

    }

    public void append(Map<String, Object> phase) throws IOException {

        if (this.closed)
            throw new IllegalStateException("Archive is closed");
        require(integer(phase, "index") == this.phaseCount, "Phase index is not consecutive");
        int decisions = JSON.array(phase.get("decisions"), "decisions").size();
        String line = json(phase);
        try {
            this.phases.write(line);
            this.phases.write('\n');
            this.phases.flush();
        } catch (IOException exception) {
            this.closed = true;
            throw exception;
        }
        this.phaseCount++;
        this.decisionCount += decisions;

    }

    public Summary finish(Map<String, Object> outcome) throws IOException {

        if (this.closed)
            throw new IllegalStateException("Archive is closed");
        this.closed = true;
        this.phases.close();
        require(this.gameId.equals(text(outcome, "game_id")), "Outcome belongs to another game");
        require(integer(outcome, "phase_count") == this.phaseCount
                && integer(outcome, "decision_count") == this.decisionCount, "Outcome counts differ");
        write("outcome.json", json(outcome));

        Map<String, Object> receipts = new LinkedHashMap<>();
        for (String name : PAYLOADS) {
            byte[] bytes = read(this.directory.resolve(name));
            receipts.put(name, object("bytes", bytes.length, "sha256", hash(bytes)));
        }
        Map<String, Object> manifest = object("schema", SCHEMA, "game_id", this.gameId,
                "files", receipts);
        Summary summary = verifyPayloads(this.directory, manifest).summary();
        write("manifest.pending", json(manifest));
        Files.move(this.directory.resolve("manifest.pending"), this.directory.resolve("manifest.json"),
                StandardCopyOption.ATOMIC_MOVE);
        return summary;

    }

    @Override
    public void close() throws IOException {

        this.closed = true;
        this.phases.close();

    }

    public static Summary verify(Path directory) throws IOException {
        return snapshot(directory).summary();
    }

    /**
     * Returns the exact verified payloads, avoiding a verification/read race.
     */
    public static Snapshot snapshot(Path directory) throws IOException {
        return verifyPayloads(directory, parse(read(directory.resolve("manifest.json"))));
    }

    private static Snapshot verifyPayloads(Path directory, Map<String, Object> manifest) throws IOException {

        require(SCHEMA.equals(text(manifest, "schema")), "Unsupported archive schema");
        Map<String, Object> receipts = JSON.object(manifest.get("files"), "files");
        require(receipts.keySet().equals(new HashSet<>(PAYLOADS)), "Unexpected archive payloads");
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        StringBuilder identity = new StringBuilder(SCHEMA);
        for (String name : PAYLOADS) {
            Map<String, Object> receipt = JSON.object(receipts.get(name), name);
            byte[] bytes = read(directory.resolve(name));
            require(integer(receipt, "bytes") == bytes.length
                    && hash(bytes).equals(text(receipt, "sha256")), "Checksum or size mismatch: " + name);
            payloads.put(name, bytes);
            identity.append('\n').append(hash(bytes));
        }
        Map<String, Object> run = parse(payloads.get("run.json"));
        Map<String, Object> outcome = parse(payloads.get("outcome.json"));
        String id = text(manifest, "game_id");
        UUID.fromString(id);
        require(SCHEMA.equals(text(run, "schema")) && id.equals(text(run, "game_id"))
                && id.equals(text(outcome, "game_id")), "Archive identities differ");
        int phases = 0, decisions = 0;
        String lines = utf8(payloads.get("phases.jsonl"));
        require(lines.isEmpty() || lines.endsWith("\n"), "Truncated final phase line");
        try (BufferedReader reader = new BufferedReader(new StringReader(lines))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String, Object> phase = JSON.object(JsonReader.read(line), "phase");
                require(integer(phase, "index") == phases++, "Phase sequence differs");
                decisions += JSON.array(phase.get("decisions"), "decisions").size();
            }
        }
        require(phases == integer(outcome, "phase_count")
                && decisions == integer(outcome, "decision_count"), "Archive counts differ");
        String termination = text(outcome, "termination");
        boolean solo = termination.equals("SOLO");
        require(solo || termination.equals("ARTIFICIAL_CUTOFF"), "Unsupported termination");
        require(outcome.containsKey("eventual_sos_target")
                && outcome.containsKey("cutoff_scores"), "Missing outcome target fields");
        require(Boolean.valueOf(solo).equals(outcome.get("value_target_eligible")),
                "Target eligibility differs from termination");
        require(solo ? outcome.get("eventual_sos_target") != null && outcome.get("cutoff_scores") == null
                        : outcome.get("eventual_sos_target") == null && outcome.get("cutoff_scores") != null,
                "Cutoff scores and eventual targets must remain separate");
        return new Snapshot(new Summary(id, phases, decisions, termination),
                utf8(payloads.get("run.json")), lines, utf8(payloads.get("outcome.json")),
                hash(identity.toString().getBytes(StandardCharsets.UTF_8)));

    }

    public static Map<String, Object> object(Object... fields) {

        if (fields.length % 2 != 0)
            throw new IllegalArgumentException("Expected key/value pairs");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            if (!(fields[index] instanceof String key) || result.containsKey(key))
                throw new IllegalArgumentException("Invalid or duplicate object key");
            result.put(key, fields[index + 1]);
        }
        return result;

    }

    public static String json(Object value) {

        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();

    }

    private static void append(StringBuilder out, Object value) {

        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            JsonEscaper.appendString(out, text);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number number) {
            require(Double.isFinite(number.doubleValue()), "Non-finite JSON number");
            out.append(number);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                require(entry.getKey() instanceof String, "JSON object keys must be strings");
                if (!first)
                    out.append(',');
                first = false;
                append(out, entry.getKey());
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> values) {
            out.append('[');
            boolean first = true;
            for (Object entry : values) {
                if (!first)
                    out.append(',');
                first = false;
                append(out, entry);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
        }

    }

    private void write(String name, String text) throws IOException {
        Files.writeString(this.directory.resolve(name), text + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static byte[] read(Path path) throws IOException {

        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES)
                throw new IOException("Archive file exceeds 64 MiB: " + path);
            return bytes;
        }

    }

    private static Map<String, Object> parse(byte[] bytes) throws IOException {
        return JSON.object(JsonReader.read(utf8(bytes)), "document");
    }

    private static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String hash(byte[] bytes) {

        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }

    }

    private static String text(Map<String, Object> object, String key) {
        return JSON.string(object.get(key), key);
    }

    private static int integer(Map<String, Object> object, String key) {
        return JSON.integer(object.get(key), key);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalArgumentException(message);
    }

}