package testing.selfcheck;

import _app.AgentGameConsoleApp;
import _app.AgentExperimentConsoleApp;
import agents.evaluation.RolloutEvaluator;
import agents.evaluation.ScoringEvaluator;
import agents.execution.*;
import agents.generation.*;
import agents.search.*;
import contracts.OrderForm;
import domain.*;
import game.*;
import game.record.*;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.adjustments.*;
import phase.movement.MovementProcessor;
import phase.retreats.RetreatOrder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Decision capture preserves policies, sampled actions, and recorded games.<br><br>
 *
 * <u>Note:</u> This class may take a long time to run, since it performs (a small amount of) self-play.
 */
public final class AgentDecisionSelfCheck {

    private static final UUID ID = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static int checks;

    private AgentDecisionSelfCheck() { }

    public static void main(String[] args) throws Exception {
        observedGame();
        phaseFormatting();
        consoleOptions();
        experimentOptions();
        System.out.println("AgentDecisionSelfCheck passed: " + checks + " checks");
    }

    private static GameRunner.Options options(int year, int candidates, int iterations) {
        Map<Nation, AgentSettings> agents = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            agents.put(nation, new AgentSettings(
                    AgentSettings.Method.values()[nation.ordinal() % AgentSettings.Method.values().length],
                    candidates, iterations, 1, candidates,
                    nation.ordinal() % 2 == 0 ? PolicyOutput.FINAL : PolicyOutput.AVERAGE,
                    nation.ordinal() % 2 == 0 ? AgentSettings.Generator.UNIFORM : AgentSettings.Generator.PLAUSIBLE,
                    nation.ordinal() < 3 ? AgentSettings.Generator.PLAUSIBLE : AgentSettings.Generator.UNIFORM));
        return new GameRunner.Options(year, 17, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, 0, agents);
    }

    private static void observedGame() {
        GameState initial = StandardGameFactory.create1901().snapshot();
        GameRunner.Options options = options(1902, 3, 2);
        List<AgentDecision> decisions = new ArrayList<>(), pending = new ArrayList<>();
        GameRunner.Result traced = new GameRunner().run(ID, initial, options, phase -> {
            Set<Nation> powers = new HashSet<>();
            List<OrderForm> submitted = new ArrayList<>();
            for (AgentDecision decision : pending) {
                require(decision.state().moment().equals(phase.gameMoment())
                        && decision.state().board().equals(phase.boardBefore()), "Decision used another root");
                require(powers.add(decision.power()), "Seat reported twice");
                submitted.addAll(decision.selected().orders());
            }
            require(!pending.isEmpty() && powers.equals(pending.getFirst().state().aliveNations()),
                    "Decision callbacks must precede the phase callback");
            require(sameOrders(submitted, orders(phase)), "Recorded orders differ from sampled actions");
            pending.clear();
        }, decision -> {
            decisions.add(decision);
            pending.add(decision);
            require(decision.selected().equals(decision.distribution().sample(new Random(decision.selectionSeed()))),
                    "Selection seed does not reproduce the action");
            require(decision.pools().get(decision.power()).equals(decision.distribution().candidates()),
                    "Candidate indices changed");
        });
        require(pending.isEmpty(), "Unresolved decision callbacks remain");
        require(decisions.stream().anyMatch(value -> value.state() instanceof GameState_Adjustments),
                "Check must include winter decisions");
        GameRunner.Result plain = new GameRunner().run(ID, initial, options);
        require(traced.state().moment().equals(plain.state().moment())
                        && traced.state().board().equals(plain.state().board())
                        && traced.reason() == plain.reason() && traced.scores().equals(plain.scores()),
                "Tracing changed the final position or scores");
        require(traced.record().resolvedPhases().size() == plain.record().resolvedPhases().size(),
                "Tracing changed the phase count");
        for (int index = 0; index < traced.record().resolvedPhases().size(); index++) {
            var a = traced.record().resolvedPhases().get(index);
            var b = plain.record().resolvedPhases().get(index);
            require(a.resolvedAt().equals(b.resolvedAt()) && a.boardBefore().equals(b.boardBefore())
                            && a.boardAfter().equals(b.boardAfter()) && orders(a).equals(orders(b)),
                    "Tracing changed recorded play");
        }
        for (AgentSettings.Method method : AgentSettings.Method.values())
            comparePolicy(decisions.stream().filter(value -> value.settings().method() == method)
                    .findFirst().orElseThrow(), options);

        AgentDecision example = decisions.getFirst();
        Map<Nation, List<ActionCandidate>> pools = new EnumMap<>(Nation.class);
        example.pools().forEach((nation, candidates) -> pools.put(nation, new ArrayList<>(candidates)));
        AgentDecision copied = new AgentDecision(example.state(), example.settings(), example.searchSeed(),
                example.rolloutSeed(), example.selectionSeed(), example.elapsedNanos(), pools,
                example.distribution(), example.searchResult(), example.selectedIndex());
        pools.values().forEach(List::clear);
        pools.clear();
        require(copied.pools().equals(example.pools()), "Decision retained mutable pools");
        expect(UnsupportedOperationException.class, () -> copied.pools().clear());
        expect(UnsupportedOperationException.class, () -> copied.pools().get(copied.power()).clear());
        expect(UnsupportedOperationException.class, () -> copied.selected().orders().clear());
        expect(IllegalArgumentException.class, () -> new AgentDecision(example.state(), example.settings(),
                0, 0, 0, 0, example.pools(), example.distribution(), example.searchResult(), -1));

        RuntimeException failure = new IllegalStateException("Decision observer failed");
        List<ResolvedPhaseRecord> resolved = new ArrayList<>();
        try {
            new GameRunner().run(ID, initial, options, resolved::add, decision -> { throw failure; });
            throw new AssertionError("Observer failure was swallowed");
        } catch (IllegalStateException exception) {
            require(exception == failure && resolved.isEmpty(), "Failed observation resolved a real phase");
        }
        List<AgentDecision> terminal = new ArrayList<>();
        new GameRunner().run(ID, traced.state(), options, phase -> {
            throw new AssertionError("Terminal input resolved another phase");
        }, terminal::add);
        require(terminal.isEmpty(), "Terminal input generated another decision");
    }

