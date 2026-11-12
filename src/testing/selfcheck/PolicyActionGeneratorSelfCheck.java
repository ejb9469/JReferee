package testing.selfcheck;

import _app.PolicyProposalExperimentApp;
import agents.encoding.*;
import agents.generation.*;
import agents.policy.*;
import contracts.OrderForm;
import domain.*;
import game.*;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentProcessor;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatProcessor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Independent anchors, scoped masks, bounded sampling, phase fallback,
 * and repeatable comparison experiments.
 */
public final class PolicyActionGeneratorSelfCheck {

    private static int checks;

    private PolicyActionGeneratorSelfCheck() { }

    public static void main(String[] args) throws Exception {
        generation();
        smallCatalogs();
        conditional();
        fallback();
        experiment();
        System.out.println("PolicyActionGeneratorSelfCheck passed: " + checks + " checks");
    }

    private static void generation() {
        GameState_Movement root = (GameState_Movement) StandardGameFactory.create1901().snapshot();
        var uniform = new LinearMovementPolicy(1024);
        var learner = new LinearMovementPolicy.Learner(1024);
        MovementEncoding france = new MovementEncoding(root, Nation.FRANCE);
        var input = france.input();
        var holds = france.encode(france.catalog().unitLocations().keySet().stream().map(Order::hold).toList());
        for (int step = 0; step < 20; step++) learner.learn(input, holds, 0.05);
        var anchor = learner.snapshot();
        PolicyActionGenerator generator = new PolicyActionGenerator(uniform, anchor);
        boolean different = false;
        for (Nation power : Nation.values()) {
            var encoding = new MovementEncoding(root, power);
            for (int limit : new int[]{1, 8, 30}) {
                var request = new ActionGenerationRequest(root, power, limit, 7);
                var result = generator.propose(request, encoding);
                require(result.candidates().getFirst().orders().equals(encoding.decode(
                                uniform.prepare(encoding.input()).sample(new Random(request.seed())))),
                        "First candidate is a policy draw without an inserted heuristic");
                require(result.equals(generator.propose(request, encoding.catalog())), "Encoding reuse preserves output");
                require(result.proposals().size() == limit && result.samples() <= 32L * limit, "Bounded filled opening pool");
                require(result.samples() == result.proposals().size() + result.duplicates(), "Sampling accounting");
                Set<Set<OrderForm>> seen = new HashSet<>();
                var prepared = anchor.prepare(encoding.input());
                for (var proposal : result.proposals()) {
                    ActionCandidate candidate = proposal.candidate();
                    require(seen.add(Set.copyOf(candidate.orders())), "Unique complete submissions");
                    require(new CatalogActionValidator().validate(root, candidate, encoding.catalog()).isEmpty(), "Catalog-valid actions");
                    double expected = prepared.logProbability(encoding.encode(candidate.orders()));
                    require(candidate.logPrior() == expected, "Exact independent anchor");
                    require(Math.abs(proposal.logProposal() + encoding.options().stream()
                            .mapToDouble(options -> Math.log(options.size())).sum()) < 1e-12, "Exact uniform proposal mass");
                    different |= Math.abs(candidate.logPrior() - proposal.logProposal()) > 1e-8;
                    require(candidate.source() == ActionCandidate.Source.POLICY_SAMPLE, "Sample source retained");
                }
                var sameProposal = new PolicyActionGenerator(uniform, uniform).generate(request, encoding.catalog());
                require(sameProposal.stream().map(ActionCandidate::orders).toList()
                        .equals(result.candidates().stream().map(ActionCandidate::orders).toList()), "Anchor does not alter sampling");
                var extended = generator.generate(new ActionGenerationRequest(root, power, limit + 1, 7), encoding.catalog());
                require(extended.subList(0, limit).equals(result.candidates()), "Growing pool preserves sampled prefix and priors");
                expect(UnsupportedOperationException.class, () -> result.proposals().clear());
                expect(UnsupportedOperationException.class, () -> result.candidates().clear());
            }
        }
        require(different, "Proposal and anchor probabilities are distinct");
        var changed = new GameState_Movement(root.moment(),
                new BoardState(reversed(root.board().locations()), reversed(root.board().owners())));
        var request = new ActionGenerationRequest(root, Nation.FRANCE, 30, 17);
        require(generator.generate(request, france.catalog()).equals(generator.generate(
                        new ActionGenerationRequest(changed, Nation.FRANCE, 30, 17), catalog(changed, Nation.FRANCE))),
                "Insertion order independence");
        expect(IllegalArgumentException.class, () -> generator.generate(request, catalog(root, Nation.ENGLAND)));
        expect(IllegalArgumentException.class, () -> generator.propose(
                new ActionGenerationRequest(changed, Nation.ENGLAND, 4, 7), france));
        require(root.equals(StandardGameFactory.create1901().snapshot()), "Root remains unchanged");
        int[] preparations = {0};
        MovementPolicy counted = observation -> {
            preparations[0]++;
            return uniform.prepare(observation);
        };
        new PolicyActionGenerator(counted, counted).propose(request, france);
        require(preparations[0] == 1, "Shared model is prepared once per full-mask call");
        var capped = generator.propose(new ActionGenerationRequest(root, Nation.FRANCE, 300, 7), france);
        require(capped.target() == 256 && capped.proposals().size() <= 256, "Hard movement cap");
    }

