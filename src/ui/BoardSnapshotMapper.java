package ui;

import domain.Nation;
import domain.Province;
import game.Game;
import phase.UnitId;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class BoardSnapshotMapper {

    private static final Comparator<Map.Entry<UnitId, Province>> UNIT_ORDER =
            Comparator.<Map.Entry<UnitId, Province>, String>comparing(entry -> entry.getValue().name())
                    .thenComparing(entry -> entry.getKey().owner().name())
                    .thenComparing(entry -> entry.getKey().unitType().name());

    private BoardSnapshotMapper() {
    }

    public static BoardSnapshot from(Game game) {
        Objects.requireNonNull(game, "game");

        List<BoardSnapshot.Unit> units = game.board().locations().entrySet().stream()
                .sorted(UNIT_ORDER)
                .map(entry -> new BoardSnapshot.Unit(
                        entry.getKey().owner().name(),
                        entry.getKey().unitType().name(),
                        entry.getValue().name()))
                .toList();

        TreeMap<String, String> ownersByCanonicalName = new TreeMap<>();
        for (Map.Entry<Province, Nation> entry : game.board().owners().entrySet()) {
            ownersByCanonicalName.put(
                    Province.canonical(entry.getKey()).name(),
                    entry.getValue().name());
        }

        Map<String, String> owners = new LinkedHashMap<>();
        ownersByCanonicalName.forEach(owners::put);

        return new BoardSnapshot(
                game.year(),
                game.phase().name(),
                units,
                owners);
    }

}
