package io;


/**
 * Imports one game request into a game catalog.
 *
 * <p>Implementations may download a remote source, parse a local archive, or
 * translate another persisted representation before storing the result.</p>
 *
 * @param <I> source-specific import request
 * @param <R> source-specific import result
 */
@FunctionalInterface
public interface GameImporter<I, R> {


    /**
     * Imports one requested game.
     *
     * @param request source-specific game request
     * @return source-specific import result
     * @throws Exception when the source cannot be read, parsed, or stored
     */
    R importGame(I request)
            throws Exception;

}