package testing;

import analysis.CandidateSelectionAudit;
import analysis.JointOrders;
import analysis.RoutePrediction;
import domain.OrderType;
import domain.Province;
import game.BoardState;
import game.StandardGameFactory;
import phase.Order;
import phase.UnitId;

import java.util.*;


public final class CandidateSelectionAuditSelfCheck {

    private CandidateSelectionAuditSelfCheck() {  }


    public static void main(String[] args) {

        BoardState board = StandardGameFactory.create1901().board();

        UnitId london = unitAt(board, Province.Lon);

        Order missingSupport = new Order(
                london, OrderType.SUPPORT, Province.Gal, null);

        Order fleetConvoy = new Order(
                london, OrderType.CONVOY, Province.Edi, Province.Nwy);

        Order armyConvoy = new Order(
                london, OrderType.CONVOY, Province.Lvp, Province.Nwy);

        Order fleetSupport = new Order(
                london, OrderType.SUPPORT, Province.Edi, null);

        Order move = Order.move(london, Province.NTH);
        Order hold = Order.hold(london);

        require(CandidateSelectionAudit.referenceIssue(board, missingSupport)
                        == CandidateSelectionAudit.ReferenceIssue.MISSING_UNIT,
                "Support to an empty province was not identified");

        require(CandidateSelectionAudit.referenceIssue(board, fleetConvoy)
                        == CandidateSelectionAudit.ReferenceIssue.CONVOY_WITHOUT_ARMY,
                "Convoy reference to a fleet was not identified");

        require(CandidateSelectionAudit.referenceIssue(board, armyConvoy)
                        == CandidateSelectionAudit.ReferenceIssue.NONE,
                "An army reference should pass this limited check");

        require(CandidateSelectionAudit.referenceIssue(board, fleetSupport)
                        == CandidateSelectionAudit.ReferenceIssue.NONE,
                "Support can reference a fleet for this limited check");

        require(CandidateSelectionAudit.referenceIssue(board, hold)
                        == CandidateSelectionAudit.ReferenceIssue.NONE,
                "Hold should not require a referenced unit");

        Map<Order, Long> counts = new LinkedHashMap<>();
        counts.put(missingSupport, 5L);
        counts.put(move, 4L);
        counts.put(fleetConvoy, 3L);
        counts.put(fleetSupport, 2L);
        counts.put(hold, 1L);

        RoutePrediction prediction =
                new RoutePrediction("POSITION_OCCUPANCY_RELAXED", 0, counts);

        List<Order> ranked = JointOrders.rankedOrders(board, prediction);

        require(!ranked.subList(0, 4).contains(hold),
                "Fixture must place hold below the original top four");

        List<Order> filtered =
                CandidateSelectionAudit.referenceCompatibleOrders(board, ranked);

        require(filtered.equals(List.of(move, fleetSupport, hold)),
                "Filtering must preserve survivor ranking and recover lower choices");

        require(prediction.counts().size() == 5
                        && prediction.observations() == 15,
                "Filtering mutated the prediction");

        require(CandidateSelectionAudit.referenceCompatibleOrders(
                        board, List.of(missingSupport, fleetConvoy)).isEmpty(),
                "All-reference-defective distribution should become empty");

        require(CandidateSelectionAudit.referenceCompatibleOrders(
                        board, List.of()).isEmpty(),
                "Empty distribution should remain empty");

        System.out.println("CandidateSelectionAudit checks passed.");

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