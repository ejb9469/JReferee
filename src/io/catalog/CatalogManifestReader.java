package io.catalog;

import io.Reader;


/**
 * Reads catalog import requests without performing remote imports.
 *
 * @param <E> one source-specific manifest-entry type
 */
public interface CatalogManifestReader<E>
        extends Reader<E> {

}