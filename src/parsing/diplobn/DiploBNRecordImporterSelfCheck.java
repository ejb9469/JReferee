package parsing.diplobn;

import analysis.UnitRoutes;
import domain.Nation;
import domain.Province;
import game.BoardState;
import game.Game;
import game.GamePhase;
import game.Moment;
import game.StandardGameFactory;
import game.record.*;
import phase.Order;
import phase.UnitId;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.BuildOrder;
import phase.adjustments.DisbandOrder;
import phase.retreats.RetreatOrder;

import java.time.Instant;
import java.util.*;


public class DiploBNRecordImporterSelfCheck {

    private DiploBNRecordImporterSelfCheck() {  }


    public static void main(String[] args) {

        DiploBNRecordImporter importer = new DiploBNRecordImporter();

        GameRecord original = fixture(true);
        DiploBNGame source = sourceGame(original);

        GameRecord imported = importer.importGame(1001, source);

        require(imported.id().equals(DiploBNRecordImporter.archiveId(1001)),
                "Archive ID is not tied to source identity");

        require(imported.resolvedPhases().size() == original.resolvedPhases().size(),
                "Wrong number of replayed phases");

        UnitId venice = unitAt(imported.initialBoard(), Province.Ven);
        UnitId trieste = unitAt(imported.initialBoard(), Province.Tri);

        require(imported.latestBoard().locationOf(venice) == Province.Tri,
                "Italian army identity did not survive movement");

        RetreatPhaseRecord retreat = (RetreatPhaseRecord)
                imported.resolvedPhases().stream()
                        .filter(phase -> phase instanceof RetreatPhaseRecord)
                        .findFirst().orElseThrow();

        require(retreat.result().outcomeOf(trieste) != null,
                "Retreat outcome lost the original Austrian fleet identity");

        require(retreat.result().outcomeOf(trieste).succeeded(),
                "Austrian fleet did not successfully retreat");

        require(retreat.boardAfter().locationOf(trieste) == Province.Alb,
                "Austrian fleet identity did not survive retreat");

        AdjustmentPhaseRecord winter = (AdjustmentPhaseRecord)
                imported.resolvedPhases().stream()
                        .filter(phase -> phase instanceof AdjustmentPhaseRecord)
                        .findFirst().orElseThrow();

        require(winter.boardBefore().locationOf(trieste) == Province.Alb,
                "Retreated fleet did not persist into Winter");

        require(winter.result().disbandedUnits().contains(trieste),
                "Expected the Austrian fleet to be automatically disbanded");

        require(winter.submittedOrders().stream().noneMatch(order ->
                        order instanceof DisbandOrder disband
                                && disband.unit().equals(trieste)),
                "Fixture unexpectedly submitted an explicit fleet disband");

        require(imported.latestBoard().locationOf(trieste) == null,
                "Winter-disbanded fleet unexpectedly remains on the final board");

        require(winter.result().builtUnits().size() == 1, "Expected one Italian build");

        UnitId built = winter.result().builtUnits().iterator().next();

        require(built.owner() == Nation.ITALY && built.origin() == Province.Rom,
                "Wrong built-unit identity");

        require(imported.latestBoard().locationOf(built) == Province.Rom,
                "Built unit did not persist into the following phase");

        UnitRoutes routes = new UnitRoutes();
        require(routes.add(imported), "Imported record failed lifetime extraction");

        require(routes.route(imported.id(), trieste).stream()
                        .anyMatch(event -> event.contains("|RETREATED")),
                "Retreat is absent from the unit route");

        require(routes.route(imported.id(), trieste).getLast()
                        .equals("1901|WINTER_ADJUSTMENT|DEATH|AUTO_DISBANDED"),
                "Fleet route should end with automatic Winter disbandment");

        require(routes.route(imported.id(), built).get(1).contains("|BIRTH|BUILD|"),
                "Built unit lacks a build-inception event");

        GameRecord destroyed = importer.importGame(1002, sourceGame(fixture(false)));
        UnitId destroyedFleet = unitAt(destroyed.initialBoard(), Province.Tri);

        UnitRoutes destroyedRoutes = new UnitRoutes();
        destroyedRoutes.add(destroyed);

        require(destroyedRoutes.route(destroyed.id(), destroyedFleet).getLast().contains("|DEATH|"),
                "Failed or omitted retreat did not end the lifetime");

        GameRecord refreshed = importer.importGame(1001, source);

        require(imported.id().equals(refreshed.id()),
                "Reimporting a source game changed its archive ID");

        require(rejects(() -> importer.importGame(1003,
                        new DiploBNGame(null, null, null,
                                List.of(source.phases().getFirst())))),
                "A lone unverified snapshot was accepted");

        List<DiploBNPhase> missingPhase = new ArrayList<>(source.phases());
        missingPhase.remove(1);

        require(rejects(() -> importer.importGame(1004,
                        new DiploBNGame(null, null, null, missingPhase))),
                "A missing movement phase was silently reconstructed");

        List<DiploBNPhase> wrongBoundary = new ArrayList<>(source.phases());
        DiploBNPhase last = wrongBoundary.getLast();

        wrongBoundary.set(wrongBoundary.size() - 1, new DiploBNPhase(
                last.sourcePhase(),
                last.sourceStatus(),
                last.gamePhase(),
                source.phases().getFirst().board(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        require(rejects(() -> importer.importGame(1005,
                        new DiploBNGame(null, null, null, wrongBoundary))),
                "A divergent final board was accepted");

        System.out.println("DiploBNRecordImporter checks passed.");

    }


    // Generate a continuous local reference game. \\

    private static GameRecord fixture(boolean retreatFleet) {

        Game game = StandardGameFactory.create1901();

        GameRecordBuilder archive = new GameRecordBuilder(
                UUID.randomUUID(), "fixture", game);

        UnitId venice = unitAt(game.board(), Province.Ven);
        UnitId rome = unitAt(game.board(), Province.Rom);
        UnitId naples = unitAt(game.board(), Province.Nap);
        UnitId trieste = unitAt(game.board(), Province.Tri);

        Map<UnitId, Order> spring = holds(game.board());
        spring.put(venice, Order.move(venice, Province.Tyr));
        spring.put(rome, Order.move(rome, Province.Ven));
        spring.put(naples, Order.move(naples, Province.ION));

        archive.resolveMovement(spring.values(), moment(game, 0));

        Map<UnitId, Order> fall = holds(game.board());
        fall.put(venice, Order.move(venice, Province.Tri));
        fall.put(rome, Order.supportMove(rome, Province.Tyr, Province.Tri));

        archive.resolveMovement(fall.values(), moment(game, 1));

        require(game.phase() == GamePhase.FALL_RETREAT,
                "Fixture did not create the expected retreat");

        archive.resolveRetreats(
                retreatFleet ? List.of(new RetreatOrder(trieste, Province.Alb)) : List.of(),
                moment(game, 2));

        archive.resolveAdjustments(
                List.of(new BuildOrder(
                        Nation.ITALY, domain.UnitType.ARMY, Province.Rom)),
                moment(game, 3));

        archive.resolveMovement(holds(game.board()).values(), moment(game, 4));

        return archive.build();

    }

    private static Map<UnitId, Order> holds(BoardState board) {

        Map<UnitId, Order> orders = new LinkedHashMap<>();

        for (UnitId unit : board.locations().keySet())
            orders.put(unit, Order.hold(unit));

        return orders;

    }

    private static Moment moment(Game game, int index) {
        return new Moment(game.year(), game.phase(), Instant.EPOCH.plusSeconds(index));
    }


    // Source units intentionally receive unrelated identities in every snapshot. \\

    private static DiploBNGame sourceGame(GameRecord record) {

        List<DiploBNPhase> phases = new ArrayList<>();
        BoardState precedingMovementBoard = null;

        for (ResolvedPhaseRecord phase : record.resolvedPhases()) {

            BoardState sourceBoard;

            if (phase instanceof RetreatPhaseRecord)
                sourceBoard = copyBoard(precedingMovementBoard);
            else
                sourceBoard = copyBoard(phase.boardBefore());

            List<DiploBNOrder> sourceOrders = new ArrayList<>();
            List<Order> movementOrders = new ArrayList<>();
            List<RetreatOrder> retreats = new ArrayList<>();
            List<AdjustmentOrder> adjustments = new ArrayList<>();

            List<DiploBNOrderResolution> resolutions = new ArrayList<>();
            List<DiploBNOrderResolution> retreatResolutions = new ArrayList<>();

            if (phase instanceof MovementPhaseRecord movement) {

                precedingMovementBoard = movement.boardBefore();

                for (Order order : movement.submittedOrders()) {

                    Province location = movement.boardBefore().locationOf(order.unit());
                    UnitId sourceUnit = unitAt(sourceBoard, location);

                    DiploBNOrder converted = new DiploBNOrder(
                            sourceUnit, order.orderType(), order.target(), order.auxiliaryTarget());

                    sourceOrders.add(converted);
                    movementOrders.add(converted);

                    resolutions.add(new DiploBNOrderResolution(
                            order.owner(), location, false,
                            movement.result().outcomes().get(order.unit()).succeeded(), null));

                }

            } else if (phase instanceof RetreatPhaseRecord retreat) {

                for (RetreatOrder order : retreat.submittedOrders()) {

                    Province origin = retreat.result().movementResult()
                            .dislodgements().get(order.unit()).displacedFrom();

                    retreats.add(new RetreatOrder(
                            unitAt(sourceBoard, origin), order.destination()));

                    retreatResolutions.add(new DiploBNOrderResolution(
                            order.owner(), origin, true,
                            retreat.result().outcomeOf(order.unit()).succeeded(), null));

                }

            } else if (phase instanceof AdjustmentPhaseRecord adjustment) {

                for (AdjustmentOrder order : adjustment.submittedOrders()) {

                    if (order instanceof BuildOrder build) {
                        adjustments.add(build);
                    } else if (order instanceof DisbandOrder disband) {

                        Province location = adjustment.boardBefore().locationOf(disband.unit());

                        adjustments.add(new DisbandOrder(
                                disband.owner(), unitAt(sourceBoard, location)));

                    } else {
                        adjustments.add(order);
                    }

                }

            }

            phases.add(new DiploBNPhase(
                    sourceCode(phase.gameMoment().year(), phase.gameMoment().gamePhase()),
                    phase.gameMoment().gamePhase().isRetreat()
                            ? "AwaitingRetreats" : "AwaitingOrders",
                    phase.gameMoment().gamePhase(),
                    sourceBoard,
                    sourceOrders,
                    movementOrders,
                    retreats,
                    adjustments,
                    resolutions,
                    retreatResolutions));

        }

        // The fixture ends after Spring 1902 movement without dislodgements.
        phases.add(new DiploBNPhase(
                19022,
                "AwaitingOrders",
                GamePhase.FALL_MOVEMENT,
                copyBoard(record.latestBoard()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        return new DiploBNGame("Fixture", "Persistent unit replay", null, phases);

    }

    private static BoardState copyBoard(BoardState board) {

        Objects.requireNonNull(board, "board");

        Map<UnitId, Province> units = new LinkedHashMap<>();

        for (Map.Entry<UnitId, Province> entry : board.locations().entrySet()) {

            UnitId old = entry.getKey();

            units.put(new UnitId(
                    UUID.randomUUID(),
                    old.owner(),
                    old.unitType(),
                    entry.getValue()), entry.getValue());

        }

        return new BoardState(units, board.owners());

    }

    private static int sourceCode(int year, GamePhase phase) {
        return year * 10 + switch (phase) {
            case SPRING_MOVEMENT, SPRING_RETREAT -> 1;
            case FALL_MOVEMENT, FALL_RETREAT -> 2;
            case WINTER_ADJUSTMENT -> 3;
        };
    }

    private static UnitId unitAt(BoardState board, Province province) {

        for (Map.Entry<UnitId, Province> entry : board.locations().entrySet())
            if (entry.getValue() == province)
                return entry.getKey();

        throw new IllegalArgumentException("No unit at " + province);

    }

    private static boolean rejects(Runnable action) {

        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return true;
        }

        return false;

    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

}