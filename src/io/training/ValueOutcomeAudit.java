package io.training;

import agents.encoding.EncodingPurpose;
import domain.Nation;
import domain.Province;
import io.catalog.SourceFingerprints;
import io.json.JsonAccess;
import io.json.JsonReader;

import java.util.*;

import static io.json.JsonEscaper.appendString;

/**
 * Inventories source evidence without assigning terminal labels.
 * A matching final count is evidence for review, not proof of termination.
 * Center names are checked, but units, orders and adjudication are not replayed.
 */
public final class ValueOutcomeAudit {


    // Construction and split identity \\

    private static final JsonAccess JSON = new JsonAccess(
            (path, message) -> new IllegalArgumentException(path + ": " + message));

    private ValueOutcomeAudit() { }

    public static EncodingPurpose split(long seed, String source, String key) {

        String hash = SourceFingerprints.sha256Hex(seed + "\n" + source + "\n" + key);
        int bucket = (int) Long.remainderUnsigned(
                Long.parseUnsignedLong(hash.substring(0, 16), 16), 100);

        return bucket < 80 ? EncodingPurpose.TRAIN
                : bucket < 90 ? EncodingPurpose.VALIDATION : EncodingPurpose.TEST;

    }


    // Evidence inventory \\

    public static Map<String, Object> inspect(
            String source, String key, String payload, String expectedHash
    ) {

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", source);
        report.put("game_key", key);
        report.put("payload_sha256", expectedHash);
        report.put("source_metadata", null);
        report.put("scoring_label", null);
        report.put("limit_label", null);
        report.put("last_status", null);
        report.put("finding", "NOT_INSPECTED");
        report.put("value_target", null);

        if (!"DIPLOBN".equals(source)) {
            report.put("finding", "UNSUPPORTED_SOURCE");
            return report;
        }

        if (payload == null || !SourceFingerprints.sha256Hex(payload).equals(expectedHash)) {
            report.put("finding", "PAYLOAD_HASH_MISMATCH");
            return report;
        }

        Map<String, Object> raw;

        try {
            raw = JSON.object(JsonReader.read(payload), "$");
        } catch (IllegalArgumentException exception) {
            report.put("finding", "INVALID_JSON");
            return report;
        }

        SourceGameMetadata metadata = new SourceGameMetadata(raw);
        Map<String, Object> selected = JSON.object(
                JsonReader.read(metadata.toJson()), "$.metadata");

        report.put("source_metadata", selected);
        report.put("scoring_label", selected.get("ScoringSystem"));
        report.put("limit_label", selected.get("LimitType"));

        try {

            List<Object> phases = JSON.array(
                    JSON.required(raw, "GamePhases", "$"), "$.GamePhases");

            report.put("phase_count", phases.size());

            if (phases.isEmpty()) {
                report.put("finding", "NO_PHASES");
                return report;
            }

            Map<String, Integer> statuses = new TreeMap<>();
            int previous = -1;
            boolean nondecreasing = true;

            for (Object value : phases) {
                Map<String, Object> phase = JSON.object(value, "$.phase");
                int code = JSON.integer(phase.get("Phase"), "$.phase.Phase");
                String status = JSON.string(phase.get("Status"), "$.phase.Status");
                statuses.merge(status, 1, Integer::sum);
                nondecreasing &= code >= previous;
                previous = code;
            }

            report.put("status_counts", statuses);
            report.put("phase_codes_nondecreasing", nondecreasing);

            Map<String, Object> last = JSON.object(phases.getLast(), "$.last");
            report.put("last_phase", last.get("Phase"));
            report.put("last_status", last.get("Status"));
            report.put("last_has_units", last.get("Units") instanceof Map<?, ?>);
            report.put("last_has_orders", last.get("Orders") instanceof Map<?, ?>);

            Map<Nation, Integer> centers = centers(last.get("SupplyCenters"));
            Map<Nation, Integer> results = results(raw.get("ResultSummary"));

            report.put("last_center_counts", named(centers));
            report.put("result_center_counts", named(results));
            report.put("counts_match", centers.equals(results));

            int total = results.values().stream().mapToInt(Integer::intValue).sum();

            if (!nondecreasing)
                report.put("finding", "NONCHRONOLOGICAL_PHASES");
            else if (total == 0)
                report.put("finding", "NO_OWNED_CENTERS");
            else if (!centers.equals(results))
                report.put("finding", "FINAL_COUNTS_DISAGREE");
            else if (results.values().stream().anyMatch(count -> count >= 18))
                report.put("finding", "SOLO_COUNTS_REQUIRE_ENDING_REVIEW");
            else
                report.put("finding", "MATCHING_COUNTS_REQUIRE_ENDING_REVIEW");

        } catch (IllegalArgumentException exception) {

            report.put("finding", "INVALID_OR_MISSING_EVIDENCE");
            // The path is useful; source values and parser excerpts are not exported.
            String message = exception.getMessage();
            int colon = message == null ? -1 : message.indexOf(':');
            report.put("invalid_path", colon > 0 && message.startsWith("$.")
                    ? message.substring(0, colon) : "$");

        }

        return report;

    }


