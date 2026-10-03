package analysis;

import analysis.openings.Classifier;
import contracts.OrderForm;
import domain.*;
import game.*;
import phase.*;
import phase.movement.MovementProcessor;

import java.io.PrintStream;
import java.util.*;

/**
 * Explicit, bounded validation-only experiment. Callers must supply evidence learned
 * only from TRAINING and fixed VALIDATION positions/scenarios, never reserved TEST data.
 * Reference orders are labels only: they cannot affect search, scores or configuration.
 */
public final class PreferenceAblation {
    public static final int MAX_FIXTURES = 16;
    public static final int MAX_UNITS = 34;
    public static final int MAX_EVIDENCE_CHOICES = 128;
    public static final int MAX_SCENARIOS = 16;
    public static final int MAX_BEAM = 32;
    public static final int MAX_CHOICES = 8;
    public static final int MAX_SCENARIO_EVALUATIONS = 4096;

    private PreferenceAblation() { }

    public record Limits(int choicesPerUnit, int beamWidth, CoordinationMode mode) {
        public Limits {
            if (choicesPerUnit < 1 || choicesPerUnit > MAX_CHOICES
                    || beamWidth < 1 || beamWidth > MAX_BEAM)
                throw new IllegalArgumentException("Ablation search limits exceeded");
            Objects.requireNonNull(mode, "mode");
        }
    }

    public record ValidationFixture(String name, BoardState board, GameMoment moment,
                                    Nation nation, Map<UnitId, RoutePrediction> trainingEvidence,
                                    Map<String, List<Order>> scenarios,
                                    Map<UnitId, Order> referenceOrders) {
        public ValidationFixture {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(board, "board");
            Objects.requireNonNull(moment, "moment");
            Objects.requireNonNull(nation, "nation");
            if (name.isBlank() || !moment.gamePhase().isMovement()
                    || board.locations().isEmpty() || board.locations().size() > MAX_UNITS)
                throw new IllegalArgumentException("Invalid or oversized validation position");
            if (trainingEvidence.size() > MAX_UNITS)
                throw new IllegalArgumentException("Too many evidence queries");
            if (!board.locations().keySet().containsAll(trainingEvidence.keySet()))
                throw new IllegalArgumentException("Evidence query names an inactive unit");
            Map<UnitId, RoutePrediction> evidence = new LinkedHashMap<>();
            for (UnitId unit : HumanPreference.sortedUnits(board).stream()
                    .filter(trainingEvidence::containsKey).toList()) {
                RoutePrediction prediction = Objects.requireNonNull(trainingEvidence.get(unit));
                if (!board.locations().containsKey(unit)
                        || prediction.counts().size() > MAX_EVIDENCE_CHOICES)
                    throw new IllegalArgumentException("Invalid or oversized evidence query");
                for (Order order : prediction.counts().keySet()) {
                    if (!order.unit().equals(unit))
                        throw new IllegalArgumentException("Evidence must be bound to its query unit");
                    Classifier.signature(board, List.of(order));
                }
                prediction.observations();
                evidence.put(unit, prediction);
            }
            trainingEvidence = Collections.unmodifiableMap(evidence);
            if (scenarios.isEmpty() || scenarios.size() > MAX_SCENARIOS)
                throw new IllegalArgumentException("Explicit bounded scenarios required");
            Set<UnitId> opponents = new HashSet<>(board.locations().keySet());
            opponents.removeIf(unit -> unit.owner() == nation);
            Map<String, List<Order>> frozen = new TreeMap<>();
            for (var entry : scenarios.entrySet()) {
                if (entry.getKey().isBlank() || entry.getValue().size() > MAX_UNITS)
                    throw new IllegalArgumentException("Invalid or oversized scenario");
                List<Order> orders = List.copyOf(entry.getValue());
                Set<UnitId> submitted = new HashSet<>();
                for (Order order : orders)
                    if (!submitted.add(order.unit()))
                        throw new IllegalArgumentException("Duplicate scenario unit");
                if (!submitted.equals(opponents))
                    throw new IllegalArgumentException("Scenario must name every opponent exactly once");
                frozen.put(entry.getKey(), orders);
            }
            scenarios = Collections.unmodifiableMap(frozen);
            if (referenceOrders.size() > MAX_UNITS)
                throw new IllegalArgumentException("Too many reference labels");
            for (var entry : referenceOrders.entrySet())
                if (!board.locations().containsKey(entry.getKey())
                        || entry.getKey().owner() != nation
                        || !entry.getKey().equals(entry.getValue().unit()))
                    throw new IllegalArgumentException("Reference labels must name active own units");
            referenceOrders = Map.copyOf(referenceOrders);
        }
    }

