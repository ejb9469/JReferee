package testing;

import domain.Province;
import domain.OrderType;
import game.Game;
import game.GamePhase;
import parsing.diplobn.DiploBNGame;
import parsing.diplobn.DiploBNOrder;
import parsing.diplobn.DiploBNOrderResolution;
import parsing.diplobn.ParseException;
import parsing.diplobn.DiploBNParser;
import parsing.diplobn.DiploBNPhase;
import phase.Order;
import phase.adjustments.AdjustmentOrder;
import phase.adjustments.BuildOrder;
import phase.adjustments.DisbandOrder;
import phase.retreats.RetreatOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static testing.AdjudicatorTestCase.TESTCASE_PREFIX;


/**
 * Dependency-free parser coverage for the DiploBN import format.
 *
 * <p>This class implements JReferee's shared {@link TestCase} reporting
 * contract so it can be evaluated and printed beside adjudicator and phase
 * processor tests.</p>
 */
public final class DiploBNParserTestCase implements TestCase {


    public static final String NAME = "DiploBN Parser";

    private static final Path VALID_GAME_PATH = Path.of(
            "src",
            "resources",
            "diplobn",
            "valid-complete-game.json");

    private static final Path INVALID_COAST_PATH = Path.of(
            "src",
            "resources",
            "diplobn",
            "invalid-unknown-coast.json");


    private TestEvaluation evaluation;
    private String eval;


    public DiploBNParserTestCase() {    }


    @Override
    public void eval(boolean print) {

        TestEvaluation.Builder checks =
                new TestEvaluation.Builder();

        try {
            DiploBNGame game = parseValidGame();

            checkGameMetadata(game, checks);
            checkMovementPhase(game.phases().get(0), checks);
            checkRetreatPhase(game.phases().get(1), checks);
            checkWinterPhase(game.phases().get(2), checks);
            checkGameCreation(game, checks);

        } catch (RuntimeException exception) {
            checks.expect(
                    "Valid fixture parses without exception",
                    "No exception",
                    describe(exception)
            );
        }

        checkInvalidCoastRejection(checks);

        this.evaluation = checks.build();
        this.eval = evaluation.format(NAME);

        if (print)
            printEval();

    }


    private DiploBNGame parseValidGame() {

        String source = readFile(VALID_GAME_PATH);

        return new DiploBNParser().parse(source);

    }

    private void checkGameMetadata(
            DiploBNGame game,
            TestEvaluation.Builder checks
    ) {

        checks.expect(
                "Competition metadata",
                "JReferee DiploBN import fixture",
                game.competition()
        );

        checks.expect(
                "Game label metadata",
                "Parser coverage game",
                game.gameLabel()
        );

        checks.expect(
                "URL metadata is present",
                true,
                game.sourceUrl() != null
                        && !game.sourceUrl().isBlank()
        );

        checks.expect(
                "Source phase count",
                3,
                game.phases().size()
        );

    }

    private void checkMovementPhase(
            DiploBNPhase phase,
            TestEvaluation.Builder checks
    ) {

        checks.expect(
                "Spring source phase",
                19011,
                phase.sourcePhase()
        );

        checks.expect(
                "Spring JReferee phase",
                GamePhase.SPRING_MOVEMENT,
                phase.gamePhase()
        );

        checks.expect(
                "Spring board unit count",
                7,
                phase.board().locations().size()
        );

        checks.expect(
                "Spring supply-center owner count",
                5,
                phase.board().owners().size()
        );

        checks.expect(
                "Split-coast unit location",
                true,
                phase.board().locations().containsValue(
                        Province.SpaSC)
        );

        checks.expect(
                "Movement order count",
                7,
                phase.movementOrders().size()
        );

        checks.expect(
                "Source movement order count",
                phase.movementOrders().size(),
                phase.sourceMovementOrders().size()
        );

        List<DiploBNOrder> sourceOrders =
                phase.sourceMovementOrders();

        if (!sourceOrders.isEmpty()) {
            DiploBNOrder firstSourceOrder =
                    sourceOrders.getFirst();

            checks.expect(
                    "First source movement order type",
                    OrderType.CONVOY,
                    firstSourceOrder.type()
            );

            checks.expect(
                    "First source movement order target",
                    Province.Lon,
                    firstSourceOrder.target()
            );

            checks.expect(
                    "First source movement order auxiliary target",
                    Province.Bre,
                    firstSourceOrder.auxiliaryTarget()
            );
        }

        for (int index = 0;
             index < phase.sourceMovementOrders().size();
             index++) {

            DiploBNOrder sourceOrder =
                    phase.sourceMovementOrders().get(index);

            Order translatedOrder =
                    phase.movementOrders().get(index);

            checks.expect(
                    "Movement order " + index + " unit alignment",
                    sourceOrder.unit(),
                    translatedOrder.unit()
            );

            checks.expect(
                    "Movement order " + index + " type alignment",
                    sourceOrder.type(),
                    translatedOrder.type()
            );

            checks.expect(
                    "Movement order " + index + " target alignment",
                    sourceOrder.target(),
                    translatedOrder.target()
            );

            checks.expect(
                    "Movement order " + index
                            + " auxiliary target alignment",
                    sourceOrder.auxiliaryTarget(),
                    translatedOrder.auxiliaryTarget()
            );
        }

        checks.expect(
                "Movement phase has no retreat orders",
                true,
                phase.retreatOrders().isEmpty()
        );

        checks.expect(
                "Movement phase has no adjustment orders",
                true,
                phase.adjustmentOrders().isEmpty()
        );

        checks.expect(
                "Movement resolution count",
                7,
                phase.resolutions().size()
        );

        DiploBNOrderResolution failedMove =
                phase.resolutions().get(1);

        checks.expect(
                "Failed movement result",
                false,
                failedMove.successful()
        );

        checks.expect(
                "Failed movement result reason",
                "Bounced",
                failedMove.reason()
        );

    }

