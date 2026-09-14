package adjudication;

import domain.OrderType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable record of one recursive dependency cycle detected while
 * adjudicating a collection of orders.<br><br>
 *
 * The list retains the actual Order references for diagnostic purposes. The stable key
 * and convoy flag are captured at construction time because Judge may later
 * mutate a paradoxical convoy into a Szykman HOLD.
 */
public final class ParadoxCycle {

    private final List<Order> members;
    private final String key;
    private final boolean containsConvoy;


    public ParadoxCycle(List<Order> members) {
        this.members = Collections.unmodifiableList(
                new ArrayList<>(members));
        this.containsConvoy = calculateContainsConvoy(this.members);
        this.key = calculateKey(this.members);
    }


    /**
     * Returns the exact Order references participating in the recursive cycle.
     */
    public List<Order> getMembers() {
        return this.members;
    }

    /**
     * Returns whether the cycle contained a convoy when it was detected.
     */
    public boolean containsConvoy() {
        return this.containsConvoy;
    }

    /**
     * Returns a stable submitted-order identity key captured when this cycle was
     * detected. It excludes mutable resolution metadata.
     */
    public String key() {
        return this.key;
    }

    private static boolean calculateContainsConvoy(List<Order> members) {
        for (Order order : members) {
            if (order.orderType == OrderType.CONVOY)
                return true;
            Order originalOrder = order.getSnapshot();
            if (originalOrder != null
                    && originalOrder.orderType == OrderType.CONVOY)
                return true;
        }
        return false;
    }

    private static String calculateKey(List<Order> members) {

        List<String> entries = new ArrayList<>();

        for (Order order : members) {
            Order originalOrder = order.getSnapshot();

            if (originalOrder == null)
                originalOrder = order;

            entries.add(
                    originalOrder.owner
                            + "|" + originalOrder.unitType
                            + "|" + originalOrder.orderType
                            + "|" + originalOrder.pos0
                            + "|" + originalOrder.pos1
                            + "|" + originalOrder.pos2
            );
        }

        Collections.sort(entries);
        return String.join("\n", entries);

    }

    @Override
    public String toString() {

        StringBuilder output = new StringBuilder();

        output.append("ParadoxCycle[")
                .append("members=")
                .append(this.members.size())
                .append(", containsConvoy=")
                .append(this.containsConvoy)
                .append("]");

        for (int i = 0; i < this.members.size(); i++) {
            output.append(System.lineSeparator())
                    .append("  [")
                    .append(i)
                    .append("] ")
                    .append(this.members.get(i));
        }

        return output.toString();

    }

}