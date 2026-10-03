package testing;

import analysis.*;
import domain.*;
import game.*;
import phase.*;
import phase.movement.MovementProcessor;

import java.util.*;

/** Dependency-free checks for opt-in preference and movement-end shaping. */
public final class PreferenceScoringSelfCheck {
    private static final GameMoment MOMENT = new GameMoment(1901, GamePhase.SPRING_MOVEMENT);
    private static final Set<UnitType> BOTH = EnumSet.allOf(UnitType.class);

    private PreferenceScoringSelfCheck() { }

    public static void main(String[] args) {
        configuration();
        humanEvidence();
        filteredDenominator();
        numericalIdentityStability();
        distances();
        optimisticPartialPlans();
        regionalDeltas();
        adjudicatedScenarios();
        dislodgement();
        tacticalOnce();
        validation();
        System.out.println("Preference scoring checks passed.");
    }

    private static void configuration() {
        ScoringConfiguration zero = ScoringConfiguration.defaults();
        require(zero.humanWeight() == 0 && !zero.hasGeography(), "Defaults must be inactive");
        require(zero.nationProfiles().size() == 7, "All seven nation profiles are required");
        var explicitZero = new ScoringConfiguration(0, Map.of(Province.Par, value(0)),
                Map.of(Nation.FRANCE, new ScoringConfiguration.NationProfile(
                        Map.of(Province.Pic, value(-0.0)),
                        List.of())));
        require(!explicitZero.hasGeography() && !explicitZero.hasGeography(Nation.FRANCE),
                "Explicit zero geographic entries must preserve inactive search");
        var foreignOnly = new ScoringConfiguration(0, Map.of(),
                Map.of(Nation.GERMANY, new ScoringConfiguration.NationProfile(
                        Map.of(Province.Bur, value(2)), List.of())));
        require(foreignOnly.hasGeography() && foreignOnly.hasGeography(Nation.GERMANY)
                        && !foreignOnly.hasGeography(Nation.FRANCE),
                "Other nation's geographic profile activated advised nation");
        var cancelled = new ScoringConfiguration(0, Map.of(Province.Bur, value(2)),
                Map.of(Nation.FRANCE, new ScoringConfiguration.NationProfile(
                        Map.of(Province.Bur, value(-2)), List.of())));
        require(!cancelled.hasGeography(Nation.FRANCE) && cancelled.hasGeography(Nation.GERMANY),
                "Effective global/profile cancellation must preserve inactive search");
        var typedCancellation = new ScoringConfiguration(0, Map.of(Province.Bur, value(2)),
                Map.of(Nation.FRANCE, new ScoringConfiguration.NationProfile(
                        Map.of(Province.Bur, new ScoringConfiguration.ProvinceValue(-2,
                                Set.of(UnitType.ARMY))), List.of())));
        require(!typedCancellation.hasGeography(Nation.FRANCE, Set.of(UnitType.ARMY))
                        && typedCancellation.hasGeography(Nation.FRANCE, Set.of(UnitType.FLEET))
                        && !typedCancellation.hasGeography(Nation.FRANCE, Set.of()),
                "Typed geography activation must respect effective cancellation and empty type sets");
        var fleetGoal = new ScoringConfiguration(0, Map.of(), Map.of(Nation.FRANCE,
                new ScoringConfiguration.NationProfile(Map.of(), List.of(
                        new ScoringConfiguration.RegionalObjective("fleet", Set.of(Province.ION),
                                2, Set.of(UnitType.FLEET), 4, 0.5)))));
        require(!fleetGoal.hasGeography(Nation.FRANCE, Set.of(UnitType.ARMY))
                        && fleetGoal.hasGeography(Nation.FRANCE, Set.of(UnitType.FLEET))
                        && !fleetGoal.hasGeography(Nation.GERMANY, BOTH)
                        && !fleetGoal.hasGeography(Nation.FRANCE, Set.of()),
                "Typed goal applicability must match search-budget activation");
        Set<UnitType> mutableTypes = new HashSet<>(BOTH);
        Map<Province, ScoringConfiguration.ProvinceValue> global = new HashMap<>();
        global.put(Province.SpaNC, new ScoringConfiguration.ProvinceValue(-2, mutableTypes));
        Map<Province, ScoringConfiguration.ProvinceValue> local = new HashMap<>();
        local.put(Province.Spa, new ScoringConfiguration.ProvinceValue(3, Set.of(UnitType.ARMY)));
        Set<Province> targets = new HashSet<>(Set.of(Province.SpaNC));
        List<ScoringConfiguration.RegionalObjective> goals = new ArrayList<>();
        goals.add(new ScoringConfiguration.RegionalObjective("coast", targets, 4, mutableTypes, 3, 0.5));
        Map<Nation, ScoringConfiguration.NationProfile> profiles = new HashMap<>();
        profiles.put(Nation.FRANCE, new ScoringConfiguration.NationProfile(local, goals));
        ScoringConfiguration configuration = new ScoringConfiguration(0.5, global, profiles);
        mutableTypes.clear();
        global.clear();
        local.clear();
        goals.clear();
        targets.clear();
        profiles.clear();
        near(configuration.effectiveValue(Nation.FRANCE, UnitType.ARMY, Province.SpaSC), 1,
                "Nation adjustment must add to canonical global value");
        near(configuration.effectiveValue(Nation.FRANCE, UnitType.FLEET, Province.SpaNC), -2,
                "Army-only adjustment leaked to fleet");
        near(configuration.effectiveValue(Nation.GERMANY, UnitType.ARMY, Province.Spa), -2,
                "Nation adjustment leaked to another profile");
        require(configuration.profile(Nation.FRANCE).objectives().getFirst().targets()
                .equals(Set.of(Province.SpaNC)), "Region target coast/copy changed");
        near(zero.effectiveValue(Nation.FRANCE, UnitType.ARMY, Province.Spa), 0, "Reset retained values");
        rejectsUnsupported(() -> configuration.globalValues().clear());
        rejectsUnsupported(() -> configuration.nationProfiles().clear());
        rejectsUnsupported(() -> configuration.profile(Nation.FRANCE).adjustments().clear());
        rejectsUnsupported(() -> configuration.profile(Nation.FRANCE).objectives().clear());
        rejectsUnsupported(() -> configuration.profile(Nation.FRANCE).objectives().getFirst().targets().clear());
        rejectsUnsupported(() -> configuration.globalValues().get(Province.Spa).unitTypes().clear());
        rejects(() -> new ScoringConfiguration(0,
                Map.of(Province.Spa, value(1), Province.SpaNC, value(2)), Map.of()));
        rejects(() -> new ScoringConfiguration.NationProfile(
                Map.of(Province.StpNC, value(1), Province.StpSC, value(2)), List.of()));
        rejects(() -> new ScoringConfiguration(-1, Map.of(), Map.of()));
        rejects(() -> new ScoringConfiguration(Double.NaN, Map.of(), Map.of()));
        rejects(() -> value(Double.POSITIVE_INFINITY));
        rejects(() -> value(ScoringConfiguration.MAX_VALUE + 1));
        rejects(() -> new ScoringConfiguration.ProvinceValue(1, Set.of()));
        rejects(() -> goal("bad", Set.of(), 1, 2, 0.5));
        rejects(() -> goal("", Set.of(Province.Bel), 1, 2, 0.5));
        rejects(() -> goal("bad", Set.of(Province.Bel), 1, -1, 0.5));
        rejects(() -> goal("bad", Set.of(Province.Bel), 1, 2, Double.NaN));
        rejects(() -> goal("bad", Set.of(Province.Bel), 1, 2, -0.1));
        rejects(() -> goal("zero", Set.of(Province.Bel), 0, 2, 0.5));
        rejects(() -> goal("negative", Set.of(Province.Bel), -1, 2, 0.5));
        rejects(() -> goal("aliases", Set.of(Province.Spa, Province.SpaNC), 1, 2, 0.5));
        var exactCoasts = goal("coasts", Set.of(Province.SpaNC, Province.SpaSC), 1, 2, 0.5);
        require(exactCoasts.targets().equals(Set.of(Province.SpaNC, Province.SpaSC)),
                "Distinct exact fleet coasts must remain legitimate targets");
        var unreachable = goal("unreachable", Set.of(Province.Swi), 1, 2, 0.5);
        near(PositionShaping.potential(unreachable,
                PositionShaping.distance(UnitType.ARMY, Province.Par, unreachable.targets())),
                0, "Core must allow explicit unreachable target diagnostics");
        var objective = goal("duplicate", Set.of(Province.Bel), 1, 2, 0.5);
        rejects(() -> new ScoringConfiguration.NationProfile(Map.of(), List.of(objective, objective)));
        List<ScoringConfiguration.RegionalObjective> tooMany = new ArrayList<>();
        for (int i = 0; i <= ScoringConfiguration.MAX_OBJECTIVES; i++)
            tooMany.add(goal("goal " + i, Set.of(Province.Bel), 1, 2, 0.5));
        rejects(() -> new ScoringConfiguration.NationProfile(Map.of(), tooMany));
        require(ScoringConfiguration.example().hasGeography(), "Example must illustrate geography");
        near(ScoringConfiguration.example().effectiveValue(Nation.GERMANY, UnitType.ARMY, Province.Pru),
                -1, "Example must discourage German Prussia");
        near(ScoringConfiguration.example().effectiveValue(Nation.FRANCE, UnitType.ARMY, Province.Pru),
                0, "Example Prussia adjustment must be German-only");
        near(ScoringConfiguration.example().effectiveValue(Nation.FRANCE, UnitType.FLEET, Province.MAO),
                1, "Example must value fleet Mid-Atlantic presence globally");
        near(ScoringConfiguration.example().effectiveValue(Nation.ITALY, UnitType.FLEET, Province.ION),
                1, "Example must value fleet Ionian presence globally");
        near(ScoringConfiguration.example().effectiveValue(Nation.FRANCE, UnitType.ARMY, Province.MAO),
                0, "Example ocean values must be fleet-only");
        OutcomeEvaluator evaluator = new OutcomeEvaluator(1, 3, 0.4, 0.2, configuration);
        require(evaluator.withTacticalBiasWeight(0.5).scoringConfiguration().equals(configuration),
                "Changing tactical weight lost configuration");
        require(evaluator.withScoringConfiguration(zero).scoringConfiguration().equals(zero),
                "Configuration reset failed");
    }

