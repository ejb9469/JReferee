package ui;

import game.BoardState;

/**
 * Read-only source of a board snapshot.
 */
public interface SnapshotSource {

    int year();

    String phaseName();

    BoardState board();

}