    private static void comparePolicy(AgentDecision decision, GameRunner.Options options) {
        var settings = decision.settings();
        var generator = settings.rootGenerator().create();
        if (settings.method() == AgentSettings.Method.PRIOR) {
            var candidates = generator.generate(new ActionGenerationRequest(decision.state(), decision.power(),
                            settings.candidateLimit(), decision.searchSeed()),
                    new LegalOrderGenerator().generate(decision.state(), decision.power()));
            require(ActionDistribution.fromPriors(candidates).equals(decision.distribution()),
                    "Prior distribution changed");
            require(decision.searchResult().isEmpty() && decision.pools().keySet().equals(Set.of(decision.power())),
                    "Prior sampling invented search results");
            return;
        }
        var evaluator = new RolloutEvaluator(new ScoringEvaluator(options.scoring()),
                settings.rolloutGenerator().create(), options::createGame,
                settings.rolloutCount(), settings.rolloutLimit(), decision.rolloutSeed());
        PolicySearch search = settings.method() == AgentSettings.Method.BEST_RESPONSE
                ? new BestResponseSearch(evaluator, generator, settings.candidateLimit(),
                settings.iterations(), decision.searchSeed())
                : PaperSearchProfile.forState(decision.state()).solver(evaluator, generator,
                settings.candidateLimit(), settings.iterations(), decision.searchSeed(), settings.output());
        require(search.search(options.createGame(decision.state()), decision.power())
                .equals(decision.searchResult().orElseThrow()), "Capturing pools changed the search");
        require(decision.pools().keySet().equals(decision.state().aliveNations()),
                "Missing opponent root pool");
        require(decision.searchResult().orElseThrow().samplesPerCandidate() == settings.iterations(),
                "Payoff observation count changed");
    }

