package io.training;

import agents.encoding.*;
import agents.search.PaperSearchProfile;
import agents.search.PolicyOutput;
import game.GameState_Movement;
import io.json.JsonReader;
import misc.scoring.ScoringSystem;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

import static io.training.SelfPlayFiles.object;

/**
 * Complete-action policy targets and separately admitted eventual-value inputs.
 * Input archives are fully validated before the output directory is created.
 */
public final class NeuralSelfPlayCache {

    public static final String SCHEMA = "jreferee-self-play-cache-v1";
    private static final int MAX_EXAMPLES = 256, MAX_PACKED = 1_000_000;
    private static final List<EncodingPurpose> SPLITS =
            List.of(EncodingPurpose.TRAIN, EncodingPurpose.VALIDATION);

    private NeuralSelfPlayCache() { }

    public static EncodingPurpose split(String logicalKey, long seed) {
        if (logicalKey == null || !logicalKey.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid logical game key");
        String hash = hash(seed + "\n" + logicalKey);
        long bucket = Long.parseUnsignedLong(hash.substring(0, 16), 16);
        return Long.remainderUnsigned(bucket, 10) == 0
                ? EncodingPurpose.VALIDATION : EncodingPurpose.TRAIN;
    }

    public static String write(
            List<Path> archives, Path output, long splitSeed, Consumer<String> progress
    ) throws IOException {
        return write(archives, output, splitSeed, 1, progress);
    }

    public static String write(
            List<Path> archives, Path output, long splitSeed, int workers, Consumer<String> progress
    ) throws IOException {

        Objects.requireNonNull(progress, "progress");
        if (archives.isEmpty())
            throw new IllegalArgumentException("Supply at least one archive");
        if (workers < 1)
            throw new IllegalArgumentException("Worker count must be positive");
        if (workers > 1)
            progress.accept("Replaying " + archives.size() + " games with up to " + workers + " workers.");
        List<SelfPlayDataset.GameExample> games = SelfPlayDataset.openAll(archives, workers).stream()
                .sorted(Comparator.comparing(SelfPlayDataset.GameExample::logicalKey)).toList();
        GameWorkerTasks.checkInterrupted();
        Files.createDirectory(output);
        Files.createDirectory(output.resolve("policy"));
        writeText(output.resolve("policy-specification.json"), SelfPlayFiles.json(specification()));

        Path work = output.resolve(".policy-work");
        List<PreparedPolicy> prepared = List.of();
        if (workers > 1) {
            Files.createDirectory(work);
            List<Integer> indices = new ArrayList<>();
            for (int index = 0; index < games.size(); index++)
                indices.add(index);
            progress.accept("Encoding per-game policy shards.");
            prepared = GameWorkerTasks.map(indices, workers, index -> {
                Path directory = Files.createDirectory(work.resolve(Integer.toString(index)));
                Files.createDirectory(directory.resolve("policy"));
                List<Map<String, Object>> reports = new ArrayList<>();
                var game = games.get(index);
                PolicyReport policy = writePolicy(game, index, directory,
                        split(game.logicalKey(), splitSeed), reports);
                return new PreparedPolicy(directory, policy, List.copyOf(reports));
            }, index -> progress.accept("Policy prepared: " + games.get(index).id()));
        }

        Map<EncodingPurpose, List<ValueDataset.GameExample>> values = new EnumMap<>(EncodingPurpose.class);
        Map<EncodingPurpose, List<Map<String, Object>>> shards = new EnumMap<>(EncodingPurpose.class);
        for (EncodingPurpose purpose : SPLITS) {
            values.put(purpose, new ArrayList<>());
            shards.put(purpose, new ArrayList<>());
        }
        long policyExamples = 0, valuePositions = 0;
        try (BufferedWriter index = Files.newBufferedWriter(output.resolve("games.jsonl"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            for (int gameIndex = 0; gameIndex < games.size(); gameIndex++) {
                GameWorkerTasks.checkInterrupted();
                var game = games.get(gameIndex);
                EncodingPurpose purpose = split(game.logicalKey(), splitSeed);
                PolicyReport policy = workers == 1
                        ? writePolicy(game, gameIndex, output, purpose, shards.get(purpose))
                        : publishPolicy(prepared.get(gameIndex), output, purpose, shards.get(purpose));
                policyExamples += policy.examples();
                List<ValueDataset.Position> positions = game.phases().stream()
                        .filter(phase -> !phase.state().moment().gamePhase().isRetreat())
                        .map(phase -> new ValueDataset.Position(phase.state().moment(),
                                phase.state().board(), ScoringSystem.SUM_OF_SQUARES)).toList();
                var assessment = game.valueAssessment();
                boolean admitted = assessment.rewards().isPresent() && !positions.isEmpty();
                if (admitted) {
                    values.get(purpose).add(new ValueDataset.GameExample(
                            "JREFEREE_SELF_PLAY", game.id().toString(), Optional.of("NO_PRESS"),
                            game.fingerprint(), positions, game.outcome()));
                    valuePositions += positions.size();
                }
                Map<String, Object> row = object(
                        "game_index", gameIndex, "game_id", game.id().toString(),
                        "logical_key", game.logicalKey(), "archive_sha256", game.fingerprint(),
                        "split", purpose.name(), "configuration", configuration(game.configuration()),
                        "termination", game.outcome().termination().name(),
                        "policy_examples", policy.examples(),
                        "skipped_non_movement", policy.nonMovement(),
                        "skipped_non_dil", policy.nonDil(),
                        "skipped_zero_units", policy.zeroUnits(),
                        "value_positions", admitted ? positions.size() : 0,
                        "value_status", assessment.rejection().map(Enum::name)
                                .orElse(admitted ? "ACCEPTED" : "NO_POSITIONS"));
                index.write(SelfPlayFiles.json(row));
                index.write('\n');
                progress.accept(purpose + " " + game.id() + ": policy=" + policy.examples()
                        + " value=" + (admitted ? positions.size() : 0));
            }
        }
        if (workers > 1)
            Files.delete(work);
        GameWorkerTasks.checkInterrupted();
        String fingerprint = hash(SCHEMA + "\n" + splitSeed + "\n"
                + ValueDatasetJson.hash(output.resolve("games.jsonl")));
        NeuralValueCache.write(new Values(values, fingerprint), output.resolve("value"), progress);
        Map<String, Object> policySplits = new LinkedHashMap<>();
        for (EncodingPurpose purpose : SPLITS)
            policySplits.put(purpose.name(), shards.get(purpose));
        Map<String, Object> manifest = object(
                "schema", SCHEMA, "complete", true, "byte_order", "LITTLE_ENDIAN",
                "dataset_fingerprint", fingerprint, "split_seed", Long.toString(splitSeed),
                "split_rule", "SHA256(seed+LF+logical_key):first16hex_unsigned_mod10:0=VALIDATION,else=TRAIN",
                "test_opened", false, "games", games.size(),
                "policy_examples", policyExamples, "value_positions", valuePositions,
                "policy_specification", describe(output.resolve("policy-specification.json")),
                "game_index", describe(output.resolve("games.jsonl")),
                "policy_splits", policySplits,
                "value_manifest", describe(output.resolve("value/manifest.json")));
        String text = SelfPlayFiles.json(manifest);
        GameWorkerTasks.checkInterrupted();
        writeText(output.resolve("manifest.pending"), text);
        GameWorkerTasks.checkInterrupted();
        Files.move(output.resolve("manifest.pending"), output.resolve("manifest.json"),
                StandardCopyOption.ATOMIC_MOVE);
        return text;

    }

    private record PolicyReport(long examples, long nonMovement, long nonDil, long zeroUnits) { }

    private record PreparedPolicy(
            Path directory, PolicyReport policy, List<Map<String, Object>> shards
    ) { }

    private static PolicyReport publishPolicy(
            PreparedPolicy prepared, Path output, EncodingPurpose split, List<Map<String, Object>> reports
    ) throws IOException {

        for (Map<String, Object> shard : prepared.shards()) {
            GameWorkerTasks.checkInterrupted();
            String name = "policy/" + split.name().toLowerCase(Locale.ROOT)
                    + "-" + String.format(Locale.ROOT, "%05d", reports.size());
            Files.move(prepared.directory().resolve(ValueDatasetJson.text(shard, "directory")),
                    output.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            Map<String, Object> published = new LinkedHashMap<>(shard);
            published.put("directory", name);
            reports.add(published);
        }
        Files.delete(prepared.directory().resolve("policy"));
        Files.delete(prepared.directory());
        return prepared.policy();

    }

    private static PolicyReport writePolicy(
            SelfPlayDataset.GameExample game, int gameIndex, Path output, EncodingPurpose split,
            List<Map<String, Object>> reports
    ) throws IOException {

        long examples = 0, nonMovement = 0, nonDil = 0, zeroUnits = 0;
        Shard writer = null;
        Throwable failure = null;
        try {
            for (int phaseIndex = 0; phaseIndex < game.phases().size(); phaseIndex++) {
                GameWorkerTasks.checkInterrupted();
                var phase = game.phases().get(phaseIndex);
                for (var decision : phase.decisions()) {
                    GameWorkerTasks.checkInterrupted();
                    if (!(phase.state() instanceof GameState_Movement movement)) {
                        nonMovement++;
                        continue;
                    }
                    if (decision.type().isEmpty()) {
                        nonDil++;
                        continue;
                    }
                    MovementEncoding encoding = new MovementEncoding(movement, decision.power());
                    NeuralMovementEncoding input = new NeuralMovementEncoding(encoding.input());
                    if (input.units() == 0) {
                        zeroUnits++;
                        continue;
                    }
                    int actionIndices = Math.multiplyExact(input.units(), decision.policy().candidates().size());
                    if (input.optionCount() > MAX_PACKED || actionIndices > MAX_PACKED)
                        throw new IOException("One policy example exceeds the packed shard limit");
                    if (writer != null && !writer.fits(input, actionIndices)) {
                        reports.add(writer.finish());
                        writer = null;
                    }
                    if (writer == null) {
                        String name = "policy/" + split.name().toLowerCase(Locale.ROOT)
                                + "-" + String.format(Locale.ROOT, "%05d", reports.size());
                        writer = new Shard(output, name, gameIndex);
                    }
                    writer.add(gameIndex, phaseIndex, encoding, input, decision,
                            game.configuration().seats().get(decision.power()).output());
                    examples++;
                }
            }
            if (writer != null)
                reports.add(writer.finish());
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (writer != null)
                try { writer.close(); }
                catch (IOException closing) {
                    if (failure == null) throw closing;
                    failure.addSuppressed(closing);
                }
        }
        return new PolicyReport(examples, nonMovement, nonDil, zeroUnits);

    }

    private static Map<String, Object> specification() {
        return object(
                "schema", SCHEMA, "movement_specification", JsonReader.read(NeuralMovementSpec.toJson()),
                "movement_specification_sha256", NeuralMovementSpec.sha256(),
                "type_fields", List.of("playing_lambda", "population_lambda_0", "population_lambda_1",
                        "population_lambda_2", "population_lambda_3", "population_weight_0",
                        "population_weight_1", "population_weight_2", "population_weight_3"),
                "profile_ids", List.of("NONE", "OPENING", "STANDARD"),
                "output_ids", List.of("NONE", "FINAL", "AVERAGE"),
                "target", "COMPLETE_ACTION_DISTRIBUTION",
                "loss", "NEGATIVE_EXPECTED_SUM_OF_CONDITIONAL_LOG_PROBABILITIES_WITH_FULL_LEGAL_MASKS",
                "type_transform", "RAW_FLOAT64",
                "temperature_schedule", "ADAPTIVE",
                "max_shard_examples", MAX_EXAMPLES, "max_packed_options_or_action_indices", MAX_PACKED);
    }

    private static Map<String, Object> configuration(SelfPlayDataset.Configuration configuration) {
        Map<String, Object> seats = new TreeMap<>();
        configuration.seats().forEach((power, seat) -> seats.put(power.name(), object(
                "method", seat.method().name(), "candidates", seat.candidates(),
                "iterations", seat.iterations(), "rollouts", seat.rollouts(),
                "rollout_limit", seat.rolloutLimit(), "output", seat.output().name(),
                "root", seat.root().name(), "continuation", seat.continuation().name(),
                "movement_models", new TreeMap<>(seat.movementModels()),
                "value_model", seat.valueModel().map(model -> object("model", model.model(),
                        "checkpoint", model.checkpoint(), "dataset", model.dataset())).orElse(null))));
        return object("last_year", configuration.lastYear(),
                "game_seed", Long.toString(configuration.seed()),
                "adjudication_trials", configuration.adjudicationTrials(),
                "adjudication_seed", Long.toString(configuration.adjudicationSeed()), "seats", seats);
    }

    private static final class Shard implements AutoCloseable {

        private final Path directory;
        private final String name;
        private final int gameIndex;
        private final Map<String, DataOutputStream> files = new LinkedHashMap<>();
        private long examples, slots, options, candidates, actions;

        private Shard(Path output, String name, int gameIndex) throws IOException {
            this.directory = output.resolve(name);
            this.name = name;
            this.gameIndex = gameIndex;
            Files.createDirectory(this.directory);
            try {
                for (String file : List.of("board.u8", "context.i32", "types.f64", "type_ids.i32",
                        "example_offsets.i64", "slot_locations.u8", "option_offsets.i64", "options.u8",
                        "candidate_offsets.i64", "action_offsets.i64", "actions.i32", "policy.f64",
                        "log_prior.f64", "mean_values.f64", "selected.i32", "metadata.jsonl"))
                    this.files.put(file, new DataOutputStream(new BufferedOutputStream(
                            Files.newOutputStream(this.directory.resolve(file), StandardOpenOption.CREATE_NEW))));
                for (String file : List.of("example_offsets.i64", "option_offsets.i64",
                        "candidate_offsets.i64", "action_offsets.i64"))
                    longNumber(file, 0);
            } catch (IOException | RuntimeException exception) {
                try { close(); } catch (IOException closing) { exception.addSuppressed(closing); }
                throw exception;
            }
        }

        private boolean fits(NeuralMovementEncoding input, int actionIndices) {
            return this.examples < MAX_EXAMPLES
                    && this.options + input.optionCount() <= MAX_PACKED
                    && this.actions + actionIndices <= MAX_PACKED;
        }

        private void add(
                int gameIndex, int phaseIndex, MovementEncoding encoding, NeuralMovementEncoding input,
                SelfPlayDataset.Decision decision, PolicyOutput output
        ) throws IOException {

            bytes("board.u8", input.board());
            integer("context.i32", input.power());
            integer("context.i32", input.phase());
            integer("context.i32", input.year());
            PaperSearchProfile type = decision.type().orElseThrow();
            integer("type_ids.i32", switch (type) { case OPENING -> 1; case STANDARD -> 2; });
            integer("type_ids.i32", switch (output) { case FINAL -> 1; case AVERAGE -> 2; });
            if (type.population().values().size() != 4)
                throw new IOException("Self-play v1 requires four population types");
            real("types.f64", type.agentLambda());
            for (double value : type.population().values())
                real("types.f64", value);
            for (double value : type.population().probabilities())
                real("types.f64", value);
            bytes("slot_locations.u8", input.slotLocations());
            bytes("options.u8", input.orderFields());
            IntBuffer offsets = input.optionOffsets();
            for (int slot = 0; slot < input.units(); slot++)
                longNumber("option_offsets.i64", this.options + offsets.get(slot + 1));
            this.slots += input.units();
            this.options += input.optionCount();
            longNumber("example_offsets.i64", this.slots);

            var pool = decision.policy().candidates();
            var search = decision.search().orElseThrow();
            for (int candidate = 0; candidate < pool.size(); candidate++) {
                List<Integer> labels = encoding.encode(pool.get(candidate).orders());
                for (int label : labels)
                    integer("actions.i32", label);
                this.actions += labels.size();
                longNumber("action_offsets.i64", this.actions);
                real("policy.f64", decision.policy().probabilities().get(candidate));
                real("log_prior.f64", pool.get(candidate).logPrior());
                real("mean_values.f64", search.meanValues().get(candidate));
            }
            this.candidates += pool.size();
            longNumber("candidate_offsets.i64", this.candidates);
            integer("selected.i32", decision.selectedIndex());
            Map<String, Object> metadata = object(
                    "game_index", gameIndex, "phase_index", phaseIndex, "power", decision.power().name(),
                    "search_seed", Long.toString(decision.searchSeed()),
                    "rollout_seed", Long.toString(decision.rolloutSeed()),
                    "selection_seed", Long.toString(decision.selectionSeed()),
                    "search_samples", search.samplesPerCandidate(),
                    "candidate_sources", pool.stream().map(action -> action.source().name()).toList());
            this.files.get("metadata.jsonl").write(
                    (SelfPlayFiles.json(metadata) + "\n").getBytes(StandardCharsets.UTF_8));
            this.examples++;

        }

        private Map<String, Object> finish() throws IOException {
            close();
            Map<String, Object> sizes = object(
                    "board.u8", this.examples * 300, "context.i32", this.examples * 12,
                    "types.f64", this.examples * 72, "type_ids.i32", this.examples * 8,
                    "example_offsets.i64", (this.examples + 1) * 8, "slot_locations.u8", this.slots,
                    "option_offsets.i64", (this.slots + 1) * 8, "options.u8", this.options * 5,
                    "candidate_offsets.i64", (this.examples + 1) * 8,
                    "action_offsets.i64", (this.candidates + 1) * 8, "actions.i32", this.actions * 4,
                    "policy.f64", this.candidates * 8, "log_prior.f64", this.candidates * 8,
                    "mean_values.f64", this.candidates * 8, "selected.i32", this.examples * 4);
            Map<String, Object> receipts = new LinkedHashMap<>();
            for (String file : this.files.keySet()) {
                Path path = this.directory.resolve(file);
                if (sizes.containsKey(file) && Files.size(path) != (Long) sizes.get(file))
                    throw new IOException("Incorrect numeric file size: " + path);
                receipts.put(file, describe(path));
            }
            return object("directory", this.name, "game_index", this.gameIndex,
                    "examples", this.examples, "slots", this.slots, "options", this.options,
                    "candidates", this.candidates, "action_indices", this.actions, "files", receipts);
        }

        private void bytes(String file, ByteBuffer buffer) throws IOException {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            this.files.get(file).write(bytes);
        }

        private void integer(String file, int value) throws IOException {
            this.files.get(file).writeInt(Integer.reverseBytes(value));
        }

        private void longNumber(String file, long value) throws IOException {
            this.files.get(file).writeLong(Long.reverseBytes(value));
        }

        private void real(String file, double value) throws IOException {
            if (!Double.isFinite(value)
                    && !(file.equals("log_prior.f64") && value == Double.NEGATIVE_INFINITY))
                throw new IOException("Non-finite numeric cache value: " + file);
            longNumber(file, Double.doubleToRawLongBits(value));
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (DataOutputStream stream : this.files.values())
                try { stream.close(); }
                catch (IOException exception) {
                    if (failure == null) failure = exception;
                    else failure.addSuppressed(exception);
                }
            if (failure != null)
                throw failure;
        }

    }

    private static final class Values implements ValueDataset {

        private final Map<EncodingPurpose, List<GameExample>> games;
        private final String fingerprint;

        private Values(Map<EncodingPurpose, List<GameExample>> games, String fingerprint) {
            this.games = new EnumMap<>(EncodingPurpose.class);
            games.forEach((split, rows) -> this.games.put(split, List.copyOf(rows)));
            this.fingerprint = fingerprint;
        }

        private List<GameExample> rows(EncodingPurpose split) {
            if (!SPLITS.contains(split))
                throw new IllegalArgumentException("Self-play cache does not open TEST");
            return this.games.get(split);
        }

        @Override public long games(EncodingPurpose split) { return rows(split).size(); }
        @Override public long positions(EncodingPurpose split) {
            return rows(split).stream().mapToLong(game -> game.positions().size()).sum();
        }
        @Override public String fingerprint() { return this.fingerprint; }

        @Override
        public Cursor open(EncodingPurpose split) {
            List<GameExample> rows = rows(split);
            return new Cursor() {
                private final MessageDigest digest = digest();
                private int index;
                private long positions;
                private String hash;
                private boolean closed;

                @Override public GameExample next() {
                    if (this.closed)
                        throw new IllegalStateException("Value cursor is closed");
                    if (this.index == rows.size()) {
                        if (this.hash == null)
                            this.hash = HexFormat.of().formatHex(this.digest.digest());
                        return null;
                    }
                    GameExample game = rows.get(this.index++);
                    this.digest.update((ValueDatasetJson.encode(game, split) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    this.positions += game.positions().size();
                    return game;
                }

                @Override public Statistics statistics() {
                    return new Statistics(this.index, this.positions, 0, Optional.ofNullable(this.hash));
                }
                @Override public void close() { this.closed = true; }
            };
        }

    }

    private static Map<String, Object> describe(Path path) throws IOException {
        return object("bytes", Files.size(path), "sha256", ValueDatasetJson.hash(path));
    }

    private static void writeText(Path path, String text) throws IOException {
        Files.writeString(path, text + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String hash(String text) {
        return HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}