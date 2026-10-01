package io.json;

import java.util.Objects;


/**
 * Writes quoted JSON strings.
 *
 * <p>Null is not a string. Callers writing nullable JSON values must emit the
 * JSON literal null separately.</p>
 */
public abstract class JsonEscaper {


    private JsonEscaper() {  }


    public static void appendString(StringBuilder out, String value) {

        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(value, "value");

        out.append('"');

        for (int index = 0; index < value.length(); index++) {

            char character = value.charAt(index);

            switch (character) {

                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");

                default -> {
                    if (character < 0x20) {
                        String hexadecimal = Integer.toHexString(character);
                        out.append("\\u");
                        out.append("0".repeat(4 - hexadecimal.length()));
                        out.append(hexadecimal);
                    } else {
                        out.append(character);
                    }
                }

            }

        }

        out.append('"');

    }


}