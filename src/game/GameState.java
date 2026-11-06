package game;


import domain.Nation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public sealed interface GameState
        permits GameState_Movement,
                GameState_Retreats,
                GameState_Adjustments {


    GameMoment  moment();

    BoardState  board();

    /**
     * Returns the nations with units or owned supply centers, in nation order.
     *
     * <p>Units awaiting retreats still count. A nation with centers but no
     * units remains alive and may have building opportunities in winter.
     * The returned set is immutable.</p>
     */
    default Set<Nation> aliveNations() {

        Set<Nation> alive = EnumSet.noneOf(Nation.class);
        board().locations().keySet().forEach(unit -> alive.add(unit.owner()));
        alive.addAll(board().owners().values());

        // exception: may contain a retreating unit whose nation has no centers.
        //              --> this will never happen in a normal game, but it is good to be sure <--
        if (this instanceof GameState_Retreats retreats)
            retreats.movementResult().dislodgements().keySet()
                    .forEach(unit -> alive.add(unit.owner()));

        return Collections.unmodifiableSet(alive);

    }


}