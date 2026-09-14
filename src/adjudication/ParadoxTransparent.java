package adjudication;

import java.util.List;

/**
 * Exposes paradox diagnostics from an `Adjudicator` run
 */
public interface ParadoxTransparent {

    List<ParadoxCycle> getParadoxCycles();

}
