package analysis;

import domain.Nation;
import domain.Province;
import game.BoardState;
import game.GameMoment;
import phase.Order;
import phase.UnitId;
import phase.movement.MovementProcessor;

import java.util.*;


public final class MovementStrategy {

    private MovementStrategy() {  }


    public static Recommendation recommend(
            RoutePreferences preferences,
            String ruleset,
            GameMoment moment,
            BoardState board,
            Nation nation,
            Map<UnitId, List<String>> histories,
            Map<Province, Double> objectives,
            Configuration configuration,
            OutcomeEvaluator evaluator,
            MovementProcessor processor) {

        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(ruleset, "ruleset");
        Objects.requireNonNull(moment, "moment");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(nation, "nation");
        Objects.requireNonNull(histories, "histories");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(processor, "processor");

        CoordinatedOrders.Diagnostics coordination = new CoordinatedOrders.Diagnostics(
                configuration.coordinationMode(), 0, 0, 0, false, List.of(), Map.of());

        if (!moment.gamePhase().isMovement())
            throw new IllegalArgumentException(
                    "Movement strategy supports Spring and Fall movement only");

        Set<UnitId> active = board.locations().keySet();

        for (UnitId supplied : histories.keySet())
            if (!active.contains(supplied))
                throw new IllegalArgumentException(
                        "History names an unknown or inactive unit: " + supplied);

        List<UnitId> ownUnits = active.stream()
                .filter(unit -> unit.owner() == nation)
                .sorted(unitOrder(board))
                .toList();

        if (ownUnits.isEmpty())
            return Recommendation.abstain(
                    configuration.policy(), Status.NATION_ABSENT, Map.of(), 0, 0,
                    List.of(), "The requested nation has no active units.", coordination);

        Map<UnitId, RoutePrediction> original = new LinkedHashMap<>();
        Map<UnitId, RoutePrediction> selected = new LinkedHashMap<>();
        Map<UnitId, UnitEvidence> evidence = new LinkedHashMap<>();

        for (UnitId unit : active.stream().sorted(unitOrder(board)).toList()) {
            List<String> history = histories.containsKey(unit)
                    ? List.copyOf(Objects.requireNonNull(
                            histories.get(unit), "unit history"))
                    : List.of();

            RoutePrediction prediction = preferences.predict(
                    ruleset, moment, board, unit, history,
                    configuration.minimumObservations());

            RoutePrediction pooled = preferences.positionEvidence(
                    ruleset, moment, board, unit).yearPooled();

            RoutePrediction prefilter = switch (configuration.policy()) {
                case YEAR_POOLED_SELECTION,
                     YEAR_POOLED_SELECTION_THEN_REFERENCE_FILTERED ->
                        YearPooledSelection.select(
                                prediction, pooled,
                                configuration.minimumObservations());
                case ORIGINAL, REFERENCE_FILTERED -> prediction;
            };

            RoutePrediction transformed = configuration.policy().apply(
                    board, prediction, pooled,
                    configuration.minimumObservations());

            original.put(unit, prediction);
            selected.put(unit, transformed);
            evidence.put(unit, new UnitEvidence(
                    prediction.basis(),
                    prefilter.basis(),
                    transformed.basis(),
                    prediction.observations(),
                    prefilter.counts().size(),
                    transformed.counts().size()));
        }

        List<UnitId> missingOwn = ownUnits.stream()
                .filter(unit -> original.get(unit).counts().isEmpty())
                .toList();

        if (!missingOwn.isEmpty())
            return Recommendation.abstain(
                    configuration.policy(), Status.OWN_EVIDENCE_INSUFFICIENT,
                    evidence, 0, 0, missingOwn,
                    "One or more own units have no qualifying route evidence.", coordination);

        List<UnitId> filteredOwn = ownUnits.stream()
                .filter(unit -> selected.get(unit).counts().isEmpty())
                .toList();

        if (!filteredOwn.isEmpty())
            return Recommendation.abstain(
                    configuration.policy(), Status.OWN_CANDIDATES_FILTERED,
                    evidence, 0, 0, filteredOwn,
                    "The selected policy leaves one or more own units without candidates.", coordination);

        CoordinatedOrders ownSearch = new CoordinatedOrders(
                configuration.choicesPerUnit(),
                configuration.nationalPlanLimit());

        CoordinatedOrders.Result generation = ownSearch.generate(
                board, nation, selected, configuration.coordinationMode());
        List<OrderPlan> candidates = generation.plans();
        coordination = generation.diagnostics();

        if (candidates.isEmpty())
            return Recommendation.abstain(
                    configuration.policy(),
                    configuration.coordinationMode() == CoordinationMode.RAW
                            ? Status.OWN_EVIDENCE_INSUFFICIENT
                            : generation.exhaustedRetainedDomains()
                            ? Status.OWN_COORDINATION_REJECTED
                            : Status.OWN_COORDINATION_LIMIT_REACHED,
                    evidence, 0, 0, List.of(),
                    configuration.coordinationMode() == CoordinationMode.RAW
                            ? "No complete national candidate plan could be generated."
                            : generation.exhaustedRetainedDomains()
                            ? "No plan satisfies the requested coordination mode in the supplied candidate domains."
                            : "No coherent plan within current limits; omitted choices or beam pruning prevent a proof of impossibility.",
                    coordination);

        List<UnitId> opponents = active.stream()
                .filter(unit -> unit.owner() != nation)
                .toList();

        List<UnitId> missingOpponents = opponents.stream()
                .filter(unit -> selected.get(unit).counts().isEmpty())
                .sorted(unitOrder(board))
                .toList();

        if (!missingOpponents.isEmpty())
            return Recommendation.abstain(
                    configuration.policy(), Status.OPPONENT_EVIDENCE_INSUFFICIENT,
                    evidence, candidates.size(), 0, missingOpponents,
                    "One or more opposing units have no qualifying route evidence.", coordination);

        OpponentScenarios scenarioSearch = new OpponentScenarios(
                configuration.choicesPerUnit(),
                configuration.nationalPlanLimit(),
                configuration.opponentScenarioLimit());

        List<OpponentScenario> scenarios =
                scenarioSearch.generate(board, nation, selected);

        Map<String, List<Order>> scenarioOrders =
                OpponentScenarios.asOrders(board, nation, scenarios);

        List<PlanEvaluation> ranked = evaluator.rank(
                board, moment, candidates, scenarioOrders,
                objectives, processor);

        return new Recommendation(
                configuration.policy(), Status.RANKED,
                ranked, evidence,
                candidates.size(), scenarios.size(),
                List.of(), "Ranked movement plans using the supplied scoring configuration."
                        + (coordination.conditionalPlans().isEmpty() ? ""
                        : " Some plans require explicit foreign cooperation; convoy routes are not guaranteed successful."),
                coordination);

    }