    private static void humanEvidence() {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId fleet = unit(Nation.FRANCE, UnitType.FLEET, Province.Bre);
        BoardState board = new BoardState(Map.of(army, Province.Par, fleet, Province.Bre), Map.of());
        Order aHold = Order.hold(army), fHold = Order.hold(fleet);
        Order aMove = Order.move(army, Province.Pic), fMove = Order.move(fleet, Province.ENG);
        Map<Order, Long> armyCounts = new LinkedHashMap<>();
        armyCounts.put(aMove, 1L);
        armyCounts.put(aHold, 3L);
        // Unselected candidate still belongs to the full selected-policy denominator.
        armyCounts.put(Order.move(army, Province.Bur), 6L);
        Map<UnitId, RoutePrediction> evidence = Map.of(
                army, new RoutePrediction("suffix-2", 2, armyCounts),
                fleet, new RoutePrediction("year-pooled", 0, Map.of(fHold, 2L, fMove, 8L)));
        OrderPlan hold = new OrderPlan(Nation.FRANCE, List.of(aHold, fHold), -1000);
        HumanPreference preference = HumanPreference.evaluate(board, hold, evidence);
        require(preference.available(), "Complete evidence unavailable");
        require(preference.diagnostic().isEmpty(), "Available preference has unavailable diagnostic");
        near(preference.score(), (Math.log(0.3) + Math.log(0.2)) / 2, "Full denominator/normalization");
        for (var detail : preference.units()) {
            require(detail.observations() == 10 && detail.denominator() == 10, "Denominator truncated");
            require(detail.selectedCount() == (detail.unit().equals(army) ? 3 : 2), "Wrong count");
            require(detail.basis().equals(detail.unit().equals(army) ? "suffix-2" : "year-pooled"),
                    "Lost evidence basis");
        }
        OrderPlan reversed = new OrderPlan(Nation.FRANCE, List.of(fHold, aHold), 0);
        require(preference.equals(HumanPreference.evaluate(board, reversed, evidence)),
                "Order iteration or manually supplied logPreference changed human score");
        BoardState reversedBoard = new BoardState(new LinkedHashMap<>(Map.of(
                fleet, Province.Bre, army, Province.Par)), Map.of());
        require(preference.equals(HumanPreference.evaluate(reversedBoard, hold, evidence)),
                "Board ordering changed diagnostics");
        UnitId relocated = unit(Nation.FRANCE, UnitType.ARMY, Province.Bur);
        BoardState movedBoard = new BoardState(Map.of(army, Province.Bur, relocated, Province.Par), Map.of());
        OrderPlan movedPlan = new OrderPlan(Nation.FRANCE,
                List.of(Order.hold(relocated), Order.hold(army)), 0);
        var movedEvidence = Map.of(army, new RoutePrediction("current", 0, Map.of(Order.hold(army), 1L)),
                relocated, new RoutePrediction("current", 0, Map.of(Order.hold(relocated), 1L)));
        require(HumanPreference.evaluate(movedBoard, movedPlan, movedEvidence).units().getFirst().unit()
                        .equals(army), "Preference units sorted by creation origin rather than current location");
        require(PositionShaping.evaluate(movedBoard, Nation.FRANCE, movedBoard.locations(), Set.of(),
                ScoringConfiguration.defaults()).units().getFirst().unit().equals(army),
                "Shaping units sorted by creation origin rather than current location");
        near(HumanPreference.evaluate(new BoardState(Map.of(army, Province.Par), Map.of()),
                new OrderPlan(Nation.FRANCE, List.of(aHold), 0), Map.of(army, evidence.get(army))).score(),
                Math.log(0.3), "Single-unit normalization");
        HumanPreference missing = HumanPreference.evaluate(board, hold, Map.of(army, evidence.get(army)));
        require(!missing.available() && missing.score() == 0 && missing.units().size() == 2,
                "Missing evidence must be zero/unavailable, with all active units");
        require(missing.diagnostic().contains("Missing post-policy evidence"),
                "Aggregate preference diagnostic missing");
        require(missing.units().stream().anyMatch(detail -> !detail.available()
                && !detail.diagnostic().isBlank()), "Missing evidence needs diagnostic");
        require(!HumanPreference.evaluate(board,
                new OrderPlan(Nation.FRANCE, List.of(aHold), 0), evidence).available(),
                "Missing own unit omitted from average");
        require(!HumanPreference.evaluate(board, hold, Map.of(army,
                new RoutePrediction("empty", 0, Map.of()), fleet, evidence.get(fleet))).available(),
                "Empty corpus must be unavailable");
        require(!HumanPreference.evaluate(board, hold, Map.of(army,
                new RoutePrediction("missing order", 0, Map.of(aMove, 1L)),
                fleet, evidence.get(fleet))).available(), "Absent selected order must be unavailable");
        rejects(() -> HumanPreference.evaluate(board, hold, Map.of(army,
                new RoutePrediction("mixed query", 0, Map.of(aHold, 1L, fHold, 99L)),
                fleet, evidence.get(fleet))));
        OutcomeEvaluator human = new OutcomeEvaluator(0, 0, 0, 0,
                new ScoringConfiguration(2, Map.of(), Map.of()));
        OrderPlan move = new OrderPlan(Nation.FRANCE, List.of(aMove, fMove), -10000);
        Map<String, List<Order>> scenarios = Map.of("holds", List.of());
        List<PlanEvaluation> ranked = human.rank(board, MOMENT, List.of(hold, move), scenarios,
                Map.of(), new MovementProcessor(), evidence);
        require(ranked.getFirst().plan().equals(move), "Human weighting must reorder equal outcomes");
        near(ranked.getFirst().humanContribution(), Math.log(0.08), "Human weight not applied once");
        near(ranked.getFirst().score(), Math.log(0.08), "Human final score wrong");
        rejects(() -> human.rank(board, MOMENT, List.of(hold), scenarios, Map.of(), new MovementProcessor()));
        var legacy = new OutcomeEvaluator(0, 0, 0).rank(board, MOMENT, List.of(hold), scenarios,
                Map.of(), new MovementProcessor()).getFirst();
        require(legacy.score() == 0 && !legacy.humanPreference().available(),
                "Zero-weight API compatibility failed");
        boolean overflowRejected = false;
        try {
            HumanPreference.evaluate(board, hold, Map.of(army, new RoutePrediction("overflow", 0,
                    Map.of(aHold, Long.MAX_VALUE, aMove, 1L)), fleet, evidence.get(fleet)));
        } catch (ArithmeticException expected) {
            overflowRejected = true;
        }
        require(overflowRejected, "Evidence observation overflow must not wrap");
    }

