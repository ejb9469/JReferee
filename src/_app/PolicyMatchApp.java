package _app;

import agents.evaluation.NeuralValueModel;
import agents.execution.*;
import domain.Nation;
import game.GameMoment;
import game.StandardGameFactory;
import io.json.JsonReader;
import io.training.*;
import misc.scoring.ScoringSystem;
import phase.movement.MovementProcessor;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

import static io.training.SelfPlayFiles.object;

/**
 * Paired one-candidate/six-reference games with an all-reference control.
 * A match changes either the proposal or the neural value, never both.
 * Complete power rotations, not turns or seats, supply uncertainty estimates.
 */
public final class PolicyMatchApp {


    // Schedule and statistics \\

    public static final String SCHEMA = "jreferee-policy-match-v1";
    public static final int MAX_ROTATIONS = 32;

    public enum Side { REFERENCE, CANDIDATE }

    public record Match(int pair, int rotation, Nation power, long seed, Side side) { }

    public record Comparison(
            double referenceMean, double candidateMean, double meanDelta,
            List<Double> rotationDeltas, OptionalDouble standardError
    ) {
        public Comparison {
            rotationDeltas = List.copyOf(rotationDeltas);
            Objects.requireNonNull(standardError, "standardError");
        }
    }

    private PolicyMatchApp() { }

    public static List<Match> schedule(int rotations, long firstSeed) {

        if (rotations < 1 || rotations > MAX_ROTATIONS)
            throw new IllegalArgumentException("Rotations must be 1.." + MAX_ROTATIONS);
        Nation[] powers = Nation.values();
        int pairs = rotations * powers.length;
        Math.addExact(firstSeed, pairs - 1L);
        List<Match> result = new ArrayList<>();
        for (int pair = 0; pair < pairs; pair++)
            for (int leg = 0; leg < 2; leg++)
                result.add(new Match(pair, pair / powers.length, powers[pair % powers.length],
                        Math.addExact(firstSeed, pair),
                        (pair + leg) % 2 == 0 ? Side.REFERENCE : Side.CANDIDATE));
        return List.copyOf(result);

    }

    /**
     * Inputs contain one focal-seat score per pair, in complete rotation order.
     * Use seven powers for the overall comparison and one for a per-power series.
     */
    public static Comparison compare(
            double[] reference, double[] candidate, int powersPerRotation
    ) {

        if (powersPerRotation != 1 && powersPerRotation != Nation.values().length)
            throw new IllegalArgumentException("Expected one or seven powers per rotation");
        if (reference.length == 0 || reference.length != candidate.length
                || reference.length % powersPerRotation != 0
                || reference.length / powersPerRotation > MAX_ROTATIONS)
            throw new IllegalArgumentException("Expected complete paired rotations");
        double[] deltas = new double[reference.length / powersPerRotation];
        for (int pair = 0; pair < reference.length; pair++) {
            for (double score : new double[]{reference[pair], candidate[pair]})
                if (!Double.isFinite(score) || score < 0 || score > 1)
                    throw new IllegalArgumentException("SoS rewards must be finite and in [0,1]");
            deltas[pair / powersPerRotation] +=
                    (candidate[pair] - reference[pair]) / powersPerRotation;
        }
        return new Comparison(
                Arrays.stream(reference).average().orElseThrow(),
                Arrays.stream(candidate).average().orElseThrow(),
                Arrays.stream(deltas).average().orElseThrow(),
                Arrays.stream(deltas).boxed().toList(),
                deltas.length < 2 ? OptionalDouble.empty()
                        : OptionalDouble.of(PolicyProposalBenchmarkApp.estimate(deltas).standardError()));

    }


    // Launcher \\

