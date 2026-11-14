package _app;

import agents.execution.ProposalInspector;
import agents.execution.ProposalInspector.*;
import agents.generation.MovementFeasibility;
import domain.Nation;
import domain.OrderType;
import io.training.ValidationPositions;
import io.training.ValidationPositions.Position;
import ui.ProposalInspectionJsonWriter;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static io.json.JsonEscaper.appendString;

/**
 * Complete proposal comparisons on supplied movement positions.
 * Counts describe retained pools without probability weighting.
 */
public final class ProposalExportApp {

    public static final List<Method> METHODS = List.of(Method.PLAUSIBLE, Method.GRU,
            Method.COHERENT_GRU, Method.COHERENT_GRU_HYBRID);
    private static final String USAGE = "Usage: ProposalExportApp --positions positions.json "
            + "--cases rank-prefix:POWER[,rank-prefix:POWER...] --output new-report.json "
            + "--linear-model policy.bin --gru-model model.bin [--anchor-model policy.bin] "
            + "[--candidates 1..64] [--seed long]";
    private ProposalExportApp() { }

    public record Case(Position position, Nation power) {
        public Case {
            Objects.requireNonNull(position);
            if (position.rank() == null || !position.rank().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Expected a complete validation position rank");
            if (!position.state().aliveNations().contains(Objects.requireNonNull(power)))
                throw new IllegalArgumentException("Eliminated power " + power + " at " + position.rank());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) { System.out.println(USAGE); return; }
        Set<String> names = Set.of("--positions", "--cases", "--output", "--linear-model", "--anchor-model",
                "--gru-model", "--candidates", "--seed");
        Map<String, String> flags = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2)
            if (index + 1 == args.length || !names.contains(args[index])
                    || args[index + 1].isBlank() || flags.putIfAbsent(args[index], args[index + 1]) != null)
                throw new IllegalArgumentException(USAGE);
        if (!flags.keySet().containsAll(Set.of("--positions", "--cases", "--output", "--linear-model", "--gru-model")))
            throw new IllegalArgumentException(USAGE);
        long seed = Long.parseLong(flags.getOrDefault("--seed", "7"));
        var options = new Options(Integer.parseInt(flags.getOrDefault("--candidates", "8")), 16, 1, 8, seed, seed, seed, false);
        Path output = Path.of(flags.get("--output")).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new FileAlreadyExistsException(output.toString());
        if (!Files.isDirectory(output.getParent())) throw new NoSuchFileException(output.getParent().toString());
        var selection = ValidationPositions.load(Path.of(flags.get("--positions")));
        var cases = select(selection.positions(), flags.get("--cases"));
        Path linear = Path.of(flags.get("--linear-model"));
        var inspector = ProposalInspector.load(linear, Path.of(flags.getOrDefault("--anchor-model", linear.toString())),
                Path.of(flags.get("--gru-model")));
        String report = export(inspector, cases, options, System.out);
        Files.writeString(output, report + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println("Saved " + output + ". Send this single JSON file; no browser is required.");
    }

    public static List<Case> select(List<Position> positions, String selectors) {
        List<Case> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String selector : selectors.split(",", -1)) {
            String[] parts = selector.trim().split(":", -1);
            if (parts.length != 2 || !parts[0].matches("[0-9a-f]{6,64}"))
                throw new IllegalArgumentException("Expected rank prefix of 6..64 lowercase hex characters followed by :POWER");
            var matches = positions.stream().filter(position -> position.rank().startsWith(parts[0])).toList();
            if (matches.size() != 1)
                throw new IllegalArgumentException("Position prefix " + parts[0] + " matches " + matches.size()
                        + " boards; use an exact rank from the supplied positions file");
            Case selected = new Case(matches.getFirst(), Nation.valueOf(parts[1]));
            if (!seen.add(selected.position().rank() + ":" + selected.power()))
                throw new IllegalArgumentException("Repeated case: " + selector);
            result.add(selected);
        }
        return List.copyOf(result);
    }

