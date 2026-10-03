package analysis;

import analysis.openings.Classifier;
import domain.*;
import game.*;
import phase.*;
import phase.movement.MovementProcessor;
import java.util.*;

public final class TacticalAnalysis {
    private TacticalAnalysis() { }

    public static final int MAX_COMPARISONS = 8;
    public static final int MAX_SCENARIO_EVALUATIONS = 128;

    public static final TacticalPrinciple FRIENDLY_CONVOY_FLEET = (board, plan) ->
            TacticalBias.inspect(board, plan).stream().filter(warning ->
                    warning.category() == TacticalPrinciple.Category.FRIENDLY_CONVOY_FLEET).toList();

    public record Provenance(String basis, long observations, long orderCount) {
        public Provenance {
            Objects.requireNonNull(basis);
            if (observations < 1 || orderCount < 1)
                throw new IllegalArgumentException("Observed evidence required");
        }
    }

    public record Comparison(TacticalPrinciple.Warning warning, OrderPlan alternative,
                             Provenance provenance, PlanEvaluation baselineEvaluation,
                             PlanEvaluation alternativeEvaluation, Map<String, Double> scenarioDeltas,
                             Double meanDelta, Double worstDelta, Double scoreDelta,
                             String diagnostic) {
        public Comparison {
            Objects.requireNonNull(warning);
            scenarioDeltas = Collections.unmodifiableMap(new LinkedHashMap<>(scenarioDeltas));
            Objects.requireNonNull(diagnostic);
        }
        public Double adjustedScoreDelta() {
            return baselineEvaluation == null ? null
                    : alternativeEvaluation.score() - baselineEvaluation.score();
        }
        public Double penaltyDelta() {
            return baselineEvaluation == null ? null
                    : alternativeEvaluation.penaltyTotal() - baselineEvaluation.penaltyTotal();
        }
        public Double positionalDelta() {
            return baselineEvaluation == null ? null
                    : (alternativeEvaluation.shapedScore() - alternativeEvaluation.baseScore())
                    - (baselineEvaluation.shapedScore() - baselineEvaluation.baseScore());
        }
        public Double humanDelta() {
            return baselineEvaluation == null ? null
                    : alternativeEvaluation.humanContribution() - baselineEvaluation.humanContribution();
        }
    }

    public record Result(List<Comparison> comparisons, int comparisonsEvaluated,
                         int scenarioEvaluations, boolean truncated) {
        public Result { comparisons = List.copyOf(comparisons); }
    }

