package analysis;

import domain.*;
import game.BoardState;
import phase.UnitId;
import phase.movement.MovementResult;

import java.util.*;

/** Actual movement-end deltas, ignoring lost units on both sides of the comparison. */
public final class PositionShaping {
    private static final int[][][] DISTANCES = buildDistances();

    private PositionShaping() { }

    public record UnitDelta(UnitId unit, Province before, Province after, double beforeValue,
                            double afterValue, double contribution, boolean surviving) {
        public UnitDelta {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(before, "before");
            if ((surviving && after == null) || !Double.isFinite(beforeValue)
                    || !Double.isFinite(afterValue) || !Double.isFinite(contribution)
                    || Double.compare(contribution, surviving ? afterValue - beforeValue : 0) != 0)
                throw new IllegalArgumentException("Invalid unit shaping delta");
        }
    }
    public record UnitPotential(UnitId unit, int beforeDistance, int afterDistance,
                                double beforePotential, double afterPotential) {
        public UnitPotential {
            Objects.requireNonNull(unit, "unit");
            if (beforeDistance < -1 || afterDistance < -1 || !Double.isFinite(beforePotential)
                    || !Double.isFinite(afterPotential))
                throw new IllegalArgumentException("Invalid unit regional potential");
        }
    }
    public record ObjectiveDelta(String name, double beforePotential, double afterPotential,
                                 double contribution, List<UnitPotential> units) {
        public ObjectiveDelta {
            Objects.requireNonNull(name, "name");
            units = List.copyOf(units);
            if (!Double.isFinite(beforePotential) || !Double.isFinite(afterPotential)
                    || !Double.isFinite(contribution)
                    || Double.compare(contribution, afterPotential - beforePotential) != 0)
                throw new IllegalArgumentException("Invalid objective shaping delta");
        }
    }
    public record Breakdown(double provinceContribution, double regionalContribution,
                            List<UnitDelta> units, List<ObjectiveDelta> objectives) {
        public Breakdown {
            units = List.copyOf(units);
            objectives = List.copyOf(objectives);
            if (!Double.isFinite(provinceContribution) || !Double.isFinite(regionalContribution))
                throw new IllegalArgumentException("Shaping overflowed");
            double provinceTotal = 0, regionalTotal = 0;
            for (UnitDelta unit : units)
                provinceTotal += unit.contribution();
            for (ObjectiveDelta objective : objectives)
                regionalTotal += objective.contribution();
            if (Double.compare(provinceContribution, provinceTotal) != 0
                    || Double.compare(regionalContribution, regionalTotal) != 0)
                throw new IllegalArgumentException("Inconsistent shaping totals");
        }
        public static Breakdown empty() { return new Breakdown(0, 0, List.of(), List.of()); }
    }

    public static Breakdown evaluate(BoardState board, Nation nation, MovementResult result,
                                     ScoringConfiguration configuration) {
        Objects.requireNonNull(result, "result");
        return evaluate(board, nation, result.finalLocations(), result.dislodgements().keySet(), configuration);
    }

