package testing.selfcheck;

import agents.encoding.*;
import agents.evaluation.NeuralValueModel;
import domain.*;
import game.*;
import io.training.NeuralValueFiles;
import io.training.ValueDatasetJson;
import io.training.ValueRewardContract;
import phase.UnitId;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Frozen PyTorch predictions versus typed Java inference, without source data.
 */
public final class NeuralValueParitySelfCheck {

    private static final double TOLERANCE = 2e-5;

    private NeuralValueParitySelfCheck() { }

    public static void main(String[] args) throws Exception {

        if (args.length != 1)
            throw new IllegalArgumentException("Usage: NeuralValueParitySelfCheck <export-directory>");

        Path directory = Path.of(args[0]);
        Path modelPath = directory.resolve("model.bin");
        Path receiptPath = directory.resolve("export.json");
        if (Files.size(receiptPath) > 1024 * 1024)
            throw new IOException("Export receipt exceeds 1 MiB");
        Map<String, Object> report = ValueDatasetJson.parse(Files.readString(receiptPath));
        boolean learned = NeuralValueModel.SELF_PLAY_SCHEMA.equals(report.get("schema"));
        require((learned ? NeuralValueModel.SELF_PLAY_SCHEMA : NeuralValueModel.SCHEMA).equals(report.get("schema"))
                && (learned ? NeuralValueModel.SELF_PLAY_MODEL_SCHEMA : "jreferee-value-network-v1")
                .equals(report.get("model_schema"))
                && ValueRewardContract.VERSION.equals(report.get("reward_contract"))
                && Boolean.TRUE.equals(report.get("complete"))
                && Boolean.FALSE.equals(report.get("test_opened")), "Incomplete or incompatible export");

        NeuralValueModel model = NeuralValueModel.load(modelPath);
        require(model.sha256().equals(fileInfo(report, "model.bin").get("sha256"))
                        && Files.size(modelPath) == ValueDatasetJson.count(fileInfo(report, "model.bin"), "bytes"),
                "Model receipt mismatch");
        require(model.checkpointSha256().equals(report.get("checkpoint_sha256"))
                && model.datasetFingerprint().equals(report.get(learned ? "dataset_cohort_sha256" : "dataset_fingerprint"))
                && model.selfPlay().isPresent() == learned
                && NeuralValueModel.specificationSha256().equals(report.get("specification_sha256"))
                && model.epoch() == ValueDatasetJson.count(report, "epoch")
                && model.hidden() == ValueDatasetJson.count(report, "hidden"), "Export identities disagree");
        if (learned)
            selfPlay(report, model);
        else
            require(model.cacheManifestSha256().equals(report.get("cache_manifest_sha256")), "Cache receipt differs");

        byte[] file = NeuralValueFiles.read(directory.resolve("parity.bin"));
        Map<String, Object> info = fileInfo(report, "parity.bin");
        require(NeuralValueFiles.sha256(file).equals(info.get("sha256"))
                && file.length == ValueDatasetJson.count(info, "bytes"), "Parity receipt mismatch");

        double maximum = 0;
        int cases;
        try (DataInputStream input = NeuralValueFiles.payload(file)) {

            require(Arrays.equals(input.readNBytes(8), "JREFVCK1".getBytes(StandardCharsets.US_ASCII)),
                    "Parity magic");
            require(NeuralValueFiles.identity(input).equals(NeuralValueModel.specificationSha256())
                    && NeuralValueFiles.identity(input).equals(model.sha256()), "Parity identities");
            cases = input.readInt();
            require(cases >= 1 && cases <= 266
                    && cases == ValueDatasetJson.count(report, "synthetic_cases")
                    + ValueDatasetJson.count(report, "validation_cases"), "Parity case count");

            for (int index = 0; index < cases; index++) {

                byte[] packed = input.readNBytes(300);
                require(packed.length == 300, "Truncated parity board");
                int phase = input.readInt(), year = input.readInt();
                GamePhase gamePhase = switch (phase) {
                    case 1 -> GamePhase.SPRING_MOVEMENT;
                    case 2 -> GamePhase.FALL_MOVEMENT;
                    case 3 -> GamePhase.WINTER_ADJUSTMENT;
                    default -> throw new IOException("Invalid parity phase");
                };
                GameMoment moment = new GameMoment(year, gamePhase);
                BoardState board = board(packed);
                NeuralValueEncoding encoded = new NeuralValueEncoding(moment, board);
                require(encoded.board().equals(ByteBuffer.wrap(packed))
                                && encoded.phase() == phase && encoded.year() == year,
                        "Python fixture differs from typed Java encoding");

                Map<Nation, Double> prediction = model.predict(moment, board);
                double expectedSum = 0;
                for (Nation nation : NeuralValueEncoding.OUTPUT_NATIONS) {
                    double expected = input.readFloat();
                    double actual = prediction.get(nation);
                    require(Double.isFinite(expected) && expected >= 0 && expected <= 1,
                            "Invalid reference prediction");
                    require(Double.isFinite(actual) && actual >= 0 && actual <= 1,
                            "Invalid Java prediction");
                    double error = Math.abs(actual - expected);
                    maximum = Math.max(maximum, error);
                    require(error <= TOLERANCE,
                            "Case " + index + " " + nation + ": expected " + expected
                                    + ", actual " + actual + ", error " + error);
                    expectedSum += expected;
                }
                require(Math.abs(expectedSum - 1) <= 1e-6
                        && Math.abs(prediction.values().stream().mapToDouble(Double::doubleValue).sum() - 1)
                        <= 1e-12, "Prediction sums");
                require(prediction.equals(model.predict(moment, board)), "Repeated inference changed");

                Map<UnitId, Province> renamed = new LinkedHashMap<>();
                long identity = 0;
                for (var entry : board.locations().entrySet()) {
                    UnitId unit = entry.getKey();
                    renamed.put(new UnitId(new UUID(0, ++identity), unit.owner(), unit.unitType(),
                            Province.Bre), entry.getValue());
                }
                require(prediction.equals(model.predict(moment, new BoardState(renamed, board.owners()))),
                        "Unit birth identity affected prediction");
                expect(UnsupportedOperationException.class,
                        () -> prediction.put(Nation.FRANCE, 1.0));

            }
            require(input.read() == -1, "Trailing parity data");

        }

        expect(IllegalArgumentException.class, () -> model.predict(
                new GameMoment(1903, GamePhase.SPRING_RETREAT), new BoardState(Map.of(), Map.of())));
        expect(IllegalArgumentException.class, () -> model.predict(
                new GameMoment(1903, GamePhase.FALL_RETREAT), new BoardState(Map.of(), Map.of())));
        failures(modelPath);
        System.out.println("Checkpoint SHA-256=" + model.checkpointSha256());
        System.out.println("Model SHA-256=" + model.sha256());
        System.out.println("Dataset fingerprint=" + model.datasetFingerprint());
        System.out.printf(Locale.ROOT,
                "Parity: epoch=%d cases=%d values=%d maximum_absolute_error=%.9g tolerance=%.1g%n",
                model.epoch(), cases, cases * 7, maximum, TOLERANCE);
        System.out.println("NeuralValueParitySelfCheck passed. No TEST data or live evaluator was used.");

    }