    // Center evidence \\

    private static Map<Nation, Integer> centers(Object value) {

        Map<String, Object> supplied = JSON.object(value, "$.last.SupplyCenters");
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);
        Set<Province> seen = new HashSet<>();

        for (Nation nation : Nation.values())
            counts.put(nation, 0);

        for (Map.Entry<String, Object> entry : supplied.entrySet()) {

            Nation nation = nation(entry.getKey());
            List<Object> locations = JSON.array(entry.getValue(), "$.last.SupplyCenters");

            for (Object location : locations) {
                Province province = Province.valueOf(
                        JSON.string(location, "$.last.SupplyCenters"));
                if (!province.supplyCenter || Province.canonical(province) != province
                        || !seen.add(province))
                    throw new IllegalArgumentException("$.last.SupplyCenters: invalid center");
                counts.merge(nation, 1, Integer::sum);
            }

        }

        return counts;

    }

    private static Map<Nation, Integer> results(Object value) {

        Map<String, Object> supplied = JSON.object(value, "$.ResultSummary");
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);

        for (Map.Entry<String, Object> entry : supplied.entrySet()) {
            Nation nation = nation(entry.getKey());
            Map<String, Object> result = JSON.object(entry.getValue(), "$.ResultSummary");
            int count = JSON.integer(result.get("CenterCount"), "$.ResultSummary.CenterCount");
            if (count < 0 || count > 34 || counts.putIfAbsent(nation, count) != null)
                throw new IllegalArgumentException("$.ResultSummary.CenterCount: invalid count");
        }

        if (counts.size() != Nation.values().length
                || counts.values().stream().mapToInt(Integer::intValue).sum() > 34)
            throw new IllegalArgumentException("$.ResultSummary: incomplete or excessive counts");

        return counts;

    }

    private static Nation nation(String name) {
        for (Nation nation : Nation.values())
            if (name.equals(nation.name().charAt(0)
                    + nation.name().substring(1).toLowerCase(Locale.ROOT)))
                return nation;
        throw new IllegalArgumentException("$.nation: unknown source nation");
    }

    private static Map<String, Integer> named(Map<Nation, Integer> counts) {
        Map<String, Integer> result = new TreeMap<>();
        counts.forEach((nation, count) -> result.put(nation.name(), count));
        return result;
    }


    // Report serialization \\

    public static String json(Object value) {

        if (value == null)
            return "null";
        if (value instanceof String text) {
            StringBuilder out = new StringBuilder();
            appendString(out, text);
            return out.toString();
        }
        if (value instanceof Boolean)
            return value.toString();
        if (value instanceof Number number && Double.isFinite(number.doubleValue()))
            return number.toString();
        if (value instanceof Map<?, ?> map) {
            StringJoiner out = new StringJoiner(",", "{", "}");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key))
                    throw new IllegalArgumentException("JSON object keys must be strings");
                out.add(json(key) + ":" + json(entry.getValue()));
            }
            return out.toString();
        }
        if (value instanceof Collection<?> values) {
            StringJoiner out = new StringJoiner(",", "[", "]");
            for (Object element : values)
                out.add(json(element));
            return out.toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value");

    }

}