package analysis.openings;

import domain.Nation;
import domain.Province;
import game.BoardState;
import game.GameMoment;
import game.GamePhase;
import game.StandardGameFactory;
import phase.Order;
import phase.UnitId;

import java.util.*;


/**
 * Validation and matching engine behind Opening.
 * Matching uses board locations, not unit UUIDs or creation origins.
 */
public class Classifier {

    private static final BoardState STARTING_BOARD =
            StandardGameFactory.create1901().board();

    private final BoardState board;
    private final List<Order> orders;
    private final Set<String> orderKeys;


    public Classifier(GameMoment moment, BoardState board,
                      Collection<? extends Order> orders) {

        Objects.requireNonNull(moment, "moment");

        if (moment.year() != 1901 || moment.gamePhase() != GamePhase.SPRING_MOVEMENT)
            throw new IllegalArgumentException("Opening classification requires Spring 1901 movement");

        requireStartingBoard(board);

        this.board = board;
        this.orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
        this.orderKeys = keys(board, this.orders);

    }


    // Signatures and completeness \\

    public static String signature(BoardState board, Collection<? extends Order> orders) {
        return "orders-v1:" + String.join(";", keys(board, orders));
    }

    public String signature(Nation... nations) {

        Set<Nation> scope = scope(nations);
        List<Order> selected = selectedOrders(scope);

        if (selected.size() != unitCount(scope))
            throw new IllegalStateException("Incomplete opening orders for " + scope);

        return signature(board, selected);

    }

    public boolean isComplete(Nation... nations) {

        Set<Nation> scope = scope(nations);
        return selectedOrders(scope).size() == unitCount(scope);

    }


    // Opening overloads reuse the existing matching logic. \\

    public boolean matches(Opening opening) {
        return matches(opening, true);
    }

    public boolean matches(Opening opening, boolean exact) {

        Objects.requireNonNull(opening, "opening");

        return matches(opening.board(), opening.orders(), exact);

    }

    public List<String> classify(Map<String, ? extends Opening> categories, boolean exact) {

        Objects.requireNonNull(categories, "categories");

        List<String> matching = new ArrayList<>();

        for (Map.Entry<String, ? extends Opening> entry : categories.entrySet()) {

            String name = requireCategoryName(entry.getKey());

            if (matches(entry.getValue(), exact))
                matching.add(name);

        }

        Collections.sort(matching);
        return List.copyOf(matching);

    }


    /**
     * Exact matching covers every starting unit of the mentioned nations.
     * Subset matching leaves unmentioned orders unrestricted.
     */
    public boolean matches(BoardState requiredBoard, Collection<? extends Order> required,
                           boolean exact) {

        requireStartingBoard(requiredBoard);

        List<Order> pattern = List.copyOf(Objects.requireNonNull(required, "required"));
        Set<String> requiredKeys = keys(requiredBoard, pattern);

        if (requiredKeys.isEmpty())
            throw new IllegalArgumentException("An opening pattern must contain at least one order");

        if (!exact)
            return orderKeys.containsAll(requiredKeys);

        Set<Nation> scope = EnumSet.noneOf(Nation.class);

        for (Order order : pattern)
            scope.add(order.owner());

        if (pattern.size() != unitCount(scope))
            throw new IllegalArgumentException(
                    "An exact pattern must include every starting unit of " + scope);

        return keys(board, selectedOrders(scope)).equals(requiredKeys);

    }

    public List<String> classify(BoardState requiredBoard, Map<String, List<Order>> categories,
                                 boolean exact) {

        Objects.requireNonNull(categories, "categories");

        List<String> matching = new ArrayList<>();

        for (Map.Entry<String, List<Order>> entry : categories.entrySet()) {

            String name = requireCategoryName(entry.getKey());

            if (matches(requiredBoard, entry.getValue(), exact))
                matching.add(name);

        }

        Collections.sort(matching);
        return List.copyOf(matching);

    }

    private static String requireCategoryName(String name) {

        Objects.requireNonNull(name, "category name");

        if (name.isBlank())
            throw new IllegalArgumentException("Category names must not be blank");

        return name;

    }


    // Order keys \\

    private static Set<String> keys(BoardState board, Collection<? extends Order> orders) {

        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(orders, "orders");

        Set<UnitId> issuers = new HashSet<>();
        Set<String> result = new TreeSet<>();

        for (Order order : orders) {

            Objects.requireNonNull(order, "order");

            Province location = board.locationOf(order.unit());

            if (location == null)
                throw new IllegalArgumentException("Order issuer is absent from the board: " + order);

            if (!issuers.add(order.unit()))
                throw new IllegalArgumentException("Duplicate order for unit: " + order.unit());

            requireMovementOrder(order);

            result.add(
                    order.owner().name() + "|"
                            + order.unitType().name() + "|"
                            + location.name() + "|"
                            + order.orderType().name() + "|"
                            + provinceKey(order.target()) + "|"
                            + provinceKey(order.auxiliaryTarget())
            );

        }

        return result;

    }

    private static String provinceKey(Province province) {
        return province == null ? "-" : province.name();
    }

    private static void requireMovementOrder(Order order) {

        // Structure only; classification does not adjudicate the orders.
        boolean valid = switch (order.orderType()) {
            case HOLD -> order.target() == null && order.auxiliaryTarget() == null;
            case MOVE -> order.target() != null && order.auxiliaryTarget() == null;
            case SUPPORT -> order.target() != null;
            case CONVOY -> order.target() != null && order.auxiliaryTarget() != null;
            default -> false;
        };

        if (!valid)
            throw new IllegalArgumentException("Malformed or non-movement order: " + order);

    }


    // Starting position and nation selection \\

    private static void requireStartingBoard(BoardState board) {

        Objects.requireNonNull(board, "board");

        if (!position(board).equals(position(STARTING_BOARD))
                || !board.owners().equals(STARTING_BOARD.owners()))
            throw new IllegalArgumentException("Board is not the standard Spring 1901 starting position");

    }

    private static Map<Province, String> position(BoardState board) {

        Map<Province, String> result = new EnumMap<>(Province.class);

        for (Map.Entry<UnitId, Province> entry : board.locations().entrySet()) {

            UnitId unit = entry.getKey();

            if (result.putIfAbsent(
                    entry.getValue(),
                    unit.owner().name() + "|" + unit.unitType().name()) != null)
                throw new IllegalArgumentException("Multiple units at " + entry.getValue());

        }

        return result;

    }

    private static Set<Nation> scope(Nation... nations) {

        Objects.requireNonNull(nations, "nations");

        if (nations.length == 0)
            return EnumSet.allOf(Nation.class);

        Set<Nation> result = EnumSet.noneOf(Nation.class);
        Collections.addAll(result, nations);
        return result;

    }

    private List<Order> selectedOrders(Set<Nation> nations) {

        List<Order> selected = new ArrayList<>();

        for (Order order : orders)
            if (nations.contains(order.owner()))
                selected.add(order);

        return selected;

    }

    private static int unitCount(Set<Nation> nations) {

        int count = 0;

        for (UnitId unit : STARTING_BOARD.locations().keySet())
            if (nations.contains(unit.owner()))
                count++;

        return count;

    }

}