    private static Map<String, Object> fileInfo(Map<String, Object> report, String name) {
        return ValueDatasetJson.object(ValueDatasetJson.object(report.get("files")).get(name));
    }

    private static void selfPlay(Map<String, Object> report, NeuralValueModel model) {

        NeuralValueModel.SelfPlay source = model.selfPlay().orElseThrow();
        require(source.trainingReportSha256().equals(report.get("training_report_sha256"))
                        && Long.toString(source.splitSeed()).equals(report.get("self_play_split_seed"))
                        && Boolean.FALSE.equals(report.get("java_parity_verified")),
                "Self-play value training receipt differs");
        Map<String, Object> initial = ValueDatasetJson.object(report.get("initial_value"));
        require(source.initialCheckpointSha256().equals(initial.get("sha256")), "Initial value checkpoint differs");
        Object raw = report.get("datasets");
        require(raw instanceof List<?>, "Missing self-play value datasets");
        List<?> datasets = (List<?>) raw;
        require(datasets.size() == source.datasets().size(), "Self-play value dataset count differs");
        Set<String> fingerprints = new HashSet<>();
        for (int index = 0; index < datasets.size(); index++) {
            Map<String, Object> dataset = ValueDatasetJson.object(datasets.get(index));
            NeuralValueModel.Dataset expected = source.datasets().get(index);
            require(expected.fingerprint().equals(dataset.get("dataset_fingerprint"))
                            && expected.manifestSha256().equals(dataset.get("cache_manifest_sha256")),
                    "Self-play value dataset receipt differs");
            fingerprints.add(expected.fingerprint());
        }
        Object references = report.get("validation_references");
        require(references instanceof List<?>, "Missing held-out value references");
        List<?> rows = (List<?>) references;
        require(rows.size() == ValueDatasetJson.count(report, "validation_cases"), "Held-out value count differs");
        Set<String> unique = new HashSet<>();
        for (Object row : rows) {
            Map<String, Object> reference = ValueDatasetJson.object(row);
            Object fingerprint = reference.get("dataset_fingerprint");
            long position = ValueDatasetJson.count(reference, "position");
            require(fingerprints.contains(fingerprint)
                    && reference.get("game_key") instanceof String key && UUID.fromString(key).toString().equals(key)
                    && reference.get("payload_sha256") instanceof String hash && hash.matches("[0-9a-f]{64}")
                    && unique.add(fingerprint + ":" + position), "Invalid held-out value reference");
        }

    }