    private static void distances() {
        require(PositionShaping.distance(UnitType.ARMY, Province.Par, Set.of(Province.Bel)) == 2,
                "Army graph distance");
        require(PositionShaping.distance(UnitType.ARMY, Province.Lon, Set.of(Province.Bel)) == -1,
                "Army graph incorrectly used a convoy");
        require(PositionShaping.distance(UnitType.ARMY, Province.Par, Set.of(Province.ENG)) == -1,
                "Army traversed sea");
        require(PositionShaping.distance(UnitType.FLEET, Province.ENG, Set.of(Province.Par)) == -1,
                "Fleet reached inland target");
        require(PositionShaping.distance(UnitType.FLEET, Province.BAR, Set.of(Province.Stp)) == 1,
                "Canonical parent target must accept north coast");
        require(PositionShaping.distance(UnitType.FLEET, Province.BOT, Set.of(Province.Stp)) == 1,
                "Canonical parent target must accept south coast");
        require(PositionShaping.distance(UnitType.FLEET, Province.StpSC, Set.of(Province.Stp)) == 0,
                "Parent target must match occupied coast");
        require(PositionShaping.distance(UnitType.FLEET, Province.StpSC, Set.of(Province.StpNC)) > 0,
                "Explicit north coast matched south coast");
        require(PositionShaping.distance(UnitType.FLEET, Province.MAO, Set.of(Province.SpaNC)) == 1,
                "Fleet exact coast adjacency");
        require(PositionShaping.distance(UnitType.FLEET, Province.Mar, Set.of(Province.SpaNC)) > 1,
                "Fleet coast-crawled across wrong coast");
        require(PositionShaping.distance(UnitType.FLEET, Province.Tus, Set.of(Province.Ven)) > 1,
                "Fleet crossed Italian land");
        require(PositionShaping.distance(UnitType.ARMY, Province.Par, Set.of(Province.Swi)) == -1,
                "Switzerland must be unreachable");
        require(PositionShaping.distance(UnitType.ARMY, Province.Stp, Set.of(Province.StpNC)) == 0,
                "Army coast target must resolve to land territory");
    }

