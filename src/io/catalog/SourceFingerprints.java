package io.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;


/**
 * Cryptographic fingerprints for persisted source payloads.
 */
public final class SourceFingerprints {


    private SourceFingerprints() {  }


    public static String sha256Hex(String source) {

        Objects.requireNonNull(source, "source");

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }

    }


}
