package analysis.openings;

import domain.Nation;
import game.BoardState;
import game.GameMoment;
import phase.Order;

import java.util.*;


public final class OpeningCombination extends Opening {

    private final Map<Nation, Opening> openings;
    private final Map<Nation, String> openingIds;


    public OpeningCombination(GameMoment moment, BoardState board,
                              Collection<? extends Order> orders) {

        super(moment, board, orders, EnumSet.allOf(Nation.class));

        Map<Nation, Opening> members = new EnumMap<>(Nation.class);
        Map<Nation, String> ids = new EnumMap<>(Nation.class);

        for (Nation nation : Nation.values()) {

            List<Order> nationalOrders = new ArrayList<>();

            // Use the already-copied submissions, not the caller's collection.
            for (Order order : orders())
                if (order.owner() == nation)
                    nationalOrders.add(order);

            Opening opening = new Opening(nation, moment(), board(), nationalOrders);

            members.put(nation, opening);
            ids.put(nation, opening.id());

        }

        this.openings = Collections.unmodifiableMap(members);
        this.openingIds = Collections.unmodifiableMap(ids);

    }


    public Map<Nation, Opening> openings() {
        return openings;
    }

    public Opening opening(Nation nation) {
        return openings.get(Objects.requireNonNull(nation, "nation"));
    }

    public Map<Nation, String> openingIds() {
        return openingIds;
    }

}