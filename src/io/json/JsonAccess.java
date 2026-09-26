package io.json;

import java.util.*;
import java.util.function.BiFunction;


/**
 * Typed access to values produced by a JSON reader.
 *
 * <p>The caller supplies its own exception factory, keeping this utility
 * independent of any particular document schema or parser package.</p>
 */
public final class JsonAccess {


    private final BiFunction<String, String, ? extends RuntimeException> errors;


    public JsonAccess(
            BiFunction<String, String, ? extends RuntimeException> errors
    ) {
        this.errors = Objects.requireNonNull(errors, "errors");
    }


    public Object required(Map<String, Object> object, String key, String path) {

        if (!object.containsKey(key))
            throw errors.apply(path, "Missing required property: " + key);

        return object.get(key);

    }

    public Map<String, Object> optionalObject(
            Map<String, Object> object,
            String key,
            String path
    ) {

        if (!object.containsKey(key) || object.get(key) == null)
            return null;

        return object(object.get(key), path + "." + key);

    }

    public String optionalString(Map<String, Object> object, String key, String path) {

        if (!object.containsKey(key) || object.get(key) == null)
            return null;

        return string(object.get(key), path + "." + key);

    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> object(Object value, String path) {

        if (!(value instanceof Map<?, ?>))
            throw errors.apply(path, "Expected object");

        return (Map<String, Object>) value;

    }

    @SuppressWarnings("unchecked")
    public List<Object> array(Object value, String path) {

        if (!(value instanceof List<?>))
            throw errors.apply(path, "Expected array");

        return (List<Object>) value;

    }

    public String string(Object value, String path) {

        if (!(value instanceof String text))
            throw errors.apply(path, "Expected string");

        return text;

    }

    public int integer(Object value, String path) {

        if (!(value instanceof Number number))
            throw errors.apply(path, "Expected integer");

        long converted = number.longValue();

        if (number.doubleValue() != converted)
            throw errors.apply(path, "Expected integer");

        if (converted < Integer.MIN_VALUE || converted > Integer.MAX_VALUE)
            throw errors.apply(path, "Integer is outside Java int range");

        return (int) converted;

    }

    public void requireSize(List<?> values, int expectedSize, String path) {

        if (values.size() != expectedSize)
            throw errors.apply(
                    path, "Expected " + expectedSize + " values but found " + values.size());

    }


}