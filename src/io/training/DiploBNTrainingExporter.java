package io.training;

import agents.encoding.MovementEncoding;
import agents.generation.*;
import domain.Nation;
import game.GameMoment;
import game.GameState_Movement;
import io.catalog.SourceFingerprints;
import io.json.JsonAccess;
import parsing.diplobn.*;
import phase.Order;

import java.io.IOException;
import java.util.*;

import static io.json.JsonEscaper.appendString;

/**
 * Exports complete, catalog-legal movement submissions from source snapshots.
 * Source results are neither replay verification nor terminal value labels.
 */
public final class DiploBNTrainingExporter {

    public static final String SCHEMA = "jreferee-diplobn-imitation-v4";
    private static final JsonAccess JSON = new JsonAccess(ParseException::new);

    public enum Split { TRAIN, VALIDATION, TEST }

    public enum Selection { INCLUDED, FILTERED_OUT, NOT_EVALUATED }

    public record Counts(long examples, long rejectionRecords) { }

    public record Report(Counts counts, String communicationType, boolean metadataAvailable,
                         int metadataWarnings, Map<String, Long> rejectionReasons, String gameJson,
                         Selection selection) {
        public Report {
            rejectionReasons = Collections.unmodifiableMap(new TreeMap<>(rejectionReasons));
            Objects.requireNonNull(selection, "selection");
        }
    }

    private final TrainingFilter filter;

    public DiploBNTrainingExporter(long splitSeed) {
        this(new TrainingFilter(splitSeed));
    }

    public DiploBNTrainingExporter(TrainingFilter filter) {
        this.filter = Objects.requireNonNull(filter, "filter");
    }

    /**
     * Assigns all positions from a provider's game to the same 80/10/10 split.
     * Refreshing the payload or changing the catalog UUID does not move it.
     */
    public Split split(String source, String externalKey) {
        requireIdentity(source, externalKey);
        String hash = SourceFingerprints.sha256Hex(filter.splitSeed() + "\n" + source + "\n" + externalKey);
        int bucket = (int) Long.remainderUnsigned(Long.parseUnsignedLong(hash.substring(0, 16), 16), 100);
        return bucket < 80 ? Split.TRAIN : bucket < 90 ? Split.VALIDATION : Split.TEST;
    }

    /**
     * Appends JSON lines to caller-owned outputs. Invalid source data is
     * recorded with its game/phase/nation; output failures propagate.
     */
    public Counts write(String source, String externalKey, String payload, String expectedHash,
                        Appendable examples, Appendable rejected) throws IOException {
        return writeDetailed(source, externalKey, payload, expectedHash, examples, rejected).counts();
    }