    private void checkRetreatPhase(
            DiploBNPhase phase,
            TestEvaluation.Builder checks
    ) {

        checks.expect(
                "Retreat source phase",
                19011,
                phase.sourcePhase()
        );

        checks.expect(
                "Retreat JReferee phase",
                GamePhase.SPRING_RETREAT,
                phase.gamePhase()
        );

        checks.expect(
                "Retreat phase has no movement orders",
                true,
                phase.movementOrders().isEmpty()
        );

        checks.expect(
                "Retreat phase has no adjustment orders",
                true,
                phase.adjustmentOrders().isEmpty()
        );

        List<RetreatOrder> retreatOrders = phase.retreatOrders();

        checks.expect(
                "Retreat order count",
                1,
                retreatOrders.size()
        );

        if (!retreatOrders.isEmpty()) {
            RetreatOrder retreatOrder = retreatOrders.getFirst();

            checks.expect(
                    "Retreat destination",
                    Province.Wal,
                    retreatOrder.destination()
            );
        }

        checks.expect(
                "Retreat resolution count",
                1,
                phase.retreatResolutions().size()
        );

        if (!phase.retreatResolutions().isEmpty()) {
            DiploBNOrderResolution retreatResolution =
                    phase.retreatResolutions().getFirst();

            checks.expect(
                    "Resolution is marked as retreat",
                    true,
                    retreatResolution.retreatOrder()
            );

            checks.expect(
                    "Successful retreat result",
                    true,
                    retreatResolution.successful()
            );

            checks.expect(
                    "Successful retreat result reason",
                    "Retreated",
                    retreatResolution.reason()
            );
        }

    }

    private void checkWinterPhase(
            DiploBNPhase phase,
            TestEvaluation.Builder checks
    ) {

        checks.expect(
                "Winter source phase",
                19013,
                phase.sourcePhase()
        );

        checks.expect(
                "Winter JReferee phase",
                GamePhase.WINTER_ADJUSTMENT,
                phase.gamePhase()
        );

        checks.expect(
                "Winter phase has no movement orders",
                true,
                phase.movementOrders().isEmpty()
        );

        checks.expect(
                "Winter phase has no retreat orders",
                true,
                phase.retreatOrders().isEmpty()
        );

        List<AdjustmentOrder> orders = phase.adjustmentOrders();

        checks.expect(
                "Winter adjustment order count",
                2,
                orders.size()
        );

        if (!orders.isEmpty()) {
            AdjustmentOrder firstOrder = orders.getFirst();

            checks.expect(
                    "First winter order is build",
                    true,
                    firstOrder instanceof BuildOrder
            );

            if (firstOrder instanceof BuildOrder buildOrder) {
                checks.expect(
                        "Build location",
                        Province.Wal,
                        buildOrder.location()
                );
            }
        }

        if (orders.size() > 1) {
            AdjustmentOrder secondOrder = orders.get(1);

            checks.expect(
                    "Second winter order is disband",
                    true,
                    secondOrder instanceof DisbandOrder
            );
        }

        checks.expect(
                "Winter resolution count",
                2,
                phase.resolutions().size()
        );

        if (phase.resolutions().size() > 1) {
            DiploBNOrderResolution failedDisband =
                    phase.resolutions().get(1);

            checks.expect(
                    "Failed disband result",
                    false,
                    failedDisband.successful()
            );

            checks.expect(
                    "Failed disband result reason",
                    "Too many units",
                    failedDisband.reason()
            );
        }

    }

    private void checkGameCreation(
            DiploBNGame imported,
            TestEvaluation.Builder checks
    ) {

        Game game = imported.newGame();

        checks.expect(
                "Imported Game starting year",
                1901,
                game.year()
        );

        checks.expect(
                "Imported Game starting phase",
                GamePhase.SPRING_MOVEMENT,
                game.phase()
        );

        checks.expect(
                "Imported Game starting board",
                imported.phases().getFirst().board(),
                game.board()
        );

    }

    private void checkInvalidCoastRejection(
            TestEvaluation.Builder checks
    ) {

        String source = readFile(INVALID_COAST_PATH);

        try {
            new DiploBNParser().parse(source);

            checks.expect(
                    "Unsupported Spain west coast is rejected",
                    "ParseException",
                    "No exception"
            );

        } catch (ParseException exception) {
            checks.expect(
                    "Unsupported Spain west coast is rejected",
                    true,
                    exception.getMessage().contains(
                            "Unsupported coast wc for Spa")
            );
        }

    }

    private String readFile(Path path) {

        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read parser fixture: " + path,
                    exception);
        }

    }

    private static String describe(RuntimeException exception) {
        return exception.getClass().getSimpleName()
                + ": "
                + exception.getMessage();
    }


    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getEval() {
        return eval;
    }

    @Override
    public int getScore() {
        return evaluation == null
                ? 0
                : evaluation.score();
    }

    @Override
    public int getSize() {
        return evaluation == null
                ? 0
                : evaluation.size();
    }

    @Override
    public void printEval() {

        if (eval == null) {
            System.out.println(
                    "UNEVALUATED "
                            + TESTCASE_PREFIX
                            + getName());
            return;
        }

        System.out.println(eval + "\n\n");

    }

    @Override
    public void printNameAndScore() {
        System.out.printf(
                "[%02d/%02d]\t%s%s%n",
                getScore(),
                getSize(),
                TESTCASE_PREFIX,
                getName()
        );
    }

    @Override
    public String toString() {
        return getName();
    }

}