package game;


public sealed interface GameState
        permits GameState_Movement,
                GameState_Retreats,
                GameState_Adjustments {


    GameMoment  moment();

    BoardState  board();


}