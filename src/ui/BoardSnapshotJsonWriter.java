package ui;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

public final class BoardSnapshotJsonWriter {

    private BoardSnapshotJsonWriter() {
    }

    public static byte[] toJsonBytes(BoardSnapshot snapshot) {
        return toJson(snapshot).getBytes(StandardCharsets.UTF_8);
    }

    public static String toJson(BoardSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        StringBuilder out = new StringBuilder(256);

        out.append('{');
        out.append("\"year\":").append(snapshot.year()).append(',');
        out.append("\"phase\":");
        appendString(out, snapshot.phase());
        out.append(',');

        out.append("\"units\":[");
        for (int i = 0; i < snapshot.units().size(); i++) {
            BoardSnapshot.Unit unit = snapshot.units().get(i);

            if (i > 0)
                out.append(',');

            out.append('{');
            out.append("\"nation\":");
            appendString(out, unit.nation());
            out.append(',');
            out.append("\"type\":");
            appendString(out, unit.type());
            out.append(',');
            out.append("\"province\":");
            appendString(out, unit.province());
            out.append('}');
        }
        out.append("],");

        out.append("\"supplyCenterOwners\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : snapshot.supplyCenterOwners().entrySet()) {
            if (!first)
                out.append(',');

            appendString(out, entry.getKey());
            out.append(':');
            appendString(out, entry.getValue());
            first = false;
        }
        out.append('}');
        out.append('}');

        return out.toString();
    }

    private static void appendString(StringBuilder out, String value) {
        Objects.requireNonNull(value, "value");

        out.append('"');

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++)
                            out.append('0');
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
                }
            }
        }

        out.append('"');
    }

}
