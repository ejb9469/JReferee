package agents.generation;

import domain.Nation;
import game.GameState;

/**
 * Generates phase-legal order options for one nation.<br><br>
 *
 * <p>Movement and retreat catalogs contain an option group for every unit
 * orderable by the nation. A retreat unit may have no legal destination.</p>
 */
public interface LegalOrderGenerator {

    LegalOrderCatalog generate(GameState state, Nation power);

}