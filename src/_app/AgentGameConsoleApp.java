package _app;

import adjudication.Justice;
import agents.execution.AgentDecision;
import agents.execution.AgentSettings;
import agents.execution.GameRunner;
import agents.generation.ActionCandidate;
import agents.generation.ActionDistribution;
import agents.search.PaperSearchProfile;
import agents.search.PolicyOutput;
import contracts.OrderForm;
import domain.Nation;
import domain.Province;
import game.BoardState;
import game.GameState_Retreats;
import game.StandardGameFactory;
import game.record.*;
import misc.scoring.ScoringSystem;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.DisbandOrder;
import phase.movement.MovementProcessor;
import phase.movement.MovementResult;
import phase.retreats.RetreatOrder;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Runs the AgentGameApp demonstration and prints its recorded phases.
 *
 * <p>Uses the same defaults and named game options as the browser application.
 * No server, UI assets, or database are required. Pass --help for usage.</p>
 *
 * <p>By default, each decision includes its own and modeled opponents' root
 * pools. --trace self prints only the deciding seat's pool; --trace none
 * retains phase output alone. Diagnostics use the original search result.</p>
 */
public final class AgentGameConsoleApp {

    private static final String USAGE = "Usage: AgentGameConsoleApp "
            + "[--seed <long>] [--last-year <year>] "
            + "[--movement-policy <policy>] "
            + "[--candidates <count>] [--iterations <count>] "
            + "[--root-generator <uniform|plausible>] [--rollout-generator <uniform|plausible>] "
            + "[--rollouts <count>] [--rollout-candidates <count>] "
            + "[--output <final|average>] [--trace <all|self|none>]";

    private enum Trace { ALL, SELF, NONE }


    // Construction \\

    private AgentGameConsoleApp() {  }


    // Application entry point \\

    public static void main(String[] args) {

        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println(USAGE);
            System.out.println("Defaults: --seed 7 --last-year 1903 --movement-policy JUDGE "
                    + "--candidates 4 --iterations 8 --root-generator uniform --rollout-generator uniform "
                    + "--rollouts 1 --output average --trace all");
            System.out.println("--rollout-candidates defaults to --candidates.");
            System.out.println("Movement policies: " + Arrays.toString(MovementProcessor.Policy.values()));
            return;
        }

        long seed = 7;
        int lastYear = 1903;
        MovementProcessor.Policy policy = MovementProcessor.Policy.JUDGE;
        int candidates = 4;
        int iterations = 8;
        int rollouts = 1;
        Integer rolloutCandidates = null;
        PolicyOutput output = PolicyOutput.AVERAGE;
        AgentSettings.Generator rootGenerator = AgentSettings.Generator.UNIFORM;
        AgentSettings.Generator rolloutGenerator = AgentSettings.Generator.UNIFORM;
        Trace trace = Trace.ALL;

