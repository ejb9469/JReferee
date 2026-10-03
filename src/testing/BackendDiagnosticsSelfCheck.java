package testing;

import analysis.*;
import analysis.openings.Classifier;
import domain.*;
import game.*;
import phase.*;
import phase.movement.*;
import java.util.*;

public final class BackendDiagnosticsSelfCheck {
    private BackendDiagnosticsSelfCheck() { }

    public static void main(String[] args) {
        components();
        dependencies();
        System.out.println("Backend diagnostics checks passed.");
    }

    private static void components() {
        UnitId par = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId bur = unit(Nation.GERMANY, UnitType.ARMY, Province.Bur);
        BoardState board = new BoardState(Map.of(par, Province.Par, bur, Province.Bur),
                Map.of(Province.Par, Nation.FRANCE));
        OrderPlan plan = new OrderPlan(Nation.FRANCE, List.of(Order.move(par, Province.Pic)), 0);
        List<Order> hold = new ArrayList<>(List.of(Order.hold(bur)));
        Map<String, List<Order>> scenarios = new LinkedHashMap<>();
        scenarios.put("z hold", hold);
        scenarios.put("a contest", List.of(Order.move(bur, Province.Pic)));
        PlanEvaluation evaluation = new OutcomeEvaluator(1, 3, 0.5).rank(board, moment(),
                List.of(plan), scenarios, Map.of(Province.Pic, 2.0), processor()).getFirst();
        require(evaluation.scenarioDetails().keySet().equals(evaluation.scenarioScores().keySet()),
                "Component coverage differs");
        require(evaluation.mean() == 1 && evaluation.worst() == 0 && evaluation.score() == 0.5,
                "Equal-weight legacy scores changed");
        for (var detail : evaluation.scenarioDetails().values()) {
            require(detail.score() == detail.objectiveDelta() + detail.centerPositionDelta()
                    - detail.dislodgementPenalty(), "Components do not reconcile");
        }
        hold.clear();
        require(evaluation.scenarioDetails().get("z hold").opponentOrders().size() == 1,
                "Opponent orders were not copied");
        immutable(() -> evaluation.scenarioDetails().clear());
        immutable(() -> evaluation.scenarioDetails().get("z hold").opponentOrders().clear());
        require(new PlanEvaluation(plan, evaluation.scenarioScores(), evaluation.mean(),
                evaluation.worst(), evaluation.score()).scenarioDetails().isEmpty(),
                "Legacy constructor incompatible");
        Map<String, List<Order>> reversed = new LinkedHashMap<>();
        reversed.put("a contest", scenarios.get("a contest"));
        reversed.put("z hold", List.of(Order.hold(bur)));
        require(evaluation.equals(new OutcomeEvaluator(1, 3, 0.5).rank(board, moment(),
                List.of(plan), reversed, Map.of(Province.Pic, 2.0), processor()).getFirst()),
                "Scenario details are not deterministic");
        UnitId picCenter = unit(Nation.FRANCE, UnitType.ARMY, Province.Pic);
        var center = new OutcomeEvaluator(1, 3, 0).rank(board(picCenter), moment(),
                List.of(new OrderPlan(Nation.FRANCE,
                        List.of(Order.move(picCenter, Province.Bel)), 0)),
                Map.of("none", List.of()), Map.of(Province.Bel, 3.0), processor())
                .getFirst().scenarioDetails().get("none");
        require(center.objectiveDelta() == 3 && center.centerPositionDelta() == 1
                && center.score() == 4, "Objective and center components conflated");
        UnitId gas = unit(Nation.GERMANY, UnitType.ARMY, Province.Gas);
        var dislodged = new OutcomeEvaluator(1, 3, 0).rank(
                new BoardState(Map.of(par, Province.Par, bur, Province.Bur, gas, Province.Gas),
                        Map.of(Province.Par, Nation.FRANCE)), moment(),
                List.of(new OrderPlan(Nation.FRANCE, List.of(Order.hold(par)), 0)),
                Map.of("attack", List.of(Order.move(bur, Province.Par),
                        Order.supportMove(gas, Province.Bur, Province.Par))),
                Map.of(Province.Par, 2.0), processor())
                .getFirst().scenarioDetails().get("attack");
        require(dislodged.dislodgedUnits() == 1 && dislodged.dislodgementPenalty() == 3
                && dislodged.objectiveDelta() == -2 && dislodged.centerPositionDelta() == -1
                && dislodged.score() == -6, "Dislodgement components incorrect");

        UnitId pic = unit(Nation.FRANCE, UnitType.ARMY, Province.Pic);
        OrderPlan bounce = new OrderPlan(Nation.FRANCE,
                List.of(Order.move(par, Province.Bur), Order.move(pic, Province.Bur)), 0);
        require(CoordinatedOrders.coordinationProblem(board(par, pic), bounce).isEmpty(),
                "Intentional bounce banned");
    }

