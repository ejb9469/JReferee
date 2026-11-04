package game;

import java.util.Objects;

public sealed interface GameState
        permits GameState_Movement,
                GameState_Retreats,
                GameState_Adjustments {


    Moment      moment();

    BoardState  board();


}