    public static void main(String[] args) throws Exception {

        if (args.length == 2 && args[0].equals("--verify")) {
            print(verify(Path.of(args[1]), System.out::println));
            return;
        }
        boolean values = args.length > 0 && args[0].equals("--value");
        if (args.length != (values ? 11 : 10))
            throw new IllegalArgumentException(
                    "Usage: PolicyMatchApp <value-model.bin|baseline> <anchor.bin> "
                            + "<reference-gru.bin> <candidate-gru.bin> <last-year> "
                            + "<candidates> <iterations> <first-pair-seed> <rotations> <new-directory>"
                            + "\n   or: PolicyMatchApp --value <reference-value.bin> <candidate-value.bin> "
                            + "<anchor.bin> <shared-gru.bin> <last-year> "
                            + "<candidates> <iterations> <first-pair-seed> <rotations> <new-directory>"
                            + "\n   or: PolicyMatchApp --verify <directory>");
        if (values)
            args = Arrays.copyOfRange(args, 1, args.length);
        long seed = Long.parseLong(args[7]);
        int rotations = Integer.parseInt(args[8]);
        Path directory = Path.of(args[9]);
        preflight(directory, rotations, seed);
        GameRunner.Options reference = SelfPlayGameApp.options(
                args[0], Path.of(args[values ? 2 : 1]), Path.of(args[values ? 3 : 2]),
                Integer.parseInt(args[4]), Integer.parseInt(args[5]), Integer.parseInt(args[6]), seed);
        AgentSettings base = reference.agents().get(Nation.ENGLAND);
        AgentSettings candidate = values
                ? base.withValueModel(NeuralValueModel.load(Path.of(args[1])))
                : new AgentSettings(
                base.method(), base.candidateLimit(), base.iterations(), base.rolloutCount(),
                base.rolloutLimit(), base.output(), base.rootGenerator(), base.rolloutGenerator(),
                Optional.of(AgentModels.load(Path.of(args[1]), Optional.of(Path.of(args[3])))),
                base.valueModel());

        String kind = values ? "VALUE" : "POLICY";
        System.out.println("BEGIN PAIRED " + kind + " MATCH");
        System.out.println("Reference: " + base);
        System.out.println("Candidate: " + candidate);
        System.out.println("Rotations=" + rotations + "; pairs=" + rotations * 7
                + "; games=" + rotations * 14 + "; last-year=" + reference.lastYear());
        System.out.println("A year limit is an artificial cutoff, not an agreed draw.");
        System.out.println("Evaluation only: no training cache, historical TEST access or promotion.");
        long started = System.nanoTime();
        print(run(directory, reference, candidate, rotations, values, System.out::println));
        System.out.printf(Locale.ROOT, "Elapsed seconds: %.3f%n", (System.nanoTime() - started) / 1e9);
        System.out.println("END PAIRED " + kind + " MATCH");

    }

    private static void print(String text) {
        Map<String, Object> report = ValueDatasetJson.parse(text);
        Map<String, Object> overall = ValueDatasetJson.object(report.get("overall"));
        System.out.println("Paired final SoS: " + SelfPlayFiles.json(overall));
        System.out.println("Endings: " + SelfPlayFiles.json(report.get("endings")));
        System.out.println("Positive delta favors the candidate against six reference agents.");
        System.out.println("Cutoffs are included in this finite-horizon score; no strength promotion.");
    }


    // Fixed configuration and persistent execution \\

    public static String run(
            Path directory, GameRunner.Options reference, AgentSettings candidate,
            int rotations, Consumer<String> progress
    ) throws IOException {
        return run(directory, reference, candidate, rotations, false, progress);
    }

    public static String runValue(
            Path directory, GameRunner.Options reference, AgentSettings candidate,
            int rotations, Consumer<String> progress
    ) throws IOException {
        return run(directory, reference, candidate, rotations, true, progress);
    }

