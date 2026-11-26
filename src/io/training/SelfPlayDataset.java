package io.training;

import agents.execution.AgentSettings;
import agents.generation.*;
import agents.search.*;
import agents.simulation.Simulator;
import contracts.OrderForm;
import contracts.OrderForms;
import domain.*;
import game.*;
import io.json.JsonAccess;
import io.json.JsonReader;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.adjustments.*;
import phase.movement.MovementProcessor;
import phase.retreats.*;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

import static io.training.SelfPlayFiles.object;

/**
 * Typed, replay-checked reading of v1 movement-start JUDGE archives.
 * No policy models, search, training, or TEST datasets are opened.
 */
public final class SelfPlayDataset {

    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));

    private SelfPlayDataset() { }

    public record ValueModel(String model, String checkpoint, String dataset) { }

    public record Seat(
            AgentSettings.Method method, int candidates, int iterations, int rollouts,
            int rolloutLimit, PolicyOutput output, AgentSettings.Generator root,
            AgentSettings.Generator continuation, Map<String, String> movementModels,
            Optional<ValueModel> valueModel
    ) {
        public Seat {
            movementModels = Map.copyOf(movementModels);
            Objects.requireNonNull(valueModel, "valueModel");
        }
    }

    public record Configuration(
            int lastYear, long seed, int adjudicationTrials, long adjudicationSeed,
            Map<Nation, Seat> seats
    ) {
        public Configuration {
            seats = Map.copyOf(seats);
        }
    }

    public record Decision(
            Nation power, Optional<PaperSearchProfile> type,
            Map<Nation, List<ActionCandidate>> pools, ActionDistribution policy,
            Optional<SearchResult> search, int selectedIndex,
            long searchSeed, long rolloutSeed, long selectionSeed, long elapsedNanos
    ) {
        public Decision {
            Map<Nation, List<ActionCandidate>> copy = new EnumMap<>(Nation.class);
            pools.forEach((powerKey, pool) -> copy.put(powerKey, List.copyOf(pool)));
            pools = Collections.unmodifiableMap(copy);
        }

        public ActionCandidate selected() {
            return this.policy.candidates().get(this.selectedIndex);
        }
    }

    public record Phase(GameState state, List<Decision> decisions, GameState successor) {
        public Phase {
            decisions = List.copyOf(decisions);
        }
    }

    public record GameExample(
            UUID id, String fingerprint, String logicalKey, Configuration configuration,
            GameState initial, List<Phase> phases, GameState finalState, ValueOutcome outcome
    ) {
        public GameExample {
            phases = List.copyOf(phases);
        }

        public ValueRewardContract.Assessment valueAssessment() {
            return ValueRewardContract.defaults().assess(this.outcome);
        }
    }

    public static List<GameExample> openAll(List<Path> directories) throws IOException {
        return openAll(directories, 1);
    }

    /**
     * Replays independent games with bounded concurrency and preserves input order.
     * Duplicate checks cover the complete batch before any games are returned.
     */
    public static List<GameExample> openAll(List<Path> directories, int workers) throws IOException {

        List<Path> inputs = List.copyOf(directories);
        List<GameExample> result = GameWorkerTasks.map(inputs, workers, SelfPlayDataset::open);
        Set<UUID> ids = new HashSet<>();
        Set<String> logicalKeys = new HashSet<>();
        for (int index = 0; index < result.size(); index++) {
            GameWorkerTasks.checkInterrupted();
            GameExample game = result.get(index);
            require(ids.add(game.id()), "Duplicate game ID: " + game.id());
            require(logicalKeys.add(game.logicalKey()),
                    "Duplicate configuration, seed and initial position: " + inputs.get(index));
        }
        return result;

    }

    public static GameExample open(Path directory) throws IOException {

        try {
            GameWorkerTasks.checkInterrupted();
            GameExample game = decode(SelfPlayFiles.snapshot(directory));
            GameWorkerTasks.checkInterrupted();
            return game;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(directory + ": " + exception.getMessage(), exception);
        }

    }

    private static GameExample decode(SelfPlayFiles.Snapshot snapshot) throws IOException {

        Map<String, Object> run = parse(snapshot.run());
        fields(run, "schema", "game_id", "source", "communication", "reward_contract",
                "initial_state", "seats", "last_year", "game_seed", "ruleset_id", "scoring",
                "movement_policy", "adjudication_trials", "adjudication_seed",
                "type_assignment", "seed_derivation");
        require(SelfPlayFiles.SCHEMA.equals(text(run, "schema"))
                && "JREFEREE_SELF_PLAY".equals(text(run, "source"))
                && "NO_PRESS".equals(text(run, "communication"))
                && ValueRewardContract.VERSION.equals(text(run, "reward_contract"))
                && "SUM_OF_SQUARES".equals(text(run, "scoring")), "Unsupported run contract");
        require("PAPER_PROFILE_BY_ROOT_PHASE".equals(text(run, "type_assignment"))
                        && "GameRunner-v1:search=0,rollout=1,selection=2".equals(text(run, "seed_derivation")),
                "Unsupported type or seed assignment");
        require("JUDGE".equals(text(run, "movement_policy")),
                "This reader supports only JUDGE archives");
        int lastYear = positive(run, "last_year");
        require(lastYear >= Constants.STARTING_YEAR && lastYear < Integer.MAX_VALUE, "Invalid last year");
        int trials = positive(run, "adjudication_trials");
        long adjudicationSeed = longValue(run, "adjudication_seed");
        require(text(run, "ruleset_id").equals("jreferee-agent-v1:JUDGE:" + trials + ":" + adjudicationSeed),
                "Ruleset identity differs");
        Map<Nation, Seat> seats = new EnumMap<>(Nation.class);
        Map<String, Object> rawSeats = map(run.get("seats"));
        require(rawSeats.keySet().equals(nationNames(EnumSet.allOf(Nation.class))), "Missing seat settings");
        for (Nation power : Nation.values())
            seats.put(power, seat(map(rawSeats.get(power.name()))));
        Configuration configuration = new Configuration(lastYear, longValue(run, "game_seed"),
                trials, adjudicationSeed, seats);
        GameState initial = initial(map(run.get("initial_state")));
        require(initial.moment().year() <= lastYear, "Initial position is beyond the cutoff");
        Game game = Game.fromState(initial, new MovementProcessor(MovementProcessor.Policy.JUDGE,
                trials, adjudicationSeed), new RetreatProcessor(), new AdjustmentProcessor());
        List<Phase> phases = new ArrayList<>();
        Simulator simulator = new Simulator();
        try (BufferedReader input = new BufferedReader(new StringReader(snapshot.phases()))) {
            String line;
            while ((line = input.readLine()) != null) {
                GameWorkerTasks.checkInterrupted();
                Map<String, Object> row = parse(line);
                fields(row, "index", "state", "decisions", "board_after");
                require(integer(row, "index") == phases.size(), "Phase index differs");
                GameState root = game.snapshot();
                require(!terminal(root, lastYear), "Recorded a decision after the ending");
                checkState(row.get("state"), root);
                Map<Nation, LegalOrderCatalog> catalogs = new EnumMap<>(Nation.class);
                for (Nation power : root.aliveNations())
                    catalogs.put(power, new LegalOrderGenerator().generate(root, power));
                Map<Nation, ActionCandidate> joint = new EnumMap<>(Nation.class);
                List<Decision> decisions = new ArrayList<>();
                for (Object raw : array(row.get("decisions"))) {
                    Decision decision = decision(map(raw), root, configuration, catalogs);
                    require(joint.putIfAbsent(decision.power(), decision.selected()) == null, "Duplicate seat");
                    decisions.add(decision);
                }
                require(joint.keySet().equals(root.aliveNations()), "Decision coverage differs");
                GameState successor = simulator.advance(game, joint);
                require(successor.moment().compareTo(root.moment()) > 0
                        && successor.moment().year() <= lastYear, "Invalid phase progression");
                equivalent(row.get("board_after"), SelfPlayRecorder.state(successor).get("board"),
                        "Adjudicated board differs");
                phases.add(new Phase(root, decisions, successor));
            }
        }
        GameState end = game.snapshot();
        Map<String, Object> ending = parse(snapshot.outcome());
        fields(ending, "game_id", "runner_reason", "termination", "value_target_eligible",
                "eventual_sos_target", "cutoff_scores", "winner", "final_state",
                "center_counts", "phase_count", "decision_count");
        require(terminal(end, lastYear), "Archive stops before a terminal boundary");
        checkState(ending.get("final_state"), end);
        Map<Nation, Integer> counts = counts(end);
        Map<String, Object> namedCounts = new TreeMap<>();
        counts.forEach((power, count) -> namedCounts.put(power.name(), count));
        equivalent(ending.get("center_counts"), namedCounts, "Final center counts differ");
        Optional<Nation> winner = counts.entrySet().stream().filter(entry -> entry.getValue() >= 18)
                .map(Map.Entry::getKey).findFirst();
        boolean solo = winner.isPresent();
        require(text(ending, "runner_reason").equals(solo ? "SOLO" : "YEAR_LIMIT")
                && text(ending, "termination").equals(solo ? "SOLO" : "ARTIFICIAL_CUTOFF")
                && Objects.equals(ending.get("winner"), winner.map(Enum::name).orElse(null))
                && Boolean.valueOf(solo).equals(ending.get("value_target_eligible")), "Ending classification differs");
        Map<Nation, Double> scores = ScoringSystem.SUM_OF_SQUARES.rewards(counts);
        Map<String, Object> rawScores = map(ending.get(solo ? "eventual_sos_target" : "cutoff_scores"));
        require(rawScores.keySet().equals(nationNames(EnumSet.allOf(Nation.class))), "Missing score components");
        for (Nation power : Nation.values())
            require(Math.abs(number(rawScores.get(power.name())) - scores.get(power)) <= 1e-12,
                    "Final SoS differs for " + power);
        require(ending.get(solo ? "cutoff_scores" : "eventual_sos_target") == null, "Mixed target kinds");
        require(integer(ending, "phase_count") == phases.size()
                        && integer(ending, "decision_count") == phases.stream().mapToInt(p -> p.decisions().size()).sum(),
                "Final counts differ");
        ValueOutcome outcome = new ValueOutcome(
                solo ? ValueOutcome.Termination.SOLO : ValueOutcome.Termination.ARTIFICIAL_CUTOFF,
                ValueOutcome.Evidence.REPLAY_VERIFIED, end.moment(), counts,
                Optional.of("SUM_OF_SQUARES"), Optional.of("lastYear=" + lastYear),
                "self-play replay; archive=" + snapshot.fingerprint());
        Map<String, Object> identity = new TreeMap<>(run);
        identity.remove("game_id");
        return new GameExample(UUID.fromString(text(run, "game_id")), snapshot.fingerprint(),
                digest(canonical(identity)), configuration, initial, phases, end, outcome);

    }

    private static Seat seat(Map<String, Object> row) {

        fields(row, "method", "candidate_limit", "iterations", "rollout_count", "rollout_limit",
                "configured_output", "root_generator", "rollout_generator", "movement_models",
                "value_model", "evaluator");
        AgentSettings.Method method = AgentSettings.Method.valueOf(text(row, "method"));
        AgentSettings.Generator root = AgentSettings.Generator.valueOf(text(row, "root_generator"));
        AgentSettings.Generator continuation = AgentSettings.Generator.valueOf(text(row, "rollout_generator"));
        require(continuation != AgentSettings.Generator.COHERENT_GRU_HYBRID, "Learned rollout generator");
        Map<String, Object> rawModels = map(row.get("movement_models"));
        Set<String> names = rawModels.keySet();
        require(names.isEmpty() || names.equals(Set.of("anchor"))
                || names.equals(Set.of("anchor", "gru", "checkpoint", "dataset")), "Invalid movement identities");
        Map<String, String> models = new TreeMap<>();
        rawModels.forEach((key, value) -> models.put(key, hashText(value)));
        int candidates = positive(row, "candidate_limit");
        require(root == AgentSettings.Generator.COHERENT_GRU_HYBRID
                ? models.containsKey("gru") && candidates <= PolicyActionGenerator.MAX_CANDIDATES
                : !models.containsKey("gru"), "Movement model/generator mismatch");
        Optional<ValueModel> value = Optional.empty();
        if (row.get("value_model") != null) {
            Map<String, Object> raw = map(row.get("value_model"));
            fields(raw, "model_sha256", "checkpoint_sha256", "dataset_fingerprint");
            value = Optional.of(new ValueModel(hashText(raw.get("model_sha256")),
                    hashText(raw.get("checkpoint_sha256")), hashText(raw.get("dataset_fingerprint"))));
        }
        require(method != AgentSettings.Method.PRIOR || value.isEmpty(), "PRIOR has an unused value model");
        String evaluator = method == AgentSettings.Method.PRIOR ? "UNUSED"
                : value.isEmpty() ? "YEAR_END_STOP_NOW_SOS" : "NEURAL_YEAR_END_WITH_EXACT_SOLO_CUTOFF";
        require(evaluator.equals(text(row, "evaluator")), "Evaluator metadata differs");
        return new Seat(method, candidates, positive(row, "iterations"), positive(row, "rollout_count"),
                positive(row, "rollout_limit"), PolicyOutput.valueOf(text(row, "configured_output")),
                root, continuation, models, value);

    }

    private static GameState initial(Map<String, Object> row) {

        Map<String, Object> moment = map(row.get("moment"));
        int year = integer(moment, "year");
        GamePhase phase = GamePhase.valueOf(text(moment, "phase"));
        require(year >= Constants.STARTING_YEAR && phase.isMovement(),
                "This reader requires a movement-phase initial position");
        Map<String, Object> board = map(row.get("board"));
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        for (Object raw : array(board.get("units"))) {
            Map<String, Object> unit = map(raw);
            Nation owner = Nation.valueOf(text(unit, "power"));
            UnitType type = UnitType.valueOf(text(unit, "type"));
            Province location = Province.valueOf(text(unit, "location"));
            require(OrderForms.isValidUnitLocation(type, location), "Invalid initial unit location");
            String key = "self-play:" + owner + ":" + type + ":" + location.name();
            UnitId id = new UnitId(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), owner, type, location);
            require(locations.putIfAbsent(id, location) == null, "Duplicate initial unit");
        }
        Map<Province, Nation> centers = new EnumMap<>(Province.class);
        map(board.get("centers")).forEach((key, value) -> {
            Province province = Province.valueOf(key);
            require(province.supplyCenter && Province.canonical(province) == province, "Invalid center key");
            centers.put(province, Nation.valueOf(JSON.string(value, "center")));
        });
        require(!centers.isEmpty(), "An initial position must have owned centers");
        GameState state = new GameState_Movement(new GameMoment(year, phase), new BoardState(locations, centers));
        checkState(row, state);
        return state;

    }

    private static Decision decision(
            Map<String, Object> row, GameState root, Configuration configuration,
            Map<Nation, LegalOrderCatalog> catalogs
    ) {

        fields(row, "power", "method", "type", "search_seed", "rollout_seed", "selection_seed",
                "elapsed_nanos", "pools", "policy", "selected_index", "search");
        Nation power = Nation.valueOf(text(row, "power"));
        require(root.aliveNations().contains(power), "Eliminated seat has a decision");
        Seat seat = configuration.seats().get(power);
        require(seat.method().name().equals(text(row, "method")), "Seat method differs");
        Optional<PaperSearchProfile> type = Optional.empty();
        if (seat.method() == AgentSettings.Method.DIL_PIKL) {
            PaperSearchProfile profile = PaperSearchProfile.forState(root);
            equivalent(row.get("type"), object("profile", profile.name(),
                            "playing_lambda", profile.agentLambda(), "population_lambdas", profile.population().values(),
                            "population_probabilities", profile.population().probabilities(),
                            "temperature_schedule", profile.temperature().name(), "policy_output", seat.output().name()),
                    "Search type differs");
            type = Optional.of(profile);
        } else {
            require(row.get("type") == null, "Non-DiL method has a lambda type");
        }
        long searchSeed = longValue(row, "search_seed"), rolloutSeed = longValue(row, "rollout_seed");
        long selectionSeed = longValue(row, "selection_seed"), elapsed = longValue(row, "elapsed_nanos");
        require(elapsed >= 0 && searchSeed == seed(configuration.seed(), root.moment(), power, 0)
                && rolloutSeed == seed(configuration.seed(), root.moment(), power, 1)
                && selectionSeed == seed(configuration.seed(), root.moment(), power, 2), "Decision seeds differ");
        Map<String, Object> rawPools = map(row.get("pools"));
        Set<Nation> expected = seat.method() == AgentSettings.Method.PRIOR ? Set.of(power) : root.aliveNations();
        require(rawPools.keySet().equals(nationNames(expected)), "Root pool coverage differs");
        Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
        CatalogActionValidator validator = new CatalogActionValidator();
        for (Nation owner : expected) {
            List<ActionCandidate> pool = new ArrayList<>();
            Set<Map<OrderForm, Integer>> distinct = new HashSet<>();
            for (Object raw : array(rawPools.get(owner.name()))) {
                Map<String, Object> candidate = map(raw);
                fields(candidate, "source", "log_prior", "orders");
                List<OrderForm> orders = array(candidate.get("orders")).stream()
                        .map(value -> order(root, map(value))).toList();
                Object prior = candidate.get("log_prior");
                double logPrior = "-Infinity".equals(prior) ? Double.NEGATIVE_INFINITY : number(prior);
                ActionCandidate action = new ActionCandidate(owner, orders, logPrior,
                        ActionCandidate.Source.valueOf(text(candidate, "source")));
                var errors = validator.validate(root, action, catalogs.get(owner));
                require(errors.isEmpty(), "Illegal candidate for " + owner + ": " + errors);
                Map<OrderForm, Integer> signature = new HashMap<>();
                orders.forEach(order -> signature.merge(order, 1, Integer::sum));
                require(distinct.add(signature), "Duplicate action in candidate pool");
                pool.add(action);
            }
            require(!pool.isEmpty() && pool.size() <= seat.candidates(), "Candidate count differs");
            if (seat.method() != AgentSettings.Method.BEST_RESPONSE || owner != power)
                ActionDistribution.fromPriors(pool);
            pools.put(owner, List.copyOf(pool));
        }
        List<Double> probabilities = array(row.get("policy")).stream().map(SelfPlayDataset::number).toList();
        ActionDistribution policy = new ActionDistribution(pools.get(power), probabilities);
        int selected = integer(row, "selected_index");
        require(selected >= 0 && selected < probabilities.size() && probabilities.get(selected) > 0,
                "Invalid selected index");
        require(policy.sample(new Random(selectionSeed)) == policy.candidates().get(selected),
                "Selection does not match its seed and policy");
        Optional<SearchResult> search = Optional.empty();
        if (seat.method() == AgentSettings.Method.PRIOR) {
            require(row.get("search") == null, "PRIOR has search values");
            equivalent(probabilities, ActionDistribution.fromPriors(policy.candidates()).probabilities(),
                    "PRIOR probabilities differ");
        } else {
            Map<String, Object> raw = map(row.get("search"));
            fields(raw, "mean_values", "samples_per_candidate", "value_semantics");
            require("HISTORICAL_RAW_PAYOFF_MEANS_NOT_FRESH_POLICY_VALUES".equals(text(raw, "value_semantics")),
                    "Unsupported value semantics");
            List<Double> means = array(raw.get("mean_values")).stream().map(SelfPlayDataset::number).toList();
            require(means.stream().allMatch(value -> value >= -1e-12 && value <= 1 + 1e-12),
                    "Payoff outside the SoS scale");
            int samples = integer(raw, "samples_per_candidate");
            require(samples == seat.iterations(), "Search sample count differs");
            search = Optional.of(new SearchResult(policy, means, samples));
            if (seat.method() == AgentSettings.Method.BEST_RESPONSE) {
                double best = Collections.max(means);
                long ties = means.stream().filter(value -> value == best).count();
                equivalent(probabilities, means.stream().map(value -> value == best ? 1.0 / ties : 0.0).toList(),
                        "Best-response probabilities differ");
            } else {
                for (int index = 0; index < probabilities.size(); index++)
                    require(policy.candidates().get(index).logPrior() != Double.NEGATIVE_INFINITY
                            || probabilities.get(index) == 0, "DiL policy assigns mass to a zero prior");
            }
        }
        return new Decision(power, type, pools, policy, search, selected,
                searchSeed, rolloutSeed, selectionSeed, elapsed);

    }

    private static OrderForm order(GameState state, Map<String, Object> row) {

        Nation power = Nation.valueOf(text(row, "power"));
        OrderType type = OrderType.valueOf(text(row, "type"));
        Province origin = province(row.get("origin")), target = province(row.get("target"));
        Province auxiliary = province(row.get("auxiliary_target"));
        OrderForm result;
        if (type == OrderType.WAIVE) {
            result = new WaiveOrder(power);
        } else {
            require(origin != null, "Non-waive order has no origin");
            require(type != OrderType.RETREAT || target != null, "Retreat order has no destination");
            UnitType unitType = UnitType.valueOf(text(row, "unit_type"));
            if (type == OrderType.BUILD) {
                result = new BuildOrder(power, unitType, origin);
            } else {
                Map<UnitId, Province> locations = new LinkedHashMap<>(state.board().locations());
                if (type == OrderType.RETREAT) {
                    require(state instanceof GameState_Retreats, "Retreat order outside retreat phase");
                    locations.clear();
                    ((GameState_Retreats) state).movementResult().dislodgements()
                            .forEach((unit, dislodgement) -> locations.put(unit, dislodgement.displacedFrom()));
                }
                UnitId unit = locations.entrySet().stream()
                        .filter(entry -> entry.getValue() == origin && entry.getKey().owner() == power
                                && entry.getKey().unitType() == unitType)
                        .map(Map.Entry::getKey).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Order has no matching exact-location unit"));
                result = switch (type) {
                    case RETREAT -> new RetreatOrder(unit, target);
                    case DESTROY -> new DisbandOrder(power, unit);
                    case HOLD, MOVE, SUPPORT, CONVOY -> new Order(unit, type, target, auxiliary);
                    default -> throw new IllegalArgumentException("Unsupported order type");
                };
            }
        }
        equivalent(row, SelfPlayRecorder.order(state, result), "Order fields differ after typed decoding");
        return result;

    }

    private static void checkState(Object raw, GameState state) {
        int units = state.board().locations().size()
                + (state instanceof GameState_Retreats retreats ? retreats.movementResult().dislodgements().size() : 0);
        require(units <= 34, "More than 34 units");
        equivalent(raw, SelfPlayRecorder.state(state), "Recorded state differs from replay");
    }

    private static boolean terminal(GameState state, int lastYear) {
        return counts(state).values().stream().anyMatch(count -> count >= 18)
                || state instanceof GameState_Adjustments && state.moment().year() == lastYear;
    }

    private static Map<Nation, Integer> counts(GameState state) {
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);
        for (Nation power : Nation.values())
            counts.put(power, 0);
        state.board().owners().values().forEach(power -> counts.merge(power, 1, Integer::sum));
        return counts;
    }

    // Frozen v1 stream derivation; does not run or resample the search.
    private static long seed(long seed, GameMoment moment, Nation nation, int stream) {
        long phase = (long) moment.year() * GamePhase.values().length + moment.gamePhase().ordinal();
        long key = (phase * Nation.values().length + nation.ordinal()) * 3 + stream;
        long value = seed + 0x9E3779B97F4A7C15L * (key + 1);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static Set<String> nationNames(Set<Nation> powers) {
        Set<String> names = new HashSet<>();
        powers.forEach(power -> names.add(power.name()));
        return names;
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(JSON.string(key, "key"), canonical(item)));
            return result;
        }
        if (value instanceof List<?> list)
            return list.stream().map(SelfPlayDataset::canonical).toList();
        if (value instanceof Number number)
            return BigDecimal.valueOf(number(number)).stripTrailingZeros();
        return value;
    }

    private static void equivalent(Object supplied, Object expected, String message) {
        require(Objects.equals(canonical(supplied), canonical(expected)), message);
    }

    private static String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(SelfPlayFiles.json(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void fields(Map<String, Object> row, String... keys) {
        require(row.keySet().equals(Set.of(keys)), "Unexpected fields: " + row.keySet());
    }

    private static Map<String, Object> parse(String text) { return map(JsonReader.read(text)); }
    private static Map<String, Object> map(Object value) { return JSON.object(value, "object"); }
    private static List<Object> array(Object value) { return JSON.array(value, "array"); }
    private static String text(Map<String, Object> row, String key) { return JSON.string(row.get(key), key); }
    private static int integer(Map<String, Object> row, String key) { return JSON.integer(row.get(key), key); }
    private static Province province(Object value) {
        return value == null ? null : Province.valueOf(JSON.string(value, "province"));
    }

    private static long longValue(Map<String, Object> row, String key) {
        String text = text(row, key);
        long value = Long.parseLong(text);
        require(Long.toString(value).equals(text), "Noncanonical long: " + key);
        return value;
    }

    private static int positive(Map<String, Object> row, String key) {
        int value = integer(row, key);
        require(value > 0, "Expected positive " + key);
        return value;
    }

    private static double number(Object value) {
        require(value instanceof Number, "Expected number");
        double number = ((Number) value).doubleValue();
        require(Double.isFinite(number), "Non-finite number");
        return number;
    }

    private static String hashText(Object value) {
        String hash = JSON.string(value, "hash");
        require(hash.matches("[0-9a-f]{64}"), "Invalid artifact hash");
        return hash;
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalArgumentException(message);
    }

}