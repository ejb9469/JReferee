package testing;

import analysis.RoutePreferences;
import domain.Province;
import game.BoardState;
import game.Game;
import game.GameMoment;
import game.GamePhase;
import game.Moment;
import game.StandardGameFactory;
import game.record.GameRecord;
import game.record.GameRecordBuilder;
import phase.Order;
import phase.UnitId;

import java.time.Instant;
import java.util.*;


public final class OccupancyRelaxationSelfCheck {

    private static final String RULESET = "occupancy-relaxation-self-check";


    private OccupancyRelaxationSelfCheck() {  }


    public static void main(String[] args) {

        RoutePreferences reference =
                new RoutePreferences(null, 4, true, false);

        RoutePreferences relaxed =
                new RoutePreferences(null, 4, true, true);

        GameRecord first = fixture();

        for (GameRecord game : List.of(first, fixture(), fixture())) {
            require(reference.add(game), "Reference training failed");
            require(relaxed.add(game), "Relaxed training failed");
        }

        boolean rejected = false;

        try {
            relaxed.add(first);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }

        require(rejected, "Duplicate training game was accepted");
        require(relaxed.gameCount() == 3, "Duplicate changed game count");

        BoardState original = StandardGameFactory.create1901().board();
        UnitId london = unitAt(original, Province.Lon);

        GameMoment spring1902 =
                new GameMoment(1902, GamePhase.SPRING_MOVEMENT);

        var before = reference.predict(
                RULESET, spring1902, original, london, List.of(), 3);

        var after = relaxed.predict(
                RULESET, spring1902, original, london, List.of(), 3);

        require(before.equals(after),
                "Relaxation replaced a qualifying year-pooled prediction");

        Map<UnitId, Province> locations =
                new LinkedHashMap<>(original.locations());

        locations.put(unitAt(original, Province.Lvp), Province.Yor);

        BoardState changedOccupancy =
                new BoardState(locations, original.owners());

        require(reference.predict(
                                RULESET, spring1902, changedOccupancy,
                                london, List.of(), 3)
                        .counts().isEmpty(),
                "Reference unexpectedly ignored occupancy");

        var recovered = relaxed.predict(
                RULESET, spring1902, changedOccupancy,
                london, List.of(), 3);

        require(recovered.basis().equals("POSITION_OCCUPANCY_RELAXED"),
                "Expected relaxed fallback");

        require(recovered.counts().equals(Map.of(Order.hold(london), 3L)),
                "Wrong rebound instructions or counts");

        var evidence = relaxed.positionEvidence(
                RULESET, spring1902, changedOccupancy, london);

        require(evidence.yearSpecific().counts().isEmpty(),
                "Unexpected year-specific evidence");

        require(evidence.yearPooled().counts().isEmpty(),
                "Unexpected occupancy-specific pooled evidence");

        require(evidence.occupancyRelaxedGames() == 3,
                "Wrong distinct supporting-game count");

        require(relaxed.predict(
                                RULESET, spring1902, changedOccupancy,
                                london, List.of(), 4)
                        .counts().isEmpty(),
                "Relaxation bypassed the observation threshold");

        Map<Province, domain.Nation> owners =
                new LinkedHashMap<>(original.owners());

        owners.remove(Province.Lon);

        BoardState changedOwnership =
                new BoardState(locations, owners);

        require(relaxed.predict(
                                RULESET, spring1902, changedOwnership,
                                london, List.of(), 3)
                        .counts().isEmpty(),
                "Relaxation incorrectly discarded center ownership");

        require(relaxed.predict(
                                RULESET,
                                new GameMoment(1902, GamePhase.FALL_MOVEMENT),
                                changedOccupancy, london, List.of(), 3)
                        .counts().isEmpty(),
                "Relaxation combined Spring and Fall");

        require(relaxed.predict(
                                "different-ruleset", spring1902,
                                changedOccupancy, london, List.of(), 3)
                        .counts().isEmpty(),
                "Relaxation combined rulesets");

        Map<UnitId, Province> relocated =
                new LinkedHashMap<>(locations);

        relocated.put(london, Province.Wal);

        require(relaxed.predict(
                                RULESET, spring1902,
                                new BoardState(relocated, original.owners()),
                                london, List.of(), 3)
                        .counts().isEmpty(),
                "Relaxation discarded the query location");

        System.out.println("OccupancyRelaxation checks passed.");

    }

    private static GameRecord fixture() {

        Game game = StandardGameFactory.create1901();

        GameRecordBuilder archive =
                new GameRecordBuilder(UUID.randomUUID(), RULESET, game);

        archive.resolveMovement(
                game.board().locations().keySet().stream()
                        .map(Order::hold)
                        .toList(),
                new Moment(
                        1901, GamePhase.SPRING_MOVEMENT, Instant.EPOCH));

        return archive.build();

    }

    private static UnitId unitAt(BoardState board, Province province) {

        return board.locations().entrySet().stream()
                .filter(entry -> entry.getValue() == province)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

    }

    private static void require(boolean condition, String message) {

        if (!condition)
            throw new AssertionError(message);

    }

}