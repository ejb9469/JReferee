package analysis.tactics;

import java.util.List;


/**
 * Interface of structural tactical-pattern detectors.
 *
 * <p>A detector recognizes submitted or proposed movement relationships.
 * It does not adjudicate orders or assign strategic value.</p>
 *
 * <p>Implementations must preserve unknown orders, retain the supplied
 * context, and return immutable, deterministic, duplicate-free findings.
 * Category contracts determine when incomplete findings are appropriate.</p>
 *
 * <p>An empty result does not establish strategic safety or absence of
 * categories that were not investigated.</p>
 */
public interface TacticDetector
        extends TacticInvestigator<TacticalContext, TacticMatch> {


    // Detector identity \\

    @Override
    TacticKind kind();

    @Override
    String version();


    // Existing detection API \\

    List<TacticMatch> detect(TacticalContext context);


    // Shared investigation API \\

    @Override
    default List<TacticMatch> investigate(TacticalContext context) {
        return detect(context);
    }


}