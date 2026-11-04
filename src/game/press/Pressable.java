package game.press;

import domain.Nation;

import java.util.List;

@Deprecated
public interface Pressable {

    PressMessage            recordPress(PressMessage message);
    List<PressMessage>      pressHistory();
    List<PressMessage>      pressVisibleTo(Nation nation);

}
