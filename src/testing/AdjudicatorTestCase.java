package testing;

import adjudication.Judge;
import adjudication.Order;
import adjudication.util.Constants;
import adjudication.util.Orders;

import java.util.*;

public class AdjudicatorTestCase implements TestCase {

    public static final String  TESTCASE_PREFIX = "TEST CASE - ";

    protected final List<Order>   orders;
    protected final List<Order>   originalOrders;
    protected String              name;

    protected List<boolean[]>     expectedFields = null;
    protected List<boolean[]>     actualFields = null;
    protected int                 score = 0;
    protected String              eval = null;


    public AdjudicatorTestCase(String name, Order... orders) {
        this.name = name;
        List<Order> ordersList = Arrays.asList(orders);
        //Collections.shuffle(ordersList);  // Cannot shuffle here, must be done layer(s) up
        this.orders = new ArrayList<>(ordersList);
        this.actualFields = new ArrayList<>(this.orders.size());
        this.score = this.orders.size();
        this.originalOrders = Orders.deepCopy(this.orders);
    }

    public AdjudicatorTestCase(String name, List<Order> orders) {
        this.name = name;
        this.orders = new ArrayList<>(orders);
        this.actualFields = new ArrayList<>(this.orders.size());
        this.score = this.orders.size();
        this.originalOrders = Orders.deepCopy(this.orders);
    }

    public AdjudicatorTestCase(AdjudicatorTestCase testCase) {
        this.orders = Orders.deepCopy(testCase.orders);
        this.name = testCase.name;
        this.expectedFields = new ArrayList<>();
        this.expectedFields.addAll(testCase.expectedFields);
        this.actualFields = new ArrayList<>();
        this.actualFields.addAll(testCase.actualFields);
        this.score = testCase.score;
        this.eval = testCase.eval;
        this.originalOrders = Orders.deepCopy(testCase.originalOrders);
    }


    protected void judge() {

        Judge judge;
        if (!orders.isEmpty())
            judge = new Judge(new ArrayList<>(orders));
        else
            judge = new Judge();

        judge.judge();

        for (Order order : judge.getOrders())
            actualFields.add(new boolean[]{order.verdict});  // Could expand with more fields later

    }


    @Override
    public void eval(boolean print) {

        // without these 3 lines, `this.eval(...)`
        // is not safe to run twice!!
        this.actualFields.clear();
        this.score = this.orders.size();
        this.eval = null;

        String name = TESTCASE_PREFIX+this.name;
        StringBuilder output = new StringBuilder(name+":\n\n");

        if (expectedFields == null) {  // Debug output -- no expected fields

            System.out.println("NO EXPECTED FIELDS for...\t\t[" + name + "]");
            for (Order order : orders)
                output.append(String.format("%s\n\t%s\n", order.toString(), order.metaToString()));

        } else {

            if (expectedFields.size() != orders.size())
                throw new IndexOutOfBoundsException("`expectedFields.size()` != `orders.size()`");

            this.judge();

            for (int i = 0; i < orders.size(); i++) {
                if (!Arrays.equals(expectedFields.get(i), actualFields.get(i)))
                    score--;
                output.append(String.format("%s\n\t%s\n\t%s:%s\n\t%s:%s\n",
                        orders.get(i).toString(), orders.get(i).metaToString(),
                        "EXPECTED", Arrays.toString(expectedFields.get(i)),
                        "ACTUAL", Arrays.toString(actualFields.get(i))));
            }

            output.append(String.format("\nSCORE: %d/%d", score, orders.size()));

            this.eval = output.toString();
            if (print)
                this.printEval();

        }

    }


