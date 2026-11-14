package io.training;

import agents.encoding.EncodingPurpose;
import agents.encoding.NeuralValueEncoding;
import domain.Nation;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Streams TRAIN and VALIDATION into fixed-width numeric arrays.
 * Targets are game-level; offsets associate each position with its game.
 */
public final class NeuralValueCache {


    // Format \\

    public static final String SCHEMA = "jreferee-value-cache-v1";

    private static final List<String> FILES = List.of(
            "board.u8", "context.i32", "targets.f32", "game_offsets.i64", "games.jsonl");

    private NeuralValueCache() { }


    // Export \\

    public static String write(
            ValueDataset dataset,
            Path output,
            Consumer<String> progress
    ) throws IOException {

        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(progress, "progress");
        output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        String fingerprint = dataset.fingerprint();
        requireHash(fingerprint);
        Files.createDirectory(output);

        Files.writeString(output.resolve("specification.json"),
                NeuralValueEncoding.specificationJson(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);

        Map<String, Object> splits = new LinkedHashMap<>();

        for (EncodingPurpose split : List.of(EncodingPurpose.TRAIN, EncodingPurpose.VALIDATION)) {

            Map<String, Object> report = writeSplit(dataset, split, output, progress);
            splits.put(split.name(), report);
            progress.accept(split + ": " + ValueDatasetJson.json(report));

        }

        if (!fingerprint.equals(dataset.fingerprint()))
            throw new IOException("Dataset identity changed during conversion");

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", SCHEMA);
        manifest.put("complete", true);
        manifest.put("encoding", NeuralValueEncoding.SCHEMA);
        manifest.put("reward_contract", ValueRewardContract.VERSION);
        manifest.put("dataset_fingerprint", fingerprint);
        manifest.put("byte_order", "LITTLE_ENDIAN");
        manifest.put("test_exported", false);
        manifest.put("specification", describe(output.resolve("specification.json")));
        manifest.put("splits", splits);

        String text = ValueDatasetJson.json(manifest) + "\n";
        Path pending = output.resolve("manifest.pending");
        Files.writeString(pending, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(pending, output.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE);
        return text;

    }

    private static Map<String, Object> writeSplit(
            ValueDataset dataset,
            EncodingPurpose split,
            Path output,
            Consumer<String> progress
    ) throws IOException {

        Path directory = output.resolve(split.name().toLowerCase(Locale.ROOT));
        Files.createDirectory(directory);
        long games = 0, positions = 0;
        ValueDataset.Statistics reading;
        ValueRewardContract contract = ValueRewardContract.defaults();
        ByteBuffer context = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer target = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer offset = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        byte[] board = new byte[NeuralValueEncoding.BOARD_BYTES];

        try (OutputStream boards = stream(directory, "board.u8");
             OutputStream contexts = stream(directory, "context.i32");
             OutputStream targets = stream(directory, "targets.f32");
             OutputStream offsets = stream(directory, "game_offsets.i64");
             BufferedWriter metadata = Files.newBufferedWriter(directory.resolve("games.jsonl"),
                     StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
             ValueDataset.Cursor cursor = dataset.open(split)) {

            offsets.write(offset.array());
            ValueDataset.GameExample game;

            while ((game = cursor.next()) != null) {

                Map<Nation, Double> rewards = game.assess(contract).rewards()
                        .orElseThrow(() -> new IOException("Rejected outcome in value dataset"));

                target.clear();
                for (Nation nation : NeuralValueEncoding.OUTPUT_NATIONS)
                    target.putFloat(rewards.get(nation).floatValue());
                targets.write(target.array());

                long start = positions;

                for (ValueDataset.Position position : game.positions()) {

                    NeuralValueEncoding input = new NeuralValueEncoding(
                            position.moment(), position.board());
                    input.board().get(board);
                    boards.write(board);
                    context.clear();
                    context.putInt(input.phase()).putInt(input.year());
                    contexts.write(context.array());
                    positions = Math.addExact(positions, 1);

                }

                offset.clear();
                offset.putLong(positions);
                offsets.write(offset.array());

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", game.source());
                row.put("game_key", game.gameKey());
                row.put("payload_sha256", game.payloadSha256());
                row.put("communication_type", game.communicationType().orElse(null));
                row.put("start", start);
                row.put("end", positions);
                metadata.write(ValueDatasetJson.json(row));
                metadata.write('\n');
                games = Math.addExact(games, 1);

                if (games % 100 == 0)
                    progress.accept(split + ": games=" + games + " positions=" + positions);

            }

            reading = cursor.statistics();

        }

        String sourceHash = reading.sha256().orElseThrow(
                () -> new IOException("Source cursor did not verify EOF"));
        requireHash(sourceHash);

        if (games != dataset.games(split) || positions != dataset.positions(split)
                || games != reading.games() || positions != reading.positions())
            throw new IOException("Cache counts differ from verified source: " + split);

        Map<String, Object> files = new LinkedHashMap<>();
        for (String name : FILES)
            files.put(name, describe(directory.resolve(name)));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("games", games);
        report.put("positions", positions);
        report.put("source_sha256", sourceHash);
        report.put("files", files);
        return report;

    }


    // Files \\

    private static OutputStream stream(Path directory, String name) throws IOException {
        return new BufferedOutputStream(Files.newOutputStream(
                directory.resolve(name), StandardOpenOption.CREATE_NEW));
    }

    private static Map<String, Object> describe(Path path) throws IOException {
        return Map.of("bytes", Files.size(path), "sha256", ValueDatasetJson.hash(path));
    }

    private static void requireHash(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IOException("Missing or invalid SHA-256 identity");
    }

}