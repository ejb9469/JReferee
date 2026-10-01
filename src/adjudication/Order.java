package adjudication;

import contracts.OrderForm;
import contracts.Snapshot;
import adjudication.util.OrderComparator;
import domain.Nation;
import domain.OrderType;
import domain.Province;
import domain.UnitType;

import java.util.Objects;


/**
 * Mutable adjudication work order, including resolution metadata.
 */
public class Order
        implements OrderForm, Snapshot, Comparable<Order> {

    // Core fields
    public Nation owner;
    public UnitType unitType;
    public OrderType orderType;
    public Province pos0, pos1, pos2;

    // Metadata fields
    public boolean resolved;
    public boolean verdict;
    public boolean visited;

    private Order originalOrder = null;


    public Order(Nation owner, UnitType unitType, Province origin,
                 OrderType orderType, Province pos1, Province pos2) {
        this.owner = owner;
        this.unitType = unitType;
        this.orderType = orderType;
        this.pos0 = origin;
        this.pos1 = pos1;
        this.pos2 = pos2;
    }

    public Order(Nation owner, UnitType unitType, Province origin,
                 OrderType orderType, Province pos1) {
        this(owner, unitType, origin, orderType, pos1, null);
    }

    public Order(Nation owner, UnitType unitType, Province origin, OrderType orderType) {
        this(owner, unitType, origin, orderType, null, null);
    }

    public Order(OrderForm source, Province issuingProvince) {
        this(
                Objects.requireNonNull(source, "source").owner(),
                source.unitType(),
                issuingProvince,
                source.orderType(),
                source.target(),
                source.auxiliaryTarget()
        );
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

        // Copy snapshots rather than sharing mutable state.
        this.originalOrder = order2.originalOrder == null
                ? null : new Order(order2.originalOrder);
    }


    @Override
    public Nation owner() {
        return this.owner;
    }

    @Override
    public UnitType unitType() {
        return this.unitType;
    }

    @Override
    public OrderType orderType() {
        return this.orderType;
    }

    @Override
    public Province origin() {
        return this.pos0;
    }

    @Override
    public Province target() {
        return this.pos1;
    }

    @Override
    public Province auxiliaryTarget() {
        return this.pos2;
    }


    // Snapshots and metadata \\

    @Override
    public Order getSnapshot() {
        return this.originalOrder;
    }

    @Override
    public void takeSnapshot() {
        this.originalOrder = new Order(this);
        getSnapshot().originalOrder = null;
    }

    @Override
    public void restoreFromSnapshot() {

        this.owner = getSnapshot().owner;
        this.unitType = getSnapshot().unitType;
        this.orderType = getSnapshot().orderType;
        this.pos0 = getSnapshot().pos0;
        this.pos1 = getSnapshot().pos1;
        this.pos2 = getSnapshot().pos2;

        this.originalOrder = null;

    }

    public void wipeMetaInf() {
        // Snapshots are intentionally retained.
        this.resolved = false;
        this.verdict = false;
        this.visited = false;
    }


    // Presentation \\

    public String unitToString() {
        return OrderForm.unitText(this, pos0);
    }

    public String metaToString() {
        return String.format("%s:%b\t%s:%b", "resolved", resolved, "verdict", verdict);
    }

    @Override
    public String toString() {
        return OrderForm.format(this, pos0);
    }


    // Identity excludes adjudication metadata. \\

    @Override
    public boolean equals(Object other) {

        if (this == other)
            return true;

        if (!(other instanceof Order order2))
            return false;

        return this.owner == order2.owner
                && this.unitType == order2.unitType
                && this.orderType == order2.orderType
                && this.pos0 == order2.pos0
                && this.pos1 == order2.pos1
                && this.pos2 == order2.pos2;

    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.owner,
                this.unitType,
                this.orderType,
                this.pos0,
                this.pos1,
                this.pos2
        );
    }

    @Override
    public int compareTo(Order other) {
        return (new OrderComparator()).compare(this, other);
    }

}