    /**
     * Also returns one game-level metadata record, selection, and summary counts.
     * The filter selects verified game metadata before phases are parsed.
     * Historical resolution messages never determine selection or acceptance.
     */
    public Report writeDetailed(String source, String externalKey, String payload, String expectedHash,
                                Appendable examples, Appendable rejected) throws IOException {
        requireIdentity(source, externalKey);
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(expectedHash, "expectedHash");
        Objects.requireNonNull(examples, "examples");
        Objects.requireNonNull(rejected, "rejected");
        Map<String, Long> reasons = new TreeMap<>();
        if (!source.equals("DIPLOBN")) {
            reject(rejected, reasons, source, externalKey, null, null, "UNSUPPORTED_SOURCE", source, "[]");
            return report(source, externalKey, expectedHash, 0, reasons, null);
        }
        if (!SourceFingerprints.sha256Hex(payload).equals(expectedHash)) {
            reject(rejected, reasons, source, externalKey, null, null, "PAYLOAD_HASH_MISMATCH", expectedHash, "[]");
            return report(source, externalKey, expectedHash, 0, reasons, null);
        }

        DiploBNGame game;
        List<Object> rawPhases;
        SourceGameMetadata metadata = null;
        try {
            Map<String, Object> raw = JSON.object(JsonReader.read(payload), "$");
            metadata = new SourceGameMetadata(raw);
            if (!filter.includes(metadata.communicationType()))
                return report(source, externalKey, expectedHash, 0, reasons, metadata);
            game = new DiploBNParser().parse(payload);
            rawPhases = JSON.array(JSON.required(raw, "GamePhases", "$"), "$.GamePhases");
        } catch (IllegalArgumentException exception) {
            reject(rejected, reasons, source, externalKey, null, null, "INVALID_GAME", exception.getMessage(), "[]");
            return report(source, externalKey, expectedHash, 0, reasons, metadata);
        }

        Set<GameMoment> moments = new HashSet<>();
        for (DiploBNPhase phase : game.phases()) {
            GameMoment moment = new GameMoment(phase.sourcePhase() / 10, phase.gamePhase());
            if (!moments.add(moment)) {
                reject(rejected, reasons, source, externalKey, phase, null, "DUPLICATE_PHASE", moment.toString(), "[]");
                return report(source, externalKey, expectedHash, 0, reasons, metadata);
            }
        }

        long accepted = 0;
        for (int index = 0; index < game.phases().size(); index++) {
            DiploBNPhase phase = game.phases().get(index);
            if (!phase.gamePhase().isMovement()) {
                reject(rejected, reasons, source, externalKey, phase, null,
                        "UNSUPPORTED_PHASE", phase.gamePhase().name(), "[]");
                continue;
            }
            Map<String, Object> raw = JSON.object(rawPhases.get(index), "$.GamePhases[" + index + "]");
            if (!(raw.get("Units") instanceof Map<?, ?>)
                    || !(raw.get("SupplyCenters") instanceof Map<?, ?>)
                    || !(raw.get("Orders") instanceof Map<?, ?>)) {
                reject(rejected, reasons, source, externalKey, phase, null, "MISSING_FIELDS",
                        "Units, SupplyCenters, and Orders objects are required", "[]");
                continue;
            }
            GameState_Movement state = new GameState_Movement(
                    new GameMoment(phase.sourcePhase() / 10, phase.gamePhase()), phase.board());
            if (state.aliveNations().isEmpty()) {
                reject(rejected, reasons, source, externalKey, phase, null,
                        "EMPTY_POSITION", "No living nations", "[]");
                continue;
            }
            for (Nation nation : state.aliveNations()) {
                MovementEncoding encoding;
                try {
                    encoding = new MovementEncoding(state, nation);
                } catch (IllegalArgumentException exception) {
                    reject(rejected, reasons, source, externalKey, phase, nation,
                            "INVALID_POSITION", exception.getMessage(), "[]");
                    continue;
                }
                if (encoding.options().isEmpty()) {
                    reject(rejected, reasons, source, externalKey, phase, nation,
                            "NO_UNITS", "No movement choices", "[]");
                    continue;
                }
                var orders = phase.movementOrders().stream().filter(order -> order.owner() == nation).toList();
                ActionCandidate candidate = new ActionCandidate(nation, orders, 0, ActionCandidate.Source.POLICY);
                var errors = new CatalogActionValidator().validate(state, candidate, encoding.catalog());
                if (!errors.isEmpty()) {
                    reject(rejected, reasons, source, externalKey, phase, nation, "INVALID_ACTION", errors.toString(),
                            errorsJson(errors, orders, encoding, state, raw, nation));
                    continue;
                }
                StringBuilder line = identity(source, externalKey, phase, nation);
                line.append(",\"payload_sha256\":");
                appendString(line, expectedHash);
                line.append(",\"split\":");
                appendString(line, split(source, externalKey).name());
                line.append(",\"validation\":\"CATALOG_LEGALITY_ONLY\"")
                        .append(",\"communication_type\":");
                appendNullable(line, metadata.communicationType());
                line.append(",\"default_order_status\":\"UNKNOWN\"")
                        .append(",\"value_target\":null,\"input\":").append(encoding.toJson())
                        .append(",\"action\":").append(encoding.encode(orders)).append("}\n");
                examples.append(line);
                accepted++;
            }
        }
        return report(source, externalKey, expectedHash, accepted, reasons, metadata);
    }

