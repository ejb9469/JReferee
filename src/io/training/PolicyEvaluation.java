package io.training;

import agents.policy.MovementPolicy;
import domain.Nation;
import agents.encoding.EncodingPurpose;

import java.io.IOException;
import java.util.*;

import static io.json.JsonEscaper.appendString;

/**
 * Held-out, teacher-forced order metrics.<br><br>
 *
 * Communication labels are used only to group the report;
 * (!!)they are never passed to the policy(!!).
 */
public class PolicyEvaluation {  // all subclasses should be final

    private PolicyEvaluation() { }

    public record Metrics(long examples, long orders, double loss, double uniformLoss,
                          long correctOrders, long exactActions) {
        public Metrics {
            if (examples < 1 || orders < examples || !Double.isFinite(loss) || loss < 0
                    || !Double.isFinite(uniformLoss) || uniformLoss < 0 || correctOrders < 0
                    || correctOrders > orders || exactActions < 0 || exactActions > examples)
                throw new IllegalArgumentException("Invalid evaluation counts or losses");
        }
        public double orderNll() { return loss / orders; }
        public String toJson() {
            double perplexity = Math.exp(orderNll());
            return "{\"examples\":" + examples + ",\"orders\":" + orders
                    + ",\"order_nll\":" + orderNll()
                    + ",\"perplexity\":" + (Double.isFinite(perplexity) ? Double.toString(perplexity) : "null")
                    + ",\"uniform_order_nll\":" + uniformLoss / orders
                    + ",\"order_accuracy\":" + (double) correctOrders / orders
                    + ",\"exact_action_accuracy\":" + (double) exactActions / examples + "}";
        }
    }

    public record Result(EncodingPurpose split, Metrics overall, Map<Nation, Metrics> powers,
                         Map<String, Metrics> communication, MovementDataset.Statistics reading,
                         long elapsedNanos) {
        public Result {
            powers = Collections.unmodifiableMap(new EnumMap<>(powers));
            Map<String, Metrics> labels = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
            labels.putAll(communication);
            communication = Collections.unmodifiableMap(labels);
        }
        public String toJson() {
            StringBuilder out = new StringBuilder("{\"split\":\"").append(split)
                    .append("\",\"overall\":").append(overall.toJson())
                    .append(",\"performance\":").append(performanceJson(reading, elapsedNanos))
                    .append(",\"by_power\":{");
            boolean first = true;
            for (var entry : powers.entrySet()) {
                if (!first) out.append(',');
                first = false;
                appendString(out, entry.getKey().name());
                out.append(':').append(entry.getValue().toJson());
            }
            out.append("},\"by_communication\":[");
            first = true;
            for (var entry : communication.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append("{\"communication_type\":");
                if (entry.getKey() == null) out.append("null");
                else appendString(out, entry.getKey());
                out.append(",\"metrics\":").append(entry.getValue().toJson()).append('}');
            }
            return out.append("]}").toString();
        }
    }

    public static Result evaluate(MovementDataset dataset, EncodingPurpose split, MovementPolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy");
        if (dataset.examples(split) == 0) throw new IllegalArgumentException("No examples in " + split);
        long start = System.nanoTime();
        Counter overall = new Counter();
        Map<Nation, Counter> powers = new EnumMap<>(Nation.class);
        Map<String, Counter> communication = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        MovementDataset.Statistics reading;
        try (var cursor = dataset.open(split)) {
            MovementDataset.Example example;
            while ((example = cursor.next()) != null) {
                var input = example.input();
                var prepared = policy.prepare(input);
                Counter power = powers.computeIfAbsent(input.power(), ignored -> new Counter());
                Counter label = communication.computeIfAbsent(example.communicationType(), ignored -> new Counter());
                double loss = 0, uniformLoss = 0;
                long correct = 0;
                for (int slot = 0; slot < example.action().size(); slot++) {
                    int options = input.slots().get(slot).options().size();
                    double[] probabilities = prepared.logProbabilities(example.action().subList(0, slot));
                    MovementPolicy.requireDistribution(probabilities, options);
                    int target = example.action().get(slot), best = 0;
                    if (!Double.isFinite(probabilities[target]))
                        throw new IllegalArgumentException("Policy assigns zero probability to an observed order");
                    loss -= probabilities[target];
                    uniformLoss += Math.log(options);
                    for (int option = 1; option < options; option++)
                        if (probabilities[option] > probabilities[best]) best = option;
                    if (target == best) correct++;
                }
                boolean exact = prepared.greedy().equals(example.action());
                for (Counter counter : List.of(overall, power, label))
                    counter.add(example.action().size(), loss, uniformLoss, correct, exact);
            }
            reading = cursor.statistics();
        }
        if (overall.examples != dataset.examples(split))
            throw new IOException("Evaluation count differs from the dataset");
        Map<Nation, Metrics> powerMetrics = new EnumMap<>(Nation.class);
        powers.forEach((power, counter) -> powerMetrics.put(power, counter.metrics()));
        Map<String, Metrics> labelMetrics = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        communication.forEach((label, counter) -> labelMetrics.put(label, counter.metrics()));
        return new Result(split, overall.metrics(), powerMetrics, labelMetrics, reading, System.nanoTime() - start);
    }

    static String performanceJson(MovementDataset.Statistics statistics, long elapsedNanos) {
        double seconds = elapsedNanos / 1e9;
        return "{\"elapsed_seconds\":" + seconds
                + ",\"read_decode_seconds\":" + statistics.readNanos() / 1e9
                + ",\"compute_seconds\":" + (elapsedNanos - statistics.readNanos()) / 1e9
                + ",\"examples_per_second\":" + statistics.examples() / seconds
                + ",\"options_per_second\":" + statistics.options() / seconds
                + ",\"characters\":" + statistics.characters()
                + ",\"cache_hits\":" + statistics.cacheHits() + ",\"cache_misses\":" + statistics.cacheMisses()
                + ",\"peak_cached_options\":" + statistics.peakCachedOptions()
                + ",\"split_sha256\":" + (statistics.sha256() == null ? "null" : "\"" + statistics.sha256() + "\"") + "}";
    }

    private static final class Counter {
        private long examples, orders, correctOrders, exactActions;
        private double loss, uniformLoss;
        private void add(int size, double nll, double uniform, long correct, boolean exact) {
            examples++;
            orders += size;
            loss += nll;
            uniformLoss += uniform;
            correctOrders += correct;
            if (exact) exactActions++;
            if (!Double.isFinite(loss)) throw new IllegalStateException("Nonfinite accumulated policy loss");
        }
        private Metrics metrics() {
            return new Metrics(examples, orders, loss, uniformLoss, correctOrders, exactActions);
        }
    }
}