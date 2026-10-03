package testing;

import analysis.*;
import domain.*;
import game.BoardState;
import phase.*;
import java.util.*;
import static testing.BackendDiagnosticsSelfCheck.*;

public final class TacticalPrinciplesSelfCheck {
    private TacticalPrinciplesSelfCheck() { }

    public static void main(String[] args) {
        UnitId eng = unit(Nation.ENGLAND, UnitType.FLEET, Province.ENG);
        UnitId nth = unit(Nation.ENGLAND, UnitType.FLEET, Province.NTH);
        UnitId yor = unit(Nation.ENGLAND, UnitType.ARMY, Province.Yor);
        UnitId nwg = unit(Nation.FRANCE, UnitType.FLEET, Province.NWG);
        UnitId nwy = unit(Nation.FRANCE, UnitType.FLEET, Province.Nwy);
        BoardState board = board(eng, nth, yor, nwg, nwy);
        Order move = Order.move(eng, Province.NTH);
        Order convoy = Order.convoy(nth, Province.Yor, Province.Bel);
        Order army = Order.move(yor, Province.Bel);
        Order support = Order.supportHold(eng, Province.NTH);
        OrderPlan plan = new OrderPlan(Nation.ENGLAND, List.of(move, convoy, army), 0);
        var evidence = predictions(move, support, convoy, army);
        var quiet = Map.of("hold", List.of(Order.hold(nwg), Order.hold(nwy)));
        var attack = Map.of("attack", List.of(Order.move(nwg, Province.NTH),
                Order.supportMove(nwy, Province.NWG, Province.NTH)));
        var warning = TacticalAnalysis.FRIENDLY_CONVOY_FLEET.inspect(board, plan).getFirst();
        require(!warning.rejected() && warning.penalty() == 0
                && warning.explanation().contains("does not itself disrupt")
                && warning.explanation().contains("does not reinforce convoy defense and may waste an order")
                && warning.principleId().equals("friendly-convoy-fleet")
                && !warning.id().equals(warning.principleId()),
                "Warning incorrectly bans or claims convoy disruption");
        UnitId edi = unit(Nation.ENGLAND, UnitType.FLEET, Province.Edi);
        Order ediMove = Order.move(edi, Province.NTH);
        Order ediSupport = Order.supportHold(edi, Province.NTH);
        OrderPlan ediPlan = new OrderPlan(Nation.ENGLAND, List.of(ediMove, convoy, army), 0);
        BoardState ediBoard = board(edi, nth, yor, nwg, nwy);
        var ediWarning = TacticalAnalysis.FRIENDLY_CONVOY_FLEET.inspect(ediBoard, ediPlan).getFirst();
        require(ediWarning.orders().contains(ediMove)
                && ediWarning.principleId().equals(warning.principleId())
                && !ediWarning.id().equals(warning.id()),
                "Edi-NTH occurrence or stable principle ID missing");
        var ediComparison = analyze(ediBoard, ediPlan,
                predictions(ediMove, ediSupport, convoy, army), attack,
                Map.of(Province.Bel, 10.0), true, 8, 128).comparisons().getFirst();
        require(ediComparison.warning().evaluatedStatus()
                == TacticalPrinciple.EvaluationStatus.EVALUATED
                && ediComparison.alternative().orders().contains(ediSupport)
                && ediComparison.scoreDelta() > 0,
                "Edi support-hold alternative was not valid and evidence-backed");
        require(CoordinatedOrders.coordinationProblem(board, plan).isEmpty(),
                "Coherent warning plan rejected");
        UnitId iri = unit(Nation.ENGLAND, UnitType.FLEET, Province.IRI);
        Order holdingSupport = Order.supportHold(iri, Province.ENG);
        OrderPlan uncheckedOriginal = new OrderPlan(Nation.ENGLAND,
                List.of(move, convoy, army, holdingSupport), 0);
        var unchecked = analyze(board(eng, nth, yor, iri, nwg, nwy), uncheckedOriginal,
                predictions(move, support, convoy, army, holdingSupport), quiet, Map.of(),
                true, 8, 128).comparisons().getFirst();
        require(unchecked.warning().evaluatedStatus() == TacticalPrinciple.EvaluationStatus.INVALID
                && unchecked.diagnostic().startsWith("Original plan")
                && unchecked.baselineEvaluation() == null,
                "RAW original incorrectly claimed coherent or adjudicated");
        require(!CoordinatedOrders.coordinationProblem(board,
                new OrderPlan(Nation.ENGLAND,
                        List.of(move, convoy, Order.move(yor, Province.Nwy)), 0)).isEmpty(),
                "Mismatched convoy accepted");
        var disabled = analyze(board, plan, evidence, quiet, Map.of(), false, 8, 128);
        require(disabled.scenarioEvaluations() == 0
                && disabled.comparisons().getFirst().warning().evaluatedStatus()
                == TacticalPrinciple.EvaluationStatus.NOT_REQUESTED, "Opt-in not respected");
        var unavailable = analyze(board, plan, predictions(move, convoy, army),
                quiet, Map.of(), true, 8, 128).comparisons().getFirst();
        require(unavailable.warning().evaluatedStatus() == TacticalPrinciple.EvaluationStatus.UNAVAILABLE
                && unavailable.scoreDelta() == null && unavailable.provenance() == null,
                "Absent evidence fabricated");
        var equal = analyze(board, plan, evidence, quiet, Map.of(Province.Bel, 10.0),
                true, 8, 128);
        require(equal.comparisonsEvaluated() == 1 && equal.scenarioEvaluations() == 2
                && equal.comparisons().getFirst().scoreDelta() == 0,
                "Equal comparison failed");
        var better = analyze(board, plan, evidence, attack, Map.of(Province.Bel, 10.0),
                true, 8, 128).comparisons().getFirst();
        require(better.warning().evaluatedStatus() == TacticalPrinciple.EvaluationStatus.EVALUATED
                && better.scoreDelta() > 0, "Evidence-backed improvement not measured");
        var worse = analyze(board, plan, evidence, attack, Map.of(Province.Bel, -10.0),
                true, 8, 128).comparisons().getFirst();
        require(worse.scoreDelta() < 0, "Worse alternative disguised as improvement");
        require(better.provenance().basis().equals("POSITION")
                && better.provenance().orderCount() == 1
                && better.provenance().observations() == 2,
                "Incorrect observed choice provenance");
        require(better.baselineEvaluation().scenarioDetails().get("attack").opponentOrders()
                .equals(better.alternativeEvaluation().scenarioDetails().get("attack").opponentOrders()),
                "Comparison changed scenarios");
        Map<String, List<Order>> paired = new LinkedHashMap<>();
        paired.putAll(attack);
        paired.putAll(quiet);
        var pair = analyze(board, plan, evidence, paired, Map.of(Province.Bel, 10.0),
                true, 8, 128);
        var pairComparison = pair.comparisons().getFirst();
        require(pair.scenarioEvaluations() == 4
                && pairComparison.meanDelta() == pairComparison.scenarioDeltas().values().stream()
                .mapToDouble(Double::doubleValue).average().orElseThrow(),
                "Paired comparison weights or work accounting changed");
        immutable(() -> better.scenarioDeltas().clear());
        immutable(() -> equal.comparisons().clear());
        require(equal.equals(analyze(board, plan, evidence, quiet,
                Map.of(Province.Bel, 10.0), true, 8, 128)), "Comparison nondeterministic");
        var capped = analyze(board, plan, evidence, attack, Map.of(), true, 8, 1);
        require(capped.scenarioEvaluations() == 0 && capped.truncated()
                && capped.comparisons().getFirst().warning().evaluatedStatus()
                == TacticalPrinciple.EvaluationStatus.LIMIT_REACHED, "Work cap ignored");

        UnitId mao = unit(Nation.ENGLAND, UnitType.FLEET, Province.MAO);
        UnitId armyLon = unit(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        UnitId por = unit(Nation.ENGLAND, UnitType.ARMY, Province.Por);
        BoardState invalidBoard = board(mao, armyLon, por);
        Order invalidMove = Order.move(por, Province.MAO);
        Order oceanConvoy = Order.convoy(mao, Province.Lon, Province.Por);
        Order movingArmy = Order.move(armyLon, Province.Por);
        OrderPlan invalid = new OrderPlan(Nation.ENGLAND,
                List.of(invalidMove, oceanConvoy, movingArmy), 0);
        var invalidComparison = analyze(invalidBoard, invalid,
                predictions(invalidMove, Order.supportHold(por, Province.MAO), oceanConvoy, movingArmy),
                Map.of("empty", List.of()), Map.of(), true, 8, 128).comparisons().getFirst();
        require(invalidComparison.warning().evaluatedStatus() == TacticalPrinciple.EvaluationStatus.INVALID
                && invalidComparison.scoreDelta() == null, "Invalid geography evaluated");
        System.out.println("Tactical principles checks passed.");
    }

    private static TacticalAnalysis.Result analyze(BoardState board, OrderPlan plan,
                                                   Map<UnitId, RoutePrediction> evidence,
                                                   Map<String, List<Order>> scenarios,
                                                   Map<Province, Double> objectives,
                                                   boolean compare, int count, int work) {
        return TacticalAnalysis.analyze(board, moment(), plan, evidence, scenarios, objectives,
                new OutcomeEvaluator(0, 0, 0.5), processor(), compare, count, work);
    }
}