    private static void phaseFormatting() throws Exception {
        UnitId french = new UnitId(Nation.FRANCE, UnitType.ARMY, Province.Par);
        UnitId german = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Mun);
        UnitId support = new UnitId(Nation.GERMANY, UnitType.ARMY, Province.Ber);
        GameState root = new GameState_Movement(new GameMoment(1907, GamePhase.FALL_MOVEMENT),
                new BoardState(Map.of(french, Province.Bur, german, Province.Mun, support, Province.Ruh),
                        Map.of(Province.Par, Nation.FRANCE, Province.Mar, Nation.FRANCE,
                                Province.Mun, Nation.GERMANY, Province.Stp, Nation.RUSSIA, Province.Mos, Nation.RUSSIA)));
        String movement = render(diagnostic(root, Nation.FRANCE, List.of(Order.hold(french))));
        require(movement.contains("Fr A Bur H") && !movement.contains("Fr A Par H"), "Movement used birth location");
        require(movement.contains("Q=N/A") && !movement.contains("Samples per candidate:"),
                "Prior decision invented payoff observations");
        Game game = options(1908, 1, 1).createGame(root);
        game.resolveMovement(List.of(Order.hold(french), Order.move(german, Province.Bur),
                Order.supportMove(support, Province.Mun, Province.Bur)));
        GameState retreat = game.snapshot();
        String retreats = render(diagnostic(retreat, Nation.FRANCE, List.of(new RetreatOrder(french, Province.Gas))));
        require(retreats.contains("Fr A Bur R Gas"), "Retreat lost displaced origin");
        String omitted = render(diagnostic(retreat, Nation.FRANCE, List.of()));
        require(omitted.contains("(no submitted orders)"), "Empty candidate was not explicit");
        game.resolveRetreats(List.of());
        GameState winter = game.snapshot();
        String build = render(diagnostic(winter, Nation.RUSSIA, List.of(
                new BuildOrder(Nation.RUSSIA, UnitType.FLEET, Province.StpSC), new WaiveOrder(Nation.RUSSIA))));
        require(build.contains("Ru F Stp/sc BUILD") && build.contains("Ru WAIVE") && !build.contains("null"),
                "Build coast or waive changed");
        String disband = render(diagnostic(winter, Nation.GERMANY, List.of(new DisbandOrder(Nation.GERMANY, support))));
        require(disband.contains("Ge A Ruh DESTROY"), "Disband used birth location");

