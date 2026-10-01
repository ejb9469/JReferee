package ui;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import static io.json.JsonEscaper.appendString;


public abstract class BoardSnapshotJsonWriter {


    private BoardSnapshotJsonWriter() {  }


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

        for (int index = 0; index < snapshot.units().size(); index++) {

            BoardSnapshot.Unit unit = snapshot.units().get(index);

            if (index > 0)
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


}