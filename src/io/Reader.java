package io;

import java.nio.file.Path;
import java.util.List;


/**
 * Reads catalog import requests without performing remote imports.
 *
 * @param <E> one source-specific manifest-entry type
 */
// somewhat useless atm [ @ 10-26-26 ], but could have future use so we'll keep it
public interface Reader<E> {


    /**
     * Reads and validates all entries in the configured manifest.
     *
     * @return immutable manifest entries
     */
    List<E> read();

    /**
     * Source manifest location.
     *
     * @return manifest path
     */
    Path path();

}