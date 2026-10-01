package analysis.openings;

import analysis.Identifiers;
import contracts.OrderForm;
import domain.Nation;
import domain.Province;
import game.BoardState;
import game.GameMoment;
import phase.Order;

import java.util.*;


public sealed class Opening permits OpeningCombination {

    private final GameMoment moment;
    private final BoardState board;
    private final List<Order> orders;
    private final Set<Nation> nations;
    private final String signature;
    private final String id;


    public Opening(Nation nation, GameMoment moment, BoardState board,
                   Collection<? extends Order> orders) {
        this(
                moment,
                board,
                orders,
                EnumSet.of(Objects.requireNonNull(nation, "nation"))
        );
    }

    protected Opening(GameMoment moment, BoardState board,
                      Collection<? extends Order> orders, Set<Nation> nations) {

        this.moment = Objects.requireNonNull(moment, "moment");
        this.board = Objects.requireNonNull(board, "board");
        this.orders = List.copyOf(Objects.requireNonNull(orders, "orders"));

        Objects.requireNonNull(nations, "nations");

        if (nations.isEmpty())
            throw new IllegalArgumentException("An opening must include at least one nation");

        EnumSet<Nation> scope = EnumSet.copyOf(nations);

        if (scope.size() != 1 && !scope.equals(EnumSet.allOf(Nation.class)))
            throw new IllegalArgumentException(
                    "An opening must describe one nation or all seven nations");

        this.nations = Collections.unmodifiableSet(scope);

        for (Order order : this.orders)
            if (!scope.contains(order.owner()))
                throw new IllegalArgumentException(
                        "Order belongs to a nation outside the opening: " + order.owner());

        // Validates the phase, standard board, issuers, and duplicate orders.
        Classifier classifier = new Classifier(moment, board, this.orders);
        Nation[] selected = scope.toArray(new Nation[0]);

        if (!classifier.isComplete(selected))
            throw new IllegalArgumentException("Incomplete opening orders for " + scope);

        this.signature = classifier.signature(selected);
        this.id = scope.size() == 1
                ? Identifiers.opening(scope.iterator().next(), signature)
                : Identifiers.combination(signature);

    }


    // Identity and scope \\

    public final String id() {
        return id;
    }

    public final String signature() {
        return signature;
    }

    public final Set<Nation> nations() {
        return nations;
    }

    public final boolean isCombination() {
        return nations.size() > 1;
    }

    public final Nation nation() {

        if (isCombination())
            throw new IllegalStateException("A combination does not have a single nation");

        return nations.iterator().next();

    }

    public final GameMoment moment() {
        return moment;
    }

    public final BoardState board() {
        return board;
    }

    public final List<Order> orders() {
        return orders;
    }


    // Presentation never changes identity. \\

    public final List<adjudication.Order> displayOrders() {

        List<adjudication.Order> result = new ArrayList<>();

        for (Order order : orders) {

            Province location = board.locationOf(order.unit());

            if (location == null)
                throw new IllegalStateException("Opening issuer is absent from its board");

            result.add(new adjudication.Order(order, location));

        }

        Collections.sort(result);
        return List.copyOf(result);

    }

    public final String label() {

        StringJoiner label = new StringJoiner("; ");

        for (adjudication.Order order : displayOrders())
            label.add(OrderForm.format(order, order.origin()));

        return label.toString();

    }

    @Override
    public final String toString() {
        return id + ": " + label();
    }

    @Override
    public final boolean equals(Object other) {

        if (this == other)
            return true;

        if (!(other instanceof Opening opening))
            return false;

        // Compare canonical content as well as the hash.
        return id.equals(opening.id)
                && signature.equals(opening.signature)
                && nations.equals(opening.nations);

    }

    @Override
    public final int hashCode() {
        return Objects.hash(id, signature, nations);
    }

}