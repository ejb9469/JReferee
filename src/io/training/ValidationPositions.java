package io.training;

import agents.encoding.*;
import domain.*;
import game.*;
import io.catalog.SourceFingerprints;
import io.json.*;
import phase.UnitId;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Fixed validation boards, selected without observed orders or model scores.
 * Each stratum retains at most one board from each source game.
 */
public final class ValidationPositions {

    public static final String SCHEMA = "jreferee-validation-positions-v1";
    private static final JsonAccess JSON = new JsonAccess((path, message) -> new IllegalArgumentException(path + ": " + message));

    public record Position(String source, String gameKey, String communication, String rank, GameState_Movement state) {
        public Position {
            if (source.isBlank() || gameKey.isBlank() || source.contains("\n") || gameKey.contains("\n"))
                throw new IllegalArgumentException("Invalid source game");
            Objects.requireNonNull(state, "state");
        }
        public String game() { return source + "\n" + gameKey; }
        public String era() { return state.moment().year() <= 1903 ? "EARLY" : state.moment().year() <= 1907 ? "MIDDLE" : "LATE"; }
        public String size() {
            int largest = state.aliveNations().stream().mapToInt(power -> (int) state.board().locations().keySet()
                    .stream().filter(unit -> unit.owner() == power).count()).max().orElse(0);
            return largest <= 4 ? "U1_4" : largest <= 8 ? "U5_8" : "U9_PLUS";
        }
        public String stratum() { return state.moment().gamePhase().name() + "/" + era() + "/" + size(); }
        public List<String> groups() {
            return List.of("ALL", "PHASE:" + state.moment().gamePhase().name(), "ERA:" + era(),
                    "MAX_UNITS:" + size(), "CELL:" + stratum());
        }
    }

    public record Selection(String datasetHash, String validationHash, long seed, int perStratum, long examples,
                            long boards, long excluded, Map<String, Long> availableBoards,
                            Map<String, Long> availableGames, List<Position> positions) {
        public Selection {
            availableBoards = Collections.unmodifiableMap(new TreeMap<>(availableBoards));
            availableGames = Collections.unmodifiableMap(new TreeMap<>(availableGames));
            positions = List.copyOf(positions);
            if (!datasetHash.matches("[0-9a-f]{64}") || !validationHash.matches("[0-9a-f]{64}")
                    || perStratum < 1 || perStratum > 10 || boards < 0 || examples < boards || excluded < 0 || excluded > boards
                    || positions.isEmpty() || positions.size() > 18 * perStratum)
                throw new IllegalArgumentException("Invalid validation selection");
            Set<String> ids = new HashSet<>(), cells = new HashSet<>();
            Map<String, Integer> counts = new HashMap<>();
            for (Position position : positions) {
                if (!position.rank().equals(rank(seed, position.source(), position.gameKey(), position.state().moment()))
                        || !eligible(position.state()) || !ids.add(position.rank())
                        || !cells.add(position.game() + "\n" + position.stratum()))
                    throw new IllegalArgumentException("Invalid or repeated selected board");
                if (counts.merge(position.stratum(), 1, Integer::sum) > perStratum)
                    throw new IllegalArgumentException("Stratum quota exceeded");
            }
            if (!availableBoards.keySet().equals(new HashSet<>(strata())) || !availableBoards.keySet().equals(availableGames.keySet())
                    || availableBoards.values().stream().mapToLong(Long::longValue).sum() != boards - excluded)
                throw new IllegalArgumentException("Inconsistent selection coverage");
            for (String cell : strata())
                if (!availableBoards.containsKey(cell) || Math.min(perStratum, availableGames.get(cell)) != counts.getOrDefault(cell, 0)
                        || availableBoards.get(cell) < availableGames.get(cell) || availableGames.get(cell) < 0)
                    throw new IllegalArgumentException("Invalid stratum counts");
        }
    }

    private record Board(Position position, Map<Nation, String> masks) { }
    private ValidationPositions() { }

