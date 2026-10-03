package testing;

import analysis.*;
import domain.*;
import game.*;
import phase.*;
import phase.movement.MovementProcessor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PreferenceSearchSelfCheck {
    private PreferenceSearchSelfCheck() { }

    public static void main(String[] args) {
        rareRecovery();
        boundedGuidance();
        hardCoordination();
        configurationMismatch();
        humanNearOutcomeReordering();
        pairedComparison();
        ablation();
        System.out.println("PreferenceSearch checks passed.");
    }

    private static void rareRecovery() {
        for (var fixture : PreferenceAblation.syntheticValidation()) {
            UnitId fleet = fixture.board().locations().keySet().iterator().next();
            var evidence = fixture.trainingEvidence();
            Order rare = evidence.get(fleet).counts().entrySet().stream()
                    .filter(entry -> entry.getValue() == 1).findFirst().orElseThrow().getKey();
            var baseline = new CoordinatedOrders(1, 1, 5).generate(fixture.board(),
                    fixture.nation(), evidence, CoordinationMode.STRICT);
            require(baseline.plans().getFirst().orders().equals(List.of(Order.hold(fleet))),
                    "Rare-move baseline fixture changed");
            var guided = new CoordinatedOrders(1, 1, 5,
                    PreferenceAblation.syntheticConfiguration()).generate(
                    fixture.board(), fixture.nation(), evidence, CoordinationMode.STRICT);
            require(guided.plans().getFirst().orders().equals(List.of(rare)),
                    "Evidenced rare " + rare.target() + " was not recovered with choices=beam=1");
            require(guided.diagnostics().geographyGuided()
                            && guided.diagnostics().replenishedChoices() == 1
                            && guided.diagnostics().omittedChoices() == 0
                            && guided.diagnostics().beamTruncated(),
                    "Guidance diagnostics lost bounded domain/beam information");
            var raw = new CoordinatedOrders(1, 1, 5,
                    PreferenceAblation.syntheticConfiguration()).generate(
                    fixture.board(), fixture.nation(), evidence, CoordinationMode.RAW);
            require(raw.plans().equals(new JointOrders(1, 1).generate(
                            fixture.board(), fixture.nation(), evidence))
                            && !raw.diagnostics().geographyGuided(),
                    "RAW used geographic guidance");
            var zero = new CoordinatedOrders(1, 1, 5, ScoringConfiguration.defaults()).generate(
                    fixture.board(), fixture.nation(), evidence, CoordinationMode.STRICT);
            require(baseline.equals(zero), "Zero configuration changed legacy search");
            Set<UnitType> fleets = Set.of(UnitType.FLEET);
            for (var inactive : List.of(
                    new ScoringConfiguration(0, Map.of(rare.target(),
                            new ScoringConfiguration.ProvinceValue(0, fleets)), Map.of()),
                    new ScoringConfiguration(0, Map.of(rare.target(),
                            new ScoringConfiguration.ProvinceValue(-0.0, fleets)), Map.of()),
                    new ScoringConfiguration(0, Map.of(), Map.of(Nation.FRANCE,
                            new ScoringConfiguration.NationProfile(Map.of(rare.target(),
                                    new ScoringConfiguration.ProvinceValue(10, fleets)), List.of()))),
                    new ScoringConfiguration(0, Map.of(rare.target(),
                            new ScoringConfiguration.ProvinceValue(10, fleets)), Map.of(Nation.ENGLAND,
                            new ScoringConfiguration.NationProfile(Map.of(rare.target(),
                                    new ScoringConfiguration.ProvinceValue(-10, fleets)), List.of()))))) {
                require(!inactive.hasGeography(fixture.nation()), "Inactive geography activated guidance");
                var unchanged = new CoordinatedOrders(1, 1, 5, inactive).generate(
                        fixture.board(), fixture.nation(), evidence, CoordinationMode.STRICT);
                require(baseline.equals(unchanged),
                        "Foreign-only, explicit zero or cancelled geography changed the entire legacy Result");
            }
            var oldRanking = new OutcomeEvaluator(1, 3, 0.5, 5).rank(
                    fixture.board(), fixture.moment(), baseline.plans(), fixture.scenarios(),
                    Map.of(), new MovementProcessor());
            var zeroRanking = new OutcomeEvaluator(1, 3, 0.5, 5,
                    ScoringConfiguration.defaults()).rank(
                    fixture.board(), fixture.moment(), baseline.plans(), fixture.scenarios(),
                    Map.of(), new MovementProcessor());
            require(oldRanking.equals(zeroRanking), "Zero configuration changed legacy evaluator");
            var preference = HumanPreference.evaluate(fixture.board(),
                    guided.plans().getFirst(), evidence);
            require(preference.available() && preference.units().getFirst().selectedCount() == 1
                            && preference.units().getFirst().denominator() == 100,
                    "Candidate truncation changed the full evidence denominator");
            near(preference.score(), Math.log(0.01), "Rare log frequency");
            var unobserved = Map.of(fleet, new RoutePrediction("TRAINING", 0,
                    Map.of(Order.hold(fleet), 100L)));
            var absent = new CoordinatedOrders(1, 1, 5,
                    PreferenceAblation.syntheticConfiguration()).generate(
                    fixture.board(), fixture.nation(), unobserved, CoordinationMode.STRICT);
            require(absent.plans().getFirst().orders().equals(List.of(Order.hold(fleet)))
                            && absent.diagnostics().replenishedChoices() == 0,
                    "Valuation fabricated missing historical evidence");
            var irrelevant = new ScoringConfiguration(0, Map.of(Province.Vie,
                    new ScoringConfiguration.ProvinceValue(10, Set.of(UnitType.FLEET))), Map.of());
            var omitted = new CoordinatedOrders(1, 1, 5, irrelevant).generate(
                    fixture.board(), fixture.nation(), evidence, CoordinationMode.STRICT);
            require(omitted.plans().equals(baseline.plans())
                            && omitted.diagnostics().omittedChoices() == 1,
                    "Unvalued omitted move was not diagnosed");
            Map<Order, Long> reversedCounts = new LinkedHashMap<>();
            reversedCounts.put(rare, 1L);
            reversedCounts.put(Order.hold(fleet), 99L);
            var reversed = new CoordinatedOrders(1, 1, 5,
                    PreferenceAblation.syntheticConfiguration()).generate(fixture.board(),
                    fixture.nation(), Map.of(fleet, new RoutePrediction("TRAINING", 0,
                            reversedCounts)), CoordinationMode.STRICT);
            require(reversed.plans().equals(guided.plans())
                            && reversed.diagnostics().equals(guided.diagnostics()),
                    "Evidence insertion order changed geographic search");
        }
    }

    private static void hardCoordination() {
        UnitId nth = unit(Nation.ENGLAND, UnitType.FLEET, Province.NTH);
        UnitId yor = unit(Nation.ENGLAND, UnitType.ARMY, Province.Yor);
        UnitId bel = unit(Nation.ENGLAND, UnitType.ARMY, Province.Bel);
        UnitId pic = unit(Nation.ENGLAND, UnitType.ARMY, Province.Pic);
        var human = new ScoringConfiguration(1_000_000, Map.of(), Map.of());
        var search = new CoordinatedOrders(1, 1, 5, human);
        require(search.generate(board(nth, yor), Nation.ENGLAND,
                        predictions(Order.convoy(nth, Province.Yor, Province.Bel),
                                Order.move(yor, Province.Nwy)), CoordinationMode.STRICT)
                        .plans().isEmpty(),
                "Human preference bypassed mismatched convoy hard check");
        require(search.generate(board(bel, pic), Nation.ENGLAND,
                        predictions(Order.supportMove(bel, Province.Pic, Province.Bur),
                                Order.move(pic, Province.Par)), CoordinationMode.STRICT)
                        .plans().isEmpty(),
                "Human preference bypassed mismatched support hard check");
    }

    private static void boundedGuidance() {
        UnitId nth = unit(Nation.ENGLAND, UnitType.FLEET, Province.NTH);
        UnitId yor = unit(Nation.ENGLAND, UnitType.ARMY, Province.Yor);
        Map<Order, Long> counts = new LinkedHashMap<>();
        counts.put(Order.hold(nth), 1000L);
        int supportCount = CoordinatedOrders.GEOGRAPHIC_CHOICE_LIMIT + 1;
        require(Province.values().length >= supportCount, "Not enough distinct support destinations");
        for (int index = 0; index < supportCount; index++)
            counts.put(Order.supportMove(nth, Province.Yor, Province.values()[index]), 10L);
        Order rare = Order.move(nth, Province.NWG);
        counts.put(rare, 1L);
        var evidence = Map.of(nth, new RoutePrediction("TRAINING wide support distribution", 0, counts),
                yor, new RoutePrediction("TRAINING", 0, Map.of(Order.hold(yor), 1L)));
        var fixture = new PreferenceAblation.ValidationFixture("bounded guidance", board(nth, yor),
                moment(), Nation.ENGLAND, evidence, Map.of("quiet", List.of()), Map.of());
        var results = PreferenceAblation.evaluate(List.of(fixture),
                PreferenceAblation.syntheticConfiguration(),
                new PreferenceAblation.Limits(1, 1, CoordinationMode.STRICT));
        var guided = results.get(2);
        long unexamined = counts.size() - CoordinatedOrders.GEOGRAPHIC_CHOICE_LIMIT;
        require(guided.available() && guided.diagnostics().guidanceChoicesUnexamined() == unexamined
                        && guided.diagnostics().omittedChoices() == counts.size() - 1
                        && guided.diagnostics().replenishedChoices() == 0
                        && !guided.ranked().getFirst().plan().orders().contains(rare),
                "Guidance cap did not expose the unexamined rare valued choice");
        var frequency = guided.ranked().getFirst().humanPreference().units().stream()
                .filter(unit -> unit.unit().equals(nth)).findFirst().orElseThrow();
        require(frequency.selectedCount() == 1000
                        && frequency.denominator() == 1001 + 10L * supportCount
                        && counts.equals(evidence.get(nth).counts()),
                "Bounded guidance fabricated evidence or truncated its denominator");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PreferenceAblation.print(results, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        require(bytes.toString(StandardCharsets.UTF_8).contains("guidanceUnexamined=" + unexamined),
                "Ablation report omitted bounded guidance diagnostic");
    }

    private static void configurationMismatch() {
        var fixture = PreferenceAblation.syntheticValidation().getFirst();
        var configuration = new MovementStrategy.Configuration(
                PredictionPolicy.ORIGINAL, 1, 1, 1, 1, CoordinationMode.STRICT,
                5, PreferenceAblation.syntheticConfiguration());
        require(rejects(() -> MovementStrategy.recommend(
                        new RoutePreferences(null, 4, true, true), "standard",
                        fixture.moment(), fixture.board(), fixture.nation(), Map.of(), Map.of(),
                        configuration, new OutcomeEvaluator(1, 3, 0.5, 5),
                        new MovementProcessor())),
                "Strategy/evaluator preference mismatch accepted");
    }

    private static void pairedComparison() {
        UnitId eng = unit(Nation.ENGLAND, UnitType.FLEET, Province.ENG);
        UnitId nth = unit(Nation.ENGLAND, UnitType.FLEET, Province.NTH);
        UnitId yor = unit(Nation.ENGLAND, UnitType.ARMY, Province.Yor);
        UnitId nwg = unit(Nation.FRANCE, UnitType.FLEET, Province.NWG);
        UnitId nwy = unit(Nation.FRANCE, UnitType.FLEET, Province.Nwy);
        BoardState board = board(eng, nth, yor, nwg, nwy);
        Order move = Order.move(eng, Province.NTH);
        Order support = Order.supportHold(eng, Province.NTH);
        Order convoy = Order.convoy(nth, Province.Yor, Province.Bel);
        Order army = Order.move(yor, Province.Bel);
        Map<UnitId, RoutePrediction> evidence = predictions(move, support, convoy, army);
        evidence.put(eng, new RoutePrediction("TRAINING full selected evidence", 0,
                Map.of(move, 9L, support, 1L)));
        Map<String, List<Order>> scenarios = new LinkedHashMap<>();
        scenarios.put("quiet", List.of(Order.hold(nwg), Order.hold(nwy)));
        scenarios.put("attack", List.of(Order.move(nwg, Province.NTH),
                Order.supportMove(nwy, Province.NWG, Province.NTH)));
        var config = new ScoringConfiguration(0.5, Map.of(Province.Bel,
                new ScoringConfiguration.ProvinceValue(7, Set.of(UnitType.ARMY))), Map.of());
        var evaluator = new OutcomeEvaluator(1, 3, 0.5, 5, config);
        OrderPlan plan = new OrderPlan(Nation.ENGLAND, List.of(move, convoy, army),
                Math.log(0.9));
        var result = TacticalAnalysis.analyze(board, moment(), plan, evidence, scenarios, Map.of(),
                evaluator, new MovementProcessor(), true, 8, 128);
        require(result.comparisonsEvaluated() == 1 && result.scenarioEvaluations() == 4,
                "Paired comparison did not freeze both explicit scenarios");
        var comparison = result.comparisons().getFirst();
        require(comparison.baselineEvaluation().scoringConfiguration().equals(config)
                        && comparison.alternativeEvaluation().scoringConfiguration().equals(config)
                        && comparison.baselineEvaluation().scenarioScores().keySet()
                        .equals(comparison.alternativeEvaluation().scenarioScores().keySet()),
                "Comparison configuration/scenario mismatch");
        near(comparison.adjustedScoreDelta(),
                comparison.scoreDelta() + comparison.positionalDelta()
                        - comparison.penaltyDelta() + comparison.humanDelta(),
                "Raw/position/penalty/human decomposition");
        for (var value : List.of(comparison.baselineEvaluation(),
                comparison.alternativeEvaluation()))
            near(value.score(), value.shapedScore() - value.penaltyTotal()
                    + value.humanContribution(), "Plan score decomposition");
        Map<String, List<Order>> reversed = new LinkedHashMap<>();
        reversed.put("attack", scenarios.get("attack"));
        reversed.put("quiet", scenarios.get("quiet"));
        require(result.equals(TacticalAnalysis.analyze(board, moment(), plan, evidence, reversed,
                        Map.of(), evaluator, new MovementProcessor(), true, 8, 128)),
                "Scenario insertion order changed paired comparison");
    }

    private static void humanNearOutcomeReordering() {
        UnitId french = unit(Nation.FRANCE, UnitType.ARMY, Province.Mar);
        UnitId german = unit(Nation.GERMANY, UnitType.ARMY, Province.Bur);
        BoardState board = new BoardState(Map.of(french, Province.Par, german, Province.Bur),
                Map.of(Province.Par, Nation.FRANCE));
        Order advance = Order.move(french, Province.Pic);
        Order stay = Order.hold(french);
        var evidence = Map.of(french, new RoutePrediction("TRAINING", 0, Map.of(stay, 9L, advance, 1L)));
        OrderPlan move = new OrderPlan(Nation.FRANCE, List.of(advance), Math.log(0.1));
        OrderPlan hold = new OrderPlan(Nation.FRANCE, List.of(stay), Math.log(0.9));
        Map<String, List<Order>> scenarios = Map.of(
                "quiet", List.of(Order.hold(german)),
                "contest", List.of(Order.move(german, Province.Pic)));
        var raw = new OutcomeEvaluator(1, 3, 0.5, 5).rank(board, moment(),
                List.of(move, hold), scenarios, Map.of(Province.Pic, 0.1),
                new MovementProcessor(), evidence);
        var human = new OutcomeEvaluator(1, 3, 0.5, 5,
                new ScoringConfiguration(0.5, Map.of(), Map.of())).rank(
                board, moment(), List.of(move, hold), scenarios, Map.of(Province.Pic, 0.1),
                new MovementProcessor(), evidence);
        require(raw.getFirst().plan().equals(move) && raw.getFirst().score() > raw.getLast().score()
                        && human.getFirst().plan().equals(hold),
                "Human weight must reverse a strict near-outcome advantage, not merely a tie");
        for (var before : raw) {
            var after = human.stream().filter(value -> value.plan().equals(before.plan()))
                    .findFirst().orElseThrow();
            require(before.scenarioScores().equals(after.scenarioScores()),
                    "Human preference changed actual adjudicated outcomes");
            near(before.baseScore(), after.baseScore(), "Human preference changed raw score");
        }
        near(human.getFirst().humanPreference().score(), Math.log(0.9),
                "Human reordered-plan frequency");
        UnitId fleet = unit(Nation.ENGLAND, UnitType.FLEET, Province.Lon);
        var fixture = new PreferenceAblation.ValidationFixture("moved fleet",
                new BoardState(Map.of(fleet, Province.NTH), Map.of()), moment(), Nation.ENGLAND,
                Map.of(fleet, new RoutePrediction("TRAINING at NTH", 0,
                        Map.of(Order.hold(fleet), 99L, Order.move(fleet, Province.NWG), 1L))),
                Map.of("quiet", List.of()), Map.of());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PreferenceAblation.print(PreferenceAblation.evaluate(List.of(fixture),
                        PreferenceAblation.syntheticConfiguration(),
                        new PreferenceAblation.Limits(1, 1, CoordinationMode.STRICT)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String report = bytes.toString(StandardCharsets.UTF_8);
        require(report.contains("En F NTH") && report.contains("NWG")
                        && !report.contains("En F Lon"),
                "Report confused creation origin with current evidence query/order location");
    }

    private static void ablation() {
        var fixtures = PreferenceAblation.syntheticValidation();
        var limits = new PreferenceAblation.Limits(1, 1, CoordinationMode.STRICT);
        var configuration = PreferenceAblation.syntheticConfiguration();
        var results = PreferenceAblation.evaluate(fixtures, configuration, limits);
        require(results.size() == 15, "Expected three positions times five variants");
        var repeated = PreferenceAblation.evaluate(fixtures, configuration, limits);
        for (int index = 0; index < results.size(); index++) {
            var a = results.get(index);
            var b = repeated.get(index);
            require(a.available() && !a.referenceMeasured()
                            && a.scenarioCount() == 1 && a.ranked().equals(b.ranked())
                            && a.diagnostics().equals(b.diagnostics())
                            && a.commonPlans() == b.commonPlans()
                            && a.commonRankChanges() == b.commonRankChanges(),
                    "Ablation counts, scores or reference availability are not deterministic");
        }
        var first = fixtures.getFirst();
        UnitId fleet = first.board().locations().keySet().iterator().next();
        var labelled = new PreferenceAblation.ValidationFixture("labelled", first.board(),
                first.moment(), first.nation(), first.trainingEvidence(), first.scenarios(),
                Map.of(fleet, Order.hold(fleet)));
        var labelledResults = PreferenceAblation.evaluate(List.of(labelled), configuration, limits);
        require(labelledResults.getFirst().referenceMeasured()
                        && labelledResults.getFirst().humanReferenceMatches() == 1
                        && labelledResults.getFirst().humanReferenceDenominator() == 1
                        && labelledResults.get(2).humanReferenceMatches() == 0,
                "Actual human reference-order match count/denominator missing");
        for (int index = 0; index < 5; index++)
            require(labelledResults.get(index).ranked().equals(results.get(index).ranked()),
                    "Reference labels leaked into candidate selection or scoring");
        var wide = PreferenceAblation.evaluate(List.of(first), configuration,
                new PreferenceAblation.Limits(2, 2, CoordinationMode.STRICT));
        require(wide.get(2).commonPlans() == 2 && wide.get(2).commonRankChanges() == 2,
                "Common-signature ranking changes were not measured");
        var missing = new PreferenceAblation.ValidationFixture("no evidence", first.board(),
                first.moment(), first.nation(), Map.of(), first.scenarios(), Map.of());
        require(PreferenceAblation.evaluate(List.of(missing), configuration, limits).stream()
                        .noneMatch(PreferenceAblation.Result::available),
                "Missing corpus evidence should abstain for all variants");
        require(rejects(() -> new PreferenceAblation.Limits(9, 1, CoordinationMode.STRICT))
                        && rejects(() -> new PreferenceAblation.Limits(1, 33, CoordinationMode.STRICT))
                        && rejects(() -> PreferenceAblation.evaluate(
                                Collections.nCopies(17, first), configuration, limits)),
                "Hard experiment work caps accepted oversized requests");
        Map<String, List<Order>> manyScenarios = new LinkedHashMap<>();
        for (int index = 0; index < 16; index++)
            manyScenarios.put("fixed scenario " + index, List.of());
        List<PreferenceAblation.ValidationFixture> expensive = fixtures.stream().map(fixture ->
                new PreferenceAblation.ValidationFixture(fixture.name(), fixture.board(),
                        fixture.moment(), fixture.nation(), fixture.trainingEvidence(),
                        manyScenarios, Map.of())).toList();
        require(rejects(() -> PreferenceAblation.evaluate(expensive, configuration,
                        new PreferenceAblation.Limits(8, 32, CoordinationMode.STRICT))),
                "Combined candidate/scenario work cap accepted oversized experiment");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PreferenceAblation.print(results, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        require(output.contains("unmeasured") && output.contains("NOT playing strength")
                        && output.contains("selectedCount=1 observations=100")
                        && output.contains("heuristicMinusRaw="),
                "Report omitted uncertainty, exact evidence denominator or raw/heuristic distinction");
    }

    private static UnitId unit(Nation nation, UnitType type, Province province) {
        return new UnitId(UUID.nameUUIDFromBytes((nation + "-" + type + "-" + province)
                .getBytes(StandardCharsets.UTF_8)), nation, type, province);
    }

    private static BoardState board(UnitId... units) {
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        for (UnitId unit : units)
            locations.put(unit, unit.origin());
        return new BoardState(locations, Map.of());
    }

    private static Map<UnitId, RoutePrediction> predictions(Order... orders) {
        Map<UnitId, Map<Order, Long>> counts = new LinkedHashMap<>();
        for (Order order : orders)
            counts.computeIfAbsent(order.unit(), ignored -> new LinkedHashMap<>()).put(order, 1L);
        Map<UnitId, RoutePrediction> result = new LinkedHashMap<>();
        counts.forEach((unit, distribution) -> result.put(unit,
                new RoutePrediction("TRAINING", 0, distribution)));
        return result;
    }

    private static GameMoment moment() {
        return new GameMoment(1901, GamePhase.SPRING_MOVEMENT);
    }

    private static boolean rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return true;
        }
        return false;
    }

    private static void near(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-9, message + ": " + actual + " vs " + expected);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
