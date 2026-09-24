package ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

public record BoardSnapshot(
        int year,
        String phase,
        List<Unit> units,
        Map<String, String> supplyCenterOwners
) {

    public BoardSnapshot {
        Objects.requireNonNull(phase, "phase");
        units = List.copyOf(Objects.requireNonNull(units, "units"));
        supplyCenterOwners = copyOwners(Objects.requireNonNull(supplyCenterOwners, "supplyCenterOwners"));
    }

    private static Map<String, String> copyOwners(Map<String, String> owners) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : owners.entrySet()) {
            String province = Objects.requireNonNull(entry.getKey(), "supplyCenterOwners key");
            String nation = Objects.requireNonNull(entry.getValue(), "supplyCenterOwners value");
            copy.put(province, nation);
        }

        return Collections.unmodifiableMap(copy);
    }

    public record Unit(
            String nation,
            String type,
            String province
    ) {
        public Unit {
            Objects.requireNonNull(nation, "nation");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(province, "province");
        }
    }

}