    public record Variant(String name, ScoringConfiguration configuration) {
        public Variant {
            Objects.requireNonNull(name);
            Objects.requireNonNull(configuration);
        }
    }

    public record Result(String fixture, Variant variant, List<PlanEvaluation> ranked,
                         CoordinatedOrders.Diagnostics diagnostics, int scenarioCount,
                         int commonPlans, int commonRankChanges, int humanReferenceMatches,
                         int humanReferenceDenominator, long elapsedNanos,
                         Map<UnitId, Province> evidenceQueryLocations) {
        public Result {
            ranked = List.copyOf(ranked);
            evidenceQueryLocations = Map.copyOf(evidenceQueryLocations);
        }
        public boolean available() { return !ranked.isEmpty(); }
        public boolean referenceMeasured() {
            return available() && humanReferenceDenominator > 0;
        }
    }

    public static List<Variant> variants(ScoringConfiguration combined) {
        Objects.requireNonNull(combined);
        Map<Nation, ScoringConfiguration.NationProfile> provinces = new EnumMap<>(Nation.class);
        Map<Nation, ScoringConfiguration.NationProfile> regions = new EnumMap<>(Nation.class);
        combined.nationProfiles().forEach((nation, profile) -> {
            provinces.put(nation, new ScoringConfiguration.NationProfile(profile.adjustments(), List.of()));
            regions.put(nation, new ScoringConfiguration.NationProfile(Map.of(), profile.objectives()));
        });
        return List.of(
                new Variant("tactical-5 baseline", ScoringConfiguration.defaults()),
                new Variant("+human", new ScoringConfiguration(combined.humanWeight(), Map.of(), Map.of())),
                new Variant("+province", new ScoringConfiguration(0, combined.globalValues(), provinces)),
                new Variant("+region", new ScoringConfiguration(0, Map.of(), regions)),
                new Variant("combined", combined));
    }

