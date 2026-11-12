package testing.selfcheck;

import _app.NeuralCacheApp;
import agents.encoding.*;
import domain.*;
import game.*;
import io.catalog.SourceFingerprints;
import io.json.*;
import io.training.*;
import phase.UnitId;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Stable neural inputs, exact numeric files, bounded shards and incomplete
 * conversion failures. No model, source database or JDBC driver is required.
 */
public final class NeuralMovementSelfCheck {

    private static final JsonAccess JSON = new JsonAccess((path, message) ->
            new IllegalArgumentException(path + ": " + message));
    private static int checks;

    private NeuralMovementSelfCheck() { }

    public static void main(String[] args) throws Exception {
        specification();
        encoding();
        Path root = Files.createTempDirectory("jreferee-neural-check-");
        try {
            caches(root);
            failures(root);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("NeuralMovementSelfCheck passed: " + checks + " checks");
    }

    private static void specification() {
        var spec = object(NeuralMovementSpec.toJson());
        require(NeuralMovementSpec.sha256().equals(SourceFingerprints.sha256Hex(NeuralMovementSpec.toJson())), "Specification digest");
        require(spec.get("schema").equals(NeuralMovementSpec.SCHEMA), "Specification version");
        require(NeuralMovementSpec.LOCATIONS.size() == 82 && NeuralMovementSpec.locationId(Province.ADR) == 1
                && NeuralMovementSpec.locationId(Province.Yor) == 81, "Fixed vocabulary endpoints");
        require(NeuralMovementSpec.powerId(Nation.AUSTRIA) == 1 && NeuralMovementSpec.powerId(Nation.TURKEY) == 7,
                "Power IDs are independent of enum order");
        for (int id = 0; id < 82; id++)
            require(NeuralMovementSpec.locationId(NeuralMovementSpec.location(id)) == id, "Location ID round trip");
        List<Object> terrain = JSON.array(spec.get("terrain"), "$.terrain");
        List<Object> centers = JSON.array(spec.get("supply_centers"), "$.supply_centers");
        require(terrain.size() == 75 && centers.stream().filter(value -> value.equals(1L)).count() == 34, "One row per province/center");
        for (Province province : List.of(Province.Spa, Province.Stp, Province.Bul))
            require(terrain.get(NeuralMovementSpec.boardRow(province)).equals(1L), "Split-coast parent is coastal terrain");
        require(NeuralMovementSpec.boardRow(Province.SpaNC) == NeuralMovementSpec.boardRow(Province.SpaSC), "One occupied province");
        require(NeuralMovementSpec.locationId(Province.SpaNC) != NeuralMovementSpec.locationId(Province.SpaSC), "Distinct exact coast IDs");
        Set<String> direct = edges(spec, "army_direct"), convoy = edges(spec, "potential_convoy"), fleet = edges(spec, "fleet_direct");
        require(!direct.contains(pair(Province.Lon, Province.Bel)) && convoy.contains(pair(Province.Lon, Province.Bel)),
                "Potential convoy endpoints are separate from direct edges");
        require(fleet.contains(pair(Province.MAO, Province.SpaNC)) && fleet.contains(pair(Province.MAO, Province.SpaSC)),
                "Both legal fleet destination coasts");
        require(!fleet.contains(pair(Province.LYO, Province.SpaNC)), "Unreachable exact fleet coast is absent");
        expect(IllegalArgumentException.class, () -> NeuralMovementSpec.locationId(Province.Swi));
        expect(IllegalArgumentException.class, () -> NeuralMovementSpec.location(82));
        expect(IllegalArgumentException.class, () -> NeuralMovementSpec.phaseId(GamePhase.WINTER_ADJUSTMENT));
        expect(UnsupportedOperationException.class, () -> NeuralMovementSpec.LOCATIONS.clear());
    }

    private static void encoding() {
        MovementInput input = input();
        var encoded = new NeuralMovementEncoding(input);
        require(encoded.units() == 2 && encoded.power() == 3 && encoded.phase() == 2
                && encoded.year() == 1902 && encoded.normalizedYear() == 0.05f, "Context and unrounded raw year");
        require(encoded.board().remaining() == 300 && encoded.orderFields().remaining() == encoded.optionCount() * 5,
                "Packed dimensions");
        ByteBuffer board = encoded.board();
        for (int row = 0; row < 75; row++) {
            Province location = NeuralMovementSpec.boardLocation(row);
            var unit = input.unitAt(location);
            require(Byte.toUnsignedInt(board.get(row * 4)) == (unit == null ? 0 : NeuralMovementSpec.unitTypeId(unit.type())),
                    "Board unit type");
            require(Byte.toUnsignedInt(board.get(row * 4 + 1)) == (unit == null ? 0 : NeuralMovementSpec.powerId(unit.owner())),
                    "Board unit owner");
            require(Byte.toUnsignedInt(board.get(row * 4 + 2)) == (unit == null ? 0 : NeuralMovementSpec.locationId(unit.location())),
                    "Exact occupied location");
            require(Byte.toUnsignedInt(board.get(row * 4 + 3)) == NeuralMovementSpec.powerId(input.centerOwner(location)),
                    "Center ownership is independent of occupancy");
        }
        IntBuffer offsets = encoded.optionOffsets();
        ByteBuffer orders = encoded.orderFields();
        int count = 0;
        for (int slot = 0; slot < input.slots().size(); slot++) {
            require(offsets.get(slot) == count, "Slot start");
            require(Byte.toUnsignedInt(encoded.slotLocations().get(slot)) ==
                    NeuralMovementSpec.locationId(input.slots().get(slot).location()), "Existing slot order");
            for (var option : input.slots().get(slot).options()) {
                int base = count++ * 5;
                require(Byte.toUnsignedInt(orders.get(base)) == NeuralMovementSpec.unitTypeId(option.unitType())
                        && Byte.toUnsignedInt(orders.get(base + 1)) == NeuralMovementSpec.locationId(option.origin())
                        && Byte.toUnsignedInt(orders.get(base + 2)) == NeuralMovementSpec.orderTypeId(option.type())
                        && Byte.toUnsignedInt(orders.get(base + 3)) == NeuralMovementSpec.locationId(option.target())
                        && Byte.toUnsignedInt(orders.get(base + 4)) == NeuralMovementSpec.locationId(option.auxiliary()), "Exact option fields");
            }
        }
        require(offsets.get(encoded.units()) == count, "Final offset");
        require(input.slots().stream().flatMap(slot -> slot.options().stream()).anyMatch(o -> o.type() == OrderType.CONVOY),
                "Fixture retains potential convoys without matching orders");
        expect(ReadOnlyBufferException.class, () -> encoded.board().put(0, (byte) 1));
        expect(ReadOnlyBufferException.class, () -> encoded.optionOffsets().put(0, 1));
        board.position(10);
        require(encoded.board().position() == 0, "Independent view positions");
        var restricted = new MovementInput(input.moment(), input.power(), input.units(), input.centers(),
                input.slots().stream().map(slot -> new MovementInput.Slot(slot.location(),
                        slot.options().stream().filter(option -> option.type() == OrderType.HOLD).toList())).toList());
        var masked = new NeuralMovementEncoding(restricted);
        require(masked.optionCount() == encoded.units() && masked.board().equals(encoded.board()),
                "Restricted masks are retained without regenerating options or changing the board");
        var empty = new MovementInput(input.moment(), Nation.TURKEY, input.units(), input.centers(), List.of());
        var noUnits = new NeuralMovementEncoding(empty);
        require(noUnits.units() == 0 && noUnits.optionCount() == 0 && noUnits.optionOffsets().get(0) == 0,
                "Live zero-unit action");
        var invalid = new MovementInput(input.moment(), Nation.TURKEY,
                List.of(new MovementInput.Unit(Nation.FRANCE, UnitType.ARMY, Province.Swi)), Map.of(), List.of());
        expect(IllegalArgumentException.class, () -> new NeuralMovementEncoding(invalid));
        require(encoded.orderFields().equals(new NeuralMovementEncoding(input).orderFields()), "Stable encoding");
    }

    private static void caches(Path root) throws Exception {
        var dataset = new Fixture();
        int count = new NeuralMovementEncoding(input()).optionCount();
        for (int run = 0; run < 2; run++) {
            Path output = root.resolve("cache" + run);
            List<EncodingPurpose> reported = new ArrayList<>();
            var report = NeuralMovementCache.write(dataset, new TrainingFilter(), output,
                    new NeuralMovementCache.Settings(2, count * 2), split -> reported.add(split.split()));
            require(reported.equals(List.of(EncodingPurpose.values())), "Each split reaches verified EOF");
            require(Files.readString(output.resolve("manifest.json")).equals(report.toJson()), "Published complete manifest");
            require(!Files.exists(output.resolve("manifest.pending")), "Pending marker removed");
            require(report.filter().equals(new TrainingFilter()) && report.datasetFingerprint().equals(dataset.fingerprint()),
                    "Original filter and dataset identity");
            require(report.splits().getFirst().shards().size() == 2 && report.splits().getLast().shards().isEmpty(),
                    "Example-bound shards and empty split");
            for (var split : report.splits()) {
                List<MovementDataset.Example> source = dataset.rows(split.split());
                int sourceIndex = 0;
                for (var shard : split.shards()) {
                    Path directory = output.resolve(shard.directory());
                    for (var file : shard.files()) {
                        byte[] bytes = Files.readAllBytes(directory.resolve(file.name()));
                        require(bytes.length == file.bytes() && hash(bytes).equals(file.sha256()), "File size and checksum");
                        if (run == 1)
                            require(Arrays.equals(bytes, Files.readAllBytes(root.resolve("cache0").resolve(shard.directory()).resolve(file.name()))),
                                    "Repeated conversion has identical data bytes");
                    }
                    ByteBuffer boards = bytes(directory, "board.u8"), contexts = bytes(directory, "context.i32");
                    ByteBuffer exampleOffsets = bytes(directory, "example_offsets.u64"), slotLocations = bytes(directory, "slot_locations.u8");
                    ByteBuffer optionOffsets = bytes(directory, "option_offsets.u64"), orders = bytes(directory, "options.u8");
                    ByteBuffer targets = bytes(directory, "targets.i32");
                    List<String> metadata = Files.readAllLines(directory.resolve("metadata.jsonl"));
                    require(exampleOffsets.getLong(0) == 0 && optionOffsets.getLong(0) == 0, "Shard-local initial offsets");
                    for (int row = 0; row < shard.examples(); row++) {
                        var example = source.get(sourceIndex++);
                        var encoded = new NeuralMovementEncoding(example.input());
                        require(boards.slice(row * 300, 300).equals(encoded.board()), "Numeric board equals live encoding");
                        require(contexts.getInt(row * 12) == encoded.power() && contexts.getInt(row * 12 + 4) == encoded.phase()
                                && contexts.getInt(row * 12 + 8) == encoded.year(), "Little-endian context");
                        int start = Math.toIntExact(exampleOffsets.getLong(row * 8));
                        int end = Math.toIntExact(exampleOffsets.getLong((row + 1) * 8));
                        require(end - start == encoded.units() && slotLocations.slice(start, end - start).equals(encoded.slotLocations()),
                                "Example-to-slot offsets");
                        int first = Math.toIntExact(optionOffsets.getLong(start * 8));
                        int last = Math.toIntExact(optionOffsets.getLong(end * 8));
                        require(orders.slice(first * 5, (last - first) * 5).equals(encoded.orderFields()), "Packed legal options");
                        for (int slot = 0; slot < encoded.units(); slot++) {
                            int selected = targets.getInt((start + slot) * 4);
                            long size = optionOffsets.getLong((start + slot + 1) * 8) - optionOffsets.getLong((start + slot) * 8);
                            require(selected == example.action().get(slot) && selected >= 0 && selected < size, "Separate local target");
                        }
                        var meta = object(metadata.get(row));
                        require(meta.get("source").equals(example.source()) && meta.get("game_key").equals(example.gameKey())
                                && Objects.equals(meta.get("communication_type"), example.communicationType()), "Metadata identity/escaping");
                    }
                    require(exampleOffsets.getLong(shard.examples() * 8) == shard.slots()
                            && optionOffsets.getLong(shard.slots() * 8) == shard.options(), "Terminal offsets match shapes");
                }
                require(sourceIndex == source.size() && split.sourceSha256().equals("a".repeat(64)), "Source count and digest");
            }
        }
        var optionsBound = NeuralMovementCache.write(dataset, new TrainingFilter(), root.resolve("option-bound"),
                new NeuralMovementCache.Settings(10, count), ignored -> { });
        require(optionsBound.splits().getFirst().shards().size() == 3, "Option-bound shard rotation");
        expect(FileAlreadyExistsException.class, () -> NeuralMovementCache.write(dataset, new TrainingFilter(),
                root.resolve("cache0"), new NeuralMovementCache.Settings(), ignored -> { }));
    }

    private static void failures(Path root) throws Exception {
        int count = new NeuralMovementEncoding(input()).optionCount();
        for (int kind = 0; kind < 4; kind++) {
            var dataset = new Fixture();
            dataset.fault = kind;
            Path output = root.resolve("failure" + kind);
            expect(IOException.class, () -> NeuralMovementCache.write(dataset, new TrainingFilter(), output,
                    new NeuralMovementCache.Settings(), ignored -> { }));
            require(!Files.exists(output.resolve("manifest.json")), "Failed source never publishes completion");
        }
        Path tooSmall = root.resolve("too-small");
        expect(IOException.class, () -> NeuralMovementCache.write(new Fixture(), new TrainingFilter(), tooSmall,
                new NeuralMovementCache.Settings(2, count - 1), ignored -> { }));
        require(!Files.exists(tooSmall.resolve("manifest.json")), "Oversized example is not truncated");
        expect(IOException.class, () -> NeuralMovementCache.write(new Fixture(), new TrainingFilter(7, Set.of("None"), false),
                root.resolve("wrong-filter"), new NeuralMovementCache.Settings(), ignored -> { }));
        expect(IllegalArgumentException.class, () -> new NeuralMovementCache.Settings(0, 1));
        expect(IllegalArgumentException.class, () -> NeuralCacheApp.main(new String[]{"--shard-examples", "1", "--shard-examples", "2"}));
    }

    private static final class Fixture implements MovementDataset {
        private int fault = -1;
        private final MovementInput input = input();
        private final List<Integer> first = Collections.nCopies(input.slots().size(), 0);
        private final List<Integer> last = input.slots().stream().map(slot -> slot.options().size() - 1).toList();

        private List<Example> rows(EncodingPurpose split) {
            return switch (split) {
                case TRAIN -> List.of(new Example("DIPLOBN", "one", "Full", input, first),
                        new Example("DIPLOBN", "quote\"\\\n", null, input, last),
                        new Example("DIPLOBN", "three", "None", input, first));
                case VALIDATION -> List.of(new Example("DIPLOBN", "four", "Full", input, last));
                case TEST -> List.of();
            };
        }
        @Override public long examples(EncodingPurpose split) { return rows(split).size() + (fault == 0 ? 1 : 0); }
        @Override public String fingerprint() { return "b".repeat(64); }
        @Override public Cursor open(EncodingPurpose split) {
            return new Cursor() {
                private int index;
                private boolean ended;
                @Override public Example next() throws IOException {
                    if (index == rows(split).size()) {
                        if (fault == 1) throw new IOException("Invalid source at EOF");
                        ended = true;
                        return null;
                    }
                    return rows(split).get(index++);
                }
                @Override public Statistics statistics() {
                    long options = (long) index * input.slots().stream().mapToInt(slot -> slot.options().size()).sum();
                    return new Statistics(index, options + (fault == 3 ? 1 : 0), 0, 0, 0, 0, 0,
                            ended && fault != 2 ? "a".repeat(64) : null);
                }
                @Override public void close() { }
            };
        }
    }

    private static MovementInput input() {
        var state = new GameState_Movement(new GameMoment(1902, GamePhase.FALL_MOVEMENT), new BoardState(
                Map.of(new UnitId(Nation.FRANCE, UnitType.FLEET, Province.Bre), Province.MAO,
                        new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par), Province.Spa,
                        new UnitId(Nation.RUSSIA, UnitType.FLEET, Province.StpSC), Province.StpNC),
                Map.of(Province.Spa, Nation.FRANCE, Province.Stp, Nation.RUSSIA, Province.Bre, Nation.FRANCE)));
        return new MovementEncoding(state, Nation.FRANCE).input();
    }

    private static Set<String> edges(Map<String, Object> spec, String name) {
        Set<String> pairs = new HashSet<>();
        for (Object row : JSON.array(spec.get(name), "$." + name)) {
            List<Object> pair = JSON.array(row, "$." + name + "[]");
            pairs.add(pair.get(0) + ":" + pair.get(1));
        }
        return pairs;
    }
    private static String pair(Province from, Province to) { return NeuralMovementSpec.locationId(from) + ":" + NeuralMovementSpec.locationId(to); }
    private static Map<String, Object> object(String text) { return JSON.object(JsonReader.read(text), "$"); }
    private static ByteBuffer bytes(Path directory, String name) throws IOException {
        return ByteBuffer.wrap(Files.readAllBytes(directory.resolve(name))).order(ByteOrder.LITTLE_ENDIAN);
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private interface Checked { void run() throws Exception; }
    private static void expect(Class<? extends Exception> type, Checked operation) {
        try { operation.run(); }
        catch (Exception exception) {
            if (!type.isInstance(exception)) throw new AssertionError("Unexpected exception", exception);
            checks++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}