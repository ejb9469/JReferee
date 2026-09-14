package contracts;

public interface Snapshot {

    Snapshot getSnapshot();
    void takeSnapshot();
    void restoreFromSnapshot();

}