    public static List<Result> evaluate(List<ValidationFixture> fixtures,
                                        ScoringConfiguration combined, Limits limits) {
        Objects.requireNonNull(limits);
        if (fixtures.isEmpty() || fixtures.size() > MAX_FIXTURES)
            throw new IllegalArgumentException("Supply 1–" + MAX_FIXTURES + " fixed validation fixtures");
        List<ValidationFixture> frozen = List.copyOf(fixtures);
        List<Variant> variants = variants(combined);
        long scenarioWork = 0;
        for (ValidationFixture fixture : frozen)
            scenarioWork += (long) fixture.scenarios().size() * limits.beamWidth() * variants.size();
        if (scenarioWork > MAX_SCENARIO_EVALUATIONS)
            throw new IllegalArgumentException("Total candidate/scenario work cap exceeded");
        List<Result> results = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ValidationFixture fixture : frozen) {
            if (!names.add(fixture.name()))
                throw new IllegalArgumentException("Duplicate validation fixture name");
            List<String> baseline = List.of();
            for (Variant variant : variants) {
                long start = System.nanoTime();
                var generated = new CoordinatedOrders(limits.choicesPerUnit(), limits.beamWidth(),
                        5, variant.configuration()).generate(fixture.board(), fixture.nation(),
                        fixture.trainingEvidence(), limits.mode());
                List<PlanEvaluation> ranked = List.of();
                boolean evidenceAvailable = fixture.board().locations().keySet().stream()
                        .filter(unit -> unit.owner() == fixture.nation()).allMatch(unit ->
                                fixture.trainingEvidence().containsKey(unit)
                                        && fixture.trainingEvidence().get(unit).observations() > 0);
                if (evidenceAvailable && !generated.plans().isEmpty())
                    ranked = new OutcomeEvaluator(1, 3, 0.5, 5, variant.configuration()).rank(
                            fixture.board(), fixture.moment(), generated.plans(), fixture.scenarios(),
                            Map.of(), new MovementProcessor(), fixture.trainingEvidence());
                List<String> signatures = ranked.stream().map(value ->
                        Classifier.signature(fixture.board(), value.plan().orders())).toList();
                if (variant == variants.getFirst())
                    baseline = signatures;
                Set<String> common = new HashSet<>(baseline);
                common.retainAll(signatures);
                List<String> baselineCommon = baseline.stream().filter(common::contains).toList();
                List<String> currentCommon = signatures.stream().filter(common::contains).toList();
                int changes = 0;
                for (int index = 0; index < baselineCommon.size(); index++)
                    if (!baselineCommon.get(index).equals(currentCommon.get(index)))
                        changes++;
                int matches = 0;
                if (!ranked.isEmpty())
                    for (Order order : ranked.getFirst().plan().orders())
                        if (order.equals(fixture.referenceOrders().get(order.unit())))
                            matches++;
                results.add(new Result(fixture.name(), variant, ranked, generated.diagnostics(),
                        fixture.scenarios().size(), common.size(), changes, matches,
                        fixture.referenceOrders().size(), System.nanoTime() - start,
                        fixture.board().locations()));
            }
        }
        return List.copyOf(results);
    }

    public static void print(List<Result> results, PrintStream output) {
        output.println("Fixed VALIDATION ablation; training-only evidence; reserved TEST never read.");
        output.println("Equal-weight explicit scenarios; heuristic differences are NOT playing strength.");
        output.println("Fixed evaluator: center=1 dislodgement=3 caution=0.5 tactical=5;"
                + " common rank changes exclude candidate-pool additions/removals.");
        for (Result result : results) {
            output.printf(Locale.ROOT,
                    "%s | %s: plans=%d available=%s scenarios=%d omitted=%d replenished=%d guidanceUnexamined=%d "
                            + "geographyGuided=%s beamTruncated=%s common=%d rankChanges=%d latencyMs=%.3f%n",
                    result.fixture(), result.variant().name(), result.ranked().size(), result.available(),
                    result.scenarioCount(), result.diagnostics().omittedChoices(),
                    result.diagnostics().replenishedChoices(), result.diagnostics().guidanceChoicesUnexamined(),
                    result.diagnostics().geographyGuided(),
                    result.diagnostics().beamTruncated(),
                    result.commonPlans(), result.commonRankChanges(), result.elapsedNanos() / 1_000_000.0);
            output.println("  human reference-order match: " + (result.referenceMeasured()
                    ? result.humanReferenceMatches() + "/" + result.humanReferenceDenominator()
                    : "unmeasured (no reference orders or no recommendation)"));
            if (!result.available())
                continue;
            PlanEvaluation best = result.ranked().getFirst();
            output.printf(Locale.ROOT,
                    "  rawMean=%.6f rawWorst=%.6f rawScore=%.6f shapedScore=%.6f "
                            + "positionDelta=%.6f penalty=%.6f human=%.6f final=%.6f "
                            + "heuristicMinusRaw=%.6f%n",
                    best.mean(), best.worst(), best.baseScore(), best.shapedScore(),
                    best.shapedScore() - best.baseScore(), best.penaltyTotal(),
                    best.humanContribution(), best.score(), best.score() - best.baseScore());
            for (var unit : best.humanPreference().units())
                output.printf(Locale.ROOT, "  %s selectedCount=%d observations=%d "
                                + "logFrequency=%.6f basis=%s%n",
                        unit.order() == null
                                ? OrderForm.unitText(unit.unit(), result.evidenceQueryLocations().get(unit.unit()))
                                : OrderForm.format(unit.order(), result.evidenceQueryLocations().get(unit.unit())),
                        unit.selectedCount(),
                        unit.denominator(), unit.logFrequency(), unit.basis());
        }
    }

    /** Illustrative evidence, not a corpus measurement or an actual human reference. */
    public static List<ValidationFixture> syntheticValidation() {
        List<ValidationFixture> fixtures = new ArrayList<>();
        for (Province[] pair : List.of(new Province[]{Province.NTH, Province.NWG},
                new Province[]{Province.ENG, Province.MAO},
                new Province[]{Province.TYS, Province.ION})) {
            UnitId fleet = new UnitId(UUID.nameUUIDFromBytes(
                    ("preference-ablation-" + pair[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    Nation.ENGLAND, UnitType.FLEET, pair[0]);
            fixtures.add(new ValidationFixture("synthetic-" + pair[1],
                    new BoardState(Map.of(fleet, pair[0]), Map.of()),
                    new GameMoment(1901, GamePhase.SPRING_MOVEMENT), Nation.ENGLAND,
                    Map.of(fleet, new RoutePrediction("synthetic TRAINING illustration", 0,
                            Map.of(Order.hold(fleet), 99L, Order.move(fleet, pair[1]), 1L))),
                    Map.of("no opposing units", List.of()), Map.of()));
        }
        return List.copyOf(fixtures);
    }

    public static ScoringConfiguration syntheticConfiguration() {
        Set<UnitType> fleets = Set.of(UnitType.FLEET);
        Map<Province, ScoringConfiguration.ProvinceValue> values = new EnumMap<>(Province.class);
        for (Province target : List.of(Province.NWG, Province.MAO, Province.ION))
            values.put(target, new ScoringConfiguration.ProvinceValue(10, fleets));
        return new ScoringConfiguration(0.5, values, Map.of(Nation.ENGLAND,
                new ScoringConfiguration.NationProfile(Map.of(), List.of(
                        new ScoringConfiguration.RegionalObjective("Illustrative sea access",
                                values.keySet(), 10, fleets, 2, 0.5)))));
    }
}