    public static Selection select(MovementDataset dataset, int perStratum, long seed) throws IOException {
        if (perStratum < 1 || perStratum > 10) throw new IllegalArgumentException("Per-stratum count must be 1..10");
        Map<String, TreeMap<String, Board>> retained = new TreeMap<>();
        Map<String, Long> available = new TreeMap<>(), games = new TreeMap<>();
        for (String cell : strata()) { retained.put(cell, new TreeMap<>()); available.put(cell, 0L); games.put(cell, 0L); }
        Map<GameMoment, Board> current = new HashMap<>();
        Set<String> finished = new HashSet<>();
        String identity = null;
        long examples = 0, boards = 0;
        String digest;
        try (var cursor = dataset.open(EncodingPurpose.VALIDATION)) {
            MovementDataset.Example example;
            while ((example = cursor.next()) != null) {
                String game = example.source() + "\n" + example.gameKey();
                if (!game.equals(identity)) {
                    boards += current.size();
                    offer(current.values(), retained, available, games, perStratum);
                    current.clear();
                    if (!finished.add(game)) throw new IOException("Game records must be contiguous");
                    identity = game;
                }
                MovementInput input = example.input();
                var position = new Position(example.source(), example.gameKey(), example.communicationType(),
                        rank(seed, example.source(), example.gameKey(), input.moment()), restore(input));
                Board board = current.computeIfAbsent(input.moment(), moment -> new Board(position, new EnumMap<>(Nation.class)));
                if (!board.position().state().equals(position.state())
                        || !Objects.equals(board.position().communication(), position.communication())
                        || board.masks().putIfAbsent(input.power(), masks(input)) != null)
                    throw new IOException("Conflicting board, label or duplicate power at " + input.moment());
                examples++;
            }
            digest = cursor.statistics().sha256();
        }
        boards += current.size();
        offer(current.values(), retained, available, games, perStratum);
        if (examples != dataset.examples(EncodingPurpose.VALIDATION) || digest == null)
            throw new IOException("Validation was not fully verified");
        List<Position> selected = new ArrayList<>();
        for (var bucket : retained.values())
            for (Board board : bucket.values()) {
                for (var entry : board.masks().entrySet()) {
                    var live = new MovementEncoding(board.position().state(), entry.getKey()).input();
                    if (!masks(live).equals(entry.getValue())) throw new IOException("Stored and live legal masks differ");
                }
                selected.add(board.position());
            }
        if (selected.isEmpty()) throw new IOException("No eligible validation boards");
        return new Selection(dataset.fingerprint(), digest, seed, perStratum, examples, boards,
                boards - available.values().stream().mapToLong(Long::longValue).sum(), available, games, selected);
    }

    private static void offer(Collection<Board> boards, Map<String, TreeMap<String, Board>> retained,
                              Map<String, Long> available, Map<String, Long> games, int limit) {
        Map<String, Board> best = new HashMap<>();
        for (Board board : boards) {
            Position position = board.position();
            if (!eligible(position.state())) continue;
            String cell = position.stratum();
            available.merge(cell, 1L, Long::sum);
            best.merge(cell, board, (a, b) -> a.position().rank().compareTo(b.position().rank()) <= 0 ? a : b);
        }
        best.forEach((cell, board) -> {
            games.merge(cell, 1L, Long::sum);
            var bucket = retained.get(cell);
            bucket.put(board.position().rank(), board);
            if (bucket.size() > limit) bucket.pollLastEntry();
        });
    }

    public static GameState_Movement restore(MovementInput input) {
        Map<UnitId, Province> units = new LinkedHashMap<>();
        for (var unit : input.units())
            units.put(new UnitId(unit.owner(), unit.type(), unit.location()), unit.location());
        return new GameState_Movement(input.moment(), new BoardState(units, input.centers()));
    }

    private static boolean eligible(GameState_Movement state) {
        if (state.moment().year() < 1901 || state.aliveNations().size() < 2 || state.board().locations().isEmpty()) return false;
        return state.aliveNations().stream().noneMatch(power ->
                state.board().owners().values().stream().filter(owner -> owner == power).count() >= 18);
    }

    private static String masks(MovementInput input) {
        StringBuilder text = new StringBuilder();
        for (var slot : input.slots()) {
            text.append(slot.location().name()).append('\n');
            slot.options().forEach(option -> text.append(option.label()).append('\n'));
        }
        return SourceFingerprints.sha256Hex(text.toString());
    }

    private static String rank(long seed, String source, String game, GameMoment moment) {
        return SourceFingerprints.sha256Hex(seed + "\n" + source + "\n" + game + "\n"
                + moment.year() + "\n" + moment.gamePhase().name());
    }

    private static List<String> strata() {
        List<String> result = new ArrayList<>();
        for (String phase : List.of("SPRING_MOVEMENT", "FALL_MOVEMENT"))
            for (String era : List.of("EARLY", "MIDDLE", "LATE"))
                for (String units : List.of("U1_4", "U5_8", "U9_PLUS")) result.add(phase + "/" + era + "/" + units);
        return result;
    }

    public static void save(Selection selection, Path file) throws IOException {
        Files.writeString(file, toJson(selection) + "\n", StandardOpenOption.CREATE_NEW);
    }

