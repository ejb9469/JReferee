package testing.selfcheck;

import _app.ValidationProposalBenchmarkApp;
import _app.PolicyProposalExperimentApp;
import agents.encoding.*;
import domain.*;
import game.*;
import io.training.*;
import phase.UnitId;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Validation-only selection, board restoration and source-game statistics.
 * All examples here are temporary synthetic positions.
 */
public final class ValidationBenchmarkSelfCheck {

    private static int checks;
    private ValidationBenchmarkSelfCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args.length != 2)
            throw new IllegalArgumentException("Usage: ValidationBenchmarkSelfCheck [linear-policy.bin gru-model.bin]");
        List<MovementDataset.Example> rows = examples();
        var selected = ValidationPositions.select(dataset(rows), 2, 7);
        require(selected.positions().size() == 36 && selected.boards() == 54, "All 18 strata with two games each");
        require(selected.examples() == 162 && selected.excluded() == 0, "Board deduplication across deciding powers");
        for (String cell : selected.availableBoards().keySet()) {
            require(selected.availableBoards().get(cell) == 3 && selected.availableGames().get(cell) == 3, "Coverage counts");
            require(selected.positions().stream().filter(position -> position.stratum().equals(cell))
                    .map(ValidationPositions.Position::game).distinct().count() == 2, "Different games within each stratum");
        }
        List<MovementDataset.Example> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);
        require(ValidationPositions.toJson(selected).equals(ValidationPositions.toJson(
                ValidationPositions.select(dataset(reversed), 2, 7))), "Selection ignores input game/power ordering");
        List<MovementDataset.Example> changedActions = rows.stream().map(row -> new MovementDataset.Example(row.source(), row.gameKey(),
                row.communicationType(), row.input(), row.input().slots().stream().map(slot -> slot.options().size() - 1).toList())).toList();
        require(ValidationPositions.toJson(selected).equals(ValidationPositions.toJson(
                ValidationPositions.select(dataset(changedActions), 2, 7))), "Human actions do not influence selection");
        require(!ValidationPositions.toJson(selected).equals(ValidationPositions.toJson(
                ValidationPositions.select(dataset(rows), 2, 8))), "Selection seed changes the sample");
        Set<String> ranks = new HashSet<>(selected.positions().stream().map(ValidationPositions.Position::rank).toList());
        require(ValidationPositions.select(dataset(rows), 1, 7).positions().stream().allMatch(position -> ranks.contains(position.rank())),
                "Smaller quotas retain the leading ranks");
        for (var position : selected.positions()) {
            var input = new MovementEncoding(position.state(), Nation.RUSSIA).input();
            require(input.units().stream().anyMatch(unit -> unit.location() == Province.StpSC)
                    && input.units().stream().anyMatch(unit -> unit.location() == Province.SpaNC), "Exact fleet coasts");
            require(position.state().aliveNations().contains(Nation.TURKEY)
                    && new MovementEncoding(position.state(), Nation.TURKEY).input().slots().isEmpty(), "Center-only power remains alive");
            require(ValidationPositions.restore(input).equals(position.state()), "Restored complete board");
        }
        require(rows.stream().map(MovementDataset.Example::communicationType).anyMatch(Objects::isNull)
                && rows.stream().anyMatch(row -> "Full".equals(row.communicationType())), "Press and unknown fixtures included");
        Path temporary = Files.createTempDirectory("jreferee-validation-positions-");
        try {
            Path file = temporary.resolve("positions.json");
            ValidationPositions.save(selected, file);
            require(ValidationPositions.toJson(ValidationPositions.load(file)).equals(ValidationPositions.toJson(selected)), "Saved position-set round trip");
            expect(FileAlreadyExistsException.class, () -> ValidationPositions.save(selected, file));
            Path corrupt = temporary.resolve("corrupt.json");
            Files.writeString(corrupt, Files.readString(file).replace("\"VALIDATION\"", "\"TEST\""));
            expect(IOException.class, () -> ValidationPositions.load(corrupt));
            Files.writeString(corrupt, Files.readString(file).replaceFirst("\"rank\":\"[0-9a-f]{64}\"", "\"rank\":\"invalid\""));
            expect(IllegalArgumentException.class, () -> ValidationPositions.load(corrupt));
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        expect(IOException.class, () -> ValidationPositions.select(dataset(List.of()), 2, 7));
        expect(IllegalArgumentException.class, () -> ValidationPositions.select(dataset(rows), 0, 7));
        List<MovementDataset.Example> duplicates = new ArrayList<>(rows);
        duplicates.add(1, rows.getFirst());
        expect(IOException.class, () -> ValidationPositions.select(dataset(duplicates), 2, 7));
        var original = rows.getFirst();
        var second = rows.get(1);
        var conflicting = new MovementDataset.Example(second.source(), second.gameKey(), "changed", second.input(), second.action());
        expect(IOException.class, () -> ValidationPositions.select(dataset(List.of(original, conflicting)), 1, 7));
        expect(IOException.class, () -> ValidationPositions.select(dataset(List.of(original, rows.get(54), second)), 1, 7));
        Map<Province, Nation> changedCenters = new EnumMap<>(original.input().centers());
        changedCenters.put(Province.Lon, Nation.FRANCE);
        var changed = new MovementInput(second.input().moment(), second.input().power(), second.input().units(), changedCenters, second.input().slots());
        expect(IOException.class, () -> ValidationPositions.select(dataset(List.of(original,
                new MovementDataset.Example(second.source(), second.gameKey(), second.communicationType(), changed, second.action()))), 1, 7));
        expect(IllegalArgumentException.class, () -> new ValidationPositions.Selection(selected.datasetHash(), selected.validationHash(),
                selected.seed(), selected.perStratum(), selected.examples(), selected.boards(), selected.excluded(),
                selected.availableBoards(), selected.availableGames(), selected.positions().subList(1, selected.positions().size())));
        List<MovementInput.Slot> masks = original.input().slots().stream().map(slot -> new MovementInput.Slot(slot.location(),
                slot.options().stream().filter(option -> option.type() == OrderType.HOLD).toList())).toList();
        var restricted = new MovementInput(original.input().moment(), original.input().power(), original.input().units(), original.input().centers(), masks);
        var invalid = new MovementDataset.Example(original.source(), original.gameKey(), original.communicationType(), restricted,
                Collections.nCopies(masks.size(), 0));
        expect(IOException.class, () -> ValidationPositions.select(dataset(List.of(invalid)), 1, 7));
        Map<Province, Nation> won = new EnumMap<>(Province.class);
        Arrays.stream(Province.values()).filter(place -> place.supplyCenter && place.parent == null).limit(18)
                .forEach(place -> won.put(place, Nation.ENGLAND));
        var terminal = new MovementInput(original.input().moment(), original.input().power(), original.input().units(), won, original.input().slots());
        var terminalExample = new MovementDataset.Example("DIPLOBN", "terminal", null, terminal, original.action());
        List<MovementDataset.Example> withTerminal = new ArrayList<>(rows);
        withTerminal.add(terminalExample);
        require(ValidationPositions.select(dataset(withTerminal), 2, 7).excluded() == 1, "Solo-threshold boards excluded explicitly");
        var estimate = ValidationProposalBenchmarkApp.grouped(Map.of("a", Collections.nCopies(100, 1.0), "b", List.of(3.0)));
        close(estimate.mean(), 2, "Games have equal weight regardless of board/repetition count");
        close(estimate.standardError(), 1, "Uncertainty across game means");
        require(estimate.games() == 2, "Cluster count");
        var singleton = ValidationProposalBenchmarkApp.grouped(Map.of("a", List.of(1.0, 2.0, 3.0)));
        require(singleton.standardError() == null && singleton.mean() == 2, "Single-game uncertainty is unavailable");
        expect(IllegalArgumentException.class, () -> ValidationProposalBenchmarkApp.grouped(Map.of()));
        expect(IllegalArgumentException.class, () -> ValidationProposalBenchmarkApp.grouped(Map.of("a", List.of(Double.NaN))));
        if (args.length == 2) experiment(ValidationPositions.restore(original.input()), args);
        System.out.println("ValidationBenchmarkSelfCheck passed: " + checks + " checks");
    }

    private static void experiment(GameState_Movement state, String[] models) throws Exception {
        String[] settings = {"--proposal-model", models[0], "--anchor-model", models[0], "--gru-model", models[1],
                "--candidates", "2", "--iterations", "2", "--opponent-samples", "2", "--rollout-candidates", "2"};
        try (var out = new PrintStream(OutputStream.nullOutputStream())) {
            var result = PolicyProposalExperimentApp.runPosition(state, settings, out);
            require(result.root().equals(state) && result.observations().size() == 28, "Only the four living powers are evaluated");
            require(result.observations().stream().filter(row -> row.power() == Nation.TURKEY)
                    .allMatch(row -> row.candidates() == 1), "Center-only power has one empty action");
            for (var cost : result.work()) {
                long candidates = result.observations().stream().filter(row -> row.method() == cost.method())
                        .mapToLong(PolicyProposalExperimentApp.Observation::candidates).sum();
                require(cost.fresh().requests() == 2 * candidates && cost.fresh().failures() == 0
                        && cost.search().failures() == 0, "Arbitrary-root work accounting");
            }
            var opening = PolicyProposalExperimentApp.run(settings, out);
            var supplied = PolicyProposalExperimentApp.runPosition(opening.root(), settings, out);
            for (int i = 0; i < opening.observations().size(); i++) {
                var a = opening.observations().get(i); var b = supplied.observations().get(i);
                require(a.method() == b.method() && a.power() == b.power() && a.candidates() == b.candidates(), "Identical fixture cells");
                close(a.freshSearch(), b.freshSearch(), "Supplied opening preserves fixture search values");
                close(a.freshAnchor(), b.freshAnchor(), "Supplied opening preserves anchor values");
            }
            expect(IllegalArgumentException.class,
                    () -> PolicyProposalExperimentApp.runPosition(state, new String[]{"--position", "opening"}, out));
        }
    }

    private static List<MovementDataset.Example> examples() {
        List<MovementDataset.Example> rows = new ArrayList<>();
        for (int game = 0; game < 3; game++)
            for (int year : new int[]{1901, 1905, 1911})
                for (GamePhase phase : List.of(GamePhase.SPRING_MOVEMENT, GamePhase.FALL_MOVEMENT))
                    for (int size : new int[]{2, 6, 9}) {
                        Map<UnitId, Province> units = new LinkedHashMap<>();
                        units.put(new UnitId(Nation.ENGLAND, UnitType.ARMY, Province.Yor), Province.Yor);
                        units.put(new UnitId(Nation.ENGLAND, UnitType.FLEET, Province.NTH), Province.NTH);
                        units.put(new UnitId(Nation.RUSSIA, UnitType.FLEET, Province.StpSC), Province.StpSC);
                        units.put(new UnitId(Nation.FRANCE, UnitType.FLEET, Province.SpaNC), Province.SpaNC);
                        int added = 2;
                        for (Province place : Province.values()) {
                            if (added == size) break;
                            if (place != Province.Swi && place.parent == null && place.geography != Geography.WATER
                                    && units.values().stream().noneMatch(p -> Province.canonical(p) == place)) {
                                units.put(new UnitId(Nation.ENGLAND, UnitType.ARMY, place), place);
                                added++;
                            }
                        }
                        int adjustedYear = year + (size == 2 ? 0 : size == 6 ? 1 : 2);
                        var state = new GameState_Movement(new GameMoment(adjustedYear, phase), new BoardState(units,
                                Map.of(Province.Lon, Nation.ENGLAND, Province.Stp, Nation.RUSSIA,
                                        Province.Bre, Nation.FRANCE, Province.Smy, Nation.TURKEY)));
                        for (Nation power : List.of(Nation.ENGLAND, Nation.RUSSIA, Nation.FRANCE)) {
                            MovementInput input = new MovementEncoding(state, power).input();
                            rows.add(new MovementDataset.Example("DIPLOBN", "fixture-" + game,
                                    game == 0 ? null : game == 1 ? "Full" : "None", input, Collections.nCopies(input.slots().size(), 0)));
                        }
                    }
        return rows;
    }

    private static MovementDataset dataset(List<MovementDataset.Example> rows) {
        return new MovementDataset() {
            @Override public Cursor open(EncodingPurpose split) {
                require(split == EncodingPurpose.VALIDATION, "Only VALIDATION may be opened");
                return new Cursor() {
                    private int index;
                    private boolean ended;
                    @Override public Example next() {
                        if (index == rows.size()) { ended = true; return null; }
                        return rows.get(index++);
                    }
                    @Override public Statistics statistics() {
                        return new Statistics(index, 0, 0, 0, 0, 0, 0, ended ? "b".repeat(64) : null);
                    }
                    @Override public void close() { }
                };
            }
            @Override public long examples(EncodingPurpose split) {
                require(split == EncodingPurpose.VALIDATION, "Only validation counts are used"); return rows.size();
            }
            @Override public String fingerprint() { return "a".repeat(64); }
        };
    }

    private static void close(double actual, double expected, String message) { require(Math.abs(actual - expected) < 1e-12, message); }
    private interface Checked { void run() throws Exception; }
    private static void expect(Class<? extends Exception> type, Checked action) {
        try { action.run(); }
        catch (Exception exception) {
            if (!type.isInstance(exception)) throw new AssertionError("Unexpected exception", exception);
            checks++; return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message); checks++;
    }
}