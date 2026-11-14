package testing.selfcheck;

import contracts.OrderForms;
import domain.*;
import game.*;
import io.catalog.SourceFingerprints;
import io.training.*;
import misc.scoring.ScoringSystem;
import phase.UnitId;

import java.util.*;

/**
 * Whole-game admission boundaries, independent of source provider.
 */
public final class ValueAdmissionSelfCheck {

    private ValueAdmissionSelfCheck() { }

    public static void main(String[] args) {

        for (String source : List.of("DIPLOBN", "FUTURE_PROVIDER")) {

            ValueSourceAdapter.Result valid = result(source, 34);
            require(valid.validatePositions() == valid,
                    "A valid boundary position was changed");

            for (int count : List.of(35, 36)) {

                ValueSourceAdapter.Result original = result(source, count);
                ValueSourceAdapter.Result rejected = original.validatePositions();

                require(rejected.game().isEmpty(), "Invalid game was retained");
                require(rejected.rejection().orElseThrow().equals("TOO_MANY_STANDING_UNITS"),
                        "Wrong rejection reason");
                require(original.game().orElseThrow().positions().size() == 2,
                        "Input positions were modified");
                require(rejected.diagnostics().getFirst().equals("Existing source diagnostic"),
                        "Existing diagnostic was lost");
                require(rejected.diagnostics().stream().anyMatch(note ->
                                note.contains("Position 1:") && note.contains("has " + count)),
                        "Offending position was not identified");

            }

        }

        ValueSourceAdapter.Result unsupported = ValueSourceAdapter.Result.rejected("UNSUPPORTED_SOURCE");
        require(unsupported.validatePositions() == unsupported,
                "An existing rejection was changed");
        System.out.println("ValueAdmissionSelfCheck passed");

    }

    private static ValueSourceAdapter.Result result(String source, int units) {

        BoardState board = board(units);
        Map<Nation, Integer> counts = new EnumMap<>(Nation.class);
        for (Nation nation : Nation.values())
            counts.put(nation, 0);
        board.owners().values().forEach(nation -> counts.merge(nation, 1, Integer::sum));

        ValueOutcome outcome = new ValueOutcome(
                ValueOutcome.Termination.SOURCE_COMPLETED,
                ValueOutcome.Evidence.SOURCE_RECORDED,
                new GameMoment(1904, GamePhase.SPRING_MOVEMENT),
                counts, Optional.empty(), Optional.empty(), "Synthetic admission fixture");

        List<ValueDataset.Position> positions = List.of(
                new ValueDataset.Position(new GameMoment(1903, GamePhase.SPRING_MOVEMENT),
                        board(34), ScoringSystem.SUM_OF_SQUARES),
                new ValueDataset.Position(new GameMoment(1903, GamePhase.WINTER_ADJUSTMENT),
                        board, ScoringSystem.SUM_OF_SQUARES)
        );

        ValueDataset.GameExample game = new ValueDataset.GameExample(
                source, "synthetic", Optional.empty(), SourceFingerprints.sha256Hex(source),
                positions, outcome);

        return new ValueSourceAdapter.Result(Optional.of(game), Optional.empty(),
                List.of("Existing source diagnostic"));

    }

    private static BoardState board(int count) {

        Map<UnitId, Province> units = new LinkedHashMap<>();
        Map<Province, Nation> centers = new EnumMap<>(Province.class);
        Nation[] nations = Nation.values();

        for (Province location : Province.values()) {

            if (location.parent != null || location == Province.Swi)
                continue;

            if (location.supplyCenter)
                centers.put(location, nations[centers.size() % nations.length]);

            if (units.size() < count) {
                UnitType type = OrderForms.isValidUnitLocation(UnitType.ARMY, location)
                        ? UnitType.ARMY : UnitType.FLEET;
                UnitId unit = new UnitId(nations[units.size() % nations.length], type, location);
                units.put(unit, location);
            }

        }

        require(units.size() == count, "Fixture did not create the requested number of units");
        return new BoardState(units, centers);

    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}