package testing;

/**
 * A runnable test case with score and reporting information.
 *
 * <p>Legacy adjudicator test cases and immutable phase-processor test cases
 * share this reporting contract, but intentionally do not share an order-list
 * API.</p>
 */
public interface TestCase {

    void                eval(boolean print);

    default void        eval() {
        eval(false);
    }

    String              getName();

    String              getEval();

    int                 getScore();

    int                 getSize();

    default boolean     passed() {
        return getScore() == getSize();
    }

    void                printEval();

    void                printNameAndScore();

}