package parsing.diplobn;


/**
 * Indicates malformed JSON or a value that does not conform to the DiploBN
 * game JSON schema supported by {@link DiploBNParser}.
 */
public final class DiploBNParseException extends IllegalArgumentException {


    public DiploBNParseException(String message) {
        super(message);
    }

    public DiploBNParseException(String path, String message) {
        super(path + ": " + message);
    }

}