    private static void filteredDenominator() {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        BoardState board = new BoardState(Map.of(army, Province.Par), Map.of());
        Order advance = Order.move(army, Province.Pic);
        RoutePrediction original = new RoutePrediction("conditioned", 2,
                Map.of(Order.supportHold(army, Province.Boh), 5L, advance, 4L,
                        Order.move(army, Province.Bur), 2L, Order.hold(army), 1L));
        RoutePrediction selected = ReferenceFilteredPrediction.apply(board, original);
        require(original.observations() == 12 && selected.observations() == 7,
                "Reference filtering must genuinely condition the distribution");
        OrderPlan topOne = new JointOrders(1, 1).generate(
                board, Nation.FRANCE, Map.of(army, selected)).getFirst();
        require(topOne.orders().equals(List.of(advance)), "Fixture did not apply actual top-one cutoff");
        var preference = HumanPreference.evaluate(board, topOne, Map.of(army, selected));
        near(preference.score(), Math.log(4.0 / 7), "Conditioned denominator must remain pre-topK");
        require(preference.units().getFirst().observations() == 7
                        && preference.units().getFirst().selectedCount() == 4,
                "Preference used raw observations or truncated selected observations");
    }

    private static void numericalIdentityStability() {
        Province[] current = {Province.Par, Province.Bur, Province.Pic};
        Province[] destination = {Province.Bre, Province.Mun, Province.Bel};
        Province[] renamedOrigins = {Province.Bud, Province.War, Province.Mos};
        long total = 1_000_000_000_000_000_000L;
        long[] counts = {1, 333_333_333_333_333_333L, 666_666_666_666_666_667L};
        Map<UnitId, Province> positions = new LinkedHashMap<>(), renamedPositions = new LinkedHashMap<>();
        Map<UnitId, Province> after = new LinkedHashMap<>(), renamedAfter = new LinkedHashMap<>();
        Map<UnitId, RoutePrediction> evidence = new LinkedHashMap<>(), renamedEvidence = new LinkedHashMap<>();
        List<Order> orders = new ArrayList<>(), renamedOrders = new ArrayList<>();
        for (int index = 0; index < current.length; index++) {
            UnitId original = unit(Nation.FRANCE, UnitType.ARMY, current[index]);
            UnitId renamed = new UnitId(new UUID(0, 3 - index), Nation.FRANCE,
                    UnitType.ARMY, renamedOrigins[index]);
            positions.put(original, current[index]);
            renamedPositions.put(renamed, current[index]);
            after.put(original, destination[index]);
            renamedAfter.put(renamed, destination[index]);
            orders.add(Order.hold(original));
            renamedOrders.add(Order.hold(renamed));
            evidence.put(original, new RoutePrediction("identity", 0, Map.of(
                    Order.hold(original), counts[index], Order.move(original, destination[index]),
                    total - counts[index])));
            renamedEvidence.put(renamed, new RoutePrediction("identity", 0, Map.of(
                    Order.hold(renamed), counts[index], Order.move(renamed, destination[index]),
                    total - counts[index])));
        }
        BoardState board = new BoardState(positions, Map.of());
        BoardState renamedBoard = new BoardState(renamedPositions, Map.of());
        var originalPreference = HumanPreference.evaluate(board,
                new OrderPlan(Nation.FRANCE, orders, -1000), evidence);
        Collections.reverse(renamedOrders);
        var renamedPreference = HumanPreference.evaluate(renamedBoard,
                new OrderPlan(Nation.FRANCE, renamedOrders, 0), renamedEvidence);
        require(Double.doubleToLongBits(originalPreference.score())
                        == Double.doubleToLongBits(renamedPreference.score()),
                "UUID/origin renaming changed numerical preference accumulation");
        var configuration = new ScoringConfiguration(0, Map.of(
                Province.Par, value(0.1), Province.Bur, value(0.2), Province.Pic, value(0.3),
                Province.Bre, value(0.01), Province.Mun, value(0.02), Province.Bel, value(0.04)), Map.of());
        double originalDelta = PositionShaping.evaluate(board, Nation.FRANCE, after, Set.of(),
                configuration).provinceContribution();
        double renamedDelta = PositionShaping.evaluate(renamedBoard, Nation.FRANCE, renamedAfter, Set.of(),
                configuration).provinceContribution();
        require(Double.doubleToLongBits(originalDelta) == Double.doubleToLongBits(renamedDelta),
                "UUID/origin renaming changed numerical shaping accumulation");
    }