    private static BoardState board(byte[] packed) {

        Map<UnitId, Province> units = new LinkedHashMap<>();
        Map<Province, Nation> centers = new EnumMap<>(Province.class);
        for (int row = 0; row < 75; row++) {
            int type = Byte.toUnsignedInt(packed[row * 4]);
            int owner = Byte.toUnsignedInt(packed[row * 4 + 1]);
            int location = Byte.toUnsignedInt(packed[row * 4 + 2]);
            int control = Byte.toUnsignedInt(packed[row * 4 + 3]);
            require(type <= 2 && owner <= 7 && location <= 81 && control <= 7, "Board IDs");
            require((type != 0) == (owner != 0) && (type != 0) == (location != 0), "Partial unit");
            if (type != 0) {
                Province place = NeuralMovementSpec.location(location);
                require(NeuralMovementSpec.boardRow(place) == row, "Wrong occupied row");
                units.put(new UnitId(nation(owner),
                        UnitType.valueOf(NeuralMovementSpec.UNIT_TYPES.get(type)), place), place);
            }
            if (control != 0) {
                Province center = NeuralMovementSpec.boardLocation(row);
                require(center.supplyCenter, "Owned non-center");
                centers.put(center, nation(control));
            }
        }
        return new BoardState(units, centers);

    }

    private static Nation nation(int id) {
        return NeuralValueEncoding.OUTPUT_NATIONS.get(id - 1);
    }

    private static void failures(Path modelPath) throws Exception {

        byte[] original = Files.readAllBytes(modelPath);
        Path path = Files.createTempFile("jreferee-value-corrupt-", ".bin");
        try {
            byte[] corrupt = original.clone();
            corrupt[0] ^= 1;
            Files.write(path, corrupt);
            expect(IOException.class, () -> NeuralValueModel.load(path));
            reseal(corrupt);
            Files.write(path, corrupt);
            expect(IOException.class, () -> NeuralValueModel.load(path));
            Files.write(path, Arrays.copyOf(original, original.length - 1));
            expect(IOException.class, () -> NeuralValueModel.load(path));
            Files.write(path, Arrays.copyOf(original, original.length + 1));
            expect(IOException.class, () -> NeuralValueModel.load(path));

            ByteBuffer header = ByteBuffer.wrap(original);
            header.position(8);
            for (int index = 0; index < 4; index++)
                header.position(header.position() + 4 + header.getInt(header.position()));
            int specHash = header.position();
            boolean learned = new String(original, 0, 8, StandardCharsets.US_ASCII).equals("JREFSVL1");
            int epoch = learned ? specHash + 172 + 64 * header.getInt(specHash + 168) : specHash + 128;
            int hidden = epoch + 4;
            int firstName = hidden + 8;
            int firstRank = firstName + 4 + header.getInt(firstName);
            for (int offset : new int[]{specHash, hidden, firstRank + 4, original.length - 36,
                    learned ? specHash + 64 : specHash}) {
                corrupt = original.clone();
                ByteBuffer.wrap(corrupt).putInt(offset, 0x7fc00000);
                reseal(corrupt);
                Files.write(path, corrupt);
                expect(IOException.class, () -> NeuralValueModel.load(path));
            }
            corrupt = original.clone();
            ByteBuffer.wrap(corrupt).putInt(epoch, learned ? -1 : 0);
            reseal(corrupt);
            Files.write(path, corrupt);
            expect(IOException.class, () -> NeuralValueModel.load(path));
        } finally {
            Files.delete(path);
        }

    }

    private static void reseal(byte[] bytes) {
        byte[] hash = NeuralValueFiles.hash(Arrays.copyOf(bytes, bytes.length - 32));
        System.arraycopy(hash, 0, bytes, bytes.length - 32, 32);
    }

    private interface Checked {
        void run() throws Exception;
    }

    private static void expect(Class<? extends Exception> type, Checked action) throws Exception {
        try {
            action.run();
        } catch (Exception exception) {
            if (type.isInstance(exception))
                return;
            throw exception;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}