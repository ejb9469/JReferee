package testing;

import analysis.openings.Classifier;
import domain.Nation;
import domain.Province;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import game.StandardGameFactory;
import phase.Order;
import phase.UnitId;

import java.util.*;


public class OpeningTestCase implements TestCase {

    public static final String NAME = "Opening classification";

    private static final GameMoment SPRING_1901 =
            new GameMoment(1901, GamePhase.SPRING_MOVEMENT);

    private TestEvaluation evaluation;


    @Override
    public void eval(boolean print) {

        TestEvaluation.Builder checks = new TestEvaluation.Builder();

        BoardState board = StandardGameFactory.create1901().board();
        List<Order> england = englandOrders(board);

        Classifier classifier =
                new Classifier(SPRING_1901, board, england);

        checks.expect("England complete", true, classifier.isComplete(Nation.ENGLAND));
        checks.expect("Whole game incomplete", false, classifier.isComplete());

        List<Order> reversed = new ArrayList<>(england);
        Collections.reverse(reversed);

        checks.expect(
                "Order-independent signature",
                Classifier.signature(board, england),
                Classifier.signature(board, reversed)
        );

        Map<UnitId, Province> newUnits = new LinkedHashMap<>();

        for (Map.Entry<UnitId, Province> entry : board.locations().entrySet()) {

            UnitId original = entry.getKey();

            UnitId replacement = new UnitId(
                    UUID.randomUUID(),
                    original.owner(),
                    original.unitType(),
                    Province.Swi
            );

            newUnits.put(replacement, entry.getValue());

        }

        BoardState otherBoard = new BoardState(newUnits, board.owners());
        List<Order> otherEngland = englandOrders(otherBoard);

        Classifier otherClassifier =
                new Classifier(SPRING_1901, otherBoard, otherEngland);

        checks.expect(
                "UUID and creation origin do not affect the opening",
                classifier.signature(Nation.ENGLAND),
                otherClassifier.signature(Nation.ENGLAND)
        );

        checks.expect(
                "Exact matching across different unit identities",
                true,
                otherClassifier.matches(board, england, true)
        );

        List<Order> fleets = england.subList(0, 2);

        checks.expect(
                "Subset matching leaves the army unrestricted",
                true,
                classifier.matches(board, fleets, false)
        );

        Map<String, List<Order>> categories = new LinkedHashMap<>();
        categories.put("Northern fleets", fleets);
        categories.put("London to North Sea", List.of(england.getFirst()));
        categories.put("London to Channel", List.of(
                Order.move(unitAt(board, Province.Lon), Province.ENG)));

        checks.expect(
                "All matching names returned in stable order",
                List.of("London to North Sea", "Northern fleets"),
                classifier.classify(board, categories, false)
        );

        List<Order> combined = new ArrayList<>(england);
        combined.add(Order.move(unitAt(board, Province.Bre), Province.ENG));

        Classifier combinedClassifier =
                new Classifier(SPRING_1901, board, combined);

        checks.expect(
                "Other nations do not affect an exact national match",
                true,
                combinedClassifier.matches(board, england, true)
        );

        checks.expect(
                "Cross-power subset",
                true,
                combinedClassifier.matches(board, List.of(
                        england.getFirst(),
                        combined.getLast()
                ), false)
        );

        Classifier incomplete =
                new Classifier(SPRING_1901, board, fleets);

        checks.expect(
                "Missing army order is not a complete national opening",
                false,
                incomplete.isComplete(Nation.ENGLAND)
        );

        checks.expect(
                "Missing required order does not confirm an exact match",
                false,
                incomplete.matches(board, england, true)
        );

        checks.expect(
                "Missing order is not treated as a hold",
                false,
                incomplete.matches(board, List.of(
                        Order.hold(unitAt(board, Province.Lvp))
                ), false)
        );

        checks.expect(
                "Incomplete national signature rejected",
                true,
                throwsException(IllegalStateException.class,
                        () -> incomplete.signature(Nation.ENGLAND))
        );

        checks.expect(
                "Incomplete exact pattern rejected",
                true,
                throwsException(IllegalArgumentException.class,
                        () -> classifier.matches(board, fleets, true))
        );

        checks.expect(
                "Duplicate orders rejected",
                true,
                throwsException(IllegalArgumentException.class,
                        () -> new Classifier(
                                SPRING_1901, board,
                                List.of(england.getFirst(), england.getFirst())))
        );

        checks.expect(
                "Retreat phase rejected",
                true,
                throwsException(IllegalArgumentException.class,
                        () -> new Classifier(
                                new GameMoment(1901, GamePhase.SPRING_RETREAT),
                                board, england))
        );

        Map<UnitId, Province> movedUnits = new LinkedHashMap<>(board.locations());
        movedUnits.put(unitAt(board, Province.Lon), Province.NTH);
        BoardState movedBoard = new BoardState(movedUnits, board.owners());

        checks.expect(
                "Nonstandard starting position rejected",
                true,
                throwsException(IllegalArgumentException.class,
                        () -> new Classifier(SPRING_1901, movedBoard, england))
        );

        UnitId brest = unitAt(board, Province.Bre);

        checks.expect(
                "Exact destination coasts remain distinct",
                false,
                Classifier.signature(board, List.of(
                        Order.move(brest, Province.SpaNC)
                )).equals(Classifier.signature(board, List.of(
                        Order.move(brest, Province.SpaSC)
                )))
        );

        UnitId liverpool = unitAt(board, Province.Lvp);

        checks.expect(
                "Support-hold and support-move remain distinct",
                false,
                Classifier.signature(board, List.of(
                        Order.supportHold(liverpool, Province.Lon)
                )).equals(Classifier.signature(board, List.of(
                        Order.supportMove(liverpool, Province.Lon, Province.Wal)
                )))
        );

        checks.expect(
                "Convoy destination participates in the signature",
                false,
                Classifier.signature(board, List.of(
                        Order.convoy(brest, Province.Lvp, Province.Wal)
                )).equals(Classifier.signature(board, List.of(
                        Order.convoy(brest, Province.Lvp, Province.Bel)
                )))
        );

        this.evaluation = checks.build();

        if (print)
            printEval();

    }