        List<AgentDecision> observed = new ArrayList<>();
        new GameRunner().run(ID, retreat, options(1907, 3, 2), phase -> { }, observed::add);
        require(!observed.isEmpty() && observed.stream().allMatch(value -> value.state().equals(retreat)),
                "Pending retreat diagnostics used another root");
        for (AgentDecision decision : observed)
            comparePolicy(decision, options(1907, 3, 2));
    }

    private static AgentDecision diagnostic(GameState state, Nation power, List<? extends OrderForm> orders) {
        var candidate = new ActionCandidate(power, orders, 0, ActionCandidate.Source.HEURISTIC);
        var settings = new AgentSettings(AgentSettings.Method.PRIOR, 1, 1, 1, 1, PolicyOutput.AVERAGE);
        return new AgentDecision(state, settings, 1, 2, 3, 0, Map.of(power, List.of(candidate)),
                ActionDistribution.fromPriors(List.of(candidate)), Optional.empty(), 0);
    }

    private static String render(AgentDecision decision) throws Exception {
        var method = AgentGameConsoleApp.class.getDeclaredMethod("printDecision", AgentDecision.class, boolean.class);
        method.setAccessible(true);
        return capture(() -> method.invoke(null, decision, true));
    }

    private static void consoleOptions() throws Exception {
        AgentSettings defaults = AgentSettings.paper(4, 8);
        require(defaults.rootGenerator() == AgentSettings.Generator.UNIFORM
                && defaults.rolloutGenerator() == AgentSettings.Generator.UNIFORM
                && defaults.rolloutCount() == 1 && defaults.rolloutLimit() == 4
                && defaults.output() == PolicyOutput.AVERAGE, "Legacy defaults changed");
        require(capture(() -> AgentGameConsoleApp.main(new String[]{"--help"})).contains("--trace <all|self|none>"),
                "Trace usage missing");
        expect(IllegalArgumentException.class, () -> AgentGameConsoleApp.main(new String[]{"--trace", "invalid"}));
        expect(IllegalArgumentException.class, () -> AgentGameConsoleApp.main(new String[]{"--trace"}));
        String baseline = null;
        for (String mode : List.of("all", "SELF", "none")) {
            String text = capture(() -> AgentGameConsoleApp.main(new String[]{"--last-year", "1901",
                    "--candidates", "1", "--iterations", "1", "--trace", mode}));
            require(text.contains("BEGIN DECISION:") == !mode.equals("none"), "Wrong trace visibility");
            require(text.contains("role=MODELED_OPPONENT") == mode.equals("all"), "Wrong opponent visibility");
            require(text.contains("END RECORDED AGENT GAME"), "Console did not complete");
            String finalBoard = text.substring(text.indexOf("FINAL POSITION:"), text.indexOf("Recorded phases:"));
            if (baseline == null)
                baseline = finalBoard;
            else
                require(finalBoard.equals(baseline), "Trace mode changed the final result");
        }
        String configured = capture(() -> AgentGameConsoleApp.main(new String[]{"--last-year", "1901",
                "--candidates", "2", "--iterations", "1", "--root-generator", "plausible",
                "--rollout-generator", "uniform", "--rollouts", "2", "--rollout-candidates", "1",
                "--output", "final", "--trace", "self"}));
        require(configured.contains("rootGenerator=PLAUSIBLE, rolloutGenerator=UNIFORM")
                        && configured.contains("rolloutCount=2, rolloutLimit=1, output=FINAL"),
                "Console settings were not propagated");
        for (String[] invalid : List.of(new String[]{"--rollouts", "0"},
                new String[]{"--rollout-candidates", "0"}, new String[]{"--root-generator", "invalid"},
                new String[]{"--rollout-generator", "invalid"}, new String[]{"--output", "invalid"}))
            expect(IllegalArgumentException.class, () -> AgentGameConsoleApp.main(invalid));
    }

    private static void experimentOptions() throws Exception {
        require(capture(() -> AgentExperimentConsoleApp.main(new String[]{"--help"})).contains("--pool-seed"),
                "Experiment seed options missing");
        for (String[] invalid : List.of(new String[]{"--rollouts", "0"}, new String[]{"--rollouts", "1,1"},
                new String[]{"--rollouts", "1,"}, new String[]{"--position", "invalid"},
                new String[]{"--iterations", "0"}, new String[]{"--rollout-candidates", "0"}))
            expect(IllegalArgumentException.class, () -> AgentExperimentConsoleApp.main(invalid));
        String first = capture(() -> AgentExperimentConsoleApp.main(new String[]{
                "--candidates", "2", "--iterations", "1", "--rollouts", "1,2", "--rollout-candidates", "1"}));
        String second = capture(() -> AgentExperimentConsoleApp.main(new String[]{
                "--candidates", "2", "--iterations", "2", "--rollouts", "1", "--rollout-candidates", "2",
                "--search-seed", "31", "--rollout-seed", "17", "--rollout-generator", "plausible"}));
        require(first.substring(first.indexOf("POOL "), first.indexOf("\nROLLOUTS "))
                        .equals(second.substring(second.indexOf("POOL "), second.indexOf("\nROLLOUTS "))),
                "Search or rollout settings changed the root pools");
        require(first.split("POOL FRANCE ", -1).length == 2
                && first.contains("ROLLOUTS 1;") && first.contains("ROLLOUTS 2;")
                && first.contains("candidate Q p_FINAL p_AVERAGE"), "Budgets did not reuse one printed pool");
        require(first.contains("END FIXED-POSITION EXPERIMENT"), "Experiment did not complete");

        Map<Nation, AgentSettings> agents = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            agents.put(nation, AgentSettings.paper(4, 8));
        var baseline = new GameRunner.Options(1901, 7, ScoringSystem.SUM_OF_SQUARES,
                MovementProcessor.Policy.JUDGE, 1, adjudication.Justice.SHUFFLE_SEED_DEFAULT, agents);
        var winterMethod = AgentExperimentConsoleApp.class.getDeclaredMethod("winter", GameRunner.Options.class);
        winterMethod.setAccessible(true);
        GameState fixture = (GameState) winterMethod.invoke(null, baseline);
        GameState played = new GameRunner().run(ID, StandardGameFactory.create1901().snapshot(), baseline).state();
        require(fixture.moment().equals(played.moment()) && fixture.board().equals(played.board()),
                "Winter fixture differs from the original seed-7 board");
        String winter = capture(() -> AgentExperimentConsoleApp.main(new String[]{
                "--position", "winter", "--root-generator", "plausible", "--rollout-generator", "plausible",
                "--candidates", "2", "--iterations", "1", "--rollouts", "1", "--rollout-candidates", "1"}));
        require(winter.contains("gamePhase=WINTER_ADJUSTMENT") && winter.contains(" BUILD")
                && winter.contains("Profile: STANDARD"), "Winter experiment lacks build alternatives");
    }

    private static List<? extends OrderForm> orders(ResolvedPhaseRecord phase) {
        return switch (phase) {
            case MovementPhaseRecord movement -> movement.submittedOrders();
            case RetreatPhaseRecord retreat -> retreat.submittedOrders();
            case AdjustmentPhaseRecord adjustment -> adjustment.submittedOrders();
        };
    }

    private static boolean sameOrders(List<? extends OrderForm> a, List<? extends OrderForm> b) {
        List<OrderForm> remaining = new ArrayList<>(b);
        for (OrderForm order : a)
            if (!remaining.remove(order))
                return false;
        return remaining.isEmpty();
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    private static String capture(Checked action) throws Exception {
        PrintStream original = System.out;
        var buffer = new ByteArrayOutputStream();
        try (var output = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            require(type.isInstance(exception), "Unexpected exception: " + exception);
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition)
            throw new AssertionError(message);
    }
}