package parsing.diplobn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Small dependency-free JSON reader.
 *
 * <p>Objects retain their source member order through {@link LinkedHashMap};
 * arrays retain their source order through {@link ArrayList}.</p>
 */
final class JsonReader {


    private final String source;
    private int index;


    private JsonReader(String source) {
        this.source = source;
        this.index = 0;
    }


    public static Object read(String source) {

        if (source == null)
            throw new NullPointerException("source");

        JsonReader reader = new JsonReader(source);

        Object value = reader.readValue();

        reader.skipWhitespace();

        if (!reader.atEnd())
            throw reader.error("Unexpected trailing content");

        return value;

    }


    private Object readValue() {

        skipWhitespace();

        if (atEnd())
            throw error("Expected a JSON value");

        return switch (current()) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if (current() == '-' || isDigit(current()))
                    yield readNumber();

                throw error("Expected a JSON value");
            }
        };

    }

    private Map<String, Object> readObject() {

        expect('{');

        Map<String, Object> object = new LinkedHashMap<>();

        skipWhitespace();

        if (consume('}'))
            return object;

        while (true) {

            skipWhitespace();

            if (atEnd() || current() != '"')
                throw error("Expected an object member name");

            String key = readString();

            skipWhitespace();
            expect(':');

            Object value = readValue();

            if (object.putIfAbsent(key, value) != null)
                throw error("Duplicate object member: " + key);

            skipWhitespace();

            if (consume('}'))
                return object;

            expect(',');

        }

    }

    private List<Object> readArray() {

        expect('[');

        List<Object> array = new ArrayList<>();

        skipWhitespace();

        if (consume(']'))
            return array;

        while (true) {

            array.add(readValue());

            skipWhitespace();

            if (consume(']'))
                return array;

            expect(',');

        }

    }

    private String readString() {

        expect('"');

        StringBuilder output = new StringBuilder();

        while (!atEnd()) {

            char character = current();
            index++;

            if (character == '"')
                return output.toString();

            if (character < 0x20)
                throw error("Control character in JSON string");

            if (character != '\\') {
                output.append(character);
                continue;
            }

            if (atEnd())
                throw error("Unterminated JSON escape");

            char escaped = current();
            index++;

            switch (escaped) {
                case '"' -> output.append('"');
                case '\\' -> output.append('\\');
                case '/' -> output.append('/');
                case 'b' -> output.append('\b');
                case 'f' -> output.append('\f');
                case 'n' -> output.append('\n');
                case 'r' -> output.append('\r');
                case 't' -> output.append('\t');
                case 'u' -> output.append(readUnicodeEscape());
                default -> throw error("Invalid JSON escape: \\" + escaped);
            }

        }

        throw error("Unterminated JSON string");

    }

    private char readUnicodeEscape() {

        if (index + 4 > source.length())
            throw error("Incomplete Unicode escape");

        String digits = source.substring(index, index + 4);

        for (int i = 0; i < digits.length(); i++) {
            if (Character.digit(digits.charAt(i), 16) < 0)
                throw error("Invalid Unicode escape: \\u" + digits);
        }

        index += 4;

        return (char) Integer.parseInt(digits, 16);

    }

    private Object readLiteral(String literal, Object value) {

        if (!source.startsWith(literal, index))
            throw error("Expected " + literal);

        index += literal.length();

        return value;

    }

    private Number readNumber() {

        int start = index;

        consume('-');

        if (atEnd())
            throw error("Incomplete JSON number");

        if (consume('0')) {
            // Zero cannot be followed by another integer digit.
            if (!atEnd() && isDigit(current()))
                throw error("Invalid JSON number");
        } else {
            readDigits();
        }

        boolean decimal = false;

        if (consume('.')) {
            decimal = true;
            readDigits();
        }

        if (consume('e') || consume('E')) {
            decimal = true;

            consume('+');
            consume('-');

            readDigits();
        }

        String token = source.substring(start, index);

        try {
            if (decimal)
                return Double.parseDouble(token);

            return Long.parseLong(token);
        } catch (NumberFormatException exception) {
            throw error("Invalid JSON number: " + token);
        }

    }

    private void readDigits() {

        if (atEnd() || !isDigit(current()))
            throw error("Expected a digit");

        while (!atEnd() && isDigit(current()))
            index++;

    }

    private boolean consume(char expected) {

        if (atEnd() || current() != expected)
            return false;

        index++;
        return true;

    }

    private void expect(char expected) {

        skipWhitespace();

        if (!consume(expected))
            throw error("Expected '" + expected + "'");

    }

    private void skipWhitespace() {
        while (!atEnd() && Character.isWhitespace(current()))
            index++;
    }

    private boolean atEnd() {
        return index >= source.length();
    }

    private char current() {
        return source.charAt(index);
    }

    private ParseException error(String message) {
        return new ParseException(
                "JSON character " + index + ": " + message);
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

}