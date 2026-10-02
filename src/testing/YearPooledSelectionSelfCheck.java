package testing;

import analysis.JointOrders;
import analysis.RoutePrediction;
import analysis.YearPooledSelection;
import domain.Province;
import game.BoardState;
import game.StandardGameFactory;
import phase.Order;
import phase.UnitId;

import java.util.List;
import java.util.Map;


public final class YearPooledSelectionSelfCheck {

    private YearPooledSelectionSelfCheck() {  }


    public static void main(String[] args) {

        BoardState board = StandardGameFactory.create1901().board();

        UnitId london = board.locations().entrySet().stream()
                .filter(entry -> entry.getValue() == Province.Lon)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

        Order hold = Order.hold(london);
        Order move = Order.move(london, Province.NTH);

        RoutePrediction pooled = new RoutePrediction(
                "POSITION_YEAR_POOLED", 0,
                Map.of(hold, 3L, move, 10L));

        for (String basis : List.of("EXACT", "SUFFIX", "POSITION")) {

            int suffixLength = basis.equals("EXACT") ? -1
                    : basis.equals("SUFFIX") ? 1 : 0;

            RoutePrediction original = new RoutePrediction(
                    basis, suffixLength, Map.of(hold, 3L));

            RoutePrediction selected =
                    YearPooledSelection.select(original, pooled, 3);

            require(selected == pooled,
                    "Qualifying " + basis + " prediction was not replaced");

            require(original.counts().equals(Map.of(hold, 3L)),
                    "Selection mutated the original prediction");

            require(JointOrders.rankedOrders(board, original).getFirst().equals(hold),
                    "Fixture must originally prefer hold");

            require(JointOrders.rankedOrders(board, selected).getFirst().equals(move),
                    "Selection should use the pooled ranking");

        }

        RoutePrediction exact = new RoutePrediction(
                "EXACT", -1, Map.of(hold, 3L));

        RoutePrediction sparse = new RoutePrediction(
                "POSITION_YEAR_POOLED", 0, Map.of(move, 2L));

        require(YearPooledSelection.select(exact, sparse, 3) == exact,
                "Below-threshold pooled evidence replaced the original");

        RoutePrediction none = new RoutePrediction("NONE", 0, Map.of());

        require(YearPooledSelection.select(exact, none, 3) == exact,
                "Absent pooled evidence replaced the original");

        require(YearPooledSelection.select(none, pooled, 3) == none,
                "Selection policy must not fill an abstention");

        require(YearPooledSelection.select(pooled, pooled, 3) == pooled,
                "Existing year-pooled prediction changed");

        RoutePrediction relaxed = new RoutePrediction(
                "POSITION_OCCUPANCY_RELAXED", 0, Map.of(hold, 7L));

        require(YearPooledSelection.select(relaxed, pooled, 3) == relaxed,
                "Occupancy-relaxed selection must remain unchanged");

        boolean invalidMinimumRejected = false;

        try {
            YearPooledSelection.select(exact, pooled, 0);
        } catch (IllegalArgumentException expected) {
            invalidMinimumRejected = true;
        }

        require(invalidMinimumRejected, "Invalid minimum was accepted");

        System.out.println("YearPooledSelection checks passed.");

    }

    private static void require(boolean condition, String message) {

        if (!condition)
            throw new AssertionError(message);

    }

}