    public static String toJson(Selection selection) {
        StringBuilder out = new StringBuilder("{\"schema\":").append(quote(SCHEMA))
                .append(",\"split\":\"VALIDATION\",\"dataset_sha256\":").append(quote(selection.datasetHash()))
                .append(",\"validation_sha256\":").append(quote(selection.validationHash()))
                .append(",\"seed\":").append(selection.seed()).append(",\"per_stratum\":").append(selection.perStratum())
                .append(",\"examples\":").append(selection.examples()).append(",\"boards\":").append(selection.boards())
                .append(",\"excluded\":").append(selection.excluded()).append(",\"coverage\":[");
        int index = 0;
        for (String cell : strata()) {
            if (index++ > 0) out.append(',');
            out.append('[').append(quote(cell)).append(',').append(selection.availableBoards().get(cell))
                    .append(',').append(selection.availableGames().get(cell)).append(']');
        }
        out.append("],\"positions\":[");
        index = 0;
        for (Position position : selection.positions()) {
            if (index++ > 0) out.append(',');
            out.append("{\"source\":").append(quote(position.source())).append(",\"game\":").append(quote(position.gameKey()))
                    .append(",\"communication\":").append(position.communication() == null ? "null" : quote(position.communication()))
                    .append(",\"rank\":").append(quote(position.rank())).append(",\"year\":").append(position.state().moment().year())
                    .append(",\"phase\":").append(quote(position.state().moment().gamePhase().name())).append(",\"units\":[");
            int unitIndex = 0;
            for (var entry : position.state().board().locations().entrySet()) {
                if (unitIndex++ > 0) out.append(',');
                out.append('[').append(quote(entry.getKey().owner().name())).append(',')
                        .append(quote(entry.getKey().unitType().name())).append(',').append(quote(entry.getValue().name())).append(']');
            }
            out.append("],\"centers\":[");
            int centerIndex = 0;
            for (var entry : new TreeMap<>(position.state().board().owners()).entrySet()) {
                if (centerIndex++ > 0) out.append(',');
                out.append('[').append(quote(entry.getKey().name())).append(',').append(quote(entry.getValue().name())).append(']');
            }
            out.append("]}");
        }
        return out.append("]}").toString();
    }

    public static Selection load(Path file) throws IOException {
        if (Files.size(file) > 16 * 1024 * 1024) throw new IOException("Position set exceeds 16 MiB");
        var root = JSON.object(JsonReader.read(Files.readString(file)), "$");
        if (!SCHEMA.equals(root.get("schema")) || !"VALIDATION".equals(root.get("split")))
            throw new IOException("Unsupported validation position set");
        List<Position> positions = new ArrayList<>();
        for (Object value : array(root, "positions")) {
            var row = JSON.object(value, "position");
            Map<UnitId, Province> units = new LinkedHashMap<>();
            for (Object raw : array(row, "units")) {
                var fields = JSON.array(raw, "unit"); JSON.requireSize(fields, 3, "unit");
                var unit = new MovementInput.Unit(Nation.valueOf(text(fields.get(0))), UnitType.valueOf(text(fields.get(1))),
                        Province.valueOf(text(fields.get(2))));
                if (units.putIfAbsent(new UnitId(unit.owner(), unit.type(), unit.location()), unit.location()) != null)
                    throw new IOException("Duplicate unit");
            }
            Map<Province, Nation> centers = new EnumMap<>(Province.class);
            for (Object raw : array(row, "centers")) {
                var fields = JSON.array(raw, "center"); JSON.requireSize(fields, 2, "center");
                if (centers.putIfAbsent(Province.valueOf(text(fields.get(0))), Nation.valueOf(text(fields.get(1)))) != null)
                    throw new IOException("Duplicate center");
            }
            GameMoment moment = new GameMoment(JSON.integer(row.get("year"), "year"), GamePhase.valueOf(text(row.get("phase"))));
            positions.add(new Position(text(row.get("source")), text(row.get("game")),
                    row.get("communication") == null ? null : text(row.get("communication")), text(row.get("rank")),
                    new GameState_Movement(moment, new BoardState(units, centers))));
        }
        Map<String, Long> boards = new TreeMap<>(), games = new TreeMap<>();
        for (Object value : array(root, "coverage")) {
            var fields = JSON.array(value, "coverage"); JSON.requireSize(fields, 3, "coverage");
            if (boards.putIfAbsent(text(fields.get(0)), number(fields.get(1))) != null) throw new IOException("Duplicate stratum");
            games.put(text(fields.get(0)), number(fields.get(2)));
        }
        return new Selection(text(root.get("dataset_sha256")), text(root.get("validation_sha256")), number(root.get("seed")),
                JSON.integer(root.get("per_stratum"), "per_stratum"), number(root.get("examples")), number(root.get("boards")),
                number(root.get("excluded")), boards, games, positions);
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(); JsonEscaper.appendString(out, text); return out.toString();
    }
    private static String text(Object value) { return JSON.string(value, "$"); }
    private static List<Object> array(Map<String, Object> row, String name) { return JSON.array(JSON.required(row, name, "$"), name); }
    private static long number(Object value) {
        if (!(value instanceof Long number)) throw new IllegalArgumentException("Expected integer");
        return number;
    }
}