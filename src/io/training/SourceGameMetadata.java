package io.training;

import domain.Nation;

import java.util.*;

import static io.json.JsonEscaper.appendString;

/**
 * Selected source metadata for dataset inspection, outside model inputs. JSON-heavy.
 * Source scores and result flags are preserved without assigning value targets.
 */
public final class SourceGameMetadata {

    private final Map<String, Object> fields = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> results = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final boolean hasResults;

    public SourceGameMetadata(Map<String, Object> source) {
        Objects.requireNonNull(source, "source");
        for (String key : List.of("CommunicationType", "ScoringSystem", "LimitType", "DeadlineType", "Modality")) {
            Object value = source.get(key);
            if (value != null && (!(value instanceof String text) || text.isBlank())) {
                warnings.add("$." + key + ": expected a nonblank string");
                value = null;
            }
            fields.put(key, value);
        }
        for (String key : List.of("DrawSize", "GameYearsCompleted")) {
            Object value = source.get(key);
            if (value != null && (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))) {
                warnings.add("$." + key + ": expected a finite number");
                value = null;
            }
            fields.put(key, value);
        }
        Object summary = source.get("ResultSummary");
        hasResults = summary instanceof Map<?, ?>;
        if (summary != null && !hasResults)
            warnings.add("$.ResultSummary: expected an object");
        if (summary instanceof Map<?, ?> nations) {
            Set<String> names = new HashSet<>();
            for (Nation nation : Nation.values()) {
                String name = nation.name().charAt(0) + nation.name().substring(1).toLowerCase(Locale.ROOT);
                names.add(name);
                Object result = nations.get(name);
                if (result == null) continue;
                if (!(result instanceof Map<?, ?> values)) {
                    warnings.add("$.ResultSummary." + name + ": expected an object");
                    continue;
                }
                Map<String, Object> selected = new LinkedHashMap<>();
                for (String key : List.of("CenterCount", "InGameAtEnd", "YearOfElimination",
                        "Score", "Rank", "UnexcusedResignation", "RankScore", "TopShare")) {
                    Object value = values.get(key);
                    if (value != null && !(value instanceof String) && !(value instanceof Boolean)
                            && !(value instanceof Number number && Double.isFinite(number.doubleValue()))) {
                        warnings.add("$.ResultSummary." + name + "." + key + ": expected a scalar");
                        value = null;
                    }
                    selected.put(key, value);
                }
                results.put(name, selected);
            }
            if (!names.containsAll(nations.keySet()))  // possible problem line:: "'Set<String>' may not contain objects of type 'capture of ?'"
                                                            // has to do with this instanceof ugliness - this may be a good candidate for redesign, later (use specific types if/where possible)
                warnings.add("$.ResultSummary: unrecognized nation keys");
        }
    }

    public String communicationType() {
        return (String) fields.get("CommunicationType");
    }

    public int warningCount() {
        return warnings.size();
    }

    /**
     * Includes only the selected source fields and metadata-shape warnings.
     * Player identities, free-text notes, and messages are omitted.
     */
    public String toJson() {
        // better to make an explicit method (since we are dealing with json), rather than overriding `toString()`
        StringBuilder out = new StringBuilder("{");
        appendFields(out, fields);
        out.append(",\"ResultSummary\":");
        if (!hasResults) out.append("null");
        else {
            out.append('{');
            boolean first = true;
            for (var entry : results.entrySet()) {
                if (!first) out.append(',');
                first = false;
                appendString(out, entry.getKey());
                out.append(":{");
                appendFields(out, entry.getValue());
                out.append('}');
            }
            out.append('}');
        }
        out.append(",\"warnings\":[");
        for (int index = 0; index < warnings.size(); index++) {
            if (index > 0) out.append(',');
            appendString(out, warnings.get(index));
        }
        return out.append("]}").toString();
    }

    private static void appendFields(StringBuilder out, Map<String, Object> values) {
        boolean first = true;
        for (var entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            appendString(out, entry.getKey());
            out.append(':');
            if (entry.getValue() instanceof String text) appendString(out, text);
            else out.append(entry.getValue());
        }
    }
}