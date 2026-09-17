package testing;

import phase.adjustments.AdjustmentInput;
import phase.adjustments.AdjustmentProcessor;
import phase.adjustments.AdjustmentResult;

/**
 * Base class for DATC section-I and section-J adjustment test cases.
 */
public abstract class AdjustmentProcessorTestCase
                        extends ProcessorTestCase<AdjustmentInput, AdjustmentResult> {

    protected AdjustmentProcessorTestCase(String name, AdjustmentInput input) {
        super(name, new AdjustmentProcessor(), input);
    }

}