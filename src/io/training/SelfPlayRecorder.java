package io.training;

import agents.execution.*;
import agents.generation.ActionCandidate;
import agents.search.PaperSearchProfile;
import contracts.OrderForm;
import domain.*;
import game.*;
import game.record.*;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.adjustments.*;
import phase.retreats.RetreatOrder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static io.training.SelfPlayFiles.object;

/**
 * Observes the built-in runner without changing search, sampling or adjudication.
 * One phase line contains every living seat's independent root search.
 */
public final class SelfPlayRecorder {

    private SelfPlayRecorder() { }

    public static GameRunner.Result run(
            Path directory, UUID id, GameState initial, GameRunner.Options options,
            Consumer<AgentDecision> observer
    ) throws IOException {

        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(observer, "observer");
        if (options.scoring() != ScoringSystem.SUM_OF_SQUARES)
            throw new IllegalArgumentException("Self-play v1 requires SUM_OF_SQUARES");
        Map<String, Object> seats = new LinkedHashMap<>();
        for (Nation power : Nation.values())
            seats.put(power.name(), settings(options.agents().get(power)));
        Map<String, Object> header = object(
                "schema", SelfPlayFiles.SCHEMA, "game_id", id.toString(),
                "source", "JREFEREE_SELF_PLAY", "communication", "NO_PRESS",
                "reward_contract", ValueRewardContract.VERSION,
                "initial_state", state(initial), "seats", seats,
                "last_year", options.lastYear(), "game_seed", Long.toString(options.seed()),
                "ruleset_id", options.rulesetId(), "scoring", options.scoring().name(),
                "movement_policy", options.movementPolicy().name(),
                "adjudication_trials", options.adjudicationTrials(),
                "adjudication_seed", Long.toString(options.adjudicationSeed()),
                "type_assignment", "PAPER_PROFILE_BY_ROOT_PHASE",
                "seed_derivation", "GameRunner-v1:search=0,rollout=1,selection=2");

        List<AgentDecision> pending = new ArrayList<>();
        int[] counts = {0, 0};
        try (SelfPlayFiles files = new SelfPlayFiles(directory, header)) {
            GameRunner.Result result;
            try {
                result = new GameRunner().run(id, initial, options, phase -> {
                    if (pending.isEmpty())
                        throw new IllegalStateException("Resolved phase has no decisions");
                    Map<String, Object> row = phase(counts[0], phase, pending);
                    try {
                        files.append(row);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                    counts[0]++;
                    counts[1] += pending.size();
                    pending.clear();
                }, decision -> {
                    if (!decision.settings().equals(options.agents().get(decision.power())))
                        throw new IllegalStateException("Decision settings differ from the recorded seat");
                    pending.add(decision);
                    observer.accept(decision);
                });
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
            if (!pending.isEmpty() || counts[0] != result.record().resolvedPhases().size())
                throw new IllegalStateException("Unresolved or missing phase records");
            boolean solo = result.reason() == GameRunner.EndReason.SOLO;
            Map<String, Object> scores = new LinkedHashMap<>();
            Map<String, Object> centers = new LinkedHashMap<>();
            for (Nation power : Nation.values()) {
                scores.put(power.name(), result.scores().get(power));
                centers.put(power.name(), Collections.frequency(
                        new ArrayList<>(result.state().board().owners().values()), power));
            }
            files.finish(object(
                    "game_id", id.toString(), "runner_reason", result.reason().name(),
                    "termination", solo ? "SOLO" : "ARTIFICIAL_CUTOFF",
                    "value_target_eligible", solo,
                    "eventual_sos_target", solo ? scores : null,
                    "cutoff_scores", solo ? null : scores,
                    "winner", result.winner().map(Enum::name).orElse(null),
                    "final_state", state(result.state()), "center_counts", centers,
                    "phase_count", counts[0], "decision_count", counts[1]));
            return result;
        }

    }

    private static Map<String, Object> settings(AgentSettings settings) {

        var identities = settings.models().map(AgentModels::identities).orElse(Map.of());
        if (settings.models().isPresent() && identities.isEmpty())
            throw new IllegalArgumentException("Recording learned policies requires loaded artifact identities");
        Object value = settings.valueModel().map(model -> object(
                "model_sha256", model.sha256(), "checkpoint_sha256", model.checkpointSha256(),
                "dataset_fingerprint", model.datasetFingerprint())).orElse(null);
        return object(
                "method", settings.method().name(), "candidate_limit", settings.candidateLimit(),
                "iterations", settings.iterations(), "rollout_count", settings.rolloutCount(),
                "rollout_limit", settings.rolloutLimit(), "configured_output", settings.output().name(),
                "root_generator", settings.rootGenerator().name(),
                "rollout_generator", settings.rolloutGenerator().name(),
                "movement_models", identities, "value_model", value,
                "evaluator", settings.method() == AgentSettings.Method.PRIOR ? "UNUSED"
                        : value == null ? "YEAR_END_STOP_NOW_SOS" : "NEURAL_YEAR_END_WITH_EXACT_SOLO_CUTOFF");

    }

    public static Map<String, Object> state(GameState state) {

        List<Map<String, Object>> dislodged = new ArrayList<>();
        List<String> standoffs = new ArrayList<>();
        if (state instanceof GameState_Retreats retreats) {
            retreats.movementResult().dislodgements().values().stream()
                    .sorted(Comparator.comparing(value -> value.displacedFrom().name()))
                    .forEach(value -> dislodged.add(object(
                            "unit", unit(value.unit(), value.displacedFrom()),
                            "attacker_power", value.attacker().owner().name(),
                            "attacker_type", value.attacker().unitType().name(),
                            "attacker_origin", value.attackerOrigin().name(),
                            "attacker_arrived_by_convoy", value.attackerArrivedByConvoy())));
            retreats.movementResult().standoffProvinces().stream().map(Enum::name)
                    .sorted().forEach(standoffs::add);
        }
        return object("moment", moment(state.moment()), "board", board(state.board()),
                "alive_powers", state.aliveNations().stream().map(Enum::name).sorted().toList(),
                "dislodged", dislodged, "standoffs", standoffs);

    }

    private static Map<String, Object> moment(GameMoment moment) {
        return object("year", moment.year(), "phase", moment.gamePhase().name());
    }

    private static Map<String, Object> board(BoardState board) {

        List<Map<String, Object>> units = board.locations().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().name()))
                .map(entry -> unit(entry.getKey(), entry.getValue())).toList();
        Map<String, String> centers = new TreeMap<>();
        board.owners().forEach((province, power) -> centers.put(province.name(), power.name()));
        return object("units", units, "centers", centers);

    }

