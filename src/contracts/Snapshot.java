package contracts;

import org.jetbrains.annotations.Nullable;

/**
 * In-place rollback contract.
 */
public interface Snapshot<S> {

    @Nullable  // depends on implementation
    S               getSnapshot();

    void            takeSnapshot();

    void            restoreFromSnapshot();

}