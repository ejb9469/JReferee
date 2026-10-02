package analysis;

import phase.Order;

import java.util.*;


public record RoutePrediction(
        String basis,
        int suffixLength,
        Map<Order, Long> counts
) {


    public RoutePrediction {

        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(counts, "counts");

        Map<Order, Long> copy = new LinkedHashMap<>();

        for (Map.Entry<Order, Long> entry : counts.entrySet()) {

            Objects.requireNonNull(entry.getKey(), "order");
            Long count = Objects.requireNonNull(entry.getValue(), "count");

            if (count < 1)
                throw new IllegalArgumentException("Observation counts must be positive");

            copy.put(entry.getKey(), count);

        }

        counts = Collections.unmodifiableMap(copy);

    }


    public long observations() {

        long total = 0;

        for (long count : counts.values())
            total = Math.addExact(total, count);

        return total;

    }

    public Optional<Order> mostCommon() {
        return counts.keySet().stream().findFirst();
    }

    public Map<Order, Double> probabilities() {

        long total = observations();
        Map<Order, Double> probabilities = new LinkedHashMap<>();

        for (Map.Entry<Order, Long> entry : counts.entrySet())
            probabilities.put(entry.getKey(), (double) entry.getValue() / total);

        return Collections.unmodifiableMap(probabilities);

    }


}