    private static Map<String, Object> unit(UnitId unit, Province location) {
        return object("power", unit.owner().name(), "type", unit.unitType().name(),
                "location", location.name());
    }

    public static Map<String, Object> decision(AgentDecision decision) {

        Object type = null;
        if (decision.settings().method() == AgentSettings.Method.DIL_PIKL) {
            PaperSearchProfile profile = PaperSearchProfile.forState(decision.state());
            type = object("profile", profile.name(), "playing_lambda", profile.agentLambda(),
                    "population_lambdas", profile.population().values(),
                    "population_probabilities", profile.population().probabilities(),
                    "temperature_schedule", profile.temperature().name(),
                    "policy_output", decision.settings().output().name());
        }
        Map<String, Object> pools = new LinkedHashMap<>();
        for (Nation power : Nation.values())
            if (decision.pools().containsKey(power))
                pools.put(power.name(), decision.pools().get(power).stream()
                        .map(candidate -> candidate(decision.state(), candidate)).toList());
        Object search = decision.searchResult().map(result -> object(
                "mean_values", result.meanValues(), "samples_per_candidate", result.samplesPerCandidate(),
                "value_semantics", "HISTORICAL_RAW_PAYOFF_MEANS_NOT_FRESH_POLICY_VALUES")).orElse(null);
        return object("power", decision.power().name(), "method", decision.settings().method().name(),
                "type", type, "search_seed", Long.toString(decision.searchSeed()),
                "rollout_seed", Long.toString(decision.rolloutSeed()),
                "selection_seed", Long.toString(decision.selectionSeed()),
                "elapsed_nanos", Long.toString(decision.elapsedNanos()),
                "pools", pools, "policy", decision.distribution().probabilities(),
                "selected_index", decision.selectedIndex(), "search", search);

    }

