package adjudication;

import contracts.HomogeneousState;
import domain.Nation;
import domain.Order;
import domain.OrderType;

import java.util.Collection;
import java.util.LinkedHashMap;

public class WinterJudge extends Judge implements HomogeneousState {


    public WinterJudge() {
        super();
        this.buildsAvailabilityMap = new LinkedHashMap<>();
    }

    public WinterJudge(Collection<Order> orders) {
        super(orders);
        this.buildsAvailabilityMap = new LinkedHashMap<>();
    }

    public WinterJudge(Collection<Order> orders, LinkedHashMap<Nation, Integer> buildsAvailabilityMap) {
        super(orders);
        this.buildsAvailabilityMap = buildsAvailabilityMap;
    }

    public WinterJudge(Collection<Order> orders, Nation... nations) {
        super(orders);
        this.buildsAvailabilityMap = new LinkedHashMap<>();
        populateBuildsMap(nations);
    }

    public WinterJudge(Collection<Order> orders, Nation[] nations, int[] builds) {
        super(orders);
        this.buildsAvailabilityMap = new LinkedHashMap<>();
        for (int i = 0; i < nations.length; i++)
            buildsAvailabilityMap.put(nations[i], builds[i]);
    }


    protected LinkedHashMap<Nation, Integer> buildsAvailabilityMap;


    public LinkedHashMap<Nation, Integer> getBuildsAvailabilityMap() {
        return buildsAvailabilityMap;
    }


    @Override
    protected boolean adjudicate(Order order, boolean optimistic) {

        enforceStasis();

        if (order.orderType == OrderType.BUILD) {

            if (order.pos0 == null)
                return (waiveBuildsFor(order.owner) >= 0);  // Waiving is fine, if going at least +0
            else if (order.owner == order.pos0.owner) {  // This implicitly checks for Home SC Ownership
                if (decrementBuildsFor(order.owner) >= 0)
                    return true;
                else {
                    incrementBuildsFor(order.owner);  // Undo the decrement
                    return false;
                }
            }

        }

        else if (order.orderType == OrderType.DESTROY) {

            if (order.pos0 == null)
                return (waiveBuildsFor(order.owner) == 0);  // Zero destroys is only possible with +0 in the bank

            if (incrementBuildsFor(order.owner) <= 0)
                return true;
            else {
                decrementBuildsFor(order.owner);  // Undo the increment
                return false;
            }

        }

        // Since `WinterJudge` enforces homogeneity (only Builds & Destroys) (ATM), ...
        // ... calling `Judge::adjudicate(...)` will likely proceed to its IllegalStateEx throw (again, ATM)
        // It's better to call Judge's method as a failsafe than to duplicate the code here
        return super.adjudicate(order, optimistic);

    }


    @Override
    public void enforceStasis() throws IllegalStateException {

        for (Order order : orders) {
            if (order.orderType != OrderType.BUILD && order.orderType != OrderType.DESTROY)
                throw new IllegalStateException(String.format(
                        "%s\n`%s:enforceStasis()`: OrderType is %s but only %s are allowed",
                        order.toString(),
                        this.getClass().getSimpleName(), order.orderType,
                        OrderType.BUILD.name() + " and " + OrderType.DESTROY.name())
                );
        }

    }


    protected void populateBuildsMap(Nation... nations) {

        for (Nation nation : nations)
            buildsAvailabilityMap.put(nation, 0);

    }

    protected void populateBuildsMap(int... builds) {

        if (builds.length > buildsAvailabilityMap.size())
            throw new IndexOutOfBoundsException();

        LinkedHashMap<Nation, Integer> mapClone = new LinkedHashMap<>(buildsAvailabilityMap);

        for (int i = 0; i < mapClone.size(); i++) {
            buildsAvailabilityMap.replace(mapClone.firstEntry().getKey(), builds[i]);
            mapClone.remove(mapClone.firstEntry().getKey());
        }

    }


    private int decrementBuildsFor(Nation nation) {

        int numBuilds = buildsAvailabilityMap.getOrDefault(nation, 1);
        buildsAvailabilityMap.replace(nation, --numBuilds);
        return numBuilds;

    }

    private int incrementBuildsFor(Nation nation) {

        int numBuilds = buildsAvailabilityMap.getOrDefault(nation, -1);
        buildsAvailabilityMap.replace(nation, ++numBuilds);
        return numBuilds;

    }

    private int waiveBuildsFor(Nation nation) {

        return buildsAvailabilityMap.getOrDefault(nation, 0);

    }


}