    /** Explicit opt-in. Limits cap paired adjudications, never scenario weights. */
    public static Result analyze(BoardState board, GameMoment moment, OrderPlan plan,
                                 Map<UnitId, RoutePrediction> observedChoices,
                                 Map<String, List<Order>> scenarios,
                                 Map<Province, Double> objectives, OutcomeEvaluator evaluator,
                                 MovementProcessor processor, boolean compareAlternatives,
                                 int comparisonLimit, int scenarioEvaluationLimit) {
        if (comparisonLimit < 0 || scenarioEvaluationLimit < 0)
            throw new IllegalArgumentException("Limits must be non-negative");
        comparisonLimit = Math.min(comparisonLimit, MAX_COMPARISONS);
        scenarioEvaluationLimit = Math.min(scenarioEvaluationLimit, MAX_SCENARIO_EVALUATIONS);
        List<Comparison> results = new ArrayList<>();
        int compared = 0, work = 0;
        boolean truncated = false;
        for (var warning : TacticalBias.inspect(board, plan)) {
            var status = TacticalPrinciple.EvaluationStatus.NOT_REQUESTED;
            OrderPlan alternative = null;
            Provenance provenance = null;
            PlanEvaluation baseline = null, replacement = null;
            Map<String, Double> deltas = new LinkedHashMap<>();
            String diagnostic = "Alternative comparison was not requested.";
            if (compareAlternatives) {
                Order suggested = warning.suggestedAlternative();
                status = TacticalPrinciple.EvaluationStatus.UNAVAILABLE;
                diagnostic = suggested == null
                        ? "No safe automatic alternative is available; not evaluated."
                        : "No observed support-hold choice is available; not evaluated.";
                RoutePrediction evidence = suggested == null ? null : observedChoices.get(suggested.unit());
                Long count = evidence == null ? null : evidence.counts().get(suggested);
                if (suggested != null && count != null && count > 0 && evidence.observations() > 0) {
                    provenance = new Provenance(evidence.basis(), evidence.observations(), count);
                    List<Order> orders = plan.orders().stream().map(order ->
                            order.unit().equals(suggested.unit()) ? suggested : order).toList();
                    double preference = 0;
                    boolean observed = true;
                    for (Order order : orders) {
                        RoutePrediction prediction = observedChoices.get(order.unit());
                        Long n = prediction == null ? null : prediction.counts().get(order);
                        if (n == null || n < 1 || prediction.observations() < 1) {
                            observed = false;
                            break;
                        }
                        preference += Math.log((double) n / prediction.observations());
                    }
                    if (observed) {
                        alternative = new OrderPlan(plan.nation(), orders, preference);
                        Optional<String> problem = CoordinatedOrders.coordinationProblem(board, alternative);
                        if (problem.isPresent()) {
                            problem = Optional.of("Alternative plan is not structurally coordinated: "
                                    + problem.get());
                        } else {
                            Optional<String> originalProblem =
                                    CoordinatedOrders.coordinationProblem(board, plan);
                            if (originalProblem.isPresent())
                                problem = Optional.of("Original plan is not structurally coordinated: "
                                        + originalProblem.get());
                        }
                        if (!moment.gamePhase().isMovement() || problem.isPresent()) {
                            status = TacticalPrinciple.EvaluationStatus.INVALID;
                            diagnostic = problem.orElse("Comparison requires a movement phase.");
                        } else if (scenarios.isEmpty()) {
                            diagnostic = "No explicit opponent scenarios; not evaluated.";
                        } else if (compared >= comparisonLimit
                                || (long) work + 2L * scenarios.size() > scenarioEvaluationLimit) {
                            status = TacticalPrinciple.EvaluationStatus.LIMIT_REACHED;
                            diagnostic = "Alternative comparison work limit reached.";
                            truncated = true;
                        } else {
                            var evaluations = evaluator.rank(board, moment, List.of(plan, alternative),
                                   scenarios, objectives, processor, observedChoices);
                            baseline = evaluations.stream().filter(value -> value.plan().equals(plan))
                                    .findFirst().orElseThrow();
                            replacement = evaluations.stream().filter(value ->
                                    value.plan().orders().equals(orders)).findFirst().orElseThrow();
                            for (String name : baseline.scenarioScores().keySet())
                                deltas.put(name, replacement.scenarioScores().get(name)
                                        - baseline.scenarioScores().get(name));
                            compared++;
                            work += 2 * scenarios.size();
                            status = TacticalPrinciple.EvaluationStatus.EVALUATED;
                            diagnostic = "Paired comparison uses identical explicit scenarios, objectives "
                                    + "and evaluator; equal weights are not probabilities.";
                        }
                    } else {
                        diagnostic = "A replacement plan order lacks observed evidence; not evaluated.";
                    }
                }
            }
            var updated = new TacticalPrinciple.Warning(warning.id(), warning.category(),
                    warning.severity(), warning.orders(), warning.explanation(),
                    warning.suggestedAlternative(), status);
            results.add(new Comparison(updated, alternative, provenance, baseline, replacement,
                    deltas, baseline == null ? null : replacement.mean() - baseline.mean(),
                    baseline == null ? null : replacement.worst() - baseline.worst(),
                    baseline == null ? null : replacement.baseScore() - baseline.baseScore(), diagnostic));
        }
        return new Result(results, compared, work, truncated);
    }
}
