package contracts;

/**
 * In-place rollback contract.
 */
public interface Snapshot {

    Snapshot        getSnapshot();
    void            takeSnapshot();
    void            restoreFromSnapshot();

}