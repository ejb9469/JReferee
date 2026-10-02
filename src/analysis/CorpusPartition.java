package analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


public enum CorpusPartition {

    TRAINING,
    VALIDATION,
    TEST;


    /**
     * Keep this version fixed throughout an experiment.
     * Identity comes from the provider, not an imported archive UUID.
     */
    public static final String SPLIT_VERSION = "diplobn-route-corpus-split-v1";


    public static CorpusPartition forSourceId(long sourceGameId) {

        if (sourceGameId < 1)
            throw new IllegalArgumentException("DiploBN GameID must be positive");

        byte[] digest;

        try {

            String key = SPLIT_VERSION + "\nDIPLOBN\n" + sourceGameId;

            digest = MessageDigest.getInstance("SHA-256").digest(
                    key.getBytes(StandardCharsets.UTF_8));

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }

        long prefix = 0;

        for (int index = 0; index < 4; index++)
            prefix = (prefix << 8) | (digest[index] & 0xffL);

        int bucket = (int) (prefix % 100);

        if (bucket < 80)
            return TRAINING;

        if (bucket < 90)
            return VALIDATION;

        return TEST;

    }

}