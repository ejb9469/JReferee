package parsing;


/**
 * Indicates malformed JSON or a value that does not conform to the DiploBN
 * game JSON schema supported by {@link parsing.diplobn.DiploBNParser}.
 */
public final class ParseException extends IllegalArgumentException {


    public ParseException(String message) {
        super(message);
    }

    public ParseException(String path, String message) {
        super(path + ": " + message);
    }

}