package adjudication;

import contracts.Snapshot;
import adjudication.util.OrderComparator;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;

import java.util.Objects;

/**
 * The `Order` class is a public-facing, *mutable* Diplomacy order 'struct'.<br><br>
 *
 * In addition to the relevant data fields, the `Order` class also contains adjudication-related 'metadata' fields --
 * (i.e. `<i>resolved</i>`, `<i>verdict</i>`, & `<i>visited</i>`)
 */
public class Order implements Snapshot, Comparable<Order> {

    // Core fields
    public Nation owner;
    public UnitType unitType;
    public OrderType orderType;
    public Province pos0, pos1, pos2;

    // Metadata fields
    // REMEMBER to update `Order.wipeMetaInf()` when adding new metadata flags
    public boolean resolved;
    public boolean verdict;

    public boolean visited;

    /** 'SNAPSHOT' aka "Original order" field, used if Order is changed during adjudication...<br>
     *      ... (for e.g. using Szykman rules)<br><br>
     *  Will <i>CLONE</i> if .setOriginalOrder() is used
     */
    private Order originalOrder = null;


    public Order(Nation owner, UnitType unitType, Province origin, OrderType orderType, Province pos1, Province pos2) {
        this.owner = owner;
        this.unitType = unitType;
        this.orderType = orderType;
        this.pos0 = origin;
        this.pos1 = pos1;
        this.pos2 = pos2;
    }

    public Order(Nation owner, UnitType unitType, Province origin, OrderType orderType, Province pos1) {
        this(owner, unitType, origin, orderType, pos1, null);
    }

    public Order(Nation owner, UnitType unitType, Province origin, OrderType orderType) {
        this(owner, unitType, origin, orderType, null, null);
    }

    public Order(Order order2) {
        this(
                order2.owner,
                order2.unitType,
                order2.pos0,
                order2.orderType,
                order2.pos1,
                order2.pos2
        );

        this.resolved = order2.resolved;
        this.verdict = order2.verdict;
        this.visited = order2.visited;

        /*
         * Snapshots should be copied rather than shared. takeSnapshot() ensures
         * a snapshot does not itself have another snapshot, so this remains shallow.
         */
        this.originalOrder = (order2.originalOrder == null)
                ? null
                : new Order(order2.originalOrder);
    }


    @Override
    public Order getSnapshot() {
        return this.originalOrder;
    }

    @Override
    public void takeSnapshot() {
        this.originalOrder = new Order(this);  // CLONE constructor
        getSnapshot().originalOrder = null;  // avoid infinite reference loop
    }

    @Override
    public void restoreFromSnapshot() {

        this.owner = getSnapshot().owner;
        this.unitType = getSnapshot().unitType;
        this.orderType = getSnapshot().orderType;
        this.pos0 = getSnapshot().pos0;
        this.pos1 = getSnapshot().pos1;
        this.pos2 = getSnapshot().pos2;

        // un-set snapshot
        this.originalOrder = null;

    }


    /**
     * Wipes all 'metadata' (adjudication-related) fields: e.g. `resolved`, `verdict`,<br>
     * but not the 'state' fields: e.g. `pos0`
     */
    public void wipeMetaInf() {
        // DOES NOT WIPE SNAPSHOT INFO!!
        this.resolved = false;
        this.verdict = false;
        this.visited = false;
        // DOES NOT WIPE SNAPSHOT INFO!!
    }


    /**
     * Generates & returns a String representation of this Order's unit components (i.e. "no orders")
     * @return String representation of this Order's unit components
     */
    public String unitToString() {
        String output = owner.getPrefix() + " ";
        output += unitType.toString().charAt(0) + " ";
        output += pos0.toString();
        return output;
    }

    /**
     * Formats & returns a String representation of this Order's metadata fields
     * @return String representation of this Order's metadata fields
     */
    public String metaToString() {
        return String.format("%s:%b\t%s:%b", "resolved", resolved, "verdict", verdict);
    }


    /**
     * <b>Overridden</b> `toString()` method; generates & returns a String representation of this Order in
     * "<a href="https://www.backstabbr.com/"><i>Backstabbr</a> notation</i>".
     *
     * @return Implicit String representation of this Order in Backstabbr notation
     */
    @Override
    public String toString() {

        String output = this.unitToString();

        if (orderType == OrderType.MOVE) {
            output += " - " + pos1.toString();  // .getName() would return the PROVINCE's full name
        } else if (orderType == OrderType.HOLD) {
            output += " H";
        } else if (orderType == OrderType.SUPPORT) {
            output += " S " + pos1.toString() + " ";
            if (pos2 == null)
                output += "H";
            else
                output += "- " + pos2.toString();
        } else if (orderType == OrderType.CONVOY) {
            output += " C " + pos1.toString() + " - " + pos2.toString();
        } else if (orderType == OrderType.RETREAT) {
            if (pos1 == null)
                output += " PIFF";
            else
                output += " R " + pos1.toString();
        } else if (orderType == OrderType.BUILD) {
            output += " BUILD";
        } else if (orderType == OrderType.DESTROY) {
            output += " DESTROY";
        }

        return output;

    }

    /**
     * <b>Overridden</b> `equals()` method; compares Object equality with another given Object<br><br>
     *
     * Will return the equality of the core Order fields, while ignoring all metadata fields.
     *
     * @param other   the reference object with which to compare.
     * @return Object equality of this and `other`
     */
    @Override
    public boolean equals(Object other) {

        if (this == other)
            return true;

        if (!(other instanceof Order order2))
            return false;

        /*
         * Resolver metadata is intentionally ignored. An Order's identity is its
         * underlying submitted order, not its current adjudication result.
         */
        return this.owner == order2.owner
                && this.unitType == order2.unitType
                && this.orderType == order2.orderType
                && this.pos0 == order2.pos0
                && this.pos1 == order2.pos1
                && this.pos2 == order2.pos2;

    }

    /**
     * <b>Overridden</b> `hashCode()` method: Returns a hash code value for this object.<br><br>
     *
     * This method is supported for the benefit of hash tables such as those provided by `java.util.HashMap`.<br><br>
     *
     * @return Hash code of this Order's principal fields, NOT (e.g.) `resolved` & `verdict`
     */
    @Override
    public int hashCode() {

        /*
         * This must use exactly the fields used by equals().
         *
         * Do not include resolved, verdict, visited, snapshots, or other mutable
         * adjudication state. Including mutable fields makes an Order unsafe in
         * HashSet and HashMap collections.
         */
        return Objects.hash(
                this.owner,
                this.unitType,
                this.orderType,
                this.pos0,
                this.pos1,
                this.pos2
        );

    }

    /**
     * This class' implementation of `Comparable.compareTo()`<br><br>
     *
     * 'Compares' `this` Order to another Order using an `OrderComparator`,<br>
     * for the purposes of "petty sorting" (not adj-related)
     *
     * @param other other Order to compare with
     * @return Positive if this Order is 'greater', negative if other Order is 'greater', 0 if equal
     */
    @Override
    public int compareTo(Order other) {
        return (new OrderComparator()).compare(this, other);
    }

}
