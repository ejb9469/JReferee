package contracts;

import game.BoardState;

public interface OrderTranslator<I extends OrderForm, O extends OrderForm> {

    O translate(I source, BoardState board);

}