    private static void regionalDeltas() {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        BoardState board = new BoardState(Map.of(army, Province.Par), Map.of());
        ScoringConfiguration configuration = region(goal("Belgium", Set.of(Province.Bel), 10, 4, 0.5));
        double far = 10 * Math.exp(-1), near = 10 * Math.exp(-0.5);
        PositionShaping.Breakdown approach = PositionShaping.evaluate(
                board, Nation.FRANCE, Map.of(army, Province.Pic), Set.of(), configuration);
        near(approach.regionalContribution(), near - far, "Approaching region");
        var diagnostic = approach.objectives().getFirst().units().getFirst();
        require(diagnostic.beforeDistance() == 2 && diagnostic.afterDistance() == 1,
                "Regional diagnostic distances");
        near(diagnostic.beforePotential(), far, "Regional before potential");
        near(diagnostic.afterPotential(), near, "Regional after potential");
        ScoringConfiguration independent = new ScoringConfiguration(0, Map.of(),
                Map.of(Nation.FRANCE, new ScoringConfiguration.NationProfile(Map.of(), List.of(
                        goal("Belgium", Set.of(Province.Bel), 10, 4, 0.5),
                        goal("home", Set.of(Province.Par), 3, 4, 0.25)))));
        var composed = PositionShaping.evaluate(board, Nation.FRANCE, Map.of(army, Province.Pic),
                Set.of(), independent);
        near(composed.regionalContribution(), near - far + 3 * (Math.exp(-0.25) - 1),
                "Independent positive regional objectives must compose signed deltas");
        require(composed.objectives().size() == 2
                        && composed.objectives().getFirst().contribution() > 0
                        && composed.objectives().getLast().contribution() < 0,
                "Independent objectives lost approach/away diagnostics");
        BoardState pic = new BoardState(Map.of(army, Province.Pic), Map.of());
        near(PositionShaping.evaluate(pic, Nation.FRANCE, Map.of(army, Province.Par), Set.of(),
                configuration).regionalContribution(), far - near, "Moving away");
        near(PositionShaping.evaluate(pic, Nation.FRANCE, Map.of(army, Province.Bur), Set.of(),
                configuration).regionalContribution(), 0, "Equal-distance move");
        BoardState bel = new BoardState(Map.of(army, Province.Bel), Map.of());
        ScoringConfiguration inside = region(goal("region", Set.of(Province.Bel, Province.Hol), 10, 4, 0.5));
        near(PositionShaping.evaluate(bel, Nation.FRANCE, Map.of(army, Province.Hol), Set.of(),
                inside).regionalContribution(), 0, "Inside-region move must not repeatedly award priority");
        double second = PositionShaping.evaluate(pic, Nation.FRANCE, Map.of(army, Province.Bel), Set.of(),
                configuration).regionalContribution();
        near(approach.regionalContribution() + second, 10 - far, "Potential deltas must telescope");
        BoardState lon = new BoardState(Map.of(army, Province.Lon), Map.of());
        near(PositionShaping.evaluate(lon, Nation.FRANCE, Map.of(army, Province.Wal), Set.of(),
                configuration).regionalContribution(), 0, "Unreachable region");
        require(PositionShaping.evaluate(lon, Nation.FRANCE, Map.of(army, Province.Wal), Set.of(),
                configuration).objectives().getFirst().units().getFirst().beforeDistance() == -1,
                "Unreachable diagnostic sentinel");
        ScoringConfiguration horizon = region(goal("short", Set.of(Province.Bel), 10, 1, 0.5));
        near(PositionShaping.evaluate(board, Nation.FRANCE, Map.of(army, Province.Pic), Set.of(),
                horizon).regionalContribution(), near, "Outside-horizon potential must be zero");
        UnitId secondArmy = unit(Nation.FRANCE, UnitType.ARMY, Province.Bur);
        BoardState two = new BoardState(Map.of(army, Province.Pic, secondArmy, Province.Bur), Map.of());
        PositionShaping.Breakdown saturated = PositionShaping.evaluate(two, Nation.FRANCE,
                Map.of(army, Province.Bel, secondArmy, Province.Hol), Set.of(), configuration);
        near(saturated.objectives().getFirst().afterPotential(), 10, "Region saturation must use MAX");
        near(saturated.regionalContribution(), 10 - near, "Region must not sum unit potentials");
        near(PositionShaping.evaluate(two, Nation.FRANCE, Map.of(secondArmy, Province.Bel), Set.of(army),
                configuration).regionalContribution(), 10 - near, "Lost unit affected surviving baseline");
        near(PositionShaping.evaluate(pic, Nation.FRANCE, Map.of(), Set.of(army),
                configuration).regionalContribution(), 0, "Dislodgement escape produced shaping gain");
    }