    private static String run(
            Path directory, GameRunner.Options reference, AgentSettings candidate,
            int rotations, boolean values, Consumer<String> progress
    ) throws IOException {

        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(progress, "progress");
        preflight(directory, rotations, reference.seed());
        AgentSettings base = reference.agents().get(Nation.ENGLAND);
        if (reference.scoring() != ScoringSystem.SUM_OF_SQUARES
                || reference.movementPolicy() != MovementProcessor.Policy.JUDGE
                || reference.agents().values().stream().anyMatch(seat -> !seat.equals(base)))
            throw new IllegalArgumentException("The control must have seven identical SoS/JUDGE agents");
        checkSeats(SelfPlayRecorder.settings(base), SelfPlayRecorder.settings(candidate), values);
        base.createEvaluator(reference, 0);
        candidate.createEvaluator(reference, 0);
        List<Match> matches = schedule(rotations, reference.seed());
        Map<String, Object> plan = object(
                "schema", SCHEMA, "evaluation_only", true,
                "design", "PAIRED_ONE_CANDIDATE_VS_SIX_REFERENCE",
                "rotations", rotations, "first_pair_seed", Long.toString(reference.seed()),
                "last_year", reference.lastYear(), "scoring", reference.scoring().name(),
                "movement_policy", reference.movementPolicy().name(),
                "adjudication_trials", reference.adjudicationTrials(),
                "adjudication_seed", Long.toString(reference.adjudicationSeed()),
                "ruleset_id", reference.rulesetId(),
                "initial_state", SelfPlayRecorder.state(StandardGameFactory.create1901().snapshot()),
                "reference_seat", SelfPlayRecorder.settings(base),
                "candidate_seat", SelfPlayRecorder.settings(candidate));
        if (values)
            plan.put("comparison", "VALUE");
        plan.put("schedule", matches.stream().map(match -> scheduled(plan, match)).toList());
        Files.createDirectory(directory);
        Files.createDirectory(directory.resolve("archives"));
        write(directory.resolve("plan.json"), plan);
        Map<String, Object> planReceipt = receipt(directory.resolve("plan.json"));
        List<Map<String, Object>> rows = new ArrayList<>();
        try (BufferedWriter journal = Files.newBufferedWriter(directory.resolve("games.jsonl"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            for (Match match : matches) {
                Map<String, Object> scheduled = scheduled(plan, match);
                Path archive = directory.resolve(ValueDatasetJson.text(scheduled, "directory"));
                Map<Nation, AgentSettings> seats = new EnumMap<>(reference.agents());
                if (match.side() == Side.CANDIDATE)
                    seats.put(match.power(), candidate);
                GameRunner.Options options = new GameRunner.Options(
                        reference.lastYear(), match.seed(), reference.scoring(), reference.movementPolicy(),
                        reference.adjudicationTrials(), reference.adjudicationSeed(), seats);
                String label = "Game " + (rows.size() + 1) + "/" + matches.size()
                        + " pair=" + match.pair() + " power=" + match.power() + " side=" + match.side();
                progress.accept(label + " seed=" + match.seed());
                GameMoment[] moment = {null};
                SelfPlayRecorder.run(archive, UUID.fromString(ValueDatasetJson.text(scheduled, "game_id")),
                        StandardGameFactory.create1901().snapshot(), options, decision -> {
                            if (!decision.state().moment().equals(moment[0])) {
                                moment[0] = decision.state().moment();
                                progress.accept(label + ": " + moment[0]);
                            }
                        });
                Map<String, Object> row = audit(directory, plan, match);
                rows.add(row);
                journal.write(SelfPlayFiles.json(row) + "\n");
                journal.flush();
                progress.accept("Replay verified: " + label + " ending=" + row.get("termination")
                        + " focal_sos=" + row.get("focal_sos"));
            }
        }
        Map<String, Object> files = object(
                "plan.json", planReceipt, "games.jsonl", receipt(directory.resolve("games.jsonl")));
        Map<String, Object> report = report(plan, rows, files);
        write(directory.resolve("manifest.pending"), report);
        progress.accept("Checking the complete plan, journal and replay receipts before publication.");
        check(directory, report, progress);
        if (!same(report, ValueDatasetJson.parse(text(bytes(directory.resolve("manifest.pending"))))))
            throw new IOException("Pending completion marker changed during readback");
        Files.move(directory.resolve("manifest.pending"), directory.resolve("manifest.json"),
                StandardCopyOption.ATOMIC_MOVE);
        return SelfPlayFiles.json(report);

    }

    private static void preflight(Path directory, int rotations, long seed) throws IOException {
        Objects.requireNonNull(directory, "directory");
        schedule(rotations, seed);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS))
            throw new FileAlreadyExistsException(directory.toString());
        Path parent = directory.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new NoSuchFileException("Output parent must already exist: " + parent);
    }

    private static boolean valueComparison(Map<String, Object> plan) {
        if (!plan.containsKey("comparison"))
            return false;
        if (!"VALUE".equals(plan.get("comparison")))
            throw new IllegalArgumentException("Unknown match comparison");
        return true;
    }