    private static void smallCatalogs() {
        UnitId fleet = new UnitId(Nation.FRANCE, UnitType.FLEET, Province.Bre);
        var root = movement(Map.of(fleet, Province.MAO), Map.of(Province.Bre, Nation.FRANCE));
        var uniform = new LinearMovementPolicy(1024);
        var generator = new PolicyActionGenerator(uniform, uniform);
        var catalog = catalog(root, Nation.FRANCE);
        var request = new ActionGenerationRequest(root, Nation.FRANCE, 256, 7);
        var result = generator.propose(request, catalog);
        require(result.proposals().size() == catalog.optionsFor(fleet).size(), "Small finite action space is exhausted");
        require(result.candidates().stream().anyMatch(c -> c.orders().contains(Order.move(fleet, Province.SpaNC)))
                        && result.candidates().stream().anyMatch(c -> c.orders().contains(Order.move(fleet, Province.SpaSC))),
                "Fleet destination coasts remain distinct");
        var holdOnly = new ProvinceScope(List.of(Province.MAO)).restrict(root, catalog);
        var restricted = generator.propose(request, holdOnly);
        var hold = restricted.proposals().getFirst();
        require(restricted.target() == 1 && restricted.samples() == 1 && hold.logProposal() == 0,
                "Scoped proposal is normalized over its own mask");
        require(hold.candidate().logPrior() == uniform.prepare(new MovementEncoding(root, Nation.FRANCE).input())
                        .logProbability(new MovementEncoding(root, Nation.FRANCE).encode(hold.candidate().orders())),
                "Scoped anchor still uses the full catalog");
        var scoped = new ScopedActionGenerator(generator, new ProvinceScope(List.of(Province.MAO)), uniform::bind);
        require(scoped.generate(request, catalog).equals(restricted.candidates()), "Existing scope wrapper integrates");
        var spain = new ProvinceScope(List.of(Province.MAO, Province.Spa)).restrict(root, catalog);
        var coastScope = generator.propose(request, spain);
        require(coastScope.proposals().size() == 3, "Restricted masks retain both Spain coasts");
        for (var proposal : coastScope.proposals()) {
            require(new CatalogActionValidator().validate(root, proposal.candidate(), spain).isEmpty(), "Scoped local-index mapping");
            require(proposal.logProposal() == -Math.log(3)
                    && proposal.candidate().logPrior() == hold.candidate().logPrior(), "Scoped probabilities preserve the full anchor");
        }

        MovementPolicy point = observation -> new MovementPolicy.Prepared() {
            @Override public MovementInput input() { return observation; }
            @Override public double[] logProbabilities(List<Integer> prefix) {
                observation.requirePrefix(prefix);
                double[] values = new double[observation.slots().get(prefix.size()).options().size()];
                Arrays.fill(values, Double.NEGATIVE_INFINITY);
                values[0] = 0;
                return values;
            }
        };
        var bounded = new PolicyActionGenerator(point, uniform, new UniformActionGenerator(), 3).propose(request, catalog);
        require(bounded.proposals().size() == 1 && bounded.samples() == 3L * bounded.target()
                && bounded.duplicates() == bounded.samples() - 1, "Point-mass duplicates exhaust a bounded budget");
        var disjoint = new PolicyActionGenerator(uniform, point).propose(request, catalog);
        require(disjoint.candidates().stream().anyMatch(c -> c.logPrior() == Double.NEGATIVE_INFINITY),
                "Zero anchor support is preserved without substitution");
        var empty = movement(Map.of(), Map.of(Province.Bre, Nation.FRANCE));
        var noUnits = generator.propose(new ActionGenerationRequest(empty, Nation.FRANCE, 30, 7), catalog(empty, Nation.FRANCE));
        require(noUnits.samples() == 1 && noUnits.candidates().getFirst().orders().isEmpty()
                && noUnits.candidates().getFirst().logPrior() == 0, "Empty live submission");
        expect(IllegalArgumentException.class, () -> new PolicyActionGenerator(uniform, uniform, new UniformActionGenerator(), 0));
    }