    public void shuffle() {

        if (orders.isEmpty() || expectedFields.isEmpty())
            return;

        List<Integer> indexes = new ArrayList<>();
        Collections.addAll(indexes, Constants.range(orders.size()));
        Collections.shuffle(indexes);

        List<Order> orders_New              = new ArrayList<>(orders.size());
        List<boolean[]> expectedFields_New  = new ArrayList<>();
        List<boolean[]> actualFields_New    = new ArrayList<>();

        for (int index : indexes) {
            orders_New.add(orders.get(index));
            expectedFields_New.add(expectedFields.get(index));
            if (!actualFields.isEmpty())
                actualFields_New.add(actualFields.get(index));
        }

        this.originalOrders.clear();
        this.originalOrders.addAll(this.orders);

        this.orders.clear();
        this.orders.addAll(orders_New);
        this.expectedFields = expectedFields_New;
        this.actualFields = actualFields_New;

    }

    /*public void unshuffle() {

        if (originalOrders.isEmpty() || orders.isEmpty() || expectedFields.isEmpty())
            return;

        List<Order>     orders_New          = new ArrayList<>(orders.size());
        List<boolean[]> expectedFields_New  = new ArrayList<>();
        List<boolean[]> actualFields_New    = new ArrayList<>();

        for (Order order : orders) {
            for (int i = 0; i < originalOrders.size(); i++) {
                if (order.pos0 == originalOrders.get(i).pos0) {
                    orders_New.add(order);
                    expectedFields_New.add(expectedFields.get(i));
                    if (!actualFields.isEmpty())
                        actualFields_New.add(actualFields.get(i));
                    break;
                }
            }
        }

        this.orders.clear();
        this.orders.addAll(orders_New);
        this.expectedFields = expectedFields_New;
        this.actualFields = actualFields_New;

    }*/


    public void setExpectedFields(boolean[]... fields) {

        if (fields.length != orders.size())
            throw new IndexOutOfBoundsException("`len(fields)` != `orders.size()`");

        expectedFields = new ArrayList<>();
        expectedFields.addAll(Arrays.asList(fields));

    }

    public void setExpectedFields(boolean... fields) {

        if (fields.length != orders.size())
            throw new IndexOutOfBoundsException("`len(fields)` != `orders.size()`");

        expectedFields = new ArrayList<>();
        for (boolean field : fields)
            expectedFields.add(new boolean[]{field});

    }

    public void setName(String name) {
        this.name = name;
    }


    @Override
    public String getName() {
        return name;
    }

    public List<Order> getOrders() {
        return orders;
    }

    public List<Order> getOriginalOrders() {
        return originalOrders;
    }

    @Override
    public String getEval() {
        return eval;
    }

    @Override
    public int getScore() {
        return score;
    }

    @Override
    public int getSize() {
        return orders.size();
    }

    public List<boolean[]> getExpectedFields() {
        return expectedFields;
    }

    public List<boolean[]> getActualFields() {
        return actualFields;
    }

    public List<boolean[]> getExpectedFieldsUnshuffled(List<Order> ordersList) {

        List<boolean[]> fieldsNew = new ArrayList<>();

        for (Order order : ordersList) {
            for (int j = 0; j < orders.size(); j++) {
                if (orders.get(j).equals(order))
                    fieldsNew.add(expectedFields.get(j));
            }
        }

        return fieldsNew;

    }

    public List<boolean[]> getActualFieldsUnshuffled(List<Order> ordersList) {

        List<boolean[]> fieldsNew = new ArrayList<>();

        for (Order order : ordersList) {
            for (int j = 0; j < orders.size(); j++) {
                if (orders.get(j).equals(order))
                    fieldsNew.add(actualFields.get(j));
            }
        }

        return fieldsNew;

    }



    @Override
    public void printEval() {
        System.out.println(eval+"\n\n");
    }

    @Override
    public void printNameAndScore() {
        System.out.printf("[%02d/%02d]\t%s%s\n", this.score, this.orders.size(), TESTCASE_PREFIX, this.getName());
    }


    @Override
    public String toString() {
        // added toString() for ease-of-use debugging purposes
        return name;
    }

}