    private Report report(String source, String key, String hash, long examples,
                          Map<String, Long> reasons, SourceGameMetadata metadata) {
        long rejected = reasons.values().stream().mapToLong(Long::longValue).sum();
        Selection selection = metadata == null ? Selection.NOT_EVALUATED
                : filter.includes(metadata.communicationType()) ? Selection.INCLUDED : Selection.FILTERED_OUT;
        StringBuilder out = identity(source, key, null, null);
        out.append(",\"payload_sha256\":");
        appendString(out, hash);
        out.append(",\"split\":");
        appendString(out, split(source, key).name());
        out.append(",\"selection\":");
        appendString(out, selection.name());
        out.append(",\"accepted_examples\":").append(examples)
                .append(",\"rejection_records\":").append(rejected)
                .append(",\"metadata_available\":").append(metadata != null)
                .append(",\"source_metadata\":").append(metadata == null ? "null" : metadata.toJson())
                .append('}');
        return new Report(new Counts(examples, rejected), metadata == null ? null : metadata.communicationType(),
                metadata != null, metadata == null ? 0 : metadata.warningCount(), reasons, out.toString(), selection);
    }

    private static String errorsJson(List<ActionValidationError> errors, List<Order> orders,
                                     MovementEncoding encoding, GameState_Movement state,
                                     Map<String, Object> raw, Nation nation) {
        Map<String, Object> rawOrders = JSON.object(raw.get("Orders"), "$.Orders");
        List<Object> sourceOrders = new ArrayList<>();
        rawOrders.forEach((name, value) -> {
            if (name.equalsIgnoreCase(nation.name()))
                sourceOrders.addAll(JSON.array(value, "$.Orders." + name));
        });
        if (sourceOrders.size() != orders.size())
            throw new IllegalStateException("Source and translated nation order counts differ");
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < errors.size(); index++) {
            if (index > 0) out.append(',');
            ActionValidationError error = errors.get(index);
            out.append("{\"code\":");
            appendString(out, error.code().name());
            out.append(",\"order_index\":").append(error.orderIndex()).append(",\"location\":");
            var location = error.unit() == null ? null : state.board().locationOf(error.unit());
            appendNullable(out, location == null ? null : location.name());
            out.append(",\"order\":");
            appendNullable(out, error.orderIndex() < 0 ? null : encoding.token(orders.get(error.orderIndex())));
            out.append(",\"source_result\":");
            List<Object> entry = error.orderIndex() < 0 ? List.of()
                    : JSON.array(sourceOrders.get(error.orderIndex()), "$.Orders.entry");
            if (entry.size() < 3) out.append("null");
            else {
                Object result = entry.get(2);
                List<Object> tuple = result instanceof String ? List.of(result) : JSON.array(result, "$.result");
                out.append("{\"successful\":").append(tuple.getFirst().equals("s")).append(",\"reason\":");
                appendNullable(out, tuple.size() < 2 ? null : JSON.string(tuple.get(1), "$.result.reason"));
                out.append('}');
            }
            out.append('}');
        }
        return out.append(']').toString();
    }

    private static void reject(Appendable out, Map<String, Long> reasons, String source, String key,
                               DiploBNPhase phase, Nation nation, String reason, String detail,
                               String errors) throws IOException {
        StringBuilder line = identity(source, key, phase, nation);
        line.append(",\"reason\":");
        appendString(line, reason);
        line.append(",\"detail\":");
        appendString(line, Objects.requireNonNullElse(detail, reason));
        out.append(line.append(",\"errors\":").append(errors).append("}\n"));
        reasons.merge(reason, 1L, Long::sum);
    }

    private static void appendNullable(StringBuilder out, String text) {
        if (text == null) out.append("null");
        else appendString(out, text);
    }

    private static StringBuilder identity(String source, String key, DiploBNPhase phase, Nation nation) {
        StringBuilder out = new StringBuilder("{\"schema\":");
        appendString(out, SCHEMA);
        out.append(",\"source\":");
        appendString(out, source);
        out.append(",\"game_key\":");
        appendString(out, key);
        out.append(",\"source_phase\":").append(phase == null ? "null" : phase.sourcePhase());
        out.append(",\"source_status\":");
        if (phase == null) out.append("null");
        else appendString(out, phase.sourceStatus());
        out.append(",\"power\":");
        if (nation == null) out.append("null");
        else appendString(out, nation.name());
        return out;
    }

    private static void requireIdentity(String source, String key) {
        if (Objects.requireNonNull(source, "source").isBlank()
                || Objects.requireNonNull(key, "externalKey").isBlank()
                || source.contains("\n") || key.contains("\n"))
            throw new IllegalArgumentException("Source and game key must be nonblank single-line identifiers");
    }
}