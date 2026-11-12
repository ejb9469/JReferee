package io.training;

import agents.encoding.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

import static io.json.JsonEscaper.appendString;

/**
 * Streams verified movement examples to little-endian numeric shards.
 * Each shard stores packed options and separate target and metadata files.
 * The completion manifest is published only after every split reaches EOF.
 */
public final class NeuralMovementCache {

    public static final String SCHEMA = "jreferee-neural-cache-v1";

    private NeuralMovementCache() { }

    public record Settings(int shardExamples, int shardOptions) {
        public Settings {
            if (shardExamples < 1 || shardExamples > 1_000_000 || shardOptions < 1 || shardOptions > 100_000_000)
                throw new IllegalArgumentException("Shard limits: 1..1000000 examples, 1..100000000 options");
        }
        public Settings() { this(4096, 1_000_000); }
        public String toJson() {
            return "{\"shard_examples\":" + shardExamples + ",\"shard_options\":" + shardOptions + "}";
        }
    }

    public record FileInfo(String name, long bytes, String sha256) {
        public String toJson() {
            return "{\"name\":\"" + name + "\",\"bytes\":" + bytes + ",\"sha256\":\"" + sha256 + "\"}";
        }
    }

    public record Shard(String directory, int examples, int slots, int options, List<FileInfo> files) {
        public Shard { files = List.copyOf(files); }
        public long bytes() { return files.stream().mapToLong(FileInfo::bytes).sum(); }
        public String toJson() {
            return "{\"directory\":\"" + directory + "\",\"examples\":" + examples + ",\"slots\":" + slots
                    + ",\"options\":" + options + ",\"files\":[" + String.join(",", files.stream().map(FileInfo::toJson).toList()) + "]}";
        }
    }

    public record Split(EncodingPurpose split, long examples, long slots, long options,
                        int maxUnits, int maxOptionsPerSlot, String sourceSha256,
                        long readNanos, long elapsedNanos, List<Shard> shards) {
        public Split { shards = List.copyOf(shards); }
        public long bytes() { return shards.stream().mapToLong(Shard::bytes).sum(); }
        public String toJson() {
            return "{\"split\":\"" + split.name() + "\",\"examples\":" + examples + ",\"slots\":" + slots
                    + ",\"options\":" + options + ",\"max_units\":" + maxUnits + ",\"max_options_per_slot\":" + maxOptionsPerSlot
                    + ",\"source_sha256\":\"" + sourceSha256 + "\",\"read_nanos\":" + readNanos
                    + ",\"elapsed_nanos\":" + elapsedNanos + ",\"bytes\":" + bytes() + ",\"shards\":["
                    + String.join(",", shards.stream().map(Shard::toJson).toList()) + "]}";
        }
    }

    public record Report(String datasetFingerprint, TrainingFilter filter, Settings settings,
                         FileInfo specification, List<Split> splits) {
        public Report { splits = List.copyOf(splits); }
        public String toJson() {
            return "{\"schema\":\"" + SCHEMA + "\",\"complete\":true,\"encoding\":\"" + NeuralMovementSpec.SCHEMA
                    + "\",\"dataset_index_sha256\":\"" + datasetFingerprint + "\",\"byte_order\":\"LITTLE_ENDIAN\""
                    + ",\"test_evaluated\":false,\"filter\":" + filter.toJson() + ",\"settings\":" + settings.toJson()
                    + ",\"specification\":" + specification.toJson() + ",\"splits\":["
                    + String.join(",", splits.stream().map(Split::toJson).toList()) + "]}\n";
        }
    }

