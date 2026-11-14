package _app;

import agents.generation.*;
import domain.OrderType;
import game.GameState_Movement;

import java.io.*;
import java.util.*;

/**
 * Retained-pool findings and their mass under the search playing policy.
 * Construction attempts are separate from raw-policy sampling counts.
 */
public final class ProposalDiagnostics {

    public record Construction(int target, int attempts, int deadEnds, int duplicates, long choices) { }
    public record Mixing(int learnedAvailable, int learnedSelected, int heuristicAvailable, int heuristicSelected, int overlap) { }
    public record Row(int orders, int holdOrders, int emptyActions, int allHoldActions,
                      int blockerActions, int convoyBlockerActions, int dependencyActions, int cautionActions,
                      double blockerMass, double convoyBlockerMass, double dependencyMass, double cautionMass,
                      double allHoldMass, double holdFraction, Construction construction, Mixing mixing) { }

    private static final List<String> COLUMNS = List.of("orders", "hold_orders", "empty_actions", "all_hold_actions",
            "blocker_actions", "convoy_blocker_actions", "dependency_actions", "caution_actions",
            "blocker_mass", "convoy_blocker_mass", "dependency_mass", "caution_mass", "all_hold_mass", "hold_fraction",
            "construction_target", "construction_attempts", "construction_dead_ends", "construction_duplicates", "construction_choices",
            "learned_available", "learned_selected", "heuristic_available", "heuristic_selected", "overlap");

    private ProposalDiagnostics() { }

    public static Row measure(GameState_Movement root, LegalOrderCatalog catalog, List<ActionCandidate> candidates,
                              List<Double> probabilities, CoherentPolicyActionGenerator.Result coherent,
                              HybridActionGenerator.Result hybrid) {
        if (candidates.isEmpty() || candidates.size() != probabilities.size()
                || probabilities.stream().anyMatch(p -> !Double.isFinite(p) || p < 0 || p > 1)
                || Math.abs(probabilities.stream().mapToDouble(Double::doubleValue).sum() - 1) > 1e-9)
            throw new IllegalArgumentException("Expected a complete search distribution");
        var feasibility = new MovementFeasibility(root, catalog);
        int orders = 0, holds = 0, empty = 0, allHold = 0, blockers = 0, convoyBlockers = 0, dependencies = 0, cautions = 0;
        double blockerMass = 0, convoyMass = 0, dependencyMass = 0, cautionMass = 0, allHoldMass = 0, holdFraction = 0;
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            var findings = feasibility.inspect(candidate);
            double probability = probabilities.get(index);
            if (findings.stream().anyMatch(f -> f.kind() == MovementFeasibility.Kind.BLOCKER)) {
                blockers++; blockerMass += probability;
            }
            if (findings.stream().anyMatch(f -> f.code() == MovementFeasibility.Code.NO_CONVOY_ROUTE
                    || f.code() == MovementFeasibility.Code.CONVOY_OFF_ROUTE)) {
                convoyBlockers++; convoyMass += probability;
            }
            if (findings.stream().anyMatch(f -> f.kind() == MovementFeasibility.Kind.DEPENDENCY)) {
                dependencies++; dependencyMass += probability;
            }
            if (findings.stream().anyMatch(f -> f.kind() == MovementFeasibility.Kind.CAUTION)) {
                cautions++; cautionMass += probability;
            }
            int count = candidate.orders().size();
            int ownHolds = (int) candidate.orders().stream().filter(order -> order.orderType() == OrderType.HOLD).count();
            orders += count; holds += ownHolds;
            if (count == 0) empty++;
            else {
                holdFraction += probability * ownHolds / count;
                if (ownHolds == count) { allHold++; allHoldMass += probability; }
            }
        }
        Construction construction = coherent == null ? null : new Construction(coherent.target(), coherent.attempts(),
                coherent.deadEnds(), coherent.duplicates(), coherent.choices());
        Mixing mixing = hybrid != null ? new Mixing(hybrid.learnedAvailable(), hybrid.learnedSelected(),
                hybrid.heuristicAvailable(), hybrid.heuristicSelected(), hybrid.overlap())
                : coherent == null ? null : new Mixing(coherent.candidates().size(), coherent.candidates().size(), 0, 0, 0);
        if (coherent != null && blockers != 0) throw new IllegalStateException("A coherent pool contains a blocker");
        if (mixing != null && mixing.learnedSelected() + mixing.heuristicSelected() != candidates.size())
            throw new IllegalStateException("Construction counts differ from the retained pool");
        return new Row(orders, holds, empty, allHold, blockers, convoyBlockers, dependencies, cautions,
                blockerMass, convoyMass, dependencyMass, cautionMass, allHoldMass, holdFraction, construction, mixing);
    }

    public static void header(Writer out, String... prefix) throws IOException {
        List<Object> fields = new ArrayList<>(Arrays.asList(prefix));
        fields.addAll(COLUMNS);
        PolicyProposalBenchmarkApp.csv(out, fields.toArray());
    }

    public static void write(Writer out, Row row, Object... prefix) throws IOException {
        List<Object> fields = new ArrayList<>(Arrays.asList(prefix));
        var c = row.construction(); var m = row.mixing();
        Collections.addAll(fields, row.orders(), row.holdOrders(), row.emptyActions(), row.allHoldActions(),
                row.blockerActions(), row.convoyBlockerActions(), row.dependencyActions(), row.cautionActions(),
                row.blockerMass(), row.convoyBlockerMass(), row.dependencyMass(), row.cautionMass(), row.allHoldMass(), row.holdFraction(),
                c == null ? null : c.target(), c == null ? null : c.attempts(), c == null ? null : c.deadEnds(),
                c == null ? null : c.duplicates(), c == null ? null : c.choices(),
                m == null ? null : m.learnedAvailable(), m == null ? null : m.learnedSelected(),
                m == null ? null : m.heuristicAvailable(), m == null ? null : m.heuristicSelected(), m == null ? null : m.overlap());
        PolicyProposalBenchmarkApp.csv(out, fields.toArray());
    }
}