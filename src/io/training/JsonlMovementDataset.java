package io.training;

import agents.encoding.EncodingPurpose;
import agents.encoding.MovementEncoding;
import agents.encoding.MovementInput;
import domain.*;
import game.*;
import io.catalog.SourceFingerprints;
import io.json.JsonAccess;
import io.training.DiploBNTrainingExporter.Selection;
//import agents.encoding.EncodingPurpose;
import io.json.JsonReader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Streams completed v4 exports using their stored movement-v2 masks.
 * The game index is retained; examples and option caches have bounded lifetimes.
 */
public final class JsonlMovementDataset implements MovementDataset {

    public static final int DEFAULT_TOKEN_CACHE = 32_768;
    public static final int MAX_LINE_CHARACTERS = 4 * 1024 * 1024;
    private static final JsonAccess JSON = new JsonAccess((path, message) ->
            new IllegalArgumentException(path + ": " + message));

    private record Key(String source, String game) { }
    private record Game(EncodingPurpose split, String hash, String communication, long examples) { }

    private final Path directory;
    private final int tokenCacheSize;
    private final Map<Key, Game> games = new HashMap<>();
    private final EnumMap<EncodingPurpose, Long> counts = new EnumMap<>(EncodingPurpose.class);
    private final EnumMap<EncodingPurpose, String> verifiedDigests = new EnumMap<>(EncodingPurpose.class);
    private final String fingerprint;
    private final TrainingFilter filter;

    public JsonlMovementDataset(Path directory) throws IOException {
        this(directory, DEFAULT_TOKEN_CACHE);
    }

