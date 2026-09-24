package ui;

import domain.Nation;
import domain.Province;
import domain.UnitType;
import game.BoardSnapshot;
import game.BoardState;
import game.Game;
import game.GamePhase;
import phase.UnitId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BoardSnapshotSelfCheck {

    private BoardSnapshotSelfCheck() {
    }

    public static void main(String[] args) {
        BoardSnapshot snapshot = BoardSnapshotMapper.from(sampleGame());

        require(snapshot.year() == 1901, "year should be 1901");
        require(GamePhase.SPRING_MOVEMENT.name().equals(snapshot.phase()), "phase should be SPRING_MOVEMENT");

        List<BoardSnapshot.Unit> expectedUnits = List.of(
                new BoardSnapshot.Unit("ENGLAND", "FLEET", "Edi"),
                new BoardSnapshot.Unit("RUSSIA", "FLEET", "StpNC")
        );
        require(expectedUnits.equals(snapshot.units()), "units should be sorted and preserve coast names");

        Map<String, String> expectedOwners = new LinkedHashMap<>();
        expectedOwners.put("Edi", "ENGLAND");
        expectedOwners.put("Stp", "RUSSIA");
        require(expectedOwners.equals(snapshot.supplyCenterOwners()), "SC owners should be canonicalized and sorted");

        String json = BoardSnapshotJsonWriter.toJson(snapshot);
        String expectedJson = "{\"year\":1901,\"phase\":\"SPRING_MOVEMENT\",\"units\":[{\"nation\":\"ENGLAND\",\"type\":\"FLEET\",\"province\":\"Edi\"},{\"nation\":\"RUSSIA\",\"type\":\"FLEET\",\"province\":\"StpNC\"}],\"supplyCenterOwners\":{\"Edi\":\"ENGLAND\",\"Stp\":\"RUSSIA\"}}";
        require(expectedJson.equals(json), "JSON should preserve deterministic ordering and canonical ownership keys");

        String escaped = BoardSnapshotJsonWriter.toJson(new BoardSnapshot(
                1901,
                "SPRING\nMOVEMENT",
                List.of(new BoardSnapshot.Unit("EN\"GLAND", "FLEET", "Edi")),
                Map.of("Edi", "ENGLAND")));
        require(escaped.contains("SPRING\\nMOVEMENT"), "JSON writer should escape newlines");
        require(escaped.contains("EN\\\"GLAND"), "JSON writer should escape quotes");
    }

    private static Game sampleGame() {
        Map<UnitId, Province> locations = new LinkedHashMap<>();
        locations.put(
                new UnitId(UUID.fromString("00000000-0000-0000-0000-000000000001"), Nation.RUSSIA, UnitType.FLEET, Province.Stp),
                Province.StpNC);
        locations.put(
                new UnitId(UUID.fromString("00000000-0000-0000-0000-000000000002"), Nation.ENGLAND, UnitType.FLEET, Province.Edi),
                Province.Edi);

        Map<Province, Nation> owners = new LinkedHashMap<>();
        owners.put(Province.StpSC, Nation.RUSSIA);
        owners.put(Province.Edi, Nation.ENGLAND);

        BoardState board = new BoardState(locations, owners);
        return new Game(1901, board);
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}