    private static void conditional() {
        UnitId army = new UnitId(Nation.ENGLAND, UnitType.ARMY, Province.Lon);
        UnitId fleet = new UnitId(Nation.ENGLAND, UnitType.FLEET, Province.Edi);
        var root = movement(Map.of(army, Province.Lon, fleet, Province.ENG), Map.of(Province.Lon, Nation.ENGLAND));
        var encoding = new MovementEncoding(root, Nation.ENGLAND);
        MovementPolicy conditional = input -> new MovementPolicy.Prepared() {
            @Override public MovementInput input() { return input; }
            @Override public double[] logProbabilities(List<Integer> prefix) {
                input.requirePrefix(prefix);
                double[] values = new double[input.slots().get(prefix.size()).options().size()];
                Arrays.fill(values, Double.NEGATIVE_INFINITY);
                if (prefix.isEmpty()) {
                    values[0] = Math.log(0.25);
                    values[1] = Math.log(0.75);
                } else values[prefix.getFirst()] = 0;
                return values;
            }
        };
        var result = new PolicyActionGenerator(conditional, new LinearMovementPolicy(1024))
                .propose(new ActionGenerationRequest(root, Nation.ENGLAND, 8, 7), encoding);
        require(result.proposals().size() == 2, "Autoregressive support produces two distinct actions");
        for (var proposal : result.proposals()) {
            var action = encoding.encode(proposal.candidate().orders());
            require(action.get(0).equals(action.get(1)), "Sampled prefix controls next slot");
            require(proposal.logProposal() == Math.log(action.getFirst() == 0 ? 0.25 : 0.75), "Conditional action probability");
        }
        var full = new PolicyActionGenerator(new LinearMovementPolicy(1024), new LinearMovementPolicy(1024))
                .propose(new ActionGenerationRequest(root, Nation.ENGLAND, 256, 7), encoding);
        require(full.candidates().stream().flatMap(c -> c.orders().stream()).anyMatch(o -> o.orderType() == OrderType.CONVOY),
                "Full-catalog proposals retain convoys");
        MovementPolicy invalid = input -> new MovementPolicy.Prepared() {
            @Override public MovementInput input() { return input; }
            @Override public double[] logProbabilities(List<Integer> prefix) { return new double[]{Double.NaN}; }
        };
        expect(IllegalArgumentException.class, () -> new PolicyActionGenerator(invalid, invalid)
                .propose(new ActionGenerationRequest(root, Nation.ENGLAND, 8, 7), encoding));
    }

