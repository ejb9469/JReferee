package analysis.tactics;

import java.util.List;


/**
 * Interface of structural tactical-pattern detectors.<br><br>
 *
 * A detector recognizes submitted or proposed order relationships.
 * It does not adjudicate orders or assign strategic value.
 *
 * <p>Implementations must:</p>
 * <ul>
 *     <li>reject a null context;</li>
 *     <li>leave the supplied context unmodified;</li>
 *     <li>preserve unknown orders rather than inventing HOLDs;</li>
 *     <li>return all occurrences, including incomplete support relationships
 *         required by the category's detection contract;</li>
 *     <li>return an immutable list without duplicate occurrences;</li>
 *     <li>sort occurrences by focus, then participant roles and locations;</li>
 *     <li>attach the supplied context, kind(), and version() to every match.</li>
 * </ul>
 *
 * <p>An empty result does not establish that the position is strategically
 * safe or that no other tactical category applies.</p>
 */
public interface TacticDetector {


    // Detector identity \\

    TacticKind kind();

    /**
     * Identifies the structural matching semantics.
     * For example: "structure-v1".
     */
    String version();


    // Detection \\

    List<TacticMatch> detect(TacticalContext context);


}