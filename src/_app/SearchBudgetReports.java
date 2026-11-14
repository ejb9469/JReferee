package _app;

import agents.execution.ProposalInspector.Method;
import domain.Nation;
import io.training.ValidationPositions.Position;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static _app.PolicyProposalBenchmarkApp.*;

/**
 * Source-game means and paired comparisons for fixed-pool checkpoints.
 */
final class SearchBudgetReports {

    record Row(Position position, int repetition, Method method, int iterations, Nation power,
               double search, double anchor, double best) { }
    private record Configuration(Method method, int iterations) { }
    private record Key(String group, String power, Configuration configuration) { }
    private static final class Totals {
        private final Set<String> boards = new HashSet<>();
        private int rows;
        private double search, anchor, best;
        void add(String board, double s, double a, double b) {
            boards.add(board); rows++; search += s; anchor += a; best += b;
        }
    }

    private SearchBudgetReports() { }

    static void write(List<Row> rows, List<Method> methods, List<Integer> checkpoints, Path output) throws IOException {
        Map<Key, Map<String, Totals>> totals = new LinkedHashMap<>();
        Map<String, List<Row>> boards = new LinkedHashMap<>();
        Set<String> unique = new HashSet<>();
        for (Row row : rows) {
            String key = row.position().rank() + ":" + row.repetition() + ":" + row.method() + ":" + row.iterations();
            if (!unique.add(key + ":" + row.power())) throw new IllegalStateException("Repeated checkpoint observation");
            boards.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            add(totals, row, row.power().name(), row.search(), row.anchor(), row.best());
        }
        for (List<Row> board : boards.values()) {
            Row first = board.getFirst();
            if (!new HashSet<>(board.stream().map(Row::power).toList()).equals(first.position().state().aliveNations()))
                throw new IllegalStateException("Incomplete board checkpoint");
            add(totals, first, "ALL", board.stream().mapToDouble(Row::search).average().orElseThrow(),
                    board.stream().mapToDouble(Row::anchor).average().orElseThrow(),
                    board.stream().mapToDouble(Row::best).average().orElseThrow());
        }
        List<Configuration> configurations = new ArrayList<>();
        for (Method method : methods)
            for (int iterations : checkpoints) configurations.add(new Configuration(method, iterations));
        try (var summary = writer(output.resolve("summary.csv")); var paired = writer(output.resolve("paired.csv"));
             var games = writer(output.resolve("games.csv"))) {
            csv(summary, "group", "power", "method", "iterations", "games", "boards", "board_seed_rows",
                    "fresh_search_mean", "game_se", "fresh_anchor_mean", "fresh_best_mean");
            csv(paired, "group", "power", "method", "iterations", "baseline_method", "baseline_iterations",
                    "games", "mean_delta", "game_se", "positive_games", "zero_games", "negative_games");
            csv(games, "group", "power", "method", "iterations", "source", "game", "boards", "board_seed_rows", "fresh_search_mean");
            for (var entry : totals.entrySet()) {
                Key key = entry.getKey();
                var group = entry.getValue();
                Map<String, List<Double>> searches = new TreeMap<>(), anchors = new TreeMap<>(), best = new TreeMap<>();
                for (var game : group.entrySet()) {
                    Totals t = game.getValue();
                    String[] identity = game.getKey().split("\n", -1);
                    searches.put(game.getKey(), List.of(t.search / t.rows));
                    anchors.put(game.getKey(), List.of(t.anchor / t.rows));
                    best.put(game.getKey(), List.of(t.best / t.rows));
                    csv(games, key.group(), key.power(), key.configuration().method(), key.configuration().iterations(),
                            identity[0], identity[1], t.boards.size(), t.rows, t.search / t.rows);
                }
                var estimate = ValidationProposalBenchmarkApp.grouped(searches);
                csv(summary, key.group(), key.power(), key.configuration().method(), key.configuration().iterations(),
                        estimate.games(), group.values().stream().mapToInt(t -> t.boards.size()).sum(),
                        group.values().stream().mapToInt(t -> t.rows).sum(), estimate.mean(), estimate.standardError(),
                        ValidationProposalBenchmarkApp.grouped(anchors).mean(), ValidationProposalBenchmarkApp.grouped(best).mean());
                for (Configuration baseline : configurations) {
                    if (baseline.equals(key.configuration())) break;
                    if (baseline.method() != key.configuration().method() && baseline.iterations() != key.configuration().iterations())
                        continue;
                    var reference = totals.get(new Key(key.group(), key.power(), baseline));
                    if (reference == null || !reference.keySet().equals(group.keySet()))
                        throw new IllegalStateException("Unpaired source games");
                    Map<String, List<Double>> differences = new TreeMap<>();
                    int positive = 0, zero = 0;
                    for (String game : group.keySet()) {
                        Totals a = group.get(game), b = reference.get(game);
                        if (a.rows != b.rows || !a.boards.equals(b.boards)) throw new IllegalStateException("Unpaired boards or repetitions");
                        double delta = a.search / a.rows - b.search / b.rows;
                        differences.put(game, List.of(delta));
                        if (delta > TIE_TOLERANCE) positive++;
                        if (Math.abs(delta) <= TIE_TOLERANCE) zero++;
                    }
                    var delta = ValidationProposalBenchmarkApp.grouped(differences);
                    csv(paired, key.group(), key.power(), key.configuration().method(), key.configuration().iterations(),
                            baseline.method(), baseline.iterations(), delta.games(), delta.mean(), delta.standardError(),
                            positive, zero, delta.games() - positive - zero);
                }
            }
        }
    }

    private static void add(Map<Key, Map<String, Totals>> totals, Row row, String power, double search, double anchor, double best) {
        for (String group : row.position().groups())
            totals.computeIfAbsent(new Key(group, power, new Configuration(row.method(), row.iterations())), ignored -> new TreeMap<>())
                    .computeIfAbsent(row.position().game(), ignored -> new Totals()).add(row.position().rank(), search, anchor, best);
    }
}