    public JsonlMovementDataset(Path directory, int tokenCacheSize) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (tokenCacheSize < 0)
            throw new IllegalArgumentException("Token cache size cannot be negative");
        this.tokenCacheSize = tokenCacheSize;
        Path manifestPath = this.directory.resolve("manifest.json");
        if (Files.size(manifestPath) > MAX_LINE_CHARACTERS)
            throw new IOException("Manifest is too large: " + manifestPath);
        String text = Files.readString(manifestPath);
        MessageDigest digest = digest();
        try {
            Map<String, Object> manifest = object(text);
            schema(manifest);
            require(Boolean.TRUE.equals(required(manifest, "complete")), "Export is incomplete");
            require(MovementEncoding.SCHEMA.equals(required(manifest, "encoding")), "Unsupported movement encoding");
            require(JSON.array(required(manifest, "split_percentages"), "$.split_percentages")
                    .equals(List.of(80L, 10L, 10L)), "Unsupported split percentages");
            require("CATALOG_LEGALITY_ONLY".equals(required(manifest, "validation"))
                    && Boolean.FALSE.equals(required(manifest, "value_targets")), "Unsupported training targets");
            Map<String, Object> settings = JSON.object(required(manifest, "filter"), "$.filter");
            long seed = integer(required(manifest, "split_seed"));
            require(integer(required(settings, "split_seed")) == seed, "Conflicting split seeds");
            Set<String> labels = null;
            Object selected = required(settings, "communication_types");
            if (selected != null) {
                labels = new HashSet<>();
                for (Object label : JSON.array(selected, "$.filter.communication_types"))
                    require(labels.add(JSON.string(label, "$.filter.communication_types[]")), "Duplicate filter label");
            }
            Object unknown = required(settings, "include_unknown_communication");
            require(unknown instanceof Boolean, "Expected include_unknown_communication boolean");
            filter = new TrainingFilter(seed, labels, Boolean.TRUE.equals(unknown));
            for (EncodingPurpose split : EncodingPurpose.values()) {
                counts.put(split, count(manifest, split.name().toLowerCase(Locale.ROOT) + "_examples"));
                require(Files.isRegularFile(file(split)), "Missing split file: " + file(split));
            }
            EnumMap<EncodingPurpose, Long> observed = new EnumMap<>(EncodingPurpose.class);
            EnumMap<Selection, Long> selections = new EnumMap<>(Selection.class);
            long rejectionRecords = 0;
            DiploBNTrainingExporter splitter = new DiploBNTrainingExporter(seed);
            try (Lines lines = new Lines(this.directory.resolve("games.jsonl"), digest)) {
                String line;
                while ((line = lines.next()) != null) {
                    try {
                        Map<String, Object> row = object(line);
                        schema(row);
                        Key key = key(row);
                        EncodingPurpose split = EncodingPurpose.valueOf(string(row, "split"));
                        require(split == splitter.split(key.source(), key.game()), "Game is in the wrong split");
                        Selection selection = Selection.valueOf(string(row, "selection"));
                        long examples = count(row, "accepted_examples");
                        long rejected = count(row, "rejection_records");
                        Object rawMetadata = required(row, "source_metadata");
                        require((selection == Selection.NOT_EVALUATED) == (rawMetadata == null)
                                        && Boolean.valueOf(rawMetadata != null).equals(required(row, "metadata_available")),
                                "Inconsistent metadata availability");
                        String communication = rawMetadata == null ? null : nullableString(required(
                                JSON.object(rawMetadata, "$.source_metadata"), "CommunicationType"));
                        if (selection == Selection.INCLUDED)
                            require(filter.includes(communication), "Included game does not match the filter");
                        else {
                            require(examples == 0, "Excluded or unevaluated game has examples");
                            if (selection == Selection.FILTERED_OUT)
                                require(!filter.includes(communication) && rejected == 0,
                                        "Inconsistent filtered game");
                        }
                        Game game = new Game(split, string(row, "payload_sha256"), communication, examples);
                        if (examples > 0)
                            require(key.source().equals("DIPLOBN") && game.hash().matches("[0-9a-f]{64}"),
                                    "Invalid accepted-game source or payload hash");
                        require(games.putIfAbsent(key, game) == null, "Duplicate game identity");
                        observed.merge(split, examples, Math::addExact);
                        selections.merge(selection, 1L, Long::sum);
                        rejectionRecords = Math.addExact(rejectionRecords, rejected);
                    } catch (IllegalArgumentException exception) {
                        throw lines.error(exception);
                    }
                }
            }
            require(games.size() == count(manifest, "games_read"), "Game index count differs from manifest");
            require(rejectionRecords == count(manifest, "rejection_records"), "Rejection totals differ from manifest");
            for (EncodingPurpose split : EncodingPurpose.values())
                require(observed.getOrDefault(split, 0L).equals(counts.get(split)), "EncodingPurpose count differs from game index");
            Map<String, Object> selectionCounts = JSON.object(required(manifest, "game_selection"), "$.game_selection");
            for (Selection selection : Selection.values())
                require(count(selectionCounts, selection.name()) == selections.getOrDefault(selection, 0L),
                        "Selection totals differ from game index");
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new IOException("Invalid dataset manifest/index at " + this.directory + ": " + exception.getMessage(), exception);
        }
        fingerprint = SourceFingerprints.sha256Hex(text + "\n" + HexFormat.of().formatHex(digest.digest()));
    }

    public TrainingFilter filter() { return filter; }
    @Override public String fingerprint() { return fingerprint; }
    @Override public long examples(EncodingPurpose split) { return counts.get(Objects.requireNonNull(split, "split")); }
    @Override public Cursor open(EncodingPurpose split) throws IOException {
        return new Rows(Objects.requireNonNull(split, "split"));
    }

    private Path file(EncodingPurpose split) {
        return directory.resolve(split.name().toLowerCase(Locale.ROOT) + ".jsonl");
    }

    private synchronized void verifyDigest(EncodingPurpose split, String digest) {
        String previous = verifiedDigests.putIfAbsent(split, digest);
        require(previous == null || previous.equals(digest), "EncodingPurpose file changed between reads");
    }

    private final class Rows implements Cursor {
        private final EncodingPurpose split;
        private final Lines lines;
        private final MessageDigest checksum = digest();
        private final Map<String, MovementInput.Option> tokens = new LinkedHashMap<>(256, 0.75f, true);
        private final Set<Key> visited = new HashSet<>();
        private final Set<String> decisions = new HashSet<>();
        private Key current;
        private long gameRows, rows, options, characters, nanos, hits, misses;
        private int peakCachedOptions;
        private boolean ended, closed;
        private String sha256;

        private Rows(EncodingPurpose split) throws IOException {
            this.split = split;
            lines = new Lines(file(split), checksum);
        }

        @Override public Example next() throws IOException {
            if (closed) throw new IOException("Dataset cursor is closed");
            if (ended) return null;
            long start = System.nanoTime();
            try {
                String line = lines.next();
                if (line == null) {
                    finishGame();
                    require(rows == examples(split), "Example count differs from manifest");
                    long expectedGames = games.values().stream().filter(g -> g.split() == split && g.examples() > 0).count();
                    require(visited.size() == expectedGames, "A game is missing from this split");
                    sha256 = HexFormat.of().formatHex(checksum.digest());
                    verifyDigest(split, sha256);
                    ended = true;
                    return null;
                }
                characters += line.length();
                Map<String, Object> row = object(line);
                schema(row);
                require(string(row, "split").equals(split.name()), "Example is in the wrong split file");
                Key key = key(row);
                Game game = games.get(key);
                require(game != null && game.split() == split && game.examples() > 0, "Example game is not eligible in this split");
                require(game.hash().equals(string(row, "payload_sha256")), "Example payload hash differs from game index");
                require(Objects.equals(game.communication(), nullableString(required(row, "communication_type"))),
                        "Communication label differs from game index");
                require("CATALOG_LEGALITY_ONLY".equals(required(row, "validation")), "Unsupported validation label");
                require(required(row, "value_target") == null, "Unexpected value target in imitation data");
                if (!key.equals(current)) {
                    finishGame();
                    require(visited.add(key), "Game examples must be contiguous");
                    current = key;
                    gameRows = 0;
                    decisions.clear();
                }
                MovementInput input = input(JSON.object(required(row, "input"), "$.input"));
                require(input.power().name().equals(string(row, "power")), "Input and example power differ");
                int phase = JSON.integer(required(row, "source_phase"), "$.source_phase");
                require(phase / 10 == input.moment().year() && phase % 10 ==
                        (input.moment().gamePhase() == GamePhase.SPRING_MOVEMENT ? 1 : 2), "Input and source phase differ");
                require(decisions.add(phase + ":" + input.power().name()), "Duplicate game/phase/power example");
                List<Integer> action = new ArrayList<>();
                for (Object value : JSON.array(required(row, "action"), "$.action"))
                    action.add(JSON.integer(value, "$.action[]"));
                Example example = new Example(key.source(), key.game(), game.communication(), input, action);
                require(++gameRows <= game.examples(), "Game contains too many examples");
                rows++;
                for (var slot : input.slots()) options += slot.options().size();
                return example;
            } catch (IllegalArgumentException exception) {
                throw lines.error(exception);
            } finally {
                nanos += System.nanoTime() - start;
            }
        }

        private void finishGame() {
            if (current != null)
                require(gameRows == games.get(current).examples(), "Example count differs for " + current);
        }

        private MovementInput input(Map<String, Object> raw) {
            require(MovementEncoding.SCHEMA.equals(required(raw, "schema")), "Unsupported input schema");
            require("STANDARD".equals(required(raw, "map")), "Unsupported map");
            GameMoment moment = new GameMoment(JSON.integer(required(raw, "year"), "$.input.year"),
                    GamePhase.valueOf(string(raw, "phase")));
            Nation power = Nation.valueOf(string(raw, "power"));
            List<MovementInput.Unit> units = new ArrayList<>();
            for (Object value : JSON.array(required(raw, "units"), "$.input.units")) {
                var unit = JSON.array(value, "$.input.units[]");
                JSON.requireSize(unit, 3, "$.input.units[]");
                units.add(new MovementInput.Unit(Nation.valueOf(text(unit.get(0))), UnitType.valueOf(text(unit.get(1))),
                        Province.valueOf(text(unit.get(2)))));
            }
            Map<Province, Nation> centers = new EnumMap<>(Province.class);
            String previous = "";
            for (Object value : JSON.array(required(raw, "centers"), "$.input.centers")) {
                var center = JSON.array(value, "$.input.centers[]");
                JSON.requireSize(center, 2, "$.input.centers[]");
                String location = text(center.getFirst());
                require(previous.compareTo(location) < 0, "Centers must be unique and sorted");
                previous = location;
                centers.put(Province.valueOf(location), Nation.valueOf(text(center.get(1))));
            }
            List<MovementInput.Slot> slots = new ArrayList<>();
            for (Object value : JSON.array(required(raw, "slots"), "$.input.slots")) {
                var slot = JSON.object(value, "$.input.slots[]");
                List<MovementInput.Option> choices = new ArrayList<>();
                for (Object label : JSON.array(required(slot, "options"), "$.input.slots[].options"))
                    choices.add(token(text(label)));
                slots.add(new MovementInput.Slot(Province.valueOf(string(slot, "location")), choices));
            }
            return new MovementInput(moment, power, units, centers, slots);
        }

        private MovementInput.Option token(String label) {
            MovementInput.Option option = tokens.get(label);
            if (option != null) { hits++; return option; }
            misses++;
            option = MovementInput.Option.parse(label);
            if (tokenCacheSize > 0) {
                if (tokens.size() == tokenCacheSize) tokens.remove(tokens.keySet().iterator().next());
                tokens.put(option.label(), option);
                peakCachedOptions = Math.max(peakCachedOptions, tokens.size());
            }
            return option;
        }

        @Override public Statistics statistics() {
            return new Statistics(rows, options, characters, nanos, hits, misses, peakCachedOptions, sha256);
        }

        @Override public void close() throws IOException {
            closed = true;
            tokens.clear();
            lines.close();
        }
    }

    private static Map<String, Object> object(String text) { return JSON.object(JsonReader.read(text), "$"); }
    private static Object required(Map<String, Object> row, String name) { return JSON.required(row, name, "$"); }
    private static String text(Object value) { return JSON.string(value, "$"); }
    private static String nullableString(Object value) { return value == null ? null : text(value); }
    private static String string(Map<String, Object> row, String name) { return text(required(row, name)); }
    private static long integer(Object value) {
        if (!(value instanceof Long number)) throw new IllegalArgumentException("Expected a JSON integer");
        return number;
    }
    private static long count(Map<String, Object> row, String name) {
        long value = integer(required(row, name));
        require(value >= 0, "Negative count: " + name);
        return value;
    }
    private static Key key(Map<String, Object> row) {
        String source = string(row, "source"), key = string(row, "game_key");
        require(!source.isBlank() && !key.isBlank() && !source.contains("\n") && !key.contains("\n"),
                "Invalid source/game identity");
        return new Key(source, key);
    }
    private static void schema(Map<String, Object> row) {
        require(DiploBNTrainingExporter.SCHEMA.equals(required(row, "schema")), "Unsupported export schema");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    /**
     * Bounded UTF-8 lines with block reads. A corrupt line cannot grow without limit.
     */
    private static final class Lines implements AutoCloseable {
        private final Path path;
        private final Reader reader;
        private final char[] buffer = new char[64 * 1024];
        private int offset, length;
        private long line;

        private Lines(Path path, MessageDigest digest) throws IOException {
            this.path = path;
            InputStream input = Files.newInputStream(path);
            if (digest != null) input = new DigestInputStream(input, digest);
            reader = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder());
        }

        private String next() throws IOException {
            StringBuilder accumulated = null;
            while (true) {
                if (offset == length) {
                    try { length = reader.read(buffer); }
                    catch (IOException exception) {
                        throw new IOException(path + ":" + (line + 1) + ": UTF-8 read failed", exception);
                    }
                    offset = 0;
                    if (length < 0) {
                        length = 0;
                        if (accumulated == null) return null;
                        line++;
                        return stripCarriageReturn(accumulated.toString());
                    }
                }
                int end = offset;
                while (end < length && buffer[end] != '\n') end++;
                int size = end - offset;
                if ((accumulated == null ? 0 : accumulated.length()) + size > MAX_LINE_CHARACTERS)
                    throw new IOException(path + ":" + (line + 1) + ": line exceeds " + MAX_LINE_CHARACTERS + " characters");
                if (end < length && accumulated == null) {
                    String result = new String(buffer, offset, size);
                    offset = end + 1;
                    line++;
                    return stripCarriageReturn(result);
                }
                if (accumulated == null) accumulated = new StringBuilder(size + 128);
                accumulated.append(buffer, offset, size);
                offset = end;
                if (end < length) {
                    offset++;
                    line++;
                    return stripCarriageReturn(accumulated.toString());
                }
            }
        }

        private static String stripCarriageReturn(String line) {
            return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        }
        private IOException error(Exception exception) {
            return new IOException(path + ":" + Math.max(1, line) + ": " + exception.getMessage(), exception);
        }
        @Override public void close() throws IOException { reader.close(); }
    }
}