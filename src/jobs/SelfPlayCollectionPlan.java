package jobs;

import java.nio.file.Path;
import java.util.*;

/**
 * A collection configuration, not a worker request.
 * The template directory is the collection root;
 *  only batch(index) requests are submitted.
 */
public record SelfPlayCollectionPlan(
        JavaProcessRuntime runtime, SelfPlayRequest template, int batches, int parallelism
) {

    public SelfPlayCollectionPlan {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(template, "template");
        if (batches < 1 || batches > 1024 || parallelism < 1
                || parallelism > Math.min(batches, 256))
            throw new IllegalArgumentException("Use 1..1024 batches and 1..min(batches,256) parallelism");
        Math.addExact(template.firstSeed(), (long) batches * template.games() - 1);
    }

    public Path directory() {
        return template.directory();
    }

    public int games() {
        return batches * template.games();
    }

    public SelfPlayRequest batch(int index) {
        Objects.checkIndex(index, batches);
        return new SelfPlayRequest(template.value(), template.anchor(), template.policy(),
                template.lastYear(), template.candidates(), template.iterations(),
                Math.addExact(template.firstSeed(), (long) index * template.games()),
                template.games(), template.splitSeed(),
                directory().resolve(String.format(Locale.ROOT, "batch-%02d", index)));
    }

}