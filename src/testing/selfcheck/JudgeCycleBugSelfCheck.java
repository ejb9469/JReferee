package testing.selfcheck;

import adjudication.Judge;
import adjudication.Order;
import adjudication.ResolutionContext;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;

import java.util.*;
import java.util.function.Predicate;

/**
 * Exercises the real resolver with controlled nested dependency graphs.
 * Board-strength equations are replaced, not the recursive resolution logic.
 */
public final class JudgeCycleBugSelfCheck {

    private static final List<Province> ORIGINS = List.of(
            Province.Par, Province.Pic, Province.Bel, Province.Ruh,
            Province.Mun, Province.Mar, Province.Gas);

    private static int checks;

    private JudgeCycleBugSelfCheck() { }

    public static void main(String[] args) {

        if (args.length != 0)
            throw new IllegalArgumentException("Usage: JudgeCycleBugSelfCheck");

        Map<Province, Rule> ancestor = new EnumMap<>(Province.class);
        ancestor.put(Province.Par, all(Province.Pic, Province.Ruh));
        ancestor.put(Province.Pic, all(Province.Bel));
        ancestor.put(Province.Bel, all(Province.Par));
        ancestor.put(Province.Ruh, any(Province.Par, Province.Gas));
        ancestor.put(Province.Gas, any());
        check("ancestor dependency", ancestor,
                Set.of(Province.Par, Province.Pic, Province.Bel, Province.Ruh));

        Map<Province, Rule> nested = nestedRules();
        check("nested backups", nested, Set.copyOf(nested.keySet()));

        Map<Province, Rule> leaf = nestedRules();
        leaf.put(Province.Ruh, all(Province.Mun, Province.Gas));
        leaf.put(Province.Gas, all());
        check("definitive leaf", leaf, Set.copyOf(leaf.keySet()));

        Map<Province, Rule> failure = nestedRules();
        failure.put(Province.Par, all(Province.Pic, Province.Ruh, Province.Gas));
        failure.put(Province.Gas, any());
        check("failed outer cycle", failure, Set.of(Province.Ruh, Province.Mun, Province.Mar));

        System.out.println("JudgeCycleBugSelfCheck passed: " + checks + " verdict checks");

    }

    private static Map<Province, Rule> nestedRules() {

        Map<Province, Rule> rules = new EnumMap<>(Province.class);
        rules.put(Province.Par, all(Province.Pic, Province.Ruh));
        rules.put(Province.Pic, all(Province.Bel));
        rules.put(Province.Bel, all(Province.Par));
        rules.put(Province.Ruh, all(Province.Mun));
        rules.put(Province.Mun, all(Province.Mar));
        rules.put(Province.Mar, all(Province.Ruh));
        return rules;

    }

    private static void check(String name, Map<Province, Rule> rules, Set<Province> successful) {

        List<Province> order = new ArrayList<>();
        for (Province origin : ORIGINS)
            if (rules.containsKey(origin))
                order.add(origin);
        permutations(name, order, 0, Map.copyOf(rules), successful);

    }

    private static void permutations(
            String name, List<Province> order, int start,
            Map<Province, Rule> rules, Set<Province> successful
    ) {

        if (start == order.size()) {
            List<Order> work = order.stream()
                    .map(origin -> new Order(Nation.FRANCE, UnitType.ARMY,
                            origin, OrderType.MOVE, Province.Bur))
                    .toList();
            new DependencyJudge(work, rules).judge();
            for (Order result : work) {
                if (!result.resolved || result.visited
                        || result.verdict != successful.contains(result.pos0))
                    throw new AssertionError(name + ": " + order + ": "
                            + result + " " + result.metaToString());
                checks++;
            }
            return;
        }

        for (int next = start; next < order.size(); next++) {
            Collections.swap(order, start, next);
            permutations(name, order, start + 1, rules, successful);
            Collections.swap(order, start, next);
        }

    }

    private record Rule(boolean any, List<Province> dependencies) { }

    private static Rule all(Province... dependencies) {
        return new Rule(false, List.of(dependencies));
    }

    private static Rule any(Province... dependencies) {
        return new Rule(true, List.of(dependencies));
    }

    private static final class DependencyJudge extends Judge {

        private final Map<Province, Rule> rules;
        private int adjudications;

        private DependencyJudge(List<Order> orders, Map<Province, Rule> rules) {
            super(orders);
            this.rules = rules;
        }

        @Override
        protected boolean adjudicate(Order order, boolean optimistic, ResolutionContext context) {

            if (++this.adjudications > 1000)
                throw new AssertionError("Resolver retried without making progress: " + order);

            Rule rule = Objects.requireNonNull(this.rules.get(order.pos0));
            // All orders share a destination, keeping the fixture in one component.
            // Hold strength delegates MOVE dependencies to Judge's actual resolver.
            Predicate<Province> succeeds = origin ->
                    calculateHoldStrength(origin, optimistic, this.orders, context) == 0;
            return rule.any()
                    ? rule.dependencies().stream().anyMatch(succeeds)
                    : rule.dependencies().stream().allMatch(succeeds);

        }

    }

}