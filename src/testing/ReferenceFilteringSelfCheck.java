package testing;

import analysis.CandidateSelectionAudit;
import analysis.JointOrders;
import analysis.ReferenceFilteredPrediction;
import analysis.RoutePrediction;
import domain.OrderType;
import domain.Province;
import game.BoardState;
import game.StandardGameFactory;
import phase.Order;
import phase.UnitId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public final class ReferenceFilteringSelfCheck {

    private ReferenceFilteringSelfCheck() {  }


    public static void main(String[] args) {

        BoardState board = StandardGameFactory.create1901().board();

        UnitId london = board.locations().entrySet().stream()
                .filter(entry -> entry.getValue() == Province.Lon)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

        Order emptySupport = new Order(
                london, OrderType.SUPPORT, Province.Gal, null);

        Order nonArmyConvoy = new Order(
                london, OrderType.CONVOY, Province.Edi, Province.Nwy);

        Order fleetSupport = new Order(
                london, OrderType.SUPPORT, Province.Edi, null);

        Order move = Order.move(london, Province.NTH);
        Order hold = Order.hold(london);

        Map<Order, Long> counts = new LinkedHashMap<>();
        counts.put(emptySupport, 5L);
        counts.put(move, 4L);
        counts.put(nonArmyConvoy, 3L);
        counts.put(fleetSupport, 2L);
        counts.put(hold, 1L);

        RoutePrediction original = new RoutePrediction(
                "POSITION_OCCUPANCY_RELAXED", 0, counts);

        RoutePrediction filtered =
                ReferenceFilteredPrediction.apply(board, original);

        require(filtered.basis().equals(original.basis())
                        && filtered.suffixLength() == original.suffixLength(),
                "Filtering lost prediction provenance");

        require(filtered.counts().equals(
                        Map.of(move, 4L, fleetSupport, 2L, hold, 1L)),
                "Wrong surviving candidates or counts");

        require(filtered.observations() == 7,
                "Wrong sum of surviving observations");

        require(original.counts().size() == 5
                        && original.observations() == 15,
                "Original prediction was mutated");

        List<Order> expected = CandidateSelectionAudit.referenceCompatibleOrders(
                board, JointOrders.rankedOrders(board, original));

        require(JointOrders.rankedOrders(board, filtered).equals(expected),
                "Filtering differs from the earlier paired audit");

        require(!JointOrders.rankedOrders(board, original)
                        .subList(0, 4).contains(hold),
                "Fixture must put hold outside the original top four");

        require(JointOrders.rankedOrders(board, filtered).contains(hold),
                "Filtering failed to expose the lower-ranked hold");

        RoutePrediction allDefective = new RoutePrediction(
                "POSITION", 0,
                Map.of(emptySupport, 3L, nonArmyConvoy, 2L));

        RoutePrediction emptied =
                ReferenceFilteredPrediction.apply(board, allDefective);

        require(emptied.counts().isEmpty(),
                "All-defective distribution should become empty");

        require(new JointOrders(4, 8).generate(
                        board, london.owner(), Map.of(london, emptied)).isEmpty(),
                "Empty evidence should not produce a national plan");

        RoutePrediction belowCutoffAfterFiltering = new RoutePrediction(
                "POSITION", 0, Map.of(emptySupport, 3L, hold, 1L));

        require(ReferenceFilteredPrediction.apply(
                                board, belowCutoffAfterFiltering)
                        .counts().equals(Map.of(hold, 1L)),
                "Filtering incorrectly reapplied the evidence threshold");

        RoutePrediction valid = new RoutePrediction(
                "EXACT", -1, Map.of(hold, 3L));

        require(ReferenceFilteredPrediction.apply(board, valid) == valid,
                "Unchanged prediction need not be rebuilt");

        require(ReferenceFilteredPrediction.apply(board, filtered).equals(filtered),
                "Filtering is not idempotent");

        RoutePrediction none = new RoutePrediction("NONE", 0, Map.of());

        require(ReferenceFilteredPrediction.apply(board, none) == none,
                "Empty prediction changed");

        System.out.println("ReferenceFiltering checks passed.");

    }

    private static void require(boolean condition, String message) {

        if (!condition)
            throw new AssertionError(message);

    }

}