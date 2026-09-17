package testing;

import phase.retreats.RetreatInput;
import phase.retreats.RetreatProcessor;
import phase.retreats.RetreatResult;

/**
 * Base class for DATC section-H retreat test cases.
 */
public abstract class RetreatProcessorTestCase
                        extends ProcessorTestCase<RetreatInput, RetreatResult> {

    protected RetreatProcessorTestCase(String name, RetreatInput input) {
        super(name, new RetreatProcessor(), input);
    }

}