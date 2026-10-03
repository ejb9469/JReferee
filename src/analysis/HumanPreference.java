package analysis;

import game.BoardState;
import phase.Order;
import phase.UnitId;

import java.util.*;

/** Average log frequency under the full post-policy evidence, before candidate truncation. */
public record HumanPreference(boolean available, double score, List<UnitPreference> units) {
    public record UnitPreference(UnitId unit, Order order, long selectedCount, long observations,
                                 String basis, double logFrequency, boolean available,
                                 String diagnostic) {
        public UnitPreference {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(diagnostic, "diagnostic");
            if (selectedCount < 0 || observations < selectedCount
                    || !Double.isFinite(logFrequency) || logFrequency > 0)
                throw new IllegalArgumentException("Invalid unit preference");
            if (available && (order == null || selectedCount == 0 || observations == 0))
                throw new IllegalArgumentException("Available preference requires observations");
            if (order != null && !order.unit().equals(unit))
                throw new IllegalArgumentException("Preference order must belong to its unit");
            double expected = available ? Math.log((double) selectedCount / observations) : 0;
            if (Double.compare(logFrequency, expected) != 0)
                throw new IllegalArgumentException("Inconsistent frequency arithmetic");
        }

        public long denominator() { return observations; }
    }

    public HumanPreference {
        units = List.copyOf(units);
        if (!Double.isFinite(score) || score > 0 || (!available && score != 0))
            throw new IllegalArgumentException("Invalid human preference");
        boolean complete = !units.isEmpty() && units.stream().allMatch(UnitPreference::available);
        double sum = 0;
        Set<UnitId> identities = new HashSet<>();
        for (UnitPreference unit : units) {
            if (!identities.add(unit.unit()))
                throw new IllegalArgumentException("Duplicate unit preference");
            sum += unit.logFrequency();
        }
        if (available != complete || Double.compare(score, complete ? sum / units.size() : 0) != 0)
            throw new IllegalArgumentException("Inconsistent preference normalization");
    }

    public static HumanPreference unavailable() {
        return new HumanPreference(false, 0, List.of());
    }

    public String diagnostic() {
        if (available)
            return "";
        if (units.isEmpty())
            return "No post-policy evidence";
        return String.join("; ", units.stream().filter(unit -> !unit.available())
                .map(unit -> unit.unit().owner() + " " + unit.unit().unitType() + " "
                        + unit.unit().origin() + ": " + unit.diagnostic()).toList());
    }

    public static HumanPreference evaluate(BoardState board, OrderPlan plan,
                                           Map<UnitId, RoutePrediction> evidence) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(evidence, "evidence");
        for (var entry : evidence.entrySet()) {
            UnitId query = Objects.requireNonNull(entry.getKey(), "evidence query");
            RoutePrediction prediction = Objects.requireNonNull(entry.getValue(), "prediction");
            for (Order observed : prediction.counts().keySet())
                if (!observed.unit().equals(query))
                    throw new IllegalArgumentException("Evidence orders must belong to their query unit");
        }
        Map<UnitId, Order> chosen = new HashMap<>();
        for (Order order : plan.orders())
            chosen.put(order.unit(), order);
        List<UnitPreference> details = new ArrayList<>();
        boolean complete = true;
        double sum = 0;
        for (UnitId unit : sortedUnits(board).stream()
                .filter(unit -> unit.owner() == plan.nation()).toList()) {
            Order order = chosen.get(unit);
            RoutePrediction prediction = evidence.get(unit);
            long observations = prediction == null ? 0 : prediction.observations();
            long count = prediction == null || order == null ? 0
                    : prediction.counts().getOrDefault(order, 0L);
            boolean available = order != null && observations > 0 && count > 0;
            double log = available ? Math.log((double) count / observations) : 0;
            String diagnostic = order == null ? "Missing selected order"
                    : prediction == null ? "Missing post-policy evidence"
                    : observations == 0 ? "No selected observations"
                    : count == 0 ? "Selected order absent from evidence" : "";
            details.add(new UnitPreference(unit, order, count, observations,
                    prediction == null ? "unavailable" : prediction.basis(), log, available, diagnostic));
            complete &= available;
            sum += log;
        }
        complete &= !details.isEmpty();
        return new HumanPreference(complete, complete ? sum / details.size() : 0, details);
    }

    static List<UnitId> sortedUnits(BoardState board) {
        return board.locations().keySet().stream().sorted(
                Comparator.comparing((UnitId unit) -> board.locationOf(unit).name())
                        .thenComparing(unit -> unit.owner().name())
                        .thenComparing(unit -> unit.unitType().name())).toList();
    }
}
