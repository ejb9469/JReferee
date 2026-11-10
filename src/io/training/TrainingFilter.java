package io.training;

import org.jetbrains.annotations.Nullable;

import java.util.*;

import static io.json.JsonEscaper.appendString;

/**
 * Game selection and split seed for a movement training export.
 * A null communicationTypes set allows every reported label; an empty set
 * allows none. Missing or malformed labels follow includeUnknownCommunication.
 */
public record TrainingFilter(
                    long splitSeed,
        @Nullable   Set<String> communicationTypes,
                    boolean includeUnknownCommunication )
{

    public TrainingFilter {
        if (communicationTypes != null) {
            for (String type : communicationTypes)
                if (Objects.requireNonNull(type, "communicationType").isBlank())
                    throw new IllegalArgumentException("Communication types must be nonblank");
            communicationTypes = Collections.unmodifiableSet(new TreeSet<>(communicationTypes));
        }
    }

    public TrainingFilter() {
        this(7);
    }

    public TrainingFilter(long splitSeed) {
        this(splitSeed, null, true);
    }

    /**
     * Matches the literal source label; null denotes missing or malformed metadata.
     */
    public boolean includes(@Nullable String communicationType) {
        return communicationType == null
                ? includeUnknownCommunication
                : communicationTypes == null || communicationTypes.contains(communicationType);
    }

    public String toJson() {
        StringBuilder out = new StringBuilder("{\"split_seed\":").append(splitSeed)
                .append(",\"communication_types\":");
        if (communicationTypes == null) out.append("null");
        else {
            out.append('[');
            boolean first = true;
            for (String type : communicationTypes) {
                if (!first) out.append(',');
                first = false;
                appendString(out, type);
            }
            out.append(']');
        }
        return out.append(",\"include_unknown_communication\":").append(includeUnknownCommunication)
                .append('}').toString();
    }
}