    /**
     * The dataset must expose verified source digests at EOF. The supplied
     * filter records its original selection; no additional filtering occurs.
     * A failed conversion leaves an incomplete directory for inspection.
     */
    public static Report write(MovementDataset dataset, TrainingFilter filter, Path output,
                               Settings settings, Consumer<Split> observer) throws IOException {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(observer, "observer");
        String fingerprint = dataset.fingerprint();
        requireHash(fingerprint, "Dataset index");
        if (dataset instanceof JsonlMovementDataset jsonl && !filter.equals(jsonl.filter()))
            throw new IllegalArgumentException("Cache filter differs from the source dataset");
        output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        Files.createDirectory(output);
        byte[] spec = NeuralMovementSpec.toJson().getBytes(StandardCharsets.UTF_8);
        Files.write(output.resolve("specification.json"), spec, StandardOpenOption.CREATE_NEW);
        var specification = new FileInfo("specification.json", spec.length, NeuralMovementSpec.sha256());
        List<Split> splits = new ArrayList<>();
        for (EncodingPurpose split : EncodingPurpose.values()) {
            Split result = writeSplit(dataset, filter, split, output, settings);
            splits.add(result);
            observer.accept(result);
        }
        if (!fingerprint.equals(dataset.fingerprint()))
            throw new IOException("Dataset identity changed during cache conversion");
        Report report = new Report(fingerprint, filter, settings, specification, splits);
        Path pending = output.resolve("manifest.pending");
        Files.writeString(pending, report.toJson(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(pending, output.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE);
        return report;
    }

    private record Pending(MovementDataset.Example example, NeuralMovementEncoding input) { }

    private static Split writeSplit(MovementDataset dataset, TrainingFilter filter, EncodingPurpose split,
                                    Path output, Settings settings) throws IOException {
        long start = System.nanoTime(), examples = 0, slots = 0, options = 0;
        int maxUnits = 0, maxOptions = 0;
        List<Shard> shards = new ArrayList<>();
        MovementDataset.Statistics reading;
        try (var cursor = dataset.open(split)) {
            Pending next = next(cursor, filter);
            while (next != null) {
                String name = split.name().toLowerCase(Locale.ROOT) + "-" + String.format(Locale.ROOT, "%05d", shards.size());
                try (var writer = new ShardWriter(output.resolve(name))) {
                    while (next != null && writer.examples < settings.shardExamples()) {
                        NeuralMovementEncoding input = next.input();
                        if (input.optionCount() > settings.shardOptions())
                            throw new IOException("One example exceeds the shard option limit: " + next.example().gameKey());
                        if (writer.examples > 0 && (long) writer.options + input.optionCount() > settings.shardOptions())
                            break;
                        writer.add(next);
                        examples++;
                        slots += input.units();
                        options += input.optionCount();
                        maxUnits = Math.max(maxUnits, input.units());
                        IntBuffer offsets = input.optionOffsets();
                        for (int slot = 0; slot < input.units(); slot++)
                            maxOptions = Math.max(maxOptions, offsets.get(slot + 1) - offsets.get(slot));
                        next = next(cursor, filter);
                    }
                    shards.add(writer.finish());
                }
            }
            reading = cursor.statistics();
        }
        if (examples != dataset.examples(split) || reading.examples() != examples || reading.options() != options)
            throw new IOException("Cache counts differ from the verified source: " + split);
        requireHash(reading.sha256(), "Source split " + split);
        return new Split(split, examples, slots, options, maxUnits, maxOptions, reading.sha256(),
                reading.readNanos(), System.nanoTime() - start, shards);
    }

    private static Pending next(MovementDataset.Cursor cursor, TrainingFilter filter) throws IOException {
        var example = cursor.next();
        if (example == null) return null;
        if (!filter.includes(example.communicationType()))
            throw new IOException("Example does not match the recorded source filter: " + example.gameKey());
        try { return new Pending(example, new NeuralMovementEncoding(example.input())); }
        catch (IllegalArgumentException | ArithmeticException exception) {
            throw new IOException("Cannot encode " + example.source() + "/" + example.gameKey() + ": " + exception.getMessage(), exception);
        }
    }

    private static final class ShardWriter implements AutoCloseable {
        private final Path directory;
        private final Map<String, Sink> files = new LinkedHashMap<>();
        private int examples, slots, options;

        private ShardWriter(Path directory) throws IOException {
            this.directory = directory;
            Files.createDirectory(directory);
            try {
                for (String name : List.of("board.u8", "context.i32", "example_offsets.u64", "slot_locations.u8",
                        "option_offsets.u64", "options.u8", "targets.i32", "metadata.jsonl"))
                    files.put(name, new Sink(directory.resolve(name)));
                files.get("example_offsets.u64").number(0, 8);
                files.get("option_offsets.u64").number(0, 8);
            } catch (IOException | RuntimeException exception) {
                try { close(); } catch (IOException closing) { exception.addSuppressed(closing); }
                throw exception;
            }
        }

        private void add(Pending row) throws IOException {
            NeuralMovementEncoding input = row.input();
            files.get("board.u8").write(input.board());
            Sink context = files.get("context.i32");
            context.number(input.power(), 4);
            context.number(input.phase(), 4);
            context.number(input.year(), 4);
            files.get("slot_locations.u8").write(input.slotLocations());
            files.get("options.u8").write(input.orderFields());
            IntBuffer offsets = input.optionOffsets();
            for (int slot = 0; slot < input.units(); slot++) {
                files.get("option_offsets.u64").number((long) options + offsets.get(slot + 1), 8);
                files.get("targets.i32").number(row.example().action().get(slot), 4);
            }
            examples++;
            slots = Math.addExact(slots, input.units());
            options = Math.addExact(options, input.optionCount());
            files.get("example_offsets.u64").number(slots, 8);
            StringBuilder metadata = new StringBuilder("{\"source\":");
            appendString(metadata, row.example().source());
            metadata.append(",\"game_key\":"); appendString(metadata, row.example().gameKey());
            metadata.append(",\"communication_type\":");
            if (row.example().communicationType() == null) metadata.append("null");
            else appendString(metadata, row.example().communicationType());
            files.get("metadata.jsonl").write(ByteBuffer.wrap(metadata.append("}\n").toString().getBytes(StandardCharsets.UTF_8)));
        }

        private Shard finish() throws IOException {
            close();
            Map<String, Long> sizes = Map.of("board.u8", examples * 300L, "context.i32", examples * 12L,
                    "example_offsets.u64", (examples + 1L) * 8, "slot_locations.u8", (long) slots,
                    "option_offsets.u64", (slots + 1L) * 8, "options.u8", options * 5L, "targets.i32", slots * 4L);
            List<FileInfo> results = new ArrayList<>();
            for (var entry : files.entrySet()) {
                Sink sink = entry.getValue();
                if (Files.size(directory.resolve(entry.getKey())) != sink.bytes
                        || sizes.containsKey(entry.getKey()) && sizes.get(entry.getKey()) != sink.bytes)
                    throw new IOException("Incorrect numeric file size: " + directory.resolve(entry.getKey()));
                results.add(new FileInfo(entry.getKey(), sink.bytes, sink.hash));
            }
            return new Shard(directory.getFileName().toString(), examples, slots, options, results);
        }

        @Override public void close() throws IOException {
            IOException failure = null;
            for (Sink sink : files.values())
                try { sink.close(); }
                catch (IOException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            if (failure != null) throw failure;
        }
    }

    private static final class Sink implements AutoCloseable {
        private final MessageDigest digest;
        private final OutputStream output;
        private final byte[] scratch = new byte[8192], number = new byte[8];
        private long bytes;
        private boolean closed;
        private String hash;

        private Sink(Path path) throws IOException {
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
            output = new DigestOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(path, StandardOpenOption.CREATE_NEW), 65536), digest);
        }

        private void number(long value, int width) throws IOException {
            for (int index = 0; index < width; index++) number[index] = (byte) (value >>> (index * 8));
            output.write(number, 0, width);
            bytes += width;
        }

        private void write(ByteBuffer buffer) throws IOException {
            while (buffer.hasRemaining()) {
                int count = Math.min(buffer.remaining(), scratch.length);
                buffer.get(scratch, 0, count);
                output.write(scratch, 0, count);
                bytes += count;
            }
        }

        @Override public void close() throws IOException {
            if (!closed) {
                closed = true;
                output.close();
                hash = HexFormat.of().formatHex(digest.digest());
            }
        }
    }

    private static void requireHash(String hash, String description) throws IOException {
        if (hash == null || !hash.matches("[0-9a-f]{64}"))
            throw new IOException(description + " requires a verified SHA-256");
    }
}