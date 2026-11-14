package testing.selfcheck;

import agents.encoding.*;
import domain.*;
import game.*;
import io.catalog.SourceFingerprints;
import io.training.*;
import misc.scoring.ScoringSystem;
import phase.UnitId;

import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Synthetic encoding, cache layout and failed-conversion checks.
 * The fake dataset never opens historical game payloads.
 */
public final class NeuralValueSelfCheck {

    private NeuralValueSelfCheck() { }

    public static void main(String[] args) throws Exception {

        encoding();
        Path root = Files.createTempDirectory("jreferee-value-check-");

        try {

            caches(root);

        } finally {

            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }

        }

        System.out.println("NeuralValueSelfCheck passed");

    }


    // Inputs \\

    private static BoardState board(Province coast, int built) {

        UnitId unit = UnitId.newlyBuilt(built, Nation.FRANCE, UnitType.FLEET, Province.Bre);
        return new BoardState(Map.of(unit, coast), Map.of(Province.Spa, Nation.RUSSIA));

    }

    private static NeuralValueEncoding input(Province coast, int built, GamePhase phase) {
        return new NeuralValueEncoding(new GameMoment(1903, phase), board(coast, built));
    }

    private static void encoding() throws Exception {

        NeuralValueEncoding south = input(Province.SpaSC, 1901, GamePhase.FALL_MOVEMENT);
        NeuralValueEncoding north = input(Province.SpaNC, 1901, GamePhase.FALL_MOVEMENT);
        NeuralValueEncoding rebuilt = input(Province.SpaSC, 1902, GamePhase.FALL_MOVEMENT);
        int row = NeuralMovementSpec.boardRow(Province.Spa) * 4;

        require(south.board().equals(rebuilt.board()), "Build identity leaked into inputs");
        require(!south.board().equals(north.board()), "Exact fleet coast was lost");
        require(south.board().get(row + 1) == NeuralMovementSpec.powerId(Nation.FRANCE)
                        && south.board().get(row + 3) == NeuralMovementSpec.powerId(Nation.RUSSIA),
                "Occupation and center ownership were conflated");
        require(south.phase() == 2 && south.year() == 1903
                && south.normalizedYear() == 0.1f, "Incorrect Fall context");
        require(input(Province.SpaSC, 1901, GamePhase.WINTER_ADJUSTMENT).phase() == 3,
                "Winter context was not retained");

        ByteBuffer view = south.board();
        view.position(10);
        require(south.board().position() == 0, "Shared buffer position");
        expect(ReadOnlyBufferException.class, () -> south.board().put(0, (byte) 1));
        expect(IllegalArgumentException.class,
                () -> input(Province.SpaSC, 1901, GamePhase.SPRING_RETREAT));
        expect(IllegalArgumentException.class,
                () -> input(Province.SpaSC, 1901, GamePhase.FALL_RETREAT));

    }


    // Cache bytes and failures \\

    private static ValueDataset.GameExample game(String source, String key) {

        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            counts.put(nation, 0);
        counts.put(Nation.FRANCE, 3);
        counts.put(Nation.RUSSIA, 4);

        ValueOutcome outcome = new ValueOutcome(
                ValueOutcome.Termination.SOURCE_COMPLETED,
                ValueOutcome.Evidence.SOURCE_RECORDED,
                new GameMoment(1904, GamePhase.SPRING_MOVEMENT),
                counts, Optional.of("Unrecognized bookkeeping label"), Optional.empty(),
                "Synthetic completed outcome"
        );
        List<ValueDataset.Position> positions = List.of(
                new ValueDataset.Position(new GameMoment(1903, GamePhase.FALL_MOVEMENT),
                        board(Province.SpaSC, 1901), ScoringSystem.SUM_OF_SQUARES),
                new ValueDataset.Position(new GameMoment(1903, GamePhase.WINTER_ADJUSTMENT),
                        board(Province.SpaNC, 1901), ScoringSystem.SUM_OF_SQUARES)
        );
        return new ValueDataset.GameExample(source, key, Optional.empty(),
                SourceFingerprints.sha256Hex(source + ":" + key), positions, outcome);

    }

    private static void caches(Path root) throws Exception {

        Path output = root.resolve("valid");
        String manifest = NeuralValueCache.write(new Fixture(true, false), output, line -> { });
        require(Files.readString(output.resolve("manifest.json")).equals(manifest),
                "Completion receipt differs");

        Path train = output.resolve("train");
        require(Files.size(train.resolve("board.u8")) == 1200, "Wrong board shape");
        require(Files.size(train.resolve("context.i32")) == 32, "Wrong context shape");
        require(Files.size(train.resolve("targets.f32")) == 56, "Targets must be per game");

        ByteBuffer offsets = bytes(train.resolve("game_offsets.i64"));
        require(offsets.getLong() == 0 && offsets.getLong() == 2
                && offsets.getLong() == 4 && !offsets.hasRemaining(), "Wrong game offsets");

        ByteBuffer context = bytes(train.resolve("context.i32"));
        require(context.getInt() == 2 && context.getInt() == 1903
                && context.getInt() == 3 && context.getInt() == 1903, "Wrong LE context");

        ByteBuffer targets = bytes(train.resolve("targets.f32"));
        float[] expected = { 0, 0, 0.36f, 0, 0, 0.64f, 0 };
        for (int game = 0; game < 2; game++)
            for (float value : expected)
                require(targets.getFloat() == value, "Wrong SoS target or power order");

        byte[] boards = Files.readAllBytes(train.resolve("board.u8"));
        require(Arrays.equals(Arrays.copyOfRange(boards, 0, 600),
                Arrays.copyOfRange(boards, 600, 1200)), "Provider identity leaked into inputs");
        require(Files.readAllLines(train.resolve("games.jsonl")).size() == 2,
                "Metadata must have one row per game");
        require(!Files.exists(output.resolve("test")), "TEST output was created");
        require(Files.size(output.resolve("validation/game_offsets.i64")) == 8
                        && Files.size(output.resolve("validation/targets.f32")) == 0,
                "Empty validation split is malformed");

        Map<String, Object> report = ValueDatasetJson.parse(manifest);
        Map<String, Object> split = ValueDatasetJson.object(
                ValueDatasetJson.object(report.get("splits")).get("TRAIN"));
        Map<String, Object> files = ValueDatasetJson.object(split.get("files"));

        for (Map.Entry<String, Object> entry : files.entrySet()) {
            Map<String, Object> info = ValueDatasetJson.object(entry.getValue());
            require(ValueDatasetJson.hash(train.resolve(entry.getKey())).equals(info.get("sha256"))
                            && Files.size(train.resolve(entry.getKey())) == ValueDatasetJson.count(info, "bytes"),
                    "File receipt differs from bytes");
        }

        expect(FileAlreadyExistsException.class,
                () -> NeuralValueCache.write(new Fixture(true, false), output, line -> { }));

        Path unverified = root.resolve("unverified");
        expect(IOException.class,
                () -> NeuralValueCache.write(new Fixture(false, false), unverified, line -> { }));
        require(!Files.exists(unverified.resolve("manifest.json")), "Unverified EOF was certified");

        Path mismatch = root.resolve("mismatch");
        expect(IOException.class,
                () -> NeuralValueCache.write(new Fixture(true, true), mismatch, line -> { }));
        require(!Files.exists(mismatch.resolve("manifest.json")), "Count mismatch was certified");

    }

    private static ByteBuffer bytes(Path path) throws IOException {
        return ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
    }


    // Synthetic cursor \\

    private record Fixture(boolean verified, boolean wrongCount) implements ValueDataset {

        @Override
        public Cursor open(EncodingPurpose split) {

            if (split == EncodingPurpose.TEST)
                throw new AssertionError("TEST was opened");

            List<GameExample> values = split == EncodingPurpose.TRAIN
                    ? List.of(game("DIPLOBN", "one"), game("FUTURE_PROVIDER", "two")) : List.of();

            return new Cursor() {

                private int index;
                private boolean ended;

                @Override
                public GameExample next() {
                    if (this.index == values.size()) {
                        this.ended = true;
                        return null;
                    }
                    return values.get(this.index++);
                }

                @Override
                public Statistics statistics() {
                    return new Statistics(this.index, this.index * 2L, 0,
                            this.ended && verified ? Optional.of(fingerprint()) : Optional.empty());
                }

                @Override
                public void close() { }

            };

        }

        @Override
        public long games(EncodingPurpose split) {
            return split == EncodingPurpose.TRAIN ? (this.wrongCount ? 3 : 2) : 0;
        }

        @Override
        public long positions(EncodingPurpose split) {
            return split == EncodingPurpose.TRAIN ? 4 : 0;
        }

        @Override
        public String fingerprint() {
            return SourceFingerprints.sha256Hex("synthetic source receipt");
        }

    }

    private interface Checked {
        void run() throws Exception;
    }

    private static void expect(Class<? extends Throwable> type, Checked action) throws Exception {
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