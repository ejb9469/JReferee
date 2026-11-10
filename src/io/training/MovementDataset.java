package io.training;

import agents.encoding.MovementInput;
import agents.encoding.EncodingPurpose;

import java.io.IOException;
import java.util.*;

/**
 * Reopenable movement examples, independent of their storage format.
 * Each cursor belongs to one caller and one split.
 */
public interface MovementDataset {

    record Example(String source, String gameKey, String communicationType,
                   MovementInput input, List<Integer> action) {
        public Example {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(gameKey, "gameKey");
            Objects.requireNonNull(input, "input");
            action = List.copyOf(action);
            input.requireAction(action);
            if (action.isEmpty())
                throw new IllegalArgumentException("A training example needs at least one unit");
        }
    }

    // TODO: STRONG CANDIDATE to move to independent record class, perhaps in `performance` (with flavored name)
    /** sha256 is available after verified EOF for byte-backed readers. */
    record Statistics(long examples, long options, long characters, long readNanos,
                      long cacheHits, long cacheMisses, int peakCachedOptions, String sha256) { }

    interface Cursor extends AutoCloseable {
        /**
         * Returns null at verified end-of-file. Invalid records throw.
         * Closing before EOF does not verify the unread remainder.
         */
                    Example next()              throws IOException;
                    Statistics statistics();
        @Override   void close()                throws IOException;
    }

    Cursor open(EncodingPurpose split) throws IOException;

    long examples(EncodingPurpose split);
    /** Identifies the dataset manifest and game index. */

    String fingerprint();

}