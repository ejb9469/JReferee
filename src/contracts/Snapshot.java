package contracts;

/**
 * In-place rollback contract.
 */
public interface Snapshot<S> {

    S               getSnapshot();
    void            takeSnapshot();
    void            restoreFromSnapshot();

}