package jobs;

import io.json.JsonAccess;
import io.json.JsonReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

/**
 * Parsed metadata and receipts use the same bytes read by the bounded caller.
 */
record ReceiptDocument(Path path, byte[] bytes) {

    private static final JsonAccess JSON = new JsonAccess(
            (location, message) -> new IllegalArgumentException(location + ": " + message));

    String text() throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    Map<String, Object> object() throws IOException {
        return JSON.object(JsonReader.read(text()), path.toString());
    }

    List<Map<String, Object>> rows() throws IOException {
        String text = text();
        if (!text.endsWith("\n"))
            throw new IOException("Truncated game index");
        return text.lines().map(line -> JSON.object(JsonReader.read(line), path.toString())).toList();
    }

    String hash() {
        return hash(bytes);
    }

    FileVerificationResult receipt() {
        return new FileVerificationResult(path, bytes.length, hash());
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

}