        for (int index = 0; index < args.length; index++) {

            if (index + 1 >= args.length)
                throw new IllegalArgumentException(USAGE);

            switch (args[index]) {
                case "--seed" -> seed = Long.parseLong(args[++index]);
                case "--last-year" -> lastYear = Integer.parseInt(args[++index]);
                case "--movement-policy" -> policy = MovementProcessor.Policy.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));
                case "--candidates" -> candidates = Integer.parseInt(args[++index]);
                case "--iterations" -> iterations = Integer.parseInt(args[++index]);
                case "--rollouts" -> rollouts = Integer.parseInt(args[++index]);
                case "--rollout-candidates" -> rolloutCandidates = Integer.parseInt(args[++index]);
                case "--output" -> output = PolicyOutput.valueOf(args[++index].toUpperCase(Locale.ROOT));
                case "--root-generator" -> rootGenerator = AgentSettings.Generator.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));
                case "--rollout-generator" -> rolloutGenerator = AgentSettings.Generator.valueOf(
                        args[++index].toUpperCase(Locale.ROOT));
                case "--trace" -> trace = Trace.valueOf(args[++index].toUpperCase(Locale.ROOT));
                default -> throw new IllegalArgumentException(USAGE);
            }

        }

        Map<Nation, AgentSettings> agents = new EnumMap<>(Nation.class);
        AgentSettings settings = new AgentSettings(AgentSettings.Method.DIL_PIKL, candidates, iterations,
                rollouts, rolloutCandidates == null ? candidates : rolloutCandidates,
                output, rootGenerator, rolloutGenerator);
        for (Nation nation : Nation.values())
            agents.put(nation, settings);

        GameRunner.Options options = new GameRunner.Options(lastYear, seed, ScoringSystem.SUM_OF_SQUARES,
                policy, policy == MovementProcessor.Policy.JUDGE ? 1 : Justice.NUM_TRIALS_DEFAULT,
                Justice.SHUFFLE_SEED_DEFAULT, agents);

        // Keep record IDs aligned with AgentGameApp for the same configuration.
        UUID id = UUID.nameUUIDFromBytes(("AgentGameApp:" + options).getBytes(StandardCharsets.UTF_8));

        System.out.println("BEGIN RECORDED AGENT GAME");
        System.out.println("Game: " + id);
        System.out.println("Configuration: " + options);
        System.out.println("All seats: " + settings);
        System.out.println("Evaluation: " + rollouts + " year-end rollout(s); Sum-of-Squares rewards.");
        System.out.println("JUDGE and SZYKMAN_JUSTICE may disagree on difficult positions.");
        System.out.println("Each phase is printed after resolution, with its starting board and actual outcomes.");
        if (trace != Trace.NONE) {
            System.out.println("Decisions are printed before resolution; candidate IDs are local to each decision.");
            System.out.println("anchor_full = exp(log_prior); anchor_pool = anchor conditioned on the retained pool.");
            System.out.println("play_p is the deciding seat's returned policy; Q is its raw search payoff mean.");
            System.out.println("Opponent pools are this seat's model, not other seats' chosen actions.");
            System.out.println("Opponent anchor_pool is not a DiL-piKL population policy; opponent Q/play_p are not reported.");
        }
        System.out.println("Searching from Spring 1901...");
        System.out.flush();

        long started = System.nanoTime();
        Trace detail = trace;
        GameRunner.Result result = new GameRunner().run(id, StandardGameFactory.create1901().snapshot(),
                options, AgentGameConsoleApp::printPhase, decision -> {
                    if (detail != Trace.NONE)
                        printDecision(decision, detail == Trace.ALL);
                });
        double elapsed = (System.nanoTime() - started) / 1e9;

        System.out.println("\nFINAL POSITION: " + result.state().moment());
        printBoard(result.state().board());
        if (result.state() instanceof GameState_Retreats retreats)
            printDislodged(retreats.movementResult());

        System.out.println("\nEnd: " + result.reason() + " at " + result.state().moment());
        result.winner().ifPresent(nation -> System.out.println("Winner: " + nation));
        if (result.reason() == GameRunner.EndReason.YEAR_LIMIT)
            System.out.println("Year limit reached after the complete Fall turn, before winter builds.");

        System.out.println("Scores (" + options.scoring() + "):");
        Map<Nation, Double> scores = result.scores();
        for (Nation nation : Nation.values())
            System.out.printf(Locale.ROOT, "  %-8s %.10f%n", nation, scores.get(nation));

        System.out.printf(Locale.ROOT, "Recorded phases: %d; elapsed: %.3f seconds%n",
                result.record().resolvedPhases().size(), elapsed);
        System.out.println("END RECORDED AGENT GAME");

    }


    // Agent decisions \\

    private static void printDecision(AgentDecision decision, boolean opponents) {

        System.out.println("\nBEGIN DECISION: " + decision.state().moment() + " seat=" + decision.power());
        System.out.println("Settings: " + decision.settings());
        System.out.printf(Locale.ROOT, "Seeds: search=%d rollout=%d selection=%d%n",
                decision.searchSeed(), decision.rolloutSeed(), decision.selectionSeed());

        switch (decision.settings().method()) {
            case PRIOR -> System.out.println("Prior sampling: Q=N/A; no payoff evaluations or opponent pools.");
            case BEST_RESPONSE -> System.out.println("Q: mean payoff against fixed sampled anchor opponents.");
            case DIL_PIKL -> {
                PaperSearchProfile profile = PaperSearchProfile.forState(decision.state());
                System.out.printf(Locale.ROOT, "Profile: %s; playing_lambda=%.8g; temperature=%s; output=%s%n",
                        profile, profile.agentLambda(), profile.temperature(), decision.settings().output());
                System.out.println("Q: mean payoff against sampled search history, not the returned policy.");
            }
        }
        decision.searchResult().ifPresent(result ->
                System.out.println("Samples per candidate: " + result.samplesPerCandidate()));

        printPool(decision, decision.power());
        if (opponents)
            for (Nation nation : decision.pools().keySet())
                if (nation != decision.power())
                    printPool(decision, nation);

        System.out.printf(Locale.ROOT, "Sampled: %s-P%d; play_p=%.10f%n",
                decision.power(), decision.selectedIndex(),
                decision.distribution().probabilities().get(decision.selectedIndex()));
        System.out.printf(Locale.ROOT, "Decision elapsed: %.3f seconds%n", decision.elapsedNanos() / 1e9);
        System.out.println("END DECISION: " + decision.state().moment() + " seat=" + decision.power());
        System.out.flush();

    }

    private static void printPool(AgentDecision decision, Nation nation) {

        List<ActionCandidate> candidates = decision.pools().get(nation);
        ActionDistribution anchor = ActionDistribution.fromPriors(candidates);
        boolean self = nation == decision.power();
        System.out.printf(Locale.ROOT, "POOL %s role=%s candidates=%d%n",
                nation, self ? "DECIDING_SEAT" : "MODELED_OPPONENT", candidates.size());

        for (int index = 0; index < candidates.size(); index++) {

            ActionCandidate candidate = candidates.get(index);
            System.out.printf(Locale.ROOT,
                    "  %s-P%d source=%s log_prior=%.10f anchor_full=%.8g anchor_pool=%.10f",
                    nation, index, candidate.source(), candidate.logPrior(),
                    StrictMath.exp(candidate.logPrior()), anchor.probabilities().get(index));
            if (self) {
                System.out.printf(Locale.ROOT, " play_p=%.10f", decision.distribution().probabilities().get(index));
                if (decision.searchResult().isPresent())
                    System.out.printf(Locale.ROOT, " Q=%.10f", decision.searchResult().orElseThrow().meanValues().get(index));
                else
                    System.out.print(" Q=N/A");
                if (index == decision.selectedIndex())
                    System.out.print(" SAMPLED");
            }
            System.out.println();

            if (candidate.orders().isEmpty())
                System.out.println("    (no submitted orders)");
            else
                candidate.orders().stream()
                        .map(order -> OrderForm.format(order, decisionOrigin(order, decision)))
                        .sorted().forEach(order -> System.out.println("    " + order));

        }

    }

    private static Province decisionOrigin(OrderForm order, AgentDecision decision) {

        if (order instanceof Order movement)
            return decision.state().board().locationOf(movement.unit());
        if (order instanceof DisbandOrder disband)
            return decision.state().board().locationOf(disband.unit());
        if (order instanceof RetreatOrder retreat) {
            if (!(decision.state() instanceof GameState_Retreats state))
                throw new IllegalStateException("Retreat order outside a retreat decision");
            return state.movementResult().dislodgementOf(retreat.unit()).displacedFrom();
        }
        return order.origin();

    }


    // Recorded phases \\

    private static void printPhase(ResolvedPhaseRecord phase) {

        System.out.println("\nBEGIN PHASE: " + phase.gameMoment());
        System.out.println("Board before resolution:");
        printBoard(phase.boardBefore());

        switch (phase) {
            case MovementPhaseRecord movement -> printMovement(movement);
            case RetreatPhaseRecord retreat -> printRetreats(retreat);
            case AdjustmentPhaseRecord adjustment -> printAdjustments(adjustment);
        }

        System.out.println("END PHASE: " + phase.gameMoment());
        System.out.flush();

    }

    private static void printMovement(MovementPhaseRecord phase) {

        printLines("Submitted orders", phase.submittedOrders().stream()
                .map(order -> OrderForm.format(order, phase.boardBefore().locationOf(order.unit())))
                .toList());

        printLines("Recorded results", phase.result().outcomes().values().stream()
                .map(outcome -> OrderForm.format(outcome.submittedOrder(),
                        phase.boardBefore().locationOf(outcome.unit()))
                        + " -> " + (outcome.succeeded() ? "SUCCEEDED" : "FAILED")
                        + "; effective=" + outcome.effectiveType()
                        + (outcome.submitted() ? "" : "; DEFAULT_ORDER")
                        + (outcome.rewrittenBySzykman() ? "; SZYKMAN_REWRITE" : "")
                        + (phase.result().dislodgements().containsKey(outcome.unit()) ? "; DISLODGED" : ""))
                .toList());

        printLines("Standoff provinces", phase.result().standoffProvinces().stream()
                .map(Province::toString).toList());
        printDislodged(phase.result());

    }

    private static void printRetreats(RetreatPhaseRecord phase) {

        MovementResult movement = phase.result().movementResult();
        printDislodged(movement);

        printLines("Submitted orders", phase.submittedOrders().stream()
                .map(order -> OrderForm.format(order, movement.dislodgementOf(order.unit()).displacedFrom()))
                .toList());

        printLines("Recorded results", phase.result().outcomes().entrySet().stream()
                .map(entry -> OrderForm.unitText(entry.getKey(),
                        movement.dislodgementOf(entry.getKey()).displacedFrom())
                        + " -> " + entry.getValue().status()
                        + (entry.getValue().destination() == null ? "" : " " + entry.getValue().destination()))
                .toList());

    }

    private static void printAdjustments(AdjustmentPhaseRecord phase) {

        printLines("Submitted orders", phase.submittedOrders().stream()
                .map(order -> OrderForm.format(order, adjustmentOrigin(order, phase.boardBefore())))
                .toList());

        List<String> outcomes = new ArrayList<>();
        Set<UnitId> orderedDisbands = new HashSet<>();

        for (var outcome : phase.result().outcomes()) {
            AdjustmentOrder order = outcome.order();
            outcomes.add(OrderForm.format(order, adjustmentOrigin(order, phase.boardBefore()))
                    + " -> " + outcome.status());
            if (outcome.accepted() && order instanceof DisbandOrder disband)
                orderedDisbands.add(disband.unit());
        }

        for (UnitId unit : phase.result().disbandedUnits())
            if (!orderedDisbands.contains(unit))
                outcomes.add(OrderForm.unitText(unit, phase.boardBefore().locationOf(unit))
                        + " -> AUTOMATIC_DISBAND");

        printLines("Recorded results", outcomes);

    }

    private static Province adjustmentOrigin(AdjustmentOrder order, BoardState board) {
        return order instanceof DisbandOrder disband ? board.locationOf(disband.unit()) : order.origin();
    }


    // Positions and output \\

    private static void printBoard(BoardState board) {

        for (Nation nation : Nation.values()) {

            List<String> units = board.locations().entrySet().stream()
                    .filter(entry -> entry.getKey().owner() == nation)
                    .map(entry -> OrderForm.unitText(entry.getKey(), entry.getValue()))
                    .sorted().toList();
            List<String> centers = board.owners().entrySet().stream()
                    .filter(entry -> entry.getValue() == nation)
                    .map(entry -> entry.getKey().toString())
                    .sorted().toList();

            System.out.printf(Locale.ROOT, "  %-8s units=%d centers=%d%n", nation, units.size(), centers.size());
            System.out.println("    Units: " + (units.isEmpty() ? "(none)" : String.join(", ", units)));
            System.out.println("    Centers: " + (centers.isEmpty() ? "(none)" : String.join(", ", centers)));

        }

    }

    private static void printDislodged(MovementResult movement) {

        printLines("Dislodged units (separate from active units)", movement.dislodgements().values().stream()
                .map(dislodgement -> OrderForm.unitText(dislodgement.unit(), dislodgement.displacedFrom())
                        + "; attacker=" + OrderForm.unitText(dislodgement.attacker(), dislodgement.attackerOrigin()))
                .toList());

    }

    private static void printLines(String heading, Collection<String> lines) {

        System.out.println(heading + ":");
        if (lines.isEmpty())
            System.out.println("  (none)");
        else
            lines.stream().sorted().forEach(line -> System.out.println("  " + line));

    }

}