    // Fixtures \\

    private static List<Order> englandOrders(BoardState board) {
        return List.of(
                Order.move(unitAt(board, Province.Lon), Province.NTH),
                Order.move(unitAt(board, Province.Edi), Province.NWG),
                Order.move(unitAt(board, Province.Lvp), Province.Yor)
        );
    }

    private static UnitId unitAt(BoardState board, Province province) {

        for (Map.Entry<UnitId, Province> entry : board.locations().entrySet())
            if (entry.getValue() == province)
                return entry.getKey();

        throw new IllegalArgumentException("No unit at " + province);

    }

    private static boolean throwsException(Class<? extends RuntimeException> type,
                                           Runnable action) {

        try {
            action.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception))
                return true;
            throw exception;
        }

        return false;

    }


    // TestCase \\

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getEval() {
        return evaluation == null ? null : evaluation.format(NAME);
    }

    @Override
    public int getScore() {
        return evaluation == null ? 0 : evaluation.score();
    }

    @Override
    public int getSize() {
        return evaluation == null ? 0 : evaluation.size();
    }

    @Override
    public void printEval() {
        System.out.println(evaluation == null ? "UNEVALUATED " + NAME : getEval());
    }

    @Override
    public void printNameAndScore() {
        System.out.printf("[%02d/%02d]\t%s%n", getScore(), getSize(), NAME);
    }

    public static void main(String[] args) {

        OpeningTestCase test = new OpeningTestCase();
        test.eval(true);

        if (!test.passed())
            throw new AssertionError("Opening classification tests failed");

    }

}