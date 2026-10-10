package analysis.tactics;

import java.util.List;


/**
 * Typed investigation of one tactical category.
 *
 * <p>C identifies the required evidence context; F identifies the finding
 * representation. Concrete families define their own evidence and
 * participant invariants.</p>
 *
 * <p>Implementations must reject null contexts, preserve caller-owned
 * evidence, return every qualifying occurrence, and return an immutable,
 * deterministic, duplicate-free list.</p>
 *
 * <p>Missing evidence must not be silently represented as a negative result.
 * A context must satisfy the investigator's documented prerequisites.</p>
 */
public interface TacticInvestigator<C, F> {


    // Identity \\

    TacticKind kind();

    String version();


    // Investigation \\

    List<F> investigate(C context);


}