    public static String export(ProposalInspector inspector, List<Case> cases, Options options, PrintStream progress) {
        Objects.requireNonNull(inspector); Objects.requireNonNull(options); Objects.requireNonNull(progress);
        cases = List.copyOf(cases);
        if (cases.isEmpty() || options.search()) throw new IllegalArgumentException("Supply cases with search disabled");
        Set<String> seen = new HashSet<>();
        for (Case selected : cases)
            if (!seen.add(selected.position().rank() + ":" + selected.power()))
                throw new IllegalArgumentException("Repeated export case");
        StringBuilder out = new StringBuilder("{\"schema\":\"jreferee-proposal-export-v1\",\"testEvaluated\":false")
                .append(",\"search\":false,\"candidates\":").append(options.candidates()).append(",\"poolSeed\":");
        appendString(out, Long.toString(options.poolSeed()));
        out.append(",\"cases\":[");
        int completed = 0, exhausted = 0;
        for (int index = 0; index < cases.size(); index++) {
            if (index > 0) out.append(',');
            Case selected = cases.get(index);
            Position position = selected.position();
            out.append("{\"position\":{\"id\":"); appendString(out, position.rank());
            out.append(",\"source\":"); appendString(out, position.source());
            out.append(",\"game\":"); appendString(out, position.gameKey());
            out.append(",\"year\":").append(position.state().moment().year()).append(",\"phase\":");
            appendString(out, position.state().moment().gamePhase().name());
            out.append(",\"communication\":");
            if (position.communication() == null) out.append("null");
            else appendString(out, position.communication());
            out.append("},\"power\":"); appendString(out, selected.power().name());
            out.append(",\"methods\":[");
            for (int methodIndex = 0; methodIndex < METHODS.size(); methodIndex++) {
                Method method = METHODS.get(methodIndex);
                if (methodIndex > 0) out.append(',');
                out.append("{\"method\":"); appendString(out, method.name());
                long start = System.nanoTime();
                Inspection inspection;
                try {
                    inspection = inspector.inspect(position.state(), selected.power(), method, options);
                } catch (ProposalInspector.CoherentExhaustion failure) {
                    if (method != Method.COHERENT_GRU || failure.power() != selected.power()) throw failure;
                    var generation = failure.generation();
                    double millis = (System.nanoTime() - start) / 1e6;
                    out.append(",\"status\":\"EXHAUSTED\",\"inspectionMillis\":").append(millis).append(",\"error\":");
                    appendString(out, failure.getMessage());
                    out.append(",\"generation\":{\"target\":").append(generation.target())
                            .append(",\"attempts\":").append(generation.attempts()).append(",\"deadEnds\":").append(generation.deadEnds())
                            .append(",\"duplicates\":").append(generation.duplicates()).append(",\"choices\":").append(generation.choices())
                            .append(",\"learnedAvailable\":0},\"summary\":null,\"inspection\":null}");
                    exhausted++;
                    progress.printf(Locale.ROOT, "[%d/%d] %s %s %s EXHAUSTED attempts=%d choices=%d%n",
                            ++completed, cases.size() * METHODS.size(), position.rank().substring(0, 6), selected.power(),
                            method, generation.attempts(), generation.choices());
                    continue;
                }
                double millis = (System.nanoTime() - start) / 1e6;
                var counts = summarize(inspection);
                out.append(",\"status\":\"OK\",\"inspectionMillis\":").append(millis).append(",\"summary\":{");
                boolean first = true;
                for (var entry : counts.entrySet()) {
                    if (!first) out.append(',');
                    appendString(out, entry.getKey()); out.append(':').append(entry.getValue());
                    first = false;
                }
                out.append("},\"inspection\":").append(ProposalInspectionJsonWriter.toJson(inspection)).append('}');
                progress.printf(Locale.ROOT, "[%d/%d] %s %s %-19s pool=%d/%d blockers=%d dependencies=%d cautions=%d holds=%d/%d inspection_ms=%.3f%n",
                        ++completed, cases.size() * METHODS.size(), position.rank().substring(0, 6), selected.power(), method,
                        counts.get("candidates"), options.candidates(), counts.get("blockerActions"), counts.get("dependencyActions"),
                        counts.get("cautionActions"), counts.get("holdOrders"), counts.get("orders"), millis);
            }
            out.append("]}");
        }
        progress.println("All methods attempted; exhausted=" + exhausted
                + ". Counts are unweighted retained-pool diagnostics, not win rates. Timings include scoring and diagnostics.");
        return out.append("],\"attempted\":").append(completed).append(",\"exhausted\":").append(exhausted).append('}').toString();
    }

    public static Map<String, Long> summarize(Inspection inspection) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("candidates", (long) inspection.proposals().size());
        for (String name : List.of("blockerActions", "dependencyActions", "cautionActions", "ownMismatchActions",
                "convoyBlockerActions", "allHoldActions", "emptyActions", "orders", "holdOrders", "supportOrders",
                "convoyOrders", "mismatchedSupports", "mismatchedConvoys"))
            counts.put(name, 0L);
        for (Proposal proposal : inspection.proposals()) {
            var findings = proposal.findings();
            for (var kind : MovementFeasibility.Kind.values()) {
                String name = switch (kind) {
                    case BLOCKER -> "blockerActions";
                    case DEPENDENCY -> "dependencyActions";
                    case CAUTION -> "cautionActions";
                };
                if (findings.stream().anyMatch(f -> f.kind() == kind)) counts.merge(name, 1L, Long::sum);
            }
            if (proposal.coordination().mismatched()) counts.merge("ownMismatchActions", 1L, Long::sum);
            if (findings.stream().anyMatch(f -> f.code() == MovementFeasibility.Code.NO_CONVOY_ROUTE
                    || f.code() == MovementFeasibility.Code.CONVOY_OFF_ROUTE))
                counts.merge("convoyBlockerActions", 1L, Long::sum);
            var orders = proposal.action().orders();
            if (orders.isEmpty()) counts.merge("emptyActions", 1L, Long::sum);
            else if (orders.stream().allMatch(order -> order.orderType() == OrderType.HOLD))
                counts.merge("allHoldActions", 1L, Long::sum);
            counts.merge("orders", (long) orders.size(), Long::sum);
            counts.merge("holdOrders", orders.stream().filter(order -> order.orderType() == OrderType.HOLD).count(), Long::sum);
            counts.merge("supportOrders", orders.stream().filter(order -> order.orderType() == OrderType.SUPPORT).count(), Long::sum);
            counts.merge("convoyOrders", orders.stream().filter(order -> order.orderType() == OrderType.CONVOY).count(), Long::sum);
            counts.merge("mismatchedSupports", (long) proposal.coordination().mismatchedSupports(), Long::sum);
            counts.merge("mismatchedConvoys", (long) proposal.coordination().mismatchedConvoys(), Long::sum);
        }
        return Collections.unmodifiableMap(counts);
    }
}