    private static void checkSeats(
            Map<String, Object> reference, Map<String, Object> candidate, boolean values
    ) {

        Map<String, Object> left = new LinkedHashMap<>(reference);
        Map<String, Object> right = new LinkedHashMap<>(candidate);
        String field = values ? "value_model" : "movement_models";
        if (values && (left.get(field) == null || right.get(field) == null))
            throw new IllegalArgumentException("Value comparisons require two neural models");
        Map<String, Object> a = ValueDatasetJson.object(left.remove(field));
        Map<String, Object> b = ValueDatasetJson.object(right.remove(field));
        if (!"DIL_PIKL".equals(reference.get("method")) || !same(left, right))
            throw new IllegalArgumentException(values
                    ? "Only the neural value may differ; keep movement artifacts and budgets fixed"
                    : "Only the proposal may differ; keep anchor, value and budgets fixed");
        if (values) {
            Set<String> keys = Set.of("model_sha256", "checkpoint_sha256", "dataset_fingerprint");
            if (!"NEURAL_YEAR_END_WITH_EXACT_SOLO_CUTOFF".equals(reference.get("evaluator"))
                    || !a.keySet().equals(keys) || !b.keySet().equals(keys)
                    || List.of(a, b).stream().flatMap(model -> model.values().stream())
                    .anyMatch(value -> !(value instanceof String hash) || !hash.matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("Value comparisons require two loaded neural model identities");
            return;
        }
        if (!a.keySet().equals(b.keySet()) || !Objects.equals(a.get("anchor"), b.get("anchor")))
            throw new IllegalArgumentException("Only the proposal may differ; keep anchor, value and budgets fixed");
        if (!a.isEmpty() && (!a.keySet().equals(Set.of("anchor", "gru", "checkpoint", "dataset"))
                || !"COHERENT_GRU_HYBRID".equals(reference.get("root_generator"))))
            throw new IllegalArgumentException("Learned comparisons require loaded GRU artifact identities");

    }

    private static Map<String, Object> header(Map<String, Object> plan, Match match) {
        Map<String, Object> seats = new LinkedHashMap<>();
        for (Nation power : Nation.values())
            seats.put(power.name(), plan.get(
                    match.side() == Side.CANDIDATE && power == match.power() ? "candidate_seat" : "reference_seat"));
        Map<String, Object> result = object("seats", seats, "initial_state", plan.get("initial_state"),
                "game_seed", Long.toString(match.seed()));
        for (String key : List.of("last_year", "scoring", "movement_policy",
                "adjudication_trials", "adjudication_seed", "ruleset_id"))
            result.put(key, plan.get(key));
        String domain = SCHEMA + (valueComparison(plan) ? ":VALUE" : "");
        UUID id = UUID.nameUUIDFromBytes((domain + ":" + match.power() + ":" + match.side()
                + ":" + SelfPlayFiles.json(result)).getBytes(StandardCharsets.UTF_8));
        result.put("game_id", id.toString());
        return result;
    }

    private static Map<String, Object> scheduled(Map<String, Object> plan, Match match) {
        return object("pair", match.pair(), "rotation", match.rotation(), "power", match.power().name(),
                "game_seed", Long.toString(match.seed()), "side", match.side().name(),
                "game_id", header(plan, match).get("game_id"),
                "directory", String.format(Locale.ROOT, "archives/pair-%05d-%s",
                        match.pair(), match.side().name().toLowerCase(Locale.ROOT)));
    }


    // Replay and report readback \\

    private static Map<String, Object> audit(
            Path directory, Map<String, Object> plan, Match match
    ) throws IOException {

        Map<String, Object> row = scheduled(plan, match);
        Path archive = directory.resolve(ValueDatasetJson.text(row, "directory"));
        SelfPlayFiles.Snapshot snapshot = SelfPlayFiles.snapshot(archive);
        SelfPlayDataset.GameExample game = SelfPlayDataset.open(archive);
        if (!snapshot.fingerprint().equals(game.fingerprint()))
            throw new IOException("Archive changed during replay: " + archive);
        Map<String, Object> recorded = ValueDatasetJson.parse(snapshot.run());
        for (var expected : header(plan, match).entrySet())
            if (!same(expected.getValue(), recorded.get(expected.getKey())))
                throw new IOException("Archive differs from planned " + expected.getKey() + ": " + archive);
        Map<Nation, Integer> centers = game.outcome().centerCounts();
        Optional<Nation> winner = centers.entrySet().stream().filter(entry -> entry.getValue() >= 18)
                .map(Map.Entry::getKey).findFirst();
        row.putAll(object("archive_sha256", game.fingerprint(), "logical_key", game.logicalKey(),
                "phases", game.phases().size(), "final_year", game.finalState().moment().year(),
                "final_phase", game.finalState().moment().gamePhase().name(),
                "termination", game.outcome().termination().name(),
                "winner", winner.map(Enum::name).orElse(null),
                "focal_centers", centers.get(match.power()),
                "focal_sos", ScoringSystem.SUM_OF_SQUARES.rewards(centers).get(match.power())));
        return row;

    }

    public static String verify(Path directory, Consumer<String> progress) throws IOException {
        Objects.requireNonNull(progress, "progress");
        Map<String, Object> manifest = ValueDatasetJson.parse(
                text(bytes(directory.resolve("manifest.json"))));
        check(directory, manifest, progress);
        return SelfPlayFiles.json(manifest);
    }

    private static void check(
            Path directory, Map<String, Object> manifest, Consumer<String> progress
    ) throws IOException {

        Map<String, Object> files = ValueDatasetJson.object(manifest.get("files"));
        if (!files.keySet().equals(Set.of("plan.json", "games.jsonl")))
            throw new IOException("Unexpected match payloads");
        Map<String, Object> plan = ValueDatasetJson.parse(read(directory, "plan.json", files));
        if (!SCHEMA.equals(plan.get("schema")) || !Boolean.TRUE.equals(plan.get("evaluation_only"))
                || !"PAIRED_ONE_CANDIDATE_VS_SIX_REFERENCE".equals(plan.get("design"))
                || !"SUM_OF_SQUARES".equals(plan.get("scoring")) || !"JUDGE".equals(plan.get("movement_policy"))
                || !same(plan.get("initial_state"),
                SelfPlayRecorder.state(StandardGameFactory.create1901().snapshot())))
            throw new IOException("Unsupported match plan");
        checkSeats(ValueDatasetJson.object(plan.get("reference_seat")),
                ValueDatasetJson.object(plan.get("candidate_seat")), valueComparison(plan));
        int rotations = Math.toIntExact(ValueDatasetJson.count(plan, "rotations"));
        List<Match> matches = schedule(rotations, Long.parseLong(ValueDatasetJson.text(plan, "first_pair_seed")));
        if (!same(plan.get("schedule"), matches.stream().map(match -> scheduled(plan, match)).toList()))
            throw new IOException("Incomplete or changed power rotation");
        String journal = read(directory, "games.jsonl", files);
        if (!journal.endsWith("\n"))
            throw new IOException("Truncated match journal");
        List<Map<String, Object>> rows = journal.lines().map(ValueDatasetJson::parse).toList();
        if (rows.size() != matches.size())
            throw new IOException("Missing or extra matched games");
        for (int index = 0; index < matches.size(); index++) {
            Map<String, Object> actual = audit(directory, plan, matches.get(index));
            if (!same(rows.get(index), actual))
                throw new IOException("Game changed after its recorded replay: " + matches.get(index));
            progress.accept("Readback verified: " + actual.get("directory"));
        }
        if (!same(manifest, report(plan, rows, files)))
            throw new IOException("Match summary differs from the paired game results");

    }

    private static Map<String, Object> report(
            Map<String, Object> plan, List<Map<String, Object>> rows, Map<String, Object> files
    ) {

        int pairs = rows.size() / 2;
        int rotations = Math.toIntExact(ValueDatasetJson.count(plan, "rotations"));
        double[] reference = new double[pairs], candidate = new double[pairs];
        List<Map<String, Object>> comparisons = new ArrayList<>();
        for (int pair = 0; pair < pairs; pair++) {
            Map<String, Object> first = rows.get(pair * 2), second = rows.get(pair * 2 + 1);
            boolean controlFirst = Side.REFERENCE.name().equals(first.get("side"));
            Map<String, Object> a = controlFirst ? first : second, b = controlFirst ? second : first;
            reference[pair] = number(a, "focal_sos");
            candidate[pair] = number(b, "focal_sos");
            comparisons.add(object("pair", pair, "rotation", pair / 7, "power", a.get("power"),
                    "game_seed", a.get("game_seed"), "reference_sos", reference[pair],
                    "candidate_sos", candidate[pair], "delta_sos", candidate[pair] - reference[pair],
                    "reference_termination", a.get("termination"), "candidate_termination", b.get("termination")));
        }
        Map<String, Object> powers = new LinkedHashMap<>();
        for (Nation power : Nation.values()) {
            double[] a = new double[rotations], b = new double[rotations];
            for (int rotation = 0; rotation < rotations; rotation++) {
                a[rotation] = reference[rotation * 7 + power.ordinal()];
                b[rotation] = candidate[rotation * 7 + power.ordinal()];
            }
            powers.put(power.name(), summary(compare(a, b, 1)));
        }
        Map<String, Object> endings = new LinkedHashMap<>();
        for (Side side : Side.values()) {
            List<Map<String, Object>> games = rows.stream().filter(row -> side.name().equals(row.get("side"))).toList();
            endings.put(side.name(), object("games", games.size(),
                    "solo_games", games.stream().filter(row -> "SOLO".equals(row.get("termination"))).count(),
                    "cutoff_games", games.stream().filter(row -> "ARTIFICIAL_CUTOFF".equals(row.get("termination"))).count(),
                    "focal_solo_wins", games.stream().filter(row -> row.get("power").equals(row.get("winner"))).count()));
        }
        Map<String, Object> result = object("schema", SCHEMA, "complete", true, "evaluation_only", true,
                "rotations", rotations, "pairs", pairs, "games", rows.size(),
                "score_semantics", "FINAL_SOS_INCLUDING_EXPLICIT_YEAR_LIMIT_CUTOFFS",
                "uncertainty_unit", "COMPLETE_SEVEN_POWER_ROTATION",
                "overall", summary(compare(reference, candidate, 7)), "by_power", powers,
                "endings", endings, "comparisons", comparisons, "files", files,
                "training_performed", false, "test_opened", false,
                "cache_created", false, "promotion_performed", false);
        if (valueComparison(plan))
            result.put("comparison", "VALUE");
        return result;

    }

    private static Map<String, Object> summary(Comparison comparison) {
        return object("reference_mean", comparison.referenceMean(), "candidate_mean", comparison.candidateMean(),
                "mean_delta", comparison.meanDelta(), "rotation_deltas", comparison.rotationDeltas(),
                "standard_error", comparison.standardError().isPresent()
                        ? comparison.standardError().getAsDouble() : null);
    }


    // Small-file handoff \\

    private static boolean same(Object a, Object b) {
        return Objects.equals(JsonReader.read(SelfPlayFiles.json(a)), JsonReader.read(SelfPlayFiles.json(b)));
    }

    private static double number(Map<String, Object> row, String key) {
        if (!(row.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Expected finite number: " + key);
        return value.doubleValue();
    }

    private static Map<String, Object> receipt(Path path) throws IOException {
        return object("bytes", Files.size(path), "sha256", ValueDatasetJson.hash(path));
    }

    private static String read(Path directory, String name, Map<String, Object> files) throws IOException {
        byte[] bytes = bytes(directory.resolve(name));
        Map<String, Object> expected = ValueDatasetJson.object(files.get(name));
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (bytes.length != ValueDatasetJson.count(expected, "bytes")
                    || !hash.equals(ValueDatasetJson.text(expected, "sha256")))
                throw new IOException("Match payload changed: " + name);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        return text(bytes);
    }

    private static byte[] bytes(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024)
                throw new IOException("Match metadata exceeds 4 MiB: " + path);
            return bytes;
        }
    }

    private static String text(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void write(Path path, Map<String, Object> value) throws IOException {
        Files.writeString(path, SelfPlayFiles.json(value) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

}