    public static Breakdown evaluate(BoardState board, Nation nation, Map<UnitId, Province> after,
                                     Set<UnitId> dislodged, ScoringConfiguration configuration) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(nation, "nation");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(dislodged, "dislodged");
        Objects.requireNonNull(configuration, "configuration");
        List<UnitId> own = HumanPreference.sortedUnits(board).stream()
                .filter(unit -> unit.owner() == nation).toList();
        List<UnitDelta> units = new ArrayList<>();
        double provinces = 0;
        for (UnitId unit : own) {
            Province before = board.locationOf(unit);
            Province finalPosition = after.get(unit);
            boolean survives = finalPosition != null && !dislodged.contains(unit);
            double beforeValue = configuration.effectiveValue(nation, unit.unitType(), before);
            double afterValue = survives
                    ? configuration.effectiveValue(nation, unit.unitType(), finalPosition) : 0;
            double contribution = survives ? afterValue - beforeValue : 0;
            provinces += contribution;
            units.add(new UnitDelta(unit, before, finalPosition, beforeValue, afterValue,
                    contribution, survives));
        }
        List<ObjectiveDelta> objectives = new ArrayList<>();
        double regions = 0;
        for (var objective : configuration.profile(nation).objectives()) {
            List<UnitPotential> potentials = new ArrayList<>();
            double bestBefore = Double.NEGATIVE_INFINITY;
            double bestAfter = Double.NEGATIVE_INFINITY;
            for (UnitDelta unit : units) {
                if (!objective.unitTypes().contains(unit.unit().unitType()))
                    continue;
                int beforeDistance = distance(unit.unit().unitType(), unit.before(), objective.targets());
                int afterDistance = unit.surviving()
                        ? distance(unit.unit().unitType(), unit.after(), objective.targets()) : -1;
                double beforePotential = unit.surviving() ? potential(objective, beforeDistance) : 0;
                double afterPotential = unit.surviving() ? potential(objective, afterDistance) : 0;
                potentials.add(new UnitPotential(unit.unit(), beforeDistance, afterDistance,
                        beforePotential, afterPotential));
                if (unit.surviving()) {
                    bestBefore = Math.max(bestBefore, beforePotential);
                    bestAfter = Math.max(bestAfter, afterPotential);
                }
            }
            if (bestBefore == Double.NEGATIVE_INFINITY) {
                bestBefore = 0;
                bestAfter = 0;
            }
            double contribution = bestAfter - bestBefore;
            regions += contribution;
            objectives.add(new ObjectiveDelta(objective.name(), bestBefore, bestAfter,
                    contribution, potentials));
        }
        return new Breakdown(provinces, regions, units, objectives);
    }

    public static double potential(ScoringConfiguration.RegionalObjective objective, int distance) {
        return distance < 0 || distance > objective.horizon() ? 0
                : objective.priority() * Math.exp(-objective.decay() * distance);
    }

    /** Cheap candidate-retention hint only; never used as an adjudicated outcome. */
    public static double optimisticDelta(BoardState board, OrderPlan plan,
                                         ScoringConfiguration configuration) {
        Map<UnitId, Province> projected = new LinkedHashMap<>(board.locations());
        for (phase.Order order : plan.orders()) {
            Province from = board.locationOf(order.unit());
            Province to = order.target();
            if (order.orderType() == OrderType.MOVE && from != null && to != null
                    && !Province.equalsIgnoreCoast(from, to) && passable(order.unitType(), to)
                    && from.isAdjacentTo(to)
                    && (order.unitType() == UnitType.ARMY || Province.adjacentBySea(from, to)))
                projected.put(order.unit(), to);
        }
        Breakdown breakdown = evaluate(board, plan.nation(), projected, Set.of(), configuration);
        double hint = breakdown.provinceContribution() + breakdown.regionalContribution();
        if (!Double.isFinite(hint))
            throw new IllegalArgumentException("Optimistic shaping overflowed");
        return hint;
    }

    /** Armies use canonical land targets without convoys; fleet coast targets stay exact. */
    public static int distance(UnitType type, Province from, Set<Province> targets) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(targets, "targets");
        if (type == UnitType.ARMY)
            from = Province.canonical(from);
        if (!passable(type, from))
            return -1;
        int best = Integer.MAX_VALUE;
        for (Province target : targets) {
            Objects.requireNonNull(target, "target");
            if (type == UnitType.ARMY)
                target = Province.canonical(target);
            int distance = DISTANCES[type.ordinal()][from.ordinal()][target.ordinal()];
            if (distance >= 0)
                best = Math.min(best, distance);
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private static int[][][] buildDistances() {
        Province[] provinces = Province.values();
        int[][][] result = new int[UnitType.values().length][provinces.length][provinces.length];
        for (UnitType type : UnitType.values()) {
            for (Province from : provinces) {
                int[] distances = result[type.ordinal()][from.ordinal()];
                Arrays.fill(distances, -1);
                if (!passable(type, from))
                    continue;
                ArrayDeque<Province> queue = new ArrayDeque<>();
                distances[from.ordinal()] = 0;
                queue.add(from);
                while (!queue.isEmpty()) {
                    Province current = queue.remove();
                    for (Province next : provinces) {
                        if (distances[next.ordinal()] >= 0 || !passable(type, next))
                            continue;
                        boolean adjacent = current.isAdjacentTo(next)
                                && (type == UnitType.ARMY || Province.adjacentBySea(current, next));
                        if (adjacent) {
                            distances[next.ordinal()] = distances[current.ordinal()] + 1;
                            queue.add(next);
                        }
                    }
                    if (type == UnitType.FLEET)
                        for (Province coast : provinces) {
                            if (coast.parent == null || distances[coast.ordinal()] < 0)
                                continue;
                            int parentDistance = distances[coast.parent.ordinal()];
                            distances[coast.parent.ordinal()] = parentDistance < 0
                                    ? distances[coast.ordinal()]
                                    : Math.min(parentDistance, distances[coast.ordinal()]);
                        }
                }
            }
        }
        return result;
    }

    private static boolean passable(UnitType type, Province province) {
        return province != Province.Swi && (type == UnitType.ARMY
                ? province.geography != Geography.WATER && province.parent == null
                : province.geography != Geography.INLAND);
    }
}
