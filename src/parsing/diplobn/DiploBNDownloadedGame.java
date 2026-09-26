package parsing.diplobn;

import java.util.Objects;


/**
 * Canonical DiploBN source data downloaded for one GameID, together with its
 * parsed JReferee-compatible representation.
 */
public record DiploBNDownloadedGame(
        long gameId,
        String canonicalUrl,
        String sourceJson,
        DiploBNGame game
) {

    public DiploBNDownloadedGame {

        if (gameId <= 0)
            throw new IllegalArgumentException("gameId must be positive");

        Objects.requireNonNull(canonicalUrl, "canonicalUrl");

        Objects.requireNonNull(sourceJson, "sourceJson");

        Objects.requireNonNull(game, "game");

    }

}