    private static void dependencies() {
        UnitId lon = unit(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        UnitId yor = unit(Nation.ENGLAND, UnitType.ARMY, Province.Yor);
        UnitId eng = unit(Nation.FRANCE, UnitType.FLEET, Province.ENG);
        UnitId nth = unit(Nation.FRANCE, UnitType.FLEET, Province.NTH);
        BoardState board = board(lon, eng, nth);
        var generated = conditional(board, predictions(Order.move(lon, Province.Bel)));
        var dependencies = generated.diagnostics().foreignDependencies()
                .get(Classifier.signature(board, generated.plans().getFirst().orders()));
        require(dependencies.alternatives().size() == 2 && !dependencies.truncated(),
                "Alternative foreign convoy routes missing");
        var coverage = dependencies.coverage(Map.of(
                "ENG", List.of(Order.convoy(eng, Province.Lon, Province.Bel), Order.hold(nth)),
                "NTH", List.of(Order.hold(eng), Order.convoy(nth, Province.Lon, Province.Bel)),
                "wrong", List.of(Order.convoy(eng, Province.Yor, Province.Bel), Order.hold(nth))));
        require(coverage.satisfiedCount() == 2 && coverage.scenarioCount() == 3,
                "Exact convoy endpoints or alternative coverage incorrect");
        immutable(() -> dependencies.alternatives().clear());
        immutable(() -> dependencies.alternatives().getFirst().clear());
        immutable(() -> generated.diagnostics().foreignDependencies().clear());
        var repeat = conditional(board, predictions(Order.move(lon, Province.Bel)));
        require(generated.diagnostics().foreignDependencies().equals(
                repeat.diagnostics().foreignDependencies()), "Dependencies nondeterministic");

        BoardState pair = board(lon, yor, eng, nth);
        var simultaneous = conditional(pair, predictions(
                Order.move(lon, Province.Bel), Order.move(yor, Province.Nwy)));
        var conjunction = simultaneous.diagnostics().foreignDependencies().values().iterator().next();
        require(conjunction.alternatives().stream().allMatch(value -> value.size() == 2),
                "Joint foreign requirements lost");
        var split = conjunction.coverage(Map.of(
                "one", List.of(Order.convoy(eng, Province.Lon, Province.Bel), Order.hold(nth)),
                "two", List.of(Order.hold(eng), Order.convoy(nth, Province.Yor, Province.Nwy)),
                "both", List.of(Order.convoy(eng, Province.Lon, Province.Bel),
                        Order.convoy(nth, Province.Yor, Province.Nwy))));
        require(split.satisfiedCount() == 1 && split.satisfyingScenarios().equals(List.of("both")),
                "Requirements counted across different scenarios");

        UnitId gas = unit(Nation.ENGLAND, UnitType.FLEET, Province.Gas);
        UnitId spa = unit(Nation.FRANCE, UnitType.FLEET, Province.SpaSC);
        BoardState coast = new BoardState(Map.of(gas, Province.Gas, spa, Province.SpaNC), Map.of());
        var coastPlan = conditional(coast, predictions(Order.supportHold(gas, Province.SpaNC)));
        var requirement = coastPlan.diagnostics().foreignDependencies().values().iterator().next()
                .alternatives().getFirst().getFirst();
        require(requirement.origin() == Province.SpaNC && requirement.unit().origin() == Province.SpaSC
                && requirement.constraint() == ForeignDependencies.Constraint.STATIONARY,
                "Current exact coast confused with unit creation origin");
        require(requirement.satisfiedBy(List.of(Order.supportHold(spa, Province.Gas)))
                && !requirement.satisfiedBy(List.of(Order.move(spa, Province.Gas))),
                "Stationary support semantics incorrect");
        require(conditional(coast, predictions(Order.supportHold(gas, Province.SpaSC))).plans().isEmpty(),
                "Wrong coast accepted");
        require(!CoordinatedOrders.Diagnostics.raw().dependenciesChecked(),
                "RAW diagnostics imply dependency-free");
        UnitId nwg = unit(Nation.ENGLAND, UnitType.FLEET, Province.NWG);
        var affected = conditional(board(yor, nwg, nth), predictions(
                Order.move(yor, Province.Nwy),
                Order.supportMove(nwg, Province.Yor, Province.Nwy)))
                .diagnostics().foreignDependencies().values().iterator().next()
                .alternatives().getFirst().getFirst().affectedFriendlyOrders();
        List<String> signatures = affected.stream()
                .map(order -> Classifier.signature(board(yor, nwg, nth), List.of(order))).toList();
        require(affected.size() == 2 && signatures.equals(signatures.stream().sorted().toList()),
                "Affected friendly orders follow UUID hash order");

        List<UnitId> crowded = new ArrayList<>(List.of(yor, nth));
        for (Province sea : List.of(Province.ADR, Province.AEG, Province.BAL, Province.BLA,
                Province.BOT, Province.EAS, Province.ENG, Province.HEL, Province.ION,
                Province.LYO, Province.MAO, Province.NAO, Province.NWG, Province.TYS, Province.WES))
            crowded.add(unit(Nation.FRANCE, UnitType.FLEET, sea));
        var bounded = conditional(board(crowded.toArray(UnitId[]::new)),
                predictions(Order.move(yor, Province.Nwy)));
        require(bounded.plans().size() == 1
                && bounded.diagnostics().dependencySearchTruncated()
                && bounded.diagnostics().dependencyAssignmentsExamined()
                <= 2L * CoordinatedOrders.FOREIGN_ASSIGNMENT_LIMIT,
                "Feasibility and alternative collection exceeded their shared per-check budget");
        var boundedDependencies = bounded.diagnostics().foreignDependencies().values().iterator().next();
        require(boundedDependencies.truncated()
                && boundedDependencies.coverage(Map.of("known",
                List.of(Order.convoy(nth, Province.Yor, Province.Nwy)))).satisfiedCount() == 1,
                "Feasible first conjunction lost when alternative search was truncated");
    }

    private static CoordinatedOrders.Result conditional(BoardState board,
                                                        Map<UnitId, RoutePrediction> predictions) {
        return new CoordinatedOrders(4, 32).generate(board, Nation.ENGLAND,
                predictions, CoordinationMode.CONDITIONAL);
    }

    static UnitId unit(Nation nation, UnitType type, Province province) {
        return new UnitId(UUID.nameUUIDFromBytes((nation + ":" + type + ":" + province)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)), nation, type, province);
    }

    static BoardState board(UnitId... units) {
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        for (UnitId unit : units)
            locations.put(unit, unit.origin());
        return new BoardState(locations, Map.of());
    }

    static Map<UnitId, RoutePrediction> predictions(Order... orders) {
        Map<UnitId, Map<Order, Long>> counts = new LinkedHashMap<>();
        for (Order order : orders)
            counts.computeIfAbsent(order.unit(), ignored -> new LinkedHashMap<>()).put(order, 1L);
        Map<UnitId, RoutePrediction> result = new LinkedHashMap<>();
        counts.forEach((unit, choices) -> result.put(unit, new RoutePrediction("POSITION", 0, choices)));
        return result;
    }

    static GameMoment moment() { return new GameMoment(1901, GamePhase.SPRING_MOVEMENT); }
    static MovementProcessor processor() {
        return new MovementProcessor(MovementProcessor.Policy.JUSTICE, 1, 1L);
    }
    static void immutable(Runnable action) {
        try { action.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Mutable diagnostic");
    }
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