    private static void fallback() {
        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId ruh = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        UnitId hol = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Kie);
        Game game = Game.fromState(movement(Map.of(french, Province.Bel, ruh, Province.Ruh, hol, Province.Hol),
                        Map.of(Province.Par, Nation.FRANCE, Province.Ber, Nation.GERMANY, Province.Kie, Nation.GERMANY)),
                new MovementProcessor(MovementProcessor.Policy.JUDGE, 1, 0), new RetreatProcessor(), new AdjustmentProcessor());
        game.resolveMovement(List.of(Order.hold(french), Order.move(ruh, Province.Bel), Order.supportMove(hol, Province.Ruh, Province.Bel)));
        MovementPolicy unused = input -> { throw new AssertionError("Nonmovement prepared a movement policy"); };
        var generator = new PolicyActionGenerator(unused, unused);
        for (int phase = 0; phase < 2; phase++) {
            GameState root = game.snapshot();
            require(phase == 0 ? root instanceof GameState_Retreats : root instanceof GameState_Adjustments, "Fallback fixture phase");
            for (Nation power : List.of(Nation.FRANCE, Nation.GERMANY)) {
                var request = new ActionGenerationRequest(root, power, 8, 7);
                var catalog = catalog(root, power);
                require(generator.generate(request, catalog).equals(new UniformActionGenerator().generate(request, catalog)),
                        "Nonmovement behavior remains uniform");
                var hybrid = new HybridActionGenerator(unused, unused);
                require(hybrid.generate(request, catalog).equals(new UniformActionGenerator().generate(request, catalog)),
                        "Hybrid nonmovement behavior remains uniform");
                expect(IllegalArgumentException.class, () -> hybrid.propose(request, catalog));
                expect(IllegalArgumentException.class, () -> generator.propose(request, catalog));
            }
            if (phase == 0) game.resolveRetreats(List.of());
        }
    }

    private static void experiment() throws Exception {
        Path directory = Files.createTempDirectory("jreferee-proposal-check-");
        Path model = directory.resolve("policy.bin"), anchor = directory.resolve("anchor.bin");
        try {
            new LinearMovementPolicy(1024).save(model);
            new LinearMovementPolicy(2048).save(anchor);
            String[] base = {"--proposal-model", model.toString(), "--anchor-model", anchor.toString(),
                    "--candidates", "2", "--iterations", "1", "--opponent-samples", "2", "--rollout-candidates", "2"};
            for (String position : List.of("opening", "contact")) {
                List<String> args = new ArrayList<>(List.of(base));
                args.addAll(List.of("--position", position));
                String first = run(args.toArray(String[]::new)), second = run(args.toArray(String[]::new));
                require(stable(first).equals(stable(second)), "Experiment repeats apart from timing");
                require(first.contains("END POLICY PROPOSAL EXPERIMENT"), "Experiment completed");
                for (String method : List.of("UNIFORM", "PLAUSIBLE", "FULL_UNIFORM", "LEARNED", "HYBRID"))
                    require(first.contains("METHOD " + method), "Comparison method present");
                require(first.lines().filter(line -> line.startsWith("POWER ")).count() == 35, "All powers and methods reported");
                require(first.lines().filter(line -> line.contains("root_work=Counts[requests=8,")).count() == 5
                                && first.lines().filter(line -> line.contains("root_work=Counts[requests=28,")).count() == 5,
                        "Equal search and fresh root budgets");
                require(first.lines().filter(line -> line.startsWith("Paired search_delta_vs_UNIFORM=0.00000000 se=0.00000000"))
                        .count() >= 7, "Reference paired deltas are zero");
                require(first.lines().filter(line -> line.contains("log_learned_proposal log_anchor")).count() == 35,
                        "Proposal and anchor labels stay separate");
                require(first.lines().filter(line -> line.startsWith("Paired search_delta_vs_PLAUSIBLE=")).count() == 28
                                && first.lines().filter(line -> line.startsWith("Paired search_delta_vs_LEARNED=")).count() == 7,
                        "Direct paired comparisons are reported");
                require(first.lines().filter(line -> line.startsWith("Coordination support_mismatch_actions=")).count() == 35
                                && first.lines().filter(line -> line.startsWith("Hybrid quotas=")).count() == 7,
                        "Coordination and hybrid construction counts are reported");
                String control = first.substring(first.indexOf("METHOD FULL_UNIFORM"), first.indexOf("METHOD LEARNED")).strip();
                String learned = first.substring(first.indexOf("METHOD LEARNED"), first.indexOf("METHOD HYBRID")).strip();
                require(stable(control).replace("METHOD FULL_UNIFORM", "METHOD LEARNED").equals(stable(learned)),
                        "Identical proposal models give identical pools, search, and common-opponent estimates");
                args.addAll(List.of("--power", "france"));
                String filtered = run(args.toArray(String[]::new));
                require(filtered.lines().filter(line -> line.startsWith("POWER ")).count() == 5, "Power filters tables");
                require(filtered.lines().filter(line -> line.contains("root_work=Counts[requests=8,")).count() == 5
                                && filtered.lines().filter(line -> line.contains("root_work=Counts[requests=28,")).count() == 5,
                        "Power selection does not reduce evaluation work");
            }
            expect(IllegalArgumentException.class, () -> run("--candidates", "0"));
            expect(IllegalArgumentException.class, () -> run("--opponent-samples", "1"));
            expect(IllegalArgumentException.class, () -> run("--candidates", "4", "--candidates", "4"));
        } finally {
            Files.deleteIfExists(model);
            Files.deleteIfExists(anchor);
            Files.delete(directory);
        }
    }

    private static String stable(String output) {
        return output.replaceAll("(generation_seconds|seconds)=[0-9.]+", "$1=TIME");
    }
    private static String run(String... args) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            PolicyProposalExperimentApp.main(args);
        } finally { System.setOut(original); }
        return output.toString(StandardCharsets.UTF_8);
    }
    private static <K, V> Map<K, V> reversed(Map<K, V> source) {
        List<K> keys = new ArrayList<>(source.keySet());
        Collections.reverse(keys);
        Map<K, V> result = new LinkedHashMap<>();
        for (K key : keys) result.put(key, source.get(key));
        return result;
    }
    private static GameState_Movement movement(Map<UnitId, Province> units, Map<Province, Nation> centers) {
        return new GameState_Movement(new GameMoment(1902, GamePhase.FALL_MOVEMENT), new BoardState(units, centers));
    }
    private static LegalOrderCatalog catalog(GameState root, Nation power) { return new LegalOrderGenerator().generate(root, power); }
    private interface Checked { void run() throws Exception; }
    private static void expect(Class<? extends Exception> type, Checked operation) {
        try { operation.run(); }
        catch (Exception exception) {
            if (!type.isInstance(exception)) throw new AssertionError("Unexpected exception", exception);
            checks++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}