    private static void optimisticPartialPlans() {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId other = unit(Nation.FRANCE, UnitType.ARMY, Province.Bur);
        BoardState board = new BoardState(Map.of(army, Province.Par, other, Province.Bur), Map.of());
        ScoringConfiguration configuration = new ScoringConfiguration(0, Map.of(Province.Pic, value(3)),
                Map.of(Nation.FRANCE, new ScoringConfiguration.NationProfile(Map.of(),
                        List.of(goal("Belgium", Set.of(Province.Bel), 10, 4, 0.5)))));
        near(PositionShaping.optimisticDelta(board,
                new OrderPlan(Nation.FRANCE, List.of(Order.move(army, Province.Pic)), 0), configuration),
                3, "Partial heuristic must retain unassigned unit's saturated regional baseline");
        near(PositionShaping.optimisticDelta(board,
                new OrderPlan(Nation.FRANCE, List.of(), 0), configuration), 0,
                "Empty partial plan must retain current positions");
        near(PositionShaping.optimisticDelta(board,
                new OrderPlan(Nation.FRANCE, List.of(Order.move(army, Province.Bel)), 0), configuration), 0,
                "Non-adjacent army shortcut must not be optimistic");
        UnitId english = unit(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        ScoringConfiguration belgium = new ScoringConfiguration(0, Map.of(Province.Bel, value(10)),
                Map.of(Nation.ENGLAND, new ScoringConfiguration.NationProfile(Map.of(),
                        List.of(goal("Belgium", Set.of(Province.Bel), 10, 4, 0.5)))));
        near(PositionShaping.optimisticDelta(new BoardState(Map.of(english, Province.Lon), Map.of()),
                new OrderPlan(Nation.ENGLAND, List.of(Order.move(english, Province.Bel)), 0), belgium), 0,
                "Army convoy candidate must not create static geographic hint");
        UnitId fleet = unit(Nation.FRANCE, UnitType.FLEET, Province.Mar);
        var coastValues = new ScoringConfiguration(0, Map.of(Province.Spa, value(10)), Map.of());
        BoardState mar = new BoardState(Map.of(fleet, Province.Mar), Map.of());
        near(PositionShaping.optimisticDelta(mar, new OrderPlan(Nation.FRANCE,
                List.of(Order.move(fleet, Province.SpaNC)), 0), coastValues), 0,
                "Wrong coast MOVE must not create static hint");
        near(PositionShaping.optimisticDelta(mar, new OrderPlan(Nation.FRANCE,
                List.of(Order.move(fleet, Province.SpaSC)), 0), coastValues), 10,
                "Valid adjacent fleet MOVE must create static hint");
    }

    private static void adjudicatedScenarios() {
        UnitId french = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId german = unit(Nation.GERMANY, UnitType.ARMY, Province.Bur);
        BoardState board = new BoardState(Map.of(french, Province.Par, german, Province.Bur), Map.of());
        OrderPlan advance = new OrderPlan(Nation.FRANCE, List.of(Order.move(french, Province.Pic)), 0);
        Map<String, List<Order>> scenarios = Map.of(
                "clear", List.of(Order.hold(german)), "bounce", List.of(Order.move(german, Province.Pic)));
        ScoringConfiguration configuration = new ScoringConfiguration(0,
                Map.of(Province.Par, value(-1), Province.Pic, value(3)), Map.of(Nation.FRANCE,
                new ScoringConfiguration.NationProfile(Map.of(),
                        List.of(goal("Belgium", Set.of(Province.Bel), 10, 4, 0.5)))));
        PlanEvaluation raw = new OutcomeEvaluator(0, 3, 0.5).rank(board, MOMENT, List.of(advance),
                scenarios, Map.of(Province.Pic, 2.0), new MovementProcessor()).getFirst();
        PlanEvaluation shaped = new OutcomeEvaluator(0, 3, 0.5, 0, configuration).rank(
                board, MOMENT, List.of(advance), scenarios, Map.of(Province.Pic, 2.0),
                new MovementProcessor()).getFirst();
        require(raw.scenarioScores().equals(shaped.scenarioScores()), "Shaping changed legacy scenarios");
        near(shaped.mean(), 1, "Legacy mean changed");
        near(shaped.worst(), 0, "Legacy worst changed");
        near(shaped.baseScore(), 0.5, "Legacy combined score changed");
        double geography = 4 + 10 * (Math.exp(-0.5) - Math.exp(-1));
        near(shaped.scenarioDetails().get("clear").provinceContribution(), 4, "Province delta");
        near(shaped.scenarioDetails().get("bounce").provinceContribution(), 0, "Bounce province gain");
        near(shaped.scenarioDetails().get("bounce").regionalContribution(), 0, "Bounce region gain");
        near(shaped.scenarioDetails().get("clear").augmentedScore(), 2 + geography, "Augmented scenario");
        near(shaped.shapedMean(), (2 + geography) / 2, "Shaped mean must use same scenarios");
        near(shaped.shapedWorst(), 0, "Shaped worst must use augmented scenarios");
        near(shaped.shapedScore(), (2 + geography) / 4, "Shaped caution score");
        near(shaped.score(), shaped.shapedScore(), "Final geography score");
        var unit = shaped.scenarioDetails().get("clear").shaping().units().getFirst();
        near(unit.beforeValue(), -1, "Effective before-value diagnostic");
        near(unit.afterValue(), 3, "Effective after-value diagnostic");
        Map<String, List<Order>> reordered = new LinkedHashMap<>();
        reordered.put("clear", scenarios.get("clear"));
        reordered.put("bounce", scenarios.get("bounce"));
        require(shaped.equals(new OutcomeEvaluator(0, 3, 0.5, 0, configuration).rank(
                board, MOMENT, List.of(advance), reordered, Map.of(Province.Pic, 2.0),
                new MovementProcessor()).getFirst()), "Scenario ordering changed results");
        // A shaped worst case may differ from the raw worst case; do not shape a preselected raw summary.
        ScoringConfiguration reverse = new ScoringConfiguration(0, Map.of(Province.Pic, value(-5)), Map.of());
        var negative = new OutcomeEvaluator(0, 3, 1, 0, reverse).rank(board, MOMENT, List.of(advance),
                scenarios, Map.of(Province.Pic, 2.0), new MovementProcessor()).getFirst();
        var clear = negative.scenarioDetails().get("clear");
        var bounce = negative.scenarioDetails().get("bounce");
        near(clear.score(), 2, "Clear scenario raw decomposition");
        near(clear.provinceContribution(), -5, "Clear scenario province decomposition");
        near(clear.regionalContribution(), 0, "Clear scenario regional decomposition");
        near(clear.augmentedScore(), -3, "Clear scenario augmented decomposition");
        near(bounce.score(), 0, "Bounce scenario raw decomposition");
        near(bounce.provinceContribution(), 0, "Bounce scenario province decomposition");
        near(bounce.regionalContribution(), 0, "Bounce scenario regional decomposition");
        near(bounce.augmentedScore(), 0, "Bounce scenario augmented decomposition");
        near(negative.mean(), 1, "Negative shaping changed raw mean");
        near(negative.worst(), 0, "Negative shaping changed raw worst");
        near(negative.baseScore(), 0, "Full caution raw score");
        near(negative.shapedWorst(), -3, "Shaped worst must belong to clear, not raw-worst bounce");
        near(negative.shapedMean(), -1.5, "Negative shaped mean");
        near(negative.shapedScore(), -3, "Full caution shaped score");
        near(negative.score(), -3, "Full caution final score");
    }

    private static void dislodgement() {
        UnitId french = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId german = unit(Nation.GERMANY, UnitType.ARMY, Province.Bur);
        UnitId support = unit(Nation.GERMANY, UnitType.ARMY, Province.Pic);
        BoardState board = new BoardState(Map.of(french, Province.Par, german, Province.Bur,
                support, Province.Pic), Map.of());
        ScoringConfiguration config = new ScoringConfiguration(0, Map.of(Province.Par, value(-10)),
                Map.of(Nation.FRANCE, new ScoringConfiguration.NationProfile(Map.of(),
                        List.of(goal("protect", Set.of(Province.Par), 10, 4, 0.5)))));
        var evaluation = new OutcomeEvaluator(0, 3, 0, 0, config).rank(board, MOMENT,
                List.of(new OrderPlan(Nation.FRANCE, List.of(Order.hold(french)), 0)),
                Map.of("attack", List.of(Order.move(german, Province.Par),
                        Order.supportMove(support, Province.Bur, Province.Par))),
                Map.of(), new MovementProcessor()).getFirst();
        var scenario = evaluation.scenarioDetails().get("attack");
        require(scenario.dislodgedUnits() == 1, "Test attack failed to dislodge");
        near(scenario.score(), -3, "Legacy dislodgement penalty lost");
        near(scenario.provinceContribution(), 0, "Dislodgement escaped negative province value");
        near(scenario.regionalContribution(), 0, "Dislodgement escaped regional penalty");
        near(evaluation.score(), -3, "Dislodgement final score");
        require(!scenario.shaping().units().getFirst().surviving(), "Lost-unit diagnostic");
    }

    private static void tacticalOnce() {
        UnitId fleet = unit(Nation.FRANCE, UnitType.FLEET, Province.Bre);
        UnitId supporter = unit(Nation.FRANCE, UnitType.FLEET, Province.ENG);
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Pic);
        BoardState board = new BoardState(Map.of(fleet, Province.Bre,
                supporter, Province.ENG, army, Province.Pic), Map.of());
        Order move = Order.move(fleet, Province.ENG);
        Order support = Order.supportMove(supporter, Province.Pic, Province.Bel);
        Order armyMove = Order.move(army, Province.Bel);
        OrderPlan plan = new OrderPlan(Nation.FRANCE, List.of(move, support, armyMove), 0);
        var evidence = Map.of(fleet, new RoutePrediction("selected", 0,
                        Map.of(move, 1L, Order.hold(fleet), 3L)),
                supporter, new RoutePrediction("selected", 0, Map.of(support, 1L)),
                army, new RoutePrediction("selected", 0, Map.of(armyMove, 1L)));
        var config = new ScoringConfiguration(0.5, Map.of(Province.Bel, value(2)), Map.of());
        var evaluator = new OutcomeEvaluator(0, 0, 0, 0.7, config);
        var one = evaluator.rank(board, MOMENT, List.of(plan), Map.of("a", List.of()),
                Map.of(), new MovementProcessor(), evidence).getFirst();
        var two = evaluator.rank(board, MOMENT, List.of(plan), Map.of("a", List.of(), "b", List.of()),
                Map.of(), new MovementProcessor(), evidence).getFirst();
        require(one.offendingMoveCount() > 0, "Tactical fixture must produce warning");
        near(one.penaltyTotal(), 0.7 * one.offendingMoveCount(), "Once-per-plan tactical penalty");
        near(one.score(), one.shapedScore() - one.penaltyTotal() + 0.5 * Math.log(0.25) / 3,
                "Human/tactical final arithmetic");
        near(one.score(), two.score(), "Scenario count multiplied preference or tactical penalty");
    }

