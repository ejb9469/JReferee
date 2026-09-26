package ui;

import domain.Nation;
import domain.Province;
import game.BoardState;
import game.Game;
import game.GameMoment;
import phase.UnitId;

import java.util.*;


public final class BoardSnapshotMapper {


    // the funny contract
    private static final Comparator<Map.Entry<UnitId, Province>> UNIT_ORDER =
            Comparator.<Map.Entry<UnitId, Province>, String>comparing(
                            entry -> entry.getValue().name())
                    .thenComparing(entry -> entry.getKey().owner().name())
                    .thenComparing(entry -> entry.getKey().unitType().name());


    private BoardSnapshotMapper() {  }


    public static BoardSnapshot from(Game game) {

        Objects.requireNonNull(game, "game");

        return from(game.year(), game.phase().name(), game.board());

    }

    public static BoardSnapshot from(GameMoment moment, BoardState board) {

        Objects.requireNonNull(moment, "moment");

        return from(moment.year(), moment.gamePhase().name(), board);

    }

    public static BoardSnapshot from(int year, String phase, BoardState board) {

        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(board, "board");

        List<BoardSnapshot.Unit> units = board.locations().entrySet().stream()
                .sorted(UNIT_ORDER)
                .map(entry -> new BoardSnapshot.Unit(
                        entry.getKey().owner().name(),
                        entry.getKey().unitType().name(),
                        entry.getValue().name()))
                .toList();

        TreeMap<String, String> sortedOwners = new TreeMap<>();

        for (Map.Entry<Province, Nation> entry : board.owners().entrySet())
            sortedOwners.put(
                    Province.canonical(entry.getKey()).name(),
                    entry.getValue().name());

        return new BoardSnapshot(
                year,
                phase,
                units,
                new LinkedHashMap<>(sortedOwners));

    }


}