    private static Map<String, Object> candidate(GameState state, ActionCandidate candidate) {

        return object("source", candidate.source().name(),
                "log_prior", candidate.logPrior() == Double.NEGATIVE_INFINITY
                        ? "-Infinity" : candidate.logPrior(),
                "orders", candidate.orders().stream().map(order -> order(state, order)).toList());

    }

    public static Map<String, Object> order(GameState state, OrderForm order) {

        Province origin = switch (order) {
            case Order movement -> Objects.requireNonNull(state.board().locationOf(movement.unit()),
                    "Movement unit is not on the board");
            case RetreatOrder retreat -> {
                if (!(state instanceof GameState_Retreats retreats))
                    throw new IllegalArgumentException("Retreat order outside a retreat phase");
                yield Objects.requireNonNull(retreats.movementResult().dislodgementOf(retreat.unit()),
                        "Retreat unit is not dislodged").displacedFrom();
            }
            case DisbandOrder disband -> Objects.requireNonNull(state.board().locationOf(disband.unit()),
                    "Disband unit is not on the board");
            case BuildOrder build -> build.location();
            case WaiveOrder ignored -> null;
            default -> throw new IllegalArgumentException("Unsupported submitted order");
        };
        return object("power", order.owner().name(),
                "unit_type", order.unitType() == null ? null : order.unitType().name(),
                "origin", origin == null ? null : origin.name(),
                "type", order.orderType().name(),
                "target", order.target() == null ? null : order.target().name(),
                "auxiliary_target", order.auxiliaryTarget() == null ? null : order.auxiliaryTarget().name());

    }

    private static Map<String, Object> phase(
            int index, ResolvedPhaseRecord phase, List<AgentDecision> decisions
    ) {

        GameState root = decisions.getFirst().state();
        Set<Nation> powers = EnumSet.noneOf(Nation.class);
        List<OrderForm> selected = new ArrayList<>();
        for (AgentDecision decision : decisions) {
            if (!decision.state().equals(root) || !powers.add(decision.power()))
                throw new IllegalStateException("Duplicate seat or mismatched decision root");
            selected.addAll(decision.selected().orders());
        }
        if (!powers.equals(root.aliveNations()) || !phase.gameMoment().equals(root.moment())
                || !phase.boardBefore().equals(root.board()))
            throw new IllegalStateException("Decision coverage differs from the resolved phase");
        List<? extends OrderForm> submitted = switch (phase) {
            case MovementPhaseRecord movement -> movement.submittedOrders();
            case RetreatPhaseRecord retreats -> retreats.submittedOrders();
            case AdjustmentPhaseRecord adjustments -> adjustments.submittedOrders();
        };
        List<OrderForm> unmatched = new ArrayList<>(submitted);
        for (OrderForm order : selected)
            if (!unmatched.remove(order))
                throw new IllegalStateException("Selected action was not submitted");
        if (!unmatched.isEmpty())
            throw new IllegalStateException("Unobserved orders were submitted");
        return object("index", index, "state", state(root),
                "decisions", decisions.stream().map(SelfPlayRecorder::decision).toList(),
                "board_after", board(phase.boardAfter()));

    }

}