    private static void validation() {
        UnitId army = unit(Nation.FRANCE, UnitType.ARMY, Province.Par);
        OrderPlan plan = new OrderPlan(Nation.FRANCE, List.of(Order.hold(army)), 0);
        var valid = new OutcomeEvaluator(0, 0, 0).rank(
                new BoardState(Map.of(army, Province.Par), Map.of()), MOMENT, List.of(plan),
                Map.of("a", List.of()), Map.of(), new MovementProcessor()).getFirst();
        rejects(() -> new PlanEvaluation(plan, valid.scenarioScores(), 0, 0, 1,
                valid.scenarioDetails(), 0, 0, 0, 0, List.of(), 0, 0, 0,
                HumanPreference.unavailable(), 0, 0, ScoringConfiguration.defaults()));
        rejects(() -> new ScenarioEvaluation("a", List.of(), 0, 0, 0, 0, 0, 1, 0, 1,
                PositionShaping.Breakdown.empty()));
        rejects(() -> new HumanPreference(false, -1, List.of()));
        // Large legacy values still receive finite-result protection.
        rejects(() -> new OutcomeEvaluator(Double.MAX_VALUE, 0, 0).rank(
                new BoardState(Map.of(army, Province.Par), Map.of()), MOMENT,
                List.of(new OrderPlan(Nation.FRANCE, List.of(Order.move(army, Province.Bre)), 0)),
                Map.of("a", List.of()), Map.of(Province.Bre, Double.MAX_VALUE), new MovementProcessor()));
    }

    private static UnitId unit(Nation nation, UnitType type, Province origin) {
        return new UnitId(UUID.nameUUIDFromBytes((nation.name() + type.name() + origin.name())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)), nation, type, origin);
    }

    private static ScoringConfiguration.ProvinceValue value(double value) {
        return new ScoringConfiguration.ProvinceValue(value, BOTH);
    }

    private static ScoringConfiguration.RegionalObjective goal(
            String name, Set<Province> targets, double priority, int horizon, double decay) {
        return new ScoringConfiguration.RegionalObjective(name, targets, priority, BOTH, horizon, decay);
    }

    private static ScoringConfiguration region(ScoringConfiguration.RegionalObjective objective) {
        return new ScoringConfiguration(0, Map.of(), Map.of(Nation.FRANCE,
                new ScoringConfiguration.NationProfile(Map.of(), List.of(objective))));
    }

    private static void near(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 1e-10, message + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid scoring input was accepted");
    }

    private static void rejectsUnsupported(Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Immutable scoring collection was mutable");
    }
}