    private static Comparator<UnitId> unitOrder(BoardState board) {
        return Comparator.comparing((UnitId unit) -> board.locationOf(unit).name())
                .thenComparing(unit -> unit.owner().name())
                .thenComparing(unit -> unit.unitType().name());
    }


    public record Configuration(
            PredictionPolicy policy,
            long minimumObservations,
            int choicesPerUnit,
            int nationalPlanLimit,
            int opponentScenarioLimit,
            CoordinationMode coordinationMode) {

        public Configuration(PredictionPolicy policy, long minimumObservations,
                             int choicesPerUnit, int nationalPlanLimit,
                             int opponentScenarioLimit) {
            this(policy, minimumObservations, choicesPerUnit, nationalPlanLimit,
                    opponentScenarioLimit, CoordinationMode.RAW);
        }

        public Configuration {
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(coordinationMode, "coordinationMode");

            if (minimumObservations < 1
                    || choicesPerUnit < 1
                    || nationalPlanLimit < 1
                    || opponentScenarioLimit < 1)
                throw new IllegalArgumentException("Strategy limits must be positive");
        }

    }


    public record UnitEvidence(
            String originalBasis,
            String selectedBasisBeforeFiltering,
            String selectedBasis,
            long originalObservations,
            int candidatesBeforeFiltering,
            int candidatesAfterPolicy) {

        public UnitEvidence {
            Objects.requireNonNull(originalBasis, "originalBasis");
            Objects.requireNonNull(selectedBasisBeforeFiltering, "selectedBasisBeforeFiltering");
            Objects.requireNonNull(selectedBasis, "selectedBasis");
        }

    }


    public record Recommendation(
            PredictionPolicy policy,
            Status status,
            List<PlanEvaluation> rankedPlans,
            Map<UnitId, UnitEvidence> evidence,
            int candidatePlanCount,
            int opponentScenarioCount,
            List<UnitId> insufficientUnits,
            String diagnostic,
            CoordinatedOrders.Diagnostics coordination) {

        public Recommendation(PredictionPolicy policy, Status status,
                              List<PlanEvaluation> rankedPlans,
                              Map<UnitId, UnitEvidence> evidence,
                              int candidatePlanCount, int opponentScenarioCount,
                              List<UnitId> insufficientUnits, String diagnostic) {
            this(policy, status, rankedPlans, evidence, candidatePlanCount,
                    opponentScenarioCount, insufficientUnits, diagnostic,
                    CoordinatedOrders.Diagnostics.raw());
        }

        public Recommendation {
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(status, "status");
            rankedPlans = List.copyOf(Objects.requireNonNull(rankedPlans, "rankedPlans"));
            evidence = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(evidence, "evidence")));
            insufficientUnits = List.copyOf(
                    Objects.requireNonNull(insufficientUnits, "insufficientUnits"));
            Objects.requireNonNull(diagnostic, "diagnostic");
            Objects.requireNonNull(coordination, "coordination");

            if (candidatePlanCount < 0 || opponentScenarioCount < 0)
                throw new IllegalArgumentException("Candidate counts must not be negative");

            if ((status == Status.RANKED) != !rankedPlans.isEmpty())
                throw new IllegalArgumentException(
                        "Only a ranked recommendation may contain ranked plans");
        }

        private static Recommendation abstain(
                PredictionPolicy policy,
                Status status,
                Map<UnitId, UnitEvidence> evidence,
                int candidates,
                int scenarios,
                List<UnitId> insufficient,
                String diagnostic,
                CoordinatedOrders.Diagnostics coordination) {
            return new Recommendation(
                    policy, status, List.of(), evidence,
                    candidates, scenarios, insufficient, diagnostic, coordination);
        }

        public boolean hasRecommendation() {
            return status == Status.RANKED;
        }

    }


    public enum Status {
        RANKED,
        NATION_ABSENT,
        OWN_EVIDENCE_INSUFFICIENT,
        OWN_CANDIDATES_FILTERED,
        OWN_COORDINATION_REJECTED,
        OWN_COORDINATION_LIMIT_REACHED,
        OPPONENT_EVIDENCE_INSUFFICIENT
    }

}
